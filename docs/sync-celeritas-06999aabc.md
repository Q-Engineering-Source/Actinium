# 上游 celeritas 同步评估（5c68ed4cb → 06999aabc）与历史未收编项复审

> 状态：**已完成并提交**（分支 `sync/celeritas-06999aabc`）。评估结论与落地结果见下方「完成记录」，
> 全部批次逐次通过 `./gradlew check --no-daemon`。
>
> 评估对象：
> 1. 上游 stonecutter 分支 `5c68ed4cb..06999aabc` 共 **14 个提交**（2026-09-08 → 2026-09-20）；
> 2. 历次同步中判定"不适用 / 暂缓 / 后续可选"的 **13 组项**（`6f3cb342`、`b8c1079a`、`376d8199`、
>    `7190f87d`、`fe57c60fa`、`5c68ed4cb` 各轮遗留）；
> 3. 旁支 `origin/shader-refactor`（48 个非 merge 提交）——此前从未被任何一轮同步评估过。
>
> 结论速览：14 个新提交 **6 个收编**、8 个不适用；历史项 **11 组维持现状**（其中 1 组新增可做子项、
> 1 组遗留疑点被证伪）、**3 组可做**（1 个真实资源泄漏、1 行构建配置、1 份待恢复的基准设施）；
> `shader-refactor` 分支**最小必做集为空集**（其内容早已进入 stonecutter，且本地已有等价物）。

## 完成记录

| 批次 | 上游 | 本地 commit | 内容 |
|---|---|---|---|
| 1 | `6140755e8` | `723215e0` | `MappedStagingBuffer.delete()` 排空未完成 fence（真实 GLsync 泄漏），删除死代码 `GlFence.sync()` |
| 1 | `752696e73` | `587554ce` | ColorMixer 截断→四舍五入；新增 `ColorMixerTest`(5)、`ChunkColorWriterTest`(3) |
| 1 | `5c68ed4cb` 决策 7 | `3cadb8ad` | 开启 Gradle build cache；`jar` 显式声明 `finalVersion` 输入（manifest 写在 `doFirst`，版本不在指纹内） |
| 2 | `727b464d0` | `0c2ff199` | 自适应光栅预算 `RasterBudget` + bitraster 缓冲复用；两处本地化；`RasterBudgetTest`(16) |
| 3 | `671ecaffc` | `9b797a3b` + `b386da63` | 半透明排序 v3：先加数据模型，再切换生产/消费端、删除 `TranslucentQuadAnalyzer`、接入切平面调度；`SortStateCompactionTest`(6) |
| 3 | `2edc3d211` | 随 `9b797a3b` | `OVERLAP_EPSILON` 在 C1 一次写入（1.12 流体侧面内缩导致的瀑布错序） |
| 4 | 新增独立项 | `6df86f59` | fluid 通道共享解包（`ClonedChunkSection` 惰性解包 + `WorldSlice` 别名） |
| 4 | `f3c5642e` 线 | `193ac440` | 恢复 JMH harness（取上游 HEAD 的 bench 源码 + 本地包名与 `MultiDrawMode` 适配），保全 tag `jmh-harness-recovered` |
| 5 | `06999aabc` | `e6d8fe66` | compact vertex 位域重平衡（位置 21bit / UV 18bit，stride 不变）；`ChunkVertexFormatTest` 补整数属性断言 |

小计：10 个提交，62 个文件变更（+5929/−638）。

### 实施中的偏差与判断

- **批次 3 未按 C1–C4 四次提交，改为两次**（C1 + 完整移植）。原因是上游的 v3 是一个自洽整体：切平面调度与
  新排序状态、`PackedSectionMetadata` 位宽、`ChunkRenderList` 桶是同一批改动；若按 C2 只切生产/消费端，
  就得自己发明一个上游从未存在、无法对照验证的"每次移动都重排"中间态。风险更高的做法留下了更少的验证依据，
  因此合并为一次完整移植。
- **`RenderSectionManager` 用三方合并落地**（`git merge-file`：本地 = A，上游父 = O，上游终态 = B），
  4 处冲突逐一按语义解决：保留本地 `frameClock` 归一化、HBM-CE 的 `CameraTransform` getfield 内联契约、
  抽出的 `releaseBuildCancellationToken` 辅助；采用上游的 `updateTranslucencyInfo(..., latestBuild)` 签名。
- **`RenderSection.updateCachedContextDataFlags()` 提前引入了 `NEEDS_DYNAMIC_SORT`**（原计划在 C3），
  因为它与 `setTranslucencySortStates` 同属状态模型；位宽扩张随之一并落地，不存在"设了位但被掩码丢掉"的中间态。
- **JMH bench 源码改取上游 HEAD 而非被回退的那份**：旧副本引用已被 multidraw 重构删除的 `MultiDrawEmitter`，
  上游 HEAD 版本才是与当前 batch API 一致的版本；本地只保留包名与 `MultiDrawMode` 两处适配。
- **未做单元测试的项**：`MappedStagingBuffer` fence 排空（需要 GL 上下文）、fluid 别名（构造
  `ClonedChunkSection` 需要真实 World/Chunk），两者都留给实机验证，理由与验证方式见 §7。


## 0. 基线与覆盖核验（机械比对，非推断）

- 上游镜像 `D:/Code/celeritas-mirror` 的 `stonecutter` HEAD = `06999aabc`，与
  `git.taumc.org/embeddedt/celeritas` 的 `stonecutter` 分支 HEAD 逐字节一致（`git ls-remote` 比对）。
- Actinium `main` 的 Celeritas 代码在内容上等于 `5c68ed4cb` 基线：上一轮 `docs/sync-celeritas-5c68ed4cb.md`
  记录的 7 个代码提交逐项落地已确认（`ClonedChunkSectionCache.MAX_CACHE_SIZE=2048`、`cleanup()` 空表早退、
  `ENABLE_ADAPTIVE_SCHEDULING=false`、region revision + sprite ticker region 缓存、`GlBufferArena.elementsFor`、
  `ChunkFogMode.EXP`）。滞留分支 `sync/celeritas-fe57c60fa` 的 7 个代码提交内容同样已在 main 中。
- **文件集合级覆盖核验**（归一化包前缀后比对）：
  - `5c68ed4cb`：上游 `common/` 282 个 Java 文件，本地仅 `shaders/CeleritasShaders.java` 无同名对应物
    （本地等价物是 `shader/` 模块的 `IrisCeleritasShaderProvider`，属架构分工差异）。
  - `06999aabc`：上游 `common/` 288 个文件，本地缺的 7 个恰好是排序 v3 的 6 个新文件 + `RasterBudget.java`，
    与"新增 7 个文件、删除 `TranslucentQuadAnalyzer.java`"（282+7−1=288）完全吻合。
  - ⇒ **此前的同步在文件层面没有遗漏；本轮的新增面就是下面 6 项**。
