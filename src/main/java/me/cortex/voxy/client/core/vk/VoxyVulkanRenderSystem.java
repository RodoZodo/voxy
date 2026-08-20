package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.client.mixin.minecraft.vulkan.VulkanRenderPassAccessor;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/**
 * The Voxy Vulkan render system (Phase 2: GPU data path). Owns Voxy's Vulkan pipelines, the
 * upload/download streams, the HiZ buffer and the shared index buffers, and records Voxy's work
 * into Minecraft's frame at the three splice points.
 *
 * <p>Deliberately does NOT use Minecraft's {@code RenderPipeline}/{@code BindGroupLayout}
 * abstraction: shader-pack integration is deferred until Aperture (Iris' Vulkan successor)
 * ships, so Voxy's shaders go through a standalone shaderc -> SPIR-V path with push
 * descriptors, recorded on Minecraft's shared primary command buffer.
 */
public final class VoxyVulkanRenderSystem {
    public static final VoxyVulkanRenderSystem INSTANCE = new VoxyVulkanRenderSystem();

    /** Pass labels Voxy splices into (vanilla chunk renderer + Sodium Vulkan). */
    private static final String TERRAIN_PASS_PREFIX = "Section layers for ";
    private static final String SODIUM_TERRAIN_PASS = "Terrain";

    private VkDevice device;
    private VkRenderProperties properties;
    private VkShaderCompiler compiler;

    //Graphics: demo quad
    private VkPipelineLayout graphicsLayout;
    private long graphicsPipeline;
    private VkBuffer quadVertexBuffer;
    private VkSharedIndexBuffer sharedIndices;
    private boolean vertexDataReady;
    private boolean initialUploadPending = true;

    //Compute: counter demo
    private VkPipelineLayout computeLayout;
    private long computePipeline;
    private VkBuffer counterBuffer;
    private long counterFrames;
    private boolean counterReadbackLogged;

    //Data path
    private VkUploadStream uploadStream;
    private VkDownloadStream downloadStream;
    private VkHiZBuffer hiZ;
    private VkLodGenerator lodGen;
    private VkModelTables modelTables;
    private VkMeshGenerator meshGen;
    private volatile VkShaderModule pendingMeshSpirv;
    private volatile boolean meshCompileFailed;

    //Octree + traversal (created when a world engine comes up)
    private AsyncNodeManager nodeManager;
    private VkNodeCleaner nodeCleaner;
    private VkTraverser traverser;
    private VkSectionGeometryData geometryData;
    private VkSectionRenderer sectionRenderer;
    private boolean activeHalf;
    private volatile ModelFactory modelFactory;
    private volatile GpuMeshService meshService;

    //Frame state
    private boolean terrainPassActive;
    private VulkanGpuTextureView terrainDepthView;

    private boolean initialized;

    private VoxyVulkanRenderSystem() {
    }

    /** Create the render system. Must be called on the render thread after the device is up. */
    public void init() {
        if (this.initialized) {
            return;
        }
        var ctx = VkContext.INSTANCE;
        this.device = ctx.vkDevice();
        this.properties = VkRenderProperties.get();
        this.compiler = new VkShaderCompiler();
        try {
            Logger.info("Voxy (Vulkan): init streams");
            this.uploadStream = new VkUploadStream(this.device, ctx.vmaAllocator());
            this.downloadStream = new VkDownloadStream(this.device, ctx.vmaAllocator());

            //Graphics path
            Logger.info("Voxy (Vulkan): init demo graphics pipeline");
            var vert = this.compileStage("demo.vert", VkShaderStage.VERTEX);
            var frag = this.compileStage("demo.frag", VkShaderStage.FRAGMENT);
            this.graphicsLayout = new VkPipelineLayout(this.device,
                    new VkPipelineLayout.Binding[]{new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT)},
                    true);
            this.graphicsPipeline = VkPipelineBuilder.createGraphics(this.device, this.graphicsLayout,
                    vert, frag,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
                    VkPipelineBuilder.VertexInput.float3(0, 0, 12),
                    this.properties.closerEqualDepthCompare(), true, true);
            vert.free(this.device);
            frag.free(this.device);

            //Compute path (demo counter)
            Logger.info("Voxy (Vulkan): init demo compute pipeline");
            var compute = this.compileStage("demo.comp", VkShaderStage.COMPUTE);
            this.computeLayout = new VkPipelineLayout(this.device,
                    new VkPipelineLayout.Binding[]{new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT)},
                    true);
            this.computePipeline = VkPipelineBuilder.createCompute(this.device, this.computeLayout, compute);
            compute.free(this.device);

