# Immersive Engineering 兼容说明

## 验证范围

- Minecraft 1.12.2
- Immersive Engineering 0.12-98
- Solas Shader V3.7b
- Garden Cloche 已放入泥土和种子
- Actinium 分支 `fix/ie-cloche-transparency`，基于 `c318ed54`

用户确认 Solas Shader V3.7b 下，Garden Cloche 玻璃从不透明恢复为半透明。复现条件与原始报告见 [issue #197](https://github.com/Q-Engineering-Source/Actinium/issues/197)。

## 根因

IE 的 `TileRenderBelljar.render` 单独绘制 OBJ `glass` 组，并设置标准 `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` blending。Iris 的 `TranslucentBlendMatcher` 会据此把方块实体切到 `BLOCK_ENTITIES_TRANSLUCENT` 程序。

之前 `ProgramId.BlockTrans` 带有默认 `BlendModeOverride.OFF`。当 shader pack 没有对应 blend directive 时，该默认值关闭实际 GL blending 并锁住 blend 状态；之后 IE 请求的 blending 只被 GLSM 延迟，玻璃纹理 alpha 没有参与混合。现在 `BlockTrans` 不再默认强制关闭 blending，因此 shader pack 没有显式 directive 时会保留方块实体的 vanilla blend 请求。shader pack 提供的显式 directive 仍优先。

## 已知 shader pack 缺口

- **ITT 3.2.0**：没有 `gbuffers_block_translucent` 程序，回退到 `gbuffers_block`；其 `Entities_FS.glsl` 对普通 block 输出 `vec4(albedo.rgb, 1.0)`，玻璃 alpha 因此丢失。用户报告 cloche 玻璃泛白；单改 blend 默认值无法恢复 shader 已丢弃的 alpha。
- **Photon v1.3b**：虽然包含 `gbuffers_block_translucent` 源码，但 `shaders.properties` 在 `MC_VERSION < 260100` 时禁用该程序。1.12.2 因而回退到 `gbuffers_block`；用户报告 cloche 内部发黑。该 pack 的 fallback shader 与 blend 设置由 pack 自身控制，当前 Actinium 修复不会覆盖它们。

目前没有对这两个 pack 加入 shader-specific workaround。通用改写 fallback shader 的 alpha 可能破坏其他 block 材质和多 render target 输出；若要支持，需要为每个 pack 明确限定 shader source 与 alpha 语义。

## 验证记录

- 自动化验证：`.\gradlew.bat :test --tests net.coderbot.iris.shaderpack.loading.ProgramIdFallbackTest --no-daemon --rerun-tasks` 通过（先红后绿）；`.\gradlew.bat check --no-daemon` 与 `.\gradlew.bat build --no-daemon` 通过。
- 实机验证：用户以 IE 0.12-98 + Solas Shader V3.7b 运行 Garden Cloche（泥土 + 种子），确认玻璃半透明恢复。
- ITT 3.2.0：用户反馈玻璃泛白；Photon v1.3b：用户反馈 cloche 内部发黑，未由本次通用 blend 默认值改动解决。
