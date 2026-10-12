# 已移植优化的统计日志（GL 路径实机验证用）

最后更新：2026-10-12。分支：`research/sdl-gpu-port`。

本文列出**本分支各项已移植优化新增的日志**：确切文本格式、字段含义、落点文件，以及
「这条日志出现/取什么值 ⇒ 该优化生效」的判读方式。用户在 `./gradlew runClient`（GL 路径，
无 SDL）跑完后，从 `run/client/logs/latest.log` 按本文核对即可，不需要 profiler。

这些日志**全部只做观测**：不改任何优化的行为语义；除正则改写的 `glslRename` 与各计数器外，
热路径上只增加字段/原子自增，字符串拼接只发生在报告生成时（周期报告在开关打开时才生成）。

## 一、怎样打开

| 途径 | 内容 | 说明 |
| --- | --- | --- |
| 游戏内 GUI | 视频设置（Video Settings）→ **Debug** 页 → **Render Timing Debug** 打开 | 对应 `sodium.options.actinium.perf_debug.name`；写入 `opts.debug.enableActiniumPerfDebug`，经 `GLSMPerfDebugHooks.setConfiguredEnabled` 生效 |
| 配置文件 | `run/client/config/actinium-options.json`：`"enable_debug_tab": true` 打开 Debug 页，`"debug": { "enable_actinium_perf_debug": true }` 打开本组日志 | Debug 页默认隐藏，按设计只能从配置文件打开（`SodiumGameOptions.enableDebugTab`） |
| 系统属性 | `-Dactinium.glsmPerfDebug=true` / `false` | 一次性实验用；优先级高于配置文件，**不要**固化进构建配置 |

打开后 `latest.log` 出现 1 秒一行的 `GLSM perf:`（logger `GLSMPerfDebug`），本组**周期段**都
追加在这一行里；**事件摘要**在各自的 logger 下按事件输出。两者都不按条数限流：周期段在
游戏运行期间每秒一行，事件摘要每次事件一行。

两级日志对开关的依赖不同：

| 级别 | 需要 `Render Timing Debug` | 内容 |
| --- | --- | --- |
| **周期段** | **是** | `pacer`、`reducer`、`progCache`、`transform`、`glslRename`、`pbr`、`uniformPrevious`、`entityId` 八段，以及已有的 `[ShaderTransformCache] <domain> hit/miss/raceReuse ...` 与 `cleared entries=N` 逐事件行 |
| **事件摘要** | **否**（默认就会输出） | `Shaderpack load:`、`Program binary cache:`、`Atlas build:`、`Terrain texture filtering:`、`VSync mode:`、`PBR resource index: scanned ...`、`Shader warmup:`、`[ShaderTransformCache] warmup submitted/done/failed`、`FPS reducer: A -> B`、`Warming the transforms of the shaderpack read ahead of time` |

也就是说：只想知道「包加载快了多少、图集是不是走了并行、过滤模式是什么」可以不开开关；
想持续观察「命中率、被 pace 的帧、降帧状态、uniform 缓存」才需要开。

### 周期段的注册顺序（= 日志里段的出现顺序）

`GLSM perf:` 行的结构是：glsm 自带的 stage 段（`chunk.upload[...]` 等）+ 各模块注册的统计段。
模块段的顺序为：`pacer`（FramePacer）→ `progCache` → `glslRename` → `reducer` → `transform` →
`pbr` → `uniformPrevious` → `entityId`（外加已有的 fastLit/shadow 段）。除 `pacer` 与 `reducer`
（它们总在描述当前状态）外，没有内容可报的段返回空字符串，整段不出现；`GLSM perf:` 行本身只
在该秒内有 glsm stage 采样时才输出。

## 二、逐项日志

### 1. 程序落盘缓存（#2206，d45f3b13）

落点：`glsm/.../shader/ProgramBinaryCache.java`（计数）、
`shader/.../gl/program/ProgramBuilder.java`、`shader/.../celeritas/IrisCeleritasChunkProgramOverrides.java`（构建计时）、
`shader/.../Iris.java`（摘要输出）。

