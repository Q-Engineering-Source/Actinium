# Actinium 架构说明

最后更新：2026-10-06。

## 概述

Actinium 是一个客户端模组兼 coremod（`@Mod(clientSideOnly = true)` + `IFMLLoadingPlugin`），面向
Minecraft 1.12.2 / Cleanroom Loader。目标是在旧版客户端引入现代化渲染管线，同时保持光影包、
经典模组内容与性能渲染共存。

渲染栈由根项目与四个内嵌 Gradle 子项目组成，最终所有 class 合并进同一个 Actinium jar：

- 根项目 `src/main/`：生命周期、配置、兼容层、Mixin 注册与 Actinium 自有渲染代码。
- `shader/`：Iris 风格光影管线（光影包解析、GLSL 变换、render targets、uniform、pipeline）。
- `celeritas-common/`：区块构建、上传、裁剪与绘制引擎（几乎不依赖 Minecraft 类）。
- `glsm/`：OpenGL 状态跟踪、重定向、固定管线模拟与调试设施。
- `GTNHLib/`：渲染原语库（tessellator / VBO / VAO / 后处理 / 顶点格式）。

## 构建模型

### 子项目依赖方向

```
GTNHLib ← glsm ← celeritas-common ← shader ← 根项目 src/main（compileOnly）
```

- `GTNHLib`：零子项目依赖，仅测试用 JUnit + LWJGL natives。
- `glsm`：`api project(':GTNHLib')`，并把根项目 `src/lwjglCommon/java`、`src/lwjgl3/java`
  两个 source set 直接并入 main（LWJGL 服务抽象层，Angelica 遗留）。
- `celeritas-common`：`api project(':glsm')`，无 resources。
- `shader`：`api project(':glsm') + project(':GTNHLib') + project(':celeritas-common')`，无 resources。

### 打包

- 根项目通过 `mergeEmbeddedLibraryClasses` 把四个子项目输出 Sync 合并进主 jar
  （`DuplicatesStrategy.FAIL`），`remapJar` 生成可安装的 SRG 产物。

开发约束：

- 子项目不得引用 `com.dhj.actinium` 实现类（`DependencyDirectionTest` 在字节码层兜底）；
  跨边界行为通过 bridge、provider 或小接口注入（约定见 `docs/bridges.md`）。
- 新增 render pass 必须明确 framebuffer、program、texture unit、viewport 与混合/深度状态归属。
- 新增 Mixin 必须加入 `MixinConfigurationTest` 覆盖的配置文件。
- 发布前运行 `build`；`check` 会验证自动化测试及 remap jar 结构。

渲染选项的持久化字段、注册页面与实现落点见 `docs/options.md`。

## 初始化链路

1. **`MixinEarly`**（`mixins/` 包，FML plugin 阶段）：实现 `IFMLLoadingPlugin` + `IEarlyMixinLoader`。
   注册 `mixins.actinium.vintage.json` 与 `mixins.actinium.iris.json` 两个 early 配置；
   注册 ASM transformer（`MacDisplayForwardCompatTransformer`、Angelica EarlyRedirector）；
   追加 AngelicaLateTweaker；设 mixin 兼容到 JAVA_11。
2. **`MixinLate`**（late 阶段）：读取 `mixins.actinium.conditions.properties` 的 mod id 门控，
   挑选 conditional 配置加载。
3. **`Actinium.onConstruct`**（`@Mod` 主类）：挂载全部第三方 bridge
   （`GLRenderDevice.VANILLA_STATE_RESETTER`、`RuntimeOptionsBridge`、`EmbeddiumRuntimeOptions`、
   `PostProcessingBridge`、`WorldRendererCompatBridge`、`IrisDebugOptions.Bridge`、
   `GLSMPerfDebugHooks`）；初始化 DH / NeoFontRender 兼容。
4. **`onInit`**：NeoFontRender 初始化、dev 命令注册、Iris fmlInitEvent。

`ActiniumRuntime`（`runtime/` 包）在类加载时静态装载 `SodiumGameOptions` 配置与版本信息，
失败时降级为只读默认值，是全局状态的静态持有者。

## 渲染执行链路

1. `EntityRendererIrisMixin` 在世界渲染中驱动 pipeline：开始世界、prepare、shadow、
   半透明阶段与最终合成。
2. 光影启用时，`CeleritasTerrainPipeline`（shader 子项目）为区块 pass 提供变换后的
   shader program；无光影时使用 `FixedFunctionWorldRenderingPipeline`。
3. `ActiniumWorldRenderer`（根项目 `render/terrain/`）作为区块渲染主入口，实现 Iris 的
   `WorldRendererCompat`，通过 `WorldRendererCompatBridge` 注册给 shader 侧。
4. pipeline 在维度切换或光影重载时销毁 GL 资源并重建，最后恢复 Minecraft 主 framebuffer。
5. `AdaptiveShadowBoundsTransformer` 在可识别的 PCF helper 入口加入 shadow-map bounds
   early return；仅当 GLSM 性能调试开启且 SSBO/GLSL 4.30 可用时，
   `DeferredWorldRenderingPipeline` 才分配独立 SSBO 并注入运行期 `atomicAdd` 统计。

## 根项目包详解（`com.dhj.actinium`）

### 顶层与生命周期

- **根包**：`Actinium`（`@Mod` 主类，FML 生命周期事件挂 bridge、F3 渲染器信息）。
- **`runtime/`**：`ActiniumRuntime` —— 静态持有 MODID、logger、版本号与 `SodiumGameOptions`。
- **`loading/fml/`**：`transformers/MacDisplayForwardCompatTransformer` —— ASM transformer，
  为 macOS 的 `Display.create()` 注入 `GLFW_OPENGL_FORWARD_COMPAT` hint，防止 core context
  回退到 2.1；由 `MixinEarly` 注册。

### Mixin 组织