- 上游 `common/src/test` 在基线处为空，HEAD 只有 `RasterBudgetTest.java`；本地测试面远大于上游，属本地自建。

## 1. 新提交结论总表（5c68ed4cb → 06999aabc）

> 下表是评估时的结论；实际落地情况与本地 commit 见上方「完成记录」。

| # | 上游 commit | 内容 | 结论 | 风险 | 优先级 |
|---|---|---|---|---|---|
| 1 | `6140755e8` | 删除 staging buffer 时清理未完成 fence | **收编** | 极低 | **P0** |
| 2 | `752696e73` | ColorMixer 截断改四舍五入 | **收编** | 低 | **P1** |
| 3 | `727b464d0` | 自适应光栅剔除预算 `RasterBudget`（#41） | **收编**（需 2 处本地化） | 中低 | **P1** |
| 4 | `671ecaffc` | 半透明排序 v3（#42） | **收编**（整包，4 次提交） | 高 | **P1**（大件） |
| 5 | `2edc3d211` | v3 的小悬垂容差修正 | **随 #4 收编（必带）** | 随 v3 | 随 v3 |
| 6 | `06999aabc` | compact vertex format 位域重平衡 | **收编** | 中 | P2 |
| 7 | `2b742f6ac` | [postmodern] 卸块清 block-data flag | 不适用（本地等价覆盖更宽） | — | 登记约束 |
| 8 | `84e64cea1` | [postmodern] `needsUpdate` 标脏图 | 不适用（本地同一注入已在位） | — | 登记约束 |
| 9 | `9fd3872b8` | Java 8 `findResource` 尾斜杠 bug | 不适用（无动态 mixin 扫描） | — | 不做 |
| 10 | `7b89abb0a` | 1.16 BufferBuilder mixin 目标写错 | 不适用（无对应结构） | — | 不做 |
| 11 | `6ddc90d9d` | batching 管线 decal 顺序 | 不适用（无 batching 管线） | — | 不做 |
| 12 | `d71d0daaf` | 流体面 flat 光照对齐 modern vanilla | 不适用（语义不同源，移植即回归） | — | 不做 |
| 13 | `d5d819b10` | README credits | 不适用（纯文案） | — | 不做 |
| 14 | `db3790215` | 上游 stonecutter/NFRT 构建修复 | 不适用（本地自有工具链） | — | 不做 |

## 2. 收编项详述

### 2.1 `6140755e8` — MappedStagingBuffer 的 fence 泄漏（P0）

- 上游：`delete()` 末尾排空 `fencedRegions` 并 `fence().delete()`；同时删除已无调用方的
  `GlFence.sync()/sync(long)`。
- 本地缺陷真实存在：`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/arena/staging/MappedStagingBuffer.java`
  的 `delete()`（`:146-150`）只做 `mappedBuffer.delete + fallbackStagingBuffer.delete + pendingCopies.clear`，
  而在飞的 fence 只在 `flip()`（`:154-167`）完成时被删除 ⇒ 每次 renderer teardown（退出世界、
  渲染距离变化、选项触发的 renderer reload）泄漏一批 GL sync object。调用链：
  `RenderSectionManager.destroy():882` → `RenderRegionManager.delete():214` → `stagingBuffer.delete()`。
- 本地可达性：`MappedStagingBuffer.isSupported()` 按 `BufferStorageFunctions` 判定，
  GL 4.4+/`ARB_buffer_storage`（Cleanroom 常态）默认走该实现；`SodiumGameOptions.useAdvancedStagingBuffers:91`
  是声明后无引用的死开关。
- 同类路径排查：`FallbackStagingBuffer` 无 fence；glsm 的 `PersistentStreamingBuffer.destroy():167-170`
  已做同样的排空；`CommandList.createFence()` 的唯一调用方就是 `MappedStagingBuffer`。
  ⇒ 这是本地唯一的 fence 泄漏点。
- 落地：`MappedStagingBuffer` +4 行；可选删除 celeritas 侧 `impl/gl/sync/GlFence.java:36-52` 的死代码。
  **注意**：`glsm/src/main/java/com/gtnewhorizons/angelica/glsm/streaming/GlFence.java` 有同名 `sync()`，
  它**正在被使用**（`PersistentStreamingBuffer.syncOldest():207`），不可误删。
- 验证：`./gradlew check --no-daemon`；无法单测（`createFence()` 直连 LWJGL，需 GL 上下文）。
  实机：光影开/关各一轮，飞行触发大量 flush，然后进出世界 10–20 次，用 RenderDoc/GL debug 观察
  sync object 数是否回落。这些 fence 走 LWJGL 直连、**绕过 glsm**，`GpuCheckpointTracker`/`RedirectorDebug`
  看不到；无 GL 工具时可在 `delete()` 内临时计数（验证后移除）。

### 2.2 `752696e73` — 颜色混合四舍五入（P1）

- 上游：`mix` 每通道 `+0x00800080` 后 `>>8`；`mul`/`mulSingleWithoutAlpha` 每通道 `+0xFF` 后 `>>8`。
- 原缺陷：`a=b=255` 旧式得 `65025>>8 = 254`，即"乘白色把每通道压暗一级"。对 AO 而言整片地形被无谓
  压暗 ≈1/255（0.39%），并在 AO 过渡处多一道向下取整台阶。
- 本地消费者只有一处：`celeritas-common/.../impl/render/chunk/ChunkColorWriter.java:16`（EMBEDDIUM 分支）
  ← `VintageBlockRenderer.writeGeometry()`，顶点色在编译期烘焙，**开/关光影都走这条**。
  `ColorMixer.mix/mul` 本地无调用方（上游调用方全在 `modern/` 源集）。
- 第二份 `GTNHLib/src/main/java/com/gtnewhorizon/gtnhlib/client/renderer/cel/api/util/ColorMixer.java`
  是全仓无引用的陈旧副本，建议一并同步以保持逐字一致，但它不影响行为。
- 验证：`./gradlew check --no-daemon`；建议新增 `ChunkColorWriterTest`，直接断言
  `ChunkColorWriter.EMBEDDIUM.writeColor(0xFFFFFFFF, 1.0f) == 0xFFFFFFFF`（当前返回 `0xFEFEFEFE`）；
  实机看密草/藤蔓的 AO 梯度与整体亮度（光影开/关各一次）。

