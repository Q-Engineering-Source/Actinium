# 图集并行加载移植（上游 #2167）

最后更新：2026-10-14。

移植上游 Angelica 提交 `c10d30b2`（*Parallelize atlas loading and fix SDL-GPU mip-level targets*，
PR #2167）的 **A 部分**（图集加载并行化）。该提交的 **B 部分**（SDL-GPU mip-level target 修复）
在早前的 Phase 5 已同步，本次只做 reconcile，结论见文末。

## 移植内容

### celeritas-common

- `impl/texture/SpritePadding`：新增 `gutterForLevel(gutter, level)` 与
  `writePaddedLevel(...)`（把某一 mip 层按 clamp-to-edge 写进 staging buffer，支持行裁剪与 BGRA 交换），
  并把 `padFrame` 重写为共用同一套 `writeLevel` 写入逻辑。上游那版把旧的
  `padClampToEdge`/`clampBorderToEdge` 变成死代码，这里直接删除。
  并行路径不碰静态 scratch，per-sprite 回退路径继续用它。

### 根项目（`src/main`）

- `util/PrefetchLane.java`（新增）：上游原样移植的按序预取通道（窗口、跳过即弃、失序即desync、
  失败计数与首因、`awaitQuiescence`）。
- `texture/atlas/AtlasAssembler.java`（新增）：把整个图集按 ~4MB 分带、每带再按线程切条，
  在工作线程上把各 mip 层的扩散边像素写进 client staging buffer，每带每层提交一次纹理上传；
  写过的精灵通过回调登记，per-sprite 上传路径据此跳过。不能批量的地方（选项关闭、放置越界、
  对齐不符、带提交失败）一律回退到逐精灵上传。
- `texture/atlas/AtlasLoadSession.java`（新增）：一次图集加载的并行工作与会话状态（本图集的精灵集合、
  策略计数见「实质差异」第 9 条），1.12.2 结构适配见下。
- `texture/atlas/AtlasPackPolicy.java`（新增）：worker 线程可读的资源域判定
  （原生文件夹/zip 包按类信任 + 探针，其余包按类名由玩家授权，未授权的域整个串行并记录）。
- `texture/atlas/MipmapSafety.java`（新增）：能否把某个精灵的 mipmap 生成放到工作线程。
- `mixin/vintage/features/textures/MixinTextureAtlasParallelLoad.java`（新增）：驱动整个流程。
- `mixin/vintage/features/textures/mixinterface/AtlasSessionHolder.java`（新增）：把会话暴露给
  同目标的另一个 mixin（`MixinTextureAtlas`），后者需要据此跳过已写入的精灵。
- `mixin/vintage/features/textures/MixinTextureAtlas.java`：上传重定向先查会话的「已写入」集合，
  命中则跳过逐精灵上传。
- `mixin/vintage/features/textures/MixinTextureAtlasSprite.java`：新增 `loadSpriteFrames` 的解码
  钩子（取用 worker 解好的图，并把主线程自己的解码登记为 worker 用的解码函数）。
- `config/ActiniumRuntimeOptions.java`、`runtime/ActiniumRuntime.java`：三个并行开关与线程数的
  读取入口（系统属性优先，回退 sodium 选项），线程数在配置装载时写入 `GLSMConfig.workerThreadCount`。
- `gui/ActiniumGameOptionPages.java`：Advanced 页新增「纹理加载」分组
  （并行纹理解码 / 并行 Mipmap / 批量图集上传 / 工作线程数 / 纹理加载资源包按钮）。
- `gui/AtlasPackScreen.java`（新增）：资源包类信任列表屏（1.12.2 `GuiSlot` + Iris `IrisGuiSlot`/`IrisButton`）。
- `celeritas-common` 的 `SodiumGameOptions.PerformanceSettings`：四个开关/线程数 + 三个包类名列表
  （信任/拒绝/观察到），`load()` 补 `String[]` 缺省值。
- `resources/assets/celeritas/lang/{en_us,ru_ru,zh_cn}.lang`：各 15 个键。
- `resources/META-INF/actinium_at.cfg`：新增三条（见下）。
- `resources/mixins.actinium.vintage.json`：登记新 mixin。

## 1.12.2 结构适配（与上游的关键差异）

上游 1.7.10 的流程是「注册阶段就把每个精灵解码成 BufferedImage → 全部生成 mipmap → 拼接 →
批量上传」，所以它能在注册循环之后、拼接之前批量铺开解码与 mipmap 两条流水线。
**1.12.2 不是这样**：`loadSprite(PngSizeInfo, boolean)` 在注册循环里只读尺寸，
真正的解码在拼接之后、由 `generateMipmaps(IResourceManager, TextureAtlasSprite)` 逐精灵完成
（`loadSpriteFrames` = 解码 + 建帧，紧接着 `generateMipmaps(int)` = 生成 mipmap），
而且上传就发生在同一个循环迭代里。因此挂点与流水线这样落地：

