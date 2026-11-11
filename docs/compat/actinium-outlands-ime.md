# Actinium、Outlands 与 SDL 输入法兼容

## 现象

在 Outlands 2026.11.11-2 上安装 Actinium 后，出现过以下现象：

- 启动时错误进入全屏，窗口画面只显示在左下角，鼠标坐标错位。
- Splash 结束后，鼠标、键盘和窗口关闭事件停止响应。
- 修复输入事件后，游戏可以正常行走和操作，但准心锁定时仍可切换 Windows 中文输入法并显示候选词。

实例使用 Windows 11、Java 25、LWJGL 3.4.3、Outlands 2026.11.11-2，Actinium 使用 `vanilla-gl` 后端，SDL GPU 选项为关闭。

## 初始全屏与输入失效

实例配置同时存在：

```text
options.txt:
fullscreen:false

config/actinium-options.json:
fullscreen_mode: EXCLUSIVE
last_fullscreen_mode: EXCLUSIVE
```

旧版 Actinium 的 `ActiniumWindowModeController.synchronize()` 只读取 Actinium 配置，忽略 vanilla `fullscreen:false`，启动时调用 Outlands 窗口全屏路径。Outlands 已经将 exclusive fullscreen 替换为 borderless fullscreen；旧控制器仍通过窗口 backend 重复申请全屏。

全屏切换后，Minecraft 的 `displayWidth` 和 `displayHeight` 仍保留创建时的 854x480，而 SDL 窗口已经改变尺寸，因此画面停在左下角，GUI 鼠标换算也使用错误尺寸。

Actinium 提交 `74ad8e93` 将 Outlands 的 exclusive 模式映射为 borderless，`3e2825d0` 记录该行为，`aea2fe5f` 让 Outlands vanilla OpenGL 启动选择 SDL window backend，并使用真实 borderless 状态判断。实例配置随后设为：

```json
"fullscreen_mode": "OFF",
"last_fullscreen_mode": "BORDERLESS"
```

## SDL 事件泵差异

Angelica 的 SDL 接入由 lwjgl3ify 所有：

```text
Lwjgl3GLRenderBackend
    -> MainThreadPump
    -> Lwjgl3ifyEventLoop.pumpEvents
    -> SDL_PumpEvents + SDL_PeepEvents
    -> lwjgl3ify Keyboard / Mouse / InputEvents
```

Angelica 的 `GLSMRedirector` 将 `Display.update()` 重定向到 `GLStateManager.updateDisplay()`，backend 的 `pumpDisplayMessages()` 仍调用 lwjgl3ify 的完整事件循环。Angelica 没有 `WindowBackends`、`SdlWindowBackend` 或第二套 SDL 事件分派。

Actinium 将窗口创建交给 Outlands：

```text
GLSMRedirector
    -> GLStateManager.updateDisplay
    -> RenderBackend.pumpDisplayMessages
    -> WindowBackends
    -> SdlWindowBackend
    -> Outlands Display.processMessages
```

早期 Actinium 的 `SdlWindowBackend.pumpMessages()` 只有 `SDL_PumpEvents()`，没有消费 SDL 队列，也没有调用 Outlands `Display.handleSdlEvent()`。因此键盘、鼠标和关闭事件没有写入 LWJGL2 队列。Splash 阶段偶尔能更新一次悬停状态，进入主循环后输入停止。

提交 `5b0013c3` 将该方法改为调用 `Display.processMessages()`，恢复 Outlands 的完整事件分派。提交 `1e8fb885` 移除了额外的 `runTick` SDL 泵，使事件处理保持在 render/backend 路径中。

## IME 生命周期

Outlands 的 IME 状态由 `TextFieldHandler` 管理：

- `screenWantsTextInput`、`focusedTextInput` 或 `forcedTextInput` 任一为真时调用 `SDL_StartTextInput`。
- `resetTextInput()` 清除这些状态、清理 composition，并调用 `SDL_StopTextInput`。
- `Display.handleKeyEvent()` 处理 `ImeForceKey`。
- `Display.handleTextEvent()` 处理 `SDL_TEXT_INPUT`。
- `Display.handleTextEditingEvent()` 处理 `SDL_TEXT_EDITING`。

lwjgl3ify 的实现使用文本输入深度计数，只在文本 GUI 生命周期内启停 text input，没有 Outlands 的 `ImeForceKey` 和持久 `forcedTextInput` 状态。

Outlands 提交 `ee43c068` 在 `Mouse.setGrabbed(true)` 中调用 `TextFieldHandler.resetTextInput()`，将准心锁定作为 GUI 到世界的统一 IME 边界。该修复对应 vanilla 1.12.2 的调用顺序：`Minecraft.setIngameFocus()` 先调用 `Mouse.setGrabbed(true)`，再关闭 GUI。

诊断日志确认进入世界时 session 已关闭：

```text
mouse grab requested; resetting text input
reset requested forced=false screen=false field=false composing=false
session changed wanted=false activeBefore=true activeAfter=false
force key matched screen=none mouseGrabbed=true
force ignored: no current screen
```

后续诊断没有出现 `SDL text input` 或 `SDL text editing` 事件。候选词仍出现时，Windows IME 已经在 SDL text input session 关闭后通过全局快捷键切换，行为没有经过游戏的 SDL 文本输入队列。该部分需要 Windows IME context 级别的处理，SDL `StopTextInput` 本身无法阻止系统全局输入法快捷键。

## Windows nested library 问题

清理 `mods` 后曾出现：

```text
java.net.URISyntaxException:
Illegal character in opaque part at index 12:
fmlnested:E:\...\Actinium-alpha-0.0.14-1e8fb885.jar!/joml-1.10.5.jar!/
```

Outlands `NestedLibraryJars.urlFor()` 直接将 Windows 反斜杠路径放进 `fmlnested:` URL，`ModClassLoader.getParentSources()` 调用 `URL.toURI()` 时失败。提交 `5ee74931` 改用文件 URI 的 raw path，并在 Windows 盘符路径上移除多余的前导斜杠。`NestedLibraryJarsTest` 的 8 项测试覆盖 nested URL、classloader 读取和 Windows URI 转换。

## 验证与实例产物

Actinium 当前部署 Jar：

```text
Actinium-alpha-0.0.14-1e8fb885.jar
SHA256: 31FAED4142E5D150F10A03AAD0BB52BE79B5F2CD467ED5CF0A49FBDE3BDD7114
```

Outlands 当前部署 Jar：

```text
outlands-2026.11.11-2.jar
SHA256: DD02965BD87EB8066302E079E92965C1A5CD2B7BD0403E75C0C0E9DFCEE5F76D
```

Outlands 的临时诊断提交 `462e1043`、`2f8957dd` 已从本地分支历史删除。功能提交 `ee43c068`、`5ee74931` 保留。Actinium 的 `[IME-TRACE]` 代码只存在于临时构建并未进入当前工作树。

Actinium 根项目的完整 `check`、`build`、分发 Jar 验证和 SDL shader 依赖隔离测试通过。Outlands `:outlands:check`、nested library 回归测试和完整 build 通过。完整客户端回归由实例运行完成。
