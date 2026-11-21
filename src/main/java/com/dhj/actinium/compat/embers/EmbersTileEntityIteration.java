package com.dhj.actinium.compat.embers;

import net.minecraft.tileentity.TileEntity;

import java.util.ArrayList;
import java.util.List;

/** Isolates Embers' render traversal from changes to the world's tile entity list. */
public final class EmbersTileEntityIteration {
    private EmbersTileEntityIteration() {
    }

    public static List<TileEntity> snapshot(List<TileEntity> loaded) {
        return new ArrayList<>(loaded);
    }
}
