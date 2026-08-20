package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = GpuDevice.class, remap = false)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend voxy$backend();
}
