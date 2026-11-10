# Celeritas 上游逐提交台账（06999aabc → b2a70f7f28）

## 基线与范围

| 项 | 值 |
|---|---|
| 上游仓库 | `https://github.com/GTNewHorizons/Celeritas`，默认分支 `nh-main`（经 `gh api` 读取，未修改 `D:/Code/celeritas-mirror`） |
| 范围 | `06999aabc...b2a70f7f28` |
| 起点 SHA | `06999aabc2ea41a772ea3d0888c88a9e72d09f02`（Rebalance compact vertex format layout） |
| 目标 SHA | `b2a70f7f28638eb3124a337584567fa72e00452a`（Bump GTNH build to 2.5.14，2026-10-29） |
| 本地基线 | Actinium `ef01e10cf2967d867c5d1d2556d664301bc07bbb`（`main`，2026-10-31） |
| 分支 | `chore/upstream-sync-baseline` |
| 主表提交数 | 36（与 `compare` 返回的 `total_commits` 一致） |
| 附加前置 | 1（`2334caab0`，范围外，单列成节） |
| `未分配` 条目 | 0 |

统计（主表 36 行）：待同步 1、分 hunk 同步 0、已有等价 1、不适用 10、已同步 24（C05 四行与 C08 一行计入已同步，待实机验证）。

## 边界

本台账只覆盖 1.12.2（Cleanroom Loader）+ LWJGL3 的 Actinium 目标。上游 `common/` 中的 `org.embeddedt.embeddium` 在本仓为 `celeritas-common` 的 `dhj.embeddedt.embeddium`；上游 `forge122/` 的 `org.taumc.celeritas.impl.render.terrain` 与 mixin 在本仓为 `src/main/java/com/dhj/actinium/render/terrain` 与 `com/dhj/actinium/mixin/vintage`；上游 `modern/`、`ornithe/`、`forge1710/` 在本仓不存在，其专用代码一律不适用。

`.github/`、`build.gradle`、`build.gradle.kts`、`gradle.properties`、`.gitignore`、`CLAUDE.md` 等 CI、发布、版本号与治理文件判不适用。

## 状态定义

- **待同步**：本地缺少对应实现，且该实现落在 1.12.2 + LWJGL3 范围内，需要移植。
- **已有等价**：已核对本地代码，行为等价物存在并给出具体位置，不需要移植。
- **分 hunk 同步**：同一提交内同时存在需要移植的通用代码与需要排除的平台专用代码，逐项拆分。
- **不适用**：目标代码全部落在本地不存在的模块、平台或治理文件中，或依赖的本地设施不存在。

**未核对本地代码的条目不得标“已有等价”。** 主表 1 条“已有等价”给出文件路径与行号。

## 逐提交台账

