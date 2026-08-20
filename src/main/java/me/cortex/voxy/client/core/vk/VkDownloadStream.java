package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
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
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/**
 * GPU -> CPU readback path (port of the GL-era {@code DownloadStream}).
 *
 * <p>A host-visible ring that {@code vkCmdCopyBuffer} regions are copied into at {@link #commit}
 * (between render passes). Callbacks are invoked on the render thread via
 * {@link RenderSystem#queueFencedTask} once the frame's GPU work completes; the callback receives
 * a read-only view of the ring (copy out what you need).
 */
public final class VkDownloadStream implements AutoCloseable {
    private static final long DEFAULT_CAPACITY = 32L * 1024 * 1024;

    private final long vma;
    private final VkBuffer readback;
    private final long capacity;
    private final long alignment;
    private final long baseAddress;
    private long allocOffset;
    private final Map<VkBuffer, List<CopyEntry>> pending = new LinkedHashMap<>();
    private final List<CallbackEntry> pendingCallbacks = new ArrayList<>();
    private final Deque<Void> inFlightFrames = new LinkedList<>();

    private record CopyEntry(long srcOffset, long dstOffset, long size) {}
    private record CallbackEntry(long readOffset, long size, Consumer<ByteBuffer> callback) {}

    public VkDownloadStream(VkDevice device, long vma) {
        this(device, vma, DEFAULT_CAPACITY);
    }

    public VkDownloadStream(VkDevice device, long vma, long capacity) {
        this.vma = vma;
        this.capacity = capacity;
        this.alignment = 256; // conservative; see VkUploadStream
        this.readback = new VkBuffer(vma, capacity, VK_BUFFER_USAGE_TRANSFER_DST_BIT, Vma.VMA_MEMORY_USAGE_GPU_TO_CPU);
        this.baseAddress = this.readback.mapPersistent();
    }

    /** Request a copy of {@code size} bytes from {@code src} at {@code srcOffset} into the readback ring. */
    public void download(VkBuffer src, long srcOffset, long size, Consumer<ByteBuffer> callback) {
        long dst = this.alloc(size);
        this.pending.computeIfAbsent(src, k -> new ArrayList<>()).add(new CopyEntry(srcOffset, dst, size));
        this.pendingCallbacks.add(new CallbackEntry(dst, size, callback));
    }

    /** Record pending copies onto the command buffer (between render passes only). */
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
                vkCmdCopyBuffer(cb, entry.getKey().handle(), this.readback.handle(), regions);
            }
        }
        VkSync.memoryBarrier(cb);

        List<CallbackEntry> callbacks = List.copyOf(this.pendingCallbacks);
        this.pendingCallbacks.clear();
        this.pending.clear();
        this.inFlightFrames.addLast(null);
        RenderSystem.queueFencedTask(() -> {
            for (var c : callbacks) {
                c.callback().accept(MemoryUtil.memByteBuffer(this.baseAddress + c.readOffset(), (int) c.size()));
            }
            this.inFlightFrames.removeFirst();
        });
    }

    private long alloc(long size) {
        size = (size + this.alignment - 1) & ~(this.alignment - 1);
        if (this.allocOffset + size > this.capacity) {
            RenderSystem.executePendingTasks();
            if (!this.inFlightFrames.isEmpty()) {
                throw new IllegalStateException("VkDownloadStream: ring full with frames still in flight");
            }
            this.allocOffset = 0;
        }
        long offset = this.allocOffset;
        this.allocOffset += size;
        return offset;
    }

    /** Run all pending readback callbacks and wait for the device to idle. */
    public void flushWaitClear() {
        RenderSystem.executePendingTasks();
        var queue = VkContext.INSTANCE.graphicsQueue();
        if (queue != null) {
            queue.waitIdle();
        }
        this.pendingCallbacks.clear();
        this.pending.clear();
        this.inFlightFrames.clear();
        this.allocOffset = 0;
    }

    @Override
    public void close() {
        this.readback.close();
    }
}
