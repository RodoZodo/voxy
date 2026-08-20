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
 * <p>Voxy's hard requirements: Vulkan 1.2 baseline (guaranteed by MC's own renderer), compute
 * support, {@code shaderInt64}, subgroup ops, indirect drawing, sparse residency (optional
 * fallback), and optionally mesh shaders.
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
    public final boolean subgroupQuad;

    public final boolean dynamicRendering;
    public final boolean hostQueryReset;

    public final boolean meshShader;
    public final boolean taskShader;

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
            if (deviceHasExtension(physicalDevice, stack, VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
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
            this.subgroupQuad = subgroupProps.quadOperationsInAllStages();
        }
    }

    public boolean isSystemSupported() {
        return this.dynamicRendering && this.subgroupBasic && this.shaderInt64;
    }

    public String hardFailureReason() {
        if (!this.dynamicRendering) return "dynamicRendering not supported (needs Vulkan 1.3)";
        if (!this.subgroupBasic) return "subgroupBasic not supported";
        if (!this.shaderInt64) return "shaderInt64 not supported (needs int64 for mesh/mip)";
        if (this.totalDeviceMemory < (512L << 20)) return "VRAM <512 MiB";
        return null;
    }

    private static boolean deviceHasExtension(VkPhysicalDevice physicalDevice, MemoryStack stack, String name) {
        IntBuffer count = stack.mallocInt(1);
        int err = vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null);
        if (err != VK_SUCCESS) {
            return false;
        }
        var props = VkExtensionProperties.calloc(count.get(0), stack);
        err = vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, props);
        if (err != VK_SUCCESS) {
            return false;
        }
        for (int i = 0; i < props.capacity(); i++) {
            if (name.equals(props.get(i).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "VkCapabilities{" + this.deviceName + " (" + String.format("0x%04x:0x%04x", this.vendorId, this.deviceId)
                + "), int64=" + this.shaderInt64 + ", subgroup=" + this.subgroupBasic + "/" + this.subgroupArithmetic
                + ", sparse=" + this.sparseResidencyBuffer + ", mesh=" + this.meshShader
                + ", dynamicRendering=" + this.dynamicRendering + ", vram=" + (this.totalDeviceMemory >> 20) + "MiB}";
    }
}