### 2.3 `727b464d0` — 自适应光栅剔除预算（P1，需 2 处本地化）

- 机制：为光栅剔除设一个"平方区块距离上限" `limit`，内层走光栅测试、外层直接判可见（保守、不新增误剔除）。
  信号来自搜索自身（`yield = culled/tested` 的 Q16、可见 section 计数、实际测试数），EMA α=1/4；
  `yieldEMA ≥ 15% 且瞬时 ≥ 15%` 翻倍、`EMA ≤ 5%` 折半、其间保持；每 64 帧一次不带预算的 probe 用
  "多剔掉的 section 数 vs 多付的测试数"决定是否直接跳到上限。常量：FLOOR=12、MIN_SAMPLE=16、
  CHEAP_PASS=32、PROBE_PERIOD=64。
- 收益：只落在剔除耗时（上游称低收益地形约为旧 overhead 的 1/5；高收益地形与 always-on 等价）。
  第二条机制是 `testable = 4×limit` 被用作 `prepareScene` 的缓冲区尺寸（FLOOR 时约 127 方块而非整个渲染
  距离），这也是 `AbstractRasterizer.resize` 必须改为"只增不减地复用数组"的原因。
- 本地对照：`RasterOccluder`、`occlusion/geometry/*`、`grondag/bitraster/*` **与上游父提交逐字节等同**；
  `OcclusionCuller` 有本地分叉（perf 计时 seam、`isWithinRenderDistance` 改纯水平圆、HBM-CE `OcclusionNode` seam）。
- **必带改动**：`visibleCount` 记账要移出 `visibleCells != null` 判定（本地
  `occlusion/OcclusionCuller.java:349`；`SectionLattice.java:530` 传 `recordVisible = shadowCuller != null`）。
  不修则无光影时 `visibleCount` 恒为 0，probe 每 64 帧误判"划算"，`limit` 永久停在 ceiling ⇒
  退化为 always-on（仅性能，不影响画面）。
- **本地化**：上游 `maxSquaredChunkDistance` 假设"dy 也被 searchDistance 截断"，而本地已是纯水平圆
  （`OcclusionCullerRenderDistanceTest` 锁定 dy=63 区块仍 in range）⇒ ceiling 会低估，在"低渲染距离 +
  高塔 / Depths Update 扩展高度"下提前满预算、丢掉收益。应改为
  `radius² + max(camSecY − minSecY, maxSecY − camSecY)²`（`minSectionY/maxSectionY` 已在 `OcclusionCuller`）。
- 无新增配置项：上游只改了两处 tooltip。建议跟随上游（`RASTER_OCCLUSION_CULLING` 开启即生效），
  不新增独立开关（避免 OptionIdentifier + GUI + 3 份语言 + compatBridge 镜像的成本）；
  A/B 用一次性 `RasterBudget.pin(UNBOUNDED)` 临时实验。
- bench（`occlusion/bench/*`、`common/build.gradle`、lang）**跳过**：本地无 jmh sourceSet，
  `celeritas-common/build.gradle` 仅 8 行。
- 测试：`RasterBudgetTest`（10 个用例）可原样移植到
  `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/`（JUnit 5，唯一外部引用是本地已有的
  `RasterOccluder.NEAR_SQUARED_CHUNK_DIST`）；建议加 1 条 ceiling 含垂直跨度的本地断言。
- 验证：实机开 "Render Timing Debug" + `-Dbitraster.stats=true`，看 `chunk.raster` 段的
  **`buffer=WxH`（预算是否生效最直接的信号）**与 `testedPerSec`；注意自适应下分母变化，
  `occludedPerSec(x%)` 的占比会自然下降，不能单独用占比判成败。场景：平原静止（应沉到 FLOOR）、
  洞穴/峡谷（probe 后跳满预算）、长距离飞行与传送（≤0.5s 回落）、扩展高度、光影开/关、HBM-CE 同场。
- 已知（**非本 commit 引入**）隐患：本地水平圆距离下，`dx≈dz≈0` 且 `|dy|` 极大的 section 在满预算态会被
  测试/绘制，而覆盖缓冲只按水平半径定尺寸，理论上有"细缝闭合→误剔除"窗口。自适应在低 limit 时反而缩小
  暴露面。建议单独立 issue。

### 2.4 `671ecaffc` + `2edc3d211` — 半透明排序 v3（P1，大件）

- 上游把排序从 v2 的"中心点距离 + 三级分类"换成 `TranslucentQuadRecorder → QuadSet`（记 AABB bounds /
  法线 / facing）+ `sealed SortState`（`None`/`Static`/`Dynamic`/`PartitionTree`，`SortState.Resortable`
  才是可重排态）+ 六级 cheapest-first 分类（共面 / 全退化 / AABB 外壳朝外 → NONE；全平行 →
  Static；≤2048 且有向图无环 → 拓扑序 Static；否则 `PartitionTree.build`；兜底 Dynamic），
  并把重排触发从"每移动一次"改为"相机跨过切平面"（`CutPlaneIndex` 分桶），索引改为
  `QuadPrimitiveType.BufferSink` 流式直写 native index buffer。
- 修掉的实际缺陷：① 中心点距离排序会把大 quad 排到它实际遮挡的小 quad 之前（正确性问题）；
  ② v2 只要法线不一致就整段 DYNAMIC，导致"本可静态排序"的几何每次移动都全量重排 + 重建索引；
  ③ 本地 `ChunkBuilderSortTask` 的 `GlBufferSegment len <= 0` 崩溃被类型化结构性消除
  （map 收窄为 `Map<TerrainRenderPass, SortState.Resortable>`）。
- 移植面：v3 的父提交是 `2b742f6ac`，`5c68ed4cb..2b742f6ac` 的 9 个中间提交与 v3 的 23 个文件
  **交集为空**；上游 `getCurrentRenderListManager()`/`shadowRenderListManager`/`isInShadowPass()` 在基线
  已存在，**不是本地分叉**。15 个已存在文件本地 == 父提交（仅 javadoc / 命名空间差异），6 个新文件
  本地不存在（约 1280 行），1 个待删（`TranslucentQuadAnalyzer`，244 行）。
- **唯一硬阻碍**：`RenderSectionManager.update(Viewport,int,boolean)` 的 HBM-CE 契约。v3 把相机记账抽到
  `updateCameraPosition()`，但本地必须把 `CameraTransform.x/y/z` 的 getfield 内联保留在 `update()` 体内
  （`HbmCameraRedirectContractTest` 用 ASM 断言；HBM 的 `@Redirect(require>=1)` 找不到目标会
  `InjectionError → NoClassDefFoundError`）。改法是把 `if (!shadowPassRanThisFrame)` 门与
  `previousCameraPosition` 捕获内联展开（约 6 行），不能委托。
