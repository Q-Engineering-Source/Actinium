# Mekanism CE Unofficial 兼容说明

兼容状态：部分（MakeUp UltraFast 9.1f 下的 dev 实测通过；其他光影包待验证）

最后更新：2026-09-28

## 验证范围

- Mekanism-CE-Unofficial 10.0.5.021（CurseForge `840735:8862568`）
- Minecraft 1.12.2、Cleanroom 0.6.12-alpha、Java 25.0.3、Windows 10 dev 环境
- MakeUp UltraFast 9.1f
- 场景：放置大型化学清洗机；手持物品模型正常，放置后的机器贴图发白

## 根因

大型化学清洗机的方块实体 renderer 开启 `ModelLargeChemicalWasher` 的 glow pass。该 pass 在基础模型之后绘制 `ChemicalWasher_ON/OFF.png` 和 LED 贴图，并请求 `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` 混合。手持物品路径用同一个模型，但传入 `isEnableGlow = false`，所以不会绘制这层 overlay。

Iris 在 shaderpack pass 内锁定 blend state。GLSM 会把锁定期间的 vanilla blend 修改延后；Mekanism 的 overlay 因此可能沿用 shaderpack 当前的混合状态。日志中可见当前 blend 被禁用，而 Mekanism 保存的 vanilla 请求仍是启用 alpha blend，这会让透明 overlay 覆盖基础贴图并使模型发白。

## 修复

条件 Mixin 只在 Mekanism 的 `MekanismRenderer` 类存在时加载。在 `enableGlow` 与 `disableGlow` 之间，兼容层检查 shaderpack 是否正在使用锁定 blend，以及保存的 vanilla blend 是否请求 alpha 混合；符合时应用该请求，并在 glow pass 结束后恢复 shaderpack 的 blend 状态和锁。

兼容层读取 blend state 的延后请求，不关闭 overlay、LED 或光影，也不改变无光影时的渲染路径。

## 验证记录

- 2026-09-28：用户在 MakeUp UltraFast 9.1f + Mekanism-CE-Unofficial 10.0.5.021 dev 环境确认大型化学清洗机贴图恢复正常。
- 诊断日志记录 13,529 个 shaderpack glow pass 应用了保存的 alpha blend 请求；506 个无光影 pass 未触发兼容覆盖。每个已覆盖 pass 都记录了 shaderpack blend 状态恢复。
- 最终源码清理后，`./gradlew check --no-daemon` 通过。
- 其他用户报告过问题的光影包仍待实测；手持物品的最终回归也待补。