- **`mixins/`（复数，装载器）**：`MixinEarly`、`MixinLate` —— early/late 配置注册与条件门控。
- **`mixin/`（单数，注入类本体）**：
  - `mixin/core/terrain`：`BufferBuilderMixin`（经 iris.json 注册）。
  - `mixin/core/vertex`：`MixinVertexFormat`（经 iris.json 注册）——在 `addElement`/`clear`
    时重建挂在格式上的预计算布局数组。
  - `mixin/features/iris`（含 `startup/`）：约 35 个 Iris 兼容注入
    （实体、粒子、渲染器、纹理地图接入与启动期纹理注入）。
  - `mixin/mod/`：按模组分组的 conditional 注入 —— `betterfoliage`、`ccl`、`dh`（7 个）、
    `gibbed`、`hbm`（2 类，机器状态与世界光照兼容）、`ichunutil`、`littletiles`（2 类，
    TE 顶点缓存接入区块网格）、`lumenized`、`revoui`、
    `voxelmap`（3 类，小地图兼容）；
    `stellarcore` 为空目录（规划占位）。
  - `mixin/vintage/`：原版 1.12.2 注入分支 —— `core`（Minecraft/RenderGlobal/Tessellator/
    纹理上传）、`core/collections`、`core/crash`（SplashProgress）、`core/frustum`、
    `core/startup`（启动序列）、`core/terrain`（大量 Accessor + RenderGlobal/
    ClientChunkManager 等）、`core/vertex`、`features/mipmaps`、`features/options`、
    `features/render/tileentity/piston`、`features/textures`、`fontrenderer`、`gui`；
    `diagnostics` 为空目录。
  - `mixin/dediag`：空目录（dedicated-server 诊断分支预留）。

### 配置

- **`config/`**：`ActiniumConfig`（静态功能开关：enableIris、enableCeleritas、
  enableThreadedChunkBuilding 等）、`ActiniumRuntimeOptions`（运行时选项，`actinium.*`
  系统属性优先，回退 `SodiumGameOptions`）、`AnimationMode` / `ManagedEnum`（动画模式枚举）。

### 兼容层（业务逻辑，mixin 只做注入）

- **`compat/` 根**：`MixinReEntranceLockFix` —— late mixin 配置入队前的 re-entrance lock
  清理与类预加载修复。
- **`compat/ccl/`**：`GlStateTrackerSnapshot` —— CCL 状态跟踪快照（配 `mixin/mod/ccl`）。
- **DH 兼容**：Distant Horizons 自行完成 Actinium/Iris 集成（Iris 侧接管见 `shader` 子项目的
  `net.coderbot.iris.compat.dh`）。Actinium 不注入 DH，也不持有 DH 的 Iris 访问器或延迟 LOD
  开关（见 `docs/compat/dh.md`）。
- **`compat/fluidlogged/`**：`FluidloggedCompat`、`FluidStateStorage`、`FluidloggedBlockAccess`
  —— 流体方块状态存取，供区块克隆离线读取。
- **`compat/gibbed/`**：`ActiniumModelRenderer` —— Gibbed 尸块渲染模型扩展。
- **`compat/hbm/`**：`HbmRenderStateCompat` —— HBM attribute scope 到 GLSM 状态栈的映射，
  以及方块实体世界 lightmap 同步（RenderUtil 走 `mixin/mod/hbm`，方块实体侧走
  `mixin/early/hbm`，后者按 `isHbmInstalled()` 运行时门控）；`HbmWeaponDepthCompat` ——
  Sedna 武器第一人称渲染的手部深度接管（用 Actinium 的光影状态回答 HBM 的 OptiFine 探针，
  避免它在 Iris 手部 pass 内清空世界深度），配 `mixin/mod/hbm` 的 `MixinItemRenderWeaponBase`。
- **`compat/ichunutil/`**：`PortalViewportFactory` / `PortalViewportProvider` /
  `PortalChunkRenderMatrices` / `PortalRenderState` / `WorldBoxVisibility`
  —— 传送门视口与渲染状态管理。
- **`compat/lumenized/`**：`BloomStateGuard` —— GTCEu/Lumenized bloom 流程前后的 GLSM
  状态快照/恢复（配 `mixin/mod/lumenized`，同一实现同时覆盖两家内嵌的
  `gregtech.client` bloom 代码）。
- **`compat/modernui/`**：`MuiGuiScaleHook` —— ModernUI 界面缩放钩子。
- **`compat/neofontrender/`**：`NeoFontRenderCompat` —— NeoFontRender 初始化兼容。
- **`compat/rfp2/`**：空目录（规划占位）。
- **`compat/voxelmap/`**：`VoxelMapCompat` —— VoxelMap 小地图兼容桥（CPU 纹理路径
  强制、mipmap 回退判定、跨 mixin 共享的 scissor 活动标志，配 `mixin/mod/voxelmap`）。
- **`compat/sodium/`**：Embeddium/钠配置引导与选项扩展收集 —— `ActiniumConfigBootstrap`、
  `ActiniumApplyActions(Impl)`、`ActiniumFlagHook`、`LegacyExtensionEntryPoint` /
  `LegacyOptionAdapter`、`OptionGUIConstructionBridge`。

### 渲染

- **`render/` 根**：`EndPortal*` 一组（CompositeLogic/CompositeRenderer/Geometry/Layers/Mesh/
  Projection/Renderer/ActivePassScope/BlockEntityIdScope —— 末地传送门复合渲染管线）、
  `FastLitItemDisplayListCache`、`BufferBuilderStreamingDrawer`、`VanillaBufferBuilderRenderer` /
  `VanillaVertexBufferRenderer`、`ProjectiveTexCoordBuffer/Writer`、`GuiGlStateBoundary`、
  `RevoScreenEffectsGradient`。
