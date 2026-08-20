package me.cortex.voxy.client.mixin.minecraft.util;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.VkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Best-effort capture of {@link VulkanDevice} as soon as Minecraft initializes the Vulkan backend.
 * Must never throw: this runs inside {@code VulkanDevice.<init>}, which is on Minecraft's
 * graphics-backend startup path — an exception trips the crash ladder back to OpenGL.
 *
 * <p>Lunar/Ichor may skip this mixin (optional config). {@code GpuDevice} unwrap +
 * {@code Minecraft.<init>} TAIL are the fallbacks.
 */
@Mixin(value = VulkanDevice.class, remap = false)
public class MixinVulkanDevice {
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void voxy$capture(CallbackInfo ci) {
        try {
            VkContext.INSTANCE.capture((VulkanDevice) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
