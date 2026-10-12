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
> 该阶段的 glsm 状态栈 COW 重构**已于 2026-10-11 完成**（见「glsm 状态栈 COW
> 重构（2026-10-11，COW-A）」）；per-drawable context 重构**亦已于 2026-10-11
> 完成**（见「glsm per-drawable context（2026-10-11，COW-B）」）。
> **上游 #2153 / #2157（`e17b45c8` / `7bb5bfd4`，Phase 4）已于 2026-10-11 同步**
> （详见「上游同步状态（2026-10-11，Phase 4）」）；**上游 #2167 / #2188 / #2191
> （`c10d30b2` / `80e5b544` / `1587a12c`，Phase 5）已于 2026-10-11 同步**
> （详见「上游同步状态（2026-10-11，Phase 5）」）；**上游 #2192 / #2194 / #2195 /
> #2213（`85892c0f` / `37afddab` / `bdcb4d18` / `38af64f1`，Phase 6）已于
> 2026-10-11 同步**（详见「上游同步状态（2026-10-11，Phase 6）」）；**上游
> #2204 / #2211 / #2215 / #2217（`61bb779d` / `6dfb0312` / `2a8c8756` /
> `12857006`，Phase 7）已于 2026-10-11 同步**（详见「上游同步状态
> （2026-10-11，Phase 7）」，其中 persistent-mapping 偏差的裁决见同一节）；**上游
> #2218 / #2224 / #2226（`3389b952` / `5ed1c925` / `d056bf6f`，Phase 8）已于
> 2026-10-11 同步，SDL-GPU 上游追赶就此收尾**（详见「上游同步状态
> （2026-10-11，Phase 8）」）。
> 本文档
> 逐条记录 Actinium 的 `sdl-gpu` 模块相对 Angelica 上游（`D:\Code\Angelica`，
> 运行于 lwjgl3ify/GTNH 环境）的**代码差异及其理由**。多数差异源于运行环境不同
> （lwjgl3ify vs Cleanroom/lwjglxx）；部分改动（如 persistent-buffer 同步）与
> 窗口无关，是后端本身的修复，应长期保留。
>
> **当前状态**：SDL GPU 后端已注册进 `RenderBackend` service 文件（Phase 1 起），
> 但在两种运行时上都不会入选：老 CRL 没有可被 GPU 设备认领的窗口，新 CRL 需要宿主安装
> LWJGLY 的 WindowBridge（见 2026-10-12 更正）。

## 2026-10-12 更正：SDL 只有 LWJGLY 一条路线

本节取代本文档里所有以「Cleanroom/lwjglxx 下用 coremod 换窗口、用 VarHandle 装
drawable」为前提的记述。变更原因（用户与 L1 决议）：老 CRL（lwjglx + lwjglxx）的窗口
恒带 GL context，SDL 无法为 `SDL_WINDOW_OPENGL` 窗口创建 Vulkan/Direct3D 12 swapchain，
`SDL_ClaimWindowForGPUDevice` 必然失败；**Vintage-SDL-Bridge coremod 路线不再追求**。
SDL 改为只走新 CRL 的 LWJGLY + 宿主 `WindowBridge`。

由此删除与重指：

| 原条目 | 处置 | 理由 |
|---|---|---|
| `SDLGPUWindowHandover`（读 `Launch.blackboard` 的 coremod 交接契约） | **删除**，连同 `com.dhj.vintagesdlbridge` 测试替身与 `SDLGPUWindowHandoverTest` | coremod 路线作废；窗口改由 `WindowBridge` 提供 |
| `SDLGPUDisplayBridge` | **删除** | 它的全部职责是往 lwjglxx 的 `Display.drawable` 装 drawable、并识别 `SharedDrawable` 包装；LWJGLY 无 drawable 模型可装（`Display.getDrawable()` 恒 null） |
| `SDLDrawable`（`device/SDLDrawable.java`） | **删除** | 同上：LWJGLY 下没有消费者；顺带说明它此前的 `createSharedContext()` 契约（返回 null）在 LWJGLY 上由「null drawable 即唯一上下文」表达 |
| `Device` / `SDLGPUGate` / `SDLGPURenderBackend` 里的 `org.lwjglx.*` 引用（`Display`、`Sys`） | **重指 `org.lwjgl.**`** | `org.lwjgl.**`（LWJGL2 空间）在两种运行时都存在，是常驻代码唯一安全的 LWJGL2 API 面 |
| 「bridge 在位」门控 | 改为 `LwjglyLayer.present()`（`Class.forName("com.cleanroommc.lwjgly.LWJGLY")`，结果缓存）+ `LwjglyWindowHost.available()` | 老 CRL 上 `isAvailable()` 必须为 false 且全程不加载 `com.cleanroommc.lwjgly.*` 类 |

`Lwjgl3GLRenderBackend`（默认 GL 后端，两种运行时都会加载）里那个未被使用的
`org.lwjglx.opengl.Display` import 也已移除。

新的启停/交接契约、宿主侧要求与 null-drawable 退化见
`docs/research/sdl-gpu-enable-path.md`；双运行时规则与对账见
`docs/research/lwjgl2-compat-dual-runtime.md`。

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

### 2. `SDLGPUDisplayBridge`（新增文件）—— lwjglxx 兼容桥（**2026-10-12 删除**）

> **2026-10-12**：文件已删除，理由见上文「2026-10-12 更正」。另外，撤除前的实现实际上
> 从来没能初始化过：它用 `MethodHandles.findVarHandle(SharedDrawable.class, "drawable",
> Drawable.class)` 读包装对象，而 lwjglxx（1.1.17 / 1.1.22 均验证）的
> `org.lwjglx.opengl.SharedDrawable` **没有任何字段**——它的构造器只是把
> `wrapped.createSharedContext()` 的结果存进继承来的 `DrawableGL.context`。因此
> `SDLGPUDisplayBridge` 的静态初始化必然抛 `ExceptionInInitializerError`，旧的
> drawable 安装路径从未真正运行。以下为历史记述。

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

### 4. `SDLDrawable` —— 无 GL context 的 drawable（**2026-10-12 删除**）

> **2026-10-12**：该类连同 `SDLGPUDisplayBridge` 一并删除。它是给 lwjglxx 的
> `Display.drawable` 用的容器，而 LWJGLY 没有可安装的 drawable 模型；SDL 模式下
> 「唯一上下文」由 null drawable 表达（见 enable-path §3.3）。以下为历史记述。

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
  **2026-10-11（Phase 7）裁决：保留**（依据见「Phase 7」一节的
  「persistent-mapping 偏差裁决」）：上游 `12857006` 的加固**未改变**
  “`unmapBuffer` 释放 persistent staging”这一语义，其 `freeAfterSeq` 延迟释放只
  保护**已入队的上传读取**，不保护客户端仍持有的映射地址，故不能吞并本偏差。
  Phase 7 仅按上游补上 `getPersistentMapping(glId) != null → return true` 分支
  （返回成功但不触碰映射），使 `glUnmapBuffer` 的返回值符合 GL 语义。
- **`releasePersistentStaging` 幂等化**（`PersistentMapping.markReleased`）：多条
  释放路径（remap swap / buffer delete）可能重叠，防止同一 staging 被重复
  `memFree`。**2026-10-11（Phase 7）**：签名随上游改为
  `releasePersistentStaging(pm, glId, cause)`（新增释放来源 debug 日志），
  `markReleased` 幂等判断保留在原处。
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
- **2026-10-11 更正与补齐**：上一条实测只落地了 `ResourceManager` 的两项与
  anisotropy 采样参数追踪（`TextureSamplerState.setf/seti`、`SamplerBinder`）；
  `SDLGPURenderBackend` 侧的三个 helper、`canReuseTexture` 放宽、
  `texImage2D`/`copyTexImage2D`/`textureImage2DEXT`/`generateTextureMipmap` 的按需
  扩容、`getInteger`/`getFloat` 的各向异性上限当时**未落地**，
  `ResourceManager.ensureTextureLevels` 因此是无调用者的死代码。这些调用侧，以及
  上游主体的纹理过滤特性，已在 `docs/research/texture-filtering-port.md` 记录的
  提交中补齐（含 `SpritePadding`、`TextureFilterMode`、`TexelSampling` 等类，
  放置位置与上游不同，理由见该文档）。

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
  （lwjgl-sdl/shaderc/spvc 缺失诊断）。**2026-10-12 修正**：该同步带入了上游的
  lwjgl3ify `DisplayEvents` 检查，但 Actinium 不使用 `DisplayEvents`（见上文「未同步」）
  且 Cleanroom 实例不装 lwjgl3ify，保留会让 `isSDLGPUAvailable()` 恒为 false，
  于是后端永远选不中；现已移除该检查并把缺失提示改为本仓库的说法
  （见 `sdl-gpu-enable-path.md`）。
- GLSL 资源按 Actinium 约定放到 `src/main/resources/assets/angelica/shaders/sdlgpu/`
  （与 `clear_image3d.csh` 同处），并新增 `clear_attachment.{v,f}sh`。
- glsm 侧必需项（已同步）：`SystemProperties.SDL_DISABLE_IN_PASS_CLEAR`、
  `DependencyVerifier.firstMissing()`。同提交的 `SystemProperties.PROFILE_*` 与
  `FLYBY_JFR` 未同步（其消费者是未同步的 `src/` profiling/flyby 代码）。
- **未同步（冲突，见下）→ 2026-10-11 已由 COW-A 补齐**：glsm 状态栈 COW 重构
  （`stacks/CowStateStack`、`CowDepths`、`CowDispatch`、`StackIdAllocator`、
  `glsm/StateSet`、`ClipPlaneBooleanState`、22 个 stack 的
  `captureSlot`/`restoreSlot` 改写、`GLStateManager`/`Feature`/`GLContextState`/
  `states/*` 的配套改动）见「glsm 状态栈 COW 重构（2026-10-11，COW-A）」一节；
  上游 `src/`（Angelica 核心：TESR、particles、BOB fog、profiling、flyby、
  celeritas、sodium/iris GUI 等）的全部改动仍未同步，per-drawable context
  （`a8c29fa3`）留给 COW-B。
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
  `Feature`/`StateSet`/`CowDispatch`/`GLContextState`/`GLStateManager` 的配套改动）
  **2026-10-11 已由 COW-A 补齐**（见「glsm 状态栈 COW 重构」一节；`FeedbackManager.
  getRenderMode()` 未移植——其消费者是后续提交的 `glClear` 反馈模式早退，本阶段不需要）。
  实际修屏逻辑（in-pass clear 的 scissor 判定）在 sdl-gpu 侧，早已同步。
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
- **per-drawable context 本体：2026-10-11 已由 COW-B 补齐**（见「glsm per-drawable
  context（2026-10-11，COW-B）」一节）。Phase 3 当时只落了 SDL 侧的 `mirroredContext`
  失效路径，而 glsm 侧仍是「每线程 only」，于是**同线程换 drawable 不会触发失效**
  ——那是当时的语义弱化；COW-B 之后 `makeCurrent(Drawable)` 会把每个非 display
  drawable 绑定到它自己的 `GLContextState`，同一线程换 drawable 时
  `st.mirroredContext != ctx` 成立并失效镜像，与上游一致。
- glsm 测试状态：`DrawableContexts` 与 `TestThreads` 已移植（`TestThreads` 进
  glsm testFixtures、`DisplayListIdAllocatorTest` 进根 `src/test`）；
  `GLSM_SharedDrawableContext_GLTest`、`GLSM_SplashCompleteLatch_UnitTest`、
  `GLSM_PerThread_UnitTest` 等需要真 GL context / drawable，按约定跳过（见 COW-B 一节）。

**COW 状态栈重构的冲突说明（Phase 3 未同步的主因，2026-10-11 已由 COW-A 解决）**

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
Phase 3 因此先把该重构放下（判据是「无法确信解决冲突时停止」）。2026-10-11 用户
明确 COW-A 可以按「上游模型重写、Actinium 扩展点重挂」的方式重做（`ctx()` 的每线程
模型是 bolt-on 适配，不必原样保留），遂完成移植，过程与结论见下一节。

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
  范围表遍历）。
- 测试：`VoxelizationDispatcherTest`、`MslCrossCompileTest` 已同步；rig 测试
  `GlsmSdlVoxelRegionBindTest` 上游该提交已删除（Actinium 从未有过）。
- **`src/` 侧与调用方已于 2026-10-11 补完**（见 `sdl-gpu-voxelization.md`）：
  `VoxelRangeTable`/`ShadowVoxelizer`/`SdlShadowVoxelizationSink` 落到
  `com.dhj.actinium.render.voxelization`，调用方接在
  `VintageRenderSectionManager.ChunkRenderer.render`（对应上游 `AngelicaChunkRenderer`），
  shader 模块补 `CeleritasTerrainPipeline.getShadowVoxelizationComputeSource()` 与
  `DeferredWorldRenderingPipeline` 的 voxelization compute（上游在 `8da561d3` 就有，
  此前未同步；**同一提交里 Actinium 的 pass 源循环漏了 `PatchShaderType.COMPUTE`**，
  已补）。`TracyFramePlots` 及其计数仍跳过（Actinium 无 game 侧 plot 聚合器）。
  另与 celeritas-common vendored 副本对齐 3 处：`BatchAssembler` 的
  `packRuns/runCount/runFirst/runLast` 恢复 public、`ChunkRenderListIterable`
  补 `getRegion(int)`、`SortedRenderLists` 实现它（Celeritas 2.5.12 本就是如此）。

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

## 上游同步状态（2026-10-11，Phase 6）

已同步上游四个提交：`85892c0f`（#2192，DSA 绑定恢复 + 液体颜色）、`37afddab`
（#2194，着色器编译结果落盘缓存）、`bdcb4d18`（#2195，Complementary/Euphoria ULTRA
修复）、`38af64f1`（#2213，着色器加载优化第一步）。sdl-gpu 与 glsm 侧全部落地；
`src/`（Angelica 根部 Iris 接线、GUI、TextureTracker、MultiListenerNotifier 等）按
范围跳过，仅按用户指定把 Iris 预处理链的增量移入 Actinium 的 `shader/` 模块。

**#2192（`85892c0f`）**

- **`RenderBackend` 删除 5 个方法**：`texStorage1D`、`texStorage3D`、
  `textureStorage1D`、`textureStorage2D`、`textureStorage3D`（`texStorage2D` 保留），
  连带删除：
  - `DSAAccess` 的 `textureStorage1D/2D/3D` 三个声明；
  - `DSAARB`、`DSAUnsupported` 的实现；
  - `RenderSystem.textureStorage1D/2D/3D` 三个转发；
  - `SDLGPURenderBackend` 的 5 个覆写（`texStorageImpl` 保留供 `texStorage2D` 使用）；
  - `Lwjgl3GLRenderBackend`（Actinium 的 `src/lwjgl3/`）的 5 个覆写；
  - `StubRenderBackend`（Actinium 测试替身）的 5 个 override。
  核查过 Actinium 无任何调用方（只有这些定义自身互相调用），故与上游一致删除；上游
  `Lwjgl2GLRenderBackend` 在 Actinium 不存在。
- `DSAUnsupported` 的 DSA 绑定恢复修复：新增 `bindingQuery(target)` /
  `previousBinding(target)` / `previous2D()` / `bindForEdit` / `restoreBinding`，
  2D 路径走 GLSM 缓存的纹理单元绑定（`GLStateManager.getTextures()
  .getTextureUnitBindings(...)`，缓存启用时），其余 target 查
  `GL_TEXTURE_BINDING_*`；`texParameter*`/`getTexParameteri`/`getTexLevelParameteri`/
  `copyTexSubImage2D`/`textureImage2D`/`textureSubImage2D` 全部改用这套 helper。
  **适配**：上游同一 hunk 还改了 `getTexParameterf`，Actinium 的 `DSAAccess` 没有这个
  成员（既有的精简差异），该部分未同步。