**事件摘要**（logger `ActiniumShaders`，每次「光影包程序加载批次」结束一行）：

```
Program binary cache: folders=6 complete=true progCache[hit=0,miss=118,hitRate=0.0%,save=118,reject=0,builds=118,restoreMs=0.000,missBuildMs=31245.918,avgMissBuildMs=264.796,estSavedCompileMs=0.000,sessionMissBuilds=118]
```

| 字段 | 含义 |
| --- | --- |
| `folders` | 本次全量编译的光影包文件夹（维度）数 |
| `complete` | 全部文件夹都编译成功（false 时本次结果不写入「已完整编译」标记，下次还会重编） |
| `hit` / `miss` | 本批次从磁盘恢复的程序数 / 未能恢复（改从源码编译）的程序数 |
| `hitRate` | `hit/(hit+miss)`；无查询时为 `n/a` |
| `save` | 本批次新写到磁盘的程序二进制数 |
| `reject` | 驱动拒绝已存二进制（`glProgramBinary` 后链接失败）的次数 |
| `builds` | 本批次内被计时的「源码编译+链接」次数 |
| `restoreMs` | 命中时 `load()` 的总耗时（即 `glProgramBinary` 恢复成本） |
| `missBuildMs` | 本批次未命中后编译+链接的总耗时 |
| `avgMissBuildMs` | **整个会话**的平均未命中编译耗时（本批只命中时，用它给命中定价） |
| `estSavedCompileMs` | `hit × avgMissBuildMs`，即命中省下的编译时间估计 |
| `sessionMissBuilds` | 会话累计被计时的编译次数 |

同一批次的另两种结局各有一行（同样是每次包加载一行）：

```
Program binary cache: not compiling every shader folder, saving compiled shaders is off
Program binary cache: every folder of this pack was compiled in a previous run, nothing to compile
```

**周期段**（同一套字段，只报本区间的增量；无活动时不出现）：

```
progCache[hit=19,miss=3,hitRate=86.4%,save=0,reject=0,builds=3,restoreMs=48.212,missBuildMs=7421.880]
```

> 判读要点：**第一次**加载某个光影包，全量编译批次里 `hit=0` 且 `save` 接近 `builds`；
> **第二次**（重开游戏后）同一包在进入世界时建管线会走 `load()`，此时周期段出现
> `hit>0, miss=0, save=0`，且 `compileMs`（见第 3 项）接近 0 —— 这就是缓存生效的直接证据。
> 若第二次仍是 `miss>0`（且 `reject=0`），说明键变了（改过设置、换过驱动、pack 指纹变了）。

### 2. 变换缓存 + 预热链（6d338faf、e955a06d）

落点：`shader/.../pipeline/transform/TransformPatcher.java`（计数 + 汇总）、
`shader/.../Iris.java`（预热提交/完成）、`src/main/java/com/gtnewhorizons/angelica/iris/ShaderWarmup.java`
（主菜单预读预热）。开关打开时**原本就有**的逐事件日志保持不变（不按条数限流）：

```
[ShaderTransformCache] graphics hit cacheSize=260
[ShaderTransformCache] graphics miss transformMs=18.4 cacheSize=261
[ShaderTransformCache] graphics raceReuse transformMs=22.1 cacheSize=261
[ShaderTransformCache] cleared entries=261
```

**新增周期段**：

```
transform[hit=57,miss=3,raceReuse=1,transformMs=64.220,cacheSize=261,capacity=400]
```

| 字段 | 含义 |
| --- | --- |
| `hit` / `miss` | 本区间从缓存取到 / 未取到的变换次数（`raceReuse` 是 `miss` 的子集：本调用解析 GLSL 期间别的调用已把同键结果放进缓存） |
| `raceReuse` | 上述竞态复用次数 |
| `transformMs` | 本区间落在缓存未命中路径上的变换耗时合计 |
| `cacheSize` / `capacity` | 当前缓存条目数 / 上限（`MAX_CACHE_ENTRIES=400`，LRU） |

无活动且 `cacheSize` 未变时该段不输出（避免空闲时每秒重复同一行）。

**预热提交/完成**（logger `ActiniumShaders`）：

