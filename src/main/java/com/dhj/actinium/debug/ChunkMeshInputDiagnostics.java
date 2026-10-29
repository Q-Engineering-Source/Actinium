package com.dhj.actinium.debug;

import com.dhj.actinium.world.WorldSlice;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the actual block-state snapshot and immutable mesh output for a small number of large section builds.
 * The task supplies its existing {@link WorldSlice}; this helper never asks the world or renderer for more data.
 */
public final class ChunkMeshInputDiagnostics {
    static final long MIN_OUTPUT_BYTES = 1L << 20;
    static final int MAX_EVENTS_PER_RENDERER = 128;
    private static final int TOP_STATE_LIMIT = 5;
    private static final AtomicLong NEXT_TRACE_ID = new AtomicLong(1L);
    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private ChunkMeshInputDiagnostics() {
    }

    /**
     * Reserves a scalar ID only for large outputs while the existing GUI-controlled performance debug switch is on.
     * The budget itself is owned by the render-section manager, so it cannot retain a world or renderer reference.
     */
    public static long reserveTraceId(long outputNativeBytes, TraceBudget budget) {
        try {
            return tryReserveTraceId(outputNativeBytes, budget, GLSMPerfDebug.isEnabled());
        } catch (RuntimeException | Error diagnosticFailure) {
            LOGGER.warn("[ActiniumMemory] scope=chunk_mesh phase=traceReservation outputNativeBytes={} diagnostic=failed",
                outputNativeBytes, diagnosticFailure);
            return 0L;
        }
    }

    static long tryReserveTraceId(long outputNativeBytes, TraceBudget budget, boolean perfDebugEnabled) {
        if (!perfDebugEnabled || outputNativeBytes < MIN_OUTPUT_BYTES) {
            return 0L;
        }
        return budget.allocate();
    }

    /**
     * Logs the build-side event for an already-reserved trace. A diagnostic exception is recorded and does not
     * replace a successful mesh result. Coordinates are the task's own section, which is inside the captured slice.
     */
    public static void reportBuildOutput(long traceId, RenderSection render, WorldSlice slice,
                                         Map<TerrainRenderPass, BuiltSectionMeshParts> meshes,
                                         long outputNativeBytes, int buildTime) {
        if (traceId == 0L || !GLSMPerfDebug.isEnabled()) {
            return;
        }

        try {
            SectionSummary input = scanSection(slice,
                sectionBlockOrigin(render.getChunkX()),
                sectionBlockOrigin(render.getChunkY()),
                sectionBlockOrigin(render.getChunkZ()));
            String passes = formatPasses(meshes);
            LOGGER.info("[ActiniumMemory] scope=chunk_mesh phase=builderTaskOutput traceId={} sectionX={} sectionY={} sectionZ={} "
                    + "blockOriginX={} blockOriginY={} blockOriginZ={} buildTime={} builderTaskOutputNativeBytes={} "
                + "inputBlocks={} inputUnknownStates={} inputNonAir={} distinctStates={} opaqueTrue={} opaqueFalse={} opaqueUnknown={} "
                    + "fullCubeTrue={} fullCubeFalse={} fullCubeUnknown={} renderTypes={} "
                    + "registryNameUnknown={} metadataUnknown={} vanillaStateIdUnknown={} internalStateIdUnknown={} "
                    + "propertyReadFailures={} topStates={} passes={}",
                traceId, render.getChunkX(), render.getChunkY(), render.getChunkZ(),
                render.getOriginX(), render.getOriginY(), render.getOriginZ(), buildTime, outputNativeBytes,
                input.blocks(), input.unknownStates(), input.nonAir(), input.distinctStates(), input.opaqueTrue(), input.opaqueFalse(),
                input.opaqueUnknown(), input.fullCubeTrue(), input.fullCubeFalse(), input.fullCubeUnknown(),
                formatRenderTypes(input), input.registryNameUnknown(), input.metadataUnknown(),
                input.vanillaStateIdUnknown(), input.internalStateIdUnknown(), input.propertyReadFailures(),
                formatTopStates(input.topStates()), passes);
        } catch (RuntimeException | Error diagnosticFailure) {
            LOGGER.warn("[ActiniumMemory] scope=chunk_mesh phase=builderTaskOutput traceId={} sectionX={} sectionY={} sectionZ={} "
                    + "buildTime={} builderTaskOutputNativeBytes={} passes=unavailable diagnostic=failed",
                traceId, render.getChunkX(), render.getChunkY(), render.getChunkZ(), buildTime, outputNativeBytes,
                diagnosticFailure);
        }
    }

