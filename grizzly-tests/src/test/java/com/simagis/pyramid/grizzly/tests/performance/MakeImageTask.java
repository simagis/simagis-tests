package com.simagis.pyramid.grizzly.tests.performance;

import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.server.Response;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by Daniel on 21/01/2016.
 */
class MakeImageTask {
    private static final Logger LOG = Logger.getLogger(MakeImageTask.class.getName());
    private static final int TIMEOUT = 7000; // ms
    private static final boolean USE_TIMEOUT_FOR_RESPONSE = true;
    private static final int CHUNK_SIZE = 65536;

    private final int width;
    private final int height;
    private final Color color;
    private final Response response;
    private final long startTime;
    private byte[] responseBytes;
    private int responseBytesCurrentOffset;

    MakeImageTask(int width, int height, Color color, Response response) {
        this.width = width;
        this.height = height;
        this.color = color;
        this.response = response;
        this.startTime = System.currentTimeMillis();
        response.setContentType("image/png");
    }

    public void perform() {
        this.responseBytes = makePngBytes();
        this.responseBytesCurrentOffset = 0;
        response.setContentLength(responseBytes.length);
        final NIOOutputStream outputStream = response.getNIOOutputStream();
        outputStream.notifyCanWrite(
            new WriteHandler() {
                @Override
                public void onWritePossible() throws IOException {
                    if (!response.isSuspended()) {
                        LOG.log(Level.WARNING, "Timeout: response was resumed/cancelled and finished");
                        return;
                    }
                    final int len = Math.min(responseBytes.length - responseBytesCurrentOffset, CHUNK_SIZE);
                    if (len > 0) {
                        outputStream.write(responseBytes, responseBytesCurrentOffset, len);
                        responseBytesCurrentOffset += len;
                    }
//                    System.out.printf("%d/%d bytes sent...%n", responseBytesCurrentOffset, responseBytes.length);
                    if (responseBytesCurrentOffset >= responseBytes.length) {
                        close();
                    } else {
                        outputStream.notifyCanWrite(this);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    response.setStatus(500, t.getMessage());
                    try {
                        close();
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Error while closing output stream", e);
                    }
                }

                private void close() throws IOException {
                    outputStream.close();
                    closeResponse();
                }
            });
    }

    public void suspendResponse() {
        if (USE_TIMEOUT_FOR_RESPONSE) {
            response.suspend(TIMEOUT, TimeUnit.MILLISECONDS, new EmptyCompletionHandler<Response>() {
                @Override
                public void cancelled() {
                    LOG.log(Level.WARNING, "Response was cancelled");
                }

                @Override
                public void failed(Throwable throwable) {
                    LOG.log(Level.SEVERE, "Response was failed");
                }
            });
        } else {
            response.suspend();
        }
    }

    public boolean isObsolete() {
        return System.currentTimeMillis() - startTime > TIMEOUT;
    }

    public void cancelTask() {
        LOG.info("Cancelling " + this);
        if (response.isSuspended()) {
            response.setStatus(500, "Task cancelled");
        }
        closeResponse();
    }

    @Override
    public String toString() {
        return "MakeImageTask{" + "width=" + width + ", height=" + height + ", color=" + color + '}';
    }

    void closeResponse() {
        if (response.isSuspended()) {
            LOG.config("Resumed");
            response.resume();
        } else {
            LOG.info("Finished");
            response.finish();
        }
    }

    byte[] makePngBytes() {
        System.out.printf("Making image for color %s...%n", color);
        long t1 = System.nanoTime();
        final BufferedImage image = makeImage();
        long t2 = System.nanoTime();
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", byteStream);
            byteStream.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        long t3 = System.nanoTime();
        LOG.info(String.format(Locale.US,
            "Image made in %.3f ms and converted to PNG in %.3f ms "
                + "(%d bytes, %d active threads)%n",
            (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
            byteStream.size(), ServerManyImagesPerformanceTest.USED_CPU_COUNT));
        return byteStream.toByteArray();
    }

    private BufferedImage makeImage() {
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(0);
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                double mult = rnd.nextDouble();
                result.setRGB(x, y, new Color(
                    (int) (color.getRed() * mult),
                    (int) (color.getGreen() * mult),
                    (int) (color.getBlue() * mult)).getRGB());
            }
        }
        return result;
    }
}
