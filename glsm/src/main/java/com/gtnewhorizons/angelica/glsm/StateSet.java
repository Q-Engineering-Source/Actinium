package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The attribute group a {@code glPushAttrib} pushed: which stacks it saves ({@link #members}, a bit
 * set of stack ids), which restored groups drive backend calls ({@link #restore}), which generation
 * counters a pop may have to bump ({@link #bump}) and whether the vanilla blend function has to be
 * snapshotted around the scope.
 */
public final class StateSet {

    public static final int R_DEPTH = 1;
    public static final int R_BLEND = 1 << 1;
    public static final int R_COLOR_MASK = 1 << 2;
    public static final int R_CLEAR_COLOR = 1 << 3;
    public static final int R_DRAW_BUFFER = 1 << 4;
    public static final int R_LOGIC_OP = 1 << 5;
    public static final int R_SECONDARY_COLOR = 1 << 6;
    public static final int R_STENCIL = 1 << 7;
    public static final int R_VIEWPORT = 1 << 8;
    public static final int R_LINE = 1 << 9;
    public static final int R_POINT = 1 << 10;
    public static final int R_POLYGON = 1 << 11;
    public static final int R_TEXTURE = 1 << 12;
    public static final int R_ACTIVE_UNIT = 1 << 13;
    public static final int R_SCISSOR = 1 << 14;

    public static final int B_FRAG_COLORBUF = 1;
    public static final int B_FRAG_TEXTURE = 1 << 1;
    public static final int B_FRAG_FOG = 1 << 2;
    public static final int B_CURRENT = 1 << 3;
    public static final int B_LIGHTING = 1 << 4;
    public static final int B_TRANSFORM = 1 << 5;
    public static final int B_TEXGEN = 1 << 6;
    public static final int B_CLIP_PLANE = 1 << 7;

    private static final ConcurrentHashMap<GLContextIdentity, ConcurrentHashMap<Integer, StateSet>> contextStateSets = new ConcurrentHashMap<>();

    public final int glMask;
    public final long[] members;
    public final int restore;
    public final int bump;
    public final boolean blendSnapshot;

    StateSet(int glMask, long[] members, int restore, int bump, boolean blendSnapshot) {
        this.glMask = glMask;
        this.members = members;
        this.restore = restore;
        this.bump = bump;
        this.blendSnapshot = blendSnapshot;
    }

    public boolean contains(int id) {
        if (id < 0) return false;
        final int word = id >> 6;
        return word < members.length && (members[word] & (1L << (id & 63))) != 0;
    }

    public static StateSet forMask(int mask) {
        final GLContextIdentity identity = GLStateManager.currentContextIdentity();
        if (identity == null) {
            throw new IllegalStateException("Attrib state mapping requires a registered current context");
        }
        return contextStateSets.computeIfAbsent(identity, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(mask, StateSet::buildForMask);
    }

    /** Drops cached stack-id bitsets with the context that assigned those ids. */
    static void onContextRetired(GLContextIdentity identity) {
        contextStateSets.remove(identity);
    }

    private static StateSet buildForMask(int mask) {
        int restore = 0;
        int bump = 0;
        final boolean blendSnapshot = (mask & (GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT)) != 0;

        if ((mask & GL11.GL_DEPTH_BUFFER_BIT) != 0) {
            restore |= R_DEPTH;
        }
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0) {
            restore |= R_BLEND | R_COLOR_MASK | R_CLEAR_COLOR | R_DRAW_BUFFER | R_LOGIC_OP;
            bump |= B_FRAG_COLORBUF;
        }
        if ((mask & GL11.GL_CURRENT_BIT) != 0) {
            restore |= R_SECONDARY_COLOR;
            bump |= B_CURRENT;
        }
        if ((mask & GL11.GL_STENCIL_BUFFER_BIT) != 0) {
            restore |= R_STENCIL;
        }
        if ((mask & GL11.GL_VIEWPORT_BIT) != 0) {
            restore |= R_VIEWPORT;
        }
        if ((mask & GL11.GL_SCISSOR_BIT) != 0) {
            restore |= R_SCISSOR;
        }
        if ((mask & GL11.GL_LINE_BIT) != 0) {
            restore |= R_LINE;
        }
        if ((mask & GL11.GL_POINT_BIT) != 0) {
            restore |= R_POINT;
        }
        if ((mask & GL11.GL_POLYGON_BIT) != 0) {
            restore |= R_POLYGON;
        }
        if ((mask & GL11.GL_TEXTURE_BIT) != 0) {
            restore |= R_TEXTURE | R_ACTIVE_UNIT;
            bump |= B_FRAG_TEXTURE | B_TEXGEN;
        }
        if ((mask & GL11.GL_LIGHTING_BIT) != 0) {
            bump |= B_LIGHTING;
        }
        if ((mask & GL11.GL_FOG_BIT) != 0) {
            bump |= B_FRAG_FOG;
        }
        if ((mask & GL11.GL_TRANSFORM_BIT) != 0) {
            bump |= B_TRANSFORM | B_CLIP_PLANE;
        }

        final long[] members = Feature.maskToMembers(mask);

        return new StateSet(mask, members, restore, bump, blendSnapshot);
    }
}