```
[ShaderTransformCache] warmup submitted source=loadedPack dimension=overworld
[ShaderTransformCache] warmup done source=loadedPack cacheSize=261
[ShaderTransformCache] warmup failed source=loadedPack; transforms will run at pipeline creation   （WARN，后随堆栈）
```

**主菜单预读预热**（logger `Actinium/ShaderWarmup`）：

```
Shader warmup: preloading shader classes
Shader warmup: reading the selected shaderpack ahead of time
Shader warmup: read the selected pack ahead and submitted its transform warmup in 812.4ms
Shader warmup: no shaderpack selected to read ahead, done in 340.1ms
Shader warmup: nothing was read ahead, done in 905.7ms
```

> 判读要点：`warmup submitted ... ` 后必须跟 `warmup done source=loadedPack cacheSize=<非 0>`；
> 之后建管线时的周期段应是 `miss=0`（预热全命中）。若 `warmup failed` 出现，说明预热没跑完，
> 管线创建时会补做（`miss>0`）。

### 3. 光影包加载提速（#2190 de9b99e2、#2213 b245191a）

落点：`shader/.../Iris.java#logLoadSummary`、`shader/.../texture/pbr/PBRResourceIndex.java`、
`glsm/.../GlslTransformUtils.java`。

**包加载摘要**（logger `ActiniumShaders`，每次包加载结束一行）：

```
Shaderpack load: pack=ComplementaryUnbound_r5.5 totalMs=14238.771 parseMs=6420.115 reused=false activateMs=18.442 fingerprintMs=53.002 compileMs=7702.331 warmupSubmitMs=0.318 glslRename[calls=214,chars=7844123,ms=118.402,avgUs=553.28] pbr[lookups=1420,memoHits=1180,answeredAbsent=228,notPbr=12,state=ready(packs=7,names=6120)]
```

| 字段 | 含义 |
| --- | --- |
| `totalMs` | `loadExternalShaderpack` 全程 |
| `parseMs` | 解析/复用包（`new ShaderPack(...)` 或复用主菜单预读的包） |
| `reused` | 是否复用了预读的包（true 时 `parseMs` 极小） |
| `activateMs` | `currentPack.activate()` |
| `fingerprintMs` | 选项合并 + 包指纹计算（`shouldSaveCompiledShaders` 打开时才有指纹） |
| `compileMs` | 全量编译所有文件夹（程序构建，见第 1 项摘要） |
| `warmupSubmitMs` | 提交变换预热（异步，不含预热执行时间，执行结束由第 2 项的 `warmup done` 报告） |
| 尾随 `glslRename[...]` | 本区间保留字改写统计，见下（仅在 `Render Timing Debug` 打开时统计） |
| 尾随 `pbr[...]` | 本区间的 PBR 索引查询统计，见下 |

**保留字改写周期段**（`glslRename`）：

```
glslRename[calls=214,chars=7844123,ms=118.402,avgUs=553.28]
```

`calls` = 真正执行了改写的着色器源码数（预扫描发现无保留字的直接返回，不计）；
`chars` = 这些源码的字符数合计；`ms` = 改写总耗时；`avgUs` = 每次平均微秒。

**PBR 索引**：

```
PBR resource index: scanned 7 packs, 6120 PBR names in 341.2ms      （logger ActiniumShaders，每次重建一行）
pbr[lookups=1420,memoHits=1180,answeredAbsent=228,notPbr=12,state=ready(packs=7,names=6120)]
```

| 字段 | 含义 |
| --- | --- |
| `scanned` 行 | 该次索引构建扫描的包数与收集到的 PBR 名数、耗时（worker 线程，资源重载时重建） |
| `lookups` | 本区间落到索引上的查询（`_n.png`/`_s.png` 结尾，即 PBR 查找） |
| `memoHits` | 同一定位重复查询由 memo 直接回答的次数 |
| `answeredAbsent` | 索引判定「不可能存在」而跳过的查询数——这就是省下的失败资源查找次数 |
| `notPbr` | 非 PBR 名字、索引直接放行的查询数 |
| `state` | `idle` / `scanning` / `ready(packs=N,names=M)` / `disabled`（扫描失败时禁用并回退直查，另有 WARN 行） |

