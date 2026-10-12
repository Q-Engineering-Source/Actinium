# SDL GPU 后端的 Cleanroom GLFW 兼容层（撤除清单）

> 状态：**已撤除（2026-08-31）**。本文档记录 Actinium 曾为在 Cleanroom Loader 上
> 运行 Angelica 的 `sdl-gpu` 后端而添加的 **GLFW 窗口兼容代码**。这些代码是为绕过
> Cleanroom 的 lwjglxx 窗口创建而写的**临时适配**，**未必符合上游 Angelica 的
> 代码规范**（大量反射、事件队列节流、handleInput 接管等）。全文保留作为历史
> 记录与未来重接（Cleanroom 提供原生 SDL 窗口后）的参考。
>
> **2026-10-12 追记**：SDL 路线此后改为只走新 CRL 的 LWJGLY + 宿主 `WindowBridge`，老 CRL
> 的 coremod 接管路线作废（老 CRL 窗口恒带 GL context，`SDL_ClaimWindowForGPUDevice` 必失败）。
> 本文档里提到的 `SDLGPUDisplayBridge`、`SDLDrawable`、`SDL` 版 `SDLGPUWindowHandover`
> 等文件也已在 2026-10-12 一并删除；当前形态见 `sdl-gpu-enable-path.md` 与
> `lwjgl2-compat-dual-runtime.md`。

## 撤除结果（2026-08-31）

按本文档清单与撤除计划执行，**GLFW 窗口兼容层已全部移除**：

- **已删除**：`SDLGPUDisplayBridge` 的 Display 镜像 / 自建 GLFW 回调 /
  `flushPendingMove` / `initializeLwjglxInput` / `safeDisplayDestroy`（文件收缩回
  上游 Angelica 形态，仅保留 drawable 安装与 SDL drawable 判断）；`SDLGPUGate` 的
  `createSDLGPUDisplay` / `fallBackToGL` / `rememberIcons`（Gate 收缩为设备探测与
  可用性入口）；`MixinMinecraft` 的 `sdlUpdateDisplay` / `sdlDisplayDestroy` /
  vsync 推送；`MixinGuiScreenSDLInput`、`MixinDisplayWindowCompat`、
  `MixinNetHandlerPlayClientJoinGame` 三个 mixin；`MixinMinecraftCoreProfileDisplay`
  与 `MixinSplashProgress` 的 SDL 接线；`LWJGLServiceProvider` 的 `USE_SDL_GPU`
  分支；`BatchingFontRenderer` 的无 GL context 分支；`runClientSdl` 任务与 SDL
  JVM 参数；调试期临时改动（自动 F3、第三方 mod compile-only、validation 参数、
  `getLimitFramerate` 放宽）。
- **启用路径已断开**：`SDLGPURenderBackend` 已从 `META-INF/services/...RenderBackend`
  中移除，后端选择永远不会命中 SDL；`sdl-gpu` 模块源码与测试完整保留（编译、嵌入
  jar、测试照常），`Device.claimPlatformWindow` / `SDL_ShowWindow` / finalTarget
  修复 / persistent 写入通知 / GLSL 保留字兜底等架构性修复全部保留。
- **重接入口**：见 `sdl-gpu-enable-path.md`（2026-10-12）。启用路径已经写出：后端加回
  `META-INF/services/com.gtnewhorizons.angelica.glsm.backend.RenderBackend`，窗口由
  **Vintage-SDL-Bridge** coremod 提供并通过 `Launch.blackboard["actinium.sdlgpu.sdlWindow"]`
  交给 Actinium（`SDLGPUWindowHandover` / `SDLGPUGate.handOverWindow`），Actinium 侧只保留
  认领窗口、装 drawable 与接管 GL 特定引擎路径（display 创建分支、present/pump、销毁重定向、
  字体/FML splash 的无 context 分支、LWJGL 服务桥）。前提条件未满足时后端不会被选中，
  OpenGL 路径不变。

## 背景

- Angelica 的 `sdl-gpu` 在 GTNH 运行于 lwjgl3ify：`DisplayEvents` 可以抑制 GL
  context 创建，SDL 后端直接认领 GLFW 窗口（`SDL_ClaimWindowForGPUDevice`）。
