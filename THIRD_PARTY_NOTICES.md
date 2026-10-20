# Third-Party Notices

Actinium contains source code adapted from other projects. The root `LICENSE` applies to
Actinium's original work; it does not replace notices or license terms attached to imported
files. When a source file carries its own copyright or license header, that header takes
precedence for that file.

This inventory describes the code currently embedded in the repository. It is not a substitute
for the full license text or legal advice.

## Embedded source trees

| Component | Repository | Local paths | Upstream license | Actinium import record |
| --- | --- | --- | --- | --- |
| Angelica / GLSM | https://github.com/GTNewHorizons/Angelica | `glsm/`, portions of `src/main/java/com/gtnewhorizons/angelica/`, Angelica resources | LGPL-3.0, with additional file-level notices | GLSM introduced in `119f607`; expanded with GTNHLib in `83ea1b7` |
| GTNHLib | https://github.com/GTNewHorizons/GTNHLib | `GTNHLib/` | LGPL-3.0, plus file-level notices | `83ea1b7` |
| Iris | https://github.com/IrisShaders/Iris | `shader/src/main/java/net/coderbot/iris/`, Iris API/resources, Iris-oriented mixins | LGPL-3.0 | full pipeline import in `79306ba`; resource mappings below |
| Celeritas renderer | historical Celeritas/Embeddium/Sodium-derived code | `celeritas-common/` | mixed upstream provenance; preserve file-level notices and consult source history | present in standalone extraction `6167e49`, split to its current tree in `2835afe` |
| grondag bitraster | https://git.taumc.org/embeddedt/celeritas (`grondag/bitraster`, Canvas-derived) | `celeritas-common/src/main/java/grondag/bitraster/` | Apache-2.0 (`third-party/licenses/bitraster-APACHE-2.0.txt`); file-level headers preserved | imported with the rasterized occlusion culling port (upstream celeritas `7190f87d8`/`6169005d8`/`261f9f685`) |
| LWJGL utility code | https://github.com/LWJGL/lwjgl3 | selected `GTNHLib/.../bytebuf/` and GL debug helpers | BSD-style LWJGL license, identified in file headers | imported through GTNHLib/GLSM; service layer work in `4826cf8` |
| GLSL Transformation Library | https://github.com/GTNewHorizons/glsl-transformation-lib | binary contained dependency | consult the dependency's published license and POM | Maven coordinate `org.taumc:glsl-transformation-lib` |

The exact upstream commit SHA used for the initial Angelica, GTNHLib, Iris, and Celeritas
imports was not recorded in this repository. The Actinium commits above are the reproducible
local import boundaries. Recovering and recording exact upstream SHAs is required before the
next bulk upstream refresh.

## Additional incorporated work

