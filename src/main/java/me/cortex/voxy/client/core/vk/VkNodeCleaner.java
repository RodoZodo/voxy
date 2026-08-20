package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/**
 * Port of the GL-era {@code NodeCleaner}: computes the worst (least-recently-visible) section
 * candidates for geometry eviction, and applies node id visibility clears/resets.
 *
 * <p>Layout note: {@code visibilityBuffer} doubles as the traversal's render tracker
 * ({@code lastRenderFrame}); both the traversal and the cleaner write to it.
 *
 * <p>Split into a pre-commit phase ({@link #tick}, {@link #collectIds}) that only records work
 * into the streams, and {@link #dispatchIdUpdates} which must run after the upload stream commit
 * so the id-list data has actually been copied to the device.
 */
public final class VkNodeCleaner implements AutoCloseable {
    private static final int SORTING_WORKER_SIZE = 64;
    private static final int WORK_PER_THREAD = 8;
    public static final int OUTPUT_COUNT = 256;
    private static final long OUTPUT_BYTES = OUTPUT_COUNT * 4L + OUTPUT_COUNT * 8L;
    private static final long REMOVE_BATCH_BYTES = OUTPUT_COUNT * 8L;

    private final VkDevice device;
    private final AsyncNodeManager nodeManager;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final VkPipelineLayout sorterLayout;
    private final VkPipelineLayout transformerLayout;
    private final VkPipelineLayout batchClearLayout;
    private final long sorterPipeline;
    private final long transformerPipeline;
    private final long batchClearPipeline;
    private final VkBuffer visibilityBuffer;
    private final VkBuffer outputBuffer;
    private final VkBuffer idListBuffer;
    private final long idListCapacity;
    private boolean filled;
    private int pendingIdCount;
    private int visibilityId;

    public VkNodeCleaner(VkDevice device, VkShaderCompiler compiler, AsyncNodeManager nodeManager,
                         VkUploadStream uploadStream, VkDownloadStream downloadStream, long vma) {
        this.device = device;
        this.nodeManager = nodeManager;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.idListCapacity = 1 << 20;

        this.visibilityBuffer = VkBuffer.deviceLocal(vma, nodeManager.maxNodeCount * 4L,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        this.outputBuffer = VkBuffer.deviceLocal(vma, OUTPUT_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        this.idListBuffer = VkBuffer.deviceLocal(vma, this.idListCapacity,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.sorterLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true, new int[]{0, 8});
        this.transformerLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true, new int[]{0, 8});
        this.batchClearLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true, new int[]{0, 8});

        var sorter = this.compileStage(compiler, "lod/hierarchical/cleaner/sort_visibility.comp", Map.of(
                "WORK_SIZE", Integer.toString(SORTING_WORKER_SIZE),
                "ELEMS_PER_THREAD", Integer.toString(WORK_PER_THREAD),
                "OUTPUT_SIZE", Integer.toString(OUTPUT_COUNT),
                "VISIBILITY_BUFFER_BINDING", "0",
                "OUTPUT_BUFFER_BINDING", "1",
                "NODE_DATA_BINDING", "2"));
        var transformer = this.compileStage(compiler, "lod/hierarchical/cleaner/result_transformer.comp", Map.of(
                "OUTPUT_SIZE", Integer.toString(OUTPUT_COUNT),
                "MIN_ID_BUFFER_BINDING", "0",
                "NODE_BUFFER_BINDING", "1",
                "OUTPUT_BUFFER_BINDING", "2",
                "VISIBILITY_BUFFER_BINDING", "3"));
        var batchClear = this.compileStage(compiler, "lod/hierarchical/cleaner/batch_visibility_set.comp", Map.of(
                "VISIBILITY_BUFFER_BINDING", "0",
                "LIST_BUFFER_BINDING", "1"));
        try {
            this.sorterPipeline = VkPipelineBuilder.createCompute(device, this.sorterLayout, sorter);
            this.transformerPipeline = VkPipelineBuilder.createCompute(device, this.transformerLayout, transformer);
            this.batchClearPipeline = VkPipelineBuilder.createCompute(device, this.batchClearLayout, batchClear);
        } finally {
            sorter.free(device);
            transformer.free(device);
            batchClear.free(device);
        }
    }

