# ArchitectureCraft 光影光照兼容（1.12-3.108）

最后更新：2026-09-29。与 LittleTiles issue #193 的光照排查一并验证；ArchitectureCraft 不是原 issue
报告的模组，相关路径由用户实测发现。

## 症状

放置在世界里的 ArchitectureCraft shape 在光影下整体偏暗，相邻的原版橡木木板可作为对照。用户使用
Photon v1.3b 与 LittleTiles 场景一起确认修复。

## 根因与修复

ArchitectureCraft 的 `RenderTargetWorld#setLight(float, int)` 把 `shadow` 乘进 RGB，同时把传入的
packed lightmap 拆进顶点 lightmap 坐标。`shadow` 同时包含基于法线的方向面明暗和 AO。启用 Iris
`separateAo` 时，这会把本应交给 shader 的因子预先烘焙进 RGB，导致颜色偏暗。

在 ArchitectureCraft 条件 Mixin 中包装 `setLight`：

- `separateAo` 开启时用中性 shadow 写 RGB，并把原始 AO 因子写入顶点 alpha；
- 当前 shader pack 关闭旧方向面着色时，从 alpha 因子中去掉 ArchitectureCraft 的方向面明暗，仅保留 AO；
- packed lightmap 原样传给原方法，继续使用 ArchitectureCraft 计算的光照坐标；
- `architecturecraft` 与目标类探测共同门控配置，未安装 ArchitectureCraft 时不加载外部类引用。

## 验证

- `compileJava`、`check`、`devShadowJar` 通过。
- 2026-09-29：用户在 Photon v1.3b 下确认相邻原版橡木木板、ArchitectureCraft shape 与 LittleTiles 方块的
  光照差异已消失。
- Solas 与其他 shader pack 尚未回归。

## 主要文件

- `src/main/java/com/dhj/actinium/compat/architecturecraft/ArchitectureCraftLightingCompat.java`
- `src/main/java/com/dhj/actinium/mixin/mod/architecturecraft/`
- `src/main/resources/mixins.actinium.architecturecraft.json`
- `src/main/resources/mixins.actinium.conditions.properties`