- **`render/vertex/`**：`BufferBuilder` 写入热路径的直接内存化 —— `DirectBufferAddress`
  （共享 `sun.misc.Unsafe` 的唯一持有点；buffer 地址解析复用 GTNHLib
  `MemoryUtilities.memAddress0`）、`FastVertexLayout` +
  `FastVertexLayoutCalculator`（每格式预计算的元素偏移与跳 PADDING 推进环，状态挂在
  `VertexFormat` 上而非共享的 element 实例）、`VertexWriter` 接口与按元素类型的
  预构建单例（`Byte/Short/Int/FloatVertexWriter` + `VertexWriters` 工厂），由
  `BufferBuilderMixin` 的 overwrite 消费。
- **`render/entity/`**：`EntityGatherer` —— 按两个 pass 收集待渲染实体。
- **`render/frustum/`**：`IClippingHelper` —— 裁剪辅助接口（配 `mixin/vintage/core/frustum`）。
- **`render/terrain/`**：`ActiniumWorldRenderer`（`SimpleWorldRenderer` 扩展，区块渲染主入口，
  implements Iris `WorldRendererCompat`）、`CameraHelper`、`TileEntityBatchDrawGuard`、
  `VintageRenderPassConfigurationBuilder`、`VintageRenderSectionManager`。
- **`render/terrain/compile/`**：`VintageChunkBuildContext`；`compile/light/`：`LightDataCache`、
  `VintageDiffuseProvider`；`compile/pipeline/`：`VintageBlockRenderer`；`compile/task/`：
  `ChunkBuilderMeshingTask`（区块网格构建任务）。
- **`render/terrain/fog/`**：`GLStateManagerFogService` —— FogService SPI 实现。
- **`render/terrain/sprite/`**：`SpriteUtil`。

### 世界与数据

- **`world/`**：`WorldSlice`（世界状态切片，离线程拷贝 blockState/biome/light）、
  `BiomeLookup`（切片 biome 查询，越界坐标夹到快照边缘而非越界索引）、`EmptyBlockAccess`。
- **`world/biome/`**：`BiomeColorCache`（生物群系颜色缓存，override 基类 `postProcessColor` hook
  在 blur 后按世界坐标注入 `BiomeColorNoise` 位置噪声）。
- **`world/cloned/`**：`ActiniumBlockAccess`、`ChunkRenderContext`、`ClonedChunkSection`、
  `ClonedChunkSectionCache` —— 克隆区块段数据，供离线构建线程使用（依赖 `compat/fluidlogged`）。

### 其它

- **`api/render/terrain/`**：`BlockQuadTransformer`（区块 quad 上传前变换钩子，供 addon 做
  CTM/emissive 类处理）与 `BlockQuadTransformerHolder`（注册与分发）。
- **`celeritas/`**：`Shaders` —— 注册/清除 Embeddium 的 `ShaderProvider` 挂载点。
- **`command/`**：`TogglePassCommand` —— dev 环境调试命令，切换渲染 pass。
- **`debug/`**：`ActiniumDiagnostics`（生产诊断日志）、`ActiniumStartupDebugConfig`、
  `CoreProfileContextAttributes`、`OpenGlVersion`（GL 版本检测）。
- **`gui/`**：`ActiniumVideoOptionsScreen`（自绘视频选项屏）、`ActiniumGameOptionPages`、
  `ActiniumWindowModeController` / `FullscreenMode`、`MinecraftOptionsStorage` /
  `VanillaBooleanOptionBinding`、`VintageDrawContext` / `VintageInteractionContext`。
- **`shadows/`**：`ShadowRenderingState`（兼容 Iris 新旧 API 的阴影状态查询）、
  `ShadowMatrixAccess`（依赖 glsm 的 `InternalShadowRenderingState`）。
- **`texture/`**：`SpriteExtension`、`TextureMapExtension` —— 精灵/纹理地图扩展
  （动画帧、mipmap、上传数据访问）。

## shader/ 子项目（Iris 风格光影管线）

源码含多个命名空间：`net.coderbot.iris`（主体）、`net.irisshaders.iris.api.v0`（对外公共 API）、
`kroppeb.stareval`（自带表达式解析器，供 `#define` 常量表达式与自定义 uniform 解析）、
`com.github.bsideup.jabel`（Jabel 降级工具）与少量 `com.gtnewhorizons.angelica.*` 补充类。
主体按职责分组：

- **顶层**：`Iris`（模组入口与启动）、`IrisLogging`、`JomlConversions`。
- **`apiimpl/`**：`IrisApiV0` 接口实现（`IrisApiV0Impl`、`IrisApiV0ConfigImpl`）。
- **`shaderpack/`**（30 类）：光影包解析核心 —— 目录发现、ProgramSet、指令（directives）、
  材质 ID 映射、include 处理、C 预处理器（jcpp）、选项系统与选项菜单、纹理定义、
  字符串变换（`ShaderPack`、`ProgramSource`、`PackDirectives`、`ShaderProperties`、
  `OptionSet`、`IncludeGraph`、`JcppProcessor`、`FlatteningMap`）。
- **`parsing/`**：Iris 自定义函数/向量类型解析（`IrisFunctions`、`VectorConstructor`、
  `SmoothFloat`）。
- **`pipeline/`**（15 类）：渲染管线主体 —— 世界渲染、天空、手持物品、阴影、clear pass
  调度（`DeferredWorldRenderingPipeline`、`FixedFunctionWorldRenderingPipeline`、
  `PipelineManager`、`ShadowRenderer`、`HandRenderer`）。
- **`pipeline/transform/`**（22+ 类）：GLSL 着色器源变换（属性、顶点格式、计算、DH、
  Celeritas 通道）—— `ShaderTransformer`、`AttributeTransformer`、`CeleritasTransformer`、
  `CommonTransformer`、`EntityPatcher`。
- **`postprocess/`**：后处理合成（composite/final pass）—— `CompositeRenderer`、
  `FinalPassRenderer`、`FullScreenQuadRenderer`、`CenterDepthSampler`。
- **`rendertarget/`**（10 类）：render target 管理与帧缓冲（`RenderTargets`、`ColorTexture`、
  `DepthTexture`、`NoiseTexture`）。
