package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class GrizzlyProxyTest {
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

        this.clientTransport = TCPNIOTransportBuilder.newInstance().build();
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
                    System.out.println("  query: " + request.getQueryString());
                    for (String name : request.getParameterNames()) {
                        System.out.printf("    %s: %s%n", name, request.getParameter(name));
                    }
                    System.out.println("  headers:");
                    for (String headerName : request.getHeaderNames()) {
                        for (String headerValue : request.getHeaders(headerName)) {
                            System.out.printf("    %s=%s%n", headerName, headerValue);
                        }
                    }
                    final HttpRequestPacket httpRequestPacket = request.getRequest();
                    System.out.println("  Request packet: " + httpRequestPacket);
                    final HttpClientFilter httpClientFilter = new HttpClientFilter();
//                    for (ContentEncoding encoding : httpClientFilter.getContentEncodings()) {
//                        System.out.println("Removing content encoding: " + encoding);
//                        httpClientFilter.removeContentEncoding(encoding);
//                    }
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
                    }

                    final GrizzlyProxyClientProcessor clientProcessor = new GrizzlyProxyClientProcessor(
                        clientTransport, request, response, serverHost, serverPort);
                    final FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
                    clientFilterChainBuilder.add(new TransportFilter());
                    clientFilterChainBuilder.add(httpClientFilter);
                    clientFilterChainBuilder.add(clientProcessor);
                    clientTransport.setProcessor(clientFilterChainBuilder.build());

                    try {
                        clientProcessor.connect();
                        response.suspend();
                        System.out.printf("Requesting %s:%d...%n", serverHost, serverPort);
                    } catch (TimeoutException e) {
                        System.err.println("Timeout while reading target resource");
                    } catch (ExecutionException e) {
                        System.err.println("Error downloading the resource");
                        e.getCause().printStackTrace();
                    }
                }
            });
    }

    void start() throws IOException {
        proxyServer.start();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: " + GrizzlyProxyTest.class.getName() + " server-host server-port proxy-port");
            return;
        }
        new GrizzlyProxyTest(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2])).start();
        System.out.println("Press ENTER to stop the server...");
        System.in.read();
    }
}
