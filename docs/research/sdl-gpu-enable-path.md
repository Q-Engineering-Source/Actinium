# SDL GPU 后端启用路径（Cleanroom + Vintage-SDL-Bridge）

> 状态：**已写出，未做运行时验证**（2026-10-12）。CRL 侧的 SDL 窗口尚不存在
> （Vintage-SDL-Bridge 目前只初始化 SDL 事件子系统），因此这条路径**惰性生效**：
> 没有 bridge 时后端不会被选中，OpenGL 路径逐字节不变。本文档记录启用路径的组件、
> 窗口交接契约、bridge 侧待实现项，以及**明确没有**从已撤除的 GLFW 兼容层恢复的东西
> （撤除清单见 `sdl-gpu-cleanroom-glfw-compat.md`）。

## 1. 启用路径总览

窗口由 **Vintage-SDL-Bridge** 提供（它是 coremod，可以用 ASM 改 lwjglx 库类），
Actinium 只负责认领窗口、安装 drawable 并接管所有 GL 特定的引擎路径。

```
启动
 │  SDLGPURenderBackend.isAvailable()
 │    → SDLGPUGate.isHandoverAvailable()
 │        = BackendOptions.sdlGpuRequested()      (-Dangelica.sdlgpu.enable=true)
 │       && isSDLGPUAvailable()                   lwjgl-sdl / shaderc / spvc 在位
 │       && isBridgePresent()                     bridge coremod 在位
 │       && isDeviceReady()                       SDL GPU 设备创建成功（探测）
 │  任一条不满足 → 后端不入选 → 走原有 OpenGL 路径（后端选择只发生一次）
 ▼
Minecraft.createDisplay()（MixinMinecraftCoreProfileDisplay，HEAD + cancellable）
 │  isHandoverAvailable() == false → 原核心 profile 创建流程（不变）
 │  isHandoverAvailable() == true  → celeritas$createSdlDisplay(...)
 │       Display.create(PixelFormat, ContextAttribs)   ← bridge 在这里换掉窗口创建
 │       SDLGPUGate.handOverWindow()
 │           SDLGPUWindowHandover.readWindow()         读 Launch.blackboard
 │           Device.claimWindow(sdlWindow)             SDL_ClaimWindowForGPUDevice
 │           SDLGPUDisplayBridge.ensureDrawableInstalled()   装 SDLDrawable
 │           BackendManager.RENDER_BACKEND.onPostWindowCreate(window)  能力串 + 缓存
 │       ci.cancel()                                  跳过 GL 版本探测循环
 │       跳过 Forge 的 initializeWindowsInformation / setWindowStyle / initializeTaskbarAPI
 ▼
帧循环（MixinMinecraft 既有帧钩子，未改动）
 │  runGameLoop HEAD              → onFrameBegin()
 │  updateDisplay BEFORE          → onFrameEnd()   （提交最终目标，present）
 │  updateDisplay 内 Display.update() → @Redirect → RENDER_BACKEND.pumpDisplayMessages()
 │  updateDisplay AFTER           → onFrameBegin()
 │  runGameLoop HEAD（首个游戏循环）→ 推送一次 vsync 到后端
 ▼
退出
   shutdownMinecraftApplet 里 Display.destroy() → @Redirect：SDL 模式直接跳过
```

## 2. 组件与文件

