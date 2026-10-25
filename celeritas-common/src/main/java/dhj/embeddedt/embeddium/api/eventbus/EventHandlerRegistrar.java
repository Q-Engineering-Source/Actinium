package dhj.embeddedt.embeddium.api.eventbus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds a list of event handlers and handles event dispatching.
 */
public class EventHandlerRegistrar<T extends EmbeddiumEvent> {
    private final List<Handler<T>> handlerList = new CopyOnWriteArrayList<>();

    public EventHandlerRegistrar() {}

    public void addListener(Handler<T> listener) {
        handlerList.add(listener);
    }

    /** Removes a previously registered listener, primarily for explicit lifecycle ownership. */
    public void removeListener(Handler<T> listener) {
        handlerList.remove(listener);
    }

    /**
     * Post the given event to all registered listeners.
     * @return true if the event is cancelable and was canceled, false otherwise
     */
    public boolean post(T event) {
        boolean canceled = false;

        if(!handlerList.isEmpty()) {
            boolean isCancelable = event.isCancelable();
            for(Handler<T> handler : handlerList) {
                handler.acceptEvent(event);
                if(isCancelable && event.isCanceled()) {
                    canceled = true;
                }
            }
        }

        // Dispatch to the platform event bus as well (currently only used on Forge)
        canceled |= postPlatformSpecificEvent(event);
        return canceled;
    }

    /**
     * Lets a compatibility dispatcher wrap each listener in an event-specific transaction boundary.
     */
    public void dispatchHandlers(Consumer<Handler<T>> dispatcher) {
        if (dispatcher == null) {
            throw new IllegalArgumentException("Handler dispatcher must not be null");
        }
        this.handlerList.forEach(dispatcher);
    }

    private static <T extends EmbeddiumEvent> boolean postPlatformSpecificEvent(T event) {
        return false;
    }

    @FunctionalInterface
    public interface Handler<T extends EmbeddiumEvent> {
        void acceptEvent(T event);
    }
}
