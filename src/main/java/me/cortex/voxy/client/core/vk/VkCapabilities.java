package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK11.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_BASIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_CLUSTERED_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUCCESS;
import static org.lwjgl.vulkan.VK11.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

/**
 * Feature/capability detection for the Vulkan backend, ported from the GL-era {@code
 * me.cortex.voxy.client.core.gl.Capabilities}. Queried once when the backend is captured.
 *
 * <p>Voxy's hard requirements for GPU LoD <em>drawing</em>: Vulkan 1.2 baseline (guaranteed by
 * MC's own renderer), compute, subgroup basic ops, and dynamic rendering. {@code shaderInt64}
 * is optional (GPU voxel mip / GPU mesher); without it those paths fall back to CPU. Subgroup
 * clustered is optional (HiZ chain); Apple GPUs via MoltenVK often lack it.
 */
public final class VkCapabilities {
    // VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT = 1000291000.
    // LWJGL's lwjgl-vulkan jar does not expose the VK_EXT_mesh_shader constants, so keep the
    // registry value here (VK_EXT_mesh_shader is extension 291, first struct offset).
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT = 1000291000;
    private static final String VK_EXT_MESH_SHADER_EXTENSION_NAME = "VK_EXT_mesh_shader";

    // The lwjgl-vulkan build shipped with MC 26.2 omits these two spec constants, so use the
    // registry values directly (the 1.3/1.4 neighbours, 53/55, are present and confirm the run).
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES = 49;
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES = 51;

    public final String deviceName;
    public final int vendorId;
    public final int deviceId;
    public final int apiVersion;

    public final boolean shaderInt64;
    public final boolean sparseResidencyBuffer;
    public final boolean vertexPipelineStoresAndAtomics;
    public final boolean fragmentStoresAndAtomics;

    public final boolean subgroup;
    public final boolean subgroupBasic;
    public final boolean subgroupArithmetic;
    public final boolean subgroupClustered;
    public final boolean subgroupQuad;

    public final boolean dynamicRendering;
    public final boolean hostQueryReset;

    public final boolean meshShader;
    public final boolean taskShader;
    /** Real multi-draw-indirect-count. MoltenVK often advertises a 1.2 bit without the COUNT commands. */
    public final boolean drawIndirectCount;

    public final long totalDeviceMemory;

    public VkCapabilities(VkPhysicalDevice physicalDevice, VulkanInstance instance) {
        try (var stack = MemoryStack.stackPush()) {
            var props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            this.deviceName = props.deviceNameString();
            this.vendorId = props.vendorID();
            this.deviceId = props.deviceID();
            this.apiVersion = props.apiVersion();

            var memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
            long maxHeap = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                var heap = memProps.memoryHeaps(i);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    maxHeap = Math.max(maxHeap, heap.size());
                }
            }
            this.totalDeviceMemory = maxHeap;

            // ---- Features chain: core -> 1.1 -> 1.2 -> 1.3 -> (EXT mesh shader) ----
            var features2 = VkPhysicalDeviceFeatures2.calloc(stack);
            var features11 = VkPhysicalDeviceVulkan11Features.calloc(stack);
            var features12 = VkPhysicalDeviceVulkan12Features.calloc(stack);
            var features13 = VkPhysicalDeviceVulkan13Features.calloc(stack);

