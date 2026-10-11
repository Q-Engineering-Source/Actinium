# sdl-gpu 相对 Angelica 上游的修改清单

> 状态：进行中（2026-08-15）；**GLFW 适配部分已于 2026-08-31 撤除**（见条目内
> 「2026-08-31」标注，撤除详情见
> `docs/research/sdl-gpu-cleanroom-glfw-compat.md`）；**上游 #2025（SDL presenter
> fixes and frame pacer adjustments，`b2f597a7`）已于 2026-08-31 同步**；
> **上游 #2065 / #2086 / #2087（`961b23fd` / `2845c9e0` / `3b4ebcf6`）的
> sdl-gpu 端口（Phase 1）已于 2026-10-11 同步**（详见「上游同步状态
> （2026-10-11，Phase 1）」）。本文档
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