- **`shadows/` + `shadow/`**：阴影 pass —— frustum 裁剪、阴影 target、合成
  （`ShadowCompositeRenderer`、`ShadowRenderTargets`、`AdvancedShadowCullingFrustum`、
  `ShadowMatrices`）。
- **`uniforms/`**（20 类）：内置 uniform 更新（相机、时间、雾、ID 映射）与自定义 uniform
  求值/缓存（`CameraUniforms`、`WorldTimeUniforms`、`IdMapUniforms`、`CustomUniforms`、
  `FloatCachedUniform`）。
- **`gl.*/`**（约 70 类）：GL 抽象层 —— program/shader/sampler/texture/framebuffer/buffer/
  image/blending/uniform（`GlProgram`、`GlTexture`、`GlFramebuffer`、`BlendModeStorage`、
  `UniformHolder`）。
- **`gbuffer_overrides.*/`**：gbuffer 程序匹配与渲染状态跟踪（`ProgramTable`、
  `RenderCondition`、`StateTracker`）。
- **`block_rendering/`**：方块渲染设置与材质 ID 映射（`BlockMaterialMapping`、
  `BlockRenderingSettings`）。
- **`celeritas*`**（12 类）：与 celeritas-common 的桥接层（`WorldRendererCompatBridge`、
  `IrisCeleritasShaderProvider`、`CeleritasTerrainPipeline`、`ExtendedChunkVertexEncoder`）。
- **`vertices/`**（10 类）：顶点构建 / QuadView 视图（`IrisQuadView`、
  `BufferBuilderPolygonView`、`ImmediateState`）。
- **`texture.*/`**：PBR 纹理系统（LabPBR）、mipmap、纹理导出（`PBRAtlasTexture`、
  `LabPBRTextureFormat`、`AbstractMipmapGenerator`）。
- **`fantastic/`**：粒子渲染封装（`IrisParticleRenderTypes`、`PhasedParticleEngine`）。
- **`layer/`**：gbuffer 层枚举（`GbufferPrograms`、`RenderLayer`）。
- **`config/`**：本地配置持久化（`IrisConfig`）。
- **`gui.*/`**：光影包选择/选项 GUI（`ShaderPackScreen`、`IrisVideoSettings`、
  `ShaderPackOptionList`）。
- **`debug/` + `debug/flight/`**（20 类）：调试 —— GL 错误检查、着色器回归测试、GL 调用
  飞行记录器（录制/回放 GL 命令流：`IrisDebugOptions`、`GlFlightRecorder`、
  `GlFlightGpuCommandRecorder`）。
- **`compat/dh/`、`compat/rfp2/`**：Distant Horizons 与 Rfp2 兼容（`DHCompat`、
  `IrisLodRenderProgram`、`Rfp2Compat`）。
- **`samplers/`**：全局 sampler/image 绑定（`IrisSamplers`、`IrisImages`）。

## celeritas-common/ 子项目（区块构建与绘制引擎）

区块上传的完成同步以 `RenderRegionManager.uploadMeshes` 为作用域：
`MappedStagingBuffer.flush` 仍在每次 arena 刷新时提交 mapped range 与 copy 命令，
但同一最外层上传批次只为累计字节创建一个 fence。`flip()` 必须等 fence 完成才归还
暂存容量，不能在批次结束时提前复用。批次外 flush 保留立即 fence 行为；空批次不创建
fence。环绕刷新按实际复制字节数计算，避免满环时起止位置相等的歧义。
环绕复制使用源 buffer 当前 position 加相对偏移，不创建 slice；连续复制在入队时合并，
累计字节数同时维护。fallback storage 仅实际使用后释放，空刷新不再重新分配。

`NativeBuffer` 的显式 free 同时撤销 phantom 回收登记，未显式释放的分配仍由回收队列处理；
分配字节使用原子计数，适配 worker 分配与主线程释放。纯内存操作依赖 GTNHLib 的
`MemoryUtilities`，其底层仍是 LWJGL 分配器，避免首次内存操作依赖当前线程的 GL 上下文。
扩容直接复制地址范围，不创建临时 ByteBuffer。区块上传结果在 finally 中释放，过滤结果
直接消费 fastutil map 的 values 视图；全局方块实体列表变空时同步移除全局 section 登记。

通用 `BufferBuilderStreamingDrawer` 仅在确实使用 orphan 路径时分配备用 VBO/VAO，
bounded ByteBuffer 视图由 drawRaw 统一创建。此改动已通过自动化检查，实机渲染回归待完成。

几乎不 import Minecraft 类（仅 2 处），是脱离 Minecraft 的纯 GL 渲染引擎
（JOML 20 处、LWJGL 3 处 import）。

- **`dhj.embeddedt.embeddium.api.*`**（9 个包，对外 API）：
  - `eventbus`：精简事件总线（`EmbeddiumEvent`、`EventHandlerRegistrar`）与选项事件。
  - `options.*`：选项系统（`Option`/`OptionGroup`/`OptionPage`、binding、control 控件、
    `StandardOptions`）。
  - `shader`：着色器接入点 —— `ShaderProvider` + `ShaderProviderHolder`（静态注册点）、
    `BlockRenderLayer`、`shader.buffer.*`、`shader.vertex.*`。
  - `debug`：`RenderDebugHooks`；`util`：`ColorABGR`/`NormI8` 等颜色工具。
