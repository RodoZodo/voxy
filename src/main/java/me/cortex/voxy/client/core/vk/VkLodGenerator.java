package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.function.Consumer;

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
 * GPU LOD generation: computes the internal mip levels of {@link VoxelizedSection}s on the GPU
 * (exact port of the CPU {@code Mipper} rule), replacing {@code WorldVoxilizedSectionMipper}.
 *
 * <p>Ingest threads submit sections (base 16^3 voxel data only); at splice 2 the render thread
 * uploads the base data, runs the 4 mip dispatches in place on a scratch section buffer, and
 * downloads the full section back to CPU. The download callback then completes the ingest
 * continuation (e.g. {@code WorldUpdater.insertUpdate}) on the render thread.
 *
 * <p>Requires {@code shaderInt64} (capability-gated); when unavailable, mips fall back to CPU.
 */
public final class VkLodGenerator implements AutoCloseable {
    public static final int SECTION_LONGS = 16 * 16 * 16 + 8 * 8 * 8 + 4 * 4 * 4 + 2 * 2 * 2 + 1;
    public static final int BASE_LONGS = 16 * 16 * 16;
    private static final int SECTION_BYTES = SECTION_LONGS * Long.BYTES;
    private static final int BASE_BYTES = BASE_LONGS * Long.BYTES;
    private static final int SLOT_COUNT = 8;
    private static final int MAX_JOBS_PER_FRAME = 64;
    private static final int MAX_PENDING_JOBS = 4096;

    private record Job(VoxelizedSection section, WorldEngine world, Consumer<VoxelizedSection> onDone) {}
    private record StagedJob(Job job, int slot) {}

    private final VkDevice device;
    private final VkShaderCompiler compiler;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final long vma;
    private final VkPipelineLayout layout;
    private final long pipeline;
    private final VkBuffer[] scratchSlots;
    private final boolean[] slotBusy;
    private VkBuffer opacityTableBuffer;
    private VkBuffer pendingOpacityTableBuffer;
    private long pendingOpacityTableRevision = -1;
    private final java.util.concurrent.ConcurrentLinkedDeque<Job> pending = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final ArrayList<StagedJob> staged = new ArrayList<>();
    private long lastOpacityRevision = -1;
    private Mapper lastMapper;

    public VkLodGenerator(VkDevice device, VkShaderCompiler compiler, VkUploadStream uploadStream, VkDownloadStream downloadStream, long vma) {
        this.device = device;
        this.compiler = compiler;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.vma = vma;
        this.layout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true, new int[]{0, 4});

        var module = this.compileStage("lod/mip.comp");
        try {
            this.pipeline = VkPipelineBuilder.createCompute(device, this.layout, module);
        } finally {
            module.free(device);
        }

        this.scratchSlots = new VkBuffer[SLOT_COUNT];
        this.slotBusy = new boolean[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.scratchSlots[i] = new VkBuffer(vma, SECTION_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    1 /* GPU_ONLY */);
        }
        this.opacityTableBuffer = VkBuffer.hostVisible(vma, 1 << 20, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    /**
     * Submit a section for GPU mip generation. On completion (render thread) the given
     * continuation runs with the fully mipped section. If the GPU path is unavailable or
     * overloaded, mips are computed on the CPU and the continuation runs synchronously.
     */
    public boolean submit(VoxelizedSection source, WorldEngine world, Mapper mapper, Consumer<VoxelizedSection> onDone) {
        if (!isGpuSupported()) {
            return false;
        }
        if (this.pending.size() >= MAX_PENDING_JOBS) {
            return false;
        }

        //The caller's section array is scratch memory (thread-local); the GPU job needs an owned copy.
        var owned = new VoxelizedSection(new long[SECTION_LONGS]);
        System.arraycopy(source.section, 0, owned.section, 0, BASE_LONGS);
        owned.lvl0NonAirCount = source.lvl0NonAirCount;
        owned.x = source.x;
        owned.y = source.y;
        owned.z = source.z;
        this.lastMapper = mapper;
        world.acquireRef();
        this.pending.addLast(new Job(owned, world, onDone));
        return true;
    }

    public boolean isGpuSupported() {
        var caps = VkContext.INSTANCE.capabilities();
        return caps != null && caps.shaderInt64;
    }

    /**
     * Stage up to {@value MAX_JOBS_PER_FRAME} pending jobs by writing their base voxel data into
     * the upload stream (the copies are recorded at the following {@link #commit}). Call at
     * splice 2 before the upload stream commit.
     */
    public void stage() {
        if (this.pending.isEmpty()) {
            return;
        }
        this.staged.clear();
        int processed = 0;
        while (!this.pending.isEmpty() && processed < MAX_JOBS_PER_FRAME) {
            int slot = this.findFreeSlot();
            if (slot == -1) {
                break;
            }
            Job job = this.pending.removeFirst();
            this.slotBusy[slot] = true;
            this.stageJob(job, slot);
            processed++;
        }
    }

    private void stageJob(Job job, int slot) {
        VkBuffer scratch = this.scratchSlots[slot];
        ByteBuffer upload = this.uploadStream.getWriteBuffer(scratch, 0, BASE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var longs = upload.asLongBuffer();
        for (int i = 0; i < BASE_LONGS; i++) {
            longs.put(job.section().section[i]);
        }
        this.staged.add(new StagedJob(job, slot));
    }

    /**
     * Record the mip dispatches + readbacks for the jobs staged this frame. Must run after the
     * upload stream commit so the base data has actually been copied into the scratch buffers.
     */
    public void dispatch(VkCommandBuffer cb) {
        if (this.staged.isEmpty()) {
            return;
        }
        this.updateOpacityTable(cb);
        for (var staged : this.staged) {
            this.dispatchJob(cb, staged.job, staged.slot);
        }
        this.staged.clear();
    }

    private void dispatchJob(VkCommandBuffer cb, Job job, int slot) {
        VkBuffer scratch = this.scratchSlots[slot];

        //Dispatch the 4 mip levels in place
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);

            var dataInfo = VkDescriptorBufferInfo.calloc(1, stack);
            dataInfo.get(0).buffer(scratch.handle()).offset(0).range(SECTION_BYTES);
            var tableInfo = VkDescriptorBufferInfo.calloc(1, stack);
            tableInfo.get(0).buffer(this.opacityTableBuffer.handle()).offset(0).range(this.opacityTableBuffer.size());

            var writes = VkWriteDescriptorSet.calloc(2, stack);
            this.setBufferWrite(writes.get(0), 0, dataInfo);
            this.setBufferWrite(writes.get(1), 1, tableInfo);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.layout.handle(), 0, writes);

            for (int lvl = 1; lvl <= 4; lvl++) {
                vkCmdPushConstants(cb, this.layout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(lvl));
                int total = 16 >> lvl;
                vkCmdDispatch(cb, (total * total * total + 63) / 64, 1, 1);
                VkSync.memoryBarrier(cb);
            }
        }

