# Celeritas Dynamic Lights 兼容（celeritasdynamiclights）

兼容状态：**代码完成，实机验证待做**。
最后更新：2026-11-17。分支：`feat/ffp-instancing`。

## 背景

Celeritas Dynamic Lights（modid `celeritasdynamiclights`，Karnatour/CeleritasDynamicLights）为
实体、方块实体与手持物品提供动态光等级，并把该等级合并进与位置相关的光照。公开入口是
`toni.sodiumdynamiclights.SodiumDynamicLights.get()`，实例方法
`getLightmapWithDynamicLight(BlockPos pos, int lightmap)` 返回合并后的 packed light：动态等级
高于 packed 中的 block light 时替换 block light，sky light 保持不变，lightmap 低 4 位的子级
归零；等级为 0 时原样返回入参。

附属自身接入了原版 `TileEntityRendererDispatcher`、`Particle.getBrightnessForRender`、手持
`ItemRenderer` 与 Actinium 设置页（`toni.sodiumdynamiclights.config.ActiniumOptionsListener`、
`DynamicLightsActiniumPage`），这些路径 Actinium 不改动。

## 桥位置与合并点

`TileEntityLight.packedLight`
（`src/main/java/com/gtnewhorizons/angelica/rendering/tesr/TileEntityLight.java`）在光影包启用时
用世界采样结果替换 `TileEntityRendererDispatcher` 的 combined light，
值为 `sky << 20 | max(worldBlockLight, state.getLightValue()) << 4`。这个分支整段舍弃调用方
传入的值，重算结果里没有附属已经并入的动态光，因此在返回前再合并一次；
`!getUseNeighborBrightness(...)` 与 `getLightOpacity(...) != 0` 的早期返回保持原样，直接返回
调用方的值。

桥由三个类组成，位于 `src/main/java/com/dhj/actinium/compat/celeritasdynamiclights/`：

| 类 | 职责 |
| --- | --- |
| `DynamicLightProvider` | 接口。`mergeDynamicLight(BlockPos, int)` 把位置动态光合并进 packed light |
| `ReflectiveDynamicLightProvider` | 唯一实现。反射调用附属的 `get()` 与 `getLightmapWithDynamicLight(BlockPos, int)` |
| `CeleritasDynamicLightsCompat` | 静态入口。首次使用时解析一次，以接口类型持有实现 |

使用反射的原因：`toni.sodiumdynamiclights.SodiumDynamicLights` 只在安装附属时存在，构建中没有
该依赖，任何编译产物都无法直接引用它；附属没有提供可编译的服务、Forge 事件或接口，调用点又
处在每帧执行的光照查询里，没有其它可用的普通入口。因此两个成员在
`CeleritasDynamicLightsCompat` 类初始化时用 `Class.forName(name, false, loader)` 解析一次并缓存
`Method`，反射只出现在 `ReflectiveDynamicLightProvider` 内，`TileEntityLight` 只调用
`CeleritasDynamicLightsCompat.mergeDynamicLight`。

## 未安装与缺 API 的行为

未安装附属时 `Class.forName` 抛 `ClassNotFoundException`：调用带 `initialize=false`，附属的类
不会被定义或初始化，桥保持禁用，`mergeDynamicLight` 原样返回入参，DEBUG 记录一行说明。光影下
方块实体的亮度与没有附属时一致。

安装了缺少 `getLightmapWithDynamicLight(BlockPos, int)` 的旧版本时抛 `NoSuchMethodException`：
ERROR 记录一次日志，正文包含缺失的方法签名与本次启动内关闭合并的结论，桥保持禁用。

运行期调用失败（`get()` 返回 null、反射调用抛出异常、返回值不是 int）时 ERROR 记录一次，然后
清空两个 `Method`；后续调用直接返回入参，不再进入反射路径，不会每帧重复报错。日志标签为
`ActiniumDynamicLightsCompat`。

## 重复应用

附属在 `TileEntityRendererDispatcher.render` 的 `World.getCombinedLight` 调用点用
`@ModifyArgs` 把动态等级写进第二个参数（`lightValue`），原版实现取它与方块光照的较大值后拼出
packed light；桥在 `TileEntityLight` 里对重算后的 packed light 再次合并。两处的合并规则一致：
block light 取动态等级与原值的较大值，sky light 不变。因此重复应用幂等：输入已经含动态光时
再次合并得到同一结果，输入不含时补上动态光，等级为 0 时附属原样返回入参。
`mergingAnAlreadyMergedLightChangesNothing` 覆盖该情形。

## 自动化验证

`CeleritasDynamicLightsCompatTest` 走生产桥（真实反射解析与真实方法调用），
`toni.sodiumdynamiclights.SodiumDynamicLights` 替身只存在于 `src/test`，按附属的实现复刻合并
规则。覆盖四项行为：动态等级高于 block light 时替换、低于时保持、等级为 0 时原样返回（含
lightmap 低 4 位）、已合并的值再次合并不变。`:test`、`compileJava`、`check` 通过；测试自身不
使用反射，桥也不为测试提供额外的 setter 或重置入口。

## 实机待验证场景

- 未安装附属：光影包下告示牌等使用 neighbor brightness 的方块实体亮度与改动前一致，日志无
  `ActiniumDynamicLightsCompat` 的 ERROR。
- 安装附属（记录版本）：光影包下手持火把或荧光物品靠近告示牌时告示牌随动态光变亮，走远后恢复；
  关闭附属动态光（设置页 Dynamic Lights = OFF）时画面不变。
- 注入共存：附属的 `ModifyArgs` 与 Actinium 的 `ModifyExpressionValue` 落在
  `TileEntityRendererDispatcher.render` 的同一个 `World.getCombinedLight` 调用点，需确认两者同时
  生效且启动日志没有注入失败。
- 已知缺口待确认：不走 neighbor brightness 的方块实体（箱子等），以及未启用光影包时的全部分支，
  `MixinTileEntityRendererDispatcherTesrLight` 直接返回 `TesrLightCache` 的缓存值，该值来自
  `World.getCombinedLight`，不含附属的动态光。本次未改动该路径，需实机确认动态光在这些场景是否
  需要补齐。
