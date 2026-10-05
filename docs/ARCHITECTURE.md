# Metal renderer for Minecraft Java: architecture sketch

Status: draft, 2026-10-04. Scaffold built; Gate 1 in progress.

## Goal

A shader mod for Minecraft Java on Apple Silicon that runs at 60 fps on base and
Pro chips, not only on Max chips. It is one opinionated renderer, not a loader
for OptiFine/Iris shader packs.

Reference machine: MacBook Pro, M5, 10-core GPU, 32 GB memory.
Target: 60 fps (16.6 ms per frame) at native Retina output (3456x2168), 16-chunk
render distance, with fog, shadows, water reflections, sky and clouds.

## Measurements (2026-10-04, M5, Minecraft 26.3)

> **Correction.** The first round of numbers on 2026-10-04 (vanilla Vulkan at
> 17.1-19.7 ms) was taken while Unreal Editor and Blender were open. Their load
> showed up as WindowServer at 64-79% GPU busy instead of the clean ~9%, and it
> inflated our frame times by about 2.5x. Everything below is from a clean
> re-run. `tools/bench/bench.sh` now prints a warning when another process
> takes more than 5% of the GPU, or WindowServer more than 30%.
> The contaminated conclusions ("about 11 ms of fixed cost", "triangle volume
> is the bottleneck", "native Metal for vanilla isn't faster than MoltenVK")
> are withdrawn.

Method:

- Same seed, same camera: 100 blocks up, over a forest and a lake.
- Native 3456x2168, render distance 16 unless noted, windowed.
- GPU time comes from 5-second Instruments Metal System Traces, split per
  process and per GPU channel by `tools/bench/gpuparse.py`.
- Raw output is in `tools/bench/results/2026-10-04-clean*.txt`.

| Setup                                     | FPS   | GPU ms/frame | Vertex | Fragment |
|-------------------------------------------|-------|--------------|--------|----------|
| 26.3 vanilla, Vulkan (MoltenVK)           | 148   | **6.66**     | 2.37   | 4.39     |
| 26.3 vanilla, Vulkan, 1/4 of the pixels   | 284   | 3.26         | 1.92   | 1.36     |
| 26.3 vanilla, Vulkan, render distance 6   | 151   | 6.44         | 2.15   | 4.39     |
| 26.3 vanilla, Vulkan, render distance 24  | 148   | 6.66         | 2.38   | 4.38     |
| 26.3 vanilla, Vulkan, fullscreen          | 155   | 6.37         | 2.23   | 4.22     |
| 1.21.11 + MetalCraft 0.1.0 (native Metal) | 115   | 8.23         | 3.84   | 7.16     |
| Rockstar Shaders 1.0, 75% render scale    | 14    | 69.8         | 9.9    | 54.7     |
| Rockstar Shaders 1.0, 100% render scale   | 8.6   | 114.5        | 11.5   | 92.9     |

Vertex and fragment work overlap, so those columns don't sum to the total.

OpenGL runs are left out on purpose. Apple's OpenGL layer splits each frame
into many Metal command buffers, so per-frame GPU time can't be attributed from
the trace (it reported nonsense like 1951 fps). Use the in-game F3 counter for
OpenGL if it's ever needed.

What this tells us:

- **Vanilla is cheap: 6.7 ms at native Retina,** so there are about 10 ms of
  headroom for effects at 60 fps.
- **Most of vanilla's cost scales with pixels:** about 1.8 ms fixed and about
  4.9 ms per-pixel at native resolution.
- **Render distance 6 to 24 changes almost nothing.** Triangle count is not the
  bottleneck at this camera. Terrain triangle reduction drops in priority.
- **Render scale is the main lever.** At 67% scale vanilla should cost about
  4 ms, which leaves about 11 ms for effects plus upscaling.
- **Fullscreen vs windowed is within noise** (about 0.3 ms).
- **Rockstar is about 10x vanilla's cost even on a clean machine.** At 75% scale
  it is already 70 ms, so its effects (ray-marched clouds, ray traced lighting
  through a cross-API wait) are what we must not copy.
- **MetalCraft's native Metal backend is not faster than MoltenVK** (8.2 ms on
  1.21.11 vs 6.7 ms for 26.3 on Vulkan; the versions differ). A native backend
  buys features, not speed.