- Cleanroom Loader 的 lwjglxx **总是**创建「GLFW 窗口 + GL context」。Vulkan 无法
  认领带 GL context 的窗口（`VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`），所以 Actinium
  必须**绕过 lwjglxx 的窗口创建**：自己用 `glfwCreateWindow(GLFW_NO_API)` 建窗口，
  再让 SDL 认领。
- 绕过 lwjglxx 窗口创建 = 绕过了 lwjglxx 在 `Display.create()` 里做的**所有窗口
  相关初始化**（GLFW 回调注册、Mouse/Keyboard 创建、Display 静态字段）→ 需要
  手动补齐，这就是本文档的「兼容层」。

## 兼容层清单

| 文件 | 改动 | 原因 / 备注 |
|---|---|---|
| `SDLGPUGate.createSDLGPUDisplay` | 用 `Minecraft.displayWidth/Height` 建 GLFW_NO_API 窗口（**不用 vidMode**），`adoptWindow` 后 `claimPlatformWindow` | vidMode 尺寸会与 Minecraft 的 framebuffer/视口错位，画面只占左上角。窗口尺寸必须匹配 `displayWidth` |
| `SDLGPUDisplayBridge.adoptWindow` | VarHandle 镜像 lwjglxx `Display` 静态字段（handle/created/width/height/fbSize/title/resizable） | lwjglxx 的 `Display.*` 查询在绕过创建后全部失效 |
| `SDLGPUDisplayBridge.installLwjglxWindowCallbacks` | **自建 GLFW 回调**（cursor/button/scroll/key/char/**focus**）转发到 lwjglxx 公开输入 API（`Mouse.addMoveEvent` 等），反射写入 `Display$Window` 静态字段后调 `setCallbacks()`；**焦点回调镜像 `displayFocused`**（lwjglxx 的 `Display.isActive()` 只读该字段） | lwjglxx 的回调对象在 `Display.create()` 里创建，被绕过后字段为 null；必须 setAccessible 才能进入 package-private 内部类。回调对象要强引用（GLFW 经 native 指针回调）。**焦点不镜像则 `Display.isActive()` 恒 false → `pauseOnLostFocus` 自动弹 Esc 菜单** |
| `SDLGPUDisplayBridge.initializeLwjglxInput` | 补调 `Mouse.create()` / `Keyboard.create()` | 两者在 `Display.create()` 里初始化 |
| `SDLGPUDisplayBridge.flushPendingMove` | cursorPos 回调只记录坐标，每帧由 SDL pump 合并为 1 个 move 事件 | lwjglxx 输入队列只有 32 个槽，Minecraft 每 tick 才 poll 一次；move 事件流会挤掉按钮事件 → 点击丢失 |
| `MixinMinecraft.actinium$sdlUpdateDisplay` | `updateDisplay` HEAD 替换：`glfwPollEvents` + `SDL_PumpEvents` + `flushPendingMove` + `Mouse.poll()` + `Keyboard.poll()` + cancel | lwjglxx 在 `Display.update()` 里 poll 输入；SDL 路径替换了 updateDisplay，必须补 poll，否则输入队列永不排空 |
| `MixinMinecraft` 帧钩子（`beginRenderFrame`/`endStreamingFrame`/`actinium$finishDisplaySwap`） | `onFrameBegin`/`onFrameEnd` 挂到 `runGameLoop` 的 `updateDisplay` 调用前后 | 对齐 Angelica 的 `MixinMinecraft_FrameHook`；present 由帧钩子驱动 |
| `MixinMinecraft.actinium$sdlDisplayDestroy`（@Redirect `shutdownMinecraftApplet` 里的 `Display.destroy()`） | SDL 模式改调 `SDLGPUDisplayBridge.safeDisplayDestroy()`（仅 `glfwDestroyWindow`），GL 模式反射走原销毁 | LWJGL2 兼容壳的 `Display.destroy()` 先执行 `Display$Window.releaseCallbacks()`，其回调字段在 SDL 路径从不填充 → 每次退出 NPE（壳类对 mixin universe 不可见，`MixinDisplayWindowCompat` 覆盖不到） |
| `MixinGuiScreenSDLInput.sdlHandleInput` | 接管 `GuiScreen.handleInput`：直接 `while(Mouse.next())` → 按钮按下事件经 `@Invoker` 直调 `mouseClicked`；move/键盘事件走原 `handleMouseInput`/`handleKeyboardInput` | vanilla 的循环里 Forge `GuiScreenEvent.MouseClickedEvent` 会吞掉点击（SDL 事件时序下），所以绕过 Forge 事件门 |
| `LWJGLServiceProvider` / `SDLGPULWJGLService` | Embeddium 的 `LWJGLService` 桥：所有 GL 调用转发 `BackendManager.RENDER_BACKEND` / `GLStateManager` | 不接这个桥，Embeddium 的 GlShader 会拿到 SENTINEL 指针崩溃 |
| `glsm/.../PersistentStreamingBuffer`（writeAt/writeAtStart） | memCopy 写 mapped buffer 后调 `RENDER_BACKEND.onPersistentBufferWrite` | SDL 后端的 `mapBufferRange(persistent)` 返回 **CPU staging**（非真实 GPU 映射）；不通知后端，GPU 永远看不到顶点数据 → 所有 persistent 路径的绘制黑屏。OpenGL 后端该调用是空实现，无副作用 |
| `Device.claimWindow` | 加 `SDL_ShowWindow` | SDL3 Vulkan 的 swapchain acquire 在窗口未 SHOWN 时无限阻塞 |
| `FrameManager.presentBlit` / `blitNamedFramebuffer` | `drawFramebuffer==0` 时目标用 `finalTarget` | SDL 无默认 framebuffer 语义 |
| `build.gradle` | `runClientSdl` 任务：继承 `runClient` 的完整启动配置（含 `${mcp_to_srg}` 属性表）+ `-Dangelica.sdlgpu.enable=true`（**调试期另加 `-Dangelica.sdlgpu.debug=true` 启用 Vulkan validation，见「调试期临时改动」**） | Unimined 只对固定名 `client`/`server` 应用完整 run 模板 |

## 已知的「不规范」点（撤除时重点核对）

1. **反射密集**：`SDLGPUDisplayBridge` 用 `VarHandle` / `Method.setAccessible` 操作
   lwjglxx 内部（`Display$Window` 是 package-private 内部类，只能反射）。
2. **事件节流依赖 lwjglxx 内部容量**（32 槽）与「每 tick poll 一次」的时序；
   CRL 原生 SDL 窗口后输入路径完全不同，节流应删除。
3. **`handleInput` 接管绕过 Forge 事件门**：`MouseClickedEvent.Pre/Post` 不再发出，
   依赖 Forge 事件的 mod 在 SDL 路径上收不到点击事件。
4. **hover 无效**（按钮 hover 变色不生效）——用户已知悉、暂不处理；撤除时若
   CRL 提供标准输入，应验证 hover 是否恢复。
5. ~~`MixinDisplayCompat` 的 target `org.lwjgl.opengl.Display` 不存在~~ **已删除**
   （2026-08-15）：mixin universe 看不到 lwjglx-1.0.0 的 LWJGL2 兼容壳类，注入从未
   生效；改为在 `SDLGPUDisplayBridge.adoptWindow` 里**反射镜像壳的
   `Display$Window.handle`**（壳的 `getWindow()` 供 DWM/taskbar 集成取句柄），
   并删除 `MixinDisplayCompat`。
6. **`glfwGetWindowUserPointer(0)` 等 GLFW 窗口句柄相关代码**（曾用于诊断）已清理。
7. 依赖 `Display.getPixelScaleFactor()`、`Display.getHeight()` 等 lwjglxx 语义做
   坐标换算，与原生 SDL 窗口的坐标语义未必一致。
8. **焦点跟踪是自补的**：lwjglxx 的 `windowFocusCallback` 字段从不初始化，
   `displayFocused` 只在 `Display.create()` 赋一次初值（GL 模式靠
   `ForgeEarlyConfig.WINDOW_START_FOCUSED`），此后永不更新；Actinium 自装
   focus 回调并镜像 lwjglxx 与 LWJGL2 壳两个 `displayFocused`。CRL 原生 SDL
   窗口后焦点由 CRL 管理，此镜像应删除。

## CRL 换 SDL 窗口后的撤除计划（建议顺序）

1. **删除** `SDLGPUDisplayBridge` 的 Display 镜像 + 自建回调 + `flushPendingMove` +
   `initializeLwjglxInput`（若 CRL 原生 SDL 窗口自带输入/Display 查询）——**含焦点
   回调与两个 `displayFocused` 镜像**（见不规范点 8）。
2. **简化** `MixinMinecraft.actinium$sdlUpdateDisplay`：事件处理交给 CRL/SDL，
   只保留 present 相关（或整个移除，帧钩子保留）。
3. **删除** `MixinGuiScreenSDLInput`（若 CRL 输入不触发 Forge 事件吞点击问题）；
   若仍触发，则把 `mouseClicked` 直调下沉到 CRL 侧。
4. **保留**（架构性，与 GLFW 无关）：
   - 帧钩子（onFrameBegin/onFrameEnd）与 present 链路
   - `PersistentStreamingBuffer` 的 `onPersistentBufferWrite` 通知（SDL 后端 staging
     语义不变）
   - 窗口尺寸匹配原则（SDL 窗口尺寸 = `displayWidth/Height`）
   - `Device.claimWindow` 的 `SDL_ShowWindow`、`blitNamedFramebuffer` finalTarget 修复
5. **清理** `MixinDisplayCompat`（target 不存在）——**已完成**（2026-08-15，见不规范点 5）。
6. 回归验证：主菜单显示、鼠标/键盘、点击切换界面、窗口 resize、进入世界渲染。

## 调试期临时改动（撤除时一并恢复）

以下改动与 GLFW 兼容层无直接关系，是为调试 SDL 后端而临时加的，**调试结束后应
删除或恢复**：

| 改动 | 位置 | 内容 | 恢复方法 |
|---|---|---|---|
| 进世界自动打开 F3 面板 | `MixinMinecraft`（`actinium$debugPanelOpenedForWorld` 字段 + `actinium$autoOpenDebugPanel` 注入） | 每次世界加载完成后置 `gameSettings.showDebugInfo = true`（SDL 模式下游戏内键盘无效，无法手动按 F3） | 移除字段与注入方法（约 12 行） |
| Vulkan validation | `build.gradle`（`runClientSdl` 两处 JVM 参数） | `-Dangelica.sdlgpu.debug=true`；启动日志出现 “Validation layers enabled” | 移除两处该参数 |
| 第三方渲染 mod 改为仅编译 | `gradle/scripts/dependencies.gradle`（注释 “compile-only while debugging the SDL GPU backend”） | DH / JourneyMap / StellarCore：`modImplementation`→`modCompileOnly`；celeritas-dynamic-lights / extra / leafculling：`modRuntimeOnly`→`modCompileOnly` | 改回 `modImplementation` / `modRuntimeOnly`（加载 mod 数 13→19） |

## 相关提交

- `fc5f88d` fix(sdl-gpu): get the SDL GPU backend through game startup
- `51117b2` fix(sdl-gpu): rename GLSL reserved words in the SDL shader pipeline
- `326a0ad` feat(sdl-gpu): embed the SDL GPU backend into the mod jar
- `1868a52` / `382994f` build: runClientSdl 启动配置
- `52be235` fix(sdl-gpu): present menu frame via persistent buffer sync and window size match
- `e69c638` fix(sdl-gpu): restore mouse and keyboard input on the SDL window
- `8d6f913` docs: sdl-gpu GLFW 兼容层清单
- `ca1f888` docs: sdl-gpu 上游差异清单
- `cf9f5dc` fix(sdlgpu): track window focus and keep persistent mappings alive
- `ff12878` fix(sdlgpu): safe display destroy on shutdown
