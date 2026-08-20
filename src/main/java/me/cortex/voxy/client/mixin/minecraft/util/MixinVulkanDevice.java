package me.cortex.voxy.client.mixin.minecraft.util;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.VkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the active {@link VulkanDevice} as soon as Minecraft initializes the Vulkan backend.
 * Runs on the render thread during startup, before {@code RenderSystem.initRenderer} returns,
 * so {@link VoxyClient#initVoxyClient()} can rely on {@link VkContext} being populated.
 */
@Mixin(VulkanDevice.class)
public class MixinVulkanDevice {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$capture(CallbackInfo ci) {
        VkContext.INSTANCE.capture((VulkanDevice) (Object) this);
    }
}
