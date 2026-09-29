# LittleTiles 光影光照兼容（1.5.87）

最后更新：2026-09-29。关联 [issue #193](https://github.com/Q-Engineering-Source/Actinium/issues/193)。

## 症状

LittleTiles 放置的微型方块在启用光影后比相邻的原版整块方块暗；关闭光影时正常。原 issue 使用
Solas Shader V3.7b，并报告其他光影包也有相同现象。本次用户实测环境使用 Photon v1.3b，场景中放有
相邻的原版橡木木板、ArchitectureCraft shape 和 LittleTiles 方块。

## 根因

LittleTiles 通过 CreativeCore 的 `RenderingThread` / `CreativeModelPipeline` 绘制组成方块，并把顶点数据
缓存为原版 `DefaultVertexFormats.BLOCK` 的原始字节。此路径绕过 Actinium 的常规 `VintageBlockRenderer`：

- Forge `VertexLighterFlat` 将 AO 与方向面明暗烘焙进 RGB；
- Iris 在 `separateAo` 模式下通过顶点 alpha 提供 vanilla AO，RGB 应保留未受 CPU 光照衰减的颜色；
- LittleTiles 会合并、复制和延迟追加自己的顶点缓存，若只搬运字节，Actinium 就丢失每个 quad 对应的
  组成方块状态，无法为后续 shader 编码保留正确的方块 ID、光照值与 render type。

Photon v1.3b 的 `oldLighting=false` 会关闭旧方向面着色。LittleTiles 的 CPU 光照因子因此要拆成两部分：
RGB 需要除去原始完整因子；alpha 只保留方向面因子剥离后的 AO。此前使用 AO-only 因子还原 RGB 会让方向明暗
继续留在 RGB，画面没有变化。

## 修复

- 在 LittleTiles smooth / flat face 渲染期间记录组成方块状态、section-local 坐标和 block / fluid render type；
- 通过 LittleTiles 的 buffer wrapper、cache、cache combine / copy 和区块追加路径同步保存 sidecar quad context；
- 在 `VintageChunkBuildContext` 组装 shader 顶点时解析延迟状态，恢复组成方块的 shader block ID 与光照值；
- `separateAo` 开启时从 RGB 中还原 CreativeCore 烘焙的完整 CPU 光照因子，并把 AO 写入 alpha；当 shader pack
  禁用旧方向面着色时，只从 alpha AO 中移除方向明暗，保留邻接产生的真实 AO；
- 所有 LittleTiles mixin 由 `littletiles` 条件配置加载。

## 验证

- `compileJava`、`check`、`devShadowJar` 通过。
- 2026-09-29：用户在 dev 环境启用 Photon v1.3b，对照相邻的原版橡木木板、ArchitectureCraft shape 与
  LittleTiles 方块，确认光照问题已修复。
- Solas V3.7b 及其他 shader pack 的交叉回归尚未完成。

## 主要文件

- `src/main/java/com/dhj/actinium/compat/littletiles/LittleTilesCompat.java`
- `src/main/java/com/dhj/actinium/compat/littletiles/LittleTilesQuadContextCarrier.java`
- `src/main/java/com/dhj/actinium/mixin/mod/littletiles/`
- `src/main/java/com/dhj/actinium/render/terrain/compile/VintageChunkBuildContext.java`
- `celeritas-common/src/main/java/dhj/embeddedt/embeddium/api/shader/buffer/`
- `src/main/resources/mixins.actinium.littletiles.json`
