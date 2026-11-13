package com.gtnewhorizons.angelica.sdlgpu.frame;

import com.gtnewhorizons.angelica.glsm.backend.MainThreadPump;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Serializes presentation and lets the window thread execute its own queued presentation before waiting. */
public final class Presenter {
    /** Draws one immutable presentation request after the window event pump finishes. */
    @FunctionalInterface
    interface PresentationTarget {
        /** Copies a completed render target into the current swapchain image. */
        void present(long texture, int width, int height, int flipMode);
    }

    private final PresentationTarget target;
    private final Executor windowThreadExecutor;
    private final MainThreadPump pump;
    private final BooleanSupplier isWindowThread;
    private final Semaphore available = new Semaphore(1);
    private volatile PendingTask pending;

    public Presenter(FrameManager frameManager, Executor executor, MainThreadPump pump, BooleanSupplier isWindowThread) {
        this(frameManager::presentOnWindowThread, executor, pump, isWindowThread);
    }

    Presenter(PresentationTarget target, Executor executor, MainThreadPump pump, BooleanSupplier isWindowThread) {
        this.target = target;
        this.windowThreadExecutor = executor;
        this.pump = pump;
        this.isWindowThread = isWindowThread;
    }

    public void requestPresent(long texture, int width, int height, int flipMode) {
        acquireSlot();
        submit(texture, width, height, flipMode);
    }

    public boolean tryRequestPresent(long texture, int width, int height, int flipMode) {
        if (!available.tryAcquire()) return false;
        submit(texture, width, height, flipMode);
        return true;
    }

    private void acquireSlot() {
        if (!isWindowThread.getAsBoolean()) {
            available.acquireUninterruptibly();
            return;
        }
        while (!available.tryAcquire()) {
            final PendingTask task = pending;
            if (task == null) {
                Thread.onSpinWait();
            } else if (task.runningThread == Thread.currentThread()) {
                throw new IllegalStateException("Recursive SDL GPU presentation request");
            } else if (!task.claimed.get()) {
                task.run();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void submit(long texture, int width, int height, int flipMode) {
        final PendingTask task = new PendingTask(texture, width, height, flipMode);
        try {
            pump.beginPresent();
        } catch (RuntimeException | Error failure) {
            available.release();
            throw failure;
        }
        pending = task;
        try {
            windowThreadExecutor.execute(task);
        } catch (RuntimeException | Error failure) {
            task.cancel();
            throw failure;
        }
    }

    public void drain() {
        final PendingTask task = pending;
        if (task != null && task.runningThread == Thread.currentThread()) return;
        acquireSlot();
        available.release();
    }

    private final class PendingTask implements Runnable {
        private final long texture;
        private final int width;
        private final int height;
        private final int flipMode;
        private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile Thread runningThread;

        private PendingTask(long texture, int width, int height, int flipMode) {
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.flipMode = flipMode;
        }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            final Thread thread = Thread.currentThread();
            runningThread = thread;
            final ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(classLoader);
            try {
                pump.runPresentPump();
                target.present(texture, width, height, flipMode);
            } finally {
                thread.setContextClassLoader(previous);
                runningThread = null;
                complete();
            }
        }

        private void cancel() {
            if (claimed.compareAndSet(false, true)) complete();
        }

        private void complete() {
            try {
                pump.endPresent();
            } finally {
                pending = null;
                available.release();
            }
        }
    }
}
