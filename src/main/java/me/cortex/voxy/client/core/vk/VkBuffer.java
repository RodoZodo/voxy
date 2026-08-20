package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

/** A VMA-backed Vulkan buffer (device-local or host-visible). */
public final class VkBuffer implements AutoCloseable {
    private final long vma;
    private final long handle;
    private final long allocation;
    private final long size;
    private boolean mapped;
    private long mappedAddress;

    public VkBuffer(long vma, long size, int vkUsage, int vmaMemoryUsage) {
        this.vma = vma;
        this.size = size;
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkBufferCreateInfo.calloc(stack);
            ci.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            ci.size(size);
            ci.usage(vkUsage);
            ci.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            var ai = VmaAllocationCreateInfo.calloc(stack);
            ai.usage(vmaMemoryUsage);

            var pBuf = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);
            int err = Vma.vmaCreateBuffer(vma, ci, ai, pBuf, pAlloc, null);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer failed: " + err);
            }
            this.handle = pBuf.get(0);
            this.allocation = pAlloc.get(0);
        }
    }

    public static VkBuffer deviceLocal(long vma, long size, int vkUsage) {
        return new VkBuffer(vma, size, vkUsage, Vma.VMA_MEMORY_USAGE_GPU_ONLY);
    }

    public static VkBuffer hostVisible(long vma, long size, int vkUsage) {
        return new VkBuffer(vma, size, vkUsage, Vma.VMA_MEMORY_USAGE_CPU_ONLY);
    }

    public long handle() {
        return this.handle;
    }

    public long size() {
        return this.size;
    }

    /** Map + copy the given data into the buffer (host-visible allocations only). */
    public void write(ByteBuffer data) {
        try (var stack = MemoryStack.stackPush()) {
            var pData = stack.mallocPointer(1);
            int err = Vma.vmaMapMemory(this.vma, this.allocation, pData);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vmaMapMemory failed: " + err);
            }
            try {
                MemoryUtil.memByteBuffer(pData.get(0), data.remaining()).put(data);
            } finally {
                Vma.vmaUnmapMemory(this.vma, this.allocation);
            }
        }
    }

    /** Persistently map the buffer (host-visible allocations only); returns the base address. */
    public long mapPersistent() {
        if (this.mapped) {
            return this.mappedAddress;
        }
        try (var stack = MemoryStack.stackPush()) {
            var pData = stack.mallocPointer(1);
            int err = Vma.vmaMapMemory(this.vma, this.allocation, pData);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vmaMapMemory failed: " + err);
            }
            this.mapped = true;
            this.mappedAddress = pData.get(0);
            return this.mappedAddress;
        }
    }

    public void unmapPersistent() {
        if (this.mapped) {
            Vma.vmaUnmapMemory(this.vma, this.allocation);
            this.mapped = false;
            this.mappedAddress = 0;
        }
    }

    /** Fill a range of the buffer with a constant value. Must be recorded outside a render pass. */
    public void fill(VkCommandBuffer cb, long offset, long size, int data) {
        vkCmdFillBuffer(cb, this.handle, offset, size, data);
    }

    @Override
    public void close() {
        this.unmapPersistent();
        Vma.vmaDestroyBuffer(this.vma, this.handle, this.allocation);
    }
}
