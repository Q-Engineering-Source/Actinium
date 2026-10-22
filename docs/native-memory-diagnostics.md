# 原生内存诊断

## 开关与日志

在 Sodium 游戏选项中打开 **Debug → Render Timing Debug**（简体中文显示为“渲染计时 DEBUG”）。该选项默认关闭，并复用 `SodiumGameOptions.debug.enableActiniumPerfDebug`；不需要写入 JVM 参数或启动配置。开启后，GLSM 的周期性能报告每秒附带一次 `[ActiniumMemory]` 汇总。切换开关时也会立即触发一次现有统计 provider。`NativeBuffer` 分配失败会额外记录失败前、现有 reclaim 尝试后的快照；terrain arena 重新分配会单独记录 `[ActiniumMemory] terrain.arenaResize`。

数据写入客户端日志（通常是 `run/client/logs/latest.log`）。数值字段名中的 `Bytes` 均为原始字节，不做 MiB/GiB 格式化。

## CPU 原生分配与队列

- `nativeBufferBytes` / `nativeBufferPeakBytes`：`NativeBuffer` 包装的 LWJGL 原生分配当前值和进程内峰值；`nativeBufferBlocks` / `nativeBufferPeakBlocks` 是底层分配块数，增长期间可能短暂同时包含旧块和新块。`nativeBufferTrackedBuffers` 是尚在 phantom-reclaim 注册表中的逻辑 `NativeBuffer` 对象数。allocation、显式 free、扩容释放、phantom reclaim 的累计次数与字节数分别记录。
- `scratchBytes` / `scratchBuffers`：区块 mesh builder 当前保留的 scratch buffer 逻辑容量与数量；`scratchPeakBytes` 是观察到的峰值。`scratchRetentionReleaseBytes` / `scratchRetentionReleaseCount` 记录 retention policy 淘汰的容量和 buffer 数。它们描述 builder 持有的 scratch，不是仅包含正在写入的有效数据长度。
- `buildResultCount` / `buildResultBytes`：已完成并仍由 chunk build result queue 持有的结果数和其中 NativeBuffer 字节数；峰值字段对应同一队列。正在运行的 worker 任务，以及 poll 后转交渲染线程本地 upload/list 的结果，都不计入队列字段；它们已有的 NativeBuffer 分配仍会出现在 `nativeBufferBytes` 中。

`nativeBufferBytes` 是上述两类数据所在的总 NativeBuffer 账本，不能再与 `scratchBytes`、`buildResultBytes` 相加；后两者用于解释总量可能来自哪里。队列保留 `ConcurrentLinkedDeque` 并用独立原子计数观察所有权转移。并发 enqueue/poll 的极短窗口可能暂时高估队列 count/bytes；生产者和消费者停稳后，账面值与队列持有结果一致。不同 NativeBuffer 计数器是独立原子快照，并发分配/释放时也可能有瞬时偏差。

这些计数只覆盖经 `NativeBuffer` 的分配，不能代表 JVM、全部 JNI、jemalloc 或进程 private bytes。其他 allocator/native image 路径不在这个总数中。

## 大型区块 mesh 输入与输出关联

同一个 **Render Timing Debug** GUI 开关也控制 `[ActiniumMemory] scope=chunk_mesh` 采样。`ChunkBuilderMeshingTask` 只在任务结束并准备输出时、开关开启的情况下计算 mesh output 大小；`builderTaskOutputNativeBytes` 是所有 render pass 的 vertex buffer 与可选 index buffer 的 `NativeBuffer` 实际字节数之和。仅当该值至少为 1 MiB 且预算未耗尽时才分配非零 `traceId` 并扫描输入。生产路径的预算由每个 `VintageRenderSectionManager` 持有，每个 manager 生命周期最多 128 个 trace；兼容旧构造入口使用 classloader/session 共享的 128 个 trace 预算。开关关闭时不计算 output bytes，也不读取 section、格式化输出或注册 trace。

