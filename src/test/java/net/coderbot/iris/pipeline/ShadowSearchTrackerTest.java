package net.coderbot.iris.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowSearchTrackerTest {
    @Test
    void unchangedSubmissionCountLeavesNoSearchToConsume() {
        ShadowSearchTracker tracker = new ShadowSearchTracker();

        tracker.recordSubmissions(7, 7, 0.42f);

        assertEquals(0.75f, tracker.angleForFrame(0.75f));
    }

    @Test
    void submittedSearchIsConsumedOnceAtTheAngleItWasSearchedWith() {
        ShadowSearchTracker tracker = new ShadowSearchTracker();

        tracker.recordSubmissions(7, 8, 0.42f);

        assertEquals(0.42f, tracker.angleForFrame(0.75f));
        assertEquals(0.75f, tracker.angleForFrame(0.75f));
    }

    @Test
    void latestSubmissionBeforeTheConsumingFrameWins() {
        ShadowSearchTracker tracker = new ShadowSearchTracker();

        tracker.recordSubmissions(7, 8, 0.42f);
        tracker.recordSubmissions(8, 9, 0.47f);

        assertEquals(0.47f, tracker.angleForFrame(0.75f));
        assertEquals(0.75f, tracker.angleForFrame(0.75f));
    }
}
