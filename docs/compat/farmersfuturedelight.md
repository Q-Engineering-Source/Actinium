# Farmers-Future-Delight 含水方块光影兼容（farmers_future_delight）

兼容状态：**已验证（用户实机确认）**。
最后更新：2026-11-05。分支：`fix/ffd-waterlogged-faces`。验证用 jar：`alpha-0.0.14-ef89ffd9`。

## 背景

Farmers-Future-Delight（modid `farmers_future_delight`，源码
`https://github.com/xy177/Farmers-Future-Delight`）在 1.12.2 上实现 1.13 式含水方块：给支持的
方块类型注入 `waterlogged` 属性，并把含水状态的模型包进自己的水面模型。开启光影后，含水方块
里的水面被当作承载方块本身的材质渲染：没有波浪、反射与折射，光影包的水下雾与水体合成也不生效，
与相邻海洋水面明显不一致。

## 模组机制

- **属性注入**：`WaterloggedBlockApi.UNIVERSAL_WATERLOGGED` 是一个名为 `waterlogged` 的
  `PropertyBool`；`FFDHeightTransformer.transformBlockStateContainer` 把它追加到支持方块
  （台阶、半砖、栅栏、栅栏门、铁栏杆、活板门、梯子、告示牌、箱子、树叶、铁轨、屏障、单层雪等）
  的 `BlockStateContainer` 构造参数里，模组自有方块也声明同名属性。
- **水面几何**：`ClientProxy.onModelRegistry` 把所有带该属性的方块以及模组自有的
  `IWaterloggableBlock` 方块注册进 `WaterloggedBlockApi.renderedBlocks()`；`ModelBakeEvent`
  再把它们的 baked model 包成 `WaterloggedPlantBakedModel`。该模型的 `getQuads` 只在
  `MinecraftForgeClient.getRenderLayer() == TRANSLUCENT` 时输出水面 quads，且只占 unassigned
  列表（六个面列表返回空），所以这一层不会重复绘制方块本体。
- **材质**：模组自有方块在含水时 `getMaterial(state)` 直接返回 `Material.WATER`；原版方块靠
  属性，材质不变。

## 根因

模组为 OptiFine 写了两处 ASM 适配，Actinium 的区块管线都不经过它们：

1. `FFDHeightTransformer.transformRenderChunk` 把 `RenderChunk` 内的 `canRenderInLayer` 调用点
   换成 `FFDClientHeightHooks.canRenderWaterloggedBlockLayer`，让含水方块额外进入 TRANSLUCENT
   层。Actinium 的 mesher 直接调用 `Block.canRenderInLayer`
   （`ChunkBuilderMeshingTask.java`），原版方块的 `getBlockLayer()` 对这些方块恒为
   SOLID/CUTOUT，于是水面 quads 根本不生成，含水原版方块里没有水。
2. `FFDHeightTransformer.transformOptiFineShaderVertexBuilder` 把
   `ShadersVertexBuilder.pushEntity` 入口的方块状态换成水源方块
   （`FFDClientHeightHooks.shaderWaterloggedState`），让水面顶点带上水的 `mc_Entity`。
   Actinium 没有 OptiFine，`VintageBlockRenderer.renderBlock` 按承载方块解析 shader id 与
   renderType，水面因此以方块身份进入光影管线，包侧按 `mc_Entity` 判材质的波浪、法线、
   反射与 `waterMask` 全部失效。

## 修复

新增 `compat/farmersfuturedelight/FarmersFutureDelightCompat`，由
`Mods.FARMERSFUTUREDELIGHT` 门控，不引用模组任何类，识别依据取模组的公开可见特征：

| 判定 | 依据 |
| --- | --- |
| 水面模型 | 模型类名为 `xy177.farmersfuturedelight.client.model.WaterloggedPlantBakedModel`（只有它在该层产出水面 quads） |
| 含水状态 | 状态带 `waterlogged=true` 属性，或方块报 `Material.WATER` 且不是 `Blocks.WATER`/`FLOWING_WATER`（模组自有方块用后者） |

接入点：

- `ChunkBuilderMeshingTask`：层判定追加
  `FarmersFutureDelightCompat.shouldForceTranslucentLayer`，让只靠注入属性的方块（原版与
  第三方方块）也进入 TRANSLUCENT 层并产出水面 quads。
- `VintageBlockRenderer`：TRANSLUCENT 层命中含水判定时，该方块这次渲染整体按水写出——材质取
  `defaultFluidMaterial()`（`Semantic.WATER` pass，与真实水一致：写深度、参与半透明排序），
  `prepareEncoder` 用 `Blocks.WATER` 与 metadata 0 走 `prepareToRenderFluid`，`mc_Entity` 因此
  是水的 block id 且 renderType 为 fluid；光照仍取承载方块自身的光照。
- `VintageChunkBuildContext.beginVanillaBlockRender`：同一判定下用水的 block id 与 fluid
  renderType，让"快速方块渲染"关闭时的 dispatcher 路径与快速路径给出一致身份。

## 已知缺口

- **深度语义变化**：水 pass 写深度（`writesDepth(true)`），含水方块的水面因此与真实水一致，但也会
  遮挡其后绘制的 pass-1 方块实体（与现有海洋水面行为相同；translucent pass 不写深度是为 #58 的
  EnderIO 储罐流体保留的行为）。
- **stored-fluid 路径**：`FFDStoredFluidStates`（`waterloggingAdditionalBlocks` 等无法携带属性
  的方块）的模型没有被包进水面模型，强行加 TRANSLUCENT 层会重复绘制方块本体，本次不处理。
- **非水流体**：含水方块里装的是水以外的流体时（`CONTAINED_FLUID`）仍按水上报。模组自己的
  `getMaterial(state)` 在含水时同样恒返回 `Material.WATER`，两者行为一致。
- **光影包 `layer.*` 强制指定层**：`ChunkBuilderMeshingTask` 的 shader 层覆盖分支按包的决定
  渲染单层，不改写，与模组在 OptiFine 下的行为一致。

## 验证记录

```text
日期：2026-11-05
Actinium commit：ef89ffd9（jar Actinium-alpha-0.0.14-ef89ffd9.jar）
Java / Cleanroom：Java 25.0.4.1 / Cleanroom 0.6.13-alpha（CleanMix 0.7.2）
GPU / 驱动 / OS：NVIDIA GeForce RTX 5070 Laptop GPU / 621.01 / Windows 11
光影包与预设：Complementary Unbound r5.9.3、MakeUp-UltraFast 9.4c
模组版本：Farmers-Future-Delight 1.5.1
场景：光影与无光影下的含水方块水面（原版方块含水与模组自有含水方块）及相邻海洋水面，
      两个光影包各测一次；开关组合未逐项记录
结果：用户实机确认通过；logs/latest.log 无区块构建异常，无兼容层异常。
      会话内另有一条 Complementary 自定义 uniform inSulfurCaves 的告警（缺少
      BIOME_SULFUR_CAVES 常量），与本次改动无关
日志与截图：实例 versions/1.12.2-Cleanroom/logs/latest.log（2026-11-05 14:40-14:43 会话）
```
