package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.List;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;
    private AsyncNodeManager nodeManager;
    private ModelBakerySubsystem modelBakery;
    private RenderGenerationService renderGen;

    public VoxyClientInstance() {
        {
            //TODO(vulkan): flashback replay storage integration was removed with the GL renderer
            var basePath = this.basePath = getBasePath().normalize();
            this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, basePath);
        }
        super();
        this.updateDedicatedThreads();
    }

    @Override
    protected boolean shouldCreateInstance() {
        return !this.config.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        this.setNumThreads(VoxyConfig.CONFIG.serviceThreads);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        try {
            return this.config.sectionStorageConfig.build(ctx);
        } catch (Throwable t) {
            Logger.error("Voxy: configured storage failed; using in-memory fallback (LoDs will not persist this session)", t);
            return new SectionSerializationStorage(new MemoryStorageBackend());
        }
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    protected void onWorldEngineCreated(WorldEngine world) {
        super.onWorldEngineCreated(world);
        if (this.nodeManager != null) {
            this.teardownNodeManager();
        }
        var rs = VoxyVulkanRenderSystem.INSTANCE;
        if (!rs.isInitialized()) {
            Logger.info("Voxy: world engine created without GPU renderer (ingest/save still active)");
            return;
        }
        Logger.info("Voxy: attaching CPU mesher + Vulkan LoD renderer (LoDs meshed on CPU workers, drawn on GPU)");
        this.modelBakery = new ModelBakerySubsystem(world.getMapper());
        try {
            var mapper = world.getMapper();
            for (var entry : mapper.getBiomeEntries()) {
                this.modelBakery.addBiome(entry);
            }
            mapper.setBiomeCallback(this.modelBakery::addBiome);
        } catch (Exception e) {
            Logger.warn("Failed to seed biomes for model bakery", e);
        }

        // 1GiB geometry + 1M sections crashed join on 8GB NVIDIA; keep a playable cap.
        int maxSections = 1 << 18;
        long geometryCapacity = 256L << 20;
        var geometryManager = new BasicAsyncGeometryManager(maxSections, geometryCapacity);
        this.renderGen = new RenderGenerationService(world, this.modelBakery, this.getServiceManager(), false);
        this.nodeManager = new AsyncNodeManager(1 << 20, geometryManager, this.renderGen::enqueueTask);
        this.renderGen.setResultConsumer(this.nodeManager::submitGeometryResult);
        world.setDirtyCallback(this.nodeManager::worldEvent);
        rs.setModelFactory(this.modelBakery.factory);
        rs.setModelBakery(this.modelBakery);
        rs.setNodeManager(this.nodeManager, maxSections, geometryCapacity);
        this.nodeManager.start();
        Logger.info("Voxy: LoD renderer attached");
    }

    public AsyncNodeManager getNodeManager() {
        return this.nodeManager;
    }

    /** If the world engine started before Vulkan pipelines, wire LoDs now. */
    public void tryAttachRenderer() {
        if (this.nodeManager != null) {
            return;
        }
        var rs = VoxyVulkanRenderSystem.INSTANCE;
        if (!rs.isInitialized()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var engine = WorldIdentifier.ofEngine(level);
        if (engine == null) {
            return;
        }
        Logger.info("Voxy: attaching LoD renderer to existing world engine");
        this.onWorldEngineCreated(engine);
    }

    public ModelBakerySubsystem getModelBakery() {
        return this.modelBakery;
    }

    public RenderGenerationService getRenderGen() {
        return this.renderGen;
    }

    @Override
    public void addDebug(List<String> debug) {
        super.addDebug(debug);
        if (this.nodeManager != null) {
            this.nodeManager.addDebug(debug);
        }
        if (this.modelBakery != null) {
            this.modelBakery.addDebugData(debug);
        }
        if (this.renderGen != null) {
            debug.add("MeshQ: " + this.renderGen.getTaskCount() + " genRev: " + (this.modelBakery != null ? this.modelBakery.factory.getBakeRevision() : 0));
        }
    }

    private void teardownNodeManager() {        if (this.nodeManager == null) {
            return;
        }
        var rs = VoxyVulkanRenderSystem.INSTANCE;
        rs.clearMeshService();
        rs.clearModelFactory();
        rs.clearNodeManager();
        this.nodeManager.stop();
        this.nodeManager = null;
        if (this.renderGen != null) {
            this.renderGen.shutdown();
            this.renderGen = null;
        }
        if (this.modelBakery != null) {
            this.modelBakery.shutdown();
            this.modelBakery = null;
        }
    }

    @Override
    public void shutdown() {
        this.teardownNodeManager();
        super.shutdown();
        //TODO(vulkan): render resource cache cleanup was removed with the GL renderer
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().gameMode;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.connection.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.ip.replace(":", "_"));
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
