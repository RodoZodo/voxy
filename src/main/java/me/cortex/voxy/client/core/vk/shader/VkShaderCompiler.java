package me.cortex.voxy.client.core.vk.shader;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Standalone GLSL -> SPIR-V compiler over LWJGL shaderc (org.lwjgl:lwjgl-shaderc is shipped by
 * Minecraft 26.2). Deliberately independent of Minecraft's {@code GlslCompiler} /
 * {@code RenderPipeline} / {@code BindGroupLayout} name-contract pipeline: the shader-pack
 * integration is deferred until Aperture (Iris' Vulkan successor) ships, and this keeps Voxy's
 * own shader path self-contained.
 *
 * <p>Shader sources are Vulkan-flavoured GLSL ({@code #version 450}) with explicit
 * {@code layout(set = N, binding = N)} bindings and {@code gl_VertexIndex} etc.
 */
public final class VkShaderCompiler implements AutoCloseable {
    // shaderc enum values (from the LWJGL binding; compute is 2, NOT 5, in this build)
    private static final int SHADERC_TARGET_ENV_VULKAN = Shaderc.shaderc_target_env_vulkan;
    private static final int SHADERC_ENV_VERSION_VULKAN_1_2 = 0x00402000;
    private static final int SHADERC_VERTEX_SHADER = Shaderc.shaderc_vertex_shader;
    private static final int SHADERC_FRAGMENT_SHADER = Shaderc.shaderc_fragment_shader;
    private static final int SHADERC_COMPUTE_SHADER = Shaderc.shaderc_compute_shader;
    private static final int SHADERC_OPTIMIZATION_LEVEL_ZERO = Shaderc.shaderc_optimization_level_zero;

    private final long compiler;
    private final long options;

    public VkShaderCompiler() {
        this.compiler = Shaderc.shaderc_compiler_initialize();
        this.options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(this.options, SHADERC_TARGET_ENV_VULKAN, SHADERC_ENV_VERSION_VULKAN_1_2);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
        Shaderc.shaderc_compile_options_set_auto_map_locations(this.options, true);
        Shaderc.shaderc_compile_options_set_generate_debug_info(this.options);
        Shaderc.shaderc_compile_options_set_optimization_level(this.options, SHADERC_OPTIMIZATION_LEVEL_ZERO);
    }

    /** Compile GLSL source into a {@link VkShaderModule}. Defines are injected after {@code #version}. */
    public VkShaderModule compile(String name, String source, VkShaderStage stage, Map<String, String> defines) {
        int shaderKind = switch (stage) {
            case COMPUTE -> SHADERC_COMPUTE_SHADER;
            case VERTEX -> SHADERC_VERTEX_SHADER;
            case FRAGMENT -> SHADERC_FRAGMENT_SHADER;
            default -> throw new IllegalStateException("Shader stage not yet supported by compiler: " + stage);
        };

        String processed = resolveImports(injectDefines(source, defines));
        //GL -> Vulkan built-in renames (shaderc's Vulkan target expects Vulkan built-ins)
        processed = processed.replace("gl_VertexID", "gl_VertexIndex").replace("gl_InstanceID", "gl_InstanceIndex");
        ByteBuffer src = MemoryUtil.memUTF8(processed, false);
        ByteBuffer file = MemoryUtil.memUTF8(name);
        ByteBuffer entry = MemoryUtil.memUTF8("main");
        long result = Shaderc.shaderc_compile_into_spv(this.compiler, src, shaderKind, file, entry, this.options);
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != 0) {
                String msg = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException("Failed to compile shader '" + name + "': " + msg);
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
            MemoryUtil.memCopy(spirv, copy);
            return new VkShaderModule(stage, copy);
        } finally {
            Shaderc.shaderc_result_release(result);
            MemoryUtil.memFree(entry);
            MemoryUtil.memFree(file);
            MemoryUtil.memFree(src);
        }
    }

    /** Compile GLSL source into a {@link VkShaderModule} without extra defines. */
    public VkShaderModule compile(String name, String source, VkShaderStage stage) {
        return this.compile(name, source, stage, Map.of());
    }

    private static String injectDefines(String source, Map<String, String> defines) {
        if (defines.isEmpty()) {
            return source;
        }
        var sb = new StringBuilder();
        int versionEnd = source.indexOf('\n');
        sb.append(source, 0, versionEnd + 1);
        for (var e : defines.entrySet()) {
            sb.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        sb.append(source, versionEnd + 1, source.length());
        return sb.toString();
    }

    /** Resolve {@code #import <voxy:path>} directives against {@code assets/voxy/shaders/vk/<path>}. */
    private static String resolveImports(String source) {
        return resolveImports(source, new HashSet<>());
    }

    private static String resolveImports(String source, Set<String> seen) {
        var sb = new StringBuilder();
        int searchFrom = 0;
        int importIdx;
        while ((importIdx = source.indexOf("#import", searchFrom)) != -1) {
            int lineEnd = source.indexOf('\n', importIdx);
            if (lineEnd == -1) {
                lineEnd = source.length();
            }
            String line = source.substring(importIdx, lineEnd).trim();
            sb.append(source, searchFrom, importIdx);
            var m = IMPORT_PATTERN.matcher(line);
            if (m.matches()) {
                String path = m.group(1);
                if (seen.add(path)) {
                    String imported = resolveImports(loadResource(path), seen);
                    sb.append(imported);
                    if (!imported.endsWith("\n")) sb.append('\n');
                }
            } else {
                sb.append(line).append('\n');
            }
            searchFrom = lineEnd + 1;
        }
        sb.append(source, searchFrom, source.length());
        return sb.toString();
    }

    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import\\s*<voxy:([^>]+)>");

    /** Load a shader source from {@code assets/voxy/shaders/vk/<file>}. */
    public static String loadResource(String file) {
        String path = "/assets/voxy/shaders/vk/" + file;
        try (var in = VkShaderCompiler.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing shader resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read shader resource: " + path, e);
        }
    }

    @Override
    public void close() {
        Shaderc.shaderc_compile_options_release(this.options);
        Shaderc.shaderc_compiler_release(this.compiler);
    }
}