    /** Record the one-time buffer fills (visibility = -1, scratch output = sentinel). */
    public void ensureFilled(VkCommandBuffer cb) {
        if (this.filled) {
            return;
        }
        this.filled = true;
        this.visibilityBuffer.fill(cb, 0, this.visibilityBuffer.size(), -1);
        this.outputBuffer.fill(cb, 0, OUTPUT_BYTES, this.nodeManager.maxNodeCount - 2);
        VkSync.memoryBarrier(cb);
    }

    /**
     * Pre-commit cleaner compute (sorter + transformer) and the remove-batch readback.
     * Runs after the traversal has written this frame's visibility values.
     */
    public void tick(VkCommandBuffer cb, VkBuffer nodeDataBuffer, long activeHalfOffset) {
        this.visibilityId++;
        if (!this.shouldCleanGeometry()) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            int maxNodeId = this.nodeManager.getCurrentMaxNodeId();
            int dispatchSize = (maxNodeId + SORTING_WORKER_SIZE * WORK_PER_THREAD - 1) / (SORTING_WORKER_SIZE * WORK_PER_THREAD);

            this.outputBuffer.fill(cb, 0, OUTPUT_BYTES, this.nodeManager.maxNodeCount - 2);
            VkSync.memoryBarrier(cb);

            //Sorter: find the worst candidates
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.sorterPipeline);
            var visInfo = VkDescriptorBufferInfo.calloc(1, stack);
            visInfo.get(0).buffer(this.visibilityBuffer.handle()).offset(0).range(this.visibilityBuffer.size());
            var outInfo = VkDescriptorBufferInfo.calloc(1, stack);
            outInfo.get(0).buffer(this.outputBuffer.handle()).offset(0).range(4L * OUTPUT_COUNT);
            var nodeInfo = VkDescriptorBufferInfo.calloc(1, stack);
            nodeInfo.get(0).buffer(nodeDataBuffer.handle()).offset(activeHalfOffset * 16L).range(nodeDataBuffer.size() - activeHalfOffset * 16L);

            var writes = VkWriteDescriptorSet.calloc(3, stack);
            this.setBufferWrite(writes.get(0), 0, visInfo);
            this.setBufferWrite(writes.get(1), 1, outInfo);
            this.setBufferWrite(writes.get(2), 2, nodeInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.sorterLayout.handle(), 0, writes);
            vkCmdPushConstants(cb, this.sorterLayout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(0, (int) activeHalfOffset));

            vkCmdDispatch(cb, dispatchSize, 1, 1);
            VkSync.memoryBarrier(cb);

