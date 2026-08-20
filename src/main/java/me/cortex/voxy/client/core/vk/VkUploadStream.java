package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/**
 * Bulk CPU -> GPU upload path (port of the GL-era {@code UploadStream}).
 *
 * <p>One persistently mapped host-visible staging ring; the CPU writes directly into it and
 * {@link #commit} records {@code vkCmdCopyBuffer} regions on Minecraft's primary command buffer
 * (must be called between render passes). Staging memory is freed after the frame completes via
 * {@link RenderSystem#queueFencedTask} (Minecraft's 2-frames-in-flight rotation).
 *
 * <p>The ring does not wrap; when full it waits for all in-flight frames and resets. 64 MiB is
 * far more than a frame's typical upload volume.
 */
public final class VkUploadStream implements AutoCloseable {
    private static final long DEFAULT_CAPACITY = 64L * 1024 * 1024;

    private final long vma;
    private final VkBuffer staging;
    private final long capacity;
    private final long alignment;
    private final long baseAddress;
    private long allocOffset;
    private final Map<VkBuffer, List<CopyEntry>> pending = new LinkedHashMap<>();
    private final Deque<Void> inFlightFrames = new LinkedList<>();

    private record CopyEntry(long srcOffset, long dstOffset, long size) {}

    public VkUploadStream(VkDevice device, long vma) {
        this(device, vma, DEFAULT_CAPACITY);
    }

    public VkUploadStream(VkDevice device, long vma, long capacity) {
        this.vma = vma;
        this.capacity = capacity;
        this.alignment = 256; // conservative (minStorageBufferOffsetAlignment); copies only need 4
        this.staging = VkBuffer.hostVisible(vma, capacity, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        this.baseAddress = this.staging.mapPersistent();
    }

    /** Reserve ring space and return a writable view; the copy is recorded at the next {@link #commit}. */
    public ByteBuffer getWriteBuffer(VkBuffer target, long targetOffset, long size) {
        long src = this.alloc(size);
        this.pending.computeIfAbsent(target, k -> new ArrayList<>()).add(new CopyEntry(src, targetOffset, size));
        return MemoryUtil.memByteBuffer(this.baseAddress + src, (int) size);
    }

    /** Copy the given data into the staging ring; the GPU copy happens at the next {@link #commit}. */
    public void upload(ByteBuffer data, VkBuffer target, long targetOffset) {
        getWriteBuffer(target, targetOffset, data.remaining()).put(data);
    }

    /** Record all pending copies onto the command buffer (between render passes only). */
    public void commit(VkCommandBuffer cb) {
        if (this.pending.isEmpty()) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            for (var entry : this.pending.entrySet()) {
                var regions = VkBufferCopy.calloc(entry.getValue().size(), stack);
                for (int i = 0; i < entry.getValue().size(); i++) {
                    var e = entry.getValue().get(i);
                    regions.get(i).srcOffset(e.srcOffset()).dstOffset(e.dstOffset()).size(e.size());
                }
                vkCmdCopyBuffer(cb, this.staging.handle(), entry.getKey().handle(), regions);
            }
        }
        VkSync.memoryBarrier(cb);
        this.pending.clear();
        //The staging space used this frame stays valid until the frame completes; queue a
        //callback so the ring may be reset once all in-flight frames are done.
        this.inFlightFrames.addLast(null);
        RenderSystem.queueFencedTask(() -> this.inFlightFrames.removeFirst());
    }

    private long alloc(long size) {
        size = (size + this.alignment - 1) & ~(this.alignment - 1);
        if (this.allocOffset + size > this.capacity) {
            //Ring is full: wait for all in-flight frames, then reset.
            RenderSystem.executePendingTasks();
            if (!this.inFlightFrames.isEmpty()) {
                throw new IllegalStateException("VkUploadStream: ring full with frames still in flight");
            }
            this.allocOffset = 0;
        }
        long offset = this.allocOffset;
        this.allocOffset += size;
        return offset;
    }

    public long getAllocOffset() {
        return this.allocOffset;
    }

    @Override
    public void close() {
        this.staging.close();
    }
}
