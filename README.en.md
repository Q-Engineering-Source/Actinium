# Actinium

[简体中文](README.md)

Actinium is an experimental rendering and shader compatibility mod for Minecraft 1.12.2 on Cleanroom Loader. It aims to bring a more modern rendering pipeline to the legacy client while keeping shader packs, classic modded content, and performance-oriented rendering work in the same world.

The project currently combines work around Celeritas, GLSM, GTNHLib, and an Iris-style shader pipeline. Its focus is practical compatibility: terrain rendering, entity rendering, shadow passes, post-processing stages, shader uniforms, framebuffer ownership, and the OpenGL state transitions that old and new renderers both depend on.
![actinium.png](docs/image/actinium.png)
## What Actinium Does

- Reworks parts of the Minecraft 1.12.2 client rendering path around a modernized pipeline.
- Integrates Celeritas terrain rendering concepts into the Cleanroom environment.
- Takes over and stabilizes GLSM-related OpenGL state handling.
- Implements an Iris-inspired shader pass model, including `shadow`, `gbuffers`, `prepare`, `deferred`, `composite`, and `final` stages.
- Improves compatibility with shader packs that expect modern framebuffer, uniform, and program-binding behavior.
- Keeps legacy modded rendering paths in view, including entities, block entities, particles, weather, sky rendering, water reflections, and shadow rendering.

## Current Status

Actinium is under active development. It is not a drop-in replacement for every Minecraft 1.12 rendering stack yet, and shader pack behavior can still vary by pack, preset, driver, and mod list.

The current development direction is compatibility-first:

- preserve expected vanilla and modded rendering behavior;
- make shader pack pipeline stages predictable;
- reduce hidden OpenGL state leaks between legacy rendering and shader rendering;
- keep regressions visible through focused tests and manual shader-pack checks.

Shader packs such as MakeUp, BSL, and Complementary are useful compatibility targets during development, but support should be treated as ongoing work rather than a finished compatibility matrix.

## Requirements

- Minecraft 1.12.2
- Cleanroom Loader
- Java 25 toolchain; produced bytecode targets Java 21
- A graphics environment capable of running the shader packs used for testing

## Building

From the project root:

```powershell
.\gradlew.bat build --no-daemon
```

For a faster compile-only check:

```powershell
.\gradlew.bat compileJava --no-daemon
```

To run the automated tests and pre-release structure checks:

```powershell
.\gradlew.bat check --no-daemon
```

Install `build/libs/Actinium-<version>.jar` in a compatible Cleanroom instance. The `-sources.jar` file is for development.

The jar version is derived from the git state at build time: an exact tag on HEAD wins; otherwise the base version from `gradle.properties` is used with the current commit's git sha appended (e.g. `2.4.0-dev-2dc019e`). It can be overridden explicitly with `-Pversion=...`.

## Mod Metadata

Actinium's Mod List metadata is configured from Gradle properties in `gradle.properties`. Main mod fields use the `mod_*` prefix (`mod_description`, `mod_url`, `mod_authors`, `mod_credits`, `mod_logo_path`). Values can also be overridden per build with `-P` arguments, for example `-Pmod_description=...`.

## Repository Layout

- `src/` contains Actinium integration, compatibility hooks, mixins, and runtime resources.
- `shader/` contains the integrated Iris-style shader pipeline.
- `glsm/` contains the embedded GLSM-side integration.
- `GTNHLib/` contains the embedded GTNHLib pieces used by the project.
- `celeritas-common/` contains the embedded Celeritas renderer implementation.
- `docs/` contains development notes, compatibility gaps, and future documentation.
- `gradle/scripts/` contains shared Gradle build and dependency logic.

See [the architecture guide](docs/architecture.md), [current roadmap](docs/roadmap.md), and [compatibility matrix](docs/compatibility-matrix.md) before changing the render pipeline. Third-party source provenance and licenses are summarized in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Contributing

Contributions of any kind are welcome: reporting issues, verifying shader pack compatibility, or submitting code and documentation. Please read the [contributing guide](CONTRIBUTING.md) first, and use the appropriate [issue template](.github/ISSUE_TEMPLATE/) when reporting problems.

## AI-Generated Code

Nearly all code in this project is produced with AI coding agents, and changes may also be reviewed by AI: every change is reviewed, tested, and regression-verified by humans and AI before it is merged. If you do not want to use AI-generated code, feel free to look for alternative projects; if you have any concerns, reach out via issues.

## Related Projects

Actinium builds on ideas, code, and compatibility research from several projects. The items below are listed to make those roots visible; each upstream project remains governed by its own license and authorship.

- Celeritas: provides much of the original Minecraft 1.12 performance-mod foundation and terrain-rendering direction that Actinium continues to adapt.
- GLSM: provides shader-pack loading, shader state management, and compatibility behavior that Actinium integrates and stabilizes.
- GTNHLib: provides utility code and compatibility infrastructure used by the embedded legacy rendering stack.
- Iris: serves as a major reference for shader pipeline structure, pass ordering, framebuffer behavior, and shader-pack expectations.
- GLSL Transformation Library: used for GLSL parsing and transformation work needed by shader compatibility code.
- Sodium: informs the broader performance-oriented rendering model and modern Minecraft renderer design.
- Angelica and the GTNH rendering ecosystem: useful references for Minecraft 1.7/1.12-era shader compatibility, legacy OpenGL behavior, and modded-client integration.
- Cleanroom Loader: provides the target Minecraft 1.12.2 runtime environment.

## License

Actinium is distributed under the license in `LICENSE`.

Code originating from other projects, along with compatibility changes made to that code, remains under the original license of the respective upstream project unless explicitly stated otherwise in the relevant source files.