输入快照读取任务现有的 `WorldSlice`，只读该 task 自己的 16³ 区块 section 状态，不重新 prepare、加载 chunk、访问邻区或扫描 live world。`sectionX/Y/Z` 是 RenderSection 的 section 坐标；`blockOriginX/Y/Z` 是对应方块原点；`buildTime` 是构建帧标记，不是耗时。记录字段包括 `inputBlocks`、`inputUnknownStates`、`inputNonAir` 和 `distinctStates`（按 `IBlockState` 对象 identity 计数），`opaqueTrue/False/Unknown`、`fullCubeTrue/False/Unknown`、`renderTypes`（各 `EnumBlockRenderType` 数量和 `unknown`）、`registryNameUnknown`、`metadataUnknown`、`vanillaStateIdUnknown`、`internalStateIdUnknown` 与 `propertyReadFailures`。`topStates` 是按出现数排序的前五种实际 state，包含 `registryName`、`metadata`、`vanillaStateId` 和 `internalStateId`；registry name 只是显示字段，不单独作为“已注册”的判定。注册状态通过 `Block.REGISTRY.getIDForObject(block)` 再以 `getObjectById(id) == block` identity 回查确认；只有通过该回查才查询 vanilla `Block.getStateId`，避免错误报告 defaulted registry 的 AIR fallback ID。`internalStateId` 独立来自 `Block.BLOCK_STATE_IDS.get`，负值记为 unknown；具名但未注册的测试 fixture 中两种 state ID 都是 unknown。每个 `passes` 项包含 `primitive`、`vertices`、`vertexBytes`、`indexBytes` 和 `bytes`；仅当 pass 是 quad primitive 且 vertex 数可整除 4 时计算 `quads`，否则记为 `unknown`。`propertyReadFailures` 只累计每个不同 `IBlockState` 首次读取时实际失败的 getter 次数；没有可用 registry name 时显示 `unknown` 或 `unregistered`。

非零 `traceId` 作为标量 `meshInputTraceId` 随 `ChunkBuildOutput` 经过 `celeritas-common`，不让 common 反向依赖 Actinium 实现类。`phase=builderTaskOutput` 在 build worker 上记录实际输入和输出；`phase=acceptedForUpload` 在 `filterChunkBuildResults` 后、调用 `regions.uploadMeshes` 前记录通过过滤且将要交给 upload 入口的同一输出。接受事件只说明结果通过过滤并准备提交到 `uploadMeshes`，不表示该方法已处理结果，更不表示 GL 调用或 GPU 上传成功。两个阶段各自重新检查 GUI 开关；若开关在构建、扫描或接受之间切换，日志可能只有一侧，缺少配对事件不能据此判断 result 被 stale filter 丢弃。输出度量、section 扫描及 accepted 诊断边界捕获 `RuntimeException` / `Error` 并尝试记录诊断失败，随后继续原有 mesh return / upload 流程；原 meshing 与 upload 操作本身不在诊断 catch 内。这里没有 OOM 恢复逻辑，若错误处理或写日志时再次失败，不能保证诊断失败行成功写出或正常流程继续。

## Terrain arena 与 staging

- `loadedRegions`、`geometryArenaCount`、`indexArenaCount` 表示当前渲染 region 和 arena 数量。`geometryUsedBytes` / `indexUsedBytes` 是已占用的逻辑区段字节数；对应 `*CapacityBytes` 是 arena 请求的 GL buffer store 容量。
- `terrainBufferCapacityBytes` 是 geometry arena、index arena、mapped staging ring 容量和 fallback staging 当前 buffer size 的合计账面值。它表示 GL buffer store 容量请求，不等于显存驻留、驱动实际提交量或进程 private bytes。
- staging 的 `ringPendingUploadBytes`、`ringUnfencedTransferredBytes`、`ringFencedBytes` 分别表示待 flush、已发出但尚未放入 fence、以及等待 fence signal 的 ring 字节。`ringInFlightBytes` 是三类互斥状态的和；fence signal 后对应字节返回 `ringAvailableBytes`。Fallback 的 `fallbackLiveBytes` 是其当前 GL buffer size；upload count/bytes 是累计值，`fallbackResetPending` 表示等待 flush 清空该 buffer。
- `terrain.arenaResize` 在分配新存储前记录 arena 类型、region 坐标、旧/新容量、已用字节、尚待上传的 request bytes 和 copy span 数。`arenaResizeOldAndNewCapacitySumEstimateBytes` 以 resize 前的全局容量加新容量估算旧、新 GL store 短暂并存的候选峰值；这是账面估算，不是实际物理提交量。

