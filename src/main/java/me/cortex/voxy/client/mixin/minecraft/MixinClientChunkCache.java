package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientChunkIngest;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = bobbyInstalled();

    @Unique
    private static boolean bobbyInstalled() {
        try {
            return FabricLoader.getInstance().isModLoaded("bobby");
        } catch (Throwable t) {
            return false;
        }
    }

    @Shadow
    private volatile ClientChunkCache.Storage storage;

    @Shadow
    public abstract LevelChunk getChunk(int x, int z, ChunkStatus status, boolean load);

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        //Verify that the position of the chunk is the same as the requested position
        if (chunk.getPos().x() == x && chunk.getPos().z() == z) {
            return chunk;//The chunk is at the requested position
        }
        //Otherwise return null
        return null;
    }

    @Inject(method = "drop", at = @At("HEAD"), require = 0)
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x(), pos.z());
            if (chunk != null) {
                ClientChunkIngest.noteAttempt(chunk);
            }
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), require = 0)
    private void voxy$ingestPacketChunk(CallbackInfoReturnable<LevelChunk> cir) {
        ClientChunkIngest.noteUpdate(cir.getReturnValue());
    }

    @Inject(method = "onLightUpdate", at = @At("TAIL"), require = 0)
    private void voxy$ingestOnLight(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var chunk = this.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        if (chunk != null) {
            ClientChunkIngest.noteAttempt(chunk);
        }
    }
}
