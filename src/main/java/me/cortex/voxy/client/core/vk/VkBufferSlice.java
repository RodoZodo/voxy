package me.cortex.voxy.client.core.vk;

/** A range within a {@link VkBuffer}. */
public record VkBufferSlice(VkBuffer buffer, long offset, long length) {
}
