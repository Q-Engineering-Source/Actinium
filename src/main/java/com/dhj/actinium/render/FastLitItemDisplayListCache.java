package com.dhj.actinium.render;

import com.dhj.actinium.mixin.features.iris.RenderItemAccessor;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.model.BakedItemModel;
import org.lwjgl.opengl.GL11;
import com.dhj.actinium.render.terrain.sprite.SpriteUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;

public final class FastLitItemDisplayListCache {
    private static final int MAX_ENTRIES = Integer.getInteger("actinium.fastLitItemDisplayListCacheSize", 256);
    private static final String BAKED_GUI_ITEM_MODEL = "net.minecraftforge.client.model.BakedItemModel$BakedGuiItemModel";
    private static final String VANILLA_MODEL_WRAPPER_BAKED = "net.minecraftforge.client.model.ModelLoader$VanillaModelWrapper$1";
    private static final int CACHEABLE_QUADS = 0;
    private static final int NON_ITEM_FORMAT = 1;
    private static final int MAX_SAMPLE_LINES = 8;

    private static final Map<CacheKey, CachedDisplayList> CACHE = new LinkedHashMap<CacheKey, CachedDisplayList>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedDisplayList> eldest) {
            if (this.size() <= MAX_ENTRIES) {
                return false;
            }

            eldest.getValue().delete();
            return true;
        }
    };
    private static int hits;
    private static int misses;
    private static int compiles;
    private static int fallbacks;
    private static int disabledFallbacks;
    private static int recordingFallbacks;
    private static int unstableModelFallbacks;
    private static int nonItemFormatFallbacks;
    private static int tintedQuadFallbacks;
    private static int compileFailedFallbacks;
    private static final Map<String, Integer> unstableModelSamples = new HashMap<>();
    private static final Map<String, Integer> nonItemFormatSamples = new HashMap<>();

    private FastLitItemDisplayListCache() {
    }

    public static synchronized CachedDisplayList getOrCompile(RenderItem renderItem, IBakedModel model, List<BakedQuad> quads, int color, ItemStack stack) {
        if (MAX_ENTRIES <= 0) {
            recordFallback(FallbackReason.DISABLED, model, null);
            return null;
        }

        if (GLStateManager.isRecordingDisplayList()) {
            recordFallback(FallbackReason.RECORDING, model, null);
            return null;
        }

        if (!isStableModel(model)) {
            recordFallback(FallbackReason.UNSTABLE_MODEL, model, null);
            return null;
        }

        int cacheability = getCacheability(quads);
        if (cacheability != CACHEABLE_QUADS) {
            recordFallback(FallbackReason.NON_ITEM_FORMAT, model, quads);
            return null;
        }

        int[] colors = getQuadColors(renderItem, quads, color, stack);
        CacheKey lookupKey = new CacheKey(model, colors);
        CachedDisplayList cached = CACHE.get(lookupKey);
        if (cached != null) {
            hits++;
            return cached;
        }

        misses++;
        cached = compile(renderItem, quads, color, stack);
        if (cached == null) {
            recordFallback(FallbackReason.COMPILE_FAILED, model, null);
            return null;
        }

        CACHE.put(lookupKey, cached);
        compiles++;
        return cached;
    }

    public static synchronized void clear() {
        for (CachedDisplayList cached : CACHE.values()) {
            cached.delete();
        }
        CACHE.clear();
    }

    private static boolean isStableModel(IBakedModel model) {
        Class<?> modelClass = model.getClass();
        return modelClass == SimpleBakedModel.class
            || modelClass == BakedItemModel.class
            || BAKED_GUI_ITEM_MODEL.equals(model.getClass().getName())
            || VANILLA_MODEL_WRAPPER_BAKED.equals(model.getClass().getName());
    }

    private static int getCacheability(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (!isItemFormat(quad)) {
                return NON_ITEM_FORMAT;
            }

        }

        return CACHEABLE_QUADS;
    }

    private static int[] getQuadColors(RenderItem renderItem, List<BakedQuad> quads, int color, ItemStack stack) {
        int[] colors = new int[quads.size()];
        boolean resolveTint = color == -1 && stack != null && !stack.isEmpty();
        ItemColors itemColors = null;

        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            int quadColor = color;
            if (resolveTint && quad.hasTintIndex()) {
                if (itemColors == null) {
                    itemColors = ((RenderItemAccessor) renderItem).actinium$getItemColors();
                }
                quadColor = itemColors.colorMultiplier(stack, quad.getTintIndex());
                if (EntityRenderer.anaglyphEnable) {
                    quadColor = TextureUtil.anaglyphColor(quadColor);
                }
                quadColor |= -16777216;
            }
            colors[i] = quadColor;
        }

        return colors;
    }

    private static boolean isItemFormat(BakedQuad quad) {
        return DefaultVertexFormats.ITEM.equals(quad.getFormat());
    }

    private static CachedDisplayList compile(RenderItem renderItem, List<BakedQuad> quads, int color, ItemStack stack) {
        int list = GLSMDraws.glGenLists(1);
        if (list == 0) {
            return null;
        }

        List<TextureAtlasSprite> sprites = collectSprites(quads);
        GLSMDraws.glNewList(list, GL11.GL_COMPILE);
        try {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
            renderItem.renderQuads(buffer, quads, color, stack);
            tessellator.draw();
        } finally {
            GLSMDraws.glEndList();
        }

        return new CachedDisplayList(list, sprites);
    }

    private static List<TextureAtlasSprite> collectSprites(List<BakedQuad> quads) {
        Set<TextureAtlasSprite> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<TextureAtlasSprite> sprites = new ArrayList<>();

        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite != null && seen.add(sprite)) {
                sprites.add(sprite);
            }
        }

        return sprites;
    }

    public static synchronized String dumpStatsAndReset() {
        String stats = "fastLitItemDisplayLists[entries=" + CACHE.size()
            + ",maxEntries=" + MAX_ENTRIES
            + ",hits=" + hits
            + ",misses=" + misses
            + ",compiles=" + compiles
            + ",fallbacks=" + fallbacks
            + ",disabled=" + disabledFallbacks
            + ",recording=" + recordingFallbacks
            + ",unstableModel=" + unstableModelFallbacks
            + ",nonItemFormat=" + nonItemFormatFallbacks
            + ",tintedQuad=" + tintedQuadFallbacks
            + ",compileFailed=" + compileFailedFallbacks
            + "]";
        stats += appendTopSamples(" unstableModels", unstableModelSamples);
        stats += appendTopSamples(" nonItemFormats", nonItemFormatSamples);
        hits = 0;
        misses = 0;
        compiles = 0;
        fallbacks = 0;
        disabledFallbacks = 0;
        recordingFallbacks = 0;
        unstableModelFallbacks = 0;
        nonItemFormatFallbacks = 0;
        tintedQuadFallbacks = 0;
        compileFailedFallbacks = 0;
        unstableModelSamples.clear();
        nonItemFormatSamples.clear();
        return stats;
    }

    private static void recordFallback(FallbackReason reason, IBakedModel model, List<BakedQuad> quads) {
        fallbacks++;
        switch (reason) {
            case DISABLED:
                disabledFallbacks++;
                break;
            case RECORDING:
                recordingFallbacks++;
                break;
            case UNSTABLE_MODEL:
                unstableModelFallbacks++;
                incrementSample(unstableModelSamples, model.getClass().getName());
                break;
            case NON_ITEM_FORMAT:
                nonItemFormatFallbacks++;
                sampleNonItemFormat(quads);
                break;
            case TINTED_QUAD:
                tintedQuadFallbacks++;
                break;
            case COMPILE_FAILED:
                compileFailedFallbacks++;
                break;
        }
    }

    private static void sampleNonItemFormat(List<BakedQuad> quads) {
        if (quads == null) {
            return;
        }

        for (BakedQuad quad : quads) {
            if (!isItemFormat(quad)) {
                incrementSample(nonItemFormatSamples, String.valueOf(quad.getFormat()));
                return;
            }
        }
    }

    private static void incrementSample(Map<String, Integer> samples, String key) {
        samples.put(key, samples.getOrDefault(key, 0) + 1);
    }

    private static String appendTopSamples(String label, Map<String, Integer> samples) {
        if (samples.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(label).append('[');
        for (int i = 0; i < MAX_SAMPLE_LINES; i++) {
            String bestKey = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : samples.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestKey = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            if (bestKey == null) {
                break;
            }
            if (i > 0) {
                sb.append(';');
            }
            sb.append(bestKey).append('=').append(bestCount);
            samples.remove(bestKey);
        }
        sb.append(']');
        return sb.toString();
    }

    private enum FallbackReason {
        DISABLED,
        RECORDING,
        UNSTABLE_MODEL,
        NON_ITEM_FORMAT,
        TINTED_QUAD,
        COMPILE_FAILED
    }

    private static final class CacheKey {
        private final IBakedModel model;
        private final int[] colors;
        private final int hash;

        private CacheKey(IBakedModel model, int[] colors) {
            this.model = model;
            this.colors = colors;
            this.hash = System.identityHashCode(model) * 31 + Arrays.hashCode(colors);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheKey)) {
                return false;
            }

            CacheKey other = (CacheKey) obj;
            return this.model == other.model && Arrays.equals(this.colors, other.colors);
        }
    }

    public static final class CachedDisplayList {
        private final int list;
        private final List<TextureAtlasSprite> sprites;

        private CachedDisplayList(int list, List<TextureAtlasSprite> sprites) {
            this.list = list;
            this.sprites = sprites;
        }

        public void render() {
            for (TextureAtlasSprite sprite : this.sprites) {
                SpriteUtil.markSpriteActive(sprite);
            }
            GLSMDraws.glCallList(this.list);
        }

        private void delete() {
            GLSMDraws.glDeleteLists(this.list, 1);
        }
    }
}

