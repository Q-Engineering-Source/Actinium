package net.coderbot.iris.shaderpack;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.coderbot.iris.shaderpack.materialmap.BlockRenderType;
import net.coderbot.iris.shaderpack.materialmap.EntityFlatteningMap;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.shaderpack.materialmap.TagEntry;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import net.coderbot.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Parses OptiFine-style item/entity/block ID maps with modern shader-pack fallback.
 */
public class IdMap {
    private final Object2IntMap<NamespacedId> itemIdMap;
    private final Int2ObjectMap<List<BlockEntry>> itemNbtEntries;

    private final Object2IntMap<NamespacedId> entityIdMap;
    private final Int2ObjectMap<List<BlockEntry>> entityNbtEntries;

    private Int2ObjectMap<List<BlockEntry>> blockPropertiesMap;
    private Int2ObjectMap<List<TagEntry>> blockTagMap;
    private Map<NamespacedId, BlockRenderType> blockRenderTypeMap;

    private final boolean hasLegacySection;

    private static final Pattern LEGACY_DIRECTIVE_PATTERN = Pattern.compile(
            "(?m)^\\s*#\\s*(?:if|elif|ifdef|ifndef)\\b[^\\n]*\\bMC_VERSION\\b[^\\n]*\\b11202\\b");

    record ParsedIdMap(Object2IntMap<NamespacedId> simpleMap, Int2ObjectMap<List<BlockEntry>> nbtEntries) {}

    IdMap(Path shaderPath, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
        this(shaderPath, detectLegacySection(shaderPath), shaderPackOptions, environmentDefines);
    }

    /**
     * Detects whether a pack's block.properties has a dedicated legacy section.
     */
    static boolean detectLegacySection(Path shaderPath) {
        return hasLegacySection(readProperties(shaderPath, "block.properties"));
    }

    /**
     * Builds the map with a legacy-section verdict the caller already computed.
     *
     * <p>Detecting the section reads and scans block.properties, which {@link ShaderPack} does once on the client
     * thread before handing the map over to a worker; passing the result in keeps the file from being read and
     * scanned twice.
     */
    IdMap(Path shaderPath, boolean hasLegacySection, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
        this.hasLegacySection = hasLegacySection;

        Iterable<StringPair> resolvedDefines = environmentDefines;
        if (!this.hasLegacySection) {
            resolvedDefines = McVersionDefines.withMcVersion(environmentDefines, 260101);
        }

        blockTagMap = new Int2ObjectLinkedOpenHashMap<>();
        loadProperties(shaderPath, "block.properties", shaderPackOptions, resolvedDefines).ifPresent(blockProperties -> {
            blockPropertiesMap = parseBlockMap(blockProperties, "block.", "block.properties", blockTagMap);
            blockRenderTypeMap = parseRenderTypeMap(blockProperties, "layer.", "block.properties");
        });

        ParsedIdMap parsedItems = loadProperties(shaderPath, "item.properties", shaderPackOptions, resolvedDefines)
                .map(properties -> parseIdMap(properties, "item.", "item.properties"))
                .orElse(new ParsedIdMap(Object2IntMaps.emptyMap(), new Int2ObjectOpenHashMap<>()));
        itemIdMap = parsedItems.simpleMap();
        itemNbtEntries = parsedItems.nbtEntries();

        ParsedIdMap parsedEntities = loadProperties(shaderPath, "entity.properties", shaderPackOptions, resolvedDefines)
                .map(properties -> parseIdMap(properties, "entity.", "entity.properties"))
                .orElse(new ParsedIdMap(Object2IntMaps.emptyMap(), new Int2ObjectOpenHashMap<>()));
        entityIdMap = augmentEntityIdMap(parsedEntities.simpleMap(), parsedEntities.nbtEntries());
        entityNbtEntries = parsedEntities.nbtEntries();

        if (blockPropertiesMap == null) {
            blockPropertiesMap = new Int2ObjectLinkedOpenHashMap<>();
            LegacyIdMap.addLegacyValues(blockPropertiesMap);
        }

        if (blockRenderTypeMap == null) {
            blockRenderTypeMap = Collections.emptyMap();
        }
    }

    static boolean hasLegacySection(String rawBlockProperties) {
        return rawBlockProperties != null && LEGACY_DIRECTIVE_PATTERN.matcher(rawBlockProperties).find();
    }

    public boolean hasLegacySection() {
        return hasLegacySection;
    }

