# SDL-GPU 后端移植评估与计划

> 目标：把 Angelica 的 `sdl-gpu` 子项目（SDL3 GPU 渲染后端）移植到 Actinium。
> 状态：调研完成，待实施。分支：`research/sdl-gpu-port`。
> 日期：2026-08-14

## 一、sdl-gpu 是什么

Angelica 的**可选渲染后端**（`SDLGPURenderBackend`，单类 3787 行），把 OpenGL 状态机与
绘制调用翻译为 **SDL3 GPU API**（Vulkan/Metal/D3D12 统一 GPU 接口）：

- 着色器管线：GLSL → taumc glsl 解析 → shaderc 编 SPIR-V → SPIRV-Cross 转
  HLSL/DXBC（D3DCompiler）/MSL，支撑 D3D12/Metal 后端运行 GLSL 着色器；
- 帧管理：多帧并行 + fence + 独立 presenter 线程（低延迟 vsync：on/mailbox/off）；
- 附带：compute/voxelization、持久映射缓冲、传输线程、splash 分发、DebugLabels。

来源：GTNH/Angelica PR #1993（squash，+60148 行/647 文件，含 TESR batching 与 Tracy
集成；2026-08-08 合入，此后仍在活跃演进）。

## 二、规模

| 维度 | 数据 |
|---|---|
| 主类 | 65 个（resource 14 / shader 9 / frame 7 / pipeline 7 / sampler 5 / device 5 / util 4 / 顶层 4 / compute 3 / compat 2 / dxbc 2 / cross 1 / splash 1 / msl 1） |
| SDLGPURenderBackend | 3787 行 |
| 测试 | 104 个 |
| 外部依赖 | `org.lwjgl:lwjgl-sdl` / `lwjgl-spvc` / `lwjgl-shaderc`（LWJGL 官方 maven + natives）；taumc glsl（`org.taumc.glsl`，Actinium 已有）；lwjgl3ify（仅 2 文件）；retrofuturabootstrap（1 文件）；celeritas（2 文件） |

## 三、契合度（有利因素）

1. **架构同源**：Actinium 的 `RenderBackend` 抽象与 `BackendManager` 已存在，
   `GLStateManager` 全量路由（"route everything through
   `BackendManager#RENDER_BACKEND`"）——接入点天然匹配；
2. **接口差距可控**：Actinium `RenderBackend` 220 方法 vs Angelica 316，缺 ~97 个
   （详见下文清单），主要为进阶能力；
3. **taumc glsl 已就绪**：`org.taumc.glsl` 已在 Actinium 的 shader/ 与 glsm 使用；
4. **native 依赖可取**：`lwjgl-sdl/spvc/shaderc` 官方 maven 直接可用；
5. **许可同源**：Angelica 为 LGPL（GTNH relicense），与 Actinium 现有 glsm 移植的
   许可处理一致（已在 credits 声明 Angelica）。

## 四、障碍与差异

### 4.1 glsm 框架补齐（最大前置）

`RenderBackend` 缺 Angelica 的 ~97 个方法（Actinium 有 220，Angelica 316）：

- **帧/交换链生命周期**：`onPostWindowCreate`、`handleMakeCurrent`、
  `handleReleaseContext`、`handleSwapBuffers`、`onPreSwapchainInvalidatingChange`、
  `onRenderThreadReleased`、`onPersistentBufferWrite`、`getTransferDebugInfo`、
  `hasSwapchainBackpressure`、`gateAnchorsNextFrameStart`、
  `wantsDisplayUpdateGateTiming`、`recordFrameGate`、`lastFrameGateNanos/EndNanos`
- **VSync**：`setVSyncEnabled/Mode/Preference`、`applyVSyncMode`、`publishVSyncMode`、
  `getEffectiveVSyncMode`、`preferredTearFreeMode`、`supportsVSyncMode`、
  `queryDisplayRefreshRateHz`、`getDisplayRefreshRateHz`、`refreshHzFrom`
- **能力标志**：`isCurrent`、`isIndirectRequired`、`supportsGeometryShaders`、
  `isSDLGPU`、`framebufferCompletenessIsMeaningful`、`isAnisotropicSupported`、
  `supportsGpuDrivenCulling`、`supportsGpuProfiling`
- **FBO/RBO**：`genRenderbuffers`、`bindRenderbuffer`、`renderbufferStorage`、
  `renderbufferStorageMultisample`、`framebufferRenderbuffer`、`isRenderbuffer`、
  `isFramebuffer`、`isTexture`、`isSampler`、`isQuery`、`getTextureParameterf`
- **查询/同步/调试**：`genQueries/deleteQueries/beginQuery/endQuery`、
  `getQueryObject*`、`queryCounter`、`getQueryResult64`、`getGpuTimestamp`、
  `waitSync`、`getSynci`、`debugMessage*`、`getDebugMessageLog`
