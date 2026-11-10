package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import com.gtnewhorizons.angelica.api.clouds.CloudLayerProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.IRenderHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudRendererTest {

    private static final ResourceLocation CLOUD_TEXTURE = new ResourceLocation("textures/environment/clouds.png");
    private static final ResourceLocation GREY_TEXTURE = new ResourceLocation("test:grey_clouds.png");

    @Test
    void theHandlerLayersWinOverTheDimensionProvider() {
        final CloudLayer handlerLayer = layer("handler:clouds", CLOUD_TEXTURE);
        final CloudLayer dimensionLayer = layer("dimension:clouds", CLOUD_TEXTURE);

        final CloudRenderer.Layers described = describe(
            new LayeredHandler(List.of(handlerLayer)), provider(dimensionLayer),
            () -> layer("vanilla:clouds", CLOUD_TEXTURE));

        assertTrue(described.owned());
        assertEquals(List.of(handlerLayer), described.layers());
    }

    @Test
    void theDimensionProviderSuppliesTheLayersOfACustomHandler() {
        final CloudLayer dimensionLayer = layer("dimension:clouds", CLOUD_TEXTURE);

        final CloudRenderer.Layers described = describe(
            new CustomHandler(), provider(dimensionLayer), () -> layer("vanilla:clouds", CLOUD_TEXTURE));

        assertTrue(described.owned());
        assertEquals(List.of(dimensionLayer), described.layers());
    }

    @Test
    void aCustomHandlerWithoutLayersKeepsItsOwnCloudPass() {
        final CloudRenderer.Layers described = describe(
            new CustomHandler(), null, () -> layer("vanilla:clouds", CLOUD_TEXTURE));

        assertFalse(described.owned());
        assertTrue(described.layers().isEmpty());
    }

    @Test
    void theVanillaLayerIsUsedWithoutAProvider() {
        final CloudLayer vanilla = layer("minecraft:clouds", CLOUD_TEXTURE);

        final CloudRenderer.Layers described = describe(null, null, () -> vanilla);

        assertTrue(described.owned());
        assertEquals(List.of(vanilla), described.layers());
    }

    @Test
    void aDimensionWithoutVanillaCloudsDrawsNoLayers() {
        final CloudRenderer.Layers described = describe(null, null, null);

        assertTrue(described.owned());
        assertTrue(described.layers().isEmpty());
    }

    @Test
    void aVanillaLayerWithAnUnusableHeightIsSkipped() {
        final CloudRenderer.Layers described = describe(null, null, () -> null);

        assertTrue(described.owned());
        assertTrue(described.layers().isEmpty());
    }

    @Test
    void theLayerPlanCreatesOnlyNewLayers() {
        final CloudLayer fresh = layer("a", CLOUD_TEXTURE);

        final CloudRenderer.LayerPlan plan = CloudRenderer.planLayers(Map.of(), List.of(fresh));

        assertTrue(plan.retired().isEmpty());
        assertEquals(List.of(fresh), plan.created());
    }

    @Test
    void theLayerPlanKeepsALayerWhoseTextureDidNotChange() {
        final CloudLayer kept = layer("a", CLOUD_TEXTURE);

        final CloudRenderer.LayerPlan plan = CloudRenderer.planLayers(Map.of("a", CLOUD_TEXTURE), List.of(kept));

        assertTrue(plan.retired().isEmpty());
        assertTrue(plan.created().isEmpty());
    }

    @Test
    void theLayerPlanReplacesALayerWhoseTextureChanged() {
        final CloudLayer changed = layer("a", GREY_TEXTURE);

        final CloudRenderer.LayerPlan plan = CloudRenderer.planLayers(Map.of("a", CLOUD_TEXTURE), List.of(changed));

        assertEquals(List.of("a"), plan.retired());
        assertEquals(List.of(changed), plan.created());
    }

    @Test
    void theLayerPlanRetiresLayersThatAreNoLongerDescribed() {
        final CloudLayer kept = layer("a", CLOUD_TEXTURE);

        final CloudRenderer.LayerPlan plan = CloudRenderer.planLayers(
            Map.of("a", CLOUD_TEXTURE, "gone", CLOUD_TEXTURE), List.of(kept));

        assertEquals(List.of("gone"), plan.retired());
        assertTrue(plan.created().isEmpty());
    }

    @Test
    void theLayerPlanRejectsDuplicateLayerIds() {
        final CloudLayer first = layer("a", CLOUD_TEXTURE);
        final CloudLayer second = layer("a", GREY_TEXTURE);

        assertThrows(IllegalArgumentException.class,
            () -> CloudRenderer.planLayers(Map.of(), List.of(first, second)));
    }

    @Test
    void theRequiredFarPlaneCoversTheLayerDiscAndItsOffset() {
        final CloudLayer layer = layer("minecraft:clouds", CLOUD_TEXTURE);

        final float required = CloudRenderer.requiredFarPlaneDistance(
            8, CloudLayerRenderer.MODE_FANCY, List.of(layer), 64.0, CloudRenderer.SCALE_MULT);

        assertTrue(required >= 16 * 12.0F + (128.0F - 64.0F),
            "the far plane must reach past the disc margin and the layer height above the camera: " + required);
    }

    @Test
    void theRequiredFarPlaneGrowsOnTheDiagonalOfAFastPlate() {
        final CloudLayer layer = layer("minecraft:clouds", CLOUD_TEXTURE);

        final float fancy = CloudRenderer.requiredFarPlaneDistance(
            8, CloudLayerRenderer.MODE_FANCY, List.of(layer), 64.0, CloudRenderer.SCALE_MULT);
        final float fast = CloudRenderer.requiredFarPlaneDistance(
            8, CloudLayerRenderer.MODE_FAST, List.of(layer), 64.0, CloudRenderer.SCALE_MULT);

        assertTrue(fast > fancy, "a fast plate is seen on the diagonal: fast " + fast + ", fancy " + fancy);
    }

    @Test
    void theRequiredFarPlaneSkipsInvisibleLayers() {
        final CloudLayer invisible = new CloudLayer("a", CLOUD_TEXTURE, 128.0F, 12.0F, 4.0F, 12.0F, 0.0, 0.0,
            0.5F, 0.5F, 0.5F, 0.0F);

        final float required = CloudRenderer.requiredFarPlaneDistance(
            8, CloudLayerRenderer.MODE_FANCY, List.of(invisible), 64.0, CloudRenderer.SCALE_MULT);

        assertEquals(0.0F, required, 0.0F);
    }

    @Test
    void theRequiredFarPlaneNeedsARenderDistance() {
        final CloudLayer layer = layer("minecraft:clouds", CLOUD_TEXTURE);

        assertEquals(0.0F, CloudRenderer.requiredFarPlaneDistance(
            0, CloudLayerRenderer.MODE_FANCY, List.of(layer), 64.0, CloudRenderer.SCALE_MULT), 0.0F);
        assertEquals(0.0F, CloudRenderer.requiredFarPlaneDistance(
            -1, CloudLayerRenderer.MODE_FANCY, List.of(layer), 64.0, CloudRenderer.SCALE_MULT), 0.0F);
    }

    @Test
    void theRequiredFarPlaneIsCappedForAbsurdRenderDistances() {
        final CloudLayer layer = layer("minecraft:clouds", CLOUD_TEXTURE);

        final float required = CloudRenderer.requiredFarPlaneDistance(
            1_000_000, CloudLayerRenderer.MODE_FAST, List.of(layer), 64.0, CloudRenderer.SCALE_MULT);

        assertEquals(CloudRenderer.MAX_FAR_PLANE_DISTANCE, required, 0.0F);
    }

    @Test
    void theLayerDistanceIsZeroInsideTheLayerAndGrowsOutside() {
        final CloudLayer layer = layer("minecraft:clouds", CLOUD_TEXTURE);

        assertEquals(0.0, CloudRenderer.distanceToLayer(
            layer, 130.0, CloudLayerRenderer.MODE_FANCY, CloudRenderer.SCALE_MULT), 0.0);
        assertTrue(CloudRenderer.distanceToLayer(layer, 30.0, CloudLayerRenderer.MODE_FANCY, CloudRenderer.SCALE_MULT) > 90.0,
            "a camera far below the layer is far away from it");
        assertTrue(CloudRenderer.distanceToLayer(layer, 230.0, CloudLayerRenderer.MODE_FANCY, CloudRenderer.SCALE_MULT) > 90.0,
            "a camera far above the layer is far away from it");
    }

    @Test
    void invalidateWorldReleasesEveryLayerAndCanBeRepeated() {
        final CloudRenderer renderer = new CloudRenderer();
        renderer.layers.put("a", new CloudLayerRenderer(renderer.resources, null));
        renderer.layers.put("b", new CloudLayerRenderer(renderer.resources, null));

        renderer.invalidateWorld();
        renderer.invalidateWorld();

        assertTrue(renderer.layers.isEmpty());
    }

    @Test
    void aResourceReloadReleasesTheLayersOfTheCurrentWorld() {
        final CloudRenderer renderer = new CloudRenderer();
        renderer.layers.put("a", new CloudLayerRenderer(renderer.resources, null));

        renderer.onResourceManagerReload(null);
        renderer.onResourceManagerReload(null);

        assertTrue(renderer.layers.isEmpty());
    }

    @Test
    void releasingBeforeTheFirstCloudFrameIsANoOp() {
        CloudRenderer.release();
        CloudRenderer.release();
    }

    /**
     * Resolves the layers of a frame. The provider priority does not read the world, so the tests leave it
     * out and the fake providers ignore it.
     */
    private static CloudRenderer.Layers describe(IRenderHandler handler, CloudLayerProvider dimensionProvider,
                                                 Supplier<CloudLayer> vanillaLayer) {
        return CloudRenderer.describeLayers(null, 0, 0.0F, handler, dimensionProvider, vanillaLayer);
    }

    private static CloudLayerProvider provider(CloudLayer layer) {
        return (world, cloudTicks, partialTicks) -> List.of(layer);
    }

    private static CloudLayer layer(String id, ResourceLocation texture) {
        return new CloudLayer(id, texture, 128.0F, 12.0F, 4.0F, 12.0F, 0.0, 0.0, 0.4F, 0.6F, 0.9F, 0.8F);
    }

    /** A Forge cloud handler that draws its own clouds and publishes no layers. */
    private static final class CustomHandler extends IRenderHandler {
        @Override
        public void render(float partialTicks, WorldClient world, Minecraft mc) {
        }
    }

    /** A Forge cloud handler that publishes the layers the new renderer draws instead. */
    private static final class LayeredHandler extends IRenderHandler implements CloudLayerProvider {

        private final List<CloudLayer> layers;

        private LayeredHandler(List<CloudLayer> layers) {
            this.layers = layers;
        }

        @Override
        public List<CloudLayer> getCloudLayers(WorldClient world, int cloudTicks, float partialTicks) {
            return layers;
        }

        @Override
        public void render(float partialTicks, WorldClient world, Minecraft mc) {
        }
    }
}
