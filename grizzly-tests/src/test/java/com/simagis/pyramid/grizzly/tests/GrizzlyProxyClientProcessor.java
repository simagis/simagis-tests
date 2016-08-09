package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.*;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class GrizzlyProxyClientProcessor extends BaseFilter {
    private static final String PATH_FOR_DEBUG = "/tmp/gpcp/";
    private static final AtomicLong counter = new AtomicLong();
    private static final boolean GLOBAL_SYNCHRONIZATION = false;
    private static final boolean LOCAL_SYNCHRONIZATION = true;
    private static final Lock GLOBAL_LOCK = new ReentrantLock();

    private final Response response;
    private final HttpRequestPacket requestToServerHeaders;
    private ByteBuffer requestToServerBody;
    private final String serverHost;
    private final int serverPort;
    private final NIOInputStream inputStream;
    private final NIOOutputStream outputStream;
    private TCPNIOConnectorHandler connectorHandler = null;
    private volatile Connection connection = null;
    private boolean firstReply = true;

    private final Lock lock = new ReentrantLock();

    GrizzlyProxyClientProcessor(
        Request request,
        Response response,
        String serverHost,
        int serverPort)
    {
        debugLock();
        try {
            this.response = response;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.inputStream = request.getNIOInputStream();
            this.outputStream = response.getNIOOutputStream();

            /*
            InputBuffer inputBuffer = request.getInputBuffer();
            //TODO!! it is not FULL buffer - not
            this.requestToServerBody = inputBuffer.readBuffer().toByteBuffer();
            //TODO!! getNIOInputStream() and wait until the last
            //TODO!! bad idea to accumulate all POST request

            request.replayPayload(Buffers.wrap(null, cloneByteBuffer(requestToServerBody)));
            // - clone necessary, because reading parameters below damages the content of "replayed" buffer
            System.out.println("Request parameters:");
            for (String name : request.getParameterNames()) {
                System.out.printf("    %s: %s%n", name, request.getParameter(name));
            }
            */

            final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
            builder.protocol(Protocol.HTTP_1_1);
            builder.method(request.getMethod());
            builder.uri(request.getRequestURI());
            builder.query(request.getQueryString());
            builder.contentType(request.getContentType());
            builder.contentLength(request.getContentLength());
            builder.chunked(false); //TODO!! - Q: is it correct?
            System.out.println("Request headers:");
            for (String headerName : request.getHeaderNames()) {
                for (String headerValue : request.getHeaders(headerName)) {
                    builder.header(headerName, headerValue);
                }
            }
            builder.removeHeader("Host");
            builder.header("Host", serverHost + ":" + serverPort);
            this.requestToServerHeaders = builder.build();
        } finally {
            debugUnlock();
        }
    }

    public void setConnectorHandler(TCPNIOConnectorHandler connectorHandler) {
        this.connectorHandler = Objects.requireNonNull(connectorHandler);
    }

    public void requestConnectionToServer() throws InterruptedException {
        System.out.println("Requesting connection to " + serverHost + ":" + serverPort + "...");
        final CompletionHandler<Connection> completionHandler = new CompletionHandler<Connection>() {
            @Override
            public void cancelled() {
            }

            @Override
            public void failed(Throwable throwable) {
                synchronized (lock) {
                    System.err.println("Connection failed");
                    throwable.printStackTrace();
                    //TODO!! logging
                    closeAndReturnError("Cannot connect to the server");
                }
            }

            @Override
            public void completed(Connection connection) {
                setConnection(connection);
                System.out.println("Connected");
            }

            @Override
            public void updated(Connection connection) {
                //ignore
            }
        };
        connectorHandler.connect(
            new InetSocketAddress(serverHost, serverPort), completionHandler);
    }

    public void close() {
        synchronized (lock) {
            if (connection != null) {
                connection.close();
                connection = null;
            }
            try {
                outputStream.close();
            } catch (IOException e) {
                //TODO!! like in ReadTask
            }
        }
    }

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        System.out.println("Connected to " + serverHost + ":" + serverPort);

        debugLock();
        try {
            inputStream.notifyAvailable(new ReadHandler() {
                @Override
                public void onDataAvailable() throws Exception {
                    sendData();
                    inputStream.notifyAvailable(this);
                }

                @Override
                public void onError(Throwable throwable) {
                    System.out.println("Error while reading request");
                    throwable.printStackTrace();
                    closeAndReturnError("Error while reading request");
                }

                @Override
                public void onAllDataRead() throws Exception {
                    System.out.println("All data ready");
                    sendData();
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {
                    }
                }

                private void sendData() {
                    final boolean finished = inputStream.isFinished();
                    final Buffer buffer = inputStream.readBuffer();
                    System.out.printf("Data ready, %d bytes%s; sending request to server: buffer %s%n",
                        inputStream.readyData(), finished ? " (FINISHED)" : "", buffer);
                    final HttpContent httpContent = HttpContent.builder(requestToServerHeaders)
                        .content(buffer)
                        .last(finished)
                        .build();
                    //TODO!! Q - when last is necessary?
                    debugWriteBytes(byteBufferToArray(buffer.toByteBuffer()), "request-", counter.getAndIncrement());
                    ctx.write(httpContent);
                    // - note: buffer will be destroyed by this call
                }
            });
            System.out.println("Starting sending request to server: header " + requestToServerHeaders);
//            ctx.write(requestToServerHeaders);
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
                // It is correct!
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
                        //TODO!! Q: is it correct?
                        close();
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

    private void setConnection(Connection connection) {
        synchronized (lock) {
            this.connection = connection;
        }
    }

    private void closeAndReturnError(String message) {
        response.setStatus(500, message);
        close();
        if (response.isSuspended()) {
            response.resume();
            System.out.println("Response is resumed");
        } else {
            response.finish();
            System.out.println("Response is finished");
        }
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
        byteBuffer = byteBuffer.duplicate();
        final byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return bytes;
    }

    private static ByteBuffer cloneByteBuffer(ByteBuffer byteBuffer) {
        byteBuffer = byteBuffer.duplicate();
        ByteBuffer result = ByteBuffer.allocate(byteBuffer.remaining());
        result.duplicate().put(byteBuffer);
        return result;
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
