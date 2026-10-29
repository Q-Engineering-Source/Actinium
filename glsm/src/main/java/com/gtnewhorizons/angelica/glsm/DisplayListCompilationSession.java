package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.recording.AccumulatedDraw;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.VirtualStateDepth;
import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import org.joml.Matrix4fStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Mutable display-list compilation state owned by one native context lifetime. */
final class DisplayListCompilationSession {
    final GLContextIdentity identity;
    final Deque<Frame> parents = new ArrayDeque<>();
    final Set<CommandRecorder> pausedRecorders = Collections.newSetFromMap(new IdentityHashMap<>());
    final VirtualStateDepth virtualStateScopes = new VirtualStateDepth(GLStateManager.MAX_ATTRIB_STACK_DEPTH);

    int listMode;
    int listId = -1;
    CommandRecorder recorder;
    DirectTessellator capture;
    boolean captureActive;
    Thread recordingThread;
    List<AccumulatedDraw> accumulatedDraws;
    Matrix4fStack relativeTransform;
    int stateGeneration;
    StackTraceElement[] stackTrace;
    List<String> pendingTransformOps;
    List<List<String>> matrixSources;
    List<String> drawSources;
    int currentRenderingList = -1;

    DisplayListCompilationSession(GLContextIdentity identity) {
        this.identity = identity;
    }

    boolean isActive() {
        return this.recorder != null || !this.pausedRecorders.isEmpty() || this.listMode != 0;
    }

    void clear() {
        this.parents.clear();
        this.pausedRecorders.clear();
        this.listMode = 0;
        this.listId = -1;
        this.recorder = null;
        this.capture = null;
        this.captureActive = false;
        this.recordingThread = null;
        this.accumulatedDraws = null;
        this.relativeTransform = null;
        this.stateGeneration = 0;
        this.stackTrace = null;
        this.pendingTransformOps = null;
        this.matrixSources = null;
        this.drawSources = null;
        this.virtualStateScopes.clear();
    }

    List<DirectTessellator> capturesOutermostFirst() {
        final List<DirectTessellator> captures = new ArrayList<>(this.parents.size() + 1);
        final var iterator = this.parents.descendingIterator();
        while (iterator.hasNext()) {
            final Frame parent = iterator.next();
            if (parent.captureActive() && parent.capture() != null) {
                captures.add(parent.capture());
            }
        }
        if (this.captureActive && this.capture != null) {
            captures.add(this.capture);
        }
        return captures;
    }

    record Frame(
        int listId,
        int listMode,
        int virtualStateDepth,
        boolean captureActive,
        DirectTessellator capture,
        CommandRecorder recorder,
        List<AccumulatedDraw> draws,
        Matrix4fStack transform,
        int stateGeneration,
        StackTraceElement[] stackTrace,
        List<String> pendingOps,
        List<List<String>> matrixSources,
        List<String> drawSources
    ) {}
}
