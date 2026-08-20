package me.cortex.voxy.client.mixin.minecraft.session;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    /**
     * Lunar/Ichor-safe fallback: {@code Minecraft.<init>} has finished backend selection, so
     * {@link RenderSystem#getDevice()} is populated even if blaze3d mixins were skipped.
     */
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void voxy$bootstrapRenderer(CallbackInfo ci) {
        try {
            VoxyClient.bootstrapRenderer(RenderSystem.tryGetDevice());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"))
    private void voxy$injectWorldClose(CallbackInfo ci) {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
