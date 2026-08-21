package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDispatchIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/**
 * Port of the GL-era {@code HierarchicalOcclusionTraverser}: the 5-iteration hierarchical
 * occlusion traversal that walks the CPU octree (node data on the GPU), culls against the
 * HiZ + frustum, and emits section render requests back to the {@link AsyncNodeManager}.
 *
 * <p>The node array is double-buffered (2 * maxNodeCount uvec4s); the traversal reads the
 * {@code activeHalf} (selected via the push-constant active-half offset), while node updates
 * staged by {@code AsyncNodeManager} land in the inactive half and become active next frame.
 *
 * <p>Split into {@link #stage} (before the upload stream commit) and {@link #dispatch} (after,
 * once the buffers the dispatches read have actually been copied to the device).
 */
public final class VkTraverser implements AutoCloseable {
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;
    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;
    private static final int LOCAL_WORK_SIZE = 1 << LOCAL_WORK_SIZE_BITS;
    private static final int UBO_SIZE = 256;
    private static final int REQUEST_BUFFER_BYTES = MAX_REQUEST_QUEUE_SIZE * 8 + 8;
    private static final int STATISTICS_BYTES = 1024;
    private static final int QUEUE_META_BYTES = 4 * 4 * MAX_ITERATIONS;

    private static final Field PLANES_FIELD;
    static {
        try {
            PLANES_FIELD = FrustumIntersection.class.getDeclaredField("planes");
            PLANES_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("FrustumIntersection.planes field not found", e);
        }
    }

    private final VkDevice device;
    private final AsyncNodeManager nodeManager;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final int halfNodeCount;
    private final VkSampler hizSampler;
    private final VkPipelineLayout layout;
    private final long pipeline;
    private final VkBuffer requestBuffer;
    private final VkBuffer nodeBuffer;
    private final VkBuffer uniformBuffer;
    private final VkBuffer statisticsBuffer;
    private final VkBuffer queueMetaBuffer;
    private final VkBuffer topNodeIds;
    private final VkBuffer scratchQueueA;
    private final VkBuffer scratchQueueB;
    private final VkBuffer renderList;
    private final boolean reverseZ;
    private boolean filled;

    private final Int2IntOpenHashMap topNode2idx = new Int2IntOpenHashMap();
    private final int[] idx2topNode = new int[MAX_QUEUE_SIZE];
    private int topNodeCount;
    private boolean topNodesDirty;
    private int meshTaskCount;

