package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties;

/** Raw Vulkan device-memory helpers (VMA in this LWJGL build only covers buffers). */
public final class VkMemory {
    /** Allocate device-local memory for an image and bind it. Returns the memory handle. */
    public static long allocateAndBindImage(VkDevice device, VkPhysicalDevice physicalDevice, long image, int propertyFlags) {
        try (var stack = MemoryStack.stackPush()) {
            var req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, req);

            var ai = VkMemoryAllocateInfo.calloc(stack);
            ai.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            ai.allocationSize(req.size());
            ai.memoryTypeIndex(findMemoryType(physicalDevice, stack, req.memoryTypeBits(), propertyFlags));

            var pMem = stack.mallocLong(1);
            int err = vkAllocateMemory(device, ai, null, pMem);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkAllocateMemory failed: " + err);
            }
            long memory = pMem.get(0);
            err = vkBindImageMemory(device, image, memory, 0);
            if (err != VK_SUCCESS) {
                vkFreeMemory(device, memory, null);
                throw new IllegalStateException("vkBindImageMemory failed: " + err);
            }
            return memory;
        }
    }

    private static int findMemoryType(VkPhysicalDevice physicalDevice, MemoryStack stack, int typeBits, int propertyFlags) {
        var props = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & propertyFlags) == propertyFlags) {
                return i;
            }
        }
        throw new IllegalStateException("No suitable memory type found for flags " + propertyFlags);
    }

    private VkMemory() {
    }
}