| 组件 | 文件 | 职责 |
|---|---|---|
| 后端注册 | `src/main/resources/META-INF/services/com.gtnewhorizons.angelica.glsm.backend.RenderBackend` | 加回 `com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend`，使其参与后端选择 |
| 可用性门控 | `sdl-gpu/.../SDLGPUGate.java` | `isHandoverAvailable()` / `isBridgePresent()` / `isDeviceReady()`；失败原因只告警一次 |
| 窗口交接契约（读侧） | `sdl-gpu/.../SDLGPUWindowHandover.java`（新） | 读 `Launch.blackboard`，类型/空值校验；探测 bridge coremod 是否在位 |
| 窗口交接（认领） | `sdl-gpu/.../SDLGPUGate.handOverWindow()` | `Device.claimWindow` → 装 drawable → `engaged` → `onPostWindowCreate` |
| 后端可用性 | `sdl-gpu/.../SDLGPURenderBackend.java` | `isAvailable()` = `SDLGPUGate.isHandoverAvailable()` |
| Display 创建分支 | `src/main/java/com/dhj/actinium/mixin/vintage/core/startup/MixinMinecraftCoreProfileDisplay.java` | SDL 模式：一次 `Display.create` + 交接 + `ci.cancel()`，不跑 GL 循环、不跑 GL 校验、不跑 Forge 窗口钩子 |
| 帧/present/退出 | `src/main/java/com/dhj/actinium/mixin/vintage/core/MixinMinecraft.java` | `Display.update()` 重定向到后端 pump；vsync 推送；`Display.destroy()` 跳过 |
| 字体批处理 | `src/main/java/com/gtnewhorizons/angelica/client/font/BatchingFontRenderer.java` | SDL 模式没有 GL context，`hasCurrentGlContext()` 直接放行 |
| FML 启动画面 | `src/main/java/com/dhj/actinium/mixin/vintage/core/startup/MixinSplashProgress.java` | SDL 模式强制关掉 Forge splash（它要 GL 共享 context） |
| LWJGL 服务桥 | `src/lwjglCommon/java/com/mitchej123/lwjgl/LWJGLServiceProvider.java`、`sdl-gpu/.../SDLGPULWJGLService.java` | Embeddium/Celeritas 的 GL 对象调用落到后端，而不是伪 capability 的哨兵函数指针 |
| 开发运行 | `build.gradle` | `runClientSdl`（run 配置 + IDEA 运行项），带 `-Dangelica.sdlgpu.enable=true` |

`MixinSplashProgress` / `MixinMinecraft` / `MixinMinecraftCoreProfileDisplay` 都已在
`mixins.actinium.vintage.json` 里，**本次没有新增 mixin**。

## 3. 窗口交接契约（Actinium ↔ Vintage-SDL-Bridge）

### 3.1 数据通道

| 项 | 值 |
|---|---|
| 通道 | `net.minecraft.launchwrapper.Launch.blackboard` |
| 键 | `actinium.sdlgpu.sdlWindow`（常量：`SDLGPUWindowHandover.WINDOW_KEY`） |
| 类型 | `Long`，SDL `SDL_Window*` 指针，非 0 |
| 写入时机 | bridge 在 `org.lwjgl.opengl.Display.create(...)` 返回前写入 |
| 读取时机 | `Display.create` 返回后立即读（`SDLGPUGate.handOverWindow()`） |
| 缺失/类型不符 | `IllegalStateException`，消息点名 bridge 与键名，**不回退 GL** |

不用的通道（各有原因）：bridge 侧静态 API 会让 Actinium 编译期依赖 bridge；
`org.lwjglx.opengl.Display` 字段镜像就是被撤除的 GLFW 兼容层做法
（见 `sdl-gpu-cleanroom-glfw-compat.md`），新方案里窗口状态由 bridge 自己维护。

### 3.2 所有权划分

- **bridge 拥有**：SDL 窗口的创建、尺寸、显示、销毁；SDL 事件泵；输入（必须喂给
  `org.lwjgl.input.Mouse/Keyboard`，vanilla 读的是这一对）。
- **Actinium 拥有**：GPU 设备的创建与销毁；`SDL_ClaimWindowForGPUDevice`；
  `Display.drawable` 的替换（`SDLDrawable`）；present；GLCapabilities 的替换。

## 4. bridge 侧待实现项

按当前 Vintage-SDL-Bridge 的进度，以下都还没有实现（Actinium 侧已按这些约定写好）：

