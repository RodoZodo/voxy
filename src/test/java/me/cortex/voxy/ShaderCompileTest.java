package me.cortex.voxy;

import me.cortex.voxy.client.core.vk.shader.VkShaderCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderStage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class ShaderCompileTest {
    static String load(String p) throws Exception { return new String(Files.readAllBytes(Path.of("src/main/resources/assets/voxy/shaders/vk/" + p))); }

    @Test
    public void compileAll() throws Exception {
        try (var compiler = new VkShaderCompiler()) {
            for (String f : new String[]{"demo.comp", "demo.vert", "demo.frag", "hiz.comp", "hiz_init.comp", "lod/mip.comp", "lod/mesh.comp"}) {
                var stage = f.endsWith(".vert") ? VkShaderStage.VERTEX : f.endsWith(".frag") ? VkShaderStage.FRAGMENT : VkShaderStage.COMPUTE;
                var module = compiler.compile("voxy:" + f, load(f), stage);
                module.free(null);
                System.out.println("OK " + f);
            }
            //Traversal (with defines)
            var defs = new java.util.HashMap<String,String>();
            for (String d : new String[]{"MAX_ITERATIONS=5","LOCAL_SIZE_BITS=5","MAX_REQUEST_QUEUE_SIZE=50","HIZ_BINDING=0","SCENE_UNIFORM_BINDING=1","REQUEST_QUEUE_BINDING=2","RENDER_QUEUE_BINDING=3","NODE_DATA_BINDING=4","NODE_QUEUE_META_BINDING=5","NODE_QUEUE_SOURCE_BINDING=6","NODE_QUEUE_SINK_BINDING=7","RENDER_TRACKER_BINDING=8","STATISTICS_BUFFER_BINDING=9","HAS_STATISTICS","USE_REVERSE_Z","USE_ZERO_ONE_DEPTH"}) {
                String[] kv = d.split("=");
                defs.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
            var module = compiler.compile("voxy:traversal", load("lod/hierarchical/traversal_dev.comp"), VkShaderStage.COMPUTE, defs);
            module.free(null);
            System.out.println("OK traversal_dev.comp");
            //Cleaner shaders (defines as injected by VkNodeCleaner)
            {
                var d = new java.util.HashMap<String,String>();
                d.put("WORK_SIZE", "64"); d.put("ELEMS_PER_THREAD","8"); d.put("OUTPUT_SIZE","256");
                d.put("VISIBILITY_BUFFER_BINDING","0"); d.put("OUTPUT_BUFFER_BINDING","1"); d.put("NODE_DATA_BINDING","2");
                var m = compiler.compile("voxy:sort_visibility", load("lod/hierarchical/cleaner/sort_visibility.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK sort_visibility");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("OUTPUT_SIZE","256");
                d.put("MIN_ID_BUFFER_BINDING","0"); d.put("NODE_BUFFER_BINDING","1"); d.put("OUTPUT_BUFFER_BINDING","2"); d.put("VISIBILITY_BUFFER_BINDING","3");
                var m = compiler.compile("voxy:result_transformer", load("lod/hierarchical/cleaner/result_transformer.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK result_transformer");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("VISIBILITY_BUFFER_BINDING","0"); d.put("LIST_BUFFER_BINDING","1");
                var m = compiler.compile("voxy:batch_visibility_set", load("lod/hierarchical/cleaner/batch_visibility_set.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK batch_visibility_set");
            }
            // VDIC renderer (opaque, cull stubbed) + prefixsum + translucents
            {
                var d = new java.util.HashMap<String,String>();
                d.put("DRAW_COUNT_BUFFER_BINDING","1"); d.put("INDIRECT_SECTION_LOOKUP_BINDING","2");
                var m = compiler.compile("voxy:prep", load("lod/gl46/prep.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK prep.comp");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("DRAW_BUFFER_BINDING","1"); d.put("DRAW_COUNT_BUFFER_BINDING","2"); d.put("SECTION_METADATA_BUFFER_BINDING","3");
                d.put("VISIBILITY_BUFFER_BINDING","4"); d.put("INDIRECT_SECTION_LOOKUP_BINDING","5"); d.put("POSITION_SCRATCH_BINDING","6");
                d.put("POSITION_SCRATCH_ACCESS","writeonly"); d.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING","7");
                d.put("TRANSLUCENT_WRITE_BASE","1024"); d.put("TEMPORAL_OFFSET","500000");
                var m = compiler.compile("voxy:cmdgen", load("lod/gl46/cmdgen.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK cmdgen.comp");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("IO_BUFFER","0");
                var m = compiler.compile("voxy:prefix_simple", load("util/prefixsum/simple.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK prefixsum/simple.comp");
                var m2 = compiler.compile("voxy:prefix_inital3", load("util/prefixsum/inital3.comp"), VkShaderStage.COMPUTE, d);
                m2.free(null);
                System.out.println("OK prefixsum/inital3.comp");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("DRAW_BUFFER_BINDING","1"); d.put("DRAW_COUNT_BUFFER_BINDING","2"); d.put("SECTION_METADATA_BUFFER_BINDING","3");
                d.put("INDIRECT_SECTION_LOOKUP_BINDING","4"); d.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING","5");
                d.put("TRANSLUCENT_WRITE_BASE","1024"); d.put("TRANSLUCENT_OFFSET","400000");
                var m = compiler.compile("voxy:buildtranslucents", load("lod/gl46/buildtranslucents.comp"), VkShaderStage.COMPUTE, d);
                m.free(null);
                System.out.println("OK buildtranslucents.comp");
            }
            {
                var d = new java.util.HashMap<String,String>();
                d.put("QUAD_BUFFER_BINDING","1"); d.put("MODEL_BUFFER_BINDING","3"); d.put("MODEL_COLOUR_BUFFER_BINDING","4");
                d.put("POSITION_SCRATCH_BINDING","5"); d.put("LIGHTING_SAMPLER_BINDING","6");
                d.put("NO_SHADE_FACE_TINT","1.0"); d.put("UP_FACE_TINT","1.0"); d.put("DOWN_FACE_TINT","0.9");
                d.put("Z_AXIS_FACE_TINT","0.85"); d.put("X_AXIS_FACE_TINT","0.82");
                var m = compiler.compile("voxy:quads3", load("lod/gl46/quads3.vert"), VkShaderStage.VERTEX, d);
                m.free(null);
                System.out.println("OK quads3.vert");
            }
            // Note: quads.frag uses sampler at binding 0 which conflicts with uniform at 0 in the same set — tested separately via the pipeline builder
            // Cull raster shaders (vertex+fragment) — not yet wired but should compile
            {
                var m = compiler.compile("voxy:cull_raster_vert", load("lod/gl46/cull/raster.vert"), VkShaderStage.VERTEX, new java.util.HashMap<>());
                m.free(null);
                System.out.println("OK cull/raster.vert");
                var m2 = compiler.compile("voxy:cull_raster_frag", load("lod/gl46/cull/raster.frag"), VkShaderStage.FRAGMENT, new java.util.HashMap<>());
                m2.free(null);
                System.out.println("OK cull/raster.frag");
            }
            // quads_white verification
            {
                var d = new java.util.HashMap<String,String>();
                d.put("QUAD_BUFFER_BINDING","1");
                d.put("MODEL_BUFFER_BINDING","3");
                d.put("MODEL_COLOUR_BUFFER_BINDING","4");
                d.put("POSITION_SCRATCH_BINDING","5");
                d.put("LIGHTING_SAMPLER_BINDING","6");
                d.put("NO_SHADE_FACE_TINT","1.0");
                d.put("UP_FACE_TINT","1.0");
                d.put("DOWN_FACE_TINT","0.9");
                d.put("Z_AXIS_FACE_TINT","0.85");
                d.put("X_AXIS_FACE_TINT","0.82");
                var m = compiler.compile("voxy:quads_white_vert", load("lod/gl46/quads_white.vert"), VkShaderStage.VERTEX, d);
                m.free(null);
                System.out.println("OK lod/gl46/quads_white.vert");
                var m2 = compiler.compile("voxy:quads_white_frag", load("lod/gl46/quads_white.frag"), VkShaderStage.FRAGMENT, new java.util.HashMap<>());
                m2.free(null);
                System.out.println("OK lod/gl46/quads_white.frag");
            }
            // Post SSAO / blits
            {
                var m = compiler.compile("voxy:ssao", load("post/ssao.comp"), VkShaderStage.COMPUTE);
                m.free(null);
                System.out.println("OK post/ssao.comp");
                var m2 = compiler.compile("voxy:fullscreen_vert", load("post/fullscreen.vert"), VkShaderStage.VERTEX);
                m2.free(null);
                System.out.println("OK post/fullscreen.vert");
                var m3 = compiler.compile("voxy:blit_depth_cutout", load("post/blit_texture_depth_cutout.frag"), VkShaderStage.FRAGMENT);
                m3.free(null);
                System.out.println("OK post/blit_texture_depth_cutout.frag");
            }
        }
    }
}
