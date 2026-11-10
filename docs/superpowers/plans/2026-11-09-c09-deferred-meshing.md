# C09 Deferred Meshing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同步 C09 的延迟方块调度、主线程捕获与 worker 收尾、每帧分配复用及退出世界释放，保持 C05/C06/C08 的现有行为。

**Architecture:** worker 先标记不能离线程处理的方块并交出快照，主线程只执行这些方块的渲染并捕获原始顶点，worker 再转换成正式网格。`DeferredMeshScheduler` 统一拥有池化 buffer、slice、capture 与 `inFlight`，每条 mesh 只释放一次。普通网格、阴影搜索、快速 renderer 与 Fluidlogged 继续使用原有路径。

**Tech Stack:** Java 21、Mixin Extras、JUnit 5、LWJGL 3、fastutil、Gradle。

## Global Constraints

- 工作目录 `D:/Code/Actinium`，分支 `feat/c09-deferred-meshing`，基线 `4cb49a92`；禁止创建 worktree。上游只读来源 `D:/Code/Angelica`。
- 覆盖 `762c5661`、`d876a203`、`6c888a63` 的通用部分、`ed6dd12d`、`8e099e95`。排除 SDL、experimental-surround 专用、Tracy 原生采集与构建治理文件，不得因此删掉通用调度或资源管理。
- 保留 `VintageBlockRenderer` 的 addon 绑定、C08 dispatcher 路由、`useFastBlockRenderer`、Snow/ArchitectureCraft/Fluidlogged 回退、C05 搜索窗口与 C06 阴影上传顺序。
- 不得只移植开关或把功能默认成不可用的空实现。Cleanroom 1.12.2 没有 `ISimpleBlockRenderingHandler`、`RenderBlocks` 或对应的 `RenderingRegistry`（编译期 `找不到符号`，`BlockRendererDispatcher.renderBlock` 只有 MODEL/LIQUID/ENTITYBLOCK_ANIMATED 分支），因此延迟捕获的判据是本地配置而不是 renderer 注解：默认所有方块保持 worker 路径，配置列出的 `namespace:block` 才进 handoff。
- 保留普通 immediate 构建路径；只有 worker 且快照可用的任务可以 handoff。任务取消、区块重建、世界退出和异常必须释放资源。
- `FINISH_PRIORITY=-1`，介于 important 与普通距离优先级之间；`RenderListManager` 仍禁止同 manager 第二个在飞搜索。
- 禁止 Mockito、任意反射、纯源码 contains 测试、占位实现、额外依赖。
- 实现者不得提交、推送、创建 worktree 或运行 `runClient`；L1 负责审查、完整构建、部署与 Git 事务。每项任务完成后先审查再继续。
- 新增 Mixin 必须加入 `mixins.actinium.vintage.json` 并通过 `MixinConfigurationTest`。测试放根项目 `src/test`，GL 测试放 `src/glIntegrationTest`。

---

### Task 1: 资源池、快照与渲染列表生命周期