- `SDLGPURenderBackend.getInteger` 把 `GL_TEXTURE_BINDING_1D/3D/CUBE_MAP/
  1D_ARRAY/2D_ARRAY/RECTANGLE/CUBE_MAP_ARRAY` 一并映射到 `boundTextureOf(...)`。
- **未同步**：`src/` 的 `TextureTracker`、`IrisGLSMBridge`、`Mixins`、`TargetedMod`、
  `ColorizeBlock`、`ProgramUniforms`、`MultiListenerNotifier`（+ 其测试）与
  mcpatcherforge 的 `MixinRenderBlocks` 系列。**MultiListenerNotifier 核查结论**：
  Actinium 的 `shader/src/main/java/net/coderbot/iris/gl/state/` 只有
  `FogMode`/`StateUpdateNotifiers`/`ValueUpdateNotifier`，没有该类，故按约定跳过。
- 测试：`DSAUnsupportedBindingTest`（`@GLCoreTest`，需要真 GL core context 与 glsm
  test 源集）与 rig 测试 `GlsmSdlTextureBindingQueryTest` 均未同步。

**#2194（`37afddab`）**

- glsm 新增着色器落盘缓存：`shader/ShaderCacheIO`（原生库/驱动的库指纹 + 堆缓冲
  序列化）与 `shader/ShaderDiskCache`（分层 key、字节/字符串/字符串表/blob 载荷、
  容量控制、失败目录），`SpirvCompiler` 接入缓存（含 `shaderc_failures/` 转储，
  已加入 `.gitignore`），`CompatShaderTransformer` 接入缓存。
  **适配**：`ShaderCacheIO`/`SpirvCompiler` 的 `@Lwjgl3Aware` 注解与其 import 被去掉
  —— Actinium 没有 lwjgl3ify（无该注解的消费方）；`CompatShaderTransformer` 的
  `transform` 由上游 hunk 与本仓库既有 `dumpShader` 路径手工合并（保留 dump 与
  `LOGGER` 用法），并顺手把上游插错的 `minGlslVersion()`/`diskKey()` 挪到类级位置。
- sdl-gpu：`ShaderManager` 的落盘 key 接线（`sdl-stage` / `sdl-prewarm` /
  `sdl-transform` 三层，含 toolchain/spvc 指纹），新增 `shader/ShaderStageSerializer`
  （stage 序列化）、`shader/cross/CrossCompileCache`、`shader/cross/CrossCompileUtil`；
  `dxbc/DxbcCrossCompile`、`msl/MslCrossCompile`、`dxbc/D3DCompiler`、`ClipZRemap`、
  `PerFrameBlockInjector`、`SamplerStripper` 同步。
- 测试：sdl-gpu 14 个文件（含新增 `ShaderStageSerializerTest`、`StageDiskCacheTest`）
  全部同步；glsm 侧 `ShaderDiskCacheTest` 移入根工程
  `src/test/java/com/gtnewhorizons/angelica/glsm/shader/`（Actinium 的 glsm 无 test
  源集），`CompatShaderTransformerTest` 的磁盘缓存用例按 Actinium 的
  `actinium_` uniform 前缀改写后并入既有测试。**适配**：根工程
  `build.gradle` 增加 `testImplementation testFixtures(project(':glsm'))`，否则这些
  glsm 侧测试用不到 `Reflect` 等测试夹具。
- **未同步**：`SpirvCompilerDiskCacheTest`（需要上游 glsm 的 `spirvTest` 任务与
  shaderc/spvc 原生库，Actinium 无该任务）与 `glsm/build.gradle.kts` 对该任务的
  过滤器追加；`glsm/shader/ProgramBinaryCache` 在 Actinium 不存在（属其它血缘），
  其增量跳过。

**#2195（`bdcb4d18`）**

- `glsm/shader/GlslVulkanPreprocess`：新增 `TARGET_RESERVED`（C++/GLSL 侧保留字表）
  与 `RESERVED_RENAMED_PREFIX = "angelica_renamed_"`，遍历标识符时把命中保留字的
  标识符重命名（Complementary/Euphoria ULTRA 的 `try`/`catch` 等局部变量导致 MSL
  交叉编译失败的根因）。
- sdl-gpu 测试：`PrewarmTreeHandoffEquivalenceTest`、`MslCrossCompileTest`（新增
  `cppKeywordLocalIsRenamedBeforeMsl`）。
  **适配**：上游该用例写成 `MslCrossCompile.Output`，而上游紧随其后的 `c97fb700`
  （"Fix (#2196)"）才把它改成 `CrossCompileCache.Output`；这里直接采用修正后的形式，
  否则测试无法编译。
- **未同步**：`src/main/java/net/coderbot/iris/pipeline/transform/ShaderTransformer.java`
  的 `extractRwImageStores(...)` 改动 —— Actinium 的对应文件
  （`shader/src/main/java/net/coderbot/iris/pipeline/transform/ShaderTransformer.java`）
  根本没有 RwImageStore 抽取阶段（无 `maybeExtractRwImageStores`、无
  `RwImageStoreExtractor`，`shader/src` 中没有任何 `isSDLGPU` 引用），该 hunk 无落点；
  同名测试 `ShaderTransformerTest`（上游）与 glsm 侧 `SpirvShaderTranslatorTest` 的
  新增用例也未同步（Actinium 没有 spirv 测试任务，且 `SpirvShaderTranslatorTest`
  不存在）。

**#2213（`38af64f1`）**

- glsm：`GlslTransformUtils`（+21，解析/格式化辅助）、`shader/ShaderDiskCache`
  （+5/-1）、`threading/AngelicaWorkers`（+47，空闲钩子与提交计数）。
- sdl-gpu：`SDLGPURenderBackend`、`resource/ResourceManager`（+70，纹理/着色器相关
  缓存登记）、`shader/ShaderManager`（+123/-30，prewarm 重构）、
  `shader/ShaderStageSerializer`；测试 `AttribLocationPatchTest`、
  `AttribLocationReflectionTest`、`StageDiskCacheTest` 更新与新增
  `UnusedSamplerReflectionTest`。
- **Iris 预处理链（按指定移入 `shader/` 模块）**：`preprocessor/JcppProcessor`
  （新增按内容哈希 + 宏集合缓存的 `glslPreprocessSource` 重载）、
  `preprocessor/PreprocessedSourceCache`（新文件，`ShaderDiskCache` 之上的
  preprocess 缓存）、`preprocessor/PropertiesPreprocessor`（接入同一缓存 +
  `cleanLines`/`cleanLine` 重写行清理逻辑以在缓存 key 之前规范化）、
  `include/IncludeProcessor`（并发缓存 + 内容哈希 + `getIncludedFileHash`）、
  `include/IncludeGraph`（经 `AngelicaWorkers` 并行预读 include 文件）。
  **适配**：`PropertiesPreprocessor` 保留 Actinium 既有的 LexerException 容忍
  （跳过坏 macro 而不是整包加载失败），并把因此不再使用的
  `java.util.stream.Collectors` import 去掉。
- **未同步（Angelica 根部 Iris 接线，Actinium 对应物结构不同）**：
  `iris/Iris.java`、`rendering/.../ShaderWarmup`、`utils/AngelicaJar`、
  `gl/program/{Program,ProgramBuilder,ComputeProgram,RetainedPrograms}`、
  `gl/GlResource`、`pipeline/DeferredWorldRenderingPipeline`、
  `pipeline/transform/ShaderTransformer`、`shaderpack/{ConstDirectiveParser,IdMap,ShaderPack}`、
  `texture/pbr/PBRResourceIndex`、`uniforms/custom/CustomUniforms`、`proxy/ClientProxy`。

## 上游同步状态（2026-10-11，Phase 7）

已同步上游四个提交：`61bb779d`（#2204，GL 入口的 context 守卫与字体 provider）、
`6dfb0312`（#2211，pixel store 状态拆分 + legacy 栈查询）、`2a8c8756`（#2215，
后端选项/启动守卫 + 驱动候选）、`12857006`（#2217，buffer mapping 生命周期加固）。
sdl-gpu 与 glsm 侧按下面的范围落地；`src/`（Angelica 根部的 GUI、配置项、字体、
workflows、moltenvk 模块等）按范围跳过。

**#2204（`61bb779d`）**

- glsm：新增 `GLStateManager.requireContext(String call)`（主线程或
  `RENDER_BACKEND.hasContextOnThread()` 之外直接抛 `IllegalStateException`），并在
  18 处入口插入守卫：`glGenTextures`（2 个重载）、`glGenSamplers`（3）、
  `glGenBuffers`（3）、`glCreateBuffers`（3）、`glGenVertexArrays`、
  `glGenFramebuffers`、`glGenRenderbuffers`、`glGenQueries`（2）、`glCreateShader`、
  `glCreateProgram`；`RenderBackend.hasContextOnThread()`（默认 `hasContext()`）；
  `DSAARB.createFramebuffer/createTexture` 与 `DSAUnsupported.createTexture`
  （后者改为走 `GLStateManager.glGenTextures()`）同步。
- sdl-gpu：`SDLGPURenderBackend.hasContextOnThread()`——当前线程是主线程，或该线程
  有 `tlState`（`hasContext() && (mainThread || tlState != null)`）。
- **跳过**：`src/.../client/font/FontProviderCustom.java`（+198/-179）——Actinium 的
  `src/main/java/com/gtnewhorizons/angelica/client/font/` 只有
  `BatchingFontRenderer`/`FontProvider`/`FontProviderMC`/`FontProviderUnicode`/
  `FontStrategist`/`GlResourceInitializer`，**没有该文件**（属未同步的 `src/` 字体
  重构），无落点。其测试同样跳过：rig 测试 `GlsmSdlOffContextCreationTest` 与
  GL 测试 `GLSM_OffContextCreation_GLTest`（Actinium 无 rig / 无真 GL core context）。

**#2211（`6dfb0312`）**

- **`PixelUnpackState` → `PixelStoreState`**：新增
  `glsm/states/PixelStoreState`（pack/unpack 双向：`isPack(pname)` + `with(...)` +
  `applyDiff(from, to, pack)`），删除 `PixelUnpackState` 及其测试。`GLContextState`
  的 `pixelUnpackState` 改类型并新增 `pixelPackState`，以及
  `glPushClientAttrib` 需要保存的
  `clientAttribSavedMask`/`clientAttribSavedPixelUnpack`/`clientAttribSavedPixelPack`/
  `clientAttribSavedUnpackBuffer`/`clientAttribSavedPackBuffer`/
  `clientAttribSavedVertexArray`（按 `GLStateManager.CLIENT_ATTRIB_STACK_DEPTH`）。
- `GLStateManager`：`glPixelStorei` 按 `PixelStoreState.isPack(pname)` 分流到
  pack/unpack；`glPushClientAttrib`/`glPopClientAttrib` 改为 mask 感知（像素存储、
  缓冲绑定、顶点数组三部分，顶点数组部分沿用 Actinium 的
  `ShaderManager.getInstance().getCurrentVertexFlags()/setCurrentVertexFlags` 加新增的
  `isPositionArrayEnabled()`）；`glPopAttrib` 增加 `attribDepth == 0` 下溢守卫 +
  `warnOnce("popattrib-underflow", ...)`。新增 12 个 legacy 查询
  （`GL_ATTRIB_STACK_DEPTH`/`GL_CLIENT_ATTRIB_STACK_DEPTH`/`GL_TEXTURE_STACK_DEPTH`、
  5 个 `GL_MAX_*`、`GL_MAX_TEXTURE_UNITS`、`GL_MAX_LIGHTS`、
  `GL_STENCIL_BITS`/`GL_DEPTH_BITS`，其中 `STENCIL_BITS`/`DEPTH_BITS` 走新增的
  `drawFramebufferBits(boolean stencil)`）、`putLightingInts`、
  `glGetMaterial(int,int,IntBuffer)`、`glGetLight(int,int,IntBuffer)`；
  `GL_CURRENT_TEXTURE_COORDS` 改为按 unit 分支——unit 1 用
  `GLSMConfig.lastBrightnessX/Y`（**注意：Actinium 的亮度坐标在 `GLSMConfig`，
  不在 `GLContextState`**），其余走 `ShaderManager.getCurrentTexCoord(unit)`，unit
  上界用 `VertexKey.MAX_UNITS`（`MAX_LEGACY_TEXTURE_UNITS = min(VertexKey.MAX_UNITS,
  MAX_TEXTURE_UNITS)`）。
- `GLSMRedirector`：ARB FBO 表补 `glGenerateMipmap`，EXT FBO 表补
  `glGenerateMipmapEXT → glGenerateMipmap`。
- 其它类型改名：`TexImage2DCmd`/`TexSubImage2DCmd`/`LTWWorkaround` 的像素存储调用
  随类型更名（注意只改类型字段，**方法名 `forcePixelUnpackState` 保持不变**）。
- 测试：`PixelStoreStateTest` 已同步（位于根工程
  `src/test/java/com/gtnewhorizons/angelica/glsm/states/`，`PixelUnpackStateTest` 删除）。
- **跳过**：`GLSM_LegacyStackQueries_GLTest`（需真 GL core context）、上游
  `GLSMRedirectorTest` 增量（Actinium 是自写 harness，结构与上游不同）、rig 测试
  `GlsmSdlLWJGLServiceRoutingTest`。

**#2215（`2a8c8756`）**

- 新增 `glsm/backend/BackendOptions`（`latch(sdlGpu, driver, glVersion)` +
  `sdlGpuRequested()`/`sdlGpuDriver()`，系统属性覆盖优先）与
  `glsm/backend/BackendStartGuard`（标记文件探测上次启动是否在 SDL GPU 模式下崩过）。
  **形态说明**：`BackendStartGuard` 与 `BackendOptions.latch(...)` 在 Actinium
  **没有调用方**——上游的启用路径在 `AngelicaConfig`/`AngelicaClientTweaker`（`src/`，
  未同步）；按“不注册 SDL 后端、启用路径保持断开”的既定约束，两者作为 API 与测试
  保留，接线时再接。测试 `BackendStartGuardTest` 已同步。
- `SystemProperties`：`USE_SDL_GPU` → `SDL_GPU_OVERRIDE`、`SDL_GPU_DRIVER` →
  `SDL_GPU_DRIVER_OVERRIDE`（新增 `parseBooleanOverride`/`parseStringOverride`，
  空串视作未设置），使“未设置”与“显式 false”可区分。**`KEY_USE_SDL_GPU`
  （`angelica.sdlgpu.enable`）仍是唯一激活开关，未改名**；`MOLTENVK_DIR` 未同步。
- `SDLGPUGate.probe()` 与 `SDLGPURenderBackend.isAvailable()` 改用
  `BackendOptions.sdlGpuRequested()`（保留 Actinium「不检查 `isEngaged`」的偏差）。
- `device/Device`：`resolveDriverName` 删除，改为 `driverCandidates(requested, platform)`
  （MACOSX→`metal,vulkan`、WINDOWS→`vulkan,direct3d12`、其它→`vulkan`；显式 requested
  优先且去重）+ `isVulkan`；`createGPUDevice(debug, candidates)` 逐个候选调用
  `SDL_CreateGPUDevice`，失败逐个告警。原先的 `SDL_SetHint(SDL_HINT_GPU_DRIVER, ...)`
  与会话级 hint 不再设置（驱动名直接进 `SDL_CreateGPUDevice`），因此
  `SDL_HINT_GPU_DRIVER`/`SDL_SetHint` 两个 import 一并去掉。
  `DriverSelectionTest` 按上游更新。