启用同一个 GUI 开关时，terrain arena replacement 还会输出 `[ActiniumMemory] terrain.arenaRetirement` 与汇总字段 `arenaRetirement*`。arena 首次创建时登记其 buffer identity；若开关在 buffer 已存在后才打开，下一次 replacement 会用当时的新旧 ID 建立 `baseline=true` 配对，不回溯此前的 lifetime。每次 replacement 在新 storage 分配、旧到新 copy 完成后插入诊断 `GLsync`，位置紧邻原有旧 buffer delete request；探针不会移动、延迟或替代该 delete。GL fence 使用当前 `GlFence.isCompleted()` 的 status 查询（`glGetSynciv`），不等待、不请求 flush、不调用 `glFinish` 或 `glGetError`。每个 share group 每秒最多轮询 128 条，单个 tracker 最多保存 512 条 replacement；超过记录上限的 resize 不创建 fence，累计到 `arenaRetirementCoverageSkippedCount/Bytes`。后端 native-delete listener 使用容量 4096 的无锁事件队列；队列满时累计 overflow，并将可识别的被丢弃 delete identity 标为 unknown；identity 溢出时保守地将当前记录标为 unknown。`arenaRetirementBackendEventOverflowCount`、`arenaRetirementDeleteEventUnknown` 和 coverage-skipped 字段用于判断采样覆盖是否完整，不能把这些采样当成无遗漏账本。

`deleteRequested` 只表示 terrain command list 已走到原本的旧 buffer delete 调用位置；`actualDeleteOutcome=returned` 则来自 glsm 对真实 native `glDeleteBuffers` wrapper 返回的观察，`java-failure` 表示 wrapper 在 Java 侧抛出。它们是不同事件。重定向层可能在内层调用返回后、外层 wrapper 返回时再次发出同一 retirement identity 的 callback；原始 `[native-buffer]` 事件和 glsm wrapper 计数保留这些逐层观察，可能多于唯一的 terrain retirement。tracker 每次 `drainBackendEvents` 最多处理 4096 个 backend event，并只在该批次遇到首个 native delete 时懒建一个至多 4096 项的 identity set；按 `(resizeSequence, registrationToken, bufferId)` 去重同批次的有效 terminal callback。仍被 tracker 保留的 retirement 也会忽略后续重复 terminal callback，并保留首个有效 callback 时间（嵌套成功调用中即更早的内层返回时间）。如果重复 callback 到达时该 retirement 已从 tracker 移除，则它会计入 `unmatchedDeleteEventCount`；没有跨 drain 的全局 tombstone。`arenaRetirementActualDeleteReturnedCount` 与 `arenaRetirementActualDeleteFailureCount` 因而是被成功配对并计入的 retirement identity 计数，不代表唯一驱动删除或物理回收。返回只代表 Java native wrapper 返回，不查询或推断 OpenGL error state；事件会同时记录调用 context 与 buffer 登记的 share group，调用 context 未知时原始 `[native-buffer]` 行仍标成 `unknown`。`arenaRetirementPendingOldCapacityBytes` 是仍有待观察 GPU fence 或 delete outcome 的旧 store 请求容量合计，不是进程/显存 residency。`arenaRetirementDeleteReturnedFencePending` 指 native delete 已返回但 fence 还未由 CPU 观察到 signal；`arenaRetirementObservationUnavailable` 包含 fence 创建不支持、创建失败或 poll 失败的记录。不开启或关闭开关、manager 销毁时会结束观察；只有当前 context 属于该 sync 所属 share group 时才删除 sync。context 不兼容时清理会延后；最后一个 share-group context 已销毁时丢弃诊断引用，不再对失效 context 发 GL 调用。

日志中的 `submittedAtNanos` 是 fence 调用附近的 CPU 单调时间；`lastUnsignaledAtNanos` 是最近一次看到未 signal 的 CPU 查询时间；`observedSignalAtNanos` 是 CPU 首次观察到 signal 的时间上界，不是 GPU 完成的精确时刻。`signalWindowLowerBoundNanos/UpperBoundNanos` 是相对 submit 的观测区间；此前没有未 signal 查询时，下界只使用 submit 时刻。Signal 仅说明插入该 fence 的 command queue 中此前的命令已经完成，不能证明驱动已经物理回收旧 backing store，也不能覆盖其它 shared context 的队列、尚存绑定引用或其它未观测使用者。

采样扫描只在 perf provider 被现有 GUI 开关调用时进行；arena resize 的全局扫描和日志也受该开关门控。诊断代码不改变 upload、现有 staging fence、正常 buffer delete 时点或分配失败处理流程；新增 fence 只供诊断轮询。

## Terrain VAO delete wrapper 边界

同一 GUI 开关开启时，`DefaultChunkRenderer.createRegionTessellation` 为其创建的 terrain VAO 登记一个标量 token，包含 `traceId`、VAO/VBO/EBO 名称和创建时的 `ownerGeneration` / `ownerShareGroup`。VBO/EBO 名称来自 tessellation 已持有的 bindings；不会创建历史对象 map，也不查询 GL 状态。开关关闭时在遍历 bindings 前返回，不登记 token。

