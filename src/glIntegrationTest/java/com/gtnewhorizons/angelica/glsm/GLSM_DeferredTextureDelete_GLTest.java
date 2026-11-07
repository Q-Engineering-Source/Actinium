package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.dsa.DSAARB;
import com.gtnewhorizons.angelica.glsm.stacks.TextureBindingStack;
import com.gtnewhorizons.angelica.glsm.states.ImageUnitBinding;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import com.gtnewhorizons.angelica.glsm.texture.TextureStaging;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the deferred texture deletion and texture staging facades against a real driver. A deleted name keeps its
 * storage for {@link GLSMTextureLifetime#RELEASE_DELAY_FRAMES} frames so that vanilla and mods can bind it again
 * without a core-profile error, and the unit-wide multi-bind paths must route through that state machine instead of
 * binding names the driver no longer owns.
 */
class GLSM_DeferredTextureDelete_GLTest extends GLStateManagerIntegrationFixture {

    private static final int RELEASE_DELAY_FRAMES = GLSMTextureLifetime.RELEASE_DELAY_FRAMES;
    private static final int SIZE = 4;
    private static final int LEVELS = 3;

    @BeforeAll
    static void completeSplash() {
        GLStateManager.markSplashComplete("GLSM_DeferredTextureDelete_GLTest");
        final GLCapabilities glCaps = GL.getCapabilities();
        RenderSystem.initializeTextureStorageSupportForTesting(glCaps.OpenGL42 || glCaps.GL_ARB_texture_storage);
    }

    @BeforeEach
    void resetDeferredNames() {
        GLSMTextureLifetime.resetForTesting();
        GLSMTextures.trimTextureStaging();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int immutableTexture() {
        assumeTrue(GL.getCapabilities().OpenGL42, "no glTexStorage2D on this context");
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R8, SIZE, SIZE);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int mipmappedTexture() {
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        for (int level = 0; level < LEVELS; level++) {
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, SIZE >> level, SIZE >> level, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int levelWidth(int tex, int level) {
        final int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        final int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return width;
    }

    private static int cachedBinding(int unit) {
        return GLSMTextures.getTextures().getTextureUnitBindings(unit).getBinding();
    }

    private static int levelWidth3D(int tex, int level) {
        final int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        final int width = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, level, GL11.GL_TEXTURE_WIDTH);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        return width;
    }

    private static ByteBuffer pixels2D(int tex) {
        final int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return pixels;
    }

    private static void framesPass(int frames) {
        for (int i = 0; i < frames; i++) GLSMTextureLifetime.onFrameEnd();
    }

    enum Attachment {
        NONE {
            @Override int attach(int tex) { return 0; }
        },
        FRAMEBUFFER {
            @Override int attach(int tex) {
                final int fbo = GLSMFramebuffer.glGenFramebuffers();
                GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
                GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                return fbo;
            }
        },
        NAMED_EXT {
            @Override int attach(int tex) {
                final int fbo = GLSMFramebuffer.glGenFramebuffers();
                GLSMFramebuffer.glNamedFramebufferTexture2DEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
                assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "named attach must restore the draw binding");
                return fbo;
            }
        };

        abstract int attach(int tex);
    }

    @ParameterizedTest
    @EnumSource(Attachment.class)
    void storageIsReleasedOnlyAfterTheDelayFramesUnattached(Attachment attachment) {
        framesPass(RELEASE_DELAY_FRAMES);
        final int before = GLSMTextureLifetime.deferredTexturesAwaitingRelease();
        final int tex = mipmappedTexture();
        final int fbo = attachment.attach(tex);

        GLSMTextures.glDeleteTextures(tex);
        assertEquals(before + 1, GLSMTextureLifetime.deferredTexturesAwaitingRelease());
        framesPass(RELEASE_DELAY_FRAMES - 1);
        assertEquals(SIZE, levelWidth(tex, 0), "storage must outlive the delete by the delay frames");
        if (fbo != 0) {
            framesPass(RELEASE_DELAY_FRAMES);
            assertEquals(SIZE, levelWidth(tex, 0), "an attached texture keeps its storage");
            assertEquals(before + 1, GLSMTextureLifetime.deferredTexturesAwaitingRelease());
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GLSMTextures.glDeleteTextures(GLSMTextures.glGenTextures());
            assertEquals(tex, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME), "a flush must not delete an attached texture");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            framesPass(RELEASE_DELAY_FRAMES - 1);
            assertEquals(SIZE, levelWidth(tex, 0), "storage must outlive the detach by the delay frames");
        }
        framesPass(1);

        for (int level = 0; level < LEVELS; level++) assertEquals(0, levelWidth(tex, level), "level " + level);
        assertEquals(before, GLSMTextureLifetime.deferredTexturesAwaitingRelease(), "released storage leaves nothing awaiting release");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    enum Rejection { ATTACH, BIND_TEXTURE_UNIT }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void deletedNamesAreRejected(Rejection rejection) {
        final int tex = mipmappedTexture();
        final int other = mipmappedTexture();
        GLSMTextures.glDeleteTextures(tex);
        try {
            switch (rejection) {
                case ATTACH -> {
                    final int fbo = GLSMFramebuffer.glGenFramebuffers();
                    try {
                        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
                        assertEquals(GL11.GL_NONE, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
                    } finally {
                        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                        GLSMFramebuffer.glDeleteFramebuffers(fbo);
                    }
                }
                case BIND_TEXTURE_UNIT -> {
                    GLSMTextures.glBindTextureUnit(2, other);
                    GLSMTextures.glBindTextureUnit(2, tex);
                    GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
                    assertEquals(other, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "driver binding");
                    assertEquals(other, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "cached binding");
                }
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTextureUnit(2, 0);
            GLSMTextures.glDeleteTextures(other);
        }
    }

    @Test
    void bindTexturesBindsEachTextureToItsTarget() {
        final int t2d = mipmappedTexture();
        final int t3d = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        try {
            GLSMTextures.glBindTextures(1, new int[] {t2d, t3d});

            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "active unit unchanged");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(t2d, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "unit 1 driver binding");
            assertEquals(t2d, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "unit 1 cached binding");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
            assertEquals(t3d, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "unit 2 driver binding");
            assertEquals(t3d, cachedBinding(2), "unit 2 cached binding");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            final int unit0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GLSMTextures.glBindTextures(GLStateManager.MAX_TEXTURE_UNITS - 1, new int[] {t3d, t2d});
            assertEquals(unit0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "an out-of-range multi-bind must not land on the active unit");

            GLSMTextures.glDeleteTextures(t2d);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "delete unbinds a multi-bound texture");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTextures(1, new int[] {0, 0});
            GLSMTextures.glDeleteTextures(t3d);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void unitBindingsUseTheTargetTheTextureWasCreatedWith() {
        assumeTrue(GL.getCapabilities().OpenGL45, "no glCreateTextures on this context");
        final int t3d = GLSMTextures.glCreateTextures(GL12.GL_TEXTURE_3D);
        try {
            GLSMTextures.glBindTextureUnit(1, t3d);

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(t3d, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "driver 3D binding");
            assertEquals(GL12.GL_TEXTURE_3D, GLSMTextures.getTextures().getTextureUnitBindings(1).getTarget(), "cached target");
            assertEquals(t3d, cachedBinding(1), "cached binding");
            assertEquals(0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "a 3D name must not land on the 2D target");
        } finally {
            GLSMTextures.glBindTextureUnit(1, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glDeleteTextures(t3d);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void dsaCreated3DTextureIsBoundAs3D() {
        final int tex = RenderSystem.createTexture(GL12.GL_TEXTURE_3D);
        final int other = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, other);
        RenderSystem.texImage3D(tex, GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(tex, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "texImage3D binds the texture as 3D");
        assertEquals(tex, cachedBinding(0), "cached 3D binding");
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GLSMTextures.glDeleteTextures(tex);
        GLSMTextures.glDeleteTextures(other);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void unbindingAUnitClearsTheLastNon2DTargetToo() {
        final int t2d = mipmappedTexture();
        final int t3d = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
        try {
            GLSMTextures.glBindTextureUnit(0, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            assertEquals(0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "2D target cleared");
            assertEquals(0, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "3D target cleared");
            assertEquals(0, cachedBinding(0), "cached binding cleared");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glDeleteTextures(t2d);
            GLSMTextures.glDeleteTextures(t3d);
        }
    }

    @Test
    void createdNon2DTextureIsNotReleasedAs2D() {
        assumeTrue(GL.getCapabilities().OpenGL45, "no glCreateTextures on this context");
        final int tex = GLSMTextures.glCreateTextures(GL12.GL_TEXTURE_3D);
        GLSMTextures.glDeleteTextures(tex);
        framesPass(RELEASE_DELAY_FRAMES);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void deletingDetachesOnlyFromBoundFramebuffers() {
        final int tex = mipmappedTexture();
        final int unbound = Attachment.FRAMEBUFFER.attach(tex);
        final int bound = Attachment.FRAMEBUFFER.attach(tex);
        try {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bound);

            GLSMTextures.glDeleteTextures(tex);

            assertEquals(GL11.GL_NONE, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, unbound);
            assertEquals(tex, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
        } finally {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(bound);
            GLSMFramebuffer.glDeleteFramebuffers(unbound);
        }
    }

    enum Rescue { PENDING, RELEASED, ATTACHED }

    @ParameterizedTest
    @EnumSource(Rescue.class)
    void rescuedTextureIsEmptyWithDefaultParameters(Rescue rescue) {
        final int tex = mipmappedTexture();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 1);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_R, GL11.GL_GREEN);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 1.5f);
        GLSMTextures.glTexParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, (FloatBuffer) BufferUtils.createFloatBuffer(4).put(new float[] {1, 1, 1, 1}).flip());
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        final int fbo = rescue == Rescue.ATTACHED ? Attachment.FRAMEBUFFER.attach(tex) : 0;
        GLSMTextures.glDeleteTextures(tex);
        if (rescue != Rescue.PENDING) framesPass(RELEASE_DELAY_FRAMES);

        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        try {
            assertEquals(rescue == Rescue.ATTACHED ? SIZE : 0, levelWidth(tex, 0), "only an attached texture keeps its storage");
            final int[][] defaults = {
                {GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR},
                {GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR},
                {GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT},
                {GL12.GL_TEXTURE_BASE_LEVEL, 0},
                {GL12.GL_TEXTURE_MAX_LEVEL, 1000},
                {GL33.GL_TEXTURE_SWIZZLE_R, GL11.GL_RED},
            };
            for (int[] pair : defaults) {
                assertEquals(pair[1], GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, pair[0]), "driver pname 0x" + Integer.toHexString(pair[0]));
                assertEquals(pair[1], GLSMTextures.glGetTexParameteri(GL11.GL_TEXTURE_2D, pair[0]), "cached pname 0x" + Integer.toHexString(pair[0]));
            }
            assertEquals(0.0f, GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS));
            assertEquals(0.0f, GL11C.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR), "border color red component");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(tex);
            if (fbo != 0) GLSMFramebuffer.glDeleteFramebuffers(fbo);
        }
    }

    @Test
    void rescuingWithADifferentTargetLeavesTheBoundTextureAlone() {
        final int t3d = GLSMTextures.glGenTextures();
        final int tex = mipmappedTexture();
        try {
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
            GLSMTextures.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GLSMTextures.glDeleteTextures(tex);

            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, tex);

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GLSM drops the bind");
            assertEquals(t3d, cachedBinding(0), "cached 3D binding");
            assertEquals(GL11.GL_LINEAR, GL11.glGetTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER));
            framesPass(RELEASE_DELAY_FRAMES);
            assertEquals(t3d, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "the release must not unbind the 3D texture");
            assertEquals(0, levelWidth(tex, 0), "the mismatched bind must leave the texture deferred");
        } finally {
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glDeleteTextures(t3d);
        }
    }

    @Test
    void deletingAStaleNameKeepsTheBoundTexture() {
        final int live = mipmappedTexture();
        final int stale = GLSMTextures.glGenTextures();
        try {
            GLSMTextures.glDeleteTextures(stale);
            GLSMTextures.glDeleteTextures(GLSMTextures.glGenTextures());
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, live);

            GLSMTextures.glDeleteTextures(stale);
            framesPass(RELEASE_DELAY_FRAMES);

            assertEquals(SIZE, levelWidth(live, 0), "a stale delete must not empty the bound texture");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(live);
        }
    }

    @Test
    void deletingAnImmutableTextureRaisesNoError() {
        final int tex = immutableTexture();

        GLSMTextures.glDeleteTextures(tex);

        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void rescuedImmutableTextureResetsParametersAndDeletesAgain() {
        assumeTrue(RenderSystem.supportsTextureStorage(), "no immutable texture storage on this context");
        final int tex = immutableTexture();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GLSMTextures.glDeleteTextures(tex);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);

        assertEquals(GL11.GL_NEAREST_MIPMAP_LINEAR, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
        GLSMTextures.glDeleteTextures(tex);

        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletingA2DTextureWhileA3DTextureIsBound(boolean deletedIsHidden2D) {
        final int t2d = GLSMTextures.glGenTextures();
        final int t3d = GLSMTextures.glGenTextures();
        final int other = GLSMTextures.glGenTextures();
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);

            GLSMTextures.glDeleteTextures(deletedIsHidden2D ? t2d : other);

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            assertEquals(deletedIsHidden2D ? 0 : t2d, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "only the deleted texture leaves the hidden 2D binding");
            assertEquals(t3d, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D));
        } finally {
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(t3d);
            GLSMTextures.glDeleteTextures(deletedIsHidden2D ? other : t2d);
        }
    }

    @Test
    void texSubImage2DReachesTheHidden2DBindingWhileA3DNameOwnsTheSlot() {
        final int t2d = GLSMTextures.glGenTextures();
        final int t3d = GLSMTextures.glGenTextures();
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        final ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        for (int i = 0; i < SIZE * SIZE; i++) {
            pixels.put(i * 4, (byte) 0x11);
            pixels.put(i * 4 + 1, (byte) 0x22);
            pixels.put(i * 4 + 2, (byte) 0x33);
            pixels.put(i * 4 + 3, (byte) 0x44);
        }
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            // Vanilla GlStateManager.bindTexture keeps its own per-unit name cache and sends no bind while the name
            // still matches, so the driver can hold a 2D binding that GLSM never sees once the unit slot moves to
            // another target.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
            assertEquals(GL12.GL_TEXTURE_3D, GLSMTextures.getTextures().getTextureUnitBindings(0).getTarget(), "the 3D bind owns the unit slot");
            assertEquals(t2d, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "the vanilla cache left the 2D binding in place");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "before the upload");

            GLSMTextures.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the upload must reach the 2D texture");
            final ByteBuffer readback = pixels2D(t2d);
            for (int i = 0; i < SIZE * SIZE; i++) {
                assertEquals((byte) 0x11, readback.get(i * 4), "R of pixel " + i);
                assertEquals((byte) 0x22, readback.get(i * 4 + 1), "G of pixel " + i);
                assertEquals((byte) 0x33, readback.get(i * 4 + 2), "B of pixel " + i);
                assertEquals((byte) 0x44, readback.get(i * 4 + 3), "A of pixel " + i);
            }
        } finally {
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(t3d);
            GLSMTextures.glDeleteTextures(t2d);
        }
    }

    @Test
    void texImageUploadsSuspendThePixelUnpackBuffer() {
        final int pbo = GLStateManager.glGenBuffers();
        final int tex3d = GLSMTextures.glGenTextures();
        final int tex1d = GLSMTextures.glGenTextures();
        final ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * SIZE * 4);
        try {
            GLStateManager.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GLStateManager.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 16L, GL15.GL_STREAM_DRAW);
            assertEquals(pbo, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING), "the pixel-unpack buffer is bound");

            RenderSystem.texImage3D(tex3d, GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            assertEquals(pbo, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING), "texImage3D restores the pixel-unpack binding");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "texImage3D must read the client pointer, not a buffer offset");
            assertEquals(SIZE, levelWidth3D(tex3d, 0), "the 3D storage was allocated");

            RenderSystem.texImage1D(tex1d, GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            assertEquals(pbo, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING), "texImage1D restores the pixel-unpack binding");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "texImage1D must read the client pointer, not a buffer offset");
        } finally {
            GLStateManager.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLStateManager.glDeleteBuffers(pbo);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, 0);
            GLSMTextures.glDeleteTextures(tex3d);
            GLSMTextures.glDeleteTextures(tex1d);
        }
    }

    @Test
    void hidden2DBindingResolvesPerUnitWithoutMovingTheActiveUnit() {
        final int t2d = GLSMTextures.glGenTextures();
        final int t3d = GLSMTextures.glGenTextures();
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            // Vanilla's per-unit name cache leaves this 2D bind in the driver without a GLSM bind.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "the active unit is back on 0");

            assertEquals(t2d, GLSMTextures.getBoundTextureForServerState(1), "unit 1 still holds the hidden 2D texture");
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "the query restores the driver unit");
            assertEquals(0, GLSMQueries.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0, "the query restores the tracked unit");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glDeleteTextures(t3d);
            GLSMTextures.glDeleteTextures(t2d);
        }
    }

    @Test
    void texParameterUpdatesTheHidden2DInfoWhileA3DNameOwnsTheSlot() {
        final int t2d = GLSMTextures.glGenTextures();
        final int t3d = GLSMTextures.glGenTextures();
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);

            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);

            final TextureInfo hidden = TextureInfoCache.INSTANCE.getInfo(t2d);
            assertTrue(hidden.isGenerateMipmap(), "the hidden 2D texture takes the parameter");
            assertFalse(TextureInfoCache.INSTANCE.getInfo(t3d).isGenerateMipmap(), "the 3D texture keeps its own info");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_FALSE);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(t3d);
            GLSMTextures.glDeleteTextures(t2d);
        }
    }

    @Test
    void dsaBindTextureToUnitKeepsTheTargetItWasCalledWith() {
        final GLCapabilities glCaps = GL.getCapabilities();
        assumeTrue(glCaps.OpenGL45 || glCaps.GL_ARB_direct_state_access, "no direct state access on this context");
        final DSAARB dsa = new DSAARB();
        final int t2d = GLSMTextures.glGenTextures();
        final int t3d = GLSMTextures.glGenTextures();
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, t3d);
            GLSMTextures.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            // Vanilla's per-unit name cache leaves this 2D bind in the driver without a GLSM bind.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t2d);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            dsa.bindTextureToUnit(GL12.GL_TEXTURE_3D, 1, t3d);

            final TextureBindingStack unit1 = GLSMTextures.getTextures().getTextureUnitBindings(1);
            assertAll(
                () -> assertEquals(GL12.GL_TEXTURE_3D, unit1.getTarget(), "the 3D bind records its own target"),
                () -> assertEquals(t3d, unit1.getBinding(), "the 3D name lands in the slot"),
                () -> assertEquals(t2d, GLSMTextures.getBoundTextureForServerState(1), "the 2D query still sees the hidden 2D binding"));
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "the query restores the driver unit");
            assertEquals(0, GLSMQueries.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0, "the query restores the tracked unit");

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(t3d, GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D), "driver 3D binding");
            assertEquals(t2d, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "driver 2D binding untouched");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            final int other2d = mipmappedTexture();
            try {
                // The 2D-only overload carries the original fixed-function semantics.
                dsa.bindTextureToUnit(1, other2d);
                assertEquals(GL11.GL_TEXTURE_2D, unit1.getTarget(), "the 2D-only overload records the 2D target");
                assertEquals(other2d, unit1.getBinding());
            } finally {
                GLSMTextures.glDeleteTextures(other2d);
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glDeleteTextures(t3d);
            GLSMTextures.glDeleteTextures(t2d);
        }
    }

    @Test
    void stagingRefusesANonDefaultUnpackState() {
        final int tex = mipmappedTexture();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, SIZE * 2);
        try {
            assertNull(GLSMTextures.beginTextureStaging(0, 0, 0, SIZE, SIZE), "a padded row cannot be staged as a packed rectangle");
        } finally {
            GLSMTextures.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(tex);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void committedStagingUploadsOnlyTheStagedRectangle() {
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
        final int width = 2;
        final int height = 2;
        final int x = 1;
        final int y = 1;
        try {
            final TextureStaging staging = GLSMTextures.beginTextureStaging(0, x, y, width, height);
            assertNotNull(staging, "a packed upload must be stageable");
            assertTrue(staging.bgra(), "the staged block already holds BGRA bytes");
            assertEquals(0, staging.level(), "the staged mip level");
            final ByteBuffer staged = staging.buffer();
            assertEquals(width * height * 4, staged.capacity(), "four bytes per staged pixel");
            for (int i = 0; i < width * height; i++) {
                staged.put(i * 4, (byte) 0x11);
                staged.put(i * 4 + 1, (byte) 0x22);
                staged.put(i * 4 + 2, (byte) 0x33);
                staged.put(i * 4 + 3, (byte) 0x44);
            }

            assertTrue(GLSMTextures.commitTextureStaging(staging), "commit uploads the staged buffer");

            final ByteBuffer readback = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
            GLSMTextures.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readback);
            for (int row = 0; row < SIZE; row++) {
                for (int column = 0; column < SIZE; column++) {
                    final int offset = (row * SIZE + column) * 4;
                    final boolean inside = column >= x && column < x + width && row >= y && row < y + height;
                    final byte expected = inside ? (byte) 0x11 : (byte) 0x00;
                    assertEquals(expected, readback.get(offset), "B of pixel " + column + "," + row);
                    assertEquals(inside ? (byte) 0x22 : (byte) 0x00, readback.get(offset + 1), "G of pixel " + column + "," + row);
                    assertEquals(inside ? (byte) 0x33 : (byte) 0x00, readback.get(offset + 2), "R of pixel " + column + "," + row);
                }
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(tex);
        }
    }

    @Test
    void abandonedStagingBufferIsReusedAndTrimKeepsTheFacadeWorking() {
        final TextureStaging first = GLSMTextures.beginTextureStaging(0, 0, 0, 2, 2);
        assertNotNull(first);
        final ByteBuffer firstBlock = first.buffer();

        GLSMTextures.abandonTextureStaging(first);
        final TextureStaging second = GLSMTextures.beginTextureStaging(0, 0, 0, 2, 2);
        assertNotNull(second);
        assertSame(firstBlock, second.buffer(), "an abandoned block is handed out again");
        GLSMTextures.abandonTextureStaging(second);

        GLSMTextures.trimTextureStaging();

        final TextureStaging afterTrim = GLSMTextures.beginTextureStaging(0, 0, 0, 2, 2);
        assertNotNull(afterTrim, "trimming the cache must not disable staging");
        assertEquals(2 * 2 * 4, afterTrim.buffer().capacity(), "a fresh block still covers the requested rectangle");
        GLSMTextures.abandonTextureStaging(afterTrim);
    }

    @Test
    void bindTexturesOverloadsReachTheStateMachine() {
        final int first = mipmappedTexture();
        final int second = mipmappedTexture();
        try {
            GLStateManager.glBindTextures(0, new int[] {first, second});
            assertEquals(first, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "array overload binds unit 0");
            assertEquals(first, cachedBinding(0), "array overload updates the cache");

            GLStateManager.glBindTextures(1, 1, IntBuffer.wrap(new int[] {second}));
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(second, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "count overload binds unit 1");
            assertEquals(second, cachedBinding(1), "count overload updates the cache");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            GLStateManager.glBindTextures(2, IntBuffer.wrap(new int[] {first}));
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
            assertEquals(first, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "buffer overload binds unit 2");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glBindTextures(0, new int[] {0, 0, 0});
            GLSMTextures.glDeleteTextures(first);
            GLSMTextures.glDeleteTextures(second);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void createTexturesOverloadsRecordTheirTarget() {
        assumeTrue(GL.getCapabilities().OpenGL45, "no glCreateTextures on this context");
        final IntBuffer buffer = BufferUtils.createIntBuffer(2);
        final int[] array = new int[2];
        try {
            GLStateManager.glCreateTextures(GL12.GL_TEXTURE_3D, buffer);
            GLStateManager.glCreateTextures(GL12.GL_TEXTURE_3D, array);

            for (int i = 0; i < 2; i++) {
                assertNotEquals(0, buffer.get(i), "buffer overload creates a name");
                assertNotEquals(0, array[i], "array overload creates a name");
                assertEquals(GL12.GL_TEXTURE_3D, GLSMTextureLifetime.targetOf(buffer.get(i)), "buffer overload records the target");
                assertEquals(GL12.GL_TEXTURE_3D, GLSMTextureLifetime.targetOf(array[i]), "array overload records the target");
            }
        } finally {
            for (int i = 0; i < 2; i++) {
                GLSMTextures.glDeleteTextures(buffer.get(i));
                GLSMTextures.glDeleteTextures(array[i]);
            }
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void bindImageTexturesBindsImageFormatsAndSkipsDeletedNames() {
        final int image = mipmappedTexture();
        final int deleted = mipmappedTexture();
        GLSMTextures.glDeleteTextures(deleted);
        try {
            GLStateManager.glBindImageTextures(1, new int[] {image, deleted});
            final ImageUnitBinding bound = GLSMTextures.getImageUnitBinding(1);
            assertEquals(image, bound.getTexture(), "an RGBA8 name takes an image binding");
            assertEquals(GL15.GL_READ_WRITE, bound.getAccess(), "a multi-bound image is readable and writable");
            assertEquals(0, GLSMTextures.getImageUnitBinding(2).getTexture(), "a deferred name cannot take an image binding");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

            GLStateManager.glBindImageTextures(1, IntBuffer.wrap(new int[] {image}));
            assertEquals(image, GLSMTextures.getImageUnitBinding(1).getTexture(), "buffer overload binds the image");
            GLStateManager.glBindImageTextures(1, 1, IntBuffer.wrap(new int[] {0}));
            assertEquals(0, GLSMTextures.getImageUnitBinding(1).getTexture(), "count overload unbinds the image");
        } finally {
            GLSMTextures.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
            GLSMTextures.glBindImageTexture(2, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
            GLSMTextures.glDeleteTextures(image);
            GLSMTextures.glDeleteTextures(deleted);
        }
    }

    @Test
    void bindSamplersOverloadsReachTheDriver() {
        final int firstSampler = GLSMTextures.glGenSamplers();
        final int secondSampler = GLSMTextures.glGenSamplers();
        try {
            GLStateManager.glBindSamplers(1, new int[] {firstSampler, secondSampler});
            assertEquals(firstSampler, GLSMQueries.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1), "array overload binds unit 1");
            assertEquals(secondSampler, GLSMQueries.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 2), "array overload binds unit 2");

            GLStateManager.glBindSamplers(1, IntBuffer.wrap(new int[] {secondSampler}));
            assertEquals(secondSampler, GLSMQueries.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1), "buffer overload binds unit 1");

            GLStateManager.glBindSamplers(1, 1, IntBuffer.wrap(new int[] {0}));
            assertEquals(0, GLSMQueries.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1), "count overload unbinds unit 1");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLStateManager.glBindSamplers(1, new int[] {0, 0});
            GLSMTextures.glDeleteSamplers(firstSampler);
            GLSMTextures.glDeleteSamplers(secondSampler);
        }
    }

    @Test
    void bindMultiTextureEXTBindsTheGivenUnit() {
        final int tex = mipmappedTexture();
        try {
            GLStateManager.glBindMultiTextureEXT(GL13.GL_TEXTURE1, GL11.GL_TEXTURE_2D, tex);
            assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "active unit restored");
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(tex, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "driver binding");
            assertEquals(tex, cachedBinding(1), "cached binding");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glBindMultiTextureEXT(GL13.GL_TEXTURE1, GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(tex);
        }
    }

    @Test
    void namedFramebufferTextureOverloadsReachTheFramebuffer() {
        final ByteBuffer zeros = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        final int tex1d = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, tex1d);
        GLSMTextures.glTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);

        final int tex2d = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex2d);
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);

        final int tex3d = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, tex3d);
        GLSMTextures.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);

        // An incomplete cube name is enough for the attachment queries. Uploading a face would send a face target to
        // glBindTexture through the bind-based DSA fallback, which the driver rejects with GL_INVALID_ENUM.
        final int cube = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, cube);

        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        try {
            GLSMFramebuffer.glNamedFramebufferTexture1DEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_1D, tex1d, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "after 1D attach");
            assertNamedAttachment(fbo, tex1d, "1D attach");
            GLSMFramebuffer.glNamedFramebufferTexture2DEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex2d, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "after 2D attach");
            assertNamedAttachment(fbo, tex2d, "2D attach");
            GLSMFramebuffer.glNamedFramebufferTexture3DEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, GL12.GL_TEXTURE_3D, tex3d, 0, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "after 3D attach");
            assertNamedAttachment(fbo, tex3d, "3D attach");
            GLSMFramebuffer.glNamedFramebufferTextureEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, tex2d, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "after whole-texture attach");
            assertNamedAttachment(fbo, tex2d, "whole-texture attach");
            GLSMFramebuffer.glNamedFramebufferTextureFaceEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, cube, 0, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "after cube face attach");
            assertNamedAttachment(fbo, cube, "cube face attach");

            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GLSMFramebuffer.glFramebufferTextureFace(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, cube, 0, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y);
            assertEquals(cube, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME), "face attach keeps the cube");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, 0);
            GLSMTextures.glDeleteTextures(tex1d);
            GLSMTextures.glDeleteTextures(tex2d);
            GLSMTextures.glDeleteTextures(tex3d);
            GLSMTextures.glDeleteTextures(cube);
        }
    }

    private static void assertNamedAttachment(int fbo, int expectedTexture, String attachment) {
        assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "named attach restores the draw binding: " + attachment);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(expectedTexture, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME), attachment);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    @Test
    void namedFramebufferLayerAttachmentKeepsTheTextureAlive() {
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GLSMTextures.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        try {
            GLSMFramebuffer.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT0, tex, 0, 0);
            assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "named layer attach restores the draw binding");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            assertEquals(tex, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME), "the named attach reached the framebuffer");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            GLSMTextures.glDeleteTextures(tex);
            framesPass(RELEASE_DELAY_FRAMES * 2);
            assertEquals(SIZE, levelWidth3D(tex, 0), "a layer attachment keeps the storage alive");
            assertEquals(1, GLSMTextureLifetime.deferredTexturesAwaitingRelease(), "the attached name is still deferred");

            GLSMFramebuffer.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT0, 0, 0, 0);
            framesPass(RELEASE_DELAY_FRAMES);
            assertEquals(0, GLSMTextureLifetime.deferredTexturesAwaitingRelease(), "detaching the layer releases the name");
            assertEquals(SIZE, levelWidth3D(tex, 0), "a non-2D name keeps its storage until the driver recycles the name");
            GLSMTextures.glGenTextures();
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
        }
    }

    @Test
    void namedFramebufferRenderbufferReplacesTheTextureSlot() {
        final int tex = mipmappedTexture();
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        final int renderbuffer = GLSMFramebuffer.glGenRenderbuffers();
        try {
            GLSMFramebuffer.glNamedFramebufferTexture2DEXT(fbo, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            GLSMFramebuffer.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            GLSMFramebuffer.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_R8, SIZE, SIZE);
            GLSMFramebuffer.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

            GLSMFramebuffer.glNamedFramebufferRenderbuffer(fbo, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, renderbuffer);
            assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "named renderbuffer attach restores the draw binding");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            assertEquals(GL30.GL_RENDERBUFFER, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE), "the renderbuffer replaced the texture");
            assertEquals(renderbuffer, GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME), "named attach reached the framebuffer");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            GLSMTextures.glDeleteTextures(tex);
            framesPass(RELEASE_DELAY_FRAMES);
            assertEquals(0, levelWidth(tex, 0), "the replaced texture no longer holds its storage");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GLSMFramebuffer.glDeleteRenderbuffers(renderbuffer);
        }
    }
}
