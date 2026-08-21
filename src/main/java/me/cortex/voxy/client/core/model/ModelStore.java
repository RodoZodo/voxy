package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan model store: owns the model/colour SSBOs and the block-model atlas texture.
 * Promoted from the CPU stub for textured LOD.
 */
public class ModelStore {
    public static final int MODEL_SIZE = 64;

    private final VkBuffer modelBuffer;
    private final VkBuffer modelColourBuffer;
    private final VkTexture atlasTexture;
    private final VkSampler sampler;
    private final long vma;
    private boolean atlasInitialized;

    public ModelStore(long vma, VkDevice device, VkPhysicalDevice phys) {
        this.vma = vma;
        this.modelBuffer = VkBuffer.hostVisible(vma, (long) MODEL_SIZE * (1 << 16),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.modelColourBuffer = VkBuffer.hostVisible(vma, 4L * (1 << 16),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        // Atlas: 12288x8192 (256*48 x 256*32), 5 mips (16->8->4->2->1), RGBA8 — device-local, uploaded via staging
        VkTexture tmpAtlas = null;
        VkSampler tmpSampler = null;
        try {
            int w = MODEL_SIZE == 64 ? 12288 : 12288; // 16*3*256
            int h = 8192; // 16*2*256
            int mips = Integer.numberOfTrailingZeros(me.cortex.voxy.client.core.model.ModelFactory.MODEL_TEXTURE_SIZE) + 1;
            tmpAtlas = new VkTexture(device, phys, w, h, mips,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            tmpSampler = new VkSampler(device);
            // Sampler uses NEAREST_MIPMAP_LINEAR, maxLod = mips-1 (matches GL's mipLvl from blocks atlas)
            // VkSampler currently defaults to repeat/nearest; mips handled in shader via lod bias
        } catch (Exception e) {
            // Fallback: keep null, renderer will use dummy white
            me.cortex.voxy.common.Logger.warn("ModelStore atlas creation failed (fallback to white): " + e.getMessage());
        }
        this.atlasTexture = tmpAtlas;
        this.sampler = tmpSampler;
        // Pre-map host-visible buffers for fast CPU writes (persistently mapped)
        if (this.modelBuffer != null) this.modelBuffer.mapPersistent();
        if (this.modelColourBuffer != null) this.modelColourBuffer.mapPersistent();
    }

    /** Legacy ctor for non-Vulkan contexts (tests) — creates CPU-only stub. */
    public ModelStore() {
        this.vma = -1;
        this.modelBuffer = null;
        this.modelColourBuffer = null;
        this.atlasTexture = null;
        this.sampler = null;
    }

    public VkBuffer modelBuffer() { return this.modelBuffer; }
    public VkBuffer modelColourBuffer() { return this.modelColourBuffer; }
    public VkTexture atlasTexture() { return this.atlasTexture; }
    public VkSampler sampler() { return this.sampler; }

    public void ensureAtlasInitialized(VkCommandBuffer cb) {
        if (!this.atlasInitialized && this.atlasTexture != null) {
            this.atlasTexture.transitionAndClear(cb, 1.0f);
            this.atlasInitialized = true;
        }
    }

    public void free() {
        if (this.modelBuffer != null) this.modelBuffer.close();
        if (this.modelColourBuffer != null) this.modelColourBuffer.close();
        if (this.atlasTexture != null) this.atlasTexture.close();
        if (this.sampler != null) this.sampler.close();
    }

    public void bind(int modelBinding, int colourBinding, int textureBinding) {
        // No-op for Vulkan — binding is done via push descriptors in VkSectionRenderer
    }
}