            //A quad in view space (camera looks down -Z): the "hello Vulkan" draw.
            this.quadVertexBuffer = VkBuffer.deviceLocal(ctx.vmaAllocator(), 4L * 3 * 4,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.sharedIndices = new VkSharedIndexBuffer(ctx.vmaAllocator(), this.uploadStream);
            this.uploadStream.upload(quadVertices(), this.quadVertexBuffer, 0);

            this.counterBuffer = VkBuffer.deviceLocal(ctx.vmaAllocator(), 4,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

            Logger.info("Voxy (Vulkan): init HiZ");
            this.hiZ = new VkHiZBuffer(this.device, this.compiler);
            Logger.info("Voxy (Vulkan): init LOD generator");
            this.lodGen = new VkLodGenerator(this.device, this.compiler, this.uploadStream, this.downloadStream, ctx.vmaAllocator());
            //Route LOD (voxel mip) generation through the GPU when supported
            WorldVoxilizedSectionMipper.setMipDispatcher((section, world, mapper, onDone) ->
                    this.lodGen != null && this.lodGen.isGpuSupported() && this.lodGen.submit(section, world, mapper, onDone));

            //GPU model tables only here. mesh.comp is huge (int64 greedy mesher) and compiling it
            // on the render thread freezes the Minecraft loading overlay, then Lunar/TDR kills the process.
            Logger.info("Voxy (Vulkan): init model tables");
            this.modelTables = new VkModelTables(ctx.vmaAllocator());

            //The vertex/index data sits in the upload stream's staging ring; it is copied into
            //the device-local buffers at the next frame's splice. The frame after that executes
            //after the copy (same queue, in-order), so the demo draw only starts then.
            this.initialUploadPending = true;

            this.initialized = true;
            this.startDeferredMeshCompile();
        } catch (Throwable e) {
            this.free();
            throw e;
        }
    }

