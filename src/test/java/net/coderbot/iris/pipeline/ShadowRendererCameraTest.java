package net.coderbot.iris.pipeline;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowRendererCameraTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void staticPlayerPositionUsesTheInterpolatedViewerPosition() {
        Entity viewer = new Entity(null) {
            @Override
            protected void entityInit() {
            }

            @Override
            protected void readEntityFromNBT(NBTTagCompound compound) {
            }

            @Override
            protected void writeEntityToNBT(NBTTagCompound compound) {
            }
        };
        viewer.lastTickPosX = 10.0;
        viewer.lastTickPosY = 20.0;
        viewer.lastTickPosZ = 30.0;
        viewer.posX = 14.0;
        viewer.posY = 28.0;
        viewer.posZ = 38.0;

        double previousX = TileEntityRendererDispatcher.staticPlayerX;
        double previousY = TileEntityRendererDispatcher.staticPlayerY;
        double previousZ = TileEntityRendererDispatcher.staticPlayerZ;
        try {
            ShadowRenderer.cacheStaticPlayerPosition(viewer, 0.25F);

            assertEquals(11.0, TileEntityRendererDispatcher.staticPlayerX);
            assertEquals(22.0, TileEntityRendererDispatcher.staticPlayerY);
            assertEquals(32.0, TileEntityRendererDispatcher.staticPlayerZ);
        } finally {
            TileEntityRendererDispatcher.staticPlayerX = previousX;
            TileEntityRendererDispatcher.staticPlayerY = previousY;
            TileEntityRendererDispatcher.staticPlayerZ = previousZ;
        }
    }
}
