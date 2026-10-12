# 已编译程序落盘缓存（上游 #2206 + #2213 剩余半块）移植对账

> 状态：**已落地**（2026-10-12，`research/sdl-gpu-port`）。来源为上游两条提交：
> `0a4d4896`「Cache Iris Shaders (#2206)」（+1324/-524，21 文件）与 `38af64f1`
> 「Initial commit for shader-loading-optimizations (#2213)」中**依赖 #2206 的那部分**。
> 上一环（`docs/research/sdl-gpu-shader-loading.md` §2.3）把它们整体记为受阻，本文是那两块的落实记录。

## 1. 机制概览

三层叠在一起，缺一不可：

| 层 | 位置 | 作用 |
|---|---|---|
| 落盘层 | `glsm/shader/ShaderDiskCache` | 通用 blob/字符串落盘（CRC + header + salt + 容量上限），目录为 `<gameDir>/angelica/shadercache` |
| 程序层 | `glsm/shader/ProgramBinaryCache` | 用 `glGetProgramBinary` 取回链接后的程序二进制并按 key 落盘，重载时 `glProgramBinary` 直接还原 |
| 内存层 | `shader/gl/program/RetainedPrograms` | 跨 pipeline 保留已链接程序（关光影再开、换维度、重载未变的包时直接复用，连二进制都不用读） |

`ProgramBinaryCache` 的 key 由 pack 层（`gl-program/<包名净化后>`）、驱动指纹
（vendor|renderer|version|GLSL|isGLES|最小 GLSL 版本）、FFP 模拟开关、程序的全部 stage 源码，
以及 `ProgramCreator` 实际绑定的顶点属性名/位置组成。二进制只在同驱动、同属性绑定下才有效，
这三者缺一项就会出现「驱动拒绝保存的程序」，此时缓存条目被删除并回退源码编译
（日志 `Driver rejected a saved shader program!!`，只报一次）。

生命周期：

- `Iris.loadExternalShaderpack` → `ProgramBinaryCache.usePack(name)` 选定层；`usePack(null)` 关闭
  （`setShadersDisabled`/`destroyEverything`）。
- 开缓存时 `Iris.compileEveryShaderFolder()` 对包的**每个维度目录**建一次一次性 pipeline 并丢弃，
  使该包的全部程序都进磁盘；同一设置指纹已全量编译过则跳过（`wasCompiledFor`）。
  全量编译期间 `beginFullCompile`/`finishFullCompile` 记录运行期用到的 key，收尾时
  `retainLayer` 丢掉本次没碰到的旧条目。
- 之后每次建程序：`RetainedPrograms.take` → `ProgramBinaryCache.load` → 源码编译，
  三级回退；成功链接的程序会 `save`（异步落盘）。
- 用户在 GUI 里关掉该选项时 `ProgramBinaryCache.deleteAll()` 清空整层。

## 2. 逐文件对账

### 2.1 glsm（`0a4d4896` 的 GL 表面 + `38af64f1` 的增量）

| 上游文件 | Actinium | 处置 |
|---|---|---|
| `glsm/shader/ProgramBinaryCache.java`（新，236 行 + #2213 的 23 行） | 同名新文件 | **全量移植**（含 `saveRetained`/`isFullCompileRunning`）；注释按本仓库风格重写 |
| `glsm/shader/ShaderDiskCache.java`（+104） | 同名 | **补齐**：layer API 与 `remove` 此前**并不存在**（上一环的对账错误记录了它，见 §6）；`put`/`putBlob` 改为返回 `boolean` |
| `glsm/GLStateManager.java`（+19） | 同名 | `glProgramParameteri`/`glGetProgramBinary`/`glProgramBinary` + `glLinkProgram` 里的 `markRetrievable`。**适配**：`glProgramBinary` 用 Actinium 的 `CompatUniformManager.beforeLinkProgram`/`onLinkProgram(program, epoch)` 组合（上游只有一个 `onLinkProgram`），与 `glLinkProgram` 保持同一写法。⚠️ 后续（2026-10-12，Phase 14.2）：`markRetrievable` 改为自身按 `isEnabled()` 门控，`glLinkProgram` 里的外层判断随之删掉 |
| `glsm/RenderSystem.java`（+8） | 同名 | `supportsProgramBinary()` 字段/getter + initRenderer 内赋值 |
| `glsm/backend/RenderBackend.java`（+4） | 同名 | 四个默认方法（`supportsProgramBinary` 默认 false，其余空实现） |
| `lwjgl3-backend/.../Lwjgl3GLRenderBackend.java`（+22） | `src/lwjgl3/java/...`（glsm 的 main source set 包含该目录） | 全部移植，额外加 `caps != null` 判断（该类既有惯例） |
| `glsm/backend/Lwjgl2GLRenderBackend.java`（+24） | — | **跳过**：Actinium 没有 LWJGL2 后端实现，只有 LWJGL3 与 SDL 两个 |
| `glsm/src/test/.../ShaderDiskCacheTest.java`（+24） | 根工程 `src/test/.../glsm/shader/ShaderDiskCacheTest.java` | 移植 `retainLayer` 用例，另加 `touchLayer`/`keepNewestSubLayers`/`remove`/`deleteLayer` 的用例 |

### 2.2 shader

| 上游文件 | Actinium | 处置 |
|---|---|---|
| `gl/program/RetainedPrograms.java`（新，#2213） | 同名新文件 | 全量移植 |
| `gl/program/ProgramBuilder.java`（+28/+45） | 同名 | 移植 `retainAs` 与三级回退；**偏差**：上游的 `initializeFallbackUniforms(...)` 在 Actinium 不存在（本仓库 FFP uniform 走 `CompatUniformManager`），故不调用 |
| `gl/program/Program.java`、`ComputeProgram.java`（+12/+14） | 同名 | 移植 `sources` 字段与 `destroyInternal` 的 retain 分支 |
| `gl/GlResource.java`（#2213 +1） | 同名 | 加 `if (!isValid) return;`——**同源程序共享后，同一个 Program 会被两个 Pass 各 destroy 一次**，没有这道守卫就是双重 `glDeleteProgram` |
| `gl/shader/ProgramCreator.java`（+42） | 同名 | 移植 `cacheKey()`/`load()`/`create(name, cacheKey, shaders)`；**适配**：属性表取 Actinium 实际绑定的 4 项（`mc_Entity`/`mc_midTexCoord`/`at_tangent`/`at_midBlock`），上游的 `iris_Entity`/`iris_CubeTangent` 在本仓库不存在 |
| `config/IrisConfig.java`（+17） | 同名 | `saveCompiledShaders` 配置项 + `setEnabled` 接线 |
| `Iris.java`（+98/+194） | 同名 | 见 §3 |
| `pipeline/PipelineManager.java`（+5） | 同名 | `discardPipeline` 移植 |
| `pipeline/DeferredWorldRenderingPipeline.java`（+70/-31 与 #2213 的 +101） | 同名 | 见 §4 |
| `shaderpack/ShaderPack.java`（+70/-29 的 `getEveryProgramSet` 部分） | 同名 | 移植 `getEveryProgramSet`/`getFolderProgramSet`/`canFallBackToBase`；**适配**：`canFallBackToBase` 按 Actinium 的 4 参 `resolveDimensionFolder` 重写；**跳过**：并行预处理/懒 `menuContainer`/`languageMap` 异步化（属 #2213 的其它半块，上一环已另行处理） |
| `uniforms/custom/CustomUniforms.java`（#2213 +5） | 同名 | `mapPassLike` 移植 |
| `gl/buffer/ShaderStorageBufferHolder.java`（+45） | 同名 | 构造失败时回收已建 SSBO（全量编译会为每个目录建 pipeline，失败不能漏 GL 对象） |
| `celeritas/IrisCeleritasChunkProgramOverrides.java`（+72） | 同名 | 移植 `cacheKey`/`SavedProgram`；**适配**：Actinium 的实现按 `WorldRenderingPipeline` 分组、且 `createShader` 带 `alphaTestOverride`/`alphaReference`，故新增 `ChunkVertexType` 入参 + `compileAndDiscard(pipeline, vertexType)`（上游为 `createShaders` + `deleteShaders`），语义一致 |
| `celeritas/IrisCeleritasShaderProvider.java`（+11） | 同名 | `compileTerrainPrograms` 移植，用独立 overrides 实例，不影响正在用的地形程序 |
| `iris/ShaderWarmup`（#2213，`com/gtnewhorizons/angelica/iris/`） | 同名新文件 | 移植；**适配**：触发点见 §3.4 |
| `gui/GuiUtil.java`（+13） | 同名 | 新增 `drawTextPanel(FontRenderer, List<String>, x, y)` 重载（Actinium 已有的单行重载保留） |
| `gui/screen/ShaderPackScreen.java`（+57） | 同名 | 移植选项按钮/tooltip/`toggleSaveCompiledShaders`；**适配**：1.12.2 的 `GuiButton` 用 `x`/`y`（非 `xPosition`）、`this.fontRenderer`（非 `fontRendererObj`），布局沿用 Actinium 既有的 `topCenter±78` 两按钮行，新按钮占 `height-75`、两个列表底边从 `height-58` 挪到 `height-82` |
| `lang/en_US`（+7） | `assets/iris/lang/en_us.lang` | 只加实际用到的 4 条（`progress`/`progressStep` 未加，见 §3.3） |
| `Mixins.java`（±2） | — | **跳过**（Actinium 用 json 配置注册 mixin） |
| `IdMap` 的 `modernFallbackMcVersion`（#2213 +19） | — | **跳过**：Actinium 的 modern fallback 版本是 `IdMap` 里的常量（`McVersionDefines.withMcVersion(..., 260101)`），不是运行时可变配置，指纹里无需再记录它 |

### 2.3 测试

| 上游 | Actinium |
|---|---|
| `ShaderDiskCacheTest`（+1 例） | 移植 + 扩充（见 §2.1） |
| `PackSettingsFingerprintTest`（新，39 行） | **按 Actinium 重写**：上游用反射调 `describeSettings` 并断言 `AngelicaConfig.modernFallbackMcVersion` 参与指纹；本仓库禁测试反射、也没有那个配置项，改为把 `Iris.describeSettings` 放宽为包内可见后直接调用，断言「stat 指纹跟随改动、content 指纹忽略 mtime 只跟随内容、选项/defines/包名参与指纹、zip 包按自身 size|mtime、路径不存在返回 null」 |
| `ProgramBindingRegressionTest`（2 行） | **跳过**：该文件在 Actinium 不存在（它测的是上游 `Program` 构造签名） |
| — | 新增 `ProgramBinaryCacheTest`：pack 层目录净化与 64 字符截断、全量编译状态机、`deleteAll` 清层（读写真二进制需要 GL，故留实机验证） |

## 3. `Iris.java` reconcile 明细

### 3.1 采纳上游
`prepareSelectedShaderPack()` / `readPreparedPack` / `parsePack` / `takePreparedPack`（主菜单后台预读 +
`SoftReference` 缓存 + 设置指纹校验）、`readChangedConfigs` / `toProperties`、
`describeSettings(..., boolean byContent)` / `describeFolder` / `contentHash`（含目录环检测与并行遍历）、
`compileEveryShaderFolder()`、`destroyEverything`/`setShadersDisabled` 的 `currentPackState`/
`currentPackSettings`/`usePack(null)` 处理、`createPipeline` 的
`RetainedPrograms.afterPipelineBuilt()` 环绕、`loadExternalShaderpack` 的
`describeSettings` → `takePreparedPack` → `RetainedPrograms.useConfiguration` → `activate()`
→ `ProgramBinaryCache.usePack(name)` 顺序。
⚠️ **后续（2026-10-12）**：`prepareSelectedShaderPack()` 的返回类型由 `Runnable` 改为
`Supplier<ProgramSet>`（仍在调用线程构造，因为它先捕获来自 GL 查询的环境 defines），预读产物改由 record
`PreRead` 携带，`ShaderWarmup` 把其中的 `ProgramSet` 交给新入口 `Iris.warmupPreparedTransforms(ProgramSet)`
预热变换，见 `sdl-gpu-upstream-diffs.md` Phase 14.3。

### 3.2 保留 Actinium 的接线
- 上游在 `onRenderSystemInit` 就 `loadShaderpack()`，Actinium 一律推迟到首个世界帧
  （`MixinMinecraftIrisLoadingComplete` → `loadShaderPackWhenPossible` →
  `EntityRendererIrisMixin` → `tryLoadShaderpackWhenPossible`）。因此
  `compileEveryShaderFolder()` 挂在 `loadExternalShaderpack` 成功之后，而不是上游的
  `onLoadingComplete`；`Iris.reload()` 与开关光影也走同一条路径，语义等价。
- Actinium 没有 `PerFrameUniformBlockHarvester`（属未移植的 #1993/#2009 链路）、没有
  `awaitProgramSetWarmup`，`compileEveryShaderFolder` 里相应行去掉；
  `ShaderTransformExecutor.prepare()`（Actinium 的线程池预热）保留。
  ⚠️ **后续（2026-10-12）**：那条链路已补齐，`compileEveryShaderFolder` 重新接上
  `awaitProgramSetWarmup()` / `PerFrameUniformBlockHarvester.harvest(programs)` / `clear()`，
  见 `sdl-gpu-shader-loading.md` §6。
- 上游 `AngelicaJar.configureShaderDiskCache` 在 `Iris.onRenderSystemInit` 里调用，Actinium
  已在 `Actinium.onInit` 调用（上一环落地），本次不改。

### 3.3 有意的偏差
1. **不驱动 `LoadingScreenRenderer`**：1.12.2 没有上游用的 `resetProgresAndWorkingMessage`
   （只有 `I18n` 化的 `resetProgressAndMessage`/`displayLoadingString`），且 Actinium 的加载点在
   首个世界帧内，`setLoadingProgress` 会在帧中途 `updateDisplay()`（换帧）并改动矩阵/绑定
   framebuffer/tessellator。全量编译仍然执行，进度改为按目录 `INFO` 日志
   （`[Load #N] Compiling shader folder i/n: <folder>`），因此 `progress`/`progressStep` 两条 lang
   键未加。
2. **`prepareSelectedShaderPack` 去掉 `areShadersEnabled()` 前置条件**：上游在启动时就把包读进
   `currentPack`，只有「光影关着」时才需要预读；Actinium 在主菜单时 `currentPack` 始终为 null，
   保留该条件会让预热在真正需要它的场景（光影开着，等进世界）彻底失效。安全性由
   `takePreparedPack` 的指纹比较保证（包名/文件/选项任一变化即丢弃重读）。

### 3.4 `ShaderWarmup` 触发点
上游在 `ClientProxy` 的 `GuiOpenEvent`（首次 `GuiMainMenu`）里 `ShaderWarmup.start()`；Actinium 没有
这条 GUI 事件路径，改为在已有 mixin `MixinGuiMainMenu` 的 `initGui` HEAD 注入一行调用
（`Iris.enabled` 守卫，`start()` 自身幂等）。mixin 仍只做注入，逻辑在
`com.gtnewhorizons.angelica.iris.ShaderWarmup`。

## 4. `DeferredWorldRenderingPipeline` reconcile 明细

上游 #2206 对该文件的 "942 行重写" 主要是**构造体整体套 try/catch 后的缩进位移**，语义增量只有三处，
Actinium 的版本又早已大改（SDL 接线、`vertexWriteReplays`、voxelization compute、`AdaptiveShadowBoundsStats`、
`HorizonRenderer`、`AODebug`、无人用的 instancing 被删等）。逐项裁决：

| 上游改动 | 处置 |
|---|---|
| 构造成员整体包 `try { ... } catch (Throwable failure) { destroy(); throw failure; }` | **采纳**（用脚本对 232–655 行统一缩进一层，避免手改 400 行）。理由：#2206 新增的「逐目录全量编译」会为一个包的每个维度目录建 pipeline，中途失败必须回收 GL 对象 |
| `destroy()` 里对 `table`/各 renderer/`customTextureManager`/`whitePixel`/`customImages`/`renderTargets`/`dhCompat` 加空值守卫 | **采纳**，并按 Actinium 的字段多加了 `adaptiveShadowBoundsStats`（本仓库独有）守卫；`vertexWriteReplays`/`horizonRenderer` 是内联初始化的 final 字段，无需守卫 |
| `compileInstancedVariants()`（供 `Iris` 全量编译预热实例化变体） | **跳过**：Actinium 的 pipeline 没有 instancing 变体（无 `InstancedVariantKey`/`supportsInstancing`/`referencesMvBuiltins`），`Iris` 侧也不调用 |
| #2213：把 gbuffer 程序 id 查表抽成 `gbufferProgramId(...)` | **采纳**（纯提取，行为不变） |
| #2213：同源程序共享（`SharedProgramKey` + `BuiltProgram` + `createPassInner(program, ...)` + `customUniforms.mapPassLike`） | **采纳并加严**：上游 key 为 `(sourceName, availability, shadow)`，Actinium 的 transform 查表键是 `transformAvailability`（`SkyTextured` 有特例），且 `wireGbufferResources` 按 `availability` 决定 level sampler 绑定，故 key 取 `(sourceName, transformAvailability, availability, shadow)`——比上游更保守，不会把绑定不同的程序合到一起。同样保留「仅当 transform 命中预计算表才共享」的条件 |
| #2213：instanced 变体共享（`InstancedVariantKey`） | **跳过**（无 instancing） |
| #2213：`referencesMvBuiltins` 的 `MV_BUILTINS` 预扫早退 | **跳过**：Actinium 没有该函数（instancing 支撑判定整体不存在） |

共享带来的两个 Actinium 专属处理：

1. `wireGbufferProgram(builder, name, transformed, availability, shadow)` 把
   `addDynamicUniforms` + `customUniforms.assignTo` + `wireGbufferResources(builder, builder, ...)`
   + `build()` + `vertexWriteReplays.attach(...)` 收在一处；**共享路径不重复 attach**，否则同一 program id
   会被挂两次 replay（多一个没人用的 compute twin）。
2. `createPassInner` 原来的 `builder.bindAttributeLocation(11..14, ...)` 删除：它们发生在 `build()` 之后
   （即链接之后）本就无效，真正的绑定在 `ProgramCreator.create` 里、链接之前完成，且与缓存键里的属性表一致。

## 5. SDL 后端的回退路径

程序二进制是 GL 概念，SDL GPU 后端没有对应能力，因此：

- `RenderBackend.supportsProgramBinary()` 默认返回 `false`，**SDL 后端不覆写** → `RenderSystem`
  在 `initRenderer` 里记为 false，日志 `Program Binary: false`。SDL 侧没有、也不需要任何 GL 调用。
- `ProgramBinaryCache.isEnabled()` 首先要求 `RenderSystem.supportsProgramBinary()`，
  因此 SDL 路径上：
  - `ProgramCreator.cacheKey()` 直接返回 null → `ProgramBuilder` 不建键、不 `load`、不 `save`，
    也不取 `key()`（`key()` 会读 GL 字符串，这是刻意的短路）；
  - `GLStateManager.glLinkProgram` 不会打 `GL_PROGRAM_BINARY_RETRIEVABLE_HINT`；
  - `IrisCeleritasChunkProgramOverrides` 的 `cacheKey` 返回 null → 照旧全量编译；
  - 全量编译的「三级回退」只剩内存保留池（`RetainedPrograms`）——它在 SDL 下依然有效，
    因为保留的是 SDL 后端自己的着色器/程序对象。
- 因此 SDL 下 `saveCompiledShaders` 打开与否都**退化为全量编译**（同上游 tooltip 的说明：
  「你的驱动不能保存已编译程序，本选项只会提前把各维度编译好」）。GUI 用
  `RenderSystem.supportsProgramBinary()` 决定是否追加那条提示。
- GL（LWJGL3）后端才实现四个方法，缓存只在 GL 路径成立。

## 6. 勘误

- `docs/research/sdl-gpu-shader-loading.md` §2.1 与
  `sdl-gpu-upstream-diffs.md` 的 Phase 6 节曾把 `ShaderDiskCache` 的 layer API 记为「早前 Phase 已同步」，
  实际当时只落了 `configure` 幂等。该缺口已随本次补齐（`retainLayer` 用例由上游移植）。
- 同处把 `GlResource` 的 `isValid` 守卫记为已同步，实际也缺失；本次补上（程序共享的前提）。

## 7. 验证

### 7.1 自动化
`./gradlew check --no-daemon`（见提交说明）。新增/扩充的用例：

- `ProgramBinaryCacheTest`（根工程 `com.gtnewhorizons.angelica.glsm.shader`）：pack 层目录净化/截断、
  全量编译状态机、`deleteAll` 清层。
- `ShaderDiskCacheTest`：`retainLayer`（含保留其它层与 in-flight `.tmp`）、
  `touchLayer`/`keepNewestSubLayers`/`remove`/`deleteLayer`。
- `PackSettingsFingerprintTest`：设置指纹的全部语义（见 §2.3）。

不在自动化范围内（需要真实 GL 驱动 + 后端单例，或需要游戏内交互）：`glGetProgramBinary`/
`glProgramBinary` 的真实往返、`RetainedPrograms` 的删除路径、全量编译的真实耗时。

### 7.2 实机验收（核心指标：第二次加载同一光影包应显著加速）

1. 启动游戏，`视频设置 → Shader Pack` 里选中一个包（如 Complementary Unbound），
   打开新增的 `Save Compiled Shaders` 开关（日志会出现
   `[Load #N] Compiling shader folder 1/…: …` 若干行，随后 `Shader disk cache at …`）。
2. 进世界，等首帧完成；确认画面正常、无
   `Driver rejected a saved shader program!!` 与
   `Shader disk cache disabled`。
3. 记录 `[Load #N] Total shaderpack load time for '…' in dimension '…': … ms`。
4. **退出游戏并重新启动**（不是 F3+R），再次进入同一世界：程序应从
   `<gameDir>/angelica/shadercache/gl-program/<包名>` 还原，`Total shaderpack load time` 应显著低于
   第一次（预期降到原来的 1/3 或更低，取决于显卡）。这是本任务的验收指标。
5. 附加确认：
   - `gl-program/<包名>` 目录里出现多个 `.bin` 文件（每个约数十 KB～数百 KB）。
   - 关掉该开关后目录整体消失（`ProgramBinaryCache.deleteAll`）。
   - 换一个光影包再换回来：仍能命中（每包一层，保留最近 3 个包）。
   - 换维度（下界/末地）应直接命中该维度目录的程序，不重新编译。
   - 开/关光影开关（K 键）不应重新编译（`RetainedPrograms` 内存池命中）。
6. 回归：SDL 后端（若在测）打开该开关时应只做全量编译、不写 `gl-program` 目录、
   日志为 `Program Binary: false`，且游戏不崩。

## 8. 遗留风险

1. **构造失败路径的破坏面**：`DeferredWorldRenderingPipeline` 构造体现在会在失败时调用 `destroy()`。
   该路径只在异常时走到，且已对全部可能未赋值的字段加了守卫；但它是本次唯一改动「异常语义」的地方，
   实机应至少覆盖一次「故意让某个维度目录编译失败」（例如临时改坏一个包的某个 shader）确认回收正常、
   不崩、且错误目录之外的包仍能加载。
2. **共享程序的生命周期**：同一 Program 被多个 Pass 引用，销毁靠 `GlResource.isValid` 去重。
   若将来给 `Pass` 增加「单独销毁某个 pass」的路径，会误删仍在使用的程序。
3. **全量编译的耗时**：开缓存后每次改光影设置会一次性编译**所有**维度目录（上游设计如此），
   表现为「应用设置更慢、后续启动更快」。这一体验变化需要实机确认可接受。
4. **缓存键的覆盖面**：键包含驱动指纹、FFP 开关、stage 源码与顶点属性绑定。
   驱动升级后 vendor/renderer/version 变化会自动失效；但同一驱动的驱动内部改动不在键里，
   只能靠 `glProgramBinary` 失败 → 删除条目 → 源码编译兜底。
5. **`compileTerrainPrograms` 的顶点格式**：预热用 provider 当前的 `vertexType`，
   与运行时 `TerrainRenderPass.vertexType()` 在 vertex format requirements 变化后可能不一致，
   表现为「该维度地形程序未命中缓存」（多编译一次），不会产出错误程序（属性表在键里）。
6. **地形（celeritas）程序可能存不下来**：~~`GL_PROGRAM_BINARY_RETRIEVABLE_HINT` 是在
   `GLStateManager.glLinkProgram` 里打的，而 celeritas 的 `GlProgram.Builder.link()` 走
   `com.mitchej123.lwjgl` 服务（`LWJGL3Service`/`SDLGPULWJGLService`）直连，不经 GLSM，
   因此地形程序**没有**打这个 hint。~~ **已于 2026-10-12 补齐**（Phase 14.2，`sdl-gpu-upstream-diffs.md`）：
   `celeritas-common` 的 `GlProgram` 新增 link 前钩子 `LinkHook`（`Actinium.onConstruct` 接线为
   `ProgramBinaryCache::markRetrievable`），`GL_PROGRAM_BINARY_RETRIEVABLE_HINT` 现在也覆盖这批程序。
   该驱动指纹维度上的行为（`glGetProgramBinary` 是否真的返回非 0 长度）仍需实机确认。
