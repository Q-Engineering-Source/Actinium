package net.coderbot.iris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PrecompileQueueTest {
    @AfterEach
    void stopQueue() {
        PrecompileQueue.cancel();
    }

    @Test
    void startExposesTheFirstJobToTheScreenBeforeCompilation() {
        PrecompileQueue.Job job = new PrecompileQueue.Job("pack", "world0", "world0");

        PrecompileQueue.start(List.of(job));

        assertSame(job, PrecompileQueue.currentJob());
    }

    @Test
    void parentProgressAggregatesFolderProgress() {
        PrecompileQueue.Job first = new PrecompileQueue.Job("a", "world0", "world0");
        PrecompileQueue.Job second = new PrecompileQueue.Job("a", "world1", "world1");
        PrecompileQueue.Job third = new PrecompileQueue.Job("b", "world0", "world0");

        PrecompileQueue.start(List.of(first, second, third));

        assertEquals(1.0F / 3.0F, PrecompileQueue.overallProgress(), 0.0001F);
        assertEquals(0.5F, PrecompileQueue.packProgress("a"), 0.0001F);
        assertEquals(0.0F, PrecompileQueue.packProgress("b"), 0.0001F);
        assertEquals(1.0F, PrecompileQueue.folderProgress("a", "world0"), 0.0001F);
        assertEquals(0.0F, PrecompileQueue.folderProgress("a", "world1"), 0.0001F);
    }
}