> 判读要点：`PBR resource index: scanned ...` 出现且 `state=ready` ⇒ 索引生效；
> `answeredAbsent` 在拼接图集区间内显著 > 0 ⇒ 确实省掉了逐精灵失败查找。
> 若出现 `PBR resource index disabled, falling back to direct lookups`（WARN）则优化未生效。

### 4. 图集并行加载（#2167，7b244cea）

落点：`src/main/java/com/dhj/actinium/texture/atlas/AtlasLoadStats.java`（汇总）、
`.../AtlasLoadSession.java`（会话计数）、
`.../mixin/vintage/features/textures/MixinTextureAtlasParallelLoad.java`（阶段计时与输出）。

**图集构建摘要**（logger `Actinium`，每次 `TextureMap.loadTextureAtlas` 结束一行；地形图集与
物品图集各一行）：

```
Atlas build: atlas=textures/blocks sprites=3176 mipLevels=4 gutter=16 filter=RGSS_ANISOTROPIC anisotropy=4x texelSnap=true workers=8 parallel=[decode=true,mipmaps=true,batchedUpload=true,submitted=3175,decoded=3172,decodeFailures=0,decodeDesync=false,mipmapsOffloaded=3160,batchedUploaded=3175,prepared=3176] phases=[stitchMs=812.400,prepareMs=5210.118,assembleMs=612.004,totalMs=6905.223] strategies=[MEAN=2401,CUTOUT=520,STRICT_CUTOUT=118,DARK_CUTOUT=137]
```

| 字段 | 含义 |
| --- | --- |
| `atlas` | `TextureMap.getBasePath()`：`textures/blocks`（地形图集）或 `textures/items` |
| `sprites` | 拼接出的精灵数 |
| `mipLevels` | 本次加载**最终**采用的 mip 层数（可能被加载过程下调） |
| `gutter` | 精灵实际被填充的边界宽度（texel，0 = 未填充） |
| `filter` | 生效的过滤模式（见第 5 项） |
| `anisotropy` | 生效的各向异性倍数（1x = 未启用） |
| `texelSnap` | 地形着色器是否自行选择 mip 层（`USE_TEXEL_SNAP`） |
| `workers` | `AngelicaWorkers.threads()`，即图集并行/光影包加载共用的工作线程数 |
| `decode` / `mipmaps` / `batchedUpload` | 三条并行路径本次是否**真的**走了（依据各自的实际工作量，而不是开关值） |
| `submitted` / `decoded` / `decodeFailures` / `decodeDesync` | 解码通道提交数 / 主线程实际取用的预解码数 / 解码失败数 / 通道失序被关闭 |
| `mipmapsOffloaded` | 甩到工作线程的 mipmap 生成次数 |
| `batchedUploaded` | 批量上传通道实际写入（并因此让逐精灵上传跳过）的精灵数 |
| `prepared` | 会话预先完成「解码+mipmap」的精灵数 |
| `phases` | `stitchMs` 拼图、`prepareMs` 解码通道+逐精灵生成+mipmap join、`assembleMs` 批量装配上传、`totalMs` 整个 `loadTextureAtlas` |
| `strategies` | 精灵按**最终生效**的 mipmap 策略计数（见第 5 项） |

> 判读要点：
> - `parallel=[decode=true,...]` 且 `decoded` 接近 `submitted` ⇒ 并行解码生效；
>   `decode=false` 说明开关关着、资源包域未授权（`AtlasPackScreen` 里可授权）或资源管理器类型不支持。
> - `mipmaps=true`（`mipmapsOffloaded>0`）⇒ 并行 mipmap 生效。
> - `batchedUpload=true`（`batchedUploaded>0`）⇒ 批量图集上传生效；若日志另有
>   `Batched atlas upload failed, remaining sprites use the per-sprite path`（WARN，logger `Actinium`）
>   则该次回退到逐精灵上传。
> - `phases` 里 `prepareMs` 应明显小于「精灵数 × 单精灵解码+生成」，且并行度越高收益越大。

