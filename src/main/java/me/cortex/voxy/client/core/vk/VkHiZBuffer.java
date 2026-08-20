package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/**
 * Hierarchical Z-buffer (port of the GL-era {@code HiZBuffer2}).
 *
 * <p>Compute-only: an init dispatch downsamples the vanilla depth texture into our pow2 R32F
 * mip_0, then the chain dispatch (subgroup clustered max reductions) fills mips 1..6. Must be
 * built between render passes (after the terrain pass ended, before the next pass begins).
 * Requires {@code subgroupArithmetic} (capability-gated).
 */
public final class VkHiZBuffer implements AutoCloseable {
    private static final int CHAIN_MIPS = 6; // mips 1..6 written by the chain shader

    private final VkDevice device;
    private final VkShaderCompiler compiler;
    private final VkSampler sampler;
    private final VkPipelineLayout initLayout;
    private final VkPipelineLayout chainLayout;
    private final long initPipeline;
    private final long chainPipeline;
    private final VkBuffer paramsBuffer;
    private VkTexture texture;
    private int width;
    private int height;
    private int levels;
    private boolean pendingClear;

    public VkHiZBuffer(VkDevice device, VkShaderCompiler compiler) {
        this.device = device;
        this.compiler = compiler;
        this.sampler = new VkSampler(device);
        this.paramsBuffer = VkBuffer.hostVisible(VkContext.INSTANCE.vmaAllocator(), 16, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);

        this.initLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        this.chainLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(7, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);

        var init = this.compileStage("hiz_init.comp");
        try {
            this.initPipeline = VkPipelineBuilder.createCompute(device, this.initLayout, init);
        } finally {
            init.free(device);
        }

        // hiz.comp needs GL_KHR_shader_subgroup_clustered. Apple GPUs (MoltenVK) often lack it;
        // mip0-only occlusion still works for the traverser.
        long chain = 0L;
        var caps = VkContext.INSTANCE.capabilities();
        boolean clustered = caps != null && caps.subgroupClustered && caps.subgroupArithmetic;
        if (clustered) {
            try {
                var chainMod = this.compileStage("hiz.comp");
                try {
                    chain = VkPipelineBuilder.createCompute(device, this.chainLayout, chainMod);
                } finally {
                    chainMod.free(device);
                }
            } catch (Throwable t) {
                Logger.warn("Voxy (Vulkan): HiZ chain pipeline failed; using mip0-only occlusion", t);
            }
        } else {
            Logger.info("Voxy (Vulkan): HiZ chain skipped (subgroup clustered unavailable); using mip0-only occlusion");
        }
        this.chainPipeline = chain;
    }

    /**
     * Ensure a GENERAL, cleared HiZ texture exists for the traverser.
     * Vanilla depth is not sampled yet: Lunar/MC 26.2 keeps that image in an attachment
     * layout, and sampling it as GENERAL is {@code VK_ERROR_DEVICE_LOST} on NVIDIA.
     * Cleared 0 is reverse-Z far, so occlusion is a no-op (everything visible).
     */
    public void build(VkCommandBuffer cb, long depthImageView, int srcWidth, int srcHeight) {
        if (this.texture == null) {
            this.alloc(128, 128);
            this.pendingClear = true;
        }
        if (this.texture == null) {
            return;
        }
        if (this.pendingClear) {
            this.texture.transitionAndClear(cb, 0.0f);
            this.pendingClear = false;
        }
    }

    /** Packed level info for the traversal shader: {@code (log2(w) << 16) | log2(h)}. */
    public int getPackedLevels() {
        return ((Integer.numberOfTrailingZeros(this.width)) << 16) | Integer.numberOfTrailingZeros(this.height);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    /** @return the R32F pow2 mip chain texture (null until the first allocation). */
    public VkTexture texture() {
        return this.texture;
    }

    private void alloc(int w, int h) {
        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }
        this.width = w;
        this.height = h;
        this.levels = Math.min(7, (int) Math.ceil(Math.log(Math.max(w, h)) / Math.log(2)));
        if (w == 0 || h == 0) {
            return;
        }
        this.texture = new VkTexture(this.device, VkContext.INSTANCE.physicalDevice(), w, h, this.levels, VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    }

    private void writeParams(MemoryStack stack, float invW, float invH, int mipW, int mipH) {
        var buf = stack.malloc(16);
        buf.putFloat(invW).putFloat(invH).putInt(mipW).putInt(mipH);
        buf.flip();
        this.paramsBuffer.write(buf);
    }

    private void setImageWrite(VkWriteDescriptorSet write, int binding, int type, VkDescriptorImageInfo.Buffer image) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(type);
        write.pImageInfo(image);
    }

    private void setBufferWrite(VkWriteDescriptorSet write, int binding, int type, VkDescriptorBufferInfo.Buffer buffer) {
        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(0L);
        write.dstBinding(binding);
        write.descriptorCount(1);
        write.descriptorType(type);
        write.pBufferInfo(buffer);
    }

    private VkShaderModule compileStage(String file) {
        var src = VkShaderCompiler.loadResource(file);
        return this.compiler.compile("voxy:" + file, src, VkShaderStage.COMPUTE);
    }

    @Override
    public void close() {
        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }
        if (this.initPipeline != 0L) {
            vkDestroyPipeline(this.device, this.initPipeline, null);
        }
        if (this.chainPipeline != 0L) {
            vkDestroyPipeline(this.device, this.chainPipeline, null);
        }
        this.initLayout.close();
        this.chainLayout.close();
        this.sampler.close();
        this.paramsBuffer.close();
    }
}
