package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class GrizzlyProxyClientProcessor extends BaseFilter {
    private final TCPNIOTransport clientTransport;
    private final Request request;
    private final Response response;
    private final String serverHost;
    private final int serverPort;
    private final NIOOutputStream outputStream;
    private volatile Connection connection = null;
    private boolean firstReply = true;

    GrizzlyProxyClientProcessor(
        TCPNIOTransport clientTransport,
        Request request,
        Response response,
        String serverHost,
        int serverPort)
    {
        this.clientTransport = clientTransport;
        this.request = request;
        this.response = response;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.outputStream = response.getNIOOutputStream();
    }

    public void connect() throws InterruptedException, ExecutionException, TimeoutException {
        Future<Connection> connectFuture = clientTransport.connect(serverHost, serverPort);
        this.connection = connectFuture.get(10, TimeUnit.SECONDS);
        System.out.printf("Connected%n");
    }

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        // Write the request asynchronously
        System.out.println("Connected to " + serverHost + ":" + serverPort);
        final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
        builder.protocol(Protocol.HTTP_1_1);
        builder.method(request.getMethod());
        builder.uri(request.getRequestURI());
        builder.query(request.getQueryString());
        for (String headerName : request.getHeaderNames()) {
            for (String headerValue : request.getHeaders(headerName)) {
                builder.header(headerName, headerValue);
            }
        }
        builder.removeHeader("accept-encoding");
        //TODO!! - why necessary?
        builder.removeHeader("Host");
        builder.header("Host", serverHost + ":" + serverPort);
        final HttpRequestPacket requestToServer = builder.build();
        ctx.write(requestToServer);
        System.out.println("Sending request to server " + requestToServer);
        return ctx.getStopAction();
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final HttpContent httpContent = ctx.getMessage();
        final ByteBuffer byteBuffer = httpContent.getContent().toByteBuffer();
        System.out.printf("ByteBuffer limit %d, position %d, remaining %d%n",
            byteBuffer.limit(), byteBuffer.position(), byteBuffer.remaining());
        if (firstReply) {
            final HttpResponsePacket httpHeader = (HttpResponsePacket) httpContent.getHttpHeader();
            response.setStatus(httpHeader.getHttpStatus());
            final MimeHeaders headers = httpHeader.getHeaders();
            for (String headerName : headers.names()) {
                for (String headerValue : headers.values(headerName)) {
                    response.addHeader(headerName, headerValue);
                    //TODO!! what will be in a case of duplicated names? double duplication?
                }
            }
            System.out.println("Setting response headers:");
            for (String headerName : response.getHeaderNames()) {
                for (String headerValue : response.getHeaderValues(headerName)) {
                    System.out.printf("    %s=%s%n", headerName, headerValue);
                }
            }
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
                    connection.close();
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
}
