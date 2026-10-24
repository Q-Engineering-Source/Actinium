package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.compat.iris.BiomeCategoryCache;
import com.gtnewhorizons.angelica.compat.iris.ModdedBiomeDetector;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.parsing.BiomeCategories;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_TICK;

public class BiomeUniforms {

    private static final Logger LOGGER = LogManager.getLogger("BiomeUniforms");
    private static final Minecraft client = Minecraft.getMinecraft();

    // Cache to avoid multiple biome lookups per tick
    private static Biome cachedBiome = null;
    private static long cachedWorldTime = -1;
    private static int cachedPlayerX = Integer.MIN_VALUE;
    private static int cachedPlayerZ = Integer.MIN_VALUE;

    /**
     * Adds biome-related uniforms that change based on the player's current biome.
     */
    public static void addBiomeUniforms(UniformHolder uniforms) {
        uniforms.uniform1i(PER_TICK, "biome", BiomeUniforms::getBiomeId)
                .uniform1i(PER_TICK, "biome_category", BiomeUniforms::getBiomeCategory)
                .uniform1i(PER_TICK, "biome_precipitation", BiomeUniforms::getBiomePrecipitation)
                .uniform1f(PER_TICK, "rainfall", BiomeUniforms::getBiomeRainfall)
                .uniform1f(PER_TICK, "temperature", () -> BiomeUniforms.getBiomeTemperature());
    }

    /**
     * Gets the current biome, with caching to avoid repeated lookups within the same tick.
     * Returns null if player or world is not available.
     */
    private static Biome getCachedBiome() {
        if (client.player == null || client.world == null) {
            return null;
        }

        final long worldTime = client.world.getTotalWorldTime();
        final int playerX = MathHelper.floor(client.player.posX);
        final int playerZ = MathHelper.floor(client.player.posZ);

        if (cachedBiome == null || cachedWorldTime != worldTime || cachedPlayerX != playerX || cachedPlayerZ != playerZ) {
            cachedBiome = client.world.getBiome(new BlockPos(playerX, 0, playerZ));
            cachedWorldTime = worldTime;
            cachedPlayerX = playerX;
            cachedPlayerZ = playerZ;
        }

        return cachedBiome;
    }

    static int getBiomePrecipitation(Biome biome, BlockPos pos) {
        if (biome == null) {
            return 0;
        }

        if (!biome.canRain() && !biome.isSnowyBiome()) {
            return 0;
        }

        final float temp = biome.getTemperature(pos);

        return temp > 0.15F ? 1 : 2;
    }

    public static int getBiomePrecipitation() {
        final Biome biome = getCachedBiome();
        if (biome == null) {
            return 0;
        }

        final BlockPos pos = new BlockPos(
                MathHelper.floor(client.player.posX),
                MathHelper.floor(client.player.posY),
                MathHelper.floor(client.player.posZ));
        return getBiomePrecipitation(biome, pos);
    }

    public static float getBiomeRainfall() {
        final Biome biome = getCachedBiome();
        return biome != null ? biome.getRainfall() : 0.0F;
    }

    static float getBiomeTemperature(Biome biome) {
        return biome != null ? biome.getDefaultTemperature() : 0.0F;
    }

    public static float getBiomeTemperature() {
        return getBiomeTemperature(getCachedBiome());
    }

    /**
     * Biome ids in 1.7.10 are not unique, instead of this the biome category should be used
     */
    public static int getBiomeId() {
        final Biome biome = getCachedBiome();
        return biome != null ? Biome.getIdForBiome(biome) : 0;
    }

    static int getBiomeCategory(Biome biome) {
        if (biome == null) {
            return BiomeCategories.NONE.ordinal();
        }

        if (biome instanceof BiomeCategoryCache cache) {
            final int cached = cache.iris$getCachedCategory();
            if (cached != -1) {
                return cached;
            }

            final int category = determineBiomeCategory(biome);
            cache.iris$setCachedCategory(category);
            final BiomeCategories[] categories = BiomeCategories.values();
            final String categoryName = (category >= 0 && category < categories.length)
                ? categories[category].name()
                : "INVALID(" + category + ")";
            LOGGER.debug("Cached biome category for '{}': {}", biome.getBiomeName(), categoryName);

            return category;
        }

        // Fallback if mixin didn't apply (shouldn't happen)
        return determineBiomeCategory(biome);
    }

