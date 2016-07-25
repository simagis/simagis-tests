package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.*;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SimpleClientTest {
    private final static boolean START_TRANSPORT_EVERY_TIME = false;

    final URI uri;
    final String resultFileName;
    FutureImpl<byte[]> serverFuture = null;
    ByteArrayOutputStream serverBytes = null;
    TCPNIOTransport transport;

    SimpleClientTest(URI uri, String resultFileName) throws IOException {
        this.uri = uri;
        this.resultFileName = resultFileName;
        if (uri.getPath().isEmpty()) {
            throw new IllegalArgumentException("Path must contain at least / character");
        }
        if (!START_TRANSPORT_EVERY_TIME) {
            transport = TCPNIOTransportBuilder.newInstance().build();
            transport.start();
        }
    }

    public void doMain() throws IOException, InterruptedException {
        final String host = uri.getHost();
        final int port = uri.getPort() > 0 ? uri.getPort() : 80;
        long t1 = System.nanoTime();
        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        clientFilterChainBuilder.add(new TransportFilter());
        clientFilterChainBuilder.add(new HttpClientFilter());
        clientFilterChainBuilder.add(new BaseFilter() {
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Write the request asynchronously
                System.out.println("Connected to " + host + ":" + port + "; getting " + uri);
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                    .uri(uri.getPath()).query(uri.getQuery()).protocol(Protocol.HTTP_1_1)
                    .header("Host", SimpleClientTest.this.uri.getHost()) // mandatory since HTTP 1.1
                    .build();
                ctx.write(httpRequest);
                System.out.println("Sending request " + httpRequest);
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                final HttpContent httpContent = ctx.getMessage();
                final ByteBuffer byteBuffer = httpContent.getContent().toByteBuffer();
                System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
                    byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
                if (serverBytes.size() == 0) {
                    System.out.printf("HTTP response: {%s}%n", httpContent.getHttpHeader());
                }
                byte[] bytes = new byte[byteBuffer.remaining()];
                System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
                    byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
                System.out.printf("Reading %d bytes...%n", bytes.length);
//                byteBuffer.rewind();
                byteBuffer.get(bytes);
                serverBytes.write(bytes);
                if (httpContent.isLast()) {
                    serverFuture.result(serverBytes.toByteArray());
                }
                return ctx.getStopAction();
            }

            @Override
            public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
                super.exceptionOccurred(ctx, error);
                error.printStackTrace();
            }
        });
        long t2 = System.nanoTime(), t3 = t2;
        if (START_TRANSPORT_EVERY_TIME) {
            // requires some time, but can be done at the very beginning once
            transport = TCPNIOTransportBuilder.newInstance().build();
            t3 = System.nanoTime();
            transport.start();
        }

        transport.setProcessor(clientFilterChainBuilder.build());
        long t4 = System.nanoTime();

        try {
            System.out.printf("Initializing request: %.4f ms creating filter chain + "
                    + "%.4f ms creating transport + %.4f ms starting transport%n",
                (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6);
            for (int k = 0; k < 3; k++) {
                t1 = System.nanoTime();
                Connection connection = null;
                System.out.printf("Requesting %s...%n", uri);
                Future<Connection> connectFuture = transport.connect(host, port);
                t2 = System.nanoTime();
                serverBytes = new ByteArrayOutputStream();
                serverFuture = SafeFutureImpl.create();
                try {
                    t3 = System.nanoTime();
                    connection = connectFuture.get(10, TimeUnit.SECONDS);
                    t4 = System.nanoTime();
                    final byte[] result = serverFuture.get(10, TimeUnit.SECONDS);
                    long t5 = System.nanoTime();
                    System.out.printf("Connected to %s: %.4f ms starting connection + "
                            + "%.4f ms allocation + %.4f ms connecting + %.4f ms getting results%n",
                        uri, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6, (t5 - t4) * 1e-6);
                    final Path resultFile = Paths.get(resultFileName + (k == 0 ? "" : "." + k));
                    Files.write(resultFile, result);
                    System.out.printf("%d bytes saved in %s%n", result.length, resultFile);
                } catch (TimeoutException e) {
                    System.err.println("Timeout while reading target resource");
                } catch (ExecutionException e) {
                    System.err.println("Error downloading the resource: " + host + ":" + port);
                    e.getCause().printStackTrace();
                } finally {
                    if (connection != null) {
                        connection.close();
                    }
                }
            }
        } finally {
            transport.shutdownNow();
            System.out.printf("Client stopped%n%n%n");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: " + SimpleClientTest.class.getName() + " URL1 [URL2] result-file");
            return;
        }

        for (int testCount = 0; testCount < 5; testCount++) {
            new SimpleClientTest(new URI(args[0]), args[1]).doMain();
        }
    }
}
