package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.vk.GpuMeshService;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
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
    private GpuMeshService meshService;

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
        return this.config.sectionStorageConfig.build(ctx);
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
        if (!rs.isInitialized() || rs.getMeshGenerator() == null) {
            Logger.info("Voxy: world engine created without GPU meshing (ingest/save still active)");
            return;
        }
        //Model baking subsystem (CPU) - provides idMappings/metadataCache for the GPU mesher
        this.modelBakery = new ModelBakerySubsystem(world.getMapper());
        //Seed biome entries and register callback for future biomes
        try {
            var mapper = world.getMapper();
            for (var entry : mapper.getBiomeEntries()) {
                this.modelBakery.addBiome(entry);
            }
            mapper.setBiomeCallback(this.modelBakery::addBiome);
        } catch (Exception e) {
            Logger.warn("Failed to seed biomes for model bakery", e);
        }

        //Section geometry data path: the CPU overlay manager feeds GPU uploads/metadata rewrites
        // which the render thread applies into the VkSectionGeometryData buffers.
        int maxSections = 1 << 20;
        long geometryCapacity = 1L << 30;
        var geometryManager = new BasicAsyncGeometryManager(maxSections, geometryCapacity);
        //GpuMeshService handles dedup/priority/pre-flight and feeds VkMeshGenerator
        this.meshService = new GpuMeshService(world, this.modelBakery.factory, this.modelBakery, rs.getMeshGenerator());
        this.nodeManager = new AsyncNodeManager(1 << 21, geometryManager, this.meshService::enqueue);
        //Wire the mesh generator's completion back to the node manager
        rs.getMeshGenerator().setNodeManager(this.nodeManager);
        world.setDirtyCallback(this.nodeManager::worldEvent);
        rs.setNodeManager(this.nodeManager, maxSections, geometryCapacity);
        rs.setModelFactory(this.modelBakery.factory);
        rs.setMeshService(this.meshService);
        this.nodeManager.start();
    }

    public AsyncNodeManager getNodeManager() {
        return this.nodeManager;
    }

    /** If the world engine started before Vulkan pipelines, wire GPU meshing now. */
    public void tryAttachRenderer() {
        if (this.nodeManager != null) {
            return;
        }
        var rs = VoxyVulkanRenderSystem.INSTANCE;
        if (!rs.isInitialized() || rs.getMeshGenerator() == null) {
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
        Logger.info("Voxy: attaching GPU meshing to existing world engine");
        this.onWorldEngineCreated(engine);
    }

    public ModelBakerySubsystem getModelBakery() {
        return this.modelBakery;
    }

    public GpuMeshService getMeshService() {
        return this.meshService;
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
        if (this.meshService != null) {
            debug.add("MeshQ: " + this.meshService.pendingCount() + " genRev: " + (this.modelBakery != null ? this.modelBakery.factory.getBakeRevision() : 0));
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
        if (this.meshService != null) {
            this.meshService.clear();
            this.meshService = null;
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
