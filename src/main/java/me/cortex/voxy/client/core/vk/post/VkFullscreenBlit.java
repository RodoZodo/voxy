package me.cortex.voxy.client.core.vk.post;
import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import org.lwjgl.vulkan.VkDevice;
import static org.lwjgl.vulkan.VK10.*;
public final class VkFullscreenBlit implements AutoCloseable {
    private final VkDevice device; private final VkPipelineLayout layout; private final long pipeline;
    public VkFullscreenBlit(VkDevice device, VkShaderCompiler compiler, String vert, String frag) {
        this.device=device;
        this.layout=new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
            new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT),
            new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_FRAGMENT_BIT),
        }, true);
        var v=compiler.compile("voxy:post/"+vert, VkShaderCompiler.loadResource("post/"+vert), VkShaderStage.VERTEX);
        var f=compiler.compile("voxy:post/"+frag, VkShaderCompiler.loadResource("post/"+frag), VkShaderStage.FRAGMENT);
        this.pipeline=VkPipelineBuilder.createGraphics(device, layout, v,f, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, null, VK_COMPARE_OP_ALWAYS, false,false);
        v.free(device); f.free(device);
    }
    @Override public void close(){ vkDestroyPipeline(device,pipeline,null); layout.close();}
}