| 上游挂点 | Actinium（1.12.2）挂点 | 说明 |
| --- | --- | --- |
| `Map.entrySet()` 前开会话 | 同（`loadTextureAtlas` 的第一个 `entrySet`） | 一致 |
| 播种解码通道（注册表顺序） | `Stitcher.getStichSlots()` 包装里，按**拼接槽顺序**播种 | 消费顺序即拼接槽顺序，通道窗口才对得上 |
| `IResourceManager.getResource`（注册循环内） | 私有 `generateMipmaps(IResourceManager, TextureAtlasSprite)` 内的 `getResource` | 1.12.2 只有这里会打开真正要解码的资源 |
| `ImageIO.read` 取预取图 | `TextureAtlasSprite.loadSpriteFrames` 内的 `TextureUtil.readBufferedImage` | 1.12.2 的解码入口在 `TextureUtil` |
| 逐精灵 `generateMipmaps(I)` join | 同名调用在私有 helper 内，用 `@WrapOperation` 就地甩到工作线程 | 见下 |
| `getStichSlots()` 里批量上传 | 同 | 一致 |
| `SpriteExtension.angelica$takeUploaded()` | 会话内「已写入」集合，键为**帧数组身份** | 见下 |

由此产生的实质差异：

1. **mipmap 与解码是同一段流水线**：上游把「解码」和「生成 mipmap」当成两批工作（先全部解码、
   再全部 mipmap）。1.12.2 里每个精灵必须先解码再算 mipmap，所以 `AtlasLoadSession.prepare`
   的做法是：先按拼接顺序播种解码通道，然后**逐精灵**调用原版私有方法
   （`@Invoker("generateMipmaps")`）——它在主线程取用 worker 解好的图、建帧，
   并把该精灵的 `generateMipmaps(int)` 甩给工作线程；循环结束后统一 join，
   再交给 `AtlasAssembler`。这比上游「每精灵各自 join」并发度更高，而且只有当帧数据齐全后才
   让装配器读取，逻辑上更紧。
2. **上传抑制改按帧数组身份**：上游在精灵上打 `angelica$setUploaded/ takeUploaded` 标记。
   Actinium 的注入点（`loadTextureAtlas` 的 `TextureUtil.uploadTextureMipmap`）拿不到精灵——
   这正是上一环为了躲开 `@Local` 歧义而用图集级 gutter 字段的原因。因此改为：装配器把它写过的
   精灵通过回调交给会话，会话用 `sprite.getFrameTextureData(0)` 的**数组身份**记账，
   上传钩子用收到的 `frameData` 反查。这样既不需要 `@Local(ordinal=0) TextureAtlasSprite`
   （1.12.2 的 `loadTextureAtlas` 里同类型局部变量不止一个，解析结果依赖编译器），
   也不需要新增 `SpriteExtension` API。
3. **`MipmapSafety` 不用反射**：上游用反射扫描精灵子类是否覆写了 `generateMipmaps`/`setFramesTextureData`，
   覆写则留在主线程。AGENTS.md 禁止用反射实现业务逻辑，所以这里改成显式白名单：
   只有 `TextureAtlasSprite` 本体并行生成 mipmap，一切模组子类留在主线程。
   这是上游行为的**严格子集**（上游会并行那些没覆写的子类），更安全，代价是少一点并行度。
4. **信任名单不带上游的 1.7.10 类名**：上游内置两批手审类名（GTNH 模组的资源包类）与一档
   「弱信任（claim-checked）」名单。这些类属于 1.7.10 分支，Actinium 没有对 1.12.2 分支的同类审计，
   而弱信任档本身就需要人手维护名单，因此两者都不移植：只有原生文件夹/zip 包按类信任，
   其余包类由玩家在「纹理加载资源包」屏里显式授权（未授权即整域串行，且会被记录下来供该屏列出）。
5. **会话生命周期与开关耦合**：批量上传的「已写入」记账需要会话，因此
   `AtlasLoadSession.begin` 只在三个开关**全关**时返回 null；三者全关时路径与上游一致地完全回到原版。
6. **`skipFirst` 检查去掉**：上游的 `TextureMap.skipFirst` 是 1.7.10 字段，1.12.2 没有。
7. **元数据预热去掉**：上游在 worker 上顺带触发 `texture`/`animation` 元数据解析；
   1.12.2 的元数据解析走资源包的 `MetadataSerializer`，没有证据表明其在 worker 上安全，
   且对预取没有收益，故不触发。