- **MoltenVK 结构化去除**：不移植 `glsm/backend/MoltenVK`、`MoltenVKTest`、`moltenvk/`
  子模块与 `.github/workflows/build-moltenvk.yml`，`Device` 里的
  `SDL_HINT_VULKAN_LIBRARY` 设置与 macOS「缺 MoltenVK」告警分支一并缺失
  （该分支只在 macOS 上生效，Actinium 当前目标平台为 Windows/Linux；macOS 的
  `vulkan` 候选在缺 MoltenVK 时只会创建失败并回落到 `metal`）。
- **跳过**：`moltenvk` 模块、`RendererOptionPages`（+174，GUI）、notfine GUI 删除、
  `AngelicaConfig`/`GLVersionChoice`/`RenderBackendChoice`/`SdlGpuDriver`、
  `dependencies.gradle.kts`/`gradle/libs.versions.toml`/`settings.gradle.kts`、
  workflows、`ReeseSodiumVideoOptionsScreen`（均属未同步的 `src/` 与构建配置）。

**#2217（`12857006`）**

- **plain mapping 的归属从 `ContextState` 移到 `ResourceManager`**：
  `ContextState` 的 6 个 `mapped*` 字段删除，改为一个 `mappingScratch`（`MappedRange`
  临时对象）；`ResourceManager` 新增 `plainMappings` 表 + `MappedRange` 对象池与
  `putPlainMappingIfAbsent`/`takePlainMapping`/`takePlainStaging`/`peekPlainMapping`/
  `copyMappedRange`/`removePlainMappingLocked`。修掉了旧实现「同一时刻只跟踪一个
  plain mapping」的缺陷（第二个 buffer 的 map 会顶掉前一个）。
- `ResourceManager` 其余新增/变更：`putPersistentMapping` → `putPersistentMappingIfAbsent`
  （与 plain mapping 互斥，`swapPersistentMapping` 删除）、`removePersistentMapping`
  仅在真的移除时递增 `mappingsVersion`、`beginPersistentDrain`/`endPersistentDrain`
  （读锁保护的 drain 窗口）、`deleteBuffer` 释放 plain staging、
  `enqueuePersistentCopyDeferred(..., readOffset, size, ...)`（越界即返回 false 让调用方
  回退）、`uploadFromPersistentMapping` 用 `covers` 校验、
  `uploadToBuffer` 拆出 `uploadRangeToBuffer(copyPass, src, srcOff, size, dstOff, ...)`
  （绝对区间上界检查 + 直接按 `srcOff` 拷贝）、`copyShadowRegion` 删除改用
  `ByteRegionCopy.copyByteRegion`、`shutdown()` 释放 plain mapping 与对象池、
  copy callsite 常量 `COPY_CALLSITE_UPLOAD_BUFFER` 删除并新增
  `COPY_CALLSITE_FILL_BUFFER`。
- `PersistentMapping` 新增 `stagingIndex(bufferOffset)` 与 `covers(offset, size)`，
  persistent 的偏移从此都按「GL 缓冲偏移 → staging 索引」换算
  （`PipelineApplier` UBO 直址、`PersistentBufferSync` drain 与镜像拷贝、
  延迟 copy 的 `accept`）。
- `PersistentBufferSync`：`onPersistentBufferWrite` 返回 `boolean`（越界返回 false，
  由后端 `warnInvalidBufferOp` 告警）；`processDirtyPersistentRegions` 在 drain 窗口内
  重新确认映射未被替换、并改用 `pm.offset + off` 作为 GPU 目标偏移；
  `mirrorPersistentCopy(src, ...)` 改收 `PersistentMapping`。
- `SDLGPURenderBackend`：`unmapBuffer` 与 `mapBufferRange` 重写为基于
  `MappedRange`/`ResourceManager` 的实现（删除 `clearMappedState`/`dropMappingFor`）；
  新增 `refuseImmutable`（不可变存储上的 `glBufferData`/`glBufferStorage`/
  `glNamedBufferData` 拒绝并告警）、`freePlainMappingFor`、`warnInvalidBufferOp`
  （按「操作 + buffer」去重的一次性告警）、`respecifyStore` 拆分；
  `glCopyBufferSubData`/`glClearBufferSubData`/UBO `glBufferSubData` 增加越界拒绝；
  persistent map 增加「存储必须带 `GL_MAP_PERSISTENT_BIT`」校验；
  `downloadBufferBlocking` 的 `flushDeferredUploadsForRead` 由既有的
  `flushAndSubmitMidFrame()` 取代（后者是它的超集，含 attrib ring 与 mid-frame submit）。
- `TransferThread`：`StagingReadUpload` 拷贝改为绝对偏移 `memCopy`，越界则丢弃该次
  上传并 `retireSeq` + 释放对象（不再让 `put` 抛异常打断传输线程）。
- `ByteRegionCopy`：新增越界 `IllegalStateException`，非 direct 路径统一走
  `dst.put(dstOff, src, srcOff, len)`（去掉 array/逐字节两条分支）。
- **persistent-mapping 偏差裁决：保留**（条目 6 的 `unmapBuffer`）。
  - 上游 #2217 的加固**没有改变**「`glUnmapBuffer` 释放 persistent staging」这一
    语义：上游 `unmapBuffer` 仍是 `removePersistentMapping` +
    `releasePersistentStaging(pm, glId, "unmap")`；其新增的 `freeAfterSeq` 延迟释放
    只在 `pm.lastEnqueuedSeq > transferThread.getSubmittedSeq()` 时生效，保护的是
    **已经入队的 staging 读取**（transfer 线程提交该序号之前不 memFree），**不保护
    客户端仍持有的映射地址**（`PersistentStreamingBuffer`/Embeddium
    `MappedStagingBuffer` 在上传路径里继续 `memCopy` 到映射地址）。
  - 因此采纳判断：**不能吞并**。Actinium 的 `unmapBuffer` 在
    `getPersistentMapping(glId) != null` 时**返回 true 但不移除、不释放**；persistent
    staging 的生命周期仍是「buffer 删除（`ResourceManager.deleteBuffer` →
    `releasePersistentStaging(..., "delete")`）」或「`shutdown`」，`destroy()` 里
    unmap 后随即 `glDeleteBuffers` 仍会释放。
  - 本提交其余加固（plain/persistent 互斥、`covers` 越界拒绝、drain 窗口、
    `MappedRange`、绝对区间上传、TransferThread 越界丢弃、双层告警）**全部采纳**——
    它们与偏差正交，且正是让「不释放」这条路更安全的部分。
  - `releasePersistentStaging` 的幂等标记 `PersistentMapping.markReleased` 保留在
    新签名内（上游该提交没有幂等标记，用 `freeAfterSeq` 顶替；Actinium 两条释放路径
    可能重叠，重复 memFree 更危险）。
- **跳过**：
  - glsm 侧 `RenderBackend.isBufferImmutable(int)` 与 `GLStateManager` 的 buffer map
    状态跟踪改动（`onBufferStorage`/`onBufferMap`/`onBufferMapRange`/`onBufferUnmap`/
    `bufferForTarget`/`markPersistentlyMapped`，以及只在映射成功时才记账的
    null 检查）。原因：Actinium 的 glsm **没有**这套 buffer-mapping 状态跟踪，
    `markPersistentlyMapped` 的消费者 `FfpExtendedAttribs.markPersistent` 也不存在
    （`glsm/ffp/` 只有着色器生成器 `VertexShaderGenerator`/`FragmentShaderGenerator`/
    `VertexKey` 等），没有落点。SDL 侧的不可变存储拒绝由
    `SDLGPURenderBackend.refuseImmutable` 直接查 `ResourceManager` 实现，与上游
    `refuseImmutable` 同一语义。
  - `pipeline/FFPTrace` 的 `stagingIndex` 修正（上游要按 staging 索引读 UBO 内容并打印
    uniform 值；Actinium 的 `FFPTrace` 只打印 `uboRangeOffset`，不读 staging 内容）。
  - rig 测试 `GlsmSdlBufferMappingLifetimeTest`（+393，需要真 SDL GPU 设备）与
    `GlsmSdlReadbackTest` 的增量（+26，同上）。
- 测试：`ResourceManagerConcurrencyTest`（+4 个 plain-mapping 竞争用例）、
  `PersistentBufferSyncDirtyGateTest`、`PersistentDrainBoundaryTest`（4096→16384
  staging）、`ReleasePersistentStagingTest`（新签名）、`AbsoluteCopyRegionTest`
  （`mirrorPersistentCopy` 新签名）已同步；`sdl-gpu:test` 全绿。
- **遗留风险（仅当 SDL 后端接线后被激活才成立）**：新加的「persistent map 必须有
  persistent 存储标记」校验依赖 storage flags 由 SDL 后端记录。Embeddium 的
  `MappedStagingBuffer` 经 `com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL` 创建
  immutable storage，而 Actinium 的 provider 恒为 `LWJGL3Service`（条目 5），在
  SDL 模式下不会经过 `SDLGPURenderBackend.bufferStorage`；上游因为 provider 会切到
  SDL 服务而不存在该问题。接线 SDL 后端时需一并处理（否则 `MappedStagingBuffer` 的
  persistent map 会被拒绝）。当前后端起用路径是断开的，OpenGL 后端不受影响。

## 上游同步状态（2026-10-11，Phase 8）

**基线**：`shader/`、`glsm/`、`sdl-gpu/` 三个模块的**在范围内**代码自此追平上游
`21a054a4`。`d056bf6f` 之后上游对这三个模块没有其它在范围改动（`21a054a4` 是
CloudRenderer 的 `src/` 修复，见本节末尾的跳过清单）。本节是 SDL-GPU 上游追赶的
收尾，逐条列出移植、适配、跳过与最终遗留。

上游时间顺序为 `3389b952`（#2218）→ `5ed1c925`（#2224）→ `d056bf6f`（#2226）；
任务单把 #2218 排在 #2224 之后，但 `3389b952` 是 `5ed1c925` 的祖先，因此移植时
所有文件统一取 **`d056bf6f` 的状态**（含 #2218 的修复与 #2226 的改动）。

**#2218（`3389b952`）**

- `RwImageStoreExtractor` +13：`imageAtomic*` 的结果被使用时不再被当成可丢的
  「只写」语义（否则设备侧编译失败）。
- `RwImageStoreExtractorTest`（651 行）随该提交建立，本阶段随 #2224 的最终状态
  一并移植（详见下面的测试清单）。

**#2224（`5ed1c925`）**

- sdl-gpu 主源（9 文件 + 资源）：
  - 新增 `compute/InvocationDispatch`（24 行，dispatch 分组的 `MAX_GROUPS_PER_DISPATCH`
    / `WORKGROUP_SIZE` 上界与 `IntUniformWriter` 接口）。
  - 新增 `compute/VertexWriteReplayer`（635 行）：捕获每次绘制的顶点/索引范围、
    在 render pass 结束后重放图像写入、间接绘制标记、图像用法登记
    （`ensureImageUsage`）、`hasPending`/`flush`/`setReplay`。
  - `ComputeBinder`：sampler / RW 存储缓冲 / UBO 三段覆盖
    （`setSamplerOverrides`/`setReadWriteStorageBufferOverrides`/
    `setUniformBlockOverrides`）、`samplerHandle`、
    `beginComputePassWithoutStorageBuffers`、`bindRoStorageBufferHandles`、
    `rebindSamplers`、`uniformBlockView`，并把 UBO 去重缓存加上「同一 program + 同一
    命令缓冲」的条件。
  - `VoxelizationDispatcher` 改用 `InvocationDispatch.dispatch(...)`（上界逻辑抽走，
    `MAX_GROUPS_PER_DISPATCH` 随之下移到 `InvocationDispatch`）。
  - `FrameManager` 新增 `renderPassEndHook`（render pass 结束时回调）。
  - `ResourceManager` 新增 `textureReplaceHook`（纹理被替换/删除/realloc 前回调），
    `createTextureCore`/`deleteTexture`/`releaseTextureHandleForRealloc` 三个入口触发。
  - `SamplerBinder` 拆出 `getSampler(samplerObject, glTexId)` 供重放使用。
  - `ShaderManager.ProgramObject` 增加 `vertexWriteReplay` 字段。
  - `SDLGPURenderBackend`：各绘制入口 `prepareVertexWrites`/`captureVertexWrites`、
    `setVertexWriteReplay`、`memoryBarrier` 与纹理变更前 flush、
    `supportsComputeImageAtomics*`、`flushVertexWritesBeforeTextureChange`。
- glsm：新增 `backend/VertexWriteReplaySetup`（23 行 record）、
  `RenderBackend.setVertexWriteReplay(int, VertexWriteReplaySetup)`（默认空实现）、
  `supportsComputeImageAtomics()`（默认 `true`）。
  **`Lwjgl3GLRenderBackend` 无需改动**——核对上游同提交：`lwjgl3-backend` 同样没有
  覆写这两个成员，GL 后端走基类默认（空实现 / `true`）。
- shader 模块（Actinium 的 Iris 在 `shader/` 模块）：
  - 新增 `pipeline/transform/RwImageStoreExtractor`（1239 行）、
    `pipeline/transform/GlslAstHelpers`（116 行，被 extractor 使用）、
    `pipeline/transform/PreRasterComputeDispatcher`（41 行）、
    `pipeline/VertexWriteReplays`（161 行）。
  - 新增 GLSL 资源 `src/main/resources/assets/actinium/shaders/include/vertex_replay_fetch.glsl`
    （103 行）。**命名空间适配**：上游放
    `assets/angelica/shaders/include/vertex_replay_fetch.glsl` 并写
    `angelica:include/...`；Actinium 的 Iris 相关 include 统一在
    `assets/actinium/shaders/include/`（`ShaderTransformer` 用的
    `actinium:include/chunk_vertex.glsl` 同处），`angelica:` 命名空间留给
    sdlgpu/glsm 的后端着色器，故改用 `actinium:include/vertex_replay_fetch.glsl`。
  - `ShaderTransformer`：新增 `extractRwImageStores`/`extractVertexReplay`/
    `needsUnsupportedAtomics`，并在 `transformInternal` 末尾按上游位置加
    `if (BackendManager.RENDER_BACKEND.isSDLGPU()) extractRwImageStores(result, patchType, RwImageStoreExtractor::supportsAtomics);`。
  - `DeferredWorldRenderingPipeline`：把 gbuffer 的 sampler/image 接线搬进
    `wireGbufferResources(SamplerHolder, ImageHolder, flipped, availability, shadow)`
    （内容原样，不改语义），建好 program 后
    `vertexWriteReplays.attach(name, builtProgram, transformed, (samplers, images) -> wireGbufferResources(samplers, images, flipped, availability, shadow))`，
    `destroy()` 里调用 `vertexWriteReplays.destroy()`。
  - `ShaderPack.activate()`（+ 在 `Iris.loadShaderpack` 里调用）把包的 custom images
    发布给 `RwImageStoreExtractor.setActiveCustomImages(...)`。
