package net.coderbot.iris.texture.pbr.loader;

import net.coderbot.iris.debug.PBRDebug;
import net.coderbot.iris.texture.pbr.PBRType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public class SimplePBRLoader implements PBRTextureLoader<SimpleTexture> {
	@Override
	public void load(SimpleTexture texture, IResourceManager resourceManager, PBRTextureConsumer pbrTextureConsumer) {
		ResourceLocation location = texture.textureLocation;

		AbstractTexture normalTexture = createPBRTexture(location, resourceManager, PBRType.NORMAL);
		AbstractTexture specularTexture = createPBRTexture(location, resourceManager, PBRType.SPECULAR);

		if (normalTexture != null) {
			pbrTextureConsumer.acceptNormalTexture(normalTexture);
		}
		if (specularTexture != null) {
			pbrTextureConsumer.acceptSpecularTexture(specularTexture);
		}
	}

	@Nullable
	protected AbstractTexture createPBRTexture(ResourceLocation imageLocation, IResourceManager resourceManager, PBRType pbrType) {
		ResourceLocation pbrImageLocation = pbrType.appendToFileLocation(imageLocation);

		if (PBRType.hasDirectionalSiblings(pbrImageLocation, resourceManager)) {
			// Looks like a cardinal-direction texture set (e.g. "_n"/"_s"/"_e"/"_w" for block faces),
			// not an actual PBR map. Don't treat it as one.
			return null;
		}

		SimpleTexture pbrTexture = new SimpleTexture(pbrImageLocation);
		try {
			pbrTexture.loadTexture(resourceManager);
			PBRDebug.textureLoaded(pbrType, pbrImageLocation);
		} catch (IOException e) {
			PBRDebug.spriteMissing(pbrType, pbrImageLocation, e);
			return null;
		}

		return pbrTexture;
	}
}
