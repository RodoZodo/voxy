package me.cortex.voxy.client.mixin.minecraft;


import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem {
    // Capture only. Pipeline init is deferred to Minecraft.tick so a failure cannot trip the crash ladder.
    @Inject(method = "initRenderer", order = 900, remap = false, at = @At("RETURN"), require = 0)
    private static void voxy$injectInit(GpuDevice device, CallbackInfo ci) {
        VoxyClient.captureRenderer(device);
    }
}
