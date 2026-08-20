package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import me.cortex.voxy.client.mixin.minecraft.GpuDeviceAccessor;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.lang.reflect.Field;

/**
 * Captures Minecraft's active Vulkan backend (set up in {@code Minecraft.<init>} when the
 * "Prefer Vulkan (Experimental)" graphics API is active) and exposes the raw device, queues
 * and VMA allocator to the rest of the Voxy rendering stack.
 *
 * <p>Everything is only valid when {@link #isVulkanActive()} is true; otherwise the mod must
 * disable itself (it is a Vulkan-only renderer, OpenGL has no fallback path anymore).
 *
 * <p>Capture must never throw: {@code VulkanDevice.<init>} runs inside Minecraft's graphics-backend
 * startup, and an exception there trips the crash ladder (Graphics API reset to Default/OpenGL).
 */
public final class VkContext {
    public static final VkContext INSTANCE = new VkContext();

    private volatile VulkanDevice device;
    private volatile VkCapabilities capabilities;

    private VkContext() {
    }

    /**
     * Best-effort capture from a constructed {@link VulkanDevice}. Safe to call from a constructor
     * mixin — never throws.
     */
    public void capture(VulkanDevice device) {
        if (device == null || this.device != null) {
            return;
        }
        try {
            this.device = device;
            this.capabilities = new VkCapabilities(device.vkDevice().getPhysicalDevice(), device.instance());
        } catch (Throwable t) {
            this.device = null;
            this.capabilities = null;
            Logger.warn("Voxy (Vulkan): VulkanDevice capture failed", t);
        }
    }

    /**
     * Unwrap {@link GpuDevice#backend} (accessor mixin, then reflection, then a one-level field walk
     * for Lunar/Genesis wrappers). Safe to call from {@code RenderSystem.initRenderer} /
     * {@code Minecraft.<init>} — never throws.
     *
     * <p>If the live {@link GpuDevice} is not Vulkan (OpenGL fallback after a failed Vulkan try),
     * any previous capture is dropped so Voxy does not keep a destroyed device.
     */
    public void captureFromGpuDevice(@Nullable GpuDevice gpu) {
        if (gpu == null) {
            return;
        }
        try {
            var info = gpu.getDeviceInfo();
            Logger.info("Voxy (Vulkan): GpuDevice backend=" + info.backendName()
                    + " type=" + info.type()
                    + " renderer=" + info.name()
                    + " vendor=" + info.vendorName());
            VulkanDevice found = findVulkanDevice(readBackend(gpu));
            if (found == null) {
                found = findVulkanDevice(gpu);
            }
            if (found != null) {
                if (this.device != found) {
                    this.device = null;
                    this.capabilities = null;
                    capture(found);
                }
            } else {
                if (this.device != null) {
                    Logger.warn("Voxy (Vulkan): active GpuDevice is " + info.backendName()
                            + ", not Vulkan — dropping captured device");
                }
                this.device = null;
                this.capabilities = null;
            }
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): GpuDevice inspect failed", t);
        }
    }

    @Nullable
    private static GpuDeviceBackend readBackend(GpuDevice gpu) {
        try {
            if (gpu instanceof GpuDeviceAccessor accessor) {
                return accessor.voxy$backend();
            }
        } catch (Throwable ignored) {
        }
        Object raw = readDeclaredField(gpu, "backend");
        return raw instanceof GpuDeviceBackend backend ? backend : null;
    }

    @Nullable
    private static Object readDeclaredField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static VulkanDevice findVulkanDevice(Object obj) {
        if (obj instanceof VulkanDevice vd) {
            return vd;
        }
        if (obj == null) {
            return null;
        }
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof VulkanDevice vd) {
                        return vd;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    /** True when the Vulkan backend actually runs (a {@link VulkanDevice} was captured). */
    public boolean isVulkanActive() {
        return this.device != null;
    }

    /**
     * Master gate: Voxy (Vulkan-only) may activate only when the Vulkan backend actually came up.
     * The options dropdown is used for the deactivation message, not as a hard gate — Minecraft's
     * crash ladder can reset the option to Default while a captured device is the real signal.
     */
    public boolean shouldActivate() {
        return this.isVulkanActive();
    }

    /** @return a human-readable reason for deactivation, or {@code null} when Voxy may run */
    @Nullable
    public String getDeactivationReason() {
        if (this.isVulkanActive()) {
            return null;
        }
        if (!this.isVulkanSelected()) {
            return "Voxy requires the experimental Vulkan renderer. Minecraft is on OpenGL "
                    + "(a previous crash resets Graphics API to Default). Set 'Graphics API' to "
                    + "\"Prefer Vulkan (Experimental)\" in Video Settings, then restart.";
        }
        return "Vulkan was selected but could not be activated (the driver fell back to OpenGL, "
                + "or Lunar/Ichor blocked VulkanDevice capture). Voxy is disabled.";
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
