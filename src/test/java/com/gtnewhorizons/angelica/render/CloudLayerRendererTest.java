package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CloudLayerRendererTest {

    @Test
    void deleteCanBeRepeatedAndTheLayerIsStillUsable() {
        final CloudLayerRenderer renderer = renderer();
        final CloudLayer layer = layer();

        renderer.configure(layer, CloudLayerRenderer.MODE_FANCY, 8, 1);
        renderer.delete();
        renderer.delete();

        renderer.configure(layer, CloudLayerRenderer.MODE_FAST, 8, 1);
        renderer.delete();
    }

    @Test
    void configureKeepsTheLayerDescriptionForTheHostOrdering() {
        final CloudLayerRenderer renderer = renderer();
        final CloudLayer layer = layer();

        renderer.configure(layer, CloudLayerRenderer.MODE_FANCY, 8, 1);

        assertEquals(layer, renderer.layer);
    }

    @Test
    void theTintKeepsTheLayerAlphaAndPassesTheColourThrough() {
        final CloudLayer layer = layer();
        final CloudLayerRenderer.Tint tint = CloudLayerRenderer.Tint.of(layer, false);

        assertEquals(layer.red(), tint.red(), 0.0F);
        assertEquals(layer.green(), tint.green(), 0.0F);
        assertEquals(layer.blue(), tint.blue(), 0.0F);
        assertEquals(layer.alpha(), tint.alpha(), 0.0F);
    }

    @Test
    void theAnaglyphTintLeavesNeutralColoursUntouched() {
        final CloudLayer grey = new CloudLayer("test:grey", new ResourceLocation("textures/environment/clouds.png"),
            128.0F, 12.0F, 4.0F, 12.0F, 0.0, 0.0, 0.5F, 0.5F, 0.5F, 0.8F);

        final CloudLayerRenderer.Tint tint = CloudLayerRenderer.Tint.of(grey, true);

        assertEquals(0.5F, tint.red(), 1.0E-6F);
        assertEquals(0.5F, tint.green(), 1.0E-6F);
        assertEquals(0.5F, tint.blue(), 1.0E-6F);
        assertEquals(grey.alpha(), tint.alpha(), 0.0F);
    }

    @Test
    void aFailedTaskReportsItsCauseInsteadOfThrowing() {
        final IllegalStateException cause = new IllegalStateException("build exploded");
        final CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(cause);

        assertSame(cause, CloudLayerRenderer.awaitFailure(failed));
        assertNull(CloudLayerRenderer.awaitFailure(CompletableFuture.completedFuture(null)));
    }

    @Test
    void aFailedTaskDoesNotBlockReleasingTheLayer() {
        final CloudLayerRenderer renderer = renderer();
        final CloudLayer layer = layer();
        renderer.configure(layer, CloudLayerRenderer.MODE_FANCY, 8, 1);

        renderer.failBuild(new CloudLayerPlan.Key(1, true, true, 8, 4.0F, false, 96, 32, false),
            new IllegalStateException("build exploded"));
        renderer.failReorder(3, -2, new IllegalStateException("reorder exploded"));

        renderer.delete();
        renderer.delete();

        renderer.configure(layer, CloudLayerRenderer.MODE_FAST, 8, 1);
        assertEquals(layer, renderer.layer);
        renderer.delete();
    }

    private static CloudLayerRenderer renderer() {
        return new CloudLayerRenderer(new CloudRenderResources(), null);
    }

    private static CloudLayer layer() {
        return new CloudLayer("test:clouds", new ResourceLocation("textures/environment/clouds.png"), 128.0F,
            12.0F, 4.0F, 12.0F, 0.0, 0.0, 0.4F, 0.6F, 0.9F, 0.8F);
    }
}
