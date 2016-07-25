package com.simagis.pyramid.grizzly.tests.performance;

import org.glassfish.grizzly.http.server.*;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by Daniel on 17/01/2016.
 */
public class ServerManyImagesPerformanceTest {
    static final boolean NON_BLOCKING_MODE = true;
    static final int USED_CPU_COUNT = Math.min(4, Runtime.getRuntime().availableProcessors());
    static final int IMAGE_WIDTH = 1000;
    static final int IMAGE_HEIGHT = 1000;

    public static void main(String[] args) throws IOException {
        HttpServer server = new HttpServer();
        server.addListener(new NetworkListener("grizzy", "localhost", 82));
        final ServerConfiguration configuration = server.getServerConfiguration();
        final MyHttpHandler handler = new MyHttpHandler();
        configuration.addHttpHandler(handler);
        server.start();
        System.out.println("Server started; press any key to stop...");
        System.in.read();
        handler.threadPool.shutdown();
    }

    public static class MyHttpHandler extends HttpHandler {

        final MakeImageTaskQueue queue;
        final MakeImageThreadPool threadPool;

        public MyHttpHandler() {
            this.queue = new MakeImageTaskQueue();
            this.threadPool = new MakeImageThreadPool(USED_CPU_COUNT, queue);
        }

        public void service(final Request request, final Response response) throws Exception {
            String uri = request.getRequestURI();
            int p = uri.lastIndexOf(".");
            final String info = uri.substring(1, p == -1 ? uri.length() : p);
            final Color color;
            try {
                color = Color.decode(info);
            } catch (NumberFormatException e) {
                response.setContentType("text/plain");
                response.getWriter().write("Invalid color in " + uri);
                System.out.println(info + ": " + e);
                return;
            }
            System.out.printf("Processing image with color %s...%n", color);
            final MakeImageTask task = new MakeImageTask(IMAGE_WIDTH, IMAGE_HEIGHT, color, response);
            if (!NON_BLOCKING_MODE) {
                final byte[] bytes = task.makePngBytes();
                response.setContentLength(bytes.length);
                final OutputStream outputStream = response.getOutputStream();
                outputStream.write(bytes);
                outputStream.close();
            } else {
                task.suspendResponse();
                queue.add(task);
            }
        }
    }
}