        //Read the full mipped section back; the callback completes the ingest continuation
        this.downloadStream.download(scratch, 0, SECTION_BYTES, readback -> {
            try {
                var longs = readback.asLongBuffer();
                for (int i = 0; i < SECTION_LONGS; i++) {
                    job.section().section[i] = longs.get(i);
                }
                job.onDone().accept(job.section());
            } finally {
                job.world().releaseRef();
                this.slotBusy[slot] = false;
            }
        });
    }

    private int findFreeSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!this.slotBusy[i]) {
                return i;
            }
        }
        return -1;
    }

    private void updateOpacityTable(VkCommandBuffer cb) {
        var mapper = this.lastMapper;
        if (mapper == null) {
            return;
        }
        // Promote pending reallocation if its fence has completed (2 frames via queueFencedTask)
        if (this.pendingOpacityTableBuffer != null && this.pendingOpacityTableRevision == this.lastOpacityRevision) {
            // The pending buffer is already the current, nothing to do (will be swapped at fence)
        }
        long revision = mapper.getBlockRevision();
        if (revision == this.lastOpacityRevision && this.pendingOpacityTableBuffer == null) {
            return;
        }
        byte[] table = mapper.getOpacityTable();
        this.lastOpacityRevision = revision;

        // Double-buffer reallocate if table outgrew current buffer — allocate new and swap next frame after fence
        if ((long) table.length * 4 > this.opacityTableBuffer.size()) {
            long newSize = 1;
            while (newSize < (long) table.length * 4) newSize <<= 1;
            if (newSize > (1 << 22)) newSize = (1 << 22); // cap 4 MiB (1M entries)
            var newBuf = VkBuffer.hostVisible(this.vma, newSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            writeOpacityBytes(newBuf, table);
            // Keep old buffer alive for 2 frames (current dispatch may still be in flight if we had not yet dispatched)
            var old = this.opacityTableBuffer;
            this.pendingOpacityTableBuffer = newBuf;
            this.pendingOpacityTableRevision = revision;
            this.opacityTableBuffer = newBuf;
            com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(() -> {
                old.close();
                if (this.pendingOpacityTableBuffer == newBuf) {
                    this.pendingOpacityTableBuffer = null;
                }
            });
            VkSync.memoryBarrier(cb);
            return;
        }

        writeOpacityBytes(this.opacityTableBuffer, table);
        VkSync.memoryBarrier(cb); //host write -> compute read
    }

    private static void writeOpacityBytes(VkBuffer target, byte[] table) {
        ByteBuffer buf = MemoryUtil.memAlloc(table.length * 4);
        try {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            for (byte b : table) {
                buf.putInt(b & 0xFF);
            }
            buf.flip();
            target.write(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private void setBufferWrite(VkWriteDescriptorSet write, int binding, VkDescriptorBufferInfo.Buffer info) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        write.pBufferInfo(info);
    }

    private VkShaderModule compileStage(String file) {
        var src = VkShaderCompiler.loadResource(file);
        return this.compiler.compile("voxy:" + file, src, VkShaderStage.COMPUTE);
    }

    @Override
    public void close() {
        //Drain any jobs that never made it to the GPU (CPU mip fallback)
        this.staged.forEach(s -> {
            try {
                WorldVoxilizedSectionMipper.mipSection(s.job.section(), this.lastMapper);
                s.job.onDone().accept(s.job.section());
            } finally {
                s.job.world().releaseRef();
            }
        });
        this.staged.clear();
        while (!this.pending.isEmpty()) {
            Job job = this.pending.removeFirst();
            try {
                WorldVoxilizedSectionMipper.mipSection(job.section(), this.lastMapper);
                job.onDone().accept(job.section());
            } finally {
                job.world().releaseRef();
            }
        }
        vkDestroyPipeline(this.device, this.pipeline, null);
        this.layout.close();
        for (VkBuffer slot : this.scratchSlots) {
            slot.close();
        }
        this.opacityTableBuffer.close();
    }
}