    private static Optional<Properties> loadProperties(
            Path shaderPath,
            String name,
            ShaderPackOptions shaderPackOptions,
            Iterable<StringPair> environmentDefines
    ) {
        String fileContents = readProperties(shaderPath, name);
        if (fileContents == null) {
            return Optional.empty();
        }

        // A line continuation that runs straight into a conditional block leaves the selected branch on a
        // line of its own: Derivative writes "block.10001 = fern \" followed by #if MC_VERSION < 12003, and
        // after preprocessing the backslash continues onto a blank line, so Properties drops the branch
        // (short_grass for modern versions). Collapsing the blank run keeps the branch in the same value.
        // When the run reaches the next "key=" line, the continuation has to end there instead, otherwise
        // the following rule is appended to the current value and its own key disappears.
        String processed = PropertiesPreprocessor.preprocessSource(fileContents, shaderPackOptions, environmentDefines)
                .replaceAll("\\\\\n(?:[^\\S\n]*\n)+(?=[^\\S\n]*[\\w.-]+[^\\S\n]*=)", "\n")
                .replaceAll("\\\\\n(?:[^\\S\n]*\n)+", " ");
        StringReader propertiesReader = new StringReader(processed);

        Properties properties = new OrderBackedProperties();
        try {
            properties.load(propertiesReader);
        } catch (IOException e) {
            Iris.logger.error("Error loading " + name + " at " + shaderPath, e);
            return Optional.empty();
        }

        return Optional.of(properties);
    }