8. **AT 新增三条**：`SimpleReloadableResourceManager.domainResourceManagers`（private）、
   `FallbackResourceManager.resourcePacks`（protected）、`FileResourcePack.getResourcePackZipFile()`（private）。
   上游靠 mcpatcherforge 的 AT 文件开放前两条（该文件 Actinium 没有）。
9. **策略计数落在会话里**：图集构建摘要的 `strategies=[...]` 不再数 `generateMipmaps` 的调用次数，
   而是会话按**自己生成的精灵**回读 `SpriteMipmapExtension.celeritas$generatedMipmapStrategy()`
   （`MixinTextureAtlasSprite` 在 `generateMipmaps` HEAD 里解析并落字段），因此每个会话精灵恰好计一次
   （`Σstrategies = prepared = sprites - 1`）。本图集之外的调用——模组精灵 loader 在自己的 `load` 里
   生成、以及随后又被图集生成一次的重复生成——报成 `foreignMipmapCalls`；非图集窗口的调用不计。
   顺带消掉了原先在 `generateMipmaps` HEAD 里从工作线程写 `AtlasLoadStats` 的竞态。计数口径的日志
   说明见 `docs/research/sdl-gpu-port-stats-logging.md` 第 4/5 项。

## 1.12.2 帧时序缺陷与 A2 修复（conarm 派生纹理崩溃）

**症状**：装有 Tinkers' Construct / Construct's Armory 的环境里，图集加载在
`TextureMap.finishLoading` 的上传循环抛 `IndexOutOfBoundsException`（读第 0 帧）；出事的精灵是
conarm 的派生纹理，尺寸 1×1、`getFrameCount() == 0`。

**根因**：Forge 的注册循环里，`loadTexture` 对每个精灵的处理顺序是「先递归装载
`getDependencies()` 的依赖 → 再跑自定义 loader（`sprite.load(...)`）→ 最后私有
`generateMipmaps(manager, sprite)`」（后者 = `loadSpriteFrames` 解码帧 + `generateMipmaps(int)` 建链）。
契约是：**loader 被调用时，它声明的依赖的帧已经装载完毕**。`AbstractColoredTexture.load` 正是靠这一点：

```java
TextureAtlasSprite baseTexture = textureGetter.apply(backupTextureLocation);
if (baseTexture == null || baseTexture.getFrameCount() <= 0) {
  this.width = 1; this.height = 1;   // 失败分支，留下 1×1 / frameCount=0 的空精灵
  return false;                       // Forge 语义：false = loader 已自己填帧，继续拼图
}
```

而移植时把整个私有 helper 短路成了 `return true`（原 `angelica$deferSpriteGeneration`），帧装载被整体
推迟到拼接后的 `prepare`。于是注册循环里的 loader 读到的是**还没解码的依赖精灵**，走了失败分支：精灵仍被
`stitcher.addSprite`，最终在上传循环里读 `getFrameTextureData(0)` 崩掉。

即使不触发失败分支，原实现还有第二重风险：依赖精灵的链被甩给 worker，而
`MipmapGenerator.generateMipLevels` 会**就地重绘 base level**（`solidify`/`fillEmptyAreasWithDarkColor`），
loader 同时 `Arrays.copyOf` 该帧就会拿到半成品的像素。

**修复（A2）**：把私有 helper 的两件事拆开——**帧留在注册循环里装载**（恢复契约），**链按"谁读帧"分流**：

1. 删除 defer 短路，让注册循环真实执行私有 helper 的 `loadSpriteFrames`。
2. 私有 helper 内 `TextureAtlasSprite;generateMipmaps(I)V` 的 hook 语义从「一律 offload」改为
   `AtlasLoadSession.takeGeneration` 的三分支：属依赖闭包 → 本阶段不建链、登记为 deferred，稍后由
   `prepare` 在主线程建；否则 → 甩给 worker（保持与下一帧装载的重叠）；不可并行的模组子类 → 交回调方就地建。
3. 新增 `AtlasLoadPlan`：在注册循环开始前读注册表快照（`Maps.newHashMap(mapRegisteredSprites)` 的实例），
   产出两样东西——`decodeOrder`（按循环的 DFS 顺序：依赖在前、自身在后，逐步复刻 `loadTexture` 的递归与
   `loadedSprites` 去重）与 `deferredChains`（所有被声明为依赖的**名字**，含尚未注册的，循环里
   `registerSprite` 动态加入的依赖因此也能覆盖）。闭包按名字而不是按精灵身份，正是因为动态加入的依赖在
   播种时还不存在。
