package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.*;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.MimeHeaders;
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
    private final String serverHost;
    private final int serverPort;
    private final NIOInputStream inputStreamFromClient;
    private final NIOOutputStream outputStreamToClient;
    private TCPNIOConnectorHandler connectorToServerHandler = null;
    private volatile Connection connectionToServer = null;
    private volatile boolean firstReply = true;
    private volatile boolean connectionToServerClosed = false;

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
            this.inputStreamFromClient = request.getNIOInputStream();
            this.outputStreamToClient = response.getNIOOutputStream();

            /*
            InputBuffer inputBuffer = request.getInputBuffer();
            this.requestToServerBody = inputBuffer.readBuffer().toByteBuffer();
            //It is not correct - readBuffer may contain only part of full POST request

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
            // builder.chunked(false);
            builder.contentType(request.getContentType());
            builder.contentLength(request.getContentLength());
            System.out.println("Request headers:");
            for (String headerName : request.getHeaderNames()) {
                for (String headerValue : request.getHeaders(headerName)) {
                    builder.header(headerName, headerValue);
                }
            }
//            builder.removeHeader("accept-encoding");
            builder.removeHeader("Host");
            builder.header("Host", serverHost + (serverPort == 80 ? "" : ":" + serverPort));
            // - Important: to be congruent with popular browsers, the port should be stripped from
            // the 'Host' field when the port is 80. In other case, it is possible that the server
            // will return "moved" response (instead of correct page) to remove port number from URL.
            this.requestToServerHeaders = builder.build();
        } finally {
            debugUnlock();
        }
    }

    public void setConnectorToServerHandler(TCPNIOConnectorHandler connectorToServerHandler) {
        this.connectorToServerHandler = Objects.requireNonNull(connectorToServerHandler);
    }

    public void requestConnectionToServer() throws InterruptedException {
        System.out.println("Requesting connection to " + serverHost + ":" + serverPort + "...");
        connectorToServerHandler.connect(
            new InetSocketAddress(serverHost, serverPort), new CompletionHandler<Connection>() {
                @Override
                public void cancelled() {
                }

                @Override
                public void failed(Throwable throwable) {
                    debugLock();
                    try {
                        System.out.println("Connection to " + serverHost + ":" + serverPort + " failed: " + throwable);
                        throwable.printStackTrace();
                        closeAndReturnError("Cannot connect to the server");
                    } finally {
                        debugUnlock();
                    }
                }

                @Override
                public void completed(Connection connection) {
                    System.out.println("Connected");
                }

                @Override
                public void updated(Connection connection) {
                }
            });
    }

    public void closeServerAndClientConnections() {
        debugLock();
        try {
            connectionToServerClosed = true;
            // - closeSilently will invoke handleClose, but it will do nothing
            if (connectionToServer != null) {
                connectionToServer.closeSilently();
                connectionToServer = null;
            }
            try {
                outputStreamToClient.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } finally {
            debugUnlock();
        }
    }

    public void closeConnectionsAndResponse() {
        debugLock();
        try {
            closeServerAndClientConnections();
            response.resume();
        } finally {
            debugUnlock();
        }
    }

    public void closeAndReturnError(String message) {
        debugLock();
        try {
            response.setStatus(500, message);
            closeServerAndClientConnections();
            if (response.isSuspended()) {
                response.resume();
                System.out.println("Response is resumed due to error");
            } else {
                response.finish();
                System.out.println("Response is finished due to error");
            }
        } finally {
            debugUnlock();
        }
    }

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        setConnectionToServer(ctx.getConnection());
        System.out.println("Connected to " + serverHost + ":" + serverPort);

        inputStreamFromClient.notifyAvailable(new ReadHandler() {
            @Override
            public void onDataAvailable() throws Exception {
                debugLock();
                try {
                    sendData();
                } finally {
                    debugUnlock();
                }
                inputStreamFromClient.notifyAvailable(this);
            }

            @Override
            public void onError(Throwable throwable) {
                System.out.println("Error while reading request");
                throwable.printStackTrace();
                closeAndReturnError("Error while reading request");
            }

            @Override
            public void onAllDataRead() throws Exception {
                debugLock();
                try {
                    System.out.println("All data ready");
                    sendData();
                    try {
                        inputStreamFromClient.close();
                    } catch (IOException ignored) {
                    }
                } finally {
                    debugUnlock();
                }
            }

            private void sendData() {
                final int readyData = inputStreamFromClient.readyData();
                final Buffer buffer = inputStreamFromClient.readBuffer();
                final boolean finished = inputStreamFromClient.isFinished();
                System.out.printf("Data ready, %d bytes%s; sending request to server: buffer %s%n",
                    readyData, finished ? " (FINISHED)" : "", buffer);
                final HttpContent httpContent = HttpContent.builder(requestToServerHeaders)
                    .content(buffer)
                    .last(finished)
                    .build();
                debugWriteBytes(byteBufferToArray(buffer.toByteBuffer()), "request-", counter.getAndIncrement());
                connectionToServer.write(httpContent);
                // - note: buffer will be destroyed by this call
            }
        });
        System.out.println("Starting sending request to server: header " + requestToServerHeaders);
//            ctx.write(requestToServerHeaders);
        return ctx.getStopAction();
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final long currentCounter = counter.getAndIncrement();
        final HttpContent httpContent = ctx.getMessage();
        if (httpContent == null) {
            throw new AssertionError("Null ctx.getMessage()");
        }
        final Buffer buffer = httpContent.getContent();
        final ByteBuffer byteBuffer = buffer.toByteBuffer();
        final byte[] bytes = byteBufferToArray(byteBuffer);

        final boolean last;
//        System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
//            byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
        debugLock();
        try {
            if (connectionToServerClosed) {
                // - to be on the safe side (should not occur)
                return ctx.getStopAction();
            }
            last = httpContent.isLast();
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
                // The following code was used when we did not remove encodings
                // from httpClientFilter: it this case, client unpacks gzipped data,
                // and we need to correct response headers
                final boolean encoded = headers.contains(Header.ContentEncoding);
                for (String headerName : headers.names()) {
                    for (String headerValue : headers.values(headerName)) {
                        if (encoded) {
                            if ("content-encoding".equalsIgnoreCase(headerName)
                                || "content-length".equalsIgnoreCase(headerName))
                            {
                                continue;
                            }
                        }
                        response.addHeader(headerName, headerValue);
                    }
                }
                if (encoded) {
                    response.addHeader("transfer-encoding", "chunked");
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

        outputStreamToClient.notifyCanWrite(new WriteHandler() {
            @Override
            public void onWritePossible() throws Exception {
//                    System.out.printf("Sending %d bytes (counter=%d): starting...%n", bytesToClient.length, currentCounter);
//                    Thread.sleep(new java.util.Random().nextInt(1000));
                debugLock();
                try {
                    System.out.printf("Sending %s bytes (counter=%d)...%n",
                        bytes.length, currentCounter);
                    outputStreamToClient.write(bytes);
                    outputStreamToClient.flush(); // - necessary to avoid a bug in Grizzly 2.3.22!

//                    Thread.sleep(new java.util.Random().nextInt(1000));
//                    System.out.printf("Sending %d bytes (counter=%d): done%n", bytesToClient.length, currentCounter);
                    if (last) {
                        closeConnectionsAndResponse();
                        System.out.println("Response is resumed: counter=" + currentCounter);
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
        System.out.printf("%d: finishing notifying about %s bytes%s...%n",
            currentCounter, bytes == null ? null : bytes.length, last ? " (LAST)" : "");

        return ctx.getStopAction();
    }

    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        // getMessage will be null
        if (connectionToServerClosed) {
            // Nothing to do: maybe we already called closeServerAndClientConnections
            System.out.println("Connection closed, message = " + ctx.getMessage());
            return ctx.getStopAction();
        }

        System.out.println("UNEXPECTED CONNECTION CLOSE, message = " + ctx.getMessage());
        closeConnectionsAndResponse();
        System.out.println("Response is resumed due to closing connection by server");
        return ctx.getStopAction();
    }

    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
        super.exceptionOccurred(ctx, error);
        error.printStackTrace();
    }

    private void setConnectionToServer(Connection connectionToServer) {
        debugLock();
        try {
            this.connectionToServer = connectionToServer;
        } finally {
            debugUnlock();
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
