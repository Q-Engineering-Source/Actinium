package net.coderbot.iris.block_rendering;

import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.materialmap.PropertiesTokenizer;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps objects with specific NBT properties to shader IDs.
 */
public class NbtConditionalIdMap<K> {
	private record PropertyMatcher(String key, String[] path, String expectedValue, long expectedLong,
	                               boolean hasExpectedLong, double expectedDouble, boolean hasExpectedDouble) {
		boolean hasPath() {
			return path != null;
		}

		boolean isExistenceOnly() {
			return expectedValue == null;
		}
	}

	private record NbtCondition(PropertyMatcher[] matchers, int shaderId) {
	}

	private final Map<K, List<NbtCondition>> conditionsByKey = new HashMap<>();

	public void addCondition(K key, Map<String, PropertiesTokenizer.NbtValue> nbtProperties, int shaderId) {
		PropertyMatcher[] matchers = new PropertyMatcher[nbtProperties.size()];
		int i = 0;

		for (Map.Entry<String, PropertiesTokenizer.NbtValue> entry : nbtProperties.entrySet()) {
			String nbtKey = entry.getKey();
			PropertiesTokenizer.NbtValue nbtValue = entry.getValue();

			String expectedValue = null;
			long expectedLong = 0L;
			boolean hasExpectedLong = false;
			double expectedDouble = 0.0D;
			boolean hasExpectedDouble = false;

			if (nbtValue != null) {
				expectedValue = nbtValue.value();

				if (!nbtValue.literal()) {
					Long registryId = resolveRegistryId(expectedValue);
					if (registryId != null) {
						expectedLong = registryId;
						hasExpectedLong = true;
					}

					try {
						expectedLong = Long.parseLong(expectedValue);
						hasExpectedLong = true;
					} catch (NumberFormatException ignored) {
						if ("true".equalsIgnoreCase(expectedValue)) {
							expectedLong = 1L;
							hasExpectedLong = true;
						} else if ("false".equalsIgnoreCase(expectedValue)) {
							expectedLong = 0L;
							hasExpectedLong = true;
						}
					}

					try {
						expectedDouble = Double.parseDouble(expectedValue);
						hasExpectedDouble = true;
					} catch (NumberFormatException ignored) {
					}
				}
			}

			String[] path = nbtKey.contains(".") ? nbtKey.split("\\.") : null;
			matchers[i++] = new PropertyMatcher(nbtKey, path, expectedValue, expectedLong, hasExpectedLong,
				expectedDouble, hasExpectedDouble);
		}

		conditionsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new NbtCondition(matchers, shaderId));
	}

	public boolean hasConditions(K key) {
		return conditionsByKey.containsKey(key);
	}

	public boolean isEmpty() {
		return conditionsByKey.isEmpty();
	}

	public int resolve(K key, NBTTagCompound nbt) {
		List<NbtCondition> conditions = conditionsByKey.get(key);
		if (conditions == null) {
			return -1;
		}

		for (NbtCondition condition : conditions) {
			if (matchesNbt(nbt, condition.matchers())) {
				return condition.shaderId();
			}
		}

		return -1;
	}

	private static boolean matchesNbt(NBTTagCompound nbt, PropertyMatcher[] matchers) {
		for (PropertyMatcher matcher : matchers) {
			if (matcher.hasPath()) {
				if (!matchesPath(nbt, matcher.path(), 0, matcher)) {
					return false;
				}
				continue;
			}

			if (!nbt.hasKey(matcher.key())) {
				return false;
			}

			if (!matcher.isExistenceOnly() && !matchesNbtValue(nbt.getTag(matcher.key()), matcher)) {
				return false;
			}
		}

		return true;
	}

	private static boolean matchesPath(NBTBase tag, String[] path, int depth, PropertyMatcher matcher) {
		if (depth == path.length) {
			return matcher.isExistenceOnly() || matchesNbtValue(tag, matcher);
		}

		String segment = path[depth];
		if (tag instanceof NBTTagCompound compound) {
			if (!compound.hasKey(segment)) {
				return false;
			}

			return matchesPath(compound.getTag(segment), path, depth + 1, matcher);
		}

		if (tag instanceof NBTTagList list) {
			for (int i = 0; i < list.tagCount(); i++) {
				NBTBase child = list.get(i);
				if (matchesPath(child, path, depth, matcher)) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean matchesNbtValue(NBTBase tag, PropertyMatcher matcher) {
		return switch (tag.getId()) {
			case 1 -> matcher.hasExpectedLong() && ((net.minecraft.nbt.NBTTagByte) tag).getLong() == matcher.expectedLong();
			case 2 -> matcher.hasExpectedLong() && ((net.minecraft.nbt.NBTTagShort) tag).getLong() == matcher.expectedLong();
			case 3 -> matcher.hasExpectedLong() && ((net.minecraft.nbt.NBTTagInt) tag).getLong() == matcher.expectedLong();
			case 4 -> matcher.hasExpectedLong() && ((net.minecraft.nbt.NBTTagLong) tag).getLong() == matcher.expectedLong();
			case 5 -> matcher.hasExpectedDouble() && ((net.minecraft.nbt.NBTTagFloat) tag).getDouble() == matcher.expectedDouble();
			case 6 -> matcher.hasExpectedDouble() && ((net.minecraft.nbt.NBTTagDouble) tag).getDouble() == matcher.expectedDouble();
			case 8 -> matchesStringValue((NBTTagString) tag, matcher);
			default -> matcher.expectedValue() != null
				&& matcher.expectedValue().equals(PropertiesTokenizer.stripQuotes(tag.toString()));
		};
	}

	private static boolean matchesStringValue(NBTTagString tag, PropertyMatcher matcher) {
		String actual = tag.getString();
		if (matcher.expectedValue() != null && matcher.expectedValue().equals(actual)) {
			return true;
		}

		if (matcher.hasExpectedLong()) {
			try {
				return Long.parseLong(actual) == matcher.expectedLong();
			} catch (NumberFormatException ignored) {
			}
		}

		if (matcher.hasExpectedDouble()) {
			try {
				return Double.parseDouble(actual) == matcher.expectedDouble();
			} catch (NumberFormatException ignored) {
			}
		}

		return false;
	}

	private static Long resolveRegistryId(String value) {
		if (!value.contains(":")) {
			return null;
		}

		ResourceLocation location;
		try {
			location = new ResourceLocation(value);
		} catch (IllegalArgumentException e) {
			Iris.logger.warn("Could not parse '{}' as an NBT registry name", value);
			return null;
		}

		Item item = Item.REGISTRY.getObject(location);
		if (item != null) {
			return (long) Item.getIdFromItem(item);
		}

		Block block = Block.REGISTRY.getObject(location);
		if (block != null) {
			return (long) Block.getIdFromBlock(block);
		}

		return null;
	}
}
