# LWJGL2 兼容双运行时（一份 jar 跑两种 Cleanroom）

> 状态：**已落地，未做运行时验证**（2026-10-12）。本文记录 Actinium 如何在**同一份 jar**
> 里同时服务两代 Cleanroom 的 LWJGL2 兼容层，逐文件列出处置与加载时机依据，并给出
> 「等宿主确认」清单。SDL 启用路径的当前形态见 `sdl-gpu-enable-path.md`；与上游 sdl-gpu
> 的差异见 `sdl-gpu-upstream-diffs.md`。

## 1. 两条运行时

| | 老 CRL（当前多数用户） | 新 CRL |
|---|---|---|
| LWJGL2 API（`org.lwjgl.**`） | `com.cleanroommc:lwjglx:1.0.0`（LWJGL2 的类放进 `org.lwjgl` 包） | LWJGLY `com.cleanroommc:lwjgly:1.0.0` |
| lwjgl3ify 风格 shim（`org.lwjglx.**`） | `com.cleanroommc:lwjglxx:1.1.x` | **不存在** |
| LWJGLY SPI（`com.cleanroommc.lwjgly.**`） | **不存在** | `com.cleanroommc:lwjgly-spi:1.0.0`（`LWJGLY`、`LWJGLYTransformer`、`spi.WindowBridge`） |
| 窗口/上下文所有权 | 宿主的 lwjglx/lwjglxx 层（GLFW 窗口 + GL context） | 宿主实现 `WindowBridge`；`org.lwjgl.opengl.Display` 只做转发 |
| `Display.getDrawable()` | 返回真实 drawable | **恒为 null**（无 `Drawable` 模型；`DrawableGL`/`SharedDrawable`/`ContextGL` 是抛异常的占位类） |
| LWJGL 3 版本 | 3.4.1（随 CRL 一起发） | 3.4.2（LWJGLY 的基线；见 §4） |

共用 API 面只有 `org.lwjgl.**`，所以**常驻加载的代码只允许引用它**；两个兼容层各自独有的
包只在对应层存在时加载。

## 2. 规则

1. **常驻代码只引 `org.lwjgl.**` + 我们自己的类型**（两代都有）。
2. **`org.lwjglx.**` 只允许出现在「确认 lwjglxx 在位」的隔离类里**；本仓库当前**没有**
   这样的类——SDL 的 lwjglxx 适配已随 coremod 路线删除（见 §3）。
3. **`com.cleanroommc.lwjgly.**` 只允许出现在「确认 LWJGLY 在位」的隔离类里**
   （`compat/lwjgly/LwjglyWindowHost.java`），探测在 `compat/LwjglyLayer.java`
   （`Class.forName(name, false, loader)`，结果缓存，老 CRL 上短路）。
4. **编译面**：`compileOnly 'com.cleanroommc:lwjglx:1.0.0'`（它同时提供 `org.lwjgl.**` 与
   `org.lwjglx.**`，覆盖两代）+ `compileOnly 'com.cleanroommc:lwjgly-spi:1.0.0'`。
   `lwjgly` 主 jar **不进编译路径**（它的 `org.lwjgl.**` 与 lwjglx 重复）。
5. **不 ship 兼容层**：lwjglx / lwjglxx / lwjgly 全由宿主提供，仓库里没有它们的
   `runtimeOnly` / `contain`；只有 lwjgl-sdl / lwjgl-spvc / lwjgl-shaderc 的 runtimeOnly
   保持原状（SDL 后端自带的三件套，与兼容层无关）。
