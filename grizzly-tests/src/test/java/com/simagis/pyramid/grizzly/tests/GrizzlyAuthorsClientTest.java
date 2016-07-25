package com.simagis.pyramid.grizzly.tests;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.*;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GrizzlyAuthorsClientTest {
    private static final Logger logger = Grizzly.logger(GrizzlyAuthorsClientTest.class);

    public static void main(String[] args) throws IOException, URISyntaxException {
        // Check command line parameters
        if (args.length < 1) {
            System.out.println("To download the resource, please run: Client <url>");
            System.exit(0);
        }

        final String url = args[0];

        // Parse passed URL
        final URI uri = new URI(url);
        final String host = uri.getHost();
        final int port = uri.getPort() > 0 ? uri.getPort() : 80;

        final FutureImpl<String> completeFuture = SafeFutureImpl.create();

        // Build HTTP client filter chain
        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        // Add transport filter
        clientFilterChainBuilder.add(new TransportFilter());
        // Add HttpClientFilter, which transforms Buffer <-> HttpContent
        clientFilterChainBuilder.add(new HttpClientFilter());
        // Add HTTP client download filter, which is responsible for downloading
        // HTTP resource asynchronously
        clientFilterChainBuilder.add(new ClientDownloadFilter(uri, completeFuture));

        // Initialize Transport
        final TCPNIOTransport transport =
            TCPNIOTransportBuilder.newInstance().build();
        // Set filterchain as a Transport Processor
        transport.setProcessor(clientFilterChainBuilder.build());

        try {
            // start the transport
            transport.start();

            Connection connection = null;

            // Connecting to a remote Web server
            Future<Connection> connectFuture = transport.connect(host, port);
            try {
                // Wait until the client connect operation will be completed
                // Once connection will be established - downloading will
                // start @ ClientDownloadFilter.onConnect(...)
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                // Wait until download will be completed
                String filename = completeFuture.get();
                logger.log(Level.INFO, "File " + filename + " was successfully downloaded");
            } catch (Exception e) {
                if (connection == null) {
                    logger.log(Level.WARNING, "Can not connect to the target resource");
                } else {
                    logger.log(Level.WARNING, "Error downloading the resource");
                }
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.close();
                }
            }
        } finally {
            logger.info("Stopping transport...");
            // stop the transport
            transport.shutdownNow();

            logger.info("Stopped transport...");
        }
    }

    static class ClientDownloadFilter extends BaseFilter {
        private final static Logger logger = Grizzly.logger(ClientDownloadFilter.class);

        // URI of a remote resource
        private final URI uri;
        // local filename, where content will be saved
        private final String fileName;

        // Download completion future
        private FutureImpl<String> completeFuture;

        // local file channel, where we save resource content
        private volatile FileChannel output;
        // number of bytes downloaded
        private volatile int bytesDownloaded;

        private final String resourcePath;

        /**
         * <tt>ClientDownloadFilter</tt> constructor
         *
         * @param uri {@link URI} of a remote resource to download
         * @param completeFuture download completion handler ({@link FutureImpl})
         */
        public ClientDownloadFilter(URI uri, FutureImpl<String> completeFuture) {
            this.uri = uri;

            // Extracting resource path
            resourcePath =
                uri.getPath().trim().length() > 0 ? uri.getPath().trim() : "/";

            int lastSlashIdx = resourcePath.lastIndexOf('/');
            if (lastSlashIdx != -1 && lastSlashIdx < resourcePath.length() - 1) {
                // if the path contains a filename - take it as local filename
                fileName = resourcePath.substring(lastSlashIdx + 1);
            } else {
                // if the path doesn't contain filename - we will use default filename
                fileName = "download#" + System.currentTimeMillis() + ".txt";
            }

            this.completeFuture = completeFuture;
        }

        /**
         * The method is called, when a client connection gets connected to a web
         * server.
         * When this method gets called by a framework - it means that client connection
         * has been established and we can send HTTP request to the web server.
         *
         * @param ctx Client connect processing context
         *
         * @return {@link NextAction}
         * @throws IOException
         */
        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL of the
            // resource we want to download
            final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                .uri(resourcePath).protocol(Protocol.HTTP_1_1)
                .header("Host", uri.getHost()).build();
            logger.log(Level.INFO, "Connected... Sending the request: {0}", httpRequest);

            // Write the request asynchronously
            ctx.write(httpRequest);

            // Return the stop action, which means we don't expect next filter to process
            // connect event
            return ctx.getStopAction();
        }

        /**
         * The method is called, when we receive a {@link HttpContent} from a server.
         * Once we receive one - we save the content chunk to a local file.
         *
         * @param ctx Request processing context
         *
         * @return {@link NextAction}
         * @throws IOException
         */
        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            try {
                // Cast message to a HttpContent
                final HttpContent httpContent = ctx.getMessage();

                logger.log(Level.INFO, "Got HTTP response chunk");
                if (output == null) {
                    // If local file wasn't created - create it
                    logger.log(Level.INFO, "HTTP response: {0}", httpContent.getHttpHeader());
                    logger.log(Level.INFO, "Create a file: {0}", fileName);
                    FileOutputStream fos = new FileOutputStream(fileName);
                    output = fos.getChannel();
                }

                // Get HttpContent's Buffer
                final Buffer buffer = httpContent.getContent();

                logger.log(Level.INFO, "HTTP content size: {0}", buffer.remaining());
                if (buffer.remaining() > 0) {
                    bytesDownloaded += buffer.remaining();

                    // save Buffer to a local file, represented by FileChannel
                    ByteBuffer byteBuffer = buffer.toByteBuffer();
                    do {
                        output.write(byteBuffer);
                    } while (byteBuffer.hasRemaining());

                    // Dispose a content buffer
                    buffer.dispose();
                }

                if (httpContent.isLast()) {
                    // it's last HttpContent - we close the local file and
                    // notify about download completion
                    logger.log(Level.INFO, "Downloaded done: {0} bytes", bytesDownloaded);
                    completeFuture.result(fileName);
                    close();
                }
            } catch (IOException e) {
                close();
            }

            // Return stop action, which means we don't expect next filter to process
            // read event
            return ctx.getStopAction();
        }

        /**
         * The method is called, when the client connection will get closed.
         * Intercepting this method let's use release resources, like local FileChannel,
         * if it wasn't released before.
         *
         * @param ctx Request processing context
         *
         * @return {@link NextAction}
         * @throws IOException
         */
        @Override
        public NextAction handleClose(FilterChainContext ctx) throws IOException {
            close();
            return ctx.getStopAction();
        }

        /**
         * Method closes the local file channel, and if download wasn't completed -
         * notify {@link FutureImpl} about download failure.
         *
         * @throws IOException If failed to close <em>localOutput</em>.
         */
        private void close() throws IOException {
            final FileChannel localOutput = this.output;
            // close the local file channel
            if (localOutput != null) {
                localOutput.close();
            }

            if (!completeFuture.isDone()) {
                //noinspection ThrowableInstanceNeverThrown
                completeFuture.failure(new IOException("Connection was closed"));
            }
        }
    }
}