- **进阶绘制**：`drawRangeElements(BaseVertex)`、`multiDrawArrays`、
  `multiDraw*IndirectCount`、`primitiveRestartIndex`、`provokingVertex`、
  `pointParameterf/i`、`polygonOffsetClamp`、`uniform3fv/4fv`、
  `getIndexedBufferBinding`、`getIntegerIndexed`、`bindBufferRange`、
  `getUniformBlockIndex`、`uniformBlockBinding`、`mapBufferRangeAddress`、
  `flushMappedBufferRange`、`clearBufferData`、`bindFragDataLocation`、
  `specializeShader`、`getActiveAttrib`
- **voxelization**：`beginVoxelizationBatch`、`voxelizeRange`、`endVoxelizationBatch`、
  `bindVoxelizationRegion`
- **文件拖放**：`supportsFileDrop`、`startFileDrop`、`stopFileDrop`、`pollDroppedFiles`
- 支撑类型：`VSyncMode` 枚举、`GLDebugMessageListener` 等需一并引入

配套：`BackendManager` 需要生命周期编排（backend 选择/初始化/窗口创建后回调），
`GLStateManager` 需要 `replayStateToBackend`、`getMainThread`、`isSplashComplete`、
`markSplashComplete`、`getInitConfig` 等（部分可能已有，需核对）。

### 4.2 Display 桥（lwjglxx 环境）

Angelica 侧经 lwjgl3ify 的 `DisplayEvents`/`DisplayWindowContext`/三参
`Display.create`/`MainThreadExec` 接管窗口并禁用 GL 上下文创建（`setCreateGLContext(false)`）。
crl/lwjglxx **没有这些 API**，且窗口由 GLFW 创建。

SDL_GPU 支持外部窗口互操作：`glfwGetWin32Window(glfwWindow)` 取平台句柄 →
`SDL_CreateWindowFrom` 包装为 SDL_Window → `SDL_ClaimWindowForGPUDevice`。事件循环
仍由 GLFW/lwjglxx 驱动，SDL 仅用于 GPU 呈现。

需要在 `MixinMinecraftCoreProfileDisplay`（已接管 `createDisplay`）中插入 SDL 接管：
建窗后取平台句柄包装并 claim，且绕过 lwjglxx 的 GL 上下文创建（lwjglxx 无
`setCreateGLContext(false)`，需在接管流程中处理）。

> 上述互操作方案已被取代：GLFW 窗口带 GL context，GPU 设备无法认领。当前方案是
> **bridge 直接提供原生 SDL 窗口**（`Launch.blackboard["actinium.sdlgpu.sdlWindow"]`），
> 启用路径与窗口交接契约见 `sdl-gpu-enable-path.md`。

### 4.3 其他适配

- `retrofuturabootstrap.MainStartOnFirstThread`（presenter 线程，1 文件）→ Cleanroom 等价
  或剥离；
- celeritas 交叉引用（`SDLGPUGate`/`SDLGPULWJGLService`，2 文件）→ 核对 Actinium
  celeritas-common 或剥离该耦合；
- `SystemProperties.USE_SDL_GPU` 等开关 → Actinium 配置入口。

## 五、移植计划（模块化，逐个提交）

1. **docs**：本文档（提交）；
2. **glsm 框架对齐**：`RenderBackend` 补齐 + `VSyncMode`/支撑类型 +
   `BackendManager` 生命周期 + `GLStateManager` 配套（提交）；
3. **Display 桥**：lwjglxx 的 SDL 窗口接管（提交）；
4. **sdl-gpu 主体**：复制 Angelica `sdl-gpu` 模块源码 + 构建脚本适配
   （`lwjgl-sdl/spvc/shaderc` 依赖、natives、`org.taumc.glsl`）+ 配置开关（提交）；
5. **测试修 bug**：构建通过后 runClient 验证（`USE_SDL_GPU=true` 选择 SDL GPU 后端、
   无 GL 上下文崩溃、OpenGL 可回退），修复问题并提交。

## 六、验证方式

- 构建：`./gradlew check --no-daemon`；
- 单测：sdl-gpu 自带 104 测试 + glsm 现有测试；
- 游戏内：runClient `-DuseSdlGpu=true`（或等价开关）启动，确认 BackendManager 选择
  "SDL GPU" 后端、渲染正常、切换回 OpenGL 后端正常；
- 回归：现有 OpenGL 路径（默认）不受影响。

## 七、风险

- 上游（GTNH Angelica）持续演进，接口可能变化——移植后需跟进；
- SDL_GPU 在 1.12.2 的完整 GL 状态机覆盖（immediate mode、FFP 模拟）依赖
  glsm 后端路由的完备性；
- 与 Cleanroom lwjglxx 的 Display 生命周期交互（窗口重建、全屏切换）需实测。
