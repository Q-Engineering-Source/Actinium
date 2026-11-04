package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingVaos;
import com.gtnewhorizons.angelica.glsm.stacks.AlphaStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Color4Stack;
import com.gtnewhorizons.angelica.glsm.stacks.ColorMaskStack;
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
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import com.gtnewhorizons.angelica.glsm.stacks.ScissorStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.StackIdAllocator;
import com.gtnewhorizons.angelica.glsm.stacks.StencilStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec3fStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec4fStack;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.states.ClipPlaneState;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.glsm.states.ImageUnitArray;
import com.gtnewhorizons.angelica.glsm.states.PixelStoreState;
import com.gtnewhorizons.angelica.glsm.states.TextureUnitArray;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.joml.Matrix4fStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class GLContextState {
    final ExitStateCapture exitCapture = new ExitStateCapture(this);
    private final List<IStateStack<?>> allStacksBuilder = new ArrayList<>();
    IStateStack<?>[] allStacks;
    final List<BooleanStateStack> allBooleanStates = new ArrayList<>();

    private BooleanStateStack track(BooleanStateStack stack) {
        stack.setStackId(StackIdAllocator.nextId());
        allBooleanStates.add(stack);
        allStacksBuilder.add(stack);
        return stack;
    }

    private <T extends IStateStack<?>> T member(T stack) {
        allStacksBuilder.add(stack);
        return stack;
    }

    public boolean poppingAttributes;
    public int mvGeneration;
    public int projGeneration;
    public int texMatrixGeneration;
    public int colorMatrixGeneration;
    public int lightingGeneration;
    public int fragmentGeneration;
    public int colorGeneration;
    public int clipPlaneGeneration;
    public int clipPlaneEnabledMask;
    public int programGeneration;
    public int drawFramebufferGeneration;
    public float overlayR;
    public float overlayG;
    public float overlayB;
    public float overlayA;
    public float shaderColorR = 1.0f;
    public float shaderColorG = 1.0f;
    public float shaderColorB = 1.0f;
    public float shaderColorA = 1.0f;
    public int texGenGeneration;
    public boolean dirtyColorAttrib;
    public boolean dirtyNormalAttrib;
    public boolean dirtyTexCoordAttrib;
    public boolean dirtyLightmapAttrib;
    public boolean unit23TexCoordSetDuringDraw;
    public int maxBoundTextureUnit;
    public final StateSet[] attribSets = new StateSet[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public RetainedState retainedOwner;
    public int attribDepth;

    /**
     * Whether any open attrib bracket declares {@code slot} in its state set. A mutation of a slot no
     * open bracket declares is never restored by a pop, so the copy-on-write stack can skip its snapshot.
     */
    public boolean savesState(int slot) {
        for (int i = attribDepth - 1; i >= 0; i--) {
            if (attribSets[i].contains(slot)) return true;
        }
        return false;
    }

    public CowStateStack<?>[] stackById;
    public CowDepths[] depthsById;
    public int[] restoreBitById;
    public int[] restoreUnitById;
    public byte[] kindById;
    public int[][] modifiedIds;
    public int[] modifiedCount;
    public final int[] savedMvGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedProjGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedTexMatGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedColorMatGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedLightingGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedFragmentGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedColorGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedNormalGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final int[] savedTexCoordGen = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    public final ShaderManager ffp = new ShaderManager();
    public final Vec3fStack normalStack = member(ffp.normalStack);
    public final Vec4fStack texCoordStack = member(ffp.texCoordStack);
    public final IntegerStateStack activeTextureUnit = member(new IntegerStateStack(0, StackIdAllocator.nextId()).restoreBit(StateSet.R_ACTIVE_UNIT));
    public int clientActiveTextureUnit;
    public final IntegerStateStack shadeModelState = member(new IntegerStateStack(GL11.GL_SMOOTH, StackIdAllocator.nextId()));
    public final TextureUnitArray textures = new TextureUnitArray();
    public final ImageUnitArray imageUnits = new ImageUnitArray();
    /** Highest image unit bound since the last reset, so cleanup loops stop early. */
    public int maxBoundImageUnit = 0;
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
    public final Color4Stack secondaryColor = member(new Color4Stack(StackIdAllocator.nextId(), new Color4(0.0F, 0.0F, 0.0F, 1.0F)).restoreBit(StateSet.R_SECONDARY_COLOR));
    public final BooleanStateStack colorSumState = track(new BooleanStateStack(GL14.GL_COLOR_SUM, false, true));
    public final Color4Stack clearColor = member(new Color4Stack(StackIdAllocator.nextId(), new Color4(0.0F, 0.0F, 0.0F, 0.0F)).restoreBit(StateSet.R_CLEAR_COLOR));
    public final ColorMaskStack colorMask = member(new ColorMaskStack(StackIdAllocator.nextId()));
    public final IntegerStateStack drawBuffer = member(new IntegerStateStack(GLStateManager.DEFAULT_DRAW_BUFFER, StackIdAllocator.nextId()).restoreBit(StateSet.R_DRAW_BUFFER));
    public final IntegerStateStack logicOpMode = member(new IntegerStateStack(GL11.GL_COPY, StackIdAllocator.nextId()).restoreBit(StateSet.R_LOGIC_OP));
    public final BooleanStateStack cullState = track(new BooleanStateStack(GL11.GL_CULL_FACE));
    public final AlphaState restoredAlphaScratch = new AlphaState();
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
    public final BooleanStateStack pointSpriteState = track(new BooleanStateStack(GL20.GL_POINT_SPRITE, false, true));
    public final BooleanStateStack vertexProgramTwoSideState = track(new BooleanStateStack(GL20.GL_VERTEX_PROGRAM_TWO_SIDE, false, true));
    public final BooleanStateStack edgeFlagState = track(new BooleanStateStack(GL11.GL_EDGE_FLAG, true, true));
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
    public final IntegerStateStack hintState = member(new IntegerStateStack(0, StackIdAllocator.nextId()).restoreBit(StateSet.R_HINT));
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
    public final ClipPlaneState clipPlaneState = new ClipPlaneState();
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
    public boolean viewportInitialized;
    public int activeProgram;
    public final IntegerStateStack programStack = member(new IntegerStateStack(0, StackIdAllocator.nextId()));
    public int listBase;
    public int defaultVAO;
    public final VAOManager vaos = new VAOManager();
    public final StreamingVaos streamingVaos = new StreamingVaos();
    public float lastBrightnessX;
    public float lastBrightnessY;
    public int savedFilterTextureId;
    public int savedFilterMin = -1;
    public int savedFilterMag = -1;
    public float savedFilterAniso = -1.0f;
    /** True while a line-quad draw is in flight, so the FFP vertex key picks the widening variant. */
    public boolean lineQuadsActive = false;
    public int boundVBO;
    public int boundVAO;
    public int boundPixelUnpackBuffer;
    public int boundPixelPackBuffer;
    public int boundCopyReadBuffer;
    public int boundCopyWriteBuffer;
    public PixelStoreState pixelUnpackState = PixelStoreState.DEFAULT;
    public PixelStoreState pixelPackState = PixelStoreState.DEFAULT;
    public int drawFramebuffer;
    public int readFramebuffer;
    /** Texture attached to each framebuffer's attachment slots, so a deferred delete can detach and ref-count it. */
    public final Int2ObjectOpenHashMap<int[]> framebufferTextures = new Int2ObjectOpenHashMap<>();
    public final int[] clientAttribSavedTextureUnit = new int[GLStateManager.CLIENT_ATTRIB_STACK_DEPTH];
    public final int[] clientAttribSavedVertexFlags = new int[GLStateManager.CLIENT_ATTRIB_STACK_DEPTH];
    public final PixelStoreState[] clientAttribSavedPixelUnpack = new PixelStoreState[GLStateManager.CLIENT_ATTRIB_STACK_DEPTH];
    public final PixelStoreState[] clientAttribSavedPixelPack = new PixelStoreState[GLStateManager.CLIENT_ATTRIB_STACK_DEPTH];
    public int clientAttribStackPointer;
    public int restoreChangedMask;
    public long restoreUnitChangedMask;

    GLContextState() {
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
        allStacks = allStacksBuilder.toArray(new IStateStack<?>[0]);

        final int capacity = StackIdAllocator.capacity();
        stackById = new CowStateStack<?>[capacity];
        depthsById = new CowDepths[capacity];
        restoreBitById = new int[capacity];
        restoreUnitById = new int[capacity];
        kindById = new byte[capacity];
        for (IStateStack<?> stack : allStacks) {
            final CowStateStack<?> cow = (CowStateStack<?>) stack;
            final int id = cow.stackId();
            stackById[id] = cow;
            depthsById[id] = cow.cowDepths();
            depthsById[id].owner = this;
            restoreBitById[id] = cow.restoreBit();
            restoreUnitById[id] = cow.restoreUnit();
            kindById[id] = CowDispatch.kindOf(cow);
        }

        modifiedIds = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH][allStacks.length];
        modifiedCount = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    }
}