### 5. 纹理过滤 / mipmap / RGSS（a52e323e、4a02b90e）

落点：`src/main/java/com/dhj/actinium/render/terrain/VintageRenderPassConfigurationBuilder.java`
（过滤模式事件）、`MipmapGenerator.effectiveStrategy` + `MixinTextureAtlasSprite`（策略计数，
计入上面的图集摘要）。

**过滤模式事件**（logger `Actinium`，每次构建地形 pass 配置输出一行，即资源重载/进入世界时；
过滤选项或 mipmap 选项改变后必然重建，所以这行同时就是「模式切换」日志）：

```
Terrain texture filtering: mode=RGSS_ANISOTROPIC anisotropy=4x texelSnap=true rgss=true mipmaps=true atlasMipLevels=4 gutter=16 mipEnabled=true
```

| 字段 | 含义 |
| --- | --- |
| `mode` | 生效模式：`RGSS` / `ANISOTROPIC` / `RGSS_ANISOTROPIC` / `NONE`（驱动不支持各向异性时降级后的值） |
| `anisotropy` | 送给地形采样器的各向异性倍数（`1x` 表示该模式不用各向异性） |
| `texelSnap` | 是否走「着色器自选 mip 层」路径 |
| `rgss` | 是否走旋转网格超采样（RGSS）路径 |
| `mipmaps` | 图集是否有 mip 链 |
| `atlasMipLevels` | 地形图集实际的 mip 层数（取自上一次图集加载记录，未加载过时为设置值） |
| `gutter` | 该 mip 层数对应的地形图集边界宽度（`SpritePadding.gutterFor`） |
| `mipEnabled` | 地形 pass 是否按「有 mip」构建（由 `gameSettings.mipmapLevels > 0` 决定） |

**各 mipmap 策略的精灵计数**：见第 4 项摘要的 `strategies=[...]`。取值是 `MipmapStrategy`
枚举名，按枚举序号排列：`MEAN`（普通盒式过滤）、`CUTOUT`、`STRICT_CUTOUT`、`DARK_CUTOUT`；
`AUTO` 已按精灵透明度解析成实际模式，因此不会出现。计数口径是
`TextureAtlasSprite.generateMipmaps` 的调用次数（每个拼接精灵一次，物品图集同样统计）；mip
层数为 0 时该调用不做实际生成，但策略仍会计入，此时 `Atlas build` 行的 `mipLevels=0` 即说明
本次没有真正的 mip 链。

> 判读要点：`strategies` 里出现 `DARK_CUTOUT`/`STRICT_CUTOUT`/`CUTOUT` ⇒ 移植的策略在生效；
> 全为 `MEAN` 说明当前资源包没有命中任何规则（叶子/花/蘑菇/玻璃/红石线路径名或 `.mcmeta`
> 的 `mipmap_strategy`），此时该移植等于未触发。

### 6. pacer + FpsReducer（c5f54409）

落点：`src/main/java/com/dhj/actinium/render/pacing/FramePacer.java`、
`.../FpsReducer.java`、`.../ReducerStats.java`。

**pacer 周期段**（logger `GLSMPerfDebug`，随 `GLSM perf:` 行；`cfg`/等待质量为既有内容，
`paced=[...]` 为本次新增）：

```
pacer: cfg=[mode=ON refresh=60Hz cap=0 ceiling=60Hz] frames=60 parks=57 slackUs avg=812 floorUs=48 overshootUs est=12 lateFrames=0 lateMaxUs=0 last=[gateUs=15833 gpuWaitUs=120 slackUs=210 spinUs=140 marginUs=1500 presentUs=16666 idleOvershootUs=0 locked=true probing=false] paced=[frames=60,paced=60,ratio=100.0%]
```

