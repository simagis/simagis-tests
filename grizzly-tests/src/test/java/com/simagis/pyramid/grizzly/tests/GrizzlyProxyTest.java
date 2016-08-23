package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.http.util.*;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.Charsets;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GrizzlyProxyTest {
    static {
        System.setProperty(
            org.glassfish.grizzly.http.util.Constants.class.getName() + ".default-character-encoding", "UTF-8");
        // - necessary to provide correct parsing GET and POST parameters, when encoding is not specified
        // (typical situation for POST, always for GET)
    }

    final String serverHost;
    final int serverPort;
    final int proxyPort;
    final HttpServer proxyServer;

    final TCPNIOTransport clientTransport;

    GrizzlyProxyTest(String serverHost, int serverPort, int proxyPort) throws IOException {
        System.out.printf("Starting proxy at port %d (server %s:%d)%n", proxyPort, serverHost, serverPort);
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.proxyPort = proxyPort;

        final TCPNIOTransportBuilder transportBuilder = TCPNIOTransportBuilder.newInstance();
        final ThreadPoolConfig threadPoolConfig =
            ThreadPoolConfig.defaultConfig().copy()
                .setCorePoolSize(Runtime.getRuntime().availableProcessors())
                .setMaxPoolSize(512);
        transportBuilder.setWorkerThreadPoolConfig(threadPoolConfig);
//        System.out.println("Current thread pool config: " + transportBuilder.getWorkerThreadPoolConfig());
        this.clientTransport = transportBuilder.build();
        clientTransport.start();

        this.proxyServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzyProxy", "localhost", proxyPort);
        proxyServer.addListener(listener);
        System.out.println("Chunking: " + listener.isChunkingEnabled());
        final ServerConfiguration configuration = proxyServer.getServerConfiguration();
        configuration.addHttpHandler(
            new HttpHandler() {
                public void service(Request request, Response response) throws Exception {
//                    if (request.getRequestURI().contains("css")) {
//                        return;
//                    }
                    System.out.println("Proxying " + request.getRequestURI() + " - " + request.getRequestURL());
                    System.out.println("  port: " + request.getServerPort());
                    System.out.println("  remote port: " + request.getRemotePort());
                    System.out.println("  path info: " + request.getPathInfo());
                    System.out.println("  context: " + request.getContextPath());
                    System.out.println("  encoding: " + request.getCharacterEncoding());
                    System.out.println("  query: " + request.getQueryString());
// Very bad idea to read parameters: breaks the request!
//                    for (String name : request.getParameterNames()) {
//                        System.out.printf("    %s: %s%n", name, request.getParameter(name));
//                    }
                    final Map<String, List<String>> queryParameters = parseQueryOnly(request);
                    System.out.println("  query parameters: " + queryParameters);
                    System.out.println("  headers:");
                    for (String headerName : request.getHeaderNames()) {
                        for (String headerValue : request.getHeaders(headerName)) {
                            System.out.printf("    %s=%s%n", headerName, headerValue);
                        }
                    }
                    final HttpRequestPacket httpRequestPacket = request.getRequest();
                    System.out.println("  Request packet: " + httpRequestPacket);
                    final HttpClientFilter httpClientFilter = new HttpClientFilter();
                    for (ContentEncoding encoding : httpClientFilter.getContentEncodings()) {
                        System.out.println("Content encoding: " + encoding);
                        httpClientFilter.removeContentEncoding(encoding);
                    }
                    /*
                    if (false) {
                        final NIOInputStream nioInputStream = request.getNIOInputStream();
                        nioInputStream.notifyAvailable(new ReadHandler() {
                            @Override
                            public void onDataAvailable() throws Exception {
                                System.out.printf("Request data available: %d%n", nioInputStream.available());
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                throwable.printStackTrace();
                            }

                            @Override
                            public void onAllDataRead() throws Exception {
                                System.out.printf("Request data complete: %d%n", nioInputStream.available());
                            }
                        });
                        return;
                    }*/

                    final GrizzlyProxyClientProcessor clientProcessor = new GrizzlyProxyClientProcessor(
                        request, response, serverHost, serverPort);
                    final FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
                    clientFilterChainBuilder.add(new TransportFilter());
                    clientFilterChainBuilder.add(httpClientFilter);
                    clientFilterChainBuilder.add(clientProcessor);
                    final FilterChain filterChain = clientFilterChainBuilder.build();
                    final TCPNIOConnectorHandler connectorHandler =
                        TCPNIOConnectorHandler.builder(clientTransport)
                            .setSyncConnectTimeout(2, TimeUnit.SECONDS)
                            .processor(filterChain).build();
                    clientProcessor.setConnectorHandler(connectorHandler);
                    response.suspend(30, TimeUnit.SECONDS, null, new TimeoutHandler() {
                        @Override
                        public boolean onTimeout(Response response) {
                            //It is timeout from the very beginning of the request: must be large for large responses
                            System.out.println("TIMEOUT while reading " +  request.getRequestURL());
                            response.finish();
                            clientProcessor.close();
                            return true;
                        }
                    });
                    clientProcessor.requestConnectionToServer();
                }
            });
    }

    void start() throws IOException {
        proxyServer.start();
    }

    private static Map<String, List<String>> parseQueryOnly(Request request) {
        final Map<String, List<String>> result = new LinkedHashMap<>();
        final Parameters parameters = new Parameters();
        final Charset charset = lookupCharset(request.getCharacterEncoding());
        parameters.setHeaders(request.getRequest().getHeaders());
        parameters.setQuery(request.getRequest().getQueryStringDC());
        parameters.setEncoding(charset);
        parameters.setQueryStringEncoding(charset);
        parameters.handleQueryParameters();
        for (final String name : parameters.getParameterNames()) {
            final String[] values = parameters.getParameterValues(name);
            final List<String> valuesList = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    valuesList.add(value);
                }
            }
            result.put(name, valuesList);
        }
        return result;
    }


    private static Charset lookupCharset(final String enc) {
        Charset charset = org.glassfish.grizzly.http.util.Constants.DEFAULT_HTTP_CHARSET;
        if (enc != null) {
            try {
                charset = Charsets.lookupCharset(enc);
            } catch (Exception e) {
                // ignore possible exception
            }
        }
        return charset;
    }


    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: " + GrizzlyProxyTest.class.getName() + " server-host server-port proxy-port");
            return;
        }
        new GrizzlyProxyTest(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2])).start();
        System.out.println("Press ENTER to stop the proxy server...");
        System.in.read();
    }
}
