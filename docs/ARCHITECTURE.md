# Metal renderer for Minecraft Java: architecture sketch

Status: draft, 2026-10-04. Nothing is built yet.

## Goal

A shader mod for Minecraft Java on Apple Silicon that runs at 60 fps on base and
Pro chips, not only on Max chips. It is one opinionated renderer, not a loader
for OptiFine/Iris shader packs.

Reference machine: MacBook Pro, M5, 10-core GPU, 32 GB memory.
Target: 60 fps (16.6 ms per frame) at native Retina output (3456x2168), 16-chunk
render distance, with fog, shadows, water reflections, sky and clouds.

## What we measured (2026-10-04, M5, Minecraft 26.3)

Same camera in every run: 100 blocks up, over a forest and a lake.
Output resolution 3456x2168. Raw numbers are in `/tmp/mcbench/results.txt`.

| Setup                                  | FPS | GPU ms/frame |
|----------------------------------------|-----|--------------|
| Vanilla, Vulkan through MoltenVK       | 47  | 19.7         |
| Rockstar Shaders 1.0, 50% render scale | 14  | 71.5         |
| Rockstar Shaders 1.0, 75% render scale | 10  | ~105         |
| Rockstar Shaders 1.0, 100% scale       | 7   | 149.3        |

What this tells us about frame cost:

- **Terrain geometry is a fixed cost.** Rockstar's G-buffer pass took 17-28 ms
  and barely changed with resolution. Vanilla's whole frame is about 20 ms.
- **Ray-marched clouds scale with pixel count:** 7.8 ms at 50% scale, 32.8 ms
  at 100%.
- **The Vulkan-to-Metal hand-off costs 5-11 ms per frame.** That is the
  `rt_trace_wait` pass, where the Vulkan side waits for the Metal ray-tracing
  step.
- **Intermediate images use 1-1.6 GB.** That is a lot of memory traffic on a
  chip with about 153 GB/s of bandwidth.

Other people's evidence (not verified by us):

- **MetalCraft** (GPL-3) reports vanilla terrain at about 4.7 ms of GPU time per
  frame on an M5, render distance 16, with a native Metal backend for Blaze3D.
  Its scene and resolution differ from ours.
- **@riccqi** claims a native Metal renderer with fog, dynamic lighting and water
  reflections at 60 fps at full Retina resolution on a roughly 5-year-old
  MacBook. No code has been published.

### Phase 0 results (2026-10-04): the graphics API is not the bottleneck

Same seed, same camera, native 3456x2168, render distance 16. GPU time comes
from Instruments Metal System Traces (5 s each, split per process and per GPU
channel, parsed with `/tmp/mcbench/gpuparse.py`).

| Setup                                   | FPS  | GPU ms/frame | Vertex | Fragment |
|-----------------------------------------|------|--------------|--------|----------|
| 1.21.11 vanilla, OpenGL                 | 36-50 (F3) | GPU 98% busy | n/a | n/a  |
| 1.21.11 + MetalCraft 0.1.0 (native Metal) | 44.5 | 21.7       | 6.3    | 19.2     |
| 26.3 vanilla, OpenGL                    | 41 (F3) | GPU 89% busy | n/a  | n/a      |
| 26.3 vanilla, Vulkan (MoltenVK)         | 56.6 | 17.1         | 5.0    | 12.4     |
| 26.3 Vulkan, opaque leaves              | 59.4 | 16.5         |        |          |
| 26.3 Vulkan, clouds off                 | 60.6 | 16.3         |        |          |
| 26.3 Vulkan, 1/4 of the pixels          | 77.4 | 12.5         | 6.6    | 6.3      |

Vertex and fragment work overlap on the GPU, so those two columns don't add up
to the total.

What we learned:

- **Every backend is GPU-bound** (89-98% busy) on the same vanilla rendering
  work. Native Metal for vanilla, as MetalCraft does it, was not faster than
  MoltenVK. Its 4.7 ms claim comes from a lighter scene.
- **About 11 ms of the 17 ms frame doesn't depend on resolution.** A quarter of
  the pixels only saved 4.7 ms. On Apple's tile-based GPUs the "fragment" stage
  also includes per-triangle work: binning triangles into tiles and
  hidden-surface removal. That part scales with triangle count, not pixels. So
  the cost is triangle volume: vanilla's naive terrain path draws every block
  face of every visible section.
