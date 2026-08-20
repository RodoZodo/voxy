package me.cortex.voxy.client.core.vk.post;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import org.lwjgl.vulkan.VkDevice;
public final class VkPostProcessor implements AutoCloseable {
    private final VkSSAO ssao;
    private final VkFullscreenBlit blit;
    public VkPostProcessor(VkDevice device, VkShaderCompiler compiler) {
        this.ssao=new VkSSAO(device, compiler, false, 12);
        this.blit=new VkFullscreenBlit(device, compiler, "post/fullscreen.vert","post/blit_texture_depth_cutout.frag");
    }
    @Override public void close(){ ssao.close(); blit.close(); }
}
