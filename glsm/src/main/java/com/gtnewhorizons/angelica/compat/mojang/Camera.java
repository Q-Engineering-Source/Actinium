package com.gtnewhorizons.angelica.compat.mojang;

import com.gtnewhorizons.angelica.rendering.RenderingState;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Singleton camera instance that tracks the current camera position and orientation.
 */
@Getter
public class Camera {
    public static final Camera INSTANCE = new Camera();

    private final Vector3d pos = new Vector3d();
    private final Vector3d entityPos = new Vector3d();
    private BlockPos blockPos = BlockPos.ORIGIN;
    private final Vector3f offset = new Vector3f();
    private final Matrix4f inverseModelView = new Matrix4f();

    private float pitch;
    private float yaw;
    private boolean isSleeping;
    private boolean thirdPerson;
    private float partialTicks;

    private Camera() {}

    public void update(EntityLivingBase entity, float partialTicks) {
        this.partialTicks = partialTicks;
        this.isSleeping = entity.isPlayerSleeping();

        // Entity position (interpolated, at eye level)
        final double entityX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        final double entityY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        final double entityZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        final double eyeHeight = entity.getEyeHeight();

        entityPos.set(entityX, entityY + eyeHeight, entityZ);

        thirdPerson = Minecraft.getMinecraft().gameSettings.thirdPersonView > 0;

        final double camX, camY, camZ;
        offset.set(0, 0, 0);

        if (thirdPerson) {
            // Third person: use inverse modelview to find camera offset
            inverseModelView.set(RenderingState.INSTANCE.getModelViewMatrix()).invert();
            inverseModelView.transformPosition(offset);
        }
        camX = entityX + offset.x;
        camY = entityY + eyeHeight + offset.y;
        camZ = entityZ + offset.z;

        pos.set(camX, camY, camZ);
        blockPos = new BlockPos(MathHelper.floor(camX), MathHelper.floor(camY), MathHelper.floor(camZ));
        pitch = entity.cameraPitch;
        yaw = entity.rotationYaw;
    }
}
