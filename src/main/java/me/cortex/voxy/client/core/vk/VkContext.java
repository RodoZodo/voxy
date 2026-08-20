package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * Captures Minecraft's active Vulkan backend (set up in {@code Minecraft.<init>} when the
 * "Prefer Vulkan (Experimental)" graphics API is active) and exposes the raw device, queues
 * and VMA allocator to the rest of the Voxy rendering stack.
 *
 * <p>Everything is only valid when {@link #isVulkanActive()} is true; otherwise the mod must
 * disable itself (it is a Vulkan-only renderer, OpenGL has no fallback path anymore).
 */
public final class VkContext {
    public static final VkContext INSTANCE = new VkContext();

    private volatile VulkanDevice device;
    private volatile VkCapabilities capabilities;

    private VkContext() {
    }

    /** Called from the VulkanDevice constructor mixin, on the render thread during startup. */
    public void capture(VulkanDevice device) {
        this.device = device;
        this.capabilities = new VkCapabilities(device.vkDevice().getPhysicalDevice(), device.instance());
    }

    /**
     * Whether the user asked for the Vulkan renderer. Single source of truth with Sodium: Sodium's
     * "Graphics API" dropdown binds to the exact same vanilla option
     * ({@code Options.preferredGraphicsBackend()}), so reading it here covers both Minecraft and
     * Sodium UIs.
     */
    public boolean isVulkanSelected() {
        var options = Minecraft.getInstance().options;
        if (options == null) {
            return false;
        }
        return options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
    }

    /** True when the Vulkan backend actually runs (a {@link VulkanDevice} was created). */
    public boolean isVulkanActive() {
        return this.device != null;
    }

    /**
     * Master gate: Voxy (Vulkan-only) may activate only when the user selected Vulkan AND the
     * backend actually came up. Otherwise the mod must deactivate itself.
     */
    public boolean shouldActivate() {
        return this.isVulkanSelected() && this.isVulkanActive();
    }

    /** @return a human-readable reason for deactivation, or {@code null} when Voxy may run */
    @Nullable
    public String getDeactivationReason() {
        if (this.isVulkanActive()) {
            if (!this.isVulkanSelected()) {
                return "Voxy requires the experimental Vulkan renderer. Set 'Graphics API' to \"Prefer Vulkan (Experimental)\" "
                        + "(or launch with --graphicsBackend vulkan), then restart the game.";
            }
            return null;
        }
        if (!this.isVulkanSelected()) {
            return "Voxy requires the experimental Vulkan renderer (Graphics API is not set to \"Prefer Vulkan (Experimental)\"). "
                    + "Restart the game to apply the change.";
        }
        return "Vulkan was selected but could not be activated (the driver fell back to OpenGL). Voxy is disabled.";
    }

    @Nullable
    public VulkanDevice device() {
        return this.device;
    }

    @Nullable
    public VkDevice vkDevice() {
        var dev = this.device;
        return dev == null ? null : dev.vkDevice();
    }

    @Nullable
    public VkPhysicalDevice physicalDevice() {
        var dev = this.device;
        return dev == null ? null : dev.vkDevice().getPhysicalDevice();
    }

    /** @return the VMA allocator handle, or -1 when the Vulkan backend is not active */
    public long vmaAllocator() {
        var dev = this.device;
        return dev == null ? -1L : dev.vma();
    }

    @Nullable
    public VulkanQueue graphicsQueue() {
        var dev = this.device;
        return dev == null ? null : dev.graphicsQueue();
    }

    @Nullable
    public VulkanQueue computeQueue() {
        var dev = this.device;
        return dev == null ? null : dev.computeQueue();
    }

    @Nullable
    public VulkanQueue transferQueue() {
        var dev = this.device;
        return dev == null ? null : dev.transferQueue();
    }

    @Nullable
    public VulkanInstance instance() {
        var dev = this.device;
        return dev == null ? null : dev.instance();
    }

    @Nullable
    public VkCapabilities capabilities() {
        return this.capabilities;
    }

    public boolean hasMeshShader() {
        return this.capabilities != null && this.capabilities.meshShader;
    }

    @Override
    public String toString() {
        return this.device == null ? "VkContext[inactive]" : "VkContext[active: " + this.capabilities + "]";
    }
}