6. 属性 `enable_lwjgl_compat`（原 `enable_lwjglx`）语义 = 「编译期带上 LWJGL2 兼容面」。
7. **`org.lwjgl.glfw.**`（仅老 CRL 存在）与 `org.lwjgl.sdl.**`（仅新 CRL 的运行时保证存在，
   LWJGLY 自己的 `org.lwjgl` 适配层就在调它）同样只允许出现在探测后的隔离类里**。GL 渲染
   路径的窗口系统调用（vsync、刷新率、当前上下文 handle、文件拖放）经 glsm
   `backend/WindowSystem.java` 两代分派：`Class.forName("org.lwjgl.glfw.GLFW", false, loader)`
   命中走 GLFW（`GlfwWindowing`/`GlfwFileDropWatcher`），未命中且
   `Class.forName("org.lwjgl.sdl.SDLVideo", false, loader)` 命中走 SDL
   （`SdlWindowing`/`SdlFileDropWatcher`）；两者皆无则 WARN 一次并降级。编译面：
   `org.lwjgl:lwjgl-glfw` 与 `org.lwjgl:lwjgl-sdl` 均为 compileOnly，实现都由对应代的宿主
   提供。（2026-10-15 补，见 §3.5）

## 3. 逐文件处置

### 3.1 重指（`org.lwjglx.*` → `org.lwjgl.**`）

| 文件 | 原引用 | 处理 | 加载时机依据 |
|---|---|---|---|
| `src/lwjgl3/.../Lwjgl3GLRenderBackend.java` | `org.lwjglx.opengl.Display` | import 删除（该 import 未被使用；字节码里本来就没有 `org/lwjglx` 引用） | 默认 GL 后端，**两种运行时都会加载**，因此绝对不能出现 `org.lwjglx` 类型 |
| `sdl-gpu/.../SDLGPURenderBackend.java` | `org.lwjglx.opengl.Display` | → `org.lwjgl.opengl.Display`（仅用于事件泵的 `Display::processMessages`） | ServiceLoader 在两种运行时都会加载并实例化该类，签名里也只能出现 `org.lwjgl` 空间类型与我们自己的类型 |
| `sdl-gpu/.../SDLGPUGate.java` | `org.lwjglx.Sys` | → `org.lwjgl.Sys`（`Sys.initialize()`） | 同上（被 mixin 直接引用） |
| `sdl-gpu/.../device/Device.java` | `org.lwjglx.opengl.Display` | → `LwjglyWindowHost.consumeResized()`（SDL 路径专属，见 §3.2） | `Device` 在两种运行时都会被实例化（`SDLGPURenderBackend` 的字段初始化），所以只把 LWJGLY 引用放在**方法体**里，且该方法只在 SDL 路径被调用 |

### 3.2 隔离（只在 LWJGLY 在位时加载）

| 文件 | 职责 | 加载时机依据 |
|---|---|---|
| `sdl-gpu/.../compat/LwjglyLayer.java` | 只用 `Class.forName` 判断 LWJGLY 是否在位，不引用任何 `com.cleanroommc.lwjgly.*` 类型 | 常驻；门控第一道 |
| `sdl-gpu/.../compat/lwjgly/LwjglyWindowHost.java` | SDL 路径里唯一引用 `com.cleanroommc.lwjgly.*` 的类：`available()` / `requirement()` / `window()` / `width()` / `height()` / `consumeResized()` | 只在门控判定 LWJGLY 在位之后被调用（调用点全部位于 SDL 路径的方法体内；没有任何常驻静态字段引用它） |

### 3.3 删除（随 SDL coremod 路线一并作废）

| 文件/成员 | 理由 |
|---|---|
| `sdl-gpu/.../SDLGPUWindowHandover.java` | 读 `Launch.blackboard` 的 coremod 交接契约；窗口改由 `WindowBridge` 提供 |
| `sdl-gpu/.../SDLGPUDisplayBridge.java` | 往 lwjglxx 的 `Display.drawable` 装 drawable、识别 `SharedDrawable` 包装；LWJGLY 无 drawable 模型。**注**：撤除前它实际上从未初始化成功（lwjglxx 的 `SharedDrawable` 没有 `drawable` 字段，`findVarHandle` 必抛 `ExceptionInInitializerError`） |
| `sdl-gpu/.../device/SDLDrawable.java` | 同上；其 `createSharedContext() == null` 的语义在 LWJGLY 上由「null drawable 即唯一上下文」表达 |
| `SDLGPUGate.ensureDrawableInstalled()` / `isBridgePresent()`、`SDLGPUGate.handOverWindow()` 里的 drawable 安装 | 没有可安装对象；SDL 模式下唯一的上下文就是宿主的上下文 |
| `sdl-gpu/src/test/.../SDLGPUWindowHandoverTest.java`、`sdl-gpu/src/test/java/com/dhj/vintagesdlbridge/` 测试替身 | 随契约删除；新增 LWJGLY 门控测试（见 §6） |