Other people's evidence (not verified by us):

- @riccqi claims a native Metal renderer with fog, dynamic lighting and water
  reflections at 60 fps at full Retina on a roughly 5-year-old MacBook. No code
  has been published. Given vanilla's 6.7 ms here, that claim is plausible.

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
- **Resolved:** Sol was right to doubt it. The "fixed cost" came from GPU
  contention. On a clean machine, cost scales with pixels and not with render
  distance; see Measurements.

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
- Vanilla plus upscaler at 67% scale must cost less than vanilla at native
  (6.7 ms) p95. Otherwise render scale saves nothing. (This replaced a 14 ms
  bar that was set against the contaminated 17 ms baseline.)

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
| Vanilla-equivalent terrain + entities   | 4.0 ms  |
| Hand-off to Metal and back             | 0.5 ms  |
| Shadows (cached far cascades)          | 1.5 ms  |
| Lighting + sky (tile memory)           | 1.0 ms  |
| Translucents + water + reflections     | 1.5 ms  |
| Volumetric fog                         | 1.5 ms  |
| Clouds (1/4 resolution + reprojection) | 1.0 ms  |
| Ambient occlusion (half resolution)    | 0.8 ms  |
| Upscale (MetalFX or TAA)               | 1.5 ms  |
| Post-processing                        | 1.0 ms  |
| GUI at native resolution               | 0.5 ms  |
| **Total**                              | **~14.8 ms** |

The terrain line comes from measured vanilla: 6.7 ms native, about 4 ms
expected at 67% scale. The largest open risk is the hand-off line, which Gate 1
measures.

## Gated plan (revised 2026-10-04 for the staged Vulkan host + Metal approach)

Each gate has a measurable pass bar on the reference scene, and a stated
consequence if it fails.

**Gate 0: baseline. DONE.** Clean vanilla measurements; see "Measurements".

