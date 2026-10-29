package com.dhj.actinium.debug;

import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Low-frequency, read-only snapshots of negative-Y chunk input at the Overworld generation stages.
 * Sampling is stateless so the same seed, dimension, and 8-by-8 chunk footprint select one column
 * at both RETURN hooks without retaining world or coordinate state.
 */
public final class WorldgenInputDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final int CHUNKS_PER_FOOTPRINT_AXIS = 8;
    private static final int TOP_STATE_LIMIT = 5;
    private static final int READ_FAILURE_BLOCK = 1;
    private static final int READ_FAILURE_REGISTRY_NAME = 1 << 1;
    private static final int READ_FAILURE_MATERIAL = 1 << 2;
    private static final int READ_FAILURE_METADATA = 1 << 3;
    private static final int READ_FAILURE_OPAQUE = 1 << 4;
    private static final int READ_FAILURE_FULL_CUBE = 1 << 5;
    private static final int READ_FAILURE_RENDER_TYPE = 1 << 6;
    private static final AtomicBoolean REGISTRY_CANDIDATES_REPORTED = new AtomicBoolean();

    private WorldgenInputDiagnostics() {
    }

    /** Returns whether a coordinate is the deterministic sample for its 8-by-8 chunk footprint. */
    public static boolean isSampledChunk(long worldSeed, int dimensionId, int chunkX, int chunkZ) {
        int footprintX = Math.floorDiv(chunkX, CHUNKS_PER_FOOTPRINT_AXIS);
        int footprintZ = Math.floorDiv(chunkZ, CHUNKS_PER_FOOTPRINT_AXIS);
        long seed = worldSeed
            ^ ((long) dimensionId * 0x9E3779B97F4A7C15L)
            ^ ((long) footprintX * 0xBF58476D1CE4E5B9L)
            ^ ((long) footprintZ * 0x94D049BB133111EBL);
        long mixed = mix64(seed);
        int selectedX = (int) (mixed & (CHUNKS_PER_FOOTPRINT_AXIS - 1));
        int selectedZ = (int) ((mixed >>> 3) & (CHUNKS_PER_FOOTPRINT_AXIS - 1));
        return Math.floorMod(chunkX, CHUNKS_PER_FOOTPRINT_AXIS) == selectedX
            && Math.floorMod(chunkZ, CHUNKS_PER_FOOTPRINT_AXIS) == selectedZ;
    }

    /** Records the returned, newly generated chunk before the provider inserts it into its loaded set. */
    public static void onGenerateReturned(World world, Chunk generatedChunk, int chunkX, int chunkZ) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }

        try {
            int dimensionId = dimensionId(world);
            if (!isSampledChunk(world.getSeed(), dimensionId, chunkX, chunkZ)) {
                return;
            }
            if (generatedChunk == null) {
                LOGGER.warn("[ActiniumMemory] scope=worldgen phase=generate_return dim={} chunkX={} chunkZ={} diagnostic=returned_chunk_missing",
                    dimensionId, chunkX, chunkZ);
                return;
            }

            reportRegistryCandidates(world, dimensionId, chunkX, chunkZ);
            scanNegativeSections(world, generatedChunk, "generate_return", dimensionId,
                chunkX, chunkZ, "true", "returned");
        } catch (RuntimeException diagnosticFailure) {
            logFailure("generate_return", world, chunkX, chunkZ, diagnosticFailure);
        }
    }

    /** Reads only the same coordinate's already loaded chunk; it never requests chunk loading. */
    public static void onPopulateReturned(World world, int chunkX, int chunkZ) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }

        int dimensionId = -1;
        try {
            dimensionId = dimensionId(world);
            if (!isSampledChunk(world.getSeed(), dimensionId, chunkX, chunkZ)) {
                return;
            }

            IChunkProvider provider = world.getChunkProvider();
            Chunk loadedChunk = provider.getLoadedChunk(chunkX, chunkZ);
            if (loadedChunk == null) {
                LOGGER.info("[ActiniumMemory] scope=worldgen phase=populate_return dim={} remote={} chunkX={} chunkZ={} footprintRegionX={} footprintRegionZ={} newlyGenerated=unknown currentChunk=unavailable minY={} topY={} sections=none",
                    dimensionId, world.isRemote, chunkX, chunkZ,
                    Math.floorDiv(chunkX, CHUNKS_PER_FOOTPRINT_AXIS),
                    Math.floorDiv(chunkZ, CHUNKS_PER_FOOTPRINT_AXIS),
                    DepthsUpdateCompat.getMinBuildHeight(world), Math.min(0, world.getHeight()));
                return;
            }

            reportRegistryCandidates(world, dimensionId, chunkX, chunkZ);
            scanNegativeSections(world, loadedChunk, "populate_return", dimensionId,
                chunkX, chunkZ, "unknown", "loadedProviderLookup");
        } catch (RuntimeException diagnosticFailure) {
            logFailure("populate_return", world, chunkX, chunkZ, diagnosticFailure, dimensionId);
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static int dimensionId(World world) {
        DimensionType dimensionType = world.provider.getDimensionType();
        return dimensionType.getId();
    }

    private static void scanNegativeSections(World world, Chunk chunk, String phase, int dimensionId,
                                             int chunkX, int chunkZ, String newlyGenerated,
                                             String currentChunk) {
        int minY = DepthsUpdateCompat.getMinBuildHeight(world);
        int topY = Math.min(0, world.getHeight());
        int footprintX = Math.floorDiv(chunkX, CHUNKS_PER_FOOTPRINT_AXIS);
        int footprintZ = Math.floorDiv(chunkZ, CHUNKS_PER_FOOTPRINT_AXIS);
        String context = "[ActiniumMemory] scope=worldgen phase=" + phase
            + " dim=" + dimensionId
            + " remote=" + world.isRemote
            + " chunkX=" + chunkX
            + " chunkZ=" + chunkZ
            + " footprintRegionX=" + footprintX
            + " footprintRegionZ=" + footprintZ
            + " newlyGenerated=" + newlyGenerated
            + " currentChunk=" + currentChunk
            + " minY=" + minY
            + " topY=" + topY;

        if (minY >= topY) {
            LOGGER.info("{} sections=none", context);
            return;
        }

        int firstSectionY = Math.floorDiv(minY, 16) * 16;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int sectionY = firstSectionY; sectionY < topY; sectionY += 16) {
            int fromY = Math.max(sectionY, minY);
            int toY = Math.min(sectionY + 16, topY);
            SectionAccumulator accumulator = new SectionAccumulator();
            try {
                for (int y = fromY; y < toY; y++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            position.setPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
                            accumulator.accept(chunk.getBlockState(position));
                        }
                    }
                }

                logSection(context, sectionY, toY - fromY, accumulator.snapshot());
            } catch (RuntimeException sectionFailure) {
                LOGGER.warn("{} sectionY={} sectionDiagnostic=failed", context, sectionY, sectionFailure);
            }
        }
    }

    private static void logSection(String context, int sectionY, int sectionHeight,
                                   SectionSummary summary) {
        LOGGER.info("{} sectionY={} sectionHeight={} blocks={} nonAir={} unknownMaterial={} "
                + "opaqueTrue={} opaqueFalse={} opaqueUnknown={} fullCubeTrue={} fullCubeFalse={} "
                + "fullCubeUnknown={} renderTypes={} registryNameUnknown={} metadataUnknown={} "
                + "unknownState={} propertyReadFailures={block:{},registryName:{},material:{},metadata:{},"
                + "opaque:{},fullCube:{},renderType:{}} topStates={}",
            context, sectionY, sectionHeight, summary.blocks(), summary.nonAir(),
            summary.unknownMaterial(), summary.opaqueTrue(), summary.opaqueFalse(), summary.opaqueUnknown(),
            summary.fullCubeTrue(), summary.fullCubeFalse(), summary.fullCubeUnknown(),
            formatRenderTypes(summary), summary.registryNameUnknown(), summary.metadataUnknown(),
            summary.unknownState(), summary.blockReadFailures(), summary.registryNameReadFailures(),
            summary.materialReadFailures(), summary.metadataReadFailures(), summary.opaqueReadFailures(),
            summary.fullCubeReadFailures(), summary.renderTypeReadFailures(),
            formatTopStates(summary.topStates()));
    }

    private static String formatRenderTypes(SectionSummary summary) {
        StringBuilder output = new StringBuilder();
        for (EnumBlockRenderType renderType : EnumBlockRenderType.values()) {
            if (output.length() > 0) {
                output.append('|');
            }
            output.append(renderType.name()).append('=')
                .append(summary.renderTypeCounts().getOrDefault(renderType, 0));
        }
        if (output.length() > 0) {
            output.append('|');
        }
        return output.append("unknown=").append(summary.renderTypeUnknown()).toString();
    }

    private static String formatTopStates(List<StateCount> states) {
        StringBuilder output = new StringBuilder();
        for (StateCount state : states) {
            if (output.length() > 0) {
                output.append('|');
            }
            output.append(state.registryName()).append('@')
                .append(state.metadata() == null ? "?" : state.metadata())
                .append('=').append(state.count());
        }
        return output.toString();
    }

    private static void reportRegistryCandidates(World world, int dimensionId, int chunkX, int chunkZ) {
        if (!REGISTRY_CANDIDATES_REPORTED.compareAndSet(false, true)) {
            return;
        }

        Block deepslate = readCandidateBlock("depthsupdate:deepslate", "depthsupdate:deepslate");
        Block id913 = readCandidateBlock("id913", "913");
        LOGGER.info("[ActiniumMemory] scope=worldgen phase=registry_candidates dim={} remote={} chunkX={} chunkZ={} "
                + "deepslateCandidate={} id913Candidate={}",
            dimensionId, world.isRemote, chunkX, chunkZ,
            describeCandidate("depthsupdate:deepslate", deepslate), describeCandidate("id913", id913));
    }

    private static Block readCandidateBlock(String field, String registryOrId) {
        try {
            return Block.getBlockFromName(registryOrId);
        } catch (RuntimeException failure) {
            LOGGER.warn("[ActiniumMemory] scope=worldgen phase=registry_candidates field={} registryLookup=failed",
                field, failure);
            return null;
        }
    }

    private static String describeCandidate(String field, Block block) {
        if (block == null) {
            return field + "{registryName=missing,blockId=unknown,stateId=unknown,internalStateId=unknown}";
        }

        String registryName = readCandidateValue(field, "registryName", () -> {
            ResourceLocation name = block.getRegistryName();
            return name == null ? "unregistered" : name.toString();
        });
        String blockId = readCandidateValue(field, "blockId", () -> Block.getIdFromBlock(block));
        String stateId = readCandidateValue(field, "stateId", () -> Block.getStateId(block.getDefaultState()));
        String internalStateId = readCandidateValue(field, "internalStateId",
            () -> Block.BLOCK_STATE_IDS.get(block.getDefaultState()));
        String internalBlockId = decodeInternalStateId(field, internalStateId, true);
        String internalMetadata = decodeInternalStateId(field, internalStateId, false);
        return field + "{registryName=" + registryName + ",blockId=" + blockId
            + ",stateId=" + stateId + ",internalStateId=" + internalStateId
            + ",internalBlockIdFromStateId=" + internalBlockId
            + ",internalMetadataFromStateId=" + internalMetadata + "}";
    }

    private static String decodeInternalStateId(String candidate, String internalStateId,
                                                boolean blockId) {
        try {
            int parsedStateId = Integer.parseInt(internalStateId);
            if (parsedStateId < 0) {
                return "unknown";
            }
            return Integer.toString(blockId ? parsedStateId >>> 4 : parsedStateId & 15);
        } catch (NumberFormatException invalidStateId) {
            if ("unknown".equals(internalStateId)) {
                return "unknown";
            }
            LOGGER.warn("[ActiniumMemory] scope=worldgen phase=registry_candidates candidate={} "
                    + "field=internalStateId value=undecodable decodedField={}",
                candidate, blockId ? "internalBlockIdFromStateId" : "internalMetadataFromStateId",
                invalidStateId);
            return "unknown";
        }
    }

    private static String readCandidateValue(String candidate, String field, Supplier<?> read) {
        try {
            Object value = read.get();
            return String.valueOf(value);
        } catch (RuntimeException failure) {
            LOGGER.warn("[ActiniumMemory] scope=worldgen phase=registry_candidates candidate={} field={} value=unknown",
                candidate, field, failure);
            return "unknown";
        }
    }

    private static void logFailure(String phase, World world, int chunkX, int chunkZ,
                                   RuntimeException diagnosticFailure) {
        logFailure(phase, world, chunkX, chunkZ, diagnosticFailure, -1);
    }

    private static void logFailure(String phase, World world, int chunkX, int chunkZ,
                                   RuntimeException diagnosticFailure, int knownDimensionId) {
        LOGGER.warn("[ActiniumMemory] scope=worldgen phase={} dim={} remote={} chunkX={} chunkZ={} diagnostic=failed",
            phase, knownDimensionId, world.isRemote, chunkX, chunkZ, diagnosticFailure);
    }

    /** Mutable per-section accumulator that caches each state's local properties on first sight. */
    static final class SectionAccumulator {
        private final IdentityHashMap<IBlockState, StateObservation> observations = new IdentityHashMap<>();
        private final EnumMap<EnumBlockRenderType, Integer> renderTypeCounts =
            new EnumMap<>(EnumBlockRenderType.class);
        private int blocks;
        private int nonAir;
        private int unknownState;
        private int unknownMaterial;
        private int opaqueTrue;
        private int opaqueFalse;
        private int opaqueUnknown;
        private int fullCubeTrue;
        private int fullCubeFalse;
        private int fullCubeUnknown;
        private int renderTypeUnknown;
        private int registryNameUnknown;
        private int metadataUnknown;
        private int blockReadFailures;
        private int registryNameReadFailures;
        private int materialReadFailures;
        private int metadataReadFailures;
        private int opaqueReadFailures;
        private int fullCubeReadFailures;
        private int renderTypeReadFailures;

        void accept(IBlockState state) {
            this.blocks++;
            if (state == null) {
                this.unknownState++;
                return;
            }

            StateObservation observation = this.observations.get(state);
            if (observation == null) {
                observation = StateObservation.read(state);
                this.observations.put(state, observation);
            }
            observation.count++;

            if (observation.material == null) {
                this.unknownMaterial++;
            } else if (observation.material != Material.AIR) {
                this.nonAir++;
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
            if (observation.registryNameUnknown) {
                this.registryNameUnknown++;
            }
            if (observation.metadata == null) {
                this.metadataUnknown++;
            }

            if ((observation.readFailureMask & READ_FAILURE_BLOCK) != 0) {
                this.blockReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_REGISTRY_NAME) != 0) {
                this.registryNameReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_MATERIAL) != 0) {
                this.materialReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_METADATA) != 0) {
                this.metadataReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_OPAQUE) != 0) {
                this.opaqueReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_FULL_CUBE) != 0) {
                this.fullCubeReadFailures++;
            }
            if ((observation.readFailureMask & READ_FAILURE_RENDER_TYPE) != 0) {
                this.renderTypeReadFailures++;
            }
        }

        SectionSummary snapshot() {
            Map<StateKey, Integer> groupedCounts = new HashMap<>();
            for (StateObservation observation : this.observations.values()) {
                groupedCounts.merge(new StateKey(observation.registryName, observation.metadata),
                    observation.count, Integer::sum);
            }

            List<StateCount> topStates = new ArrayList<>(groupedCounts.size());
            for (Map.Entry<StateKey, Integer> entry : groupedCounts.entrySet()) {
                StateKey key = entry.getKey();
                topStates.add(new StateCount(key.registryName(), key.metadata(), entry.getValue()));
            }
            topStates.sort(Comparator.comparingInt(StateCount::count).reversed()
                .thenComparing(StateCount::registryName)
                .thenComparing(StateCount::metadata, Comparator.nullsFirst(Integer::compareTo)));
            if (topStates.size() > TOP_STATE_LIMIT) {
                topStates = new ArrayList<>(topStates.subList(0, TOP_STATE_LIMIT));
            }

            EnumMap<EnumBlockRenderType, Integer> allRenderTypes =
                new EnumMap<>(EnumBlockRenderType.class);
            for (EnumBlockRenderType type : EnumBlockRenderType.values()) {
                allRenderTypes.put(type, this.renderTypeCounts.getOrDefault(type, 0));
            }
            return new SectionSummary(this.blocks, this.nonAir, this.unknownMaterial,
                this.opaqueTrue, this.opaqueFalse, this.opaqueUnknown,
                this.fullCubeTrue, this.fullCubeFalse, this.fullCubeUnknown,
                this.renderTypeUnknown, Map.copyOf(allRenderTypes), this.registryNameUnknown,
                this.metadataUnknown, this.unknownState, this.blockReadFailures,
                this.registryNameReadFailures, this.materialReadFailures, this.metadataReadFailures,
                this.opaqueReadFailures, this.fullCubeReadFailures, this.renderTypeReadFailures,
                List.copyOf(topStates));
        }
    }

    private static final class StateObservation {
        private final String registryName;
        private final Material material;
        private final Integer metadata;
        private final Boolean opaque;
        private final Boolean fullCube;
        private final EnumBlockRenderType renderType;
        private final boolean registryNameUnknown;
        private final int readFailureMask;
        private int count;

        private StateObservation(String registryName, Material material, Integer metadata,
                                 Boolean opaque, Boolean fullCube, EnumBlockRenderType renderType,
                                 boolean registryNameUnknown, int readFailureMask) {
            this.registryName = registryName;
            this.material = material;
            this.metadata = metadata;
            this.opaque = opaque;
            this.fullCube = fullCube;
            this.renderType = renderType;
            this.registryNameUnknown = registryNameUnknown;
            this.readFailureMask = readFailureMask;
        }

        private static StateObservation read(IBlockState state) {
            int readFailureMask = 0;
            Block block = null;
            try {
                block = state.getBlock();
            } catch (RuntimeException readFailure) {
                readFailureMask |= READ_FAILURE_BLOCK;
            }

            String registryName = "unknown";
            boolean registryNameUnknown = true;
            if (block != null) {
                try {
                    ResourceLocation name = block.getRegistryName();
                    if (name == null) {
                        registryName = "unregistered";
                    } else {
                        registryName = name.toString();
                        registryNameUnknown = false;
                    }
                } catch (RuntimeException readFailure) {
                    readFailureMask |= READ_FAILURE_REGISTRY_NAME;
                }
            }

            Material material = null;
            try {
                material = state.getMaterial();
            } catch (RuntimeException readFailure) {
                readFailureMask |= READ_FAILURE_MATERIAL;
            }

            Integer metadata = null;
            if (block != null) {
                try {
                    metadata = block.getMetaFromState(state);
                } catch (RuntimeException readFailure) {
                    readFailureMask |= READ_FAILURE_METADATA;
                }
            }

            Boolean opaque = null;
            try {
                opaque = state.isOpaqueCube();
            } catch (RuntimeException readFailure) {
                readFailureMask |= READ_FAILURE_OPAQUE;
            }

            Boolean fullCube = null;
            try {
                fullCube = state.isFullCube();
            } catch (RuntimeException readFailure) {
                readFailureMask |= READ_FAILURE_FULL_CUBE;
            }

            EnumBlockRenderType renderType = null;
            try {
                renderType = state.getRenderType();
            } catch (RuntimeException readFailure) {
                readFailureMask |= READ_FAILURE_RENDER_TYPE;
            }

            return new StateObservation(registryName, material, metadata, opaque, fullCube,
                renderType, registryNameUnknown, readFailureMask);
        }
    }

    record StateCount(String registryName, Integer metadata, int count) {
    }

    private record StateKey(String registryName, Integer metadata) {
    }

    record SectionSummary(int blocks, int nonAir, int unknownMaterial,
                          int opaqueTrue, int opaqueFalse, int opaqueUnknown,
                          int fullCubeTrue, int fullCubeFalse, int fullCubeUnknown,
                          int renderTypeUnknown, Map<EnumBlockRenderType, Integer> renderTypeCounts,
                          int registryNameUnknown, int metadataUnknown, int unknownState,
                          int blockReadFailures, int registryNameReadFailures,
                          int materialReadFailures, int metadataReadFailures,
                          int opaqueReadFailures, int fullCubeReadFailures,
                          int renderTypeReadFailures,
                          List<StateCount> topStates) {
    }
}