### 3.4 保留

| 文件 | 说明 |
|---|---|
| `src/main/.../MacDisplayForwardCompatTransformer.java` | 见 §5 |
| glsm 的 `testFixtures` 依赖 `com.cleanroommc:lwjglx:1.0.0` | 保留：它模拟老运行时（`org.lwjgl.opengl.Display` 的 `create`/`destroy`/`isCreated`），是 GL 类测试的既有约定 |
| `sdl-gpu` 的 `Lwjgl3GLCapabilitiesShim` / `AwareGLDebugShim` | 与兼容层无关（SDL 后端自建的伪 capability 层） |

### 3.5 窗口系统调用的两代分派（2026-10-15 补）

GL 渲染路径（`Lwjgl3GLRenderBackend` + `BatchingFontRenderer`）原本直接调
`org.lwjgl.glfw.GLFW`（`glfwSwapInterval`、`glfwGetVideoMode`、`glfwGetCurrentContext`、
`glfwSetDropCallback`），在新 CRL 上会 `NoClassDefFoundError`。现按 §2 规则 7 隔离：

| 文件 | 职责 | 加载时机依据 |
|---|---|---|
| `glsm/.../backend/WindowSystem.java` | 常驻门面：只按类名探测，自身不引 GLFW/SDL 类型；逐调用分派，探测不到任何一代时 WARN 一次并降级 | 常驻；门控第一道 |
| `glsm/.../backend/GlfwWindowing.java` | GLFW 版 vsync / 刷新率（`periodFromIntegerHz`）/ 当前上下文 | 仅探测命中 `org.lwjgl.glfw.GLFW` 后触达 |
| `glsm/.../backend/GlfwFileDropWatcher.java` | GLFW 版文件拖放（原有实现，未改） | 同上，仅经 `GlfwWindowing` 触达 |
| `glsm/.../backend/SdlWindowing.java` | SDL 版：`SDL_GL_SetSwapInterval`、`SDL_GetCurrentDisplayMode(SDL_GetPrimaryDisplay())`（回落 `SDL_GetDesktopDisplayMode`，`periodFromRational`）、`SDL_GL_GetCurrentContext` | 仅 GLFW 缺席且 `org.lwjgl.sdl.SDLVideo` 在位时触达 |
| `glsm/.../backend/SdlFileDropWatcher.java` | SDL 版文件拖放：`SDL_AddEventWatch` 观察 `SDL_EVENT_DROP_FILE`（只看不消费，返回 true 让事件留给宿主泵） | 同上，仅经 `SdlWindowing` 触达 |

`SdlWindowing` 的 natives 依据：LWJGLY 的 `org.lwjgl.opengl.Display` 适配层自身调
`SDLVideo.SDL_GetDesktopDisplayMode`（实测 lwjgly-1.0.2 常量池），故新 CRL 运行时必有
lwjgl-sdl 类与 SDL3 native；dev 运行时另有 root `runtimeOnly lwjgl-sdl + natives` 兜底。
**运行时验证待办**（同 §7 清单）：新 CRL 上确认 vsync/刷新率/拖放三项实际生效，尤其宿主的
SDL 事件泵确实把 DROP 事件推过事件队列（event watch 才能观察到）。

