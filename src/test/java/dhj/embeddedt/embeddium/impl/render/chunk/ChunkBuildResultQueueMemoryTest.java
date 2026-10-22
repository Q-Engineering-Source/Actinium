package dhj.embeddedt.embeddium.impl.render.chunk;

import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkSortOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBuildResultQueueMemoryTest {
    @Test
    void accountsBuildAndSortBuffersAcrossQueueTransferAndRequeue() {
        NativeBuffer.MemoryStats before = NativeBuffer.getMemoryStats();
        NativeBuffer vertexBuffer = new NativeBuffer(32);
        NativeBuffer indexBuffer = new NativeBuffer(8);
        NativeBuffer sortIndexBuffer = new NativeBuffer(12);

        var buildMeshes = new Reference2ReferenceOpenHashMap<TerrainRenderPass, BuiltSectionMeshParts>();
        buildMeshes.put(null, new BuiltSectionMeshParts(vertexBuffer, indexBuffer, null, Map.of()));
        ChunkBuildOutput buildOutput = new ChunkBuildOutput(null, null, buildMeshes, 0);

        var sortMeshes = new Reference2ReferenceOpenHashMap<TerrainRenderPass, ChunkSortOutput.SortedMesh>();
        sortMeshes.put(null, new ChunkSortOutput.SortedMesh(sortIndexBuffer));
        ChunkSortOutput sortOutput = new ChunkSortOutput(null, 0, sortMeshes);

        var queue = new ChunkBuildResultQueue();
        var buildResult = new ChunkJobResult.Success<>(buildOutput, 1L);
        var sortResult = new ChunkJobResult.Success<>(sortOutput, 1L);

        try {
            queue.add(buildResult);
            queue.add(sortResult);

            ChunkBuildResultQueue.MemoryStats queued = queue.getMemoryStats();
            assertEquals(2, queued.resultCount());
            assertEquals(52, queued.nativeBytes());
            assertEquals(2, queued.peakResultCount());
            assertEquals(52, queued.peakNativeBytes());

            assertInstanceOf(ChunkJobResult.Success.class, queue.poll());
            assertEquals(1, queue.getMemoryStats().resultCount());
            assertEquals(12, queue.getMemoryStats().nativeBytes());

            queue.add(buildResult);
            assertEquals(2, queue.getMemoryStats().resultCount());
            assertEquals(52, queue.getMemoryStats().nativeBytes());

            assertInstanceOf(ChunkJobResult.Success.class, queue.poll());
            assertInstanceOf(ChunkJobResult.Success.class, queue.poll());
            assertEquals(0, queue.getMemoryStats().resultCount());
            assertEquals(0, queue.getMemoryStats().nativeBytes());

            var failure = new ChunkJobResult.Failure<ChunkTaskOutput>(new IllegalStateException("expected"));
            queue.add(failure);
            assertEquals(1, queue.getMemoryStats().resultCount());
            assertEquals(0, queue.getMemoryStats().nativeBytes());
            queue.poll();
            assertEquals(0, queue.getMemoryStats().resultCount());
        } finally {
            buildOutput.delete();
            sortOutput.delete();
        }

        NativeBuffer.MemoryStats after = NativeBuffer.getMemoryStats();
        assertEquals(before.liveBytes(), after.liveBytes());
        assertEquals(before.liveBlocks(), after.liveBlocks());
        assertEquals(before.trackedBuffers(), after.trackedBuffers());
    }

    @Test
    void retainsExactSettledCountsWithMultipleProducersAndConsumers() throws InterruptedException {
        int producerCount = 4;
        int consumerCount = 3;
        int resultsPerProducer = 512;
        int expectedResults = producerCount * resultsPerProducer;

        var queue = new ChunkBuildResultQueue();
        var failureResult = new ChunkJobResult.Failure<ChunkTaskOutput>(new IllegalStateException("expected"));
        var producersDone = new CountDownLatch(producerCount);
        var consumedResults = new AtomicInteger();
        var workerFailure = new AtomicReference<Throwable>();
        var consumers = new Thread[consumerCount];
        var producers = new Thread[producerCount];

        for (int index = 0; index < consumerCount; index++) {
            consumers[index] = new Thread(() -> {
                try {
                    while (producersDone.getCount() != 0 || queue.getMemoryStats().resultCount() != 0) {
                        if (queue.poll() != null) {
                            consumedResults.incrementAndGet();
                        } else {
                            Thread.yield();
                        }
                    }
                } catch (Throwable throwable) {
                    workerFailure.compareAndSet(null, throwable);
                }
            }, "chunk-result-consumer-test-" + index);
        }

        for (int index = 0; index < producerCount; index++) {
            producers[index] = new Thread(() -> {
                try {
                    for (int result = 0; result < resultsPerProducer; result++) {
                        queue.add(failureResult);
                    }
                } catch (Throwable throwable) {
                    workerFailure.compareAndSet(null, throwable);
                } finally {
                    producersDone.countDown();
                }
            }, "chunk-result-producer-test-" + index);
        }

        for (Thread consumer : consumers) {
            consumer.start();
        }
        for (Thread producer : producers) {
            producer.start();
        }
        for (Thread producer : producers) {
            producer.join();
        }
        for (Thread consumer : consumers) {
            consumer.join();
        }

        if (workerFailure.get() != null) {
            throw new AssertionError("Concurrent queue worker failed", workerFailure.get());
        }

        ChunkBuildResultQueue.MemoryStats settled = queue.getMemoryStats();
        assertEquals(expectedResults, consumedResults.get());
        assertEquals(0, settled.resultCount());
        assertEquals(0, settled.nativeBytes());
        assertTrue(settled.peakResultCount() > 0);
        assertTrue(settled.peakResultCount() <= expectedResults);
        assertEquals(0, settled.peakNativeBytes());
    }
}