- **接线决策（RwImageStoreExtractor）——接线，不是「只移植不接线」**：
  - 钩子点与上游一致（`ShaderTransformer.transformInternal` 末尾、`isSDLGPU()` 门控），
    且下游消费者在 Actinium 齐备：ATTRIBUTES（gbuffers/shadow 顶点）抽出的
    `PatchShaderType.COMPUTE` 由 `DeferredWorldRenderingPipeline` →
    `VertexWriteReplays.attach` 建成 compute 孪生程序（`ProgramBuilder.beginCompute`/
    `buildCompute` 在 Actinium 已有）→ `RenderBackend.setVertexWriteReplay` →
    `SDLGPURenderBackend` 的 `VertexWriteReplayer`。整条 vertex-replay 链端到端打通。
  - **适配 1（artifactsOut）**：Actinium 的 `ShaderTransformer` 没有上游的
    `StageArtifact`/`artifactsOut`，也没有 `ShaderDiskCache` 的 iris-transform 磁盘键，
    故 `extractRwImageStores`/`extractVertexReplay` **去掉 `artifactsOut` 形参**与其
    `artifactsOut.remove(...)`；抽取结果的缓存失效改用 Actinium 的等价物
    `TransformPatcher.clearCache()` + `ShaderTransformer.clearSessionState()`
    （`RwImageStoreExtractor.setActiveCustomImages` 内）。
  - **适配 2（DWRP 结构）**：Actinium 没有上游的
    `beginBuilder`/`wireGbufferProgram` 拆分，改为把 `createPassInner` 里原有的内联
    接线原样搬进新方法 `wireGbufferResources(...)` 并复用，`createPassInner` 只多带
    `name`/`transformed` 两个参数。重放程序需要的 sampler/image 与 gbuffer 程序一致。
  - **已知缺口（已接线，2026-10-12）**：上游把非 ATTRIBUTES 分支抽出的 compute 做成 pre-raster
    前置通道——消费方是 `CompositeRenderer`/`ShadowCompositeRenderer` 的 `preRasterCompute`
    （`PreRasterComputeDispatcher.dispatch`），这套机制来自更早的 #1993（`8da561d3`）。本阶段
    移植时 Actinium 的 shader 模块还没有 `preRaster*`，`PreRasterComputeDispatcher` 因此
    **无调用方**，COMPOSITE/CELERITAS_TERRAIN 的 image 写入被剥离而不重放（其它补丁类型的
    「剥离并丢弃」就是上游行为）。该缺口已由 `812f69e6`（`feat(shader): wire pre-raster compute
    dispatch for extracted image stores`）补齐：消费方接在 `PreRasterComputeStages`（注册/dispatch/
    清理），`CompositeRenderer` 与 `ShadowCompositeRenderer` 各自持有，细节见本文档 Phase 11 一节。
    CELERITAS_TERRAIN 的抽取结果仍由 `CeleritasTerrainPipeline` 消费（Phase 8 随该提交一并移植，
    非本缺口）。
  - **跳过**：上游 gbuffer **variant** 程序的接线（`sourceName + kind.variantSuffix`）
    ——Actinium 的 `DeferredWorldRenderingPipeline` 没有这套变体程序。
  - **跳过**：`InstancedAttribs`/`Instancing`（上游 glsm 的 FFP 实例化）与
    `TransformPatcher.patchAttributesInstanced` 在 Actinium 不存在，
    `VertexReplayExtractionTest.instancedVariantReadsEntityIdFromItsInstanceAttribute`
    用例及其 import 一并去掉。
- **跳过（Dragon API 与 Angelica `src/` 接线）**：`src/api/EyePassRenderer`、
  `src/api/EnderDragonBeams`（GTNH 1.7.10 内容，Actinium 无对应物）、
  `src/mixin/java/.../MixinRenderDragon`、`helpers/RendererLivingEntityHelper`、
  上游 `src/` 侧 `DeferredWorldRenderingPipeline` 的 Dragon 相关改动
  （Actinium 的该文件在 `shader/` 模块，只移植 SDL 相关接线）。

**#2226（`d056bf6f`）**

- sdl-gpu：新增 `compute/ImageAtomicsProbe`（152 行，Metal 运行时探针：编译
  `atomic_probe.csh`、建 compute pipeline、跑一次并回读以判断该尺寸是否支持原生
  image atomics）；`shader/cross/CrossCompileUtil.usesImageAtomics(ByteBuffer)`
  （扫 `SpvOpImageTexelPointer`，并顺带把 `parseLocalSize` 的指令遍历重写为共用的
  `nextInstruction`）；`shader/msl/MslCrossCompile` 的 MSL 2.2 / 3.1 双缓存
  （`compile(spirv, glShaderType, textureAtomics)` 按需选择 `CACHE_3_1`）；
  `Device.readMetalLanguageVersion()`/`metalTextureAtomics()`/
  `supportsImageAtomics(w,h,d)`（按尺寸缓存）；`SDLGPURenderBackend` 的两个
  `supportsComputeImageAtomics*` 覆写；`ShaderManager` 把
  `device.metalTextureAtomics()` 传给 MSL 交叉编译。
- GLSL 资源按 Actinium 约定放 `src/main/resources/assets/angelica/shaders/sdlgpu/atomic_probe.csh`
  （上游放 `sdl-gpu/src/main/resources/assets/angelica/shaders/sdlgpu/`；本仓库
  sdlgpu 后端着色器统一在根 `src/main/resources`，与 `clear_image3d.csh`
  `logic_op.glsl` 同处，加载名 `angelica:sdlgpu/atomic_probe.csh` 不变）。
- glsm：`RenderBackend.supportsComputeImageAtomics(int, int, int)` 重载
  （默认转调无参版本）。
- **适配**：`SystemProperties.FLYBY_DIMENSION`（该提交新增）**未移植**——唯一消费者
  `FlybyRunner` 属未同步的 flyby/profiling 代码，移植会留下没人读取的常量。
  `build.gradle.kts`、`scripts/flyby-*`、`FlybyRunner` 一并跳过。
- 跳过：GPU 测试 `ImageAtomicsGpuTest`、`DeviceImageAtomicsGpuTest`（需要真 SDL GPU
  设备）。

**Phase 8 的测试**

- sdl-gpu：`VoxelizationDispatcherTest`、`MslCrossCompileTest`、
  `FragmentStorageTextureSlotTest` 按上游更新（`MslCrossCompile.compile` 的新形参、
  `InvocationDispatch.MAX_GROUPS_PER_DISPATCH`）。
- 根工程（Actinium 的 shader 模块测试挂在根 `src/test`）：新增
  `RwImageStoreExtractorTest`（44 例）、`VertexReplayExtractionTest`（12 例）、
  `CompositeVertexWriteExtractionTest`（2 例）、`CustomImageAtomicsTest`（3 例）、
  `GlslAstHelpersTest`（4 例）。
- **适配**：
  - `CustomImageAtomicsTest` 上游用 Mockito 的委托替身替换
    `BackendManager.RENDER_BACKEND`；Actinium 没有 Mockito 依赖，改为把
    `src/test/.../glsm/streaming/StubRenderBackend` 提升为 `public` 并派生一个只覆写
    那两个 `supportsComputeImageAtomics` 的替身。
  - `Reflect.setStaticFinal` 原实现用 `Field.modifiers` hack，在 JDK 12 之后必抛
    `NoSuchFieldException`（本阶段第一次真正用到它），改为
    `sun.misc.Unsafe.staticFieldOffset/staticFieldBase + putObject`。
  - extractor 源码里上游用了 Java 22+ 的未命名变量（`_ ->`，
    `addAttr` 的 `computeIfAbsent`），本项目 `-source 21`，改写为具名参数。
  - 上游 `org.embeddedt.embeddium...ShaderLoader` → `dhj.embeddedt...`；
    `ImageInformation.internalTextureFormat()` 返回 Actinum 的
    `net.coderbot.iris.gl.texture.InternalTextureFormat`，故 extractor 的
    `InternalTextureFormat`/`TextureType` import 一并改到该包
    （`VertexWriteReplays` 同）。上游 `InputAvailability.of(...)` 在 Actinium 不存在，
    测试改用构造函数。
- 跳过：`ShaderTransformerTest`（该提交只改 4 行，但 Actinium 从来没有这个文件，
  属未同步的 shader 测试集）。

**跳过清单（最终，Phase 1–8 累积）**

- 上游提交整条跳过：`0dfffca8`（#2061 Flyby 与 OSX 改进）、`c9d46e70`（#2151
  Celeritas 2.5.12 半透明排序）、`af599f29`（#2158 Iris instancing/batching）、
  `38b1d9a0`（#2190 加速启动）、`c97fb700`（#2196，其修复本身已在 Phase 6 采纳）、
  `10922edc`（#2216 补脚本）、`338d9419`（#2197 帧节奏）、`21a054a4`（#2227
  CloudRenderer 世界客户端泄漏，属 `src/`）。
- 范围外模块/主题：`moltenvk` 子模块与 MoltenVK 集成、GUI（RendererOptionPages、
  notfine 删除、ReeseSodium 选项屏）、`src/api/{EyePassRenderer,EnderDragonBeams}`
  等 GTNH 1.7.10 内容、glsm-sdl headless rig 与其 rig 测试、glsm 状态栈 COW 重构、
  per-drawable GLSM context 重构、上游 `lwjgl3-backend` 模块本体、`src/` 的
  Iris 接线/GUI/字体/flyby/profiling 主体、构建配置（`dependencies.gradle.kts`、
  `libs.versions.toml`、`settings.gradle.kts`、workflows）。

## glsm 状态栈 COW 重构（2026-10-11，COW-A）

把 glsm 的状态栈从 Actinium 的「push 时 eagerly 记账 + 全局 depth 数组」换成上游
tip（`21a054a4`）的「按槽位 copy-on-write」模型。上游来源是 `9dd60f6a`（#2124）
以及它之后的 `44c930f4`（#2138）里依赖 COW 的 glsm 部分。Phase 3 曾实测：把
#2124 的补丁对 Actinium 当时的文件做 `--fuzz=0` dry-run，GLStateManager 82 个 hunk
有 66 个冲突，因此当时搁置。本次按用户裁决「以 `21a054a4` 的语义为目标重写模型、
再把 Actinium 扩展重新挂上」执行，不做逐 hunk 拼贴。

**文件**

- 新增：`stacks/CowStateStack`、`CowDepths`、`CowDispatch`、`StackIdAllocator`、
  `glsm/StateSet`、`glsm/ClipPlaneBooleanState`、`states/ScissorState`、
  `stacks/ScissorStateStack`。
- 改写为上游形态：22 个 `stacks/*Stack`（`Alpha/Blend/Boolean/Color4/ColorMask/
  Depth/Fog/Integer/LightModel/Light/Line/Material/MatrixMode/Point/Polygon/Stencil/
  TextureBinding/TextureUnitBoolean/Vec3f/Vec4f/ViewPort/Scissor`）、
  `states/TexEnvState`（变成 `CowStateStack`）、`states/TextureUnitArray`
  （每个单元栈分配 id，暴露 `idStacks()`）、`Feature`（改为 `maskToMembers(long[])`
  的位图映射）、`GLContextState`、`GLStateManager`。
- 删除：`stacks/IStateStack`（Actinium 的 4 行别名；改用 GTNHLib 的
  `com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack`，与上游一致）。
- 未新增：`stacks/RetainedState`（见下「未移植」）。

**模型**

- `StateSet.forMask(mask)` 把 `glPushAttrib` 的掩码展开成：`members`（该组保存的
  栈 id 位图）、`restore`（恢复时要驱动后端的组位 `R_*`）、`bump`（该组可能要让
  哪些 generation 计数器前进 `B_*`）、`blendSnapshot`（是否需要为 vanilla blend
  钩子做快照）。
- 每个栈自带 `CowDepths`（id + owner 上下文 + 槽位深度栈）：
  `beforeModify()` 在「当前 attribDepth 内、该组保存此栈、且尚未为这层 claim 过」
  时 `captureSlot(claim(depth))` 并 `registerModifiedState(owner, id)`——写入点于是
  只在真正发生修改的栈上付费。
- `popState()` 只看 `modifiedIds[depth]`：组成员按 `CowDepths.pop()` + `CowDispatch`
  取槽恢复，并在 `topSlotChanged()` 时点亮 `changed` 位；非组成员用
  `CowDepths.discard(poppedDepth)` 把槽位「提升」给父层并重新登记（父层 pop 时再恢复）。
- 恢复阶段 `issueRestoredBackendState` 只在 `(restore & R_x) != 0 && (changed & R_x) != 0`
  时下发布局调用；`applyRestoredState` 按 `bump` 比较 push 时快照，决定 generation 是否前进。
- 栈 id 由 `StackIdAllocator` 在上下文构建窗口内顺序分配（`beginContext/endContext`
  校验跨上下文的数量一致性），`GLContextState.init()` 建 `stackById/depthsById/
  restoreBitById/restoreUnitById/kindById` 与 `modifiedIds/modifiedCount`。

**`ctx()` 的处理决策**：保留 Actinium 的每线程 `ctx()`（`primaryContext` +
worker `ThreadLocal`）。核对上游 tip 后发现上游也有同样的每线程结构
（`mainCtx`/`tlWorkerContext`/`workerContextsActive`），只是额外带了
`drawableContexts`（#2142 的 per-drawable 上下文 = COW-B）。因此 COW-A 不动 `ctx()`
的解析逻辑，只把上下文创建的两个入口（`primaryContext()`、`enterWorkerContext()`）
包进 `StackIdAllocator.beginContext()/endContext()`。

**关键适配**

1. 上游靠**调用点**的 `mod(...)`（= `beforeModify()`）触发惰性 claim，而 Actinium
   原来是 push 时用 `Feature.maskToNonBooleanStacks(mask)` 对非布尔栈逐个
   `pushDepth()`。为保持上游语义，在 `GLStateManager` 的**158 处**状态写入点加上
   `mod(...)` 包装；写入点由脚本按「跟踪栈字段 + setter 名白名单」生成，并用
   「把 `mod(...)` 解开后必须与旧文件逐行完全一致」的校验脚本确认无一处被改坏。
   注意 `restoreSlot`/`captureSlot` 自身不经过 `mod(...)`，因此恢复过程不会再 claim。
2. `MAX_ATTRIB_STACK_DEPTH` 18 → 32（上游值），新增 `STATE_SLOTS`。上游的
   `STATE_SLOTS = MAX+1` 额外那一个槽是 `RETAINED_SLOT`，属未移植的 RetainedState，
   这里取 `STATE_SLOTS = MAX_ATTRIB_STACK_DEPTH`。
3. `ShaderManager` 在 Actinium 是静态单例（上游是每上下文实例），它的
   `normalStack`/`texCoordStack` 拿不到构建期 id，改为在 `GLContextState.init()`
   里 `setStackId(StackIdAllocator.nextId())` 并登记。**已知语义弱化**：这两个栈
   在多个上下文间共享同一个 `CowDepths.owner`，owner 指向最后初始化的上下文；
   主线程状态路径不受影响，worker 上下文下不如上游精确，属 COW-B（每 drawable
   上下文）要解决的范围。
4. `#2138` 依赖 COW 的部分补齐：`glScissor` 现在记账到 `ctx().scissorState`
   （`mod(...)`）并保留后端调用、`glGetIntegerv(GL_SCISSOR_BOX)` 从该栈返回、
   `replayStateToBackend` 重放 scissor、`Feature` 的 `GL_SCISSOR_BIT` 含
   `getScissorState()`、新增 `rasterizerDiscard` 状态与 4 处 capability 分支
   （`glEnable/glDisable/isEnabled`×2）。
