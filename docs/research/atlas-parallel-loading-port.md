# 图集并行加载移植（上游 #2167）

最后更新：2026-10-11。

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
    （`@Invoker` 也解析到同一成员）、`IResourceManager.getResource` → `func_110536_a`、
    `TextureAtlasSprite.generateMipmaps(I)V` → `func_147963_d`、
    `getResourceLocation(TextureAtlasSprite)` → `func_184396_a`。
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
- **未验证（待实机）**：图集视觉正确性（gutter + 批量上传 + 并行 mipmap 三者叠加）、
  资源重载（F3+T）与光影包加载、并行解码/上传对启动时间的实际收益、
  以及非法/异常资源包在 worker 线程上的行为。`check` 只覆盖到编译、mixins 配置与纯逻辑测试；
  mixin 注入点是否真正命中要到客户端启动时才验证。