    public static int getBiomeCategory() {
        return getBiomeCategory(getCachedBiome());
    }

    private static int determineBiomeCategory(Biome biome) {
        final Biome lookupBiome = biome;

        BiomeCategories category = null;

        // Tier 1: Hardcoded vanilla biome IDs
        if (isVanillaBiome(biome)) {
            category = getVanillaBiomeCategory(Biome.getIdForBiome(lookupBiome));

            if (category != null) {
                return category.ordinal();
            }

            // Tier 2: forge BiomeDictionary detection
            if (BiomeDictionary.hasAnyType(biome)) {
                category = detectBiomeByBiomeDictionary(biome);
            }
        }

        if (category != null) {
            return category.ordinal();
        }

        // Tier 3: Class-based detection for vanilla biome types
        category = detectVanillaBiomeByClass(biome);
        if (category != null) {
            return category.ordinal();
        }

        // Tier 4: Modded biome detection (BiomesOPlenty, Realistic World Gen, lotr)
        category = detectModdedBiome(biome);
        if (category != null) {
            return category.ordinal();
        }

        // Tier 5: Name pattern matching
        category = detectBiomeByName(biome);
        if (category != null) {
            return category.ordinal();
        }

        // Tier 6: Temperature/rainfall heuristics
        category = detectBiomeByProperties(biome);
        if (category != null) {
            return category.ordinal();
        }

        // Default: NONE
        return BiomeCategories.NONE.ordinal();
    }

    private static BiomeCategories getVanillaBiomeCategory(int biomeID) {
        return switch (biomeID) {
            // OCEAN
            case 0, 10, 24 -> BiomeCategories.OCEAN; // ocean, frozenOcean, deepOcean

            // PLAINS
            case 1 -> BiomeCategories.PLAINS; // plains

            // DESERT
            case 2, 17 -> BiomeCategories.DESERT; // desert, desertHills

            // EXTREME_HILLS (Vanilla mountains)
            case 3, 20, 34 -> BiomeCategories.EXTREME_HILLS; // extremeHills, extremeHillsEdge, extremeHillsPlus

            // FOREST
            case 4, 18, 27, 28, 29 ->
                    BiomeCategories.FOREST; // forest, forestHills, birchForest, birchForestHills, roofedForest

            // TAIGA
            case 5, 19, 30, 31, 32, 33 ->
                    BiomeCategories.TAIGA; // taiga, taigaHills, coldTaiga, coldTaigaHills, megaTaiga, megaTaigaHills

            // SWAMP
            case 6 -> BiomeCategories.SWAMP; // swampland

            // RIVER
            case 7, 11 -> BiomeCategories.RIVER; // river, frozenRiver

            // NETHER
            case 8 -> BiomeCategories.NETHER; // hell

            // THE_END
            case 9 -> BiomeCategories.THE_END; // sky

            // ICY
            case 12, 13 -> BiomeCategories.ICY; // icePlains, iceMountains

            // MUSHROOM
            case 14, 15 -> BiomeCategories.MUSHROOM; // mushroomIsland, mushroomIslandShore

            // BEACH
            case 16, 25, 26 -> BiomeCategories.BEACH; // beach, stoneBeach, coldBeach

            // JUNGLE
            case 21, 22, 23 -> BiomeCategories.JUNGLE; // jungle, jungleHills, jungleEdge

            // SAVANNA
            case 35, 36 -> BiomeCategories.SAVANNA; // savanna, savannaPlateau

            // MESA
            case 37, 38, 39 -> BiomeCategories.MESA; // mesa, mesaPlateau_F, mesaPlateau

            default -> null; // Not a vanilla biome or unknown
        };
    }