| 字段 | 含义 |
| --- | --- |
| `cfg=[mode=... refresh=... cap=... ceiling=...]` | 当前 pacing 模式（`ON`/`MAILBOX`/`OFF`）、面板刷新率、玩家设置的上限、最终生效上限（既有字段） |
| `last=[... locked=.. probing=..]` | 上一帧的门等待/GPU 等待/余量/是否锁定到驱动栅格（既有字段） |
| `paced=[frames=N,paced=M,ratio=P%]` | **本区间**结束的帧数、其中被 pacer 真正纳管（`cadenceNanos>0`，即存在节奏）的帧数、占比；无帧时 `ratio=n/a` |

**降帧激活状态周期段**（logger `GLSMPerfDebug`）：

```
reducer=[state=FOCUSED,enabled=true,cap=0,userLimit=0,volume=100%,benchmark=false,skipRender=false]
```

| 字段 | 含义 |
| --- | --- |
| `state` | 降帧状态机的当前状态：`DISABLED` / `FORCED` / `MINIMIZED` / `UNFOCUSED` / `IDLE` / `FOCUSED` |
| `enabled` | 选项里是否开启降帧 |
| `cap` | 该状态施加的帧率上限（0 = 不限制） |
| `userLimit` | 玩家自己的帧率上限（`gameSettings.limitFramerate`） |
| `volume` | 已应用的主音量百分比 |
| `benchmark` | 是否处于 benchmark（基准测试）接管期 |
| `skipRender` | 是否整帧跳过世界渲染（仅 `MINIMIZED`） |

状态**切换**时另有既有事件行（logger `Actinium`）：

```
FPS reducer: FOCUSED -> IDLE
```

> 判读要点：`paced=[frames=60,paced=60,ratio=100.0%]` ⇒ 每帧都被纳管；
> `paced=0` 且 `cfg=[mode=OFF ... cap=0 ...]` ⇒ 没有任何东西限制帧率（`VSync Off` 且上限 0）。
> 帧率低于玩家设置时，先看 `reducer` 段的 `state`/`cap`：`state=IDLE` 且 `cap=30` 就是降帧在起作用。

### 7. vsync 三模式（ca162eed）

落点：`glsm/.../backend/RenderBackend.java`（GL/公共路径）、
`sdl-gpu/.../SDLGPURenderBackend.java`（SDL 交换链路径）。

**模式切换事件**（logger `GLSMBackend`，每次调用 `setVSyncMode` 一行）：

```
VSync mode: requested=ON applied=ON honored=true previous=ON
```

| 字段 | 含义 |
| --- | --- |
| `requested` | 选项请求的模式：`OFF` / `ON` / `MAILBOX` |
| `applied` | 后端**实际**能做到的模式。GL 路径只会是 `ON`/`OFF`（见到 `MAILBOX` 说明走了 SDL GPU 路径） |
| `honored` | 驱动是否真的按 vsync 阻塞（false 时 pacer 退回墙钟节流） |
| `previous` | 切换前生效的模式 |

SDL 路径另有交换链应用行（logger `Angelica-SDLGPU`）：

```
VSync mode: requested=MAILBOX applied=MAILBOX (SDL GPU swapchain)
```

> 判读要点：GL 路径下逐项核对 `requested → applied`：`requested=MAILBOX applied=ON` 是
> **预期**的降级（GL 无 mailbox present mode）；`requested=OFF applied=OFF` 表示 vsync 关闭。

### 8. B3 uniform 优化（0bd51b55）

落点：`shader/.../uniforms/MatrixUniforms.java`、`shader/.../uniforms/EntityIdHelper.java`。

**共享 previous 矩阵跟踪器**（周期段）：

```
uniformPrevious[instances=18,reads=4320]
```

| 字段 | 含义 |
| --- | --- |
| `instances` | 共享跟踪器实例数 = **uniform 名字数量**（如 `gbufferPreviousModelView` 等）。未共享时这里是「程序数 × 名字数」量级 |
| `reads` | 本区间 `get()` 被读取次数 |

只在 `instances` 变化或本区间有读取时输出。

**实体 id 解析**（周期段）：

```
entityId[specialQueries=1860,specialResolves=0,specialHits=1860,idCacheHits=1798,idCacheMisses=62]
```

