# Reese's Sodium Options 移植方案（1.12.2）

最后更新：2026-08-14。

## 背景与目标

用户要求将 **Reese's Sodium Options（RSO，MIT 许可）** 的整套设置界面移植到 1.12.2，
替换基于 `net.caffeinemc` 的旧版 Sodium 风格设置界面，且**不改变 RSO 给人的视觉效果**
（布局、颜色、间距、交互保留原样）。

已确认的需求决策：

- **移植范围**：完整移植（tab 分组/折叠、搜索、主题、tooltip、action 按钮、键盘导航等全部功能）。
- **捐赠弹窗与支持按钮**：全部移除（Sodium 捐赠弹窗、ko-fi 按钮及其配置项）。
- **删除旧界面系统**：整个 `net.caffeinemc.mods.sodium.{api,client}.config` 数据模型与
  `net.caffeinemc.mods.sodium.client.gui` 设置界面（PolyForm Shield 1.0.0 许可）**全部删除**，
  不再以任何形式保留或分发。
- **数据层**：RSO 依赖 **Embeddium 框架**（celeritas-common `dhj.embeddedt.embeddium.api.options.*`）
  作为选项数据模型，并以自研（MIT）扩展补齐 RSO 需要的 pending/applied 语义与外部页能力。
- **入口方式**：仿上游用 mixin 拦截入口——`MixinGuiOptions` 拦截视频设置按钮（id 101），
  RSO `enabled` 开启时打开 RSO 屏幕，关闭时走原版 `GuiVideoSettings`。
- **Controlify 控制器支持**：1.12.2 无 Controlify，不移植（`controlify/` 包不引入）。

## 数据层（替代已删除的 net.caffeinemc）

- **embeddium 选项模型**：页面/组/选项改为 embeddium `OptionPage` / `OptionGroup` / `Option<T>`
  及其 Builder；内置 Actinium 页面（`ActiniumGameOptionPages` / `CommonOptionPages`）与
  RSO 自身配置页（`ReeseSodiumOptionsConfigEntryPoint`）均以 embeddium `OptionPage` 形式提供。
- **自研扩展**（celeritas-common `dhj.embeddedt.embeddium.api.options.*`，MIT）：
  - `Option` 接口默认方法 `getAppliedValue/getDefaultValue/resetToDefault/shouldHideControl`，
    在 `OptionImpl` 以 `modifiedValue` 与 `defaultValue` 实现，还原 RSO 需要的
    pending/applied 语义（`UndoAction`/`ResetAction` 依赖）。
  - `OptionGroup.getName()/setName()`：组头标题（RSO 折叠组用）。
  - `ExternalPage`：导航型页面（tab 激活时转发到独立 `GuiScreen`，如 Iris 光影包选择）。
  - `ExternalButtonControl`：选项行上的"打开外部页面"按钮控件。
- **RSO 侧包装**（自研，MIT）：
  - `RsoOption`：包装 embeddium `Option<?>`，按控件类型分派
    （isTickBox/isSlider/isCycling/isExternalButton），暴露 pending/applied 值语义与
    slider/cycling 参数访问，行类只依赖它。
  - `RsoModOptions`：把 embeddium `OptionPage` 列表按 modId 聚合成 RSO tab 视图
    （configId/name/version/icon/theme/pages）。
  - `ActiniumOptionHost`：进程级宿主，收集内置页面 + RSO 自身页 + Iris 页 +
    `OptionGUIConstructionEvent` 扩展，按 modId 分组为 `List<RsoModOptions>`，
    并协调 `applyChanges/undoChanges/hasPendingChanges/resetToDefaults` 与 flag 副作用。
  - `ActiniumOptionPages.builtInPages(SodiumGameOptions)`：内置页面（General/Quality/
    Performance/Advanced，仅当配置 `enable_debug_tab` 为 true 时追加 Debug）+
    `IrisConfigEntryPoint.createPages()`（shadow distance 滑块 + 光影包外部页）。

RSO 行渲染不再接触任何 Sodium 配置类；`OptionSearch` 直接遍历
`OptionGroup.getOptions()` 的 embeddium `Option<?>` 并以其 `OptionIdentifier` 建索引。

## 界面层移植

- RSO 移植代码保留原包名 `me.flashyreese.mods.reeses_sodium_options.*`，便于对照上游。
- 新增 **1.12.2 适配层**：`com.dhj.actinium.gui.rso.compat`，提供 RSO 依赖但 1.12.2
  不存在的新版 MC GUI API 等价物（事件模型、渲染上下文、文本组件包装）。
- 资源：`assets/reeses-sodium-options/`（lang 转 1.12.2 `.lang` 格式、纹理原样复制）。
- 配置：`reeses_sodium_options.json`（RSO 自身配置），经
  `ReeseSodiumOptionsConfigEntryPoint` 以 embeddium `OptionPage` 注册进 `ActiniumOptionHost`。

### 适配层设计（com.dhj.actinium.gui.rso.compat）

