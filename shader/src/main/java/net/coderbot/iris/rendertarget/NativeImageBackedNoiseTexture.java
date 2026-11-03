package net.coderbot.iris.rendertarget;

import com.gtnewhorizons.angelica.compat.mojang.NativeImage;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import java.util.Random;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;

public class NativeImageBackedNoiseTexture extends DynamicTexture {
	public NativeImageBackedNoiseTexture(int size) {
		super(create(size));
	}

	private static NativeImage create(int size) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		Random random = new Random(0);

		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				int color = random.nextInt() | (255 << 24);

				image.setPixelRGBA(x, y, color);
			}
		}

		return image;
	}

	@Override
    public void updateDynamicTexture() {
        // DynamicTexture's upload path (TextureUtil.uploadTextureSub) resets the
        // filtering to GL_NEAREST, which turns the per-pixel random noise into harsh
        // pixel stripes when sampled (Sildur-style water waves show as crossing
        // straight lines). Re-apply linear filtering after the upload so the noise
        // interpolates into smooth ripples.
        super.updateDynamicTexture();
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

}