**Gate 1: Vulkan -> Metal -> Vulkan round trip + MetalFX** (bars in "Integration
decision").

**Gate 1 result (2026-10-04): PASS, with a caveat.** Three interleaved runs per
mode on a frozen world snapshot (`tools/bench/results/2026-10-04-gate1.txt`):

| Mode                                  | GPU ms/frame | FPS   | GPU busy |
|---------------------------------------|--------------|-------|----------|
| Vanilla, native 3456x2168             | 6.20         | 158.9 | 98.5%    |
| Hand-off only (noop, native)          | 6.15         | 152.1 | 93.6%    |
| MetalFX spatial, 67% (2316x1453)      | 5.96         | 149.0 | 88.8%    |

How each bar did:

- **Hand-off: pass.** No measurable GPU time, but about 0.28 ms of wall time
  per frame, because the GPU idles while one queue waits on the other.
- **MetalFX: pass** at about 1.3 ms (bilinear costs about 0.3 ms).
- **Vanilla + upscaler at 67% below native GPU time: pass, barely** (5.96 vs
  6.20 ms).
- **Stability: pass.** 14 minutes and about 119k frames with 0 errors, through
  post effects, glowing entities and four resizes or fullscreen toggles.

Caveat: vanilla is cheap, so upscaling it barely pays, and FPS is slightly
lower than native because of the idle gaps. Render scale earns its keep once
per-pixel effects are added (Gates 3-4). Two things to watch:

- The cross-queue idle gaps (busy 98% -> 89%).
- Whether MetalFX temporal (Gate 5) or a cheaper spatial filter is worth it.

**Gate 2: Metal draws Minecraft's terrain.**
- Export vanilla's section vertex/index buffers (`VulkanGpuBuffer`) as
  `MTLBuffer`s, with no copies.
- Draw opaque/cutout terrain from Metal into a shadow map first: a depth-only
  pass from the sun.
- Bar: correct shadow map, and 1.5 ms or less for a 2048^2 cascade set with
  cached far cascades.

**Gate 2 result (2026-10-04): PASS.** What was built:

- **Shadow map:** a 2048^2 sun map drawn from vanilla's own section buffers.
  SOLID and alpha-tested CUTOUT casters within 80 blocks; the map is redrawn
  every 8 frames, or sooner when the sun or camera moves enough.
- **Apply pass:** a 4-tap PCF pass at 67% scale darkens shadowed pixels of
  vanilla's image before MetalFX.

Interleaved runs (run 1 of each mode discarded as warm-up;
`tools/bench/results/2026-10-04-gate2.txt`):

| Mode                          | GPU ms/frame | FPS   |
|-------------------------------|--------------|-------|
| Vanilla, native               | 6.24         | 157   |
| MetalFX 67%                   | 5.98         | 149   |
| MetalFX 67% + shadows         | 6.49         | 138   |

Shadows cost about 0.5 ms of GPU time, against the 1.5 ms bar. Screenshot:
`docs/gate2-shadows.png`.

Limitations, which Gate 3 removes:

- Shadows are an overlay on vanilla's already-lit colour, so there is no real
  sun term, and caves under covered terrain get darkened too.
- There's no G-buffer, so normals come from depth derivatives.

Sol's Gate 2 audit:

- Found no chunk-buffer race, ABI mismatch or wrong MTLBuffer offset (it
  checked against MoltenVK 1.4.2 source).
- Confirmed and now fixed:
  - the top section row was skipped (`maxSectionY` is inclusive);
  - shadows ran outside the Overworld sky;
  - debug gizmos clear depth before our copy;
  - a failed submit poisoned the cached map;
  - a shutdown hang if Metal waits on a never-submitted signal. Shutdown now
    CPU-signals the shared event.

**Gate 3: our opaque terrain pass + tile-memory lighting.**
- Turn off vanilla's opaque/cutout terrain. Metal draws terrain into a
  memoryless G-buffer, then lights it in the same pass: sun with shadows, sky
  light, block light, sky.
- Vanilla still draws entities, particles and translucents over our colour and
  depth.
- Bar: terrain + lighting at or below vanilla terrain cost +1 ms at 67%.

**Gate 3 result (2026-10-04): PASS.** Metal draws vanilla's visible SOLID and
CUTOUT sections at a second mid-frame hand-off (`LevelRenderer.prepareTranslucents`,
after the sky and before the main render pass):

- **Shading:** forward. Sky light, shadow-mapped sun and block light, plus
  vanilla's fog.
- **Output:** writes colour and reverse-Z depth, so vanilla's entities, water
  and particles composite on top. Vanilla's opaque terrain group is skipped.
- **Matches vanilla:** back faces culled with the same clockwise front faces;
  clip-space Y negated to match Minecraft's Vulkan convention.
- **Forward instead of a tile-memory G-buffer:** Apple's hidden-surface removal
  already shades each opaque pixel about once.

Interleaved runs (`tools/bench/results/2026-10-04-gate3-culled.txt`):

| Mode                                                | GPU ms/frame | FPS   | GPU busy |
|-----------------------------------------------------|--------------|-------|----------|
| Vanilla, native                                     | 6.19         | 159.0 | 98.4%    |
| Metal terrain + lighting + shadows + MetalFX (67%)  | 5.36         | 143.5 | 77%      |

**Open problem (fixed 2026-10-05, see "Removing the hand-off idle time"): cross-queue idle gaps.** The two Vulkan<->Metal hand-offs leave
the GPU idle about 23% of the frame, so FPS is about 10% under vanilla even
though GPU work is 14% lower.

Things to try:

- Commit Metal work earlier so it is already queued when Vulkan signals.
- Merge the two hand-offs.
- Eventually, option A (no cross-queue sync at all).

Not urgent against the 60 fps target (143 fps here), but it is the largest
remaining inefficiency.

Sol's Gate 3 audit found no ordering or timeline bug. Fixed:

- stale terrain-skip flag after Tilelight turns off, and in panorama
  captures;
- unchecked encoder allocation;
- silent holes when a section can't be exported (now falls back to vanilla);
- double-sided terrain;
- shadow map validity;
- vanilla's exact CameraBlockPos/CameraOffset arithmetic for matching depth.

**Gate 4: effects.**
- Water with SSR and an environment fallback, volumetric fog, low-resolution
  clouds, AO, bloom/tonemap.
- Each effect must fit its budget line, and the whole frame must reach 60 fps
  p95 (16.6 ms) at native output.

**Gate 4 status (2026-10-04): PASS on budget, two effects in.**

- **Volumetric sun light (haze and shafts):**
  - How: a quarter-resolution compute march through the shadow map (16
    jittered steps, Henyey-Greenstein g=0.6, height falloff), composited
    before the upscale.
  - Cost: about 0.56 ms GPU, including the world-depth copy
    (`tools/bench/results/2026-10-04-gate4-volumetric.txt`, clean runs only).
- **Water reflections:**
  - How: screen-space reflections with a sky fallback and Schlick Fresnel.
    Water pixels are found by comparing the terrain-only depth (copied right
    after the Metal terrain pass) with the depth after vanilla's translucents,
    restricted to upward-facing surfaces.
  - Cost: about 0.35 ms (from Metal's own command-buffer timing; not yet in an
    interleaved sweep).
- **Whole frame: about 130 fps** at native output on the reference scene,
  against the 60 fps (16.6 ms) bar.
- **Not done:**
  - Clouds and AO: vanilla's are kept (AO is baked into vertex colours).
  - Bloom/tonemap: no HDR pipeline yet, since lighting is in vanilla's
    sRGB-encoded space.
- **Measurement hygiene:** this chat app's own window redrawing showed up as
  GPU contention (T3 Code Helper 8-25%, WindowServer 40%) during runs where
  tool output was streaming. `bench.sh` flagged those runs and they are
  excluded. Run sweeps while idle.

**Gate 5: temporal upscaling and polish.**
- MetalFX temporal (needs depth, motion vectors and jitter) vs. spatial.
- Dynamic resolution, presets.

**Gate 5 result (2026-10-04): done, with a cost finding.**

- **Settings:** in `config/tilelight.json` (`TilelightConfig`), written with
  defaults on first run; `-Dtilelight.*` system properties override them for
  benchmarks.
- **MetalFX temporal upscaling** (`upscaler: "temporal"`):
  - Halton(2,3) sub-pixel jitter is applied to vanilla's world projection
    where it is captured.
  - Camera-reprojection motion vectors come from the world depth, with zero
    motion on hand pixels; reverse-Z depth is passed to MetalFX.
  - Jitter sign verified visually: the +1 convention is sharp, -1 is blurry
    (`docs/gate5-temporal-crop.png`).
  - Moving entities have no per-object motion and may ghost.

Interleaved, clean runs (`tools/bench/results/2026-10-04-gate5-upscalers.txt`):

| Upscaler (with terrain, shadows, haze, water) | GPU ms/frame | FPS  |
|-----------------------------------------------|--------------|------|
| MetalFX spatial, 67%                          | 6.27         | 127  |
| MetalFX temporal, 67%                         | 11.2         | 78   |
| MetalFX temporal, 50%                         | 9.28         | 92   |

**MetalFX temporal costs about 5 ms** at 3456x2168 output on a base M5, more
than three times its 1.5 ms budget line. Spatial is therefore the default, and
temporal is an opt-in "quality" setting that still holds 60+ fps.

A cheaper anti-aliasing path is future work: our own TAA at internal resolution
plus spatial upscaling.

Dynamic resolution is not implemented; nothing has needed it yet at 127 fps.

**Gate 6 (hardware ray tracing): skipped for now.** Raster shadows cost about
0.5 ms and volumetrics about 0.56 ms, so ray-traced shadows have little cost
headroom to win. Revisit for reflections beyond the screen (SSR misses
off-screen objects), where ray tracing would add quality, not save time.

**Gate 6 (optional, M3+): hardware ray tracing** for shadows and reflections,
only if it beats the raster version on cost for equal quality.

Fallback: if Gate 1's hand-off bar fails, re-evaluate a full Metal backend for
RenderPearl (option A). Backends receive SPIR-V, and the game ships SPIRV-Cross.

## Benchmarking rules

- Fixed world seed, fixed camera, a 50-60 second warm-up, and Minecraft's idle
  frame-rate cap turned off (`inactivityFpsLimit:"minimized"`).
- Nothing else heavy on the GPU. Close Unreal, Blender and video players.
  `bench.sh` warns on contention; a run with a warning doesn't count.
- Record per-pass GPU timings, total GPU time (Metal HUD), frame time and the
  1% lows.
- Every change to the renderer gets a before/after number on the reference
  scene.

## Decisions

1. **Decided (2026-10-04): write the backend from scratch, under a permissive
   license** (Apache-2.0 or MIT; Apache-2.0 chosen, see 5). MetalCraft (GPL-3) is used only
   as an external benchmark. Do not read or copy its source.
2. **Minecraft version.** Target 26.3 now. Blaze3D changes between releases, so
   expect some porting work on every update.
3. **Compatibility.** From Gate 3 on, this replaces terrain, so it is
   incompatible with Sodium, Iris, Distant Horizons and Voxy, the same as
   Rockstar.
5. **License: Apache-2.0** (chosen 2026-10-04; explicit patent grant).
4. **Minimum hardware.** M1 and later (Metal 3) for the main renderer.
   Ray-tracing features only on M3 and later.

## Removing the hand-off idle time (2026-10-05)

The Gate 3 result left the GPU only about 77-80% busy, about 10% below
vanilla's FPS. What the measurements showed (`tools/bench/gaps.py`,
`tools/bench/timeline.py`, and render-thread timing from `FrameTimer`):

