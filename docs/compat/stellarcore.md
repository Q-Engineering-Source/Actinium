# StellarCore 兼容性说明

兼容状态：**部分**（HudCaching 与 Actinium 渲染管线已深度兼容，见下文）
最后更新：2026-08-14

## 验证范围

- 版本：1.6.0（`stellarcore-1064321-8406201`）；历史记录含 1.5.22
- 整合包：NovaEngineering-World-cleanroom
- 相关功能：HUD 缓存（HudCaching）
- 初始排查日期：2026-07-21；归因修正：2026-08-12；HudCaching 冲突确认与修复：2026-08-14

## 历史记录：GUI 闪烁或 Esc 菜单消失

### 现象与当时的验证结果

2026-07-21 在生产整合包中观察：同时启用以下两个 StellarCore 配置时，游戏内 GUI
会闪烁，按 Esc 打开的菜单可能消失：

```text
general.performance.vanilla.HudCaching=true
general.performance.ingameinfoxml.HUDFramebuffer=true
```

A/B 验证将两个选项同时设为 `false` 后症状恢复，当时归因于 StellarCore。日志确认
`mixins.stellar_core_hudcaching.json` 正常加载；HUDCaching 会接管 `EntityRenderer`、
`GuiIngame` 和 `Framebuffer` 并拦截 blend/depth/color 状态，存在冲突风险假设。

### 归因修正（2026-08-12）

**实测表明 GUI 闪烁 / Esc 菜单消失 / 背包与 JEI 闪烁 / 左上角 HUD 异常均不是
StellarCore 引起**，而是 Draconic Evolution 的渲染污染（DE 每帧 HUD 经 CCL
`GlStateTracker` 基于冻结的原版 `GlStateManager` 字段重置 GL 状态，见
[draconic-evolution.md](draconic-evolution.md)）。DE 兼容桥修复后，HUD 症状消失。

### 当时的排除验证（保留备查）

2026-07-21 保持两个 HUD 选项关闭时：临时禁用 Alfheim、Lumenized、StellarCore
整包，云层消失/草方块侧面颜色异常均无变化——该排除结论仍有效（这些症状最终
归因于 DE，见 draconic-evolution.md）。

## HudCaching 与 JourneyMap 小地图冲突（2026-08-14）

### 现象

`HudCaching=true` 时（默认），JourneyMap 小地图上的**半透明元素**渲染异常：

- 网格线（区块分割线）、玩家准心、东南西北方向标显示为**全透明**，直接透出
  小地图 GUI 看到游戏场景；
- 无 Actinium 的对照实例（JourneyMap + StellarCore 1.6.0 + HudCaching=true）
  显示正常，确认是 Actinium 侧的交互缺陷。

### 机制

StellarCore 的 `GlStateManagerMixin_HUDCaching`（`mixins.stellar_core_hudcaching.json`）
在 HUD 缓存渲染期间（`HUDCaching.renderingCacheOverride=true`）拦截原版
`GlStateManager.blendFunc/tryBlendFuncSeparate`，强制 alpha 混合因子
`(ONE, ONE_MINUS_SRC_ALPHA)` 并经 `OpenGlHelper.glBlendFuncSeparate` 直接调用。

Actinium 的 `GLSMRedirector`（AngelicaRedirectorTransformer）会把 HUD 元素
（journeymap 等）对原版 `GlStateManager` 的调用重定向到 angelica `GLStateManager`
（`glBlendFunc` 等只更新缓存状态），**StellarCore 的 mixin 钩子不再被触发**，
HUD 缓存帧缓冲（非主 framebuffer）上的混合状态与 actinium 缓存不一致，导致
HUD 缓存帧缓冲的 alpha 通道为 0，blit 到屏幕后元素区域透明。

### 修复：GLSM 状态镜像（2026-08-14）

Actinium 侧实现等价覆盖，镜像 StellarCore 的拦截窗口：

- `GLSMConfig.hudCacheOverride` / `GLSMConfig.hudCacheBlendEnabled`：GLSM 的
  blend/color 路径在 `hudCacheOverride=true` 期间应用与 StellarCore 相同的
  覆盖（`enableBlend/disableBlend` 记录混合开关；混合关闭时
  `changeColor` 强制 alpha=1；`tryBlendFuncSeparate` 强制
  `(ONE, ONE_MINUS_SRC_ALPHA)` 因子）；
- `StellarCoreHudCachingCompatTransformer`（launchwrapper transformer，
  注册于 `MixinEarly.getASMTransformerClass`）：重写
  `HUDCaching.renderCachedHud`，在 `renderingCacheOverride` 每次赋值后镜像到
  `GLSMConfig.hudCacheOverride`，并在缓存渲染的 `GuiIngame.renderGameOverlay`
  调用前恢复 HUD 基线（alpha test `GREATER/0.1` 等）；
- `GuiGlStateBoundary.restoreHudBaseline` 承担基线恢复（原由
  `EntityRenderer.updateCameraAndRender` 的注入点负责，但 StellarCore 的
  `@Redirect` 替换了该调用点）。

**为什么用 transformer 而不是 mixin**：`HUDCaching` 类由 StellarCore 自己的
early mixin loader 在 tweak 引导阶段加载，早于任何 mixin prepare 阶段；无论
early 还是 late 注册，mixin 都会抛 `MixinTargetAlreadyLoadedException`（实测
确认）。launchwrapper transformer 在类 define 前介入，与加载时机无关。

单元测试：`StellarCoreHudCachingCompatTransformerTest`（用依赖 jar 的真实
`HUDCaching` 字节码验证镜像窗口与基线恢复的注入结果）。

### 验证

- `HudCaching=true` + `JourneyMap 5.7.1p3`：小地图网格线、玩家准心、方向标
  正常可见（2026-08-14 实测通过）。

## 推荐配置

**HudCaching 保持开启**（默认值，已深度兼容）；`HUDFramebuffer`（InGameInfoXML）
与 Actinium GUI 管线共存无已知问题。

## 残余限制

- 归因修正基于 DE 兼容桥修复后的生产实测；StellarCore 版本升级后需重测
  （`HUDCaching.renderCachedHud` 字节码结构变化会使 transformer 匹配失败并
  记录 WARN，需同步更新）。
- 本说明不代表 StellarCore 的其他功能均已验证。
