package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Excludes renderers that mix batched model parts with their own draws, which reorders their geometry. */
public final class BatchEligibility {

    private static final Logger LOGGER = LogManager.getLogger("BatchEligibility");
    private static final Set<String> loggedDiagnosticKeys = new HashSet<>();

    public static final byte UNKNOWN = 0;
    public static final byte SAFE = 1;
    public static final byte DENIED = 2;

    private static int depth;
    private static boolean allowed;
    private static long drawsAtStart;
    private static long expectedDraws;
    private static long foreignDraws;
    private static long bracketDrawsAtStart;
    private static long bracketExpectedAtStart;
    private static int bracketDepth;
    private static int parts;
    private static boolean uncapturedState;
    private static ModelPartBatcher.BailReason allowedBail;

    private static final ArrayList<SavedScope> isolatedScopes = new ArrayList<>();
    private static int isolatedDepth;

    private static final class SavedScope {
        int depth, bracketDepth, parts;
        boolean allowed, uncapturedState;
        ModelPartBatcher.BailReason allowedBail;
        long drawsAtStart, expectedDraws, foreignDraws, bracketDrawsAtStart, bracketExpectedAtStart, isolatedDrawsAtStart;

        void save(long drawCount) {
            depth = BatchEligibility.depth;
            allowed = BatchEligibility.allowed;
            uncapturedState = BatchEligibility.uncapturedState;
            allowedBail = BatchEligibility.allowedBail;
            drawsAtStart = BatchEligibility.drawsAtStart;
            expectedDraws = BatchEligibility.expectedDraws;
            foreignDraws = BatchEligibility.foreignDraws;
            bracketDrawsAtStart = BatchEligibility.bracketDrawsAtStart;
            bracketExpectedAtStart = BatchEligibility.bracketExpectedAtStart;
            bracketDepth = BatchEligibility.bracketDepth;
            parts = BatchEligibility.parts;
            isolatedDrawsAtStart = drawCount;
        }

        void restore(long drawCount) {
            BatchEligibility.depth = depth;
            BatchEligibility.allowed = allowed;
            BatchEligibility.uncapturedState = uncapturedState;
            BatchEligibility.allowedBail = allowedBail;
            BatchEligibility.drawsAtStart = drawsAtStart;
            BatchEligibility.expectedDraws = expectedDraws + drawCount - isolatedDrawsAtStart;
            BatchEligibility.foreignDraws = foreignDraws;
            BatchEligibility.bracketDrawsAtStart = bracketDrawsAtStart;
            BatchEligibility.bracketExpectedAtStart = bracketExpectedAtStart;
            BatchEligibility.bracketDepth = bracketDepth;
            BatchEligibility.parts = parts;
        }
    }

    private BatchEligibility() {}

    public static void beginIsolated(byte state, long drawCount) {
        if (isolatedDepth == isolatedScopes.size()) isolatedScopes.add(new SavedScope());
        isolatedScopes.get(isolatedDepth++).save(drawCount);
        depth = 0;
        bracketDepth = 0;
        begin(state, drawCount);
    }

    public static byte endIsolated(byte state, long drawCount) {
        try {
            return end(state, drawCount);
        } finally {
            isolatedScopes.get(--isolatedDepth).restore(drawCount);
        }
    }

    public static boolean begin(byte state, long drawCount) {
        if (depth++ > 0) return allowed;
        drawsAtStart = drawCount;
        expectedDraws = 0L;
        foreignDraws = 0L;
        parts = 0;
        uncapturedState = false;
        allowedBail = null;
        bracketDepth = 0;
        allowed = state == SAFE;
        return allowed;
    }

    public static byte end(byte state, long drawCount) {
        if (--depth > 0) return state;
        allowed = false;
        GLSMHooks.resolvePendingProgram();
        if (uncapturedState) return DENIED;
        if (parts == 0) return state;
        final long foreign = (drawCount - drawsAtStart) - expectedDraws;
        if (foreign > 0L) {
            foreignDraws = foreign;
            return DENIED;
        }
        return state == UNKNOWN ? SAFE : state;
    }