4. 解码通道播种点从 `prepare` 前移到会话开始（消费点回到注册循环，顺序才对得上）；`takeResource` 增加
   「未播种的精灵不查通道」的保护，避免动态注册的依赖被当成失序消费而让整条通道 desync。
5. `angelica$assembleAtlas` 里的 `preparedResult` 过滤段删除：生成失败的精灵在注册循环里就不会被
   `stitcher.addSprite`，原本该过滤段只是重复了这件事。
6. 诊断：`prepare` 对拼接后仍无帧的精灵打 WARN（精灵名 + 实际类名 + `hasCustomLoader` + 依赖列表），
   "某 mod 的 loader 产出空精灵"因此可一眼定位；不修不兜底，让原版在上传处 fail fast。

**与上游 1.7.10 的结构差异**：上游是「注册阶段解码成 BufferedImage → 全部生成 mipmap → 拼接 → 批量上传」，
帧与链天然是两个阶段，不存在"循环期间 loader 读别人的帧"这个耦合；1.12.2 的帧与链同在一个私有 helper 里，
所以修复只能把链这一步摘出来分流，帧必须留在循环内。同理，依赖闭包是 1.12.2（Forge `hasCustomLoader` /
`getDependencies` 契约 + 循环内解码）才需要的新概念。

## 选项与界面

- sodium 选项（Advanced 页「纹理加载」分组）：并行纹理解码、并行 Mipmap、批量图集上传
  （三者都标 `REQUIRES_ASSET_RELOAD`）、工作线程数（0 = 默认，标 `REQUIRES_GAME_RESTART`）、
  以及打开「纹理加载资源包」屏的外部按钮。
- 工作线程数是新增的对外选项：Phase 5 移植 `AngelicaWorkers` 时只保留了自动默认，
  现在由 `ActiniumRuntimeOptions.workerThreadCount()`（`actinium.workerThreads` 系统属性优先）
  驱动，并在配置装载时写入 `GLSMConfig.workerThreadCount`——该池同时服务光影包加载。
- `AtlasPackScreen` 按 Actinium 的 1.12.2 GUI 设施改写：列表用 Iris 的 `IrisGuiSlot`
  （`drawSlot(int,int,int,int,int,int,float)`、`elementClicked(int,boolean,int,int,int)`、
  `mouseClicked/mouseReleased/mouseScrolled`），按钮用 `IrisButton`，关闭时写回 sodium 选项。
  上游的 `SubScreenOption` 在 Actinium 用 `ExternalButtonControl` 代替。

## B 部分（SDL-GPU mip-level target）reconcile 结论

**已在，无需移植。**把 `c10d30b2` 中 sdl-gpu/glsm 的每一处新增行与 Actinium 现状逐条对账：

- 已在：`TextureStaging` 接口、`RenderBackend` 的四个 staging 方法与 client staging 缓存、
  `GLStateManager` 的四个包装、`AngelicaWorkers`（Actinium 版比上游多一个 `invokeAll`）、
  `GLSMConfig.workerThreadCount`、sdl-gpu 的 mip 级 FBO（`FboState.colorLevels[]`/`depthLevel`、
  `FBOClearTracker.clearColorLevel/clearDepthStencilLevel`、`PixelOps.mipLevelSize` 取尺寸、
  readback 按 mip 级、`TextureStagingRegion`/`trackMipUploadForTexture`、
  `LogicOpEmulator`/`PipelineApplier`/`TextureOps`/`FrameManager`/`ContextState`/`ShaderManager`）。
- 差异（既有 Phase 5 决策，非本次缺口）：display list 录制期改为抛
  `UnsupportedOperationException`（Actinium 无 `DisplayListManager.unsupportedInList`）；
  没有 `ModRedirector`/`RfbModRedirector`/`ShaderWorkSubmitter`/`getConstantPoolSearchStrings`
  （RFB 是 1.7.10 GTNH 启动器设施，Actinium 无对应物，也无其使用者）；
  `FrameManager`/`ShaderManager` 已演进到 #2191/#2192 之后的形态（`Submits.submit*`、
  `AsyncCompile`/`QueuedCompile`），所以逐行对账时显示为「未找到」；
  6 个 rig 测试（`GlsmSdl*`）仍未同步（需要 glsm-sdl 测试台）。
- 结论：B 无缺口；`docs/research/sdl-gpu-upstream-diffs.md` 的 Phase 5 记录与现状一致，
  只在本文件记录本次对账。

## 验证

