# Voxy Vulkan Port — Research & Integration Notes

Status: **Phase 0 research complete** (2026-08-19) | Sources: Minecraft Wiki + CFR decompilation of the MC 26.2 client jar.

---

## 1. Goal & locked decisions

Recreate the Voxy mod (voxel LoD renderer for Minecraft Java) targeting Minecraft's new **Vulkan** renderer instead of the OpenGL renderer.

| Decision | Choice |
|---|---|
| Scope | MVP LoD renderer first, then feature completion |
| Target | MC 26.2 ("Chaos Cubed", released 2026-06-16) — repo target unchanged |
| Activation | Vulkan is **experimental** ("Prefer Vulkan" video setting / `--graphicsBackend vulkan`) |
| Backend | Vulkan-only. GL code replaced, not abstracted. GL stays as fallback (Mojang's own fallback logic) |
| Bindings/device | **Reuse Minecraft's own**: LWJGL-Vulkan 3.4.1 + lwjgl-vma + lwjgl-shaderc/spvc, and Mojang's `blaze3d` GPU abstraction |
| Frame integration | Record inline into Mojang's shared primary command buffer / open dynamic-rendering pass (see §3 — NOT secondary CBs) |
| Shaders | Rewritten for Vulkan (GLSL `#version 450` → SPIR-V at runtime via Mojang's `GlslCompiler`) |
| Vulkan baseline | 1.2 required by MC (dynamic rendering + push descriptors + sync2); we target features MC itself enables |
| Mesh shaders | Optional `VK_EXT_mesh_shader` path; indirect multidraw baseline |
| Compat | Sodium only (Iris/Nvidium/Flashback/Vivecraft/FREX dropped) |
| Reuse unchanged | `common/` + `commonImpl/` (~22k LOC, API-agnostic) |
| Test HW | NVIDIA, AMD, Apple Silicon (MoltenVK) |
| Developer level | New to Vulkan (plan includes learning path) |

---

## 2. Verified facts about the MC 26.2 Vulkan renderer

### 2.1 Activation & requirements (Minecraft Wiki, confirmed in jar)

- New video setting **"Graphics API"**: `Default` / `Prefer Vulkan (Experimental)` / `Prefer OpenGL`.
  - Serialized as `preferredGraphicsBackend` in options.txt; CLI `--graphicsBackend <default|opengl|vulkan>`, `--vulkanValidation`.
  - Crash-detection auto-downgrades the setting on startup failure.
- Requirement: **Vulkan 1.2** with **dynamic rendering** (`VK_KHR_dynamic_rendering`), **push descriptors** (`VK_KHR_push_descriptor`), `VK_KHR_synchronization2`, `VK_KHR_swapchain`, `VK_EXT_vertex_attribute_divisor`. Optional: `VK_EXT_multi_draw`, portability subset, AMD/NV checkpoints.
- macOS: **MoltenVK**. Under Vulkan, a **dedicated GPU is preferred** over iGPU (different from GL).
- F3 debug overlay shows which backend is active.
- GL renderer still ships and is the fallback ("Default" ≈ "Prefer OpenGL").
- Voxy must therefore **gate on Vulkan being active** and disable itself in GL mode.

### 2.2 Backend selection & initialization

- `net.minecraft.client.PreferredGraphicsApi` enum (`DEFAULT`/`OPENGL`/`VULKAN`); `getBackendsToTry()` → `GpuBackend[]` (Vulkan first when preferred, GL fallback after).
- `Minecraft.<init>` (Minecraft.java:421-535): for each backend → `new Window(handle, backend)` → `backend.createDevice(handle, shaderSource, debugOptions, criticalShaderLoader)` → `RenderSystem.initRenderer(device)`.
- `VulkanBackend.createDevice(...)` (public): creates `VulkanInstance`, picks `VulkanPhysicalDevice` (discrete-GPU preference + known-bad device blacklist `VulkanUtils.KNOWN_PROBLEMATIC_DEVICES`), `vkCreateDevice`, creates **VMA** allocator, returns `new GpuDevice(new VulkanDevice(...), criticalShaderLoader)`.
- **No static singleton.** The global is `RenderSystem.DEVICE` (`RenderSystem.getDevice()` / `tryGetDevice()`, public static). `GpuDevice` wraps a `GpuDeviceBackend` with no getter → capture the concrete `VulkanDevice` at construction.

### 2.3 Architecture: WebGPU-style shared abstraction (CRITICAL)

MC 26.2 has a **full renderer abstraction shared by GL and Vulkan** (package `com.mojang.blaze3d.pipeline`, `blaze3d.systems`, `blaze3d.framegraph`):

- `GpuDevice` (buffer/texture/view creation, pipeline precompile)
- `CommandEncoder` (render passes, transient memory, fences, submit)
- `RenderPass` (`setPipeline`, `bindTexture`, `setUniform`, `setVertexBuffer`, `setIndexBuffer`, `draw`, `drawIndexed`, `drawIndirect`, `drawIndexedIndirect`, `drawMultipleIndexed`)
- `RenderPipeline` + `BindGroupLayout` (declarative; names are the contract)
- `GpuBuffer` / `GpuTexture` / `GpuTextureView` / `GpuSampler`
- `TransientMemory` (per-frame ring of staging/GPU blocks — `allocateCpu/Staging/Gpu/GpuMapped`, `uploadStaging`, `uploadGpu`)
- `FrameGraphBuilder` + frame passes ("clear", "sky", **"main"**, post, "clouds", "weather", "always_on_top")

**There are NO secondary command buffers and NO `vkCmdExecuteCommands` in the whole client.** Everything is recorded into **one primary command buffer per frame**, submitted once. Render passes are `vkCmdBeginRenderingKHR`/`vkCmdEndRenderingKHR` (**dynamic rendering**, no `VkRenderPass` objects). Descriptors are **push descriptors** (single descriptor set at index 0, one pipeline layout).

Frame loop (Minecraft.renderFrame): acquire swapchain image → `gameRenderer.render(...)` (records the whole LevelRenderer frame graph inline) → blit → `createCommandEncoder().submit()` → present.

**Consequence for the port:** "record parallel secondary CBs and merge" is not the right model here (secondary CBs don't inherit push-descriptor state and MC never uses `VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT`). The correct model is **inline recording into the shared primary CB** — our draws get spliced into the open terrain render pass. This is simpler and avoids all secondary-CB state-inheritance problems.

### 2.4 Resource access (what a mod can reach — all public)

From a captured `VulkanDevice` (public class):
- `vkDevice()` → `org.lwjgl.vulkan.VkDevice`
- `instance()` → `VulkanInstance` → `vkInstance()`, `enabledExtensions()`, `debug()`
- `vkDevice().getPhysicalDevice()` → `VkPhysicalDevice`
- `graphicsQueue()` / `computeQueue()` / `transferQueue()` → `VulkanQueue` (public record `(VkQueue vkQueue, int queueFamilyIndex)`)
- `vma()` → VMA allocator handle (`org.lwjgl.util.vma.Vma`)
- `createCommandEncoder()` → the singleton `VulkanCommandEncoder` (public)
- `checkpointExtension()` (public)

Buffers/textures via the abstraction: `GpuDevice.createBuffer(...)`, `createTexture(...)`, `createTextureView(...)` — public. Raw handles exposed: `VulkanGpuBuffer.vkBuffer()`-ish accessors, `VulkanGpuTexture.vkImage()`, `VulkanGpuTextureView.vkImageView()`.

Current frame attachments: `Minecraft.getInstance().gameRenderer.mainRenderTarget()` → `RenderTarget.getColorTexture()/getColorTextureView()/getDepthTexture()/getDepthTextureView()` (public). **Depth format is `D32_FLOAT`** (`VK_FORMAT_D32_SFLOAT`), depth texture created with COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT → **bindable as a sampled image**.

### 2.5 Layout / synchronization model

- **Everything lives in `VK_IMAGE_LAYOUT_GENERAL` permanently** (initial transition at texture creation; attachments/sampled/blits all use GENERAL). A mod never needs layout transitions — only **memory barriers** (`VulkanCommandEncoder.memoryBarrier(...)` is public static, `vkCmdPipelineBarrier2KHR`).
- **Timeline semaphore frame pacing**: `VulkanCommandEncoder` creates one timeline semaphore (`submitSemaphore`); 2 frames in flight (`MAX_SUBMITS_IN_FLIGHT = 2`); submit signals `currentSubmitIndex` then blocks on `currentSubmitIndex - 2` (5s timeout). Command pools / destroy queue / transient memory rotate on the same 2-slot cadence.
- Completion for a mod: `VulkanCommandEncoder.createFence()` → `GpuFence.awaitCompletion(timeoutMs)`; or `RenderSystem.queueFencedTask(Runnable)` / `executePendingTasks()`. Also `VulkanQueue.waitIdle()`.
- `waitSemaphore`/`signalSemaphore`/`execute(VkCommandBuffer)` on the encoder throw **while inside a render pass** — only legal between passes.

### 2.6 Shaders (Mojang's compile path)

- **Language: GLSL, entry point `main`**, assets like `shaders/core/<id>.vsh|.fsh`; loaded via `ShaderManager` + `GlslPreprocessor` (`#include`, defines); passed to the backend through the `ShaderSource` functional interface.
- **`GlslCompiler` (public)** uses LWJGL **shaderc** → SPIR-V at runtime: target env Vulkan 1.2, `auto_bind_uniforms`, `auto_map_locations`, debug info, O0; injects `gl_VertexID → gl_VertexIndex`, `gl_InstanceID → gl_InstanceIndex`.
- **Reflection/rebinding via SPIRV-Cross (`Spvc`)**: lists uniforms/samplers/inputs/outputs, **patches SPIR-V binding words directly**, and **throws if the shader declares bindings the pipeline's `BindGroupLayout`s don't declare**.
- Pipeline compile (`VulkanDevice.compileShader` → `GlslCompiler.compile` → `VulkanRenderPipeline.compile`): builds `VkPipelineRenderingCreateInfoKHR` with `depthAttachmentFormat(126)` (D32); **two pipelines per pipeline** (with/without depth); cached in an `IdentityHashMap<RenderPipeline, VulkanRenderPipeline>`.
- **Names (not binding indices) are the contract** between GLSL and `BindGroupLayout` (e.g. "Projection", "Sampler0", "ChunkSection", "DynamicTransforms"). A mod's shaders must follow this same path.

### 2.7 Compute — NOT exposed by the abstraction (KEY FINDING)

There is **no compute pipeline / dispatch API** anywhere in `blaze3d`. Voxy's compute-heavy subsystems must go through **raw LWJGL-Vulkan** (`vkCreateComputePipelines`, `vkCmdDispatch*`, push descriptor sets or compute descriptor sets) recorded into the shared primary command buffer via a mixin accessor to `VulkanCommandEncoder.commandBuffer()` (private field/method). Our own barriers (`VulkanCommandEncoder.memoryBarrier`) reconcile compute/graphics ordering on the same CB.

### 2.8 Debug / validation

- `VulkanDebug` (interface): `Enabled`/`Disabled`; validation layers via `--vulkanValidation`; debug markers via `renderDebugLabels` or `ENABLE_VULKAN_RENDERDOC_CAPTURE=1`.
- Public API: `vulkanDevice.instance().debug().beginDebugGroup(VkCommandBuffer, Supplier)`, `endDebugGroup(...)`, `setObjectName(...)` — usable by the port for GPU markers/printf-style debugging.

---

## 3. Corrected port architecture

```
Minecraft 26.2 (Vulkan, experimental)
  ONE primary VkCommandBuffer per frame (recorded inline)
  Render pass "main" = vkCmdBeginRenderingKHR(..., depth=D32_FLOAT, color=RGBA8)
      └─ ChunkSectionsToRender.renderGroup(OPAQUE,...)  ← vanilla/Sodium chunk draws
      └─ Voxy spliced here (submitRenderPass HEAD): LoD terrain + far terrain
  Compute (raw LWJGL-Vulkan, on same CB): traversal, cmdgen, culling, HiZ, SSAO, prefixsum
```

| Voxy subsystem | Port target |
|---|---|
| GPU resource creation | MC abstraction (`GpuDevice.createBuffer/Texture/View`) + raw VMA when needed |
| Per-frame staging (UploadStream) | `TransientMemory` (`uploadStaging`/`uploadGpu`/`allocateGpuMapped`) + own arena for the big geometry buffer |
| GPU→CPU readback (DownloadStream) | `TransientMemory` staging + `createFence()` / `queueFencedTask`, or raw copy + timeline wait |
| Graphics pipelines (quads, cull raster, blits, outlines) | `RenderPipeline` + `BindGroupLayout` + `GlslCompiler` (names contract) |
| **Compute** (traversal_dev, cmdgen, prep, buildtranslucents, prefixsum, memcpy/scatter/set, HiZ comp, SSAO) | **Raw LWJGL-Vulkan compute pipelines** on the shared CB via `VulkanCommandEncoder.commandBuffer()` accessor |
| Terrain draw (MDIC) | `VulkanRenderPass.drawIndexedIndirect`/`drawMultipleIndexed`; if count-in-buffer needed, raw `vkCmdDrawIndexedIndirectCount` on the CB |
| Depth masking vs vanilla | Depth-test against vanilla's D32 depth (GENERAL layout, no transitions). Stencil trick: N/A — D32 (no stencil) → use depth-only masking |
| HiZ | Compute mip chain from `mainRenderTarget().getDepthTextureView()` (R32F output we own) |
| Camera/fog uniforms | `RenderSystem.bindDefaultUniforms(renderPass)` ("Projection"/"Fog"/"Globals"/"Lighting"); own UBOs via TransientMemory + `setUniform` |
| Chunk ingest / world events | Unchanged (`common/`, `commonImpl/`, MixinClientChunkCache/MixinClientLevel survive) |
| Sodium compat | Hook the same `ChunkSectionsToRender`/`submitRenderPass`/`createRenderPass` points (Sodium integrates at the same level) |

### 3.1 Mixin surface (concrete targets)

| Goal | Target (class.method) | Visibility | How |
|---|---|---|---|
| Capture device/encoder | `VulkanDevice.<init>` | public | @Inject TAIL → stash static `VulkanDevice` |
| | `GpuDevice.<init>` | public | @Inject TAIL → capture `GpuDeviceBackend` |
| | `RenderSystem.initRenderer(GpuDevice)` | public static | @Inject TAIL |
| Hook terrain opaque pass | `ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)` | public | pass bracketing for "main" |
| | `VulkanCommandEncoder.createRenderPass(RenderPassDescriptor)` | public | @Inject RETURN → stash current `VulkanRenderPass` |
| Spliced draws | `VulkanCommandEncoder.submitRenderPass()` | public | @Inject HEAD → record Voxy draws into the stashed pass before `vkCmdEndRenderingKHR` |
| Raw CB access (compute/indirect-count) | `VulkanCommandEncoder.commandBuffer()` / `VulkanRenderPass.commandBuffer()` / `pushDescriptors()` | private | @Accessor/@Inject cancellable |
| Attachments | `gameRenderer.mainRenderTarget().getDepthTextureView()/getColorTextureView()` | public | none needed |
| Frame sync | `createFence()` / `RenderSystem.queueFencedTask` | public | none needed |

Note: `VulkanRenderPass.VALIDATION` throws in IDE runs when drawing without a pipeline or with unbound uniforms — our draws must always `setPipeline` and bind all declared uniforms/samplers first.

### 3.2 GL-era hooks that do NOT carry over

- `MixinRenderSystem` (`RenderSystem.initRenderer` GL ctor) → replaced by Vulkan device capture (above).
- Sodium `MixinDefaultChunkRenderer` (`ShaderChunkRenderer.end`) → GL-era Sodium; replaced by the `ChunkSectionsToRender`/pass-level hooks.
- The whole `client/core/gl/` wrapper layer (GlBuffer/GlTexture/GlFramebuffer/GlFence/GlVertexArray/GlPersistentMappedBuffer/Capabilities) and `AutoBindingShader` binding-point culture → replaced by MC abstraction + push descriptors + raw compute.
- `GL_NV_representative_fragment_test`, ubyte index buffers, D24S8 stencil, texture-barrier feedback, SSBO-binding save/restore dance → all gone (no equivalents / not needed).

---

## 4. Research gates — status

| Gate | Status |
|---|---|
| MC version with Vulkan | ✅ 26.2 ("Prefer Vulkan (Experimental)") |
| Activation toggle | ✅ `preferredGraphicsBackend` option / `--graphicsBackend vulkan` |
| Bindings | ✅ LWJGL-Vulkan 3.4.1 + lwjgl-vma + lwjgl-shaderc/spvc |
| Device/queue/allocator access | ✅ all public on `VulkanDevice` |
| Depth/color attachments | ✅ `mainRenderTarget()` views public; D32_FLOAT, GENERAL layout |
| Camera matrices/fog | ✅ `RenderSystem.bindDefaultUniforms` + own UBOs |
| Frame pacing / completion | ✅ 2-in-flight timeline semaphore; `createFence()`/`queueFencedTask` |
| Compute support | ✅ **absent from abstraction → raw LWJGL-Vulkan on shared CB** |
| Sodium Vulkan reference | ⏳ Sodium 26.2 Vulkan code — study hooks (same level as us) |

## 5. Remaining questions / risks

- Exact Sodium 26.2 hook targets (for compat phase) — verify from Sodium source.
- `VK_EXT_multi_draw` availability for draw-count-in-buffer on the pass object vs raw `vkCmdDrawIndexedIndirectCount` (baseline: raw on CB).
- MoltenVK: mesh-shader ext absent, sparse residency limited, int64/subgroup probing — capability-gate.
- GPU memory heuristics: `VK_EXT_memory_budget` instead of NVX.
- Shader authoring for `GlslCompiler`: only VERTEX/FRAGMENT types via MC path (`.vsh`/`.fsh`); compute shaders are ours to compile (raw shaderc via LWJGL, same options as `GlslCompiler`).

## 6. Reference material

- Decompiled `com.mojang.blaze3d.vulkan` (+ blaze3d.pipeline/systems/framegraph/opengl) sources: `/var/folders/cp/_9bq_r356szfq_2mtx0yrgc00000gn/T/opencode/mc26.2/src/` (CFR output)
- MC 26.2 client jar + version manifest: same temp dir (`client.jar`, `26.2.json`)
- Git tag `gl-legacy` marks the GL-era codebase state before the port.

---

## 7. Phase 1B — GL strip & deactivation gate (2026-08-19)

### What changed
- All GL-coupled + dropped-compat sources moved to `docs/gl-reference/java/` (same package layout,
  **not compiled** — Loom only builds `src/main/java`): `client/core/gl`, render pipelines,
  traversal/MDIC/bounding/HiZ/Upload-Download, model bakery+meshing stack, `client/iris`,
  iris/nvidium/flashback/chunky mixins, GL-era vanilla/sodium mixins. ~124 files total.
- `src/main/java` reduced to: `common/` + `commonImpl/` (world engine, storage, voxelization),
  the Vulkan foundation (`client/core/vk/`), config/commands/debug/session, and 9 backend-agnostic
  mixins. Zero `org.lwjgl.opengl` references remain in compiled code.
- Pruned `client.voxy.mixins.json` + `common.voxy.mixins.json`, trimmed `voxy.accesswidener`
  (removed GL entries), dropped iris/nvidium/flashback/vivecraft/chunky from `build.gradle` +
  `gradle.properties`. Sodium + ModMenu + Lithium deps retained.

### Auto-deactivation gate (single source of truth: `VkContext`)
```
isVulkanSelected() = Minecraft.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN
isVulkanActive()   = a com.mojang.blaze3d.vulkan.VulkanDevice was captured (MixinVulkanDevice)
shouldActivate()   = isVulkanSelected() && isVulkanActive()
getDeactivationReason() -> null when active; else a human-readable explanation
```
Sodium 26.2's "Graphics API" dropdown binds to the same vanilla option
(`SodiumConfigBuilder` → `vanillaOpts.preferredGraphicsBackend()`), so this covers both UIs.

Surfaces when deactivated:
- **Log**: `Voxy (Vulkan): disabled - <reason>` at renderer init.
- **Chat**: one-time system message on first session start (ClientSessionEvents).
- **F3**: `voxy-<ver> (disabled)` + reason (DebugEntries version entry).
- **ModMenu**: Voxy options always registered; `enabled` starts off when inactive.

### Deactivation matrix

| Graphics API setting | Actual backend | Voxy state |
|---|---|---|
| `DEFAULT` (≈ OpenGL) | OpenGL | deactivated (`Vulkan not selected`) |
| `OPENGL` | OpenGL | deactivated (`Vulkan not selected`) |
| `VULKAN` | Vulkan | gate passes → capability check → pipeline-under-construction (inert, log only) |
| `VULKAN` | OpenGL (driver fallback) | deactivated (`Vulkan selected but couldn't activate`) |
| `--graphicsBackend vulkan` | Vulkan | gate passes (same as above) |

### Reference layout
- GL renderer sources for the Phase 2/3 port: `docs/gl-reference/java/me/cortex/voxy/client/**`
- GLSL shaders: `docs/gl-reference/shaders/**`
- Full GL-era snapshot: git tag `gl-legacy`

---

## 8. Phase 1C — Vulkan renderer foundation ("Hello Vulkan") (2026-08-19)

### What was built
- **`client/core/vk/shader/`** — standalone raw shader path (shaderc -> SPIR-V -> own VkPipelines):
  `VkShaderCompiler` (GLSL `#version 450`, Vulkan 1.2 env, auto locations/bindings),
  `VkShaderModule`, `VkPipelineLayout` (push-descriptor set layout at set 0),
  `VkPipelineBuilder` (dynamic rendering: RGBA8 color + D32 depth, matching MC's main target).
  Deliberately independent of MC's `RenderPipeline`/`BindGroupLayout`/`GlslCompiler`
  (deferred until Aperture ships) - Aperture compat becomes an additive layer later.
- **`client/core/vk/VkBuffer`** — VMA-backed buffers (`VkContext.vmaAllocator()` + `org.lwjgl.util.vma.Vma`).
- **`client/core/vk/VkRenderProperties`** — reverse-Z detection from MC's shared
  `DepthStencilState.DEFAULT.depthTest()` (zero-to-one is always true in Vulkan).
- **`client/core/vk/VoxyVulkanRenderSystem`** — owns shaders/pipelines/buffers; per-frame UBO via
  MC's **`TransientMemory.uploadStaging`** (raw `VulkanGpuBuffer.vkBuffer()` + slice offset for push
  descriptors); records a view-space demo triangle into the terrain pass.
- **Mixins (`client/mixin/minecraft/vulkan/`)** — `@Invoker` on `VulkanRenderPass.commandBuffer()`
  and `VulkanCommandEncoder.commandBuffer()`, `@Accessor`/`@Shadow` on `currentRenderPass`, and
  `@Inject(submitRenderPass HEAD)` -> splice Voxy's draws into the still-open dynamic-rendering
  pass, filtered by pass label:
  - `"Section layers for "` (vanilla chunk terrain)
  - `"Terrain"` (Sodium 26.2's Vulkan chunk renderer label)
- **Shaders** (`assets/voxy/shaders/vk/`): `demo.vert`, `demo.frag`, `demo.comp` (compute
  pipeline compiles; dispatch comes with the data path).

### Depth masking (Milestone 1)
- Depth-only: Voxy pipelines use MC's projection + matching compare op
  (GEQUAL on reverse-Z, LESS otherwise) against vanilla's D32 depth. No stencil (D32 has none).

### Verified
- `./gradlew build` green; jar contains the vk/ layer, the 3 new mixins, and the shaders;
  zero `org.lwjgl.opengl` / `RenderPipeline` / `BindGroupLayout` usage in voxy code.
- In-game test needed on NVIDIA / AMD / Apple Silicon (MoltenVK): a green triangle should appear
  in front of the camera (view space, z=-3) when "Prefer Vulkan (Experimental)" is active.
  Dev validation: launch with `--vulkanValidation`.

### LWJGL-vulkan quirks discovered (this build)
- `VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1/1_2_FEATURES`, mesh-shader constants absent ->
  use spec literals (see `VkCapabilities`).
- `VK_CULL_MODE_NONE` (not `_NONE_BIT`); `MemoryUtil.memCopy` is 2-arg only;
  `vkCmdBindVertexBuffers` has no `bindingCount` param; some functions take raw
  `long[]` (e.g. `vkCreateGraphicsPipelines`) while others take `LongBuffer`.

---

## 9. Phase 2 — GPU data path (2026-08-19)

### What was built
- **3-point splice model** (`VulkanCommandEncoderMixin`): `submitRenderPass` HEAD (draws),
  `submitRenderPass` RETURN (compute + buffer copies, the command buffer is open between passes),
  `submit()` HEAD (end-of-frame catch-all). `onRenderPassCreated` captures the terrain pass's
  depth view from `RenderPassDescriptor.depthAttachment()`.
- **`VkSync`** - wraps MC's public static `VulkanCommandEncoder.memoryBarrier` (sync2,
  all-commands, everything lives in GENERAL layout).
- **`VkUploadStream`** - VMA host-visible 64MiB staging ring, persistently mapped; per-target
  `VkBufferCopy[]` regions at splice 2 + barrier; staging freed via
  `RenderSystem.queueFencedTask` (2-frames-in-flight); no-wrap with reset-on-overflow.
- **`VkDownloadStream`** - GPU_TO_CPU 32MiB readback ring; callbacks (render thread, after the
  frame's fence) via `queueFencedTask`; `flushWaitClear()` on shutdown (executePendingTasks +
  `VulkanQueue.waitIdle`).
- **`VkTexture` / `VkMemory` / `VkSampler`** - raw VkImage + device-local memory + per-mip views
  (MC's `GpuTexture` cannot create storage images); raw allocator (VMA in this LWJGL build only
  covers buffers); NEAREST/CLAMP sampler.
- **`VkHiZBuffer`** - all-compute HiZ: `hiz_init.comp` downsamples the vanilla depth into our
  pow2 R32F mip_0 (sized by `highestOneBit` like the GL era), `hiz.comp` (ported, subgroup
  clustered max) fills mips 1..6; built at splice 2 right after the terrain pass (no stale
  frame); gated on `subgroupArithmetic`; skipped for windows < 128px (needs 7 mips).
- **`VkSharedIndexBuffer`** - u16 quad + cube index buffers (Vulkan forbids u8), uploaded via the
  stream.
- **Demo proof** - the quad is now uploaded through `VkUploadStream` into a device-local buffer,
  drawn via the shared u16 index buffer (`vkCmdDrawIndexed`, `VK_INDEX_TYPE_UINT16`); the demo
  compute shader dispatches every 120 frames into a device-local counter buffer and reads it
  back through `VkDownloadStream` -> "Voxy (Vulkan): compute counter readback = 64 (expected 64)".
  Initial draw is gated on the first committed upload (frame-order, no fence needed).
- **`VkShaderCompiler.loadResource`** shared helper; `VkBuffer` gained `deviceLocal`/`hostVisible`
  factories, `fill()` (vkCmdFillBuffer) and persistent mapping.

### Verified
- `./gradlew build` green; jar contains all data-path classes + shaders; zero GL usage.
- In-game test on NVIDIA / AMD / MoltenVK: green quad after ~1 frame, log shows the counter
  readback (~120 frames in), F3 describe() shows upload offset + HiZ dims. `--vulkanValidation`
  in dev.

### LWJGL quirks added
- `pImageInfo`/`pBufferInfo` take `.Buffer` types (no single-struct overloads).
- `vmaCreateImage` does not exist in this LWJGL build -> raw `vkCreateImage` + device-local
  allocation (`VkMemory`).
- `MemoryUtil.memCopy` is 2-arg only; `vkCmdBindVertexBuffers`/`vkCmdBindIndexBuffer` take
  `LongBuffer`+`long` args; `vkCmdFillBuffer` present.

## 10. Phase 3 — Traversal core + GPU LOD generation (2026-08-19)

Completes the CPU octree (re-homed from the GL reference) and the GPU hierarchical occlusion
traversal, plus moves LOD (voxel mip) generation onto the GPU. Nodes exist in the octree and are
traversed/culled each frame, but receive no geometry yet (meshing is Phase 4) - requests
accumulate, which is the intended end-to-end proof of the loop.

### Shader infra (`VkShaderCompiler` / `VkPipelineLayout`)
- `#import <voxy:path>` resolution against `assets/voxy/shaders/vk/<path>` (recursive, guarded by
  the existing `#ifndef` include guards), and define injection after `#version`
  (`compile(name, src, stage, defines)`).
- `VkPipelineLayout` gained an optional push-constant range (COMPUTE stage).
- **Unified 2-uint push constant block** shared by traversal + cleaner: `a` = queueIdx /
  visibilityCounter / count; `b` = activeHalfOffset / setTo. Each compiled shader has exactly one
  declaration (node.glsl provides it for traversal+sorter; the transformer and batch shaders
  declare their own).

### GPU LOD generation (`VkLodGenerator` + `mip.comp`)
- `mip.comp` is an exact port of the CPU `Mipper.mip` rule (max `(opacity<<4)|cornerIdx` over
  non-air children, else averaged light applied to I111), computed in place over the section's
  internal layout `[4096 L0][512 L1][64 L2][8 L3][1 L4]`. Requires `shaderInt64` (gated).
- Opacity table = per-blockId byte snapshot from `Mapper.getOpacityTable()` (revision-tracked),
  uploaded host-visible on change.
- `WorldVoxilizedSectionMipper.mipSectionOrDispatch(section, world, mapper, onDone)` routes the
  ingest mip step through a registered `MipDispatcher` (VkLodGenerator) with a CPU fallback;
  ingest/importer call sites use the provided (possibly owned-copy) section in the continuation.
- `VkLodGenerator` is **split stage()/dispatch()**: `stage` writes base data into the upload
  stream (before commit), `dispatch` records the 4 mip dispatches + readbacks (after commit) -
  required because dispatches must be recorded after the copies they read.
- On completion the download callback runs the ingest continuation (`WorldUpdater.insertUpdate`)
  on the render thread; the world holds a ref for the job's lifetime.

### Octree re-home
- Moved back from `docs/gl-reference/java/`: `NodeManager`, `NodeStore`, `NodeChildRequest`,
  `SingleNodeRequest`, `SectionUpdateRouter`, `ISectionWatcher`, `RenderDistanceTracker`,
  `GeometryCache`, `BuiltSection`, `ExpandingObjectAllocationList`, geometry interfaces,
  `AsyncNodeManager`. GL references stripped:
  - `NodeStore.writeNode(ByteBuffer, nodeId)` (LE); `NodeManager.writeChanges(VkBuffer, offset,
    VkUploadStream)` + `writeNode(int, ByteBuffer|long)`.
  - `AsyncNodeManager` ctor `(maxNodeCount, IGeometryManager, @Nullable meshRequestConsumer)`;
    render-thread `tick` applies node updates via upload-stream copy regions into the **inactive
    half** of the double-buffered node array; geometry uploads/metadata are stubbed for Phase 4.
  - `IGeometryManager` gained default `getGeometryUsedBytes()`/`getSectionCount()`.
- `NoOpGeometryManager` used this phase (nodes never receive geometry).

### Traversal (`VkTraverser` + ported shaders)
- Ported: `traversal_dev.comp` + `node.glsl`, `queue.glsl`, `screenspace.glsl` (with
  `frustum.glsl`/`pos_util.glsl`/`depthutils.glsl`). Bindings moved to set 0 (HIZ=0, SCENE=1,
  REQUEST=2, RENDER=3, NODE_DATA=4, META=5, SOURCE=6, SINK=7, TRACKER=8, STATS=9); `queueIdx`
  uniform-location -> push constant; `USE_REVERSE_Z`/`USE_ZERO_ONE_DEPTH` always defined (MC
  Vulkan is reverse-Z); `HAS_STATISTICS` always on (counters always written, readback gated by
  the F3 toggle).
- Buffers: double-buffered `nodeBuffer` (2*maxNodeCount*16, fill -1), `requestBuffer` (50*8+8),
  host-visible 256B UBO, `statisticsBuffer`, `queueMetaBuffer` (host-visible,
  `VK_BUFFER_USAGE_INDIRECT_PARAMETERS_BIT` = literal 0x1000, trimmed from LWJGL),
  `topNodeIds`, `scratchQueueA/B`, `renderList` (200k).
- CPU camera math: `MVP = projection * viewRotation` (no translation - terrain.vsh applies
  `Position + (ChunkPosition - CameraBlockPos) + CameraOffset`), `section = floor(cam)>>5`,
  `innerTranslation`; std140 UBO written at the GL offsets (0/64/76/80/92/96/192/196/200/204).
- 5-iteration dispatch chain: direct first dispatch from `topNodeIds` (queueIdx 0), then
  `vkCmdDispatchIndirect(queueMetaBuffer, iter*16)` with A/B scratch flip-flop; request + stats
  readbacks via `VkDownloadStream`.
- TLN add/remove mirror on the CPU, re-uploaded to `topNodeIds` when dirty.

### Cleaner (`VkNodeCleaner` + ported shaders)
- `visibilityBuffer` (maxNodeCount*4, fill -1; doubles as the traversal's render tracker),
  `outputBuffer` (256*12), `idListBuffer`; sorter/transformer/batch-clear pipelines.
- `tick` (pre-commit) = sorter -> transformer + remove-batch readback; `collectIds` (staged) +
  `dispatchIdUpdates` (post-commit) = batch_visibility_set. `shouldCleanGeometry` (256MB-free
  heuristic) is inert this phase (no geometry to evict) but `visibilityId` still increments
  (the traversal's frameId).

### Wiring (render system, splice 2 order)
HiZ build -> `AsyncNodeManager.tick` (stage node updates into inactive half) -> `cleaner.tick`
-> `traverser.stage` + `lodGen.stage` -> `uploadStream.commit` -> `cleaner.dispatchIdUpdates` ->
`traverser.dispatch` -> `lodGen.dispatch` -> `counterTick` -> `downloadStream.commit` -> toggle
active half. `setNodeManager`/`clearNodeManager` create/destroy the traverser+cleaner per world.

### World engine reactivation
- `VoxyClient.initVoxyClient` registers the instance factory (once) when the gate passes, so a
  `VoxyClientInstance` is created at session start and chunk ingest fills the octree.
- `VoxyInstance.onWorldEngineCreated` hook (new) -> `VoxyClientInstance` builds an
  `AsyncNodeManager` (1<<21 nodes, NoOpGeometryManager), wires `world.setDirtyCallback`, and
  hands it to the render system; torn down on shutdown.

### Verified
- `./gradlew build` green; jar contains the octree classes, VkTraverser/VkNodeCleaner/
  VkLodGenerator + all ported shaders; zero `org.lwjgl.opengl` references in the re-homed and
  new code.
- In-game (NVIDIA / AMD / MoltenVK): world engine + octree active, traversal runs each frame;
  F3 `HTC:[...]` shows traversal counts > 0 with mesh requests accumulating (nodes stay EMPTY
  until the meshing phase). `--vulkanValidation` in dev.

## 11. Phase 4A — GPU geometry data path (2026-08-19)

Wires the section-geometry data path: CPU-side geometry manager (section ids + quad heap +
uploads/removals/metadata) feeds GPU uploads/metadata rewrites through the existing copy-region
streams. Nodes still receive no meshes (GPU greedy meshing is 4B), so a debug command drives the
path end-to-end.

### `VkSectionGeometryData` (new, `client/core/vk/`)
Vulkan rewrite of the GL `BasicSectionGeometryData`:
- `metadataBuffer`: device-local, `maxSectionCount * 32` bytes (`SectionMeta` per section id).
- `geometryBuffer`: device-local, full-capacity quad heap (8 bytes per quad; no GL sparse in the
  port). `close()` waits for the device before freeing.
- Created per world in `setNodeManager` (render thread); destroyed in `clearNodeManager`.

### Geometry managers (re-homed, CPU-only)
- `AbstractSectionGeometryManager` (unchanged) + `BasicAsyncGeometryManager` (+ nested
  `SectionMeta`) moved back; `writeMetadata`/`writeMetadataSplit` rewritten to little-endian
  `ByteBuffer` writes (the render thread copies them into the upload stream).
- `IGeometryManager` gained a `getGeometryCapacityBytes()` default; `AsyncNodeManager` now types
  its geometry manager as `BasicAsyncGeometryManager` (needs `getUploads()`/`getHeapRemovals()`/
  `getUpdateIds()`).

### AsyncNodeManager geometry sync + apply
- CPU sync loop re-added: `heapUploads` -> `results.geometryUpload.upload(point, buf)` (then frees
  the MemoryBuffer, ownership transferred); `heapRemoveUploads` -> `geometryUpload.remove`;
  `invalidatedIds` -> 32-byte metadata snapshots in a new `results.metadataUpdates` map.
- Render-thread `tick(...)` gained a `VkSectionGeometryData` param and applies:
  - quad uploads via `VkUploadStream` copy regions from the scratch header/data buffers into the
    geometry buffer (`dst = point*8`);
  - metadata rewrites as 32-byte copy regions into `metadataBuffer` (`dst = id*32`);
  - `setSectionCount(results.geometrySectionCount)`.
- `needsWaitForSync` limits (2 MiB/frame, cleaner ops, scatter map, tln delta) now live.

### Cleaner eviction live
`shouldCleanGeometry` now uses the real capacity/used bytes (1 GiB default), so the
sorter -> transformer -> remove-batch -> `NodeManager.removeNodeGeometry` -> `removeSection` loop
is active (it just won't trigger until geometry actually fills VRAM).

### Debug hook
`/voxy testmesh` builds a synthetic 6-quad cube (`BuiltSection`) and pushes it through
`AsyncNodeManager.submitDirectGeometryUpload` (a new async-thread direct-upload queue that
bypasses the octree node mapping). It flows: async thread -> `uploadSection` -> sync loop ->
copy regions + metadata on GPU. F3 `instance_debug` shows `UC/GC,#N:` used/capacity/sections.

### Verified
- `./gradlew clean build` green; jar contains VkSectionGeometryData + the re-homed managers; zero
  `org.lwjgl.opengl` in the geometry/octree code.
- In-game: `/voxy testmesh` -> F3 `UC/GC` used geometry increases (48 bytes + upsize), section
  count = 1, metadata written to the GPU buffer. `--vulkanValidation` in dev.

### Deferred (4B/4C)
- GPU greedy meshing (`RenderDataFactory` -> compute) feeding real BuiltSections.
- VDIC renderer (prep/cull/cmdgen/prefixsum/buildtranslucents + `quads3`/`quads` shaders +
  `vkCmdDrawIndexedIndirectCount`) with the full `ModelStore`/atlas bake.

## 12. Phase 4B — GPU greedy meshing (opaque) + shader validation harness (2026-08-19)

Ports the opaque passes of the greedy mesher (`RenderDataFactory`) to a per-section compute shader,
with the GPU doing both the voxel->quad-data preparation and the greedy scan. The real model
baking/service wiring is the next increment; this phase delivers the mesher core + a self-contained
verification path plus a critical shader-compilation fix.

### CRITICAL FIX: shaderc compute kind
`shaderc_compute_shader` is **2** in this LWJGL build (not 5). `VkShaderCompiler` now uses the
binding's named constants instead of hardcoded values. Without this, every compute shader (HiZ,
traversal, cleaner, mip) was being compiled as a non-compute stage and would have failed at
runtime. Verified offline via the new test harness (see below).

### Shader validation harness (`src/test/.../ShaderCompileTest.java`)
Compiles every shipped shader (demo, hiz, mip, mesh, traversal + cleaner, with the exact defines
the Java side injects) through the real shaderc path under Gradle. This caught the kind bug and
several GLSL portability issues:
- `u64` literal suffixes are not accepted by this glslang -> use `uint64_t(0x...u)` casts.
- Binary literals (`0b...`) with `u`/`u64` suffixes unsupported -> decimal equivalents.
- GLSL precedence: `&` binds *lower* than `==` -> parenthesize `((x & m) == c)`.
- A local named `max` shadowed the `max()` builtin (mip.comp) -> renamed to `best`.
- `mip.comp` needed the int64 extension line.

### `mesh.comp` (new)
Per-section compute (64 threads, one workgroup): stage 1 prepares `sectionData[2*32^3]` +
`opaqueMasks/nonOpaqueMasks/fluidMasks` from raw voxels + `idMappings`/`metadataCache`
(`atomicOr` per voxel; missing model -> flag); stage 2 runs the faithful opaque greedy port:
YZ inner/outer then X inner/outer, one mesher thread per plane (the X passes were already 32-way
parallel in the CPU), with the exact `ScanMesher2D` `putNext/skip/endRow/finish` + `emitQuad`
64-bit packing + lane routing + AABB atomics; stage 3 packs the AABB + totals. Requires
`shaderInt64`.

### `VkModelTables` (new)
Host-visible device buffers for `idMappings` (1<<20 ints) + `metadataCache` (1<<16 u64),
revision-gated re-upload. Real data comes from the model baking subsystem (next increment);
supports synthetic seeding for the mesher verification.

### `VkMeshGenerator` (new)
4 host-visible slots; `stage()` uploads the 32768-long section + 6144-long neighbor planes into
a slot via the upload stream; `dispatch()` runs the mesh compute (after the commit + table
upload); a fenced task reads the host-visible slot, compacts the per-lane quads into a
`BuiltSection`, and feeds it through `AsyncNodeManager.submitGeometryResult`. `pending` is a
`ConcurrentLinkedDeque` (cross-thread submit from the debug command).

### Wiring + verification
- Render system init creates the tables + generator; splice-2: `meshGen.stage()` (pre-commit) ->
  `tables.upload(cb)` + `meshGen.dispatch(cb)` (post-commit).
- `/voxy testmesh` now seeds synthetic tables (a fully-opaque cube model) + a solid 32^3 section
  through the GPU mesher; the readback logs the result. Expected: 6 quads (one 32x32 quad per
  outer face), lanes `[0,0,0,1,1,1,1,1]`, AABB covering 0..32.
- `./gradlew build` green (includes the shader test).

### Deferred (4B next / 4C)
- Model baking subsystem re-home (ModelFactory/ModelBakerySubsystem/bakery) -> real
  `idMappings`/`metadataCache` + the `GpuMeshService` (dedup/priority/requeue on bake-miss)
  feeding real section positions.
- Fluid + non-opaque mesh passes.
- 4C: VDIC renderer + atlas draw.