    /** Records an output-size diagnostic failure without changing the task's successful mesh result. */
    public static void reportOutputMeasurementFailure(RenderSection render, Throwable diagnosticFailure) {
        if (GLSMPerfDebug.isEnabled()) {
            LOGGER.warn("[ActiniumMemory] scope=chunk_mesh phase=outputMeasurement sectionX={} sectionY={} sectionZ={} diagnostic=failed",
                render.getChunkX(), render.getChunkY(), render.getChunkZ(), diagnosticFailure);
        }
    }

    /** Converts the section coordinate used by RenderSection to its block-space origin, including negative Y. */
    static int sectionBlockOrigin(int sectionCoordinate) {
        return sectionCoordinate << 4;
    }

    static StateIdentity identifyState(IBlockState state) {
        int readFailures = 0;
        Block block = null;
        try {
            block = state.getBlock();
        } catch (RuntimeException readFailure) {
            readFailures++;
        }

        String registryName = "unknown";
        boolean registeredBlock = false;
        if (block != null) {
            try {
                ResourceLocation name = block.getRegistryName();
                if (name == null) {
                    registryName = "unregistered";
                } else {
                    registryName = name.toString();
                    registeredBlock = isRegisteredBlock(block);
                }
            } catch (RuntimeException readFailure) {
                readFailures++;
            }
        }

        Integer metadata = null;
        if (block != null) {
            try {
                metadata = block.getMetaFromState(state);
            } catch (RuntimeException readFailure) {
                readFailures++;
            }
        }

        Integer vanillaStateId = null;
        if (registeredBlock) {
            try {
                int id = Block.getStateId(state);
                if (id >= 0) {
                    vanillaStateId = id;
                }
            } catch (RuntimeException readFailure) {
                readFailures++;
            }
        }

        Integer internalStateId = null;
        try {
            int id = Block.BLOCK_STATE_IDS.get(state);
            if (id >= 0) {
                internalStateId = id;
            }
        } catch (RuntimeException readFailure) {
            readFailures++;
        }

        return new StateIdentity(registryName, metadata, vanillaStateId, internalStateId,
            block == Blocks.AIR, readFailures);
    }

    private static boolean isRegisteredBlock(Block block) {
        int registryId = Block.REGISTRY.getIDForObject(block);
        return Block.REGISTRY.getObjectById(registryId) == block;
    }

