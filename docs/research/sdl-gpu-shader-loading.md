# 光影包加载 / 启动提速（上游 #2190 + #2213 移植对账）

> 状态：**部分落地**（2026-10-12）。本文档记录上游 `38b1d9a0`「Speed up boot (#2190)」与
> `38af64f1`「Initial commit for shader-loading-optimizations (#2213)」在 Actinium 的
> **逐文件对账结果**：哪些早前 Phase 已同步、本次补了什么、哪些因缺少上游前置提交而**受阻**
> 未移植。对账以当前工作树（`research/sdl-gpu-port`）为准。

## 0. 结论摘要

| 部分 | 状态 |
|---|---|
| #2190 的性能与 PBR 部分（GLSL 重命名快路径、PBR 资源索引） | **本次落地** |
| #2190 的 Iris 预热重构 | **受阻**：依赖未移植的「早期 shader 变换预热」链路（上游 #1993/#2009） |
| #2213 的 glsm / SDL 阶段缓存 / 预处理链 | **早前 Phase 已同步**（本次仅补两处未接线点） |
| #2213 的「已编译程序落盘缓存」主体 | **受阻**：依赖未移植的上游前置提交 `0a4d4896`「Cache Iris Shaders (#2206)」 |
| mcpatcher / RFB / Tracy 相关 | **跳过**（本仓库无对应物） |

## 1. #2190（`38b1d9a0`）

### 1.1 本次落地

- **`glsm/GlslTransformUtils`**：保留字重命名正则由 `\bsample\b` 改为 `sample(?<=\bsample)\b`
  （先匹配字面量再断言词边界）。这就是「boot speed」里的一项：前导 `\b` 会让正则引擎在每个
  输入位置尝试，而光影包源码很长。语义等价由 `ReservedWordRenameTest` 锁定（边界用例 +
  5000 组随机输入与旧实现逐一对比），`RENAMED_PREFIX` 因此由 `private` 放宽为包内可见。
- **`glsm/hooks/GLSMHooks.uniformBlockBarrier`** + **`sdlgpu/shader/ShaderManager`** 的
  `shaderSource` 消费点：非 worker 线程在丢弃/重建 SPIR-V future 之前，先等持有 per-frame
  uniform block 状态的预热结束。**注意：上游由 Iris 的早期变换预热设置该 barrier，而 Actinium
  没有那条链路（见 §3），因此该字段目前无人设置，SDL 侧的调用以 `!= null` 守卫、行为不变。**
  保留它是为了后端表面与上游一致，等预热链路移植时直接接线。
- **`iris/texture/pbr/PBRResourceIndex`（新，约 260 行）**：block 图集的每个 sprite 都会问
  「有没有 `_n`/`_s` 贴图」，旧实现是直接尝试加载，于是不支持 PBR 的包要为每个 sprite、每种
  类型付一次失败查找（含异常构造）。索引在 worker 线程上对每个资源包扫描一次，按名字集合回答；
  `mayExist` 结果的 memo（`#2213` 的 +13 也一并落地）避免同一次缝合里重复回答。
  两个加载器（`AtlasPBRLoader`/`SimplePBRLoader`）在触及资源管理器之前先问索引。
  **它只是优化，不是正确性门**：名字不匹配、未扫描到、扫描失败一律回「可能」，退化为旧行为。
- **资源重载接线**：`PBRTextureManager.onResourceManagerReload(resourceManager)` = `clear()` +
  `PBRResourceIndex.start(...)`，挂到 `Actinium.onInit` 的 `registerReloadListener`。
  上游是在 mcpatcher 的 `TexturePackChangeHandler` / `MixinTextureManager_ReloadCount` 里做同一件事
  （本仓库没有这两个东西），这里是 Actinium 的等价接法。
  ⚠️ 顺带修掉一个既有缺口：`PBRTextureManager.clear()` 此前没有任何生产调用点，换资源包后 PBR
  贴图不会失效。
- **测试**：`ReservedWordRenameTest`（根测试树，glsm 无 test source set）、
  `PBRResourceIndexTest`（7 例：跨包类型/域名与 `getResource` 一致、存在性、非 PBR 名不误判、
  懒建、重载后新包可见、扫描失败降级、zip 共享同一 `ZipFile`）。
  测试里用 `PBRResourceIndex.drop()`（包内可见）替代上游的 `Reflect.invokeStatic`（AGENTS.md 禁测试反射）；
  `assertAgrees` 在**大小写不敏感文件系统**（Windows/macOS）上容忍索引更宽松的方向，并显式断言这只
  发生在 `index=true & manager=false` 时。

### 1.2 受阻未移植

- **`iris/Iris.java`（+30/-11）的预热重构**：把 `currentPack.getProgramSet(...)` +
  `PerFrameUniformBlockHarvester.harvest` + `DeferredWorldRenderingPipeline.warmupTransforms`
  从加载路径挪到 worker 线程，并让 `createPipeline`/`destroyEverything` 通过
  `awaitProgramSetWarmup()` 汇合。Actinium **完全没有**这条链路：`warmupShaderTransforms`、
  `PerFrameUniformBlockHarvester`（上游 #1993 `8da561d3`）、
  `DeferredWorldRenderingPipeline.warmupTransforms`、`IrisGLSMBridge.installPostTransformHook`
  （上游 #2009 `b17bb42c`）都不存在。因此本次只落了 barrier 的消费侧。
- `IrisProgramSetWarmupTest`（+57）测的就是上面这条链路，一并跳过。
- `Mixins.java`（±2）：Actinium 通过 json 配置注册 mixin，无此文件。
- mcpatcher 四文件（`TexturePackChangeHandler` 的 deferral、`CITUtils`/`CTMUtils`/`TileLoader`）
  与 `TexturePackChangeHandlerDeferralTest`：本仓库没有 `com.prupe.mcpatcher` 包。

## 2. #2213（`38af64f1`）

### 2.1 逐文件对账：早前 Phase 已同步（本次核对确认，无需改动）

- glsm：`GlslTransformUtils.containsWholeWord` 早退、`threading/AngelicaWorkers.invokeAll`、
  `shader/ShaderDiskCache.configure` 幂等 + layer API（`touchLayer`/`keepNewestSubLayers`/
  `retainLayer`/`deleteLayer`/`remove`）。
- shader 预处理链：`IncludeGraph`（AngelicaWorkers 并行读）、`IncludeProcessor`（并发 + hash）、
  `PreprocessedSourceCache`、`PropertiesPreprocessor`（Actinium 保留 LexerException 容错偏差）、
  `JcppProcessor`（内容哈希 + NUL 剥离）、`pipeline/transform/ShaderTransformer`（image-store
  提取收敛）、`gl/GlResource` 的 `isValid` 守卫、`IdMap.hasLegacySection`（Actinium 是重写实现，
  上游 `detectLegacySection` 本次改动 **N/A**）。
- sdlgpu：`shader/ShaderManager`（`QueuedCompile` 可取消/可强制执行的编译排队、
  `registerSamplerLocations` 未用 sampler 处理、`variantKey` 顶点变体落盘缓存）、
  `shader/ShaderStageSerializer`（`unusedSamplerNames`）、`resource/ResourceManager`
  （`fillBuffer` 模式重复上传、`zeroTexture3D`）、`SDLGPURenderBackend`
  （`FILL_CHUNK_BYTES`/`isAllZero`/clear 走 `fillBuffer`、3D 纹理零填充），
  以及 `UnusedSamplerReflectionTest` 与三处测试微调。

### 2.2 本次补的缺口

- **`shaderpack/ConstDirectiveParser`**：`findDirectives` 改为扫 `const` 出现位置并扩展到整行，
  不再 `source.split("\\R")`（每个包 shader 都要过这里，旧实现会为整个文件分配行数组）。
  行终止符判定内联 `isLineBreak`（等价 `\R` 的字符集合）。
- **`shaderpack/ShaderPack`**：源码提供器改用 `JcppProcessor.glslPreprocessSource(contentHash, supplier, defines)`。
  **这是把早已存在的预处理磁盘缓存真正接上**：此前 Actinium 调用的是无哈希重载，`PreprocessedSourceCache`
  永远查不到/写不进（缓存是惰性的），现在按被包含文件的内容哈希作为键，未变动的包重载直接命中。
- **`glsm .../utils/AngelicaJar`（新）+ 磁盘缓存启用**：`AngelicaJar.location()` 取本 mod 的
  jar/classes 路径，`configureShaderDiskCache(jar)` 以 `Tags.VERSION|jar 长度|jar mtime` 为 salt
  配置 `<gameDir>/angelica/shadercache`（dev 环境是目录，`isFile()` 为假 → 自动不启用）。
  在 `Actinium.onInit` 的 Iris 初始化之前调用，**这让已存在的 GLSL/SPIR-V/预处理缓存从"从不启用"
  变成生产可用**（上游在 postInit 做同一件事，Actinium 没有 ClientProxy.postInit 这一层）。
- **`PBRResourceIndex` 的 memo 增量**（上游 #2213 对该文件的 +13）。

### 2.3 受阻未移植（需要上游前置提交）

**`0a4d4896`「Cache Iris Shaders (#2206)」（+1324/-524，21 文件）从未同步进 Actinium**，而
#2213 是它的演进。缺失的整块能力：

| 缺失 | 说明 |
|---|---|
| `glsm/shader/ProgramBinaryCache`（255 行） | `glGetProgramBinary` 落盘/回读，含 layer 保留策略 |
| glsm GL 表面 | `GLStateManager.glProgramParameteri/glProgramBinary/glGetProgramBinary`、`RenderSystem.supportsProgramBinary()`、`RenderBackend` + Lwjgl3/Lwjgl2 后端实现（Actinium 全无引用） |
| `iris/gl/program/RetainedPrograms`（+60） | 跨包重载保留已链接程序的 `take/retain` 生命周期 |
| `ProgramBuilder`/`Program`/`ComputeProgram`（+37/+12/+12） | `retainAs(sources)` + `saveRetained`/`isFullCompileRunning` 接线 |
| `Iris.java`（+178/-16） | `prepareSelectedShaderPack()`（主菜单后台预读选中包）、`currentPackState` 指纹、`takePreparedPack`、`RetainedPrograms.useConfiguration`、`currentPack.activate()` |
| `iris/ShaderWarmup`（+96）、`ClientProxy` 接线 | 主菜单时后台预载 shader 类 + 预读包 |
| `DeferredWorldRenderingPipeline`（+70/-31，且 #2206 自身重写了 942 行） | 程序持久化/全量编译模式 |
| `ShaderPack`（+89/-29 中与 `activate()`/`profiles` 懒加载相关的部分）、`CustomUniforms.mapPassLike` | 配合上面 |
| 测试 `PackSettingsFingerprintTest`、`ProgramBindingRegressionTest` | 测上面这些 |

**未移植的原因**：#2206 含一次 942 行的 `DeferredWorldRenderingPipeline` 重写，而 Actinium 的
同一文件已有大量自有改动（SDL 后端接线、voxelization compute、Actinium 专属 pass 表等），
本分支又无法做运行时验证；盲目跟改的风险远大于收益。建议单独排一个「#2206 移植」任务，
届时再把 #2213 的这半块接上（`saveRetained`/`isFullCompileRunning` 就在本次记录的上游 hunk 里）。

## 3. 跳过项汇总

| 项 | 理由 |
|---|---|
| `com/prupe/mcpatcher/**`（4 文件 + 1 测试） | 本仓库无 mcpatcher |
| `Mixins.java` | Actinium 用 json 配置注册 mixin |
| `TracyFramePlots`、RFB 相关 | 无对应物 |
| `IdMap.detectLegacySection` 快扫 | Actinium 的 `IdMap.hasLegacySection` 是重写实现，方法不存在 |
| `#2190` 的 Iris 预热重构、`IrisProgramSetWarmupTest` | 依赖未移植的预热链路（#1993/#2009） |
| `#2213` 的程序落盘缓存与预热接线（见 §2.3） | 依赖未移植的 #2206 |

## 4. 实机验证建议（启动 / 包加载耗时是验收指标）

1. **缓存启用证据**：启动日志应出现 `Shader disk cache at <...>/angelica/shadercache`
   （`ShaderDiskCache` 的 INFO）。若日志出现 `Shader disk cache disabled` 则是 IO 失败，需看堆栈。
2. **启动耗时**：以「启动器 launch 到主菜单可交互」的墙钟时间为准，对比改动前后的中位数
   （至少 3 次，首次启动会建缓存，应分开记录冷/热两次）。
3. **光影包加载耗时**：切换光影包时对比 Iris 的 `[Load #N] Transformed shader for ...` 日志耗时之和；
   第二次加载同一包（缓存命中）应明显低于第一次。`PreprocessedSourceCache` 命中不单独打日志，
   可用 `angelica.shadercache` 目录里 `iris-preprocess` 层文件数量作为间接证据。
4. **PBR 索引正确性**：装一个带 PBR 资源包（如 Stratum/Patrix 类）的整合包，确认方块 normal/specular
   在换包后立即生效（不再需要重启），且日志无 `PBR resource index disabled` 警告。
   若出现该警告，说明索引已降级为直接查找（功能正确、只是变慢），把堆栈报上来。
5. **回归**：换资源包（F3+T / 选项里切换）后贴图、PBR、光影包都正常；本地 zip 包与文件夹包都要试。

## 5. 遗留风险

- `uniformBlockBarrier` 目前**无人设置**（消费侧在 SDL `ShaderManager`），预热链路移植前它是惰性 API。
- 索引在**大小写不敏感文件系统**上比资源管理器更宽松（可能多一次失败加载），这是刻意的降级方向，
  已在测试中显式断言。
- 磁盘缓存启用后会在实例目录写文件（上限 256 MB，salt 变化时整层清除）；dev 环境不启用。
- #2213 的 SDL 阶段缓存（`variantKey` 等）此前已在，但**在磁盘缓存启用之前一直是惰性的**——
  本次启用后它们才真正生效，属于行为变化，需在实机确认无陈旧缓存问题（salt 含 jar mtime，
  换 jar 会整体失效）。