- `2edc3d211` **必须同批带上**：它只改 v3 新建的 `QuadSet`/`PartitionTree`，新增 `OVERLAP_EPSILON = 0.008`
  以容忍"原版 1.12 流体侧面内缩 0.001 但保留整宽"造成的瀑布错序——恰是 1.12 专属几何。
- 落地拆分（每步过 `check`）：C1 纯新增包（含 `OVERLAP_EPSILON`，无可调用者）→ C2 切换生产/消费端并删
  `TranslucentQuadAnalyzer`（暂不引入切平面调度，TREE 先沿用"每移动一次重排"，正确但无性能收益）→
  C3 `NEEDS_DYNAMIC_SORT` 位 + `PackedSectionMetadata` 位平移 + `CutPlaneIndex` 调度 + **HBM 适配** →
  C4 测试重写 + 文档。总计约 21 文件、+1600/−500 行。
- 测试：删除 `TranslucentQuadAnalyzerSortStateTest`（被测类已删），改写为针对新类型的真实逻辑测试
  （`compactForStorage()` 语义、`analyze()` 分级、`PartitionTree.order()` 是 `0..n-1` 的排列）。
- 主要风险：`PackedSectionMetadata` 位平移（本地无额外的 occlusion/raster 位，平移是干净的，但
  `GRAPH_INPUT_MASK` 含 visuals ⇒ NEEDS_DYNAMIC_SORT 变化会多一次图搜索）；新增的 fail-fast 断言
  （索引缓冲容量必须精确相等、tree quad 数必须一致）在多 pass 合并 + MultiDrawMode 路径下需确认恒等；
  `PartitionTree.build` 发生在 meshing 工作线程（2048 上限的 DFS 是 O(n²)）；影子 pass 的
  `previousCameraPosition` 语义。
- 验证：实机重点看瀑布（`2edc3d211` 场景）、玻璃墙贴大冰面（大 quad 遮挡小 quad）、水下湖边缘、
  跨 section 与长距离传送（观察调试 overlay 的 `Sorting: NONE/STATIC/TREE/DYNAMIC` 与
  `Tree Sort: N planes, N trig/s` 是否只在跨切平面时跳）、光影 + 阴影 pass、DH 开关、HBM-CE 在场加载不崩。

### 2.5 `06999aabc` — compact vertex format 位域重平衡（P2）

- 上游：24 字节 stride 与偏移 0/8/12/16/20 全不变，把位置 16→**21 bit**（xyz 高 16 位仍在 `a_PosId.xyz`，
  低 5 位塞进原 material/section 的 `a_PosId.w`）、贴图 16→**18 bit**（低 2 位塞进 draw-params 字 bit4-5）、
  material 8→**4 bit**、light 并入同一 int32 高 16 位；`a_TexCoord` 改整数属性、`a_LightCoord` 改
  `UNSIGNED_INT ×1`。量化精度从 1/2048 方块提升到 1/65536，UV 从 1/32768 提升到 1/131072。
- 本地两处文件与上游父提交**逐行等价**（仅命名空间差异、本地 `USE_BILINEAR_CORRECTION` 的 4 处 `#ifdef`
  与一个多余 import）：`celeritas-common/.../vertex/format/impl/CompactChunkVertex.java`、
  `src/main/resources/assets/actinium/shaders/include/chunk_vertex.glsl`。
- 与 #126 **无冲突**：`ChunkMeshBufferBuilder` 的 scratch 复用与 stride 参数无关；stride/偏移不变；
  顶点缓冲不落盘（不涉及存档）；`decodePosition(short→int)` 本地与上游都零调用方；
  `VanillaLikeChunkVertex` 本就用同一套 `encodeDrawParameters/encodeLight` 打包方案（本 commit 实质是把
  compact 与既有方案统一）。
- 影响面：只有"关光影 + `performance.useCompactVertexFormat`（默认 true）"才走 COMPACT 分支；
  Iris 路径只会用 VANILLA_LIKE/extended（`ExtendedChunkVertexType.BASE_TYPE = VANILLA_LIKE`，不定义
  `USE_VERTEX_COMPRESSION`），因此压缩分支是**关光影专属路径**。
- 风险：中。位域写错通常表现为几何乱码 / 错误 draw 段 / 半透明错排，而不是细微抖动。
  验证：(a) 关光影 + 默认 compact + 渲染距离 ≥12，看铁轨/栅栏/红石/睡莲/花盆无抖动穿洞、region 与区块
  边界无裂缝、草/树叶/水染色与 AO 正常、树叶 alpha cutoff 正常；(b) 切 `useCompactVertexFormat=false`
  对比；(c) 再开光影（Complementary/BLS）确认扩展格式路径无回归。
  建议顺带扩展 `ChunkVertexFormatTest`（现仅断言 stride / `a_RdhFactor`）。

## 3. 不适用项的凭证

- **`2b742f6ac` / `84e64cea1`（postmodern）**：该 source set 对应 **Minecraft ≥ 1.21.11**
  （`buildSrc/src/main/kotlin/celeritas.platform-conventions.gradle.kts:153-160` 的
  `compare(current.version, "1.21.11") >= 0`），与 1.12.2 无继承关系。且这两类缺陷本地已有等价或更宽的覆盖：
  - 卸块清 flag：`src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinClientChunkManager.java:27-44`
    在 `loadChunk` / `unloadChunk`（`Chunk.onUnload()` 之后）双向注入 `FLAG_ALL`，并且 `tick` 每客户端 tick
    调 `ChunkTracker.reconcile` 兜底（`ChunkTrackerReconcileTest` 锁定）；
  - `needsUpdate` 标脏图：`MixinRenderGlobal.java:131-134` 注入 `setDisplayListEntitiesDirty` →
    `SimpleWorldRenderer.scheduleTerrainUpdate()` → `RenderSectionManager.markGraphDirty()`；另有
    `prepareFrame` 的 `CameraState` 脏检查与地形 pass 每帧 `startGraphUpdate`。
  - ⇒ 只登记为**"勿删除既有注入"的回归约束**，不改代码。可选后续（非本轮）：shadow-pass 路径的图更新仍以
    `isNeedsUpdate()` 门控，而 `CameraState`（`ActiniumWorldRenderer.captureCameraState`）不含 FOV/宽高比；
    若将来出现"开光影 + 站定改 FOV/拉伸窗口后远处区块不出现"的报障，最小修复点在那里。
