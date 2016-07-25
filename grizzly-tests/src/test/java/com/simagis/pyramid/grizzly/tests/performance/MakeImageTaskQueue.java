package com.simagis.pyramid.grizzly.tests.performance;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Created by Daniel on 21/01/2016.
 */
class MakeImageTaskQueue {
    private static final int QUEUE_MAX_SIZE = 100;

    private LinkedBlockingDeque<MakeImageTask> queue = new LinkedBlockingDeque<>(QUEUE_MAX_SIZE);
    private volatile boolean cleanPossible = true;

    public MakeImageTaskQueue() {
    }

    public void add(MakeImageTask task) {
        synchronized (this) {
            cleanPossible = false;
        }
        try {
            if (!queue.offer(task)) {
                throw new IllegalStateException("Task queue is full");
            }
        } finally {
            synchronized (this) {
                cleanPossible = true;
                notifyAll();
            }
        }
    }

    public MakeImageTask take() throws InterruptedException {
        synchronized (this) {
            cleanPossible = false;
        }
        try {
            return queue.take();
        } finally {
            synchronized (this) {
                cleanPossible = true;
                notifyAll();
            }
        }
    }

    public void cleanObsolete() {
        synchronized (this) {
            while (!cleanPossible) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (Iterator<MakeImageTask> iterator = queue.iterator(); iterator.hasNext(); ) {
                final MakeImageTask task = iterator.next();
                if (task.isObsolete()) {
                    task.cancelTask();
                    iterator.remove();
                }
            }
        }
    }
}
