# Tinkers' Antique 字体颜色兼容性说明

兼容状态：**代码支持（实机待验）**（字体批处理器按模组存在性整体让位）
最后更新：2026-11-21

## 验证范围

- 版本：Tinkers' Antique 1.12.2-2.13.0.209，使用 Tinkers' Construct 的共享 mod id `tconstruct`
- 环境：Cleanroom Loader 0.6.13-alpha，Actinium alpha-0.0.13
- 相关功能：匠魂工具提示中的挖掘等级、词条与材料名颜色

## 症状：工具提示文本颜色丢失

Tinkers' Antique 的匠魂工具提示中，部件挖掘等级、词条和材料名颜色回落为默认深灰色。
启动参数 `-Dactinium.disableFontBatcher=true` 可恢复这些颜色。

## 原因与处理

Tinkers' Antique 的 `CustomFontRenderer` 将私有区字符用作 RGB 标记，并在重写的逐字形绘制
方法中截获标记、设置颜色，再返回零宽度。`MixinFontRenderer` 会在 `drawString`、
`renderString` 与 `getCharWidth` 入口转用 `BatchingFontRenderer`，批处理器把这些标记当作字形，
没有执行 `CustomFontRenderer` 的 RGB 解码逻辑。Neo Font Renderer 已有整体让位模式；
Tinkers' Antique 安装后，`FontBatcherCompat` 现让以上三个入口回退 `FontRenderer` 实现。

Tinkers' Antique 与 Tinkers' Construct 使用相同的 `tconstruct` mod id。因此，任何使用该 id
的发行版都会启用这一全局兼容模式，并关闭所有 `FontRenderer` 实例的字体批处理。模组检测集中在
GTNHLib 的 `Mods.TINKERSCONSTRUCT`，字体兼容策略不加载该模组的类。

构建与自动化检查通过。用户已确认临时 JVM 参数能恢复颜色；代码修复的实机验证待做。
