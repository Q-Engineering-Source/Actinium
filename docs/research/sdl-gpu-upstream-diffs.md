# sdl-gpu 相对 Angelica 上游的修改清单

> 状态：进行中（2026-08-15）；**GLFW 适配部分已于 2026-08-31 撤除**（见条目内
> 「2026-08-31」标注，撤除详情见
> `docs/research/sdl-gpu-cleanroom-glfw-compat.md`）；**上游 #2025（SDL presenter
> fixes and frame pacer adjustments，`b2f597a7`）已于 2026-08-31 同步**；
> **上游 #2065 / #2086 / #2087（`961b23fd` / `2845c9e0` / `3b4ebcf6`）的
> sdl-gpu 端口（Phase 1）已于 2026-10-11 同步**（详见「上游同步状态
> （2026-10-11，Phase 1）」）；**上游 #2095 / #2099 / #2096 / #2104
> （`81b82748` / `a07cecb8` / `26c161f0` / `3876f7e6`，Phase 2）已于 2026-10-11
> 同步**（详见「上游同步状态（2026-10-11，Phase 2）」）；**上游 #2124 / #2133 /
> #2138 / #2142（`9dd60f6a` / `1babfd96` / `44c930f4` / `a8c29fa3`，Phase 3）已于
> 2026-10-11 同步 sdl-gpu 侧**（详见「上游同步状态（2026-10-11，Phase 3）」；
> 该阶段的 glsm 状态栈 COW / per-drawable context 重构**未同步**，原因见同一节）。
> **上游 #2153 / #2157（`e17b45c8` / `7bb5bfd4`，Phase 4）已于 2026-10-11 同步**
> （详见「上游同步状态（2026-10-11，Phase 4）」）；**上游 #2167 / #2188 / #2191
> （`c10d30b2` / `80e5b544` / `1587a12c`，Phase 5）已于 2026-10-11 同步**
> （详见「上游同步状态（2026-10-11，Phase 5）」）。
> 本文档
> 逐条记录 Actinium 的 `sdl-gpu` 模块相对 Angelica 上游（`D:\Code\Angelica`，
> 运行于 lwjgl3ify/GTNH 环境）的**代码差异及其理由**。多数差异源于运行环境不同
> （lwjgl3ify vs Cleanroom/lwjglxx）；部分改动（如 persistent-buffer 同步）与
> 窗口无关，是后端本身的修复，应长期保留。
>
> **当前状态**：SDL GPU 后端未注册进 `RenderBackend` service 文件（启用路径断开），
> 模块源码与测试保留。

## 环境差异总览

| 方面 | Angelica 上游（lwjgl3ify） | Actinium（Cleanroom/lwjglxx） |
|---|---|---|
| 窗口创建 | `DisplayEvents.setCreateGLContext(false)` 抑制 GL context，正常 `Display.create` | lwjglxx 无该 API，**总是**创建 GLFW+GL context 窗口；SDL 无法认领 → 曾自建 GLFW_NO_API 窗口（**2026-08-31 撤除**，当前后端未接线） |
| SDL 窗口包装 | `SDL_CreateWindowFrom`（绑定提供） | lwjgl-sdl 3.4.1 **无** `SDL_CreateWindowFrom` → `SDL_CreateWindowWithProperties` + `SDL_WINDOW_EXTERNAL`（`Device.claimPlatformWindow` 保留，调用方已随适配撤除） |
| SDL 视频初始化 | lwjgl3ify 窗口后端初始化 | 需自行 `SDL_InitSubSystem(SDL_INIT_VIDEO)` |
| Display 静态状态 | lwjgl3ify 维持 | 曾需 VarHandle 镜像 lwjglxx `Display` 字段（**2026-08-31 撤除**） |
| 输入回调/队列 | lwjgl3ify 注册 GLFW 回调 | 曾需自建回调 + 补 poll（**2026-08-31 撤除**） |
| 窗口线程执行器 | `MainStartOnFirstThread`（retrofuturabootstrap） | 无该依赖 → `Runnable::run`（内联） |
| GLCapabilities 注入 | `GLCapabilitiesOverride.set`（lwjgl3ify） | `Lwjgl3GLCapabilitiesShim.installOnCurrentThread`（自建 shim） |
| swapchain 失效事件 | `DisplayEvents.addPreSwapchainInvalidatingChangeListener` | 无事件总线（TODO 注释），回调挂接缺失 |

## 修改清单

### 1. `SDLGPUGate` —— 窗口接管改为自建 GLFW_NO_API 窗口

> **2026-08-31：已撤除。** Gate 收缩为设备探测与可用性入口
> （`device()` / `probe()` / `isActive()` / prewarm 等），`createSDLGPUDisplay` /
> `fallBackToGL` / `rememberIcons` 及其状态机已删除。以下为历史记录。

- 上游：`registerListeners()` + `DisplayEvents.setCreateGLContext(false)` +
  `Display.create(...)` 正常走 lwjgl3ify 窗口创建，随后用 SDL 认领。
- Actinium：`createSDLGPUDisplay()` 自建 `glfwCreateWindow(GLFW_NO_API)` →
  `SDLGPUDisplayBridge.adoptWindow`（镜像 Display 状态）→ 取平台句柄（HWND/X11）
  → `Device.claimPlatformWindow`。
- 理由：Cleanroom 的 lwjglxx 没有 `DisplayEvents`，也无法在创建时抑制 GL context；
  Vulkan 认领带 GL context 的窗口会报 `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`。
- **窗口尺寸用 `Minecraft.displayWidth/Height`，而不是显示器 vidMode**：Minecraft 的
  framebuffer/视口/最终 blit 全部按 `displayWidth/Height` 尺寸计算，vidMode 尺寸的
  窗口会导致画面只占左上角（852×480 vs 2560×1600 的典型错位）。
- macOS 未接线（TODO 注释：Metal layer property）。

### 2. `SDLGPUDisplayBridge`（新增文件）—— lwjglxx 兼容桥

> **2026-08-31：lwjglxx 适配部分已撤除**，文件收缩回上游形态（VarHandle 安装
> `Display.drawable` 为 `SDLDrawable`、`present()` / `releaseRenderThread()` /
> SDL drawable 判断）。以下 1-4 项为历史记录。

