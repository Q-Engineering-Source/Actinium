package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizons.angelica.glsm.ClipPlaneBooleanState;
import com.gtnewhorizons.angelica.glsm.stacks.AlphaStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Color4Stack;
import com.gtnewhorizons.angelica.glsm.stacks.ColorMaskStack;
import com.gtnewhorizons.angelica.glsm.stacks.ClientAttribStack;
import com.gtnewhorizons.angelica.glsm.stacks.CowDepths;
import com.gtnewhorizons.angelica.glsm.stacks.CowDispatch;
import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.DepthStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightModelStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LineStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MaterialStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MatrixModeStack;
import com.gtnewhorizons.angelica.glsm.stacks.PointStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.PolygonStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.ScissorStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.StackIdAllocator;
import com.gtnewhorizons.angelica.glsm.stacks.StencilStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.states.ClipPlaneState;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.glsm.states.ImageUnitArray;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import com.gtnewhorizons.angelica.glsm.states.PixelPackState;
import com.gtnewhorizons.angelica.glsm.states.PixelUnpackState;
import com.gtnewhorizons.angelica.glsm.states.TextureUnitArray;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import com.gtnewhorizons.angelica.glsm.states.BufferGenerationTracker;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingVaos;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderCache;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4fStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-GL-context state tracked by {@link GLStateManager}. The primary context is created lazily
 * on first access; worker contexts (parallel chunk building) get their own instance via
 * {@code enterWorkerContext()}. After a display-context migration the cached values here are
 * replayed onto the new context via {@link GLStateManager#replayStateToBackend()}.
 */
public class GLContextState {
    private static final Logger MEMORY_LOGGER = LogManager.getLogger("GLSMContextMemory");

    /** Stable native context lifetime that owns every context-local field in this state object. */
    GLContextIdentity identity;
    /** Context-local FFP program selection and uniforms over a share-group-owned linked-program cache. */
    final ShaderManager shaderManager;

    /** Every BooleanStateStack owned by this context, for {@link GLStateManager#replayStateToBackend()}. */
    final List<BooleanStateStack> allBooleanStates = new ArrayList<>();

    private final List<IStateStack<?>> allStacksBuilder = new ArrayList<>();

    public BooleanStateStack track(BooleanStateStack s) {
        s.setStackId(StackIdAllocator.nextId());
        allBooleanStates.add(s);
        allStacksBuilder.add(s);
        return s;
    }

    public <T extends IStateStack<?>> T member(T stack) {
        allStacksBuilder.add(stack);
        return stack;
    }

    public boolean poppingAttributes;
    // modelview matrix changes (any: translation, rotation, scale)
    public int mvGeneration;
    // projection matrix changes
    public int projGeneration;
    // texture matrix changes
    public int texMatrixGeneration;
    // GL_COLOR matrix changes (Actinium extension for ARB_imaging color matrix)
    public int colorMatrixGeneration;
    public int lightingGeneration;
    // fog + alpha ref
    public int fragmentGeneration;
    // current vertex color
    public int colorGeneration;
    // clip plane equation changes
    public int clipPlaneGeneration;
    /** Bit mask of enabled clip planes, mirrored by {@link ClipPlaneBooleanState}. */
    public int clipPlaneEnabledMask;
    // active shader program changes
    public int programGeneration;
    // draw framebuffer binding changes
    public int drawFramebufferGeneration;
    // Overlay color fed to the FFP fragment shader; part of fragmentGeneration tracking
    public float overlayR = 0.0f;
    public float overlayG = 0.0f;
    public float overlayB = 0.0f;
    public float overlayA = 0.0f;
    // Shader color override (Iris-style); tracked for FFP uniform upload
    public float shaderColorR = 1.0f;
    public float shaderColorG = 1.0f;
    public float shaderColorB = 1.0f;
    public float shaderColorA = 1.0f;
    // texgen mode changes
    public int texGenGeneration;
    // Deferred vertex attribute upload flags - set when state changes, flushed before draw
    public boolean dirtyColorAttrib;
    public boolean dirtyNormalAttrib;
    public boolean dirtyTexCoordAttrib;
    public boolean dirtyLightmapAttrib;
    public boolean unit23TexCoordSetDuringDraw = false;
    public int maxBoundTextureUnit = 0;
    public int attribDepth = 0;
    public final StateSet[] attribSets = new StateSet[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public IStateStack<?>[] allStacks;
    public CowStateStack<?>[] stackById;
    public CowDepths[] depthsById;
    public int[] restoreBitById;
    public int[] restoreUnitById;
    public byte[] kindById;
    public int[][] modifiedIds;
    public int[] modifiedCount;
    public int restoreChangedMask;
    public long restoreUnitChangedMask;
    public final AlphaState restoredAlphaScratch = new AlphaState();
    public final int[] savedMvGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedProjGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedTexMatGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedColorMatGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedLightingGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedFragmentGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedColorGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedTexGenGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedClipPlaneGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedNormalGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedTexCoordGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final IntegerStateStack activeTextureUnit = member(new IntegerStateStack(0, StackIdAllocator.nextId()).restoreBit(StateSet.R_ACTIVE_UNIT));
    public int clientActiveTextureUnit = 0;
    public final IntegerStateStack shadeModelState = member(new IntegerStateStack(GL11.GL_SMOOTH, StackIdAllocator.nextId()));
    public final TextureUnitArray textures = new TextureUnitArray();
    public final ImageUnitArray imageUnits = new ImageUnitArray();
    public final BlendStateStack blendState = member(new BlendStateStack(StackIdAllocator.nextId()));
    public final BooleanStateStack blendMode = track(new BooleanStateStack(GL11.GL_BLEND));
    public final BlendState vanillaBlendBefore = new BlendState();
    public final BlendState vanillaBlendAfter = new BlendState();
    public boolean vanillaBlendEnabledBefore;
    public final BooleanStateStack scissorTest = track(new BooleanStateStack(GL11.GL_SCISSOR_TEST));
    public final ScissorStateStack scissorState = member(new ScissorStateStack(StackIdAllocator.nextId()));
    public final DepthStateStack depthState = member(new DepthStateStack(StackIdAllocator.nextId()));
    public final BooleanStateStack depthTest = track(new BooleanStateStack(GL11.GL_DEPTH_TEST));
    public final FogStateStack fogState = member(new FogStateStack(StackIdAllocator.nextId()));
    public final BooleanStateStack fogMode = track(new BooleanStateStack(GL11.GL_FOG, false, true));
    public final Color4Stack color = member(new Color4Stack(StackIdAllocator.nextId()));
    // GL_CURRENT_SECONDARY_COLOR - alpha is always 1 and unused, the color sum only adds RGB
    public final Color4Stack secondaryColor = member(new Color4Stack(StackIdAllocator.nextId(), new Color4(0.0F, 0.0F, 0.0F, 1.0F)).restoreBit(StateSet.R_SECONDARY_COLOR));
    public final BooleanStateStack colorSumState = track(new BooleanStateStack(GL14.GL_COLOR_SUM, false, true));
    public final Color4Stack clearColor = member(new Color4Stack(StackIdAllocator.nextId(), new Color4(0.0F, 0.0F, 0.0F, 0.0F)).restoreBit(StateSet.R_CLEAR_COLOR));
    public final ColorMaskStack colorMask = member(new ColorMaskStack(StackIdAllocator.nextId()));
    public final IntegerStateStack drawBuffer = member(new IntegerStateStack(GLStateManager.DEFAULT_DRAW_BUFFER, StackIdAllocator.nextId()).restoreBit(StateSet.R_DRAW_BUFFER));
    public final IntegerStateStack logicOpMode = member(new IntegerStateStack(GL11.GL_COPY, StackIdAllocator.nextId()).restoreBit(StateSet.R_LOGIC_OP));
    public final BooleanStateStack cullState = track(new BooleanStateStack(GL11.GL_CULL_FACE));
    public final AlphaStateStack alphaState = member(new AlphaStateStack(StackIdAllocator.nextId()));
    public final BooleanStateStack alphaTest = track(new BooleanStateStack(GL11.GL_ALPHA_TEST, false, true));
    public final BooleanStateStack lightingState = track(new BooleanStateStack(GL11.GL_LIGHTING, false, true));
    public final BooleanStateStack rescaleNormalState = track(new BooleanStateStack(GL12.GL_RESCALE_NORMAL, false, true));
    public final BooleanStateStack normalizeState = track(new BooleanStateStack(GL11.GL_NORMALIZE, false, true));
    public final BooleanStateStack ditherState = track(new BooleanStateStack(GL11.GL_DITHER, true));
    public final BooleanStateStack stencilTest = track(new BooleanStateStack(GL11.GL_STENCIL_TEST));
    public final BooleanStateStack lineSmoothState = track(new BooleanStateStack(GL11.GL_LINE_SMOOTH));
    public final BooleanStateStack lineStippleState = track(new BooleanStateStack(GL11.GL_LINE_STIPPLE));
    public final BooleanStateStack pointSmoothState = track(new BooleanStateStack(GL11.GL_POINT_SMOOTH));
    public final BooleanStateStack polygonSmoothState = track(new BooleanStateStack(GL11.GL_POLYGON_SMOOTH));
    public final BooleanStateStack polygonStippleState = track(new BooleanStateStack(GL11.GL_POLYGON_STIPPLE));
    public final BooleanStateStack multisampleState = track(new BooleanStateStack(GL13.GL_MULTISAMPLE, true));
    public final BooleanStateStack sampleAlphaToCoverageState = track(new BooleanStateStack(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE));
    public final BooleanStateStack sampleAlphaToOneState = track(new BooleanStateStack(GL13.GL_SAMPLE_ALPHA_TO_ONE));
    public final BooleanStateStack sampleCoverageState = track(new BooleanStateStack(GL13.GL_SAMPLE_COVERAGE));
    public final BooleanStateStack colorLogicOpState = track(new BooleanStateStack(GL11.GL_COLOR_LOGIC_OP));
    public final BooleanStateStack indexLogicOpState = track(new BooleanStateStack(GL11.GL_INDEX_LOGIC_OP));
    public final BooleanStateStack polygonOffsetPointState = track(new BooleanStateStack(GL11.GL_POLYGON_OFFSET_POINT));
    public final BooleanStateStack polygonOffsetLineState = track(new BooleanStateStack(GL11.GL_POLYGON_OFFSET_LINE));
    public final BooleanStateStack polygonOffsetFillState = track(new BooleanStateStack(GL11.GL_POLYGON_OFFSET_FILL));
    public final LineStateStack lineState = member(new LineStateStack(StackIdAllocator.nextId()));
    public final PointStateStack pointState = member(new PointStateStack(StackIdAllocator.nextId()));
    public final PolygonStateStack polygonState = member(new PolygonStateStack(StackIdAllocator.nextId()));
    public final StencilStateStack stencilState = member(new StencilStateStack(StackIdAllocator.nextId()));
    public final BooleanStateStack autoNormalState = track(new BooleanStateStack(GL11.GL_AUTO_NORMAL, false, true));
    public final BooleanStateStack map1Color4State = track(new BooleanStateStack(GL11.GL_MAP1_COLOR_4, false, true));
    public final BooleanStateStack map1IndexState = track(new BooleanStateStack(GL11.GL_MAP1_INDEX, false, true));
    public final BooleanStateStack map1NormalState = track(new BooleanStateStack(GL11.GL_MAP1_NORMAL, false, true));
    public final BooleanStateStack map1TextureCoord1State = track(new BooleanStateStack(GL11.GL_MAP1_TEXTURE_COORD_1, false, true));
    public final BooleanStateStack map1TextureCoord2State = track(new BooleanStateStack(GL11.GL_MAP1_TEXTURE_COORD_2, false, true));
    public final BooleanStateStack map1TextureCoord3State = track(new BooleanStateStack(GL11.GL_MAP1_TEXTURE_COORD_3, false, true));
    public final BooleanStateStack map1TextureCoord4State = track(new BooleanStateStack(GL11.GL_MAP1_TEXTURE_COORD_4, false, true));
    public final BooleanStateStack map1Vertex3State = track(new BooleanStateStack(GL11.GL_MAP1_VERTEX_3, false, true));
    public final BooleanStateStack map1Vertex4State = track(new BooleanStateStack(GL11.GL_MAP1_VERTEX_4, false, true));
    public final BooleanStateStack map2Color4State = track(new BooleanStateStack(GL11.GL_MAP2_COLOR_4, false, true));
    public final BooleanStateStack map2IndexState = track(new BooleanStateStack(GL11.GL_MAP2_INDEX, false, true));
    public final BooleanStateStack map2NormalState = track(new BooleanStateStack(GL11.GL_MAP2_NORMAL, false, true));
    public final BooleanStateStack map2TextureCoord1State = track(new BooleanStateStack(GL11.GL_MAP2_TEXTURE_COORD_1, false, true));
    public final BooleanStateStack map2TextureCoord2State = track(new BooleanStateStack(GL11.GL_MAP2_TEXTURE_COORD_2, false, true));
    public final BooleanStateStack map2TextureCoord3State = track(new BooleanStateStack(GL11.GL_MAP2_TEXTURE_COORD_3, false, true));
    public final BooleanStateStack map2TextureCoord4State = track(new BooleanStateStack(GL11.GL_MAP2_TEXTURE_COORD_4, false, true));
    public final BooleanStateStack map2Vertex3State = track(new BooleanStateStack(GL11.GL_MAP2_VERTEX_3, false, true));
    public final BooleanStateStack map2Vertex4State = track(new BooleanStateStack(GL11.GL_MAP2_VERTEX_4, false, true));
    public final BooleanStateStack[] clipPlaneStates = new BooleanStateStack[GLStateManager.MAX_CLIP_PLANES];
    public final ClipPlaneState clipPlaneState = member(new ClipPlaneState(StackIdAllocator.nextId()));
    public final FloatBuffer queryScratch = BufferUtils.createFloatBuffer(16);
    public final DoubleBuffer queryScratchDouble = BufferUtils.createDoubleBuffer(16);
    public final MatrixModeStack matrixMode = member(new MatrixModeStack(StackIdAllocator.nextId()));
    public final Matrix4fStack modelViewMatrix = new Matrix4fStack(GLStateManager.MAX_MODELVIEW_STACK_DEPTH);
    public final Matrix4fStack projectionMatrix = new Matrix4fStack(GLStateManager.MAX_PROJECTION_STACK_DEPTH);
    public final Matrix4fStack colorMatrix = new Matrix4fStack(GLStateManager.MAX_COLOR_STACK_DEPTH);
    public final BooleanStateStack[] lightStates = new BooleanStateStack[8];
    public final LightStateStack[] lightDataStates = new LightStateStack[8];
    public final BooleanStateStack colorMaterial = track(new BooleanStateStack(GL11.GL_COLOR_MATERIAL, false, true));
    public final IntegerStateStack colorMaterialFace = member(new IntegerStateStack(GL11.GL_FRONT_AND_BACK, StackIdAllocator.nextId()));
    public final IntegerStateStack colorMaterialParameter = member(new IntegerStateStack(GL11.GL_AMBIENT_AND_DIFFUSE, StackIdAllocator.nextId()));
    public final LightModelStateStack lightModel = member(new LightModelStateStack(StackIdAllocator.nextId()));
    public final MaterialStateStack frontMaterial = member(new MaterialStateStack(GL11.GL_FRONT, StackIdAllocator.nextId()));
    public final MaterialStateStack backMaterial = member(new MaterialStateStack(GL11.GL_BACK, StackIdAllocator.nextId()));
    public final ViewPortStateStack viewportState = member(new ViewPortStateStack(StackIdAllocator.nextId()));
    public int activeProgram = 0;
    public int listBase = 0;
    public int boundVBO;
    public int boundEBO;
    public int boundVAO;
    /** Default VAO and VAO-local tracking belong to this context even when names repeat elsewhere. */
    public int defaultVAO;
    public final Int2IntOpenHashMap vaoEboMap = new Int2IntOpenHashMap();
    public final VertexAttribState vertexAttribState;
    public final StreamingVaos streamingVaos = new StreamingVaos();
    /** Client-array upload scratch is context-owned because array-buffer bindings are context-local. */
    public int clientArraysVBO;
    public int clientArraysVBOCapacity;
    public final int[] clientArraysVBOOffsets = new int[VertexAttribState.MAX_ATTRIBS];
    public final int[] clientArraysVBOUploadLengths = new int[VertexAttribState.MAX_ATTRIBS];
    public int boundPixelUnpackBuffer;
    public int boundPixelPackBuffer;
    public PixelUnpackState pixelUnpackState = PixelUnpackState.DEFAULT;
    public PixelPackState pixelPackState = PixelPackState.DEFAULT;
    public int drawFramebuffer = 0;
    public int readFramebuffer = 0;
    public final ClientAttribStack clientAttribStack = new ClientAttribStack();

    /** True when an open attribute scope saves the stack with this stable id. */
    public boolean savesState(int id) {
        for (int depth = attribDepth - 1; depth >= 0; depth--) {
            if (attribSets[depth].contains(id)) {
                return true;
            }
        }
        return false;
    }

    GLContextState(GLContextIdentity identity, ShaderCache shaderCache) {
        this.identity = identity;
        this.shaderManager = new ShaderManager(shaderCache);
        this.vertexAttribState = new VertexAttribState(
            identity.shareGroupId(), BufferGenerationTracker.shared(identity.shareGroupId()));
        vaoEboMap.defaultReturnValue(0);
        allStacksBuilder.addAll(textures.idStacks());
        for (int i = 0; i < lightStates.length; i++) {
            lightStates[i] = track(new BooleanStateStack(GL11.GL_LIGHT0 + i, false, true));
        }
        for (int i = 0; i < GLStateManager.MAX_CLIP_PLANES; i++) {
            clipPlaneStates[i] = track(new ClipPlaneBooleanState(this, i, GL11.GL_CLIP_PLANE0 + i));
        }
    }

    void init() {
        for (int i = 0; i < lightDataStates.length; i++) {
            lightDataStates[i] = member(new LightStateStack(GL11.GL_LIGHT0 + i, StackIdAllocator.nextId()));
        }

        // These stacks belong to the current context's ShaderManager and share this context's COW ids.
        ShaderManager.getNormalStack().setStackId(StackIdAllocator.nextId());
        allStacksBuilder.add(ShaderManager.getNormalStack());
        ShaderManager.getTexCoordStack().setStackId(StackIdAllocator.nextId());
        allStacksBuilder.add(ShaderManager.getTexCoordStack());

        allStacks = allStacksBuilder.toArray(new IStateStack<?>[0]);
        final int capacity = StackIdAllocator.allocated();
        stackById = new CowStateStack<?>[capacity];
        depthsById = new CowDepths[capacity];
        restoreBitById = new int[capacity];
        restoreUnitById = new int[capacity];
        kindById = new byte[capacity];

        for (IStateStack<?> stateStack : allStacks) {
            if (!(stateStack instanceof CowStateStack<?> cow)) {
                throw new IllegalStateException("Registered GL state stack does not support COW: " + stateStack.getClass().getName());
            }
            final int id = cow.stackId();
            if (id < 0 || id >= capacity || stackById[id] != null) {
                throw new IllegalStateException("Invalid or duplicate GL state stack id " + id + " for " + stateStack.getClass().getName());
            }
            stackById[id] = cow;
            depthsById[id] = cow.cowDepths();
            depthsById[id].owner = this;
            restoreBitById[id] = cow.restoreBit();
            restoreUnitById[id] = cow.restoreUnit();
            kindById[id] = CowDispatch.kindOf(cow);
        }

        for (int id = 0; id < capacity; id++) {
            if (stackById[id] == null) {
                throw new IllegalStateException("Missing GL state stack registration for id " + id);
            }
        }

        modifiedIds = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH][allStacks.length];
        modifiedCount = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    }

    /** Transfers display-owned cached values to its replacement context and drops context-local bindings. */
    void adoptNativeContext(GLContextIdentity replacement, ShaderCache replacementCache) {
        final GLContextIdentity previous = identity;
        final boolean sameShareGroup = previous.shareGroupId() == replacement.shareGroupId();
        identity = replacement;
        shaderManager.adoptContextIdentity(replacement, replacementCache);
        defaultVAO = 0;
        boundVAO = 0;
        boundEBO = 0;
        boundVBO = 0;
        boundPixelPackBuffer = 0;
        boundPixelUnpackBuffer = 0;
        pixelPackState = PixelPackState.DEFAULT;
        pixelUnpackState = PixelUnpackState.DEFAULT;
        drawFramebuffer = 0;
        readFramebuffer = 0;
        activeProgram = 0;
        programGeneration++;
        vaoEboMap.clear();
        vertexAttribState.resetForShareGroup(
            replacement.shareGroupId(), BufferGenerationTracker.shared(replacement.shareGroupId()));
        streamingVaos.clear();
        clientAttribStack.clear();
        if (!sameShareGroup) {
            logClientArraysVbo("shareGroupMigration", previous, replacement);
            clientArraysVBO = 0;
            clientArraysVBOCapacity = 0;
        }
    }

    /** Releases Java-side snapshots that belong only to a destroyed native context. */
    void onContextRetired() {
        logClientArraysVbo("retire", identity, null);
        shaderManager.retireContext();
        vaoEboMap.clear();
        vertexAttribState.releaseState();
        streamingVaos.clear();
        clientAttribStack.clear();
    }

    private void logClientArraysVbo(String event, GLContextIdentity owner, GLContextIdentity replacement) {
        if (GLSMPerfDebug.isEnabled() && (clientArraysVBO != 0 || clientArraysVBOCapacity != 0)) {
            if (replacement == null) {
                MEMORY_LOGGER.info("[ActiniumMemory][context] event={} generation={} shareGroup={} clientArraysVbo={} capacityBytes={}",
                    event, owner.generation(), owner.shareGroupId(), clientArraysVBO, clientArraysVBOCapacity);
            } else {
                MEMORY_LOGGER.info("[ActiniumMemory][context] event={} generation={} shareGroup={} replacementGeneration={} replacementShareGroup={} clientArraysVbo={} capacityBytes={}",
                    event, owner.generation(), owner.shareGroupId(), replacement.generation(), replacement.shareGroupId(),
                    clientArraysVBO, clientArraysVBOCapacity);
            }
        }
    }
}
