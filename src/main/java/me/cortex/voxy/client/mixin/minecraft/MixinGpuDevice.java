package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import me.cortex.voxy.client.core.vk.VkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Capture the backend from {@link GpuDevice}'s constructor argument. This class is in
 * {@code client.voxy.mixins.json} (the config Lunar already applies) so it does not depend on
 * the optional Vulkan mixin file. Must never throw: {@code GpuDevice} is created on the
 * graphics-backend startup path.
 */
@Mixin(value = GpuDevice.class, remap = false)
public class MixinGpuDevice {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void voxy$captureBackend(GpuDeviceBackend backend, Runnable criticalShaderLoader, CallbackInfo ci) {
        try {
            VkContext.INSTANCE.captureFromBackend(backend);
        } catch (Throwable ignored) {
        }
    }
}