**Files:**
- Create: `src/main/java/com/dhj/actinium/render/terrain/deferred/ObjectPool.java`
- Modify: `src/main/java/com/dhj/actinium/world/WorldSlice.java`
- Modify: `src/main/java/com/dhj/actinium/world/cloned/ClonedChunkSection.java`
- Modify: `src/main/java/com/dhj/actinium/world/cloned/ClonedChunkSectionCache.java`
- Modify: `src/main/java/com/dhj/actinium/world/cloned/ChunkRenderContext.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/VintageChunkBuildContext.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/light/LightDataCache.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer.java`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/{ChunkRenderList,VisibleChunkCollector,RenderListManager}.java`
- Create: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/RenderListPool.java`
- Test: `src/test/java/com/dhj/actinium/render/terrain/deferred/ObjectPoolTest.java`
- Test: `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/RenderListPoolTest.java`
- Test: `src/test/java/com/dhj/actinium/world/cloned/ClonedChunkSectionSnapshotTest.java`
- Test: `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/region/TestRegions.java`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/model/light/data/LightDataAccess.java`

**Interfaces:**
- `ObjectPool<T>` supplies `acquire()`、`release(T)`、`freeCount()`、`trim(now,minFree)`、`close()`。池为 LIFO，`inUse` 与 `freeCount` 使用原子计数；`maxFree` 限制空闲保留，空闲淘汰调用资源 disposer。
- `WorldSlice.releaseContext()` 释放本 slice 持有的快照引用并清空数据；`reset()` 复用该入口，重复调用不得重复释放。
- `VintageChunkBuildContext.swapWorldSlice(WorldSlice)` 返回旧 slice 并同步 `LightDataCache`；`getRenderBuffers()` 返回 handoff buffer 或原 context buffer，不能改变原 context 的 `buffers` 所有权。
- `RenderListPool` 提供两个轮换槽，每槽持有 `ObjectArrayList<ChunkRenderList>` 与按 region id 索引的数组；`ChunkRenderList.reset()` 清空所有 section 数组和计数但保留 region。

- [ ] **Step 1: 先写池与复用测试**

验证 LIFO、超过 `maxFree` 丢弃并调用 disposer、`trim` 的两个 30 秒窗口峰值与 `minFree`、关闭后不再保留空闲对象。用 `AtomicInteger` 和实际池对象，不用常量相等性测试。

验证 `ChunkRenderList` 写入后 reset 清掉 geometry/sprite/entity/dynamic-sort 计数与数组，复用相同 region 时不保留上一次结果；两个槽交替时上一槽的列表仍可被消费者读取。验证 `VisibleChunkCollector` 使用池时创建新的排序列表容器或清空可复用容器，不叠加旧 section。

- [ ] **Step 2: 运行 RED 并实现池**

Run: `./gradlew :test --tests "com.dhj.actinium.render.terrain.deferred.ObjectPoolTest" --tests "dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderListPoolTest" --no-daemon`

Expected: 缺少新类或 reset 行为失败。实现 `ObjectPool`、`ChunkRenderList.reset`、`RenderListPool` 与 collector/manager 接线。`trim` 使用 `TRIM_WINDOW_NANOS=30s`、`max(windowPeak,previousPeak)` 和减半空闲淘汰公式；`close()` 只释放已空闲资源，在飞对象仍由所属任务释放。

- [ ] **Step 3: 给快照加引用计数和真正的初始化/重置**

`ClonedChunkSectionCache.acquire` 对命中或新建的 section 增加 `refs`；`release(ClonedChunkSection[])` 递减。`invalidate` 和 LRU 淘汰先移除 cached 标记，只有 `!cached && refs==0` 的对象进入最多 64 个对象的 free 队列。`init` 必须拒绝 `refs!=0 || cached` 的复用，清空 tile entity、fluid unpacked cache、light 数据并重新填充。

`ClonedChunkSection` 保存独立的 `ExtendedBlockStorage` 副本，不能继续把 live section 的 `data` 引用当作快照。用 `new ExtendedBlockStorage(section.getYLocation(), section.getSkyLight()!=null)` 和真实 `get`/`set` 复制 block state，并复制光照数组。`sectionPos`、`data`、`fluidData` 可以在 init 时替换，其它数组复用并完整重置。

`ChunkRenderContext` 记录 cache 与是否已把快照所有权交给 `WorldSlice`。未启动任务调用 `release()` 时必须释放；`WorldSlice.copyData` 接收所有权，`releaseContext` 只释放一次。`WorldSlice.reset` 的现有 sections/volume/biome 清理保留。

- [ ] **Step 4: 给 build context 增加可交换 slice 和 handoff buffer 读取口**

`worldSlice` 去 final，`swapWorldSlice` 返回旧值并调用 `lightDataCache.rebind(newSlice)`；`LightDataCache.world` 去 final 并实现 `rebind(IBlockAccess)`。快速 renderer 的 `this.context.buffers` 改为 `this.context.getRenderBuffers()`，可选 handoff buffer 不得改变原 context `buffers` 的释放路径。`cleanup()` 清除 handoff 指针但继续释放原 context 的资源。

- [ ] **Step 5: 验证真实快照**

`ClonedChunkSectionSnapshotTest` 使用真实 `World`/`Chunk`（可采用 `WorldClient` 子类提供实际区块，禁止绕过构造器），验证复制后 live section 修改不会改变已有快照、light/biome/tile 数据在复用前重置。`Mods` 的 Forge 启动扫描阻止在 JUnit JVM 内初始化完整 cache，不能用 mock 代替；refs、淘汰、`releaseContext` 与 `swapWorldSlice` 由 Task 3 接线和部署后的真实区块重建验证，报告必须保留该验证缺口。运行 `ObjectPoolTest`、`RenderListPoolTest`、`ClonedChunkSectionSnapshotTest`，必须 RED→GREEN。

---

### Task 2: 延迟调度与主线程捕获判据

**Files:**
- Create: `src/main/java/com/dhj/actinium/render/terrain/deferred/{DeferredBlock,DeferredSectionMesh,DeferredFinishTask,DeferredMeshScheduler}.java`
- Create: `src/main/java/com/dhj/actinium/rendering/BlockRenderSafetyRegistry.java`
- Create: `src/main/java/com/dhj/actinium/gui/DeferredBlocksScreen.java`
- Modify: `src/main/java/com/dhj/actinium/gui/ActiniumGameOptionPages.java`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gui/SodiumGameOptions.java`
- Modify: `src/main/java/com/dhj/actinium/world/WorldSlice.java`、`src/main/java/com/dhj/actinium/world/cloned/DeferredSlice.java`
- Modify: `src/main/resources/assets/actinium/lang/{en_us,ru_ru,zh_cn}.lang`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/RenderSectionManager.java`
- Test: `src/test/java/com/dhj/actinium/render/terrain/deferred/{DeferredMeshSchedulerTest,DeferredSectionMeshTest}.java`
- Test: `src/test/java/com/dhj/actinium/rendering/BlockRenderSafetyRegistryTest.java`

**Interfaces:**
- `DeferredSectionMesh.Handler` exposes `isObsolete()`、`captureDeferred(mesh)`、`finishDeferred(mesh,ChunkBuildContext)`（mesher 向下转型到 `VintageChunkBuildContext`）；Task 3 的 mesher 实现该接口，Task 2 不依赖未实现的 Task 3 方法。
- `DeferredMeshScheduler` supplies `acquireBuffers()`、`acquireSlice()`、`submit()`、`runImportant()`、`markDestroyed()`、`releaseOrphans()`、`pendingCount()`、`finishingCount()`、`freeSliceCount()`、`trimPools()`。
- `BlockRenderSafetyRegistry.canRenderOffThread(Block)` returns `false` only for the configured `namespace:block` ids, `true` otherwise；`parseBlockIds(String)` 接受逗号/空白分隔、去重、非法输入抛 `IllegalArgumentException`；`formatBlockIds`/`configuredBlockIds` 供编辑界面使用。
- `SodiumGameOptions.PerformanceSettings.deferredBlockIds` 保存该列表；`DeferredBlocksScreen` 负责编辑、校验、保存并重载区块渲染器。

- [x] **Step 1: 先写调度状态机测试**

使用真实 `ChunkBuilder(ChunkBuilder.ManagedBlocker.NONE, () -> new ChunkBuildContext(null), -1)`、`ChunkBuildBuffers`、`WorldSlice` 和匿名 `DeferredSectionMesh.Handler`，不使用 Mockito。覆盖 important、obsolete-at-capture、obsolete-at-finish、capture failure、capture 抛异常（普通与 important 两条交付路径）、finish throw、builder 关闭后提交 finish、destroyed、正常 finish、最近距离优先、burst 超 worker 数、释放异常与 `releaseOrphans` 幂等。断言 `pending=0`、`finishing=0`、buffer/slice/capture 重新可取回、结果类型与线程归属正确，且异常不逃出 `runNext`。

- [x] **Step 2: 运行 RED 并实现调度类**

Run: `./gradlew :test --tests "com.dhj.actinium.render.terrain.deferred.*" --no-daemon`

Expected: 缺少调度类。实现 `DeferredBlock`、`DeferredSectionMesh`、`DeferredFinishTask`、`DeferredMeshScheduler`。`submit` 按 important 分队列；普通队列按距离，`scheduleAsyncTask` 只投递一个共享 `runNext`；`inFlight` 是唯一恰好释放一次的集合。`finished` 合并 main/worker 纳秒，先移除 inFlight，再释放，释放异常转为 `ChunkJobResult.Failure`，并用原输出的 `delete()` 释放被替换的网格。`run` 捕获 capture、提交与释放的异常，原始异常与释放异常用 `addSuppressed` 合并后作为 `Failure` 投递（important 走 collector），不能让异常冒到整帧；`captureDeferred` 返回非 `Failure` 时 fail fast。未提交 finish 的失败走 `ChunkJobCollector.onJobSkipped`（只投递结果、不释放完成许可），真正完成的 finish 继续走 `onJobFinished`，保证 `runImportant` 仍等待已提交的 finish。`release` 在 `finally` 递减 pending，buffer/slice/capture 的归还异常逐项合并。`releaseOrphans` 先取消共享 `runNext`，再处理 important/normal/inFlight，禁止重复递减 pending。

- [x] **Step 3: 实现原始顶点捕获池**

`DeferredCapture` 用可增长的直接 `ByteBuffer` 保存所有捕获条目，条目记录 `Material`、layer、byte offset/length、`List<VanillaQuadContext>`。`add` 拒绝非 direct 的 `ByteBuffer`（否则会把堆地址交给 native copy）。`clear()` 复用容量并清条目，过大时缩容；`dispose()` 用 LWJGL `MemoryUtil.memFree` 释放。`ObjectPool` 的 trim/close 必须调用 disposer，不能依赖 GC 回收 native buffer。

- [x] **Step 4: 实现本地捕获判据与配置界面**

上游的 ISBRH 分类在本平台没有宿主，改为本地配置判据：`BlockRenderSafetyRegistry.canRenderOffThread(Block)` 默认返回 `true`，只有 `performance.deferred_block_ids` 命中该方块的 `namespace:block` 时返回 `false`。不得创建 `IsbrhDispatch`、`ThreadSafeISBRH`、`ThreadSafeISBRHFactory`、`IRenderingRegistryExt`、`MixinRenderingRegistry` 或修改 `mixins.actinium.vintage.json`；不得把 Snow Real Magic、ArchitectureCraft、Fluidlogged 这些已有 worker 路径的模组硬编码成 unsafe。

`parseBlockIds` 接受逗号/空白分隔、去重并保持顺序、非法输入抛 `IllegalArgumentException`。`canRenderOffThread` 按配置列表的实例缓存不可变 `Set<ResourceLocation>`，配置替换列表后重建，空列表直接放行，不逐格生成字符串或线性扫描。`ActiniumGameOptionPages` 在快速方块渲染器同组放一个 `ExternalButtonControl`，打开 `DeferredBlocksScreen`（`GuiTextField`、保存/取消、错误提示、Escape 取消、窗口 resize 保留未保存草稿）；保存时仅在列表实际变化时写入 `deferredBlockIds`、调用 `ActiniumRuntime.options().save()`，并在 `renderGlobal != null` 时调用 `Minecraft.renderGlobal.loadRenderers()`（主菜单无区块渲染器），取消时不写配置。三种语言文件补齐文本。测试直接读写真实 `ActiniumRuntime.options()` 并在 `finally` 恢复，不使用 Mockito 或反射。

- [x] **Step 5: 暴露管理器队列并验证**

`RenderSectionManager` 提供 `getBuildResults()` 与 `cancelAsyncTask(Runnable)`（按引用移除，不使用无调用方的 `AngelicaRenderQueue`）。运行调度测试、`BlockRenderSafetyRegistryTest` 与 `MixinConfigurationTest`，必须 RED→GREEN。

---

### Task 3: 任务 handoff、capture/finish 与世界退出

**Files:**
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/task/ChunkBuilderMeshingTask.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/VintageRenderSectionManager.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/VintageChunkBuildContext.java`
- Test: `src/test/java/com/dhj/actinium/render/terrain/deferred/DeferredMeshSchedulerTest.java`
- Modify: `docs/architecture.md`
- Modify: `docs/sync-angelica-8e099e95.md`
- Modify: `docs/compatibility-matrix.md`