- **Not CPU-bound.** The render thread records a frame in about 1.2 ms
  (vanilla: 0.85 ms) and waits about 5 ms for the GPU.
- **Each Vulkan<->Metal transition left about 250-350 us of GPU idle.** That
  is four per frame, about 1.2 ms of a 7 ms frame.
- **Moving our command buffers onto MoltenVK's own `MTLCommandQueue` alone
  changed nothing.** The cost is the `MTLSharedEvent` wait/signal itself, not
  the queue switch.
- **Correction to earlier numbers:** a command buffer blocked on
  `encodeWaitForEvent` counts as GPU-busy in Metal System Trace. Tilelight's
  "GPU ms/frame" figures in Gates 1-5 included that waiting. Compare FPS
  instead.

Fix (config `sameQueue`, default on):

- Our Metal work is committed to MoltenVK's queue.
- At each hand-off, the Vulkan work recorded so far is submitted early: the
  current `VulkanQueue.Submission` is closed and a new one begun. This leaves
  Minecraft's end-of-frame submit and frame pacing untouched.
- No events are used. Commit order on the single queue plus Metal hazard
  tracking order the work; every shared resource was checked at runtime to be
  hazard-tracked (logged once per session).

Interleaved, three runs each (`tools/bench/results/2026-10-05-shared-queue.txt`):

| Mode                                    | FPS   | GPU busy |
|-----------------------------------------|-------|----------|
| Vanilla, native                         | 160   | 98.5%    |
| Tilelight, separate queue + events      | 126.5 | 80%      |
| Tilelight, shared queue, no events      | 154   | 98.7%    |

Tilelight with terrain lighting, shadows, haze, water and MetalFX now runs
within about 4% of vanilla.

## Known issues

From GPT-6.1 Sol's audit of Gate 1 (2026-10-04). Findings 1, 2, 3, 5 and 6
were verified against the sources and fixed. These two are deferred:

- **The `VK_EXT_metal_objects` exports skip the export-create structures the
  spec requires** (VUIDs 06791/06795) on Minecraft's instance and images.
  MoltenVK 1.4.2 returns the objects anyway; recheck on every MoltenVK
  upgrade. The fix is to chain `VkExportMetalObjectCreateInfoEXT` into instance
  creation and into `VulkanGpuTexture`'s `VkImageCreateInfo`.
- **Device loss or a rejected `vkQueueSubmit2` after our Metal command buffer is
  committed** leaves `worldDone` without a producer. Our queue would wait
  forever. That is harmless to Minecraft, which is already failing, but it
  isn't handled.