`[ActiniumMemory][terrain-vao]` 事件通过 `traceId` 关联：`event=created` 表示登记；`service-call-entered` / `service-call-returned` / `service-call-java-failure` 覆盖 `GlVertexArray.destroyInternal` 中的 `LWJGLServiceProvider.LWJGL` wrapper；`native-call-entered` / `native-call-returned` / `native-call-java-failure` 只覆盖 `Lwjgl3GLRenderBackend` 中匹配 VAO ID 的唯一 `GL30C.glDeleteVertexArrays` 调用。每条事件带 `eventTimeNanos`、owner/caller 的 generation 和 share group、`ownerGenerationMatch` / `ownerShareGroupMatch`、VBO/EBO ID、outcome、failure class 与 wrapper。generation/share-group 缺失时 match 为 `unknown`；同 share group 下 generation 不同会明确记录 `ownerGenerationMatch=false`。

`native-call-returned` 仅表示 LWJGL 的 Java native wrapper 返回，不读取 GL error，不证明驱动已物理回收 VAO 或其 backing storage。该 probe 不添加 GL query、sync、flush，不改变创建、删除或资源释放时点；关开关时不登记 token、不建 delete scope、不生成这些日志。它只观测经过上述 terrain VAO service/native wrapper 路径的事件；LWJGL3Service 的 Apple 分支通过 raw JNI 函数地址调用删除，不经过这里的 GL30C backend hook，因而不在 native-call 覆盖范围内。缺少日志不能证明没有发生删除。

周期 provider 由渲染线程推进，因此主线程卡在 GL 调用或加载工作时，不会按墙钟持续产生每秒采样。NativeBuffer 与结果队列的常驻 peak 计数会保留观察到的最大值，但不记录峰值发生时间。`NativeBuffer` 分配失败快照与 terrain arena 分配前事件都使用 `[ActiniumMemory]` 前缀并写入同一客户端日志，可按日志时间线关联；它们不能替代卡顿期间的持续采样，也不能观测所有没有触发分配/扩容的阻塞窗口。

## Worldgen 输入快照

同一个 **Render Timing Debug** GUI 开关也门控 `[ActiniumMemory] scope=worldgen` 采样。DEBUG 检查在读取 world/chunk、遍历方块、创建累加器或格式化日志之前执行。采样按 `worldSeed + dimension + 8×8 chunk footprint` 确定性地选取每个 footprint 中一个 chunk；负坐标使用 floor division，因此同一世界坐标在 `generate_return` 和 `populate_return` 阶段会落到相同的采样选择。它只观测开关启用后未来实际触发的 RETURN 阶段，不保存 chunk，也不回溯已经生成或加载的区域。

`phase=generate_return` 汇总生成调用实际返回的 `Chunk`，并标记 `newlyGenerated=true`、`currentChunk=returned`。`phase=populate_return` 只通过 `getLoadedChunk(chunkX, chunkZ)` 读取同坐标已加载的 chunk；不会调用可能加载 chunk 的 `getChunk` / `provideChunk`，也不读取相邻 chunk。若当前 chunk 不在 loaded set 中，日志会记录 `currentChunk=unavailable` 和 `sections=none`；否则 `newlyGenerated=unknown`，因为此入口不能确定它是否在本次会话刚生成。已有 chunk 可能只出现 populate 样本；两个阶段不保证都出现，即使坐标相同也不能证明它们引用同一个 `Chunk` 对象。

快照只遍历该 chunk 中 `DepthsUpdateCompat.getMinBuildHeight(world)` 至 `min(0, world.getHeight())` 的负 Y 范围，并按 16-block section 输出 `sectionY`、有效高度和 block 数。每段统计 non-air / unknown material、state 的 opaque 与 full-cube true/false/unknown、各 `renderType` 数量，以及前五种 `{registryName, metadata, count}`；读取失败字段会计入对应 unknown/read-failure 计数。它不调用侧面可见性判断，也不代表跨 chunk 的 render region 组合、最终 mesh、模型额外 quads 或最终裁剪结果。

首次采到的样本还会输出一次 `phase=registry_candidates`，将 `depthsupdate:deepslate` 和 `id913` 作为待核查候选记录各自的 `registryName`、`blockId`、vanilla `stateId`、`internalStateId` 及可解码的内部 block/meta 值。候选字段仅用于对照实际 registry/state 编号，不表示该方块已被认定为根因或配置目标。