5. **未移植（记录原因）**：
   - `RetainedState`/`retainModifiedState`/`applyRetainedState`/`RETAINED_SLOT`：
     唯一消费者是未同步的 `src/` HUDCaching，移植会留下无人调用的 API。
   - `BatchStateGuard`/`beforeUncapturedStateChange`/`batchStateGeneration`
     （上游批量绘制特性；Actinium 无 BatchStateGuard）。
   - `DisplayListManager.virtualStateDepth/recordPushState/recordPopStateIfPending`
     （上游显示列表状态记录；Actinium 用 `recordPushAttrib(mask)`）。
   - `programStack`/`R_PROGRAM` 与 `StateSet` 的固定分组
     （`BATCH/FONT/FONT_PIPELINE/HAND/BLEND/ALPHA/CUTOUT/MASKS/CULL`，以及
     `pushCommand`）：消费者是上游未同步的字体/HUD 批量状态推送。
   - `FeedbackManager.getRenderMode()`：消费者是后续提交里 `glClear` 的反馈模式早退。
   - `glClearDepth` 的 `[0,1]` clamp（`44c930f4` 里的非 COW 修复，属另一主题）。
   - `attribPushes/attribSlotsSaved/attribValueRestores/attribBackendCalls` 计数：
     上游仅供 `GLSM_StateSet_GLTest` 等 GL 测试与性能诊断使用，本阶段不引入死计数。
   - 同一提交给 `Feature` 加的不是列表而是位图；Actinium 的 `states/GenerationTrackedState`、
     `states/VertexAttribState`、`ImageUnitArray`/`SamplerUnitArray` 不是 COW 栈，原样保留。
   - 上游状态类里与 COW 无关的改动（`LightState`/`MaterialState`/`LightModelState`
     的 raw-position 缓存、`shouldBypassCache → isCachingEnabled` 改名、
     `BooleanState.onEnabledChanged` 之外的批量钩子）未随本阶段移植；只为
     `ClipPlaneBooleanState` 补了 `BooleanState.onEnabledChanged()` 钩子。

**Actinium 扩展重挂清单（逐项核对）**

| 扩展 | 现状 |
|---|---|
| glColor clamp（#181） | 保留（`changeColor` 内 `Color4.clamp01`，未改） |
| vanilla 纹理镜像同步（#186） | 保留（`VanillaStateLayer`/`vanillaLayer` 进入上游 stack 形态） |
| `GLESCaps` + `isGLES/hasClipCullDistance/glesVersion` | 保留（未触及） |
| `pixelStoref`、`remapPixelTypeForGLES` | 保留（未触及） |
| `replayStateToBackend`/`takeStateSeedPending`/`warnOnce` | 保留，并补 scissor 重放 |
| `BooleanState.applyToBackend` | 保留 |
| `SamplerUnitArray`（Phase 2） | 保留 |
| `BlendStateStack.clearFuncUnknownState` | 保留，恢复路径按上游语义收窄 |
| `MainThreadPump`/gate/refresh、`BackendOptions`/`BackendStartGuard`（无调用方） | 保留 |
| `hasContextOnThread` + 18 处 `requireContext` | 保留 |
| `TextureStaging`/`AngelicaWorkers`（Phase 5） | 保留（`ctx().pixelUnpackState` 等访问点未变） |
| `reset()`/`invalidateStateMirror`（Phase 3） | `reset()` 改按 COW 的 `allStacks.drain()` + `attribSets/modifiedCount` 清零；`invalidateStateMirror` 未变 |
| streaming 缓冲的 `onPersistentBufferWrite` 偏差 | 保留 |
| `BlendStateStack.isEnabled()`（调试日志用） | 保留（上游 stack 无此方法，重挂） |
| `assertMainThread`/`GpuCommandDiagnostics` 等 Actinium 自有工具 | 保留（未触及） |

**语义变化**

1. 引入惰性 COW：push 不再为整组栈付拷贝开销；pop 只对 `modifiedIds` 里的栈取槽恢复。
2. 空 scope（push 后什么都没改）在 pop 时**不再下发**任何后端调用（旧实现按 mask 无条件
   重发 depth/blend/colorMask/… 调用）。这是本阶段最直接的性能收益。
3. blend 恢复按上游收窄：只有 `R_BLEND` 且（`changed` 命中或 `isFuncUnknown()`）才
   `issueBlendFunc`，且只有 `changed` 命中才补发 equation/blendColor；同时该路径改用
   `issueBlendFunc`，因此 `GLSMConfig.hudCacheOverride` 在恢复路径上也生效（上游语义）。
4. `glPopAttrib` 的空栈守卫、`GLDebug` 分组、显示列表记录路径保持 Actinium 原样。
5. 新增 scissor 矩形记账与 `GL_SCISSOR_BOX` 查询（`GL_SCISSOR_BIT` 组可保存/恢复）。
6. `MAX_ATTRIB_STACK_DEPTH`（以及 `glGetIntegerv(GL_MAX_ATTRIB_STACK_DEPTH)` 的答复）
   从 18 变为 32。
7. 因为每栈槽位只在被修改时 claim，`GLStateManager.registerModifiedState` 的签名
   从 `(IStateStack)` 变为 `(GLContextState, int id)`（glsm 内部 API）。

**测试**

- 新增纯 Java 用例 `src/test/java/com/gtnewhorizons/angelica/glsm/CowStateStackTest.java`
  （9 例）：分组成员/`restore`/`bump`/`blendSnapshot` 断言、pop 只恢复被改过的栈并到达
  后端、**空 scope 零后端调用**、`GL_SCISSOR_BIT` 组的 scissor 恢复、嵌套作用域顺序、
  「被 pop 的组未保存的栈由父层恢复」的槽位提升、`CowDepths` 单元语义（claim/pop/discard）、
  `IntegerStateStack` 的 push/pop 往返。用安装到 `BackendManager.RENDER_BACKEND` 的
  记录式替身（继承 `StubRenderBackend`）让 `pushState/popState` 能在无 GL 上下文下跑通。
- **跳过**（按约定，需要真 GL core context / glsm 测试台）：上游
  `GLSM_PushPop_UnitTest`（`@GLCompatTest`）、`GLSM_StateSet_GLTest`、
  `GLSM_ValueStackCow_GLTest`、`GLSM_PerThread_UnitTest`、`GLSM_DirtyTracking_UnitTest`、
  `GLSM_RetainedState_GLTest`、`GLSM_PopAttribMaskFidelity_GLTest`、
  `GLSM_WorkerContextStacks_GLTest`、`GLSM_DisplayList_StateSet_GLTest`。
- `GLSMRedirector` 无需改动：本阶段没有改名/删除 GLStateManager 的公开 GL 入口方法
  （公开面只多出 `getScissorState()`，不是重定向目标）。

**验证**：`./gradlew :glsm:compileJava :sdl-gpu:compileJava :shader:compileJava
compileJava compileTestJava`、`./gradlew :glsm:test :sdl-gpu:test`、`./gradlew :test`、
`./gradlew check` 全绿。

## glsm per-drawable context（2026-10-11，COW-B）

把 GLSM 的上下文从「每线程 only」换成上游 `a8c29fa3`（#2142）的「每个 GL drawable 一个
GLSM context」，目标是上游 tip（`21a054a4`）语义。COW-A 已经把栈的 id / `CowDepths.owner`
按上下文划分好，本阶段补上上下文本身的注册与切换，并把仍然全局共享的每上下文状态搬进
`GLContextState`。

**drawableContexts 模型（实现形态）**

- `GLStateManager`：`primaryContext`（display drawable 用）、`mainCtx`（主线程当前绑定的
  上下文，`bindThreadContext` 维护）、`tlWorkerContext`（非主线程绑定）、
  `drawableContexts`（`IdentityHashMap<Object, GLContextState>`）与
  `drawableContextStates`（同一批上下文的数组，供跨 drawable 清理使用）。
  Phase 3 的 `registeredContexts`（CopyOnWriteArrayList）删除。
- `ctx()`：主线程 → `mainCtx`，为空则 `primaryContext()`；非主线程 → 绑定的上下文
  （worker 或 drawable），否则 `primaryContext()`。与上游 tip 的该方法逐行同构。
- `newContext()`：`StackIdAllocator.beginContext()` / `new GLContextState()` / `init()` /
  `endContext()`，与 COW-A 的 id 分配约定一致（每个上下文的 id 数量必须相同，
  `endContext()` 会校验）。
- `bindThreadContext(target)`：设置/清除线程绑定，并维护 `workerContextsActive`；
  在 `MainThread` 上同时更新 `mainCtx`。
- `contextFor(Drawable)`：非 display drawable 首次 makeCurrent 时创建上下文，按
  `Display.getWidth()/getHeight()` 初始化 viewport 与 scissor（`setDrawableSize`），
  登记到 map + 数组。上游此处还会从 primary context 复制 stencil bit mask
  （`applyStencilBitMask`）；Actinium 的 stencil mask 状态不在 `GLContextState` 上
  （见“适配”），该步未移植。
- `makeCurrent(Drawable)`：`bindThreadContext(isDisplayDrawable(d) ? null : contextFor(d))`
  → 走 `RENDER_BACKEND.handleMakeCurrent(drawable)`（未处理则 `drawable.makeCurrent()`）
  → 绑定变化且未 handled 时 `replayStateToBackend()` → 然后才做 Actinium 原有的
  splash/`drawableGLHolder` 缓存判定。异常路径回滚线程绑定。
- `releaseContext(Drawable)`：新增（Actinium 之前没有），走
  `RENDER_BACKEND.handleReleaseContext(drawable)` + `bindThreadContext(null)` +
  清 `drawableGLHolder`。
- `isCachingEnabled()`：`splashComplete || t == drawableGLHolder || tlWorkerContext != null`
  —— 持有 worker/drawable 绑定的线程也参与缓存（上游语义）。
- 删除/新增：`onDeleteTexture` 现在遍历 `ctx()` 与 `drawableContextStates`，把被删纹理名
  在**所有 drawable 上下文**的缓存绑定里清掉（纹理名会被驱动回收）。

**搬进 `GLContextState` 的每上下文状态**（上游同提交把 GLStateManager 的静态搬到这里；
Actinium 沿用上游字段名，除注明外）：

`lockBindCallback`、`wideLineEmulationActive`、`lineStippleActive`、`defaultVAO`、
`foreignDrawDepth`、`vaoEboMap`、`vao`（Actinium 的 `VertexAttribState` 实例，替代上游
`ffp/VAOManager`）、`ffp`（`ShaderManager` 实例）、`multMatrix`、`conversionMatrix4d/4f`、
`gluMatrix`/`gluMatrix2`/`gluVec4`/`gluBuffer`、`texGenTempPlane`、`shaderBuf`、
`projectionMatrixBuffer`、`lastBrightnessX/Y`。

- `ffp/ShaderManager` 由静态单例改为**可实例化 + 静态访问器**：`getInstance()` 返回
  `ctx().ffp`，`currentNormal`/`currentTexCoords`/`normalStack`/`texCoordStack`/
  `normalGeneration`/`texCoordGeneration` 变为实例字段，`getNormalGeneration()` 等静态
  访问器改为读 `ctx().ffp`；`enabled` 保持全局静态（FFP 是进程级开关），构造函数
  `active = enabled`。`VertexFormat.registerSetupBufferStateOverride` 从 `enable()` 移到
  静态初始化块，并写入 `ctx().ffp`（不再捕获某个实例），避免多 drawable 下最后注册者
  通吃。program cache 变静态共享（`ShaderManager.destroy()` 不再 `cache.destroy()`）。
  **这解决了 COW-A 记录的遗留 1**：normal/texcoord 栈不再跨上下文共享 `CowDepths.owner`。
- 亮度坐标从 `hooks/GLSMConfig.lastBrightnessX/Y` 静态字段搬进 `GLContextState`，
  并按上游加上 `GLSMConfig.packBrightness/packedLastBrightness()`（后者读 `ctx()`）。
- `states/VertexAttribState` 由全静态工具类改为**实例类**（每个上下文一个，
  `GLContextState.vao`），全部调用点改为 `GLStateManager.ctx().vao.x(...)`

**worker context 接线决策**：核对上游 tip —— `enterWorkerContext()/exitWorkerContext()`
在 glsm 与 `src/` 里**只有测试调用**（`GLSM_PerThread_UnitTest`，需要真 GL context），
生产路径靠 `makeCurrent` 的 drawable 绑定 + `primaryContext()` 兜底。因此本阶段**不加**
Actinium 专有的 chunk-worker 钩子：`enterWorkerContext` 保留为 API，语义改为经
`bindThreadContext`（含 `already bound` 守卫），celeritas 的并行区块构建线程继续走
「未绑定 → `primaryContext()`」的既有兜底（与改动前一致，`isCachingEnabled()` 只对显式
绑定的线程放开缓存）。若将来要让 worker 线程完全隔离，需要在 Actinium 的
celeritas/渲染入口显式调用 `enterWorkerContext()`——那是 Actinium 侧调用方的改动，
上游没有对应代码可抄。

**SDL 侧**：核对 `a8c29fa3` 的 3 个主源文件 —— `ContextState.defaultFboId`/
`mirroredContext`、`SDLGPURenderBackend.enterContext`/`beginSplashFrame`/`bindFramebuffer(0)`
重映射/`deleteFramebuffers` 重绑/`drawableEnum` 系列、`OffscreenTarget.isFor` 用
`defaultFboId`：Phase 3 已落地绝大部分，本阶段只补上 `OffscreenTarget.isFor` 的
`boundFboId → defaultFboId` 一处。**COW-B2（2026-10-11）补齐了 COW-B 留下的半转换状态**：见下一节。

**因此 SDL 镜像语义现在与上游一致**：同线程换 drawable
时 `st.mirroredContext != ctx` 成立 → `invalidateMirror()`，不再是 Phase 3 的“可接受弱化”。

**适配与未移植（记录原因）**

- 上游 `applyStencilBitMask`（新 drawable context 继承 primary 的 stencil bit mask）：
  Actinium 的 stencil mask 记录在 `StencilStateStack` 上但没有 `GLContextState.stencilBitMask`
  这一汇总字段，且没有上游 `glStencilMask` 的全局 mask 语义；
  新上下文由 `glStencilMask*` 首次调用填默认值。未移植。
- `hooks/TextureUtilHooks`、`ffp/VAOManager`、`ffp/FfpExtendedAttribs`、
  `streaming/StreamingVaos`：Actinium 没有这些类（分别由
  `GLStateManager`/`states/VertexAttribState`/`streaming/*` 承担），其 per-context 意图
  已通过上表的搬移与 `VertexAttribState` 实例化覆盖。
- **DisplayListManager / Uniforms / QuadConverter / CompatUniformManager /
  RenderSystem / streaming/TessellatorStreamingDrawer**：上游同提交对它们做了 per-context
  化（把静态状态改为经 `ctx()` 访问），但这些文件在 Actinium 已大幅分叉（与上游 parent
  的差异 250–1500 行不等）。本阶段**只做了能安全落地的部分**：`DisplayListIDAllocator`
  的 4 个方法加 `synchronized`（多 drawable/多线程共享 id 分配器）；`FeedbackManager`/
  `AttribSnapshot`/`VertexKey`/`FragmentKey` 经 `ctx().vao`/`ctx().wideLineEmulationActive`
  等取每上下文状态（由 `VertexAttribState` 实例化与属性搬移顺带完成）。其余 per-context
  状态（DisplayList 的编译/记录缓冲、Uniforms 的常量缓存、QuadConverter 的临时数组、
  TessellatorStreamingDrawer 的 buffer）在 Actinium 仍是静态/单例：多 drawable 场景下会
  共享，单 drawable（当前接线路径）下与前行为一致。
- `GLSMInitConfig.streamingDrawerDestroy`：上游改为每上下文 drawer 后删掉了这个钩子；
  Actinium 的 `TessellatorStreamingDrawer`/`BufferBuilderStreamingDrawer` 仍是静态的
  （其 per-context 化属上述未移植部分），因此**保留**该钩子以免破坏
  `MixinOpenGlHelper` 的调用，并**不再**从每上下文的 `ShaderManager.destroy()` 调用它
  （否则一个上下文销毁会连带销毁共享 drawer）。
- `hooks/FrameHooks.bindSplashVao()` 上游删除；Actinium 没有该调用方，无操作。
- 上游 `GLContextState` 里与 drawable 无关的其它新增（`internalGlDepth`/`internalDrawDepth`/
  `savedFilterTextureId`/`fontVao`/`ffpInstancing` 等）Actinium 没有对应状态，未引入。