    public VkTraverser(VkDevice device, VkShaderCompiler compiler, AsyncNodeManager nodeManager,
                       VkUploadStream uploadStream, VkDownloadStream downloadStream, long vma, boolean reverseZ) {
        this.device = device;
        this.nodeManager = nodeManager;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.halfNodeCount = nodeManager.maxNodeCount;
        this.reverseZ = reverseZ;
        this.topNode2idx.defaultReturnValue(-1);
        this.hizSampler = new VkSampler(device);

        this.requestBuffer = VkBuffer.deviceLocal(vma, REQUEST_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.nodeBuffer = VkBuffer.deviceLocal(vma, 2L * this.halfNodeCount * 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.uniformBuffer = VkBuffer.hostVisible(vma, UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        this.statisticsBuffer = VkBuffer.deviceLocal(vma, STATISTICS_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.queueMetaBuffer = VkBuffer.hostVisible(vma, QUEUE_META_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | 0x00001000 /* VK_BUFFER_USAGE_INDIRECT_PARAMETERS_BIT (trimmed from LWJGL) */ | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.topNodeIds = VkBuffer.deviceLocal(vma, MAX_QUEUE_SIZE * 4L,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.scratchQueueA = VkBuffer.deviceLocal(vma, MAX_QUEUE_SIZE * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.scratchQueueB = VkBuffer.deviceLocal(vma, MAX_QUEUE_SIZE * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.renderList = VkBuffer.deviceLocal(vma, MAX_QUEUE_SIZE * 4L,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

        this.layout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(9, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true, new int[]{0, 8});

        var defines = new java.util.HashMap<String, String>();
        defines.put("MAX_ITERATIONS", Integer.toString(MAX_ITERATIONS));
        defines.put("LOCAL_SIZE_BITS", Integer.toString(LOCAL_WORK_SIZE_BITS));
        defines.put("MAX_REQUEST_QUEUE_SIZE", Integer.toString(MAX_REQUEST_QUEUE_SIZE));
        defines.put("HIZ_BINDING", "0");
        defines.put("SCENE_UNIFORM_BINDING", "1");
        defines.put("REQUEST_QUEUE_BINDING", "2");
        defines.put("RENDER_QUEUE_BINDING", "3");
        defines.put("NODE_DATA_BINDING", "4");
        defines.put("NODE_QUEUE_META_BINDING", "5");
        defines.put("NODE_QUEUE_SOURCE_BINDING", "6");
        defines.put("NODE_QUEUE_SINK_BINDING", "7");
        defines.put("RENDER_TRACKER_BINDING", "8");
        defines.put("STATISTICS_BUFFER_BINDING", "9");
        defines.put("HAS_STATISTICS", "");
        defines.put("USE_REVERSE_Z", "");
        defines.put("USE_ZERO_ONE_DEPTH", "");
        var stage = this.compileStage(compiler, "lod/hierarchical/traversal_dev.comp", defines);
        try {
            this.pipeline = VkPipelineBuilder.createCompute(device, this.layout, stage);
        } finally {
            stage.free(device);
        }

        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    /** Record the one-time buffer fills (node data = -1, scratch = 0). */
    public void ensureFilled(VkCommandBuffer cb) {
        if (this.filled) {
            return;
        }
        this.filled = true;
        this.nodeBuffer.fill(cb, 0, this.nodeBuffer.size(), -1);
        this.requestBuffer.fill(cb, 0, REQUEST_BUFFER_BYTES, 0);
        this.statisticsBuffer.fill(cb, 0, STATISTICS_BYTES, 0);
        this.queueMetaBuffer.fill(cb, 0, QUEUE_META_BYTES, 0);
        this.topNodeIds.fill(cb, 0, MAX_QUEUE_SIZE * 4L, 0);
        this.scratchQueueA.fill(cb, 0, MAX_QUEUE_SIZE * 4L, 0);
        this.scratchQueueB.fill(cb, 0, MAX_QUEUE_SIZE * 4L, 0);
        this.renderList.fill(cb, 0, MAX_QUEUE_SIZE * 4L, 0);
        VkSync.memoryBarrier(cb);
    }

    /**
     * Stage the top-level-node id list upload (the copy is recorded at the next commit).
     * Call before the upload stream commit.
     */
    public void stage() {
        if (!this.topNodesDirty || this.topNodeCount == 0) {
            this.topNodesDirty = false;
            return;
        }
        this.topNodesDirty = false;
        var bb = this.uploadStream.getWriteBuffer(this.topNodeIds, 0, this.topNodeCount * 4L).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < this.topNodeCount; i++) {
            bb.putInt(this.idx2topNode[i]);
        }
    }

    /**
     * Record the traversal dispatches + readbacks. Must run after the upload stream commit
     * (node data + top-node ids are committed by then) and after the HiZ is built.
     */
    public void dispatch(VkCommandBuffer cb, VkTexture hizTexture, CameraRenderState camera,
                         int width, int height, int frameId, long activeHalfOffset, VkBuffer renderTracker) {
        if (this.topNodeCount == 0) {
            return;
        }
        int firstDispatchSize = (this.topNodeCount + LOCAL_WORK_SIZE - 1) >> LOCAL_WORK_SIZE_BITS;
        if (firstDispatchSize == 0) {
            return;
        }

        //Scene uniform (host-visible write + barrier before the dispatches)
        this.writeUniform(camera, width, height, frameId, hizTexture.getWidth(), hizTexture.getHeight(),
                (int) (this.renderList.size() / 4 - 1));
        //Queue metadata (host-visible; feeds vkCmdDispatchIndirect)
        this.writeQueueMeta(firstDispatchSize);

        try (var stack = MemoryStack.stackPush()) {
            //Reset the request/render/statistics counters (filled values), then barrier
            this.requestBuffer.fill(cb, 0, 4, 0);
            this.renderList.fill(cb, 0, 4, 0);
            if (RenderStatistics.enabled) {
                this.statisticsBuffer.fill(cb, 0, STATISTICS_BYTES, 0);
            }
            VkSync.memoryBarrier(cb);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);

            var hizInfo = VkDescriptorImageInfo.calloc(1, stack);
            hizInfo.get(0).imageView(hizTexture.view(0)).sampler(this.hizSampler.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            var sceneInfo = VkDescriptorBufferInfo.calloc(1, stack);
            sceneInfo.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(UBO_SIZE);
            var reqInfo = VkDescriptorBufferInfo.calloc(1, stack);
            reqInfo.get(0).buffer(this.requestBuffer.handle()).offset(0).range(REQUEST_BUFFER_BYTES);
            var renderInfo = VkDescriptorBufferInfo.calloc(1, stack);
            renderInfo.get(0).buffer(this.renderList.handle()).offset(0).range(this.renderList.size());
            var nodeInfo = VkDescriptorBufferInfo.calloc(1, stack);
            nodeInfo.get(0).buffer(this.nodeBuffer.handle()).offset(activeHalfOffset * 16L).range(this.halfNodeCount * 16L);
            var metaInfo = VkDescriptorBufferInfo.calloc(1, stack);
            metaInfo.get(0).buffer(this.queueMetaBuffer.handle()).offset(0).range(QUEUE_META_BYTES);
            var trackInfo = VkDescriptorBufferInfo.calloc(1, stack);
            trackInfo.get(0).buffer(renderTracker.handle()).offset(0).range(renderTracker.size());
            var statsInfo = VkDescriptorBufferInfo.calloc(1, stack);
            statsInfo.get(0).buffer(this.statisticsBuffer.handle()).offset(0).range(STATISTICS_BYTES);

            //Iteration 0: direct dispatch from the top-node-id list into scratch B
            var srcInfo = VkDescriptorBufferInfo.calloc(1, stack);
            srcInfo.get(0).buffer(this.topNodeIds.handle()).offset(0).range(this.topNodeCount * 4L);
            var sinkInfo = VkDescriptorBufferInfo.calloc(1, stack);
            sinkInfo.get(0).buffer(this.scratchQueueB.handle()).offset(0).range(this.scratchQueueB.size());
            this.pushDescriptors(cb, stack, hizInfo, sceneInfo, reqInfo, renderInfo, nodeInfo, metaInfo, srcInfo, sinkInfo, trackInfo, statsInfo);
            vkCmdPushConstants(cb, this.layout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(0, (int) activeHalfOffset));
            vkCmdDispatch(cb, firstDispatchSize, 1, 1);
            VkSync.memoryBarrier(cb);

            //Iterations 1..4: indirect dispatch, A/B flip-flop
            for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
                boolean even = (iter & 1) == 0;
                VkBuffer src = even ? this.scratchQueueA : this.scratchQueueB;
                VkBuffer sink = even ? this.scratchQueueB : this.scratchQueueA;
                var iterSrc = VkDescriptorBufferInfo.calloc(1, stack);
                iterSrc.get(0).buffer(src.handle()).offset(0).range(src.size());
                var iterSink = VkDescriptorBufferInfo.calloc(1, stack);
                iterSink.get(0).buffer(sink.handle()).offset(0).range(sink.size());
                this.pushDescriptors(cb, stack, hizInfo, sceneInfo, reqInfo, renderInfo, nodeInfo, metaInfo, iterSrc, iterSink, trackInfo, statsInfo);
                vkCmdPushConstants(cb, this.layout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(iter, (int) activeHalfOffset));
                vkCmdDispatchIndirect(cb, this.queueMetaBuffer.handle(), iter * 16L);
                VkSync.memoryBarrier(cb);
            }

            //Read back the mesh requests for the CPU octree
            this.downloadStream.download(this.requestBuffer, 0, REQUEST_BUFFER_BYTES, readback -> {
                var rb = readback.order(ByteOrder.LITTLE_ENDIAN);
                int count = rb.getInt(0);
                if (count < 0 || count > 50000) {
                    return;
                }
                if (count > (REQUEST_BUFFER_BYTES >> 3) - 1) {
                    count = (REQUEST_BUFFER_BYTES >> 3) - 1;
                }
                if (count != 0) {
                    var mb = new MemoryBuffer(count * 8L + 8);
                    MemoryUtil.memCopy(MemoryUtil.memAddress(readback), mb.address, count * 8L + 8);
                    MemoryUtil.memPutInt(mb.address, count);
                    this.nodeManager.submitRequestBatch(mb);
                }
            });

            //Read back the traversal statistics when the F3 debug toggle is on
            if (RenderStatistics.enabled) {
                this.downloadStream.download(this.statisticsBuffer, 0, 2L * MAX_ITERATIONS * 4, readback -> {
                    var rb = readback.order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < MAX_ITERATIONS; i++) {
                        RenderStatistics.hierarchicalTraversalCounts[i] = rb.getInt(i * 4);
                    }
                    for (int i = 0; i < MAX_ITERATIONS; i++) {
                        RenderStatistics.hierarchicalRenderSections[i] = rb.getInt(MAX_ITERATIONS * 4 + i * 4);
                    }
                });
            }
        }
    }

    private void pushDescriptors(VkCommandBuffer cb, MemoryStack stack,
                                 VkDescriptorImageInfo.Buffer hizInfo, VkDescriptorBufferInfo.Buffer sceneInfo,
                                 VkDescriptorBufferInfo.Buffer reqInfo, VkDescriptorBufferInfo.Buffer renderInfo,
                                 VkDescriptorBufferInfo.Buffer nodeInfo, VkDescriptorBufferInfo.Buffer metaInfo,
                                 VkDescriptorBufferInfo.Buffer srcInfo, VkDescriptorBufferInfo.Buffer sinkInfo,
                                 VkDescriptorBufferInfo.Buffer trackInfo, VkDescriptorBufferInfo.Buffer statsInfo) {
        var writes = VkWriteDescriptorSet.calloc(10, stack);
        this.setImageWrite(writes.get(0), 0, hizInfo);
        this.setBufferWrite(writes.get(1), 1, sceneInfo);
        this.setBufferWrite(writes.get(2), 2, reqInfo);
        this.setBufferWrite(writes.get(3), 3, renderInfo);
        this.setBufferWrite(writes.get(4), 4, nodeInfo);
        this.setBufferWrite(writes.get(5), 5, metaInfo);
        this.setBufferWrite(writes.get(6), 6, srcInfo);
        this.setBufferWrite(writes.get(7), 7, sinkInfo);
        this.setBufferWrite(writes.get(8), 8, trackInfo);
        this.setBufferWrite(writes.get(9), 9, statsInfo);
        vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.layout.handle(), 0, writes);
    }

    // ------------------------------------------------------------------ CPU helpers

    private void writeUniform(CameraRenderState camera, int width, int height, int frameId,
                              int hizW, int hizH, int renderQueueMaxSize) {
        var mvp = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);

        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;
        int sx = Mth.floor(camX) >> 5;
        int sy = Mth.floor(camY) >> 5;
        int sz = Mth.floor(camZ) >> 5;

        var frustum = new FrustumIntersection();
        frustum.set(mvp, false);
        Vector4f[] planes;
        try {
            planes = (Vector4f[]) PLANES_FIELD.get(frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        float subDivisionSize = VoxyConfig.CONFIG.subDivisionSize;
        float minSSS = (subDivisionSize * subDivisionSize) / (float) (width * height);
        float renderDistance = (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance * 16 * 32, 2);

        //requestSize: full budget when the mesh pipeline is idle (no meshing this phase)
        final double targetCount = 4000;
        double fillness = Math.max(0, (targetCount - this.meshTaskCount) / targetCount);
        fillness = Math.pow(fillness, 2);
        int requestSize = (int) Math.ceil(fillness * MAX_REQUEST_QUEUE_SIZE);
        requestSize = Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize));

        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.calloc(UBO_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            mvp.get(buf);
            buf.putInt(64, sx);
            buf.putInt(68, sy);
            buf.putInt(72, sz);
            buf.putInt(76, (hizW << 16) | hizH);
            buf.putFloat(80, (float) (camX - (sx << 5)));
            buf.putFloat(84, (float) (camY - (sy << 5)));
            buf.putFloat(88, (float) (camZ - (sz << 5)));
            buf.putFloat(92, minSSS);
            for (int i = 0; i < 6; i++) {
                var p = planes[i];
                buf.putFloat(96 + i * 16, p.x);
                buf.putFloat(100 + i * 16, p.y);
                buf.putFloat(104 + i * 16, p.z);
                buf.putFloat(108 + i * 16, p.w);
            }
            buf.putInt(192, renderQueueMaxSize);
            buf.putInt(196, frameId);
            buf.putInt(200, requestSize);
            buf.putFloat(204, renderDistance);
            buf.position(0);//absolute writes leave the position elsewhere; copy the whole block
            this.uniformBuffer.write(buf);
        }
        //Host write -> compute read is made visible by the barrier the caller records
        // (before the first dispatch), alongside the counter fills.
    }

    private void writeQueueMeta(int firstDispatchSize) {
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.calloc(QUEUE_META_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(0, firstDispatchSize);
            buf.putInt(4, 1);
            buf.putInt(8, 1);
            buf.putInt(12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                int base = i * 16;
                buf.putInt(base, 0);
                buf.putInt(base + 4, 1);
                buf.putInt(base + 8, 1);
                buf.putInt(base + 12, 0);
            }
            buf.position(0);//absolute writes leave the position at 0, keep limit at capacity
            this.queueMetaBuffer.write(buf);
        }
        //Host write -> indirect-dispatch read is covered by the caller's pre-dispatch barrier.
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;
        if (this.topNodeCount > MAX_QUEUE_SIZE) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }
        if (this.topNode2idx.put(id, aid) != -1) {
            throw new IllegalStateException("Duplicate top level node");
        }
        this.idx2topNode[aid] = id;
        this.topNodesDirty = true;
    }

    private void remTLN(int id) {
        int idx = this.topNode2idx.remove(id);
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException("Removing unknown top level node");
        }
        if (idx == this.topNodeCount) {
            this.topNodesDirty = true;
            return;
        }
        int endTLNId = this.idx2topNode[this.topNodeCount];
        this.idx2topNode[idx] = endTLNId;
        if (this.topNode2idx.put(endTLNId, idx) == -1) {
            throw new IllegalStateException();
        }
        this.topNodesDirty = true;
    }

    public void setMeshTaskCount(int meshTaskCount) {
        this.meshTaskCount = meshTaskCount;
    }

    public VkBuffer nodeBuffer() {
        return this.nodeBuffer;
    }

    public VkBuffer getRenderList() {
        return this.renderList;
    }

    public int getTopNodeCount() {
        return this.topNodeCount;
    }

    public boolean isReverseZ() {
        return this.reverseZ;
    }

    private void setImageWrite(VkWriteDescriptorSet write, int binding, VkDescriptorImageInfo.Buffer image) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        write.pImageInfo(image);
    }

    private void setBufferWrite(VkWriteDescriptorSet write, int binding, VkDescriptorBufferInfo.Buffer buffer) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        write.pBufferInfo(buffer);
    }

    private VkShaderModule compileStage(VkShaderCompiler compiler, String file, Map<String, String> defines) {
        var src = VkShaderCompiler.loadResource(file);
        return compiler.compile("voxy:" + file, src, VkShaderStage.COMPUTE, defines);
    }

    @Override
    public void close() {
        vkDestroyPipeline(this.device, this.pipeline, null);
        this.layout.close();
        this.hizSampler.close();
        this.requestBuffer.close();
        this.nodeBuffer.close();
        this.uniformBuffer.close();
        this.statisticsBuffer.close();
        this.queueMetaBuffer.close();
        this.topNodeIds.close();
        this.scratchQueueA.close();
        this.scratchQueueB.close();
        this.renderList.close();
    }
}