    /** shaderc of lod/mesh.comp on a daemon thread so the title screen can present. */
    private void startDeferredMeshCompile() {
        Thread t = new Thread(() -> {
            try {
                Logger.info("Voxy (Vulkan): compiling mesh.comp off render thread");
                var module = VkMeshGenerator.compileMeshSpirv();
                if (!this.initialized) {
                    module.free(null);
                    return;
                }
                this.pendingMeshSpirv = module;
                Logger.info("Voxy (Vulkan): mesh.comp SPIR-V ready");
            } catch (Throwable e) {
                this.meshCompileFailed = true;
                Logger.warn("Voxy (Vulkan): mesh.comp compile failed, GPU meshing disabled", e);
            }
        }, "voxy-mesh-compile");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Create the GPU mesh pipeline on the render thread once background SPIR-V is ready.
     * Safe to call every tick; never throws.
     */
    public void pollDeferredMeshGenerator() {
        if (!this.initialized || this.meshGen != null || this.meshCompileFailed) {
            return;
        }
        var module = this.pendingMeshSpirv;
        if (module == null) {
            return;
        }
        // Pipeline create can stall the NVIDIA driver; do not do it on the title/loading screen.
        try {
            var mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        this.pendingMeshSpirv = null;
        try {
            Logger.info("Voxy (Vulkan): creating GPU mesh pipeline");
            this.meshGen = new VkMeshGenerator(this.device, module, this.uploadStream, this.modelTables,
                    VkContext.INSTANCE.vmaAllocator());
            Logger.info("Voxy (Vulkan): GPU mesh generator ready");
        } catch (Throwable t) {
            this.meshCompileFailed = true;
            Logger.warn("Voxy (Vulkan): GPU mesh pipeline failed, ingest still runs", t);
            try {
                module.free(this.device);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public String describe() {
        if (!this.initialized) {
            return "uninitialized";
        }
        String hiz = this.hiZ == null || this.hiZ.getWidth() == 0 ? "none" : this.hiZ.getWidth() + "x" + this.hiZ.getHeight() + " (" + this.hiZ.getPackedLevels() + ")";
        String traversal = this.traverser == null ? "idle" : "TLN#" + this.traverser.getTopNodeCount() + " half=" + (this.activeHalf ? 1 : 0);
        return "Vulkan " + this.properties + ", upload=" + (this.uploadStream.getAllocOffset() >> 10) + "KiB, HiZ=" + hiz + ", " + traversal;
    }

    public VkMeshGenerator getMeshGenerator() {
        return this.meshGen;
    }

    public VkSharedIndexBuffer getSharedIndices() {
        return this.sharedIndices;
    }

    public void setModelFactory(ModelFactory factory) {
        this.modelFactory = factory;
    }

    public void clearModelFactory() {
        this.modelFactory = null;
    }

    public void setMeshService(GpuMeshService service) {
        this.meshService = service;
    }

    public void clearMeshService() {
        if (this.meshService != null) {
            this.meshService.clear();
            this.meshService = null;
        }
    }

    /**
     * Debug hook: seed synthetic model tables + a solid 32^3 cube and push it through the GPU
     * mesher. The result (quads/AABB) is logged by the generator's readback.
     */
    public void meshTest() {
        if (this.modelTables == null || this.meshGen == null || !this.meshGen.isGpuSupported()) {
            Logger.warn("Voxy (Vulkan): GPU mesher unavailable (shaderInt64 required)");
            return;
        }
        long face = 0xFL;//occludes | coversFullBlock | canBeOccluded | usesSelfLighting
        long metadata = 1L << 54;//isFullyOpaque
        for (int f = 0; f < 6; f++) {
            metadata |= face << (8 * f);
        }
        this.modelTables.update(1, 1, metadata);//blockId 1 -> modelId 1 (fully opaque cube)
        this.modelTables.update(0, 0, 0);//air model

        long[] raw = new long[32768];
        java.util.Arrays.fill(raw, 1L << 27);//solid 32^3 of blockId 1
        long[] neighbors = new long[6144];//all air
        this.meshGen.submit(0, (byte) 0, raw, neighbors);
        Logger.info("Voxy (Vulkan): GPU mesh test queued (solid 32^3 cube)");
    }

    /** Attach the octree node manager (created when a world engine comes up). Runs on the render thread. */
    public void setNodeManager(AsyncNodeManager nodeManager, int maxSections, long geometryCapacity) {
        if (RenderSystem.isOnRenderThread()) {
            this.setNodeManagerOnRenderThread(nodeManager, maxSections, geometryCapacity);
        } else {
            RenderSystem.queueFencedTask(() -> this.setNodeManagerOnRenderThread(nodeManager, maxSections, geometryCapacity));
        }
    }

    private void setNodeManagerOnRenderThread(AsyncNodeManager nodeManager, int maxSections, long geometryCapacity) {
        if (this.nodeManager != null) {
            this.clearNodeManagerOnRenderThread();
        }
        this.nodeManager = nodeManager;
        this.activeHalf = false;
        this.geometryData = new VkSectionGeometryData(VkContext.INSTANCE.vmaAllocator(), maxSections, geometryCapacity);
        this.nodeCleaner = new VkNodeCleaner(this.device, this.compiler, nodeManager, this.uploadStream, this.downloadStream, VkContext.INSTANCE.vmaAllocator());
        this.traverser = new VkTraverser(this.device, this.compiler, nodeManager, this.uploadStream, this.downloadStream,
                VkContext.INSTANCE.vmaAllocator(), this.properties.isReverseZ());
        this.sectionRenderer = new VkSectionRenderer(this.device, VkContext.INSTANCE.vmaAllocator(), this.compiler);
        if (this.meshGen != null) {
            this.meshGen.setNodeManager(nodeManager);
        }
    }

    /** Detach + free the traversal resources (world engine torn down). Runs on the render thread. */
    public void clearNodeManager() {
        if (RenderSystem.isOnRenderThread()) {
            this.clearNodeManagerOnRenderThread();
        } else {
            RenderSystem.queueFencedTask(this::clearNodeManagerOnRenderThread);
        }
    }

    private void clearNodeManagerOnRenderThread() {
        if (this.sectionRenderer != null) {
            this.sectionRenderer.close();
            this.sectionRenderer = null;
        }
        if (this.traverser != null) {
            this.traverser.close();
            this.traverser = null;
        }
        if (this.nodeCleaner != null) {
            this.nodeCleaner.close();
            this.nodeCleaner = null;
        }
        if (this.geometryData != null) {
            this.geometryData.close();
            this.geometryData = null;
        }
        this.nodeManager = null;
    }

    // ---------------------------------------------------------------- splice points

    public void onRenderPassCreated(VulkanRenderPass pass, RenderPassDescriptor descriptor) {
        if (!this.initialized) {
            return;
        }
        if (!isTerrainPass(pass)) {
            return;
        }
        this.terrainPassActive = true;
        var depth = descriptor.depthAttachment();
        if (depth == null || depth.textureView() == null) {
            this.terrainDepthView = null;
        } else {
            this.terrainDepthView = (VulkanGpuTextureView) depth.textureView();
        }
    }

    /**
     * Splice point 1: inside the still-open terrain render pass, after vanilla's chunk draws.
     * Records Voxy's demo draw, depth-tested against vanilla's D32 depth.
     */
    public void onRenderPassSubmit(VulkanRenderPass pass) {
        if (!this.initialized || !this.vertexDataReady) {
            return;
        }
        if (!isTerrainPass(pass)) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return;
        }
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!camera.initialized) {
            return;
        }

        var cb = ((VulkanRenderPassAccessor) (Object) pass).voxy$invokeCommandBuffer();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        this.recordDemo(cb, camera, width, height);

        // VDIC renderer: opaque + translucent LOD terrain (straight alpha blend for translucent, cull stubbed all-visible)
        if (this.sectionRenderer != null) {
            try {
                int w = mc.getWindow().getWidth();
                int h = mc.getWindow().getHeight();
                if (w > 0 && h > 0) {
                    this.sectionRenderer.renderOpaque(cb, w, h);
                    this.sectionRenderer.renderTranslucent(cb, w, h);
                }
            } catch (Exception e) {
                // Renderer not yet fully wired — skip this frame
            }
        }
    }

    /**
     * Splice point 2: between render passes (the terrain pass just ended, the primary command
     * buffer is still open). The frame's Voxy work goes here, in dependency order:
     *
     * <ol>
     *   <li>HiZ build (reads the depth the terrain pass just wrote)</li>
     *   <li>{@code AsyncNodeManager.tick}: stage node updates (inactive half) + cleaner ids</li>
     *   <li>cleaner tick: sorter/transformer + remove-batch readback (reads last frame's nodes)</li>
     *   <li>stage top-node ids + GPU mip bases</li>
     *   <li>upload stream commit: copies node updates, cleaner ids, top-node ids, mip bases</li>
     *   <li>cleaner id clears (need the committed id list)</li>
     *   <li>traversal dispatches (read committed node data + HiZ)</li>
     *   <li>mip dispatches + readbacks (read committed mip bases)</li>
     *   <li>download stream commit: request/stats/remove readbacks</li>
     *   <li>toggle the active node-buffer half for next frame</li>
     * </ol>
     */
    public void onRenderPassEnded(VkCommandBuffer cb) {
        if (!this.initialized) {
            return;
        }
        if (!this.terrainPassActive) {
            return;
        }
        this.terrainPassActive = false;

        if (this.nodeManager != null && this.traverser != null && this.nodeCleaner != null) {
            this.traverser.ensureFilled(cb);
            this.nodeCleaner.ensureFilled(cb);
        }

        var mc = Minecraft.getInstance();
        if (mc.gameRenderer != null) {
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            if (this.hiZ != null && this.terrainDepthView != null && w > 0 && h > 0) {
                this.hiZ.build(cb, this.terrainDepthView.vkImageView(), w, h);
            }
        }

        //Pre-commit: stage all uploads + non-data-dependent compute
        //Model tables: sync from factory (CPU copy) so the upcoming upload has fresh data
        if (this.modelFactory != null && this.modelTables != null) {
            this.modelTables.syncFromFactory(this.modelFactory);
            // Atlas upload needs the command buffer (buffer->image) — use the cb overload when available
            if (cb != null) {
                this.modelFactory.processUploads(cb);
            } else {
                this.modelFactory.processUploads();
            }
        }
        //Mesh service: turn queued section positions into GPU mesh jobs (pre-flight + submit)
        if (this.meshService != null) {
            this.meshService.drain();
        }
        long activeHalfOffset = this.activeHalf ? this.halfNodeCount() : 0;
        if (this.nodeManager != null && this.traverser != null && this.nodeCleaner != null) {
            this.nodeManager.tick(this.traverser.nodeBuffer(), this.nodeCleaner, this.uploadStream,
                    this.halfNodeCount(), this.activeHalf, this.geometryData);
            this.nodeCleaner.tick(cb, this.traverser.nodeBuffer(), activeHalfOffset);
            this.traverser.stage();
        }
        if (this.lodGen != null) {
            this.lodGen.stage();
        }
        if (this.meshGen != null) {
            this.meshGen.stage();
        }

        //Commit all staged uploads
        this.uploadStream.commit(cb);
        if (this.modelTables != null) {
            this.modelTables.upload(cb);
        }

        //Post-commit: dispatches that depend on the committed data
        if (this.nodeManager != null && this.traverser != null && this.nodeCleaner != null) {
            this.nodeCleaner.dispatchIdUpdates(cb);
            var camera = this.currentCamera();
            if (camera != null && this.hiZ != null && this.hiZ.texture() != null) {
                int w = mc.getWindow().getWidth();
                int h = mc.getWindow().getHeight();
                if (w > 0 && h > 0) {
                    this.traverser.dispatch(cb, this.hiZ.texture(), camera, w, h,
                            this.nodeCleaner.getVisibilityId(), activeHalfOffset, this.nodeCleaner.visibilityBuffer());
                }
            }
            // VDIC renderer: build draw calls from the traversal's render list (opaque, cull stubbed)
            if (this.sectionRenderer != null && this.traverser != null && this.geometryData != null && camera != null) {
                try {
                    var mvp = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
                    var basePos = new Vector3i((int) (camera.pos.x) >> 5, (int) (camera.pos.y) >> 5, (int) (camera.pos.z) >> 5);
                    var camSub = new org.joml.Vector3f((float) (camera.pos.x - (basePos.x << 5)), (float) (camera.pos.y - (basePos.y << 5)), (float) (camera.pos.z - (basePos.z << 5)));
                    this.sectionRenderer.buildDrawCalls(cb, this.traverser.getRenderList(), this.nodeCleaner.visibilityBuffer(), this.geometryData, mvp, basePos, this.nodeCleaner.getVisibilityId(), camSub);
                } catch (Exception e) {
                    // Section renderer not yet fully wired (e.g. missing geometry) — skip this frame
                }
            }
        }
        if (this.lodGen != null) {
            this.lodGen.dispatch(cb);
        }
        if (this.meshGen != null) {
            this.meshGen.dispatch(cb);
        }

        this.counterTick(cb);
        this.downloadStream.commit(cb);

        if (this.nodeManager != null) {
            this.activeHalf = !this.activeHalf;
        }

        if (this.initialUploadPending) {
            this.initialUploadPending = false;
            this.vertexDataReady = true;
        }
    }

    private CameraRenderState currentCamera() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        return camera.initialized ? camera : null;
    }

    private int halfNodeCount() {
        return this.nodeManager == null ? 0 : this.nodeManager.maxNodeCount;
    }

    /** Splice point 3: end-of-frame catch-all (e.g. frames without a terrain pass). */
    public void onFrameSubmit(VkCommandBuffer cb) {
        if (!this.initialized) {
            return;
        }
        this.uploadStream.commit(cb);
        this.downloadStream.commit(cb);
    }

    // ---------------------------------------------------------------- demo draw

    private void recordDemo(VkCommandBuffer cb, CameraRenderState camera, int width, int height) {
        var mvp = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);

        GpuBufferSlice ubo;
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(64);
            mvp.get(data);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            ubo = encoder.transientMemory().uploadStaging(data, 16L, GpuBuffer.USAGE_UNIFORM);
        }
        long uboHandle = ((VulkanGpuBuffer) ubo.buffer()).vkBuffer();
        long uboOffset = ubo.offset();

        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsPipeline);

