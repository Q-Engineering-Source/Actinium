# SDL GPU 后端启用路径（Cleanroom + LWJGLY）

> 状态：**已写出，未做运行时验证**（2026-10-12 按 LWJGLY 路线重写）。
>
> **2026-10-12 路线变更**：本文档此前描述的是 Vintage-SDL-Bridge coremod 路线（coremod 用
> ASM 换掉 `org.lwjglx.opengl.Display.create` 的窗口创建、把 `SDL_Window*` 写到
> `Launch.blackboard`）。该路线**作废，不再追求**，原因：老 CRL（lwjglx + lwjglxx）的窗口
> 恒带 GL context，而 SDL 无法为 `SDL_WINDOW_OPENGL` 窗口创建 Vulkan/Direct3D 12 swapchain，
> `SDL_ClaimWindowForGPUDevice` 必然失败。**SDL 只有新 CRL（LWJGLY + 宿主 WindowBridge）
> 一条路线**：LWJGLY 的 `org.lwjgl.opengl.Display` 自己什么都不建，把窗口、上下文与输入
> 全部转发给宿主安装的 `com.cleanroommc.lwjgly.spi.WindowBridge`，Actinium 认领那个窗口、
> 接管所有 GL 特定的引擎路径与 present。
>
> 因此这条路径在新 CRL 上惰性生效；老 CRL 上 `isAvailable()` 恒为 false（`LwjglyLayer`
> 探测失败即短路），OpenGL 路径逐字节不变，且**不会加载任何 `com.cleanroommc.lwjgly.*` 类**。
> 双运行时（同一份 jar 跑两种 CRL）的规则与对账见 `lwjgl2-compat-dual-runtime.md`。
>
> 本文档记录启用路径的组件、窗口交接契约（与宿主 WindowBridge 的边界）、宿主侧待实现项，
> 以及**明确没有**从已撤除的 GLFW 兼容层恢复的东西（撤除清单见
> `sdl-gpu-cleanroom-glfw-compat.md`）。

## 1. 启用路径总览

窗口由**宿主**提供（新 CRL 上 LWJGLY 的 WindowBridge 就是宿主的窗口实现），Actinium 只负责
认领窗口并接管所有 GL 特定的引擎路径。

```
启动
 │  SDLGPURenderBackend.isAvailable()
 │    → SDLGPUGate.isHandoverAvailable()
 │        = BackendOptions.sdlGpuRequested()      (-Dangelica.sdlgpu.enable=true)
 │       && isSDLGPUAvailable()                   lwjgl-sdl / shaderc / spvc 在位
 │       && isWindowSupplierPresent()             LwjglyLayer.present()（Class.forName，不初始化）
 │                                                && LwjglyWindowHost.available()（宿主已 setWindowBridge）
 │       && isDeviceReady()                       SDL GPU 设备创建成功（探测）
 │  任一条不满足 → 后端不入选 → 走原有 OpenGL 路径（后端选择只发生一次）
 ▼
Minecraft.createDisplay()（MixinMinecraftCoreProfileDisplay，HEAD + cancellable）
 │  isHandoverAvailable() == false → 原核心 profile 创建流程（不变）
 │  isHandoverAvailable() == true  → celeritas$createSdlDisplay(...)
 │       Display.create(PixelFormat, ContextAttribs)   LWJGLY 下 = adoptContext：
 │                                                    校验宿主已建好的上下文，不建窗口
 │       SDLGPUGate.handOverWindow()
 │           LwjglyWindowHost.window()                 读宿主 WindowBridge 的 SDL_Window
 │           Device.claimWindow(sdlWindow)             SDL_ClaimWindowForGPUDevice
 │           BackendManager.RENDER_BACKEND.onPostWindowCreate(window)  能力串 + 缓存
 │       ci.cancel()                                  跳过 GL 版本探测循环
 │       跳过 Forge 的 initializeWindowsInformation / setWindowStyle / initializeTaskbarAPI
 ▼
帧循环（MixinMinecraft 既有帧钩子，未改动）
 │  runGameLoop HEAD              → onFrameBegin()
 │  updateDisplay BEFORE          → onFrameEnd()   （提交最终目标，present）
 │  updateDisplay 内 Display.update() → @Redirect → RENDER_BACKEND.pumpDisplayMessages()
 │    （经 Display.processMessages() → WindowBridge.pump()，宿主事件泵只被驱动一次）
 │  updateDisplay AFTER           → onFrameBegin()
 │  runGameLoop HEAD（首个游戏循环）→ 推送一次 vsync 到后端
 ▼
退出
   shutdownMinecraftApplet 里 Display.destroy() → @Redirect：SDL 模式直接跳过
   （窗口属于宿主，GPU 设备由后端自己的 shutdown 释放）
```