- The shader-loading backport selectively reuses the already adapted OpenGL-path work from
  Actinium's `research/sdl-gpu-port` commits `b1e91a17`, `de9b99e2`, `b245191a`, and
  the common cache/layer helpers in `d45f3b13`. The original upstream sources are Angelica
  `38b1d9a0c37b4e94fe3f63d4879ba1941fa8f25a` (#2190),
  `37afddab5d6546313a10bc862c3e72d7ea46d034` (#2194), and
  `38af64f10cc2d9410a38e60984df0440488e82ae` (#2213), under the existing LGPL-3.0 notices.
  Local paths are `glsm/.../shader/{ShaderCacheIO,ShaderDiskCache}.java`,
  `glsm/.../threading/AngelicaWorkers.java`, root `.../utils/AngelicaJar.java`,
  `shader/.../shaderpack/{include,preprocessor}/`, `ShaderPack.java`, and
  `shader/.../texture/pbr/`. This batch includes PBR resource indexing and include/preprocessor
  disk caching; it does not represent a full-tree sync or the Iris AST/program-binary cache ports.
- The `iris-transform` AST disk layer is a local adaptation informed by Angelica
  `37afddab5d6546313a10bc862c3e72d7ea46d034` (#2194), not a direct port. Upstream serializes the
  stage maps inside `ShaderTransformer` through `diskOrCompute` and `Parameters.appendDiskKey`;
  Actinium instead keys the result map already published by `TransformPatcher`, contributes the
  local `AdaptiveShadowBoundsStats` instrumentation inputs and the OpenGL capability inputs that
  drive version hoisting, and leaves out the SDL RW-image/`StageArtifact` pipeline that upstream's
  key also covers. Files: `shader/.../pipeline/transform/{TransformPatcher,TransformDiskCache}.java`
  and `RenderSystem` test capability hooks.
- The GL program binary cache follows Angelica `0a4d48964edc7a20c0a93c456123860a4a9e16de` (#2206)
  as adapted in Actinium's `research/sdl-gpu-port` commit `d45f3b13`, together with the terrain
  link-hint fix in `f9e6531e` and selected cache metrics/tests from `b665f73b`, under the
  existing LGPL-3.0 notices. Local files: `glsm/.../shader/{ProgramBinaryCache,ProgramBinaryOps,
  ProgramBinaryOpsImpl}.java`, the program-binary surface in `backend/RenderBackend`,
  `src/lwjgl3/.../Lwjgl3GLRenderBackend`, `RenderSystem` and `GLStateManager`,
  `shader/.../gl/shader/ProgramCreator`, `shader/.../gl/program/ProgramBuilder`, and
  `shader/.../celeritas/IrisCeleritasChunkProgramOverrides`, plus the `LinkHook` installed by the
  root project on `celeritas-common/.../gl/shader/GlProgram`. Local deviations: the GL half sits
  behind a `ProgramBinaryOps` port instead of calling `RenderSystem` directly (upstream tests
  swap the backend singleton reflectively, which this repository forbids), keys use the local
  `ShaderDiskCache.Key` encoding, and full-directory precompilation is not part of this batch.
- The unified program-transform submission follows Angelica work adapted in Actinium's
  `research/sdl-gpu-port` commits `5ec46f48` (single `ProgramTransforms.submit`), `6d338faf`
  (CPU transform warmup), and `f1f0abc9` (lightmap-only attribute availability variant), under
  the existing Angelica/Iris LGPL-3.0 notices. Local files:
  `shader/.../gbuffer_overrides/matching/{InputAvailability,TransformInputs}.java`,
  `shader/.../pipeline/DeferredWorldRenderingPipeline.java`, and `shader/.../Iris.java`. This
  batch takes the CPU-side transform warmup and the availability fix; the SDL uniform-block
  harvesting/warming pieces are deliberately left out because the OpenGL path never consumes them.
- The retained program pool, the same-source program sharing and the main-menu read-ahead follow the
  same downstream adaptation: `d45f3b13` (retain/shared parts), `e955a06d`, `899f6a98` and
  `005b8fd9` (read-ahead trigger, completion reporting and re-arm) via
  `research/sdl-gpu-port`, under the existing LGPL-3.0 notices. Local files:
  `shader/.../gl/program/{RetainedPrograms,ProgramDeleter,ProgramFallback}.java`,
  `shader/.../pipeline/DeferredWorldRenderingPipeline.java`,
  `shader/.../{Iris,shaderpack/ShaderPack}.java`, and root
  `com/gtnewhorizons/angelica/iris/ShaderWarmup.java` with its main-menu mixin. Local deviations:
  the pool is bounded by an explicit budget and keyed on the pack fingerprint, same-source sharing
  keeps this project's four-dimensional key instead of the upstream three, the read-ahead thread
  parses only and leaves feature validation to the main thread, and the reflective class preloading
  plus the SDL uniform-block harvest are not carried over. Display-context invalidation
  (`DisplayContextIdentity`, `ProgramCacheContextReset`) is local work with no upstream counterpart.
- The 2026-10-20 selective upstream adaptations retain the existing source licenses;
  they do not advance the full-tree synchronization baselines:
  - Celeritas `48eb54f5c616834567cda4bc23334b301a94daa0` (bitraster near clipping,
    `celeritas-common/src/main/java/grondag/bitraster/`, Apache-2.0) and
    `98c1af3357572ec0b1dc0050176723accf2d6296` (terrain shader conditional varying,
    `src/main/resources/assets/actinium/shaders/blocks/block_layer_opaque.vsh`).
  - Angelica `b76284048aa754b9f95fae25d3ae8d0584c17ca6`,
    `cbd16b1a761fe61d84fd1b95014cff40c0c25a42`,
    `69019b9a6204ea58e27162d309b84a84dad3a271`,
    `fe67a22d5aa42035f834c07974960e0ae155de8f`, and
    `85892c0f7fedc58783b04d847cab21f781db7898` (dimension selection, shadow inputs,
    metadata-aware NBT material rules, custom-quad alpha, dynamic uniform notifications,
    and DSA texture binding restoration; `shader/`, root terrain/bridge code, and
    `glsm/.../dsa/`, under the existing Angelica/Iris LGPL-3.0 notices).
  - Angelica `44c930f4abbd17b96dc172edbcbd4cfc630cd219` and
    `e98c927a93530ca94547ace22f7045e08eedb8e6` (scissor rectangle tracking and image
    binding defaults/deletion, `glsm/`, LGPL-3.0); image bind/cache wiring also restores
    behavior present in the previously referenced Angelica `02f0b0fc` baseline.
  - Angelica `6dfb0312b13c8ff2b7a2d6cd84fbbf3a22da24b9` and selected query fixes from
    `3978be161d5743942674db61c52a0997e6dde3f8` (legacy state queries, buffer-position
    semantics, and mask-aware client pixel-store/PBO restoration, `glsm/`, LGPL-3.0).
    This adaptation preserves Actinium's existing vertex-array model rather than importing
    the upstream client-array rewrite.
  - Angelica `01ff612d1dc639964669ccb61a98e53400076714` (alpha-aware animated sprite
    interpolation, adapted into `src/main/java/com/dhj/actinium/texture/SpriteInterpolation.java`
    and the existing sprite Mixin). The upstream `InterpolatedIcon` credits ganymedes01
    for the original Et Futurum implementation and Roadhog360 for its NotFine port.
    Those credits are preserved in the adapted helper.
- Parts of the renderer retain package names and implementation ideas from Embeddium and Sodium.
- Several GTNHLib byte-buffer files carry explicit LWJGL copyright and license notices.
- `MergeSort.java` carries Apache-2.0 and CERN notices in its source header.
- The occluder-box decomposition sources under
  `celeritas-common/.../render/chunk/occlusion/geometry/` are Canvas-derived; `Area`,
  `AreaFinder`, and `BoxFinder` carry GNU LGPL-3.0 notices in their file headers
  (`OccluderBoxes` has no header upstream).
- ASM-derived utility code retains its upstream copyright notice.
- Iris GUI resources are retained under the Iris namespace: upstream
  `common/src/main/resources/assets/iris/textures/gui/widgets.png` maps to
  `src/main/resources/assets/iris/textures/gui/widgets.png`, and upstream
  `common/src/main/resources/assets/iris/textures/gui/config-icon.png` maps to
  `src/main/resources/assets/iris/textures/gui/config-icon.png` (Iris commit
  `844542b8440f1ce6479d2914c212d099ff9d264c`, LGPL-3.0).
- Shader compatibility behavior is informed by OptiFine/ShadersMod conventions; shader packs
  themselves are not distributed by this repository.
- The public Sodium maintainer comment at
  https://github.com/CaffeineMC/sodium/issues/2400#issuecomment-2038560656 states that ports to very
  old Minecraft versions do not compete with Sodium. Actinium records this as public context for
  its Minecraft 1.12.2 port, not as legal advice or a replacement for license compliance review.
- The `net.caffeinemc.mods.sodium.client.gui` screens, the
  `net.caffeinemc.mods.sodium.{api,client}.config` option model, and every resource under
  `assets/sodium` previously licensed under the PolyForm Shield License 1.0.0 have been
  **removed entirely** and are no longer distributed by this repository. The settings UI is now
  the MIT-licensed Reese's Sodium Options port backed by the embeddium option model
  (`docs/rso-port.md`); the translation keys it uses live under the celeritas namespace.

## Binary dependencies

The build also resolves libraries and optional Minecraft mods from Maven, Modrinth, and
CurseMaven. Their artifacts are governed by their own licenses. Dependencies placed in the
`contain` configuration are packaged as nested jars and must keep their original metadata.
See `gradle/scripts/dependencies.gradle` and `build.gradle` for the authoritative coordinates.

- **Mesa3D EGL runtime (JMH only)**: `celeritas-common/src/jmh/natives/mesa-windows-x64.zip` contains
  `libEGL.dll`, `libgallium_wgl.dll`, and `opengl32.dll`, copied unmodified from the Mesa3D
  26.1.8 MSVC x64 release package and repacked without changes. The checked-in ZIP SHA256 is
  `77ca424f81ecfe3ba3e9871692f4e0d87347ead46a992d28e9492b98ad89c0bfd`; the upstream package is published by
  [mesa-dist-win](https://github.com/pal1000/mesa-dist-win/releases/expanded_assets/26.1.8)
  (archive SHA256 `4c6d32e653e0ff9ad07796e40c0bcfabf2764d849e3ce4f3b1590112c87e42f9`).
  The bundled Mesa source corresponds to tag `mesa-26.1.8`, commit
  `0fadfea4f394211946f308458f614839ef253ee8`. Mesa has component-level licenses; the upstream
  license text set is copied to `third-party/licenses/mesa-26.1.8/`. See the
  [Mesa license and copyright information](https://docs.mesa3d.org/license.html) and the
  corresponding source tag for file-level SPDX notices. These DLLs are used only by the JMH
  task and are not included in the distributed mod jar. The `mesa-dist-win` packaging source
  MIT notice from commit `53ce894` is retained at
  `third-party/licenses/mesa-dist-win-MIT.txt`.

## Maintenance rule

For any future imported code:

1. Record the upstream repository, exact commit/tag, local paths, license, and Actinium import
   commit in this file.
2. Preserve upstream headers and include any required license or notice text in distributions.
3. Keep upstream synchronization separate from Actinium-specific behavior changes when practical.
4. Do not infer a license from package names or from Actinium's root license.

See `docs/upstream-maintenance.md` for the synchronization procedure and known provenance gaps.
