# FCL 启动器兼容性说明

兼容状态：**已验证**（Android 主菜单点击恢复，用户实机确认）
最后更新：2026-10-25。分支：`fix/fcl-mouse-input`。

FCL（Fold Craft Launcher，包名 `com.tungsten.fcl`）是独立的 Android Minecraft 启动器项目。
本文记录它在原生全屏窗口下与 Actinium 窗口模式同步产生的鼠标点击失效问题。

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | OnePlus PLQ110（SM8750，arm64，Android SDK 36） |
| 启动器 | FCL 1.3.3.8（`FCL Version Code: 1338`） |
| 加载器 | Cleanroom 0.6.13-alpha |
| LWJGL 兼容层 | LWJGLXX 1.1.22 |
| 渲染器 | MobileGlues 2.0.0（`POJAV_RENDERER=opengles3`，`POJAVEXEC_EGL=libmobileglues.so`） |
| JRE | FCL 内置 jre25 |
| 游戏版本 | Minecraft 1.12.2（实例 `1.12.2-Cleanroom`） |

## 症状

主菜单的 `Singleplayer`、`Options` 等按钮完全无响应，鼠标指针位置与按钮位置吻合，
按键事件也确实到达客户端。退出到其他界面同样点不中。

## 根因

点击失效来自鼠标坐标换算被污染，与按钮本身、输入事件分发都无关。

### 版本分界

