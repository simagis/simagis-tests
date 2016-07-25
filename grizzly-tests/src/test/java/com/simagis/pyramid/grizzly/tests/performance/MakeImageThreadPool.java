package com.simagis.pyramid.grizzly.tests.performance;

import java.util.logging.Logger;

/**
 * Created by Daniel on 21/01/2016.
 */
class MakeImageThreadPool {
    private static final Logger LOG = Logger.getLogger(MakeImageTask.class.getName());

    private final Thread[] threads;
    private final Thread cleaningThread;
    private final MakeImageTaskQueue queue;
    private volatile boolean shutdown = false;

    MakeImageThreadPool(int poolSize, MakeImageTaskQueue queue) {
        this.queue = queue;
        this.threads = new Thread[poolSize];
        for (int k = 0; k < threads.length; k++) {
            threads[k] = new MakeImageThread();
            threads[k].start();
            LOG.info("Starting make-image thread #" + k);
        }
        this.cleaningThread = new CleaningThread();
        this.cleaningThread.start();
    }

    public void shutdown() {
        this.shutdown = true;
    }

    class MakeImageThread extends Thread {
        @Override
        public void run() {
            while (!shutdown) {
                try {
                    LOG.fine("Waiting for new task...");
                    final MakeImageTask task = queue.take();
                    LOG.fine("Taking " + task);
                    task.perform();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    class CleaningThread extends Thread {
        @Override
        public void run() {
            while (!shutdown) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // nothing to do
                }
//                LOG.info("Checking tasks to cancel...");
                queue.cleanObsolete();
            }
        }
    }
}

