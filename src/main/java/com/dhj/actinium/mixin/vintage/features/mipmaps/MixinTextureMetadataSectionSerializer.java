package com.dhj.actinium.mixin.vintage.features.mipmaps;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.dhj.actinium.texture.mipmap.TextureMetadataExtension;
import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import net.minecraft.client.resources.data.TextureMetadataSection;
import net.minecraft.client.resources.data.TextureMetadataSectionSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/**
 * Reads the {@code mipmap_strategy} key out of a texture's {@code mcmeta} {@code texture} section.
 *
 * <p>The section itself is only read by {@code SimpleTexture} in 1.12.2 - the atlas loader never asks for it -
 * so the value parsed here is picked up by the sprite-side atlas hook instead, which has the sprite's
 * resource in hand.
 */
@Mixin(TextureMetadataSectionSerializer.class)
public class MixinTextureMetadataSectionSerializer {

    @Inject(
        method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Lnet/minecraft/client/resources/data/TextureMetadataSection;",
        at = @At("RETURN"))
    private void angelica$readMipmapStrategy(JsonElement json, Type type, JsonDeserializationContext context,
                                             CallbackInfoReturnable<TextureMetadataSection> cir) {
        final TextureMetadataSection section = cir.getReturnValue();
        if (section == null || !json.isJsonObject()) {
            return;
        }
        final JsonObject object = json.getAsJsonObject();
        if (!object.has("mipmap_strategy")) {
            return;
        }
        final JsonElement value = object.get("mipmap_strategy");
        if (!value.isJsonPrimitive()) {
            return;
        }
        ((TextureMetadataExtension) section).angelica$setMipmapStrategy(MipmapStrategy.byName(value.getAsString()));
    }
}
