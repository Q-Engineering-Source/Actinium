# 渲染选项与实现落点

记录 Actinium 自有 GUI 选项的落点，方便定位与做取舍。选项模型与页面框架来自
`celeritas-common`，页面以 `OptionIdentifier` 绑定到 `SodiumGameOptions` 的持久化字段。

## 选项的组织方式

- 持久化字段：`celeritas-common` 的 `dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions`，
  按 `Performance / Advanced / Quality / Notification / Debug / Window` 分段。
- 选项 ID：`dhj.embeddedt.embeddium.api.options.structure.StandardOptions`。
  Actinium 自有选项使用 `actinium` 命名空间（`ACTINIUM_MOD_NAME`）。
- 页面与分组：`src/main/java/com/dhj/actinium/gui/ActiniumGameOptionPages.java`
  （`general` / `quality` / `advanced` / `debug`）与
  `celeritas-common` 的 `...gui.options.CommonOptionPages`（`performance`）。
- 标签：`src/main/resources/assets/celeritas/lang/{en_us,zh_cn}.lang`。
- 构建期守卫：`MixinConfigurationTest` 要求每个已编译 Mixin 恰好在配置文件中声明一次，
  新增 Mixin 类必须同步 `src/main/resources/mixins.actinium.vintage.json`。

## DEBUG 页开关（`enable_debug_tab`）

视频设置 GUI 的 DEBUG 页默认不显示。`config/actinium-options.json` 顶层字段
`enable_debug_tab`（默认 `false`，持久化在 `SodiumGameOptions.enableDebugTab`）为 `true`
时才暴露该页。开关只存在于配置文件，GUI 内没有入口——页面隐藏后无法从自身重新打开；
配置在启动装载时读取，改动需重启游戏生效。已有配置文件不会自动补写该字段，需要手工加在顶层：

```json
{
  "enable_debug_tab": true,
  "quality": { }
}
```

`ActiniumOptionPages.retainEnabledPages(List, SodiumGameOptions)` 在关闭时按
`StandardOptions.Pages.DEBUG` 把该页从候选页面里剔除，因此该页既不出现在标签栏，也不进入
搜索索引，也不参与 `ActiniumOptionHost` 的 apply/undo 扫描。判定步骤与页面构建分离，页面构建
依赖客户端 locale 与 GL 上下文，单测只覆盖判定。

DEBUG 页原先与高级/性能页重复的 5 项（模型渲染器批处理、模型渲染器显示列表、快速光照物品
渲染、快速光照物品显示列表、渲染通道优化）已移除；这些开关继续在高级页（前四项）与性能页
（渲染通道优化）调整，对应的 `sodium.options.actinium.shader_debug.*` tooltip 键已删除。

隐藏该页只影响 GUI 呈现，不改变任何开关的生效路径：`ActiniumStartupDebugConfig` 与
`RedirectorDebugOptions` 在启动期直接解析 JSON 的 `debug` 段（LWJGL/重定向器开关），
`Actinium.onConstruct` 把 `enable_actinium_perf_debug` 推给 `GLSMPerfDebugHooks`，其余经
`IrisDebugOptions.Bridge`、`GlStateDiffProbe`、`ActiniumDiagnostics` 热读配置字段。因此
`enable_debug_tab=false` 时仍可用配置文件开启任一 DEBUG 开关（需要重启的项除外）。

## 性能页 → CHUNK_UPDATES 分组

| 选项 | 字段 | 实现落点 |
| --- | --- | --- |
| 释放过大的区块构建缓冲（`actinium:trim_chunk_build_scratch`，默认开） | `performance.trimChunkBuildScratch` | `ScratchRetentionWindow`（开关 + 连续两个 64 任务窗口低负载才释放的判定）、`ChunkMeshBufferBuilder.finishTask` 与 `TranslucentQuadRecorder.finishTask`（释放点，分别回收原生顶点缓冲与半透明排序暂存数组）、`ActiniumRuntime.loadConfig`（配置装载出口统一同步开关） |

释放后容量不丢：后续构建任务需要更大缓冲时按原有扩容路径重新分配。关闭选项只停用
释放判定，不影响已保留缓冲的复用。

## 质量页 → DETAILS 分组

| 选项 | 字段 | 实现落点 |
| --- | --- | --- |
| 天气质量 | `quality.weatherQuality` | `mixin.vintage.features.options.MixinEntityRenderer`（`renderRainSnow` 重定向 `fancyGraphics`） |
| 树叶质量 | `quality.leavesQuality` | 资源重载路径 |
| 生物群系颜色噪声 | `quality.useBiomeColorNoise` 等 | 生物群系染色计算 |
| 暗角 | `quality.enableVignette` | `mixin.vintage.features.options.MixinGuiIngameForge` |
| 动态视野 | `quality.dynamicFov` | `mixin.vintage.features.options.MixinEntityRendererDynamicFov` |

## 动态视野（`quality.dynamicFov`）

默认开启；关闭后视野锁定在 `fovSetting`。

原版 1.12.2 的动态视野由三段构成，选项只作用于中间那一段：

1. `AbstractClientPlayer.getFovModifier()` 计算因子 —— 飞行 ×1.1、移动速度属性折算
   （含疾跑与速度效果）、拉弓最多 ×0.85。
2. `EntityRenderer.updateFovModifierHand()` 做缓动（系数 0.5）并钳制到 0.1~1.5。
   **这是该因子唯一的写入点，且只被 `updateRenderer()` 调用。**
3. `EntityRenderer.getFOVModifier(float, boolean)` 仅当 `useFOVSetting == true` 时把因子
   乘到 `fovSetting` 上。

`MixinEntityRendererDynamicFov` 在 `updateFovModifierHand()` 头部取消该方法并把
`fovModifierHand` / `fovModifierHandPrev` 都置为 1.0，于是第 3 步的插值恒为 1.0。
选择这个注入点而非在读取处替换表达式，是因为它无需访问局部变量、也不做"先乘后除"的
浮点往返（后者可能产生 1 ulp 偏差）。

同一方法内另外两项缩放**不受该选项影响**，与 OptiFine / Sodium 的 `dynamicFov` 语义一致：

- 死亡镜头（`getHealth() <= 0` 时的除法）；
- 水下（`Material.WATER` 的 ×60/70）。

`useFOVSetting == false` 的调用（手部渲染、Iris 的 `HandRenderer`）本来就不消费该因子。
该选项在光影包激活时同样生效——它只影响传入投影矩阵的 FOV 值。

**维护提示**：`fovModifierHand` 的写入点一旦被上游或其它补丁挪出
`updateFovModifierHand()`，该 Mixin 会静默失去作用（不会报错），需同步检查。