| 序 | 短 SHA | 上游标题 | 日期 | 状态 | 目标 PR | 处置说明 |
|---|---|---|---|---|---|---|
| 1 | `c64f4225f` | Move shader downgrading into ShaderLoader (#46) | 2026-09-24 | 已同步 | C01 | 降级逻辑移入 `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/shader/ShaderLoader.java` 的 `useLegacyGlsl()` 与 `downgradeIfNeeded(ShaderType, String)`，`ShaderChunkRenderer` 删掉四个正则常量与私有 `loadShader`，构造函数改读 `ShaderLoader.useLegacyGlsl()`。`LEGACY_PREAMBLE` 保留 `#extension GL_EXT_gpu_shader4 : require`（上游删掉了该行，本地地形着色器用到整数类型，删掉后 GLSL 1.20 编译失败） |
| 2 | `98c1af335` | Add ifdefs back to `block_layer_opaque.vsh` (#45) | 2026-09-24 | 已同步 | C01 | `src/main/resources/assets/actinium/shaders/blocks/block_layer_opaque.vsh` 的 `v_MaterialAlphaCutoff` 声明与赋值均加 `USE_FRAGMENT_DISCARD` 守卫，与 fsh 侧一致 |
| 3 | `8883da78b` | Remove `GLRenderDevice.VANILLA_STATE_RESETTER` (#44) | 2026-09-24 | 已同步 | C07 | 常量与 `Actinium.java`、`HeadlessGl.java` 两处赋值已删除，`makeActive`/`makeInactive` 改调私有 `unbindArrayBuffer()`（`LWJGL.glBindBuffer(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), 0)`）。上游把 vanilla 复位搬进 modern 的 `WorldRendererMixin`；本地无该 mixin，复位点改由 `src/main/java/com/dhj/actinium/render/ManagedCodeBoundary.java` 承担：它在 `RenderDevice.enterManagedCode()`/`exitManagedCode()` 前后各补一次 `GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)`，同步 GLSM 的 `ctx().boundVBO` 缓存（原来的 `OpenGlHelper.glBindBuffer` 被 GLSM 重定向，因此旧实现同时清了该缓存；只做裸解绑会让缓存保留悬垂句柄）。`MixinRenderGlobal` 的 4 处与 `MixinRenderGlobalProxy` 的 1 处已改走该边界 |
| 4 | `7e5e9d7ec` | Remove the FogService SPI (#43) | 2026-09-25 | 已同步（原有等价） | C07 | 本地 `celeritas-common/.../chunk/shader/ChunkShaderEnvironment.java` 已携带 `FogService`；`ShaderChunkRenderer` 显式创建 environment 并传给 `DefaultChunkShaderInterface`，`ChunkFogMode` 与 `ChunkShaderFogComponent` 从该实例读取雾状态。`VintageRenderSectionManager.getFogService()` 与内部 `ChunkRenderer` 都注入 `GLStateManagerFogService.INSTANCE`；`RenderSectionManager.getEffectiveRenderDistance` 走同一抽象。`FogService` SPI 文件不存在，代码中没有 `ServiceLoader` 或静态 `FOG_SERVICE`。2026-11-10 逐项复核，不重复修改正常路径 |
| 5 | `64158a451` | Fix workflow [skip ci] | 2026-09-25 | 不适用 | — | `release.yaml` 工作流修正，属 CI 文件 |
| 6 | `3345e59fa` | Bump base to 2.5 [skip ci] | 2026-09-25 | 不适用 | — | `gradle.properties` 的 base 版本提升 |
| 7 | `9b661b70e` | Opt publish task out of the configuration cache | 2026-09-25 | 不适用 | — | `build.gradle.kts` 的配置缓存选项，属构建治理 |
| 8 | `26ab21758` | Route the fast block renderer through BlockRendererDispatcher | 2026-10-01 | 已同步（`feat/c08-dispatcher-block-renderer`） | C08 | `ChunkBufferBuilder` 携带构建上下文、render layer 与原版回退标记，`ChunkBlockRendererRouter.shouldUseFastRenderer` 判定资格，`MixinBlockRendererDispatcher` 用 `@WrapOperation` 只包裹 `BlockRendererDispatcher.renderBlock` 内的 `BlockModelRenderer.renderModel` 调用点；`ChunkBuilderMeshingTask` 的普通 LAYERS 分支统一走 `getBufferForLayer` + `beginVanillaBlockRender` + `dispatcher.renderBlock` + `endVanillaRender`，Snow Real Magic、ArchitectureCraft 与 Fluidlogged 由 buffer 标记留在原版路径。本地不采用上游把 `renderBlock` 主体改名搬进 `renderModel` 的做法：`renderBlock` 主体、返回值、两个 `renderQuadList` 调用点与 `shouldSideBeRendered` 调用点保持在原位（`VintageBlockRendererBindingContractTest` 锁定的 addon 绑定面），新增私有 `preparedModel` 字段让 `renderBlock` 跳过 `getActualState` 与 `shapes.getModelForState`，`renderModel` 只注入模型并读取 `renderedAnyQuad`。shader layer override 的直接快速路径保留 `allowRenderPassOptimization=false` |
| 9 | `6150f9694` | Fix Oculus ShaderChunkRenderer init mixin after FogService constructor change | 2026-10-01 | 不适用 | — | 修的是 modern 源集里 Oculus 的 `MixinShaderChunkRenderer`，本地无该 Mixin |
| 10 | `48eb54f5c` | Don't treat vertices past the far plane as needing a near clip | 2026-10-01 | 已同步 | C01 | 已核实 `AbstractRasterizer.needsNearClip` 与 `PerspectiveRasterizer` 仅接受 `w > 0 && tz > 0`，远平面外顶点不再触发近平面裁剪，避免边界覆盖误判 |
| 11 | `7818b3317` | Relax SectionLattice restrictions on world height | 2026-10-11 | 已同步（`feat/c05-shadow-lattice`） | C05 | `SectionLattice` 以 `worldDimY` 记录世界高度加两侧哨兵，`allocate` 取 `dimY = min(worldDimY, dimXZ)` 并由 `windowedY` 决定 Y 是否随相机窗口化；`shiftRebase`、`dropLeaving`、`fillNewBand` 扩为三轴，新增 `shiftContentsWithY` 与 `moveCells` 搬运全部平行数组（含 `shadowVisitState`）。构造函数不再因世界高度超过 `MAX_DIM` 抛异常 |
| 12 | `ff6331466` | Add project docs | 2026-10-13 | 不适用 | — | 新增 `CLAUDE.md`，属治理文件 |
| 13 | `d94ec1e59` | Avoid carrying the transparency level enum through pixel loops | 2026-10-13 | 已有等价 | — | 本地 `MixinTextureAtlasSprite.embeddium$processTransparentImages` 已用 ordinal 累积替代逐像素 `chooseNextLevel` 调用（`src/main/java/com/dhj/actinium/mixin/vintage/features/mipmaps/MixinTextureAtlasSprite.java:73` 起），并保留 `chooseNextLevel` 于 `SpriteTransparencyLevel`；上游新增的 `SpriteTransparencyLevel.of(boolean, boolean)` 静态 helper 是可评估项，本次不引入 |
| 14 | `d3946af4e` | Move the shadow window update onto the search thread | 2026-10-22 | 已同步（`feat/c05-shadow-lattice`） | C05 | 只取并发语义：窗口准备不得与执行中的搜索重叠。本地已用 `SectionGraph.prepareWindow`（`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/SectionGraph.java:72`）加 `assertSearchNotRunning` 在提交搜索前统一准备所有视口，比上游把 `ensureWindowCovers` 移进搜索线程更早；不采用后者 |
| 15 | `c2823be43` | Remap the shadow search roots when the lattice window moves | 2026-10-22 | 已同步（`feat/c05-shadow-lattice`） | C05 | `SectionLattice.remapVisibleCells` 在 `rebase`/`shiftRebase` 之后按旧原点解出根的世界坐标，用新窗口重解析槽位并重写打包坐标，落出窗口的根被丢弃，`visibleCount` 同步收缩；窗口未变化时提前返回。`ShadowOcclusionCuller.seed` 的注释同步说明根集会被重映射 |
| 16 | `625b6f40f` | Stop translucent sorting hanging on non-finite quads | 2026-10-22 | 已同步 | C01 | 已核实 `TranslucentQuadRecorder` 将非有限四边形记为退化，`QuadSet.union` 与 `TranslucentSorter` 的共面/包围盒分析跳过退化四边形，排序索引仍保留 |
| 17 | `86d4cfe7b` | Align geometry arena elements to whole primitives | 2026-10-27 | 已同步（`feat/shared-chunk-infrastructure`） | C03 | `computeCommonVertexStride` 对各 pass 的 `stride * verticesPerPrimitive` 求 `lcm`，arena 的每次分配从完整图元边界开始，避免 `gl_VertexID` 取错 quad 角点；真实 GL 测试覆盖跨格式上传与 arena 搬移 |
| 18 | `b34b76ae2` | [modern] treat blended equal-depth render types as decals | 2026-10-27 | 不适用 | — | 改的是 `modern/src/main/batching_java` 的 Iris 渲染类型，本地无 modern 源集 |
| 19 | `c317f81a9` | [modern] give fixed-buffer render types their own builders | 2026-10-27 | 不适用 | — | `modern/src/main/batching_java` 的固定缓冲渲染类型构建器；本地 `FullyBufferedMultiBufferSource`、`SegmentedBufferBuilder` 不存在 |
| 20 | `e79964174` | GTNH build: common only, publish to GTNH maven | 2026-09-08 | 不适用 | — | GTNH 构建发布配置与 CI 工作流 |
| 21 | `0d003b82f` | Allow overriding the LWJGL service with a system property | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | `actinium.lwjglService` 非空时直接构造指定服务，未设置时保持 `LWJGL3Service`；子进程测试使用 Java argument file 传递完整 classpath，验证覆盖值进入服务构造 |
| 22 | `88e905ca5` | Upload indirect command buffers through DRAW_INDIRECT_BUFFER | 2026-09-08 | 已同步（`feat/chunk-batch-recycling`） | C02 | 已随 C04 落地：`CommandList` 与 `GLRenderDevice` 提供显式 `GlBufferTarget` 上传重载，旧重载继续使用 `ARRAY_BUFFER`；`IndirectMultiDrawBatch.upload` 固定使用 `DRAW_INDIRECT_BUFFER`，GL 测试同时校验 indirect 绑定与 ARRAY_BUFFER 未受污染 |
| 23 | `588a269e2` | Finish the render pass timer on exception | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | `RenderSectionManager.renderLayer` 通过 `try/finally` 闭合已启动的 pass 计时，保留原有 `commandList.flush()` 位置 |
| 24 | `170bb7d14` | Make MultiDrawBatch substitutable | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | `DirectMultiDrawBatch` 可继承，`DefaultChunkRenderer.executeBatch` 提供覆写入口并保留本地 INDIVIDUAL 分派；tessellation 由缓存批次携带，动画 section 继续使用专用 emitter；工厂与缓冲复用行为不变 |
| 25 | `1ce0ce4eb` | Expose device memory stats | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | `DeviceMemoryStats` 与 `getDeviceMemoryStats` 汇总 geometry/index arena；每个 region 只有一份共享资源，无资源时跳过，`getDebugStrings` 复用该统计 |
| 26 | `d7a146f03` | Bump visibility | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | `AoFaceData` 公开，`SimpleWorldRenderer.processChunkEvents` 改为 `protected`，`SimpleFrustum.getFrustumIntersection()` 暴露内部对象；`BatchAssembler` 公开成员与 `prepareTessellation` 已由 C01 完成，`prepareFrame` 不在该补丁中 |
| 27 | `57ca70de4` | Add renderSectionBlockEntities hook | 2026-09-08 | 已同步（`feat/shared-chunk-infrastructure`） | C02 | culled/global 方块实体都经过 `renderSectionBlockEntities`，默认 global 仍调用 `renderGlobalBlockEntityList`；阴影收集使用该钩子向本地 `List<TileEntity>` 添加元素，不触发 TESR 批次绘制 |
| 28 | `ba0981433` | Expose render list regions by index | 2026-09-08 | 已同步（`feat/gpu-terrain-culling`） | C02 | `ChunkRenderListIterable.getRegion(int)` 与 `SortedRenderLists.getRegion(int)` 已随 C01 完成，并有索引访问测试 |
| 29 | `77aec052d` | Let subclasses gate the shadow-pass search | 2026-09-08 | 已同步（`feat/shadow-pass-scheduling`） | C06 | `RenderSectionManager` 拆出 `canSubmitShadowGraphSearch` 与 `submitShadowGraphSearch`，保留统一窗口准备与地形搜索顺序；`VintageRenderSectionManager` 仅在阴影列表 dirty 时提交并累计提交次数 |
| 30 | `14cb5f7f0` | Upload build results in the shadow pass instead of joining its searches | 2026-09-27 | 已同步（`feat/shadow-pass-scheduling`） | C06 | 阴影搜索提交前上传构建结果，主地形 setup 只补传 `blockingResultsPending`；树排序始终进入地形队列，上传时检查两边 `hasAdditionalUpdates`，`updateChunksImmediately` 的末尾上传保留。真实 GL 测试覆盖调度顺序、子类门控与阻塞结果补传 |
| 31 | `f33e50ce0` | Test that a non-finite quad can't stall translucent sorting | 2026-10-21 | 已同步 | C01 | `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/sorting/TranslucentQuadRecorderTest.java` 覆盖非有限四边形的退化记录、有限值、共面快捷路径与包围盒判定 |
| 32 | `408881f27` | Test shadow search roots across a lattice window move | 2026-10-21 | 已同步（`feat/c05-shadow-lattice`） | C05 | `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/SectionLatticeWindowTest.java` 覆盖窗口移动后的根集与超高世界的近相机搜索；fixture 经 `region/TestRegions.java` 构造 compact stride 的 region |
| 33 | `9a731e9b6` | Bump GTNH build to 2.5.13 | 2026-10-21 | 不适用 | — | `common/build.gradle` 的 GTNH 构建版本号提升 |
| 34 | `69e91690d` | Reuse the indirect command buffer when a region batch is rebuilt | 2026-10-25 | 已同步（`feat/chunk-batch-recycling`） | C04 | `BatchBufferRecycler` 保存至多两个待复用缓冲，重建前先退休旧批次，`BatchAssembler` 在上传前接管其缓冲；`IndirectMultiDrawBatch` 延迟创建、分离并判空删除缓冲，`SectionRenderDataStorage.delete` 清理全部 spare。`CommandList` 与 `GLRenderDevice` 新增显式 target 上传，indirect 固定走 `DRAW_INDIRECT_BUFFER`，旧重载继续使用 `ARRAY_BUFFER`。保留本地 `MultiDrawMode`，`MultiDrawBatchFactory` 仅作为覆盖入口，默认 DIRECT/INDIVIDUAL 行为不变；真实 GL 测试验证缓冲名复用与最终释放 |
| 35 | `6bf6a433f` | Key cached batches by view | 2026-10-28 | 已同步（`feat/chunk-batch-recycling`） | C04 | `BatchCacheParams` 包含 `useBlockFaceCulling` 与 `view`，view 0/1 复用四个共享键，其余视口按值比较。`DefaultChunkRenderer.batchCacheParams` 提供覆写入口，默认仍为 view 0；目标 Angelica 未提供阴影视口的 view 1 覆写，C06 已核对并保持默认值，阴影批次由自身面剔除配置的缓存键区分。`getRegionBatch` 集中缓存查询与重建，两个视口交替时各自保留批次和缓冲 |
| 36 | `b2a70f7f2` | Bump GTNH build to 2.5.14 | 2026-10-29 | 不适用 | — | `common/build.gradle` 的 GTNH 构建版本号提升 |

## 验证规则

每个 PR 从本台账取行后，按以下顺序执行，判据未达成不进入下一步。

1. **范围核对**：用 `gh api repos/GTNewHorizons/Celeritas/commits/<sha>` 取出 `files` 清单，按本台账的排除清单逐文件确认。混合提交必须给出排除清单，不接受“整提交不适用”。
2. **本地实现位置确认**：先在 Actinium 全仓按类名定位目标文件，再确认包映射（`org.embeddedt.embeddium` → `dhj.embeddedt.embeddium`）。上游 `forge122/` 的实现落到 `src/main/java/com/dhj/actinium/render/terrain`。
3. **三方合并**：`RenderSectionManager`、`SimpleWorldRenderer`、`DefaultChunkRenderer`、`SectionLattice`、`ShaderChunkRenderer`、`IndirectMultiDrawBatch` 这类被多轮同步改过的文件用 `git merge-file`（本地=A，上游父=O，上游终态=B），冲突按语义逐个解决并在 PR 描述里记录。
4. **编译与测试**：`./gradlew check --no-daemon` 通过。上游的单元测试按 Actinium 现有结构落在根项目 `src/test`，不需要为 `celeritas-common` 或 `glsm` 新建 test source set；需要 GL 上下文的用例放根项目 `src/glIntegrationTest`。
5. **实机确认**：涉及渲染观感与性能的条目不替用户执行 `runClient`，把验证场景写进 PR 描述交用户实测。
6. **文档回写**：确认解决后把根因、触发机制与验证记录写进 `docs/compat/` 或 `docs/compatibility-matrix.md`。

## 目标 PR 索引

| PR | 覆盖的上游提交 |
|---|---|
| C01 | `48eb54f5c`、`625b6f40f`、`f33e50ce0`、`c64f4225f`、`98c1af335`、`fcf8ad06`、`cfc6e12e` |
| C02 | `0d003b82f`、`88e905ca5`、`588a269e2`、`170bb7d14`、`1ce0ce4eb`、`d7a146f03`、`57ca70de4`、`ba0981433` |
| C03 | `2334caab0`（范围外前置）、`86d4cfe7b` |
| C04 | `69e91690d`、`6bf6a433f` |
| C05 | `7818b3317`、`c2823be43`、`408881f27`、`d3946af4e`（只取并发语义） |
| C06 | `14cb5f7f0`、`77aec052d` |
| C07 | `7e5e9d7ec`、`8883da78b` |
| C08 | `26ab21758` |

C04、C09 与 P01 等编号下另有来自 Angelica 的条目，登记在 `docs/sync-angelica-8e099e95.md`，本台账不重复占行。

## 关于 `2334caab0`

该提交（Allow multiple vertex formats to share the same arena，2026-09-07）是 C03 的前置，不在 `06999aabc...b2a70f7f28` 范围内：

- `gh api repos/GTNewHorizons/Celeritas/compare/2334caab0...06999aabc` 返回 `status=ahead`、`ahead_by=17`、`behind_by=0`，说明它是 `06999aabc` 的祖先。
- `gh api repos/GTNewHorizons/Celeritas/compare/5c68ed4cb...2334caab0` 返回 `behind_by=3`，说明它也不在上一轮 `5c68ed4cb..06999aabc` 范围内。

台账主表严格对应范围内 36 个提交，`2334caab0` 单列在下方“范围外前置”小节，不计入 36，也不作为范围比对差异。

## 范围外前置

| 短 SHA | 上游标题 | 日期 | 状态 | 目标 PR | 处置说明 |
|---|---|---|---|---|---|
| `2334caab0` | Allow multiple vertex formats to share the same arena | 2026-09-07 | 已同步（`feat/shared-chunk-infrastructure`） | C03 | 每个 region 的不同顶点格式共用一份 geometry arena 与 index arena，`SectionRenderDataStorage` 按公共元素大小换算顶点偏移。tessellation 按 `TerrainRenderPass.TessellationKey` 缓存并随批次保存，arena 刷新或资源释放时失效。保留本地 512 KiB geometry arena 最小容量、缓冲回收与 GPU 剔除元数据；整图元对齐由后续 `86d4cfe7b` 完成。 |

## 统计与核对方法

主表 36 行对应范围内 36 个提交，一一对应。`d3946af4e` 只取并发语义那一半，仍占一行并在处置说明里写明取舍。前置条目 `2334caab0` 独立成节，统计时明确记为 36 + 1。

## PR 0 已完成的自动化验证

PR 0 的任务 1 恢复了 GL 集成测试入口，任务 2 提取了 `BUFFERBUILDER_*` 与 `SHADOW_*` 性能计时。两项的自动化验证记录在 `.superpowers/sdd/pr0-task1-report.md` 与 `.superpowers/sdd/pr0-task2-report.md`：

- `./gradlew check --no-daemon` 通过，覆盖 `:test`（795 个测试，0 失败）、`:verifyDistributedJar`、`:verifyModuleBoundaries` 与四个子项目的 `check`，并包含 `:compileGlIntegrationTestJava`。
- `./gradlew glIntegrationTest --no-daemon` 通过，2 个 SDL 窗口后端集成测试实际执行并通过；`check` 不依赖该任务。
- 任务 2 的计时接线通过 RED/GREEN：先得到编译失败与断言失败，再实现 stage 常量与接线使 17 个测试全部通过。

本任务（PR 0 任务 3）只新增两份台账文档，未改代码、未执行 Git 提交。本台账的 SHA 覆盖由脚本机械核对，方法与结果见 `.superpowers/sdd/pr0-task3-report.md`。

## 用户实机性能基线（待采集）

以下项目尚未采集，台账不记录任何实测数值：

1. **渲染包与版本**：Derivative Main `d24.4.14`。
2. **环境固定项**：同一存档、同一坐标、同一视角朝向、同一画质预设、同一渲染距离、同一 Distant Horizons 配置（LOD 距离与质量档位需一并记录）。
3. **采样方式**：每项配置连续采集 5 次、每次 60 秒，丢弃首次（含世界加载抖动）。
4. **指标**：FPS（平均值与 1% low）、CPU frame time（平均值与 P95）、进程内存峰值。
5. **对照基线**：同一环境下的 Actinium `ef01e10c` 与各 PR 合并后的构建，两组在同一会话内交替采集。
6. **判定**：FPS 与 CPU frame time 的差值超出两次同构建重复采集的波动范围才计入回归。

C 系列的多数条目落在区块渲染热路径上（`RenderSectionManager`、`DefaultChunkRenderer`、`SectionLattice`、批量绘制），性能判定必须用上述基线，不能用单次观测值。

## 与上一轮评估的衔接

上一轮 `docs/sync-celeritas-06999aabc.md` 评估到 `06999aabc` 为止。本台账的起点与之相同，主表 36 个提交是此后新增的部分，历史遗留项仍以该文档的“遗留验证项”为准。`2334caab0` 属于更早的未同步提交，单列在前置小节。
