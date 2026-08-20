package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/**
 * GPU greedy mesher (opaque passes): dispatches {@code lod/mesh.comp} per section and reads the
 * resulting quads back from the host-visible slot, then feeds a {@link BuiltSection} through the
 * normal async geometry path. Requires {@code shaderInt64}.
 *
 * <p>Each slot holds: raw section data (32768 u64) + 6 neighbor planes (6144 u64) + working
 * {@code sectionData}/{@code masks} + the per-lane quad scratch + counters. The mesh compute runs
 * at splice 2 (after the upload stream commit so the raw/neighbor copies have landed); a fenced
 * task then reads the host-visible slot and completes the geometry upload.
 */
public final class VkMeshGenerator implements AutoCloseable {
    public static final int LANE_CAP = 1 << 14;
    private static final int MAX_SLOTS = 4;
    private static final int MAX_JOBS_PER_FRAME = 4;

    //Slot byte layout (matches mesh.comp)
    private static final long RAW_OFFSET = 0;
    private static final long NEIGHBOR_OFFSET = 32768L * 8;
    private static final long SECTION_OFFSET = NEIGHBOR_OFFSET + 6144L * 8;
    private static final long MASKS_OFFSET = SECTION_OFFSET + 65536L * 8;
    private static final long QUADS_OFFSET = MASKS_OFFSET + 3L * 1024 * 4;
    private static final long COUNTERS_OFFSET = QUADS_OFFSET + LANE_CAP * 8L * 8;
    private static final long SLOT_SIZE = COUNTERS_OFFSET + 17L * 4;
    private static final int C_LANE_COUNTS = 0;
    private static final int C_AABB = 8;
    private static final int C_TOTAL = 9;
    private static final int C_FLAGS = 10;

    private record MeshJob(long position, byte childExistence, long[] raw, long[] neighbors) {}
    private record StagedJob(MeshJob job, int slot) {}

    private final VkDevice device;
    private final VkUploadStream uploadStream;
    private final VkModelTables tables;
    private final VkPipelineLayout layout;
    private final long pipeline;
    private final VkBuffer[] slots;
    private final long[] slotBase;
    private final boolean[] slotBusy;
    private final java.util.concurrent.ConcurrentLinkedDeque<MeshJob> pending = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final ArrayList<StagedJob> staged = new ArrayList<>();
    private AsyncNodeManager nodeManager;

