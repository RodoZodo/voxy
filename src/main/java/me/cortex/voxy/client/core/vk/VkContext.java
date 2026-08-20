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
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

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
    private volatile String lastBackendName = "unknown";
    private volatile String lastGpuClass = "unknown";
    private volatile String lastInspectLog;

    private VkContext() {
    }

    /**
     * Best-effort capture from a constructed {@link VulkanDevice}. Safe to call from a constructor
     * mixin — never throws.
     */
    public void capture(VulkanDevice device) {
        if (device == null) {
            return;
        }
        if (this.device == device) {
            return;
        }
        try {
            // Store the device only. Capability query enumerates every device extension and must
            // not run inside VulkanDevice.<init> / Minecraft graphics startup (crash ladder +
            // LWJGL MemoryStack overflow on NVIDIA's ~200 extensions).
            this.device = device;
            this.capabilities = null;
            Logger.info("Voxy (Vulkan): captured VulkanDevice (capability query deferred)");
        } catch (Throwable t) {
            this.device = device;
            Logger.warn("Voxy (Vulkan): capture logging failed", t);
        }
    }

    /**
     * Query device features on the game thread after Minecraft has finished constructing
     * {@link VulkanDevice}. Safe to call repeatedly; never throws.
     */
    public void ensureCapabilities() {
        var dev = this.device;
        if (dev == null || this.capabilities != null) {
            return;
        }
        try {
            this.capabilities = new VkCapabilities(dev.vkDevice().getPhysicalDevice(), dev.instance());
            Logger.info("Voxy (Vulkan): caps=" + this.capabilities);
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): capability query failed", t);
        }
    }

    /** Capture from {@link GpuDevice}'s constructor argument. Never throws. */
    public void captureFromBackend(@Nullable Object backend) {
        try {
            if (backend == null) {
                Logger.warn("Voxy (Vulkan): GpuDevice constructed with null backend");
                return;
            }
            Logger.info("Voxy (Vulkan): GpuDevice backend arg class=" + backend.getClass().getName());
            if (backend instanceof VulkanDevice vd) {
                capture(vd);
                return;
            }
            VulkanDevice found = findVulkanDeviceDeep(backend, 0, new IdentityHashMap<>());
            if (found != null) {
                capture(found);
                return;
            }
            Logger.warn("Voxy (Vulkan): backend is not a VulkanDevice: " + backend.getClass().getName());
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): captureFromBackend failed", t);
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
            this.lastGpuClass = gpu.getClass().getName();
            var info = gpu.getDeviceInfo();
            this.lastBackendName = info == null || info.backendName() == null ? "unknown" : info.backendName();
            String preferred = "unknown";
            try {
                var options = Minecraft.getInstance().options;
                if (options != null && options.preferredGraphicsBackend() != null) {
                    preferred = String.valueOf(options.preferredGraphicsBackend().get());
                }
            } catch (Throwable ignored) {
            }
            String inspect = "backend=" + this.lastBackendName
                    + " preferred=" + preferred
                    + " type=" + (info == null ? "?" : info.type())
                    + " renderer=" + (info == null ? "?" : info.name())
                    + " vendor=" + (info == null ? "?" : info.vendorName())
                    + " class=" + this.lastGpuClass
                    + " captured=" + (this.device != null);
            if (!inspect.equals(this.lastInspectLog)) {
                this.lastInspectLog = inspect;
                Logger.info("Voxy (Vulkan): GpuDevice " + inspect);
            }

            VulkanDevice found = findVulkanDeviceDeep(gpu, 0, new IdentityHashMap<>());
            if (found != null) {
                if (this.device != found) {
                    this.device = null;
                    this.capabilities = null;
                    capture(found);
                }
                return;
            }

            if (looksLikeVulkan(this.lastBackendName)) {
                // Lunar wrappers can hide VulkanDevice behind a delegate. Do not drop a
                // successful MixinVulkanDevice capture just because unwrap failed this tick.
                Logger.warn("Voxy (Vulkan): backend is " + this.lastBackendName
                        + " but VulkanDevice unwrap failed; keeping captured=" + (this.device != null));
                return;
            }

            if (this.device != null) {
                Logger.warn("Voxy (Vulkan): active GpuDevice is " + this.lastBackendName
                        + ", not Vulkan — dropping captured device");
            }
            this.device = null;
            this.capabilities = null;
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): GpuDevice inspect failed", t);
        }
    }

    public static boolean looksLikeVulkan(@Nullable String backendName) {
        if (backendName == null) {
            return false;
        }
        String n = backendName.toLowerCase();
        return n.contains("vulkan") || n.contains("vk");
    }

    public String lastBackendName() {
        return this.lastBackendName;
    }

    public String lastGpuClass() {
        return this.lastGpuClass;
    }

    @Nullable
    private static GpuDeviceBackend readBackend(GpuDevice gpu) {
        try {
            if (gpu instanceof GpuDeviceAccessor accessor) {
                var backend = accessor.voxy$backend();
                if (backend != null) {
                    return backend;
                }
            }
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): GpuDeviceAccessor failed", t);
        }
        Object raw = readDeclaredField(gpu, "backend");
        if (raw == null) {
            raw = readFieldUnsafe(gpu, "backend");
        }
        if (raw != null && !(raw instanceof GpuDeviceBackend)) {
            Logger.warn("Voxy (Vulkan): GpuDevice.backend class=" + raw.getClass().getName());
        }
        return raw instanceof GpuDeviceBackend backend ? backend : null;
    }

    @Nullable
    private static Object readFieldUnsafe(Object obj, String name) {
        try {
            Field f = null;
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    f = c.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                return null;
            }
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
            return unsafe.getObject(obj, unsafe.objectFieldOffset(f));
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): unsafe read of " + name + " failed", t);
            return null;
        }
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
    private static VulkanDevice findVulkanDeviceDeep(Object obj, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (obj == null || depth > 6) {
            return null;
        }
        if (obj instanceof VulkanDevice vd) {
            return vd;
        }
        if (seen.put(obj, Boolean.TRUE) != null) {
            return null;
        }
        Class<?> type = obj.getClass();
        String name = type.getName();
        if (type.isPrimitive() || type.isEnum() || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.")) {
            return null;
        }
        if (name.startsWith("net.minecraft.client.Minecraft")
                || name.contains("ClientLevel")
                || name.contains("client.gui")
                || name.contains("TextureManager")) {
            return null;
        }
        if (obj instanceof GpuDevice gpu) {
            VulkanDevice fromBackend = findVulkanDeviceDeep(readBackend(gpu), depth + 1, seen);
            if (fromBackend != null) {
                return fromBackend;
            }
        }
        Class<?> c = type;
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                try {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    f.setAccessible(true);
                    VulkanDevice found = findVulkanDeviceDeep(f.get(obj), depth + 1, seen);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
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

    public boolean gpuLooksLikeVulkan() {
        return this.isVulkanActive() || looksLikeVulkan(this.lastBackendName);
    }

    /** @return a human-readable reason for deactivation, or {@code null} when Voxy may run */
    @Nullable
    public String getDeactivationReason() {
        if (this.isVulkanActive()) {
            return null;
        }
        if (!this.isVulkanSelected()) {
            return "Voxy disabled: this session is OpenGL. Set Graphics API to Prefer Vulkan (Experimental) and restart.";
        }
        return "Voxy disabled: Graphics API is Prefer Vulkan, but this session still started as OpenGL.";
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