## Shader texture 与 GL context

光影包全量 shader compile 与 native OOM 是两个独立的观测问题。一次运行在
`AngelicaJar` 解析 `jar:` CodeSource URI 时记录 `FileSystemNotFoundException`，随后
Complementary Unbound R5.9.3 的预编译日志为
`fullcompile=skipped reason=programBinaryUnavailable`；同一行显示
`saveEnabled=true`、`packSelected=true`、`driverSupported=true`，只有
`diskCacheConfigured=false`。这说明当次全量编译没有启动，原因是磁盘缓存未配置；它本身
不能说明 native OOM 的来源。

启动路径现从 FML indexed mod container 的 `getSource()` 获取 artifact `File`，并将
Minecraft `gameDir` 传给 `AngelicaJar.configureShaderDiskCache`。已安装的 Jar 把缓存放在
`<gameDir>/angelica/shadercache`，salt 由 mod version、Jar 大小和修改时间组成；classes
directory 环境仍禁用磁盘缓存。缓存专项自动验证包括真实配置调用后的 blob 磁盘写入/读取与
`IrisFullShaderCompileTest` 9/9；当次 `check` / `devShadowJar` 结果为 1,251 tests、0 failures、
0 errors、4 skipped，早于本轮 BF 测试。随后包含 BF 修复的统一验证共 266 suites、1,254 tests，
0 failures、0 errors、4 skipped，`check` 与 `devShadowJar` 均成功。这些验证没有启动客户端；需要用户实机再次加载光影包，并确认
`diskCacheConfigured=true` 与全量编译结束日志，才能确认运行时持久化行为。此修复不构成原生
OOM 已解决的证据。

同一周期报告还可包含 `[ActiniumMemory][texture]` 汇总：`renderTargetSets`、`renderTargetObjects`、`renderTargetEstBytes` 和 `renderTargetCreateCount` / `renderTargetResizeCount` / `renderTargetDestroyCount` 描述 Iris 主 render targets；`shadowTargetSets`、`shadowColorBuffers`、`shadowEstBytes` 和 `shadowCreateCount` / `shadowColorBufferCreateCount` / `shadowDestroyCount` 描述 shadow targets；`pbrAtlases`、`pbrAtlasEstBytes` 和 `pbrAtlasUploadCount` / `pbrAtlasCloseCount` 描述 PBR atlas。上述 `*Count` 是从诊断类初始化以来的累计事件数，不会被周期报告清零；对应 `*Sets`、对象数和 `*EstBytes` 是当前逻辑存活 gauge。`pbrImageDecodeCount` 是加载流程中解码的 NativeImage 数量累计值，`pbrImageMaxEstBytes` 是单张已解码图片中最大的 `width × height × 4` 估算值，不是 atlas 数或当前存活图片数。计数器会在 GUI 关闭时继续更新，因而之后开启也能看到累计事件和当前 gauge；关闭时不会遍历资源或生成汇总字符串。

纹理估算使用内部格式的名义每像素字节数和尺寸。Iris 双缓冲 color target 每个算两张纹理，render/shadow targets 各自的两个 owned depth copy 也计入；PBR atlas 按 RGBA8 mip chain 估算。驱动分配粒度、压缩、驻留、临时 old/new 并存和实际提交量不由这些字段表示。

RenderTargets 与 ShadowRenderTargets 仅在构造走到末尾后才登记 aggregate。若构造中途已经创建部分 GL 资源后抛异常，这些部分资源不会进入 gauge，当前构造路径也没有诊断层的清理/回滚计数。新增 shader 单测覆盖 color/depth/mip 字节估算公式；对象构造失败、resize、重复 destroy 和资源全生命周期闭合只做过静态审阅，没有生命周期运行时单测，也没有全局 GL 资源扫描。

`[ActiniumMemory][shader] retainedPrograms[...]` 中 `hitCount`、`missCount`、`deleteCallCount`、`deleteQueueCount` 是进程期累计事件数；`cached`、`pendingDelete` 与 share group 数是当前 gauge，这些名称数不是字节估值。`programBinaryInterval[...]` 是程序二进制缓存区间计数，每次周期读取会 drain/清零 interval；`programBinaryPackTotal[...]` 是当前 pack epoch 的累计快照，不会被周期读取清零，但 pack 汇总 dump 或切换 pack 会结束/清空该 epoch。full compile 的 begin/end/skip 日志会附 pack、folder 数、完成状态和 cache availability。`[ActiniumMemory][context]` 的 create/retire 事件带 native context generation/share group；client arrays VBO 的 retire/share-group migration 事件另带对象名和逻辑 capacity bytes。

