package net.coderbot.iris.gl.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiListenerNotifierTest {
    @Test
    void notifiesEveryListenerInOrderAcrossCapacityGrowth() {
        MultiListenerNotifier notifier = new MultiListenerNotifier();
        List<Integer> notifications = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int index = i;
            notifier.setListener(() -> notifications.add(index));
        }

        notifier.run();

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), notifications);
    }

    @Test
    void repeatedRegistrationUsesListenerIdentityAndClearAllowsReactivation() {
        MultiListenerNotifier notifier = new MultiListenerNotifier();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        for (int i = 0; i < 100; i++) {
            notifier.setListener(listener);
        }
        notifier.run();
        assertEquals(1, notifications.get());

        notifier.setListener(null);
        notifier.run();
        assertEquals(1, notifications.get());

        notifier.setListener(listener);
        notifier.run();
        assertEquals(2, notifications.get());
    }

    @Test
    void clearingDuringNotificationStopsOldProgramBeforeDispatchingItsRemainingListeners() {
        MultiListenerNotifier notifier = new MultiListenerNotifier();
        List<String> notifications = new ArrayList<>();
        notifier.setListener(() -> {
            notifications.add("old-first");
            notifier.setListener(null);
            notifier.setListener(() -> notifications.add("new-program"));
        });
        notifier.setListener(() -> notifications.add("old-second"));

        notifier.run();
        assertEquals(List.of("old-first"), notifications);

        notifier.run();
        assertEquals(List.of("old-first", "new-program"), notifications);
    }
}