| 字段 | 含义 |
| --- | --- |
| `specialQueries` | 本区间 `getSpecialEntityId` 调用数（每实体每帧一次） |
| `specialResolves` | 两个硬编码名字（`minecraft:current_player`、`minecraft:zombie_villager_converting`）**重新解析**的次数。优化后应恒等于「本区间换过的实体 id map 数」（换光影包=1），不随实体数增长 |
| `specialHits` | 直接由缓存的两个特殊 id 回答的次数 |
| `idCacheHits` / `idCacheMisses` | 类→实体 id 缓存的命中/未命中 |

> 判读要点：`specialResolves` 远小于 `specialQueries`（且总量个位数）⇒ resolve-once 生效；
> 若两者同量级，说明每个实体都在重新解析（优化未生效）。`uniformPrevious[instances=N]` 的
> `N` 只随光影包声明的 uniform 名字变化 ⇒ 共享实例生效（未共享时每次程序注册都会新建）。

## 三、「日志行 → 判断生效」总对照表

| # | 优化 | 视为生效的证据 | 视为未生效 / 需要注意 |
| --- | --- | --- | --- |
| 1 | 程序落盘缓存 | 第二次加载同一包：周期段 `progCache[hit>0,miss=0,save=0]`；首次：`save≈builds` 且有 `estSavedCompileMs` | `reject>0`（驱动拒存，看 ERROR 行）；`miss>0` 且 `hit=0` 第二次仍全编译（键变了/盘上文件被清）；`Program binary cache: ... saving compiled shaders is off`（选项关着） |
| 1 | 地形程序同套统计 | `folders=.. complete=true` 的摘要里包含 celeritas 地形程序（`compileMs` 段）与 `ProgCreator` 路径同样的 `hit/miss/save` | `complete=false` + 上文 `Failed to compile shader folder ...`（ERROR） |
| 2 | 变换缓存 | 周期段 `transform[hit>0]`；`[ShaderTransformCache] ... hit cacheSize=..`；`warmup done ... cacheSize>0` | `warmup failed`（WARN）；建管线时 `miss>0`（预热未覆盖该维度） |
| 2 | 主菜单预读预热 | `Shader warmup: read the selected pack ahead and submitted its transform warmup in Nms` + 之后 `Shaderpack load: ... reused=true` | `Shader warmup: nothing was read ahead` / `no shaderpack selected`（未预读，属正常回退） |
| 3 | 包加载提速 | `Shaderpack load: ... compileMs` 与 `parseMs` 数值；`reused=true` 时 `parseMs` 极小 | `totalMs` 远大于三段之和 ⇒ 瓶颈不在解析/编译（看下一行文件夹日志找阶段） |
| 3 | PBR 索引 | `PBR resource index: scanned N packs, M PBR names in Xms` 且周期段 `state=ready(...)`、`answeredAbsent>0` | `PBR resource index disabled, falling back to direct lookups`（WARN）；`state=idle/scanning` 表示尚未建成 |
| 3 | 保留字改写 | 周期段 `glslRename[calls>0,avgUs=..]` | 段不出现 = 本区间没有需要改写的源码（属正常），或 `Render Timing Debug` 未开 |
| 4 | 图集并行解码 | `Atlas build: ... parallel=[decode=true,...,submitted≈decoded]` | `decode=false`（开关关/域名未授权）；`decodeFailures>0` 或 `decodeDesync=true`（有失败/失序，看 `Actinium` logger 的警告） |
| 4 | 并行 mipmap | `parallel=[...,mipmaps=true,...]` 且 `mipmapsOffloaded>0` | `mipmaps=false`（开关关，或所有精灵都是模组子类被白名单挡在主线程） |
| 4 | 批量图集上传 | `parallel=[...,batchedUpload=true]` 且 `batchedUploaded>0`；`phases.assembleMs` 有值 | `batchedUploaded=0`；或出现 `Batched atlas upload failed, remaining sprites use the per-sprite path`（WARN） |
| 4 | 图集阶段耗时 | `phases=[stitchMs=..,prepareMs=..,assembleMs=..,totalMs=..]` | 三段之和 << `totalMs` ⇒ 时间花在逐精灵上传循环里（并行未覆盖的部分） |
| 5 | 过滤模式 | `Terrain texture filtering: mode=... anisotropy=..x rgss=.. texelSnap=..` | `mode=NONE` 表示不加过滤；`mode` 与选项不一致但 `anisotropy=1x` ⇒ 驱动不支持各向异性，已按设计降级 |
| 5 | mipmap 策略 | `Atlas build: ... strategies=[CUTOUT=..,STRICT_CUTOUT=..,DARK_CUTOUT=..]` | `strategies=[MEAN=<全部精灵数>]` ⇒ 没有精灵命中规则/元数据，移植未触发 |
| 5 | gutter / mip 层 | `Atlas build: ... mipLevels=.. gutter=..` 与过滤行 `atlasMipLevels=.. gutter=..` 一致 | `gutter=0` ⇒ 未填充边界（mip=0 且非地形图集），此时各向异性路径不适用 |
| 6 | pacer | `paced=[frames=N,paced=M,ratio>0%]`；`cfg=[mode=ON/MAILBOX ...]`；锁定后 `locked=true` | `mode=OFF cap=0` ⇒ 无任何限制；`ratio=0%` ⇒ 帧不受 pacer 纳管 |
| 6 | FpsReducer | `reducer=[state=<非 FOCUSED>,cap=<非 0>]` + `FPS reducer: A -> B` 切换行 | `state=FOCUSED` 且 `cap=0` ⇒ 降帧未激活（窗口有焦点且未超时，属正常） |
| 7 | vsync 模式 | `VSync mode: requested=.. applied=.. honored=..` | GL 路径出现 `applied=MAILBOX` ⇒ 实际跑的是 SDL GPU 后端（不是本次验证的路径） |
| 8 | previous 矩阵共享 | `uniformPrevious[instances=N]`，`N` 只是 uniform 名字数（十几到几十） | `N` 随程序数增长 ⇒ 共享失效 |
| 8 | 实体 id 缓存 | `entityId[specialResolves≪specialQueries]`，`idCacheHits≫idCacheMisses` | `specialResolves≈specialQueries` ⇒ resolve-once 失效 |

