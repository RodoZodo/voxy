package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.shader.VkPipelineBuilder;
import me.cortex.voxy.client.core.vk.shader.VkPipelineLayout;
import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderModule;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.Map;

import static org.lwjgl.vulkan.KHRDrawIndirectCount.vkCmdDrawIndexedIndirectCountKHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan port of {@code MDICSectionRenderer} — the VDIC indirect-draw renderer.
 * Phase 4C initial: opaque path only, cull stubbed (all visible), no translucency/temporal.
 * Full cull/prefixsum/translucent follow.
 */
public final class VkSectionRenderer implements AutoCloseable {
    private final VkDevice device;
    private final VkBuffer uniformBuffer;
    private final VkBuffer drawCallBuffer;
    private final VkBuffer drawCountCallBuffer;
    private final VkBuffer positionScratchBuffer;
    private final VkBuffer distanceCountBuffer;

    private final VkPipelineLayout prepLayout;
    private final long prepPipeline;
    private final VkPipelineLayout cullLayout;
    private final long cullPipeline;
    private final VkPipelineLayout cmdgenLayout;
    private final long cmdgenPipeline;
    private final VkPipelineLayout prefixSumLayout;
    private final long prefixSumPipeline;
    private final VkPipelineLayout translucentLayout;
    private final long translucentPipeline;
    private final VkPipelineLayout quadsLayout;
    private final long quadsPipeline;
    private final VkPipelineLayout quadsTexturedLayout;
    private long quadsTexturedPipeline;
    private final VkPipelineLayout quadsTranslucentLayout;
    private long quadsTranslucentPipeline;
    private final VkBuffer modelBuffer;
    private final VkBuffer modelColourBuffer;
    private final VkTexture atlasTexture;
    private final VkSampler atlasSampler;
    private final boolean drawIndirectCount;
    private final VkBuffer hostDrawCountBuffer;