| 新版 API | 适配层提供 | 说明 |
| --- | --- | --- |
| `net.minecraft.client.input.KeyEvent` | `KeyEvent` record | key/scancode/modifiers + isSelection/isCopy/isPaste/isCut/hasShiftDown/hasControlDown/isSelectAll/isLeft/isRight |
| `net.minecraft.client.input.MouseButtonEvent` | `MouseButtonEvent` record | button/x/y |
| `net.minecraft.client.input.CharacterEvent` | `CharacterEvent` record | codepointAsString/isAllowedChatCharacter |
| `GuiEventListener` / `ContainerEventHandler` | 同名接口 | 事件分发树，语义同新版 |
| `ComponentPath` | 同名类 | 焦点路径（leaf/path） |
| `FocusNavigationEvent` / `ScreenDirection` / `ScreenRectangle` | 同名类 | 键盘焦点导航 |
| `GuiGraphicsExtractor` | 同名类 | fill/text/centeredText/blit/enableScissor/disableScissor/requestCursor，内部用 1.12.2 原语 |
| narration 系列 | 空实现接口 | 1.12.2 无屏幕朗读，保留签名不产生行为 |
| `net.minecraft.network.chat.Component` | `Component` 包装 | 包装 ITextComponent；translatable/literal/empty/copy/withStyle/append/getString |
| `Style` / `CommonComponents` / `FormattedText` / `MutableComponent` | 包装 | 同上 |
| `net.minecraft.resources.Identifier` | 直接用 `ResourceLocation` | 机械替换 import |
| `Util.getMillis` / `openUri` | `System.currentTimeMillis` / `Desktop.browse` | 内联替换 |
| `Mth` | `MathHelper` | 机械替换 |
| `Font` | `FontRenderer` | 机械替换 |
| `SimpleSoundInstance` / `SoundEvents` | 1.12.2 `SoundEvents` / `PositionedSoundRecord` | 机械替换 |
| `ChatFormatting` | `TextFormatting` | 机械替换 |
| `Language` / `StringUtil` / `FormattedCharSequence` / `RenderPipelines` / `CursorTypes` | 最小实现或空 | tooltip 分行、文本过滤、光标请求（无操作） |

## 移植文件清单

### 近乎原样（仅改 import / 文本组件 / 机械替换）
`client/search/*`（6）、`client/gui/state/*`（8）、`client/gui/layout/LayoutBounds`、
`client/gui/theme/GuiTheme`、`client/gui/frame/ScrollFrameLayout`、
`client/gui/frame/tab/TabGroup`、`client/gui/frame/option/PageLayout`、
`client/gui/option/RsoOption`、`client/gui/option/RsoModOptions`。

### 需要适配事件/渲染
`client/gui/frame/*`（AbstractFrame/BasicFrame/ScrollableFrame）、
`client/gui/frame/tab/*`（Tab/TabFrame/TabRail/TabGroupModel/TabSelectionState/TabButtonWidget）、
`client/gui/frame/option/*`（AbstractOptionRow 及 6 个行类、OptionRowFactory、PageFrame、
OptionTooltipController、action 5 个）、`client/gui/widget/*`（6）、
`client/gui/search/*`（2）、`client/gui/SodiumVideoOptionsScreen`（改继承 GuiScreen，
事件从 1.12.2 生命周期转为 compat 事件对象）、`client/gui/theme/IconRenderer`、
`client/gui/control/*`（2，仅数据类）。

### 配置与接线
`client/config/ReeseSodiumOptionsConfig`（JSON 读写，路径改 1.12.2 配置目录）、
`ReeseSodiumOptionsConfigEntryPoint`（embeddium OptionPage 化，移除支持组）、
`PreviousScreenHolder`、入口 mixin `MixinGuiOptions`（拦截视频设置按钮 id 101 切屏，
RSO 关闭时放行原版 `GuiVideoSettings`）、
`com.dhj.actinium.compat.sodium`（ActiniumOptionHost / ActiniumOptionPages /
ActiniumApplyActions / OptionGUIConstructionBridge）、
`net.irisshaders.iris.compat.sodium.IrisConfigEntryPoint`（embeddium 化并接入内置页面）。

## 滑块精调

RSO 整数滑块拖动期间按住 Shift，会将临时范围缩至按下时当前值的 ±2，并允许以 1 为步长调整；松开 Shift 后恢复选项声明的上下限和步长。范围在接近选项边界时限制在选项原有范围内。

## 视觉保真保障

- 所有颜色常量、尺寸常量、布局计算（`LayoutBounds`/`ScrollFrameLayout`/`TabFrame` 常量、
  toolbar 布局、行高、tooltip 尺寸）**原样保留**。
- 绘制调用统一经 `GuiGraphicsExtractor` 适配层映射到 1.12.2 原语
  （`Gui.drawRect`/`FontRenderer.drawString`/scissor），不做任何视觉调整。
- 搜索/主题/折叠/焦点边框等行为逻辑逐行保留，仅替换 API 面。

## 验证

- `./gradlew compileJava --no-daemon`、`./gradlew build --no-daemon`（`check` 校验
  remap Jar 结构，产物中已无 `net.caffeinemc` 类）。
- 搜索逻辑（SearchIndex/SearchSession/NgramGenerator）与行分派（RsoOption）、
  option host 收集均有 JUnit 覆盖（349 项）。
- 滑块精调已由用户于 2026-10-17 在客户端完成实机验证。
- 视觉对比需运行客户端人工确认（记录到 compatibility-matrix）。
