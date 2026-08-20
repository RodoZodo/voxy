package me.cortex.voxy.client.core.vk.post;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import org.lwjgl.vulkan.VkDevice;
import static org.lwjgl.vulkan.VK10.*;
public final class VkSSAO implements AutoCloseable {
    private final VkDevice device;
    private final VkPipelineLayout layout;
    private final long pipeline;
    public VkSSAO(VkDevice device, VkShaderCompiler compiler, boolean better, int steps) {
        this.device = device;
        var defs = new java.util.HashMap<String,String>();
        if (better) { defs.put("BETTER_SSAO",""); defs.put("SSAO_STEPS", String.valueOf(steps)); }
        this.layout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
            new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT),
            new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT),
            new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT),
            new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        var m = compiler.compile("voxy:post/ssao.comp", VkShaderCompiler.loadResource("post/ssao.comp"), VkShaderStage.COMPUTE, defs);
        this.pipeline = VkPipelineBuilder.createCompute(device, this.layout, m);
        m.free(device);
    }
    @Override public void close(){ vkDestroyPipeline(device,pipeline,null); layout.close(); }
}