            features2.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
            features11.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES);
            features12.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
            features13.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);

            features2.pNext(features11.address());
            features11.pNext(features12.address());
            features12.pNext(features13.address());

            VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = null;
            if (deviceHasExtension(physicalDevice, VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
                meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack);
                meshFeatures.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT);
                features13.pNext(meshFeatures.address());
            }

            vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

            var f = features2.features();
            this.shaderInt64 = f.shaderInt64();
            this.sparseResidencyBuffer = f.sparseResidencyBuffer();
            this.vertexPipelineStoresAndAtomics = f.vertexPipelineStoresAndAtomics();
            this.fragmentStoresAndAtomics = f.fragmentStoresAndAtomics();

            this.dynamicRendering = features13.dynamicRendering();
            this.hostQueryReset = features12.hostQueryReset();

            boolean hasCountExt = deviceHasExtension(physicalDevice, "VK_KHR_draw_indirect_count");
            boolean featCount = features12.drawIndirectCount();
            // Apple + feature-bit-only is MoltenVK mapping Metal indirect draws, not COUNT.
            this.drawIndirectCount = hasCountExt || (featCount && this.vendorId != 0x106B);

            if (meshFeatures != null) {
                this.meshShader = meshFeatures.meshShader();
                this.taskShader = meshFeatures.taskShader();
            } else {
                this.meshShader = false;
                this.taskShader = false;
            }

            // ---- Subgroup is a property, not a feature ----
            var props2 = VkPhysicalDeviceProperties2.calloc(stack);
            props2.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
            var subgroupProps = VkPhysicalDeviceSubgroupProperties.calloc(stack);
            subgroupProps.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES);
            props2.pNext(subgroupProps.address());

            vkGetPhysicalDeviceProperties2(physicalDevice, props2);

            int supportedOps = subgroupProps.supportedOperations();
            this.subgroup = true; // Vulkan 1.1+ always has subgroup support
            this.subgroupBasic = (supportedOps & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0;
            this.subgroupArithmetic = (supportedOps & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
            this.subgroupClustered = (supportedOps & VK_SUBGROUP_FEATURE_CLUSTERED_BIT) != 0;
            this.subgroupQuad = subgroupProps.quadOperationsInAllStages();
        }
    }

    /** Features required to draw LoDs on the GPU. Int64/clustered are optional fallbacks. */
    public boolean isSystemSupported() {
        return this.dynamicRendering && this.subgroupBasic;
    }

    public boolean isAppleGpu() {
        return this.vendorId == 0x106B;
    }

    public String vendorLabel() {
        return switch (this.vendorId) {
            case 0x10DE -> "NVIDIA";
            case 0x1002 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x106B -> "Apple";
            default -> "vendor 0x" + Integer.toHexString(this.vendorId);
        };
    }

    public String hardFailureReason() {
        if (!this.dynamicRendering) return "dynamicRendering not supported (needs Vulkan 1.3)";
        if (!this.subgroupBasic) return "subgroupBasic not supported";
        if (this.totalDeviceMemory < (512L << 20)) return "VRAM <512 MiB";
        return null;
    }

    /**
     * Enumerate device extensions on the heap. NVIDIA advertises hundreds of extensions;
     * {@code VkExtensionProperties} is ~260 bytes each, which overflows LWJGL's default 64KiB
     * {@link MemoryStack} if allocated there.
     */
    private static boolean deviceHasExtension(VkPhysicalDevice physicalDevice, String name) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int err = vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null);
            if (err != VK_SUCCESS) {
                return false;
            }
            int n = count.get(0);
            if (n <= 0) {
                return false;
            }
            if (n > 4096) {
                n = 4096;
            }
            var props = VkExtensionProperties.calloc(n);
            try {
                count.put(0, n);
                err = vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, props);
                if (err != VK_SUCCESS) {
                    return false;
                }
                int got = Math.min(count.get(0), props.capacity());
                for (int i = 0; i < got; i++) {
                    if (name.equals(props.get(i).extensionNameString())) {
                        return true;
                    }
                }
                return false;
            } finally {
                props.free();
            }
        }
    }

    @Override
    public String toString() {
        return "VkCapabilities{" + this.vendorLabel() + " " + this.deviceName + " (" + String.format("0x%04x:0x%04x", this.vendorId, this.deviceId)
                + "), int64=" + this.shaderInt64 + ", subgroup=" + this.subgroupBasic + "/" + this.subgroupArithmetic
                + "/" + this.subgroupClustered + ", drawIndirectCount=" + this.drawIndirectCount
                + ", sparse=" + this.sparseResidencyBuffer + ", mesh=" + this.meshShader
                + ", dynamicRendering=" + this.dynamicRendering + ", vram=" + (this.totalDeviceMemory >> 20) + "MiB}";
    }
}