1. **换掉窗口创建**：bridge 是 coremod，可加 `IClassTransformer`（参考
   `MacDisplayForwardCompatTransformer` 的做法）改写
   `org.lwjgl.opengl.Display.create()`：不建 GLFW 窗口、不建 GL context，建原生
   SDL 窗口（尺寸取 `Minecraft.displayWidth/displayHeight`），并在返回前把
   `SDL_Window*` 写进 `Launch.blackboard["actinium.sdlgpu.sdlWindow"]`。
   - 注意：`org.lwjgl.opengl.Display`(lwjglx-1.0.0 壳) 与 `org.lwjglx.opengl.Display`
     (lwjglxx) **都在 mixin universe 之外**，bridge 现有的 `DisplayLifecycleMixin`
     对它们注入不会生效（`require = 0` 所以静默跳过），必须走 ASM transformer。
2. **保持 Display 状态一致**：SDL 后端读 `org.lwjglx.opengl.Display` 的
   `wasResized()`（`Device.wasWindowResized()`）并调用其 `processMessages()`
   （后端的 display pump），bridge 必须让这两个方法在 SDL 窗口上仍然有意义。
3. **输入**：`org.lwjgl.input.Mouse/Keyboard` 与 lwjglxx 侧的输入都要被喂；
   `MainThreadPump.pollInput()` 会 poll 它们。
4. **窗口生命周期**：销毁窗口（Actinium 在 SDL 模式不再调 `Display.destroy()`）。
5. 可选：Windows 下把 HWND 交给 Forge/CRL 的 DWM/taskbar 接线
   （见第 7 节未决项）。

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

## 8. 验证

自动化（本次已跑）：

```
./gradlew :glsm:compileJava :sdl-gpu:compileJava :shader:compileJava compileJava compileTestJava
./gradlew :glsm:test :sdl-gpu:test
./gradlew :test
./gradlew check
```

新增测试：

- `sdl-gpu/src/test/java/com/gtnewhorizons/angelica/sdlgpu/SDLGPUWindowHandoverTest.java`
  —— 交接契约：正常读取、缺键、类型不符、空指针、bridge 在位/缺失（用
  `sdl-gpu/src/test/java/com/dhj/vintagesdlbridge/VintageSdlBridgeLoadingPlugin.java` 这个
  测试替身 + 一个看不到它的 null-parent loader）。
- `sdl-gpu/src/test/java/com/gtnewhorizons/angelica/sdlgpu/SDLGPUGateAvailabilityTest.java`
  —— 未请求 SDL 时门控关闭、且不会去碰 GPU 设备（`isDeviceReady()` 短路）；依赖探测在位。
  「bridge 缺失 / 设备不可用」两行无法在无 GPU 的测试 JVM 里确定性复现（请求开关是
  进程级系统属性、设备探测要真起 SDL），只能在实机验证时观察告警日志。

实机（待 bridge 提供窗口后）：

```
./gradlew runClientSdl          # = runClient + -Dangelica.sdlgpu.enable=true
```

预期日志：`SDL GPU device created: ...` → `SDL GPU engaged on window 0x...`
→ `SDLGPURenderBackend initialized: ...`；若缺前置条件则是
`SDL GPU requested but unavailable, running on OpenGL: <原因>`。

## 9. 未决项

- **全屏切换**：`ActiniumWindowModeController` 仍走 `Display.setFullscreen` /
  `Display.setDisplayMode`（GLFW 语义），SDL 模式下会失败或无效。窗口重建后重新认领
  （`Device.claimWindow` + `onPreSwapchainInvalidatingChange`）尚未接线。
- **窗口缩放跟踪**：SDL 模式下 `Display.update()` 被替换，`Display.processMessages()`
  的尺寸跟踪不再刷新，`checkWindowResize()` 实际不会触发。依赖 bridge 保持
  `org.lwjglx.opengl.Display` 的尺寸/resized 状态。
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
- **输入**：bridge 尚未把事件喂给 `org.lwjgl.input.*`，游戏内键鼠不可用属预期。