    private static final Map<Class<? extends Biome>, BiomeCategories> VANILLA_CLASS_MAP = createVanillaClassMap();

    private static Map<Class<? extends Biome>, BiomeCategories> createVanillaClassMap() {
        final Map<Class<? extends Biome>, BiomeCategories> map = new HashMap<>();
        // Most specific first (subclasses before superclasses)
        map.put(net.minecraft.world.biome.BiomeStoneBeach.class, BiomeCategories.BEACH);
        map.put(net.minecraft.world.biome.BiomeBeach.class, BiomeCategories.BEACH);
        map.put(net.minecraft.world.biome.BiomeMushroomIsland.class, BiomeCategories.MUSHROOM);
        map.put(net.minecraft.world.biome.BiomeOcean.class, BiomeCategories.OCEAN);
        map.put(net.minecraft.world.biome.BiomePlains.class, BiomeCategories.PLAINS);
        map.put(net.minecraft.world.biome.BiomeDesert.class, BiomeCategories.DESERT);
        map.put(net.minecraft.world.biome.BiomeHills.class, BiomeCategories.EXTREME_HILLS);
        map.put(net.minecraft.world.biome.BiomeForest.class, BiomeCategories.FOREST);
        map.put(net.minecraft.world.biome.BiomeForestMutated.class, BiomeCategories.FOREST);
        map.put(net.minecraft.world.biome.BiomeTaiga.class, BiomeCategories.TAIGA);
        map.put(net.minecraft.world.biome.BiomeSwamp.class, BiomeCategories.SWAMP);
        map.put(net.minecraft.world.biome.BiomeRiver.class, BiomeCategories.RIVER);
        map.put(net.minecraft.world.biome.BiomeHell.class, BiomeCategories.NETHER);
        map.put(net.minecraft.world.biome.BiomeEnd.class, BiomeCategories.THE_END);
        map.put(net.minecraft.world.biome.BiomeSnow.class, BiomeCategories.ICY);
        map.put(net.minecraft.world.biome.BiomeJungle.class, BiomeCategories.JUNGLE);
        map.put(net.minecraft.world.biome.BiomeSavanna.class, BiomeCategories.SAVANNA);
        map.put(net.minecraft.world.biome.BiomeSavannaMutated.class, BiomeCategories.SAVANNA);
        map.put(net.minecraft.world.biome.BiomeMesa.class, BiomeCategories.MESA);
        map.put(net.minecraft.world.biome.BiomeVoid.class, BiomeCategories.NONE);
        return map;
    }

