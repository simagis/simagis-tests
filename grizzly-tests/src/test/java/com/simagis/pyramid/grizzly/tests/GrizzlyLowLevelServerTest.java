package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.filterchain.*;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpServerFilter;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GrizzlyLowLevelServerTest {
    public static final int PORT = 82;
    public static void main(String[] args) throws IOException {
        // lifecycle of the executor used by the IdleTimeoutFilter must be explicitly
        // managed
        final DelayedExecutor timeoutExecutor = IdleTimeoutFilter.createDefaultIdleDelayedExecutor();
        timeoutExecutor.start();

        FilterChainBuilder serverFilterChainBuilder = FilterChainBuilder.stateless();
        serverFilterChainBuilder.add(new TransportFilter());
        serverFilterChainBuilder.add(new IdleTimeoutFilter(timeoutExecutor, 10, TimeUnit.SECONDS));
        serverFilterChainBuilder.add(new HttpServerFilter());
        // Simple server implementation, which locates a resource in a local file system
        // and transfers it via HTTP
        serverFilterChainBuilder.add(new BaseFilter() {
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                final HttpContent httpContent = ctx.getMessage();
                final HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();

                // Check if it's the last HTTP request chunk
                if (!httpContent.isLast()) {
                    return ctx.getStopAction();
                }
                System.out.println("Server request: " + request);

                // Start asynchronous file download
                final HttpResponsePacket responsePacket = HttpResponsePacket.builder(request).
                    protocol(request.getProtocol()).status(200).
                    reasonPhrase("OK").chunked(true).build();
                final HttpContent httpResponseContent =
                    responsePacket.httpContentBuilder().content(Buffers.wrap(null, "O'k")).build();
                ctx.write(httpResponseContent);
                return ctx.getStopAction();
            }
        });

        // Initialize Transport
        final TCPNIOTransport transport =
            TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(serverFilterChainBuilder.build());

        try {
            transport.bind("localhost", PORT);
            transport.start();

            System.out.println("Press any key to stop the server...");
            System.in.read();
        } finally {
            transport.shutdownNow();
            timeoutExecutor.stop();
            timeoutExecutor.destroy();
        }

    }
}