- **`dhj.embeddedt.embeddium.impl.*`**（实现）：
  - `gl.*`：GL 设备抽象与状态管理 —— `device.RenderDevice`/`DrawCommandList`（命令列表
    架构）、`arena.GlBufferArena` + `staging.*`（缓冲竞技场与映射暂存）、`buffer.*`
    （immutable/mutable buffer）、`state.GlStateTracker`、`shader.*`/`shader.uniform.*`、
    `tessellation.*`、`sync.GlFence`、`profiling.TimerQueryManager`、`debug.GLDebug`、
    `attribute/array/functions/util`。
  - `render.chunk.*`：区块渲染核心 —— `region.RenderRegionManager`（区域管理）、
    `compile.executor.ChunkBuilder`（异步编译线程池）、`compile.buffers.ChunkModelBuilder`、
    `data.*`、`occlusion.*`（八叉树遮挡剔除）、`multidraw.*`（MultiDraw 发射器）、
    `lists.*`（渲染列表/可见区块收集）、`shader.*`（区块着色器接口）、`terrain.*`
    （地形 pass 与材质）、`vertex.*`（顶点格式与编码器）、`sorting.*`（半透明排序）、
    `map.ChunkTracker`、`fog.FogService`、`metrics.*`、`sprite.*`。
  - `render.*`：`terrain.SimpleWorldRenderer`、`frame.RenderAheadManager`、`viewport.*`
    （`CameraTransform`、`ViewportProvider`）、`ShaderModBridge`。
  - `model.*`：方块模型与光照 —— `light.smooth.SmoothLightPipeline`、
    `light.flat.FlatLightPipeline`、`light.LightPipelineProvider`、`quad.ModelQuad`、
    `quad.properties.ModelQuadFacing`。
  - `gui.*`：选项 GUI 框架（framework/frame/widgets/options/theme/console）、
    `SodiumGameOptions`。
  - 其它：`asm.ProxyClassGenerator`、`biome.BiomeColorCache`、`runtime.EmbeddiumRuntimeOptions`、
    `texture.MipmapHelper`、`util.*`（集合、颜色、迭代器、随机、排序、任务）。

### 遮挡搜索的并发契约

地形 pass 与阴影 pass 共享同一个 `occlusion.SectionLattice` 与单条搜索线程
（`lists.SectionGraph` 的单线程 executor）。一次可见性搜索会读写 lattice 的平行数组
（`visitState`/`sectionMeta`/`regionOfCell`/`latticeSection`），因此自搜索提交到 join 期间
这些数组必须保持结构稳定：

- `attach/detach` 由 `SectionGraph.assertSearchNotRunning()` 兜底（在飞即抛异常）；
- 元数据更新由 `SectionGraph.submitUpdateTask` 延迟到所有搜索 join 后执行；
- 窗口准备（`SectionLattice.ensureWindowCovers`，可能触发 allocate/shiftRebase/rebase 就地
  改写数组）只能经 `SectionGraph.prepareWindow` 在无搜索在飞时执行。两个 pass 的窗口准备
  统一在 `RenderSectionManager.updateForShadowPass` 提交任何搜索之前完成，因此
  `startShadowGraphUpdate` 不再自行准备窗口。

两个 pass 的相机并不相同：阴影 pass 交给地形 pass 的是上一帧捕获的 viewport，两者相差一帧
的相机位移。窗口因此按本帧全部搜索相机的跨度开窗（`RenderListManager.prepareSearchWindow`
可传多个 viewport），保证两边的搜索 root 都落在 lattice 可安装区内；单相机时窗口尺寸与
放置与原先一致，没有阴影 pass 的世界不受影响。

阴影搜索（`ShadowOcclusionCuller`）消费地形搜索当帧写出的 `visibleCells` 根集，因此两次
搜索之间不允许任何结构变化（含窗口重建）。

## glsm/ 子项目（GL 状态跟踪与渲染后端抽象）

核心原则：所有 GL 调用不直接调用 OpenGL，而是路由到 `BackendManager.RENDER_BACKEND`
（ServiceLoader 加载的后端）。

- **顶层 `com.gtnewhorizons.angelica.glsm`**（约 20 类）：`GLStateManager`（6300+ 行状态
  缓存管理器，静态初始化时查询 GL 能力，必须在 GL context 创建后加载）、`RenderSystem`
  （GL 调用抽象 + 渲染线程断言）、`DisplayListManager`/`DisplayListIDAllocator`、
  `CommandBufferBuilder`、`TransformOptimizer`（矩阵变换累积/坍缩优化）、`QuadConverter`、
  `CompatShaderTransformer`/`GlslTransformUtils`/`CompatUniformManager`/
  `CompatProgramUniformState(s)`（旧 GLSL/uniform 兼容，`actinium_renamed_` 前缀重命名）、
  `Feature`/`GLFeatureSet`、`FeedbackManager`、`GpuCommandDiagnostics`、`Vendor`、`GLDebug`、
  `ITessellatorData`。
- **`backend/`**：渲染后端抽象 —— `RenderBackend`、`BackendManager`、`DebugMessageHandler`。
- **`compat/`**：`FogHelper`（雾色状态捕获）；`compat/lwjgl/`：`AngelicaCylinder/Disk/
  PartialDisk/Sphere`（替代 LWJGL2 GLU quadric 形状）。
- **`debug/`**：`GLSMDebug`（详细 draw 日志）、`GLSMPerfDebug` + `GLSMPerfDebugHooks`
  （周期性能采样；stats provider 列表扩展点允许 celeritas-common 注册调度器/遮挡剔除
  统计段，主模组注册 fastLit/shadow 段）、`GpuCheckpointTracker`（GPU fence 环形检查点）。
- **`dsa/`**：`DSAAccess` 接口 + `DSACore/DSAARB/DSAEXT/DSAUnsupported` —— Direct State
  Access 分层实现。
- **`ffp/`**（固定管线模拟）：`ShaderManager`、`Program`/`ProgramUniformState`、
  `VertexKey`/`FragmentKey`、`Vertex/Fragment/GeometryShaderGenerator`、`ShaderCache`、
  `Uniforms` —— 由固定管线状态生成 shader。
- **`hooks/`**：`GLSMHooks`（静态事件总线集合：纹理绑定/删除、program 切换、blend、fog、
  lightmap 等）、`DeferredAlpha/Blend/DepthColorHandler`（延迟状态应用）、`GLSMConfig`/
  `GLSMInitConfig`、`GpuCommandRecorder/Phase/Type`、`GpuCheckpointType`；`hooks/events/`
  各状态变更事件类。