- `./gradlew check --no-daemon` 通过。
- `./gradlew build --no-daemon` 通过；产物 `mixins.actinium-refmap.json` 里本次新增的注入点全部
  解析到真实 SRG 成员，可确认目标存在且签名一致：
  - `MixinTextureAtlasParallelLoad`：`loadTextureAtlas` → `func_110571_b`、
    `Stitcher.getStichSlots` → `func_94309_g`、私有 helper
    `TextureMap.generateMipmaps(IResourceManager,TextureAtlasSprite)Z` → `func_184397_a`
    （两处 `@WrapOperation` 挂在它内部）、`IResourceManager.getResource` → `func_110536_a`、
    `TextureAtlasSprite.generateMipmaps(I)V` → `func_147963_d`、
    `getResourceLocation(TextureAtlasSprite)` → `func_184396_a`。
    A2 之后 `loadTexture(Stitcher,...)` 的 defer 注入与私有 helper 的 `@Invoker` 都已删除，同一位置改挂
    `loadTextureAtlas` 里 `HashMap.entrySet()` 的 `@WrapOperation`（JDK 成员，`remap = false`，故不进
    refmap）；会话由它拿到注册循环真正迭代的那份注册表快照。
  - `MixinTextureAtlasSprite`：`loadSpriteFrames` → `func_188539_a(IResource;I)V`、
    `TextureUtil.readBufferedImage` → `func_177053_a`。
- 新增/修改的自动化测试：
  - `com.dhj.actinium.util.PrefetchLaneTest`：窗口限制、跳过即弃、失序即 desync 并关闭、
    失败计数与首因、关闭时的清理、null 任务、窗口参数校验、提交器抛错计入失败。
  - `dhj.embeddedt.embeddium.impl.texture.SpritePaddingTest`：新增 `gutterForLevel`、
    staging 写入的行裁剪重组一致性（随机化 × 各挡位 × 交换 R/B）、
    矩形外字节不被触碰（并发切条的前提）、int[] 与 staging 两种 sink 输出一致。
  - `com.dhj.actinium.texture.atlas.MipmapSafetyTest`：本体可并行、子类一律主线程。
  - `com.dhj.actinium.texture.atlas.AtlasAssemblerTest`：分带高度对齐且不越界、
    跨失败带的精灵不算已覆盖。
  - `com.dhj.actinium.texture.atlas.AtlasPackPolicyTest`：原生包可并行、未知包类使整域串行并记录、
    被授权后可并行、被拒绝优先、单个包探针抛错则整域串行。
  - `ActiniumRuntimeOptionsTest`：新增 `resolveWorkerThreadCount` 的系统属性覆盖与回退。
  - 未移植上游的 `AtlasAssemblerStripeTest`（整篇用反射访问私有内部类与方法，
    违反 Actinium 的测试规范）；其覆盖的切条写入不变量由上面的 `SpritePaddingTest` 用例承担。
  - `com.dhj.actinium.texture.atlas.AtlasLoadPlanTest`（A2 新增）：依赖先于声明者播种、依赖链外层最后、
    多个精灵依赖同一精灵时只播种一次、循环依赖终止、被声明的依赖全部进入 `deferredChains`、
    注册表尚未持有的依赖按名字覆盖。
  - `com.dhj.actinium.texture.atlas.AtlasLoadSessionTest`（A2 改写）：按注册循环的真实调用序列驱动
    （`beginRegistration` → 逐精灵 `takeGeneration` → `prepare`），除原有计数与策略口径外新增
    「只有 deferred 的精灵在装配前建链、且只建一次」「无人读取的精灵交给 worker
    （按 `mipmapsOffloaded` 断言）」。原「同名不同身份的精灵不算本图集」断言随 `expects` 改为按名字
    判定（并发读安全，见会话注释）而更新为「未注册的精灵不算本图集」。
- **未验证（待实机）**：图集视觉正确性（gutter + 批量上传 + 并行 mipmap 三者叠加）、
  资源重载（F3+T）与光影包加载、并行解码/上传对启动时间的实际收益、
  以及非法/异常资源包在 worker 线程上的行为。`check` 只覆盖到编译、mixins 配置与纯逻辑测试；
  mixin 注入点是否真正命中要到客户端启动时才验证。
- **待实机验证（本案例，A2 新增）**：TiCon / Construct's Armory 派生纹理
  （`AbstractColoredTexture` 一族：`hasCustomLoader == true` + `getDependencies()` 声明基纹理）——
  注册循环内依赖帧必须先装载，装配阶段不再有派生精灵的 1×1 / `frameCount == 0` 崩溃；
  同时确认派生纹理着色与 mipmap 一并正确（依赖链改由装配前主线程建链）。

