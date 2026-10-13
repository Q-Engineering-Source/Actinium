# iChunUtil 兼容性说明

兼容状态：**代码支持**（Portal 渲染集成；阴影 pass 崩溃已修复，专项复现待补）
最后更新：2026-10-13

## 模组信息

- iChunUtil（modid `ichunutil`），1.12.2 版本
- 接入方式：late/conditional Mixin（`mixins.actinium.ichunutil.json`，`ichunutil` 条件）

## 机制

- Mixin（3）：`MixinPortalFrustum`（传送门视锥）、`MixinRenderGlobalProxy`（渲染
  代理）、`MixinWorldPortalRenderer`（传送门渲染器）。
- 实现（5）：`PortalRenderState`（传送门渲染状态）、`PortalChunkRenderMatrices`
  （传送门视口下的区块矩阵）、`PortalViewportFactory` / `PortalViewportProvider`
  （视口构造/注入）、`WorldBoxVisibility`（世界包围盒可见性）。

## 已知问题与修复

### 阴影 pass 崩溃（2026-10-13 修复，`eba565a4`）

- **触发**：开实体阴影的光影包 + 维度切换（实测路径：切包后进入下界，很快崩溃）。
- **根因**：`ShadowRenderer.renderEntities` 只按旁观者 / RFP2 假人 / 阴影视锥过滤，就把
  `loadedEntityList` 全部交给 `RenderManager.renderEntityStatic`；vanilla 主渲染循环只渲染
  通过 `RenderManager.shouldRender` 的实体（其首条即 `Entity.isInRangeToRender3d`）。
  iChunUtil 的 `EntityLatchedRenderer` 占位实体在 latch 目标缺失时
  `isInRangeToRenderDist` 返回 false，且 `getBrightnessForRender` 解引用同一目标；
  维度切换后残留的占位实体在主 pass 会被跳过，却进入了阴影 pass，
  在 `renderEntityStatic` 开头的 lightmap 查询处崩溃。
- **修复**：`ShadowRenderer` 的两条实体选择路径（实体列表与玩家）都改用
  `RenderManager.shouldRender`，与 `MixinRenderGlobal` 的加速主循环及上游 Iris 的选择方式一致。
- **验证**：修复后多轮 dev 运行（光影包互切、世界进出）无复发；原触发场景
  （含实体阴影的包 + 进入下界）尚未专项复现。

## 验证记录

- 待补：启用 iChunUtil 后验证传送门渲染（视口/矩阵/遮挡）正常。
- 2026-10-13：阴影 pass 崩溃修复（`eba565a4`，见上），常规运行无复发；下界专项复现待补。

## 待办

- [ ] 运行验证传送门渲染（视口/矩阵/遮挡）并回填验证记录
- [ ] 开实体阴影的包 + 进入下界，专项复现阴影 pass 崩溃场景