## 2. 组件与文件

| 组件 | 文件 | 职责 |
|---|---|---|
| 后端注册 | `src/main/resources/META-INF/services/com.gtnewhorizons.angelica.glsm.backend.RenderBackend` | `com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend` 参与后端选择 |
| 可用性门控 | `sdl-gpu/.../SDLGPUGate.java` | `isHandoverAvailable()` / `isWindowSupplierPresent()` / `isDeviceReady()`；失败原因只告警一次 |
| 兼容层探测 | `sdl-gpu/.../compat/LwjglyLayer.java` | 只用 `Class.forName(name, false, loader)` 判断 LWJGLY 是否在位，结果缓存；**不引用任何 `com.cleanroommc.lwjgly.*` 类型**，老 CRL 上短路即止 |
| 宿主窗口（隔离类） | `sdl-gpu/.../compat/lwjgly/LwjglyWindowHost.java` | SDL 路径里**唯一**引用 `com.cleanroommc.lwjgly.*` 的类：窗口句柄、宽高、resize 消费、宿主义务文本；只在门控通过后被加载 |
| 窗口认领 | `sdl-gpu/.../SDLGPUGate.handOverWindow()` | `LwjglyWindowHost.window()` → `Device.claimWindow` → `engaged` → `onPostWindowCreate` |
| 后端可用性 | `sdl-gpu/.../SDLGPURenderBackend.java` | `isAvailable()` = `SDLGPUGate.isHandoverAvailable()`；上下文身份 = null drawable（见 §3.3） |
| Display 创建分支 | `src/main/java/com/dhj/actinium/mixin/vintage/core/startup/MixinMinecraftCoreProfileDisplay.java` | SDL 模式：一次 `Display.create` + 交接 + `ci.cancel()`，不跑 GL 循环、不跑 GL 校验、不跑 Forge 窗口钩子 |
| 帧/present/退出 | `src/main/java/com/dhj/actinium/mixin/vintage/core/MixinMinecraft.java` | `Display.update()` 重定向到后端 pump；vsync 推送；`Display.destroy()` 跳过 |
| 字体批处理 | `src/main/java/com/gtnewhorizons/angelica/client/font/BatchingFontRenderer.java` | SDL 模式没有 GL context，`hasCurrentGlContext()` 直接放行 |
| FML 启动画面 | `src/main/java/com/dhj/actinium/mixin/vintage/core/startup/MixinSplashProgress.java` | SDL 模式强制关掉 Forge splash（它要 GL 共享 context，而 LWJGLY 的 `SharedDrawable` 不可用） |
| LWJGL 服务桥 | `src/lwjglCommon/java/com/mitchej123/lwjgl/LWJGLServiceProvider.java`、`sdl-gpu/.../SDLGPULWJGLService.java` | Embeddium/Celeritas 的 GL 对象调用落到后端，而不是伪 capability 的哨兵函数指针 |
| 开发运行 | `build.gradle` | `runClientSdl`（run 配置 + IDEA 运行项），带 `-Dangelica.sdlgpu.enable=true` |

`MixinSplashProgress` / `MixinMinecraft` / `MixinMinecraftCoreProfileDisplay` 都已在
`mixins.actinium.vintage.json` 里，**本次没有新增 mixin**。

## 3. 窗口交接契约（Actinium ↔ 宿主 WindowBridge）

契约只有一个实现（LWJGLY），所以它是**一个隔离类加明确边界**，而不是接口加多实现：
`com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly.LwjglyWindowHost` 就是这条边界的全部。
旧的 `SDLGPUWindowHandover`（读 `Launch.blackboard`）随 coremod 路线一并删除。

