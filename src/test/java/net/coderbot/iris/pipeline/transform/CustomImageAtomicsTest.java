package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import net.coderbot.iris.gl.image.ImageInformation;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gl.texture.PixelFormat;
import net.coderbot.iris.gl.texture.PixelType;
import net.coderbot.iris.gl.texture.TextureType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomImageAtomicsTest {

    private RenderBackend original;

    @AfterEach
    void restoreBackend() {
        if (original != null) Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, original);
        original = null;
        RwImageStoreExtractor.setActiveCustomImages(null);
    }

    private void installBackend(boolean atomicsEverywhere) {
        original = BackendManager.RENDER_BACKEND;
        Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, new AtomicsBackend(atomicsEverywhere));
    }

    /** Only the two image atomics hooks are reached: the extraction asks the backend nothing else. */
    private static final class AtomicsBackend extends StubRenderBackend {
        private final boolean atomicsEverywhere;

        AtomicsBackend(boolean atomicsEverywhere) {
            this.atomicsEverywhere = atomicsEverywhere;
        }

        @Override
        public boolean supportsComputeImageAtomics() {
            return atomicsEverywhere;
        }

        @Override
        public boolean supportsComputeImageAtomics(int width, int height, int depth) {
            return atomicsEverywhere || (width == 40 && height == 10 && depth == 1);
        }
    }

    private static ImageInformation image(String name, int width, int height, boolean relative) {
        return new ImageInformation(name, name + "_sampler", TextureType.TEXTURE_2D, PixelFormat.RED_INTEGER, InternalTextureFormat.R32I, PixelType.INT, width, height, 1, false, relative, relative ? 1.0f : 0.0f, relative ? 1.0f : 0.0f);
    }

    private static Map<String, ImageInformation> images() {
        return Map.of("small_img", image("small_img", 40, 10, false), "large_img", image("large_img", 64, 64, false), "screen_img", image("screen_img", 40, 10, true));
    }

    @Test
    void onlyImagesTheBackendAcceptsBySizeKeepAtomics() {
        installBackend(false);
        RwImageStoreExtractor.setActiveCustomImages(images());

        assertTrue(RwImageStoreExtractor.supportsAtomics("small_img"));
        assertFalse(RwImageStoreExtractor.supportsAtomics("large_img"));
        assertFalse(RwImageStoreExtractor.supportsAtomics("screen_img"));
        assertFalse(RwImageStoreExtractor.supportsAtomics("colortex0"));
        assertEquals("small_img", RwImageStoreExtractor.atomicCustomImagesKey());
    }

    @Test
    void backendWithImageAtomicsEverywhereNeedsNoPerImageList() {
        installBackend(true);
        RwImageStoreExtractor.setActiveCustomImages(images());

        assertTrue(RwImageStoreExtractor.supportsAtomics("small_img"));
        assertTrue(RwImageStoreExtractor.supportsAtomics("large_img"));
        assertTrue(RwImageStoreExtractor.supportsAtomics("screen_img"));
        assertTrue(RwImageStoreExtractor.supportsAtomics("colortex0"));
        assertEquals("", RwImageStoreExtractor.atomicCustomImagesKey());
    }

    @Test
    void clearingTheImagesClearsTheList() {
        installBackend(false);
        RwImageStoreExtractor.setActiveCustomImages(images());
        RwImageStoreExtractor.setActiveCustomImages(null);

        assertFalse(RwImageStoreExtractor.supportsAtomics("small_img"));
        assertEquals("", RwImageStoreExtractor.atomicCustomImagesKey());
    }
}