- **`loading/`**：`TransformerNarrower`（coremod transformer 收窄规则）、
  `EcosystemNarrowRules`、`DependencyVerifier`。
- **`recording/`**（显示列表录制）：`CommandRecorder`、`ImmediateModeRecorder`、
  `CommandBuffer`、`CommandBufferExecutor/Processor`、`CompiledDisplayList`、
  `DisplayListVBO`/`DisplayListVBOBuilder`、`GLCommand`、`AccumulatedDraw`；
  `recording/commands/` —— `DisplayListCommand` 接口及 `MultMatrixCmd`/`TexImage2DCmd`/
  `TexSubImage2DCmd` 等具体命令。
- **`redirect/`**：`GLSMRedirector`（GL 调用重定向）、`RedirectorDebugOptions`。
- **`shadow/`**：`InternalShadowRenderingState` —— shadow pass 矩阵与渲染开关状态。
- **`stacks/`**：全部状态栈（Alpha/Blend/Boolean/Color4/ColorMask/Depth/Fog/Integer/Light/
  LightModel/Line/Material/MatrixMode/Point/Polygon/Stencil/TextureBinding/
  TextureUnitBoolean/ViewPort/Vec3f/Vec4f），接口 `IStateStack`。
- **`states/`**：与 stacks 一一对应的状态值对象（`ISettableState`），另有
  `TextureUnitArray`、`GenerationTrackedState`、`TexEnvState`、`TexGenState`、
  `VertexAttribState`、`ClipPlaneState`。
- **`streaming/`**（流式 VBO）：`StreamingBuffer` 接口、`OrphanStreamingBuffer`、
  `PersistentStreamingBuffer`、`GlFence`、`StreamingUploader`、`TessellatorStreamingDrawer`。
- **`texture/`**：`TextureInfo`/`TextureInfoCache` —— 跨 GL context 共享的服务端纹理状态缓存。
- **`com.gtnewhorizons.angelica.compat/`**：`ModStatus`；`compat/mojang/`：1.12 下补齐
  mojang 新 API（`Camera`、`ChunkPos`、`ChunkOcclusionData(Builder)`、`Constants` 等）；
  `compat/iris/` 与 `client/rendering/` 为空目录。
- **`com.gtnewhorizons.angelica.rendering/`**：`AngelicaRenderQueue`、`RenderingState`
  （全局渲染状态单例）。
- **`com.mitchej123.glsm/`**（Angelica 遗留 API）：`GLStateManagerService`/
  `RenderSystemService` + `*ServiceProvider` + `impl/PassThrough*`（直通实现）。
- **`net.minecraftforge.eventbus.api/`**：精简版 `EventBus` + `MutableEvent` —— 不依赖完整
  FML eventbus 的小型总线，供 `GLSMHooks` 使用。

LWJGL 后端（并入本子项目）：

- **`src/lwjglCommon`（`com.mitchej123.lwjgl`）**：LWJGL2 风格 API 抽象 —— `LWJGLService`
  接口（GL 版本/扩展查询、buffer 操作、指针大小）、`LWJGLServiceProvider`、`MemoryStack`
  抽象、`GL11`~`GL44`/`GLExtension` 常量转发类、`DebugExtension`、`DebugMessageHandler`。
- **`src/lwjgl3`**：LWJGL3 实现 —— `com.mitchej123.lwjgl.lwjgl3.LWJGL3Service`、
  `LWJGL3MemoryStack`、`LWJGL3DebugSupport`；`com.gtnewhorizons.angelica.lwjgl3.
  Lwjgl3GLRenderBackend` —— glsm `RenderBackend` 的唯一实现，经
  `META-INF/services/...glsm.backend.RenderBackend` 注册，由 `BackendManager` ServiceLoader 加载。

## GTNHLib/ 子项目（渲染原语库，`com.gtnewhorizon.gtnhlib`）

依赖链最底层，不依赖任何子项目。

- 顶层：`GTNHLib`（MODID 常量）、`ClientProxy`（mc 单例）。
- **`asm/`**：`ClassConstantPoolParser` —— ASM ClassReader 派生的常量池解析器（coremod 用）。
- **`blockpos/`**：`BlockPos` —— 可变 int 三元组（避免分配）。
- **`bytebuf/`**：LWJGL MemoryStack 生态移植 —— `MemoryStack`、`MemoryUtilities`、
  `APIUtil`、`Checks`、`MemoryManage`、`MultiReleaseMemCopy`、`MultiReleaseTextDecoding`、
  `Pointer`、`StackWalkUtil`。
- **`client/opengl/`**：`GLCaps`（能力探测）、`FBOFunctions`、`UniversalVAO`
  （GL3.0/APPLE/ARB 三路回退的 VAO 封装）。
- **`client/renderer/`**：`TessellatorManager`（tessellator 栈与捕获管理）、
  `DirectTessellator`（直接模式）、`CallbackTessellator` + `DirectDrawCallback`、
  `ITessellatorInstance`、`RuntimeOptionsBridge`（direct memory 开关桥）。
- **`client/renderer/cel/`**（Celeritas 移植的渲染模型 API）：`api/util`（`ColorABGR`/
  `ColorARGB`/`ColorU8`/`NormI8`/`ColorMixer`）、`model/line`、`model/primitive`、
  `model/quad`（`ModelQuad`/`ModelQuadView`/`ModelQuadViewMutable`）+ `properties`
  （`ModelQuadFacing/Flags/Orientation/Winding`）、`model/tri`、`polyfill/Maps`、`util`
  （`MathUtil`、`ModelQuadUtil`）。
- **`client/renderer/postprocessing/`**：`PostProcessingManager`/`PostProcessingHelper`/
  `PostProcessingBridge`（bridge 注入点）、`CustomFramebuffer`、`SharedDepthFramebuffer`
  （共享深度缓冲）、`DepthTextureProvider`、`I3DGeometryRenderer`；`shaders/` 子包：
  `PostProcessingRenderer` 基类 + `BloomShader`/`BloomTonemapShader`/`UniversiumShader`。