            //Transformer: convert candidate node ids into section positions + mark visibility
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.transformerPipeline);
            var minIdInfo = VkDescriptorBufferInfo.calloc(1, stack);
            minIdInfo.get(0).buffer(this.outputBuffer.handle()).offset(0).range(4L * OUTPUT_COUNT);
            var tNodeInfo = VkDescriptorBufferInfo.calloc(1, stack);
            tNodeInfo.get(0).buffer(nodeDataBuffer.handle()).offset(activeHalfOffset * 16L).range(nodeDataBuffer.size() - activeHalfOffset * 16L);
            var tOutInfo = VkDescriptorBufferInfo.calloc(1, stack);
            tOutInfo.get(0).buffer(this.outputBuffer.handle()).offset(4L * OUTPUT_COUNT).range(8L * OUTPUT_COUNT);
            var tVisInfo = VkDescriptorBufferInfo.calloc(1, stack);
            tVisInfo.get(0).buffer(this.visibilityBuffer.handle()).offset(0).range(this.visibilityBuffer.size());

            var tWrites = VkWriteDescriptorSet.calloc(4, stack);
            this.setBufferWrite(tWrites.get(0), 0, minIdInfo);
            this.setBufferWrite(tWrites.get(1), 1, tNodeInfo);
            this.setBufferWrite(tWrites.get(2), 2, tOutInfo);
            this.setBufferWrite(tWrites.get(3), 3, tVisInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.transformerLayout.handle(), 0, tWrites);
            vkCmdPushConstants(cb, this.transformerLayout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(this.visibilityId, (int) activeHalfOffset));

            vkCmdDispatch(cb, 1, 1, 1);
            VkSync.memoryBarrier(cb);

            //Read back the worst candidates for CPU-side geometry eviction
            this.downloadStream.download(this.outputBuffer, 4L * OUTPUT_COUNT, REMOVE_BATCH_BYTES, readback -> {
                var mb = new MemoryBuffer(REMOVE_BATCH_BYTES);
                MemoryUtil.memCopy(MemoryUtil.memAddress(readback), mb.address, REMOVE_BATCH_BYTES);
                this.nodeManager.submitRemoveBatch(mb);
            });
        }
    }

    /**
     * Stage id-list writes into the upload stream (from the async results). The actual
     * {@code batch_visibility_set} dispatch happens in {@link #dispatchIdUpdates} after commit.
     */
    public void collectIds(IntOpenHashSet collection) {
        int count = collection.size();
        if (count == 0) {
            return;
        }
        if (count * 4L > this.idListCapacity) {
            throw new IllegalStateException("Cleaner id list too large: " + count);
        }
        ByteBuffer bb = this.uploadStream.getWriteBuffer(this.idListBuffer, 0, count * 4L).order(ByteOrder.LITTLE_ENDIAN);
        var iter = collection.iterator();
        while (iter.hasNext()) {
            bb.putInt(iter.nextInt());
        }
        this.pendingIdCount = count;
    }

    /** Dispatch the visibility clears for collected ids. Must run after the upload stream commit. */
    public void dispatchIdUpdates(VkCommandBuffer cb) {
        int count = this.pendingIdCount;
        if (count == 0) {
            return;
        }
        this.pendingIdCount = 0;
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.batchClearPipeline);
            var visInfo = VkDescriptorBufferInfo.calloc(1, stack);
            visInfo.get(0).buffer(this.visibilityBuffer.handle()).offset(0).range(this.visibilityBuffer.size());
            var listInfo = VkDescriptorBufferInfo.calloc(1, stack);
            listInfo.get(0).buffer(this.idListBuffer.handle()).offset(0).range(count * 4L);

            var writes = VkWriteDescriptorSet.calloc(2, stack);
            this.setBufferWrite(writes.get(0), 0, visInfo);
            this.setBufferWrite(writes.get(1), 1, listInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.batchClearLayout.handle(), 0, writes);
            vkCmdPushConstants(cb, this.batchClearLayout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(count, this.visibilityId));

            vkCmdDispatch(cb, (count + 127) / 128, 1, 1);
            VkSync.memoryBarrier(cb);
        }
    }

    private boolean shouldCleanGeometry() {
        long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
        return remaining < 256_000_000;//If less than 256 mb free memory
    }

    public VkBuffer visibilityBuffer() {
        return this.visibilityBuffer;
    }

    public int getVisibilityId() {
        return this.visibilityId;
    }

    private void setBufferWrite(VkWriteDescriptorSet write, int binding, VkDescriptorBufferInfo.Buffer info) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        write.pBufferInfo(info);
    }

    private VkShaderModule compileStage(VkShaderCompiler compiler, String file, Map<String, String> defines) {
        var src = VkShaderCompiler.loadResource(file);
        return compiler.compile("voxy:" + file, src, VkShaderStage.COMPUTE, defines);
    }

    @Override
    public void close() {
        vkDestroyPipeline(this.device, this.sorterPipeline, null);
        vkDestroyPipeline(this.device, this.transformerPipeline, null);
        vkDestroyPipeline(this.device, this.batchClearPipeline, null);
        this.sorterLayout.close();
        this.transformerLayout.close();
        this.batchClearLayout.close();
        this.visibilityBuffer.close();
        this.outputBuffer.close();
        this.idListBuffer.close();
    }
}
