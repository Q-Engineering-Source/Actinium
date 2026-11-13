package com.dhj.actinium.startup;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.events.LoadingCheckpointEvent;

/** Gives asynchronous splash rendering opportunities to present and finish loading uploads. */
public final class LoadingCheckpoints {
    private LoadingCheckpoints() {}

    public static void post(boolean requiresSync) {
        if (!GLSMHooks.LOADING_CHECKPOINT.hasListeners()) return;
        LoadingCheckpointEvent event = new LoadingCheckpointEvent();
        event.requiresSync = requiresSync;
        GLSMHooks.LOADING_CHECKPOINT.post(event);
    }
}