Distant Horizons 的 late mixin 仅在 `distanthorizons` mod id 存在且 `dh-buffer-diagnostics` ABI token 通过时加载。ABI gate 从 DH `GLBuffer.class` 资源读取字节码，校验 mixin 依赖字段和方法签名，不初始化或加载 DH 类。周期日志字段为 `dh.glBufferLiveCount`、`dh.glBufferCapacityBytesReported`、`dh.glBufferCapacityBytesReportedPeak`、`dh.glBufferCreateTotal`、`dh.glBufferLiveCountPeak`、`dh.glBufferCapacityChangeTotal`、`dh.glBufferDestroyTotal`、`dh.glBufferCountReported`：live count/容量是当前 ledger gauge，peak 是运行期最大值，create/capacity change/destroy 是从首次 provider 注册以来的累计事件数，`dhReportedBufferCount` 是 DH 自己的 bufferCount 快照。容量取自 DH 的 `size` 字段，不是 OpenGL 查询或实际驱动驻留量。销毁账目只在 `destroyBufferIdNow` 返回后扣减；如果 DH 将删除排队异步执行，待处理删除仍计入 live gauge，诊断不单独统计待删除队列。

`PBRAtlasTexture.upload()` 在分配 atlas 后立刻登记估算；后续 sprite upload 抛异常时 `tryUpload()` 返回 false，`AtlasPBRLoader` 只把成功 atlas 交给 consumer，因此失败对象没有从该路径调用 `close()`。这是已确认的独立生命周期缺口，与 SDL 基准相同；当前崩溃日志没有观察到该失败路径。单个 8192×8192 RGBA8 全 mip atlas 约为 341 MiB，不能解释本次约 30 GiB 的 private/NMT 差额。相关位置：`shader/src/main/java/net/coderbot/iris/texture/pbr/PBRAtlasTexture.java` 的 `upload` / `tryUpload` / `close`，以及 `shader/src/main/java/net/coderbot/iris/texture/pbr/loader/AtlasPBRLoader.java` 的 atlas 接收分支。

## 本次复现证据与边界

现有崩溃发生于 2026-10-22 07:44:57，最终栈为 `GL43C.nglMultiDrawElementsIndirect` 的 terrain shadow draw。`latest.log` 在 07:44:56 先报告 geometry/vertex arena 扩容调用 `glBufferData` 时 GL OOM，后续 invalid copy 与 AV 是其后的失败信号，不能据此单独认定 native 内存来源。

已有 private/native-memory 采样从 5.01 GiB 增至 39.12 GiB；同期 NMT 从 3.87 GiB 增至 7.89 GiB，差额约 30.09 GiB。最大一次 private 增长出现在 07:42:53.981 到 07:43:02.058（+14.501 GiB），NMT 同期约减少 134 MiB；之后 private 再增约 13.166 GiB，而 NMT 约减少 17.6 MiB。结束时系统 commit 为 61.25 / 62.29 GiB。原始采样文件为 `.tmp/native-memory-capture/native-memory-11828-20261021-234225-814.csv` 及同名 `.nmt.txt`。

该区间包括 07:42:41 进入世界、07:42:47–50 加载 ComplementaryUnbound R5.9.3，以及 07:43:00–01 Depths Update cascading generation warnings。时间相邻不能证明这些事件导致内存增长。现有运行没有 F3 renderer live counter 或本页新增字段的同期样本，因此目前无法将 private 增长归因到 NativeBuffer、GL driver、光影 shader/texture、JNI 或其他 allocator。NMT 与 private bytes 的差额也不能单独当作 GPU 驱动归因证据。`scratchBytes` 和 `buildResultBytes` 是 `nativeBufferBytes` 的子集；terrain 容量与 shader texture bytes 是 GL 逻辑容量估算。它们都不能与 private/NMT 作精确加和或当作进程内存闭合账本。

本页记录诊断能力，不表示已修复暴涨。第一批诊断能力的历史验证没有启动 client，也没有实机复现；当时统一 `./gradlew check --no-daemon` 已通过（1217 tests，0 failures/errors，4 skipped）。该结果只覆盖当时的第一批诊断改动，不代表本轮新增 mesh/VAO probe 已通过构建或测试；本轮验证结果应单独记录。仍需在用户实机复现并采集新增日志后再验证因果链。