    private static SectionSummary scanSection(WorldSlice slice, int minX, int minY, int minZ) {
        InputAccumulator accumulator = new InputAccumulator();
        for (int y = minY; y < minY + 16; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    // The task's exact section is contained by its existing snapshot volume; this read cannot take
                    // WorldSlice's out-of-volume fallback to World#getBlockState.
                    accumulator.accept(slice.getBlockState(x, y, z));
                }
            }
        }
        return accumulator.snapshot();
    }

    private static String formatRenderTypes(SectionSummary summary) {
        StringBuilder output = new StringBuilder();
        for (EnumBlockRenderType renderType : EnumBlockRenderType.values()) {
            if (output.length() > 0) {
                output.append('|');
            }
            output.append(renderType.name()).append('=').append(summary.renderTypeCounts().get(renderType));
        }
        if (output.length() > 0) {
            output.append('|');
        }
        return output.append("unknown=").append(summary.renderTypeUnknown()).toString();
    }

    private static String formatTopStates(List<StateCount> states) {
        StringJoiner output = new StringJoiner("|");
        for (StateCount state : states) {
            StateIdentity identity = state.identity();
            output.add(identity.registryName() + "@meta=" + valueOrUnknown(identity.metadata())
                + "{vanillaStateId=" + valueOrUnknown(identity.vanillaStateId())
                + ",internalStateId=" + valueOrUnknown(identity.internalStateId())
                + "}=" + state.count());
        }
        return output.toString();
    }

    private static String valueOrUnknown(Integer value) {
        return value == null ? "unknown" : value.toString();
    }

    private static String formatPasses(Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        List<String> passes = new ArrayList<>(meshes.size());
        for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : meshes.entrySet()) {
            TerrainRenderPass pass = entry.getKey();
            BuiltSectionMeshParts mesh = entry.getValue();
            long vertices = 0L;
            for (VertexRange range : mesh.ranges().values()) {
                vertices += range.vertexCount();
            }

            long vertexBytes = mesh.vertexBuffer().getLength();
            long indexBytes = mesh.indexBuffer() == null ? 0L : mesh.indexBuffer().getLength();
            ChunkPrimitiveType primitiveType = pass.primitiveType();
            String quads = primitiveType instanceof QuadPrimitiveType && (vertices & 3L) == 0L
                ? Long.toString(vertices / 4L)
                : "unknown";
            passes.add(pass.name() + "{primitive=" + primitiveType.getClass().getSimpleName()
                + ",vertexBytes=" + vertexBytes + ",indexBytes=" + indexBytes
                + ",bytes=" + (vertexBytes + indexBytes) + ",vertices=" + vertices + ",quads=" + quads + "}");
        }
        passes.sort(Comparator.naturalOrder());
        return String.join("|", passes);
    }

    /** Per-renderer event budget, deliberately free of world, section, or task references. */
    public static final class TraceBudget {
        private int reservedEvents;

        private synchronized long allocate() {
            if (this.reservedEvents >= MAX_EVENTS_PER_RENDERER) {
                return 0L;
            }

            long traceId = NEXT_TRACE_ID.getAndIncrement();
            if (traceId <= 0L) {
                throw new IllegalStateException("Chunk mesh trace ID space exhausted");
            }
            this.reservedEvents++;
            return traceId;
        }

        synchronized int reservedEvents() {
            return this.reservedEvents;
        }
    }

    /** Accumulates actual block-state identities and render-relevant properties for one section. */
    static final class InputAccumulator {
        private final IdentityHashMap<IBlockState, StateObservation> observations = new IdentityHashMap<>();
        private int blocks;
        private int unknownStates;
        private int nonAir;
        private int opaqueTrue;
        private int opaqueFalse;
        private int opaqueUnknown;
        private int fullCubeTrue;
        private int fullCubeFalse;
        private int fullCubeUnknown;
        private int renderTypeUnknown;
        private int registryNameUnknown;
        private int metadataUnknown;
        private int vanillaStateIdUnknown;
        private int internalStateIdUnknown;
        // Counts actual failed getter calls across one inspection of each distinct state.
        private int propertyReadFailures;
        private final EnumMap<EnumBlockRenderType, Integer> renderTypeCounts =
            new EnumMap<>(EnumBlockRenderType.class);

        void accept(IBlockState state) {
            this.blocks++;
            if (state == null) {
                this.unknownStates++;
                return;
            }

            StateObservation observation = this.observations.get(state);
            if (observation == null) {
                observation = StateObservation.read(state, this.observations.size());
                this.observations.put(state, observation);
                // State properties are inspected once per distinct state, so this is the actual number of failed
                // getter calls rather than an occurrence-weighted count of blocks using a failed state.
                this.propertyReadFailures += observation.propertyReadFailures;
            }
            observation.count++;

            if (!observation.identity.air()) {
                this.nonAir++;
            }
            if (observation.identity.registryName().equals("unknown")
                || observation.identity.registryName().equals("unregistered")) {
                this.registryNameUnknown++;
            }
            if (observation.identity.metadata() == null) {
                this.metadataUnknown++;
            }
            if (observation.identity.vanillaStateId() == null) {
                this.vanillaStateIdUnknown++;
            }
            if (observation.identity.internalStateId() == null) {
                this.internalStateIdUnknown++;
            }
            if (observation.opaque == null) {
                this.opaqueUnknown++;
            } else if (observation.opaque) {
                this.opaqueTrue++;
            } else {
                this.opaqueFalse++;
            }
            if (observation.fullCube == null) {
                this.fullCubeUnknown++;
            } else if (observation.fullCube) {
                this.fullCubeTrue++;
            } else {
                this.fullCubeFalse++;
            }
            if (observation.renderType == null) {
                this.renderTypeUnknown++;
            } else {
                this.renderTypeCounts.merge(observation.renderType, 1, Integer::sum);
            }
        }

        SectionSummary snapshot() {
            List<StateObservation> ordered = new ArrayList<>(this.observations.values());
            ordered.sort(Comparator.comparingInt((StateObservation state) -> state.count).reversed()
                .thenComparing(state -> state.identity.registryName())
                .thenComparing(state -> state.identity.metadata(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(state -> state.identity.vanillaStateId(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(state -> state.identity.internalStateId(), Comparator.nullsLast(Integer::compareTo))
                .thenComparingInt(state -> state.firstSeen));

            List<StateCount> topStates = new ArrayList<>(Math.min(TOP_STATE_LIMIT, ordered.size()));
            for (int index = 0; index < Math.min(TOP_STATE_LIMIT, ordered.size()); index++) {
                StateObservation state = ordered.get(index);
                topStates.add(new StateCount(state.identity, state.count));
            }

            EnumMap<EnumBlockRenderType, Integer> allRenderTypes = new EnumMap<>(EnumBlockRenderType.class);
            for (EnumBlockRenderType renderType : EnumBlockRenderType.values()) {
                allRenderTypes.put(renderType, this.renderTypeCounts.getOrDefault(renderType, 0));
            }

            return new SectionSummary(this.blocks, this.unknownStates, this.nonAir, this.observations.size(),
                this.opaqueTrue, this.opaqueFalse, this.opaqueUnknown,
                this.fullCubeTrue, this.fullCubeFalse, this.fullCubeUnknown,
                this.renderTypeUnknown, Map.copyOf(allRenderTypes), this.registryNameUnknown,
                this.metadataUnknown, this.vanillaStateIdUnknown, this.internalStateIdUnknown,
                this.propertyReadFailures, List.copyOf(topStates));
        }
    }

    private static final class StateObservation {
        private final StateIdentity identity;
        private final Boolean opaque;
        private final Boolean fullCube;
        private final EnumBlockRenderType renderType;
        private final int propertyReadFailures;
        private final int firstSeen;
        private int count;

        private StateObservation(StateIdentity identity, Boolean opaque, Boolean fullCube,
                                 EnumBlockRenderType renderType, int propertyReadFailures, int firstSeen) {
            this.identity = identity;
            this.opaque = opaque;
            this.fullCube = fullCube;
            this.renderType = renderType;
            this.propertyReadFailures = propertyReadFailures;
            this.firstSeen = firstSeen;
        }

        private static StateObservation read(IBlockState state, int firstSeen) {
            StateIdentity identity = identifyState(state);
            int readFailures = identity.readFailures();
            Boolean opaque = null;
            try {
                opaque = state.isOpaqueCube();
            } catch (RuntimeException readFailure) {
                readFailures++;
            }

            Boolean fullCube = null;
            try {
                fullCube = state.isFullCube();
            } catch (RuntimeException readFailure) {
                readFailures++;
            }

            EnumBlockRenderType renderType = null;
            try {
                renderType = state.getRenderType();
            } catch (RuntimeException readFailure) {
                readFailures++;
            }

            return new StateObservation(identity, opaque, fullCube, renderType, readFailures, firstSeen);
        }
    }

    record StateIdentity(String registryName, Integer metadata, Integer vanillaStateId,
                         Integer internalStateId, boolean air, int readFailures) {
    }

    record StateCount(StateIdentity identity, int count) {
    }

    record SectionSummary(int blocks, int unknownStates, int nonAir, int distinctStates,
                          int opaqueTrue, int opaqueFalse, int opaqueUnknown,
                          int fullCubeTrue, int fullCubeFalse, int fullCubeUnknown,
                          int renderTypeUnknown, Map<EnumBlockRenderType, Integer> renderTypeCounts,
                          int registryNameUnknown, int metadataUnknown, int vanillaStateIdUnknown,
                          int internalStateIdUnknown, int propertyReadFailures, List<StateCount> topStates) {
    }
}