    private static String readProperties(Path shaderPath, String name) {
        try {
            return Files.readString(shaderPath.resolve(name), StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            Iris.logger.debug("An " + name + " file was not found in the current shaderpack");
            return null;
        } catch (IOException e) {
            Iris.logger.error("An IOException occurred reading " + name + " from the current shaderpack", e);
            return null;
        }
    }

    private static Object2IntMap<NamespacedId> augmentEntityIdMap(
            Object2IntMap<NamespacedId> idMap,
            Int2ObjectMap<List<BlockEntry>> nbtEntries
    ) {
        Object2IntMap<NamespacedId> augmented = new Object2IntOpenHashMap<>(idMap);
        augmented.defaultReturnValue(-1);

        for (Object2IntMap.Entry<NamespacedId> entry : idMap.object2IntEntrySet()) {
            NamespacedId id = entry.getKey();
            if (!"minecraft".equals(id.getNamespace())) {
                continue;
            }

            int intId = entry.getIntValue();
            BlockEntry nbtMapping = EntityFlatteningMap.toLegacyWithNbt(id.getName());
            if (nbtMapping != null) {
                nbtEntries.computeIfAbsent(intId, ignored -> new ArrayList<>()).add(nbtMapping);
                continue;
            }

            String legacyName = EntityFlatteningMap.toLegacy(id.getName());
            if (legacyName != null) {
                augmented.putIfAbsent(new NamespacedId(legacyName), intId);
            }
        }

        return Object2IntMaps.unmodifiable(augmented);
    }

    static List<String> parseIdentifierList(String value, String fileName, String key) {
        if (value.indexOf('"') == -1) {
            String[] parts = value.split("\\s+");
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (!part.isEmpty()) {
                    result.add(part);
                }
            }
            return result;
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (inQuotes) {
            Iris.logger.warn(fileName + " [" + key + "]: Unclosed quote");
        }

        if (escaped) {
            Iris.logger.warn(fileName + " [" + key + "]: Trailing backslash");
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    private static ParsedIdMap parseIdMap(Properties properties, String keyPrefix, String fileName) {
        Object2IntMap<NamespacedId> idMap = new Object2IntOpenHashMap<>();
        idMap.defaultReturnValue(-1);
        Int2ObjectMap<List<BlockEntry>> nbtEntries = new Int2ObjectOpenHashMap<>();

        properties.forEach((keyObject, valueObject) -> {
            String key = (String) keyObject;
            String value = (String) valueObject;

            if (!key.startsWith(keyPrefix)) {
                return;
            }

            int intId;
            try {
                intId = Integer.parseInt(key.substring(keyPrefix.length()));
            } catch (NumberFormatException e) {
                Iris.logger.warn("Failed to parse line in " + fileName + ": invalid key " + key);
                return;
            }

            for (String part : parseIdentifierList(value, fileName, key)) {
                if (part.contains("[")) {
                    try {
                        BlockEntry entry = BlockEntry.parse(part);
                        if (entry.hasNbtProperties()) {
                            nbtEntries.computeIfAbsent(intId, ignored -> new ArrayList<>()).add(entry);
                        } else {
                            idMap.put(entry.getId(), intId);
                        }
                    } catch (Exception e) {
                        Iris.logger.warn("Failed to parse NBT entry in " + fileName + " for key " + key + ": " + part, e);
                    }
                    continue;
                }

                if (part.contains("=")) {
                    Iris.logger.warn("Failed to parse an ResourceLocation in " + fileName + " for the key " + key
                            + ": state properties are currently not supported: " + part);
                    continue;
                }

                idMap.put(new NamespacedId(part), intId);
            }
        });

        return new ParsedIdMap(Object2IntMaps.unmodifiable(idMap), nbtEntries);
    }

    private static Int2ObjectMap<List<BlockEntry>> parseBlockMap(Properties properties, String keyPrefix, String fileName,
                                                                 Int2ObjectMap<List<TagEntry>> tagsById) {
        Int2ObjectMap<List<BlockEntry>> entriesById = new Int2ObjectLinkedOpenHashMap<>();

        properties.forEach((keyObject, valueObject) -> {
            String key = (String) keyObject;
            StringBuilder value = new StringBuilder((String) valueObject);

            if (!key.startsWith(keyPrefix)) {
                return;
            }

            int intId;
            try {
                intId = Integer.parseInt(key.substring(keyPrefix.length()));
            } catch (NumberFormatException e) {
                Iris.logger.warn("Failed to parse line in " + fileName + ": invalid key " + key);
                return;
            }

            List<BlockEntry> entries = new ArrayList<>();
            List<TagEntry> tags = new ArrayList<>();

            if (value.toString().contains("minecraft:leaves")) {
                List<ItemStack> leaves = OreDictionary.getOres("treeLeaves");
                for (ItemStack leaf : leaves) {
                    if (leaf.getItem() instanceof ItemBlock) {
                        ResourceLocation leafName = Item.REGISTRY.getNameForObject(leaf.getItem());
                        Iris.logger.warn("Found leaf " + leafName);
                        value.append(" ").append(leafName);
                    }
                }
            }

            for (String part : parseIdentifierList(value.toString(), fileName, key)) {
                if (part.isEmpty()) {
                    continue;
                }

                try {
                    if (part.startsWith("%")) {
                        tags.add(TagEntry.parse(part));
                    } else {
                        entries.add(BlockEntry.parse(part));
                    }
                } catch (Exception e) {
                    Iris.logger.warn("Unexpected error while parsing an entry from " + fileName + " for the key " + key + ":", e);
                }
            }

            entriesById.put(intId, Collections.unmodifiableList(entries));
            if (!tags.isEmpty()) {
                tagsById.put(intId, Collections.unmodifiableList(tags));
            }
        });

        return Int2ObjectMaps.unmodifiable(entriesById);
    }

    private static Map<NamespacedId, BlockRenderType> parseRenderTypeMap(Properties properties, String keyPrefix, String fileName) {
        Map<NamespacedId, BlockRenderType> overrides = new HashMap<>();

        properties.forEach((keyObject, valueObject) -> {
            String key = (String) keyObject;
            String value = (String) valueObject;

            if (!key.startsWith(keyPrefix)) {
                return;
            }

            String keyWithoutPrefix = key.substring(keyPrefix.length());
            BlockRenderType renderType = BlockRenderType.fromString(keyWithoutPrefix).orElse(null);
            if (renderType == null) {
                Iris.logger.warn("Failed to parse line in " + fileName + ": invalid block render type: " + key);
                return;
            }

            for (String part : parseIdentifierList(value, fileName, key)) {
                overrides.put(new NamespacedId(part), renderType);
            }
        });

        return overrides;
    }

    public Int2ObjectMap<List<BlockEntry>> getBlockProperties() {
        return blockPropertiesMap;
    }

    public Int2ObjectMap<List<TagEntry>> getTagEntries() {
        return blockTagMap;
    }

    public Object2IntFunction<NamespacedId> getItemIdMap() {
        return itemIdMap;
    }

    public Int2ObjectMap<List<BlockEntry>> getItemNbtEntries() {
        return itemNbtEntries;
    }

    public Object2IntFunction<NamespacedId> getEntityIdMap() {
        return entityIdMap;
    }

    public Int2ObjectMap<List<BlockEntry>> getEntityNbtEntries() {
        return entityNbtEntries;
    }

    public Map<NamespacedId, BlockRenderType> getBlockRenderTypeMap() {
        return blockRenderTypeMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        IdMap idMap = (IdMap) o;
        return Objects.equals(itemIdMap, idMap.itemIdMap)
                && Objects.equals(entityIdMap, idMap.entityIdMap)
                && Objects.equals(blockPropertiesMap, idMap.blockPropertiesMap)
                && Objects.equals(blockTagMap, idMap.blockTagMap)
                && Objects.equals(blockRenderTypeMap, idMap.blockRenderTypeMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemIdMap, entityIdMap, blockPropertiesMap, blockTagMap, blockRenderTypeMap);
    }
}
