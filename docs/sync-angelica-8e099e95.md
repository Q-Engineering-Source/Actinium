# Angelica 上游逐提交台账（02f0b0fc → 8e099e95）

## 基线与范围

| 项 | 值 |
|---|---|
| 上游仓库 | `D:/Code/Angelica`（只读参照，本次未修改） |
| 范围 | `02f0b0fc..8e099e95` |
| 起点 SHA | `02f0b0fca17808303d9a5cdad7f245232219198a`（Fix mipmaps bleeding (#2112)） |
| 目标 SHA | `8e099e952921a53ea5db88414e1071135f716ed4`（Free chunk-meshing memory when leaving a world (#2313)，2026-10-29） |
| 本地基线 | Actinium `ef01e10cf2967d867c5d1d2556d664301bc07bbb`（`main`，2026-10-31） |
| 分支 | `chore/upstream-sync-baseline` |
| 提交总数 | 132（机械核对，见文末） |
| `未分配` 条目 | 0 |

统计：待同步 69、分 hunk 同步 47、已有等价 2、不适用 14。

## 边界

本台账只覆盖 1.12.2（Cleanroom Loader）+ LWJGL3 的 Actinium 目标。上游 `sdl-gpu/`、`moltenvk/`、`umbra/`、`tracy-client/`、`lwjgl2-backend/`、`experimental-surround/`、`modern/`、`ornithe/`、`forge122/`、`forge1710/` 与 `common/` 这些本地不存在的模块，其专用实现一律判为不适用；混合提交按 hunk 拆分，逐项写出通用代码去向与排除部分，不对提交整体判不适用。

`.github/`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`dependencies.gradle.kts`、`buildSrc/`、`README`、`libs.versions.toml` 等 CI、发布、版本号与治理文件同样判不适用。

## 状态定义

- **待同步**：本地缺少对应实现，且该实现落在 1.12.2 + LWJGL3 范围内，需要移植。
- **已有等价**：已核对本地代码，行为等价物存在并给出具体位置，不需要移植。
- **分 hunk 同步**：同一提交内同时存在需要移植的通用代码与需要排除的平台专用代码，逐项拆分。
- **不适用**：目标代码全部落在本地不存在的模块、平台或治理文件中，或依赖的本地设施不存在。

**未核对本地代码的条目不得标“已有等价”。** 本台账 2 条“已有等价”均给出文件路径与行号。

## 逐提交台账

| 序 | 短 SHA | 上游标题 | 日期 | 状态 | 目标 PR | 处置说明 |
|---|---|---|---|---|---|---|
| 1 | `939e3146` | More Cloud Optimizations (#2114) | 2026-09-19 | 待同步 | F01 | 本地无云层渲染设施（`CloudRenderer`、`CloudVertexMesh`、`CloudShape` 均缺失），F01 需先建立该设施 |
| 2 | `b6a4497f` | Redirected Util.checkGLError to respect disableErrorChecks (#2110) | 2026-09-19 | 待同步 | G03 | 重定向本身本地已有（`GLSMRedirector` 的 `org/lwjgl/opengl/Util checkGLError()V` desc 重定向与 `GLStateManager.checkGLError`，见 `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:5752`），但本地 `glGetError`（同类第 5746 行）没有 `isNoErrorChecks()` 短路，`GLSMInitConfig` 也没有该开关。上游在 `02f0b0fc` 时 `glGetError` 已带此门控，该提交让 `checkGLError` 与之对齐；本地缺这门控，不能称行为等价，需随 G03 一并补上开关与短路 |
| 3 | `ef68af58` | Fix texture filtering sampling regression (#2115) | 2026-09-19 | 待同步 | S02 | 修正 `TERRAIN_NO_MIPS` 与 `USE_TEXEL_SNAP` 采样路径；本地 `block_layer_opaque.fsh` 无该分支，地形选项也无 texel snap 开关 |
| 4 | `c578970a` | Fix edge cases with cloud wraps (#2122) | 2026-09-20 | 待同步 | F01 | 云盘包裹边界，依赖 F01 云层实现 |
| 5 | `87c83f06` | fix asm class dumper following rfb changes (#2123) | 2026-09-21 | 不适用 | — | 修正 RFB `ClassNodeHandle` 转储路径；本地 `AngelicaClassDump` 直接处理字节数组，不经过该 API |
| 6 | `9dd60f6a` | Render pass and state stack optimizations (#2124) | 2026-09-21 | 分 hunk 同步 | G01 | 取通用 hunks：`StateSet`、`CowStateStack`/`CowDispatch`/`CowDepths`、`StackIdAllocator`、`ClipPlaneBooleanState`、`Feature`、`GLContextState`、`GLStateManager`、各状态栈与 FFP key 类，以及 `glsm/config/SystemProperties`。排除 `sdl-gpu/` 的 30 个文件与 `dependencies.gradle.kts` 的版本号变更 |
| 7 | `53776684` | Properly handle display lists with the push/pop optimizations (#2126) | 2026-09-22 | 待同步 | G01 | display list 与 push/pop 优化交互，本地 `DisplayListManager` 无 `StateSet` 支撑 |
| 8 | `f2c07dea` | Weather instancing (#2127) | 2026-09-22 | 待同步 | F01 | 天气实例化，本地缺 `Instancing`、`WeatherInstancedAttribs`、`WeatherQuadMesh` |
| 9 | `a23cefba` | Only enable shader DH compat if DH is enabled (#2130) | 2026-09-24 | 已有等价 | — | `shader/src/main/java/net/coderbot/iris/gl/shader/StandardMacros.java:121` 已用 `DHCompat.hasRenderingEnabled()` 作为 `DISTANT_HORIZONS` 宏门控 |
| 10 | `888a4de0` | Add Galaxy Space compat and fix culling issues (#2131) | 2026-09-25 | 待同步 | F01 | 银河空间云层兼容与剔除修正，依赖 F01 云层重写 |
| 11 | `1babfd96` | Properly reset/flush state for crash interceptors (#2133) | 2026-09-25 | 分 hunk 同步 | G05 | 取通用 hunks：`glsm/GLDebug`、`GLStateManager`、`backend/RenderBackend`、`glsm/ffp/ShaderManager`、`client/font/BatchingFontRenderer`、`commands/AngelicaCommand`、`compat/bettercrashes/BetterCrashesCompat`、`hudcaching/HUDCaching`、`rendering/RenderRecovery`、`rendering/tesr/ModelPartBatcher`、`TesrBatchRenderer`、`TesrBlendScope`、`MixinTileEntityRendererDispatcher`。本地缺 `RenderRecovery`、`HUDCaching`、`BetterCrashesCompat`。排除：`sdl-gpu/SDLGPURenderBackend`、`sdl-gpu/frame/ContextState` 两个 SDL 帧状态文件 |
| 12 | `762c5661` | Stop blocking chunk workers on deferred ISBRH blocks (#2136) | 2026-09-26 | 待同步 | C09 | 延迟 ISBRH 区块任务，本地缺 `DeferredMeshScheduler`、`DeferredBlock`、`AngelicaBlockSafetyRegistry` |
| 13 | `b7628404` | Fix shader packs in modded dimensions (#2137) | 2026-09-26 | 待同步 | P01 | 模组维度光影包解析；本地 `ShaderPack.getProgramSet` 无 `resolveDimensionFolder`，也没有 `legacyDefaultFolder` |
| 14 | `44c930f4` | Fix scissor clears wiping the whole screen on SDL-GPU (#2138) | 2026-09-26 | 分 hunk 同步 | G03 | 取 `glsm` 侧 `ScissorState`、`ScissorStateStack`、`Feature`、`FeedbackManager`、`CowDispatch` 与对应测试；排除 `sdl-gpu/` 的 `AttachmentClear`、`PixelOps` 与 SDL 清除跟踪测试 |
| 15 | `7f3fb35e` | Use actual camera when doing cloud related culling and fuctions (#2139) | 2026-09-26 | 待同步 | F01 | 云剔除改用真实相机，依赖 F01 |
| 16 | `7c28a69b` | Optimize Enchant Glint Rendering (#2140) | 2026-09-26 | 待同步 | F02 | 附魔闪光合并渲染，本地缺 `CombinedGlint`、`GlintClock`、`ModelPartBatcher` |
| 17 | `a8c29fa3` | Give each GL drawable its own GLSM context (#2142) | 2026-09-26 | 分 hunk 同步 | G02 | 取通用 hunks：`GLContextState` 构造与 `GLStateManager` 的按 drawable 取上下文路径、`DisplayListManager`/`DisplayListIDAllocator`、`FeedbackManager`、`CompatUniformManager`、`QuadConverter`、`ShaderCache`/`ShaderManager`/`Uniforms`/`VertexKey`/`FragmentKey`、`VAOManager`、`AttribSnapshot`、`StreamingVaos`、`StackIdAllocator` 与各 state stack。本地 `GLStateManager.ctx()` 目前只有主上下文与 worker `ThreadLocal`（`glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:179`），无按 drawable 创建上下文的路径。排除 `sdl-gpu/` 与 `FrameHooks`、`TextureUtilHooks` 这类 SDL 生命周期钩子 |
| 18 | `c9d46e70` | Update to Celeritas 2.5.12 with translucency sorting v3 (#2151) | 2026-09-27 | 部分已同步 | C01 + C04 + C06 + C07 + F02 + F07 + S02 | 按 `git show c9d46e70 --name-status` 的实际 diff 拆分。排序 v3 消费端与 `CutPlaneIndex`、`PartitionTree` 本地已有等价（`celeritas-common` 的 `sorting/SortState`、`sorting/PartitionTree`、`sorting/CutPlaneIndex` 与 `RenderSection.translucencySortStates`），不重复移植。待处理：新增 `debug/DebugKeyHandler` 与 `AngelicaConfig.verboseF3`（F3+V 切换）归 F07；新增 `MixinShaderLoader` 在 `RenderSystem.isGLES()` 时禁用 GLSL 1.20 路径归 C01；新增 `MixinSharedQuadIndexBuffer` 与 `MixinRenderRegionManager` 的 `TerrainDrawStats.recordIndexBufferGrowth`/`recordSectionUpload` 埋点、`TerrainDrawStats` 的三个累计计数与 getter、`CubeStatusTracker` 由 `implements ChunkTracker` 改为 `extends ChunkTracker`（本地 `celeritas-common/.../chunk/map/ChunkTracker` 是类，前置成立）归 C04；`ShadowRenderer` 删除 `preSubmitGraphUpdate` 与 `preSubmitFrustumHolder`、改 `shadowSearchPending`，`AngelicaRenderSectionManager` 的 `canSubmitShadowGraphSearch`/`submitShadowGraphSearch` 改 `protected` 并加 `shadowSearchSubmissions` 计数、删除 `initialCameraSectionReady` 与 `update` 的阴影 pass 早退归 C06；`CeleritasWorldRenderer` 新增 `shadowPassPlayerViewport` 字段与 `setShadowPassPlayerViewport`、新增无参的 `setupShadowTerrain` 重载（在 `clipRenderersByFrustum` 入口取回玩家视口，未捕获时回退到 `getLastViewport()`）、`setupTerrain` 改用 `renderSectionManager.didShadowPassRunThisFrame()` 配合 `lastCameraState` 复用阴影 pass 的相机状态，并删除 `setCurrentViewport`/`getCurrentViewport`/`collectTileEntitiesForShadow`，同样归 C06；本地 `SimpleWorldRenderer` 与 `RenderSectionManager` 已有 `setupShadowTerrain` 与 `didShadowPassRunThisFrame`（`celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/RenderSectionManager.java:339`），但 `CeleritasWorldRenderer` 尚无 `shadowPassPlayerViewport` 字段；`AngelicaFogService.INSTANCE` 与删除 `META-INF/services` 的 `FogService` 登记归 C07；`PackShadowDirectives.shouldRenderLightBlockEntities`（含 `ShadowRenderer` 的 `shouldRenderLightBlockEntities` 字段与 debug 串）归 F02；`AngelicaRenderPassConfiguration.samplerDefines` 抽取归 S02，但 `block_layer_opaque.fsh` 的 `v_RdhFactor`、`v_QuadCoord` 与 `u_FogShape` 输入本地已具备（`src/main/resources/assets/actinium/shaders/blocks/block_layer_opaque.fsh` 第 8、9、30 行，vsh 侧第 14、15、32 行），不重复同步。该提交在上游还删除 `SectionAgeMath`、`SectionAgeMathTest`、`AngelicaChunkTracker` 与 `MixinDefaultChunkShaderInterface`，本地不存在这四个文件，删除项不构成移植内容。排除：`sdl-gpu/` 全部、`gradle/libs.versions.toml` 的 Celeritas 版本与 `lang/en_US.lang` 文案。已同步（批次二第 11、13、15 项）：`MixinShaderLoader` 落 `src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinShaderLoader.java`；`TerrainDrawStats`、`MixinSharedQuadIndexBuffer`、`MixinRenderRegionManager` 落 `src/main/java/com/dhj/actinium/render/terrain/` 与同名 mixin 包，`TerrainDrawStats` 只移植三个累计计数与 getter（`take*` 系列依赖的 `recordBatch`/`recordCullRegion`/`recordSectionMetaBytes` 与 `Tracy` zone 归后续 F07 调试屏批次，本地无消费方）。`CubeStatusTracker` 已按 `extends ChunkTracker` 移植到 `src/main/java/com/dhj/actinium/render/terrain/CubeStatusTracker.java`，但本地无 CubicChunks 事件源（1.12.2 无 `com.cardinalstar.cubicchunks.util.HashSet3D`/`HashMap3D`，改用 fastutil 长整型集合打包 xyz），也没有 `MixinWorldClient` 的 `ModStatus.isCubicChunksLoaded` 分支，该类当前无构造点。剩余未同步：shadow 适配（C06）、`DebugKeyHandler` 与 `verboseF3`（F07）、`PackShadowDirectives.shouldRenderLightBlockEntities`（F02）、`samplerDefines`（S02）。
| 19 | `e17b45c8` | Fix NEI items vanishing with ModularUI2 screens open on SDL-GPU (#2153) | 2026-09-27 | 不适用 | — | 全部文件位于 `sdl-gpu/`，为 SDL 附件清除与管线 |
| 20 | `233c74e0` | Better Biome Blending (#2152) | 2026-09-27 | 待同步 | F11 | 生物群系混色，本地缺 `BiomeVertexBlender`、`SmoothBiomeColorCache`、`SkyColorCache` |
| 21 | `74ea5c47` | Add in-game Tracy capture (#2156) | 2026-09-27 | 分 hunk 同步 | F09 + F10 | 取通用 hunks：`rendering/FpsReducer`（帧率限制器，归 F09）；`zoom/Zoom`（仅将 `zoomKey` 的 key category 从 `key.categories.misc` 改为 `AngelicaMod.KEY_CATEGORY`，无 mixin 变更，归 F10）；`SodiumOptionsGUI`/`ActionOption`/`ButtonOption`/`SubScreenOption`/`ControlValueFormatter` 的选项控件、`AngelicaCommand`/`AngelicaMod`/`ClientProxy`/`Mixins`/`AngelicaConfig` 的入口接线与 `en_US.lang` 文案、`AsprofRecorder` 与 `EarlyLoadingBoundaryTest`。排除：`tracy-client/build.gradle.kts` 与 `tracy-client/src` 的原生采集、`glsm/profiling/Tracy*`、`client/gui/TracyOptionPages`、`debug/profiling/TracyCaptureNotifier`、`.github/scripts/build-tracy-natives.sh`、`.github/workflows/build-tracy-natives.yml`、`libs.versions.toml` 的 tracy 版本与 `scripts/flyby-capture.sh` 的采集参数 |
| 22 | `7bb5bfd4` | Emulate color logic ops and fix pixel readback on SDL-GPU (#2157) | 2026-09-27 | 分 hunk 同步 | G05 | 取 `RenderBackend` 的逻辑运算与回读接口、`GLStateManager` 委托与 `Lwjgl3GLRenderBackend` 实现；排除 `sdl-gpu/` 的 `LogicOpEmulator`、`PixelOps`、`PixelPack` |
| 23 | `af599f29` | Fix Iris issues with instancing and batching (#2158) | 2026-09-27 | 分 hunk 同步 | F02 + S06 | 取实例化与批量渲染的 Iris 交互（`GlobalStateUniforms` 之外与 batching 相关的 `glsm/ffp/InstancedAttribs`、`FfpExtendedAttribs`、`BatchStateGuard`、`DisplayListManager` 与 `recording/` 命令扩展），归 F02；取 `DisplayListTextureAnimationTest` 与 `IPatchedTextureAtlasSprite`/`MixinTextureAtlasSprite` 的图集侧改动，归 S06。排除 `sdl-gpu/src/test` 与 `dependencies.gradle.kts` |
| 24 | `9814a96b` | Fix attrib stack overflow from world-less TESR item renderers (#2159) | 2026-09-28 | 待同步 | F02 | 无世界 TESR 物品渲染的属性栈溢出，本地缺 `TesrProviderDispatch` |
| 25 | `1f274a64` | TESR state and batching leak fixes (#2162) | 2026-09-28 | 待同步 | F02 | TESR 状态与批量泄漏，本地缺 `StateSet`、`BatchDrawDefaults`、`DrawState` |
| 26 | `9081f4c3` | Various font improvements and fixes (#2165) | 2026-09-29 | 待同步 | F05 | 字体改进，本地缺 `FontConfigScreen`、`FontProviderCustom` |
| 27 | `2f4ac2a9` | Apply the baked transform to vertices written directly into BakedTransformCapture (#2164) | 2026-09-30 | 待同步 | F02 | 依赖 `BakedTransformCapture`，本地缺失 |
| 28 | `c10d30b2` | Parallelize atlas loading and fix SDL-GPU mip-level targets (#2167) | 2026-09-29 | 分 hunk 同步 | S02 | 取 `glsm` 侧 `ShaderWorkSubmitter`、`GLSMConfig`、`GLSMHooks` 与图集策略（`AtlasPackPolicy`、`MipmapSafety`、`PrefetchLane`）；排除 SDL mip 目标、`sdl-gpu/` 资源层与全部 `sdl-gpu` 测试 |
| 29 | `977db3d6` | Add option to change mouse movement from Cinematic to Standard while in Angelica Zoom (#2166) | 2026-09-29 | 待同步 | F10 | 缩放期间鼠标模式选项，本地无 `Zoom`、`Settings` |
| 30 | `97057224` | Fix Depth Mask Issue for Nametags, etc for Text (#2168) | 2026-09-29 | 待同步 | F05 | 字体深度掩码按分段记录；本地 `BatchingFontRenderer` 无 `deferredSegments` 结构与 `depthMask` 分段字段，`glDepthMask` 在该文件中不出现，需先有分段批量结构再引入该修正 |
| 31 | `fa71eda3` | Font Texture Crash Fix on Splash Screen (#2169) | 2026-09-29 | 待同步 | F05 | splash 字体纹理绑定回退；本地 `MixinFontRenderer` 有 splash 分支，但 `BatchingFontRenderer` 无 `textureFor` 回退 |
| 32 | `cd0a9608` | Fix async atlas with KaizPatchX (#2175) | 2026-09-30 | 待同步 | S02 + F12 | 异步图集与 KaizPatchX，本地缺 `AtlasPackPolicy`、`EmissiveTextureAutoloader` |
| 33 | `cbd16b1a` | Fix shadow pass receiving a full-bright lightmap constant and misreporting terrain renderStage (#2170) | 2026-10-01 | 待同步 | P01 | 阴影 pass 全亮亮度常量与 terrain renderStage 误报；本地 `ShadowRenderer` 无对应设置点 |
| 34 | `9cb35c67` | Dark mode text recoloring with resource packs configs (#2118) | 2026-10-01 | 待同步 | F06 | 深色模式文字重着色，本地无 `DarkModeFontTransform`、`DarkModeUtils` |
| 35 | `a8e95f70` | Improve biome blending and improve compat (#2180) | 2026-10-01 | 待同步 | F11 | 生物群系混色兼容，依赖 `BiomeVertexBlender` |
| 36 | `d16c1e7c` | Internal format fix (#2179) | 2026-10-02 | 待同步 | F05 | `FontProviderCustom` 的纹理内部格式修正；本地无 `FontProviderCustom`，随 F05 的自定义字体提供者一并引入 |
| 37 | `66d2a548` | Add more targets for darkmode transformer (#2178) | 2026-10-02 | 待同步 | F06 | 深色模式转换目标扩充，改 `loading/shared/transformers/DarkModeFontTransform`；本地无该转换器，随 F06 的 darkmode 一组一并建立 |
| 38 | `fe67a22d` | Improve shader support for custom block rendering (#2176) | 2026-10-01 | 待同步 | P01 | 自定义方块渲染的光影支持，本地缺 `StateAwareTessellator`、`ImmediateExtendedAttribHandler` |
| 39 | `6c888a63` | Cut per-frame allocations in rendering mixins (#2182) | 2026-10-02 | 分 hunk 同步 | C09 | 取通用 hunks：`src/mixin/java` 的渲染 mixin（`surround` 调用点改造）、`src/main/java` 与 `glsm/` 的配套类、`src/test/java` 的对应测试，以及 `scripts/flyby-smoke.sh`、`flyby-smoke-check.py`。排除：`experimental-surround/ap/`（7 个文件）与 `experimental-surround/runtime/`（33 个文件），`experimental-surround/surround.settings.gradle.kts`、`surround.root.gradle.kts`、`surround.project.gradle.kts`（3 个构建脚本），以及 `gradle/libs.versions.toml`、`settings.gradle.kts`、`build.gradle.kts` 的构建与版本号变更 |
| 40 | `8387b4e9` | Add Darkmode Transformer target (#2184) | 2026-10-03 | 待同步 | F06 | 深色模式转换目标补充，同 `DarkModeFontTransform`；随 F06 的 darkmode 一组一并建立 |
| 41 | `bc08e4ba` | Fix signs being ungodly bright (#2185) | 2026-10-02 | 待同步 | F02 | 告示牌过亮，本地缺 `DynamicLights`、`TileEntityLight` |
| 42 | `9f19d94c` | Fix the look of EFR Boats under batching (#2186) | 2026-10-02 | 待同步 | F02 | EFR 船批量渲染外观，本地缺 `EntityMaterials` |
| 43 | `db7d3284` | Stop shaders from forever breaking entity instancing (#2187) | 2026-10-02 | 待同步 | F02 | 实例化被光影永久禁用，本地缺 `EntityRenderScope` |
| 44 | `80e5b544` | Fix voxeliation performance under SDL GPU (#2188) | 2026-10-02 | 分 hunk 同步 | F04 | 取通用 hunks：`rendering/voxelization/ShadowVoxelizer`、`VoxelRangeTable`、`VoxelRangeTableTest`、`ShadowVoxelizerTest`、`glsm/backend/RenderBackend` 的体素区域绑定接口、Iris 的 `RwImageStoreExtractor` 与 `RwImageStoreExtractorTest`、`rendering/celeritas/AngelicaChunkRenderer` 的体素调用点。本地 Iris 侧 `ShadowRenderer` 已有 voxelization 引用。排除：`sdl-gpu/SDLGPURenderBackend`、`sdl-gpu/compute/VoxelizationDispatcher`、`VoxelizationDispatcherTest`、`GlsmSdlVoxelRegionBindTest`、`MslCrossCompileTest`，以及 `SdlShadowVoxelizationSink`（SDL 专用）、`profiling/TracyFramePlots` |
| 45 | `12fa08ab` | Cut redundant GLSM state tracking (#2189) | 2026-10-02 | 待同步 | G03 | 冗余 GLSM 状态跟踪削减，依赖 `CowDepths`、`CowStateStack` |
| 46 | `38b1d9a0` | Speed up boot (#2190) | 2026-10-02 | 分 hunk 同步 | S03 + F12 | 取 `GlslTransformUtils` 与 `com/prupe` 的 `CITUtils`、`CTMUtils`、`TileLoader`（F12）；排除 `sdlgpu/shader/ShaderManager`。已同步（批次二第 19 项）的 `GlslTransformUtils` 半：保留字正则改为 `sample(?<=\bsample)\b`、`new(?<=\bnew)\b`、`sampler(?<=\bsampler)\b(?!\d)`，`parseFullQuiet` 改走 SLL + `BailErrorStrategy`，失败时 `reset()` 后切 `DefaultErrorStrategy` + LL 重试；`com/prupe` 半属第 38 项 |
| 47 | `1587a12c` | Work around SDL Metal fence bugs and pump during present (#2191) | 2026-10-02 | 分 hunk 同步 | G05 + S03 | 取通用 hunks：`glsm/backend/MainThreadPump` 与 `MainThreadPumpTest`（G05 主线程泵）、`glsm/profiling/DebugCounters`（S03 的计数器设施）与 `debug/flyby/FlybyRunner` 的计数器采集。排除 `sdl-gpu/` 的 23 个文件（fence 探测、presenter、transfer thread 与对应测试） |
| 48 | `85892c0f` | Fix DSA binding restore and liquid colors (#2192) | 2026-10-02 | 分 hunk 同步 | G03 + F12 | 取 DSA 绑定恢复、`TextureTracker` 与 `MultiListenerNotifier`（G03）；`mcpatcherforge` 的 `MixinRenderBlocks` 重组归 F12；排除 SDL 绑定查询测试与 Lwjgl2 后端 |
| 49 | `d876a203` | Cut more per-frame allocations in terrain and rendering (#2193) | 2026-10-02 | 待同步 | C09 | 地形与渲染每帧分配削减，依赖 `FenceQueue`、`GlStreamingRing`、`RenderListPool` |
| 50 | `37afddab` | Cache transformed and compiled shaders on disk (#2194) | 2026-10-02 | 分 hunk 同步 | S01 + S04 + S05 | 取 `glsm/shader` 的 `ShaderDiskCache`、`ShaderCacheIO` 与 `CompatShaderTransformer` 接线；排除 `sdlgpu/shader` 的交叉编译缓存与阶段序列化。已同步（批次二第 18 项）：`ShaderCacheIO`、`ShaderDiskCache` 落 `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/shader/`；`CompatShaderTransformer.transform` 改为先查内存缓存、再查磁盘、最后走 AST 变换，并抽出 `minGlslVersion()` 与 `diskKey()`；`SpirvCompiler` 增加磁盘层与 `compile(..., boolean diskCache)` 重载；`ClientProxy.configureShaderDiskCache` 由 `Actinium.onInit` 调用。`ShaderDiskCache.resetForTesting()` 替代上游 `testutil.Reflect` 的静态字段写入，因为本地无该测试工具且 AGENTS.md 禁止测试用反射。`@Lwjgl3Aware` 注解未移植，本地无 lwjgl3ify 依赖 |
| 51 | `bdcb4d18` | Fix Complementary + Euphoria Patches ULTRA on SDL-GPU (#2195) | 2026-10-02 | 分 hunk 同步 | S05 | 取 `GlslVulkanPreprocess` 与 Iris `ShaderTransformer` 修正；排除 SDL/GLES 交叉编译与预热测试。已同步（批次二第 19 项）：`GlslVulkanPreprocess` 的 `TARGET_RESERVED` C++ 保留字集合与 `IDENTIFIER_RENAMES` 内置标识符重命名；Iris 侧的 `isSDLGPU` 分支与 `extractRwImageStores` 改签名未移植，本地无 `RwImageStoreExtractor`，`ShaderTransformerTest` 依赖 `StageArtifact` 也未移植 |
| 52 | `c97fb700` | Fix (#2196) | 2026-10-02 | 不适用 | — | 单个 `sdl-gpu` 测试文件 |
| 53 | `75a02781` | Fix splash issues (#2200) | 2026-10-03 | 待同步 | F05 | splash 字体问题；本地 `FontProviderMC`、`FontProviderUnicode` 结构与上游不同，需按本地结构调整 |
| 54 | `338d9419` | Settle frame pacing after an inconsistent vsync gate (#2197) | 2026-10-03 | 已同步 | F09 | 已落地：`com/dhj/actinium/render/frame/` 下的 `PacerCore`、`FramePacer`、`PacerSleeper`，glsm 侧的 gate 采样与 `VSyncMode`，机制与日志见 `docs/compat/intel-present.md` |
| 55 | `c7a0f3a2` | Default raw custom textures to bilinear filtering and clamping (#2198) | 2026-10-04 | 已有等价 | — | `shader/src/main/java/net/coderbot/iris/shaderpack/ShaderPack.java:468` 起 `readRawTexture` 已默认 `blur = true`、`clamp = true`，仅 `.mcmeta` 可覆盖 |
| 56 | `fe82cd32` | Plenty of fixes for GTNH (#2202) | 2026-10-03 | 分 hunk 同步 | G01 + F02 + F05 | 取 `StateSet` 修正、`GeometryShaderGenerator`、`BatchDrawDefaults`、字体与选项页修正；SDL 相关条目排除 |
| 57 | `7cd97d12` | Fix HUD caching flicker and match non-cached GL state (#2201) | 2026-10-03 | 待同步 | G03 | HUD 缓存与非缓存 GL 状态对齐，本地缺 `CowStateStack`、`RetainedState`、`ScissorStateStack` |
| 58 | `61bb779d` | Fix crash when mods measure text off-thread with a custom font (#2204) | 2026-10-03 | 分 hunk 同步 | F06 | 取通用 hunks：`glsm/GLStateManager`、`backend/RenderBackend`、`glsm/dsa/DSAARB`、`DSAUnsupported` 的离上下文对象创建路径、`client/font/FontProviderCustom` 与 `FontProviderCustomGLTest`（落根项目 `src/test`）。本地缺 `FontProviderCustom` 与离上下文创建路径。排除：`sdl-gpu/SDLGPURenderBackend`、`sdl-gpu/glsm/GlsmSdlOffContextCreationTest`，以及 `glsm/` 的 `GLSM_OffContextCreation_GLTest`（需要 GL 上下文，落 `src/glIntegrationTest`） |
| 59 | `231a2ab2` | Add Darkmode Transformer target (#2205) | 2026-10-04 | 待同步 | F06 | 深色模式转换目标，同 `DarkModeFontTransform`；随 F06 的 darkmode 一组一并建立 |
| 60 | `0a4d4896` | Cache Iris Shaders (#2206) | 2026-10-03 | 分 hunk 同步 | S06 + S08 | 取 Iris 缓存开关与 `glsm/shader` 缓存接线；排除 `Lwjgl2GLRenderBackend` 与语言文案。已同步（批次二第 18 项）的 `ProgramBinaryCache` 部分：`ProgramBinaryCache` 落 `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/shader/`；`RenderSystem.supportsProgramBinary` 与字段赋值并入 `initRenderer()` 的静态初始化块，取值来自 `RENDER_BACKEND.supportsProgramBinary()`；`Lwjgl3GLRenderBackend` 覆写 `supportsProgramBinary`、`programParameteri`、`getProgramBinary`、`programBinary` 四个方法（`RenderBackend` 的默认空实现已存在，未重复添加）。`GLSMPrograms` 的四个程序二进制委托方法本地已具备，未改动。`ProgramBinaryCache.setEnabled` 由 `IrisConfig.saveCompiledShaders` 在 `load()` 与 `setSaveCompiledShaders` 驱动，`IrisConfig` 新增该字段与 properties 读写；选项页按钮属第 22 项。调用点已在 `fix/program-binary-cache-wiring` 补齐：`Iris.java` 的 `usePack` 三处、`ProgramCreator` 的 `cacheKey`/`load`/`create` 重载、`ProgramBuilder` 的缓存命中与 `initializeFallbackUniforms`、`GLSMPrograms.glLinkProgram` 的 retrievable 提示、`IrisCeleritasChunkProgramOverrides` 的地形程序读写。`RetainedPrograms`、`beginFullCompile`/`finishFullCompile`/`wasCompiledFor` 与 `Program.saveRetained` 的预热路径仍未接入 |
| 61 | `6dfb0312` | Answer legacy stack and client state queries from GLSM (#2211) | 2026-10-04 | 分 hunk 同步 | G03 | 取通用 hunks：`glsm/GLContextState`、`GLStateManager`、`LTWWorkaround`、`recording/commands/TexImage2DCmd`、`TexSubImage2DCmd`、`redirect/GLSMRedirector`、`states/PixelStoreState`、`states/PixelUnpackState`，以及 `PixelStoreStateTest`、`PixelUnpackStateTest`、`GLSM_LegacyStackQueries_GLTest`、`GLSMRedirectorTest`。本地 `states/` 只有 unpack 侧 `PixelUnpackState`，无 `PixelStoreState` 与 `LegacyQueries`。排除：`sdl-gpu/glsm/GlsmSdlLWJGLServiceRoutingTest` |
| 62 | `fcf8ad06` | Fix terrain disappearing at screen edges with compute culling (#2212) | 2026-10-04 | 待同步 | C01 | 计算剔除屏幕边缘地形缺失，本地缺 `GpuTerrainCuller`、`FrustumExtractor` |
| 63 | `23e36f2b` | Narrow overly broad transformer exclusions for PowerConverters (#2214) | 2026-10-04 | 待同步 | F11 | `EcosystemNarrowRules` 本地已存在（`glsm/src/main/java/com/gtnewhorizons/angelica/glsm/loading/EcosystemNarrowRules.java`），但只含 `EARLY_REDIRECTOR_TARGETS` 一项，没有 `NarrowRule` 列表结构；缺的是新增的 `NarrowRule("PowerConverters", "covers1624.powerconverters.", …)` 规则项、`AngelicaClientTweaker` 里 `narrowTransformerConfig("PowerConverters", …)` 的接线，以及 `AngelicaConfig.transformerCompat.narrowPowerConverters` 配置项 |
| 64 | `2a8c8756` | Add renderer selection to video settings - (#2215) | 2026-10-04 | 分 hunk 同步 | G05 | 取通用 hunks：新增 `glsm/backend/BackendOptions`、`BackendStartGuard`（含 `BackendStartGuardTest`），这两个新文件不依赖 SDL/MoltenVK，是 `8e180962` 崩溃分类的前置；同时取 `GLVersionChoice` 与 `MixinForgeHooksClient_CoreProfile` 的核心 profile 选择。`glsm/config/SystemProperties` 在该提交里是**修改既有文件**（`M`，非新增），只取其中与渲染后端选择无关的通用配置语义，SDL 专用属性（`angelica.sdlgpu.*` 与驱动选择相关项）逐行排除。排除：`moltenvk/` 全部、`SDLGPUGate`、`SDLGPURenderBackend`、`Device`、`MoltenVK`、`RenderBackendChoice`、`SdlGpuDriver`、`RendererOptionPages`，以及 `.github/`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`dependencies.gradle.kts` 的构建与版本号变更 |
| 65 | `10922edc` | Include missed scripts (#2216) | 2026-10-04 | 分 hunk 同步 | G05 | 取 `src/test/java/com/gtnewhorizons/angelica/config/GLVersionChoiceTest`（GL 版本选择校验，落在根项目 `src/test`）。排除 `.github/scripts/companion-version.sh` 与 `release-companion-jar.sh` 两个发布脚本 |
| 66 | `38af64f1` | Initial commit for shader-loading-optimizations (#2213) | 2026-10-04 | 分 hunk 同步 | S01 + S04 + S05 + S06 + S07 | 取 `glsm/shader` 的 `ProgramBinaryCache`、`ShaderDiskCache`、`AngelicaWorkers`、`ShaderWarmup` 与 Iris 侧接线；排除 SDL 阶段缓存、磁盘缓存测试与 `sdl-gpu/` 资源层。已同步的 glsm 半（批次二第 18 项）：`AngelicaWorkers` 落 `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/threading/`，线程名 `Angelica-Worker-N`，与 celeritas 的 `Chunk Render Task Executor` 池互不复用，池在 120 秒空闲后自行关闭；`AngelicaWorkers` 的线程数按 CPU 核数取半并限制在 2 到 12 之间；上游的 `GLSMConfig.workerThreadCount` 在本地没有 GUI 配置入口，按约束跳过该字段，未新增占位配置；`ShaderDiskCache.configure` 增加 idempotent 短路与 `configuredSalt`；`ProgramBinaryCache.save` 拆出 `saveBinary` 并补 `isFullCompileRunning`、`saveRetained`；`GlslTransformUtils.renameReservedWords` 增加整词预检短路与 `containsWholeWord`。未同步：`ShaderWarmup` 归第 20 项；`AngelicaJar`、Iris 侧 `Iris.java`/`ProgramBuilder`/`RetainedPrograms` 与 `ShaderTransformer` 归第 20、22 项；`sdl-gpu/` 的 `ResourceManager`、`ShaderManager`、`StageDiskCacheTest` 不适用 |
| 67 | `12857006` | Harden SDL-GPU buffer mapping lifetime (#2217) | 2026-10-04 | 分 hunk 同步 | G05 | 取通用 hunks：`glsm/backend/RenderBackend` 新增的缓冲区映射生命周期接口与 `GLStateManager` 的对应委托。排除 `sdl-gpu/` 的 17 个文件（`PersistentMapping`、`MappedRange`、`ByteRegionCopy`、`PersistentBufferSync`、`TransferThread` 与对应测试） |
| 68 | `b49d37d8` | Ignore additional tags when deriving the mod version (#2219) | 2026-10-04 | 分 hunk 同步 | S04 | 取 `StandardMacros` 的版本标签解析与 `StandardMacrosVersionTest`：上游把版本推导改为忽略额外标签，本地 `formatActiniumVersion`（`shader/src/main/java/net/coderbot/iris/gl/shader/StandardMacros.java:55`）按首个数字三元组解析，需核对该解析在带额外标签（如 `-dirty`、`-SNAPSHOT`）时与上游一致。排除 `build.gradle.kts`、`gradle/mod-version.gradle.kts`、`gradle.properties`、`umbra/` 的构建文件 |
| 69 | `3389b952` | Fix SDL compile failure when an image atomic's result is used (#2218) | 2026-10-04 | 不适用 | — | SDL/GLES 编译修补，依赖本地不存在的 `RwImageStoreExtractor` |
| 70 | `5ed1c925` | SDL Vertex Replay + Dragon API (#2224) | 2026-10-05 | 分 hunk 同步 | F02 + G07 | 取 `MixinRenderDragon`、`RendererLivingEntityHelper` 与 Dragon API 部分；顶点重放依赖本地缺失的 `VertexWriteReplays`，SDL 计算派发部分排除 |
| 71 | `d056bf6f` | Run image atomics natively on Metal where the runtime allows (#2226) | 2026-10-05 | 分 hunk 同步 | S05 | 取 `ShaderTransformer` 与 iris 变换侧通用部分；Metal/MSL 原生 image atomic、`atomic_probe.csh` 与 flyby 场景排除，通用部分依赖本地缺失的 `RwImageStoreExtractor` |
| 72 | `21a054a4` | fix world client leak in CloudRenderer (#2227) | 2026-10-06 | 待同步 | F01 | 云渲染器 world 引用泄漏，本地无 `CloudRenderer`、`CeleritasWorldRenderer` |
| 73 | `acedba4e` | Modern Font Sheets (#2225) | 2026-10-07 | 待同步 | F06 | 现代字体表，本地缺 `FontProviderBitmap` |
| 74 | `5457df53` | Marginal improvements (#2222) | 2026-10-07 | 待同步 | F05 | 字体抗锯齿外扩；本地 `pushTexRect` 无 `MAX_AA_REACH` 边距处理 |
| 75 | `ce2462ca` | Fix server thread leak in ThreadedBlockData (#2230) | 2026-10-07 | 不适用 | — | `ThreadedBlockData` 不适用本地 worker 架构，本地无该类与对应 mixin |
| 76 | `0d959c2b` | Add Darkmode Transformer target (#2221) | 2026-10-07 | 待同步 | F06 | 深色模式转换目标，同 `DarkModeFontTransform`；随 F06 的 darkmode 一组一并建立 |
| 77 | `159f7ff8` | Fix NaNs on EP (#2231) | 2026-10-07 | 分 hunk 同步 | S05 | 取 `GlslVulkanPreprocess`、`SpirvShaderTranslator` 与 Iris `ShaderTransformer` 通用修正；SDL 着色器链与派生提升排除。已同步（批次二第 19 项）：新增 `ShaderIndex`（160 行，与上游逐字一致）与 `GlslTransformUtils.quietLexer`/`restoreReservedWordsInTree`；`GlslVulkanPreprocess.collectEdits` 改为 `ShaderIndex` 驱动，删 `versionDirectiveEnd`/`firstVersionDirective`/`collectDeclaredNames`；`SpirvShaderTranslator` 删 `glslToGlslEsRawForTest` 与 `rawOutput` 分支、uniform 判定改用 `ShaderIndex.hasStorageQualifier`；Iris 两处 `mutateTree` 改为内联 `restoreReservedWordsInTree` 后格式化，删无调用方的 `addIfNotExistsType`。排除：`parseBothQuiet`/`parsePreQuiet`（本地 `CompatShaderTransformer` 走 `org.taumc.glsl.ShaderParser`，该库内部已是单 lexer 双 parser，且 `BufferedTokenStream` 会漏掉前导注释与空行之后的指令，本地改用 `ShaderIndex.directives().versionEnd()`）、`RwImageStoreExtractor`（`mayWriteImages` 快速路径依赖 SDL compute 链路） |
| 78 | `78abc057` | Update Readme | 2026-10-07 | 不适用 | — | README 更新与 HUD Caching 配置文案从 Experimental 改为 Semi-stable；本地没有 HUD Caching 功能（仅有 `StellarCoreHudCachingCompatTransformer` 兼容层），文案与状态标注对当前本地不构成代码同步项。若 G03 后续引入对应功能，再复核文案 |
| 79 | `cae4fea2` | Fix Little Tiles rendering issues (#2238) | 2026-10-09 | 待同步 | F11 | Little Tiles 渲染问题，本地缺 `VertexArrayQuadView` |
| 80 | `bfb66d84` | Fix interop with Serene Seasons and other similar mods (#2240) | 2026-10-08 | 待同步 | F11 | `BiomeGenBase` 互操作 mixin，本地无该 mixin |
| 81 | `1946c2ad` | Fix dark AE2 cables when shaders enabled (#2223) | 2026-10-09 | 待同步 | F11 | AE2 线缆变暗，本地缺 `IsbrhDispatch` |
| 82 | `990dadc0` | Reject non 3.3 core with a more clear message (#2243) | 2026-10-10 | 待同步 | P01 | 非 3.3 core 的拒绝信息；本地由 `CoreProfileDisplayCreator` 承担，需评估信息合并方式 |
| 83 | `2f1f380e` | Bug Cleanup after 2.9RC2 (#2239) | 2026-10-10 | 分 hunk 同步 | G07 + F11 | 宽线部分已同步（批次一第 8 项，`feat/glsm-line-quads`）：新增 `LineQuads`、`hooks/LineDrawHandler`，`GLSMDraws` 的 `widensLineDraw`、`drawLinesAsQuads`、`lineElementsFromBuffer`、`warnIfInstancedWideLines`、`readsBufferObject` 与各 draw 入口的宽线分支，`VertexKey` 的 `BIT_LINE_QUADS` 与 `lineQuads()`，`VertexShaderGenerator` 的顶点阶段加宽与跨线段雾/裁剪插值，`ImmediateModeRecorder.processDrawElementsFromAttribs`，`DisplayListVBO`/`DisplayListVBOBuilder` 的宽线录制与回放，`TessellatorStreamingDrawer` 的宽线路径与 `lineQuad*` VAO，`GLContextState.lineQuadsActive`，`hooks/GLSMHooks.lineDrawHandler`；删除 `GeometryShaderGenerator.java`，`ShaderCache`、`ShaderManager`、`Program` 的几何着色器分支同步移除。`SelectionBoxRenderer` 与 `selection_box.geom.glsl` 属 F11，本地无该类，用 `BoundingBoxOutline` 自承，跳过。排除：`sdl-gpu/` 的 `Device`、`InstancedConstantBuffers` 与实例化常量缓冲 |
| 84 | `d29a8278` | Make API functions for enderstorage to use (#2244) | 2026-10-10 | 待同步 | F11 | 末影存储使用的 API 函数，本地缺 `api/shaders/ShaderPacks` |
| 85 | `2ed838ac` | Fix shaders that redefine a GLSL built-in failing on SDL-GPU and GLES (#2245) | 2026-10-10 | 分 hunk 同步 | S05 | 取 `GlslVulkanPreprocess` 的内置函数重命名与 `SpirvShaderTranslator` 修正；SDL/GLES 交叉编译测试排除。已同步（批次二第 19 项）：`GlslVulkanPreprocess.renameUserFunctions` 按「原型与定义次数 + 调用点次数等于 AST 标识符出现次数」判定，跳过 `main` 与出现在 directive 里的名字，避免误伤同名变量与宏参数；`SpirvCompiler.compile` 首次编译失败后用它重试，重试成功打 INFO 并采用结果，仍失败则写 failure dump 并返回原始错误；`UserFunctionRenameTest` 落根项目 `src/test/java/com/gtnewhorizons/angelica/glsm/shader/`，期望前缀改为 `actinium_renamed_` |
| 86 | `3978be16` | Answer legacy GL queries from tracked state (#2247) | 2026-10-10 | 分 hunk 同步 | G03 | 取通用 hunks：`LegacyQueries`、`StateSet`、`GLContextState`、`FeedbackManager`、`Feature`、`VAOManager`、`states/PointState` 与 `states/TextureUnitArray`、`GLSMRedirector` 的查询重定向，以及根项目 `src/test` 的查询往返与 capture gate 测试。本地 `glGetInteger`（`GLStateManager.java:1032`）已覆盖部分分支但没有 `LegacyQueries` 与 `StateSet` 支撑。排除 `sdl-gpu/src/test` 的 115 个文件与 `lwjgl3-backend`、`tracy-client` 的测试工件 |
| 87 | `69428c12` | Fix MixinRendererLivingEntity_GlintClock expected count (#2248) | 2026-10-11 | 待同步 | F02 | Glint 时钟 mixin 期望计数，本地无该 mixin |
| 88 | `23aa0436` | Fix crash replaying display lists compiled during the splash screen (#2250) | 2026-10-10 | 待同步 | G04 | splash 期编译的 display list 回放崩溃：`IndexedDrawBatch` 在编译上下文建 VAO、`BIND_VAO` 无条件重放。已移植（`feat/glsm-display-list-recording`）：改为首次 `bind()` 时在当前上下文建数组，`CompiledDisplayList` 记录编译上下文并据此过滤 `BIND_VAO`，新增 `DisplayListContextBindIntegrationTest` |
| 89 | `3d3d0f9e` | Angelica side fix to RC Lantern rendering.  Works with or without a patched RC (#2251) | 2026-10-11 | 待同步 | F11 | RC 灯笼渲染修正，本地无该 mixin |
| 90 | `d3dee841` | Skip zero-size buffer uploads and readbacks on SDL-GPU (#2249) | 2026-10-11 | 不适用 | — | 全部 `sdl-gpu/` 缓冲上传与回读 |
| 91 | `46f81570` | New workflow (#2252) | 2026-10-11 | 不适用 | — | CI 工作流替换 |
| 92 | `85d0641a` | Speed up tests and run SDL-GPU device tests on CI (#2253) | 2026-10-12 | 分 hunk 同步 | G01 | 取架构约束测试思路，落在根项目 `src/test/java/com/dhj/actinium/architecture/`（现有 `DependencyDirectionTest.java` 即为该目录）；SDL 设备测试与 kts 构建脚本排除 |
| 93 | `0cae2be1` | Fix Chisel energised voidstone missing its border corners (#2254) | 2026-10-12 | 待同步 | F11 | 凿子充能虚空石边角，本地缺 `QuadMaterialSelector` |
| 94 | `01ff612d` | Make animation interpolation respect alpha (#2258) | 2026-10-14 | 待同步 | S02 | 交错纹理动画 alpha；本地无 `jss/notfine` 包，需随 S02 一并建立 |
| 95 | `bd9a3c3c` | initial commit for witchery-cauldron-not-drawing-base (#2260) | 2026-10-14 | 待同步 | F02 | 命名牌绘制与炼药锅基座渲染，依赖 `ModelPartBatcher`、`RetainedTesrGroups` 与 `TessellatorStreamingDrawer` 签名调整 |
| 96 | `49db8e11` | Allow Iris to use "more" Samplers on SDL-GPU (#2256) | 2026-10-14 | 分 hunk 同步 | G05 | 取 `glsm/shader` 的采样器别名去重（`SamplerAliasDeduplicator`）；SDL 采样器搬运、`Device`、`SamplerBinder` 排除 |
| 97 | `f81ee4b0` | fixed some osx metal compability with Distant horizon 1.7.10 backport (#2261) | 2026-10-15 | 不适用 | — | macOS Metal 能力垫片，全部 `sdl-gpu/` |
| 98 | `8e180962` | Stop resetting SDL GPU to OpenGL on unrelated crashes (#2266) | 2026-10-16 | 分 hunk 同步 | G05 | 取通用 hunks：`glsm/backend/BackendStartGuard`、`BackendStartGuardTest`、`BackendOptions` 的崩溃分类与选项读取，`glsm/GLContextState`、`GLStateManager` 的点尺寸查询缓存改造；`loading/AngelicaClientTweaker` 与 `proxy/ClientProxy` 的启动链加固。**前置来源：**`BackendOptions`、`BackendStartGuard`、`BackendStartGuardTest` 由 `2a8c8756` 新增，本提交对它们是修改（`M`）；本地当前无这三个文件，需先落 `2a8c8756` 的通用 hunks。该提交**不包含** `SystemProperties`（`git show --name-only` 无匹配）；上游的 `glsm/config/SystemProperties` 在 `2a8c8756` 里为 `M`（修改既有文件），本地尚未建立该类（`glsm/src/main/java` 下无 `config` 包）。排除：`sdl-gpu/SDLGPUGate`、SDL 崩溃回退路径与 `MixinInitGLStateManager` 的 SDL 分支 |
| 99 | `8537835d` | Fix Player Capture for EP in SDL-GPU (#2262) | 2026-10-17 | 待同步 | F04 + G07 | 玩家反射捕获，依赖 `VertexWriteReplays` 与 `RwImageStoreExtractor` |
| 100 | `cfc6e12e` | Minor Compute Cull Optimizations (#2263) | 2026-10-17 | 分 hunk 同步 | C01 | 取通用 hunks：`glsm/GLContextState` 新增的 `uniformBufferBindings`、`storageBufferBindings` 数组与 `GLStateManager` 的 `INDEXED_BUFFER_SLOTS`、`trackIndexedBufferBinding`、`glBindBufferBase` 跟踪、`glDeleteBuffers` 的绑定清理（本地 `glBindBufferBase` 为直接转发，无跟踪），以及 `GLSM_DeletedBufferBinding_GLTest`。排除：`GpuDrivenChunkCuller`、`GpuTerrainCuller` 的 GPU 剔除器改动（本地无这两个类，随 C01 的剔除设施一并补齐） |
| 101 | `ab3bf176` | Set entity, block entity and item IDs per vertex (#2265) | 2026-10-17 | 分 hunk 同步 | G07 | 取通用 hunks：`glsm/streaming/TessellatorEntityIds`、`StreamingVaos`、`TessellatorStreamingDrawer`、`glsm/ffp/InstancedAttribs`、`ShaderManager`、`glsm/hooks/ImmediateExtendedAttribHandler`、`glsm/GLContextState`、`GLStateManager`、`iris/ImmediateExtendedAttribs`、`iris/IrisGLSMBridge`、`iris/gl/shader/ProgramCreator`、`iris/uniforms/CapturedRenderingState`、`MixinTessellator_CoreProfile`、`rendering/tesr/ModelPartBatcher`，以及 `TessellatorEntityIdsGLTest`、`InstancedFFPDrawGLTest`、`IrisInstancingGLTest`、`AttributeTransformerInstancedTest`（落根项目 `src/test`）。排除：`sdl-gpu/glsm/GlsmSdlAttribBindingTest`、`GlsmSdlFfpTest`、`GlsmSdlTessellatorEntityIdsTest` 与 `glsm/testFixtures` 的 `EntityIdsOnlyHandler`、`TessellatorEntityIdsFixture`、`FfpFixture` |
| 102 | `482dece0` | Expand SDL-GPU's GLSM redirector to GLSM and remove unredirected GL workarounds and move action back to master (#2268) | 2026-10-17 | 分 hunk 同步 | G05 | 取 `glsm` 侧重定向代理与测试夹具（`GlsmRedirectAgent`、`GLSMCoreExtension`）；`sdl-gpu/src/glsmSdlAgent` 与构建脚本排除 |
| 103 | `69019b9a` | Implement metadata checking into the NBT path of block.properties parsing (#2267) | 2026-10-18 | 待同步 | P01 | block.properties 的 NBT 路径加入元数据判定；本地 `NbtConditionalIdMap.addCondition` 无 `metas` 参数，`NbtCondition` 无元数据集合 |
| 104 | `fa8842a6` | Fix birch and spruce leaves showing the oak biome color with fancy biome blending (#2273) | 2026-10-18 | 待同步 | F11 | 桦木与云杉树叶混色，依赖 `BiomeVertexBlender` |
| 105 | `2d9d1a45` | initial commit for entity-atlases (#2272) | 2026-10-18 | 分 hunk 同步 | F03 + S03 | 取通用 hunks：`rendering/tesr/EntityTextureAtlas`（新增）、`InstancedTemplateRenderer`、`ModelBoxCapture`、`ModelPartBatcher`、`RetainedTesrGroups`、`TesrLifecycle`；`rendering/items/BlockRenderListManager`、`DroppedItemInstancer`；`glsm/ffp/CubeParams`、`glsm/texture/TextureInfo`、`TextureInfoCache`、`RenderSystem`、`GLStateManager`；`MixinItemRenderer_Instanced`、`MixinRenderItem_Instanced`、`MixinModelBox_CubeParams`、`MixinRender_EntityFireBatch`（新增，实体火焰批量）、`itemrenderer/MixinRenderBlocks`；`AngelicaConfig`/`Mixins`/`ClientProxy` 接线与 `EntityTextureAtlasGLTest`、`ModelBoxCaptureTest`、`RetainedTesrGroups(Test\|GLTest)`。`DeferredWorldRenderingPipeline` 归 F03：该 hunk 在 pipeline 里接入实体图集与 instances 分支。`PBRTextureManager` 归 S03：该 hunk 调整 PBR 资源索引与加载顺序，属着色器资源加载链。排除：`sdl-gpu/SDLGPURenderBackend`、`sdl-gpu/resource/FboState`、`sdl-gpu/glsm/GlsmSdlCopyBlitTest`、`glsm/testFixtures/CubeParityFixture`（本地无 testFixtures 源集） |
| 106 | `32f8eef1` | Fixes a startup CME when BLS (#2274) | 2026-10-18 | 待同步 | F05 | 启动期离线程字体测量的 CME；本地 `FontStrategist.getFontProvider` 目前没有现代字形查找分支，因此该路径暂不存在，但 F05 引入字体表后会立刻成立。守卫条件 `GLStateManager.isMainThread()` 本地已具备（`glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:162`），随 F05 一并加在字形查找入口 |
| 107 | `b446968e` | Instance animated TESRs and let mods register shaders for batched TESRs (#2275) | 2026-10-18 | 分 hunk 同步 | F03 | 取通用 hunks，以目录范围列出避免漏项：`src/main/java/com/gtnewhorizons/angelica/api/tesr/`（全部：`TesrMaterial`、`TesrShader`、`TesrShaderUniforms`、`TesrShaders`、`package-info`）；`src/main/java/com/gtnewhorizons/angelica/rendering/tesr/`（全部：`AngelicaTesrMeshCache`、`DrawState`、`InstancedTemplateRenderer`、`ModelPartBatcher`、`RetainedTesrGroups`、`TesrAnchorMath`、`TesrBatchRenderer`、`TesrProviderDispatch`、`TesrShaderPrelude`、`TesrShaderPrograms`）；`src/mixin/java/.../early/angelica/tesr/MixinTileEntityRendererDispatcher`；`glsm/CompatUniformManager`、`glsm/ffp/`（`FfpGlsl`、`FragmentKey`、`FragmentShaderGenerator`、`Uniforms`、`VertexShaderGenerator`）、`glsm/states/FogState`；`compat/mojang/RenderLayer`、`compat/thaumcraft/PortalDrawer`、`debug/flyby/`（`FlybyCommandSender`、`FlybyRunner`、`FlybyScene`）；以及 `src/test/java` 下 `api/tesr/`、`rendering/tesr/`、`debug/flyby/` 的全部对应测试（落根项目 `src/test`）。排除：`sdl-gpu/shader/ClipZRemap`、`sdl-gpu/shader/ShaderManager`、`PrewarmTreeHandoffEquivalenceTest`、`ShaderSourceTransformCacheTest`、`ShaderTransformChain`，`profiling/TracyFramePlots`，`scripts/flyby-scenes/opencomputers-*.txt`、`scripts/flyby-smoke.sh`与 `dependencies.gradle.kts` |
| 108 | `e98c927a` | Reduce test duplication (#2276) | 2026-10-19 | 分 hunk 同步 | G03 | 取通用 hunks：`glsm/states/ImageUnitBinding` 新增的 `reset()` 与 `setBinding` 的空纹理守卫、`GLStateManager` 的图像单元释放与 `glBindImageTexture` 调用修正。排除：全部测试去重文件（`CompatShaderTransformerTest`、`DisplayListCompilationParityTest`、`FeedbackManagerTest`、`GLESFormatRemapTest`、`GLSM_*_GLTest` 等 45 个 `glsm/src/test` 文件、35 个 `src/test` 文件与 `sdl-gpu/src/test`、`sdl-gpu/src/main`、`glsm/src/testFixtures`），这部分面向上游测试集，本地测试结构不同，不按该提交重组 |
| 109 | `ed260a34` | Fix tests & SDL-GPU framebuffers losing attachments on resize or texture delete (#2282) | 2026-10-21 | 不适用 | — | SDL 帧缓冲附件与对应测试 |
| 110 | `a037a96f` | Update to Celeritas 2.5.13-GTNH (#2283) | 2026-10-21 | 不适用 | — | 依赖版本号提升 |
| 111 | `67ca46a2` | why min 16 (#2278) | 2026-10-22 | 不适用 | — | 本地无 `mobSpawnerRenderDistance` 选项与 `SodiumGameOptionPages`，选项下限无同步基础 |
| 112 | `98703a11` | Read only the mcmeta in the interpolated icon check (#2284) | 2026-10-22 | 待同步 | S02 | 交错图标检测只读 mcmeta；本地无 `jss/notfine` 包，也没有 `notfine_at.cfg`，该优化随 S02 的 interpolated texture 实现一并移植（实现时需同时补 `FallbackResourceManager` 的 access transformer 条目） |
| 113 | `69124e3f` | Stop capping mailbox present mode to the refresh rate (#2279) | 2026-10-21 | 已同步 | F09 | `resolveTearFreeMode` 默认回 `VSyncMode.ON`；MAILBOX 在 GLFW 路径由 `supportsVSyncMode` 标记为不支持，选项只暴露 ON/OFF |
| 114 | `73d61d38` | initial commit for gl-fences (#2285) | 2026-10-21 | 已同步 | G05 | glsm 侧由 `feat/glsm-streaming-ring` 落地：新建 `FenceQueue` 与 `GlStreamingRing`，`PersistentStreamingBuffer` 改为薄包装，删除 `GlFence` 与 `StreamingBuffer` 接口，fence 时机、容量阈值、10ms 等待切片与 5s 总超时、`forcedReclaims` 计数与上游逐项一致。`Uniforms.invalidateBinding` 随 FFPUniformBlock+UBO ring 一并排除（本地逐 uniform 上传）；TESR/particle 侧的 `InstanceRing`、`ModelPartBatcher`、`TesrBatchRenderer`、`ParticleInstancer` 随 F03；Tracy 帧图与脚本排除 |
| 115 | `e84c8c8d` | Stop stalls on some GPUs when closing the questbook (#2286) | 2026-10-22 | 分 hunk 同步 | G05 | 取 GLSM 延迟纹理删除、`FrameHooks`、`TextureTargets` 与 `DSAUnsupported`；SDL 采样器与 `Lwjgl2GLRenderBackend` 排除 |
| 116 | `fc8d3e8b` | Keep armor and held-item glint materials separate (#2289) | 2026-10-22 | 待同步 | F02 | 盔甲与手持附魔材质分离，本地缺 `EntityMaterials`、`ItemGlint` |
| 117 | `8af9340b` | Initial commit for shadow-pass-camera (#2291) | 2026-10-22 | 待同步 | P01 | 阴影 pass 相机同步；本地 `ShadowRenderer` 只保存与恢复 `renderManager.viewerPos*`，未设置 `cacheActiveRenderInfo` 与 `TileEntityRendererDispatcher.staticPlayer*` |
| 118 | `102c6cad` | Initial commit for glsm-exit-state-capture (#2292) | 2026-10-24 | 已同步 | G07 | 退出状态捕获已落地：`ExitState`、`ExitStateCapture`、`hooks/BatchStateGuard` 三个新类，`GLStateManager` 的入口按门面拆到 8 个 `GLSM*` 类，`RenderSystem` 与 Iris 桥接各补一处。`hooks/TextureUtilHooks`、`MixinTextureUtil`、`VanillaLayerRig` 因本地缺类跳过；门面里的 `rejectExitCapture` 守卫留到 54 个缺失方法那一批 |
| 119 | `713509a8` | Entity Shadow Replay (#2294) | 2026-10-24 | 待同步 | F04 | 实体阴影重放，本地缺 `EntityShadowReplay`、`RetainedTesrGroups` |
| 120 | `ea50e0a0` | Bunch of rendering fixes (#2295) | 2026-10-25 | 待同步 | F01 | 云着色属性与玻璃板渲染等一组渲染修正 |
| 121 | `6c5e146f` | Fix GL core tests that depended on test order and JIT timing (#2298) | 2026-10-24 | 分 hunk 同步 | G01 | 取通用 hunks：`glsm/` 的 `GLSM_SharedDrawableContext_GLTest`、`ffp/FFPVertexLightingGLTest`（落 `src/glIntegrationTest`）、`testFixtures/` 的 `GLSMCoreExtension`、`GlsmRedirectAgent`、`ffp/FfpFixture`、`testutil/MutableStatics`、`Reflect`（按 Actinium 现有结构转为根项目 `src/test` 的测试辅助类）与 `RetainedTesrGroupsGLTest`、`TesrLayerStateTest`、`TesrShaderProgramsGLTest`、`ProgramBindingRegressionTest`。排除：`build.gradle.kts`、`glsm/build.gradle.kts` 的构建配置与 `sdl-gpu/glsm/GlsmSdlAttachmentClearTest` |
| 122 | `7cc9cea3` | Cache the tile entity lightmap lookup for one tick (#2296) | 2026-10-25 | 待同步 | F02 | 方块实体光照缓存，本地缺 `TesrLightCache` |
| 123 | `34ed1d10` | Update Japanese Localizations (#2297) | 2026-10-25 | 不适用 | — | 语言包；本地文案键集与上游不同，需在各功能 PR 内随选项重写 |
| 124 | `17a2ed7f` | Initial commit for gui-item-supersampling (#2299) | 2026-10-25 | 待同步 | F08 | GUI 物品超采样，本地缺 `GuiBlockSupersampling` |
| 125 | `1772e9ea` | Mipmap stuff again (#2300) | 2026-10-25 | 分 hunk 同步 | S02 | 取 mipmap 策略（`MipmapStrategies`、`ModernMipmapBlender`）与 `SamplerUnitArray`；SDL 顶点重放设置与设备层排除 |
| 126 | `a019b7ee` | Update PR template to be in line with the GTNH org | 2026-10-25 | 不适用 | — | PR 模板 |
| 127 | `9bbb0537` | Backport modern F3 debug screen + debug overlays (#2301) | 2026-10-25 | 分 hunk 同步 | F07 | 取通用 hunks：`api/debug/` 的调试屏 API（`DebugContext`、`DebugEntryCategory`、`DebugEntryHandle`、`DebugEntrySpec`、`DebugFact`、`DebugFactLines`、`DebugGroupHandle`、`DebugGroupSpec`、`DebugScreenApi` 等）、`glsm/` 侧的调试注册点与 `src/main/java` 的调试条目、`src/mixin/java` 的注入点、`src/main/resources` 的文案，以及 `src/test` 的对应测试。本地无 `api/debug` 包。排除：`sdl-gpu/SDLGPURenderBackend`、`sdl-gpu/device/Device`、`sdl-gpu/resource/ResourceManager` 与 `scripts/flyby-*` 采集脚本、`dependencies.gradle.kts` |
| 128 | `91e67f8d` | Split up GLStateManager and add public GLState API (#2305) | 2026-10-26 | 分 hunk 同步 | G06 | 取通用 hunks：`glsm/` 下的公开 API 拆分（`GLSMBlend`、`GLSMDraws`、`GLSMEnable`、`GLSMFog`、`GLSMFramebuffer`、`GLSMLighting`、`GLSMMatrices`、`GLSMPrograms`、`GLSMQueries`、`GLSMRasterizer`、`GLSMTexEnvGen`、`GLSMTextures`、`GLSMVertexInput` 等）、`GLContextState`、`Feature`、`FeedbackManager`、`CompatUniformManager`、`DisplayListManager`、`ExitStateCapture`，以及 `src/main/java` 与 `src/mixin/java` 的调用点改造。本地 `glsm/` 只有单一 `GLStateManager`，没有拆分后的公开面。排除 `experimental-surround/`、`sdl-gpu/src/main`、`sdl-gpu/src/test` 与 `glsm/src/testFixtures` |
| 129 | `ed6dd12d` | Move deferred chunk meshing back off the main thread (#2306) | 2026-10-26 | 待同步 | C09 | 延迟区块网格构建移回工作线程，本地缺 `DeferredFinishTask` |
| 130 | `08aa3771` | Only animate PBR textures that are visible (#2310) | 2026-10-28 | 待同步 | S03 | 只动画可见 PBR 纹理；本地 `PBRAtlasTexture.cycleAnimationFrames` 仍遍历全部动画精灵 |
| 131 | `bbbdc12b` | Cut terrain rebuilds and draw-buffer churn (#2312) | 2026-10-29 | 分 hunk 同步 | C04 | 取 `GLSMAttribStack`、`GLSMBuffers`、`VAOManager` 与地形重建削减；`sdlgpu/resource/ResourceManager` 与版本号文件排除 |
| 132 | `8e099e95` | Free chunk-meshing memory when leaving a world (#2313) | 2026-10-29 | 分 hunk 同步 | C09 | 取通用 hunks：`rendering/celeritas/` 的 `AngelicaRenderSectionManager`、`DeferredMeshScheduler`、`ObjectPool` 与 `DeferredMeshSchedulerTest`、`ObjectPoolTest`（落根项目 `src/test`）、`rendering/AngelicaRenderQueue`、`debug/profiling/AsprofRecorder`、`glsm/config/SystemProperties` 的配置项。本地缺 `ObjectPool` 与 `DeferredMeshScheduler`。排除：`tracy-client/TracyClientBackend`、`scripts/flyby-smoke.sh`、`scripts/flyby-smoke-check.py`、`debug/flyby/FlybyHeapCheck`、`FlybyRunner` |

## 验证规则

每个 PR 从本台账取行后，按以下顺序执行，判据未达成不进入下一步。

1. **范围核对**：从上游取出该提交的完整 `name-only` 清单，按本台账的排除清单逐文件确认。混合提交必须给出排除清单，不接受“整提交不适用”。
2. **本地实现位置确认**：先在 Actinium 全仓按类名定位目标文件（`src/main/java`、`glsm`、`celeritas-common`、`shader`、`GTNHLib`），确认包的映射关系。上游 `org.embeddedt.embeddium` 在本仓为 `dhj.embeddedt.embeddium`，`org.taumc.celeritas` 的 forge122 实现在本仓为 `com.dhj.actinium.render.terrain` 与 `com.dhj.actinium.world`。
3. **三方合并**：`RenderSectionManager`、`SimpleWorldRenderer`、`GLStateManager`、`ChunkBuilderMeshingTask`、`VintageBlockRenderer` 这类被多轮同步改过的文件用 `git merge-file`（本地=A，上游父=O，上游终态=B），冲突按语义逐个解决并在 PR 描述里记录。
4. **编译与测试**：`./gradlew check --no-daemon` 通过。上游的单元测试按 Actinium 现有结构落在根项目 `src/test`，不需要为 `glsm` 或 `celeritas-common` 新建 test source set；需要 GL 上下文的用例放根项目 `src/glIntegrationTest`，由 `glIntegrationTest` 任务显式执行。
5. **实机确认**：涉及渲染观感的条目不替用户执行 `runClient`，把验证场景（光影包、渲染距离、DH 设置、维度、模组组合）写进 PR 描述交用户实测。
6. **文档回写**：确认解决后把根因、触发机制与验证记录写进 `docs/compat/<模组>.md` 或 `docs/compatibility-matrix.md`。

## 目标 PR 索引

| PR | 覆盖的上游提交 |
|---|---|
| P01 | `b7628404`、`cbd16b1a`、`fe67a22d`、`69019b9a`、`990dadc0`、`8af9340b` |
| G01 | `9dd60f6a`、`53776684`、`fe82cd32`（StateSet）、`85d0641a`、`6c5e146f` |
| G02 | `a8c29fa3` |
| G03 | `12fa08ab`、`7cd97d12`、`44c930f4`、`6dfb0312`、`3978be16`、`85892c0f`、`b6a4497f`、`e98c927a` |
| G04 | `23aa0436` |
| G05 | `e84c8c8d`、`73d61d38`、`1babfd96`、`482dece0`、`49db8e11`（非 SDL-only）、`7bb5bfd4`、`8e180962`、`2a8c8756`、`1587a12c`、`12857006`、`10922edc`；SDL 侧部分见「SDL-GPU 后端引入顺序」 |
| G06 | `91e67f8d` |
| G07 | `102c6cad`、`2f1f380e`（recording/LineQuads + FFP/AttributeTransformer）、`ab3bf176`（streaming 与 `TessellatorEntityIds` + Iris/FFP）、`5ed1c925`、`8537835d` |
| C01 | `48eb54f5`、`625b6f40`、`f33e50ce`、`c64f4225`、`98c1af33`、`fcf8ad06`、`cfc6e12e`、`c9d46e70`（`MixinShaderLoader`） |
| C02 | `0d003b82`、`88e905ca`、`588a269e`、`170bb7d1`、`1ce0ce4e`、`d7a146f0`、`57ca70de`、`ba098143` |
| C03 | `2334caab0`（范围外前置）、`86d4cfe7` |
| C04 | `69e91690`、`6bf6a433`、`bbbdc12b`（通用部分）、`c9d46e70`（`TerrainDrawStats`、`MixinSharedQuadIndexBuffer`、`MixinRenderRegionManager`、`CubeStatusTracker`） |
| C05 | `7818b331`、`c2823be4`、`408881f2`、`d3946af4`（只取并发语义） |
| C06 | `14cb5f7f`、`77aec052`、`c9d46e70`（shadow 适配） |
| C07 | `7e5e9d7e`、`8883da78`、`c9d46e70`（fog 复位） |
| C08 | `26ab2175` |
| C09 | `762c5661`、`d876a203`、`6c888a63`（通用分配优化）、`ed6dd12d`、`8e099e95` |
| S01 | `37afddab`、`38af64f1` |
| S02 | `c10d30b2`、`01ff612d`、`98703a11`、`1772e9ea`、`ef68af58`、`cd0a9608`、`c9d46e70`（着色器适配） |
| S03 | `38b1d9a0`、`08aa3771`、`1587a12c`（`DebugCounters`）、`2d9d1a45`（`PBRTextureManager`） |
| S04 | `37afddab`、`38af64f1`、`b49d37d8` |
| S05 | `37afddab`、`38af64f1`、`bdcb4d18`、`d056bf6f`、`159f7ff8`、`2ed838ac` |
| S06 | `0a4d4896`、`38af64f1`、`af599f29`（图集与纹理动画部分） |
| S07 | `38af64f1` |
| S08 | `0a4d4896` |
| F01 | `939e3146`、`c578970a`、`7f3fb35e`、`888a4de0`、`21a054a4`、`f2c07dea`、`ea50e0a0` |
| F02 | `9814a96b`、`1f274a64`、`2f4ac2a9`、`7c28a69b`、`69428c12`、`fc8d3e8b`、`9f19d94c`、`db7d3284`、`bc08e4ba`、`7cc9cea3`、`fe82cd32`、`af599f29`（实例化与 batching 部分）、`bd9a3c3c`、`5ed1c925`（Dragon API 部分）、`c9d46e70`（发光方块实体阴影指令） |
| F03 | `2d9d1a45`、`b446968e` |
| F04 | `713509a8`、`80e5b544`、`8537835d`（玩家反射捕获部分） |
| F05 | `9081f4c3`、`97057224`、`fa71eda3`、`75a02781`、`5457df53`、`32f8eef1`、`fe82cd32`、`d16c1e7c` |
| F06 | `61bb779d`、`acedba4e`、`9cb35c67`、`66d2a548`、`8387b4e9`、`231a2ab2`、`0d959c2b` |
| F07 | `9bbb0537`、`c9d46e70`（`DebugKeyHandler` 与 `verboseF3`） |
| F08 | `17a2ed7f` |
| F09 | `338d9419`、`69124e3f`、`74ea5c47`（`FpsReducer`） |
| F10 | `977db3d6`、`74ea5c47`（`Zoom`） |
| F11 | `233c74e0`、`a8e95f70`、`fa8842a6`、`0cae2be1`、`1946c2ad`、`bfb66d84`、`cae4fea2`、`3d3d0f9e`、`23e36f2b`、`d29a8278`、`2f1f380e`（通用兼容） |
| F12 | `com/prupe`、`jss/notfine` 相关提交（`38b1d9a0`、`85892c0f`、`cd0a9608`） |

## G05 落地顺序

按「无前置、有前置、随 SDL 引入」三层排定，每层单独一个 PR。

| 顺序 | 上游范围 | 内容 | 状态 |
|---|---|---|---|
| 1 | `73d61d38` | `FenceQueue`、`GlStreamingRing`，`PersistentStreamingBuffer` 改薄包装，删除 `GlFence` 与 `StreamingBuffer` 接口 | PR #257 |
| 2 | `1babfd96`、`8e180962`、`7bb5bfd4` | 点尺寸改静态缓存并保留惰性查询，`glReadPixels` 与 `glGetTexImage` 的 PBO 重载重定向测试 | PR #257；`1babfd96` 的 `GLDebug.resetGroupStack`、`RenderBackend.invalidateStateMirror`、`ShaderManager.invalidateBoundProgram` 三项在本地没有崩溃恢复宿主，落地后无调用方，等 `RenderRecovery` 一并做 |
| 3 | `e84c8c8d` 阶段一 | `TextureTargets`、`RenderBackend` 纹理目标接口与 `Lwjgl3` 实现、`DSAUnsupported`/`DSAARB` 改经 `GLStateManager`、`GLSMRedirector` 表项 | PR #257；`RenderSystem` 的 stencil 与 texture storage 能力查询等第一个真实消费者，随阶段二 |
| 4 | `2a8c8756`、`10922edc` | `GLVersionChoice` 与其测试，接到 `CoreProfileDisplayCreator` 的版本探测起点；`SystemProperties.GL_PROFILE` 读 `angelica.glProfile` | PR #261 |
| 5 | `2a8c8756`、`8e180962` | `BackendOptions`、`BackendStartGuard` 与测试，`ActiniumConfig` 增加后端选择项与 GL 版本请求，`Actinium.onConstruct` 记录选项 | PR #261；`BackendStartGuard` 的 `arm` 与 `disarm` 等 SDL 后端接线，单后端下 `tripped()` 恒为 false |
| 6 | `482dece0` 前置 | `3b4ebcf6`（上游范围外）的 `glsm/loading` 五件套与 `GLSMRedirector` 的四参 `transformClassNode`，之后才能落 `GlsmRedirectAgent` | 待做；本地 `glsm/loading` 结构与上游不同，需按 `AngelicaRedirector` 与 `AngelicaClassDump` 重写，并给 `glIntegrationTest` 加 `javaagent` 配置 |
| 7 | `12857006` | `RenderBackend.isBufferImmutable`；`onPersistentBufferWrite` 已在顺序 1 落地 | PR #261 |
| 8 | `e84c8c8d` 阶段二 | 延迟纹理删除状态机，前置是 G02 的 draw lock 与帧结束钩子 | 已在 `feat/texture-staging` 落地，见批次一第 7 项 |
| — | `1587a12c` | `MainThreadPump` 与 `MainThreadPumpTest` | 待做；唯一驱动方是 SDL 的 presenter，且 `RenderBackend` 缺 `pumpDisplayMessages` 与 `parkNanos`，前置是 F09 的 `2845c9e0` |
| — | `49db8e11` | `SamplerAliasDeduplicator` 与其测试 | 待做；该类由 `8da561d3` 引入，测试还依赖 `TransformTestSupport`，真正消费点在 SDL 采样器搬运，随 S05 的 `ShaderIndex` 一并做 |

## SDL-GPU 后端引入顺序

SDL-GPU 后端确定后续引入，台账中标注为排除的 `sdl-gpu/`、`moltenvk/` 文件属于延后同步，引入时按下列顺序补齐。通用侧接口必须先就位，`RenderBackend` 的新方法一律给默认实现，SDL 后端接入时覆写。

| 顺序 | 范围 |
|---|---|
| 1 | `moltenvk/` 子项目与 `RendererOptionPages`、`SdlGpuDriver`、`RenderBackendChoice`（`2a8c8756`） |
| 2 | `Device`、`SDLGPURenderBackend`、`SDLGPUGate` 与 `sdl-gpu/build.gradle.kts` |
| 3 | 缓冲映射生命周期的 17 个文件（`12857006`）、`MainThreadPump` 的 present 三态（`1587a12c`） |
| 4 | 采样器搬运与 `SamplerAliasDeduplicator` 的 SDL 接线（`49db8e11`）、`glsmSdlAgent`（`482dece0`） |

## 无 SDL 前提下的 PR 顺序

SDL 后端暂缓期间可推进的全部工作，共 38 个 PR，分三批。批次之间无依赖，可交叉并行；批内编号即顺序。

评估前提：只按本地已有的类与接口判定可落性，纯 SDL / MoltenVK / Tracy 原生采集 / `src/sdl-gpu` 的内容一律不计。G01、G02、G03 的绝大部分已在 #249、#252、#253 落地，台账里仍标「待同步」的行属滞后记录，实际差集见下表的补丁项。

### 批次一：GLSM 收尾

已在 PR #263 上合并：第 1、2、10、12、24 项。第 5 项（`ExitStateCapture`）在后续 PR 上，它不依赖 Tracy no-op 门面；`TextureUtilHooks`、`MixinTextureUtil`、`VanillaLayerRig` 三个上游文件因本地缺类整体跳过，`rejectExitCapture` 守卫留给第 6 项与那 54 个门面方法一起做。

第 6 项的 `GLSMTextureLifetime` 拆出已在 `feat/glsm-texture-lifetime` 分支完成：`glsm/profiling/Tracy` 落 no-op 门面（无原生采集、无 `TracyBackend`、无 `tracy-client` 子项目），`GLSMTextureLifetime` 用 `Int2LongOpenHashMap` 状态机接管延迟删除，`GLStateManager.deferDeleteTexture`/`flushDeferredTextureDeletes` 改为委托、签名与调用点不变。`beginAttach` 里上游的 `TextureInfoCache.onWrittenOutsideUploads` 一项因本地无该字段跳过，`TextureStaging` 属第 7 项；上游 `FrameHooks.frameEnd()` 的帧计数改由 `MixinMinecraft` 在 `updateDisplay` 前调用 `GLSMTextureLifetime.onFrameEnd()`。

门面的公开方法补齐与 `rejectExitCapture` 守卫已在 `feat/glsm-facade-guards` 分支完成。按 `8e099e95` 与本地逐方法比对，17 个门面共缺 113 个 `public static` 方法，补了 94 个；剩余 19 个依赖本地不存在的类型或设施，留给对应批次：`TextureStaging` 与 `samplerUnits` 相关 8 个属第 7 项，`flushDeferredVertexAttribs`/`flushEntityIds`/`setEntityIds`/`setFfpInstancing` 属第 9 项，`getMvLinearGeneration`、`isProgramPendingDeletion`、`glBindImageTextures`、`lookupTexParameteri`、`markTextureAsAtlas`、`setTextureSupersamplingSamples`、`unbindAllSamplers` 依赖本地未移植的 generation/生命周期字段与 `isImageFormat` 判定。守卫按上游分布逐方法插入，上游 810 个公开方法中 291 个带守卫，本地已对齐 284 个，差异 7 个全部落在上面未移植的方法上。

`GLSM_ExitCaptureCoverage_UnitTest` 按上游语义重写：枚举 17 个门面的 `gl*` 公开静态方法，检查每个方法直接或经门面调用间接到达 `ExitStateCapture`。`GLSM_ExitState_GLTest.untrackedCallsAndStateQueriesReject` 的 6 个分支与 `GLSM_ExitTextureFilter_GLTest.textureParameterReadsAndWritesStillReject` 已恢复。

第 7 项已在 `feat/texture-staging` 落地：`TextureStaging` 的默认 CPU 路径使用 16 槽精确容量缓存，提交 BGRA 上传或放弃后回收；同一块只能归还一次，重复归还与跨后端使用直接抛异常。`GLSMTextures` 缓存全部纹理 target，按名称绑定 2D 与非 2D 纹理，批量 image/sampler 绑定及 `glCreateTextures` 重载接入统一门面。1D/3D 上传经过 GLSM 绑定并恢复 pixel-unpack buffer，命名 framebuffer layer/renderbuffer 复用普通挂接以维持引用计数，通用纹理、附件、DSA 与 EXT 重定向已补齐。最近 target 非 2D 时，2D 对象与 `TextureInfo` 查询读取驱动保留的 2D 绑定，跨单元查询恢复 active unit，DSA 三参绑定保留调用方 target，避免影响上传、uniform 与 mipmap。延迟删除复用第 6 项状态机，帧结束仍由 `MixinMinecraft` 驱动；未引入 SDL 或 `EntityTextureAtlas` 回调。新增 GL 覆盖延迟释放、immutable 纹理、3D 绑定、暂存像素与缓冲生命周期、批量 image/sampler 调用及上述 2D/PBO 回归，另以无 GL 测试覆盖缓存淘汰和非法重复归还。

| # | 范围 | 覆盖上游 | 规模 | 前置 |
|---|---|---|---|---|
| 1 | `GLSMRedirector` 的 `Util.checkGLError` 表项与 `glGenerateMipmapEXT` 别名、`ImageUnitBinding` 的 `reset` 与默认值、`glBindImageTexture` 跟踪、`MultiListenerNotifier` | `b6a4497f`、`6dfb0312`、`e98c927a`、`85892c0f` | 5 改 1 新，约 200 行 | 无 |
| 2 | COW 剪枝：`GLContextState.savesState`、`CowStateStack.beforeModify` 提前返回、`popState` 的 `claimedAt` 守卫 | `12fa08ab` | 4 改 1 新，约 150 行 | 无 |
| 3 | 测试骨架：`ArchScan`、`NoWildcardImportsTest`、`GlsmRedirectAgent`、`GLSMCoreExtension`、`FfpFixture`，`glIntegrationTest` 补 `javaagent` | `85d0641a`、`6c5e146f` | 4 新 2 改，约 400 行 | 无，可与 1、2 并行 |
| 4 | 门面补齐（334 个无调用方方法），用架构测试防回退 | `91e67f8d` 部分 | 17 改，约 1200 行 | 1、2 |
| 5 | `ExitStateCapture`、`ExitState`、`hooks/BatchStateGuard`，`GLStateManager` 入口按门面重定向 | `102c6cad` | 3 新 21 改，约 2300 行 | 已完成；Tracy no-op 门面不在此提交内，留给需要它的 `GLSMTextureLifetime` |
| 6 | 门面收尾（补齐公开方法并逐方法插入 `rejectExitCapture` 守卫）与 `GLSMTextureLifetime` 拆出 | `91e67f8d` 剩余 | 17 改 1 新，约 1600 行 | 5；`GLSMTextureLifetime` 拆出已完成（`feat/glsm-texture-lifetime`）；门面方法补齐与守卫已在 `feat/glsm-facade-guards` 完成，见下方小节 |
| 7 | 延迟纹理删除入口与 `TextureStaging` | `e84c8c8d` 阶段二、`c10d30b2` 的 CPU 暂存 | 已完成（`feat/texture-staging`）：4 新 7 改 | 6；复用第 6 项状态机，补齐纹理目标缓存与外围入口 |
| 8 | 宽线 `LineQuads` 与 `LineDrawHandler`，`ImmediateModeRecorder.processDrawElementsFromAttribs`，删除 `GeometryShaderGenerator` | `2f1f380e` | 已完成（`feat/glsm-line-quads`）：2 新 11 改 1 删 | 7；`WideLineClipGLTest` 依赖 `GLStateManagerIntegrationFixture.routeVertexFormats` 覆盖 `VertexFormat.setupBufferState`，因为集成测试环境没有 ASM redirector |
| 9 | 逐顶点实体 ID：`TessellatorEntityIds`、`ImmediateExtendedAttribHandler`、`setFfpInstancing` | `ab3bf176` | 2 新 12 改，约 800 行 | 8，且需 F02 的 `FfpExtendedAttribs` 与 `InstancedAttribs` |

### 批次二：Celeritas 与着色器

| # | 范围 | 覆盖上游 | 规模 | 前置 |
|---|---|---|---|---|
| 10 | bitraster 远平面判定与半透明排序鲁棒性 | `48eb54f5`、`625b6f40`、`f33e50ce` | 5 改 1 新，约 120 行 | 无 |
| 11 | 着色器降级迁入 `ShaderLoader` 与 GLES 护栏 | `c64f4225`、`98c1af33`、`c9d46e70` 的 `MixinShaderLoader` | 5 改 1 新，约 90 行 | 无。已完成 |
| 12 | 移除 `FogService` 的 SPI 登记，改走 `ChunkShaderEnvironment` | `7e5e9d7e` | 8 改 1 新 1 删，约 90 行 | 无 |
| 13 | 移除 `GLRenderDevice.VANILLA_STATE_RESETTER` | `8883da78` | 3 改，约 10 行 | 无。已完成 |
| 14 | `BatchBufferRecycler` 与 `BatchCacheParams` 的视口分键 | `69e91690`、`6bf6a433` | 6 改 3 新，约 320 行 | 无 |
| 15 | `TerrainDrawStats` 计数与两个埋点 mixin、`CubeStatusTracker` 继承改造 | `c9d46e70` 部分 | 1 改 4 新，约 160 行 | 无。已完成计数与两个 mixin；`CubeStatusTracker` 已移植但本地无构造点 |
| 16 | 阴影 pass 的搜索门控与上传时机 | `77aec052`、`14cb5f7f` | 4 改，约 130 行 | 14 |
| 17 | `GLContextState` 的索引缓冲绑定跟踪 | `cfc6e12e` 通用半 | 3 改 1 新，约 180 行 | 无 |
| 18 | 着色器磁盘缓存与程序二进制缓存、`AngelicaWorkers` | `37afddab`、`38af64f1` 的 glsm 半、`0a4d4896` 的 `ProgramBinaryCache` | 7 改 4 新，约 700 行 | 无。已完成（`feat/shader-disk-cache`）：新增 `AngelicaWorkers`、`ShaderCacheIO`、`ShaderDiskCache`、`ProgramBinaryCache`；改 `CompatShaderTransformer`、`SpirvCompiler`、`GlslTransformUtils`、`RenderSystem`、`Lwjgl3GLRenderBackend`、`ClientProxy`、`Actinium`、`GLSMConfig`。`supportsProgramBinary` 取值链为 `RENDER_BACKEND.supportsProgramBinary()` → `Lwjgl3GLRenderBackend` 的 `!isGLES() && (OpenGL41 \\|\\| GL_ARB_get_program_binary) && GL_NUM_PROGRAM_BINARY_FORMATS > 0`。`GLSMConfig.workerThreadCount` 已落地：`GLSMConfig` 与 `ActiniumConfig` 各加字段，`Actinium.onConstruct` 赋值，`AngelicaWorkers.resolveThreads` 读它，选项页绑定留给 GUI 批次；`ProgramBinaryCache.setEnabled` 由 `IrisConfig.saveCompiledShaders` 在 `load()` 与 `setSaveCompiledShaders` 驱动，`IrisConfig` 新增该字段与 properties 读写，选项页按钮留给第 22 项。`ProgramBinaryCache` 的 `load`、`save`、`saveRetained`、`usePack`、`markRetrievable` 五个入口在第 18 项落地时没有任何调用方，打开「保存已编译着色器」不会写出 `gl-program/`；调用点分散在第 20 项（`Iris.java` 的 `usePack`、`ProgramBuilder` 的 `saveRetained`）与第 22 项（`ProgramCreator`、`IrisCeleritasChunkProgramOverrides`、`GLSMPrograms.glLinkProgram` 的 `retrievable` 提示），已在 `fix/program-binary-cache-wiring` 提前补齐通用路径；`@Lwjgl3Aware` 注解因本地无 lwjgl3ify 依赖未移植 |
| 19 | GLSL 变换与 Vulkan 预处理修正、`ShaderIndex` | `bdcb4d18`、`159f7ff8`、`2ed838ac`、`38b1d9a0` 的 `GlslTransformUtils` | 4 改 3 新，约 400 行 | 无。已完成（`feat/glsl-transform-fixes`）：新增 `ShaderIndex`（与上游逐字一致）与 `GlslTransformUtils.getFormattedShaderRebased`/`rebaseAt`（SDL 阶段产物预留）；`GlslVulkanPreprocess` 改为 `ShaderIndex` 驱动并加 `TARGET_RESERVED`、`IDENTIFIER_RENAMES`、`renameUserFunctions`；`SpirvCompiler` 加内置函数重命名后的编译重试；`SpirvShaderTranslator` 删 `glslToGlslEsRawForTest` 并改用 `hasStorageQualifier`；Iris `ShaderTransformer` 两处保留字还原改 token 级、删 `addIfNotExistsType`。`SAMPLER_RENAMED` 与 `RESERVED_RENAMED_PREFIX` 统一为 `actinium_` 前缀。排除：`RwImageStoreExtractor`、Iris 的 `isSDLGPU` 分支、`parseBothQuiet`/`parsePreQuiet`（本地用 `org.taumc.glsl.ShaderParser`，内部已是单 lexer 双 parser） |
| 20 | 预处理缓存与着色器预热 | `38af64f1` 的 Iris 半、`b49d37d8` | 14 改 4 新，约 900 行 | 18。已完成（`feat/shader-preprocess-warmup`）：`PreprocessedSourceCache` 按输入、宏和 GLSL 版本缓存预处理结果，include 与 properties 解析并行化，语言表异步读取，选项菜单延迟构建；`ShaderWarmup` 在主菜单后台加载类并预读选定光影包，正式加载按指纹复用，zip 文件系统随包生命周期管理；`StandardMacros` 忽略分支、提交距离与 dirty 等版本尾巴，无法解析时使用最新版本哨兵。预编译 GUI 覆盖整包选择、目录列表持久化、父子行进度和逐项状态刷新；世界内队列结束或停止后重载当前管线，嵌套跟踪不替换目录 marker，并行缓存写入保留覆盖语义。`RetainedPrograms`、共享 gbuffer 构建、地形程序预编译与 `PipelineManager.discardPipeline` 已接入。实例化与宽线程序随各自渲染设施同步，`PerFrameUniformBlockHarvester` 预留 SDLGPU 路径 |
| 21 | 纹理过滤、PBR 可见性与 `DebugCounters` | `08aa3771`、`1587a12c` 的 `DebugCounters` | 8 改 1 新，约 250 行 | 18。已完成（`feat/texture-pbr-debug-counters`）：PBR normal/specular 动画继承基础纹理的可见标记，隐藏时只推进帧计数，不上传新帧；无动画元数据或缺 Mixin 时持续上传，开关关闭时全部上传。`SpriteAnimationGate` 与 `SpriteAnimationTicker` 隔离 shader 和根项目配置，`DebugCounters` 保留累计值并按周期输出差值，接入 `GLSMPerfDebug`。旧 `VisibleTextureIterator` 已删除。`MainThreadPump`、fence 与 SDL 部分留对应路线 |
| 22 | Iris 着色器缓存开关与图集接口改名 | `0a4d4896` 的 Iris 半、`af599f29` 的图集半 | 8 改，约 350 行 | 18。已完成（`feat/iris-cache-atlas-api`）：缓存开关保留现有视频设置项，开启只保存设置，不触发重载或自动完整编译，关闭通过 `IrisConfig` 删除全部程序缓存与完成标记。选择性预编译继续逐帧执行，只有玩家明确选中某包全部目录并成功完成后才写整包完成标记并裁剪该包缓存，部分目录不触发整包裁剪；新队列会关闭被取代的未完成会话。`RetainedPrograms`、`beginFullCompile`/`finishFullCompile`/`wasCompiledFor` 的保留路径与异步 marker 即时可见已接通。图集接口统一为 `angelica$` 前缀，PBR 动画行为不变，新增缓存生命周期、选择性队列与 section 动画复播测试 |

### 批次三：光影包与功能

| # | 范围 | 覆盖上游 | 规模 | 前置 |
|---|---|---|---|---|
| 23 | 维度文件夹解析与 NBT 材质映射 | `b7628404`、`69019b9a` | 6 改 1 新，约 80 行 | 无 |
| 24 | 核心 profile 拒绝信息与探测上限 | `990dadc0` | 2 改 1 新，约 60 行 | 无 |
| 25 | 阴影 pass 索尼相机与逐层 stage | `cbd16b1a`、`8af9340b` | 3 改，约 60 行 | 无 |
| 26 | 自定义方块渲染的光影属性（`StateAwareTessellator`、`ImmediateExtendedAttribs`、方块材质属性） | `fe67a22d` | 5 新 4 改加 4 个 mixin，约 350 行 | 23、9 |
| 27 | 帧节奏：`PacerCore`、`FramePacer`（`FpsReducer` 未移植） | `338d9419`、`69124e3f`、`74ea5c47` 的 `FpsReducer` | 5 新 7 改，约 1000 行 | 无 |
| 28 | 缩放与鼠标模式选项 | `977db3d6`、`74ea5c47` 的 `Zoom` | 2 新 2 改，约 200 行 | 27 |
| 29 | 字体改进：分段深度掩码、splash 纹理回退、抗锯齿外扩、自定义字体提供者 | `9081f4c3`、`97057224`、`fa71eda3`、`75a02781`、`5457df53`、`32f8eef1`、`d16c1e7c` | 2 新 9 改，约 700 行 | 无 |
| 30 | 深色模式与离上下文字体创建 | `61bb779d`、`acedba4e`、`9cb35c67`、`66d2a548`、`8387b4e9`、`231a2ab2`、`0d959c2b` | 6 新 10 改，约 1300 行 | 29 |
| 31 | 云层重写与云 culling | `939e3146`、`c578970a`、`7f3fb35e`、`888a4de0`、`21a054a4`、`ea50e0a0` 的云部分 | 16 新 2 改，约 2600 行 | 无，需剥离 `jss/notfine` 依赖 |
| 32 | GUI 物品超采样 | `17a2ed7f` | 3 新 13 改，约 560 行 | 无 |
| 33 | 现代 F3 调试屏（拆两次提交） | `9bbb0537`、`c9d46e70` 的 `DebugKeyHandler` | 40 新 8 改，约 6000 行 | 无 |
| 34 | 生物群系混色 | `233c74e0`、`a8e95f70`、`fa8842a6`、`bfb66d84` | 10 新 8 改，约 900 行 | 无 |
| 35 | 第三方 API 与 RC 灯笼 | `d29a8278`、`3d3d0f9e` | 3 新 1 改，约 80 行 | 无 |
| 36 | TESR 批处理与实体图集、体素阴影：先 `FfpExtendedAttribs` 与 `InstancedAttribs` 骨架，再实体图集，再完整批量，最后体素与重放 | `9814a96b`、`1f274a64`、`2f4ac2a9`、`7c28a69b`、`69428c12`、`fc8d3e8b`、`9f19d94c`、`db7d3284`、`bc08e4ba`、`7cc9cea3`、`af599f29`、`5ed1c925`、`c9d46e70` 的 `shouldRenderLightBlockEntities`、`2d9d1a45`、`b446968e`、`713509a8`、`80e5b544`、`8537835d` | 45 新 20 改，约 8000 行 | 9、18 |
| 37 | 区块材质选择与 ISBRH 派发 | `0cae2be1`、`1946c2ad`、`cae4fea2` | 6 新 4 改，约 500 行 | 36 |
| 38 | CTM 与 notfine 启动路径 | `38b1d9a0` 的 `com/prupe` 半、`85892c0f` 的 mcpatcherforge 半、`cd0a9608` 的 `com/prupe` 半、`2f1f380e` 的 `SelectionBoxRenderer` | 12 新 3 改，约 1200 行 | 37 |

### 关键路径与阻塞

`ExitStateCapture`（#5）是 G06 剩余 54 个方法的硬前置，也是 #6、#7 的前置，整条批次一的价值都压在它上面。`Tracy` 在本地没有 `profiling` 包，不落 no-op 门面的话 `GLSMTextureLifetime` 无法编译，这是 #5 必须先解决的问题。

`FfpExtendedAttribs` 与 `InstancedAttribs` 同时是 #9 与 #36 的前置，建议在 #36 的第一步单独成一个 PR，先于两者落地。

`C001` 到 `C09` 的多数 SHA 在 `D:/Code/celeritas` 仓库而非 Angelica，两条台账（本文件与 `docs/sync-celeritas-b2a70f7f28.md`）必须一起读。`69e91690`、`6bf6a433`、`f33e50ce`、`14cb5f7f`、`77aec052` 在本地两个仓库都不存在，只能用 `gh api repos/GTNewHorizons/Celeritas/commits/<sha>` 取证。

`F12`（#38）需要整包引入 `com/prupe/mcpatcher` 与 `jss/notfine`，这两个包在本地完全不存在；`jss/notfine/core/Settings` 同时是 #31 云渲染与 #28 缩放的依赖，先落 #38 的 `Settings` 部分可以少一次改造成本。

## 多目标提交的归属拆解

以下 16 个提交在台账里写多个目标 PR，执行时按功能切分，不要整提交塞进某一个 PR。

| 短 SHA | 拆解 |
|---|---|
| `c9d46e70` | `DebugKeyHandler` 与 `verboseF3` → F07；`MixinShaderLoader` → C01；`MixinSharedQuadIndexBuffer`、`MixinRenderRegionManager`、`TerrainDrawStats`、`CubeStatusTracker` 继承改造 → C04；shadow viewport 与搜索行为复用 → C06；`PackShadowDirectives.shouldRenderLightBlockEntities` → F02；`samplerDefines` → S02；fog 复位 → C07 |
| `af599f29` | 实例化与 batching 的 Iris 交互（`InstancedAttribs`、`FfpExtendedAttribs`、`BatchStateGuard`、`recording/` 命令扩展）→ F02；图集与纹理动画（`IPatchedTextureAtlasSprite`、`MixinTextureAtlasSprite`、`DisplayListTextureAnimationTest`）→ S06 |
| `5ed1c925` | Dragon API 与发光束、眼渲染 → F02；顶点重放与 `AttributeTransformer` 扩展 → G07 |
| `8537835d` | 玩家反射捕获 → F04；顶点重写回放判定 → G07 |
| `cd0a9608` | 图集加载策略与异步图集 → S02；`com/prupe` 的 `TexturePackAPI` → F12 |
| `2f1f380e` | recording/LineQuads 与 FFP 宽线、`AttributeTransformer` 同属一次宽线重写（`LineQuads` 数据、`GLStateManager` 拦截、FFP 顶点着色器三处必须同批落地），合并在 G07；`SelectionBoxRenderer` 等通用兼容 → F11 |
| `1587a12c` | `MainThreadPump` → G05；`DebugCounters` 与 `FlybyRunner` 采集 → S03 |
| `74ea5c47` | `rendering/FpsReducer` 帧率限制器 → F09；`zoom/Zoom` 的 key category 整理（无 mixin）→ F10 |
| `2d9d1a45` | 实体图集与 instances 分支 → F03；`PBRTextureManager` 的资源索引与加载顺序 → S03 |
| `37afddab` | 着色器磁盘缓存（S01）、S04、S05 的共享部分，按 `docs` 中 S 系列定义切分 |
| `38af64f1` | 着色器加载优化，横跨 S01、S04、S05、S06、S07 |
| `0a4d4896` | Iris 着色器缓存 → S06；缓存开关 → S08 |
| `38b1d9a0` | 通用启动优化 → S03；`com/prupe` 启动路径 → F12 |
| `85892c0f` | DSA 绑定恢复与纹理跟踪 → G03；`mcpatcherforge` 的 `MixinRenderBlocks` 重组 → F12 |
| `fe82cd32` | `StateSet` 修正 → G01；`BatchDrawDefaults` 与 `TextDrawState` → F02；字体与选项页 → F05 |
| `ab3bf176` | 每顶点实体 ID 的 streaming、Iris 与 FFP 侧同属一条数据链（`TessellatorEntityIds` 读 `Tessellator` 顶点游标，写入由 `ImmediateExtendedAttribHandler` 消费，调用方在 Iris 的 `CapturedRenderingState`），合并在 G07 |

## 统计与核对方法

条目数按上游提交逐行枚举，每个上游提交在台账中只占一行，多目标写在同一行的“目标 PR”列。`未分配` 条目为 0，132 行全部落到具体 PR 或“不适用”。

混合提交的判定方法：逐个提交用 `git show <sha> --name-status` 取出完整清单，人工判断每个文件是否属于本地存在的模块，并确认保留的文件块与排除的文件块各自可独立说明。判定结果分两类：保留通用实现并排除平台专用或治理文件的写“分 hunk 同步”并列出排除清单；整提交目标代码都落在本地不存在的模块、平台或治理文件里的写“不适用”。不按目录前缀机械统计，分类结果以本表逐行的处置说明为准。

## PR 0 已完成的自动化验证

PR 0 的任务 1 恢复了 GL 集成测试入口（`glIntegrationTest` source set、LWJGL SDL 依赖与独立任务），任务 2 提取了 `BUFFERBUILDER_*` 与 `SHADOW_*` 性能计时。两项的自动化验证记录在 `.superpowers/sdd/pr0-task1-report.md` 与 `.superpowers/sdd/pr0-task2-report.md`：

- `./gradlew check --no-daemon` 通过，覆盖 `:test`（795 个测试，0 失败）、`:verifyDistributedJar`、`:verifyModuleBoundaries` 与四个子项目的 `check`，并包含 `:compileGlIntegrationTestJava`。
- `./gradlew glIntegrationTest --no-daemon` 通过，2 个 SDL 窗口后端集成测试实际执行并通过；`check` 不依赖该任务，普通验证不创建原生窗口。
- 任务 2 的计时接线通过 RED/GREEN：先在 `GLSMPerfDebugTest` 得到编译失败与断言失败，再实现 stage 常量与接线使 17 个测试全部通过。

本任务（PR 0 任务 3）新增两份台账文档，未改代码、未执行 Git 提交。台账的 SHA 覆盖由脚本机械核对，方法与结果见 `.superpowers/sdd/pr0-task3-report.md`。

## 用户实机性能基线（待采集）

以下项目尚未采集，台账不记录任何实测数值：

1. **渲染包与版本**：Derivative Main `d24.4.14`。
2. **环境固定项**：同一存档、同一坐标、同一视角朝向、同一画质预设、同一渲染距离、同一 Distant Horizons 配置（LOD 距离与质量档位需一并记录）。
3. **采样方式**：每项配置连续采集 5 次、每次 60 秒，丢弃首次（含世界加载抖动）。
4. **指标**：FPS（平均值与 1% low）、CPU frame time（平均值与 P95）、进程内存峰值。
5. **对照基线**：同一环境下的 Actinium `ef01e10c` 与各 PR 合并后的构建，两组在同一会话内交替采集，避免时段差异。
6. **判定**：FPS 与 CPU frame time 的差值超出两次同构建重复采集的波动范围才计入回归。

在采集到数据之前，任何 PR 都不得声称性能改进或回归；计时数据的解读以 `.superpowers/sdd/pr0-task2-report.md` 记录的采样语义为准（`BUFFERBUILDER_*` 按默认节奏采样，`SHADOW_*` 每次采样）。