            var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(uboHandle);
            bufferInfo.get(0).offset(uboOffset);
            bufferInfo.get(0).range(ubo.length());
            var write = VkWriteDescriptorSet.calloc(1, stack);
            write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            write.dstSet(0L);
            write.dstBinding(0);
            write.descriptorCount(1);
            write.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            write.pBufferInfo(bufferInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsLayout.handle(), 0, write);

            var vp = VkViewport.calloc(1, stack);
            vp.get(0).x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cb, 0, vp);

            var sc = VkRect2D.calloc(1, stack);
            sc.get(0).offset().set(0, 0);
            sc.get(0).extent().set(width, height);
            vkCmdSetScissor(cb, 0, sc);

            var pBuffers = stack.longs(this.quadVertexBuffer.handle());
            var pOffsets = stack.longs(0L);
            vkCmdBindVertexBuffers(cb, 0, pBuffers, pOffsets);
            vkCmdBindIndexBuffer(cb, this.sharedIndices.quad.handle(), 0, VK_INDEX_TYPE_UINT16);

            vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------- counter demo

    private void counterTick(VkCommandBuffer cb) {
        this.counterFrames++;
        if (this.counterFrames % 120 != 0) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            this.counterBuffer.fill(cb, 0, 4, 0);
            VkSync.memoryBarrier(cb);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);
            var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(this.counterBuffer.handle()).offset(0).range(4);
            var write = VkWriteDescriptorSet.calloc(1, stack);
            write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            write.dstSet(0L);
            write.dstBinding(0);
            write.descriptorCount(1);
            write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            write.pBufferInfo(bufferInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.computeLayout.handle(), 0, write);

            vkCmdDispatch(cb, 1, 1, 1);
            VkSync.memoryBarrier(cb);

            this.downloadStream.download(this.counterBuffer, 0, 4, buf -> {
                int count = buf.getInt(0);
                Logger.info("Voxy (Vulkan): compute counter readback = " + count + " (expected 64)");
                this.counterReadbackLogged = true;
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean isTerrainPass(VulkanRenderPass pass) {
        var label = pass.getLabel();
        if (label == null) {
            return false;
        }
        String name = label.get();
        if (name == null) {
            return false;
        }
        return name.startsWith(TERRAIN_PASS_PREFIX) || name.equals(SODIUM_TERRAIN_PASS);
    }

    private VkShaderModule compileStage(String file, VkShaderStage stage) {
        var src = VkShaderCompiler.loadResource(file);
        return this.compiler.compile("voxy:" + file, src, stage);
    }

    private static ByteBuffer quadVertices() {
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.malloc(4 * 3 * 4);
            buf.putFloat(-0.9f).putFloat(-0.7f).putFloat(-3.0f);
            buf.putFloat(0.9f).putFloat(-0.7f).putFloat(-3.0f);
            buf.putFloat(-0.9f).putFloat(0.7f).putFloat(-3.0f);
            buf.putFloat(0.9f).putFloat(0.7f).putFloat(-3.0f);
            buf.flip();
            var copy = org.lwjgl.system.MemoryUtil.memCalloc(buf.remaining());
            copy.put(buf);
            copy.flip();
            return copy;
        }
    }

    public void free() {
        if (!this.initialized && this.device == null) {
            return;
        }
        WorldVoxilizedSectionMipper.setMipDispatcher(null);
        this.clearMeshService();
        this.clearModelFactory();
        this.clearNodeManager();
        if (this.downloadStream != null) {
            this.downloadStream.flushWaitClear();
        }
        if (this.pendingMeshSpirv != null) {
            try {
                this.pendingMeshSpirv.free(this.device);
            } catch (Throwable ignored) {
            }
            this.pendingMeshSpirv = null;
        }
        if (this.meshGen != null) {
            this.meshGen.close();
            this.meshGen = null;
        }
        if (this.modelTables != null) {
            this.modelTables.close();
            this.modelTables = null;
        }
        if (this.lodGen != null) {
            this.lodGen.close();
            this.lodGen = null;
        }
        if (this.hiZ != null) {
            this.hiZ.close();
            this.hiZ = null;
        }
        if (this.sharedIndices != null) {
            this.sharedIndices.close();
            this.sharedIndices = null;
        }
        if (this.quadVertexBuffer != null) {
            this.quadVertexBuffer.close();
            this.quadVertexBuffer = null;
        }
        if (this.counterBuffer != null) {
            this.counterBuffer.close();
            this.counterBuffer = null;
        }
        if (this.graphicsPipeline != 0) {
            vkDestroyPipeline(this.device, this.graphicsPipeline, null);
            this.graphicsPipeline = 0;
        }
        if (this.computePipeline != 0) {
            vkDestroyPipeline(this.device, this.computePipeline, null);
            this.computePipeline = 0;
        }
        if (this.graphicsLayout != null) {
            this.graphicsLayout.close();
            this.graphicsLayout = null;
        }
        if (this.computeLayout != null) {
            this.computeLayout.close();
            this.computeLayout = null;
        }
        if (this.uploadStream != null) {
            this.uploadStream.close();
            this.uploadStream = null;
        }
        if (this.downloadStream != null) {
            this.downloadStream.close();
            this.downloadStream = null;
        }
        if (this.compiler != null) {
            this.compiler.close();
            this.compiler = null;
        }
        this.device = null;
        this.initialized = false;
        Logger.info("Voxy (Vulkan): render system freed");
    }
}
