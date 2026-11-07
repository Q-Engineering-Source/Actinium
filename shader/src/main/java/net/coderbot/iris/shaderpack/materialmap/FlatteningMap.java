package net.coderbot.iris.shaderpack.materialmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlatteningMap {
    private static final Map<String, List<BlockEntry>> MODERN_TO_LEGACY = new HashMap<>();
    private static final Map<String, List<BlockEntry>> STATE_MAPPINGS = new HashMap<>();

    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] WOOD_TYPES = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak"
    };

    static {
        rename("grass_block", "grass");
        rename("dirt_path", "grass_path");
        rename("note_block", "noteblock");
        rename("powered_rail", "golden_rail");
        rename("cobweb", "web");
        rename("dead_bush", "deadbush");
        rename("moving_piston", "piston_extension");
        rename("dandelion", "yellow_flower");
        rename("bricks", "brick_block");
        rename("spawner", "mob_spawner");
        rename("oak_door", "wooden_door");
        rename("cobblestone_stairs", "stone_stairs");
        rename("oak_pressure_plate", "wooden_pressure_plate");
        rename("snow_block", "snow");
        rename("sugar_cane", "reeds");
        rename("oak_fence", "fence");
        rename("carved_pumpkin", "pumpkin");
        rename("nether_portal", "portal");
        rename("jack_o_lantern", "lit_pumpkin");
        rename("oak_trapdoor", "trapdoor");
        rename("melon", "melon_block");
        rename("attached_pumpkin_stem", "pumpkin_stem");
        rename("attached_melon_stem", "melon_stem");
        rename("oak_fence_gate", "fence_gate");
        rename("lily_pad", "waterlily");
        rename("nether_bricks", "nether_brick");
        rename("oak_button", "wooden_button");
        rename("nether_quartz_ore", "quartz_ore");
        rename("terracotta", "hardened_clay");
        metas("wall_torch", "torch", 1, 2, 3, 4);
        rename("snow", "snow_layer");
        metas("redstone_wall_torch", "redstone_torch", 1, 2, 3, 4);
        rename("oak_sign", "standing_sign");
        rename("oak_wall_sign", "wall_sign");
        rename("spruce_sign", "standing_sign");
        rename("spruce_wall_sign", "wall_sign");
        rename("birch_sign", "standing_sign");
        rename("birch_wall_sign", "wall_sign");
        rename("jungle_sign", "standing_sign");
        rename("jungle_wall_sign", "wall_sign");
        rename("acacia_sign", "standing_sign");
        rename("acacia_wall_sign", "wall_sign");
        rename("dark_oak_sign", "standing_sign");
        rename("dark_oak_wall_sign", "wall_sign");
        head("skeleton_skull", 0);
        head("skeleton_wall_skull", 0);
        head("wither_skeleton_skull", 1);
        head("wither_skeleton_wall_skull", 1);
        head("zombie_head", 2);
        head("zombie_wall_head", 2);
        head("player_head", 3);
        head("player_wall_head", 3);
        head("creeper_head", 4);
        head("creeper_wall_head", 4);

        meta("granite", "stone", 1);
        meta("polished_granite", "stone", 2);
        meta("diorite", "stone", 3);
        meta("polished_diorite", "stone", 4);
        meta("andesite", "stone", 5);
        meta("polished_andesite", "stone", 6);
        meta("smooth_stone", "double_stone_slab", 8);

        meta("coarse_dirt", "dirt", 1);
        meta("podzol", "dirt", 2);

        meta("oak_planks", "planks", 0);
        meta("spruce_planks", "planks", 1);
        meta("birch_planks", "planks", 2);
        meta("jungle_planks", "planks", 3);
        meta("acacia_planks", "planks", 4);
        meta("dark_oak_planks", "planks", 5);

        metas("oak_sapling", "sapling", 0, 8);
        metas("spruce_sapling", "sapling", 1, 9);
        metas("birch_sapling", "sapling", 2, 10);
        metas("jungle_sapling", "sapling", 3, 11);
        metas("acacia_sapling", "sapling", 4, 12);
        metas("dark_oak_sapling", "sapling", 5, 13);

        meta("red_sand", "sand", 1);

        logVariants("oak_log", "oak_wood", 0);
        logVariants("spruce_log", "spruce_wood", 1);
        logVariants("birch_log", "birch_wood", 2);
        logVariants("jungle_log", "jungle_wood", 3);
        log2Variants("acacia_log", "acacia_wood", 0);
        log2Variants("dark_oak_log", "dark_oak_wood", 1);
        logVariants("stripped_oak_log", "stripped_oak_wood", 0);
        logVariants("stripped_spruce_log", "stripped_spruce_wood", 1);
        logVariants("stripped_birch_log", "stripped_birch_wood", 2);
        logVariants("stripped_jungle_log", "stripped_jungle_wood", 3);
        log2Variants("stripped_acacia_log", "stripped_acacia_wood", 0);
        log2Variants("stripped_dark_oak_log", "stripped_dark_oak_wood", 1);

        leafVariants("oak_leaves", "leaves", 0);
        leafVariants("spruce_leaves", "leaves", 1);
        leafVariants("birch_leaves", "leaves", 2);
        leafVariants("jungle_leaves", "leaves", 3);
        leafVariants("acacia_leaves", "leaves2", 0);
        leafVariants("dark_oak_leaves", "leaves2", 1);

        meta("wet_sponge", "sponge", 1);

        meta("chiseled_sandstone", "sandstone", 1);
        metas("cut_sandstone", "sandstone", 2);
        metas("smooth_sandstone", "sandstone", 2);
        meta("chiseled_red_sandstone", "red_sandstone", 1);
        metas("cut_red_sandstone", "red_sandstone", 2);
        metas("smooth_red_sandstone", "red_sandstone", 2);

        meta("grass", "tallgrass", 1);
        meta("short_grass", "tallgrass", 1);
        meta("fern", "tallgrass", 2);
        metas("tall_grass", "double_plant", 2, 10);
        metas("large_fern", "double_plant", 3, 11);
        metas("sunflower", "double_plant", 0, 8);
        metas("lilac", "double_plant", 1, 9);
        metas("rose_bush", "double_plant", 4, 12);
        metas("peony", "double_plant", 5, 13);
        doublePlant("sunflower", 0);
        doublePlant("lilac", 1);
        doublePlant("tall_grass", 2);
        doublePlant("large_fern", 3);
        doublePlant("rose_bush", 4);
        doublePlant("peony", 5);
        state("double_plant", "half", "lower", entryMetas("double_plant", 0, 1, 2, 3, 4, 5));
        state("double_plant", "half", "upper", entryMetas("double_plant", 8, 9, 10, 11, 12, 13));

        meta("poppy", "red_flower", 0);
        meta("blue_orchid", "red_flower", 1);
        meta("allium", "red_flower", 2);
        meta("azure_bluet", "red_flower", 3);
        meta("red_tulip", "red_flower", 4);
        meta("orange_tulip", "red_flower", 5);
        meta("white_tulip", "red_flower", 6);
        meta("pink_tulip", "red_flower", 7);
        meta("oxeye_daisy", "red_flower", 8);
        metas("water_cauldron", "cauldron", 1, 2, 3);
        meta("cauldron", "cauldron", 0);
        meta("mossy_cobblestone_wall", "cobblestone_wall", 1);
        pot("potted_oak_sapling", "sapling", 0);
        pot("potted_spruce_sapling", "sapling", 1);
        pot("potted_birch_sapling", "sapling", 2);
        pot("potted_jungle_sapling", "sapling", 3);
        pot("potted_acacia_sapling", "sapling", 4);
        pot("potted_dark_oak_sapling", "sapling", 5);
        pot("potted_fern", "tallgrass", 2);
        pot("potted_dandelion", "yellow_flower", 0);
        pot("potted_poppy", "red_flower", 0);
        pot("potted_blue_orchid", "red_flower", 1);
        pot("potted_allium", "red_flower", 2);
        pot("potted_azure_bluet", "red_flower", 3);
        pot("potted_red_tulip", "red_flower", 4);
        pot("potted_orange_tulip", "red_flower", 5);
        pot("potted_white_tulip", "red_flower", 6);
        pot("potted_pink_tulip", "red_flower", 7);
        pot("potted_oxeye_daisy", "red_flower", 8);
        pot("potted_red_mushroom", "red_mushroom", 0);
        pot("potted_brown_mushroom", "brown_mushroom", 0);
        pot("potted_dead_bush", "deadbush", 0);
        pot("potted_cactus", "cactus", 0);

        for (int i = 0; i < COLORS.length; i++) {
            String color = COLORS[i];
            meta(color + "_wool", "wool", i);
            meta(color + "_stained_glass", "stained_glass", i);
            meta(color + "_stained_glass_pane", "stained_glass_pane", i);
            meta(color + "_carpet", "carpet", i);
            meta(color + "_terracotta", "stained_hardened_clay", i);
            meta(color + "_stained_hardened_clay", "stained_hardened_clay", i);
            rename(color + "_bed", "bed");
        }

        metas("brown_mushroom_block", "brown_mushroom_block", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14);
        metas("red_mushroom_block", "red_mushroom_block", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14);
        multi("mushroom_stem",
                entryMetas("brown_mushroom_block", 10, 15),
                entryMetas("red_mushroom_block", 10, 15));

        meta("infested_stone", "monster_egg", 0);
        meta("infested_cobblestone", "monster_egg", 1);
        meta("infested_stone_bricks", "monster_egg", 2);
        meta("infested_mossy_stone_bricks", "monster_egg", 3);
        meta("infested_cracked_stone_bricks", "monster_egg", 4);
        meta("infested_chiseled_stone_bricks", "monster_egg", 5);

        meta("stone_bricks", "stonebrick", 0);
        meta("mossy_stone_bricks", "stonebrick", 1);
        meta("cracked_stone_bricks", "stonebrick", 2);
        meta("chiseled_stone_bricks", "stonebrick", 3);

        slabVariants("stone_slab", "stone_slab", 0);
        slabVariants("sandstone_slab", "stone_slab", 1);
        slabVariants("petrified_oak_slab", "stone_slab", 2);
        slabVariants("cobblestone_slab", "stone_slab", 3);
        slabVariants("brick_slab", "stone_slab", 4);
        slabVariants("stone_brick_slab", "stone_slab", 5);
        slabVariants("nether_brick_slab", "stone_slab", 6);
        slabVariants("quartz_slab", "stone_slab", 7);
        slabVariants("red_sandstone_slab", "stone_slab2", 0);
        slabVariants("purpur_slab", "stone_slab2", 1);
        slabVariants("oak_slab", "wooden_slab", 0);
        slabVariants("spruce_slab", "wooden_slab", 1);
        slabVariants("birch_slab", "wooden_slab", 2);
        slabVariants("jungle_slab", "wooden_slab", 3);
        slabVariants("acacia_slab", "wooden_slab", 4);
        slabVariants("dark_oak_slab", "wooden_slab", 5);
        slabStates("stone_slab", "stone_slab", 0, "double_stone_slab", 0);
        slabStates("sandstone_slab", "stone_slab", 1, "double_stone_slab", 1);
        slabStates("petrified_oak_slab", "stone_slab", 2, "double_stone_slab", 2);
        slabStates("cobblestone_slab", "stone_slab", 3, "double_stone_slab", 3);
        slabStates("brick_slab", "stone_slab", 4, "double_stone_slab", 4);
        slabStates("stone_brick_slab", "stone_slab", 5, "double_stone_slab", 5);
        slabStates("nether_brick_slab", "stone_slab", 6, "double_stone_slab", 6);
        slabStates("quartz_slab", "stone_slab", 7, "double_stone_slab", 7);
        slabStates("red_sandstone_slab", "stone_slab2", 0, "double_stone_slab2", 0);
        slabStates("purpur_slab", "stone_slab2", 1, "double_stone_slab2", 1);
        slabStates("oak_slab", "wooden_slab", 0, "double_wooden_slab", 0);
        slabStates("spruce_slab", "wooden_slab", 1, "double_wooden_slab", 1);
        slabStates("birch_slab", "wooden_slab", 2, "double_wooden_slab", 2);
        slabStates("jungle_slab", "wooden_slab", 3, "double_wooden_slab", 3);
        slabStates("acacia_slab", "wooden_slab", 4, "double_wooden_slab", 4);
        slabStates("dark_oak_slab", "wooden_slab", 5, "double_wooden_slab", 5);

        multi("water", entry("water"), entry("flowing_water"));
        multi("lava", entry("lava"), entry("flowing_lava"));
        liquidLevels("water", "water", "flowing_water");
        liquidLevels("lava", "lava", "flowing_lava");

        state("furnace", "lit", "false", entry("furnace"));
        state("furnace", "lit", "true", entry("lit_furnace"));
        state("redstone_torch", "lit", "false", entry("unlit_redstone_torch"));
        state("redstone_torch", "lit", "true", entry("redstone_torch"));
        state("redstone_wall_torch", "lit", "false", entryMetas("unlit_redstone_torch", 1, 2, 3, 4));
        state("redstone_wall_torch", "lit", "true", entryMetas("redstone_torch", 1, 2, 3, 4));
        state("redstone_ore", "lit", "false", entry("redstone_ore"));
        state("redstone_ore", "lit", "true", entry("lit_redstone_ore"));
        state("redstone_lamp", "lit", "false", entry("redstone_lamp"));
        state("redstone_lamp", "lit", "true", entry("lit_redstone_lamp"));
        state("daylight_detector", "inverted", "false", entry("daylight_detector"));
        state("daylight_detector", "inverted", "true", entry("daylight_detector_inverted"));
        state("repeater", "powered", "false", entry("unpowered_repeater"));
        state("repeater", "powered", "true", entry("powered_repeater"));
        state("comparator", "powered", "false", entry("unpowered_comparator"));
        state("comparator", "powered", "true", entry("powered_comparator"));

        for (int power = 0; power <= 15; power++) {
            state("redstone_wire", "power", String.valueOf(power), entryMetas("redstone_wire", power));
        }

        for (int layers = 1; layers <= 8; layers++) {
            state("snow", "layers", String.valueOf(layers), entryMetas("snow_layer", layers - 1));
        }

        state("farmland", "moisture", "0", entryMetas("farmland", 0));
        state("farmland", "moisture", "1", entryMetas("farmland", 0));
        state("farmland", "moisture", "2", entryMetas("farmland", 0));
        state("farmland", "moisture", "3", entryMetas("farmland", 0));
        state("farmland", "moisture", "4", entryMetas("farmland", 0));
        state("farmland", "moisture", "5", entryMetas("farmland", 0));
        state("farmland", "moisture", "6", entryMetas("farmland", 0));
        state("farmland", "moisture", "7", entryMetas("farmland", 1, 2, 3, 4, 5, 6, 7));

        for (int age = 0; age <= 7; age++) {
            state("wheat", "age", String.valueOf(age), entryMetas("wheat", age));
            state("carrots", "age", String.valueOf(age), entryMetas("carrots", age));
            state("potatoes", "age", String.valueOf(age), entryMetas("potatoes", age));
            state("pumpkin_stem", "age", String.valueOf(age), entryMetas("pumpkin_stem", age));
            state("melon_stem", "age", String.valueOf(age), entryMetas("melon_stem", age));
        }

        state("cocoa", "facing", "south", entryMetas("cocoa", 0, 4, 8));
        state("cocoa", "facing", "west", entryMetas("cocoa", 1, 5, 9));
        state("cocoa", "facing", "north", entryMetas("cocoa", 2, 6, 10));
        state("cocoa", "facing", "east", entryMetas("cocoa", 3, 7, 11));
        state("cocoa", "age", "0", entryMetas("cocoa", 0, 1, 2, 3));
        state("cocoa", "age", "1", entryMetas("cocoa", 4, 5, 6, 7));
        state("cocoa", "age", "2", entryMetas("cocoa", 8, 9, 10, 11));

        state("hopper", "facing", "down", entryMetas("hopper", 0, 8));
        state("hopper", "facing", "north", entryMetas("hopper", 2, 10));
        state("hopper", "facing", "south", entryMetas("hopper", 3, 11));
        state("hopper", "facing", "west", entryMetas("hopper", 4, 12));
        state("hopper", "facing", "east", entryMetas("hopper", 5, 13));
        state("hopper", "enabled", "true", entryMetas("hopper", 0, 2, 3, 4, 5));
        state("hopper", "enabled", "false", entryMetas("hopper", 8, 10, 11, 12, 13));

        state("furnace", "facing", "north", entryMetas("furnace", 2), entryMetas("lit_furnace", 2));
        state("furnace", "facing", "south", entryMetas("furnace", 3), entryMetas("lit_furnace", 3));
        state("furnace", "facing", "west", entryMetas("furnace", 4), entryMetas("lit_furnace", 4));
        state("furnace", "facing", "east", entryMetas("furnace", 5), entryMetas("lit_furnace", 5));

        state("brewing_stand", "has_bottle_0", "true", entryMetas("brewing_stand", 1, 3, 5, 7));
        state("brewing_stand", "has_bottle_0", "false", entryMetas("brewing_stand", 0, 2, 4, 6));
        state("brewing_stand", "has_bottle_1", "true", entryMetas("brewing_stand", 2, 3, 6, 7));
        state("brewing_stand", "has_bottle_1", "false", entryMetas("brewing_stand", 0, 1, 4, 5));
        state("brewing_stand", "has_bottle_2", "true", entryMetas("brewing_stand", 4, 5, 6, 7));
        state("brewing_stand", "has_bottle_2", "false", entryMetas("brewing_stand", 0, 1, 2, 3));

        state("jukebox", "has_record", "false", entryMetas("jukebox", 0));
        state("jukebox", "has_record", "true", entryMetas("jukebox", 1));
        state("water_cauldron", "level", "1", entryMetas("cauldron", 1));
        state("water_cauldron", "level", "2", entryMetas("cauldron", 2));
        state("water_cauldron", "level", "3", entryMetas("cauldron", 3));

        state("piston", "extended", "false", entryMetas("piston", 0, 1, 2, 3, 4, 5));
        state("piston", "extended", "true", entryMetas("piston", 8, 9, 10, 11, 12, 13));
        state("sticky_piston", "extended", "false", entryMetas("sticky_piston", 0, 1, 2, 3, 4, 5));
        state("sticky_piston", "extended", "true", entryMetas("sticky_piston", 8, 9, 10, 11, 12, 13));
        repeaterFacing("repeater", "south", 0, 4, 8, 12);
        repeaterFacing("repeater", "west", 1, 5, 9, 13);
        repeaterFacing("repeater", "north", 2, 6, 10, 14);
        repeaterFacing("repeater", "east", 3, 7, 11, 15);
        repeaterDelay("repeater", "1", 0, 1, 2, 3);
        repeaterDelay("repeater", "2", 4, 5, 6, 7);
        repeaterDelay("repeater", "3", 8, 9, 10, 11);
        repeaterDelay("repeater", "4", 12, 13, 14, 15);

        comparatorFacing("comparator", "south", 0, 4, 8, 12);
        comparatorFacing("comparator", "west", 1, 5, 9, 13);
        comparatorFacing("comparator", "north", 2, 6, 10, 14);
        comparatorFacing("comparator", "east", 3, 7, 11, 15);
        comparatorMode("compare", 0, 1, 2, 3, 8, 9, 10, 11);
        comparatorMode("subtract", 4, 5, 6, 7, 12, 13, 14, 15);

        railShape("north_south", 0);
        railShape("east_west", 1);
        railShape("ascending_east", 2);
        railShape("ascending_west", 3);
        railShape("ascending_north", 4);
        railShape("ascending_south", 5);
        railShape("north_east", 6);
        railShape("south_east", 7);
        railShape("south_west", 8);
        railShape("north_west", 9);
        poweredRailStates("powered_rail", "golden_rail");
        poweredRailStates("detector_rail", "detector_rail");
        poweredRailStates("activator_rail", "activator_rail");

        state("end_portal_frame", "eye", "true", entryMetas("end_portal_frame", 4, 5, 6, 7));
        state("end_portal_frame", "eye", "false", entryMetas("end_portal_frame", 0, 1, 2, 3));
        state("end_portal_frame", "facing", "south", entryMetas("end_portal_frame", 0, 4));
        state("end_portal_frame", "facing", "west", entryMetas("end_portal_frame", 1, 5));
        state("end_portal_frame", "facing", "north", entryMetas("end_portal_frame", 2, 6));
        state("end_portal_frame", "facing", "east", entryMetas("end_portal_frame", 3, 7));

        for (int rotation = 0; rotation <= 15; rotation++) {
            signRotation("oak_sign", rotation);
            signRotation("spruce_sign", rotation);
            signRotation("birch_sign", rotation);
            signRotation("jungle_sign", rotation);
            signRotation("acacia_sign", rotation);
            signRotation("dark_oak_sign", rotation);
        }

        wallFacing("oak_wall_sign", "wall_sign");
        wallFacing("spruce_wall_sign", "wall_sign");
        wallFacing("birch_wall_sign", "wall_sign");
        wallFacing("jungle_wall_sign", "wall_sign");
        wallFacing("acacia_wall_sign", "wall_sign");
        wallFacing("dark_oak_wall_sign", "wall_sign");
        wallFacing("skeleton_wall_skull", "skull");
        wallFacing("wither_skeleton_wall_skull", "skull");
        wallFacing("zombie_wall_head", "skull");
        wallFacing("player_wall_head", "skull");
        wallFacing("creeper_wall_head", "skull");

        metas("chipped_anvil", "anvil", 4, 5, 6, 7);
        metas("damaged_anvil", "anvil", 8, 9, 10, 11);
        wallFacing("chest", "chest");
        wallFacing("trapped_chest", "trapped_chest");
        wallFacing("ender_chest", "ender_chest");
        wallFacing("ladder", "ladder");

        state("lever", "face", "floor", entryMetas("lever", 5, 6, 13, 14));
        state("lever", "face", "wall", entryMetas("lever", 1, 2, 3, 4, 9, 10, 11, 12));
        state("lever", "face", "ceiling", entryMetas("lever", 0, 7, 8, 15));
        state("lever", "powered", "false", entryMetas("lever", 0, 1, 2, 3, 4, 5, 6, 7));
        state("lever", "powered", "true", entryMetas("lever", 8, 9, 10, 11, 12, 13, 14, 15));
        state("lever", "facing", "east", entryMetas("lever", 1, 6, 0, 9, 14, 8));
        state("lever", "facing", "west", entryMetas("lever", 2, 6, 0, 10, 14, 8));
        state("lever", "facing", "south", entryMetas("lever", 3, 5, 7, 11, 13, 15));
        state("lever", "facing", "north", entryMetas("lever", 4, 5, 7, 12, 13, 15));

        buttonStates("stone_button", "stone_button");
        buttonStates("oak_button", "wooden_button");
        pumpkinFacingStates("carved_pumpkin", "pumpkin");
        pumpkinFacingStates("jack_o_lantern", "lit_pumpkin");
        anvilFacingStates("anvil", 0);
        anvilFacingStates("chipped_anvil", 4);
        anvilFacingStates("damaged_anvil", 8);

        logAxisStates("oak_log", 0);
        logAxisStates("spruce_log", 1);
        logAxisStates("birch_log", 2);
        logAxisStates("jungle_log", 3);
        logAxisStates("stripped_oak_log", 0);
        logAxisStates("stripped_spruce_log", 1);
        logAxisStates("stripped_birch_log", 2);
        logAxisStates("stripped_jungle_log", 3);
        log2AxisStates("acacia_log", 0);
        log2AxisStates("dark_oak_log", 1);
        log2AxisStates("stripped_acacia_log", 0);
        log2AxisStates("stripped_dark_oak_log", 1);
        woodAxisStates("oak_wood", 12);
        woodAxisStates("spruce_wood", 13);
        woodAxisStates("birch_wood", 14);
        woodAxisStates("jungle_wood", 15);
        woodAxisStates("stripped_oak_wood", 12);
        woodAxisStates("stripped_spruce_wood", 13);
        woodAxisStates("stripped_birch_wood", 14);
        woodAxisStates("stripped_jungle_wood", 15);
        wood2AxisStates("acacia_wood", 12, 14);
        wood2AxisStates("dark_oak_wood", 13, 15);
        wood2AxisStates("stripped_acacia_wood", 12, 14);
        wood2AxisStates("stripped_dark_oak_wood", 13, 15);
        state("hay_block", "axis", "y", entryMetas("hay_block", 0));
        state("hay_block", "axis", "x", entryMetas("hay_block", 4));
        state("hay_block", "axis", "z", entryMetas("hay_block", 8));
        state("quartz_pillar", "axis", "y", entryMetas("quartz_block", 2));
        state("quartz_pillar", "axis", "x", entryMetas("quartz_block", 3));
        state("quartz_pillar", "axis", "z", entryMetas("quartz_block", 4));

        for (int i = 0; i < 6; i++) {
            state(WOOD_TYPES[i] + "_sapling", "stage", "0", entryMetas("sapling", i));
            state(WOOD_TYPES[i] + "_sapling", "stage", "1", entryMetas("sapling", i + 8));
        }

        state("acacia_leaves", "persistent", "true", entryMetas("leaves2", 4, 12));
        state("acacia_leaves", "persistent", "false", entryMetas("leaves2", 0, 8));
        state("dark_oak_leaves", "persistent", "true", entryMetas("leaves2", 5, 13));
        state("dark_oak_leaves", "persistent", "false", entryMetas("leaves2", 1, 9));
        state("oak_leaves", "persistent", "true", entryMetas("leaves", 4, 12));
        state("oak_leaves", "persistent", "false", entryMetas("leaves", 0, 8));
        state("spruce_leaves", "persistent", "true", entryMetas("leaves", 5, 13));
        state("spruce_leaves", "persistent", "false", entryMetas("leaves", 1, 9));
        state("birch_leaves", "persistent", "true", entryMetas("leaves", 6, 14));
        state("birch_leaves", "persistent", "false", entryMetas("leaves", 2, 10));
        state("jungle_leaves", "persistent", "true", entryMetas("leaves", 7, 15));
        state("jungle_leaves", "persistent", "false", entryMetas("leaves", 3, 11));

        state("wall_torch", "facing", "east", entryMetas("torch", 1));
        state("wall_torch", "facing", "west", entryMetas("torch", 2));
        state("wall_torch", "facing", "south", entryMetas("torch", 3));
        state("wall_torch", "facing", "north", entryMetas("torch", 4));
        state("redstone_wall_torch", "facing", "east",
                entryMetas("redstone_torch", 1), entryMetas("unlit_redstone_torch", 1));
        state("redstone_wall_torch", "facing", "west",
                entryMetas("redstone_torch", 2), entryMetas("unlit_redstone_torch", 2));
        state("redstone_wall_torch", "facing", "south",
                entryMetas("redstone_torch", 3), entryMetas("unlit_redstone_torch", 3));
        state("redstone_wall_torch", "facing", "north",
                entryMetas("redstone_torch", 4), entryMetas("unlit_redstone_torch", 4));

        state("tripwire_hook", "facing", "south", entryMetas("tripwire_hook", 0, 4, 8, 12));
        state("tripwire_hook", "facing", "west", entryMetas("tripwire_hook", 1, 5, 9, 13));
        state("tripwire_hook", "facing", "north", entryMetas("tripwire_hook", 2, 6, 10, 14));
        state("tripwire_hook", "facing", "east", entryMetas("tripwire_hook", 3, 7, 11, 15));
        state("tripwire_hook", "attached", "false", entryMetas("tripwire_hook", 0, 1, 2, 3, 8, 9, 10, 11));
        state("tripwire_hook", "attached", "true", entryMetas("tripwire_hook", 4, 5, 6, 7, 12, 13, 14, 15));
        state("tripwire_hook", "powered", "false", entryMetas("tripwire_hook", 0, 1, 2, 3, 4, 5, 6, 7));
        state("tripwire_hook", "powered", "true", entryMetas("tripwire_hook", 8, 9, 10, 11, 12, 13, 14, 15));

        state("tripwire", "powered", "true", entryMetas("tripwire", 1, 5, 9, 13));
        state("tripwire", "powered", "false", entryMetas("tripwire", 0, 4, 8, 12));
        state("tripwire", "attached", "true", entryMetas("tripwire", 4, 5, 12, 13));
        state("tripwire", "attached", "false", entryMetas("tripwire", 0, 1, 8, 9));
        state("tripwire", "disarmed", "true", entryMetas("tripwire", 8, 9, 12, 13));
        state("tripwire", "disarmed", "false", entryMetas("tripwire", 0, 1, 4, 5));

        state("vine", "south", "true", entryMetas("vine", 1, 3, 5, 7, 9, 11, 13, 15));
        state("vine", "west", "true", entryMetas("vine", 2, 3, 6, 7, 10, 11, 14, 15));
        state("vine", "north", "true", entryMetas("vine", 4, 5, 6, 7, 12, 13, 14, 15));
        state("vine", "east", "true", entryMetas("vine", 8, 9, 10, 11, 12, 13, 14, 15));
        state("vine", "south", "false", entryMetas("vine", 0, 2, 4, 6, 8, 10, 12, 14));
        state("vine", "west", "false", entryMetas("vine", 0, 1, 4, 5, 8, 9, 12, 13));
        state("vine", "north", "false", entryMetas("vine", 0, 1, 2, 3, 8, 9, 10, 11));
        state("vine", "east", "false", entryMetas("vine", 0, 1, 2, 3, 4, 5, 6, 7));

        stairStates("oak_stairs", "oak_stairs");
        stairStates("cobblestone_stairs", "stone_stairs");
        stairStates("brick_stairs", "brick_stairs");
        stairStates("stone_brick_stairs", "stone_brick_stairs");
        stairStates("nether_brick_stairs", "nether_brick_stairs");
        stairStates("sandstone_stairs", "sandstone_stairs");
        stairStates("spruce_stairs", "spruce_stairs");
        stairStates("birch_stairs", "birch_stairs");
        stairStates("jungle_stairs", "jungle_stairs");
        stairStates("quartz_stairs", "quartz_stairs");
        stairStates("acacia_stairs", "acacia_stairs");
        stairStates("dark_oak_stairs", "dark_oak_stairs");
        stairStates("red_sandstone_stairs", "red_sandstone_stairs");
        stairStates("purpur_stairs", "purpur_stairs");
        doorStates("oak_door", "wooden_door");
        doorStates("iron_door", "iron_door");
        sixDirectionalStates("piston", "piston", "extended", "false", "true");
        sixDirectionalStates("sticky_piston", "sticky_piston", "extended", "false", "true");
        sixDirectionalStates("piston_head", "piston_head", "type", "normal", "sticky");
        sixDirectionalStates("dispenser", "dispenser", "triggered", "false", "true");
        sixDirectionalStates("dropper", "dropper", "triggered", "false", "true");

        for (String color : COLORS) {
            bedStates(color + "_bed");
        }

        trapdoorStates("oak_trapdoor", "trapdoor");
        trapdoorStates("spruce_trapdoor", "spruce_trapdoor");
        trapdoorStates("birch_trapdoor", "birch_trapdoor");
        trapdoorStates("jungle_trapdoor", "jungle_trapdoor");
        trapdoorStates("acacia_trapdoor", "acacia_trapdoor");
        trapdoorStates("dark_oak_trapdoor", "dark_oak_trapdoor");
        trapdoorStates("iron_trapdoor", "iron_trapdoor");
        fenceGateStates();
    }

    private FlatteningMap() {
    }

    public static List<BlockEntry> toLegacy(String modernName) {
        return MODERN_TO_LEGACY.get(modernName);
    }

    public static Map<String, String> getUnmappedStateProperties(String modernName, Map<String, String> stateProperties) {
        if (stateProperties == null || stateProperties.isEmpty()) {
            return stateProperties;
        }

        Map<String, String> remaining = null;

        for (Map.Entry<String, String> property : stateProperties.entrySet()) {
            if (!STATE_MAPPINGS.containsKey(stateKey(modernName, property.getKey(), property.getValue()))) {
                continue;
            }

            if (remaining == null) {
                remaining = new java.util.LinkedHashMap<>(stateProperties);
            }

            remaining.remove(property.getKey());
        }

        if (remaining == null) {
            return stateProperties;
        }

        return remaining.isEmpty() ? Collections.emptyMap() : remaining;
    }

    public static List<BlockEntry> toLegacy(String modernName, Map<String, String> stateProperties) {
        if (stateProperties != null && !stateProperties.isEmpty()) {
            List<List<BlockEntry>> propertyResults = new ArrayList<>();

            for (Map.Entry<String, String> property : stateProperties.entrySet()) {
                List<BlockEntry> result = STATE_MAPPINGS.get(stateKey(modernName, property.getKey(), property.getValue()));
                if (result != null) {
                    propertyResults.add(result);
                }
            }

            if (propertyResults.size() == 1) {
                return propertyResults.get(0);
            }

            if (propertyResults.size() > 1) {
                List<BlockEntry> combined = propertyResults.get(0);
                for (int i = 1; i < propertyResults.size(); i++) {
                    combined = intersectBlockEntries(combined, propertyResults.get(i));
                    if (combined.isEmpty()) {
                        break;
                    }
                }

                if (!combined.isEmpty()) {
                    return combined;
                }
            }
        }

        return MODERN_TO_LEGACY.get(modernName);
    }

    private static List<BlockEntry> intersectBlockEntries(List<BlockEntry> a, List<BlockEntry> b) {
        List<BlockEntry> result = new ArrayList<>(Math.min(a.size(), b.size()));
        for (BlockEntry entryA : a) {
            for (BlockEntry entryB : b) {
                if (!entryA.getId().equals(entryB.getId())) {
                    continue;
                }

                Set<Integer> metasA = entryA.getMetas();
                Set<Integer> metasB = entryB.getMetas();

                if (metasA.isEmpty() && metasB.isEmpty()) {
                    result.add(new BlockEntry(entryA.getId(), Collections.emptySet()));
                } else if (metasA.isEmpty()) {
                    result.add(new BlockEntry(entryA.getId(), metasB));
                } else if (metasB.isEmpty()) {
                    result.add(new BlockEntry(entryA.getId(), metasA));
                } else {
                    Set<Integer> intersection = new java.util.HashSet<>(metasA);
                    intersection.retainAll(metasB);
                    if (!intersection.isEmpty()) {
                        result.add(new BlockEntry(entryA.getId(), Set.copyOf(intersection)));
                    }
                }
            }
        }

        return result;
    }

    private static void rename(String modern, String legacy) {
        MODERN_TO_LEGACY.put(modern, List.of(new BlockEntry(new NamespacedId("minecraft", legacy), Collections.emptySet())));
    }

    private static void meta(String modern, String legacy, int meta) {
        MODERN_TO_LEGACY.put(modern, List.of(new BlockEntry(new NamespacedId("minecraft", legacy), Collections.singleton(meta))));
    }

    private static void metas(String modern, String legacy, int... metaValues) {
        ArrayList<Integer> metaList = new ArrayList<>(metaValues.length);
        for (int metaValue : metaValues) {
            metaList.add(metaValue);
        }
        MODERN_TO_LEGACY.put(modern, List.of(new BlockEntry(new NamespacedId("minecraft", legacy), Set.copyOf(metaList))));
    }

    private static void pot(String modern, String itemName, int data) {
        MODERN_TO_LEGACY.put(modern, List.of(new BlockEntry(
                new NamespacedId("minecraft", "flower_pot"),
                Collections.emptySet(),
                Collections.emptyMap(),
                Map.of(
                        "Item", new PropertiesTokenizer.NbtValue("minecraft:" + itemName, false),
                        "Data", new PropertiesTokenizer.NbtValue(String.valueOf(data), false)
                ))));
    }

    private static void head(String modern, int skullType) {
        MODERN_TO_LEGACY.put(modern, List.of(new BlockEntry(
                new NamespacedId("minecraft", "skull"),
                Collections.emptySet(),
                Collections.emptyMap(),
                Map.of("SkullType", new PropertiesTokenizer.NbtValue(String.valueOf(skullType), false))
        )));
    }

    private static void multi(String modern, BlockEntry... entries) {
        MODERN_TO_LEGACY.put(modern, List.of(entries));
    }

    private static void state(String modern, String property, String value, BlockEntry... entries) {
        STATE_MAPPINGS.put(stateKey(modern, property, value), List.of(entries));
    }

    private static String stateKey(String modern, String property, String value) {
        return modern + "|" + property + "=" + value;
    }

    private static void liquidLevels(String modern, String legacyStatic, String legacyDynamic) {
        for (int level = 0; level <= 15; level++) {
            state(modern, "level", String.valueOf(level), entryMetas(legacyStatic, level), entryMetas(legacyDynamic, level));
        }
    }

    private static BlockEntry entry(String legacyName) {
        return new BlockEntry(new NamespacedId("minecraft", legacyName), Collections.emptySet());
    }

    private static BlockEntry entryMetas(String legacyName, int... metaValues) {
        ArrayList<Integer> metaList = new ArrayList<>(metaValues.length);
        for (int metaValue : metaValues) {
            metaList.add(metaValue);
        }
        return new BlockEntry(new NamespacedId("minecraft", legacyName), Set.copyOf(metaList));
    }

    private static void logVariants(String logName, String woodName, int typeOffset) {
        metas(logName, "log", typeOffset, typeOffset + 4, typeOffset + 8);
        meta(woodName, "log", typeOffset + 12);
    }

    private static void log2Variants(String logName, String woodName, int typeOffset) {
        int phantomTypeOffset = typeOffset + 2;
        metas(logName, "log2",
                typeOffset, typeOffset + 4, typeOffset + 8,
                phantomTypeOffset, phantomTypeOffset + 4, phantomTypeOffset + 8);
        metas(woodName, "log2", typeOffset + 12, phantomTypeOffset + 12);
    }

    private static void leafVariants(String modern, String legacy, int typeOffset) {
        metas(modern, legacy, typeOffset, typeOffset + 4, typeOffset + 8, typeOffset + 12);
    }

    private static void slabVariants(String modern, String legacy, int typeOffset) {
        metas(modern, legacy, typeOffset, typeOffset + 8);
    }

    private static void slabStates(String modern, String slabBlock, int typeOffset, String doubleBlock, int doubleMeta) {
        state(modern, "type", "bottom", entryMetas(slabBlock, typeOffset));
        state(modern, "type", "top", entryMetas(slabBlock, typeOffset + 8));
        state(modern, "type", "double", entryMetas(doubleBlock, doubleMeta));
    }

    private static void doublePlant(String modern, int variant) {
        int upper = 0x8 | variant;
        state(modern, "half", "lower", entryMetas("double_plant", variant));
        state(modern, "half", "upper", entryMetas("double_plant", upper));
    }

    private static void signRotation(String modern, int rotation) {
        state(modern, "rotation", String.valueOf(rotation), entryMetas("standing_sign", rotation));
    }

    private static void wallFacing(String modern, String legacy) {
        state(modern, "facing", "north", entryMetas(legacy, 2));
        state(modern, "facing", "south", entryMetas(legacy, 3));
        state(modern, "facing", "west", entryMetas(legacy, 4));
        state(modern, "facing", "east", entryMetas(legacy, 5));
    }

    private static void railShape(String shape, int meta) {
        state("rail", "shape", shape, entryMetas("rail", meta));
    }

    private static void repeaterFacing(String modern, String facing, int... metas) {
        state(modern, "facing", facing,
                entryMetas("unpowered_repeater", metas),
                entryMetas("powered_repeater", metas));
    }

    private static void repeaterDelay(String modern, String delay, int... metas) {
        state(modern, "delay", delay,
                entryMetas("unpowered_repeater", metas),
                entryMetas("powered_repeater", metas));
    }

    private static void comparatorFacing(String modern, String facing, int... metas) {
        state(modern, "facing", facing,
                entryMetas("unpowered_comparator", metas),
                entryMetas("powered_comparator", metas));
    }

    private static void comparatorMode(String mode, int... metas) {
        state("comparator", "mode", mode,
                entryMetas("unpowered_comparator", metas),
                entryMetas("powered_comparator", metas));
    }

    private static void poweredRailStates(String modern, String legacy) {
        state(modern, "shape", "north_south", entryMetas(legacy, 0, 8));
        state(modern, "shape", "east_west", entryMetas(legacy, 1, 9));
        state(modern, "shape", "ascending_east", entryMetas(legacy, 2, 10));
        state(modern, "shape", "ascending_west", entryMetas(legacy, 3, 11));
        state(modern, "shape", "ascending_north", entryMetas(legacy, 4, 12));
        state(modern, "shape", "ascending_south", entryMetas(legacy, 5, 13));
        state(modern, "powered", "false", entryMetas(legacy, 0, 1, 2, 3, 4, 5));
        state(modern, "powered", "true", entryMetas(legacy, 8, 9, 10, 11, 12, 13));
    }

    private static void buttonStates(String modern, String legacy) {
        state(modern, "face", "floor", entryMetas(legacy, 5, 13));
        state(modern, "face", "wall", entryMetas(legacy, 1, 2, 3, 4, 9, 10, 11, 12));
        state(modern, "face", "ceiling", entryMetas(legacy, 0, 8));
        state(modern, "powered", "false", entryMetas(legacy, 0, 1, 2, 3, 4, 5));
        state(modern, "powered", "true", entryMetas(legacy, 8, 9, 10, 11, 12, 13));
        state(modern, "facing", "east", entryMetas(legacy, 1, 9));
        state(modern, "facing", "west", entryMetas(legacy, 2, 10));
        state(modern, "facing", "south", entryMetas(legacy, 3, 11));
        state(modern, "facing", "north", entryMetas(legacy, 4, 12));
    }

    private static void trapdoorStates(String modern, String legacy) {
        state(modern, "facing", "south", entryMetas(legacy, 0, 4, 8, 12));
        state(modern, "facing", "north", entryMetas(legacy, 1, 5, 9, 13));
        state(modern, "facing", "east", entryMetas(legacy, 2, 6, 10, 14));
        state(modern, "facing", "west", entryMetas(legacy, 3, 7, 11, 15));
        state(modern, "open", "false", entryMetas(legacy, 0, 1, 2, 3, 8, 9, 10, 11));
        state(modern, "open", "true", entryMetas(legacy, 4, 5, 6, 7, 12, 13, 14, 15));
        state(modern, "half", "bottom", entryMetas(legacy, 0, 1, 2, 3, 4, 5, 6, 7));
        state(modern, "half", "top", entryMetas(legacy, 8, 9, 10, 11, 12, 13, 14, 15));
    }

    private static void fenceGateStates() {
        state("oak_fence_gate", "facing", "south", entryMetas("fence_gate", 0, 4));
        state("oak_fence_gate", "facing", "west", entryMetas("fence_gate", 1, 5));
        state("oak_fence_gate", "facing", "north", entryMetas("fence_gate", 2, 6));
        state("oak_fence_gate", "facing", "east", entryMetas("fence_gate", 3, 7));
        state("oak_fence_gate", "open", "false", entryMetas("fence_gate", 0, 1, 2, 3));
        state("oak_fence_gate", "open", "true", entryMetas("fence_gate", 4, 5, 6, 7));
    }

    private static void stairStates(String modern, String legacy) {
        state(modern, "facing", "east", entryMetas(legacy, 0, 4));
        state(modern, "facing", "west", entryMetas(legacy, 1, 5));
        state(modern, "facing", "south", entryMetas(legacy, 2, 6));
        state(modern, "facing", "north", entryMetas(legacy, 3, 7));
        state(modern, "half", "bottom", entryMetas(legacy, 0, 1, 2, 3));
        state(modern, "half", "top", entryMetas(legacy, 4, 5, 6, 7));
    }

    private static void doorStates(String modern, String legacy) {
        state(modern, "half", "lower", entryMetas(legacy, 0, 1, 2, 3, 4, 5, 6, 7));
        state(modern, "half", "upper", entryMetas(legacy, 8, 9, 10, 11));
        state(modern, "facing", "east", entryMetas(legacy, 0, 4));
        state(modern, "facing", "south", entryMetas(legacy, 1, 5));
        state(modern, "facing", "west", entryMetas(legacy, 2, 6));
        state(modern, "facing", "north", entryMetas(legacy, 3, 7));
        state(modern, "open", "false", entryMetas(legacy, 0, 1, 2, 3));
        state(modern, "open", "true", entryMetas(legacy, 4, 5, 6, 7));
        state(modern, "hinge", "left", entryMetas(legacy, 8, 10));
        state(modern, "hinge", "right", entryMetas(legacy, 9, 11));
        state(modern, "powered", "false", entryMetas(legacy, 8, 9));
        state(modern, "powered", "true", entryMetas(legacy, 10, 11));
    }

    private static void sixDirectionalStates(String modern, String legacy,
                                             String flagProp, String flagFalse, String flagTrue) {
        state(modern, "facing", "down", entryMetas(legacy, 0, 8));
        state(modern, "facing", "up", entryMetas(legacy, 1, 9));
        state(modern, "facing", "north", entryMetas(legacy, 2, 10));
        state(modern, "facing", "south", entryMetas(legacy, 3, 11));
        state(modern, "facing", "west", entryMetas(legacy, 4, 12));
        state(modern, "facing", "east", entryMetas(legacy, 5, 13));
        state(modern, flagProp, flagFalse, entryMetas(legacy, 0, 1, 2, 3, 4, 5));
        state(modern, flagProp, flagTrue, entryMetas(legacy, 8, 9, 10, 11, 12, 13));
    }

    private static void logAxisStates(String modern, int typeOffset) {
        state(modern, "axis", "y", entryMetas("log", typeOffset));
        state(modern, "axis", "x", entryMetas("log", typeOffset + 4));
        state(modern, "axis", "z", entryMetas("log", typeOffset + 8));
    }

    private static void woodAxisStates(String modern, int meta) {
        state(modern, "axis", "y", entryMetas("log", meta));
        state(modern, "axis", "x", entryMetas("log", meta));
        state(modern, "axis", "z", entryMetas("log", meta));
    }

    private static void wood2AxisStates(String modern, int meta, int phantomMeta) {
        state(modern, "axis", "y", entryMetas("log2", meta, phantomMeta));
        state(modern, "axis", "x", entryMetas("log2", meta, phantomMeta));
        state(modern, "axis", "z", entryMetas("log2", meta, phantomMeta));
    }

    private static void log2AxisStates(String modern, int typeOffset) {
        int phantom = typeOffset + 2;
        state(modern, "axis", "y", entryMetas("log2", typeOffset, phantom));
        state(modern, "axis", "x", entryMetas("log2", typeOffset + 4, phantom + 4));
        state(modern, "axis", "z", entryMetas("log2", typeOffset + 8, phantom + 8));
    }

    private static void pumpkinFacingStates(String modern, String legacy) {
        state(modern, "facing", "south", entryMetas(legacy, 0));
        state(modern, "facing", "west", entryMetas(legacy, 1));
        state(modern, "facing", "north", entryMetas(legacy, 2));
        state(modern, "facing", "east", entryMetas(legacy, 3));
    }

    private static void anvilFacingStates(String modern, int damageOffset) {
        state(modern, "facing", "south", entryMetas("anvil", damageOffset));
        state(modern, "facing", "west", entryMetas("anvil", damageOffset + 1));
        state(modern, "facing", "north", entryMetas("anvil", damageOffset + 2));
        state(modern, "facing", "east", entryMetas("anvil", damageOffset + 3));
    }

    private static void bedStates(String modern) {
        state(modern, "facing", "south", entryMetas("bed", 0, 4, 8, 12));
        state(modern, "facing", "west", entryMetas("bed", 1, 5, 9, 13));
        state(modern, "facing", "north", entryMetas("bed", 2, 6, 10, 14));
        state(modern, "facing", "east", entryMetas("bed", 3, 7, 11, 15));
        state(modern, "occupied", "false", entryMetas("bed", 0, 1, 2, 3, 8, 9, 10, 11));
        state(modern, "occupied", "true", entryMetas("bed", 4, 5, 6, 7, 12, 13, 14, 15));
        state(modern, "part", "foot", entryMetas("bed", 0, 1, 2, 3, 4, 5, 6, 7));
        state(modern, "part", "head", entryMetas("bed", 8, 9, 10, 11, 12, 13, 14, 15));
    }
}
