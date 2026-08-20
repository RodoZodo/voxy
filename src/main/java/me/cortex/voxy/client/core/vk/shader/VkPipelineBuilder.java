package me.cortex.voxy.client.core.vk.shader;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/** Graphics/compute pipeline creation for Voxy's own shaders (dynamic rendering). */
public final class VkPipelineBuilder {
    /** Vertex input layout: one binding of interleaved attributes. */
    public record VertexInput(VkVertexInputBindingDescription.Buffer bindings, VkVertexInputAttributeDescription.Buffer attributes) {
        public static VertexInput float3(int binding, int location, int stride) {
            try (var stack = MemoryStack.stackPush()) {
                var bindings = VkVertexInputBindingDescription.calloc(1, stack);
                bindings.get(0).binding(binding).stride(stride).inputRate(0);
                var attributes = VkVertexInputAttributeDescription.calloc(1, stack);
                attributes.get(0).binding(binding).location(location).format(15 /* R32G32B32_SFLOAT */).offset(0);
                // copies must survive the stack pop
                var b2 = VkVertexInputBindingDescription.calloc(1).put(bindings.get(0)).flip();
                var a2 = VkVertexInputAttributeDescription.calloc(1).put(attributes.get(0)).flip();
                return new VertexInput(b2, a2);
            }
        }
    }

    public static long createGraphics(VkDevice device, VkPipelineLayout layout, VkShaderModule vertex, VkShaderModule fragment,
                                      int colorFormat, int depthFormat, @Nullable VertexInput vertexInput,
                                      int depthCompareOp, boolean depthTest, boolean depthWrite) {
        return createGraphics(device, layout, vertex, fragment, colorFormat, depthFormat, vertexInput, depthCompareOp, depthTest, depthWrite, false);
    }

    public static long createGraphics(VkDevice device, VkPipelineLayout layout, VkShaderModule vertex, VkShaderModule fragment,
                                      int colorFormat, int depthFormat, @Nullable VertexInput vertexInput,
                                      int depthCompareOp, boolean depthTest, boolean depthWrite, boolean translucentBlend) {
        ensureCreated(device, vertex, fragment);
        try (var stack = MemoryStack.stackPush()) {
            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            stages.get(0).stage(VK_SHADER_STAGE_VERTEX_BIT);
            stages.get(0).module(vertex.handle());
            stages.get(0).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            stages.get(1).stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            stages.get(1).module(fragment.handle());
            stages.get(1).pName(stack.UTF8("main"));

            var vi = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vi.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            if (vertexInput != null) {
                vi.pVertexBindingDescriptions(vertexInput.bindings());
                vi.pVertexAttributeDescriptions(vertexInput.attributes());
            }

            var ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            ia.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            ia.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            var vs = VkPipelineViewportStateCreateInfo.calloc(stack);
            vs.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            vs.viewportCount(1);
            vs.scissorCount(1);

            var rs = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rs.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rs.polygonMode(VK_POLYGON_MODE_FILL);
            rs.cullMode(VK_CULL_MODE_NONE);
            rs.frontFace(VK_FRONT_FACE_CLOCKWISE);
            rs.lineWidth(1.0f);

            var ms = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            ms.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            ms.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            var ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            ds.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            ds.depthTestEnable(depthTest);
            ds.depthWriteEnable(depthWrite);
            ds.depthCompareOp(depthCompareOp);

            var cbState = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            cbState.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            cbState.logicOpEnable(false);
            var attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            attachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            if (translucentBlend) {
                attachment.blendEnable(true);
                attachment.srcColorBlendFactor(6); // VK_BLEND_FACTOR_SRC_ALPHA
                attachment.dstColorBlendFactor(7); // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                attachment.colorBlendOp(0); // VK_BLEND_OP_ADD
                attachment.srcAlphaBlendFactor(1); // VK_BLEND_FACTOR_ONE
                attachment.dstAlphaBlendFactor(7); // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                attachment.alphaBlendOp(0); // VK_BLEND_OP_ADD
            }
            cbState.pAttachments(attachment);

            var dyn = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dyn.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
            dyn.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            var rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack);
            rendering.sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO);
            rendering.colorAttachmentCount(1);
            rendering.pColorAttachmentFormats(stack.ints(colorFormat));
            rendering.depthAttachmentFormat(depthFormat);

            var ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            var info = ci.get(0);
            info.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            info.pNext(rendering.address());
            info.stageCount(2);
            info.pStages(stages);
            info.pVertexInputState(vi);
            info.pInputAssemblyState(ia);
            info.pViewportState(vs);
            info.pRasterizationState(rs);
            info.pMultisampleState(ms);
            info.pDepthStencilState(ds);
            info.pColorBlendState(cbState);
            info.pDynamicState(dyn);
            info.layout(layout.handle());

            long[] p = new long[1];
            int err = vkCreateGraphicsPipelines(device, 0L, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateGraphicsPipelines failed: " + err);
            }
            return p[0];
        }
    }

    public static long createCompute(VkDevice device, VkPipelineLayout layout, VkShaderModule compute) {
        ensureCreated(device, compute);
        try (var stack = MemoryStack.stackPush()) {
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack);
            stage.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            stage.stage(VK_SHADER_STAGE_COMPUTE_BIT);
            stage.module(compute.handle());
            stage.pName(stack.UTF8("main"));

            var ci = VkComputePipelineCreateInfo.calloc(1, stack);
            var info = ci.get(0);
            info.sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
            info.stage(stage);
            info.layout(layout.handle());

            var p = stack.mallocLong(1);
            int err = vkCreateComputePipelines(device, 0L, ci, null, p);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateComputePipelines failed: " + err);
            }
            return p.get(0);
        }
    }

    /** SPIR-V is host-side until {@link VkShaderModule#create}; a 0 handle crashes NVIDIA's driver. */
    private static void ensureCreated(VkDevice device, VkShaderModule... modules) {
        for (var module : modules) {
            if (module != null) {
                module.create(device);
            }
        }
    }

    private VkPipelineBuilder() {
    }
}