### 3.1 契约方法表

| `LwjglyWindowHost` | 对应的 LWJGLY API | 调用点 |
|---|---|---|
| `available()` | `LWJGLY.hasWindowBridge()` | 门控 `isWindowSupplierPresent()` |
| `requirement()` | —（宿主义务文本） | 门控的「SDL 被请求但不可用」告警 |
| `window()` | `LWJGLY.windowBridge().handle()` | `SDLGPUGate.handOverWindow()` → `Device.claimWindow` |
| `width()` / `height()` | `WindowBridge.width()` / `height()` | `SDLGPURenderBackend.init()` 的 splash 离屏目标尺寸 |
| `consumeResized()` | `WindowBridge.consumeResized()` | `Device.wasWindowResized()`（swapchain 重建） |

明确**不**经此边界的两件事，及其理由：

- **事件泵**：走后端既有的 `Display.processMessages()`（`org.lwjgl.**` 常驻 API）。LWJGLY 把
  它转发到 `WindowBridge.pump()`，正好满足「只有一个事件泵」的要求；而在这里引用 LWJGLY
  会让 `SDLGPURenderBackend`（ServiceLoader 在两种运行时都会加载并实例化）在后端类加载时
  就触发 LWJGLY 类加载。
- **GL 上下文绑定**：SDL 后端自己安装伪造的 `Lwjgl3GLCapabilitiesShim`，从不调用
  `WindowBridge.makeCurrent()` / `releaseContext()`——调用它会用宿主 GL 上下文的真实能力
  覆盖后端的伪造能力，与 SDL 后端「不跑 GL 调用」的前提冲突。

### 3.2 所有权划分

- **宿主拥有**：SDL 窗口的创建、尺寸、显示、销毁；事件泵；输入（喂给 `org.lwjgl.input.*`，
  vanilla 读的是这一对）；GL 上下文（`adoptContext` / `makeCurrent`）。
- **Actinium 拥有**：GPU 设备的创建与销毁；`SDL_ClaimWindowForGPUDevice`；present；
  GLCapabilities 的替换。

### 3.3 上下文身份：null drawable

LWJGLY 的 `Display.getDrawable()` **恒为 null**（宿主拥有唯一的上下文，且 LWJGLY 不提供可用的
`Drawable`/`DrawableGL`/`SharedDrawable` 实现）。因此：

- SDL 后端把 `null` drawable 认作自己的上下文：`handleMakeCurrent(null)` /
  `handleReleaseContext(null)` 返回 true（安装伪造能力 + 进入 SDL 上下文状态），非 null 的
  drawable 一律返回 false 交给 GL 路径。
- GLSM 侧同步退化：`isDisplayDrawable(null)` 为真（`null == Display.getDrawable()`），
  `makeCurrent(null)` / `releaseContext(null)` 走 `Display.makeCurrent()` /
  `Display.releaseContext()`（LWJGLY 下即 `WindowBridge.makeCurrent()` / `releaseContext()`），
  且不再对 null 解引用；drawable 身份记账（`drawableGL` / `drawableGLHolder`）在 null 请求下
  保持不变，不会误判成「切离显示上下文」。
- 老 CRL 上这套退化不生效（`Display.getDrawable()` 非 null），行为与改动前一致。

## 4. 宿主侧要求（等宿主确认清单）

Actinium 侧已按下列约定写好，缺哪一条都会在调用点 fail-fast 并给出具名原因：

1. **安装 WindowBridge**（`LWJGLY.setWindowBridge(...)`）：宿主必须在自己窗口创建后、任何
   mod 代码运行前安装。未安装时 `isWindowSupplierPresent()` 为 false → 后端不入选 →
   只有一条告警。
2. **窗口必须能被 GPU 设备认领**：`WindowBridge.handle()` 返回的 `SDL_Window` 不能是
   `SDL_WINDOW_OPENGL` 窗口（SDL 无法为它建 Vulkan/D3D12 swapchain）。`Device.claimWindow`
   在认领前检查该 flag 并告警，`SDL_ClaimWindowForGPUDevice` 的失败原因紧跟在同一条日志里。
   **待确认**：新 CRL 是否会为「请求 SDL GPU」的进程创建不带 GL flag 的窗口（或提供无 GL
   窗口/第二窗口模式）。
