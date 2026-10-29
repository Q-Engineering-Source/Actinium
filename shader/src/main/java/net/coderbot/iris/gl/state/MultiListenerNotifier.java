package net.coderbot.iris.gl.state;

import java.util.Arrays;

/** Delivers a state change to every active uniform or sampler using that state. */
public final class MultiListenerNotifier implements ValueUpdateNotifier {
    private Runnable[] listeners = new Runnable[4];
    private int count;
    private int generation;

    @Override
    public void setListener(Runnable listener) {
        if (listener == null) {
            // Release inactive programs as well as stopping their notifications.
            Arrays.fill(listeners, 0, count, null);
            count = 0;
            generation++;
            return;
        }
        for (int i = 0; i < count; i++) {
            if (listeners[i] == listener) {
                return;
            }
        }
        if (count == listeners.length) {
            listeners = Arrays.copyOf(listeners, count << 1);
        }
        listeners[count++] = listener;
    }

    /**
     * Removes one program's registration by listener identity, keeping every other program's registration and
     * the registration order. The generation bump stops a notification that is already running from
     * continuing over the compacted array.
     */
    @Override
    public void removeListener(Runnable listener) {
        for (int i = 0; i < count; i++) {
            if (listeners[i] != listener) {
                continue;
            }

            final int moved = count - i - 1;
            if (moved > 0) {
                System.arraycopy(listeners, i + 1, listeners, i, moved);
            }
            listeners[--count] = null;
            generation++;
            return;
        }
    }

    /** Notifies listeners in registration order on the render thread. */
    public void run() {
        final Runnable[] currentListeners = listeners;
        final int currentGeneration = generation;
        // A callback can deactivate the program; stop before accessing its cleared registrations.
        for (int i = 0, size = count; i < size && generation == currentGeneration; i++) {
            currentListeners[i].run();
        }
    }
}
