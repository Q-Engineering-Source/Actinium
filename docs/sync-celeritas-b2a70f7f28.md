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

统计（主表 36 行）：待同步 23、分 hunk 同步 2、已有等价 1、不适用 10。

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
| 1 | `c64f4225f` | Move shader downgrading into ShaderLoader (#46) | 2026-09-24 | 待同步 | C01 | 着色器降级逻辑从 `ShaderChunkRenderer` 移入 `ShaderLoader`；本地降级仍在 `ShaderChunkRenderer.loadShader`（`LEGACY_PREAMBLE` + 正则替换），`ShaderLoader.loadShader` 无降级分支 |
| 2 | `98c1af335` | Add ifdefs back to `block_layer_opaque.vsh` (#45) | 2026-09-24 | 待同步 | C01 | `v_MaterialAlphaCutoff` 的声明与赋值加 `USE_FRAGMENT_DISCARD` 守卫；本地 `src/main/resources/assets/actinium/shaders/blocks/block_layer_opaque.vsh` 第 22 行与第 85 行仍无条件声明并使用（fsh 侧已有守卫） |
| 3 | `8883da78b` | Remove `GLRenderDevice.VANILLA_STATE_RESETTER` (#44) | 2026-09-24 | 待同步 | C07 | 删除 `GLRenderDevice.VANILLA_STATE_RESETTER`；本地 `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/device/GLRenderDevice.java:32` 仍定义并在第 49 行调用，`jmh` 的 `HeadlessGl` 仍赋值。上游把 vanilla 状态复位搬进 modern 的 `WorldRendererMixin`，本地需另找复位点 |
| 4 | `7e5e9d7ec` | Remove the FogService SPI (#43) | 2026-09-25 | 待同步 | C07 | 移除 FogService SPI 并新增 `ChunkShaderEnvironment`；本地仍保留 `src/main/resources/META-INF/services/dhj.embeddedt.embeddium.impl.render.chunk.fog.FogService`，且不存在 `ChunkShaderEnvironment` |
| 5 | `64158a451` | Fix workflow [skip ci] | 2026-09-25 | 不适用 | — | `release.yaml` 工作流修正，属 CI 文件 |
| 6 | `3345e59fa` | Bump base to 2.5 [skip ci] | 2026-09-25 | 不适用 | — | `gradle.properties` 的 base 版本提升 |
| 7 | `9b661b70e` | Opt publish task out of the configuration cache | 2026-09-25 | 不适用 | — | `build.gradle.kts` 的配置缓存选项，属构建治理 |
| 8 | `26ab21758` | Route the fast block renderer through BlockRendererDispatcher | 2026-10-01 | 待同步 | C08 | 快速方块渲染器改走 `BlockRendererDispatcher`；本地 `VintageBlockRenderer.renderBlock` 仍自行 `getActualState` 加 `shapes.getModelForState`，无 `ChunkBufferBuilder` 与 dispatcher mixin |
| 9 | `6150f9694` | Fix Oculus ShaderChunkRenderer init mixin after FogService constructor change | 2026-10-01 | 不适用 | — | 修的是 modern 源集里 Oculus 的 `MixinShaderChunkRenderer`，本地无该 Mixin |
| 10 | `48eb54f5c` | Don't treat vertices past the far plane as needing a near clip | 2026-10-01 | 待同步 | C01 | 远平面外顶点不再判定需要近平面裁剪；本地 `AbstractRasterizer.needsNearClip` 仍是 `z > 0 && z <= w` 判定，`PerspectiveRasterizer` 第 103 行的接受条件同样带 `tz <= w` |
| 11 | `7818b3317` | Relax SectionLattice restrictions on world height | 2026-10-11 | 待同步 | C05 | 放宽 `SectionLattice` 的世界高度限制（Y 也参与窗口化）；本地 `SectionLattice` 的 Y 维固定为全世界高度，`dimY` 超 `MAX_DIM` 直接抛异常 |
| 12 | `ff6331466` | Add project docs | 2026-10-13 | 不适用 | — | 新增 `CLAUDE.md`，属治理文件 |
| 13 | `d94ec1e59` | Avoid carrying the transparency level enum through pixel loops | 2026-10-13 | 已有等价 | — | 本地 `MixinTextureAtlasSprite.embeddium$processTransparentImages` 已用 ordinal 累积替代逐像素 `chooseNextLevel` 调用（`src/main/java/com/dhj/actinium/mixin/vintage/features/mipmaps/MixinTextureAtlasSprite.java:73` 起），并保留 `chooseNextLevel` 于 `SpriteTransparencyLevel`；上游新增的 `SpriteTransparencyLevel.of(boolean, boolean)` 静态 helper 是可评估项，本次不引入 |
| 14 | `d3946af4e` | Move the shadow window update onto the search thread | 2026-10-22 | 分 hunk 同步 | C05 | 只取并发语义：窗口准备不得与执行中的搜索重叠。本地已用 `SectionGraph.prepareWindow`（`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/SectionGraph.java:72`）加 `assertSearchNotRunning` 在提交搜索前统一准备所有视口，比上游把 `ensureWindowCovers` 移进搜索线程更早；不采用后者 |
| 15 | `c2823be43` | Remap the shadow search roots when the lattice window moves | 2026-10-22 | 待同步 | C05 | 窗口移动后重映射阴影搜索根；本地 `SectionLattice` 无 `remapVisibleCells`，`visibleCells` 仅在 `OcclusionCuller` 写入后由 `ShadowOcclusionCuller` 直接读取 |
| 16 | `625b6f40f` | Stop translucent sorting hanging on non-finite quads | 2026-10-22 | 待同步 | C01 | 非有限四边形的半透明排序；本地 `QuadSet.union()` 未跳过退化四边形，`TranslucentQuadRecorder` 无 `Float.isFinite` 归零分支 |
| 17 | `86d4cfe7b` | Align geometry arena elements to whole primitives | 2026-10-27 | 待同步 | C03 | 几何 arena 元素按整图元对齐；本地 `RenderRegionManager` 无 `computeCommonVertexStride`（该文件仅剩 staging 与上传流程），`MathUtil` 无 `gcd`/`lcm` |
| 18 | `b34b76ae2` | [modern] treat blended equal-depth render types as decals | 2026-10-27 | 不适用 | — | 改的是 `modern/src/main/batching_java` 的 Iris 渲染类型，本地无 modern 源集 |
| 19 | `c317f81a9` | [modern] give fixed-buffer render types their own builders | 2026-10-27 | 不适用 | — | `modern/src/main/batching_java` 的固定缓冲渲染类型构建器；本地 `FullyBufferedMultiBufferSource`、`SegmentedBufferBuilder` 不存在 |
| 20 | `e79964174` | GTNH build: common only, publish to GTNH maven | 2026-09-08 | 不适用 | — | GTNH 构建发布配置与 CI 工作流 |
| 21 | `0d003b82f` | Allow overriding the LWJGL service with a system property | 2026-09-08 | 待同步 | C02 | `LWJGLServiceProvider.createInstance` 支持系统属性覆盖；本地 `src/lwjglCommon/java/com/mitchej123/lwjgl/LWJGLServiceProvider.java` 直接 `constructInstance` 固定类名，无 `System.getProperty` 读取 |
| 22 | `88e905ca5` | Upload indirect command buffers through DRAW_INDIRECT_BUFFER | 2026-09-08 | 待同步 | C02 | 间接命令缓冲改经 `DRAW_INDIRECT_BUFFER` 上传；本地 `CommandList` 只有 5 参 `uploadData`（无 `GlBufferTarget` 重载），`IndirectMultiDrawBatch.upload` 走 `ARRAY_BUFFER` |
| 23 | `588a269e2` | Finish the render pass timer on exception | 2026-09-08 | 待同步 | C02 | 渲染 pass 计时在异常路径也闭合；本地 `RenderSectionManager.renderLayer` 的 `timer.finishProfiling()` 在 `chunkRenderer.render` 之后直写，无 `try/finally` |
| 24 | `170bb7d14` | Make MultiDrawBatch substitutable | 2026-09-08 | 待同步 | C02 | `MultiDrawBatch` 可替换；本地 `DirectMultiDrawBatch` 声明为 `final`，且无 `MultiDrawBatchFactory` |
| 25 | `1ce0ce4eb` | Expose device memory stats | 2026-09-08 | 待同步 | C02 | 暴露设备显存统计；本地 `RenderSectionManager` 只有 `getDebugStrings`，无 `DeviceMemoryStats` 类型与 `getDeviceMemoryStats` 访问点 |
| 26 | `d7a146f03` | Bump visibility | 2026-09-08 | 分 hunk 同步 | C02 | 只取通用可见性提升与签名对齐：`AoFaceData` 由 `class` 提升为 `public class`、`BatchAssembler` 的 `MASK_NOT_UNIFORM`/`uniformCullMask`/`packRuns` 提升为 `public`、`SimpleWorldRenderer` 的 `prepareFrame` 参数调整、`SimpleFrustum` 新增 `getFrustumIntersection()` 访问器（向上暴露内部 `FrustumIntersection`）。本地 `AoFaceData` 仍是包私有类（`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/model/light/smooth/AoFaceData.java:9`），`BatchAssembler` 三个成员仍为 `private`（第 25、278、295 行），`SimpleFrustum`（同包）只有 `testAab` 与 `intersectAab`，无 `getFrustumIntersection()`；`SimpleFrustum.intersectAab` 本地已有等价实现，不重复引入。排除清单：无平台专用文件，该提交 4 个文件全部在 `common/`，`jmh` 侧无改动 |
| 27 | `57ca70de4` | Add renderSectionBlockEntities hook | 2026-09-08 | 待同步 | C02 | 新增 `renderSectionBlockEntities` 可覆写钩子；本地 `SimpleWorldRenderer` 只有 `renderBlockEntityList` 与 `renderGlobalBlockEntityList`，逐区块循环直接调用 `renderBlockEntityList` |
| 28 | `ba0981433` | Expose render list regions by index | 2026-09-08 | 待同步 | C02 | 按索引暴露渲染列表；本地 `ChunkRenderListIterable` 只有 `iterator`、`getNumRegions`、`hasPass`，无 `getRegion(int)`，`SortedRenderLists` 亦无 |
| 29 | `77aec052d` | Let subclasses gate the shadow-pass search | 2026-09-08 | 待同步 | C06 | 子类可关闭阴影搜索；本地 `RenderSectionManager.updateForShadowPass` 无 `canSubmitShadowGraphSearch`/`submitShadowGraphSearch` 拆分 |
| 30 | `14cb5f7f0` | Upload build results in the shadow pass instead of joining its searches | 2026-09-27 | 待同步 | C06 | 阴影 pass 内只上传不等待；本地无 `blockingResultsPending`/`hasBlockingResultsPending`，`scheduleTreeSort` 仍写入 `getCurrentRenderListManager()` 的重建队列 |
| 31 | `f33e50ce0` | Test that a non-finite quad can't stall translucent sorting | 2026-10-21 | 待同步 | C01 | 非有限四边形不阻塞半透明排序的测试；本地无 `TranslucentQuadRecorderTest`，该测试按 Actinium 现有结构落在根项目 `src/test`，不需要为 `celeritas-common` 新建 test source set |
| 32 | `408881f27` | Test shadow search roots across a lattice window move | 2026-10-21 | 待同步 | C05 | 窗口移动后阴影搜索根的测试；本地无 `SectionLatticeWindowTest` 与 `TestRegions`，按 Actinium 现有结构落在根项目 `src/test` |
| 33 | `9a731e9b6` | Bump GTNH build to 2.5.13 | 2026-10-21 | 不适用 | — | `common/build.gradle` 的 GTNH 构建版本号提升 |
| 34 | `69e91690d` | Reuse the indirect command buffer when a region batch is rebuilt | 2026-10-25 | 待同步 | C04 | 区域批次重建时复用间接命令缓冲；本地无 `BatchBufferRecycler`，`IndirectMultiDrawBatch.bufferObject` 为 `final` 且在构造时创建 |
| 35 | `6bf6a433f` | Key cached batches by view | 2026-10-28 | 待同步 | C04 | 缓存批次按视口分键；本地 `SectionRenderDataStorage.BatchCacheParams` 只有 `useBlockFaceCulling` 一个字段，无 `view` 与 `of(...)` 工厂 |
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
| `2334caab0` | Allow multiple vertex formats to share the same arena | 2026-09-07 | 待同步 | C03 | 该提交触及 10 个文件，全部在 `common/`，按上游 patch 逐文件核对：**本地已有等价**：`GlBufferArena`（`elementsFor` 向上取整，`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/arena/GlBufferArena.java:337`）。**待取**：`MathUtil`（新增 `gcd` 与 `lcm`，本地无这两个方法）；`RenderRegionManager`（新增字段 `commonVertexStride` 与 `computeCommonVertexStride`，实现是对 `getAllKnownRenderPasses()` 各 pass 的 `vertexType().getVertexFormat().getStride()` 取 `lcm`，不乘顶点数；本地该类只剩 staging 与上传流程）；`RenderRegion`（构造器新增 `commonVertexStride` 参数并按其校验每个 pass 的 stride 能整除，同时按 `commonVertexStride / stride` 构造 `SectionRenderDataStorage`）；`TerrainRenderPass`（新增 `record TessellationKey(GlVertexFormat vertexFormat, ChunkPrimitiveType primitiveType, boolean sorted)` 与 `tessellationKey` 字段）；`SectionRenderDataStorage`（新增 `verticesPerArenaElement` 字段，构造器第三参接收「公共 stride ÷ 本 pass stride」，顶点偏移按 `allocation.getOffset() * verticesPerArenaElement` 计算）；`DefaultChunkRenderer`（按 `TerrainRenderPass.TessellationKey` 缓存与更新 `GlTessellation`，替换原有的按格式分桶）；`RenderSectionManager`（区域分配路径的配套签名）；`CachedBatch`（图元对齐后的批次描述）；`BatchAssembler`（按图元对齐的 run 拆解）。 |

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
