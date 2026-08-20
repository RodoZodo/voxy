package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK11.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

/**
 * A raw 2D texture (VkImage + device-local memory + one image view per mip level). Needed for
 * storage images (e.g. HiZ output), which Minecraft's {@code GpuTexture} cannot create.
 */
public final class VkTexture implements AutoCloseable {
    private final VkDevice device;
    private final long image;
    private final long memory;
    private final long[] views;
    private final int width;
    private final int height;
    private final int mipLevels;

    public VkTexture(VkDevice device, VkPhysicalDevice physicalDevice, int width, int height, int mipLevels, int format, int usage) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkImageCreateInfo.calloc(stack);
            ci.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            ci.imageType(VK_IMAGE_TYPE_2D);
            ci.format(format);
            ci.extent().set(width, height, 1);
            ci.mipLevels(mipLevels);
            ci.arrayLayers(1);
            ci.samples(VK_SAMPLE_COUNT_1_BIT);
            ci.tiling(VK_IMAGE_TILING_OPTIMAL);
            ci.usage(usage);
            ci.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            ci.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            var p = stack.mallocLong(1);
            int err = vkCreateImage(device, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateImage failed: " + err);
            }
            this.image = p.get(0);
        }
        this.memory = VkMemory.allocateAndBindImage(device, physicalDevice, this.image, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        this.views = new long[mipLevels];
        for (int i = 0; i < mipLevels; i++) {
            this.views[i] = this.createView(i, format);
        }
    }

    private long createView(int mipLevel, int format) {
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkImageViewCreateInfo.calloc(stack);
            ci.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            ci.image(this.image);
            ci.viewType(VK_IMAGE_VIEW_TYPE_2D);
            ci.format(format);
            ci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            ci.subresourceRange().baseMipLevel(mipLevel);
            ci.subresourceRange().levelCount(1);
            ci.subresourceRange().baseArrayLayer(0);
            ci.subresourceRange().layerCount(1);

            var p = stack.mallocLong(1);
            int err = vkCreateImageView(this.device, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateImageView failed: " + err);
            }
            return p.get(0);
        }
    }

    public long image() {
        return this.image;
    }

    public long view(int mipLevel) {
        return this.views[mipLevel];
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getMipLevels() {
        return this.mipLevels;
    }

    @Override
    public void close() {
        for (long view : this.views) {
            vkDestroyImageView(this.device, view, null);
        }
        vkDestroyImage(this.device, this.image, null);
        vkFreeMemory(this.device, this.memory, null);
    }
}
