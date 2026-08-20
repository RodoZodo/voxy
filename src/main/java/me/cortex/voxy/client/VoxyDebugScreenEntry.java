package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class VoxyDebugScreenEntry implements DebugScreenEntry {
    @Override
    public void display(DebugScreenDisplayer lines, @Nullable Level world, @Nullable LevelChunk clientChunk, @Nullable LevelChunk chunk) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return;
        }

        //TODO(vulkan): render debug info was removed with the GL renderer
        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        lines.addToGroup(Identifier.fromNamespaceAndPath("voxy", "instance_debug"), instanceLines);
    }
}
