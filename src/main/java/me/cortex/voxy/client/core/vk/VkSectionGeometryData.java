package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

/**
 * GPU-side section geometry store (Vulkan rewrite of the GL-era {@code BasicSectionGeometryData}).
 *
 * <p>Owns the two buffers the VDIC renderer consumes:
 * <ul>
 *   <li>{@code metadataBuffer}: one 32-byte {@code SectionMeta} per section id.</li>
 *   <li>{@code geometryBuffer}: the packed quad heap (8 bytes per quad). Allocated in full up
 *       front (no sparse residency in the Vulkan port).</li>
 * </ul>
 *
 * <p>Written via {@link VkUploadStream} copy regions (geometry quads + metadata), read by the
 * traversal/cleaner/renderer shaders. Must be created and used on the render thread.
 */
public final class VkSectionGeometryData implements AutoCloseable {
    public static final int SECTION_METADATA_SIZE = 32;
    private static final long GEOMETRY_ELEMENT_SIZE = 8;

    private final VkBuffer metadataBuffer;
    private final VkBuffer geometryBuffer;
    private final int maxSectionCount;
    private final long geometryCapacity;
    private int currentSectionCount;

    public VkSectionGeometryData(long vma, int maxSectionCount, long geometryCapacity) {
        if ((maxSectionCount & (maxSectionCount - 1)) != 0) {
            throw new IllegalArgumentException("Max sections should be a power of 2");
        }
        if (geometryCapacity % GEOMETRY_ELEMENT_SIZE != 0) {
            throw new IllegalArgumentException("Geometry capacity must be a multiple of " + GEOMETRY_ELEMENT_SIZE);
        }
        this.maxSectionCount = maxSectionCount;
        this.geometryCapacity = geometryCapacity;
        this.metadataBuffer = VkBuffer.deviceLocal(vma, maxSectionCount * (long) SECTION_METADATA_SIZE,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.geometryBuffer = VkBuffer.deviceLocal(vma, geometryCapacity,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    public VkBuffer metadataBuffer() {
        return this.metadataBuffer;
    }

    public VkBuffer geometryBuffer() {
        return this.geometryBuffer;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {
        return this.geometryCapacity;
    }

    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }

    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    @Override
    public void close() {
        //Wait for any in-flight frames that may still reference the buffers before freeing
        RenderSystem.executePendingTasks();
        var queue = VkContext.INSTANCE.graphicsQueue();
        if (queue != null) {
            queue.waitIdle();
        }
        this.metadataBuffer.close();
        this.geometryBuffer.close();
    }
}