- **`9fd3872b8`**：本地 mixin 配置是静态白名单（`mixins/MixinEarly.java:19-24`），无"按包路径扫描 mod 文件"
  的机制，全仓 `findResource` 0 命中；运行环境是 Java 25 + Cleanroom + MixinBooter，Java 8 的 `Path`
  语义问题不会复现。
- **`7b89abb0a`**：1.12.2 的 `net.minecraft.client.renderer.BufferBuilder` 无 `ensureCapacity`/`MemoryTracker`；
  本地 `BufferBuilderMixin` 是完全不同的形态（`@Overwrite` 写方法 + `growBuffer` TAIL 刷新直接地址），
  且本地不做几何扩容改写。
- **`6ddc90d9d`**：本地无 `FullyBufferedMultiBufferSource` / `TransparencyType` / `batchedentityrendering`
  （唯一 `MultiBufferSource` 命中是 Iris 1.16 时代的粒子相位类），1.12.2 也没有分层 RenderType 体系。
- **`d71d0daaf`**：本地没有 Celeritas 流体渲染器，1.12.2 的水/岩浆是 `EnumBlockRenderType.LIQUID` →
  vanilla `BlockFluidRenderer`（"上=自身、下=下方块、侧=水平邻位"），Forge `BlockFluidBase` 类 mod 流体走
  `VintageBlockRenderer` 的 `FlatLightPipeline`（face-offset 语义），两者都与 1.12.2 vanilla 一致；
  上游的 `max(self, above)` 是 modern 专属语义，**移植会造成 1.12.2 光照偏离 vanilla 的回归**。
- **`d5d819b10` / `db3790215`**：纯上游 README 致谢与 stonecutter/MDG/NFRT 构建修复，本地无对应对象。

## 4. 旁支 `origin/shader-refactor`（历史从未评估）— 无需补同步

### 4.1 前提更正：`git cherry` 的标记语义

`git cherry stonecutter origin/shader-refactor` 中 **`-` = 上游已有等价补丁，`+` = 上游没有**（容易读反）。
实测（对 48 个非 merge 提交逐一双向 `patch-id --stable` 比对）：`-` 组 **28/28** 在 stonecutter 有
patch 等价提交（例：`44711ceb7` ≡ `91ef370b8`、`be719b1d7` ≡ `a46579512`，patch-id 完全相同），
`+` 组 20 个中 **18 个**在 stonecutter 有同 subject 提交（diffstat 近似，属 rebase 重放）。
⇒ 该分支**不是"未合并的活分支"**，而是**已被 stonecutter 吸收并重组过的历史线**。

### 4.2 分支性质

- merge-base `5d2eaacad`（2025-07-09），提交作者日期 2025-07-09..2025-08-05，**早于当前跟踪的最早同步窗口
  一年多**（最早基线 `376d8199` = 2026-08-25），属 `upstream-maintenance.md`「待追溯：早期 Celeritas 进入
  当前代码树的文件范围」一类。
- 其独占产物 `common-shaders/`（`net.irisshaders.iris` 重写、`foss_transform/ShaderTransformer`、
  `ProgramSource` 重写）在 stonecutter HEAD **已不存在**，内容被拆入 `modern/src/main/shaders_java/`；
  本地 Iris 仍是 `net.coderbot.iris`（1.6.x 基线），与该重写不是同一代码基。
- 分支里的 **postmodern 目标实为 MC 1.21.6/1.21.8 线**（`a96639024` 同期加入 `parchment_version_1_21_8`、
  `neoforge_1_21_8`），与 1.12.2 无关；1.12.2 在 stonecutter 里是独立的 `forge122/` 源集。

### 4.3 逐项核对（含看起来最相关的几项）

