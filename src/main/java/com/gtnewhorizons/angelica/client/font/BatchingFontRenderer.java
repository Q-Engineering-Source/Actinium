package com.gtnewhorizons.angelica.client.font;
import com.google.common.collect.ImmutableSet;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryStack;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IndexBuffer;
import com.gtnewhorizons.angelica.config.FontConfig;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingUploader;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.mixins.interfaces.FontRendererAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Setter;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.ResourceLocation;
import dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.joml.Matrix4f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import com.dhj.actinium.runtime.ActiniumRuntime;


import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import java.util.Comparator;
import java.util.Objects;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryStack.stackPush;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.*;

/**
 * A batching replacement for {@code FontRenderer}
 *
 * @author eigenraven
 */
public class BatchingFontRenderer {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumFontDebug");
    private static final int MAX_FONT_DEBUG_FLUSHES = 64;
    private static final int MAX_FONT_DEBUG_SPLASH_FLUSHES = 4;
    private static final int MAX_FONT_DEBUG_BINDS = 128;
    private static final int MAX_FONT_DEBUG_SPLASH_BINDS = 8;
    private static int fontDebugFlushCount;
    private static int fontDebugSplashFlushCount;
    private static int fontDebugBindCount;
    private static int fontDebugSplashBindCount;

    /** The underlying FontRenderer object that's being accelerated */
    protected FontRenderer underlying;
    /** Array of width of all the characters in default.png */
    protected int[] charWidth;
    /**
     * Array of RGB triplets defining the 16 standard chat colors followed by 16 darker version of the same colors for
     * drop shadows.
     */
    private final int[] colorCode;
    /** Location of the primary font atlas to bind. */
    protected final ResourceLocation locationFontTexture;
    private final TextureManager textureManager;

    final boolean isSGA;
    final boolean isSplash;

    /**
     * @return {@code true} when this batcher accelerates a splash-screen font renderer
     *         (Forge/Cleanroom SplashProgress or Modern Splash's SplashFontRenderer)
     */
    public boolean isSplash() {
        return this.isSplash;
    }

    /** For use with modded books. Affects calculations and forces some defaults. */
    @Setter
    boolean bookMode = false;

    public BatchingFontRenderer(FontRenderer underlying, int[] charWidth, int[] colorCode, ResourceLocation locationFontTexture,
        TextureManager textureManager) {
        this.underlying = underlying;
        this.charWidth = charWidth;
        this.colorCode = colorCode;
        this.locationFontTexture = locationFontTexture;
        this.textureManager = textureManager;

        for (int i = 0; i < 64; i++) {
            batchCommandPool.add(new FontDrawCmd());
        }

        this.isSGA = Objects.equals(this.locationFontTexture.getPath(), "textures/font/ascii_sga.png");
        this.isSplash = FontStrategist.isSplashFontRendererActive(underlying);

        FontProviderMC.get(this.isSGA).charWidth = this.charWidth;
        FontProviderMC.get(this.isSGA).locationFontTexture = this.locationFontTexture;

        logFontDebug(
            "font-init-cpu renderer={} texture={} isSGA={} isSplash={} charWidthLen={} unicode={}",
            underlying.getClass().getName(),
            this.locationFontTexture,
            this.isSGA,
            this.isSplash,
            this.charWidth != null ? this.charWidth.length : -1,
            underlying.getUnicodeFlag()
        );
    }

    private static final int INITIAL_BATCH_SIZE = 2048;
    private static final ResourceLocation DUMMY_RESOURCE_LOCATION = new ResourceLocation("angelica$dummy",
        "this is invalid!");

    // Layout in data:
    // [v, v, t, t, c, c, c, c, tb, tb, tb, tb]
    // v, t and tb are floats, c is bytes; 36 bytes total
    private static final int VERTEX_SIZE = 36;
    private static int rawCapacity = INITIAL_BATCH_SIZE * VERTEX_SIZE;
    private static ByteBuffer vertexData = memAlloc(rawCapacity);
    private static long vertexDataAddress = memAddress0(vertexData);
    private static int vboCapacity;


    // OpenGL objects are shared between all font renderers and safely published after first use.
    private static final GlResourceInitializer<SharedGlResources> SHARED_GL_RESOURCES =
        new GlResourceInitializer<>(BatchingFontRenderer::hasCurrentGlContext, BatchingFontRenderer::createSharedGlResources);

    private static final class SharedGlResources {

        private final Program fontShader;
        private final int fontShaderId;
        private final int samplerLocation;
        private final int aaModeLocation;
        private final int aaStrengthLocation;
        private final int alphaTestRefLocation;
        private final int mvpMatrixLocation;
        private final int fontVao;
        private final int vbo;
        private final IndexBuffer ebo;
        private int eboCapacity;