3. **`Display.create(pf, attribs)` 在 SDL 模式下的语义**：LWJGLY 下它是 `adoptContext`——
   校验请求与宿主**已存在**的上下文。SDL 模式并不需要那个 GL 上下文；**待确认**：宿主在
   SDL 模式下是否仍创建 GL 上下文，还是提供一条无 GL 的路径。
4. **macOS forward-compatible**：`MacDisplayForwardCompatTransformer` 注入的是 GLFW 的
   window hint，而 LWJGLY 的 `Display.create()` 根本不建窗口，注入点不存在（见 §7）。该
   hint 现在属于宿主创建上下文的一步，**待确认**：宿主在 macOS 上是否设置
   `GLFW_OPENGL_FORWARD_COMPAT`（否则请求 3.3/4.1 会退回 2.1，Actinium 会在 `GL_VERSION`
   校验处抛错）。
5. **输入**：宿主需要把键鼠事件喂给 `org.lwjgl.input.Mouse/Keyboard`（LWJGLY 的
   `WindowBridge` 已定义 `nextKeyEvent` / `nextMouseEvent` 等队列，宿主实现即可）。
6. **窗口生命周期**：Actinium 在 SDL 模式不再调 `Display.destroy()`；窗口销毁由宿主负责。
7. **`SharedDrawable` 缺口（LWJGLY 侧）**：LWJGLY 的 `SharedDrawable` 构造即抛
   `UnsupportedOperationException`，所以任何「把 `Display.getDrawable()` 包进
   `SharedDrawable`」的代码（FML splash 的共享上下文路径）在 LWJGLY 上都不可用。SDL 模式下
   Forge splash 已被 `MixinSplashProgress` 关掉；GL 路径上该路径是否会被走到需要宿主/上游确认。

## 5. LWJGLServiceProvider 的初始化顺序

`LWJGLServiceProvider.LWJGL` 是 `static final`，在**首次使用该类**时构造服务。
判定用「进程实际运行的后端」而不是 flag：

```java
if (BackendManager.RENDER_BACKEND.isSDLGPU()) → SDLGPULWJGLService
else                                        → LWJGL3Service
```

理由与顺序分析：

- 读 `BackendManager.RENDER_BACKEND` 会**强制触发后端选择**（静态字段 = `loadService()`），
  而选择本身是确定且幂等的，因此「提前」触发不影响结果；不需要窗口存在，因为
  `SDLGPURenderBackend.isAvailable()` 只做 §1 的四项判断，不看 `engaged`。
- 触发点在窗口交接之前是安全的：`BackendManager.init()`（建 presenter / splash target /
  交换链格式缓存）在 `GLStateManager` 初始化时才调，而那时交接已经完成。
- 反向依赖已核查：`Lwjgl3GLRenderBackend` / `SDLGPURenderBackend` 的静态初始化都不引用
  `LWJGLServiceProvider` 或 `BackendManager.RENDER_BACKEND`，不存在类初始化环。
- 上游用系统属性 `celeritas.lwjglService` + `SDLGPULWJGLService.bind()`（在窗口认领后绑定）。
  Actinium 不采用：那个属性在本仓库没有消费方，按后端身份判定更直接，
  也与 Phase 7 的 `BackendOptions` 语义一致。

## 6. SDLGPULWJGLService 刷新

在原有转发基础上补齐了三处对上当前 `RenderBackend` 表面的实现（原来是对上游的
`return 0` / `-1` / 空实现，会让调用方拿到假失败）：

| 方法 | 之前 | 现在 |
|---|---|---|
| `nglMapBuffer` | `return 0`（恒失败，`BufferCopyFunctions` 会抛 "could not be mapped"） | `memAddress(mapBuffer(...))`，后端返回 null 时才是 0 |
| `glGetUniformBlockIndex` | `-1`（TODO 占位） | `RENDER_BACKEND.getUniformBlockIndex` |
| `glUniformBlockBinding` | 空实现（TODO 占位） | `RENDER_BACKEND.uniformBlockBinding` |
| `glBindFragDataLocation` | 空实现 | 转发到后端（基类为空实现，保持一致） |

