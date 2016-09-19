package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class GrizzlyServerTest {
    static {
        System.setProperty(
            org.glassfish.grizzly.http.util.Constants.class.getName() + ".default-character-encoding", "UTF-8");
        // - necessary to provide correct parsing GET and POST parameters, when encoding is not specified
        // (typical situation for POST, always for GET)
    }

    public static void main(String[] args) throws IOException {
        final boolean ssl = args.length >= 1 && args[0].equalsIgnoreCase("ssl");

        HttpServer server = new HttpServer();
        server.addListener(new NetworkListener("grizzy82", "localhost", 82));
        if (ssl) {
            final NetworkListener sslListener = new NetworkListener("grizzySSL", "localhost", 9999);
            sslListener.setSecure(true);
            sslListener.setSSLEngineConfig(createSslConfiguration());
            server.addListener(sslListener);
        }
        final ServerConfiguration configuration = server.getServerConfiguration();
        configuration.addHttpHandler(new StaticHttpHandler("/LiveTms"), "/LiveTms");
        // - only files, not directories
        configuration.addHttpHandler(
            new HttpHandler() {
                public void service(Request request, final Response response) throws Exception {
                    final SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                    System.out.println("Processing " + request.getRequestURI() + " - " + request.getRequestURL());
                    System.out.println("  method: " + request.getMethod());
                    System.out.println("  port: " + request.getServerPort());
                    System.out.println("  remote port: " + request.getRemotePort());
                    System.out.println("  path info: " + request.getPathInfo());
                    System.out.println("  context: " + request.getContextPath());
                    System.out.println("  encoding: " + request.getCharacterEncoding());
                    System.out.println("  query: " + request.getQueryString());
                    for (String name : request.getParameterNames()) {
                        System.out.printf("%s: %s%n", name, request.getParameter(name));
                    }
                    System.out.println("  headers:");
                    for (String headerName : request.getHeaderNames()) {
                        for (String headerValue : request.getHeaders(headerName)) {
                            System.out.printf("    %s=%s%n", headerName, headerValue);
                        }
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
                                        final int firstPortion = Math.min(25, bytes.length);
                                        response.setContentLength(result.length());
                                        outputStream.write(Arrays.copyOfRange(bytes, 0, firstPortion));
                                        outputStream.flush();
                                        System.out.printf("Sending %d bytes...%n", firstPortion);
                                        for (long t = System.currentTimeMillis();
                                             System.currentTimeMillis() - t < 20000; ) {
                                        }
                                        System.out.printf("Sending %d bytes...%n", bytes.length - firstPortion);
                                        outputStream.write(Arrays.copyOfRange(bytes, firstPortion, bytes.length));
                                        outputStream.close();
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
                                        System.out.println("ERROR!");
                                        t.printStackTrace();
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

    private static SSLEngineConfigurator createSslConfiguration() throws FileNotFoundException {
        // Initialize SSLContext configuration
        SSLContextConfigurator sslContextConfig = new SSLContextConfigurator();

        ClassLoader cl = GrizzlyServerTest.class.getClassLoader();
        // Set key store
        final String keystrokeFile = "ssl-test-keystore.jks";
        URL keystoreUrl = cl.getResource(keystrokeFile);
        if (keystoreUrl == null) {
            throw new FileNotFoundException("Keystroke file " + keystrokeFile + " not found");
        }
        sslContextConfig.setKeyStoreFile(keystoreUrl.getFile());
        sslContextConfig.setKeyStorePass("changeit");


        // Create SSLEngine configurator
        return new SSLEngineConfigurator(sslContextConfig.createSSLContext(),
            false, false, false);
    }
}
