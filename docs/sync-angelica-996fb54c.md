# Angelica 上游增量台账（8e099e95 → 996fb54c）

## 基线与范围

- 上游：`D:/Code/Angelica` 的 `upstream/master`。固定范围为 `8e099e952921a53ea5db88414e1071135f716ed4..996fb54cb05e256ea6039f4e2c8457a221a179b7`，共 18 个提交。表格按 Git 提交顺序排列，日期使用作者日期。
- 对照基线：Actinium `3a333db7fd9f3faffd510bdd9000902c72933d02`（`main`）。覆盖 Minecraft 1.12.2 的 Cleanroom、Forge 和 Outlands 路径，以及本地 GLSM、Iris 和 SDL GPU 后端。
- 前序：[Angelica 上游逐提交台账（02f0b0fc → 8e099e95）](sync-angelica-8e099e95.md)。

## 逐提交结论

| 序号 | 提交 | 日期 | 处置 | 上游改动与本地对照 |
| --- | --- | --- | --- | --- |
| 1 | [`2b46b4f6`，#2315](https://github.com/GTNewHorizons/Angelica/pull/2315) | 2026-10-30 | 已移植 | `DisplayListManager.glEndList` 在发布列表时保留显式 ID，删除时移除缓存并释放 ID。分配器以稀疏区间存储 ID，支持跨边界的连续分配。`DisplayListVBO.SubVBO.render` 与 `BatchedIndexedDrawCmd.execute` 在回放结束后恢复调用方的 VAO。上游 `pendingExtVbo` 路径在本地无对应结构。 |
| 2 | [`ea2d3458`，#2324](https://github.com/GTNewHorizons/Angelica/pull/2324) | 2026-11-01 | 不直接移植 | 在 Thaumcraft 镜面 `PortalDrawer.drawDirect` 进直接绘制路径前调用 `BatchStateGuard.beforeChange()`。本地有 `BatchStateGuard`，没有上游 `compat/thaumcraft/PortalDrawer`。只有 1.12.2 镜面复现同类状态串扰时，才检查对应绘制入口的批次刷新时机。 |
| 3 | [`85bde582`，#2321](https://github.com/GTNewHorizons/Angelica/pull/2321) | 2026-11-01 | 资源择取 | 更新 `assets/angelica` 与 `assets/notfine` 的波兰语资源。本地语言键分布在 `assets/actinium`、`assets/celeritas` 等命名空间，当前无波兰语文件。新增波兰语时按本地英文资源逐键筛选，排除未移植的 NotFine 选项与界面。 |
| 4 | [`5da38c91`，#2325](https://github.com/GTNewHorizons/Angelica/pull/2325) | 2026-11-01 | 待移植 | `BatchDrawDefaults.apply()` 只在有延迟光影管线时保留 `DepthColorStorage` 登记的 program。本地同名方法仍无条件豁免这些 program；无光影时 TESR 批次可能继承不属于当前绘制的 program。验证两条分支的 program 及 framebuffer 恢复。 |
| 5 | [`e46eb463`，#2326](https://github.com/GTNewHorizons/Angelica/pull/2326) | 2026-11-01 | 分项评估 | `ScreenCapture`、OpenGL 的缩放与 PBO 环、SDL 的 `SDLScreenCapture` 为 `TracyFrameImages` 采集帧图。本地 `glsm/profiling/Tracy` 没有原生采集后端，帧图功能依赖后端接入。另行评估其中可独立应用的 SDL 读回改进：本地 `ReadbackShadows` 已有 transfer buffer 与 `serve`，上游按槽记录有效字节长度和 fence，并补齐写入或释放时的失效处理。 |
| 6 | [`e64a4e72`，#2327](https://github.com/GTNewHorizons/Angelica/pull/2327) | 2026-11-02 | 随前置移植 | `PositionEntries.PlayerPosition` 每次显示时从当前 `WorldProvider` 取维度名，删掉内含世界引用的 `cachedProvider`。本地没有该现代 F3 实现。做前序台账第 11 项时采用修正后的代码。 |
| 7 | [`8840c210`，#2328](https://github.com/GTNewHorizons/Angelica/pull/2328) | 2026-11-02 | 已移植 | `IndexedDrawBatch.bind` 在首次创建 VAO 时登记颜色、法线及两个纹理坐标通道的顶点格式标志，供 `GLStateManager.preDrawFFP` 选择匹配的变体。 |
| 8 | [`52444510`，#2343](https://github.com/GTNewHorizons/Angelica/pull/2343) | 2026-11-06 | 随前置移植 | 只调整 `assets/angelica/textures/font/ascii.png` 中 `~` 的位置。本地 `MixinFontRenderer` 给宿主 `FontRenderer` 创建批处理实现，仓库没有这张现代字体图。若前序台账第 8 项引入该资产，直接使用修正后的版本。 |
| 9 | [`a6d014a9`，#2336](https://github.com/GTNewHorizons/Angelica/pull/2336) | 2026-11-06 | 已有对应路径 | 上游把 1.7.10 `MixinRenderFallingBlock` 的 `renderStandardBlock` 改为 `renderBlockByRenderType`。本地无此覆盖；1.12.2 patched `RenderFallingBlock.doRender` 绑定方块图集，从 `BlockRendererDispatcher.getModelForState` 取当前状态对应的烘焙模型，经 `BlockModelRenderer.renderModel` 绘制。当前没有相同的强制标准方块绘制路径。 |
| 10 | [`38762184`，#2346](https://github.com/GTNewHorizons/Angelica/pull/2346) | 2026-11-06 | 部分待移植 | `GLStateManager.maxPointSize()` 的 GLES 查询可独立改用 `GL_ALIASED_POINT_SIZE_RANGE`。完整原生 GLES 支持另做：本地 `src/lwjgl3` 的 `Lwjgl3GLRenderBackend` 仍走桌面能力表及 `GL33C` 的 sampler/divisor，`CompatShaderTransformer` 未接 GLES 转译。需一起验证 ES 函数地址、GL proxy texture 和 `GlslVulkanPreprocess` / `SpirvShaderTranslator` 的 UBO 保留。SDL 已有 proxy texture 查询，架构改造可复用，不重复实现；上游 CI、独立模块布局和 LWJGL 2 stubs 不照搬。 |
| 11 | [`aee989d2`，#2347](https://github.com/GTNewHorizons/Angelica/pull/2347) | 2026-11-07 | 分项评估 | 上游在有光影且 TESR 变换压扁物品模型时，把固定管线方向光烘入顶点色并跳过相关实例化与物品缓存；预着色文字跳过再次照明。本地 Storage Drawers 的 `StorageDrawersLightingImpl` 只修正设置灯光时的 model-view 矩阵，覆盖范围不同。先验证 1.12.2 抽屉或桶的扁平物品图标，再按 `BufferBuilder` 顶点格式和本地物品批次路径适配；上游 1.7.10 `Tessellator.rawBuffer` 和 `RenderBlocks` 的改动不直接取用。 |
| 12 | [`9493d630`，#2348](https://github.com/GTNewHorizons/Angelica/pull/2348) | 2026-11-07 | 待移植 | 本地 `RenderSystem.resolveClipCullDistanceSupport`、FFP `VertexKey` 和 Iris `CommonTransformer` 已按设备能力门控原生裁剪，仍缺 clip distance 模拟。移植时保留 MobileGlues 判定，加入实际平面数量与 `ClipPlaneBooleanState` 的变更跟踪；配套实现 FFP 的顶点距离与片元舍弃、SDL 能力和 uniform 数组、Iris 程序变体切换与缓存键。上游 Iris 模拟只覆盖 `Patch.ATTRIBUTES` 的顶点和片元阶段，无 geometry/tessellation 的程序才使用它。测试方块与强制模拟系统属性不进入生产代码。 |
| 13 | [`d69fd20a`，#2351](https://github.com/GTNewHorizons/Angelica/pull/2351) | 2026-11-07 | 分项评估 | `CaptureGate` 启动时增加 `GL_EXT_debug_tool` 探测，`GLSMInitConfig` 向帧调试标记提供版本标签。两项可独立于 Tracy 评估，接入本地 `GLStateManager`、`GLDebug` 和 `MixinOpenGlHelper` 的初始化路径。Tracy 首次连接消息依赖原生后端；Spark 改动针对 `Forge1710SparkPlugin.getKnownSources`，不移植该 Mixin。 |
| 14 | [`367f9d91`，#2353](https://github.com/GTNewHorizons/Angelica/pull/2353) | 2026-11-08 | 不直接移植 | 为 1.7.10 的 `RenderBlocks.renderBlockByRenderType` 增加 LOTR Coremod 的 `LOTRReplacedMethods$BlockRendering.renderStandardBlock` 调用目标。本地 `VintageBlockRenderer.renderBlock` 与 `renderModel` 使用 1.12.2 的 `IBlockState` 和 `IBakedModel`；`ModdedBiomeDetector.initializeLOTR` 是独立的生物群系探测。 |
| 15 | [`60d1a3de`，#2354](https://github.com/GTNewHorizons/Angelica/pull/2354) | 2026-11-09 | 部分待移植 | 上游恢复存储流体绘制，并在主线程预扫描中同时检查宿主与流体方块。本地 `FluidloggedCompat.renderFluidState` 已绘制存储流体，`ChunkBuilderMeshingTask` 的工作线程与 `captureFluidRender` 主线程路径都已调用它，但 `prescanDeferred` 只检查宿主。补充存储流体的线程安全判定，用线程安全宿主和要求主线程的流体验证整格延后、流体仅绘制一次。 |
| 16 | [`a12d2bb4`，#2360](https://github.com/GTNewHorizons/Angelica/pull/2360) | 2026-11-11 | 不直接移植 | 清除 1.7.10 Extra Utilities `RenderBlockConnectedTexturesEthereal` 的两个临时 renderer 持有的世界引用。本地 Extra Utilities 2 使用独立的 `MixinXUBlockStaticLazyQuads` 模型 quad 缓存和 `MixinGLStateAttributes` GUI 状态兼容。若 XU2 复现退出后仍持有 `WorldClient`，按堆引用链定位持有者。 |
| 17 | [`d1e7838c`，#2356](https://github.com/GTNewHorizons/Angelica/pull/2356) | 2026-11-14 | 已有对应路径 | 上游给 GTNH LiquidLogged `0.2.0` 新增区段流体快照与邻接查询，并在绘制存储流体时配对调用 `beginStoredFluid` / `endStoredFluid`。本地针对不同依赖 JBredwards Fluidlogged API `3.0.6`，`ClonedChunkSection`、`FluidStateStorage` 与 `WorldSlice.getFluidState` 已快照并读取完整 `FluidState`。保持本地 API，结合第 15 项验证非默认流体状态、区段接面及移除流体后重建。 |
| 18 | [`996fb54c`，#2364](https://github.com/GTNewHorizons/Angelica/pull/2364) | 2026-11-13 | 随前置移植 | 在已有原版雨雪实例化的基础上，新增 `WeatherColumnProvider` / `WeatherColumnSink`，支持 handler 决定是否保留原版降水，并按纹理层提交带颜色、淡出半径及 tick 的列。本地具备 `Instancing.WEATHER`、`WeatherQuadMesh`、`WeatherParams` 与 48 字节实例布局，但 Iris 的 `WEATHER` program 列表为空，`EntityRendererIrisMixin.actinium$renderWeather` 仍调宿主 `renderRainSnow`。先接前序 `f2c07dea` 的原版降水实例化，再迁移 80 字节布局、FFP/Iris 变换、额外 uniform 与世界切换清理。Flyby 采集脚本及 1.7.10 NotFine 的 Biomes O’ Plenty 树叶 Mixin 分开处理。 |

## 建议移植顺序与验证

1. **显示列表回放（第 7、1 项）**：完整构建、分配器单测及启用生产 GL 重定向的 34 项定向 GL 集成测试已通过。覆盖带光照与纹理的索引列表首次及重复回放的像素结果、显式高位 ID 的占用与删除复用、普通与宽线回放后的 VAO/EBO 绑定。用户在 `1.12.2-Cleanroom` 测试实例试用后暂未发现问题。
2. **批次 program 与流体线程安全（第 4、15 项）**：两项可独立推进。前者覆盖无光影和延迟管线，并比较批次绘制前后的 program 与 framebuffer；后者覆盖工作线程、主线程延迟捕获和跨区段接面。
3. **GLES 和 clip distance（第 10、12 项）**：点大小查询可单独验证；原生 GLES 启动需要真实 ES 上下文。clip distance 从现有 `ClipPlaneInjectionTest` 扩展，另在 GL 集成验证启停、`push/pop`、列表回放和 Iris 普通、实例化、宽线变体切换，并在 SDL 检查能力报告与 uniform 上传。
4. **先验证再决定范围（第 5、11、13 项）**：SDL 读回单独验证 fence、映射长度及 buffer 重分配；抽屉和桶先在 1.12.2 光影环境重现，兼顾普通、附魔物品与文字；捕获工具先验证 `GL_EXT_debug_tool` 探测和帧标记，Tracy 帧图等原生后端接入后再做。
5. **天气（前序 `f2c07dea` → 第 18 项）**：先验证原版雨雪实例化，再验证多纹理层自定义降水、保留与替换原版降水、缺少实例变体时原 handler 只执行一次、切换世界和维度后刷新。保留本地 `RAIN_SNOW` 阶段、深度写入设置和嵌套渲染保护。
