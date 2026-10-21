package com.gtnewhorizon.gtnhlib.client.renderer;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TessellatorOwnedCaptureTest {

    @Test
    void suspendedCaptureKeepsItsInProgressTessellationState() {
        final DirectTessellator capture = TessellatorManager.startOwned(tessellator -> false);
        try {
            capture.startDrawing(GL11.GL_POINTS);
            capture.addVertex(1.0D, 2.0D, 3.0D);
            assertEquals(1, capture.getVertexCount());

            TessellatorManager.suspend(capture);
            assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
            assertTrue(capture.isDrawing());
            assertEquals(1, capture.getVertexCount());

            TessellatorManager.resume(capture);
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw());
            assertTrue(capture.isDrawing());
            assertEquals(1, capture.getVertexCount());
        } finally {
            TessellatorManager.cancel(capture);
        }

        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
    }

    @Test
    void suspendingAndStoppingNonTopCapturesLeavesTheOtherOwnerOnTheStack() {
        final List<CallbackTessellator> firstDraws = new ArrayList<>();
        final List<CallbackTessellator> secondDraws = new ArrayList<>();
        final DirectTessellator first = TessellatorManager.startOwned(tessellator -> {
            firstDraws.add(tessellator);
            return true;
        });
        final DirectTessellator second = TessellatorManager.startOwned(tessellator -> {
            secondDraws.add(tessellator);
            return true;
        });
        boolean firstActive = true;
        boolean secondActive = true;
        try {
            TessellatorManager.suspend(first);
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw(),
                "suspending a lower stack entry leaves the top capture active");
            drawSingleVertex();
            assertEquals(1, secondDraws.size());
            assertSame(second, secondDraws.getFirst());

            TessellatorManager.resume(first);
            drawSingleVertex();
            assertEquals(1, firstDraws.size());
            assertSame(first, firstDraws.getFirst());

            TessellatorManager.stop(second);
            secondActive = false;
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw(),
                "stopping a lower stack entry leaves the top capture active");
            drawSingleVertex();
            assertEquals(2, firstDraws.size());
            assertSame(first, firstDraws.getLast());

            TessellatorManager.stop(first);
            firstActive = false;
            assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
            assertThrows(IllegalStateException.class, () -> TessellatorManager.resume(first),
                "a stopped token cannot be resumed");
            assertThrows(IllegalArgumentException.class, () -> TessellatorManager.startCapturingDirect(first),
                "a freed token cannot be reactivated through the legacy API");
        } finally {
            if (secondActive) {
                TessellatorManager.cancel(second);
            }
            if (firstActive) {
                TessellatorManager.cancel(first);
            }
        }
    }

    @Test
    void suspendedOwnedCaptureCanMoveToAnotherThread() throws InterruptedException {
        final AtomicReference<CallbackTessellator> delivered = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean captureStopped = new AtomicBoolean();
        final DirectTessellator capture = TessellatorManager.startOwned(tessellator -> {
            delivered.set(tessellator);
            return true;
        });
        TessellatorManager.suspend(capture);

        final Thread worker = new Thread(() -> {
            try {
                assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw(),
                    "the receiving thread starts without the sender's capture in its ThreadLocal stack");
                TessellatorManager.resume(capture);
                assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw());
                drawSingleVertex();
                TessellatorManager.stop(capture);
                captureStopped.set(true);
                assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                if (!captureStopped.get()) {
                    try {
                        TessellatorManager.cancel(capture);
                    } catch (RuntimeException | Error cleanupFailure) {
                        final Throwable previous = failure.get();
                        if (previous == null) {
                            failure.set(cleanupFailure);
                        } else if (previous != cleanupFailure) {
                            previous.addSuppressed(cleanupFailure);
                        }
                    }
                }
            }
        }, "owned-tessellator-capture-migration");
        worker.start();
        worker.join();

        if (failure.get() != null) {
            throw new AssertionError("Owned capture migration failed", failure.get());
        }
        assertTrue(captureStopped.get());
        assertSame(capture, delivered.get());
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
        assertThrows(IllegalStateException.class, () -> TessellatorManager.resume(capture));
    }

    @Test
    void anotherThreadCannotSuspendAnActiveCaptureOrReadItsThreadLocalStack() throws InterruptedException {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean rejected = new AtomicBoolean();
        final AtomicReference<CallbackTessellator> delivered = new AtomicReference<>();
        final DirectTessellator capture = TessellatorManager.startOwned(tessellator -> {
            delivered.set(tessellator);
            return true;
        });
        try {
            final Thread worker = new Thread(() -> {
                try {
                    TessellatorManager.suspend(capture);
                } catch (IllegalStateException expected) {
                    rejected.set(true);
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "owned-tessellator-wrong-owner");
            worker.start();
            worker.join();

            if (failure.get() != null) {
                throw new AssertionError("Wrong-thread capture access failed unexpectedly", failure.get());
            }
            assertTrue(rejected.get(), "an active token cannot mutate a different thread's capture stack");
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw());
            drawSingleVertex();
            assertSame(capture, delivered.get());
        } finally {
            TessellatorManager.stop(capture);
        }
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
    }

    @Test
    void detachedCaptureCanBeCanceledFromAnotherThreadWithoutAffectingOtherCaptures() throws InterruptedException {
        final DirectTessellator retained = TessellatorManager.startOwned(tessellator -> true);
        final DirectTessellator canceled = TessellatorManager.startOwned(tessellator -> true);
        TessellatorManager.suspend(canceled);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean canceledOnWorker = new AtomicBoolean();
        final Thread worker = new Thread(() -> {
            try {
                TessellatorManager.cancel(canceled);
                canceledOnWorker.set(true);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        }, "owned-tessellator-detached-cancel");
        worker.start();
        worker.join();

        try {
            if (failure.get() != null) {
                throw new AssertionError("Detached capture cancellation failed", failure.get());
            }
            assertTrue(canceledOnWorker.get());
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw(),
                "canceling a detached lower token leaves the current owner active");
            assertThrows(IllegalStateException.class, () -> TessellatorManager.resume(canceled));
        } finally {
            if (!canceledOnWorker.get()) {
                TessellatorManager.cancel(canceled);
            }
            TessellatorManager.stop(retained);
        }
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
    }

    @Test
    void legacyCaptureStillUsesLifoAroundAnOwnedCapture() {
        TessellatorManager.startCapturingDirect();
        DirectTessellator owned = null;
        boolean ownedActive = false;
        try {
            owned = TessellatorManager.startOwned(tessellator -> true);
            ownedActive = true;
            TessellatorManager.stop(owned);
            ownedActive = false;
            assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw(),
                "stopping the owned top capture must reveal the legacy capture below it");
        } finally {
            if (ownedActive && owned != null) {
                TessellatorManager.cancel(owned);
            }
            TessellatorManager.stopCapturingDirect();
        }
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw());
    }

    private static void drawSingleVertex() {
        final BufferBuilder buffer = new BufferBuilder(64);
        buffer.begin(GL11.GL_POINTS, new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F));
        buffer.pos(1.0D, 2.0D, 3.0D).endVertex();
        TessellatorManager.interceptBufferBuilderDraw(buffer);
    }
}
