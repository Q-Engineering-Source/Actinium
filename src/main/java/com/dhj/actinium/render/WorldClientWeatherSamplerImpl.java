package com.dhj.actinium.render;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.lang.ref.WeakReference;

/**
 * {@link WeatherSampler} over the client world of the running game.
 *
 * <p>The three queries mirror the vanilla weather loop in {@code EntityRenderer#renderRainSnow}: the
 * biome and the precipitation height are read at the column base, and the light is read at the block
 * that is drawn. Every query reuses one mutable position, so a full column refresh allocates nothing;
 * the sampler must therefore stay on the render thread.</p>
 *
 * <p>The world is held weakly. A renderer keeps one sampler per world for the whole lifetime of that
 * world, so a strong reference here would keep a world the client already dropped alive until the
 * next one loads. Querying after the world was released is a call ordering error and fails loudly
 * instead of reading a detached world.</p>
 */
public final class WorldClientWeatherSamplerImpl implements WeatherSampler {

    private static final int COLUMN_SAMPLE_Y = 0;

    private final WeakReference<WorldClient> world;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    /**
     * Creates a sampler for one world.
     *
     * @param world the client world to read; the caller keeps one sampler per world so the
     *              {@link WeatherColumns} cache is not discarded on every update
     */
    public WorldClientWeatherSamplerImpl(WorldClient world) {
        this.world = new WeakReference<>(world);
    }

    @Override
    public Biome biome(int x, int z) {
        return world().getBiome(pos.setPos(x, COLUMN_SAMPLE_Y, z));
    }

    @Override
    public int precipitationHeight(int x, int z) {
        return world().getPrecipitationHeight(pos.setPos(x, COLUMN_SAMPLE_Y, z)).getY();
    }

    @Override
    public int combinedLight(int x, int y, int z) {
        return world().getCombinedLight(pos.setPos(x, y, z), 0);
    }

    private WorldClient world() {
        final WorldClient current = this.world.get();
        if (current == null) {
            throw new IllegalStateException("Weather sampled after the client world was released");
        }
        return current;
    }
}