    public VkSectionRenderer(VkDevice device, long vma, VkShaderCompiler compiler) {
        this.device = device;
        var caps = VkContext.INSTANCE.capabilities();
        this.drawIndirectCount = caps != null && caps.drawIndirectCount;
        this.uniformBuffer = VkBuffer.hostVisible(vma, 1024, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        this.drawCallBuffer = VkBuffer.deviceLocal(vma, 12_000_000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.drawCountCallBuffer = VkBuffer.deviceLocal(vma, 1024, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        this.positionScratchBuffer = VkBuffer.deviceLocal(vma, 3_200_000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.distanceCountBuffer = VkBuffer.deviceLocal(vma, 404_096, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.modelBuffer = VkBuffer.deviceLocal(vma, 64L * 65536, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.modelColourBuffer = VkBuffer.deviceLocal(vma, 4L * 65536, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if (this.drawIndirectCount) {
            this.hostDrawCountBuffer = null;
        } else {
            this.hostDrawCountBuffer = VkBuffer.hostVisible(vma, 32, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.hostDrawCountBuffer.mapPersistent();
            Logger.info("Voxy (Vulkan): LoD draws use vkCmdDrawIndexedIndirect (no drawIndirectCount; typical on MoltenVK/Apple)");
        }
        var phys = VkContext.INSTANCE.physicalDevice();
        this.atlasTexture = new VkTexture(device, phys, 256, 256, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        this.atlasSampler = new VkSampler(device);

        this.prepLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        var prep = compile(compiler, "lod/gl46/prep.comp", Map.of("DRAW_COUNT_BUFFER_BINDING","1","INDIRECT_SECTION_LOOKUP_BINDING","2"));
        this.prepPipeline = VkPipelineBuilder.createCompute(device, this.prepLayout, prep);
        prep.free(device);

        // Cull raster — draws section AABBs and writes visibility (early_fragment_tests, depth test against HiZ)
        VkPipelineLayout cullL = null; long cullP = 0;
        try {
            cullL = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                    new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
            }, true);
            var cv = compile(compiler, "lod/gl46/cull/raster.vert", Map.of(
                    "SECTION_METADATA_BUFFER_BINDING","1","VISIBILITY_BUFFER_BINDING","2","INDIRECT_SECTION_LOOKUP_BINDING","3"));
            var cf = compile(compiler, "lod/gl46/cull/raster.frag", Map.of("VISIBILITY_BUFFER_BINDING","2"));
            cullP = VkPipelineBuilder.createGraphics(device, cullL, cv, cf,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, null,
                    VK_COMPARE_OP_GREATER_OR_EQUAL, false, false);
            cv.free(device); cf.free(device);
        } catch (Exception e) {
            if (cullL != null) cullL.close();
            cullL = null;
            me.cortex.voxy.common.Logger.warn("VkSectionRenderer cull pipeline failed (fallback to all-visible): " + e.getMessage());
        }
        this.cullLayout = cullL; this.cullPipeline = cullP;

        this.cmdgenLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        var cmdgen = compile(compiler, "lod/gl46/cmdgen.comp", Map.of(
                "DRAW_BUFFER_BINDING","1","DRAW_COUNT_BUFFER_BINDING","2","SECTION_METADATA_BUFFER_BINDING","3",
                "VISIBILITY_BUFFER_BINDING","4","INDIRECT_SECTION_LOOKUP_BINDING","5","POSITION_SCRATCH_BINDING","6",
                "POSITION_SCRATCH_ACCESS","writeonly","TRANSLUCENT_DISTANCE_BUFFER_BINDING","7",
                "TRANSLUCENT_WRITE_BASE","1024","TEMPORAL_OFFSET","500000"));
        this.cmdgenPipeline = VkPipelineBuilder.createCompute(device, this.cmdgenLayout, cmdgen);
        cmdgen.free(device);

        this.prefixSumLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        var prefix = compile(compiler, "util/prefixsum/simple.comp", Map.of("IO_BUFFER","0"));
        this.prefixSumPipeline = VkPipelineBuilder.createCompute(device, this.prefixSumLayout, prefix);
        prefix.free(device);

        this.translucentLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
                new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT),
        }, true);
        var trans = compile(compiler, "lod/gl46/buildtranslucents.comp", Map.of(
                "DRAW_BUFFER_BINDING","1","DRAW_COUNT_BUFFER_BINDING","2","SECTION_METADATA_BUFFER_BINDING","3",
                "INDIRECT_SECTION_LOOKUP_BINDING","4","TRANSLUCENT_DISTANCE_BUFFER_BINDING","5",
                "TRANSLUCENT_WRITE_BASE","1024","TRANSLUCENT_OFFSET","400000"));
        this.translucentPipeline = VkPipelineBuilder.createCompute(device, this.translucentLayout, trans);
        trans.free(device);

        this.quadsLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_VERTEX_BIT),
        }, true);
        var tintDefsWhite = new java.util.HashMap<String,String>();
        tintDefsWhite.put("QUAD_BUFFER_BINDING","1");
        tintDefsWhite.put("MODEL_BUFFER_BINDING","3");
        tintDefsWhite.put("MODEL_COLOUR_BUFFER_BINDING","4");
        tintDefsWhite.put("POSITION_SCRATCH_BINDING","5");
        tintDefsWhite.put("LIGHTING_SAMPLER_BINDING","6");
        tintDefsWhite.put("NO_SHADE_FACE_TINT","1.0");
        tintDefsWhite.put("UP_FACE_TINT","1.0");
        tintDefsWhite.put("DOWN_FACE_TINT","0.9");
        tintDefsWhite.put("Z_AXIS_FACE_TINT","0.85");
        tintDefsWhite.put("X_AXIS_FACE_TINT","0.82");
        var qvWhite = compile(compiler, "lod/gl46/quads_white.vert", tintDefsWhite);
        var qfWhite = compile(compiler, "lod/gl46/quads_white.frag", Map.of());
        this.quadsPipeline = VkPipelineBuilder.createGraphics(device, this.quadsLayout, qvWhite, qfWhite,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, null,
                VK_COMPARE_OP_GREATER_OR_EQUAL, true, true);
        qvWhite.free(device); qfWhite.free(device);

        // Textured pipeline (quads3 + quads) — uses ModelStore atlas when available, fallback to white dummy otherwise
        VkPipelineLayout texturedLayout = null;
        long texturedPipe = 0;
        try {
            texturedLayout = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                    new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT),
            }, true);
            // Face-tint defines (match CardinalLighting.DEFAULT 1.0 fallback when level null)
            var tintDefs = new java.util.HashMap<String,String>();
            tintDefs.put("QUAD_BUFFER_BINDING","1"); tintDefs.put("MODEL_BUFFER_BINDING","3"); tintDefs.put("MODEL_COLOUR_BUFFER_BINDING","4");
            tintDefs.put("POSITION_SCRATCH_BINDING","5"); tintDefs.put("LIGHTING_SAMPLER_BINDING","6");
            tintDefs.put("BLOCK_MODEL_TEXTURE_BINDING","7"); tintDefs.put("DEPTH_TEXTURE_BINDING","2");
            // Cardinal lighting tints — use defaults (1.0) if level not yet available
            tintDefs.put("NO_SHADE_FACE_TINT","1.0"); tintDefs.put("UP_FACE_TINT","1.0"); tintDefs.put("DOWN_FACE_TINT","0.9");
            tintDefs.put("Z_AXIS_FACE_TINT","0.85"); tintDefs.put("X_AXIS_FACE_TINT","0.82");
            var qv = compile(compiler, "lod/gl46/quads3.vert", tintDefs);
            var qf = compile(compiler, "lod/gl46/quads.frag", tintDefs);
            texturedPipe = VkPipelineBuilder.createGraphics(device, texturedLayout, qv, qf,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, null,
                    VK_COMPARE_OP_GREATER_OR_EQUAL, true, true);
            qv.free(device); qf.free(device);
        } catch (Exception e) {
            if (texturedLayout != null) texturedLayout.close();
            texturedLayout = null;
            me.cortex.voxy.common.Logger.warn("VkSectionRenderer textured pipeline failed, fallback to white: " + e.getMessage());
        }
        this.quadsTexturedLayout = texturedLayout;
        this.quadsTexturedPipeline = texturedPipe;

        // Translucent pipeline — same shaders with TRANSLUCENT + blend (straight alpha)
        VkPipelineLayout transL = null; long transP = 0;
        try {
            transL = new VkPipelineLayout(device, new VkPipelineLayout.Binding[]{
                    new VkPipelineLayout.Binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                    new VkPipelineLayout.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_VERTEX_BIT),
                    new VkPipelineLayout.Binding(7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT),
            }, true);
            var tintDefs2 = new java.util.HashMap<String,String>();
            tintDefs2.put("QUAD_BUFFER_BINDING","1"); tintDefs2.put("MODEL_BUFFER_BINDING","3"); tintDefs2.put("MODEL_COLOUR_BUFFER_BINDING","4");
            tintDefs2.put("POSITION_SCRATCH_BINDING","5"); tintDefs2.put("LIGHTING_SAMPLER_BINDING","6");
            tintDefs2.put("BLOCK_MODEL_TEXTURE_BINDING","7"); tintDefs2.put("DEPTH_TEXTURE_BINDING","2");
            tintDefs2.put("TRANSLUCENT",""); tintDefs2.put("NO_SHADE_FACE_TINT","1.0"); tintDefs2.put("UP_FACE_TINT","1.0");
            tintDefs2.put("DOWN_FACE_TINT","0.9"); tintDefs2.put("Z_AXIS_FACE_TINT","0.85"); tintDefs2.put("X_AXIS_FACE_TINT","0.82");
            var qv2 = compile(compiler, "lod/gl46/quads3.vert", tintDefs2);
            var qf2 = compile(compiler, "lod/gl46/quads.frag", tintDefs2);
            transP = VkPipelineBuilder.createGraphics(device, transL, qv2, qf2,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, null,
                    VK_COMPARE_OP_GREATER_OR_EQUAL, true, false, true);
            qv2.free(device); qf2.free(device);
        } catch (Exception e) {
            if (transL != null) transL.close();
            transL = null;
            me.cortex.voxy.common.Logger.warn("VkSectionRenderer translucent pipeline failed: " + e.getMessage());
        }
        this.quadsTranslucentLayout = transL;
        this.quadsTranslucentPipeline = transP;
    }

    private static VkShaderModule compile(VkShaderCompiler compiler, String file, Map<String,String> defines) {
        var src = VkShaderCompiler.loadResource(file);
        return compiler.compile("voxy:"+file, src, file.endsWith(".vert") ? VkShaderStage.VERTEX : file.endsWith(".frag") ? VkShaderStage.FRAGMENT : VkShaderStage.COMPUTE, defines);
    }

    public void buildDrawCalls(VkCommandBuffer cb, VkBuffer indirectLookupBuffer, VkBuffer visibilityBuffer, VkSectionGeometryData geometryData, Matrix4f mvp, Vector3i baseSectionPos, int frameId, org.joml.Vector3f cameraSubPos) {
        // Upload uniform (92 bytes used, 1024 allocated)
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.calloc(1024);
            mvp.get(buf);
            baseSectionPos.get(64, buf);
            buf.putInt(76, frameId & 0x7fffffff);
            cameraSubPos.get(80, buf);
            this.uniformBuffer.write(buf);
        }
        VkSync.memoryBarrier(cb);

        // Prep (1,1,1)
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.prepPipeline);
            var u = VkDescriptorBufferInfo.calloc(1, stack); u.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(1024);
            var c = VkDescriptorBufferInfo.calloc(1, stack); c.get(0).buffer(this.drawCountCallBuffer.handle()).offset(0).range(1024);
            var l = VkDescriptorBufferInfo.calloc(1, stack); l.get(0).buffer(indirectLookupBuffer.handle()).offset(0).range(indirectLookupBuffer.size());
            var writes = VkWriteDescriptorSet.calloc(3, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER); writes.get(0).pBufferInfo(u);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(1).dstSet(0); writes.get(1).dstBinding(1); writes.get(1).descriptorCount(1); writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(1).pBufferInfo(c);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(2); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(l);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.prepLayout.handle(), 0, writes);
            vkCmdDispatch(cb, 1, 1, 1);
            VkSync.memoryBarrier(cb);
        }

        // Cull raster — pipeline is compiled (cullPipeline) but depth attachment wiring is deferred to use HiZ/main depth.
        // For this phase we keep all-visible (no depth cull) so cmdgen sees every section; the raster pipeline is ready for the next slice.
        // Zero distance histogram (opaque path doesn't strictly need it but cmdgen expects it)
        this.distanceCountBuffer.fill(cb, 0, 4096, 0);
        VkSync.memoryBarrier(cb);
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.cmdgenPipeline);
            var u = VkDescriptorBufferInfo.calloc(1, stack); u.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(1024);
            var d = VkDescriptorBufferInfo.calloc(1, stack); d.get(0).buffer(this.drawCallBuffer.handle()).offset(0).range(this.drawCallBuffer.size());
            var c = VkDescriptorBufferInfo.calloc(1, stack); c.get(0).buffer(this.drawCountCallBuffer.handle()).offset(0).range(1024);
            var m = VkDescriptorBufferInfo.calloc(1, stack); m.get(0).buffer(geometryData.metadataBuffer().handle()).offset(0).range(geometryData.metadataBuffer().size());
            var v = VkDescriptorBufferInfo.calloc(1, stack); v.get(0).buffer(visibilityBuffer.handle()).offset(0).range(visibilityBuffer.size());
            var l = VkDescriptorBufferInfo.calloc(1, stack); l.get(0).buffer(indirectLookupBuffer.handle()).offset(0).range(indirectLookupBuffer.size());
            var p = VkDescriptorBufferInfo.calloc(1, stack); p.get(0).buffer(this.positionScratchBuffer.handle()).offset(0).range(this.positionScratchBuffer.size());
            var writes = VkWriteDescriptorSet.calloc(7, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER); writes.get(0).pBufferInfo(u);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(1).dstSet(0); writes.get(1).dstBinding(1); writes.get(1).descriptorCount(1); writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(1).pBufferInfo(d);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(2); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(c);
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(3).dstSet(0); writes.get(3).dstBinding(3); writes.get(3).descriptorCount(1); writes.get(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(3).pBufferInfo(m);
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(4).dstSet(0); writes.get(4).dstBinding(4); writes.get(4).descriptorCount(1); writes.get(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(4).pBufferInfo(v);
            writes.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(5).dstSet(0); writes.get(5).dstBinding(5); writes.get(5).descriptorCount(1); writes.get(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(5).pBufferInfo(l);
            writes.get(6).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(6).dstSet(0); writes.get(6).dstBinding(6); writes.get(6).descriptorCount(1); writes.get(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(6).pBufferInfo(p);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.cmdgenLayout.handle(), 0, writes);
            // Indirect dispatch via drawCountCallBuffer[0]
            vkCmdDispatchIndirect(cb, this.drawCountCallBuffer.handle(), 0);
            VkSync.memoryBarrier(cb);
        }

        // Prefix-sum for translucent distance sorting (simple 256-wide scan, 1024 entries)
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.prefixSumPipeline);
            var io = VkDescriptorBufferInfo.calloc(1, stack); io.get(0).buffer(this.distanceCountBuffer.handle()).offset(0).range(this.distanceCountBuffer.size());
            var writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(0).pBufferInfo(io);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.prefixSumLayout.handle(), 0, writes);
            vkCmdDispatch(cb, 1, 1, 1);
            VkSync.memoryBarrier(cb);
        }

        // Build translucent draw commands (indirect, uses prefix-summed distances)
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.translucentPipeline);
            var u = VkDescriptorBufferInfo.calloc(1, stack); u.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(1024);
            var d = VkDescriptorBufferInfo.calloc(1, stack); d.get(0).buffer(this.drawCallBuffer.handle()).offset(0).range(this.drawCallBuffer.size());
            var c = VkDescriptorBufferInfo.calloc(1, stack); c.get(0).buffer(this.drawCountCallBuffer.handle()).offset(0).range(1024);
            var m = VkDescriptorBufferInfo.calloc(1, stack); m.get(0).buffer(geometryData.metadataBuffer().handle()).offset(0).range(geometryData.metadataBuffer().size());
            var l = VkDescriptorBufferInfo.calloc(1, stack); l.get(0).buffer(indirectLookupBuffer.handle()).offset(0).range(indirectLookupBuffer.size());
            var t = VkDescriptorBufferInfo.calloc(1, stack); t.get(0).buffer(this.distanceCountBuffer.handle()).offset(0).range(this.distanceCountBuffer.size());
            var writes = VkWriteDescriptorSet.calloc(6, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER); writes.get(0).pBufferInfo(u);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(1).dstSet(0); writes.get(1).dstBinding(1); writes.get(1).descriptorCount(1); writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(1).pBufferInfo(d);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(2); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(c);
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(3).dstSet(0); writes.get(3).dstBinding(3); writes.get(3).descriptorCount(1); writes.get(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(3).pBufferInfo(m);
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(4).dstSet(0); writes.get(4).dstBinding(4); writes.get(4).descriptorCount(1); writes.get(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(4).pBufferInfo(l);
            writes.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(5).dstSet(0); writes.get(5).dstBinding(5); writes.get(5).descriptorCount(1); writes.get(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(5).pBufferInfo(t);
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.translucentLayout.handle(), 0, writes);
            vkCmdDispatchIndirect(cb, this.drawCountCallBuffer.handle(), 0);
            VkSync.memoryBarrier(cb);
        }
        this.snapshotDrawCounts(cb);
    }

    public void renderOpaque(VkCommandBuffer cb, int width, int height) {
        if (this.quadsPipeline == 0) return;
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.quadsPipeline);
            var uniformInfo = VkDescriptorBufferInfo.calloc(1, stack); uniformInfo.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(1024);
            var quadInfo = VkDescriptorBufferInfo.calloc(1, stack); quadInfo.get(0).buffer(this.drawCallBuffer.handle()).offset(0).range(this.drawCallBuffer.size());
            var posInfo = VkDescriptorBufferInfo.calloc(1, stack); posInfo.get(0).buffer(this.positionScratchBuffer.handle()).offset(0).range(this.positionScratchBuffer.size());
            var writes = VkWriteDescriptorSet.calloc(6, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER); writes.get(0).pBufferInfo(uniformInfo);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(1).dstSet(0); writes.get(1).dstBinding(1); writes.get(1).descriptorCount(1); writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(1).pBufferInfo(quadInfo);
            
            var modelInfo = VkDescriptorBufferInfo.calloc(1, stack); modelInfo.get(0).buffer(this.modelBuffer.handle()).offset(0).range(this.modelBuffer.size());
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(3); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(modelInfo);
            
            var colourInfo = VkDescriptorBufferInfo.calloc(1, stack); colourInfo.get(0).buffer(this.modelColourBuffer.handle()).offset(0).range(this.modelColourBuffer.size());
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(3).dstSet(0); writes.get(3).dstBinding(4); writes.get(3).descriptorCount(1); writes.get(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(3).pBufferInfo(colourInfo);
            
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(4).dstSet(0); writes.get(4).dstBinding(5); writes.get(4).descriptorCount(1); writes.get(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(4).pBufferInfo(posInfo);
            
            var samplerInfo = VkDescriptorImageInfo.calloc(1, stack); samplerInfo.get(0).sampler(this.atlasSampler.handle()).imageView(this.atlasTexture.view(0)).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(5).dstSet(0); writes.get(5).dstBinding(6); writes.get(5).descriptorCount(1); writes.get(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER); writes.get(5).pImageInfo(samplerInfo);
            
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.quadsLayout.handle(), 0, writes);
            var shared = VoxyVulkanRenderSystem.INSTANCE.getSharedIndices();
            if (shared != null) {
                vkCmdBindIndexBuffer(cb, shared.quad.handle(), 0, VK_INDEX_TYPE_UINT16);
            }
            var vp = VkViewport.calloc(1, stack); vp.get(0).set(0, 0, width, height, 0, 1);
            vkCmdSetViewport(cb, 0, vp);
            var scissor = VkRect2D.calloc(1, stack); scissor.offset().set(0, 0); scissor.extent().set(width, height);
            vkCmdSetScissor(cb, 0, scissor);
            this.drawIndexedIndirect(cb, 0, 12, 400000);
        }
    }

    public void renderTranslucent(VkCommandBuffer cb, int width, int height) {
        long pipe = this.quadsTranslucentPipeline != 0 ? this.quadsTranslucentPipeline : this.quadsPipeline;
        var layout = this.quadsTranslucentPipeline != 0 ? this.quadsTranslucentLayout : this.quadsLayout;
        if (pipe == 0) return;
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            var uniformInfo = VkDescriptorBufferInfo.calloc(1, stack); uniformInfo.get(0).buffer(this.uniformBuffer.handle()).offset(0).range(1024);
            var quadInfo = VkDescriptorBufferInfo.calloc(1, stack); quadInfo.get(0).buffer(this.drawCallBuffer.handle()).offset(0).range(this.drawCallBuffer.size());
            var posInfo = VkDescriptorBufferInfo.calloc(1, stack); posInfo.get(0).buffer(this.positionScratchBuffer.handle()).offset(0).range(this.positionScratchBuffer.size());
            
            boolean isWhiteLayout = (layout == this.quadsLayout);
            var writes = VkWriteDescriptorSet.calloc(isWhiteLayout ? 6 : 3, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(0).dstSet(0); writes.get(0).dstBinding(0); writes.get(0).descriptorCount(1); writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER); writes.get(0).pBufferInfo(uniformInfo);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(1).dstSet(0); writes.get(1).dstBinding(1); writes.get(1).descriptorCount(1); writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(1).pBufferInfo(quadInfo);
            
            if (isWhiteLayout) {
                var modelInfo = VkDescriptorBufferInfo.calloc(1, stack); modelInfo.get(0).buffer(this.modelBuffer.handle()).offset(0).range(this.modelBuffer.size());
                writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(3); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(modelInfo);
                
                var colourInfo = VkDescriptorBufferInfo.calloc(1, stack); colourInfo.get(0).buffer(this.modelColourBuffer.handle()).offset(0).range(this.modelColourBuffer.size());
                writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(3).dstSet(0); writes.get(3).dstBinding(4); writes.get(3).descriptorCount(1); writes.get(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(3).pBufferInfo(colourInfo);
                
                writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(4).dstSet(0); writes.get(4).dstBinding(5); writes.get(4).descriptorCount(1); writes.get(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(4).pBufferInfo(posInfo);
                
                var samplerInfo = VkDescriptorImageInfo.calloc(1, stack); samplerInfo.get(0).sampler(this.atlasSampler.handle()).imageView(this.atlasTexture.view(0)).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(5).dstSet(0); writes.get(5).dstBinding(6); writes.get(5).descriptorCount(1); writes.get(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER); writes.get(5).pImageInfo(samplerInfo);
            } else {
                writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET); writes.get(2).dstSet(0); writes.get(2).dstBinding(5); writes.get(2).descriptorCount(1); writes.get(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER); writes.get(2).pBufferInfo(posInfo);
            }
            
            vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout.handle(), 0, writes);
            var shared = VoxyVulkanRenderSystem.INSTANCE.getSharedIndices();
            if (shared != null) {
                vkCmdBindIndexBuffer(cb, shared.quad.handle(), 0, VK_INDEX_TYPE_UINT16);
            }
            var vp = VkViewport.calloc(1, stack); vp.get(0).set(0, 0, width, height, 0, 1);
            vkCmdSetViewport(cb, 0, vp);
            var scissor = VkRect2D.calloc(1, stack); scissor.offset().set(0, 0); scissor.extent().set(width, height);
            vkCmdSetScissor(cb, 0, scissor);
            this.drawIndexedIndirect(cb, 400000L * 20L, 16, 100000);
        }
    }

    private void snapshotDrawCounts(VkCommandBuffer cb) {
        if (this.drawIndirectCount || this.hostDrawCountBuffer == null) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            var region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0).dstOffset(0).size(32);
            vkCmdCopyBuffer(cb, this.drawCountCallBuffer.handle(), this.hostDrawCountBuffer.handle(), region);
        }
    }

    private void drawIndexedIndirect(VkCommandBuffer cb, long drawOffset, long countOffset, int maxDraws) {
        if (this.drawIndirectCount) {
            vkCmdDrawIndexedIndirectCountKHR(cb, this.drawCallBuffer.handle(), drawOffset,
                    this.drawCountCallBuffer.handle(), countOffset, maxDraws, 20);
            return;
        }
        int count = 0;
        if (this.hostDrawCountBuffer != null) {
            long addr = this.hostDrawCountBuffer.mapPersistent();
            count = MemoryUtil.memGetInt(addr + countOffset);
        }
        if (count <= 0) {
            return;
        }
        if (count > maxDraws) {
            count = maxDraws;
        }
        vkCmdDrawIndexedIndirect(cb, this.drawCallBuffer.handle(), drawOffset, count, 20);
    }

    @Override
    public void close() {
        if (this.quadsPipeline != 0) vkDestroyPipeline(this.device, this.quadsPipeline, null);
        if (this.quadsTexturedPipeline != 0) vkDestroyPipeline(this.device, this.quadsTexturedPipeline, null);
        if (this.quadsTranslucentPipeline != 0) vkDestroyPipeline(this.device, this.quadsTranslucentPipeline, null);
        vkDestroyPipeline(this.device, this.prepPipeline, null);
        if (this.cullPipeline != 0) vkDestroyPipeline(this.device, this.cullPipeline, null);
        vkDestroyPipeline(this.device, this.cmdgenPipeline, null);
        vkDestroyPipeline(this.device, this.prefixSumPipeline, null);
        vkDestroyPipeline(this.device, this.translucentPipeline, null);
        this.prepLayout.close();
        if (this.cullLayout != null) this.cullLayout.close();
        this.cmdgenLayout.close();
        this.prefixSumLayout.close();
        this.translucentLayout.close();
        if (this.quadsLayout != null) this.quadsLayout.close();
        if (this.quadsTexturedLayout != null) this.quadsTexturedLayout.close();
        if (this.quadsTranslucentLayout != null) this.quadsTranslucentLayout.close();
        this.uniformBuffer.close();
        this.drawCallBuffer.close();
        this.drawCountCallBuffer.close();
        this.positionScratchBuffer.close();
        this.distanceCountBuffer.close();
        this.modelBuffer.close();
        this.modelColourBuffer.close();
        this.atlasTexture.close();
        this.atlasSampler.close();
        if (this.hostDrawCountBuffer != null) {
            this.hostDrawCountBuffer.close();
        }
    }
}
