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