## 4. lwjgl 3 版本决定：Actinium 保持 3.4.1

**结论**：Actinium 的 `org.lwjgl:lwjgl*` 钉子保持 **3.4.1**（`gradle/scripts/dependencies.gradle`
的 `lwjglVersion`、`sdl-gpu`/`glsm`/`GTNHLib`/`celeritas-common` 各自钉的版本不变），而
**新 CRL 的运行时必须提供 3.4.2**。

依据（实测，非推断）：

1. LWJGLY 1.0.0（`repo.cleanroommc.com/releases`，sha1 `e0713f7d…`）的
   `org.lwjgl.opengl.ContextCapabilities` 引用了 14 个 capability 字段：
   `GL_APPLE_aux_depth_stencil`、`GL_APPLE_client_storage`、`GL_APPLE_element_array`、
   `GL_APPLE_fence`、`GL_APPLE_float_pixels`、`GL_APPLE_flush_buffer_range`、
   `GL_APPLE_object_purgeable`、`GL_APPLE_rgb_422`、`GL_APPLE_row_bytes`、
   `GL_APPLE_texture_range`、`GL_APPLE_vertex_array_object`、`GL_APPLE_vertex_array_range`、
   `GL_APPLE_vertex_program_evaluators`、`GL_APPLE_ycbcr_422`。
   `org.lwjgl.opengl.GLCapabilities` 在 **3.4.1 有 427 个 `GL_*` boolean 字段、3.4.2 有 441**，
   多出来的恰好是这 14 个 `GL_APPLE_*`。所以在 3.4.1 运行时上加载
   `ContextCapabilities`（GLSM 与 `GLContext.getCapabilities()` 的必经之路）会
   `NoSuchFieldError`。**LWJGLY 的运行时下限是 3.4.2。**
2. 反向检查（Actinium 的编译面）不要求 3.4.2：对 lwjgly 全量 class 常量池逐条比对
   3.4.1/3.4.2 的成员（名称+描述符），除上面那批 LWJGLY 自己合并进
   `GLCapabilities`/`GL11` 的成员外没有版本差异；Actinium 自身也没有用到任何 3.4.2 新增 API。
3. 运行时 LWJGL 版本由宿主决定：老 CRL 的 dev classpath 就是 3.4.1（`lwjgl*:3.4.1` +
   natives 3.4.1），把 Actinium 的编译钉子抬到 3.4.2 只会造成「编译 3.4.2 / 跑 3.4.1」的
   假安全，还可能在同一 classpath 上引入 3.4.2 jar + 3.4.1 natives 的混搭。
   **等宿主确认**：新 CRL 发布时把 LWJGL 3 与 natives 一并升到 3.4.2（与 LWJGLY 基线一致）；
   Actinium 的钉子随宿主同批调整。

## 5. transformer 处置

`MacDisplayForwardCompatTransformer`（`@IFMLLoadingPlugin` 注册的 `IClassTransformer`）：

- **注册链本身不引用 lwjglx 类型**：`MixinEarly.getASMTransformerClass()` 只写类名；transformer
  的两个目标是**字符串**（`"org.lwjgl.opengl.Display"`、`"org.lwjglx.opengl.Display"`），
  目标类不存在时它自然不会被调用。
- **保留，不重指、不删除**：它的作用是往 GLFW 的 `glfwDefaultWindowHints` 之后插
  `GLFW_OPENGL_FORWARD_COMPAT`（macOS 上请求 3.2+ core profile 必需）。老 CRL 上
  `org.lwjgl.opengl.Display` 是 lwjglx 的实现（实测调用 `org/lwjgl3/glfw/GLFW.glfwDefaultWindowHints`）
  → 注入有效；`org.lwjglx.opengl.Display`（lwjglxx，调用 `org/lwjgl/glfw/GLFW`）也已在目标表里。
