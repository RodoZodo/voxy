package me.cortex.voxy.client.core.vk.shader;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

/**
 * A pipeline layout with a single descriptor-set layout at set 0 (created with the push-descriptor
 * flag, matching Minecraft's own descriptor model). Bindings are declared explicitly in the
 * shader via {@code layout(set = 0, binding = N)}.
 */
public final class VkPipelineLayout implements AutoCloseable {
    /** A single descriptor binding (binding index -> Vk descriptor type + shader stage flags). */
    public record Binding(int binding, int descriptorType, int stageFlags) {}

    private final VkDevice device;
    private final long setLayout;
    private final long layout;

    /** @param pushConstants a (offset, size) range for push constants, or null */
    public VkPipelineLayout(VkDevice device, Binding[] bindings, boolean pushDescriptor, @Nullable int[] pushConstants) {
        this.device = device;
        try (var stack = MemoryStack.stackPush()) {
            var binds = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                var b = bindings[i];
                binds.get(i).binding(b.binding());
                binds.get(i).descriptorType(b.descriptorType());
                binds.get(i).descriptorCount(1);
                binds.get(i).stageFlags(b.stageFlags());
            }

            var slci = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            slci.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            slci.flags(pushDescriptor ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0);
            slci.pBindings(binds);

            var pSet = stack.mallocLong(1);
            int err = vkCreateDescriptorSetLayout(device, slci, null, pSet);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateDescriptorSetLayout failed: " + err);
            }
            this.setLayout = pSet.get(0);

            var plci = VkPipelineLayoutCreateInfo.calloc(stack);
            plci.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            plci.pSetLayouts(stack.longs(this.setLayout));
            if (pushConstants != null) {
                var range = VkPushConstantRange.calloc(1, stack);
                range.get(0).stageFlags(0x20 /* VK_SHADER_STAGE_COMPUTE_BIT */);
                range.get(0).offset(pushConstants[0]);
                range.get(0).size(pushConstants[1]);
                plci.pPushConstantRanges(range);
            }

            var pLayout = stack.mallocLong(1);
            err = vkCreatePipelineLayout(device, plci, null, pLayout);
            if (err != VK_SUCCESS) {
                vkDestroyDescriptorSetLayout(device, this.setLayout, null);
                throw new IllegalStateException("vkCreatePipelineLayout failed: " + err);
            }
            this.layout = pLayout.get(0);
        }
    }

    public VkPipelineLayout(VkDevice device, Binding[] bindings, boolean pushDescriptor) {
        this(device, bindings, pushDescriptor, null);
    }

    public long handle() {
        return this.layout;
    }

    public long setLayout() {
        return this.setLayout;
    }

    @Override
    public void close() {
        vkDestroyPipelineLayout(this.device, this.layout, null);
        vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
    }
}
