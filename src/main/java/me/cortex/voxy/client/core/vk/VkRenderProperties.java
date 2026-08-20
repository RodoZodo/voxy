package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;

import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;

/**
 * Render conventions for the active renderer. In Vulkan the Z range is always 0..1
 * (zero-to-one), so only the reverse-Z convention must be detected; Minecraft's shared
 * {@link DepthStencilState} is backend-agnostic, so the same check as the GL-era code works.
 */
public record VkRenderProperties(boolean isReverseZ) {

    public static VkRenderProperties get() {
        return new VkRenderProperties(DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL));
    }

    public boolean isZero2One() {
        return true;
    }

    /** Depth compare for fragments closer than the stored depth. */
    public int closerDepthCompare() {
        return this.isReverseZ ? VK_COMPARE_OP_GREATER : VK_COMPARE_OP_LESS;
    }

    /** Depth compare for fragments closer or equal. */
    public int closerEqualDepthCompare() {
        return this.isReverseZ ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
    }

    public float clearDepth() {
        return this.isReverseZ ? 0.0f : 1.0f;
    }
}
