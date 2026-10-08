# Lumenized / GTCEu Bloom 兼容性说明

兼容状态：**兼容**（Lumenized 动态光源 + Bloom 泛光、GTCEu Bloom 泛光均通过同一兼容层工作）
最后更新：2026-10-08

## 模组信息

- Lumenized 1.0.3（作者 Kasumi_Nova，GTCEu 发光功能的独立提取版）：动态光源 +
  发光纹理 Bloom 泛光。
- GregTech CEu 2.8.10-beta：内置同源 Bloom 后处理。
- 关键点：两个模组包含**同包同名**的 bloom 实现类（`gregtech.client.utils.BloomEffectUtil`
  / `RenderUtil` / `DepthTextureUtil`、`gregtech.client.shader.Shaders`、
  `gregtech.client.shader.postprocessing.BloomEffect`），一套 Mixin 即可同时覆盖。
  二者不会共存（类冲突），门控为类探测：`mixins.actinium.lumenized.json=class:gregtech.client.utils.BloomEffectUtil`，
  任一模组提供该 bloom 类即加载兼容层，与 mod id 无关。
- 挂载点差异：Lumenized 用 Mixin 在 `renderWorldPass` 第 4 处 `renderBlockLayer`
  （TRANSLUCENT）之后追加调用；GTCEu 用 ASM（`GregTechTransformer`）把该调用点直接替换为
  `BloomEffectUtil.renderBloomBlockLayer`。方法体结构也有差异：Lumenized 将全部流程内联在
  `renderBloomBlockLayer`；GTCEu 2.8 拆成 `renderBloomBlockLayer` 包装 +
  `renderBloomInternal` 实现（framebuffer 调用在后者）。

## 兼容层机制（`mixin/mod/lumenized/`，配置 `mixins.actinium.lumenized.json`）

- `MixinDepthTextureUtil`：强制 `isUseDefaultFBO()=false`、`shouldRenderDepthTexture()=false`，
  禁用私有 depth-texture 通道（该通道在 Actinium 渲染栈下会渲染到错误的 framebuffer，
  深度纹理为空导致泛光穿墙），改为共享主 framebuffer 深度。
- `MixinRenderUtilDepth`：修正 `hookDepthBuffer` 非 stencil 分支把
  GL_DEPTH_COMPONENT24 renderbuffer 绑到 GL_DEPTH_STENCIL_ATTACHMENT 的问题，
  改绑 GL_DEPTH_ATTACHMENT（其真实格式），bloom FBO 才拥有可用深度附件。
- `MixinBloomEffectUtilClear`：在主 framebuffer 绑定后捕获其深度附件，bloom FBO 绑定后
  仅清 color、把主深度附件共享过来并开深度测试，使泛光被世界几何正确遮挡。
  注入目标同时声明 `renderBloomBlockLayer` 与 `renderBloomInternal`（`require=0`），
  兼容 Lumenized 内联结构与 GTCEu 拆分结构。
- `MixinShadersDepthTest`：`Shaders.renderFullImageInFBO` 每次合成前显式开启深度测试，
  避免泛光隔着前景几何透出。
- `MixinBloomEffectUtilStateGuard`：在 `renderBloomBlockLayer` 入口/出口用
  `compat/lumenized/BloomStateGuard` 对 GLSM 跟踪状态做快照/恢复（framebuffer 绑定、
  活动纹理单元、**全部**纹理单元的绑定与 TEXTURE_2D 使能、混合/深度/剔除状态、program 0）。
  首次恢复时会把实际发生漂移的字段记录到日志（单次 INFO），用于定位泄漏源。

## 空 ticket 组跳过（`mixins.actinium.lumenizedperf.json`）

独立于主兼容层的性能优化配置，门控同为 `class:gregtech.client.utils.BloomEffectUtil`（类探测，
与 mod id 无关）。`MixinBloomEmptyGroupPostProcess` 用 `compat/lumenized/BloomSubmissionState`
跟踪每个 ticket 组：组内没有任何 ticket 进入 `IBloomEffect.renderBloomEffect` 时，跳过该组
后续的逐组全屏后处理（renderLOG/renderUnity/renderUnreal 第二组与第 4/5 次
`renderFullImageInFBO` 合成）。判据保守（effect 被调用即视为有内容），不会误跳过真实泛光。

- 结构容错与 `MixinBloomEffectUtilClear` 相同：pass 内重定向同时声明 `renderBloomBlockLayer`
  与 `renderBloomInternal` 且 `require=0`，覆盖 Lumenized 内联结构与 GTCEu 拆分结构，注入失配
  只是失去优化、不影响画面；`draw` 内的标记重定向保持 `require=1` fail-fast——两侧 `draw`
  结构同源一致，失配说明目标类已变化，必须显式失败而非静默吞掉泛光（跟踪已应用而标记未应用
  会把所有组读成空组）。
- 视觉等价性依赖"空组写入全零"（composite 为 `src*dstAlpha`、blend 为叠加），已通过自动化
  检查（`BloomSubmissionStateTest` 覆盖嵌套组），**实机画面对比待验证**。

## 已修复问题

- **进世界画面纯色（历史）**：Unreal Bloom 主流程与 GLSM/Celeritas 渲染栈冲突，
  世界渲染结果无法上屏。由 depth 共享 + 清理 + composite 深度测试一组 Mixin 根治，
  Bloom 效果真实生效（不再是旧的 "bloomStyle=0 safe mode" 规避，该策略已移除）。
- **第一人称手部模型与所持物品全黑**：默认 Unreal 管线（`BloomEffect.renderUnreal`，
  `bloomStyle=2`、`nMips=5`）在合成时对 0..4 号纹理单元逐个 `enableTexture2D()`，结束只
  解绑纹理、**从不关使能**；vanilla 代码从不触碰 2 号以上单元，泄漏的"使能+纹理 0"进入
  GLSM 固定管线 shader key（每个使能单元都采样并 MODULATE，不完整纹理 0 采样恒为黑），
  手部与所持物品 RGB 被乘零。由 `BloomStateGuard` 对**全部**纹理单元快照/恢复修复
  （首版只覆盖 0/1 号单元，unit2+ 泄漏穿越守护，实机验证后扩面）。GTCEu 与 Lumenized
  内嵌同一份 `BloomEffect`，症状与修复相同。
- **GTCEu 共存启动崩溃（EntityRenderer invalid classes）**：GregTechTransformer ASM
  替换 translucent 调用点导致 Actinium 侧 ordinal=3 调试标记注入 0 命中，已放宽为
  `require=0`（commit `2e61e73b`）。

## 验证记录

- 2026-09-08：手部/所持物品全黑与泛光效果在 GTCEu 与 Lumenized 两条路径下实机验证均正常。
- 2026-08-28：GTCEu 2.8.10-beta（curse 557242:5519022）+ CubicChunks 共存启动不再崩溃
  （实机确认）；随后发现手部/所持物品全黑。首版 `BloomStateGuard`（仅 0/1 号单元）实机验证
  无效，漂移日志确认守护每帧触发——据此把真因收窄到守护面之外，最终定位为
  `renderUnreal` 对 2..4 号单元的 TEXTURE_2D 使能泄漏，守护扩为全单元快照/恢复。
- 2026-07-20（兼容层实现前手动验证）：默认配置进世界纯色、Esc 可见世界；
  禁用 Lumenized 或 `bloomStyle=0` + `hookDepthTexture=false` 画面正常。
  该记录是旧 safe mode 的依据，safe mode 已被"让 Bloom 真正工作"的兼容层取代。