**Interfaces:**
- `ChunkBuilderMeshingTask.bindScheduler(DeferredMeshScheduler, boolean important)`；`captureDeferred(mesh)` 返回 `ChunkJobResult<ChunkBuildOutput>` 或 null；`finishDeferred(mesh, VintageChunkBuildContext)` 返回正式 `ChunkBuildOutput`。
- `VintageRenderSectionManager.updateChunks` calls `trimPools()` 与 `runImportant()`；`destroy()` 的顺序为 `markDestroyed → super.destroy → runImportant → releaseOrphans`。

- [x] **Step 1: 在 worker 预扫并交出需要主线程的方块**

`prescanDeferred` 扫描 16³ `IBlockState`，写入 `long[64]` 位掩码。判据用 `BlockRenderSafetyRegistry.canRenderOffThread(Block)`：默认全部留在 worker，只有配置列出的方块进 handoff；不得把 Snow Real Magic、ArchitectureCraft、Fluidlogged 等已有 worker 路径的回退硬编码成 unsafe。仅 worker 且快照非空时允许 handoff；没有需要主线程的方块时完全走原 immediate 路径。handoff 时从 scheduler 取池化 buffers 并通过 `getRenderBuffers()` 提供给快速 renderer，延迟方块不进入 immediate 几何。

