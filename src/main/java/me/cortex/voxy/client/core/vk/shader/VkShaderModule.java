package me.cortex.voxy.client.core.vk.shader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/** A compiled SPIR-V shader module (not yet created on the device until {@link #create}). */
public final class VkShaderModule {
    public final VkShaderStage stage;
    private final ByteBuffer spirv;
    private long handle;

    public VkShaderModule(VkShaderStage stage, ByteBuffer spirv) {
        this.stage = stage;
        this.spirv = spirv;
    }

    public void create(VkDevice device) {
        if (this.handle != 0) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkShaderModuleCreateInfo.calloc(stack);
            ci.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            ci.pCode(this.spirv);
            var p = stack.mallocLong(1);
            int err = vkCreateShaderModule(device, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule failed: " + err);
            }
            this.handle = p.get(0);
        }
    }

    public long handle() {
        return this.handle;
    }

    public void free(VkDevice device) {
        if (this.handle != 0) {
            vkDestroyShaderModule(device, this.handle, null);
            this.handle = 0;
        }
        if (this.spirv != null) {
            MemoryUtil.memFree(this.spirv);
        }
    }
}
