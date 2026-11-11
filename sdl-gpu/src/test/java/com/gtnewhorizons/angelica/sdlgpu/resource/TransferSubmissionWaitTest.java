package com.gtnewhorizons.angelica.sdlgpu.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class TransferSubmissionWaitTest {
    @Test
    void interruptionPreservesUploadOrderingAndIsRestoredAfterSubmission() throws InterruptedException {
        final Object monitor = new Object();
        final AtomicLong submitted = new AtomicLong();
        final AtomicBoolean returned = new AtomicBoolean();
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        final CountDownLatch checked = new CountDownLatch(1);
        final CountDownLatch rechecked = new CountDownLatch(1);
        final Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            TransferThread.awaitSubmission(monitor, () -> {
                if (checked.getCount() != 0) checked.countDown();
                else rechecked.countDown();
                return submitted.get();
            }, 7L);
            returned.set(true);
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        checked.await();
        rechecked.await();
        assertFalse(returned.get());
        synchronized (monitor) {
            submitted.set(6L);
            monitor.notifyAll();
        }
        assertFalse(returned.get());
        synchronized (monitor) {
            submitted.set(7L);
            monitor.notifyAll();
        }
        waiter.join();
        assertTrue(returned.get());
        assertTrue(interruptRestored.get());
    }
}