- **`client/renderer/shader/`**：`ShaderProgram`、`AutoShaderUpdater`、`IShaderReloadRunnable`。
- **`client/renderer/stacks/`**：`IStateStack`、`Vector3dStack`。
- **`client/renderer/textures/`**：`TextureAtlas`（静态图集）、`TextureLoader`、
  `AnimatedTexture`、`SpriteAnimationMetadata`。
- **`client/renderer/vao/`**：`VAOManager`（可变/不可变缓冲分类创建）、`IVertexArrayObject`、
  `BaseVAO`、`IndexedVAO`、`IndexBuffer`、`VaoFunctions`、`VertexArrayUnsupported`、
  `VertexBufferFactory`/`VertexBufferType`。
- **`client/renderer/vbo/`**：`VertexBuffer`（glBufferData 可变 / glBufferStorage 不可变
  双模式）、`VBOManager`、`IVertexBuffer`、`IModelCustomExt`。
- **`client/renderer/vertex/`**：`VertexFormat`/`VertexFormatElement`、`DefaultVertexFormat`、
  `VertexFlags`、`VertexOptimizer`；`writers/` 子包：`IVertexAttributeWriter` +
  Position/Color/Texture/Normal/Light 五种属性写入器。
- **`compat/`**：`Mods`（模组存在性检查）。
- **`core/`**：`GTNHLibCore`（isObf 判断）。
- **`util/`**：`ObjectPooler`（对象池）；`util/font/`：`IFontParameters`（`actinium$` 前缀
  的字体字形参数 mixin 注入接口）。
- `client/model/`：空目录。

## Mixin 配置清单

`src/main/resources/` 下的 early/conditional 配置与 1 个门控声明：

| 配置 | 阶段 | 用途 |
| --- | --- | --- |
| `mixins.actinium.vintage.json` | early（MixinEarly） | 原版注入全量：`mixin/vintage` 下 60+ 类 |
| `mixins.actinium.iris.json` | early（MixinEarly） | `mixin/core/terrain.BufferBuilderMixin` + `mixin/core/vertex.MixinVertexFormat` + `mixin/features/iris` 全部（含 startup） |
| `mixins.actinium.hbm.early.json` | early（MixinEarly） | `MixinTileEntityRendererDispatcherLightmap` —— 原版 TE dispatcher 的世界 lightmap 同步（必须 early：目标类会被核心 mod 的 ASM 变压器在 late 窗口前拉起，late 配置会以 `MixinTargetAlreadyLoadedException` 中止启动；注入体按 `isHbmInstalled()` 门控） |
| `mixins.actinium.gibbed.json` | late/conditional（gibbed） | `BasicGibMixin` |
| `mixins.actinium.ichunutil.json` | late/conditional（ichunutil） | `mixin/mod/ichunutil` 3 类 |
| `mixins.actinium.lumenized.json` | late/conditional（lumenized） | `mixin/mod/lumenized` 3 类 |
| `mixins.actinium.revoui.json` | late/conditional（neofontrender_ui_enhancements） | `mixin/mod/revoui` 3 类 |
| `mixins.actinium.betterfoliage.json` | late/conditional（betterfoliage） | `MixinChunkBuilderMeshingTaskBetterFoliage` |
| `mixins.actinium.ccl.json` | late/conditional（codechickenlib） | `MixinGlStateTracker` |
| `mixins.actinium.hbm.json` | late/conditional（hbm） | `MixinRenderUtil`、`MixinItemRenderWeaponBase`（Sedna 武器手部深度：跳过 Iris 手部 pass 内的深度清理，改用 `HandRenderer.DEPTH` 投影压缩） |
| `mixins.actinium.littletiles.json` | late/conditional（littletiles） | `MixinChunkBuilderMeshingTaskLittleTiles`、`MixinTileEntityRenderManager`（TE 顶点缓存注入 section 网格 + 缓存构建完成触发 section 重建） |
| `mixins.actinium.voxelmap.json` | late/conditional（voxelmap） | `mixin/mod/voxelmap` 3 类（GLUtils/GLShim/renderMap，小地图 CPU 路径与 HudCaching alpha 保护） |

门控映射在 `mixins.actinium.conditions.properties`（mixin loader 不认 json 自定义字段），
由 `MixinLate` 读取，对应 mod id 存在才加载。`META-INF/actinium_at.cfg` 访问转换器将
GlStateManager/EntityRenderer/TextureMap 等私有成员公开。

空目录占位（规划预留，无代码无配置）：`compat/rfp2`、`mixin/mod/stellarcore`、
`mixin/dediag`、`mixin/vintage/diagnostics`。

## 跨模块边界与桥接机制

子项目对根项目零引用，交互全部通过静态注册点/接口完成：

- **ShaderProvider 机制**（核心）：celeritas-common 定义 `api.shader.ShaderProvider` +
  静态 `ShaderProviderHolder`；shader 侧 `IrisCeleritasShaderProvider` 实现之，启动时
  `ShaderProviderHolder.setProvider(...)` 注册（根项目 `Shaders.java` 可置 null 卸载）；
  celeritas 通过它查询光影是否启用、是否 shadow pass。
- **WorldRendererCompatBridge**（shader 侧）：静态 Provider 注册点，根项目 `Actinium.java`
  注册 `ActiniumWorldRenderer::instanceNullable`，供 iris 阴影 pass 获取宿主世界渲染器。
- **ShaderModBridge**（celeritas 侧）：用 MethodHandle 探测 `net.irisshaders.iris.api.v0.
  IrisApi` 与 `me.cortex.nvidium.Nvidium`，避免编译期依赖 shader。
- **其它 Bridge/Provider**：`IrisDebugOptions.Bridge`（根项目实现）、`ViewportProvider`、
  `RenderDebugHooks`、`DiffuseProvider`/`LightPipelineProvider`（celeritas 内部）、
  `IrisItemLightProvider`（iris API）、`PostProcessingBridge`、`RuntimeOptionsBridge`、
  `GLSMPerfDebugHooks`。