新增 `create()` 工厂（`LWJGLServiceProvider.constructInstance` 通过反射找的就是它）。
上游的 `create()` 会在未 engaged 时抛异常；这里不设该守卫：服务对
`BackendManager.RENDER_BACKEND` 是动态转发的，后端身份本身就是充分条件，
早于窗口认领构造也只会得到「与 GL 路径同样可用性」的行为，不会静默降级。

## 7. 未恢复项（相对已撤除的 GLFW 兼容层）

撤除清单见 `sdl-gpu-cleanroom-glfw-compat.md`。**没有**恢复的东西及原因：

| 未恢复 | 原因 |
|---|---|
| 自建 `GLFW_NO_API` 窗口、`adoptWindow` 字段镜像、自建 GLFW 回调、`initializeLwjglxInput`、`flushPendingMove` | 窗口与输入整体交给 bridge，这些是 Actinium 侧的临时适配，规范做法在 bridge 侧 |
| `MixinGuiScreenSDLInput` | 输入时序问题应随 bridge 的输入实现消失；若仍出现，下沉到 bridge |
| `MixinDisplayWindowCompat`（跳过 `Display$Window.releaseCallbacks`） | 新的 destroy 重定向跳过**整个** `Display.destroy()`，`releaseCallbacks` 不会再被调用。若将来 bridge 改为保留 GLFW 窗口（不抑制 GL context），则需恢复该 skip |
| `MixinNetHandlerPlayClientJoinGame`（延后 join 包） | 当时的根因是「客户端线程跑太快」＋帧率限速被绕过；现在 vsync 推送到后端来限速，且该 mixin 属于时序兜底而非启用路径 |
| `MixinMinecraft` 的 `getLimitFramerate` 放宽、F3 自动面板 | 调试期临时改动 |
| `MixinSplashProgress` 的「关掉 splash」**之外**的部分、`MixinMinecraftCoreProfileDisplay` 的 GLFW 分支 | 新分支只做「跳过 GL 循环 + 交接」，不建窗口、不碰 Display 字段 |

**相对原计划的偏离**（写代码时核对源码后修正）：

1. **没有新增 `Display.create` 的 mixin**。原计划在 `org.lwjgl.opengl.Display.create()V`
   的 RETURN 注入，但那个类（以及 `org.lwjglx.opengl.Display`）不在 mixin universe 内，
   注入不会生效；而 Actinium 自己的 `MixinMinecraftCoreProfileDisplay` 本来就**替换了**
   `Minecraft.createDisplay`，调用点就在我们手里，直接在那里交接即可。
2. **`updateDisplay` 不 cancel，改为重定向内部的 `Display.update()` 调用**。cancel 会连带
   跳过 `checkWindowResize()`；重定向只替换 GL swap（改成后端 pump），外层逻辑保持原样。
   present 由帧钩子的 `onFrameEnd()` 驱动。
3. **去掉上游的 lwjgl3ify `DisplayEvents` 依赖检查**。该检查是 88a73961 同步上游时带入的，
   而 Actinium 根本不使用 `DisplayEvents`（文档亦记录该接线**未同步**），Cleanroom 实例也
   不装 lwjgl3ify —— 保留它会让 `isSDLGPUAvailable()` 恒为 false，**后端永远选不中**。
   现在只要求 lwjgl-sdl / lwjgl-shaderc / lwjgl-spvc。

**2026-10-12 追记**：本节其余内容仍然成立，但「bridge」这个词现在指宿主（新 CRL 上即
LWJGLY 的 `WindowBridge` 实现）而不是 Vintage-SDL-Bridge coremod；上表里所有「交给 bridge」
的条目因此指向宿主侧，而不是一个还要写出来的 coremod。coremod 路线作废原因见开头。

## 8. 验证

自动化（本次已跑）：

```
./gradlew :glsm:compileJava :sdl-gpu:compileJava :shader:compileJava compileJava compileTestJava
./gradlew :glsm:test :sdl-gpu:test
./gradlew :test
./gradlew check
```