        private SharedGlResources(Program fontShader, int fontShaderId, int samplerLocation, int aaModeLocation,
            int aaStrengthLocation, int alphaTestRefLocation, int mvpMatrixLocation, int fontVao, int vbo,
            IndexBuffer ebo) {
            this.fontShader = fontShader;
            this.fontShaderId = fontShaderId;
            this.samplerLocation = samplerLocation;
            this.aaModeLocation = aaModeLocation;
            this.aaStrengthLocation = aaStrengthLocation;
            this.alphaTestRefLocation = alphaTestRefLocation;
            this.mvpMatrixLocation = mvpMatrixLocation;
            this.fontVao = fontVao;
            this.vbo = vbo;
            this.ebo = ebo;
        }
    }

    private int batchDepth = 0;

    private int vertexDataPos = 0;
    private int idxWriterIndex = 0;

    private final ObjectArrayList<FontDrawCmd> batchCommands = ObjectArrayList.wrap(new FontDrawCmd[64], 0);
    private final ObjectArrayList<FontDrawCmd> batchCommandPool = ObjectArrayList.wrap(new FontDrawCmd[64], 0);

    private int blendSrcRGB = GL11.GL_SRC_ALPHA;
    private int blendDstRGB = GL11.GL_ONE_MINUS_SRC_ALPHA;


    private static boolean hasCurrentGlContext() {
        final boolean current = WindowBackends.get().currentContextHandle() != 0L;
        if (!current) {
            LOGGER.error("Font rendering attempted without a current OpenGL context on thread {}", Thread.currentThread().getName());
        }
        return current;
    }

    private static SharedGlResources createSharedGlResources() {
        final String vsh = ShaderLoader.getShaderSource("angelica:fontFilter.vsh");
        final String fsh = ShaderLoader.getShaderSource("angelica:fontFilter.fsh");
        final Program fontShader = ProgramBuilder.begin("fontFilter", vsh, null, fsh, ImmutableSet.of(0)).build();
        //noinspection deprecation
        final int fontShaderId = fontShader.getProgramId();
        final int samplerLocation = GLStateManager.glGetUniformLocation(fontShaderId, "sampler");
        final int aaModeLocation = GLStateManager.glGetUniformLocation(fontShaderId, "aaMode");
        final int aaStrengthLocation = GLStateManager.glGetUniformLocation(fontShaderId, "strength");
        final int alphaTestRefLocation = GLStateManager.glGetUniformLocation(fontShaderId, "alphaTestRef");
        final int mvpMatrixLocation = GLStateManager.glGetUniformLocation(fontShaderId, "u_MVPMatrix");
        final IndexBuffer ebo = new IndexBuffer();
        final int vbo = GLStateManager.glGenBuffers();
        final int fontVao = GLStateManager.glGenVertexArrays();
        final SharedGlResources resources = new SharedGlResources(
            fontShader,
            fontShaderId,
            samplerLocation,
            aaModeLocation,
            aaStrengthLocation,
            alphaTestRefLocation,
            mvpMatrixLocation,
            fontVao,
            vbo,
            ebo
        );
        populateEBO(resources, rawCapacity / VERTEX_SIZE);
        logFontDebug(
            "font-init-gl shader={} samplerLoc={} aaLoc={} strengthLoc={} alphaLoc={} mvpLoc={} vao={} vbo={}",
            fontShaderId,
            samplerLocation,
            aaModeLocation,
            aaStrengthLocation,
            alphaTestRefLocation,
            mvpMatrixLocation,
            fontVao,
            vbo
        );
        return resources;
    }

    private static void populateEBO(SharedGlResources resources, int capacity) {
        final int quadCount = capacity * 6;
        final ByteBuffer data = memAlloc(quadCount * 6 * 2);
        long ptr = memAddress0(data);
        for (int i = 0; i < quadCount; i++) {
            int base = (i * 4);

            // triangle 1
            memPutShort(ptr, (short) base);
            memPutShort(ptr + 2, (short) (base + 1));
            memPutShort(ptr + 4, (short) (base + 2));

            // triangle 2
            memPutShort(ptr + 6, (short) (base + 2));
            memPutShort(ptr + 8, (short) (base + 1));
            memPutShort(ptr + 10, (short) (base + 3));
            ptr += 12;
        }

        resources.ebo.upload(data);
        resources.eboCapacity = capacity;

        memFree(data);

    }

    /**
     * Converts normalized GL color components to an ARGB int.
     *
     * @return the packed ARGB value, e.g. (1.0F, 1.0F, 1.0F, 1.0F) becomes {@code 0xFFFFFFFF}
     */
    public static int floatsToArgb(float r, float g, float b, float a) {
        return (Math.round(a * 255.0F) & 0xFF) << 24
            | (Math.round(r * 255.0F) & 0xFF) << 16
            | (Math.round(g * 255.0F) & 0xFF) << 8
            | (Math.round(b * 255.0F) & 0xFF);
    }

