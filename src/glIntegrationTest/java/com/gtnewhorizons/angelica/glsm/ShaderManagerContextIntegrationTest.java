package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderManagerContextIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void primaryAndWorkerCaptureCurrentAttributesAtTheSameDepth() throws Exception {
        final int entryDepth = GLStateManager.getAttribDepth();
        final CountDownLatch workerReady = new CountDownLatch(1);
        final CountDownLatch allowWorkerModify = new CountDownLatch(1);
        final CountDownLatch workerModified = new CountDownLatch(1);
        final CountDownLatch allowWorkerPop = new CountDownLatch(1);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ShaderManager.setCurrentNormal(0.0f, 0.0f, 1.0f);
            ShaderManager.setCurrentTexCoord(0.0f, 0.0f, 0.0f, 1.0f);
            GLStateManager.glPushAttrib(GL11.GL_CURRENT_BIT);
            ShaderManager.setCurrentNormal(1.0f, 2.0f, 3.0f);
            ShaderManager.setCurrentTexCoord(1.0f, 2.0f, 3.0f, 4.0f);

            final Future<float[]> workerRestored = pool.submit(() -> {
                GLStateManager.enterWorkerContext();
                try {
                    GLStateManager.glPushAttrib(GL11.GL_CURRENT_BIT);
                    workerReady.countDown();
                    assertTrue(allowWorkerModify.await(10, TimeUnit.SECONDS));
                    ShaderManager.setCurrentNormal(4.0f, 5.0f, 6.0f);
                    ShaderManager.setCurrentTexCoord(5.0f, 6.0f, 7.0f, 8.0f);
                    workerModified.countDown();
                    assertTrue(allowWorkerPop.await(10, TimeUnit.SECONDS));
                    GLStateManager.glPopAttrib();
                    return new float[] {
                        ShaderManager.getCurrentNormal().x,
                        ShaderManager.getCurrentNormal().y,
                        ShaderManager.getCurrentNormal().z,
                        ShaderManager.getCurrentTexCoord().x,
                        ShaderManager.getCurrentTexCoord().y,
                        ShaderManager.getCurrentTexCoord().z,
                        ShaderManager.getCurrentTexCoord().w
                    };
                } finally {
                    while (GLStateManager.getAttribDepth() > 0) {
                        GLStateManager.popState();
                    }
                    GLStateManager.exitWorkerContext();
                }
            });

            try {
                assertTrue(workerReady.await(10, TimeUnit.SECONDS));
                allowWorkerModify.countDown();
                assertTrue(workerModified.await(10, TimeUnit.SECONDS));
                GLStateManager.glPopAttrib();
                assertEquals(0.0f, ShaderManager.getCurrentNormal().x, 0.0001f);
                assertEquals(0.0f, ShaderManager.getCurrentNormal().y, 0.0001f);
                assertEquals(1.0f, ShaderManager.getCurrentNormal().z, 0.0001f);
                assertEquals(0.0f, ShaderManager.getCurrentTexCoord().x, 0.0001f);
                assertEquals(1.0f, ShaderManager.getCurrentTexCoord().w, 0.0001f);
            } finally {
                allowWorkerModify.countDown();
                allowWorkerPop.countDown();
            }

            final float[] restored = workerRestored.get(10, TimeUnit.SECONDS);
            assertEquals(1.0f, restored[0], 0.0001f);
            assertEquals(2.0f, restored[1], 0.0001f);
            assertEquals(3.0f, restored[2], 0.0001f);
            assertEquals(1.0f, restored[3], 0.0001f);
            assertEquals(2.0f, restored[4], 0.0001f);
            assertEquals(3.0f, restored[5], 0.0001f);
            assertEquals(4.0f, restored[6], 0.0001f);
        } finally {
            allowWorkerModify.countDown();
            allowWorkerPop.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            ShaderManager.setCurrentNormal(0.0f, 0.0f, 1.0f);
            ShaderManager.setCurrentTexCoord(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }
}