- **Leaf transparency and clouds are minor** (about 4-5% each).
- **WindowServer showed 64-79% GPU busy in every run** (compositing a windowed
  game on a 120 Hz display). Test fullscreen before trusting absolute numbers.

Revised conclusion:

- **Biggest lever:** draw far fewer triangles. That means GPU occlusion culling,
  face-direction culling, merged quads (greedy meshing) and low-detail far
  terrain. Phase 1 starts there.
- **Native Metal is still the right base, for different reasons than first
  assumed:** memoryless tile G-buffer, MetalFX, Metal ray tracing with no
  cross-API waiting, and indirect command buffers. Raw API overhead isn't one of
  the reasons.

## Integration decision (2026-10-04, provisional)

Two independent opinions were gathered: Opus (author of this doc) and an
independent review by GPT-6.1 Sol, which was given the problem, constraints and
evidence but not the author's preference.

- **Opus initially proposed option A:** a full Metal backend for Minecraft's
  GPU abstraction, plus our own world renderer.
- **Sol recommends a staged option B:** keep Minecraft's Vulkan backend and its
  swapchain/presentation, and run one or two large native Metal sections per
  frame by sharing images through `VK_EXT_metal_objects`. Move to A only if a
  measured gate fails.
  - Sol's argument: A has to make every resource, pipeline, upload, readback
    and other mods' draws work before the world renderer delivers any value.
  - Sol also argues that Rockstar's 5-11 ms `rt_trace_wait` shows how Rockstar
    synchronises, not an unavoidable interop cost.

Adopted: **Sol's staged plan, with an explicit gate.** Reasons:

- Its first prototype is cheap, and decisive for both options.
- It keeps the GUI, entities and other mods working from day one.
- A remains open. Sol verified that in 26.3 the GPU API moved to Mojang's
  `com.mojang.renderpearl` library (`FrontendGpuDevice` wraps a
  `GpuDeviceBackend`), and that backends receive SPIR-V. The game also ships
  LWJGL's SPIRV-Cross (`lwjgl-spvc`), so a later Metal backend would not need
  to process GLSL.

Facts from Sol's inspection that the plan depends on (verify in the prototype):

- `VulkanDevice` exposes the device and queue; `VulkanGpuTexture` exposes
  `vkImage()`.
- `VulkanCommandEncoder` throttles submissions, so every extra submit boundary
  must be measured.
- `GpuSurface` / `VulkanGpuSurface` own the swapchain and presentation.
- Windowing is SDL, not GLFW.
- Vanilla terrain already supports multi-draw indirect. `LevelRenderer`,
  `ChunkSectionsToRender` and `SectionRenderDispatcher` are the places to hook
  terrain replacement.
- `VK_EXT_metal_objects` export must be declared when an image is created.
  Existing images can't be extracted, so the shared images have to be ours.

Agreed by both:

- Java 25 FFM over a small C ABI. Batch calls per frame, never per draw.
- Remember that FFM arenas don't know when the GPU has finished with memory.

Checked and ruled out: 26.3's "improved transparency" pipeline was off in all
benchmark runs (`improvedTransparency:false`).

Disagreement still open:

- Sol says the "about 11 ms fixed cost" reading is a crude two-point fit, not
  proof of a triangle bottleneck. Changing resolution also changes how GPU work
  overlaps.
- Settle this with an Xcode GPU frame capture (per-pass and per-draw timings,
  bandwidth, attachment load/store) at 100/67/50% scale, windowed and
  fullscreen, before investing in terrain.

### Gate 1: in-game Vulkan -> Metal -> Vulkan round trip, then MetalFX

Steps:

- Render the world on Vulkan into our own exportable offscreen colour and depth
  images at about 2316x1452.
- Submit at an explicit boundary.
- Run a Metal pass, then MetalFX upscaling to 3456x2168.
- Hand back to Vulkan for the GUI and normal presentation.
- Synchronise GPU-side only: no `waitIdle` / `waitUntilCompleted` per frame.

Pass bars:

- Correct over a 10-minute run that includes resize, resource reload and
  fullscreen toggles.
- No-op round-trip cost of 0.5 ms or less p95.
- MetalFX costs 1.5 ms or less.
- Vanilla plus upscaler at the target scale: 14 ms GPU or less p95.

If it fails:

- Failing the sync/overhead bar rejects option B. Re-evaluate A.
- Failing the total-time bar means terrain must be optimised before any
  effects are added.

## Overall structure (sketch assumed option A; under B, Minecraft's Vulkan backend stays and `MetalGpuDevice` is deferred)

```
+-----------------------------  Java (Fabric mod, MC 26.3)  -----------------------------+
|  Mixins / entrypoints                                                                  |
|   - MetalGpuDevice: implements Blaze3D GpuDevice (GUI, entities, particles, items)     |
|   - WorldRenderer hook: takes over terrain + sky + weather drawing in LevelRenderer    |
|   - ChunkMesher: builds compact section meshes on worker threads (Sodium-style)        |
|   - Settings UI, benchmark/telemetry hooks                                             |
+----------------------------------------  FFM  -----------------------------------------+
|  Native library (Objective-C++ / metal-cpp, arm64 only)                                |
|   - Device, queues, frame pacing (CAMetalLayer, 3 frames in flight)                    |
|   - Arena allocators on unified memory (meshes, uniforms, transient)                   |
|   - Render graph: passes, transient-resource aliasing, memoryless attachments          |
|   - Shader library: hand-written MSL, precompiled .metallib + binary archives          |
|   - GPU timers (MTLCounterSampleBuffer) -> per-pass timings back to Java               |
+----------------------------------------------------------------------------------------+
```

Notes:

- **Use Java 25's Foreign Function & Memory API (FFM), not JNI.** Minecraft 26.x
  already ships Java 25, FFM has no glue code to maintain, and calls are cheap
  enough to batch per frame.
- **Unified memory means no uploads.** Chunk meshes are written by the meshing
  threads straight into shared `MTLBuffer` regions.