    /**
     * Drops every bracket the current frame left open. A renderer that throws between
     * {@link #begin} and {@link #end} would otherwise leave {@code allowed} true and let the next
     * frame batch draws that were never proven safe.
     */
    public static void reset() {
        depth = 0;
        allowed = false;
        drawsAtStart = 0L;
        expectedDraws = 0L;
        foreignDraws = 0L;
        bracketDrawsAtStart = 0L;
        bracketExpectedAtStart = 0L;
        bracketDepth = 0;
        parts = 0;
        uncapturedState = false;
        allowedBail = null;
        isolatedScopes.clear();
        isolatedDepth = 0;
    }

    public static boolean batchingAllowed() {
        return allowed;
    }

    static boolean insideRenderer() { return depth != 0; }

    static void onUncapturedState() {
        EntityShadowReplay.rejectUncapturedState();
        if (depth == 0) return;
        uncapturedState = true;
        allowed = false;
    }

    static void onBatchFlushed(long draws) {
        if (depth != 0) expectedDraws += draws;
    }

    static void onBail(ModelPartBatcher.BailReason reason) {
        EntityShadowReplay.rejectBatcherBail();
        if (depth == 0 || !allowed) return;
        if (reason == ModelPartBatcher.BailReason.INACTIVE) {
            allowed = false;
            return;
        }
        if (allowedBail == null) allowedBail = reason;
    }

    public static void onPartQueued() {
        parts++;
    }

    public static void onPartFallback(long drawsBefore, long drawsNow) {
        EntityShadowReplay.rejectPartFallback();
        parts++;
        if (!allowed) {
            expectedDraws += drawsNow - drawsBefore;
        }
    }

    public static void beginExpectedDraws(long drawCount) {
        if (bracketDepth++ > 0) return;
        bracketDrawsAtStart = drawCount;
        bracketExpectedAtStart = expectedDraws;
    }

    public static void endExpectedDraws(long drawCount) {
        if (--bracketDepth > 0) return;
        final long alreadyExpected = expectedDraws - bracketExpectedAtStart;
        expectedDraws += (drawCount - bracketDrawsAtStart) - alreadyExpected;
    }

    public static void onStateChange(Object renderer, Object subject, byte state) {
        if (state != DENIED) return;
        if (ActiniumRuntime.options().debug.enableEntityRenderDiagnostics) {
            final String name = renderer.getClass().getName();
            final String reason = uncapturedState ? "uncaptured_state" : "mixed_renderer";
            final String target = describeSubject(subject);
            final String key = name + '|' + reason + '|' + describeSubjectType(subject);
            if (loggedDiagnosticKeys.add(key)) {
                LOGGER.warn("[ACTINIUM-ENTITY-DIAGNOSTICS] renderer={} reason={} target={} foreignDraws={} expectedDraws={} parts={} firstAllowedBail={}",
                    name, reason, target, foreignDraws, expectedDraws, parts, allowedBail);
            }
        }
        final String name = renderer.getClass().getName();
        if (uncapturedState) {
            GLStateManager.warnOnce("tesr-state:" + name, "{} changes state outside the batch format - keeping its model parts immediate", name);
            return;
        }
        GLStateManager.warnOnce("tesr-mixed:" + name, "{} draws its own geometry alongside model parts ({} foreign draws, first allowed bail: {}) - excluding it from model part batching", name, foreignDraws, allowedBail);
    }

    private static String describeSubject(Object subject) {
        if (subject instanceof Entity entity) {
            String description = entity.getClass().getName() + "#" + entity.getEntityId() + "@"
                + entity.posX + "," + entity.posY + "," + entity.posZ;
            if (entity instanceof EntityItem droppedItem) {
                final ItemStack stack = droppedItem.getItem();
                return description + " item=" + (stack.isEmpty() ? "empty" : stack.getItem().getClass().getName())
                    + " count=" + stack.getCount();
            }
            return description;
        }
        if (subject instanceof TileEntity tileEntity) {
            return tileEntity.getClass().getName() + "@" + tileEntity.getPos();
        }
        return subject == null ? "unknown" : subject.getClass().getName();
    }

    private static String describeSubjectType(Object subject) {
        if (subject instanceof EntityItem droppedItem) {
            final ItemStack stack = droppedItem.getItem();
            return subject.getClass().getName() + ':'
                + (stack.isEmpty() ? "empty" : stack.getItem().getClass().getName());
        }
        return subject == null ? "unknown" : subject.getClass().getName();
    }
}