| 分支提交 | 内容 | 本地结论 |
|---|---|---|
| `44711ceb7` | 1.12.2 Options GUI (#18) | **已有超集**：全部性能选项字段（`chunkBuilderThreads`/`alwaysDeferChunkUpdates`/`useFogOcclusion`/`useBlockFaceCulling`/`useTranslucentFaceSorting`/`useRenderPassConsolidation`/`asyncOcclusionMode`）与渲染侧接线（`VintageRenderPassConfigurationBuilder`、`VintageRenderSectionManager` 全接 `ActiniumRuntime.options()`）本地已具备，并额外含 shadow pass 处理与 RSO 搜索框 |
| `e4df9d84e` / `6fcbaa3c6` | 雾组件通用化 / postmodern 雾 GLSL | 已有等价物（`ChunkShaderComponent`/`Factory`/`ChunkShaderOptions`/`FogService.getFogMode()`；`fog.glsl` 的 `_linearFogValue` 与 `USE_FOG_POSTMODERN` 双距离段，本地另含 `_expFog`/`FOG_SHAPE_PLANAR` 超集） |
| `f6f8306a3` / `5bbac1254` | GL 对象自持删除 | 已有等价物（`impl/gl/GlObject.java` 的 `delete()`/`destroyInternal()`/`@Deprecated destroy()`） |
| `a7ad54498` / `33219a741` / `3109f56ba` | timer query 仅 UI 请求时运行 / `timingMap` 缺失 duration 崩溃 / render list manager 改 protected | 已有等价物（`RenderSectionManager` 的 `isDebugInfoShown()`、判空守卫、protected 字段，本地另加 GPU 采样开关门控） |
| `1585fe36d` | 去掉 common 对 `ShaderModBridge` 的依赖 | 已有等价物且更彻底（`emulateLegacyColorBrightnessFormat` 全仓无命中；光影判断已下沉到 `VintageRenderSectionManager.useFogOcclusion()`） |
| `a96639024` / `c56825ae8` / `c89680083` / `f0b06f0bc` / `ad8ffe55d` / `64414120f` | postmodern/pass 合并/Material 断言/渲染配置报错/`CyclingControl` 非枚举/空气判断 | common 侧改动逐条已在本地；`64414120f` 本地为 `block == Blocks.AIR`（更严格，模组额外空气实例不跳过，仅性能差异） |
| `0c08ae5f3` | fastutil BE map 迭代优化 | 不适用：1.12.2 `Chunk#getTileEntityMap()` 是普通 `HashMap`，无 fastutil map 可探测 |
| `be719b1d7` | 流体顶面过早剔除 | 不适用：本地**没有 Celeritas 自有流体渲染器**（流体走 vanilla `BlockFluidRenderer`），改动的 `VoxelShape`/`Shapes` 面在 1.12.2 不存在 |
| 其余（构建脚手架、`common-shaders/` 内部重构、babric/NeoForge 目标、`bc964f63d`/`f66d07ab3` 等） | — | 不适用 |

### 4.4 结论与流程建议

- **最小必做集为空集**：该分支没有任何提交满足"值得补同步"的判据；不需要为它开专门分支。
- 可选 P4 清理（仅由该分支勘察顺带揭示，与是否同步无关）：常量 `32768` 在本地三处重复
  （`CompactChunkVertex.java:23`、`ExtendedChunkVertexEncoder.java:22`、`ExtendedChunkVertexType.java:14`）；
  `ChunkBuilderMeshingTask.java:104` 的空气判断可考虑精确类匹配（收益极低）。
- **`docs/upstream-maintenance.md` 建议补两条**：(1) 写明本仓 `git cherry` 的 `-`/`+` 语义（已实测），
  避免后续会话按"`-` = 待同步"误判；(2) 把 `origin/shader-refactor` 标注为"已被 stonecutter 吸收并重构的
  历史线，不再作为同步源"。

## 5. 历史未收编项复审

### 5.1 `8d63cb7d4` BufferBuilder 重构 — 维持现状

历史理由（`sync-celeritas-fe57c60fa.md`）："全部针对 modern immediate-consumer 基础设施（SodiumBufferBuilder
等本地不存在），且与本地 #126 自研 vertex writer 体系冲突"。今天复核：13 个文件全在 `modern/`，本地
`SodiumBufferBuilder`/`SegmentRendering`/`fast_delegate` 零匹配，措辞应修正为"**无适用面**"；
#126 已用 `BufferBuilderMixin`（`@Overwrite` + `growBuffer` TAIL 刷新 `DirectBufferAddress`）独立达成同一效果，
且规避了上游那次修复的"primitive 中途扩容导致裸指针悬垂"隐患（1.12.2 的 `byteBuffer` 只在构造与
`growBuffer` 被赋值，两处都已刷新）。
**重启触发条件**：若本地决定给立即模式（实体/粒子/TESR）引入真正的逐顶点扩展数据（放弃常量属性方案），
再采纳其"元素偏移一次解析 + 写顶点时就地补 midTex/tangent"的扩展点模式。

### 5.2 `acb7d79fd` 共享 block 解包 — 部分成立：block 通道收益已边际，**fluid 通道是新的可做项**

历史理由（`sync-celeritas-5c68ed4cb.md`）："前提是 modern 的 `PalettedContainer` 不可变快照 +
`sodium$unpack` 模型；本地 `ClonedChunkSection` 直接引用 live `ExtendedBlockStorage`"，并给出"EBS 深拷贝快照
→ section 内共享解包 → copyData 改别名"的前置路径。
复核修正：
- "本地无 palette 解包"这个隐含前提**不准确**——1.12.2 的 `ExtendedBlockStorage.get` 同样走
  `BlockStateContainer` 的 palette + bit array 解包；
- 但本地 `WorldSlice` 是**裁剪式拷贝**（origin 全量 4096 + 邻居只拷 2 格厚切片），每任务约 8000 次 vs 上游
  110,592 次（7.2%），共享后最多再省一半 ⇒ block 通道收益约 **1%** 量级，还需付出 +16KB/已渲染 section
  内存与 `BlockStateContainer` 深拷贝快照（1.12.2 无 `copy()`，需 accessor mixin）的复杂度。
  **不建议优先做**；若做，不得在 clone 时直接 4096 次 `get`（会把 palette 查找搬到主线程）。
- **新增可做项（低风险、零前置）**：fluid 通道的源已经是不可变快照
  （`src/main/java/com/dhj/actinium/compat/fluidlogged/FluidStateStorage.java:17-34` 在 clone 时逐格复制），
  但 `WorldSlice.unpackFluidData`（`WorldSlice.java:251-264`）仍然每任务对 27 个 section 各做 4096 次拷贝
  并用 `FluidState.of(...)` 装箱（空 section 也要 `Arrays.fill`），量级与上游 block 基线同级。
  方案：`ClonedChunkSection` 增"懒解包 + 别名共享"的 `getUnpackedFluidData()`，`WorldSlice` 改别名，
  约 2 文件 / 60 行，只读数组、无可变源。验证：`check` + 单测（克隆后改源容器 → 解包数组不变；别名数组无写入）
  + 实机 Fluidlogged API 下大视距飞行的 `chunk.*` meshing 段耗时对比。

### 5.3 `2334caab0` 多 vertex format 共享 arena — 维持现状

历史理由（`sync-celeritas-5c68ed4cb.md`）："本地同一时刻只有一种 `ChunkVertexType`，共享收益为零"。
复核：单选链路未变（`ActiniumWorldRenderer.chooseVertexType()` → `VintageRenderSectionManager.create` →
`VintageRenderPassConfigurationBuilder` 全部 pass 同一格式；shader 侧单 `vertexType` 字段，变更即整体
`requestRendererReload()`）；DH 改造 #157 后也不再引入第二格式。`TessellationKey`/`TessellationProvider`/
`commonVertexStride` 本地零匹配，`BatchAssembler.createCachedBatch` 仍是 7 参。
上游 `5c68ed4cb..06999aabc` 的 14 个提交中没有任何一个依赖该结构。
**重启触发条件**：(a) 引入第二个 `ChunkVertexType`（如按 pass 用不同布局）；(b) 出现"同 stride、不同属性布局"
的格式——此时**必须**先做 `TessellationKey`（否则 tessellation 绑定取错，属正确性问题）；(c) 上游出现依赖
`DeviceResources`/`CachedBatch.getTessellation()` 的新提交。

### 5.4 暂缓的 GL 项 — 全部维持不做

| 项 | 历史理由 | 复核结论 |
|---|---|---|
| `208127b4` GlFence.sync 参数 | "上游面向 glWaitSync，本地用 glClientWaitSync" | **自然消解**：上游后续 `6140755e8` 已删除整个 `sync()`；本地两份 `GlFence` 都用 `glClientWaitSync` + 正确 flag。该项转为 §2.1 的 fence 泄漏修复 |
| `7de8b00c` EXT_timer_query / 去嵌套 | "本地用 timestamp query 且存在嵌套计时区间" | **不做**：嵌套是真实的（`shader/.../pipeline/DeferredWorldRenderingPipeline.java` 的外层 timer 包住两个内层），换 `GL_TIME_ELAPSED` begin/end 必然 `GL_INVALID_OPERATION`；且本地 `MixinMinecraftCoreProfileDisplay` 强制 context ≥ GL 3.3，`caps.OpenGL33` 恒真 ⇒ EXT 分支**不可达**，收益仅限调试计时。触发条件：放弃 GL 3.3 硬门槛，或先重排计时器层级 |
| `4afdabd2` instanced vertex attribute bindings | "GLSM 已有等价能力，当前无实例化绘制调用" | **不做**：celeritas-common 全模块 0 实例化绘制；GLSM 的实例化能力在另一条链上（`GLStateManager` + `Lwjgl3GLRenderBackend` 的 `glVertexAttribDivisor`），两条路径不可复用；上游自身也无 `divisor != 0` 的使用者 |
| `1948aef1` LWJGL wrapper 增补 5 个方法 | "需确认本地有无对应直接调用" | **不做**：本地 `LWJGLService` 一个都没有，celeritas-common 对这 5 个方法 0 调用；上游 `common/src/main` 同样 0 调用 |
| `38ee3f207` 残留 4 项 | ShaderPackScreen/colorspace/IrisRenderingPipeline/ExtendedShader | **不做**：本地 `ShaderPackSelectionList` 无 `Closeable`/zip 资源；`colorspace` 包不存在；`IrisRenderingPipeline` 不存在且其两个有效 hunk 已由 `DeferredWorldRenderingPipeline` 覆盖；`ProgramBuilder` 链接后立即 `shader.destroy()` |
| `shadowRenderer.destroy()` 疑点 | "疑似从未被调用" | **证伪，应从遗留清单撤销**：本地 `shader/.../pipeline/ShadowRenderer.java` 没有 `destroy()` 方法、也不持有自有 GL 对象（targets 由 pipeline `:1402` 销毁、compositeRenderer 由 `:1383-1385` 销毁、RenderBuffers 相关代码整体注释掉），不存在资源未释放路径 |

### 5.5 构建配置 — caching 做，configuration-cache 暂不做

- `org.gradle.caching=true`：**可做**（1 行）。子项目极薄；唯一需先确认的正确性点是
  `build.gradle:480-502` 在 `jar { doFirst { manifest { ... } } }` 里设版本清单，目前靠输出路径含
  `project.version`（含 git sha）侥幸成立；`processResources` 已正确声明 `inputs.properties`。
  验证：`./gradlew build --no-daemon --build-cache` 连跑两次看 `FROM-CACHE`；再 `git commit --allow-empty`
  后解包比对 `mcmod.info` 的 `mod_version` 与 MANIFEST 的 `Implementation-Version` 是否跟着新 HEAD 变
  （不跟着就把 manifest 块从 `doFirst` 移到配置期）。CI 如需复用要在 workflow 里显式加 `--build-cache`。
- `org.gradle.configuration-cache=true`：**暂不做**。现在开启会直接失败（Gradle 默认遇到 configuration cache
  问题即构建失败）。已定位阻断点：配置期 `ProcessBuilder` 调 git（`build.gradle:34-47`，官方明确不支持，
  且缓存命中时会静默给出旧版本号）、6 处执行期 project 访问（`jar`/`remapJar`/`verifyDistributedJar`/
  `verifyModuleBoundaries`/`prepareChunkAnimatorMcpMappings` 的 doFirst/doLast）、执行期捕获 SourceSet
  （`devShadowJar.get()`、`files(provider { sourceSets... })`）、以及 unimined/cleanroom/shadow/idea-ext/blossom
  的兼容性未知。作为独立技术项排期，不与上游同步混做。

### 5.6 JMH 基准 — 先保住资产，再决定恢复

- 上游 bench 现有 18 个文件，EGL/GL 依赖集中在 `HeadlessGl`（`EGL_PLATFORM_SURFACELESS_MESA` +
  硬校验 `EGL_KHR_surfaceless_context` + 4.6 core），使用方 5 处；本机 Windows 无系统 EGL，Mesa `libEGL.dll`
  缺件即 `error 126`，**不是代码问题**。
- **重要**：本仓历史上曾有一份"已适配 LWJGL 3.4.1"的完整 harness（commit `16eaec90`，13 个 jmh 文件 +
  `celeritas-common/build.gradle` 107 行），随后被 `90d7aa15` revert，**当前不被任何分支包含（dangling object，
  `git gc` 即丢）**。已打 tag 保全：`jmh-harness-recovered` → `16eaec90`。
- 恢复成本低：`git revert 90d7aa15`（或从该 tag 取文件）→ 把 bench 源码包名从 `org.embeddedt.embeddium`
  迁到 `dhj.embeddedt.embeddium`（13 个文件机械改写）→ 补 `gradle.properties` 的 `jmh_version=1.37`
  → `./gradlew :celeritas-common:compileJmhJava --no-daemon` 验证（编译不需要 GL context）。
  运行基准另开 Linux/CI job（ubuntu-latest 自带 Mesa llvmpipe），不要塞进现有 `build.yml`。

#### Windows EGL 复测与运行方式（2026-09-25）

- 用 JBR 自带的 `libEGL.dll` 实际启动 JMH 后，DLL 可以加载，但
  `eglBindAPI(EGL_OPENGL_API)` 返回 `0x300c`，该实现不接受基准要求的 desktop OpenGL API；因此单纯把 DLL
  放进搜索路径不能解决问题。
- `HeadlessGl` 使用 EGL，不创建窗口。Windows x64 的 `:celeritas-common:jmh` 首次运行时会将
  `src/jmh/natives/mesa-windows-x64.zip` 解压到 `celeritas-common/build/jmh/mesa-windows-x64`，自动把目录加入 JMH 进程和 fork
  的 `PATH`/`java.library.path`，并选择 `llvmpipe`；其他平台继续使用系统 EGL。context 创建后会检查
  OpenGL 4.4，因为 `MappedStagingBuffer` 需要该版本。
- Mesa ZIP 为 23,849,118 bytes（低于 GitHub 50 MiB 提示线），解压后包含三 DLL 共 62,131,712 bytes；最大
  DLL 61,716,480 bytes。仓库没有 Git LFS filter。来源、Mesa tag commit、发布包和仓库 ZIP 的 SHA256 与
  许可证说明见 `THIRD_PARTY_NOTICES.md`。
- 试跑时两个 GL 属性基准都通过普通 `:celeritas-common:jmh` 命令以 EGL、1 fork、`renderDistance=4`、100 ms
  warmup/measurement 完成，没有设置运行环境变量。这些短跑只证明 EGL 与 fork runtime 可用，不是性能比较结果。
- 独立 JMH runtime 还需显式提供游戏运行时原本提供的 Log4j API、fastutil 与 JOML。`:jmh` 使用 `-foe true`
  使基准 setup/measurement 异常以非零退出码返回，避免只打印 `<failure>` 却显示 Gradle 成功。
- 验证：`:celeritas-common:compileJmhJava` 通过；Windows 上 `OcclusionCullerBench.staticCameraCountingOnly`
  与 `MultiDrawBench.emitSolid` 均以 1 fork、`renderDistance=4`、100 ms warmup/measurement 启动并完成。该参数只
  验证运行链路，不是可用于性能比较的测量结果。

## 6. 批次与实施顺序（已完成）

| 批次 | 内容 | 说明 |
|---|---|---|
| 1 | `6140755e8` fence 清理；`752696e73` ColorMixer（+ `ChunkColorWriterTest`）；`org.gradle.caching=true` | 零/低风险，互不依赖 |
| 2 | `727b464d0` RasterBudget（含 2 处本地化 + `RasterBudgetTest`） | 自包含，与其余项零文件重叠，失败方向保守 |
| 3 | `671ecaffc` + `2edc3d211` 排序 v3 | 合并为 C1 + 完整移植两次提交（理由见「实施中的偏差」） |
| 4 | fluid 通道共享解包（§5.2）；恢复 JMH harness（§5.6） | 独立小项 |
| 5 | `06999aabc` compact vertex format | 行数最少但触及所有地形顶点与 GLSL 解包，单独验证 |

后续同步仍按此模板：评估文档 → 分批判定 → 逐批 `check` + 提交 → 推进 `docs/upstream-maintenance.md` 基线；
§5 的"维持现状"结论作为下一轮复审输入，不要重复推导。

## 7. 遗留验证与风险登记

- **实机验证清单（本轮新增改动，尚未实机确认）**：
  - `RasterBudget`：`chunk.raster` 的 `buffer=WxH`（预算是否生效）与 `testedPerSec`；平原静止、洞穴/峡谷、
    长距离飞行与传送、扩展高度、光影开/关、HBM-CE 同场。
  - 排序 v3：瀑布（`2edc3d211` 场景）、玻璃墙贴大冰面（大 quad 遮挡小 quad）、水下湖边缘、跨 section 与
    远距离传送（调试 overlay 的 `Sorting:` 与 `Tree Sort:` 应只在跨切平面时跳）、光影 + 阴影 pass、DH、HBM。
  - compact vertex：关光影 + 默认 compact + 渲染距离 ≥12 看铁轨/栅栏/红石/睡莲/花盆无抖动穿洞、区块与 region
    边界无裂缝、树叶 alpha cutoff 正常；再切 `useCompactVertexFormat=false` 与开光影各看一次。
  - ColorMixer：密草/藤蔓的 AO 梯度与整体亮度（光影开/关）。
  - fence 清理：光影开/关各一轮后进出世界 10–20 次，用 RenderDoc/GL debug 看 sync object 是否回落。
  - fluid 共享解包：装 Fluidlogged API，大视距飞行/传送时对比 `chunk.*` meshing 段耗时；确认液体外观无变化。
- **回归约束（勿删除既有注入）**：`MixinClientChunkManager` 的 `loadChunk`/`unloadChunk`/`tick` 三处注入、
  `ChunkTracker.reconcile`、`MixinRenderGlobal.setDisplayListEntitiesDirty`、
  `SimpleWorldRenderer.scheduleTerrainUpdate`/`prepareFrame` 的 `CameraState` 脏检查；删除或改 shift 会重新
  引入与上游 `2b742f6ac`/`84e64cea1` 同源的缺陷。
- **HBM-CE seam**：`RenderSectionManager.update(Viewport,int,boolean)` 的三个 `CameraTransform` getfield
  已保持内联（v3 落地时按此解决冲突），`HbmCameraRedirectContractTest` 是编译期兜底，仍需实机带 HBM 验证。
- **`PackedSectionMetadata` 位布局**：visuals 已由 3 bit 扩到 4 bit 并平移其后所有位（46..49 / 50..52 / 53 / 54）；
  外部只用 `VISIBILITY_MASK`/`GRAPH_INPUT_MASK`，`PackedSectionMetadataTest`/`VisibleChunkCollectorSchedulingTest`/
  `OcclusionCullerProvisionalVisibilityTest` 是兜底。
- **JMH**：本机 Windows 无 EGL，运行基准需 Linux/CI；`compileJmhJava` 是本地可做的验证。
- **未做单元测试的两项**：`MappedStagingBuffer` 的 fence 排空（需 GL 上下文）与 fluid 别名
  （构造 `ClonedChunkSection` 需要真实 World/Chunk），因此都列为实机验证项，而不是用占位测试充数。

## 8. 事故记录：地形整体不渲染（已修复）

**症状**：实机进入世界后所有地形不可见（连脚下方块也没有），天空、雾、实体、箱子/告示牌/末地传送门方块正常；
F3 显示 `solid - 21 sections`、`cutout_mipped - 22 sections`、`G: 23/33 MiB`，即区块**已建好、已上传、已提交绘制**，
也没有任何 Java 异常。关闭 compact vertex format 与 raster occlusion culling 均无变化。

**根因**：`assets/actinium/shaders/include/chunk_vertex.glsl` 的条件指令**不配对**——移植 `06999aabc` 时用三方合并
解决 shader 冲突，结果 `#endif` 落在 `#else` 之前，使 `#else` 与文件末尾的 `#endif` 成为孤儿。引擎自己的报错是：

```
Shader compilation log for actinium:blocks/block_layer_opaque.vsh: 0(144) : error C7102: unmatched #else
                                                               0(152) : error C0122: #else cannot follow #else
ShaderChunkRenderer: There was an error creating a chunk program. Terrain will not render until this is fixed.
```

区块 program 编译失败 ⇒ 所有地形 pass 什么都不画；天空/雾/实体/TESR 走别的 program ⇒ 不受影响；Java 侧不抛异常。
两个开关无效也由此解释：坏掉的条件块同时包住 compact 与 uncompact 两条分支。

**修复**：`25d1704d`（去掉多余的 `#endif`，恢复 `#ifdef USE_VERTEX_COMPRESSION … #else … #endif` 的正确配对）。
**为什么之前的验证没拦住**：`check` 只覆盖 Java 编译与 JUnit，GLSL 要等运行时才编译；而我当时对 shader 的"验证"
是逐行比对上游内容，恰好把合并留下的 `#ifdef` 差异误当成既有的本地分叉。

**新增防护**：`ShaderConditionalBalanceTest` 用栈式解析遍历所有已打包的着色器源，对"未闭合条件""无对应 `#if` 的
`#else`/`#elif`""多余 `#endif`"报错；已做红绿验证（放回多余的 `#endif` 会精确报出
`line 71: '#else' without an open conditional`）。**今后任何触碰 shader 的改动都要以该测试通过为准。**

**实机确认**：2026-09-21 用户实机确认地形恢复正常（同一次会话中开关状态未变，故确认是修复本身生效）。