- **Keep both renderers.** `MetalGpuDevice` exists so anything we don't take over
  (GUI, entities, other mods' Blaze3D draws) still works. The custom world
  renderer is where the performance comes from.

## Frame pipeline

Rendering happens at an internal resolution (default 67%, about 2304x1445) and
is then upscaled to native.

1. **Terrain culling (compute).** Frustum culling per section, plus occlusion
   culling against a depth pyramid built from last frame. Per-direction face
   culling (only faces that can face the camera). Writes indirect draw
   commands.
2. **Terrain G-buffer and lighting in one render pass** (see the next section).
   The G-buffer stays in on-chip tile memory. Only lit HDR colour, depth and
   motion vectors are written to memory.
3. **Shadows.** Cascaded shadow maps. Terrain doesn't move, so the far cascades
   are cached and redrawn every N frames or when the sun moves. Only the near
   cascade is redrawn every frame.
4. **Translucents and water**, forward-rendered. Water uses screen-space
   reflections with a sky/cubemap fallback, and refraction from a copy of the
   opaque colour.
5. **Volumetric fog** in a coarse 3D grid of camera-aligned cells (for example
   160x90x64), computed in a compute shader, filtered over time, and applied in
   one composite step.
6. **Clouds** at 1/4 resolution, with temporal reprojection to fill in the rest.
   Rockstar's clouds took 33 ms at full resolution, so this is the clearest win.
7. **Ambient occlusion** at half resolution, with a bilateral upsample.
8. **Upscale to native.** MetalFX temporal upscaling, with our own TAA as a
   fallback. Note: Rockstar's docs say MetalFX lost detail in panning shots, so
   compare both.
9. **Post-processing** in one compute pass: bloom (a small mip chain), exposure,
   tonemapping and colour grading.
10. **GUI and HUD** at native resolution through `MetalGpuDevice`.

### Deferred shading kept in tile memory (the core Apple Silicon advantage)

Use a single render pass that draws the G-buffer and then shades it:

- G-buffer attachments (albedo, normals, material, light levels) use
  `MTLStorageModeMemoryless`. They exist only in on-chip tile memory and are
  never written to RAM.
- The lighting step reads those attachments in the same pass, using programmable
  blending or a tile shader, and writes HDR colour.
- Results stored to memory: HDR colour (RGBA16F), depth, motion vectors.
  Everything else is discarded at the end of the tile.

This directly removes the memory traffic that dominates Rockstar's
1-1.6 GB of intermediate images.

### Terrain data

- **Compact vertices, 16-20 bytes each:** packed position relative to the
  section, texture-atlas coordinates, normal index, light levels, ambient
  occlusion.
- **One large arena buffer for all sections**, with a free list. Meshes are
  rebuilt on worker threads and written in place.
- **Draw from GPU-built command lists.** Draws are either
  `drawIndexedPrimitives(indirectBuffer:)` or a Metal indirect command buffer
  filled by the culling pass. The CPU cost per frame stays close to constant.
- **Later: low-detail far terrain** (Voxy/Distant Horizons style) for
  "horizon" distances, kept out of the main geometry budget.

## Frame budget (targets, not measurements)

Internal resolution 67%, output 3456x2168, M5 10-core GPU:

| Pass                                   | Budget  |
|----------------------------------------|---------|
| Culling + depth pyramid                | 0.5 ms  |
| Terrain G-buffer + lighting (tile)     | 5.0 ms  |
| Shadows (cached far cascades)          | 1.5 ms  |
| Translucents + water + reflections     | 1.5 ms  |
| Volumetric fog                         | 1.5 ms  |
| Clouds (1/4 resolution + reprojection) | 1.0 ms  |
| Ambient occlusion (half resolution)    | 0.8 ms  |
| Upscale (MetalFX or TAA)               | 1.5 ms  |
| Post-processing                        | 1.0 ms  |
| Entities, particles, GUI               | 1.5 ms  |
| **Total**                              | **~15.8 ms** |

The biggest risk is the 5 ms terrain number. Phase 0 checks it first.

## Phased plan

**Phase 0: prove native Metal is worth it. DONE 2026-10-04, see "Phase 0
results" above.**
- Reuse the benchmark harness from the Rockstar test: a world generated by
  Mojang's dedicated server, the camera set in the player's save file, Metal HUD
  plus GPU pass timers.
- Measure MetalCraft (Minecraft 1.21.11) against vanilla OpenGL and vanilla
  Vulkan on the same scene. This tells us how much of the 19.7 ms is MoltenVK
  and the vanilla renderer, and how much is unavoidable.
- Exit criterion: vanilla-equivalent terrain at 6 ms of GPU time or less, at
  native resolution, on our scene.

**Phase 1: Metal backend plus our own terrain renderer.**
- `MetalGpuDevice` for everything else in the game, so vanilla looks the same
  as before.
- GPU-driven terrain with compact meshes, compute culling and indirect draws.
- Cut triangle count first: occlusion culling against a depth pyramid,
  per-direction face culling, merged quads for identical faces (texture
  repeat inside the atlas, or a texture array), and low-detail far sections.
- Exit criterion: vanilla-look terrain at 6 ms of GPU time or less on the
  reference scene. The current baseline is 17.1 ms for vanilla on Vulkan.

**Phase 2: lighting in tile memory, plus sky.**
- Single-pass deferred shading in memoryless tile memory, PBR-lite materials
  (LabPBR later), physically based sky look-up tables, sun and block light.

**Phase 3: effects.**
- Shadows, water with reflections, volumetric fog, clouds, ambient occlusion,
  bloom. Each one is added only if it fits its line in the budget table.

**Phase 4: upscaling and polish.**
- MetalFX compared with our own TAA, a dynamic-resolution governor, quality
  presets.

**Phase 5 (optional, M3 and later): hardware ray tracing.**
- Ray-traced shadows and water reflections in Metal compute. There is no
  cross-API waiting because everything is in Metal.

## Benchmarking rules

- Fixed world seed, fixed camera, a 50-second warm-up, and Minecraft's idle
  frame-rate cap turned off (`inactivityFpsLimit:"minimized"`).
- Record per-pass GPU timings, total GPU time (Metal HUD), frame time and the
  1% lows.
- Every change to the renderer gets a before/after number on the reference
  scene.

## Decisions

1. **Decided (2026-10-04): write the backend from scratch, under a permissive
   license** (Apache-2.0 or MIT, not yet chosen). MetalCraft (GPL-3) is used only
   as an external benchmark. Do not read or copy its source.
2. **Minecraft version.** Target 26.3 now. Blaze3D changes between releases, so
   expect some porting work on every update.
3. **Compatibility.** This replaces terrain, so it is incompatible with Sodium,
   Iris, Distant Horizons and Voxy, the same as Rockstar.
4. **Minimum hardware.** M1 and later (Metal 3) for the main renderer.
   Ray-tracing features only on M3 and later.