- 上游无此文件（lwjgl3ify 环境 Display 状态与输入天然一致）。
- Actinium 职责：
  1. VarHandle 镜像 lwjglxx `Display` 静态字段（handle/created/width/height/
     framebufferSize/title/resizable）**以及 LWJGL2 兼容壳的 `Display$Window.handle`**
     （壳的 `getWindow()` 供 DWM/taskbar 集成取句柄；mixin universe 看不到壳类，
     只能反射镜像）；
  2. 自建 GLFW 回调（cursor/button/scroll/key/char/**focus**）转发 lwjglxx 公开
     输入 API；**focus 回调镜像两个 `displayFocused` 字段**（lwjglxx 的
     `Display.isActive()` 只读该字段且从不更新；不镜像则 `pauseOnLostFocus` 会
     自动弹出 Esc 菜单）；
  3. 补调 `Mouse.create()` / `Keyboard.create()`；
  4. `flushPendingMove()`：cursor 移动合并为每帧 1 个事件。
- 理由：绕过 lwjglxx 窗口创建 = 绕过了它的窗口初始化；不补齐则 `Display.*`
  查询失效、输入全死。细节与撤除计划见 `sdl-gpu-cleanroom-glfw-compat.md`。

### 3. `Device` —— SDL 初始化 + 平台窗口认领

- `createDevice()` 里补 `SDL_InitSubSystem(SDL_INIT_VIDEO)`（上游由 lwjgl3ify
  初始化 SDL 视频子系统；lwjglxx 桥用 GLFW，SDL 从未初始化）。
- 新增 `claimPlatformWindow(long)`：`SDL_CreateProperties` + `SDL_WINDOW_EXTERNAL`
  属性 + `SDL_CreateWindowWithProperties` 包装平台句柄，再 `claimWindow`；
  新增 `sdlWindowHandle` 字段。替代上游的 `SDL_CreateWindowFrom`（lwjgl-sdl
  3.4.1 绑定缺失）。**2026-08-31：实现保留，但其唯一调用方（Gate 自建窗口路径）
  已随适配撤除，重接窗口路径时复用。**

### 4. `SDLDrawable` —— 无 GL context 的 drawable

- 上游：`super((ContextGL) null)`；`getWindow()` 返回 `Display.getWindow()`。
- Actinium：`super()`；`getWindow()` 返回 `SDLGPUGate.device().getSdlWindowHandle()`。
- 理由：Cleanroom 的 `SharedDrawable`/`SplashProgress` 路径需要一个非 null 的
  Drawable；窗口句柄必须是 SDL 认领的那个。

### 5. `SDLGPULWJGLService` —— 基类与转发目标变化

- 上游：`extends LWJGLService`（Celeritas 的抽象类），`getPriority()` 检查
  `USE_SDL_GPU && isSDLGPUAvailable && isEngaged`（priority 200），部分调用走
  `GLStateManager`。
- Actinium：`implements LWJGLService`（**自建接口**，`src/lwjglCommon`——Cleanroom
  环境没有 Celeritas 的 lwjglCommon 源依赖），**全部转发
  `BackendManager.RENDER_BACKEND`**（不经过 `GLStateManager` 中间层），并补
  `glUniform3i/glUniform4i` 转发。
- 理由：SDL GPU 模式下 fabricate 的 GLCapabilities 只有 SENTINEL 函数指针，任何
  走真实 GL 的调用都会崩溃；统一直连后端最稳妥。
- `LWJGLServiceProvider`（`src/lwjglCommon`）在 `USE_SDL_GPU` 时反射实例化
  `SDLGPULWJGLService`，否则用真实 GL 的 `LWJGL3Service`。**2026-08-31：该
  `USE_SDL_GPU` 分支已随 GLFW 适配撤除，`SDLGPULWJGLService` 文件保留但当前
  不可达。**

### 6. `SDLGPURenderBackend` —— 环境适配 + 后端修复

- `isActive()`：上游检查 `isEngaged()`；Actinium 不检查——后端选择发生在窗口
  创建之前（`BackendManager` 在 `GLStateManager` 类初始化时加载），`isEngaged()`
  此时必为 false。窗口认领曾在 `MixinMinecraft` 的 createDisplay 钩子里进行
  （**2026-08-31 随 GLFW 适配撤除**，`isAvailable()` 注释已同步更新）。
- swapchain 失效监听：上游 `DisplayEvents.addPreSwapchainInvalidatingChangeListener`
  → Actinium 仅留 TODO（lwjglxx 无事件总线），`onPreSwapchainInvalidatingChange`
  的调用方缺失。
- `Presenter` 执行器：上游 `MainStartOnFirstThread.instance()` → `Runnable::run`
  （无 retrofuturabootstrap 依赖），present 在线程内联执行。
- GLCapabilities 注入：上游 `GLCapabilitiesOverride.set(caps)` →
  `Lwjgl3GLCapabilitiesShim.installOnCurrentThread(caps)`（Cleanroom 无
  `GLCapabilitiesOverride`）。
- **`blitNamedFramebuffer`：`drawFramebuffer == 0` 时目标纹理用
  `frameManager.finalTarget().colorTexture()`**（SDL 没有默认 framebuffer 的
  FBO 0 语义；上游直接 `getFbo(0)` 返回 null）。
- **`unmapBuffer` 不再释放 persistent mapping**（2026-08-15 修复）：上游行为未
  核验；Actinium 实现以 GL 语义为准——persistent mapping 存活到 buffer 删除或
  `mapBufferRange(persistent)` 重映射。原实现 `unmapBuffer` 无条件
  `removePersistentMapping` + `releasePersistentStaging`，而 glsm 的
  `PersistentStreamingBuffer` 与 Embeddium `MappedStagingBuffer` 持有 persistent
  staging 持续写入，`StreamingUploader` 每帧 map+unmap 会命中并 memFree 仍在
  使用的 staging → **use-after-free native 崩溃**（进世界渲染开始后 1-2 秒，
  `StubRoutines::jbyte_disjoint_arraycopy`）。
- **`releasePersistentStaging` 幂等化**（`PersistentMapping.markReleased`）：多条
  释放路径（remap swap / buffer delete）可能重叠，防止同一 staging 被重复
  `memFree`。
- 新增一次性「first draw/bindFramebuffer」诊断日志与 dropped-draw 细分警告
  （`frameInactive/rpInactive/pipeline/eboMissing/fan` 计数），用于定位绘制丢失。
  （每秒帧统计诊断 `t+Ns frame=... drawsTotal=...` 已随调试结束删除。）

### 7. `FrameManager` / `DrawDispatch` —— present 错误日志 + dropped 细分

- `presentBlit`：acquire 失败 / submit 失败增加 `SDL_GetError()` 日志（上游静默
  `return false`）。
- `FrameState` 增加 dropped 细分字段（`droppedFrameInactive` 等）并在 reset 时清零；
  `DrawDispatch` 的 `prepareIndexedDraw`/`drawTriangleFanAsTriangleList` 各失败分支
  分别计数并打印一次警告（上游 fan 路径静默 return，不计数不告警）。

### 8. `shader/ShaderManager` —— GLSL 保留字重命名兜底

- `shaderSource()` 开头统一 `GlslTransformUtils.renameReservedWords(raw, 460)`。
- 理由：部分调用者（如 vanilla `blur.fsh` 里的 `sample`）不经过
  `GLStateManager.glShaderSource`，直接到后端编译；`sample`/`new` 在 GLSL 4.60
  是保留字，不重命名则 SPIRV 编译失败。重命名幂等（前缀不会再次匹配）。

### 9. `glsm/streaming/PersistentStreamingBuffer`（glsm 模块）—— persistent 写入通知

- `writeAt`/`writeAtStart` 在 memCopy 写 mapped buffer 后调用
  `RENDER_BACKEND.onPersistentBufferWrite(bufferId, offset, size)`。
- 理由：SDL 后端的 `mapBufferRange(persistent)` 返回 **CPU staging**（非真实 GPU
  映射）；不通知后端则 GPU 永远看不到顶点数据 → 所有 persistent 路径（streaming
  drawer 的 GL_QUADS/fan 等）绘制黑屏。OpenGL 后端该调用是空实现，无副作用。
- 这是**后端语义修复**，与 GLFW 兼容层无关，CRL 换 SDL 窗口后仍应保留。

### 10. `MixinMinecraft` 帧钩子 / `MixinGuiScreenSDLInput`

- 帧钩子（`onFrameBegin`/`onFrameEnd` 挂 `runGameLoop` 的 `updateDisplay` 调用前后）
  对齐 Angelica 的 `MixinMinecraft_FrameHook`，**保留**；`updateDisplay` 曾在 SDL
  模式下替换为事件泵（**2026-08-31 已撤除**，还原 vanilla swap 路径）。
- `MixinGuiScreenSDLInput`：SDL 路径下接管 `GuiScreen.handleInput`，按钮事件经
  `@Invoker` 直调 `mouseClicked`（绕过 Forge `MouseClickedEvent` 吞点击）。
  Cleanroom 的 lwjglxx 事件时序下 vanilla 循环 + Forge 事件门会丢点击。
  **2026-08-31：整个 mixin 已删除。**

## 上游同步状态（2026-08-31）

移植快照已包含上游 #1994–#2009 的全部内容；在其后上游仅合入
#2025（`b2f597a7`，SDL presenter fixes and frame pacer adjustments），已同步：

- `FrameManager`：swapchain acquire 改为每进程一次的
  `checkAcquireOnWindowThread()`（deobf 下 fail-fast，
  `DISABLE_SDL_PRESENTER_THREAD` 可关闭）；`presentFinalTarget` 重构出
  `presentSplash`（`SDL_FLIP_VERTICAL`）与统一 `present(...)` 入口；
  `presentOnWindowThread` 带 flipMode；`presenter` 字段 volatile。
- `SDLGPURenderBackend`：splash teardown 延迟到下一帧 `onFrameBegin`
  （`splashTeardownPending` + `finishSplash()`，teardown 时
  `tryDispatch(force=true)` + `presenter.drain()`）；`presenter` /
  `splashTarget` volatile；`handleMakeCurrent` / `handleSwapBuffers` 使用局部
  快照；`lastFrameGateNanos/EndNanos` 删除 `isEngaged()` 门控。
- `Presenter`：删除 `isEngaged()`（splash 门控由 present 路径自身处理），
  `requestPresent` 增加 flipMode。
- `SplashDispatcher.tryDispatch`：增加 `force` 参数（teardown 绕过节流）。
- `glsm/SystemProperties`：`DUMP_SHADERS` 常量改为 `dumpShaders()` 方法 +
  `isDeobf()` 公开（sdl-gpu 的 ShaderManager / MslCrossCompile /
  DxbcCrossCompile 引用同步更新）。
- `glsm/Tracy`：后端 ABI 哨兵检查（`sectionEnter`）+ `init()` 的
  `LinkageError` 捕获，旧版 native 后端不再拖垮进程。
- `SdlFramePlots`：删除 `sdl.gateUs` plot（上游移除）。
- `PresenterTest`：采用上游版本（4 参 `requestPresent` + 新增 splash present
  走窗口线程的覆盖）。

**未同步**（均为 lwjgl3ify 专属或当前不可达，重接时按上游 HEAD 重写）：
`SDLGPULWJGLService` 的 query 计时重构与 `isOfferedAsAvailable` 优先级门控、
`SDLGPUGate` 的 lwjgl3ify 事件接线（`DisplayEvents` / `MainThreadExec`）。

## 上游同步状态（2026-10-11，Phase 1）

已同步上游三个提交：`961b23fd`（Fix tests #2065）、`2845c9e0`（FPS pacer work
#2086，仅 glsm API + sdl-gpu 侧）与 `3b4ebcf6`（Update Celeritas #2087，仅
sdl-gpu 主源与部分测试）。

**glsm（`2845c9e0` 的 API 面，sdl-gpu 依赖部分）**

- `RenderBackend` 刷新率 API 由 Hz 改为周期：
  `getDisplayRefreshRateHz()/queryDisplayRefreshRateHz()/refreshHzFrom()` 删除，
  改为 `refreshPeriodNanos()` / `queryRefreshPeriodNanos()` /
  `periodFromRational()` / `periodFromIntegerHz()` / `hzFromPeriod()` /
  `plausiblePeriodNanos()`（20–1000Hz 合理性过滤，仅接受 1ms–50ms）。
- 新增 `applyHonored()` / `vsyncHonored()`、`displayGeneration()` /
  `bumpDisplayGeneration()`（`publishVSyncMode` 与
  `onPreSwapchainInvalidatingChange` 都会递增）。
- gate API 改为 `GateRecorder` + `GateSample`：`recordGate` / `recordGpuWait` /
  `readGateSample`；删除 `recordFrameGate` / `lastFrameGateNanos` /
  `lastFrameGateEndNanos` / `gateAnchorsNextFrameStart`。
- 新增 `awaitPresent()`、`parkNanos()`、`pumpDisplayMessages()`、
  `setPresentSuppressed()` / `isPresentSuppressed()`。
- 新增 `MainThreadPump`（RFB 主线程消息泵，含 `pollInput`）；对应测试
  `BackendPolicyTest` / `GateRecorderTest` / `MainThreadPumpTest` 放在**根工程**
  `src/test/java/com/gtnewhorizons/angelica/glsm/backend/`（glsm 无 test 源集）。
- `SystemProperties.FFP_TRACE` 与 `TextureInfoCache.isProxyTarget()` 随
  `3b4ebcf6` 的 sdl-gpu 代码一并引入（`GLStateManager.glBindTexture` 的
  proxy 旁路未同步，见下）。
- **Actinium 适配**：
  - `Lwjgl3GLRenderBackend.queryRefreshPeriodNanos()` 走 GLFW 显示器模式 +
    `periodFromIntegerHz()`（上游用 SDL 的有理数模式）。GLFW 无 swap interval
    回读 API，因此不覆写 `applyHonored()`，沿用基类 `true`。
  - `SDLGPURenderBackend.pumpDisplayMessages()` 的 `MainThreadPump` 用
    `Runnable::run` + `GLStateManager::isMainThread` +
    `Display::processMessages`（上游用 retrofuturabootstrap / lwjgl3ify 的
    `MainStartOnFirstThread` / `MainThreadExec` / `Lwjgl3ifyEventLoop`）。
  - `parkNanos` 走 `SDLTimer.SDL_DelayNS`（与上游一致）。
- **未同步**：上游 `GLStateManager.updateDisplay()` 的 gate/present 重构与
  `setPresentSuppressed` 包装、`firstClear` 计时——Actinium 的 glsm 没有
  `updateDisplay`（`Display.update()` 仍由 vanilla `Minecraft.runGameLoop`
  直接调用），无对应接入点。

**sdl-gpu（`3b4ebcf6` 主源 + `961b23fd` / `2845c9e0`）**

- `FrameManager`：`acquireAffinityViolated()`（2 参）、swapchain acquire 拆成
  `SDL_WaitForGPUSwapchain`（GPU 等待，记 `recordGpuWait`）+
  `SDL_AcquireGPUSwapchainTexture`；`sdl.gpuWaitUs` plot；`presentFinalTarget`
  受 `isPresentSuppressed()` 门控；`getSwapchainFormat()` 无窗口时回退
  finalTarget 的 FBO0 颜色格式。
- `SplashDispatcher.intervalElapsed()`、`Presenter` 复用预分配 `Runnable`。
- proxy texture 支持：`ContextState.ProxyTextureState`、`texImage1D/2D/3D` /
  `texStorage*` / `getTexLevelParameteri` 的 proxy 分支、
  `ProxyTextureTargetTest` / `ProxyTextureLimitsTest`。
- `readPixels` 重写（FBO0 行翻转 + `CopyRectClip` 裁剪 + 非整宽走 staging）；
  `blitNamedFramebuffer` 重写（FBO0 双向 + `SDL_FLIP_VERTICAL` +
  `resolveBlitClears`）——原「6 的 `blitNamedFramebuffer` finalTarget 修复」
  已被上游的通用实现吸收，等价（`getFbo0Texture()` 即
  `finalTarget.colorTexture()`）。
- `TextureOps`：`copyTexture`/`blitTexture` 增加 mip level 与 flipMode 参数；
  新增 `copyFromDefaultFramebuffer`（FBO0 → 纹理）。
- `PixelOps.mipLevelSize()`、`ResourceManager.isMappableTextureFormat()` +
  `lookupTextureFormat()`（未映射格式告警去重）、
  `FBOClearTracker.resolveDestinationForWrite()`。
- `ShaderManager.patchAttribLocations` 重写：binding 优先、未绑定输入占用最小
  空闲槽、保留被优化掉的绑定位置、重复位置报错。
- `dispatchComputeIndirect` 改用 `GL_DISPATCH_INDIRECT_BUFFER` 绑定
  （`ContextState.boundDispatchIndirectBuffer`）。
- 新增 `FFPTrace`（`angelica.debug.ffpTrace`）与 `CopyRectClip`。
- 同步的测试：`AcquireAffinityTest`、`PresenterTest`（复用 Runnable）、
  `DispatchIndirectBindingTest`、`ProxyTextureTargetTest`、
  `ProxyTextureLimitsTest`、`CopyRectClipTest`、
  `CopyTexSubImageFromFbo0GpuTest`、`AttribLocationPatchTest`（重写后的
  patchAttribLocations 语义）。`ReadbackSubmitOrderGpuTest` 的上游改动
  （`assertNotEquals(0L, commandBuffer)`）与 Actinium 既有写法
  （`assertTrue(commandBuffer != 0L)`）语义相同，保留 Actinium 版本。

**Phase 1 未同步 / 保留的差异**

- **headless glsm-sdl 测试台**（`glsmSdlStubs` / `glsmSdlAgent` 源集、
  `GlsmSdlRedirectAgent`、27 个 `glsm-sdl` tag 测试、`glsmSdlTest` 任务、
  `glsm-stubs` capability）按用户决定暂缓，`sdl-gpu/build.gradle` 未改动。
- `SDLGPULWJGLService` 的 celeritas 迁移（`org.taumc.celeritas.lwjgl.*`、
  `create()` / `bind()` / `unbind()` / `isConstructed()`、
  `KEY_CELERITAS_LWJGL_SERVICE`）不适用：Actinium 保留
  `com.mitchej123.lwjgl.LWJGLService` 自建接口与全部直连后端的转发，且
  `SDLGPUGate` 无 `fallBackToGL()` 路径，故 `961b23fd`/`3b4ebcf6` 中
  `SDLGPUGate`、`SDLGPULWJGLService` 的改动整体未同步
  （`SDLGPULWJGLServiceGatingTest` 不在 Actinium，无测试改动）。
- `FFPTrace` 的 FFP UBO 成员值转储未同步：`appendFfpUniforms` 只输出绑定的
  UBO id 与 base offset，不再解码 `u_AlphaRef` / `u_MVPMatrix` /
  `u_LightmapTextureMatrix`——Actinium 的 glsm FFP 走普通 uniform
  （`Program.locMVPMatrix`），没有上游 `glsm.ffp.FFPUniformBlock` 的 std140
  布局可解码。上游 `FFPTraceTest`（反射调用 `floatIgnoringLimit`）随之不适用。
- `glsm/GLStateManager.glBindTexture` 的 proxy 旁路、`initialize()` 的
  Display 尺寸回退、`GLDebug.pushGroup(String, long)`、`FFPTrace` 之外的
  `glsm/*`（`hooks/`、`loading/`、`texture/TextureTargets`）改动属 Angelica 核心
  / lwjgl3ify 转向器范畴，不在本次 sdl-gpu 端口范围内。
- `DrawDispatch` / `SDLGPURenderBackend` 的 dropped 细分诊断保留（上游只计数，
  不细分）；因此 fan 路径只由 `DrawDispatch` 计数，后端调用点不重复 `+1`。
- `MemoryStackWrapper` 的 import 仍为 `com.mitchej123.lwjgl.MemoryStack`
  （上游改为 `org.taumc.celeritas.lwjgl.MemoryStack`）。
- RenderBackend service 文件仍只注册
  `com.gtnewhorizons.angelica.lwjgl3.Lwjgl3GLRenderBackend`，SDL 后端未注册
  （上游 `sdl-gpu/src/main/resources/META-INF/services/com.mitchej123.lwjgl.LWJGLService`
  在 Actinium 从未存在，无需删除）。

## 撤除 / 回归建议（2026-08-31 已执行）

- GLFW 窗口适配差异（窗口创建 / Display 桥 / 输入回调）已撤除，后端选择已断开；
  执行结果见 `sdl-gpu-cleanroom-glfw-compat.md` 的「撤除结果」节。
- **已保留**：6 的 `blitNamedFramebuffer` finalTarget 修复、6 的 `unmapBuffer`
  persistent 生命周期修复与 `releasePersistentStaging` 幂等、8（GLSL 保留字）、
  9（persistent 写入通知）、7（dropped 细分诊断）、6 的 `isActive()` 时序语义、
  `Device` 的 `SDL_InitSubSystem` 与 `claimPlatformWindow` 实现、
  `Lwjgl3GLCapabilitiesShim`、`SDLDrawable`。
- 重接后回归验证清单：主菜单显示、鼠标/键盘、点击切换界面、窗口 resize、进入
  世界渲染、`glBlitFramebuffer`（FBO0→finalTarget）路径。

## 上游同步状态（2026-10-11，Phase 2）

已同步上游四个提交：`81b82748`（readback ordering #2095）、`a07cecb8`（GLSM
bypass fixes #2099）、`26c161f0`（texture filtering #2096，仅 sdl-gpu 侧）、
`3876f7e6`（instance more things #2104，仅 sdl-gpu 侧）。

**#2095 readback ordering**

- `SDLGPURenderBackend`：从 `midFrameFenceFlush()` 拆出
  `flushAndSubmitMidFrame()`，并在每个 readback 前调用（`readPixels` 两条路径 +
  `getTexImage`）。
- `TextureOps.readbackTexture` 不再自行 `frameManager.submitMidFrame()`，改为
  `/// requires a flush + submit to not read stale data` 文档约定（调用方负责）。
- 删除 `ReadbackSubmitOrderGpuTest`（上游以 `CopyTexSubImageFromFbo0GpuTest`
  覆盖；Actinium 的该测试已在 Phase 1 引入，本次补 `frameManager.submitMidFrame()`
  到其 `download(...)` 助手）。
- **未同步**：rig 测试 `GlsmSdlReadbackTest`（glsm-sdl 测试台暂缓）。

**#2099 GLSM bypass fixes**

- 新增 `glsm/states/SamplerUnitArray`（+`GLContextState.samplerUnits`）与
  `BlendStateStack.setFuncUnknownState/isFuncUnknown/clearFuncUnknownState`。
- `RenderSystem`：sampler 单元追踪从 `RenderSystem.samplers[]` 移入 GLSM
  （新增 `maxCombinedTextureImageUnits`）；`enableBufferBlend` /
  `disableBufferBlend` / `blendFuncSeparatei` / `genSampler` / `destroySampler` /
  `samplerParameteri` / `bindSamplerToUnit` / `unbindAllSamplers` 一律改走
  `GLStateManager.gl*`，不再直连后端（原路径会绕过 GLSM 的绑定缓存）。
- `GLStateManager`：新增 `glEnablei` / `glDisablei` / `markIndexedCapUnknown` /
  `glBlendFuncSeparatei`（indexed 调用会把 blend/scissor 的 enable 与 blend func
  标记为 unknown，下一次非 indexed 调用强制重放）、`glGenSamplers` /
  `glDeleteSamplers` / `glBindSampler(s)` 的数组与 `IntBuffer` 重载 + 绑定追踪、
  `getSamplerBinding` / `getSamplerUnitCount`、`glMapBufferRangeAddress`、
  `GL33.GL_SAMPLER_BINDING` 查询、`replayStateToBackend` 补
  `clearFuncUnknownState()` 与 sampler 回放。
- **Actinium 适配**：
  - `applyRestoredState` 没有上游的 `restoreBlendChanged` 标志，故只在无 blend
    handler 覆盖时应用 blendFunc 并清 `funcUnknown`；blend equation/color 的
    应用条件保持 Actinium 既有行为（不随该提交收窄）。
  - `glMapBufferRangeAddress` 不调用上游的 `onBufferMapRange`（Actinium 无该
    GLSM hook，`glMapBufferRange`/`glUnmapBuffer` 同为直通包装）。
- `GLSMRedirector`：`glBindSamplers` 并入 gl44 表；新增 `ARBSamplerObjects`、
  `ARBMultiBind` 重定向表。Actinium 的 redirector 结构不同
  （`org/lwjgl/opengl/...` 常量 + `METHOD_REDIRECTS`），按同一 intent 落地；
  新增目标（`glIsSampler` 等）在 Actinium GLStateManager 中均已声明。
- `RenderBackend.bindVoxelizationRegion` 去掉 `vertexBufferGlId` 参数：SSBO 必须已
  经 GLSM 的 `glBindBufferBase` 绑定，后端只校验 `boundSsboByIndex`。
- streaming：`OrphanStreamingBuffer` 与 `PersistentStreamingBuffer` 的
  genBuffers / bindBuffer / mapBufferRange / unmapBuffer / deleteBuffers /
  fenceSync 改走 `GLStateManager`（进入 GLSM 的绑定缓存与删除失效路径），
  `onPersistentBufferWrite`（SDL 专属通知，见条目 9）保留。
- **未同步（streaming）**：上游 `GlStreamingRing` / `UniformRingBuffer` 在
  Actinium 不存在（Actinium 是 `GlFence` + `PersistentStreamingBuffer` 的自有
  ring 设计），其绑定缓存改动无处落地；`StreamingUploader` 保留 Actinium 的
  `upload(RenderBackend, ...)` 测试接缝与 null-map 兜底（`StreamingUploaderTest`
  是无 GL 的回归测试，且测试 JVM 里没有可用后端）；
  `PersistentStreamingBuffer` 的 fence 仍由 `GlFence` 经 LWJGLService 等待/删除。
- `BlendModeStorage`（上游 root `src/main`，Actinium 位于
  `shader/src/main/java/net/coderbot/iris/gl/blending/`）：
  `deferBlendModeToggle` / `deferBlendFunc` 仅在值真的变化时记录，
  避免每次 vanilla blend 调用都触发 override 冲刷重放。Actinium 的字段名是
  `originalBlend` / `originalBlendEnable`，故按其语义落地。
- **未同步的测试**：`GLSM_BlendFuncUnknownState_GLTest`、
  `GLSM_SamplerBinding_GLTest`、`StreamingBindingCacheGLTest` 需要上游 glsm 的
  test 源集 + 真 GL core context（Actinium 的 glsm 无 test 源集，glsm-sdl 测试台
  暂缓）；`StreamingBindingCacheGLTest` 还引用 Actinium 不存在的
  `glsm.ffp.FfpExtendedAttribs`。上游 `GLSMRedirectorTest` 是另一套 harness
  （Actinium 已有自写的 redirector 测试），其断言未同步；改为新增
  `SamplerUnitArrayTest` 覆盖 sampler 数组语义（越界拒绝、删除即解绑）。
  上游 `BlendModeStorageDeferDedupeTest` 用反射读写私有字段，Actinium 版改为经
  `setBlendStateAccessForTesting` 注入记录型 `BlendStateAccess` 做行为断言。
- sdl-gpu：`PipelineCache` 每个 draw buffer 的 blend factor 数组 +
  `setBlendFactors`（全局/按 buffer 两个重载）、`setVertexInputs`、
  `PipelineApplier.reuseOrAlloc` 改为可空并让各 uniform 入口早退、
  `deleteBuffers` 解除 VAO/索引/各绑定槽、`applyDrawBuffersFromIntBuffer` 数量上限、
  `uniform3fv/uniform4fv(float[])` 走 staging 存储、`ReadbackShadows.release`
  用迭代器删除、`FFPTrace` 字段改名。
- **未同步（sdl-gpu）**：`SDLGPULWJGLService` 的整文件
  `BackendManager.RENDER_BACKEND.*` → `GLStateManager.gl*` 转换——该文件在
  Actinium 不可达（未注册进 service 文件、无任何调用方，见条目 5），其上游改动
  属 celeritas `LWJGLService` 迁移；对应 rig 测试
  `GlsmSdlLWJGLServiceRoutingTest` / `GlsmSdlSamplerBindingTest` /
  `GlsmSdlIndexedBlendRestoreTest` / `GlsmSdlStreamingRingTest` /
  `GlsmSdlVoxelRegionBindTest` 未同步。

**#2096 texture filtering（仅 sdl-gpu 侧）**

- `SDLGPURenderBackend`：`requestedMipLevels` / `fullMipLevels` /
  `growForLevel` 三个 helper；`ensureLevel0Storage` 简化；新增
  `ResourceManager.ensureTextureLevels`（按需重建带更多 mip 层级的纹理并拷贝已
  填充层级）；`canReuseTexture` 允许 `levels >=`；`generateTextureMipmap` 先补齐
  层级链再生成；`textureImage2DEXT` 复用 `ensureLevel0Storage`/`growForLevel`；
  anisotropy 采样器参数追踪 + `GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT` 返回 16 +
  `getFloat` 未处理 pname 的告警去重（原为静默 0）。
- `ResourceManager`：usage promote 改走 `createTextureWithSdlFormat`，保留纹理的
  SDL 格式（此前会把 sRGB 等格式降级掉）。
- **未同步（按指示跳过）**：上游主体（`src/utils/MipmapGenerator`、
  `src/utils/SpritePadding`、`TextureFilterMode`/`TexelSampling`、mixin、
  sodium GUI、celeritas、iris PBR、shader 等）——Actinium 没有
  `MipmapGenerator` / `SpritePadding` / `TextureFilterMode` /
  `TexelSampling` 对应类。

**#2104 instance more things（仅 sdl-gpu 侧）**

- 新增 `pipeline/ScissorClamp`（越界 scissor 钳制为 `int[4]`，空矩形返回 false）；
  `PipelineApplier` 对 scissor 做钳制与一次性告警，空矩形直接放弃本帧
  （`SDL_Rect` 写入移到钳制之后）。
- `PipelineCache.vertexOffsetBase` / `vertexOffsetMisalign`：顶点缓冲绑定偏移
  按 4 字节对齐，失配位进入 pipeline key 并加到 attrib relative offset 上
  （否则未对齐绑定会取到错的顶点数据）。
- 空绘制早退：`DrawDispatch.issueIndexedDraw`（`count <= 0 || instances <= 0`）、
  `SDLGPURenderBackend.drawArrays`（`count <= 0`）、`drawArraysInstanced`
  （`count <= 0 || primcount <= 0`）。
- `ShaderManager`：新增变换结果缓存（`TransformKey`/`TransformEntry`，
  上限 256，`clearPrewarmCache` 一并清空），避免每次 `shaderSource` 重跑
  GLSL→Vulkan 预处理/去采样器/PerFrame 注入。
- 快路径：`PerFrameBlockInjector.inject`（两个 block 皆空则原样返回）、
  `SamplerStripper.stripUnused`（源码不含 `sampler` 则原样返回）。
- sdl-gpu 测试：新增 `ScissorClampTest`、`ShaderSourceTransformCacheTest`、
  `ShaderTransformChain`；更新 `PerFrameBlockInjectorTest`、
  `SdlSamplerStripperPreprocessorTest`、`PrewarmSingleParseEquivalenceTest`。
- **未同步**：TESR/Particle/Item 实例化主体（上游 `glsm/ffp/*` 实例化、
  Cube/Particle mesh、TESR API、mixin、Flyby/Tracy 等 ~+9950 行）——Actinium
  无对应实现；rig 测试 `GlsmSdlDegenerateDrawTest` / `GlsmSdlFbo0ViewportTest` /
  `GlsmSdlFfpTest` / `GlsmSdlUnalignedAttribTest` 未同步。

## 上游同步状态（2026-10-11，Phase 3）

已同步上游四个提交的 **sdl-gpu 侧**：`9dd60f6a`（render pass / state stack
optimizations #2124）、`1babfd96`（crash interceptor 状态重置 #2133）、
`44c930f4`（scissor clear 修屏 #2138）、`a8c29fa3`（per-drawable GLSM context
#2142），以及这些改动在 glsm 侧**必须**的少量成员。glsm 的状态栈 COW 重构与
per-drawable context 重构**未同步**（见本节末尾的冲突说明）。

**#2124（`9dd60f6a`）**

- sdl-gpu 主源 17 个文件全部落地：新增 `pipeline/AttachmentClear`（独立
  depth/stencil（含 color）附件 clear pass、按 layout/mask 缓存 pipeline）与
  `resource/ByteRegionCopy`（绝对偏移拷贝，direct / array / 逐字节三条路径）；
  `FrameManager` 新增 `PASS_END_*` 结束原因常量、`endRenderPassIfActive(cause)` /
  `ensureCopyPass(cause)` 重载、`inPassClearsThisFrame` 与
  `passEndCauseCountsThisFrame` 计数、`noteInPassClear()`；`SDLGPURenderBackend`
  接入 in-pass clear 与 pass 结束原因、depth/stencil write-mask 抑制 clear 的告警、
  UBO shadow 走 `ByteRegionCopy`；`PipelineCache`/`ShaderManager`/
  `PipelineStore`/`VertexAttribs` 的输入哈希缓存（`VAOState.slotHash` 等）、
  uniform location kind（`LOCATION_KIND_*`）与 `vertexInputSetId`；
  `ContextState`/`SdlFramePlots`/`ComputeBinder`/`DrawDispatch`/`PipelineApplier`/
  `FBOClearTracker`/`PersistentBufferSync`/`TextureOps`/`PixelOps` 同步更新。
- `SDLGPUGate` 改用 `DependencyVerifier.firstMissing()` + `Check` 列表做依赖探测
  （lwjgl3ify 最低版本、lwjgl-sdl/shaderc/spvc 缺失诊断）。
- GLSL 资源按 Actinium 约定放到 `src/main/resources/assets/angelica/shaders/sdlgpu/`
  （与 `clear_image3d.csh` 同处），并新增 `clear_attachment.{v,f}sh`。
- glsm 侧必需项（已同步）：`SystemProperties.SDL_DISABLE_IN_PASS_CLEAR`、
  `DependencyVerifier.firstMissing()`。同提交的 `SystemProperties.PROFILE_*` 与
  `FLYBY_JFR` 未同步（其消费者是未同步的 `src/` profiling/flyby 代码）。
- **未同步（冲突，见下）**：glsm 状态栈 COW 重构（`stacks/CowStateStack`、
  `CowDepths`、`CowDispatch`、`StackIdAllocator`、`glsm/StateSet`、
  `ClipPlaneBooleanState`、22 个 stack 的 `captureSlot`/`restoreSlot` 改写、
  `GLStateManager`/`Feature`/`GLContextState`/`states/*`/`ffp/*` 的配套改动），
  以及上游 `src/`（Angelica 核心：TESR、particles、BOB fog、profiling、flyby、
  celeritas、sodium/iris GUI 等）的全部改动。
- 测试：`UboShadowSubDataTest`、`AttachmentClearTest`、`BlendConstantsGuardTest`、
  `PerFrameBlockDedupTest`、`PipelineCacheVertexInputTest`、
  `AbsoluteCopyRegionTest`、`UniformLocationKindTest` 已同步；
  4 个 `GLSM_*_GLTest`（需 GL core context 与 glsm test 源集）与
  `GlsmSdl*` rig 测试未同步。

**#2133（`1babfd96`）**

- `RenderBackend.invalidateStateMirror()`（基类空实现）+ sdl-gpu 侧
  `SDLGPURenderBackend.invalidateStateMirror()` 与
  `ContextState.invalidateMirror()`（清空所有 lastApplied/lastPushed 镜像标志、
  markInput/Output/ShaderDirty、`needsSeed = true`）已同步。
- `GLDebug.resetGroupStack()` 与 `GLStateManager.reset()` 的「失效镜像 + 重放」
  路径已按 Actinium 的字段移植：清零 `saved*Gen` 数组、递增 Actinium 拥有的
  generation、重置 draw/read framebuffer、`GLDebug.resetGroupStack()`，最后
  `RENDER_BACKEND.invalidateStateMirror()` + `replayStateToBackend()`。
- `replayStateToBackend()` 补上游同一提交新增的 VAO / program / draw+read
  framebuffer 重绑（`ShaderManager.invalidateBoundProgram()` 未同步：Actinium 的
  ffp `ShaderManager` 没有 `lastBoundProgramId` 缓存，每次 `updateVariant` 都无条件
  `useProgram`，无对应成员可失效）。
- **未同步**：上游在 `lwjgl3-backend` 里并为 `invalidateStateMirror()` 提供实现
  （核对上游 tip：只有 glsm 基类空实现与 SDL 后端覆写），因此
  `Lwjgl3GLRenderBackend` 不添加空覆写；`src/` 侧的 crash interceptor
  （`RenderRecovery`、`BetterCrashesCompat`、`AngelicaCommand`、TESR/HUD caching
  作用域）未同步；`GLStateManager.reset()` 的 `runningSplash = true` 仍保留
  Actinium 语义（未换成上游的 `DisplayListManager.abortCompilation()`，后者在
  Actinium 不存在，且 `reset()` 在 Actinium 当前没有调用方）。

**#2138（`44c930f4`）**

- sdl-gpu 侧全部落地：`AttachmentClear` 的 scissor 感知 clear（`ScissorState`
  语义走 `ContextState.scissor*` 镜像）、`PixelOps` 的 depth/stencil
  texel 辅助、`SDLGPURenderBackend.clear` 的 `rasterizerDiscard` 早退、
  `ContextState.rasterizerDiscard`、`AttachmentClearTest` 更新。
- glsm 侧新增的 `states/ScissorState`、`stacks/ScissorStateStack`（以及
  `Feature`/`StateSet`/`CowDispatch`/`GLContextState`/`FeedbackManager`/
  `GLStateManager` 的配套改动）**未同步**：它们是 COW 状态栈体系的一部分。
  实际修屏逻辑（in-pass clear 的 scissor 判定）在 sdl-gpu 侧，已同步。
- rig 测试 `GlsmSdlAttachmentClearTest`（+212）与 `GlsmSdlFbo0ViewportTest`
  的补充未同步。

**#2142（`a8c29fa3`）**

- sdl-gpu 侧全部落地：`SDLGPURenderBackend.handleMakeCurrent` 的
  `enterContext(...)`（GLSM context 变化时失效镜像、splash 上下文用
  `defaultFboId`、`needsSeed` 时 `replayStateToBackend()`）、`beginSplashFrame()`
  抽取、`bindFramebuffer(0)` 重映射到 `defaultFboId`、
  `deleteFramebuffers` 重新绑定、`framebufferTexture2D`/`drawBuffers`/
  `readBuffer`/`getFramebufferAttachmentParameteri`/`glGetInteger` 的
  `defaultFboId` 比较、`OffscreenTarget.isFor` 改用 `defaultFboId`、
  `ContextState.defaultFboId` 与 `mirroredContext`。
- 为让 sdl-gpu 能读到 GLSM 的 context 对象，`glsm/GLContextState` 改为
  **public**、`GLStateManager.ctx()` 改为 **public**（上游同一提交的做法）。
- **未同步**：per-drawable context 本体（`DrawableContexts`、`GLStateManager`
  ~1022 行、`FeedbackManager`/`DisplayListManager`/`QuadConverter`/`VAOManager`/
  `ShaderManager`/`CompatUniformManager`/`streaming/StreamingVaos` 等改动）。
  Actinium 的 `GLContextState` 已是**每线程**（`ctx()`），每线程模型下
  `st.mirroredContext != ctx` 仍能捕获线程级 context 切换；drawable 级切换
  （同线程换 drawable）不会触发失效——属可接受的语义弱化，重接窗口路径时按上游
  重做。
- glsm 测试（`GLSM_SharedDrawableContext_GLTest`、`GLSM_SplashCompleteLatch_UnitTest`、
  `DrawableContexts`、`TestThreads` 等）与 rig 测试未同步。

**COW 状态栈重构的冲突说明（Phase 3 未同步的主因）**

上游 `9dd60f6a` 把状态栈从「push 时整份拷贝」改成「按槽位 copy-on-write」
（`CowStateStack` 接口 + `CowDepths` 槽位深度 + `CowDispatch` 类型分发 +
`GLStateManager.registerModifiedState/attribSets` + `StateSet` 整状态集），
`44c930f4`/`a8c29fa3` 都建立在其上。Actinium 的 glsm 走的是**另一条血缘**：
`ctx().attribs`（IntStack 掩码）+ `Feature.maskToFeatures` + `IStateStack.pop()`
+ `modifiedAtDepth` + `VertexAttribState`（上游为 `ffp/VAOManager`），并带有
Actinium 自己的扩展（worker context、glColor clamp #181、纹理镜像同步 #186、
`replayStateToBackend`/`takeStateSeedPending`/`warnOnce`、
`BooleanState.applyToBackend`、`GLESCaps`、Phase 2 的 `SamplerUnitArray`）。

实测冲突面（把上游该提交的补丁对 Actinium 当前文件做 `patch --dry-run --fuzz=0`）：

| 文件 | hunk 数 | 冲突 |
|---|---|---|
| `glsm/GLStateManager.java` | 82 | 66 |
| `glsm/Feature.java` | 4 | 4 |
| `glsm/GLContextState.java` | 12 | 10 |

即 GLStateManager 的 save/restore/pushAttrib/popAttrib 区域几乎全被改写过。
按用户约定「COW 重构与 Actinium 特有成员冲突且无法确信解决时停止并上报」，
本阶段**不做**该重构：逐 hunk 手改等价于重写 Actinium 的状态机，且本环境没有
GL context 测试台（glsm-sdl rig 暂缓）可验证语义。后续接入 glsm-sdl rig 后，
应以「上游 COW 模型 + Actinium 扩展点」重做，而不是把两边拼贴。

**Phase 3 的其它 Actinium 适配**

- `AttachmentClear` 的 `ShaderLoader` 引用改为 Actinium 的 embedded 包
  （`dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader`，上游为
  `org.embeddedt...`）。
- `clear_attachment.fsh` 的 `main()` 内保留一个惰性语句（上游为空函数体）：
  Actinium 固定的 `glsl-transformation-lib:0.2.0-32.g7dd88a4-GTNH` 在
  `Transformer.appendMain` 追加 color 写入时对空 `main()` 抛 NPE（上游用
  0.2.0-33）。这是**运行期**问题（颜色 clear 路径），不只是测试问题。
- `sdl-gpu/build.gradle`：测试新增 `testImplementation project(':celeritas-common')`
  （`AttachmentClear` 经 `ShaderLoader` 读 GLSL）与把根工程
  `assets/angelica/shaders/**` 挂到测试资源（GLSL 按 Actinium 约定存放在根工程，
  模块测试默认看不到）。
- `AbsoluteCopyRegionTest.warmedCopiesDoNotAllocate` 的 direct→direct 组合（flags
  3/11）不再断言零分配：LWJGL 3.4.1 上 `MemoryUtil.memCopy` 在此 JVM 每调用分配
  数十字节（上游在 3.4.2 上测同一断言）；这些组合的内容正确性仍由
  `copiesAbsoluteIndicesAcrossBufferKinds` 覆盖。
- 测试里的 `VertexAttribState.init(0)`（Actinium 版 `VAOManager.init(0)`）保留：
  上游 #2142 的 per-drawable VAO 重构未同步，init 仍是必需步骤。

## 上游同步状态（2026-10-11，Phase 4）

已同步上游两个提交：`e17b45c8`（#2153，FBO clear/readback 状态跟踪）与
`7bb5bfd4`（#2157，logic op 模拟 + pixel readback 重写）。两者都在 Phase 3 的
sdl-gpu 基础上叠加，未触及 Phase 3 记录为未同步的 glsm COW / per-drawable 部分。

**#2153（`e17b45c8`）**

- 用 `ResourceManager` 的 `definedContentTextures` 取代每帧的
  `clearedTexturesThisFrame` / `clearedStencilTexturesThisFrame` 两个集合：
  `ContextState` 删除这两个字段，`AttachmentClear`、`FBOClearTracker` 的各登记点
  删除，`PipelineApplier` 改用 `resourceManager.isTextureContentDefined(...)`
  判断是否需要首次 clear，`markTextureContentDefined` 幂等化（已定义则直接返回）。
- `discardPendingClearIfFullyCovered` / `resolveDestinationForWrite` 增加
  `allAspects` 参数：depth-stencil 附件上的**部分 aspect** 写入不再丢弃 pending
  clear（原来按「整份写覆盖」判断，导致 NEI/ModularUI2 这类只写 color 或单 aspect
  的路径把 depth/stencil clear 吞掉，物品图标随后变黑/消失）；
  `resolveDestinationForWrite` 结束时统一 `markTextureContentDefined(destTex)`。
- `blitNamedFramebuffer`（color 走 `allAspects = true`，depth/stencil 时按
  mask 是否同时包含 DEPTH|STENCIL 或目标非 depth-stencil 格式决定）、
  `TextureOps.copyTexSubImageImpl`、`copyTextureToTexture` 等调用点同步。
- 测试：`ClearConsumptionTest`、`FullCoverageClearDiscardTest`、
  `StencilClearTrackingTest` 已同步；3 个 rig 测试
  （`GlsmSdlAttachmentClearTest`、`GlsmSdlCopyBlitTest`、`GlsmSdlHeadlessRig`）
  未同步。

**#2157（`7bb5bfd4`）**

- glsm：
  - `RenderBackend.readPixels(..., long pixelBufferOffset)` 从 Actinium 的「具体
    实现抛 `UnsupportedOperationException`」改为**抽象方法**（上游该提交的做法），
    由各后端实现；`Lwjgl2GLRenderBackend` 在 Actinium 不存在，其实现跳过。
  - Actinium 的 `GLStateManager.glReadPixels(..., long)` 已存在且语义一致
    （`remapPixelTypeForGLES` + 不扰动 pixel-pack 绑定），无需改动。
  - 新增 `@Getter private static boolean windows`（在 `init()` 里按 `os.name`
    赋值）与 Lwjgl3 AMD workaround 所需的 `GLStateManager.isWindows()`：上游的
    平台探测字段，Actinium 之前只有 `vendorIsAMD()`/`vendorIsIntel()`。
  - `src/test/.../streaming/StubRenderBackend`（Actinium 测试替身）补
    `readPixels(..., long)` override（抛 `UnsupportedOperationException`，与该类
    其余 GL 入口一致）。
- `Lwjgl3GLRenderBackend`：Windows + AMD（vendor 以 amd / ati technologies /
  advanced micro devices 开头）时 `glShaderSource` 改走
  `nglShaderSource(shader, 1, pointers, 0L)` + `MemoryUtil.memUTF8(source, true)`
  （上游 lwjgl3-backend 的实现；LWJGL 3.4.1 已具备这些 API，无需升级）。
- sdl-gpu 22 个主源文件全部落地：新增 `pipeline/DepthStencilReadback`、
  `pipeline/FullscreenPass`、`pipeline/LogicOpEmulator`、`resource/PackState`、
  `resource/PixelPack`、`shader/LogicOpFormats`、`shader/LogicOpLowering`、
  `shader/LogicOpVariant`；`SDLGPURenderBackend` 的 4 个 `readPixels` 与 3 个
  `getTexImage` 全部汇入统一的 `readPixelsInto` / `getTexImageInto` / `readback`
  路径（支持 PBO offset、depth/stencil 经 `DepthStencilReadback` 渲染回读、
  BGRA/格式打包由 `PixelPack` 处理）；`PixelOps`、`ResourceManager`、
  `TextureOps`、`SamplerBinder`、`PipelineApplier`/`PipelineCache`、
  `FBOClearTracker`、`FrameManager`/`SdlFramePlots`、`ContextState`、
  `ShaderManager`、`DrawDispatch`、`AttachmentClear` 同步更新。
- GLSL 资源按 Phase 3 的约定放在根工程
  `src/main/resources/assets/angelica/shaders/sdlgpu/`：`logic_op.glsl`（片段，
  由 `LogicOpLowering.SNIPPET` 静态加载并内联进 pipeline 的片段着色器）、
  `readback_depth.fsh`、`readback_stencil.fsh`。三者函数体非空，
  **不需要** Phase 3 `clear_attachment.fsh` 那种惰性语句 workaround。
- **适配**：
  - 新文件的 `ShaderLoader` 引用改为 Actinium 的 embedded 包
    `dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader`
    （`FullscreenPass`、`DepthStencilReadback`、`LogicOpLowering`，与 Phase 3 的
    `AttachmentClear` 一致）。
  - `AttachmentClear` 在本提交中收缩（clear pipeline 迁入 `FullscreenPass`），
    其 12 个不再使用的 import 手动清理：上游的 import hunk 因 Phase 3 的
    `ShaderLoader` 包名适配而未能自动应用（body 改动均已应用）。
- 测试：`DepthStencilReadbackTest`、`PixelPackTest`、`LogicOpLoweringTest`、
  `BgraStorageChannelTest`、`ResourceManagerFormatMappingTest` 已同步。
- **未同步**：rig 测试 `GlsmSdlLogicOpTest`、`GlsmSdlReadbackMatrixTest`；glsm 侧
  `ReadbackGLTest`、`ffp/LogicOpGLTest` 及 testFixtures
  `ReadbackFixture`/`LogicOpFixture`（需要 GL core context 与 glsm test 源集）；
  上游 `GLSMRedirectorTest` 的新断言（`glReadPixels`/`glGetTexImage` 的 PBO
  overload 重定向目标）——Actinium 的 redirector 早已包含这些条目，且其
  redirector 测试是自写 harness；`src/` 侧的 `MixinGuiTextField`/
  `TextHighlightHooks`/reeses sodium options 改动属核心 GUI 范畴。

## 上游同步状态（2026-10-11，Phase 5）

已同步上游三个提交：`c10d30b2`（#2167，atlas 并行加载 + SDL-GPU mip-level
target）、`80e5b544`（#2188，voxelization 性能）、`1587a12c`（#2191，Metal fence
workaround + present 期间 pump）。三者的 sdl-gpu / glsm 侧均已落地，`src/` 侧的
功能主体按范围跳过。

**#2167（`c10d30b2`）**

- glsm 新增：
  - `texture/TextureStaging` 接口（`buffer()` / `bgra()` / `level()`）。
  - `RenderBackend.beginTextureStaging/commitTextureStaging/abandonTextureStaging/
    trimTextureStaging`：通用客户端 staging 实现（`clientStagingCache` 复用 +
    `recycleClientStaging` 按容量择劣替换 + `trimTextureStaging` 全部释放；
    `commitTextureStaging` 走 `texSubImage2D(GL_TEXTURE_2D, …, GL_BGRA,
    GL_UNSIGNED_INT_8_8_8_8_REV|GLES remap, …)`）。
  - `GLStateManager` 的四个包装：display list 录制中抛
    `UnsupportedOperationException`（Actinium 无 `DisplayListManager.unsupportedInList`，
    按 Actinium 既有 idiom 写），`ctx().pixelUnpackState` 非默认时
    `beginTextureStaging` 返回 null；`commitTextureStaging` 前后
    `suspendPixelUnpackBuffer()/restorePixelUnpackBuffer()`，成功后
    `TextureInfoCache.INSTANCE.onTexSubImage2D(...)` + `maybeGenerateMipmap(...)`。
- **删除 `glsm/hooks/ShaderWorkSubmitter`**（连同 `GLSMHooks.shaderWorkSubmitter`
  字段）：按约定核查过 Actinium 的 `src/`、`celeritas-common/`、`shader/`
  —— 无任何引用，唯一使用者是 sdl-gpu 自己的 `ShaderManager`，本提交内改为
  `AngelicaWorkers.submit(...)`，故与上游一致删除。
- 新增 `glsm/threading/AngelicaWorkers`（自包含的 2..12 线程池、prestarted、
  120s 空闲回收 + `onIdleShutdown` 钩子、`isWorkerThread()`）；其配置项
  `GLSMConfig.workerThreadCount` 一并引入（0 = 自动按 CPU 核数取
  2..12；Actinium 未同步提供该选项 UI 的 `src/` 侧代码，保持自动默认）。
- sdl-gpu 10 个文件：SDL 后端的 `beginTextureStaging/commitTextureStaging/
  abandonTextureStaging` 覆盖（`ResourceManager.TextureStagingRegion`、
  `shouldDeferTextureUpload`/`commitTextureStagingDeferred`/
  `commitTextureStagingInline`、`textureOps.trackMipUploadForTexture`）；
  `FboState.colorLevels[]`/`depthLevel` 与 mip 级 FBO 尺寸/查询
  （`GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL`、`fbo.width/height` 用
  `PixelOps.mipLevelSize`）；readback 按 mip 级取源尺寸，depth/stencil 附件非 0
  级读回显式抛 `UnsupportedOperationException`；`FBOClearTracker.clearColorLevel`/
  `clearDepthStencilLevel`；`LogicOpEmulator`/`PipelineApplier`/`TextureOps`/
  `ShaderManager`/`FrameManager`/`ContextState` 同步。
- **Lwjgl3GLRenderBackend 无需改动**：核对上游 `lwjgl3-backend`，它没有覆写这四个
  staging 方法——通用客户端实现就是 GL 后端的实现（与上游一致），因此没有“镜像
  上游 lwjgl3 实现”这一步可做。
- 测试：`SDLGPUFboTargetCacheTest` 已同步；6 个 rig 测试
  （`GlsmSdlCopyBlitTest`、`GlsmSdlHeadlessRig`、`GlsmSdlMipAttachmentTest`、
  `GlsmSdlOutOfFrameExtension`、`GlsmSdlOutOfFrameReadbackTest`、
  `GlsmSdlTextureStagingTest`）未同步。
- **未同步**：`glsm/loading/ModRedirector`、`RfbModRedirector` 与
  `glsm/redirect/GLSMRedirector.getConstantPoolSearchStrings()` 的常量池匹配优化
  （Actinium 的 `glsm/loading` 只有 `DependencyVerifier`/`EcosystemNarrowRules`/
  `TransformerNarrower`，没有这两个 redirector，且没有 retrofuturabootstrap
  依赖），以及 atlas 并行加载主体（`src/` +4166：`AtlasAssembler`、
  `AtlasLoadSession`、`AtlasPackPolicy`、`AtlasPackScreen`、`AngelicaConfig`、
  RFB transformer、`Mixins`、`SpriteExtension`、`IrisGLSMBridge` 等）。

**#2188（`80e5b544`）**

- `RenderBackend`：删除 `bindVoxelizationRegion(...)`（Phase 2 曾按 `a07cecb8`
  把它改成去掉 `vertexBufferGlId` 的版本），`voxelizeRange(pass, vertexOffset,
  vertexCount)` 替换为 `voxelizeRegion(pass, rebindVertexBuffer, x, y, z,
  rangeBase, rangeCount, vertexTotal)`；`SDLGPURenderBackend` 与
  `compute/VoxelizationDispatcher` 同步实现（区域偏移 uniform + 可选顶点缓冲重绑 +
  范围表遍历）。Actinium 目前没有 voxelization 调用方，API 与后端实现仍按上游
  落地，保持后端表面一致。
- 测试：`VoxelizationDispatcherTest`、`MslCrossCompileTest` 已同步；rig 测试
  `GlsmSdlVoxelRegionBindTest` 上游该提交已删除（Actinium 从未有过）。
- **未同步**：`src/` 的 voxelization 主体（`SdlShadowVoxelizationSink`、
  `ShadowVoxelizer`、`VoxelRangeTable`、`TracyFramePlots`、
  `AngelicaChunkRenderer`、`RwImageStoreExtractor` 及其测试）。

**#2191（`1587a12c`）**

- glsm：`MainThreadPump` 重写——present 期间也驱动 pump
  （`runPresentPump()`、`presentPumpPending`、`presentFailure` 抛回调用线程、
  中断标志保持、stall 告警保留）；新增 `profiling/DebugCounters`
  （LongAdder：`FENCE_WAIT_NANOS`、`TRANSFER_WAIT_NANOS`、`PRESENTS`、
  `EMPTY_ACQUIRES`）。`MainThreadPumpTest` 按上游更新（仍位于 Actinium 根工程
  `src/test/java/com/gtnewhorizons/angelica/glsm/backend/`）。
- sdl-gpu：新增 `device/{FenceProbe,FenceReleaser,FenceWait,Submits}`；`Device`、
  `frame/FenceTracker`、`frame/FrameManager`、`frame/Presenter`、
  `resource/PersistentBufferSync`、`resource/ResourceManager`、
  `resource/TextureOps`、`resource/TextureSamplerState`、`resource/TransferThread`、
  `sampler/SamplerBinder`、`SDLGPURenderBackend` 同步（fence 能力探测与等待/释放
  抽象、present 提交计数、transfer 线程唤醒策略、采样器状态生成号）。Metal 特例
  保持上游形态——按 device properties 探测，不按平台判断。
- **Actinium 适配（既有偏差全部保留）**：
  - `Presenter` 构造器新增 `MainThreadPump` 参数 → Actinium 传
    `new Presenter(frameManager, Runnable::run, Pump.INSTANCE)`，即保留
    Phase 1 的执行器偏差（无 `MainStartOnFirstThread`，present 内联执行）。
  - `FrameManager.presentBlit` 的提交改走 `Submits.submit(cb)`，但保留 Actinium
    的失败日志（`presentBlit: SDL_SubmitGPUCommandBuffer failed: {}`），并按上游
    接上 `DebugCounters.PRESENTS.increment()`。
  - Phase 1–4 的其它偏差经 sweep 确认仍在（`isPresentSuppressed`、dropped 细分
    计数、persistent `unmapBuffer` 生命周期、`Lwjgl3GLCapabilitiesShim`、
    `SDL_DISABLE_IN_PASS_CLEAR`、`invalidateStateMirror`、
    `replayStateToBackend` 的 program/VAO/FBO 重绑、`SamplerUnitArray`）。
- 测试：`SdlTestRig`、`FenceProbeRealDeviceTest`、`FenceReleaserTest`、
  `FenceWaitTest`、`PresenterTest`、`TextureSamplerStateTest`、
  `TransferThreadWakePolicyTest`、`SamplerBindGenerationTest` 已同步；
  `src/` 的 `FlybyRunner` 未同步。