随后一次采样（PID 13468，2026-10-22）记录到 JVM malloc fatal 与系统 commit 接近上限。`08:40:09–08:40:47` 间，Private−NMT 差额增加 26.078 GiB；同期当前 geometry arena capacity 增加 10.395 GiB，而 geometry growth 事件的 `ΣnewCapacityBytes` 增加 26.074 GiB。该段共有 347 次 geometry growth（57 次首次分配、290 次 replacement）；`ΣoldCapacityBytes=17.119 GiB` 是累计 replacement 输入，不能解释为同时存活或泄漏容量。采样末尾 NativeBuffer live 为 318 MiB、峰值 563 MiB；terrain geometry 为 57 个 arena、used `9,557,584,256 B`（8.90 GiB）/ capacity `12,225,626,028 B`（11.39 GiB），index capacity 14 MiB，staging 16 MiB 且无 fallback；DH reported capacity 232 MiB，shader texture estimate 586 MiB 且稳定。原始采样为 `.tmp/native-memory-capture/native-memory-13468-20261022-003939-911.csv` 和同目录 `.nmt.txt`，日志为 `run/client/logs/latest.log` 与 `debug.log`。这些容量和时间关联仍不证明 backing store 没有执行 delete，也不能区分 GPU queue 未完成、驱动缓存或其它 native allocator；本轮新增 fence/delete 探针需要下一次实机采样提供关联证据。

另一次复现（PID 13892，2026-10-22）在约 72 秒的 11 个 private-memory 样本中，从 5.40 GiB 增至 37.87 GiB；末次 NMT committed 为 7.90 GiB，Windows system commit 为 62.09 / 62.33 GiB。`latest.log` 于 10:39:16 先在 buffer data 分配报告 `GL_OUT_OF_MEMORY`（line 56511），随后出现两次 `GL_INVALID_VALUE` size/offset（56549、56587），最终 native crash 栈落在 terrain shadow draw 的 `GL43C.nglMultiDrawElementsIndirect`。错误顺序显示 buffer allocation OOM 先于后续 invalid calls 与 shadow draw 崩溃，但不能据此证明私有内存增长的来源或唯一因果链。

崩溃附近的 memory 汇总为 geometry used/capacity `9,326,058,544 / 11,649,227,348 B`，`NativeBuffer` live/peak `240,863,928 / 567,664,664 B`，arena retirement pending count/bytes 均为 0。按 `(resizeSequence, registrationToken, bufferId)` 归并后有 382 个 unique delete-returned identity，与 382 个观察到的 fence signal 对应；该旧运行原始 tracker 汇总仍显示 764 次嵌套 wrapper success callback。新版 tracker 在同一 backend-event drain 中最多处理 4096 个事件，并按该 identity 对重复 terminal callback 只计一次、保留首个有效 callback 时间；若重复 callback 晚于对应 retirement 移除才到达，则会计入 unmatched，且不维护跨 drain tombstone。原始 `[native-buffer]` / GLSM wrapper 事件仍记录各层回调，不能声称它们已去重。fence signal 与 Java wrapper return 仍不证明物理回收。

PID 13892 的采集尚无本轮新增的 `scope=chunk_mesh` 或 `terrain-vao` 事件，因此这些诊断不能回溯解释这次 OOM。修复是否消除原暴涨或 native crash 仍须由用户实机复测确认。

随后 PID 5052 的采样（2026-10-22，约 45 秒、7 个 private-memory 样本）从 6.56 GiB 增至 35.04 GiB；末次 NMT committed 为 6.77 GiB，Private−NMT 差额约 28.27 GiB，Windows commit 为 `63,933,337,600 / 66,877,648,896 B`，接近上限。`scope=chunk_mesh` 记录到 128 个 `builderTaskOutput` 与 128 个 `acceptedForUpload`，trace ID 一一对应；这只说明结果通过过滤并到达 `uploadMeshes` 入口，不代表 GPU upload 成功。71 个负 Y section 样本中有 69 个以 `botania:biomestoneb5slab` 为 top state。trace 125 的 section `(12, -2, 2)` 含该 slab 3,710 个，solid pass 记有 44,930 quads、`cutout_mipped` 44 quads。源码路径显示 Better Foliage 的额外 cutout 调度可能让普通 block fallback 重复构建；`BakedQuadGroupAnalyzer` 又可将不透明 sprite quad 选入 solid material。这使 slab layer fallback 成为高 mesh 体积的具体候选机制，但 trace 的 section 汇总不能把全部 solid quads 归因到这些 slab，也不能解释 Private−NMT 差额全量。