    /**
     * Reads the current GL color as an ARGB int.
     *
     * <p>Splash font renderers (e.g. Modern Splash's SplashFontRenderer) draw text with an
     * explicit color of 0 while relying on the fixed-pipeline current color, which they set to
     * their configured font color before drawing. Vanilla 1.12.2 {@code renderString} would
     * force black for color 0, so the current color is sampled here instead.</p>
     *
     * <p>The value comes from the GLSM color state rather than a {@code GL_CURRENT_COLOR} query:
     * the splash renderer's {@code glColor*} calls are redirected into the GLSM state cache (the
     * GL context is a core profile where the fixed-pipeline current color is not a real queryable
     * state).</p>
     */
    public static int readCurrentGlColorAsArgb() {
        Color4 color = GLStateManager.getColor();
        return floatsToArgb(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    private void ensureCapacity() {
        if (vertexDataPos + (4 * VERTEX_SIZE) > rawCapacity) {
            rawCapacity *= 2;
            vertexData = memRealloc(vertexData, rawCapacity);
            vertexDataAddress = memAddress0(vertexData);
        }
    }

    private void pushVtx(float x, float y, int rgba, float u, float v, float uMin, float uMax, float vMin, float vMax) {
        final long ptr = vertexDataAddress + vertexDataPos;

        // v, v
        memPutFloat(ptr, x);
        memPutFloat(ptr + 4, y);

        // t, t
        memPutFloat(ptr + 8, u);
        memPutFloat(ptr + 12, v);

        // c, c, c, c
        // 0xAARRGGBB
        memPutByte(ptr + 16, (byte) ((rgba >> 16) & 0xFF));
        memPutByte(ptr + 17, (byte) ((rgba >> 8) & 0xFF));
        memPutByte(ptr + 18, (byte) (rgba & 0xFF));
        memPutByte(ptr + 19, (byte) ((rgba >> 24) & 0xFF));

        // tb, tb, tb, tb
        memPutFloat(ptr + 20, uMin);
        memPutFloat(ptr + 24, uMax);
        memPutFloat(ptr + 28, vMin);
        memPutFloat(ptr + 32, vMax);

        vertexDataPos += VERTEX_SIZE;
    }

    private void pushUntexRect(float x, float y, float w, float h, int rgba) {
        ensureCapacity();
        pushVtx(x, y, rgba, 0, 0, -1, 0, 0, 0);
        pushVtx(x, y + h, rgba, 0, 0, -1, 0, 0, 0);
        pushVtx(x + w, y, rgba, 0, 0, -1, 0, 0, 0);
        pushVtx(x + w, y + h, rgba, 0, 0, -1, 0, 0, 0);
        pushQuadIdx();
    }

    private void pushTexRect(float x, float y, float w, float h, float itOff, int rgba, float uStart, float vStart, float uSz, float vSz) {
        ensureCapacity();
        pushVtx(x + itOff, y, rgba, uStart, vStart, uStart, uStart + uSz, vStart, vStart + vSz);
        pushVtx(x - itOff, y + h, rgba, uStart, vStart + vSz, uStart, uStart + uSz, vStart, vStart + vSz);
        pushVtx(x + itOff + w, y, rgba, uStart + uSz, vStart, uStart, uStart + uSz, vStart, vStart + vSz);
        pushVtx(x - itOff + w, y + h, rgba, uStart + uSz, vStart + vSz, uStart, uStart + uSz, vStart, vStart + vSz);
        pushQuadIdx();
    }

    private void pushQuadIdx() {
        idxWriterIndex += 6;
    }

    private void pushDrawCmd(int startIdx, int idxCount, ResourceLocation texture, boolean isUnicode) {
        if (!batchCommands.isEmpty()) {
            final FontDrawCmd lastCmd = batchCommands.get(batchCommands.size() - 1);
            final int prevEndVtx = lastCmd.startVtx + lastCmd.idxCount;
            if (prevEndVtx == startIdx && lastCmd.texture == texture) {
                lastCmd.idxCount += idxCount;
                return;
            }
        }
        if (batchCommandPool.isEmpty()) {
            for (int i = 0; i < 64; i++) {
                batchCommandPool.add(new FontDrawCmd());
            }
        }
        final FontDrawCmd cmd = batchCommandPool.pop();
        cmd.reset(startIdx, idxCount, texture, isUnicode);
        batchCommands.add(cmd);
    }

    private static final class FontDrawCmd {

        public int startVtx;
        public int idxCount;
        public boolean isUnicode;
        public ResourceLocation texture;

        public void reset(int startVtx, int vtxCount, ResourceLocation texture, boolean isUnicode) {
            this.startVtx = startVtx;
            this.idxCount = vtxCount;
            this.texture = texture;
            this.isUnicode = isUnicode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (FontDrawCmd) obj;
            return this.startVtx == that.startVtx && this.idxCount == that.idxCount && Objects.equals(this.texture,
                that.texture);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startVtx, idxCount, texture);
        }

        @Override
        public String toString() {
            return "FontDrawCmd["
                + "startVtx="
                + startVtx
                + ", "
                + "vtxCount="
                + idxCount
                + ", "
                + "texture="
                + texture
                + ']';
        }

        public static final Comparator<FontDrawCmd> DRAW_ORDER_COMPARATOR = Comparator.comparing((FontDrawCmd fdc) -> fdc.texture,
            Comparator.nullsLast(Comparator.comparing(ResourceLocation::getNamespace)
                .thenComparing(ResourceLocation::getPath))).thenComparing(fdc -> fdc.startVtx);
    }

    /**
     * Starts a new batch of font rendering operations. Can be called from within another batch with a matching end, to
     * allow for easier optimizing of blocks of font rendering code.
     */
    public void beginBatch() {
        batchDepth++;
    }

    public void endBatch() {
        if (batchDepth <= 0) {
            batchDepth = 0;
            return;
        }
        batchDepth--;
        if (batchDepth == 0) {
            flushBatch();
        }
    }

    private static final Matrix4f scratchMvp = new Matrix4f();
    private int fontAAModeLast = -1;
    private int fontAAStrengthLast = -1;

    private void flushBatch() {
        if (vertexDataPos == 0) {
            clearBatch();
            return;
        }

        final SharedGlResources resources = SHARED_GL_RESOURCES.get();
        final int requiredEboCapacity = rawCapacity / VERTEX_SIZE;
        if (resources.eboCapacity < requiredEboCapacity) {
            populateEBO(resources, requiredEboCapacity);
        }

        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, resources.vbo);
        vertexData.limit(vertexDataPos);
        vboCapacity = StreamingUploader.upload(vertexData, vboCapacity);

        final int prevProgram = GLStateManager.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        batchCommands.sort(FontDrawCmd.DRAW_ORDER_COMPARATOR);

        final boolean isAlphaTestEnabledBefore = GLStateManager.glIsEnabled(GL11.GL_ALPHA_TEST);
        final boolean isBlendEnabledBefore = GLStateManager.glIsEnabled(GL11.GL_BLEND);
        final Color4 colorBefore = GLStateManager.getColor().copy();
        final int activeTextureBefore = GLStateManager.getActiveTextureUnit();
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        final boolean isTextureEnabledBefore = GLStateManager.glIsEnabled(GL11.GL_TEXTURE_2D);
        final int boundTextureBefore = GLStateManager.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean textureChanged = false;
        final boolean logFlush = shouldLogFontDebugFlush(this.isSplash);
        if (logFlush) {
            LOGGER.info(
                "font-flush-start renderer={} shader={} prevProgram={} vertexBytes={} idx={} cmds={} activeBefore={} tex0Before={} texEnabled={} alphaEnabled={} blendEnabled={} vao={} vbo={} unicode={} aaMode={} aaStrength={} alphaRef={}",
                underlying.getClass().getName(),
                resources.fontShaderId,
                prevProgram,
                vertexDataPos,
                idxWriterIndex,
                batchCommands.size(),
                activeTextureBefore,
                boundTextureBefore,
                isTextureEnabledBefore,
                isAlphaTestEnabledBefore,
                isBlendEnabledBefore,
                resources.fontVao,
                resources.vbo,
                underlying.getUnicodeFlag(),
                FontConfig.fontAAMode,
                FontConfig.fontAAStrength,
                GLStateManager.getAlphaState().getReference()
            );
        }
        ResourceLocation lastTexture = DUMMY_RESOURCE_LOCATION;
        GLStateManager.enableTexture();
        GLStateManager.enableAlphaTest();
        GLStateManager.enableBlend();
        GLStateManager.tryBlendFuncSeparate(blendSrcRGB, blendDstRGB, GL11.GL_ONE, GL11.GL_ZERO);
        GLStateManager.glShadeModel(GL11.GL_FLAT);

        GLStateManager.glUseProgram(resources.fontShaderId);
        GLStateManager.glUniform1i(resources.samplerLocation, 0);
        if (FontConfig.fontAAMode != fontAAModeLast) {
            fontAAModeLast = FontConfig.fontAAMode;
            GLStateManager.glUniform1i(resources.aaModeLocation, FontConfig.fontAAMode);
        }
        if (FontConfig.fontAAStrength != fontAAStrengthLast) {
            fontAAStrengthLast = FontConfig.fontAAStrength;
            GLStateManager.glUniform1f(resources.aaStrengthLocation, FontConfig.fontAAStrength / 120.f);
        }
        GLStateManager.glUniform1f(resources.alphaTestRefLocation, GLStateManager.getAlphaState().getReference());
        try (MemoryStack stack = stackPush()) {
            final FloatBuffer mvpBuf = stack.mallocFloat(16);
            GLStateManager.getProjectionMatrix().mul(GLStateManager.getModelViewMatrix(), scratchMvp);
            scratchMvp.get(mvpBuf);
            GLStateManager.glUniformMatrix4(resources.mvpMatrixLocation, false, mvpBuf);
        }

        GLStateManager.glBindVertexArray(resources.fontVao);
        setupFontVertexArray(resources);
        if (logFlush) {
            LOGGER.info(
                "font-shader-ready shader={} activeProgram={} samplerLoc={} samplerUnit={} aaMode={} aaStrength={} alphaRef={}",
                resources.fontShaderId,
                GLStateManager.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                resources.samplerLocation,
                0,
                FontConfig.fontAAMode,
                FontConfig.fontAAStrength,
                GLStateManager.getAlphaState().getReference()
            );
        }

        // Use plain for loop to avoid allocations
        final FontDrawCmd[] cmdsData = batchCommands.elements();
        final int cmdsSize = batchCommands.size();
        for (int i = 0; i < cmdsSize; i++) {
            final FontDrawCmd cmd = cmdsData[i];
            if (!Objects.equals(lastTexture, cmd.texture)) {
                if (lastTexture == null) {
                    GLStateManager.glEnable(GL11.GL_TEXTURE_2D);
                } else if (cmd.texture == null) {
                    GLStateManager.glDisable(GL11.GL_TEXTURE_2D);
                }
                if (cmd.texture != null) {
                    final int textureId = bindFontTexture(cmd.texture);
                    logFontTextureBind(cmd.texture, textureId, cmd.isUnicode, this.isSplash, cmd.startVtx, cmd.idxCount);
                    textureChanged = true;
                }
                lastTexture = cmd.texture;
            }
            GLStateManager.glDrawElements(GL11.GL_TRIANGLES, cmd.idxCount, GL11.GL_UNSIGNED_SHORT, (long) cmd.startVtx * 2L);
        }


        GLStateManager.glUseProgram(prevProgram);

        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glBindVertexArray(0);

        if (isTextureEnabledBefore) {
            GLStateManager.glEnable(GL11.GL_TEXTURE_2D);
        } else {
            GLStateManager.glDisable(GL11.GL_TEXTURE_2D);
        }
        // The alpha test is deliberately left enabled instead of being restored to isAlphaTestEnabledBefore.
        // Vanilla's FontRenderer.drawString opens with GlStateManager.enableAlpha() and the class contains no
        // disableAlpha at all (net.minecraft.client.gui.FontRenderer:235 - the only alpha/blend call in it), so
        // drawing any string leaves the alpha test enabled. Third-party map GUIs depend on that side effect:
        // Xaero's RadarRenderer#postRender disables the alpha test without restoring it and relies on a later
        // string draw turning it back on, so restoring the previous value here left its GuiTexturedButton icons
        // drawing under alphaTest=false + blend=false and wrote their transparent texels as an opaque plate.
        GLStateManager.enableAlphaTest();
        if (!isBlendEnabledBefore) {
            GLStateManager.disableBlend();
        }
        if (textureChanged) {
            net.minecraft.client.renderer.GlStateManager.bindTexture(boundTextureBefore);
        }
        GLStateManager.glColor4f(colorBefore.getRed(), colorBefore.getGreen(), colorBefore.getBlue(), colorBefore.getAlpha());
        if (logFlush) {
            LOGGER.info(
                "font-flush-end shader={} restoredProgram={} tex0Now={} activeBeforeRestore={} restoreTex={} textureChanged={} restoreColor=[{},{},{},{}]",
                resources.fontShaderId,
                prevProgram,
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GLStateManager.getActiveTextureUnit(),
                boundTextureBefore,
                textureChanged,
                colorBefore.getRed(),
                colorBefore.getGreen(),
                colorBefore.getBlue(),
                colorBefore.getAlpha()
            );
        }
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + activeTextureBefore);