FCL 1.3.3.7 上点击正常，升级到 1.3.3.8 后失效。差异是 FCL 侧
[#1915](https://github.com/FCL-Team/FoldCraftLauncher/pull/1915) 补齐了精简 GLFW 中缺失的
`glfwGetWindowPos(IntBuffer)` 重载。

LWJGLXX 1.1.21 与 1.1.22 的 `org.lwjglx.opengl.Display.isBorderless()` 字节码逐字相同
（`javap -p -c` 对比无差异），都先判断 `glfwGetWindowMonitor(window) == 0`，再用
`glfwGetWindowPos(long, IntBuffer, IntBuffer)` 与 `glfwGetWindowSize(long, IntBuffer, IntBuffer)`
把当前窗口几何与目标全屏模式的 `PositionedGLFWVidMode` 做比对。该重载在原 FCL 的 GLFW
里不存在：

- 1.3.3.7：`isBorderless()` 在解析 `glfwGetWindowPos` 重载时抛错，Actinium 静态块的
  `try/catch (Throwable)` 捕获后 `borderlessSupported = false`，`borderlessActive` 保持
  `false`，后续不会走 borderless 分支。
- 1.3.3.8：重载补齐后 `isBorderless()` 正常返回。Android 原生全屏窗口没有 GLFW monitor，
  窗口几何等于屏幕几何，函数返回 `true`。Actinium 静态块把该值采纳为初值
  `borderlessActive = true`。

### 故障链

1. Actinium 启动时 `ActiniumWindowModeController` 静态块执行 `borderlessActive = Display.isBorderless()`，在 1.3.3.8 上得到 `true`。
2. 首帧 `Minecraft.runTick` → `synchronize` 的配置模式为 `OFF`，`isWindowStateCompatible(OFF)`
   要求 `!borderlessActive`，不成立，于是进入 `applyWindowed`。
3. `applyWindowed` 见 `borderlessActive == true`，调用 `Display.setBorderless(false)`。
4. LWJGLXX 1.1.22 的 `setBorderless(false)` 走“退出 borderless”分支，依次执行
   `glfwSetWindowSizeLimits(window, 0, 0, -1, -1)`、`glfwSetWindowSize(window, savedW[0], savedH[0])`、
   `glfwSetWindowMonitor(window, 0, savedX[0], savedY[0], savedW[0], savedH[0], 0)`。
   `savedX/savedY/savedW/savedH` 只在 `setBorderless(true)`（或等价的全屏进入路径）里写入，
   Actinium 此前从未进入过全屏，四个数组元素全是 `0`。
5. GLFW 把窗口尺寸写成 0x0。设备日志保留了这一行：
   `[org.lwjgl.glfw.GLFW:glfwSetWindowSize]: GLFW: Set size for window ..., width=0, height=0`。
6. `Display.getPixelScaleFactor()` 用 `glfwGetWindowSize` 与 `glfwGetFramebufferSize` 相除后取
   `Math.max`，窗口宽高为 0 时得到 `0/0 = NaN`。
7. `Mouse.addMoveEvent(double, double)` 把原始光标坐标乘上 `getPixelScaleFactor()`；乘 `NaN`
   后 `(int)` 转换为 0，`latestX` 恒为 0，`latestY` 由 `getHeight()` 减去 0 得到，`getEventY()` 因而固定在窗口底边。
   `getHeight()` 读的是缓存字段 `displayFramebufferHeight`，该值仍是启动时的 1081，
   因此所有点击落在 `(0,1081)`，落到所有按钮热区之外。

`Display.getWidth()` / `getHeight()` 读的是缓存字段而非实时窗口尺寸，所以诊断日志里
`display=2380x1081` 看起来正常，`Mouse.next` 的坐标却已经是 `x=0 y=1081`。这条缓存让
故障表现为“窗口尺寸正常但点击失效”，而不是黑屏或窗口消失。

`Display.setFullscreen(false)` 读同一组 `savedW/H/X/Y`，是第二条会写入 0 几何的路径；
首次同步时 `Display.isFullscreen()`（`glfwGetWindowMonitor(window) != 0`）在无 monitor 的
Android 原生全屏窗口上返回 `false`，该分支本身不命中，作为同类风险一并加条件保护。

## 修复策略

只改 `ActiniumWindowModeController`，不改 LWJGLXX、FCL、鼠标事件与坐标换算。

新增两个字段：

```java
private static final boolean androidRuntime = System.getenv("POJAV_RENDERER") != null;
private static boolean windowedGeometryCaptured;
```

`androidRuntime` 是 Android 分支的判定边界，来自 `POJAV_RENDERER` 环境变量是否被启动器设置。
`windowedGeometryCaptured` 初始为 `false`，只在控制器自己成功进入全屏或 borderless 之后
置为 `true`，表示 LWJGLXX 已经保存过一组可恢复的真实几何。

三个 apply 方法的处理：

- `applyWindowed`、`applyExclusiveFullscreen`：`Display.setBorderless(false)` 只在
  `!androidRuntime || windowedGeometryCaptured` 时调用；`borderlessActive` 仍按原逻辑置 `false`。
- `applyWindowed`、`applyBorderlessFullscreen`：`Display.setFullscreen(false)` 同样加
  `Display.isFullscreen() && (!androidRuntime || windowedGeometryCaptured)` 条件。
- `applyExclusiveFullscreen`、`applyBorderlessFullscreen`：在 `Display.setFullscreen(true)` /
  `Display.setBorderless(true)` 之后把 `windowedGeometryCaptured` 置 `true`。

桌面环境 `androidRuntime` 恒为 `false`，所有条件恒为真，四条 `Display` 恢复调用与修改前
逐条一致，桌面窗口模式行为不变。

Android 首次同步因此不再写入 0 几何，窗口保持启动时的真实尺寸，`getPixelScaleFactor()`
回到有限值（当前分辨率下为 1.0），`Mouse.addMoveEvent` 的换算恢复正常。后续用户自己切换到
全屏或 borderless 时 `windowedGeometryCaptured` 已经为 `true`，退出全屏仍会按 LWJGLXX 的
保存值恢复。

## 验证记录

原始环境（诊断版 `Actinium-alpha-0.0.13-input-debug-2`，含 `[FCL-INPUT]` 日志）：

- `[FCL-INPUT] addMoveEvent before raw=0.0x0.0 scale=NaN current=0x0 display=2380x1081`
- `[FCL-INPUT] addMoveEvent after raw=NaNxNaN scale=NaN current=0x0 display=2380x1081`
- `[FCL-INPUT] borderless-exit=before position=0x0 display=2380x1081 ... borderless=true fullscreen=false`
- `[FCL-INPUT] borderless-exit=after position=0x0 display=2380x1081 ... borderless=false fullscreen=false`
- 随后 `GLFW: Set size for window ..., width=0, height=0`
- `[FCL-INPUT] Mouse.next button=0 state=true x=0 y=1081`，`gui=net.minecraft.client.gui.GuiMainMenu ... event=0x1081`，所有按键事件坐标恒为 `(0,1081)`
- 全程没有出现 `main-menu action=<id>`，说明 `GuiMainMenu.actionPerformed` 从未被触发

修复后（诊断版 `Actinium-alpha-0.0.13-input-fix-verify`，同一设备同一实例）：

- 用户在 FCL 1.3.3.8 上确认主菜单点击恢复正常。
- 根因修复本身不产生日志，上述确认来自用户实机反馈，未采集修复版 `[FCL-INPUT]` 逐行日志。

## 适用边界

`androidRuntime` 的判定依赖当前运行环境设置 `POJAV_RENDERER`。该名称只是后端环境变量，
不用于描述 FCL 的项目归属。本次实测的 FCL 环境会设置该变量；未设置该变量的其他 Android
环境不在本修复覆盖范围：`androidRuntime` 会为 `false`，行为回到修改前，即在原生全屏
窗口上执行恢复调用，故障链仍然成立。此类环境需要按同样机制补判定。

桌面环境若意外设置同名变量会启用 Android 分支，跳过首次全屏恢复调用；实测 Windows +
Cleanroom 环境不满足该条件。

FCL 没有真正的窗口化模式，退出全屏后得到的是铺满屏幕、尺寸非 0 的窗口。`savedW/H` 在
首次进入全屏时保存的就是当时的全屏尺寸，这是 LWJGLXX `Display` 语义下可得的结果，
`scale` 保持有限，不再出现 NaN 与点击错位。

## 文件清单

- `src/main/java/com/dhj/actinium/gui/ActiniumWindowModeController.java`（新增 `androidRuntime`、
  `windowedGeometryCaptured` 与三处 apply 方法的条件）