    public VkMeshGenerator(VkDevice device, VkShaderCompiler compiler, VkUploadStream uploadStream, VkModelTables tables, long vma) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.tables = tables;
        this.layout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);

        var module = this.compileStage(compiler, "lod/mesh.comp");
        try {
            this.pipeline = VkPipelineBuilder.createCompute(device, this.layout, module);
        } finally {
            module.free(device);
        }

        this.slots = new VkBuffer[MAX_SLOTS];
        this.slotBase = new long[MAX_SLOTS];
        this.slotBusy = new boolean[MAX_SLOTS];
        for (int i = 0; i < MAX_SLOTS; i++) {
            this.slots[i] = VkBuffer.hostVisible(vma, SLOT_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.slotBase[i] = this.slots[i].mapPersistent();
        }
    }

    public boolean isGpuSupported() {
        var caps = VkContext.INSTANCE.capabilities();
        return caps != null && caps.shaderInt64;
    }

    public void setNodeManager(AsyncNodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    /** Queue a section for GPU meshing (raw = 32768 longs, neighbors = 6144 longs). */
    public void submit(long position, byte childExistence, long[] raw, long[] neighbors) {
        if (!this.isGpuSupported()) {
            return;
        }
        if (raw.length != 32768 || neighbors.length != 6144) {
            throw new IllegalArgumentException("Bad mesh input sizes");
        }
        this.pending.addLast(new MeshJob(position, childExistence, raw, neighbors));
    }

    /** Stage pending jobs into the slots (copies recorded at the next upload stream commit). */
    public void stage() {
        this.staged.clear();
        int processed = 0;
        while (!this.pending.isEmpty() && processed < MAX_JOBS_PER_FRAME) {
            int slot = this.findFreeSlot();
            if (slot == -1) {
                break;
            }
            MeshJob job = this.pending.removeFirst();
            this.slotBusy[slot] = true;
            this.stageJob(job, slot);
            processed++;
        }
    }

    private void stageJob(MeshJob job, int slot) {
        try (var stack = MemoryStack.stackPush()) {
            var raw = this.uploadStream.getWriteBuffer(this.slots[slot], RAW_OFFSET, 32768L * 8).order(ByteOrder.LITTLE_ENDIAN);
            var rawL = raw.asLongBuffer();
            for (long v : job.raw()) {
                rawL.put(v);
            }
            var nbr = this.uploadStream.getWriteBuffer(this.slots[slot], NEIGHBOR_OFFSET, 6144L * 8).order(ByteOrder.LITTLE_ENDIAN);
            var nbrL = nbr.asLongBuffer();
            for (long v : job.neighbors()) {
                nbrL.put(v);
            }
        }
        this.staged.add(new StagedJob(job, slot));
    }

    /**
     * Record the mesh dispatches + readbacks for the staged jobs. Must run after the upload
     * stream commit (raw/neighbor data landed) and after {@code tables.upload}.
     */
    public void dispatch(VkCommandBuffer cb) {
        for (var staged : this.staged) {
            this.dispatchJob(cb, staged.job, staged.slot);
        }
        this.staged.clear();
    }

    private void dispatchJob(VkCommandBuffer cb, MeshJob job, int slot) {
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);

            var idsInfo = VkDescriptorBufferInfo.calloc(1, stack);
            idsInfo.get(0).buffer(this.tables.idMappingsBuffer().handle()).offset(0).range(this.tables.idMappingsBuffer().size());
            var metaInfo = VkDescriptorBufferInfo.calloc(1, stack);
            metaInfo.get(0).buffer(this.tables.metadataCacheBuffer().handle()).offset(0).range(this.tables.metadataCacheBuffer().size());
            var slotInfo = VkDescriptorBufferInfo.calloc(1, stack);
            slotInfo.get(0).buffer(this.slots[slot].handle()).offset(0).range(SLOT_SIZE);
            var fluidInfo = VkDescriptorBufferInfo.calloc(1, stack);
            fluidInfo.get(0).buffer(this.tables.fluidLUTBuffer().handle()).offset(0).range(this.tables.fluidLUTBuffer().size());

            var writes = VkWriteDescriptorSet.calloc(4, stack);
            this.setBufferWrite(writes.get(0), 0, idsInfo);
            this.setBufferWrite(writes.get(1), 1, metaInfo);
            this.setBufferWrite(writes.get(2), 2, slotInfo);
            this.setBufferWrite(writes.get(3), 3, fluidInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.layout.handle(), 0, writes);

            vkCmdDispatch(cb, 1, 1, 1);
            VkSync.memoryBarrier(cb);
        }

        //Read back the host-visible slot once the frame's GPU work completes
        RenderSystem.queueFencedTask(() -> {
            try {
                this.completeJob(job, slot);
            } finally {
                this.slotBusy[slot] = false;
            }
        });
    }

    private void completeJob(MeshJob job, int slot) {
        if (this.nodeManager == null) {
            return;
        }
        long base = this.slotBase[slot] + COUNTERS_OFFSET;
        int flags = MemoryUtil.memGetInt(base + C_FLAGS * 4L);
        if ((flags & 1) != 0) {
            Logger.warn("Voxy (Vulkan): GPU mesh had a missing model id, result discarded");
            return;
        }
        if ((flags & 2) != 0) {
            Logger.warn("Voxy (Vulkan): GPU mesh quad lane overflow, result discarded");
            return;
        }
        int total = MemoryUtil.memGetInt(base + C_TOTAL * 4L);
        int aabb = MemoryUtil.memGetInt(base + C_AABB * 4L);

        int[] laneCounts = new int[8];
        int coff = 0;
        int[] offsets = new int[8];
        for (int i = 0; i < 8; i++) {
            laneCounts[i] = MemoryUtil.memGetInt(base + (C_LANE_COUNTS + i) * 4L);
            offsets[i] = coff;
            coff += laneCounts[i];
        }
        if (coff != total) {
            Logger.error("Voxy (Vulkan): GPU mesh count mismatch " + coff + " != " + total);
            return;
        }

        if (total == 0) {
            Logger.info("Voxy (Vulkan): GPU mesh empty: pos=" + job.position());
            this.nodeManager.submitGeometryResult(BuiltSection.emptyWithChildren(job.position(), job.childExistence()));
            return;
        }

        Logger.info("Voxy (Vulkan): GPU mesh result: pos=" + job.position() + " quads=" + total
                + " aabb=" + Integer.toHexString(aabb) + " lanes=[" + java.util.Arrays.toString(laneCounts) + "]");

        //Compact the per-lane quads into a contiguous BuiltSection buffer
        var buff = new MemoryBuffer(total * 8L);
        long srcBase = this.slotBase[slot] + QUADS_OFFSET;
        for (int i = 0; i < 8; i++) {
            if (laneCounts[i] == 0) {
                continue;
            }
            MemoryUtil.memCopy(srcBase + i * (long) LANE_CAP * 8L, buff.address + offsets[i] * 8L, laneCounts[i] * 8L);
        }
        this.nodeManager.submitGeometryResult(new BuiltSection(job.position(), job.childExistence(), aabb, buff, offsets, null));
    }

    private int findFreeSlot() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!this.slotBusy[i]) {
                return i;
            }
        }
        return -1;
    }

    private void setBufferWrite(VkWriteDescriptorSet write, int binding, VkDescriptorBufferInfo.Buffer info) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        write.pBufferInfo(info);
    }

    private VkShaderModule compileStage(VkShaderCompiler compiler, String file) {
        var src = VkShaderCompiler.loadResource(file);
        return compiler.compile("voxy:" + file, src, VkShaderStage.COMPUTE);
    }

    @Override
    public void close() {
        vkDestroyPipeline(this.device, this.pipeline, null);
        this.layout.close();
        for (VkBuffer slot : this.slots) {
            slot.close();
        }
    }
}
