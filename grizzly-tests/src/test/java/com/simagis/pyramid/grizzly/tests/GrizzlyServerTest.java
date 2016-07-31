package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GrizzlyServerTest {
    public static void main(String[] args) throws IOException {
        HttpServer server = new HttpServer();
        server.addListener(new NetworkListener("grizzy81", "localhost", 81));
        server.addListener(new NetworkListener("grizzy82", "localhost", 82));
        final ServerConfiguration configuration = server.getServerConfiguration();
        configuration.addHttpHandler(new StaticHttpHandler("/LiveTms"), "/LiveTms");
        // - only files, not directories
        configuration.addHttpHandler(
            new HttpHandler() {
                public void service(Request request, final Response response) throws Exception {
                    final SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                    System.out.println("Processing " + request.getRequestURI() + " - " + request.getRequestURL());
                    System.out.println("method: " + request.getMethod());
                    System.out.println("port: " + request.getServerPort());
                    System.out.println("remote port: " + request.getRemotePort());
                    System.out.println("path info: " + request.getPathInfo());
                    System.out.println("context: " + request.getContextPath());
                    System.out.println("query: " + request.getQueryString());
                    for (String name : request.getParameterNames()) {
                        System.out.printf("%s: %s%n", name, request.getParameter(name));
                    }
                    System.out.println("input buffer: " + request.getInputBuffer().getBuffer());
                    final String result = format.format(new Date(System.currentTimeMillis()))
                        + " at " + request.getRequestURI();
                    response.setContentType("text/plain");
                    response.suspend();
                    new Thread() {
                        @Override
                        public void run() {
                            for (long t = System.currentTimeMillis(); System.currentTimeMillis() - t < 5000; ) {
                            }
                            final NIOOutputStream outputStream = response.getNIOOutputStream();
                            outputStream.notifyCanWrite(
                                new WriteHandler() {
                                    @Override
                                    public void onWritePossible() throws Exception {
                                        final byte[] bytes = result.getBytes();
                                        response.setContentLength(result.length());
                                        outputStream.write(bytes);
                                        outputStream.close();
                                        System.out.printf("Sending %d bytes...%n", bytes.length);
                                        if (response.isSuspended()) {
                                            System.out.println("Resumed");
                                            response.resume();
                                        } else {
                                            System.out.println("Finished");
                                            response.finish();
                                        }
                                    }

                                    @Override
                                    public void onError(Throwable t) {

                                    }
                                });
                        }
                    }.start();
                }
            },
            "/");
        server.start();
        System.out.println("Press ENTER to stop the server...");
        System.in.read();
        server.shutdown();
    }
}