        clearBatch();
    }

    private void clearBatch() {
        batchCommandPool.addAll(batchCommands);
        batchCommands.clear();
        vertexDataPos = 0;
        idxWriterIndex = 0;
    }

    private static void setupFontVertexArray(SharedGlResources resources) {
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, resources.vbo);
        resources.ebo.bind();

        // position
        GLStateManager.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, VERTEX_SIZE, 0);
        GLStateManager.glEnableVertexAttribArray(0);

        // color
        GLStateManager.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_SIZE, 16);
        GLStateManager.glEnableVertexAttribArray(1);

        // texcoords
        GLStateManager.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, VERTEX_SIZE, 8);
        GLStateManager.glEnableVertexAttribArray(2);

        // tex bounds
        GLStateManager.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, VERTEX_SIZE, 20);
        GLStateManager.glEnableVertexAttribArray(3);

        GLStateManager.glDisableVertexAttribArray(4);

    }

    public static boolean charInRange(char what, char fromInclusive, char toInclusive) {
        return (what >= fromInclusive) && (what <= toInclusive);
    }

    public boolean forceDefaults() {
        return this.bookMode || this.isSGA || this.isSplash;
    }

    public float getGlyphScaleX() {
        return forceDefaults() ? 1 : (float) (FontConfig.glyphScale * Math.pow(2, FontConfig.glyphAspect));
    }

    public float getGlyphScaleY() {
        return forceDefaults() ? 1 : (float) (FontConfig.glyphScale / Math.pow(2, FontConfig.glyphAspect));
    }

    public float getGlyphSpacing() {
        return forceDefaults() ? 0 : FontConfig.glyphSpacing;
    }

    public float getWhitespaceScale() {
        return forceDefaults() ? 1 : FontConfig.whitespaceScale;
    }

    public float getShadowOffset() {
        return forceDefaults() ? 1 : FontConfig.fontShadowOffset;
    }

    private static final char FORMATTING_CHAR = 167; // §

    public float drawString(final float anchorX, final float anchorY, final int color, final boolean enableShadow,
        final boolean unicodeFlag, final CharSequence string, int stringOffset, int stringLength) {
        // noinspection SizeReplaceableByIsEmpty
        if (string == null || string.length() == 0) {
            return anchorX + (enableShadow ? 1.0f : 0.0f);
        }
        final int shadowColor = (color & 0xfcfcfc) >> 2 | color & 0xff000000;

        FontProviderMC.get(this.isSGA).charWidth = this.charWidth;
        FontProviderMC.get(this.isSGA).locationFontTexture = this.locationFontTexture;

        this.beginBatch();
        float curX = anchorX;
        try {
            final int totalStringLength = string.length();
            stringOffset = MathHelper.clamp(stringOffset, 0, totalStringLength);
            stringLength = MathHelper.clamp(stringLength, 0, totalStringLength - stringOffset);
            if (stringLength <= 0) {
                return 0;
            }
            final int stringEnd = stringOffset + stringLength;

            int curColor = color;
            int curShadowColor = shadowColor;
            boolean curItalic = false;
            boolean curRandom = false;
            boolean curBold = false;
            boolean curStrikethrough = false;
            boolean curUnderline = false;

            float glyphScaleY = getGlyphScaleY();
            float glyphScaleX = getGlyphScaleX();
            float heightNorth = anchorY + (underlying.FONT_HEIGHT - 1.0f) * (0.5f - glyphScaleY / 2);

            final float underlineY = heightNorth + (underlying.FONT_HEIGHT - 1.0f) * glyphScaleY;
            float underlineStartX = 0.0f;
            float underlineEndX = 0.0f;

            final float strikethroughY = heightNorth + ((float) (underlying.FONT_HEIGHT / 2) - 1.0f) * glyphScaleY;
            float strikethroughStartX = 0.0f;
            float strikethroughEndX = 0.0f;

            for (int charIdx = stringOffset; charIdx < stringEnd; charIdx++) {
                char chr = string.charAt(charIdx);
                if (chr == FORMATTING_CHAR && (charIdx + 1) < stringEnd) {
                    final char fmtCode = Character.toLowerCase(string.charAt(charIdx + 1));
                    charIdx++;

                    if (curUnderline && underlineStartX != underlineEndX) {
                        final int ulIdx = idxWriterIndex;
                        pushUntexRect(underlineStartX, underlineY, underlineEndX - underlineStartX, glyphScaleY, curColor);
                        pushDrawCmd(ulIdx, 6, null, false);
                        underlineStartX = underlineEndX;
                    }
                    if (curStrikethrough && strikethroughStartX != strikethroughEndX) {
                        final int ulIdx = idxWriterIndex;
                        pushUntexRect(
                            strikethroughStartX,
                            strikethroughY,
                            strikethroughEndX - strikethroughStartX,
                            glyphScaleY,
                            curColor);
                        pushDrawCmd(ulIdx, 6, null, false);
                        strikethroughStartX = strikethroughEndX;
                    }

                    final boolean is09 = charInRange(fmtCode, '0', '9');
                    final boolean isAF = charInRange(fmtCode, 'a', 'f');
                    if (is09 || isAF) {
                        curRandom = false;
                        curBold = false;
                        curStrikethrough = false;
                        curUnderline = false;
                        curItalic = false;

                        final int colorIdx = is09 ? (fmtCode - '0') : (fmtCode - 'a' + 10);
                        final int rgb = this.colorCode[colorIdx];
                        curColor = (curColor & 0xFF000000) | (rgb & 0x00FFFFFF);
                        final int shadowRgb = this.colorCode[colorIdx + 16];
                        curShadowColor = (curShadowColor & 0xFF000000) | (shadowRgb & 0x00FFFFFF);
                    } else if (fmtCode == 'k') {
                        curRandom = true;
                    } else if (fmtCode == 'l') {
                        curBold = true;
                    } else if (fmtCode == 'm') {
                        curStrikethrough = true;
                        strikethroughStartX = curX - 1.0f;
                        strikethroughEndX = strikethroughStartX;
                    } else if (fmtCode == 'n') {
                        curUnderline = true;
                        underlineStartX = curX - 1.0f;
                        underlineEndX = underlineStartX;
                    } else if (fmtCode == 'o') {
                        curItalic = true;
                    } else if (fmtCode == 'r') {
                        curRandom = false;
                        curBold = false;
                        curStrikethrough = false;
                        curUnderline = false;
                        curItalic = false;
                        curColor = color;
                        curShadowColor = shadowColor;
                    }

                    continue;
                }

                if (curRandom) {
                    chr = FontProviderMC.get(this.isSGA).getRandomReplacement(chr);
                }

                FontProvider fontProvider = FontStrategist.getFontProvider(this, chr, FontConfig.enableCustomFont, unicodeFlag);
                if (!fontProvider.isGlyphAvailable(chr)) {
                    continue;
                }

                heightNorth = anchorY + (underlying.FONT_HEIGHT - 1.0f) * (0.5f - glyphScaleY * fontProvider.getYScaleMultiplier() / 2);
                float heightSouth = (underlying.FONT_HEIGHT - 1.0f) * glyphScaleY * fontProvider.getYScaleMultiplier();

                // Check ASCII space, NBSP, NNBSP
                if (chr == ' ' || chr == '\u00A0' || chr == '\u202F') {
                    curX += 4 * this.getWhitespaceScale() + (curBold ? 1 : 0);
                    continue;
                }

                final float uStart = fontProvider.getUStart(chr);
                final float vStart = fontProvider.getVStart(chr);
                final float xAdvance = fontProvider.getXAdvance(chr) * glyphScaleX;
                final float glyphW = fontProvider.getGlyphW(chr) * glyphScaleX;
                final float uSz = fontProvider.getUSize(chr);
                final float vSz = fontProvider.getVSize(chr);
                final float itOff = curItalic ? 1.0F : 0.0F; // italic offset
                final float shadowOffset = fontProvider.getShadowOffset();
                final int shadowCopies = FontConfig.shadowCopies;
                final int boldCopies = FontConfig.boldCopies;
                final ResourceLocation texture = fontProvider.getTexture(chr);
                final int idxId = idxWriterIndex;

                if (enableShadow) {
                    for (int n = 1; n <= shadowCopies; n++) {
                        final float shadowOffsetPart = shadowOffset * ((float) n / shadowCopies);
                        pushTexRect(curX + shadowOffsetPart, heightNorth + shadowOffsetPart, glyphW - 1.0f, heightSouth, itOff, curShadowColor, uStart, vStart, uSz, vSz);

                        if (curBold) {
                            pushTexRect(curX + 2.0f * shadowOffsetPart, heightNorth + shadowOffsetPart, glyphW - 1.0f, heightSouth, itOff, curShadowColor, uStart, vStart, uSz, vSz);
                        }
                    }
                }

                pushTexRect(curX, heightNorth, glyphW - 1.0f, heightSouth, itOff, curColor, uStart, vStart, uSz, vSz);

                if (curBold) {
                    for (int n = 1; n <= boldCopies; n++) {
                        final float shadowOffsetPart = shadowOffset * ((float) n / boldCopies);
                        pushTexRect(curX + shadowOffsetPart, heightNorth, glyphW - 1.0f, heightSouth, itOff, curColor, uStart, vStart, uSz, vSz);
                    }
                }

                /*
                Vertex-per-char counts for different configurations
                    default:        4
                    shadow only:    4(1 + shadowCopies)
                    bold only:      4(1 + boldCopies)
                    both:           4(1 + 2 * shadowCopies + boldCopies)
                 */
                int charCount = 1;
                if (enableShadow) { charCount += shadowCopies * (curBold ? 2 : 1); }
                if (curBold) { charCount += boldCopies; }
                final int vtxCount = 4 * charCount;
                pushDrawCmd(idxId, vtxCount / 2 * 3, texture, chr > 255);

                curX += (xAdvance + (curBold ? 1.0f : 0.0f)) + getGlyphSpacing();
                if (bookMode) { curX = (int) curX; }
                underlineEndX = curX;
                strikethroughEndX = curX;
            }

            if (curUnderline && underlineStartX != underlineEndX) {
                final int ulIdx = idxWriterIndex;
                pushUntexRect(underlineStartX, underlineY, underlineEndX - underlineStartX, glyphScaleY, curColor);
                pushDrawCmd(ulIdx, 6, null, false);
            }
            if (curStrikethrough && strikethroughStartX != strikethroughEndX) {
                final int ulIdx = idxWriterIndex;
                pushUntexRect(
                    strikethroughStartX,
                    strikethroughY,
                    strikethroughEndX - strikethroughStartX,
                    glyphScaleY,
                    curColor);
                pushDrawCmd(ulIdx, 6, null, false);
            }

        } finally {
            this.endBatch();
        }
        return curX + (enableShadow ? 1.0f : 0.0f);
    }

    public float getCharWidthFine(char chr) {
        if (chr == FORMATTING_CHAR) { return -1; }

        if (chr == ' ' || chr == '\u00A0' || chr == '\u202F') {
            return 4 * this.getWhitespaceScale();
        }

        FontProvider fp = FontStrategist.getFontProvider(this, chr, FontConfig.enableCustomFont, underlying.getUnicodeFlag());

        return fp.getXAdvance(chr) * this.getGlyphScaleX();
    }

    public void overrideBlendFunc(int srcRgb, int dstRgb) {
        blendSrcRGB = srcRgb;
        blendDstRGB = dstRgb;
    }

    public void resetBlendFunc() {
        blendSrcRGB = GL11.GL_SRC_ALPHA;
        blendDstRGB = GL11.GL_ONE_MINUS_SRC_ALPHA;
    }

    private int bindFontTexture(ResourceLocation texture) {
        if (textureManager == null) {
            ((FontRendererAccessor) underlying).angelica$bindTexture(texture);
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        ITextureObject textureObject = textureManager.getTexture(texture);
        if (textureObject == null) {
            textureObject = new SimpleTexture(texture);
            textureManager.loadTexture(texture, textureObject);
        }

        final int textureId = textureObject.getGlTextureId();
        ((FontRendererAccessor) underlying).angelica$bindTexture(texture);
        return textureId;
    }

    private static boolean shouldLogFontDebug() {
        String override = System.getProperty("actinium.fontDebug");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }

        try {
            return ActiniumRuntime.options().debug.enableActiniumGlDebug;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void logFontDebug(String message, Object... params) {
        if (shouldLogFontDebug()) {
            LOGGER.info(message, params);
        }
    }

    private static boolean shouldLogFontDebugFlush(boolean splash) {
        if (!shouldLogFontDebug()) {
            return false;
        }
        if (splash) {
            return fontDebugSplashFlushCount++ < MAX_FONT_DEBUG_SPLASH_FLUSHES;
        }
        return fontDebugFlushCount++ < MAX_FONT_DEBUG_FLUSHES;
    }

    private void logFontTextureBind(ResourceLocation texture, int textureId, boolean unicode, boolean splash, int startVtx, int idxCount) {
        if (!shouldLogFontDebug()) {
            return;
        }
        if (splash) {
            if (fontDebugSplashBindCount++ >= MAX_FONT_DEBUG_SPLASH_BINDS) {
                return;
            }
        } else if (fontDebugBindCount++ >= MAX_FONT_DEBUG_BINDS) {
            return;
        }

        String textureClass = "missing";
        try {
            if (textureManager == null) {
                textureClass = "no-texture-manager";
            } else {
                ITextureObject textureObject = textureManager.getTexture(texture);
                if (textureObject != null) {
                    textureClass = textureObject.getClass().getName();
                }
            }
        } catch (RuntimeException e) {
            textureClass = "error:" + e.getClass().getSimpleName();
        }

        LOGGER.info(
            "font-bind texture={} unicode={} splash={} managerId={} bound2D={} active={} textureClass={} startVtx={} idxCount={} tex={}x{} internal=0x{} alphaBits={} redBits={} greenBits={} blueBits={}",
            texture,
            unicode,
            splash,
            textureId,
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            GLStateManager.getActiveTextureUnit(),
            textureClass,
            startVtx,
            idxCount,
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT),
            Integer.toHexString(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)),
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_ALPHA_SIZE),
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_RED_SIZE),
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_GREEN_SIZE),
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_BLUE_SIZE)
        );
    }
}
