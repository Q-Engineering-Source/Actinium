# ScalingGUIs 兼容性说明

兼容状态：**待实机验证**（编译与 `check` 通过；用户实例打开配置页时遇到 ICU `StackOverflowError`）
最后更新：2026-10-02

## 模组信息

- ScalingGUIs 1.12.2-1.0.3.1，modid `scalingguis`
- mod 存在性由 GTNHLib 的 `Mods.SCALINGGUIS` 统一检测。编译期类由仓库已有的
  `compileOnly 'curse.maven:scalingguis-319656:5150678'` 提供。

## 兼容行为

ScalingGUIs 会将原版视频设置的 GUI Scale 控件替换为自己的 "GUI Scales" 按钮；按钮使用
`scalingguis.videosettings.button` 文本键，点击打开
`GuiConfigSG(currentScreen, GuiConfigSG.MAIN_ID)`。

Actinium 的 RSO 视频设置页在检测到 ScalingGUIs 时，将 GUI Scale 滑块替换为无背景的链接按钮，
显示相同文本并打开 ScalingGUIs 配置界面，不再直接绑定 `GameSettings.guiScale`。未检测到
ScalingGUIs 时保留原滑块。

## 实现位置

- `GTNHLib/src/main/java/com/gtnewhorizon/gtnhlib/compat/Mods.java`：缓存 `SCALINGGUIS` 标志。
- `src/main/java/com/dhj/actinium/compat/scalingguis/ScalingGuiCompat.java`：打开其配置界面。
- `ActiniumGameOptionPages.general()`：根据 `Mods.SCALINGGUIS` 选择滑块或外部按钮。
- `ExternalButtonControl`、`RsoOption` 与 `ExternalButtonOptionRow`：支持使用 mod 自己的按钮文本。

## 待验证

- [ ] 排除用户实例同时注入的 ICU 51.2 与 78.3 冲突后，验证按钮文字、配置界面打开/关闭及 GUI scale 调整。
- [ ] ScalingGUIs 未加载时原 GUI Scale 滑块正常。