**测试**：`TestThreads`（glsm testFixtures，纯 Java 线程夹具）与上游
`DisplayListIdAllocatorTest`（含新增的 `concurrentAllocationsRemainUnique`，验证
`synchronized` 那 4 个方法）已移植到根 `src/test`；
`GLSM_SharedDrawableContext_GLTest`、`GLSM_SplashCompleteLatch_UnitTest`（`@GLCoreTest`）、
`GLSM_ServerSideState_UnitTest`（`@GLCompatTest`）、`GLSM_PerThread_UnitTest`
与 `DrawableContexts` 夹具（需要真 `SharedDrawable`）按约定跳过。drawable context 的
注册/切换本身**没有单元测试覆盖**（必须真 drawable），需 rig 或实机验证。

**验证**：`./gradlew :glsm:compileJava :sdl-gpu:compileJava :shader:compileJava
compileJava compileTestJava`、`./gradlew :glsm:test :sdl-gpu:test`、`./gradlew :test`、
`./gradlew check` 全绿。

## glsm 每上下文 FFP 状态补齐（2026-10-11，COW-B2）

COW-B 只把「上下文注册/切换」和一批 GLStateManager 静态搬进了 `GLContextState`，六个
大文件仍是半转换（静态状态与 `ctx()` 混用）。本节把它们收尾，规则同前：以上游 tip
（`21a054a4`）语义为目标重写，保留 Actinium 自己的实现与扩展，凡是上游的每上下文答案
依赖 Actinium 故意没有的东西（HUDCaching、BatchStateGuard、上游的 draw lock /
display-list `virtualStateDepth`），就用 Actinium 的惯用法做最小等价实现。

**逐文件**

- `streaming/TessellatorStreamingDrawer` + 新增 `streaming/StreamingVaos`：per-format 的
  VAO id 数组（`persistentVAOs`/`orphanVAOs`）从 TSD 静态数组搬进新的每上下文 holder
  `StreamingVaos`（`GLContextState.streamingVaos`，与上游同名类同形）。上游为此删掉了
  `persistentVaoOwners`/`orphanVaoOwners` 这两组「哪个线程创建了这个 VAO」的静态数组
  ——**本仓库同样删除，并记录理由**：VAO 数组现在按上下文隔离，一个上下文的数组只可能
  含它自己创建的 id，不再存在 issue #150 的「已死 splash 上下文的 id 被驱动回收后指向
  新上下文里的活 VAO」问题；`destroy()` 改为删除**当前上下文**的 VAO（共享的
  `persistentBuffer`/`orphanBuffers`/repack/ext 暂存仍为静态，与上游一致）。
- `CompatUniformManager` + `states/GenerationTrackedState`：上游给 `ProgramUniforms` 加了
  `owner`，上下文变化时清掉「已上传到第几代」标记。Actinium 的对应物是
  `CompatProgramUniformState`（继承 `GenerationTrackedState`），因此把该守卫做在基类：
  新增 `bindOwner(GLContextState)`，owner 变化即 `initialized = 0`；在
  `CompatUniformManager.onUseProgram` 与 `refreshCurrentProgramMatrices` 里绑定当前上下文。
  **这是本次修掉的真实每上下文缺陷**：同一个程序先被 drawable A 上传过，切到 drawable B
  后生成号不同（或相同但值不同）时，旧标记会让 B 跳过上传。上游给 map 加
  `acquireDrawLock` 的部分不需要——Actinium 的 `CompatProgramUniformStates` 自身
  `synchronized`。
- `RenderSystem`：`PROJECTION_MATRIX_BUFFER`（public static final 暂存 FloatBuffer）搬到
  `GLContextState.projectionMatrixBuffer`，`setupProjectionMatrix` 改用
  `ctx().projectionMatrixBuffer`（上游同名的每上下文暂存）。能力探测类静态（`supports*`/
  `max*`/`isGLES`/`glesVersion`/`isLTW`/`dsaState`）与 GLES 处理、`remapPixelTypeForGLES`
  等扩展原样保留——它们是进程级/后端级事实，不属于每上下文状态。
- `QuadConverter`：**核对后不离散**——上游 tip 也把 quad/scratch EBO（`eboId`/`maxQuads`/
  `scratchEbo*`）留作静态共享（EBO 是共享对象，且上游仍以静态方式持有），每上下文相关
  的读取 Actinium 早已走 `ctx()`（`preDraw` 内部 `ctx()`、`getBoundEBO()` →
  `ctx().boundEBO`）。本次只修一处生命周期问题：`ShaderManager.destroy()` 不再调用
  `QuadConverter.destroy()`（否则销毁某一个上下文会删掉共享 EBO），改由既有的全局收尾
  钩子（`MixinOpenGlHelper` 的 `streamingDrawerDestroy` lambda）调用，那里也一起销毁
  `TessellatorStreamingDrawer`/`BufferBuilderStreamingDrawer`/`EndPortalCompositeRenderer`。
- `ffp/Uniforms`：**核对后无需改动**——它没有静态可变状态；上传标记
  （`normalGen`/`texCoordGen`/…）是 `Uniforms` 实例字段，而 `Uniforms` 属于
  `ShaderManager`（COW-B 起每上下文一个）；它读取的 `ShaderManager.get*Generation()`/
  `getCurrentTexCoord()` 等静态访问器在 COW-B 已改为读 `ctx().ffp`。上游把 `glCtx` 显式
  传参只是形态差异，不改变语义。
- `DisplayListManager`：显示列表录制是**线程作用域**而非上下文作用域。补齐上游的守卫：
  `isCompileAndExecute()`/`describeCurrentCompilation()` 改用 `isRecording()`（线程感知）、
  `pauseRecording`/`resumeRecording` 加录制线程校验（resume 跨线程直接抛错）、
  `getRecordingListId`/`getListMode`/`trackDrawRangeSource` 非录制线程返回中性值、
  `glNewList`/`glEndList` 加 `synchronized` + 「另一线程正在录制」快速失败。
  读取 `displayListCache` 的两个入口用 `synchronized`（Actinium 没有上游的 draw lock，
  用同一把类锁与 `glNewList`/`glEndList` 互斥）。Actinium 自己的 `recordPushAttrib(mask)`
  记录模型不变。**未移植**：上游 `glNewList` 里的失败回滚（try/catch 释放 recorder 与
  捕获层）——那是健壮性改进，与每上下文无关，记录为后续可选。
- `applyStencilBitMask`（COW-B 的开口项，**已裁决并实现**）：按上游加
  `GLContextState.stencilBitMask`（默认 `0xFFFFFFFF`）；`GLStateManager.init` 用
  `GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE`（core profile 下 `GL_STENCIL_BITS` 已移除）
  算出掩码、为 0 时告警，并用它初始化缓存的 value/write 掩码与驱动掩码；新建 drawable 上
  下文继承 primary 的掩码；`glStencilMask`/`glStencilMaskSeparate` 把应用给的掩码按
  `ctx().stencilBitMask` 夹紧后写入缓存（与驱动自身的夹紧一致，避免缓存里留下驱动从未
  持有的值）。这样 Actinium 的 stencil 语义与上游一致，且不再有开口项。
- `GLSMInitConfig.streamingDrawerDestroy`：保留（Actinium 的 drawer 的**共享**部分仍由它
  收尾），但不再从每上下文的 `ShaderManager.destroy()` 调用；其 lambda 增加
  `QuadConverter.destroy()`（见上）。

**测试**：新增根 `src/test` 用例
`com.gtnewhorizons.angelica.glsm.PerContextStateTest`（3 例：绑定第二个上下文后
`ffp`/`vao`/`streamingVaos`/`vaoEboMap`/normalStack 都是独立实例；写一个上下文的状态不会
泄漏到另一个；`StreamingVaos` 数组按上下文隔离）与
`com.gtnewhorizons.angelica.glsm.GenerationTrackedStateTest`（2 例：同一上下文的标记保留、
切换上下文后标记失效）。两者都先装一个只回答 `getInteger` 的 `StubRenderBackend` 替身
（`BackendManager.RENDER_BACKEND` 通过 `Reflect.setStaticFinal` 换成替身），因为
`GLStateManager` 的类初始化会查询后端，真实后端在没有 GL context 时会崩 JVM。同时给三个
`VertexAttribState*` 测试补上同样的替身（COW-B 让它们开始经过 `ctx()`，之前只是靠测试执行
顺序侥幸没崩）。上游的 GL/rig 测试（`GLSM_SharedDrawableContext_GLTest` 等）继续跳过。

**验证**：`./gradlew :glsm:compileJava :sdl-gpu:compileJava :shader:compileJava
compileJava compileTestJava`、`./gradlew :glsm:test :sdl-gpu:test`、`./gradlew :test`、
`./gradlew check` 全绿。

## 上游同步状态（2026-10-12，光影包加载提速：`38b1d9a0` + `38af64f1`）