`DeferredBlock` 保存世界 xyz、原始 `IBlockState`、`layerMask`、shader material override。普通层按 vanilla ordinal 记录；shader override 记录对应 material，禁止在 finish 中再次转换 override 顶点。

> 已落地。掩码由 `VintageChunkBuildContext` 持有（`getDeferredMask()`），`BlockRenderSafetyRegistry.hasDeferredBlocks()` 在列表为空时直接短路，预扫与取池都不发生。handoff 门槛为 `!Minecraft.isCallingFromMinecraftThread() && scheduler != null && 预扫命中`。延迟方块在扫描期不调用 `canRenderInLayer` 也不调用任何渲染方法：普通层 pass mask 记为全部 layer，override 记为该 material 的单一 layer，实际渲染哪些层由主线程按谓词决定（与上游 `canRender = !canRenderOffThread || canRenderInPass(pass)` 的短路一致）。`DeferredBlock` 保存 block、pass mask 与材质覆盖，原始 `IBlockState` 存在 mesher 的 `DeferredRender` 里随 mesh 走。shader 层先映射到 vanilla 层再查材质；capture 保留该材质，Fluidlogged 仍用实际捕获层。留在构建线程的原版顶点在提交前 `convertVanillaDataToCeleritasData` 编入池化 buffers（LittleTiles 的追加点同在此）。

