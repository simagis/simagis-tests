package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class GrizzlyProxyClientProcessor extends BaseFilter {
    private static final String PATH_FOR_DEBUG = "/tmp/gpcp/";
    private static final AtomicLong counter = new AtomicLong();
    private static final boolean GLOBAL_SYNCHRONIZATION = false;
    private static final boolean LOCAL_SYNCHRONIZATION = true;
    private static final Lock GLOBAL_LOCK = new ReentrantLock();

    private final TCPNIOTransport clientTransport;
    private final Response response;
    private final HttpRequestPacket requestToServerHeaders;
    private final byte[] requestToServerBody;
    private final String serverHost;
    private final int serverPort;
    private final NIOOutputStream outputStream;
    volatile Connection connection = null;
    private boolean firstReply = true;

    private final Lock lock = new ReentrantLock();

    GrizzlyProxyClientProcessor(
        TCPNIOTransport clientTransport,
        Request request,
        Response response,
        String serverHost,
        int serverPort)
    {
        debugLock();
        try {
            this.clientTransport = clientTransport;
            this.response = response;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.outputStream = response.getNIOOutputStream();

            InputBuffer inputBuffer = request.getInputBuffer();
            this.requestToServerBody = byteBufferToArray(inputBuffer.readBuffer().toByteBuffer());

            final Buffer wrap = Buffers.wrap(null, ByteBuffer.wrap(requestToServerBody.clone()));
            request.replayPayload(wrap);
            System.out.println("Request parameters:");
            for (String name : request.getParameterNames()) {
                System.out.printf("    %s: %s%n", name, request.getParameter(name));
            }

            final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
            builder.protocol(Protocol.HTTP_1_1);
            builder.method(request.getMethod());
            builder.uri(request.getRequestURI());
            builder.query(request.getQueryString());
            builder.contentType(request.getContentType());
            builder.contentLength(request.getContentLength());
            builder.chunked(false);
            System.out.println("Request headers:");
            for (String headerName : request.getHeaderNames()) {
                for (String headerValue : request.getHeaders(headerName)) {
                    builder.header(headerName, headerValue);
                }
            }
//            builder.removeHeader("accept-encoding"); //TODO!! - why necessary?
            builder.removeHeader("Host");
            builder.header("Host", serverHost + ":" + serverPort);
            this.requestToServerHeaders = builder.build();
        } finally {
            debugUnlock();
        }
        debugWriteBytes(requestToServerBody, "request-", counter.getAndIncrement());
    }

    public void connect() throws InterruptedException, ExecutionException, TimeoutException {
//        debugLock();
        try {
            Future<Connection> connectFuture = clientTransport.connect(serverHost, serverPort);
            this.connection = connectFuture.get(10, TimeUnit.SECONDS);
            connection.setReadTimeout(2, TimeUnit.SECONDS);
            System.out.printf("Connected%n");
        } finally {
//            debugUnlock();
        }
    }

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        System.out.println("Connected to " + serverHost + ":" + serverPort);

        debugLock();
        try {
            final HttpContent.Builder contentBuilder = HttpContent.builder(requestToServerHeaders);
            Buffer buffer = Buffers.wrap(null, ByteBuffer.wrap(requestToServerBody));
//        buffer.rewind();
            contentBuilder.content(buffer);
            contentBuilder.last(true);
            HttpContent content = contentBuilder.build();
            System.out.println("Sending request to server: header " + content.getHttpHeader());
            System.out.println("Sending request to server: buffer " + buffer);

            ctx.write(content);
            return ctx.getStopAction();
        } finally {
            debugUnlock();
        }
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final long currentCounter = counter.getAndIncrement();
        debugLock();
        final HttpContent httpContent = ctx.getMessage();
        final Buffer buffer = httpContent.getContent();
        final ByteBuffer byteBuffer = buffer.toByteBuffer();
        final byte[] bytes = byteBufferToArray(byteBuffer);
//        System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
//            byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
        try {
            System.out.printf("first: %s, isHeader: %s, isLast: %s, counter: %d%n",
                firstReply, httpContent.isHeader(), httpContent.isLast(), currentCounter);
            if (firstReply) {
                System.out.println("Initial response headers:");
                for (String headerName : response.getHeaderNames()) {
                    for (String headerValue : response.getHeaderValues(headerName)) {
                        System.out.printf("    %s=%s%n", headerName, headerValue);
                    }
                }
                final HttpResponsePacket httpHeader = (HttpResponsePacket) httpContent.getHttpHeader();
                System.out.println("Server response: " + httpHeader);
                response.setStatus(httpHeader.getHttpStatus());
                final MimeHeaders headers = httpHeader.getHeaders();
                for (String headerName : headers.names()) {
                    for (String headerValue : headers.values(headerName)) {
                        response.addHeader(headerName, headerValue);
                    }
                }
                /*
                final boolean encoded = headers.contains(Header.ContentEncoding);
                for (String headerName : headers.names()) {
                    for (String headerValue : headers.values(headerName)) {
                        if (encoded) {
                            if ("content-encoding".equalsIgnoreCase(headerName)
                                || "content-length".equalsIgnoreCase(headerName))
                            {
                                continue;
                                //TODO!! - why necessary?
                            }
                        }
                        response.addHeader(headerName, headerValue);
                        //TODO!! what will be in a case of duplicated names? double duplication?
                    }
                }
                if (encoded) {
                    response.addHeader("transfer-encoding", "chunked");
                    //TODO!! -why necessary??
                }
                */

                System.out.println("Setting response headers:");
                for (String headerName : response.getHeaderNames()) {
                    for (String headerValue : response.getHeaderValues(headerName)) {
                        System.out.printf("    %s=%s%n", headerName, headerValue);
                    }
                }
                firstReply = false;
            }
            System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
                byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
            System.out.printf("%d: reading %d bytes%s...%n",
                currentCounter, bytes.length, httpContent.isLast() ? " (LAST)" : "");

            debugWriteBytes(bytes, "", currentCounter);
            System.out.printf("%d: notifying about %d bytes%s...%n",
                currentCounter, bytes.length, httpContent.isLast() ? " (LAST)" : "");
            // It seems that events in notifyCanWrite are internally synchronized,
            // and all calls of onWritePossible will be in proper order.
        } finally {
            debugUnlock();
        }

        outputStream.notifyCanWrite(new WriteHandler() {
            @Override
            public void onWritePossible() throws Exception {
//                    System.out.printf("Sending %d bytes (counter=%d): starting...%n", bytesToClient.length, currentCounter);
//                    Thread.sleep(new java.util.Random().nextInt(1000));
                debugLock();
                try {
                    System.out.printf("Sending %d bytes (counter=%d)...%n", bytes.length, currentCounter);
                    outputStream.write(bytes);
//                    Thread.sleep(new java.util.Random().nextInt(1000));
//                    System.out.printf("Sending %d bytes (counter=%d): done%n", bytesToClient.length, currentCounter);
                    if (httpContent.isLast()) {
                        outputStream.close();
                        connection.close();
                        if (response.isSuspended()) {
                            response.resume();
                            System.out.println("Response is resumed: " + currentCounter);
                        } else {
                            response.finish();
                            System.out.println("Response is finished: " + currentCounter);
                        }
                    }
                } finally {
                    debugUnlock();
                }
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        System.out.printf("%d: finishing notifying about %d bytes%s...%n",
            currentCounter, bytes.length, httpContent.isLast() ? " (LAST)" : "");
        return ctx.getStopAction();
    }

    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
        super.exceptionOccurred(ctx, error);
        error.printStackTrace();
    }

    private void debugLock() {
        if (GLOBAL_SYNCHRONIZATION) {
            GLOBAL_LOCK.lock();
        } else if (LOCAL_SYNCHRONIZATION) {
            lock.lock();
        }
    }

    private void debugUnlock() {
        if (GLOBAL_SYNCHRONIZATION) {
            GLOBAL_LOCK.unlock();
        } else if (LOCAL_SYNCHRONIZATION) {
            lock.unlock();
        }
    }

    private static byte[] byteBufferToArray(ByteBuffer byteBuffer) {
        final byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return bytes;
    }

    private static byte[] inputStreamToArray(InputStream inputStream) throws IOException {
        int len;
        byte[] data = new byte[16384];
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        while ((len = inputStream.read(data, 0, data.length)) != -1) {
            result.write(data, 0, len);
        }
        result.flush();
        return result.toByteArray();
    }

    private static void debugWriteBytes(byte[] bytes, String prefix, long counter) {
        final Path resultFolder = Paths.get(PATH_FOR_DEBUG);
        try {
            Files.createDirectories(resultFolder);
            Files.write(resultFolder.resolve(String.format(prefix + "bytes-%04d", counter)), bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