- **`38b1d9a0`「Speed up boot (#2190)」**：落地 GLSL 保留字重命名的字面量优先正则
  （+`ReservedWordRenameTest`）、`GLSMHooks.uniformBlockBarrier` 与 SDL `ShaderManager`
  的消费点、新增 `iris/texture/pbr/PBRResourceIndex`（含 #2213 的 memo 增量）+
  两个 PBR 加载器的 `mayExist` 短路 + 资源重载时重建索引（Actinium 用 reload listener，
  上游用 mcpatcher 的 `TexturePackChangeHandler`，本仓库无该包）。
  **未落地**：`Iris` 的预热重构与 `IrisProgramSetWarmupTest`——它们依赖 Actinium 从未移植的
  早期变换预热链路（`warmupShaderTransforms`/`PerFrameUniformBlockHarvester`/
  `DeferredWorldRenderingPipeline.warmupTransforms`，来自上游 #1993/#2009），
  因此 barrier 目前只有消费侧、无人设置（已在文档中标注）。
  ⚠️ **后续（2026-10-12）**：上述整条预热链已补齐，`#2190` 这部分随之落地，
  barrier 的消费侧终于有生产端。逐文件对账与偏差见
  `sdl-gpu-shader-loading.md` §6。
- **`38af64f1`「Initial commit for shader-loading-optimizations (#2213)」**：对账后确认
  glsm 侧（`containsWholeWord`、`AngelicaWorkers.invokeAll`、`ShaderDiskCache.configure` 幂等）
  与 sdlgpu 侧（`QueuedCompile`、`registerSamplerLocations`、顶点变体落盘、`ShaderStageSerializer`
  的 `unusedSamplerNames`、`ResourceManager.fillBuffer`/`zeroTexture3D`、clear/3D 零填充路径）
  **早前 Phase 已同步**；本次补三处缺口：`ConstDirectiveParser` 行扫描、`ShaderPack` 改用
  内容哈希重载的 `JcppProcessor.glslPreprocessSource`（**接上此前无人查询的预处理磁盘缓存**）、
  以及 `AngelicaJar` + 在 `Actinium.onInit` 启用 `<gameDir>/angelica/shadercache`（使既有的
  GLSL/SPIR-V/预处理磁盘缓存从"从不启用"变为生产可用）。
  **未落地（受阻）**：`ProgramBinaryCache`/`RetainedPrograms`/`ShaderWarmup`/`Iris` 的
  `prepareSelectedShaderPack` 与 `activate()`/`ProgramBuilder`/`Program`/`ComputeProgram`/
  `DeferredWorldRenderingPipeline` 程序持久化——它们全部建立在上游前置提交 `0a4d4896`
  「Cache Iris Shaders (#2206)」之上，而该提交（+1324/-524，含 942 行
  `DeferredWorldRenderingPipeline` 重写）从未同步进 Actinium。详见
  `sdl-gpu-shader-loading.md` §2.3。
  ⚠️ **勘误（2026-10-12，Phase 9）**：本节原先把 `ShaderDiskCache` 的 layer API
  （`touchLayer`/`keepNewestSubLayers`/`retainLayer`/`deleteLayer`/`remove`）记为「早前 Phase
  已同步」，实际**并不存在**（当时只落了 `configure` 幂等）。该缺口随 Phase 9 补齐，见下节。

## 上游同步状态（2026-10-12，Phase 9：程序落盘缓存 `0a4d4896` + `38af64f1` 剩余半块）

本轮把上一环受阻的两块一起接上：上游 `0a4d4896`「Cache Iris Shaders (#2206)」（+1324/-524，
21 文件）与 `38af64f1`「shader-loading-optimizations (#2213)」中**依赖 #2206 的那半块**
（`saveRetained`/`isFullCompileRunning`/`RetainedPrograms` 等）。逐文件对账、偏差与验证方式见
`docs/research/sdl-gpu-program-binary-cache.md`，此处只记结论：

- **glsm**：新增 `shader/ProgramBinaryCache`（落盘/回读 `glProgramBinary`，按 pack / 设置指纹 /
  驱动 / 顶点属性为键）；`RenderBackend` 增加 `supportsProgramBinary`/`programParameteri`/
  `getProgramBinary`/`programBinary` 四个默认方法面，LWJGL3 后端实现（`!isGLES && (OpenGL41 ||
  ARB_get_program_binary) && GL_NUM_PROGRAM_BINARY_FORMATS > 0`）；`GLStateManager` 增加
  `glProgramParameteri`/`glGetProgramBinary`/`glProgramBinary`，并在 `glLinkProgram` 里对
  可缓存程序打 `GL_PROGRAM_BINARY_RETRIEVABLE_HINT`；`RenderSystem.supportsProgramBinary`。
  `ShaderDiskCache` 补 layer API（`touchLayer`/`keepNewestSubLayers`/`retainLayer`/`deleteLayer`/
  `remove`）并把 `put`/`putBlob` 改为返回 `boolean`。
- **shader**：新增 `gl/program/RetainedPrograms`；`ProgramBuilder`/`Program`/`ComputeProgram`/
  `ProgramCreator` 接上缓存键、`retainAs` 与保留池；`GlResource.destroy` 加 `isValid` 守卫
  （共享程序会被两个 Pass 各 destroy 一次）；`Iris` 接上 `prepareSelectedShaderPack`/
  设置指纹/`compileEveryShaderFolder`/`ProgramBinaryCache.usePack`；`IrisConfig` 增加
  `saveCompiledShaders`；`ShaderPack.getEveryProgramSet`；`PipelineManager.discardPipeline`；
  `CustomUniforms.mapPassLike`；`ShaderStorageBufferHolder` 构造失败时回收已建 SSBO；
  `DeferredWorldRenderingPipeline` 构造失败时 `destroy()` + 同源程序共享 + `destroy()` 空值守卫。
- **src/main**：新增 `com.gtnewhorizons.angelica.iris.ShaderWarmup`，由 `MixinGuiMainMenu`
  的 `initGui` HEAD 触发（上游在 `ClientProxy` 的 `GuiOpenEvent` 里触发，Actinium 没有该路径）。
- **跳过**：`Lwjgl2GLRenderBackend`（本仓库无 LWJGL2 后端实现）、上游 `Mixins.java`（json 配置）、
  `IdMap.modernFallbackMcVersion` 相关 hunk（Actinium 的 fallback 版本是 `IdMap` 内的常量）、
  instancing（`compileInstancedVariants`/`InstancedVariantKey`/`MV_BUILTINS`，Actinium 的 pipeline
  没有 instancing 变体）、mcpatcher/RFB/Tracy 相关，以及 `ProgramBindingRegressionTest`
  （该文件在 Actinium 不存在）。
- **偏差**：`Iris.compileEveryShaderFolder` 不驱动 `LoadingScreenRenderer` 进度条（1.12.2 没有
  上游用的 `resetProgresAndWorkingMessage`，且该路径在首个世界帧里执行，画加载屏会中途换帧），
  改为按目录打 INFO 日志；`prepareSelectedShaderPack` 去掉了上游的 `areShadersEnabled()` 前置条件
  （Actinium 在进世界前不加载包，保留该条件会让预热永远不生效）。

## 上游同步状态（2026-10-12，Phase 10：vsync 三态 + Windows 输入延迟 + GLSL 单次解析 `b17bb42c`）

上游 `b17bb42c`「vsync adjustments: on/mailbox/off + windows input lag fix + glsl parsing
optimizations (#2009)」（67 文件 +2919/-734）。本轮**不是整提交照搬**：本分支此前已从更晚的
上游状态落过其中两块，因此逐文件对账后只补剩余面。对账结论与 reconcile 见下。

### 1. 已在树内（本轮不动）

| 上游文件 | 处置依据 |
|---|---|
| `VSyncMode`（枚举删 `AUTO`、留 `tearFree()`） | 本树已是 `OFF/ON/MAILBOX` 三态，与上游 master 逐字节相同 |
| `RenderBackend`（vsync 门控、gate 记录、`hasSwapchainBackpressure`） | 本树走的是 **#2086 之后的形态**（`refreshPeriodNanos`/`vsyncHonored`/`displayGeneration`/`GateSample`/`recordGate`/`parkNanos`），`b17bb42c` 的 `frameGateNanos`/`gateAnchorsNextFrameStart`/`queryDisplayRefreshRateHz` 是 #2086 之前的旧 gate 模型，**不取**，否则会覆盖已落地的 pacer 面 |
| `Lwjgl3GLRenderBackend.applyVSyncMode` | 已有（本仓库 GL 路径走 GLFW swap interval，不是上游的 `SDLVideo.SDL_GL_SetSwapInterval`） |
| sdl-gpu `Device`/`FrameManager`/`Presenter`/`OffscreenTarget`/`SdlFramePlots`/`SplashDispatcher`/`ThreadRegistry`/`UscaledRetype`/`PerFrameBlockInjector`/`ShaderManager`/`TextureOps`/`PipelineApplier`/`FboState`/`GlslVulkanPreprocess`/`ShaderTransformPostProcessor` | 与上游 master 逐字节相同（SDL-GPU 后端整体按更晚的上游状态移植过），`b17bb42c` 的改动被后续提交包含 |
| iris `Iris.warmupShaderTransforms`、`DeferredWorldRenderingPipeline.warmupTransforms` | `6d338faf` 已落 |
| `FramePacer`/`PacerSleeper`/`FpsReducer` | `c5f54409` 已按 #2086+#2197 落地；本提交里的旧形态不覆盖 |
| `MixinMinecraft` 的 render-ahead 等待搬迁、`getPresentDebugInfo` 从 F3 移除 | pacer 移植时已随之落地（Actinium 的 F3 行由 `FpsReducer.onDebugText` 提供，`RenderGameOverlayEvent.Text` 只在 `showDebugInfo` 时派发，故 `MixinMinecraft_FPSCap` 的 `showDebugInfo` 守卫无对象） |

### 2. 本轮新落

**vsync 三态（主题 1）**

- `glsm/GLStateManager`：新增 `setVSyncEnabled(boolean)` / `setVSyncMode(VSyncMode)` /
  `setVSyncPreference(VSyncMode)` / `supportsVSyncMode(VSyncMode)` / `preferredTearFreeMode()` /
  `getEffectiveVSyncMode()` / `hasSwapchainBackpressure()` / `pumpDisplayMessages()`。只补三态模型
  需要的面；`getDisplayRefreshRateHz` 与帧门 API 仍走本树的新形态，不引入旧面。
- `celeritas-common` `SodiumGameOptions.AdvancedSettings.vsyncMode`：**可空**，无默认值——新装的
  正确默认取决于显示器与后端能力，配置解析时两者都不知道。
- 新增 `com.dhj.actinium.gui.VSyncOptions`：可选模式（显示序 `OFF,ON,MAILBOX`，按后端过滤，空集
  回落 `{ON}`）、`cyclingControl`、`defaultMode()`（跟随 vanilla `enableVsync`）、`storedMode()`
  （后端做不到的模式读作 `ON` 而不是 `OFF`，因为玩家要的是"不撕裂"）；`selectableModes`/
  `supportedMode` 以 predicate 为内核，便于不碰后端地陈述规则。
- `ActiniumGameOptionPages`：vsync 由布尔勾选框改为 `CyclingControl<VSyncMode>`，绑定写模式、把
  `enableVsync` 作为两值影子、存 `options.txt` 并把模式下发给后端；Max Framerate 滑条步长
  `10 → 1`（与上游一致，便于对齐刷新率）。
- `ClientProxy.toggleVSync(boolean)`（vanilla 两值侧：解析成当前偏好的 tear-free 模式并落盘），
  `MixinGameSettingsVSyncRouting` 改调它；`MixinMinecraft.actinium$routeVSync` 改走
  `GLStateManager.setVSyncEnabled`（行为不变，收拢到 glsm 缝）。
- `Actinium.onInit → initVSync()`：补默认模式、同步 `enableVsync`、`setVSyncPreference`（**只记
  偏好，不碰驱动**——此时还没有窗口，模式由 vanilla 启动开关经后端下发）。
- `FramePacer.setIdleWork(...)` 接上 `ActiniumWorldRenderer.runFrameIdleWork(long)`（新）：
  用 EWMA 估算 `runAsyncTasks()` / `uploadChunks()` 的耗时，超出 pacer 给的 deadline 就跳过该阶段，
  shadow pass 期间不做。
- lang：`assets/celeritas/lang/{en_us,zh_cn,ru_ru}.lang` 新增
  `sodium.options.vsync_mode.{off,on,mailbox}`，并按三态语义改写 `v_sync` / `fps_limit` 的提示。

**Windows 输入延迟（主题 2）**

- `MixinMinecraft.actinium$freshenMouseInput`：在 `runGameLoop` 调
  `EntityRenderer.updateCameraAndRender(FJ)V` 之前再泵一次窗口消息。帧末的 `updateDisplay` 会泵
  一次，于是相机用的是上一帧的鼠标增量；在算相机前补泵一次消掉这一帧延迟。`ThreadRegistry`
  （本提交新增的那个类）本树已在，且已被 `FrameManager`/`SDLGPURenderBackend` 使用。

**GLSL 单次解析（主题 3）**

- `shader/ShaderTransformer`：版本提升扫描由正则改为 lexer token 扫描（注释/字符串里的关键词不再
  误提升），补 `iimage`/`uimage` 的 `prefix` 匹配（`iimage2D` 这类整词之外的前缀形式原先匹配不到）；
  新增 `finalizeStage(version, headerTail, body, tree, stage)`，在打印后**用树**再查一次所需版本；
  打印走 `getFormattedShaderRebased(tree, "")`（body 不带 header、token 偏移重定位到 body）+
  `restoreReservedWordsInTree`，产出 `StageArtifact(tree, headerLen)`；`extractRwImageStores`/
  `extractVertexReplay` 在替换某阶段源码时**丢弃该阶段的 artifact**（剥掉 image 写之后的源码已经
  不是那棵树打印出来的东西）。
- `shader/TransformPatcher`：承接 artifact 并转交；缓存命中没有树，回落到 2 参回调。
- `src/main` `IrisGLSMBridge.installPostTransformHook`：实现 4 参 `onTransformed`，把树与 headerLen
  传给 `SDLGPUGate.prewarmSpirv(src, tree, headerLen, id)`（SDL 侧 `ShaderManager` 的树入路径早已
  在树内，这一步补的是生产者）。
- `shader/DeferredWorldRenderingPipeline`：`CustomTextureManager` 提前到 transform futures 提交
  **之前**构建，并恢复进入时的活动纹理单元——它要占 `GL_TEXTURE2`，而随后启动的 transform 线程也会
  碰纹理单元。
- `glsm/RenderSystem`：在 `BackendManager.init()` 之后显式解析一次
  `com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL`，把服务的构造固定到持有上下文的线程上。

### 3. 跳过项与理由

- **`SDLGPULWJGLService` 的查询模拟 + `getPriority()/isOfferedAsAvailable()` + `SDLGPUGate.fallBackToGL`
  守卫**：Actinium 的 `LWJGLService` 是自建接口，服务选择按 `BackendManager.RENDER_BACKEND.isSDLGPU()`
  判定（见 `sdl-gpu-enable-path.md` §5），且 `RenderBackend` 有**真实**的
  `queryCounter`/`getQueryObjectui64`；Actinium 也没有 `fallBackToGL`（门控只有"入选/不入选"），
  上游那套"已把服务交出去就不能回退"的守卫无对象。
- **`Lwjgl2GLRenderBackend`**：本仓库没有 LWJGL2 后端实现（只有 LWJGL3 + SDL 两个）。
- **instancing 相关 hunk**（`mvBuiltinsMemo`、instanced transform 的 `null` 容忍）：Actinium 的
  `DeferredWorldRenderingPipeline` 没有 `INSTANCED_PROGRAM_IDS` 变体路径。
- **`.gitignore` 的 `tracy-client/angelica/`、`gradle/libs.versions.toml`、`sdl-gpu/build.gradle.kts`**：
  本仓库没有 `tracy-client` 子项目、没有 `libs.versions.toml`/`celeritas` 依赖（兼容桥已移除）、
  sdl-gpu 用 `build.gradle` 且不引 `retrofuturabootstrap`。
- **GPU/rig 测试**（`ReadbackSubmitOrderGpuTest`、`SdlTestRig` 增量）需要真 SDL 设备，按惯例跳过。
- **`HeadlessTestLWJGLService`（上游 +695）**：上游用它替换 lwjgl3ify 的 GL 绑定做无头测试；本仓库的
  `LWJGLService` 是自建接口、测试用 `LWJGL3Service`/`glsm` 的 `testFixtures`，没有对象。

### 4. 偏差

- 本树的 `GlslTransformUtils.restoreReservedWords(String)` 会把 `new` 也还原（上游不会）。iris 路径
  改走树内还原之后，树内还原按上游语义**保持 `new` 处于重命名状态**（`new` 在所有 GLSL 版本里都是保留
  字，还原回去反而危险），而 compat 路径（`CompatShaderTransformer`）仍用字符串还原。两种语义的差异由
  新增的 `ReservedWordRestoreTest` 明确钉住，避免以后有人"顺手统一"。
- `iimage`/`uimage` 的 `prefix` 匹配是本轮随 lexer 扫描一起补的（上游在 `b17bb42c` 之前就有，本树的
  旧正则实体没有）；它们由 `RenderSystem::supportsImageLoadStore` 门控，硬件不支持时不会提升版本。

### 5. 验证

```
./gradlew compileJava :glsm:compileJava :celeritas-common:compileJava :shader:compileJava :sdl-gpu:compileJava
./gradlew compileTestJava
./gradlew test --tests "*VSyncOptionsTest" --tests "*ShaderTransformArtifactTest" --tests "*ReservedWordRestoreTest" ...
./gradlew check --no-daemon
```

新增测试：

- `src/test/.../com/dhj/actinium/gui/VSyncOptionsTest`：可选模式顺序与空集回落、`storedMode` 的降级
  规则、`modeKey` 与枚举一一对应（都经 predicate 内核，不依赖后端）。
- `src/test/.../net/coderbot/iris/pipeline/transform/ShaderTransformArtifactTest`：真跑
  `ShaderTransformer.transform(..., artifactsOut)`（含"未用 sampler 被剥离 + per-frame uniform 进块
  + 顶点 clip-Z 重映射"这些会产生 Edit 的场景），断言每个阶段都有 artifact、`headerLen` 正好切在
  header 末尾，并把 artifact 喂给消费者 `ShaderManager.applyPrewarmTransformsFull(source, tree,
  headerLen, ...)`，与重新解析源码的路径**逐字节比对**产出的 source 与 bool uniform 集合——偏移错一个
  字节这里就会红。
- `src/test/.../com/gtnewhorizons/angelica/glsm/ReservedWordRestoreTest`：树内还原与字符串还原在两者
  都能处理的词上一致；`new` 保持重命名。
- `src/test/.../com/gtnewhorizons/angelica/glsm/redirect/GLSMRedirectorTest`：新增 EXTTimerQuery 用例
  （本提交为该重定向补的测试；`METHOD_REDIRECTS` 里的 EXT 映射本树已有，此前无覆盖）。
- 既有 `BackendPolicyTest`（vsync 解析/周期换算）与 `PrewarmSingleParseEquivalenceTest`/
  `PrewarmTreeHandoffEquivalenceTest`（SDL 侧单次解析等价）继续覆盖 pacer/预热面，未改语义。
- `VertexReplayExtractionTest`/`CompositeVertexWriteExtractionTest` 的 `extractRwImageStores` 调用点
  跟着签名补 `null`（不收集 artifact 的调用方语义不变）。

实机验证建议（需用户执行）：

1. **三态**：OpenGL 路径下视频设置里应只有 `Off/On`（GL 后端 `supportsVSyncMode(MAILBOX)` 为假），
   SDL 路径下应为 `Off/On/Mailbox`；切换后 F3 行的 ` [vsync 60 ]` / ` [mailbox 60 ]` / ` [cap N]`
   应随之变化，`options.txt` 的 `enableVsync` 与 `actinium-options.json` 的 `vsyncMode` 应同步。
2. **mailbox 输入延迟**：SDL + Mailbox 下鼠标手感应接近 Off，且无撕裂；日志出现
   `Frame pacing: mailbox 60Hz`。
3. **pacer 在 mailbox 下的门控识别**：Mailbox 下 pacer 不应把驱动当成阻塞
   （`locked=false`），F3 不应出现 `, wall clock`；On 下驱动不阻塞时才应出现
   `Frame pacing: vsync ... driver not blocking, paced by wall clock` 警告。
4. **输入延迟修复**：Windows 上"帧率被 pacer 限到 60"时，视角转动应比改动前少一帧延迟
   （主观验证，可与 `-Dangelica.sdlgpu.enable=false` 的 GL 路径对照）。

## 上游同步状态（2026-10-12，Phase 11：pre-raster compute 消费方接线）

**提交**：`812f69e6`（`feat(shader): wire pre-raster compute dispatch for extracted image stores`）。

**背景**：Phase 8 把 `PreRasterComputeDispatcher` 整文件移植进来却**没有调用方**（见上文 Phase 8
的「已知缺口」）：SDL 后端下 COMPOSITE 的 image 写入会被抽取成 compute 并从光栅阶段剥离，剥离后
无人重放。本阶段补上消费方，形态以上游 master tip `21a054a4` 为准——该机制来自 `8da561d3`
（#1993），`5ed1c925`（#2224）之后没有改过消费方；上游 `CompositeRenderer` /
`ShadowCompositeRenderer` 的 `preRasterCompute` / `preRasterMode` / `preRasterTargetSizeLoc`
与 `dispatchPreRasterCompute(...)` 即目标形态。

**接线设计**

- 新增 `shader/.../postprocess/PreRasterComputeProgram`（接口）、`GlPreRasterComputeProgram`
  （包私有实现，经 `PreRasterComputeProgram.of(...)` 静态工厂取得）、
  `shader/.../postprocess/PreRasterComputeStages`（注册 / dispatch / 清理）。
- `PreRasterComputeStages` 持有每个已注册预通道的 `PreRasterComputeProgram`、抽取模式与
  `_vg_target_size` 位置缓存，`register` 返回 slot（无预通道时返回 `NONE`），`dispatch(slot, w, h)`
  把上一次解析到的位置回传并缓存，`destroy()` 逐个释放并清空。GL 交互全部经由
  `PreRasterComputeProgram`，因此注册 / dispatch / 清理可以无上下文地真实驱动（见下）。
- `CompositeRenderer` 与 `ShadowCompositeRenderer` 各持一个 `PreRasterComputeStages` 实例：
  建程序时把 `transformed.get(PatchShaderType.COMPUTE)` 交给 `register`（`Factory` 回调里做各自的
  sampler / image 接线，即上游 `buildPreRasterCompute` 的内容），Pass 上只留 `preRasterSlot`；
  `renderAll()` 在设好 pass viewport 之后、`framebuffer.bind()` 之前 dispatch；`destroy()` 里
  `preRasterComputes.destroy()`。world composite 用 `pass.viewWidth/viewHeight`，
  shadow composite 用缩放后的 shadow 分辨率，与上游两处一致。
- `ShadowCompositeRenderer` 的 `TransformPatcher.patchComposite(...)` 从 `createProgram` 内部提到
  构造体，方法随之更名 `createProgramFromTransformed`——上游自 #1993 起就是这个形状，抽取结果与
  建程序共用同一张 transformed 图。

**与上游的差异（reconcile）**

- 上游把 `preRasterCompute` / `preRasterMode` / `preRasterTargetSizeLoc` 三个字段放在 `Pass` 上；
  本树把三者收进 `PreRasterComputeStages`，`Pass` 只留 slot。语义一致：每 pass 一套、位置缓存按
  pass 独立（`keepsTheTargetSizeLocationOfEachSlotApart` 钉住这一点），且两个 renderer 共用一份
  实现。
- 上游 `register` 的判据是 `parseSentinel(...) != null`；本树收紧为只接受 `COMPOSITE_VSH` 与
  `COMPOSITE_FSH`。`CHUNK`（celeritas 地形体素化，消费方 `CeleritasTerrainPipeline`）与
  `VERTEX_REPLAY`（逐绘制顶点重放，消费方 `VertexWriteReplays`）另有驱动者，落到 composite pass 上
  只会按 1×1×1 错跑，故显式不注册（`registersNothingForTheExtractionsOtherStagesDrive`）。
- `DeferredWorldRenderingPipeline` **无需改动**：composite 的抽取结果本来就在
  `submitCompositeTransforms` 产出的 future 里——`extractRwImageStores` 在 `transformInternal`
  末尾按上游位置执行，`result.put(PatchShaderType.COMPUTE, ...)` 与其它阶段同图，缓存
  （`TransformPatcher` 的 `immutableResult` 复制整张 EnumMap）与预热（`notifyPostTransform` 遍历
  `entrySet`，COMPUTE 也在内）都原样覆盖它。`CompositeRenderer.getTransformed` 取到的就是这张图。
  #2206 重写的 RetainedPrograms / SharedProgramKey / `wireGbufferResources` 只涉及 gbuffer 与
  ATTRIBUTES 路径，与本接线无交集。

**与 voxelization 的边界**

- 两者不共享对象、不共享生命周期：pre-raster 是**包级 image 写入重放**（每个 composite pass 前
  一次 dispatch，由 pass 的调用序驱动，程序归 renderer 的 `PreRasterComputeStages`）；voxelization
  是**shadow 几何体素化**（`SdlShadowVoxelizationSink` / `ShadowVoxelizer` / `VoxelRangeTable`，
  由 chunk render list 驱动，`shadowVoxelizationCompute` 由 DWRP 建/销毁并经
  `bindVoxelizationMatrixUniforms` / `prepareShadowVoxelizationCompute` 供给）。
- image binding 纪律各自遵循各自那一侧：pre-raster 沿用 composite 程序的 sampler / image 装配
  序列（`IrisSamplers.addRenderTargetSamplers` / `IrisImages.addRenderTargetImages` /
  `IrisImages.addCustomImages` ...，与同一 renderer 的光栅程序逐调用对齐）；voxelization 的
  custom images 显式按名字排序。两者是相互独立的 program id，各自绑定自己的 image set，不会互相
  覆盖。

**门控**

- 门控在抽取侧：`ShaderTransformer.transformInternal` 末尾 `if (BackendManager.RENDER_BACKEND.isSDLGPU()) extractRwImageStores(...)`。
  GL 后端下没有 `PatchShaderType.COMPUTE`，`register` 收到 null 返回 `NONE`，`renderAll` 的 dispatch
  分支不会进入，GL 路径行为与接线前逐条相同。`theGlBackendHasNoPrePassToRegister` 用真实
  `TransformPatcher.patchComposite` 在非 SDL 后端下断言这一点。

**缓存失效与生命周期**

- 抽取 artifact 的失效路径已接（Phase 8）：`RwImageStoreExtractor.setActiveCustomImages` 在
  atomic 可用集合变化时 `TransformPatcher.clearCache()` + `ShaderTransformer.clearSessionState()`。
- 程序生命周期由 pipeline 管理：`CompositeRenderer.destroy()`（DWRP 的 `destroy()` 调用
  `beginRenderer` / `prepareRenderer` / `deferredRenderer` / `compositeRenderer` 的 destroy）与
  `ShadowCompositeRenderer.destroy()` 各自释放自己注册的预通道；`recalculateSizes()` 只重建
  framebuffer，预通道与尺寸无关（`_vg_target_size` 每帧按 pass 尺寸写入）。

**测试**

- 新增 `src/test/.../net/coderbot/iris/postprocess/PreRasterComputeStagesTest`（9 例）：用
  `RwImageStoreExtractor.tryExtract` 真实抽取出的 compute 源做输入、用替身
  `PreRasterComputeProgram` 记录 GL 侧调用，覆盖注册（mode 与命名、null / 非 composite sentinel
  不注册）、dispatch（mode 与尺寸透传、`_vg_target_size` 位置跨帧缓存且 slot 之间隔离）、清理
  （每个程序只释放一次、二次 destroy 无副作用、释放后 dispatch 抛 `IllegalStateException`）、
  以及 GL 后端无预通道可注册。不涉及反射去驱动被测代码（仅沿用既有
  `Reflect.setStatic(RenderSystem.class, "maxGlslVersion", ...)` 夹具）。

**验证**

```
./gradlew :test --tests 'net.coderbot.iris.postprocess.PreRasterComputeStagesTest' --rerun
./gradlew check --no-daemon
```

实机验证建议（需用户执行，SDL 后端 + 写 image 的光影包，如 Euphoria Patches 的 End prepare
/imageStore 路径）：

1. composite / shadowcomposite 阶段自身写 image 的包（writeonly image + imageStore）在 SDL 后端下
   应看到此前丢失的 image 效果（计数器 / 状态图类前值），GL 后端（`-Dangelica.sdlgpu.enable=false`
   等价开关）结果应与改动前一致。
2. 日志出现 `[RwImageStoreExtractor] Extracted compute pre-pass for COMPOSITE (mode=composite-vsh|composite-fsh, written=[...])`，
   且该 pass 的 `_vg_target_size` 只在首帧解析一次（无每帧 `glGetUniformLocation` 抖动）。
3. 与 voxelization（写 `voxel_img` 的 shadow 地形）同时开启时，两者的 image 写入互不串扰：体素化
   结果与 composite 前置重放结果都正确。

## 上游同步状态（2026-10-12，Phase 12：#1993 的三项 uniform 热路径优化）

**提交**：`0bd51b55`（`perf(shader): share previous matrices and reuse uniform update listeners`）。

**背景**：`8da561d3`（#1993）除 SDL 后端、Tracy、TESR batching 三大块之外，还夹着三处与预热链、
落盘缓存都无关的 uniform 热路径优化。Phase 8/9 的预热移植把它们显式记成「跳过：与预热无关的性能
改动」（见 `sdl-gpu-shader-loading.md` §3 与 §6.2 的对账表），本阶段补齐。

**逐项对账**

| 上游项 | 上游位置与规模 | 上游改法 | 本树落地 |
|---|---|---|---|
| previous 矩阵共享 | `net/coderbot/iris/uniforms/MatrixUniforms.java`（+35/−13） | `Previous` 从「每 program 一个实例」改为按 uniform 名共享：静态 `INSTANCES`（`ConcurrentHashMap`）+ `Previous.shared(name, supplier)`；实例持有 `previous`/`current` 两个 `Matrix4f` 与 `lastFrame`，`get()` 每帧只推进一次并返回共享快照 | 语义照搬（含 `static final` 可见性与 `shared` 工厂） |
| entity id 特殊名缓存 | `net/coderbot/iris/uniforms/EntityIdHelper.java`（+18/−16） | `minecraft:current_player` / `minecraft:zombie_villager_converting` 在 id map 换新时解析一次，`getSpecialEntityId` 去掉 map 参数；判断顺序改为先比 `!= -1`，使未定义 `current_player` 的包不再走到 `Minecraft.getMinecraft()` | 缓存规则与判断顺序照搬，载体改为包内 `SpecialIds`（偏差见下） |
| 更新监听器复用 | `gl/uniform/{Float,Int,Vector2IntegerJoml,Vector3IntegerJoml,Vector4,Vector4Array,Vector4IntegerJoml}Uniform.java`（各 +3/−3）、`gl/sampler/SamplerBinding.java`（+5/−2） | `notifier.setListener(this::updateX)` 改为持有 `private final Runnable updateListener = this::updateX` | 逐文件照搬（8 个类） |

**为什么值得拿**（本树的消费路径）

- `ProgramUniforms.update()` 每帧都会 `updateStage(dynamic)`：每个 program 的每个 dynamic uniform
  每帧调用一次 `update()`，而 `update()` 每次都用 `this::updateValue` 重新注册监听器，于是每帧每
  uniform 分配一个方法引用对象。全屏 pass 多、dynamic uniform 密集（自定义 uniform、colortex 密集）
  的包下这是纯垃圾。
- `Previous.get()` 原先每次调用 new 两个 `Matrix4f`（`gbufferPrevious*` 每个 program 各一份），共享后
  每帧只读一次源矩阵、零分配，并且所有 program 读到的上一帧矩阵一致。
- 两个特殊实体名的查询在**不定义**它们的包上也是纯 miss：每个第三人称玩家实体、每只转化中的僵尸
  村民、每帧各一次 hash 查表。缓存后每张 id map 只查一次。

**reconcile 明细**

- **与 #2206 的边界**：#1993 同一批 uniform 改动里的 `ManifestCollectingHolder`、`UniformManifest`、
  `ProgramUniforms`/`ProgramBuilder` 的 manifest 接线（含 `addDynamicUniform(Uniform, ValueUpdateNotifier)`
  签名）已被 #2206（`d45f3b13`）以自身形态落地，本阶段一律不动；`ProgramUniforms` 中
  `for (int i = 0, n = ...)` 的索引循环重写同属该批、也与落盘缓存无关，未取。
- **`CommonUniforms`**：上游此处只有 `AngelicaConfig.enableHardcodedCustomUniforms` →
  `Iris.hardcodedCustomUniforms` 的重命名；本树门控是 `IrisDebugOptions.enableHardcodedCustomUniforms()`，
  语义相同，不改。
- **`GbufferPrograms`**：上游 +24 行的 `beginEntityLoop` / `endEntityLoop` / `onEntityRenderBoundary`
  属 TESR batching（本阶段不移植）；本树已有 `enterEntityPhase`/`PhaseOperations` 等价机制并由
  `GbufferProgramsEntityPhaseTest` 锁定，不重复实现。
- **`StateUpdateNotifiers` / `ValueUpdateNotifier` / `FogMode` / `FogUniforms` / `IrisInternalUniforms`**：
  `git show 8da561d3 -- 'src/main/java/net/coderbot/iris/gl/state/*'` 为空——这些文件在 #1993 中**没有
  任何改动**，监听器复用只发生在 uniform 类与 `SamplerBinding` 内部，上述调用点无需配合改动。
- **偏差：`EntityIdHelper` 的缓存载体**。上游把两个 id 放成 `EntityIdHelper` 的静态字段，在
  `if (entityIdMap != cachedEntityIdMap)` 块里解析。本树抽成包内 `EntityIdHelper.SpecialIds`
  （`refresh(map)` 内部做同一性判断，配 `currentPlayer()` / `convertingVillager()` 两个访问器），
  `getEntityId` 每次调用 `SPECIAL_IDS.refresh(entityIdMap)`。动机是可测：任何
  `net.minecraft.entity.Entity` 的构造都要过 game Bootstrap（`Entity.<init>` → `DataSerializers` →
  `ForgeHooks` → `Items`，dev 测试 JVM 直接失败于 `Accessed Items before Bootstrap!`），`getEntityId`
  在单测里无法驱动；把「每张 map 只解析一次」收进一个可实例化的小类型后，缓存与失效都能被真实驱动。
  运行时成本不变：原来那次引用比较搬进了 `refresh`，没有变成两次判断。
- **未取（同属 #1993，但不在这三项内）**：`MatrixUniform` 的 `cachedValue` 预分配 + `hasValue` 守卫
  （+9/−9；`Previous` 共享已拿走其中大部分每帧分配）、`MatrixFromFloatArrayUniform`（本树无此类）、
  `custom/CustomUniforms` + `cached/CachedUniform` 的 valueGen 推送去重（+76/+19；不是监听器复用，
  且耦合 Tracy，而 Tracy 整体不移植）。

**测试**

- `src/test/java/net/coderbot/iris/uniforms/PreviousMatrixSharingTest`（3 例）：同名 `shared` 返回同一
  实例、异名各自独立；首帧报 identity、第二帧报第一帧读到的值、帧内后续读重复同一快照、帧内改动源
  矩阵不会提前进入 previous；两个「program」共读同一 tracker 时不会二次推进。
- `src/test/java/net/coderbot/iris/gl/uniform/UniformUpdateListenerReuseTest`（9 例）：9 条动态注册路径
  （`uniform1f` 的 Float/Int/Double 重载、`uniform1i`、`uniform2i`、`uniform3i`、`uniform4f`、
  `uniform4fArray`、`uniform4i`）经真实的 `DynamicLocationalUniformHolder` 默认方法建出 uniform，
  `update()` 两次后断言 notifier 收到的是同一个 listener 对象，并 `run()` 它证明它确实驱动同一条取值
  路径（取值计数 2 → 3）。所有取值保持不变，因此不会触发 GL 调用。
- `src/test/java/net/coderbot/iris/uniforms/EntityIdHelperSpecialIdCacheTest`（3 例）：用计数型 id map
  断言两个名字在换 map 时各解析一次、重复 `refresh` 不再解析、换 map 后重新解析、map 未定义时保持 −1。
- **未覆盖**：`SamplerBinding` 的同一个改动（`updateSampler()` 一进来就调 `RenderSystem`，单测无法
  驱动）；`EntityIdHelper.getEntityId` 的实体级集成路径（同上，需要 Bootstrap）。

**验证**

```
./gradlew :test --tests 'net.coderbot.iris.uniforms.PreviousMatrixSharingTest' \
  --tests 'net.coderbot.iris.uniforms.EntityIdHelperSpecialIdCacheTest' \
  --tests 'net.coderbot.iris.gl.uniform.UniformUpdateListenerReuseTest' --rerun
./gradlew check --no-daemon      # 1141 例全绿
```

实机验证建议（需用户执行）：

1. **TAA / 运动模糊类效果**（`gbufferPreviousModelView` / `gbufferPreviousProjection` 是它们的输入）：
   用带 TAA 或运动模糊的包（如 Bliss、Complementary Reimagined 打开 TAA），进世界后转动视角与移动，
   拖影方向与强度应与改动前一致，不应出现「上一帧矩阵卡住」（拖影不跟随、或永不消失）。同一帧内既有
   shadow pass 又有 gbuffer pass 的包尤其要看。
2. **DH 场景**：开 Distant Horizons 观察 `dhPreviousProjection` 相关效果无异常。
3. **实体 id**：装定义了 `minecraft:current_player` 的包（第三人称看自己）并触发一次僵尸村民转化，
   实体着色应与改动前一致；连续换包两次以上（覆盖 `SpecialIds` 的失效路径）后仍正确。
4. **性能**：帧时间不应有回归；`-Xlog:gc` 粗看分配速率（方法引用对象与矩阵分配减少）。