同一 PID 5052 采样记录 499 个 terrain VAO 创建事件和 427 个 native delete wrapper 返回事件，差额 72 对应该观察窗口内仍存活的 VAO；这些事件的 owner/caller generation 与 share group 均匹配。350 个旧 buffer retirement 同时观察到 delete 返回和 fence signal。wrapper 返回与 fence signal 均不能证明驱动已物理回收 backing storage；这组数据没有证明原生 OOM 已解决。原始采样为 `.tmp/native-memory-capture/native-memory-5052-20261022-035300-360.csv` 与同目录 `.nmt.txt`，日志为 `run/client/logs/latest.log`。

## 下一步取证

下一次实机复现时先启用上述 GUI 开关，并保留同一时间基准的 `[ActiniumMemory]` 日志、private bytes 和 NMT 采样。除 NativeBuffer、scratch、结果队列、terrain capacity、shader texture 与 DH `GLBuffer` capacity 外，按 `traceId` 对照 `chunk_mesh` 的 builder input/output 与 accepted-for-upload 记录；对 terrain VAO 按 `traceId` 和 `eventTimeNanos` 关联 create、service wrapper、native wrapper 事件，并检查 owner/caller generation 与 share group。accepted-for-upload 不表示 GL 成功；VAO wrapper return 不表示物理回收。若新 probe 缺少事件，要同时检查 GUI 开关、128 条采样预算、filter 结果和观测覆盖，再判断日志能说明什么；不要从 Private−NMT 差额单独推断归属。

## Private 增长的账本归属（PID 41904，2026-10-22）

把 PID 41904 的采样与本轮 `terrain.arenaResize`、GL 计数器对齐后，Private 增长可以被单一机制闭合，不需要再假设未知 allocator 或驱动缓存：

| 账面 | 值 |
|---|---:|
| Private 首末样本 | 9.38 → 37.51 GiB |
| 其中 java 堆（NMT committed） | 5.97 GiB |
| 其中其它 NMT 原生类别 | 1.48 GiB |
| 其中 GPU 计数器（Dedicated + Shared） | 11.70 GiB |
| **无法由上述账本解释的余量** | **18.37 GiB** |
| **被替换下线的旧 geometry arena 存储量（ΣoldCapacity）** | **18.17 GiB** |

复算方式：`ΣnewCapacity − 当前 globalGeometryCapacityBytes` = `29.30 − 10.89` = **18.40 GiB**，与上表余量相差 0.04 GiB。这一条不能当作独立证据：同一 arena 的扩容是链式的（本次新容量就是下次被替换的旧容量），所以 `ΣoldCapacity = ΣnewCapacity − 当前容量` 是代数恒等式，两个数字必然一致。独立成立的是量级对应关系——GPU 计数器（11.70 GiB）接近当前存活容量（10.89 GiB），说明 GPU 账本只收录存活容量、不含已下线的旧存储；而进程 Private 比这份账本多出的 18.37 GiB，与被替换下线的 18.17 GiB 同阶且几乎相等。

机制：`GlBufferArena.transferSegments` 每次扩容都新建 dst buffer、`glBufferData` 申请完整新容量、逐段 `copyBufferSubData` 复制旧数据，然后 `deleteBuffer(srcBufferObj)` 删除旧 buffer。本轮 382 次 geometry 扩容共签发 **29.30 GiB** 新存储、复制了 **18.17 GiB** 旧数据，但 `arenaRetirementActualDeleteReturnedCount=496`、`ActualDeleteFailureCount=0` 只证明 Java native wrapper 返回，`arenaRetirementObservedSignalCount=495` 只证明 fence 观察到 signal；两者都不证明驱动已释放 backing storage。旧存储因此在这个约 54 秒的窗口里持续累积。

放大器在扩容策略本身：382 次扩容的实际请求合计只有 **1,532 MiB**（平均 4.01 MiB），却签发了 **29,999 MiB** 新容量，**19.6 倍**放大。`growthTargetFor` 对大 arena 只用 `base + base/2`，只要剩余空间不足就整块重分配，没有把多个小请求合并成一次扩容。填充分布显示 279 次扩容发生在 `used/old ≥ 0.9`，说明这些不是碎片驱动的浪费性压缩，而是被逐次小请求推着走。

因此 "Private−NMT 差额" 不应再被归因为未知来源：它是 terrain arena 扩容换下的旧存储累积。仍需实机确认的是驱动为何没有在 `glDeleteBuffers` 之后回收这批存储，以及是否需要在扩容前后主动同步或批量合并扩容来避免峰值叠加。
