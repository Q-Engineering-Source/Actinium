# CodeChickenLib 兼容性说明

兼容状态：**已验证**（DE 与 Thermal Dynamics 场景回归通过）
最后更新：2026-10-30

## 模组信息

- CodeChickenLib（modid `codechickenlib`），3.3.8（生产整合包为 3.2.3.358）
- 接入方式：late/conditional Mixin（`mixins.actinium.ccl.json`，`codechickenlib` 条件）

## 机制

- `MixinGlStateTracker`：`@Overwrite` CCL 的 `GlStateTracker.pushState()/popState()`，
  委托给 `com.dhj.actinium.compat.ccl.GlStateTrackerSnapshot`（mixin 包外的实现类）。
  CCL 的 tracker 原本读取原版 `GlStateManager` 字段缓存——在 GLSM 重定向下该缓存
  冻结，DE 每帧 HUD 的 push/pop 会把真实 GL 状态重置为初始默认值；兼容桥改为读写
  GLSM 真实 tracked 状态，恢复帧末状态为「GUI 渲染后的合理状态」。

## 自定义方块缓冲区初始化

`f0579750` 的区块构建重构只在流体与原版模型 capture 路径调用
`VintageChunkBuildContext.beginVanillaBlockRender`。Thermal Dynamics 的自定义 render type
不经过这两条路径，其 CCL 渲染器调用 `CCRenderState.bind(buffer)` 时，buffer 尚未执行
`begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK)`，使 `CCRenderState.fmt` 保持 `null`，
随后在 `CCRenderPipeline.rebuild` 中反复抛出空指针。

修复位于 `ChunkBuilderMeshingTask` 的 shader layer override 与普通多 render layer 路径。
每次调用 `BlockRendererDispatcher.renderBlock` 前均执行 `beginVanillaBlockRender`，
并在 `finally` 中依次调用 `endVanillaRender` 与 `actinium$endInvocation`。该生命周期同时
为 CCL 自定义方块提供顶点格式与光影方块上下文，快速模型和嵌套 vanilla 模型继续使用原有
上下文栈。

## 验证记录

- dev 环境（Cleanroom 0.6.7，DE 2.3.28.354 + CCL 3.3.8）：云/草方块侧面/主菜单
  三症状修复回归通过（详见 draconic-evolution.md）。
- 2026-10-30，用户生产整合包中的 Thermal Dynamics 能量管道、透明流体管道与 servo
  场景回归通过。有、无光影时重建相关 chunk 均恢复正常显示，未再出现
  `CCRenderPipeline.rebuild` 的 `this.renderState.fmt` 空指针。
- `./gradlew build --no-daemon` 通过，包含完整测试与 remap Jar 结构验证。
- CCL 3.2.3.358（生产整合包版本）的 `GlStateTracker` 方法签名兼容性待实际验证。

## 待办

- [ ] CCL 3.2.3.358 下验证 `GlStateTracker` mixin 兼容性
