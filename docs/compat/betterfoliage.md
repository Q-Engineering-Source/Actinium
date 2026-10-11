# Better Foliage 兼容性说明

兼容状态：**代码支持，dev 运行验证通过**
最后更新：2026-09-26

## 模组信息

- 原版 Better Foliage（modid `betterfoliage`），Minecraft 1.12.2；检查了 CurseForge 文件 3393955（JAR 元数据版本 2.3.2）。
- RLFoliage 使用相同 modid，但提供不同的渲染 API，不能共用原版适配 Mixin。
- 两种实现分别由其入口类存在性加载：原版 `mods.betterfoliage.client.Hooks`、RLFoliage 的 `betterfoliage.render.feature.RenderingHandler`。

## 根因与机制

- 原版 Better Foliage 的 `BetterFoliageTransformer` 会改写 vanilla `RenderChunk.rebuildChunk`：把 `BlockRendererDispatcher.renderBlock` 转为 `Hooks.renderWorldBlock`，并把方块层检查转为 `Hooks.canRenderBlockInLayer`。
- Actinium 使用自己的 `ChunkBuilderMeshingTask.execute`，不会经过上述 vanilla 调用点；已有适配器调用的是 RLFoliage 的 `RenderingHandler`，原版 Better Foliage 不包含这个类。
- 原版适配现在在快速路径中只对可处理的 Better Foliage feature renderer 调用原版 Hook；普通方块仍走 Actinium 快速 renderer。走 vanilla dispatcher 的分支直接经过原版 Hook。RLFoliage 保留独立适配和独立条件。
- HBM 2.5.0.5 的必需 `MixinRenderChunk` 也包裹 vanilla `BlockRendererDispatcher.renderBlock`。Better Foliage 的 coremod 先把该调用换成 `Hooks.renderWorldBlock`，使 HBM 的注入命中数变为 0。Actinium 在这两个 mod 同时存在时，于 Better Foliage 转换之后恢复该 vanilla 调用点；Actinium 自己的 meshing task 仍走上面的 Better Foliage Hook。

## 验证记录

- 原版 Better Foliage 1.12.2 JAR 已反编译核对 Hook 签名和 transformer 目标；RLFoliage JAR 已核对其使用独立 API 类。
- dev 启动的首个崩溃是缺少 `net.shadowfacts.forgelin.KotlinAdapter`；后续运行日志已显示 Forgelin 和 Better Foliage 均加载，并推进到 HBM 的 `RenderChunk` Mixin 冲突。
- HBM 与 Better Foliage 的 RenderChunk 注入冲突已按两者的实际字节码路径适配。
- `./gradlew check --no-daemon`：BUILD SUCCESSFUL。
- dev 运行验证（用户确认）：HBM 正常，树叶正常。
