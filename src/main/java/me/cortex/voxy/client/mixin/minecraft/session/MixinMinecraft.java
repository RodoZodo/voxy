package me.cortex.voxy.client.mixin.minecraft.session;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    /**
     * Vanilla treats Lunar's unclean process exit as a graphics crash and writes Graphics API
     * to Default, then OpenGL. That makes Voxy (Vulkan-only draw path) a no-op every launch.
     * Skip those two {@code OptionInstance.set} calls. Must never throw: this is on {@code <init>}.
     */
    @Redirect(
            method = "<init>",
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=Detected unexpected shutdown during last game startup: resetting preferred graphics API to Default"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/blaze3d/systems/GpuBackend;")
            ),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;set(Ljava/lang/Object;)V"),
            require = 0
    )
    private void voxy$keepPreferredGraphicsApi(OptionInstance<Object> instance, Object value) {
        try {
            if (value == PreferredGraphicsApi.OPENGL || value == PreferredGraphicsApi.DEFAULT) {
                Logger.info("Voxy: skipping crash-ladder Graphics API reset to " + value);
                return;
            }
            instance.set(value);
        } catch (Throwable t) {
            try {
                instance.set(value);
            } catch (Throwable ignored) {
            }
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void voxy$afterInit(GameConfig config, CallbackInfo ci) {
        // Capture only. Pipeline init stays on tick.
        try {
            VoxyClient.captureRenderer(null);
        } catch (Throwable ignored) {
        }
    }

    /** After the window exists — never compile pipelines during {@code <init>}. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void voxy$deferredBootstrap(CallbackInfo ci) {
        try {
            VoxyClient.bootstrapRenderer(null);
            ClientSessionEvents.tick((Minecraft) (Object) this);
        } catch (Throwable t) {
            Logger.warn("Voxy: tick hook failed", t);
        }
    }

    @Inject(method = "setLevel", at = @At("TAIL"), require = 0)
    private void voxy$onSetLevel(ClientLevel level, CallbackInfo ci) {
        try {
            ClientSessionEvents.tick((Minecraft) (Object) this);
        } catch (Throwable t) {
            Logger.warn("Voxy: setLevel hook failed", t);
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"), require = 0)
    private void voxy$injectWorldClose3(CallbackInfo ci) {
        try {
            var mc = (Minecraft) (Object) this;
            if (ClientSessionEvents.inSession && mc.level == null) {
                ClientSessionEvents.sessionEnd();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"), require = 0)
    private void voxy$injectWorldClose2(Screen screen, boolean bl, CallbackInfo ci) {
        try {
            var mc = (Minecraft) (Object) this;
            if (ClientSessionEvents.inSession && mc.level == null) {
                ClientSessionEvents.sessionEnd();
            }
        } catch (Throwable ignored) {
        }
    }
}