新增桥的登记与约定见 `docs/bridges.md`。

## 模块所有权

多人协作下，每个子项目有明确的默认 owner 与变更流程：

| 模块 | 职责边界 | 变更流程 |
| --- | --- | --- |
| 根项目 `src/main` | 生命周期、配置、兼容层、mixin 注册 | 兼容改动经 `docs/compat/*.md` 留痕；mixin 遵循「Mixin 组织约定」 |
| `shader/` | 光影管线（Iris 移植）：解析、GLSL 变换、render targets、uniform、pipeline | 上游同步走 `docs/upstream-maintenance.md`；对根项目零引用（架构测试强制） |
| `celeritas-common/` | 区块构建、上传、裁剪、绘制 | 渲染算法改动需 dev 回归 + 兼容矩阵更新 |
| `glsm/` | GL 状态跟踪、重定向、FFP 模拟 | 状态语义改动影响面大，需 DE/光影双场景回归；第三方包边界见 upstream-maintenance.md |
| `GTNHLib/` | 渲染原语库（tessellator/VBO/VAO/后处理） | API 改动需同步全部调用方；新增原语须先评估与现有实现的重叠 |

通用规则：改接口需 cross-review；新增桥遵循 `docs/bridges.md` 约定；PR 原则上只动一个
子项目 + 必要桥接。

Mixin 组织约定：

- **mixin 类只做注入**，业务逻辑放在 mixin 包之外的实现类（`compat/`、功能包或工具类）。
- **注入接口与注入类分离**：访问器/调用器按 `core.terrain.AccessorEntityRenderer` 模式
  组织；跨类暴露的注入能力，接口放对应 `mixinterface/` 子包，实现留在 mixin 类。
- **mixin 包隔离规则**：mixin 包内的辅助类（含内部类/静态嵌套类）不得被 mixin 目标类或
  其调用方直接引用——会触发 `IllegalClassLoadError`（该检查**仅在非 dev 环境强制**，
  dev 验证无法覆盖；2026-08-12 生产崩溃实例：`MixinGlStateTracker$Saved`）。
  辅助实现一律放 mixin 包外。

## 兼容层总览

- Distant Horizons：API event、framebuffer、depth texture、LOD shader 和条件 Mixin。
- Fluidlogged API：world slice 中的 fluid state 快照与渲染。
- Gibbed：模型渲染快速路径及条件 Mixin。
- ModernUI 和若干 HUD/地图模组：GUI scale 或编译期兼容接口。
- Celeritas 系 addon：直接适配 Actinium 主实现（选项 API 位于 `dhj.embeddedt.embeddium.api`，
  renderer 绑定面由 `VintageBlockRendererBindingContractTest` 锁定）。
  - **类路径重构（2026）**：`celeritas-common` 的 `org.embeddedt.embeddium.{api,impl}.*` 已整体
    迁至 `dhj.embeddedt.embeddium.*`，打包时 joml 的 relocate 目标同步为
    `dhj.embeddedt.embeddium.impl.shadow.joml`，以免与同一 classpath 上上游
    Embeddium/Celeritas jar 内的同名类冲突。仍按上游 `org.embeddedt.embeddium.*` 二进制名
    绑定的 addon（如 HBM-CE 的 `MixinRenderSectionManager`、celeritasleafculling 的
    `VintageBlockRendererMixin`）必须随 Actinium 迁移，否则 `@Redirect` / `@Shadow` 匹配不到
    目标。
  - 例外：探测标记类保持 `org.taumc.celeritas.core.CeleritasLoadingPlugin` 不变 —— LoliASM
    用精确类名 `Class.forName` 探测，改名即失效，见 [compat/censoredasm.md](compat/censoredasm.md)。

兼容代码应由模组存在性检查保护。引用外部类的 Mixin 必须放在 late/conditional 配置中，
避免未安装对应模组时触发类加载。

## 测试结构

`src/test` 按功能分包：

- `com.dhj.actinium` 自身逻辑：`api/render/terrain`（BlockQuadTransformerHolder 注册分发）、
  `architecture`（包依赖方向约束，如 DependencyDirectionTest）、`compat/*`（各兼容层逻辑）、
  `config`（运行时选项系统属性覆盖）、`debug`（启动 debug 配置、core profile、GL 版本、
  flight 记录含 crash 子进程）、`gui`（窗口模式）、`loading/fml/transformers`（Mac
  transformer 注入逻辑）、`mixin`/`mixins`（MixinConfigurationTest 配置覆盖校验、
  MixinLate 门控逻辑）、`render`（EndPortal、流式绘制、投影纹理坐标、光照缓存等）。
- 嵌入第三方源码的测试命名空间：`com.gtnewhorizons.angelica.*`（glsm：shader/uniform
  兼容、ffp 生成器、GPU 诊断、streaming）、`dhj.embeddedt.embeddium.*`（选项/区块渲染）、
  `net.coderbot.iris.*` 与
  `net.irisshaders.iris.*`（shaderpack 解析、pipeline transform、uniforms、阴影）。
- `net/minecraft/client/renderer/culling/ClippingHelperImpl.java` 为测试用 stub。

普通单元测试适合覆盖属性解析、GLSL 变换、ID 映射、fallback 和打包契约。
OpenGL 状态恢复、画面正确性、驱动差异和性能仍需运行客户端验证，记录到兼容矩阵
（`docs/compatibility-matrix.md`）。

## 文档索引

- `docs/bridges.md`：跨模块桥的登记与新增约定。
- `docs/compatibility-matrix.md`：光影包/模组兼容现状。
- `docs/roadmap.md`：当前路线图。
- `docs/upstream-maintenance.md`：第三方上游同步流程。
- `docs/project-structure-plan.md`：项目结构重构计划与最终结构。
- `docs/compat/*.md`：单模组兼容研究记录（如 `draconic-evolution.md`）。
- `docs/archive/`：历史方案归档（仅追溯决策）。
