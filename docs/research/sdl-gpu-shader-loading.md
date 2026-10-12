# 光影包加载 / 启动提速（上游 #2190 + #2213 移植对账）

> 状态：**主体已落地**（2026-10-12）；`#2213` 依赖 `0a4d4896` 的「已编译程序落盘缓存」半块
> 已于 2026-10-12 补齐（见 §2.3 与 `sdl-gpu-program-binary-cache.md`）；#2190 剩余半块
> （Iris 预热重构）所依赖的**早期 shader 变换预热链**已于 2026-10-12 接上（见 §6）。
> 本文档记录上游
> `38b1d9a0`「Speed up boot (#2190)」与
> `38af64f1`「Initial commit for shader-loading-optimizations (#2213)」在 Actinium 的
> **逐文件对账结果**：哪些早前 Phase 已同步、本次补了什么、哪些因缺少上游前置提交而**受阻**
> 未移植。对账以当前工作树（`research/sdl-gpu-port`）为准。

## 0. 结论摘要

| 部分 | 状态 |
|---|---|
| #2190 的性能与 PBR 部分（GLSL 重命名快路径、PBR 资源索引） | **本次落地** |
| #2190 的 Iris 预热重构 | **2026-10-12 已接上**（其依赖的预热链同时补齐，见 §6） |
| #2213 的 glsm / SDL 阶段缓存 / 预处理链 | **早前 Phase 已同步**（本次仅补两处未接线点） |
| #2213 的「已编译程序落盘缓存」主体 | **2026-10-12 已接上**（连同其前置提交 `0a4d4896`，见 §2.3 与 `sdl-gpu-program-binary-cache.md`） |
| 预热链：#1993 的 `PerFrameUniformBlockHarvester` + 变换后钩子、#2009 的 `warmupShaderTransforms` | **2026-10-12 已落地**（见 §6） |
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

### 1.2 受阻未移植 → **2026-10-12 已接（见 §6）**

- **`iris/Iris.java`（+30/-11）的预热重构**：把 `currentPack.getProgramSet(...)` +
  `PerFrameUniformBlockHarvester.harvest` + `DeferredWorldRenderingPipeline.warmupTransforms`
  从加载路径挪到 worker 线程，并让 `createPipeline`/`destroyEverything` 通过
  `awaitProgramSetWarmup()` 汇合。当时 Actinium **完全没有**这条链路：`warmupShaderTransforms`、
  `PerFrameUniformBlockHarvester`（上游 #1993 `8da561d3`）、
  `DeferredWorldRenderingPipeline.warmupTransforms`、`IrisGLSMBridge.installPostTransformHook`
  （上游 #2009 `b17bb42c`）都不存在。因此当时只落了 barrier 的消费侧；
  **上述整条链路已在 §6 补齐，#2190 的这部分随之落地。**
- `IrisProgramSetWarmupTest`（+57）测的就是上面这条链路，**已按 Actinium 规范重写**（去反射，
  直接调用包内可见的 `Iris.awaitProgramSetWarmup`/`programSetWarmup`）。
- `Mixins.java`（±2）：Actinium 通过 json 配置注册 mixin，无此文件。
- mcpatcher 四文件（`TexturePackChangeHandler` 的 deferral、`CITUtils`/`CTMUtils`/`TileLoader`）
  与 `TexturePackChangeHandlerDeferralTest`：本仓库没有 `com.prupe.mcpatcher` 包。

## 2. #2213（`38af64f1`）

### 2.1 逐文件对账：早前 Phase 已同步（本次核对确认，无需改动）

- glsm：`GlslTransformUtils.containsWholeWord` 早退、`threading/AngelicaWorkers.invokeAll`、
  `shader/ShaderDiskCache.configure` 幂等。
  ⚠️ **勘误（2026-10-12）**：本节原先把 `ShaderDiskCache` 的 layer API
  （`touchLayer`/`keepNewestSubLayers`/`retainLayer`/`deleteLayer`/`remove`）与
  `gl/GlResource` 的 `isValid` 守卫一并记为已同步，核对后确认**当时并不存在**（只有 `configure`
  幂等）。两者已随程序落盘缓存一起补齐（见 `sdl-gpu-program-binary-cache.md` §2.1、§6）。
- shader 预处理链：`IncludeGraph`（AngelicaWorkers 并行读）、`IncludeProcessor`（并发 + hash）、
  `PreprocessedSourceCache`、`PropertiesPreprocessor`（Actinium 保留 LexerException 容错偏差）、
  `JcppProcessor`（内容哈希 + NUL 剥离）、`pipeline/transform/ShaderTransformer`（image-store
  提取收敛）、`IdMap.hasLegacySection`（Actinium 是重写实现，
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

### 2.3 受阻未移植（需要上游前置提交）→ **已于 2026-10-12 接上**

**`0a4d4896`「Cache Iris Shaders (#2206)」（+1324/-524，21 文件）从未同步进 Actinium**，而
#2213 是它的演进。当时缺失的整块能力：

| 缺失 | 说明 | 现状（2026-10-12） |
|---|---|---|
| `glsm/shader/ProgramBinaryCache`（255 行） | `glGetProgramBinary` 落盘/回读，含 layer 保留策略 | **已移植** |
| glsm GL 表面 | `GLStateManager.glProgramParameteri/glProgramBinary/glGetProgramBinary`、`RenderSystem.supportsProgramBinary()`、`RenderBackend` + Lwjgl3/Lwjgl2 后端实现（Actinium 全无引用） | **已移植**（Lwjgl2 无对应物，SDL 后端按默认 false 回退） |
| `iris/gl/program/RetainedPrograms`（+60） | 跨包重载保留已链接程序的 `take/retain` 生命周期 | **已移植** |
| `ProgramBuilder`/`Program`/`ComputeProgram`（+37/+12/+12） | `retainAs(sources)` + `saveRetained`/`isFullCompileRunning` 接线 | **已移植** |
| `Iris.java`（+178/-16） | `prepareSelectedShaderPack()`（主菜单后台预读选中包）、`currentPackState` 指纹、`takePreparedPack`、`RetainedPrograms.useConfiguration`、`currentPack.activate()` | **已移植**（`prepareSelectedShaderPack` 的前置条件按 Actinium 的延迟加载改造） |
| `iris/ShaderWarmup`（+96）、`ClientProxy` 接线 | 主菜单时后台预载 shader 类 + 预读包 | **已移植**（触发点改为 `MixinGuiMainMenu.initGui`） |
| `DeferredWorldRenderingPipeline`（+70/-31，且 #2206 自身重写了 942 行） | 程序持久化/全量编译模式 | **已 reconcile**（构造失败回收 + 同源程序共享；instancing 相关 hunk 因本仓库无 instancing 跳过） |
| `ShaderPack`（+89/-29 中与 `activate()`/`profiles` 懒加载相关的部分）、`CustomUniforms.mapPassLike` | 配合上面 | **已移植** |
| 测试 `PackSettingsFingerprintTest`、`ProgramBindingRegressionTest` | 测上面这些 | `PackSettingsFingerprintTest` **已按 Actinium 重写**（去反射）；`ProgramBindingRegressionTest` 无对应文件，**跳过** |

**当时未移植的原因**：#2206 含一次 942 行的 `DeferredWorldRenderingPipeline` 重写，而 Actinium 的
同一文件已有大量自有改动（SDL 后端接线、voxelization compute、Actinium 专属 pass 表等），
盲改风险大于收益。2026-10-12 的移植把这次重写拆成「构造失败回收（缩进位移）+ 同源程序共享」
两处语义增量逐项裁决，明细、偏差与验证方式见 `docs/research/sdl-gpu-program-binary-cache.md`。

## 3. 跳过项汇总

| 项 | 理由 |
|---|---|
| `com/prupe/mcpatcher/**`（4 文件 + 1 测试） | 本仓库无 mcpatcher |
| `Mixins.java` | Actinium 用 json 配置注册 mixin |
| `TracyFramePlots`、RFB 相关 | 无对应物 |
| `IdMap.detectLegacySection` 快扫 | Actinium 的 `IdMap.hasLegacySection` 是重写实现，方法不存在 |
| `#2190` 的 Iris 预热重构、`IrisProgramSetWarmupTest` | ~~依赖未移植的预热链路（#1993/#2009）~~ → 2026-10-12 已随预热链一起接上（见 §6） |
| `#1993` 的 `Previous` 矩阵共享、`EntityIdHelper` 缓存、uniform 更新监听器复用 | 与预热无关的性能改动，不属于本次移植范围 → **2026-10-12 已由 Phase 12（`0bd51b55`）补齐**，见 `sdl-gpu-upstream-diffs.md` |
| `#1993` 的 GLES 预热（`CompatShaderTransformer.prewarm`） | 本仓库的 `CompatShaderTransformer` 没有该方法；它服务于 GLES 路径，SDL/GL 桌面路径用不到 |
| `#2009` 的 vsync on/mailbox/off、GLSL 解析优化（`StageArtifact` 单次解析交接） | 不属于预热链（详见 §6.4） |
| `#2213` 的程序落盘缓存与预热接线（见 §2.3） | ~~依赖未移植的 #2206~~ → 2026-10-12 已随 #2206 一起接上 |
| `0a4d4896` 的 `Lwjgl2GLRenderBackend`（+24） | 本仓库无 LWJGL2 后端实现 |
| `0a4d4896` 的 mcpatcher/RFB/Tracy 相关与 `Mixins.java` | 无对应物 |
| `#2213` 的 `ProgramBindingRegressionTest`（2 行） | 该测试文件在 Actinium 不存在 |

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
6. **程序落盘缓存（核心验收）**：见 `sdl-gpu-program-binary-cache.md` §7.2——打开 Shader Pack 界面里新增的
   `Save Compiled Shaders` 开关后，**重启游戏**再进同一世界，`[Load #N] Total shaderpack load time`
   应显著低于首次。
7. **预热链（§6）**：
   - 主菜单里选中光影包（触发一次 `Iris.reload()`）后进世界：`[Load #N] Total shaderpack load time`
     应低于未预热时；`GLSMPerfDebug` 打开时可见 `[ShaderTransformCache] graphics hit ...` 成片出现。
   - **世界内切包**（光影设置里换包）后首帧不应出现明显长卡顿；换回原包同理。
   - **SDL 后端（核心行为变化）**：进世界后画面与光影选项全部正常，尤其检查随时间/天气/雾/相机
     变化的 uniform（`frameTimeCounter`、`sunPosition`、`fogColor`、`cameraPosition`、
     `gbufferModelViewProjection*`）是否仍然正确——这些现在经 per-frame uniform block 而不是松散
     uniform 下发。日志不应出现 `Failed to harvest the shared uniform blocks`。
   - 关掉光影（`setShadersDisabled`）后仍能正常渲染：此时 block 由 `createPipeline` 清空。

## 5. 遗留风险

- ~~`uniformBlockBarrier` 目前无人设置~~ → **2026-10-12 已由 `Iris.warmupShaderTransforms()` 设置**
  （见 §6）。仍存的风险是预热失败时它与 `PerFrameUniformBlockHarvester.clear()` 的时序，见 §6.6。
- 索引在**大小写不敏感文件系统**上比资源管理器更宽松（可能多一次失败加载），这是刻意的降级方向，
  已在测试中显式断言。
- 磁盘缓存启用后会在实例目录写文件（上限 256 MB，salt 变化时整层清除）；dev 环境不启用。
- #2213 的 SDL 阶段缓存（`variantKey` 等）此前已在，但**在磁盘缓存启用之前一直是惰性的**——
  本次启用后它们才真正生效，属于行为变化，需在实机确认无陈旧缓存问题（salt 含 jar mtime，
  换 jar 会整体失效）。
- 程序落盘缓存（2026-10-12 新增）引入的风险集中在 `DeferredWorldRenderingPipeline` 构造失败回收、
  同源程序共享的生命周期，以及「改设置时一次性编译所有维度」的耗时变化，详见
  `sdl-gpu-program-binary-cache.md` §8。

## 6. 预热链补齐（2026-10-12，`#1993` + `#2009` 预热部分 + `#2190` 剩余半块）

上一环把 #2190 的 Iris 预热重构记为受阻，因为它的整条前置链路从未移植。本节是那条链路的落实记录。

### 6.1 机制概览

四件事串起来才成立：

| 环节 | 位置 | 作用 |
|---|---|---|
| 变换预热 | `Iris.warmupShaderTransforms()` → `DeferredWorldRenderingPipeline.warmupTransforms(programs)` | 包加载完成后，把该维度 ProgramSet 的 composite / attributes / celeritas / compute 变换全部提交到 `ShaderTransformExecutor`，真正建管线时全部命中 `TransformPatcher` 的缓存 |
| 汇合点 | `Iris.programSetWarmup` + `awaitProgramSetWarmup()` | `createPipeline` / `destroyEverything` / `compileEveryShaderFolder` 在读取变换结果前等待预热；失败只记日志，变换会在建管线时重跑（正确、只是慢） |
| SDL barrier | `GLSMHooks.uniformBlockBarrier = Iris::awaitProgramSetWarmup` | SDL `ShaderManager.shaderSource` 在非 worker 线程上会先等预热结束再读 per-frame uniform block（消费侧早前已移植，本次终于有生产端） |
| uniform block 采集 | `PerFrameUniformBlockHarvester.harvest(programs)` | 用 `ManifestCollectingHolder` 跑一遍常规 uniform 注册，得到该包声明的 per-frame uniform 集合，切成 `perFrameUniformBlock`/`perPassUniformBlock` 两种共享块 |
| 变换后钩子 | `IrisGLSMBridge.installPostTransformHook()` + `TransformPatcher` 的通知点 | 每个变换完成的 stage 立刻交给 SDL 后端在 worker 线程上编译成 SPIR-V（`SDLGPUGate.prewarmSpirv`），避免首帧在主线程上现编译 |

### 6.2 三处来源逐文件对账

**来源一：`8da561d3`「SDL GPU backend, ... (#1993)」的预热相关部分**

| 上游文件 | Actinium | 处置 |
|---|---|---|
| `net/coderbot/iris/gl/uniform/ManifestCollectingHolder.java`（新，+77） | `shader/src/main/java/net/coderbot/iris/gl/uniform/` | **全量移植**，注释按本仓库风格重写 |
| `net/coderbot/iris/gl/uniform/UniformManifest.java`（新，+38） | 同上 | **全量移植** |
| `net/coderbot/iris/uniforms/PerFrameUniformBlockHarvester.java`（新，+90） | `shader/src/main/java/net/coderbot/iris/uniforms/` | **移植**；去掉 `iris_WeatherParams0..2`（本仓库没有这三个 uniform，写进排除集是死代码），其余 `LIVE_STATE_UNIFORMS` 原样 |
| `TransformPatcher` 的 `GLSMHooks.postTransformProcessor` 调用点（+25） | `shader/.../pipeline/transform/TransformPatcher.java` | **移植**（`transform`/`transformCompute` 各一处）；见 §6.4 的两点适配 |
| `UniformType` 归并（把 `net/coderbot/iris/gl/uniform/UniformType.java` 移到 glsm） | 删除该文件，8 个引用点改 import `com.gtnewhorizons.angelica.glsm.shader.UniformType` | **采纳**：`PerFrameUniformBlock.Member` 用的是 glsm 的枚举，不归并就只能在 harvester 里写一份 11 分支的翻译表 |
| `Iris.ShaderTransformExecutor.warmup()` 的 `.exceptionally` 非阻塞化 | `shader/.../Iris.java` | **采纳** |
| `Previous` 矩阵共享、`EntityIdHelper` 缓存、`FloatUniform`/`IntUniform` 的 `updateListener` 复用 | — | **当时跳过**：与预热无关的性能改动 → **2026-10-12 已补齐**（`0bd51b55`，`sdl-gpu-upstream-diffs.md` Phase 12；监听器复用实际覆盖 7 个 uniform 类 + `SamplerBinding`） |

**来源二：`b17bb42c`「vsync adjustments ... (#2009)」的预热相关 hunk**

| 上游内容 | Actinium | 处置 |
|---|---|---|
| `IrisGLSMBridge.installPostTransformHook()`（抽出并在 `register()` 里调用） | `src/main/java/com/gtnewhorizons/angelica/iris/IrisGLSMBridge.java` | **移植**（2 参 `onTransformed` 形态，不带 GLSL 语法树）；`register()` 里以 `BackendManager.RENDER_BACKEND.isSDLGPU()` 守卫 |
| `Iris.warmupShaderTransforms()` 的引入 | `shader/.../Iris.java` + 在 `loadExternalShaderpack` 尾部触发 | **移植**；`BackendManager`/`IrisGLSMBridge.installPostTransformHook()` 那两行按 §6.4 去掉 |
| vsync on/mailbox/off、GLSL 解析优化（`ShaderTransformer` 的 `StageArtifact` 单次解析交接） | — | **跳过**（非预热链） |

**来源三：`38b1d9a0`「Speed up boot (#2190)」的剩余半块**

| 上游内容 | Actinium | 处置 |
|---|---|---|
| `Iris.programSetWarmup` future、`GLSMHooks.uniformBlockBarrier = Iris::awaitProgramSetWarmup`、`awaitProgramSetWarmup()` | `shader/.../Iris.java` | **移植**；`awaitProgramSetWarmup`/`programSetWarmup` 放宽为**包内可见**，供测试直接调用（替代上游的 `Reflect`） |
| `destroyEverything()` / `createPipeline()` 的汇合点 | 同上 | **移植**；另外 `compileEveryShaderFolder()` 开头的 `awaitProgramSetWarmup()` 也一并补上（上一环把它删了） |
| `IrisProgramSetWarmupTest`（+57） | `src/test/java/net/coderbot/iris/IrisProgramSetWarmupTest.java` | **按 Actinium 规范重写**：去反射、真实调用，3 例（无预热时是 no-op、阻塞到完成、失败被吞并清空） |
| `ShaderTransformExecutor.warmup()` 调用点 | 已在（`Iris.fmlInitEvent`） | 不动 |

**第 4 项（本轮新增的方法）：`DeferredWorldRenderingPipeline.warmupTransforms(ProgramSet)`**

上游是静态方法，Actinium 以当前工作树（#2206 之后）为体做语义移植——它只是**复用**已经存在的静态 helper：

```
submitCompositeTransforms(programs.getPrepare()/getDeferred()/getComposite(), 各自 TextureStage, textureMap)
submitCompositeTransform(programs.getCompositeFinal(), COMPOSITE_AND_FINAL, textureMap)
submitAttributeTransforms(new ProgramFallbackResolver(programs))
submitCeleritasTerrainTransform(...) × 4（terrain / translucent / shadow / shadowTranslucent）
submitComputeTransform(programs.getSetup(), TextureStage.SETUP)
submitComputeTransform(programs.getShadowCompute(), TextureStage.GBUFFERS_AND_SHADOW)
```

### 6.3 与当前工作树的 reconcile 明细

- **`DeferredWorldRenderingPipeline`（#2206 大改之后）**：`submitCompositeTransforms`/`submitCompositeTransform`/`submitAttributeTransforms(resolver)`/`submitCeleritasTerrainTransform` 在 #2206 移植时已经是 `private static`，与上游形态一致，因此 `warmupTransforms` 直接复用它们——**预热与构造体提交的是同一批变换、同一套 `Parameters`**，缓存键按值相等（`AttributeParameters`/`TextureStageParameters` 都是字段级 `equals/hashCode`），这才是「预热能命中」的前提。
- **instancing**：Actinium 没有 `Instancing`/instanced 变体（#2206 移植时已判定跳过），因此不遍历 instancing 种类，只调一次 `submitAttributeTransforms`；覆盖集合与构造体一致（构造体也是这一次调用）。
- **attributes 覆盖面**：Actinium 的 `submitAttributeTransforms` 遍历 `ProgramId.values()` × `INPUT_AVAILABILITIES`（4 个，含 `INPUT_TEXTURE_NO_COLOR`——`getTransformInputAvailability` 对 `ProgramId.SkyTextured` 的特例正好映射到它），构造体与预热走同一函数，键集合相同。
- **compute**：Actinium 的构造体是**同步**建 setup/shadow compute（上游是 future），所以预热的 compute 部分按构造体的调用点等价复刻（`TextureStage.SETUP` / `TextureStage.GBUFFERS_AND_SHADOW`）。
- **`Iris` 触发点**：上游在 `ClientProxy.preInit`（`AngelicaJar.configureShaderDiskCache` 之后）触发，那时 `currentPack` 已由 `onRenderSystemInit` 装载；Actinium 的包加载统一推迟到首个世界帧，因此触发点放在 `loadExternalShaderpack` 成功、`compileEveryShaderFolder()` **之后**（=「包已装载完成」）。此时若玩家在主菜单选包，预热会在后台跑完，进世界时建管线全部命中；世界内切包则与 `reload()` 的 `preparePipeline` 紧邻，预热的主要收益退化为「变换并行在 worker 池上 + 完成后再建管线」。
- **`ShaderType` 的两个副本**：glsm 的 `ShaderTransformPostProcessor` 用 glsm 的 `ShaderType`，而变换模块用 `net.coderbot.iris.gl.shader.ShaderType`（上游把后者也归并进了 glsm，本仓库有 ~30 个引用点）。为控制改动面，**只做归并 `UniformType`**，`ShaderType` 在 `TransformPatcher.toBackendShaderType(PatchShaderType)` 里用穷尽 switch 转换（枚举成员一一对应、编译器保证不漏）。
- **`IrisGLSMBridge` 的位置**：它在根工程，`shader` 子项目不依赖根工程，因此 `Iris.warmupShaderTransforms()` **不能**调用 `installPostTransformHook()`（上游在那里调）。改为 `register()`（`Actinium.onInit`）里安装，并加 `isSDLGPU()` 守卫——`register()` 早于任何一次变换（包加载在世界帧，`ShaderWarmup` 的主菜单预读只读文件不做变换），语义与上游「只在 SDL 后端安装该钩子」一致。
- **`ShaderTransformExecutor.isOnWorker()`**：上游的实现是 `AngelicaWorkers.isWorkerThread()`（上游的变换池就是 glsm 的 `AngelicaWorkers`），Actinium 的变换池是独立 ForkJoinPool，因此新增一个 `Worker extends ForkJoinWorkerThread` 标记类，`isOnWorker()` 判 `instanceof`。注意 SDL `ShaderManager` 的 barrier 判据用的是 `AngelicaWorkers.isWorkerThread()`，而 `shaderSource` 只会在主线程被调用（`GLStateManager`/`FullscreenPass`），不受影响。
- **`TransformPatcher` 的通知点**：Actinium 的缓存命中路径是提前 `return`，上游是单出口。这里在**命中与未命中两条路都通知**（命中时补通知是有意的：SDL 侧的 prewarm 缓存可能因为换包被清掉，而变换缓存还在，此时只有补通知才不会退回主线程现编译）。

### 6.4 有意的偏差

1. **不在 `warmupShaderTransforms()` 里装钩子**：见 §6.3 的模块边界说明。
2. **`ShaderType` 不归并，做一次显式转换**：见 §6.3；`UniformType` 则归并（harvester 必须产出 glsm 的值）。
3. **`iris_WeatherParams*` 不进 `LIVE_STATE_UNIFORMS`**：本仓库没有这三个 uniform。
4. **主菜单预读包（`prepareSelectedShaderPack`）不触发预热**：预热入口读的是 `currentPack`，而预读发生在 `currentPack == null` 时；让预读包也预热需要把 ProgramSet 从 `ShaderWarmup` 传到预热入口，超出本次范围（见 §5/§6.6 的收益说明）。
5. **测试去反射**：`PerFrameUniformBlockHarvesterTest` 按上游形态重写（无反射）+ 补 3 例（dynamic/外部托管 uniform 必须排除、同名重复声明只入块一次、块内顺序=声明顺序）；`IrisProgramSetWarmupTest` 用包内可见成员替代 `Reflect`。

### 6.5 自动化验证

`./gradlew check --no-daemon` 全绿（含 `verifyModuleBoundaries`：`shader` 只新增了对 `glsm` 的引用，方向合法）。新增/重写的用例：

- `PerFrameUniformBlockHarvesterTest`（10 例）：live-state uniform 必须留在推送路径、frame-global 必须进块、块内顺序、per-pass 块内容、两块互斥、dynamic/外部托管排除、同名只入块一次、真实 `CommonUniforms.addDynamicUniforms` 注册出来的名字一个都不进块、非 SDL 后端 `harvest` 必须清空块。
- `IrisProgramSetWarmupTest`（3 例）：无预热 no-op、等待阻塞到完成、失败被吞并并清空。

不在自动化范围内（需要真实 GL 设备/世界）：预热与建管线的缓存命中比、SDL block 注入后的实际渲染结果、`uniformBlockBarrier` 的真实竞态。

### 6.6 遗留风险

1. **`PerFrameUniformBlock` 是全局状态**：SDL 后端给**任何**在块生效期间编译的 shader 注入块声明（但只在 shader 真正引用了块成员时才注入，见 `PerFrameBlockInjector.collectEdits`）。关闭光影时 `createPipeline` 的 `currentPack == null` 分支会清空它；`compileEveryShaderFolder` 结束也会清空。
2. **预热维度可能与实际维度不一致**：预热用 `lastDimensionName ?: Overworld`，若玩家第一次进的不是主世界维度，预热的是另一套 ProgramSet（白做一次工作，且 `createPipeline` 仍会等它）。正确性不受影响——`createPipeline` 会为重采样的维度重新 `harvest`。
3. **`GLSMPerfDebug` 打开时的缓存键漂移**：`CacheKey` 含 `AdaptiveShadowBoundsStats` 的启用位与 binding，而该状态在管线构造时才 `activate`；打开 perf debug 时预热写入的键与建管线时的键不同 → 预热全部落空（回退到「变换跑两遍」）。属于调试路径的性能退化，不影响正确性。
4. **prewarm 与变换缓存的寿命不一致**：`TransformPatcher` 的缓存会在变换线程池空闲 120 s 后清空，SDL 的 prewarm 缓存是 LRU；两者各自失效都可能让对方多干一次活（我们只在命中路径补通知来兜底）。
5. **收益位置**：Actinium 的包加载发生在首个世界帧，所以「进世界首帧」这一场景里预热的并行收益有限；收益最明显的是「主菜单里已经选好包（触发过 `reload`）再进世界」与「世界内切包后的首帧」。要把它前移到主菜单预读包，见 §6.4 第 4 条。