## 四、验证记录

- `./gradlew check --no-daemon`（D:\Code\Actinium，Git Bash 正斜杠）：**通过**，
  根项目测试 1196 项 0 失败（含本次新增）。
- 本次新增/扩展的自动化测试（直接调用被测代码，无反射、无纯文本包含式断言）：
  - `com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCacheStatsTest`（5 项）：
    区间空报告、未命中编译计时与清空语义、会话均摊与 `estSavedCompileMs`、
    命中率与 `n/a` 分支。
  - `com.dhj.actinium.texture.atlas.AtlasLoadStatsTest`（3 项）：采集字段、
    采集外的记录被丢弃（`isCollecting` 门控）、单行输出格式。
  - `com.dhj.actinium.render.pacing.ReducerStatsTest`（4 项）：降帧状态段的四种形态。
  - `FramePacerTest` 新增 2 项：被纳管帧计数与清空、无节流时不纳管。
  - `TransformPatcherCacheTest` 新增 1 项：一次 miss + 一次 hit 的区间报告。
  - `PBRResourceIndexTest` 新增 1 项：真实查询驱动的 `pbr[...]` 计数。
  - `EntityIdHelperSpecialIdCacheTest` 新增 1 项：每个 id map 只重新解析一次。
  - `PreviousMatrixSharingTest` 新增 1 项：共享实例数与读取数。
- **未验证（待实机）**：上述日志在真实光影包/资源包下的取值（本任务只保证格式与统计逻辑，
  不替用户跑 `runClient`）。

## 五、相关文档

- `docs/research/sdl-gpu-program-binary-cache.md`（#2206 程序落盘缓存）
- `docs/research/sdl-gpu-shader-loading.md`（包加载提速、变换缓存与预热）
- `docs/research/atlas-parallel-loading-port.md`（#2167 图集并行加载）
- `docs/research/texture-filtering-port.md`、`docs/research/mipmap-strategy-port.md`（过滤与 mipmap）
- `docs/research/sdl-gpu-upstream-diffs.md`（本分支相对上游的差异总清单）