- [x] **Step 2: 主线程 capture 原始数据**

`captureDeferred` 遍历 deferred blocks/pass mask，保持 shader layer override 的 `allowRenderPassOptimization=false`、快速开关、Snow/ArchitectureCraft/Fluidlogged 回退、Better Foliage/RLFoliage 包装和 `ComponentModelHiderCompat.beginBuild/endBuild`。

每条捕获记录包含 byte range、material/layer 和 consume 的 `VanillaQuadContext`。使用 `buffer.getByteBuffer().position()` 在渲染前后计算差值，不能把其它方块的顶点算入本记录。捕获结束后不要调用 `convertVanillaDataToCeleritasData`，否则 finish 会重复编码。

> 已落地，偏差一处：本平台的 `BufferBuilder.addVertexData` 只推进 `rawIntBuffer`，`byteBuffer` 的 position 在绘制期恒为 0（`finishDrawing` 才写入 position/limit；按 mapped Cleanroom 类 javap 核对），因此区间改用渲染前后的 `getVertexCount()` 差值乘 `DefaultVertexFormats.BLOCK.getSize()`，再 duplicate+position/limit 出该块自己的有界视图。判定与两条渲染路径（dispatcher 原版路径、快速 renderer）统一收进 `renderBlockLayer`，Better Foliage 与 RLFoliage 的 `@Redirect`/`@WrapOperation` 也改注入该方法，构建线程与主线程捕获共用同一条调用链。捕获用独立 `VintageChunkBuildContext`（manager 构造时创建），捕获前后换入/换出 mesh 快照、设置/清空 handoff buffers、`setupTranslation`，结束时 `discardVanillaBuffers()` 清空原版 buffer。Fluidlogged 流体按 layer 单独记录区间。

- [x] **Step 3: worker finish 并生成正式结果**

