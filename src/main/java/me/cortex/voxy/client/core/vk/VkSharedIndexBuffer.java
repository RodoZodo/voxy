package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

/**
 * Shared index buffers (Vulkan requires u16/u32 indices; the GL era used u8/16 strips).
 * Created device-local and uploaded through the {@link VkUploadStream}.
 */
public final class VkSharedIndexBuffer {
    public final VkBuffer quad; // u16, 6 indices
    public final VkBuffer cube; // u16, 36 indices

    public VkSharedIndexBuffer(long vma, VkUploadStream upload) {
        this.quad = VkBuffer.deviceLocal(vma, 16384L * 6 * 2, VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.cube = VkBuffer.deviceLocal(vma, 36L * 2, VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        var quads = quadIndicesFull();
        var cube = cubeIndices();
        try {
            upload.upload(quads, this.quad, 0);
            upload.upload(cube, this.cube, 0);
        } finally {
            MemoryUtil.memFree(quads);
            MemoryUtil.memFree(cube);
        }
    }

    public void close() {
        this.quad.close();
        this.cube.close();
    }

    private static ByteBuffer quadIndicesFull() {
        // 16384 quads × 6 u16 indices = 196KiB — larger than LWJGL's default MemoryStack.
        var buf = MemoryUtil.memCalloc(16384 * 6 * 2);
        for (int i = 0; i < 16384; i++) {
            int base = i * 4;
            buf.putShort((short) (base + 0)).putShort((short) (base + 1)).putShort((short) (base + 2));
            buf.putShort((short) (base + 2)).putShort((short) (base + 1)).putShort((short) (base + 3));
        }
        buf.flip();
        return buf;
    }

    private static ByteBuffer cubeIndices() {
        var buf = MemoryUtil.memCalloc(36 * 2);
        // -Z
        quad(buf, 0, 2, 1, 3);
        // +Z
        quad(buf, 4, 5, 6, 7);
        // -X
        quad(buf, 0, 1, 4, 5);
        // +X
        quad(buf, 2, 3, 6, 7);
        // -Y
        quad(buf, 0, 4, 2, 6);
        // +Y
        quad(buf, 1, 5, 3, 7);
        buf.flip();
        return buf;
    }

    private static void quad(ByteBuffer buf, int a, int b, int c, int d) {
        buf.putShort((short) a).putShort((short) b).putShort((short) c);
        buf.putShort((short) c).putShort((short) b).putShort((short) d);
    }
}
