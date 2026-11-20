package com.dhj.actinium.compat.embers;

import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbersTileEntityIterationTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void changesToTheLoadedListDoNotInterruptTheCurrentRenderTraversal() {
        TileEntity first = new TestTileEntity();
        TileEntity second = new TestTileEntity();
        TileEntity added = new TestTileEntity();
        List<TileEntity> loaded = new ArrayList<>(List.of(first, second));
        var snapshot = EmbersTileEntityIteration.snapshot(loaded);
        List<TileEntity> rendered = new ArrayList<>();

        for (TileEntity tile : snapshot) {
            rendered.add(tile);
            if (tile == first) {
                loaded.remove(first);
                loaded.add(added);
            }
        }

        assertEquals(List.of(first, second), rendered);
        assertEquals(second, snapshot.get(1));
        assertEquals(List.of(second, added), EmbersTileEntityIteration.snapshot(loaded));
    }

    private static final class TestTileEntity extends TileEntity {
    }
}
