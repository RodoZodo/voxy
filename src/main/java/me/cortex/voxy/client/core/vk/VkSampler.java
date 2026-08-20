package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NEVER;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/** Nearest/CLAMP sampler used for the HiZ and other texel-aligned sampling. */
public final class VkSampler implements AutoCloseable {
    private final VkDevice device;
    private final long sampler;

    public VkSampler(VkDevice device) {
        this.device = device;
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkSamplerCreateInfo.calloc(stack);
            ci.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            ci.magFilter(VK_FILTER_NEAREST);
            ci.minFilter(VK_FILTER_NEAREST);
            ci.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            ci.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            ci.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            ci.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            ci.compareEnable(false);
            ci.compareOp(VK_COMPARE_OP_NEVER);
            ci.minLod(0.0f);
            ci.maxLod(16.0f);
            ci.mipLodBias(0.0f);
            ci.anisotropyEnable(false);
            ci.maxAnisotropy(1.0f);
            ci.unnormalizedCoordinates(false);

            var p = stack.mallocLong(1);
            int err = vkCreateSampler(device, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler failed: " + err);
            }
            this.sampler = p.get(0);
        }
    }

    public long handle() {
        return this.sampler;
    }

    @Override
    public void close() {
        vkDestroySampler(this.device, this.sampler, null);
    }
}
