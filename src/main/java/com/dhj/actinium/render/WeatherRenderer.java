package com.dhj.actinium.render;

import com.dhj.actinium.mixin.vintage.core.terrain.AccessorEntityRenderer;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherParams;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherQuadMesh;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Replaces the vanilla rain and snow passes with a version that reads the shared column cache.
 *
 * <p>{@link #render(float)} reproduces {@code EntityRenderer.renderRainSnow}: the same window around
 * the camera, the same clamping of a column between the camera and the precipitation height, the
 * same texture scroll, transparency falloff, lightmap and rain/snow grouping, and the same GL state
 * around the pass. The only difference is where the per column data comes from: the cache is sampled
 * on a rolling schedule instead of querying the world for every column of every frame.</p>
 *
 * <p>{@link #spawnRainParticles()} reproduces {@code EntityRenderer.addRainParticles} including the
 * magma and lava smoke branch, the seeded random stream, the range and temperature gates and the
 * landing sound.</p>
 *
 * <p>The column cache identifies its world by the sampler instance, so this class keeps one sampler
 * per world and hands the same instance to every update. The sampler only holds the world weakly, so
 * a client that drops a world is not kept alive by the weather pass.</p>
 */
public final class WeatherRenderer {

    private static final ResourceLocation RAIN_TEXTURE = new ResourceLocation("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEXTURE = new ResourceLocation("textures/environment/snow.png");

    /** Downfall radius of the vanilla weather window with fancy graphics. */
    private static final int FANCY_WEATHER_RADIUS = 10;

    /** Downfall radius of the vanilla weather window with fast graphics. */
    private static final int FAST_WEATHER_RADIUS = 5;

    /** Horizontal range around the player in which rain particles may land. */
    private static final int PARTICLE_RADIUS = 10;

    /** Vanilla temperature threshold above which precipitation falls as rain. */
    private static final float RAIN_TEMPERATURE = 0.15F;

    /** Packed light of a fully lit block, used to lerp the snow lightmap towards daylight. */
    private static final int FULL_DAYLIGHT = 15728880;

    /** Seed factor vanilla applies to the renderer update count before spawning particles. */
    private static final long PARTICLE_SEED = 312987231L;

    /** Frames between two forced instance buffer rebuilds. */
    private static final int REBUILD_TICKS = 20;

    /** Streaming weather resources are only touched on the render thread. */
    private static final WeatherColumns columns = new WeatherColumns();
    private static final Random particleRandom = new Random();

    private static WeatherSampler sampler;
    private static WeakReference<WorldClient> samplerWorld = new WeakReference<>(null);

    private static int rainSoundCounter;

    private static int instanceBuffer;
    private static ByteBuffer instanceData;
    private static int instanceCapacity;
    private static int instanceCount;
    private static int rainInstances;
    private static BuildKey builtKey;

    private WeatherRenderer() {
    }

    /**
     * Draws the rain and snow of the current world.
     *
     * <p>Called instead of {@code EntityRenderer.renderRainSnow}. When the instanced path is available
     * the columns are uploaded as one instance buffer and drawn with two instanced draw calls, one per
     * precipitation type; otherwise the pass emits per column quads through the vanilla
     * {@code BufferBuilder} path. Both paths share the column cache, the texture scroll, the lightmap
     * and the transparency, so the configuration only changes the cost of the pass.</p>
     *
     * @param partialTicks frame interpolation of the render pass
     * @return whether the pass was handled; {@code false} leaves the vanilla pass in place
     */
    public static boolean render(float partialTicks) {
        if (deferredToVanilla()) return false;

        final Minecraft mc = Minecraft.getMinecraft();
        final WorldClient world = mc.world;
        final float rainStrength = world.getRainStrength(partialTicks);
        if (rainStrength <= 0.0F) return true;

        final int radius = weatherRadius(mc);
        final Entity view = mc.getRenderViewEntity();
        final int viewBlockX = MathHelper.floor(view.posX);
        final int viewBlockY = MathHelper.floor(view.posY);
        final int viewBlockZ = MathHelper.floor(view.posZ);

        final double cameraX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        final double cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        final double cameraZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        final int cameraBlockY = MathHelper.floor(cameraY);

        final int updateCount = updateCount(mc);
        final WeatherSampler sampler = sampler(world);
        columns.update(sampler, viewBlockX, viewBlockZ, radius, updateCount);

        final WeatherInstanceWriter writer = new WeatherInstanceWriter(columns,
                world.getBiomeProvider()::getTemperatureAtHeight);
        final BuildKey key = new BuildKey(columns.revision(), updateCount, viewBlockX, viewBlockY,
                viewBlockZ, cameraBlockY, radius);

        final DeferredWorldRenderingPipeline deferred = deferredPipeline();
        final boolean variantReady = deferred != null && instancedWeatherEnabled()
                && BackendManager.RENDER_BACKEND.supportsInstancedDrawing()
                && deferred.hasInstancedVariant(Instancing.WEATHER);
        final boolean backendInstanced = BackendManager.RENDER_BACKEND.supportsInstancedDrawing();
        if (useInstancedPath(instancedWeatherEnabled(), backendInstanced, irisActive(), variantReady)) {
            renderInstanced(mc, writer, sampler, view, rainStrength, partialTicks, updateCount, radius,
                    viewBlockX, viewBlockY, viewBlockZ, cameraX, cameraY, cameraZ, cameraBlockY, key,
                    variantReady ? deferred : null);
        } else {
            renderFallback(mc, writer, sampler, view, rainStrength, partialTicks, updateCount, radius,
                    viewBlockX, viewBlockY, viewBlockZ, cameraX, cameraY, cameraZ, cameraBlockY);
        }
        return true;
    }

    private static void renderFallback(Minecraft mc, WeatherInstanceWriter writer, WeatherSampler sampler,
                                       Entity view, float rainStrength, float partialTicks, int updateCount,
                                       int radius, int viewBlockX, int viewBlockY, int viewBlockZ,
                                       double cameraX, double cameraY, double cameraZ, int cameraBlockY) {
        final QuadEmitter emitter = new QuadEmitter(mc, sampler, view, rainStrength, partialTicks, updateCount,
                radius, viewBlockX, viewBlockZ);

        mc.entityRenderer.enableLightmap();
        GlStateManager.disableCull();
        GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.setTranslation(-cameraX, -cameraY, -cameraZ);
        writer.forEachColumn(viewBlockX, viewBlockY, viewBlockZ, cameraBlockY, radius, emitter);
        emitter.flush();
        buffer.setTranslation(0.0D, 0.0D, 0.0D);

        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        mc.entityRenderer.disableLightmap();
    }

    private static void renderInstanced(Minecraft mc, WeatherInstanceWriter writer, WeatherSampler sampler,
                                        Entity view, float rainStrength, float partialTicks, int updateCount,
                                        int radius, int viewBlockX, int viewBlockY, int viewBlockZ,
                                        double cameraX, double cameraY, double cameraZ, int cameraBlockY,
                                        BuildKey key, DeferredWorldRenderingPipeline deferred) {
        if (needsRebuild(builtKey, key)) {
            rebuildInstances(writer, sampler, radius, viewBlockX, viewBlockY, viewBlockZ, cameraBlockY);
            builtKey = key;
        }
        if (instanceCount == 0) return;

        WeatherParams.set((float) (viewBlockX - cameraX), (float) -cameraY, (float) (viewBlockZ - cameraZ),
                1.0F / radius, (float) (view.posX - viewBlockX), (float) (view.posZ - viewBlockZ),
                partialTicks, updateCount + partialTicks, updateCount, updateCount & 511, rainStrength);

        mc.entityRenderer.enableLightmap();
        GlStateManager.disableCull();
        GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        if (deferred != null) {
            deferred.bindInstancedVariant(Instancing.WEATHER);
        }
        try {
            drawRange(mc, RAIN_TEXTURE, 0, rainInstances, deferred == null);
            drawRange(mc, SNOW_TEXTURE, rainInstances, instanceCount - rainInstances, deferred == null);
        } finally {
            if (deferred != null) {
                deferred.restoreActivePass();
            }
        }

        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        mc.entityRenderer.disableLightmap();
    }

    private static void rebuildInstances(WeatherInstanceWriter writer, WeatherSampler sampler, int radius,
                                         int centerX, int centerY, int centerZ, int cameraBlockY) {
        final int slots = (radius * 2 + 1) * (radius * 2 + 1);
        final int stride = WeatherInstanceWriter.STRIDE;
        if (instanceCapacity < slots) {
            instanceCapacity = slots;
            instanceData = ByteBuffer.allocateDirect(slots * stride).order(ByteOrder.nativeOrder());
            if (instanceBuffer == 0) {
                instanceBuffer = GLSMBuffers.glGenBuffers();
            }
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, (long) slots * stride, GL15.GL_DYNAMIC_DRAW);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            WeatherQuadMesh.bindInstanceBuffer(instanceBuffer);
        }

        rainInstances = writer.write(instanceData, sampler, centerX, centerY, centerZ, cameraBlockY,
                radius, true, 0);
        instanceCount = writer.write(instanceData, sampler, centerX, centerY, centerZ, cameraBlockY,
                radius, false, rainInstances);
        if (instanceCount == 0) return;

        instanceData.position(0);
        instanceData.limit(instanceCount * stride);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceData);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        instanceData.clear();
    }

    private static void drawRange(Minecraft mc, ResourceLocation texture, int first, int count, boolean ffp) {
        if (count <= 0) return;
        mc.getTextureManager().bindTexture(texture);

        final VAOManager vaoManager = GLStateManager.getVaoManager();
        final int previousFlags = vaoManager.getVertexFlags();
        GLSMVertexInput.glBindVertexArray(WeatherQuadMesh.vao());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        try {
            WeatherInstancedAttribs.pointInstanceAttribs((long) first * WeatherInstanceWriter.STRIDE);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            vaoManager.setVertexFlags(WeatherQuadMesh.VERTEX_FLAGS);
            if (ffp) {
                GLSMPrograms.setFfpInstancing(Instancing.WEATHER);
            }
            GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, WeatherQuadMesh.VERTEX_COUNT, count);
        } finally {
            if (ffp) {
                GLSMPrograms.setFfpInstancing(Instancing.NONE);
            }
            vaoManager.setVertexFlags(previousFlags);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
        }
    }

    /**
     * Releases the weather GL resources and forgets the sampler and rebuild state.
     *
     * <p>Called when the client world goes away. The column cache keeps its sampler identity, so
     * dropping the sampler here also forces the next world to resample every column.</p>
     */
    public static void clear() {
        WeatherQuadMesh.delete();
        if (instanceBuffer != 0) {
            GLSMBuffers.glDeleteBuffers(instanceBuffer);
            instanceBuffer = 0;
        }
        instanceData = null;
        instanceCapacity = 0;
        instanceCount = 0;
        rainInstances = 0;
        builtKey = null;
        sampler = null;
        samplerWorld = new WeakReference<>(null);
    }

    /**
     * Instance data of one weather rebuild: everything the buffer content depends on.
     *
     * @param revision      column cache revision
     * @param updateCount   renderer update count, also the texture scroll source
     * @param blockX        camera block x, centre of the window
     * @param blockY        camera block y
     * @param blockZ        camera block z, centre of the window
     * @param cameraBlockY  interpolated camera block y, the light sample height
     * @param radius        column radius of the window
     */
    record BuildKey(int revision, int updateCount, int blockX, int blockY, int blockZ, int cameraBlockY,
                    int radius) {
    }

    /**
     * Returns whether the instance buffer must be rebuilt.
     *
     * <p>The cached data changes revision on its own, but light and temperature never do, so a forced
     * rebuild every {@link #REBUILD_TICKS} frames keeps those from going stale.</p>
     *
     * @param built   key of the buffer currently uploaded, {@code null} when nothing was built
     * @param current key of the frame about to be drawn
     * @return whether the buffer must be rebuilt
     */
    static boolean needsRebuild(BuildKey built, BuildKey current) {
        if (built == null) return true;
        return current.revision() != built.revision()
                || current.updateCount() - built.updateCount() >= REBUILD_TICKS
                || current.blockX() != built.blockX()
                || current.blockY() != built.blockY()
                || current.blockZ() != built.blockZ()
                || current.cameraBlockY() != built.cameraBlockY()
                || current.radius() != built.radius();
    }

    /**
     * Returns whether the weather pass may use the instanced path.
     *
     * <p>The pass needs the config switch, a backend that draws instances and, with a shader pack, the
     * pipeline's own weather variant. Without any of the three the per-column fallback keeps the
     * weather correct.</p>
     *
     * @param configEnabled             whether the player enabled instanced weather
     * @param backendSupportsInstanced  whether the active backend can draw instances
     * @param irisActive                whether a shader pipeline is active
     * @param irisVariantAvailable      whether the pipeline built the weather instance variant
     * @return whether the instanced path may be used
     */
    static boolean useInstancedPath(boolean configEnabled, boolean backendSupportsInstanced, boolean irisActive,
                                    boolean irisVariantAvailable) {
        if (!configEnabled || !backendSupportsInstanced) return false;
        return !irisActive || irisVariantAvailable;
    }

    private static boolean instancedWeatherEnabled() {
        return ActiniumRuntime.options().advanced.useInstancedWeather;
    }

    private static boolean irisActive() {
        return Iris.enabled;
    }

    /** Returns whether the dimension registered a Forge weather renderer of its own. */
    static boolean hasCustomWeatherRenderer(WorldProvider provider) {
        return provider.getWeatherRenderer() != null;
    }

    private static boolean deferredToVanilla() {
        return Mods.isModPresent("dsurround") || hasCustomWeatherRenderer(Minecraft.getMinecraft().world.provider);
    }

    /**
     * Returns the deferred pipeline of the active shader pack, or {@code null} without one.
     */
    private static DeferredWorldRenderingPipeline deferredPipeline() {
        if (!Iris.enabled) return null;
        return Iris.getPipelineManager().getPipelineNullable() instanceof DeferredWorldRenderingPipeline pipeline
                ? pipeline : null;
    }

    /**
     * Spawns the rain particles of the current frame.
     *
     * <p>Called instead of {@code EntityRenderer.addRainParticles}. The attempt count, the seeded
     * random stream, the height range, the biome gates, the magma and lava smoke branch and the
     * landing sound all follow the vanilla method.</p>
     *
     * @return whether the pass was handled; {@code false} leaves the vanilla pass in place
     */
    public static boolean spawnRainParticles() {
        if (deferredToVanilla()) return false;
        final Minecraft mc = Minecraft.getMinecraft();
        final WorldClient world = mc.world;
        float strength = world.getRainStrength(1.0F);
        if (!isFancyWeather(mc)) strength /= 2.0F;
        if (strength == 0.0F) return true;

        particleRandom.setSeed(updateCount(mc) * PARTICLE_SEED);
        final Entity view = mc.getRenderViewEntity();
        final BlockPos viewPos = new BlockPos(view);
        final int attempts = particleAttempts(strength, mc.gameSettings.particleSetting);

        double soundX = 0.0D;
        double soundY = 0.0D;
        double soundZ = 0.0D;
        int landed = 0;

        for (int attempt = 0; attempt < attempts; attempt++) {
            final BlockPos precipitation = world.getPrecipitationHeight(viewPos.add(
                    particleRandom.nextInt(PARTICLE_RADIUS) - particleRandom.nextInt(PARTICLE_RADIUS), 0,
                    particleRandom.nextInt(PARTICLE_RADIUS) - particleRandom.nextInt(PARTICLE_RADIUS)));
            final Biome biome = world.getBiome(precipitation);
            final BlockPos below = precipitation.down();
            final IBlockState state = world.getBlockState(below);
            if (precipitation.getY() > viewPos.getY() + PARTICLE_RADIUS
                    || precipitation.getY() < viewPos.getY() - PARTICLE_RADIUS
                    || !biome.canRain()
                    || biome.getTemperature(precipitation) < RAIN_TEMPERATURE) {
                continue;
            }

            final double offsetX = particleRandom.nextDouble();
            final double offsetZ = particleRandom.nextDouble();
            final AxisAlignedBB bounds = state.getBoundingBox(world, below);
            if (state.getMaterial() == Material.LAVA || state.getBlock() == Blocks.MAGMA) {
                world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                        precipitation.getX() + offsetX, precipitation.getY() + 0.1F - bounds.minY,
                        precipitation.getZ() + offsetZ, 0.0D, 0.0D, 0.0D);
            } else if (state.getMaterial() != Material.AIR) {
                if (particleRandom.nextInt(++landed) == 0) {
                    soundX = below.getX() + offsetX;
                    soundY = below.getY() + 0.1F + bounds.maxY - 1.0D;
                    soundZ = below.getZ() + offsetZ;
                }
                world.spawnParticle(EnumParticleTypes.WATER_DROP,
                        below.getX() + offsetX, below.getY() + 0.1F + bounds.maxY, below.getZ() + offsetZ,
                        0.0D, 0.0D, 0.0D);
            }
        }

        if (landed > 0 && particleRandom.nextInt(3) < rainSoundCounter++) {
            rainSoundCounter = 0;
            if (soundY > viewPos.getY() + 1 && world.getPrecipitationHeight(viewPos).getY() > viewPos.getY()) {
                world.playSound(soundX, soundY, soundZ, SoundEvents.WEATHER_RAIN_ABOVE, SoundCategory.WEATHER,
                        0.1F, 0.5F, false);
            } else {
                world.playSound(soundX, soundY, soundZ, SoundEvents.WEATHER_RAIN, SoundCategory.WEATHER,
                        0.2F, 1.0F, false);
            }
        }
        return true;
    }

    /**
     * Returns the x component of the billboard half axis of a column.
     *
     * <p>The quad faces the camera, so its axis is the column offset rotated by a quarter turn and
     * normalised. The camera column has a zero length offset; vanilla divides by that length and
     * writes NaN into the vertex buffer, which this method avoids by returning a zero extension so the
     * column collapses to an invisible quad.</p>
     *
     * @param dx column offset from the camera on x
     * @param dz column offset from the camera on z
     * @return x component of the unit axis, {@code 0} for the camera column
     */
    static float billboardAxisX(int dx, int dz) {
        if (dx == 0 && dz == 0) return 0.0F;
        return -dz / MathHelper.sqrt((float) (dx * dx + dz * dz));
    }

    /**
     * Returns the z component of the billboard half axis of a column.
     *
     * @param dx column offset from the camera on x
     * @param dz column offset from the camera on z
     * @return z component of the unit axis, {@code 0} for the camera column
     * @see #billboardAxisX(int, int)
     */
    static float billboardAxisZ(int dx, int dz) {
        if (dx == 0 && dz == 0) return 0.0F;
        return dx / MathHelper.sqrt((float) (dx * dx + dz * dz));
    }

    /**
     * Returns the transparency of a column at its distance from the camera.
     *
     * @param distanceRatio distance divided by the window radius
     * @param rain          rain fades from the full window radius, snow fades later and less
     * @param rainStrength  world rain strength of the frame
     * @return the alpha the column is drawn with
     */
    static float fadeAlpha(float distanceRatio, boolean rain, float rainStrength) {
        final float fade = rain ? 0.5F : 0.3F;
        return ((1.0F - distanceRatio * distanceRatio) * fade + 0.5F) * rainStrength;
    }

    /**
     * Returns how many rain particles vanilla attempts for a frame.
     *
     * @param strength        rain strength after the fast graphics halving
     * @param particleSetting vanilla particle setting: {@code 0} all, {@code 1} decreased, {@code 2} minimal
     * @return the number of attempts
     */
    static int particleAttempts(float strength, int particleSetting) {
        int attempts = (int) (100.0F * strength * strength);
        if (particleSetting == 1) {
            attempts >>= 1;
        } else if (particleSetting == 2) {
            attempts = 0;
        }
        return attempts;
    }

    /**
     * Returns the packed lightmap of one column quad.
     *
     * <p>Vanilla samples the column light at the higher of the precipitation height and the
     * interpolated camera block height, so a column seen from above is lit where the camera is and
     * not where the quad starts. Snow is then lerped three quarters towards full daylight.</p>
     *
     * @param columns cache holding the sampled columns
     * @param sampler world reads for the column light
     * @param slot    ring slot of the column in the cache
     * @param x       block x of the column
     * @param z       block z of the column
     * @param lightY  height the column light is sampled at
     * @param rain    whether the column rains instead of snowing
     * @return packed sky and block light the quad is drawn with
     */
    static int columnLightmap(WeatherColumns columns, WeatherSampler sampler, int slot, int x, int z,
                              int lightY, boolean rain) {
        final int combinedLight = columns.lightAtSlot(sampler, slot, x, z, lightY);
        return rain ? combinedLight : (combinedLight * 3 + FULL_DAYLIGHT) / 4;
    }

    /**
     * Returns the sampler of the given world, keeping one instance per world.
     *
     * <p>The cache drops every column when the sampler changes, so a new instance must only be
     * created when the world itself changed.</p>
     */
    private static WeatherSampler sampler(WorldClient world) {
        if (samplerWorld.get() != world) {
            samplerWorld = new WeakReference<>(world);
            sampler = new WorldClientWeatherSamplerImpl(world);
        }
        return sampler;
    }

    private static int weatherRadius(Minecraft mc) {
        return isFancyWeather(mc) ? FANCY_WEATHER_RADIUS : FAST_WEATHER_RADIUS;
    }

    private static boolean isFancyWeather(Minecraft mc) {
        return ActiniumRuntime.options().quality.weatherQuality.isFancy(mc.gameSettings.fancyGraphics);
    }

    private static int updateCount(Minecraft mc) {
        return ((AccessorEntityRenderer) mc.entityRenderer).celeritas$getRendererUpdateCount();
    }

    /**
     * Emits the quads of the visited columns, grouping them by texture.
     *
     * <p>A batch is started on the first column of a precipitation type and drawn when the type
     * changes, which keeps the draw count of a window with interleaved biomes as low as vanilla's.</p>
     */
    private static final class QuadEmitter implements WeatherInstanceWriter.ColumnVisitor {

        private final Minecraft mc;
        private final WeatherSampler sampler;
        private final Entity view;
        private final float rainStrength;
        private final float partialTicks;
        private final int updateCount;
        private final int radius;
        private final int viewBlockX;
        private final int viewBlockZ;
        private final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        private int bound = -1;

        private QuadEmitter(Minecraft mc, WeatherSampler sampler, Entity view, float rainStrength,
                            float partialTicks, int updateCount, int radius, int viewBlockX, int viewBlockZ) {
            this.mc = mc;
            this.sampler = sampler;
            this.view = view;
            this.rainStrength = rainStrength;
            this.partialTicks = partialTicks;
            this.updateCount = updateCount;
            this.radius = radius;
            this.viewBlockX = viewBlockX;
            this.viewBlockZ = viewBlockZ;
        }

        @Override
        public void column(int slot, int x, int z, int bottom, int top, int lightY, boolean rain) {
            final int axisX = x - viewBlockX;
            final int axisZ = z - viewBlockZ;
            final float halfX = billboardAxisX(axisX, axisZ) * 0.5F;
            final float halfZ = billboardAxisZ(axisX, axisZ) * 0.5F;

            final double offsetX = (x + 0.5F) - view.posX;
            final double offsetZ = (z + 0.5F) - view.posZ;
            final float distance = MathHelper.sqrt(offsetX * offsetX + offsetZ * offsetZ) / radius;

            final int brightness = columnLightmap(columns, sampler, slot, x, z, lightY, rain);
            final double scrollBottom;
            final double scrollTop;
            final float u0;
            final float u1;
            final float alpha;

            if (rain) {
                bind(0, RAIN_TEXTURE);
                final double scroll = -(((updateCount + columns.hashAtSlot(slot)) & 31) + partialTicks) / 32.0D
                        * (3.0D + columns.jitterAtSlot(slot, 0));
                scrollBottom = bottom * 0.25D + scroll;
                scrollTop = top * 0.25D + scroll;
                u0 = 0.0F;
                u1 = 1.0F;
                alpha = fadeAlpha(distance, true, rainStrength);
            } else {
                bind(1, SNOW_TEXTURE);
                final double age = updateCount + partialTicks;
                final double scroll = -((updateCount & 511) + partialTicks) / 512.0D;
                final double driftU = columns.jitterAtSlot(slot, 0) + age * 0.01D * columns.jitterAtSlot(slot, 1);
                final double driftV = columns.jitterAtSlot(slot, 2) + age * columns.jitterAtSlot(slot, 3) * 0.001D;
                scrollBottom = bottom * 0.25D + scroll + driftV;
                scrollTop = top * 0.25D + scroll + driftV;
                u0 = (float) driftU;
                u1 = (float) (1.0D + driftU);
                alpha = fadeAlpha(distance, false, rainStrength);
            }

            emit(x, z, bottom, top, halfX, halfZ, u0, u1, scrollBottom, scrollTop, brightness, alpha);
        }

        /**
         * Draws the open batch and closes it.
         */
        private void flush() {
            if (bound >= 0) {
                Tessellator.getInstance().draw();
                bound = -1;
            }
        }

        private void bind(int type, ResourceLocation texture) {
            if (bound == type) return;
            flush();
            bound = type;
            mc.getTextureManager().bindTexture(texture);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        }

        private void emit(int x, int z, int bottom, int top, float halfX, float halfZ, float u0, float u1,
                          double vBottom, double vTop, int brightness, float alpha) {
            final int skyLight = (brightness >> 16) & 0xFFFF;
            final int blockLight = brightness & 0xFFFF;
            final double minX = x - halfX + 0.5D;
            final double maxX = x + halfX + 0.5D;
            final double minZ = z - halfZ + 0.5D;
            final double maxZ = z + halfZ + 0.5D;

            vertex(minX, bottom, minZ, u0, vBottom, skyLight, blockLight, alpha);
            vertex(maxX, bottom, maxZ, u1, vBottom, skyLight, blockLight, alpha);
            vertex(maxX, top, maxZ, u1, vTop, skyLight, blockLight, alpha);
            vertex(minX, top, minZ, u0, vTop, skyLight, blockLight, alpha);
        }

        private void vertex(double x, double y, double z, float u, double v, int skyLight, int blockLight,
                            float alpha) {
            buffer.pos(x, y, z).tex(u, v).color(1.0F, 1.0F, 1.0F, alpha).lightmap(skyLight, blockLight).endVertex();
        }
    }
}
