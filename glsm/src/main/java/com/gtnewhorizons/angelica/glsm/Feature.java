package com.gtnewhorizons.angelica.glsm;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Feature {
    private static final int[] supportedAttribs = new int[] { GL11.GL_ACCUM_BUFFER_BIT, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_CURRENT_BIT, GL11.GL_DEPTH_BUFFER_BIT,
        GL11.GL_ENABLE_BIT, GL11.GL_EVAL_BIT, GL11.GL_FOG_BIT, GL11.GL_HINT_BIT, GL11.GL_LIGHTING_BIT, GL11.GL_LINE_BIT, GL11.GL_LIST_BIT,
        GL13.GL_MULTISAMPLE_BIT, GL11.GL_PIXEL_MODE_BIT, GL11.GL_POINT_BIT, GL11.GL_POLYGON_BIT, GL11.GL_POLYGON_STIPPLE_BIT, GL11.GL_SCISSOR_BIT,
        GL11.GL_STENCIL_BUFFER_BIT, GL11.GL_TEXTURE_BIT, GL11.GL_TRANSFORM_BIT, GL11.GL_VIEWPORT_BIT };

    static final Int2ObjectMap<List<IStateStack<?>>> maskToFeaturesMap = new Int2ObjectOpenHashMap<>();
    static final Int2ObjectMap<IStateStack<?>[]> maskToNonBooleanStacksMap = new Int2ObjectOpenHashMap<>();

    static List<IStateStack<?>> maskToFeatures(int mask) {
        if(maskToFeaturesMap.containsKey(mask)) {
            return maskToFeaturesMap.get(mask);
        }

        final Set<IStateStack<?>> features = new HashSet<>();

        for(int attrib : Feature.supportedAttribs) {
            if((mask & attrib) == attrib) {
                features.addAll(getFeatures(attrib));
            }
        }

        final List<IStateStack<?>> asList = new ArrayList<>(features);

        maskToFeaturesMap.put(mask, asList);
        return asList;
    }

    /**
     * Returns only non-BooleanStateStack instances for the given mask.
     * These use traditional push/pop without global depth tracking.
     */
    static IStateStack<?>[] maskToNonBooleanStacks(int mask) {
        IStateStack<?>[] cached = maskToNonBooleanStacksMap.get(mask);
        if (cached != null) {
            return cached;
        }

        final List<IStateStack<?>> all = maskToFeatures(mask);
        final List<IStateStack<?>> nonBooleans = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            final IStateStack<?> stack = all.get(i);
            if (!(stack instanceof BooleanStateStack)) {
                nonBooleans.add(stack);
            }
        }
        cached = nonBooleans.toArray(new IStateStack<?>[0]);
        maskToNonBooleanStacksMap.put(mask, cached);
        return cached;
    }

    private static final Map<Integer, Set<IStateStack<?>>> attribToFeatures = new HashMap<>();

    /**
     * Helper method to add all texture gen states (S, T, R, Q) for all texture units to a collection.
     * Used by both GL_ENABLE_BIT and GL_TEXTURE_BIT.
     */
    private static void addTextureGenStates(Set<IStateStack<?>> collection) {
        for(int i = 0 ; i < GLStateManager.MAX_TEXTURE_UNITS; i++) {
            collection.add(GLStateManager.getTextures().getTexGenSStates(i));
            collection.add(GLStateManager.getTextures().getTexGenTStates(i));
            collection.add(GLStateManager.getTextures().getTexGenRStates(i));
            collection.add(GLStateManager.getTextures().getTexGenQStates(i));
        }
    }

    static {
        attribToFeatures.put(GL11.GL_COLOR_BUFFER_BIT, ImmutableSet.of(
              GLStateManager.getAlphaTest()  // GL_ALPHA_TEST enable bit
            , GLStateManager.getAlphaState() // Alpha test function and reference value
            , GLStateManager.getBlendMode()  // GL_BLEND enable bit
            , GLStateManager.getBlendState() // Blending source/destination functions, equation, blend color
            , GLStateManager.getColorLogicOpState() // GL_COLOR_LOGIC_OP enable bit
            , GLStateManager.getDitherState() // GL_DITHER enable bit
            , GLStateManager.getDrawBuffer() // GL_DRAW_BUFFER setting
            , GLStateManager.getIndexLogicOpState() // GL_INDEX_LOGIC_OP enable bit
            , GLStateManager.getLogicOpMode() // Logic op function
            , GLStateManager.getColorMask()   // Color-mode and index-mode writemasks
            , GLStateManager.getClearColor()  // Color-mode and index-mode clear values
        ));
        attribToFeatures.put(GL11.GL_CURRENT_BIT, ImmutableSet.of(
              GLStateManager.getColor()  // Current RGBA color
            , GLStateManager.getSecondaryColor() // Current secondary color
            , ShaderManager.getNormalStack()    // Current normal vector
            , ShaderManager.getTexCoordStack()  // Current texture coordinates
            // Current color index
            // Current raster position
            // GL_CURRENT_RASTER_POSITION_VALID flag
            // RGBA color associated with current raster position
            // Color index associated with current raster position
            // Texture coordinates associated with current raster position
            // GL_EDGE_FLAG flag
        ));
        attribToFeatures.put(GL11.GL_DEPTH_BUFFER_BIT, ImmutableSet.of(
              GLStateManager.getDepthTest()     // GL_DEPTH_TEST enable bit
            , GLStateManager.getDepthState()    // Depth buffer test function
            // Depth buffer clear value
            // GL_DEPTH_WRITEMASK enable bit
        ));

        final HashSet<IStateStack<?>> enableBits = new HashSet<>(ImmutableSet.of(
              GLStateManager.getAlphaTest() // GL_ALPHA_TEST flag
            , GLStateManager.getAutoNormalState() // GL_AUTO_NORMAL flag
            , GLStateManager.getBlendMode() // GL_BLEND flag
            , GLStateManager.getColorLogicOpState() // GL_COLOR_LOGIC_OP flag
            , GLStateManager.getColorSumState() // GL_COLOR_SUM flag
            , GLStateManager.getColorMaterial() // GL_COLOR_MATERIAL
            , GLStateManager.getCullState() // GL_CULL_FACE flag
            , GLStateManager.getDepthTest() // GL_DEPTH_TEST flag
            , GLStateManager.getDitherState() // GL_DITHER flag
            , GLStateManager.getFogMode() // GL_FOG flag
            , GLStateManager.getIndexLogicOpState() // GL_INDEX_LOGIC_OP flag
            , GLStateManager.getLightStates()[0] // GL_LIGHT0
            , GLStateManager.getLightStates()[1] // GL_LIGHT1
            , GLStateManager.getLightStates()[2] // GL_LIGHT2
            , GLStateManager.getLightStates()[3] // GL_LIGHT3
            , GLStateManager.getLightStates()[4] // GL_LIGHT4
            , GLStateManager.getLightStates()[5] // GL_LIGHT5
            , GLStateManager.getLightStates()[6] // GL_LIGHT6
            , GLStateManager.getLightStates()[7] // GL_LIGHT7
            , GLStateManager.getLightingState() // GL_LIGHTING flag
            , GLStateManager.getLineSmoothState() // GL_LINE_SMOOTH flag
            , GLStateManager.getLineStippleState() // GL_LINE_STIPPLE flag
            , GLStateManager.getMap1Color4State() // GL_MAP1_COLOR_4
            , GLStateManager.getMap1IndexState() // GL_MAP1_INDEX
            , GLStateManager.getMap1NormalState() // GL_MAP1_NORMAL
            , GLStateManager.getMap1TextureCoord1State() // GL_MAP1_TEXTURE_COORD_1
            , GLStateManager.getMap1TextureCoord2State() // GL_MAP1_TEXTURE_COORD_2
            , GLStateManager.getMap1TextureCoord3State() // GL_MAP1_TEXTURE_COORD_3
            , GLStateManager.getMap1TextureCoord4State() // GL_MAP1_TEXTURE_COORD_4
            , GLStateManager.getMap1Vertex3State() // GL_MAP1_VERTEX_3
            , GLStateManager.getMap1Vertex4State() // GL_MAP1_VERTEX_4
            , GLStateManager.getMap2Color4State() // GL_MAP2_COLOR_4
            , GLStateManager.getMap2IndexState() // GL_MAP2_INDEX
            , GLStateManager.getMap2NormalState() // GL_MAP2_NORMAL
            , GLStateManager.getMap2TextureCoord1State() // GL_MAP2_TEXTURE_COORD_1
            , GLStateManager.getMap2TextureCoord2State() // GL_MAP2_TEXTURE_COORD_2
            , GLStateManager.getMap2TextureCoord3State() // GL_MAP2_TEXTURE_COORD_3
            , GLStateManager.getMap2TextureCoord4State() // GL_MAP2_TEXTURE_COORD_4
            , GLStateManager.getMap2Vertex3State() // GL_MAP2_VERTEX_3
            , GLStateManager.getMap2Vertex4State() // GL_MAP2_VERTEX_4
            , GLStateManager.getMultisampleState() // GL_MULTISAMPLE flag
            , GLStateManager.getNormalizeState() // GL_NORMALIZE flag
            , GLStateManager.getPointSmoothState() // GL_POINT_SMOOTH flag
            , GLStateManager.getPolygonOffsetPointState() // GL_POLYGON_OFFSET_POINT flag
            , GLStateManager.getPolygonOffsetLineState() // GL_POLYGON_OFFSET_LINE flag
            , GLStateManager.getPolygonOffsetFillState() // GL_POLYGON_OFFSET_FILL flag
            , GLStateManager.getPolygonSmoothState() // GL_POLYGON_SMOOTH flag
            , GLStateManager.getPolygonStippleState() // GL_POLYGON_STIPPLE flag
            , GLStateManager.getRescaleNormalState() // GL_RESCALE_NORMAL flag
            , GLStateManager.getSampleAlphaToCoverageState() // GL_SAMPLE_ALPHA_TO_COVERAGE flag
            , GLStateManager.getSampleAlphaToOneState() // GL_SAMPLE_ALPHA_TO_ONE flag
            , GLStateManager.getSampleCoverageState() // GL_SAMPLE_COVERAGE flag
            , GLStateManager.getScissorTest()  // GL_SCISSOR_TEST flag
            , GLStateManager.getStencilTest() // GL_STENCIL_TEST flag
        ));

        // Enable bits for the user-definable clipping planes
        for(int i = 0; i < GLStateManager.getClipPlaneStates().length; i++) {
            enableBits.add(GLStateManager.getClipPlaneStates()[i]);
        }

        // GL_TEXTURE_1D, GL_TEXTURE_2D, GL_TEXTURE_3D flags
        for(int i = 0 ; i < GLStateManager.MAX_TEXTURE_UNITS; i++) {
            enableBits.add(GLStateManager.getTextures().getTexture1DStates(i));
            enableBits.add(GLStateManager.getTextures().getTextureUnitStates(i));
            enableBits.add(GLStateManager.getTextures().getTexture3DStates(i));
        }

        // Flags GL_TEXTURE_GEN_x where x is S, T, R, or Q
        addTextureGenStates(enableBits);

        attribToFeatures.put(GL11.GL_ENABLE_BIT, enableBits);
        attribToFeatures.put(GL11.GL_EVAL_BIT, ImmutableSet.of(
            // GL_MAP1_x enable bits, where x is a map type
            // GL_MAP2_x enable bits, where x is a map type
            // 1D grid endpoints and divisions
            // 2D grid endpoints and divisions
            // GL_AUTO_NORMAL enable bit
        ));
        attribToFeatures.put(GL11.GL_FOG_BIT, ImmutableSet.of(
              GLStateManager.getFogMode()    // GL_FOG enable bit
            , GLStateManager.getColorSumState() // GL_COLOR_SUM enable bit
            , GLStateManager.getFogState()   // Fog color
                                       // ^^ Fog density
                                       // ^^ Linear fog start
                                       // ^^ Linear fog end
            // Fog index
                                       // ^^ GL_FOG_MODE value
        ));
        attribToFeatures.put(GL11.GL_HINT_BIT, ImmutableSet.of(
            // GL_PERSPECTIVE_CORRECTION_HINT setting
            // GL_POINT_SMOOTH_HINT setting
            // GL_LINE_SMOOTH_HINT setting
            // GL_POLYGON_SMOOTH_HINT setting
            // GL_FOG_HINT setting
            // GL_GENERATE_MIPMAP_HINT setting
            // GL_TEXTURE_COMPRESSION_HINT setting
        ));
        attribToFeatures.put(GL11.GL_LIGHTING_BIT, ImmutableSet.of(
            GLStateManager.getColorMaterial() // GL_COLOR_MATERIAL enable bit
            , GLStateManager.getColorMaterialFace() // GL_COLOR_MATERIAL_FACE value
            , GLStateManager.getColorMaterialParameter() // Color material parameters that are tracking the current color
            , GLStateManager.getLightModel() // Ambient scene color, GL_LIGHT_MODEL_LOCAL_VIEWER, GL_LIGHT_MODEL_TWO_SIDE
            , GLStateManager.getLightingState()  // GL_LIGHTING enable bit
            // Enable bit for each light
            , GLStateManager.getLightStates()[0] // GL_LIGHT0
            , GLStateManager.getLightStates()[1] // GL_LIGHT1
            , GLStateManager.getLightStates()[2] // GL_LIGHT2
            , GLStateManager.getLightStates()[3] // GL_LIGHT3
            , GLStateManager.getLightStates()[4] // GL_LIGHT4
            , GLStateManager.getLightStates()[5] // GL_LIGHT5
            , GLStateManager.getLightStates()[6] // GL_LIGHT6
            , GLStateManager.getLightStates()[7] // GL_LIGHT7
            // Ambient, diffuse, and specular intensity for each light
            // Direction, position, exponent, and cutoff angle for each light
            // Constant, linear, and quadratic attenuation factors for each light
            , GLStateManager.getLightDataStates()[0]
            , GLStateManager.getLightDataStates()[1]
            , GLStateManager.getLightDataStates()[2]
            , GLStateManager.getLightDataStates()[3]
            , GLStateManager.getLightDataStates()[4]
            , GLStateManager.getLightDataStates()[5]
            , GLStateManager.getLightDataStates()[6]
            , GLStateManager.getLightDataStates()[7]
            // Ambient, diffuse, specular, and emissive color for each material
            // Ambient, diffuse, and specular color indices for each material
            // Specular exponent for each material
            , GLStateManager.getFrontMaterial()
            , GLStateManager.getBackMaterial()
            , GLStateManager.getShadeModelState() // GL_SHADE_MODEL setting
        ));
        attribToFeatures.put(GL11.GL_LINE_BIT, ImmutableSet.of(
              GLStateManager.getLineSmoothState() // GL_LINE_SMOOTH flag
            , GLStateManager.getLineStippleState() // GL_LINE_STIPPLE enable bit
            , GLStateManager.getLineState() // Line stipple pattern, repeat counter, and width
        ));
        attribToFeatures.put(GL11.GL_LIST_BIT, ImmutableSet.of(
            // GL_LIST_BASE setting
        ));
        attribToFeatures.put(GL13.GL_MULTISAMPLE_BIT, ImmutableSet.of(
            // GL_MULTISAMPLE enable bit
            // GL_SAMPLE_ALPHA_TO_COVERAGE flag
            // GL_SAMPLE_ALPHA_TO_ONE flag
            // GL_SAMPLE_COVERAGE flag
            // GL_SAMPLE_COVERAGE_VALUE value
            // GL_SAMPLE_COVERAGE_INVERT value
        ));
        attribToFeatures.put(GL11.GL_PIXEL_MODE_BIT, ImmutableSet.of(
            // GL_RED_BIAS and GL_RED_SCALE settings
            // GL_GREEN_BIAS and GL_GREEN_SCALE values
            // GL_BLUE_BIAS and GL_BLUE_SCALE
            // GL_ALPHA_BIAS and GL_ALPHA_SCALE
            // GL_DEPTH_BIAS and GL_DEPTH_SCALE
            // GL_INDEX_OFFSET and GL_INDEX_SHIFT values
            // GL_MAP_COLOR and GL_MAP_STENCIL flags
            // GL_ZOOM_X and GL_ZOOM_Y factors
            // GL_READ_BUFFER setting
        ));
        attribToFeatures.put(GL11.GL_POINT_BIT, ImmutableSet.of(
              GLStateManager.getPointSmoothState() // GL_POINT_SMOOTH flag
            , GLStateManager.getPointState() // Point size
        ));
        attribToFeatures.put(GL11.GL_POLYGON_BIT, ImmutableSet.of(
              GLStateManager.getCullState() // GL_CULL_FACE enable bit
            , GLStateManager.getPolygonSmoothState() // GL_POLYGON_SMOOTH flag
            , GLStateManager.getPolygonStippleState() // GL_POLYGON_STIPPLE enable bit
            , GLStateManager.getPolygonOffsetFillState() // GL_POLYGON_OFFSET_FILL flag
            , GLStateManager.getPolygonOffsetLineState() // GL_POLYGON_OFFSET_LINE flag
            , GLStateManager.getPolygonOffsetPointState() // GL_POLYGON_OFFSET_POINT flag
            , GLStateManager.getPolygonState() // GL_CULL_FACE_MODE, GL_FRONT_FACE, GL_POLYGON_MODE, GL_POLYGON_OFFSET_FACTOR/UNITS
        ));
        attribToFeatures.put(GL11.GL_POLYGON_STIPPLE_BIT, ImmutableSet.of(
            // Polygon stipple pattern
        ));
        attribToFeatures.put(GL11.GL_SCISSOR_BIT, ImmutableSet.of(
              GLStateManager.getScissorTest() // GL_SCISSOR_TEST enable bit
            // Scissor box
        ));
        attribToFeatures.put(GL11.GL_STENCIL_BUFFER_BIT, ImmutableSet.of(
              GLStateManager.getStencilTest() // GL_STENCIL_TEST enable bit
            , GLStateManager.getStencilState() // Stencil function, ref, mask, ops, writemask, clear value
        ));
        final Set<IStateStack<?>> textureAttribs = new HashSet<>(ImmutableSet.of(
            GLStateManager.getActiveTextureUnitStack() // Active texture unit
                // GL_TEXTURE_ENV_MODE + GL_TEXTURE_ENV_COLOR — now per-unit in TexEnvState (added below)
                // Enable bits for the four texture coordinates

                // Border color for each texture image
                // Minification function for each texture image
                // Magnification function for each texture image
                // Texture coordinates and wrap mode for each texture image

                // Enable bits GL_TEXTURE_GEN_x, x is S, T, R, and Q
                // GL_TEXTURE_GEN_MODE setting for S, T, R, and Q
                // glTexGen plane equations for S, T, R, and Q
                // Current texture bindings (for example, GL_TEXTURE_BINDING_2D) - Below
        ));

        // Current Texture Bindings - GL_TEXTURE_BINDING_2D + per-unit TexEnvState
        for(int i = 0 ; i < GLStateManager.MAX_TEXTURE_UNITS; i++) {
            textureAttribs.add(GLStateManager.getTextures().getTextureUnitBindings(i));
            textureAttribs.add(GLStateManager.getTextures().getTexEnvState(i));
        }

        // Enable bits GL_TEXTURE_GEN_x where x is S, T, R, or Q
        addTextureGenStates(textureAttribs);

        attribToFeatures.put(GL11.GL_TEXTURE_BIT, textureAttribs);

        attribToFeatures.put(GL11.GL_TRANSFORM_BIT, ImmutableSet.of(
            // Coefficients of the six clipping planes
            // Enable bits for the user-definable clipping planes
              GLStateManager.getMatrixMode()
            , GLStateManager.getNormalizeState() // GL_NORMALIZE flag
            , GLStateManager.getRescaleNormalState() // GL_RESCALE_NORMAL flag
        ));
        attribToFeatures.put(GL11.GL_VIEWPORT_BIT, ImmutableSet.of(
            // Depth range (near and far)
            GLStateManager.getViewportState()
        ));
    }

    public static Set<IStateStack<?>> getFeatures(int attrib) {
        return attribToFeatures.getOrDefault(attrib, Collections.emptySet());
    }


}