`finishDeferred` 绑定 mesh slice 与 light cache，按捕获条目创建有界 ByteBuffer 视图，调用 `VintageChunkBuildContext.copyBlockData`。每个 entry 的 quad context 数量和 byte range 必须匹配，最后调用 `groupFromBuildBuffers` 与原路径相同的 `finalizeSection`，设置 `hasBlockGeometry`、`visibilityData`、`occluderBoxes`。

`DeferredSectionMesh.isObsolete()` 检查 token、`render.isDisposed()` 和 `render.getLastBuiltFrame()>buildTime`，capture/finish 各检查一次。`releaseUnstartedContext()` 处理未启动任务的快照。`finished` 结果进入正常 `buildResults`，不改变 `blockingResultsPending` 或阴影上传顺序。

> 已落地。`copyBlockData` 改为 public；`requireCompleteEntry` 校验 `length % (顶点大小*4) == 0` 且 quad context 数量等于四边形数。`isObsolete()` 用提交时的取消 token、`render.isDisposed()`、`render.getLastBuiltFrame() > buildTime`。`ChunkBuilderTask` 新增默认 `releaseUnstartedContext()`，`ChunkJobTyped.execute` 在 `!started` 时调用一次；`ChunkBuilderMeshingTask` 覆写为 `ChunkRenderContext.release()`（`copyData` 转移所有权后为空操作）。`ChunkBuilder.shutdown()` 在销毁 local context 前驱动排队任务的取消释放并投递结果。新增 `ChunkJobUnstartedContextTest`（RED→GREEN）。

- [x] **Step 4: 接通管理器和退出世界**

`createRebuildTask` 绑定 scheduler，importance 取 `render.getPendingUpdate().isImportant()`。`updateChunks` 在现有调度之后调用 `trimPools()` 与 `runImportant()`；空 important 队列不得引入等待。`destroy()` 按固定顺序释放，不允许共享 `runNext` 继续持有 scheduler 和池。

> 已落地。`VintageRenderSectionManager` 构造时创建 `DeferredMeshScheduler` 与捕获 context，`createRebuildTask` 用 `Objects.requireNonNull(render.getPendingUpdate()).isImportant()` 绑定；`updateChunks` 在 `super.updateChunks` 与 `FluxLoadingCompat` 之间调用 `DeferredMeshScheduler.update()`，由它执行 `trimPools()`、`runImportant()` 并安排下一次普通捕获；`destroy()` 为 `markDestroyed → super.destroy → runImportant → releaseOrphans → captureContext.destroy()`。`runImportant` 在空队列时直接返回。普通捕获不在 `runAsyncTasks` 内重新入队，避免集中构建时整批占用主线程。

- [x] **Step 5: 回归与文档**

运行全部 deferred、`BlockRenderSafetyRegistryTest`、资源池、`VintageBlockRendererBindingContractTest`、`MixinConfigurationTest`、`ShadowPassUploadSchedulingGLTest`。验证普通 mesh 结果与强制 handoff 的顶点数/面朝向一致，capture 失败和 finish 失败会释放并报告。

更新 `docs/architecture.md` 的资源所有权、线程阶段和退出顺序；`docs/sync-angelica-8e099e95.md` 的五个 C09 条目记录实际实现；兼容矩阵只记录已验证行为，不能把未测模组标成实机通过。

> 已完成。`./gradlew check --no-daemon` 通过（1231 个用例、0 失败、4 跳过），`./gradlew glIntegrationTest --no-daemon --info` 通过（264 个真实 GL 用例、0 失败）。用户确认 `minecraft:stone` 导致的间歇卡顿在清空列表后消失，增加了普通捕获分周期调度、过期项释放、队列容量限制和淘汰后的重建登记，并在 pacing02 版本上同意合并。BF/RLF 未安装，其运行时注入不在已验证范围内。三份文档及 `docs/options.md` 已更新。

- [x] **Step 6: 完整构建与实机验证**

```bash
./gradlew check glIntegrationTest build -Pversion=alpha-0.0.23-c09-pacing02 --no-daemon --info
```

整批构建通过，1231 项普通测试与 264 项真实 GL 测试无失败，4 项跳过。同一 jar 部署 Cleanroom 和三个 Prism 实例，SHA-256 为 `77860ddc5cfb5b55799a3e8f5c98a7138de25f8570e4c2a02cbcb10ccae3b268`。用户已验收并授权提交、创建集中 PR 和合并。