    private static BiomeCategories detectVanillaBiomeByClass(Biome biome) {
        // Direct class lookup (O(1) for exact matches)
        final BiomeCategories direct = VANILLA_CLASS_MAP.get(biome.getClass());
        if (direct != null) {
            return direct;
        }

        // Fallback: instanceof checks for subclasses (modded biomes extending vanilla)
        for (Map.Entry<Class<? extends Biome>, BiomeCategories> entry : VANILLA_CLASS_MAP.entrySet()) {
            if (entry.getKey().isInstance(biome)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static BiomeCategories detectModdedBiome(Biome biome) {
        return ModdedBiomeDetector.detectModdedBiome(biome);
    }

    private static BiomeCategories detectBiomeByName(Biome biome) {
        final String biomeName = biome.getBiomeName();
        if (biomeName == null) return null;

        final String name = biomeName.toLowerCase(Locale.ROOT);

        // Check for keywords in biome names
        // Priority order matters - check more specific terms first

        if (name.contains("nether")) return BiomeCategories.NETHER;
        if (name.contains("end")) return BiomeCategories.THE_END;

        if (name.contains("ocean") || name.contains("sea")) return BiomeCategories.OCEAN;
        if (name.contains("river") || name.contains("stream")) return BiomeCategories.RIVER;
        if (name.contains("beach") || name.contains("shore") || name.contains("coast")) return BiomeCategories.BEACH;

        if (name.contains("mushroom")) return BiomeCategories.MUSHROOM;
        if (name.contains("swamp") || name.contains("marsh") || name.contains("bog")) return BiomeCategories.SWAMP;

        if (name.contains("jungle")) return BiomeCategories.JUNGLE;
        if (name.contains("savanna") || name.contains("savannah")) return BiomeCategories.SAVANNA;
        if (name.contains("mesa") || name.contains("badlands")) return BiomeCategories.MESA;
        if (name.contains("desert")) return BiomeCategories.DESERT;

        // Mountain detection - for modded biomes
        if (name.contains("mountain") || name.contains("peak") || name.contains("alpine") ||
            name.contains("cliff") || name.contains("crag")) return BiomeCategories.MOUNTAIN;

        // Ice/Snow detection
        if (name.contains("ice") || name.contains("frozen") || name.contains("snow") ||
            name.contains("arctic") || name.contains("tundra") || name.contains("glacier")) return BiomeCategories.ICY;

        if (name.contains("taiga") || name.contains("boreal") || name.contains("conifer")) return BiomeCategories.TAIGA;

        if (name.contains("forest") || name.contains("wood") || name.contains("grove") ||
            name.contains("thicket")) return BiomeCategories.FOREST;

        if (name.contains("plain") || name.contains("field") || name.contains("meadow") ||
            name.contains("grassland") || name.contains("prairie")) return BiomeCategories.PLAINS;

        return null;
    }

    private static BiomeCategories detectBiomeByProperties(Biome biome) {
        final float temp = biome.getDefaultTemperature();
        final float rain = biome.getRainfall();

        // Very cold biomes with snow
        if (temp <= 0.0F && biome.isSnowyBiome()) {
            return BiomeCategories.ICY;
        }

        // Hot, dry biomes
        if (temp >= 2.0F && !biome.canRain()) {
            return BiomeCategories.DESERT;
        }

        // Warm, dry biomes (savanna-like)
        if (temp >= 1.0F && rain <= 0.1F && !biome.canRain()) {
            return BiomeCategories.SAVANNA;
        }

        // Wet biomes with high rainfall
        if (rain >= 0.85F && temp >= 0.5F && temp <= 1.0F) {
            return BiomeCategories.SWAMP;
        }

        // Cold forested biomes
        if (temp >= 0.0F && temp <= 0.4F && rain >= 0.4F) {
            return BiomeCategories.TAIGA;
        }

        // Temperate forested biomes
        if (temp >= 0.4F && temp <= 0.9F && rain >= 0.5F) {
            return BiomeCategories.FOREST;
        }

        // Default to plains for temperate, moderate biomes
        if (temp >= 0.4F && temp <= 1.0F && rain >= 0.3F && rain <= 0.6F) {
            return BiomeCategories.PLAINS;
        }

        return null;
    }

    /**
     * Use the forge BiomeDictionary when available
     */
    private static BiomeCategories detectBiomeByBiomeDictionary(Biome biome) {
        if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SAVANNA)) {
            return BiomeCategories.SAVANNA;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.JUNGLE)) {
            return BiomeCategories.JUNGLE;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.NETHER)) {
            return BiomeCategories.NETHER;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.END)) {
            return BiomeCategories.THE_END;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.MUSHROOM)) {
            return BiomeCategories.MUSHROOM;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)) {
            return BiomeCategories.OCEAN;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER)) {
            return BiomeCategories.RIVER;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.MESA)) {
            return BiomeCategories.MESA;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.FOREST)) {
            return BiomeCategories.FOREST;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.PLAINS)) {
            return BiomeCategories.PLAINS;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.MOUNTAIN)) {
            return BiomeCategories.MOUNTAIN;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SWAMP)) {
            return BiomeCategories.SWAMP;
        } else if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SANDY)) {
            return BiomeCategories.DESERT;
        }

        return null;
    }

    private static boolean isVanillaBiome(Biome biome) {
        return biome.getRegistryName() != null
                && "minecraft".equals(biome.getRegistryName().getNamespace());
    }
}