新增/改写的测试（2026-10-12，跑在根项目 `src/test`，两种运行时的类都在其测试
classpath 上）：

- `src/test/.../sdlgpu/compat/LwjglyLayerTest.java` —— 探测：本 JVM 能看到 LWJGLY 时为真；
  用一个看不到任何东西的 null-parent loader 断言「老 CRL 上为假」这一分支。
- `src/test/.../sdlgpu/compat/lwjgly/LwjglyWindowHostTest.java` —— 宿主窗口契约，用真实的
  `WindowBridge` 测试替身（`TestWindowBridge`）：未安装 bridge 时 `available()` 为假、
  门控 `isWindowSupplierPresent()` 为假、`window()` 报出具名义务；安装后句柄/宽高/
  resize 消费都来自 bridge；句柄为 0 时 fail-fast 并点出 `SDL_WINDOW_OPENGL` 义务。
- `sdl-gpu/src/test/.../SDLGPUGateAvailabilityTest.java`（保留）—— 未请求 SDL 时门控关闭、
  且不会去碰 GPU 设备（`isDeviceReady()` 短路）；依赖探测在位。
- 已删除：`SDLGPUWindowHandoverTest`（blackboard 交接契约作废）与
  `com/dhj/vintagesdlbridge/VintageSdlBridgeLoadingPlugin` 测试替身。

「设备不可用」那一行无法在无 GPU 的测试 JVM 里确定性复现（请求开关是进程级系统属性、
设备探测要真起 SDL），只能在实机验证时观察告警日志。GLSM 的 null-drawable 退化同样无法在此
测试 JVM 里跑：`org.lwjgl.opengl.Display`（LWJGL2 壳）需要宿主重定位后的 `org.lwjgl3.**`
才加载得起来，故该路径留给实机验证（代码见 §3.3）。

实机（待宿主提供可被 GPU 设备认领的窗口后）：

```
./gradlew runClientSdl          # = runClient + -Dangelica.sdlgpu.enable=true
```

预期日志：`SDL GPU device created: ...` → `SDL GPU engaged on the LWJGLY host window 0x...`
→ `SDLGPURenderBackend initialized: ...`；若缺前置条件则是
`SDL GPU requested but unavailable, running on OpenGL: <原因>`。

## 9. 未决项

- **全屏切换**：`ActiniumWindowModeController` 仍走 `Display.setFullscreen` /
  `Display.setDisplayMode`（GLFW 语义），SDL 模式下会失败或无效。窗口重建后重新认领
  （`Device.claimWindow` + `onPreSwapchainInvalidatingChange`）尚未接线。
- **窗口缩放跟踪**：SDL 模式下 `Display.update()` 被替换，`Display.processMessages()`
  的尺寸跟踪不再刷新，`checkWindowResize()` 实际不会触发。后端自己的 swapchain 重建走
  `Device.wasWindowResized()` → `WindowBridge.consumeResized()`。
- **DWM/taskbar**：`ForgeHooksClient.initializeWindowsInformation/setWindowStyle/
  initializeTaskbarAPI` 在 SDL 模式被跳过（它们通过 `Display.getWindow()` 取 HWND，
  SDL 窗口没有）。要做的话应从已认领的 SDL 窗口取
  `SDL_PROP_WINDOW_WIN32_HWND_POINTER` 再自行调用。
- **`BackendStartGuard` / `BackendOptions.latch`**：本仓库仍无调用方。它们服务的是
  「后端选择持久化在配置里 + 上次 SDL 启动没到主菜单就重置回 OpenGL」，而 Actinium
  的后端选择只有系统属性 `angelica.sdlgpu.enable`（无配置项、无可重置状态），
  接上就是空转。等有了渲染器选择配置界面再按上游接线
  （上游调用点：`AngelicaClientTweaker` 的 `BackendStartGuard.begin` / `BackendOptions.latch`、
  `ClientProxy` 的 `BackendStartGuard.finish`、`RendererOptionPages` 的 `tripped()`）。
- **输入**：宿主尚未把事件喂给 `org.lwjgl.input.*`，游戏内键鼠不可用属预期（见 §4 第 5 条）。