- **新 CRL 上它是惰性的**：LWJGLY 的 `org.lwjgl.opengl.Display.create()` 里**没有任何 GLFW
  调用**（实测 class 常量池里 `glfw` 命中 0 次），注入点不存在，方法原样返回字节码。因此
  macOS forward-compat 的责任移到**宿主创建上下文**那一步（见 §7）。

## 6. null-drawable 退化核对

LWJGLY 下 `Display.getDrawable()` 恒为 null，逐点核对（glsm，`GLStateManager`）：

| 位置 | null 请求下的行为 | 处置 |
|---|---|---|
| `isDisplayDrawable(d)` | `d == Display.getDrawable()` ⇒ `null == null` ⇒ **真**：null 请求被认作显示上下文，绑定 primary context | 不改（语义正确），补 javadoc |
| `contextFor(d)` | 不会被 null 触达（上面为真时走 primary 分支） | 不改 |
| `makeCurrent(null)` | `bindThreadContext(null)` → 后端钩子（SDL 后端返回 true）→ 未处理时回落 `Display.makeCurrent()`；**不再对 null 解引用** | 改：null 时走 `Display.makeCurrent()`，且仅在 `Display.isCreated()` 时调用（没有显示时请求驱动会落到无窗口的 native 调用） |
| `releaseContext(null)` | 同上，回落 `Display.releaseContext()` | 同上 |
| `drawableGL` 惰性捕获与 `drawableGLHolder` 记账 | 原逻辑会在 null 请求下把 holder 清成 null（= 关闭主线程缓存） | 改：null 请求直接认定为持有显示上下文（`drawableGLHolder = CurrentThread`），不参与「切离 DrawableGL」判断 |

SDL 后端侧：`handleMakeCurrent(null)` / `handleReleaseContext(null)` 返回 true（安装伪能力 +
进入 SDL 上下文状态），非 null 一律返回 false 交给 GL 路径。

**测试覆盖说明**：`makeCurrent(null)` / `releaseContext(null)` 的端到端路径**无法在本仓库的
测试 JVM 里跑**——`org.lwjgl.opengl.Display`（LWJGL2 壳）需要宿主重定位后的 `org.lwjgl3.**`
类才加载得起来（实测在测试 JVM 里 `NoClassDefFoundError: org/lwjgl3/system/Struct`），
而测试 classpath 上没有它们。该分支因此只做了静态核对 + 实机验证留待用户；SDL 侧的
「null 即我们的上下文」规则由 `LwjglyWindowHostTest` 等测试覆盖。

## 7. 等宿主确认清单

1. **新 CRL 发布 LWJGLY**：把 `lwjgly`/`lwjgly-spi` 与 LWJGL 3 **3.4.2**（含 natives）一起发，
   并在类加载时安装 `LWJGLYTransformer`（`handles`/`transform`）与 `LWJGLY.setWindowBridge(...)`。
   当前 dev 运行（unimined `cleanroom` loader `0.6.12-alpha`）既没有 LWJGLY，也不装 transformer，
   因此**dev 的 `runClientSdl` 在宿主补齐前无法验证 SDL 路径**（GL 路径不受影响）。
2. **可被 GPU 设备认领的窗口**：`WindowBridge.handle()` 交给后端时，该 `SDL_Window` 不能是
   `SDL_WINDOW_OPENGL`（见 `sdl-gpu-enable-path.md` §4 第 2 条）。
3. **SDL 模式下不建 GL 上下文**（或提供无 GL 路径）：`Display.create(pf, attribs)` 在 LWJGLY
   下是 `adoptContext`，语义是校验宿主已有的 GL 上下文。
4. **macOS forward-compat**：宿主创建上下文时自行设置 `GLFW_OPENGL_FORWARD_COMPAT`（§5）。
5. **macOS 上的 `Display.getAvailableDisplayModes`** 等 LWJGLY 占位行为不在本次范围内，但会
   影响视频设置界面（见 LWJGLY 的 `build/lwjgly/PROBLEMS.md`）。
