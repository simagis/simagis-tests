package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.*;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class GrizzlyProxyTest {
    final String serverHost;
    final int serverPort;
    final int proxyPort;
    final HttpServer proxyServer;

    final TCPNIOTransport clientTransport;

    GrizzlyProxyTest(String serverHost, int serverPort, int proxyPort) throws IOException {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.proxyPort = proxyPort;

        this.clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.start();

        this.proxyServer = new HttpServer();
        proxyServer.addListener(new NetworkListener("grizzyProxy", "localhost", proxyPort));
        final ServerConfiguration configuration = proxyServer.getServerConfiguration();
        configuration.addHttpHandler(
            new HttpHandler() {
                public void service(Request request, final Response response) throws Exception {
                    System.out.println("Proxying " + request.getRequestURI() + " - " + request.getRequestURL());
                    System.out.println("port: " + request.getServerPort());
                    System.out.println("remote port: " + request.getRemotePort());
                    System.out.println("path info: " + request.getPathInfo());
                    System.out.println("context: " + request.getContextPath());
                    System.out.println("query: " + request.getQueryString());
                    for (String name : request.getParameterNames()) {
                        System.out.printf("%s: %s%n", name, request.getParameter(name));
                    }
                    AtomicReference<Connection> connection = new AtomicReference<>();
                    clientTransport.setProcessor(newClientProcessor(
                        request, response, connection, serverHost, serverPort));
                    Future<Connection> connectFuture = clientTransport.connect(serverHost, serverPort);
                    try {
                        connection.set(connectFuture.get(10, TimeUnit.SECONDS));
                        System.out.printf("Connected%n");
                        response.suspend();
                        Thread.sleep(1000);
                        // waiting...
                    } catch (TimeoutException e) {
                        System.err.println("Timeout while reading target resource");
                    } catch (ExecutionException e) {
                        System.err.println("Error downloading the resource");
                        e.getCause().printStackTrace();
                    }
                    System.out.printf("Requesting %s:%d...%n", serverHost, serverPort);
                }
            });
    }

    void start() throws IOException {
        proxyServer.start();
    }

    static FilterChain newClientProcessor(
        final Request request,
        final Response response,
        final AtomicReference<Connection> connection,
        final String serverHost,
        final int serverPort)
    {
        final NIOOutputStream outputStream = response.getNIOOutputStream();

        final FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        clientFilterChainBuilder.add(new TransportFilter());
        clientFilterChainBuilder.add(new HttpClientFilter());
        clientFilterChainBuilder.add(new BaseFilter() {
            boolean firstReply = true;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Write the request asynchronously
                System.out.println("Connected to " + serverHost + ":" + serverPort);
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                    .uri(request.getRequestURI())
                    .query(request.getQueryString())
                    .protocol(Protocol.HTTP_1_1)
                    .header("Host", serverHost) //TODO!! all headers
                    .build();
                ctx.write(httpRequest);
                System.out.println("Sending request to server " + httpRequest);
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                final HttpContent httpContent = ctx.getMessage();
                final ByteBuffer byteBuffer = httpContent.getContent().toByteBuffer();
                System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
                    byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
                if (firstReply) {
                    System.out.printf("HTTP response: {%s}%n", httpContent.getHttpHeader());
                    //TODO!! pass to response
                    firstReply = false;
                }
                final byte[] bytes = new byte[byteBuffer.remaining()];
                System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
                    byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
                System.out.printf("Reading %d bytes...%n", bytes.length);
                byteBuffer.get(bytes);
                outputStream.notifyCanWrite(new WriteHandler() {
                    @Override
                    public void onWritePossible() throws Exception {
                        outputStream.write(bytes);
                        if (httpContent.isLast()) {
                            outputStream.close();
                            connection.get().close();
                            if (response.isSuspended()) {
                                response.resume();
                                System.out.println("Response is resumed: " + this);
                            } else {
                                response.finish();
                                System.out.println("Response is finished: " + this);
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {

                    }
                });

                return ctx.getStopAction();
            }

            @Override
            public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
                super.exceptionOccurred(ctx, error);
                error.printStackTrace();
            }
        });
        return clientFilterChainBuilder.build();

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
