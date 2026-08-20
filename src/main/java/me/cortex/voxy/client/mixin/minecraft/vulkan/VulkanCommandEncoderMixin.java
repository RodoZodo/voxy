package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Splices Voxy's work into Minecraft's frame recording (one primary command buffer per frame,
 * dynamic rendering, no secondary command buffers). Three splice points:
 *
 * <ol>
 *   <li>{@code submitRenderPass} HEAD - inside the still-open pass: Voxy's draws.</li>
 *   <li>{@code submitRenderPass} RETURN - between passes (the pass has ended, the command
 *       buffer is still open): compute dispatches (HiZ) and buffer copies (upload/download
 *       streams).</li>
 *   <li>{@code submit} HEAD - end of frame catch-all: any pending stream work.</li>
 * </ol>
 */
@Pseudo
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public abstract class VulkanCommandEncoderMixin {
    @Shadow
    @Nullable
    private VulkanRenderPass currentRenderPass;

    @Inject(method = "createRenderPass", at = @At("RETURN"), require = 0)
    private void voxy$onCreateRenderPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPassBackend> cir) {
        try {
            VoxyVulkanRenderSystem.INSTANCE.onRenderPassCreated((VulkanRenderPass) cir.getReturnValue(), descriptor);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "submitRenderPass", at = @At("HEAD"), require = 0)
    private void voxy$onSubmitRenderPass(CallbackInfo ci) {
        try {
            var pass = this.currentRenderPass;
            if (pass == null) {
                return;
            }
            VoxyVulkanRenderSystem.INSTANCE.onRenderPassSubmit(pass);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "submitRenderPass", at = @At("RETURN"), require = 0)
    private void voxy$onRenderPassEnded(CallbackInfo ci) {
        try {
            VoxyVulkanRenderSystem.INSTANCE.onRenderPassEnded(((VulkanCommandEncoderAccessor) (Object) this).voxy$invokeCommandBuffer());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void voxy$onFrameSubmit(CallbackInfo ci) {
        try {
            VoxyVulkanRenderSystem.INSTANCE.onFrameSubmit(((VulkanCommandEncoderAccessor) (Object) this).voxy$invokeCommandBuffer());
        } catch (Throwable ignored) {
        }
    }
}
