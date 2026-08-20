package me.cortex.voxy.client.mixin.minecraft.session;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    /** After the window exists — never during {@code <init>}, which is on the crash-ladder path. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void voxy$deferredBootstrap(CallbackInfo ci) {
        VoxyClient.bootstrapRenderer(null);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"), require = 0)
    private void voxy$injectWorldClose(CallbackInfo ci) {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
