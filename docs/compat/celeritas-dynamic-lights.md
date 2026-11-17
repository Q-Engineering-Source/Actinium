# Celeritas Dynamic Lights 兼容（celeritasdynamiclights）

兼容状态：**代码完成，实机验证待做**。
最后更新：2026-11-17。分支：`feat/ffp-instancing`。适配的附属 API 版本：1.2.10 起（上游仓库当前
1.2.11，`getLightmapWithDynamicLight(BlockPos, int)` 签名相同）。

## 背景

Celeritas Dynamic Lights（modid `celeritasdynamiclights`，Karnatour/CeleritasDynamicLights）为
实体、方块实体与手持物品提供动态光等级，并把该等级合并进与位置相关的光照。公开入口是
`toni.sodiumdynamiclights.SodiumDynamicLights.get()`，实例方法
`getLightmapWithDynamicLight(BlockPos pos, int lightmap)` 返回合并后的 packed light：动态等级
高于 packed 中的 block light 时替换 block light，sky light 保持不变，lightmap 低 4 位的子级
归零；等级为 0 时原样返回入参。

附属自身接入了原版 `TileEntityRendererDispatcher`、`Particle.getBrightnessForRender` 与手持
`ItemRenderer`。设置页分两条路径：`toni.sodiumdynamiclights.config.CeleritasOptionsListener` 在
Celeritas 系（`org.taumc.celeritas.api.OptionGUIConstructionEvent`）下注册
`DynamicLightsCeleritasPage`；`ActiniumOptionsListener` 在 Actinium
（`dhj.embeddedt.embeddium.api.OptionGUIConstructionEvent`）下注册 `DynamicLightsActiniumPage`。
在 Actinium 环境中生效的是后者。这些路径 Actinium 不改动。

## 桥位置与合并点

`TileEntityLight.packedLight`
（`src/main/java/com/gtnewhorizons/angelica/rendering/tesr/TileEntityLight.java`）与
`MixinTileEntityRendererDispatcherTesrLight` 会整段替换 dispatcher 调用返回的 combined light，
替换值因此丢失了附属已经并入的动态光。两条返回路径都经
`CeleritasDynamicLightsCompat.mergeDynamicLight` 合并：

| 路径 | 返回值 |
| --- | --- |
| 未启用光影包 | `TesrLightCache` 的缓存值，在 Mixin 中合并 |
| 启用光影包、方块不使用 neighbor brightness 或 `getLightOpacity != 0` | 调用方传入的值，在 `TileEntityLight` 的早期返回里合并 |
| 启用光影包、neighbor brightness 分支 | `sky << 20 \| max(worldBlockLight, state.getLightValue()) << 4`，重算后合并 |

桥由三个类组成，位于 `src/main/java/com/dhj/actinium/compat/celeritasdynamiclights/`：

| 类 | 职责 |
| --- | --- |
| `DynamicLightProvider` | 接口。`mergeDynamicLight(BlockPos, int)` 合并位置动态光；常量 `NONE` 是禁用实现，原样返回入参 |
| `DynamicLightProviderImpl` | 唯一实现。反射调用附属的 `get()` 与 `getLightmapWithDynamicLight(BlockPos, int)` |
| `CeleritasDynamicLightsCompat` | 静态入口。首次使用时解析一次，以接口类型持有实现，并负责唯一的日志标签 |

使用反射的原因：`toni.sodiumdynamiclights.SodiumDynamicLights` 只在安装附属时存在，构建中没有
该依赖，任何编译产物都无法直接引用它；附属没有提供 ServiceLoader 声明或可编译的查询接口，
调用点又处在每帧执行的光照查询里，没有其它可用的普通入口。因此两个成员在
`CeleritasDynamicLightsCompat` 类初始化时用 `Class.forName(name, false, loader)` 解析一次并缓存
`Method`，反射只出现在 `DynamicLightProviderImpl` 内，调用方只看到
`CeleritasDynamicLightsCompat.mergeDynamicLight`。

## 未安装、缺 API 与坏 jar 的行为

未安装附属时 `Class.forName` 抛 `ClassNotFoundException`：调用带 `initialize=false`，附属的类
不会被定义或初始化，桥使用 `DynamicLightProvider.NONE`，`mergeDynamicLight` 原样返回入参，
DEBUG 记录一行说明。光影下三条返回路径的亮度与没有附属时一致。

安装了缺少 `getLightmapWithDynamicLight(BlockPos, int)` 的旧版本时抛 `NoSuchMethodException`：
ERROR 记录一次日志，正文包含缺失的方法签名与本次启动内关闭合并的结论，桥同样使用 `NONE`。

附属 jar 损坏或在类链接阶段缺依赖时抛 `LinkageError`（含 `NoClassDefFoundError`）：解析处捕获
并记录一次 ERROR，桥使用 `NONE`，不会在每帧的调用点抛出 `NoClassDefFoundError`。

运行期调用失败（`get()` 返回 null、反射调用抛出异常、返回值不是 int）时，实现类把当前 provider
换成 `NONE`，并 ERROR 记录一次；后续调用直接返回入参，不再进入反射路径。日志标签
`ActiniumDynamicLightsCompat` 只定义在 `CeleritasDynamicLightsCompat` 一处，解析失败与调用失败
共用。

## 重复应用

附属在 `TileEntityRendererDispatcher.render` 的 `World.getCombinedLight` 调用点用
`@ModifyArgs` 把动态等级写进第二个参数（`lightValue`）。当前 Mixin 版本的 `@ModifyArgs` 在调用点
原地保留该 INVOKE，只替换参与调用的参数表达式，因此与 Actinium 在同一调用点上的
`@ModifyExpressionValue` 不互相改写注入目标，两者的应用顺序不影响结果：先改参数、再改返回值。

两处的合并规则一致：block light 取动态等级与原值的较大值，sky light 不变。因此重复应用幂等：
输入已经含动态光时再次合并得到同一结果，输入不含时补上动态光，等级为 0 时附属原样返回入参。
`CeleritasDynamicLightsCompatTest.mergingAnAlreadyMergedLightChangesNothing` 覆盖该情形。

## 自动化验证

`CeleritasDynamicLightsCompatTest` 走生产桥（真实反射解析与真实方法调用），
`toni.sodiumdynamiclights.SodiumDynamicLights` 替身只存在于 `src/test`，按附属的实现复刻合并
规则。覆盖四项行为：动态等级高于 block light 时替换、低于时保持、等级为 0 时原样返回（含
lightmap 低 4 位）、已合并的值再次合并不变。

`TileEntityLightTest` 用真实 `World` 与 `Block` 夹具直接调用 `TileEntityLight.packedLight`，覆盖
三条返回路径中的前两条合并点与重算分支：不使用 neighbor brightness 的早期返回、`getLightOpacity`
非 0 的早期返回、neighbor brightness 的重算值（世界 block light 与方块自发光取 max 后再合并）、
重算值高于动态等级时保持。

未启用光影包那条路径的合并在
`MixinTileEntityRendererDispatcherTesrLight.actinium$tesrLight` 的私有方法里，Mixin 处理器无法
在不启用 Mixin 的测试 VM 中执行，这部分由代码审查覆盖，测试不伪造该场景。

## 实机待验证场景

- 未安装附属：光影包下告示牌等使用 neighbor brightness 的方块实体亮度与改动前一致，日志无
  `ActiniumDynamicLightsCompat` 的 ERROR。
- 安装附属（记录版本，1.2.10 或 1.2.11）：光影包下手持火把或荧光物品靠近告示牌时告示牌随动态光
  变亮，走远后恢复；关闭附属动态光（设置页 Dynamic Lights = OFF）时画面不变。
- 非光影路径：不启用光影包时，箱子等方块实体随动态光变化，确认缓存值与合并同时生效。
- 注入共存：附属的 `ModifyArgs` 与 Actinium 的 `ModifyExpressionValue` 落在
  `TileEntityRendererDispatcher.render` 的同一个 `World.getCombinedLight` 调用点，确认两者同时
  生效且启动日志没有注入失败。
- 测试环境选择：安装附属 jar，或自行在现有 dev runtime 的依赖配置里启用对应条目。本仓库不新增
  Gradle 依赖，验证方式由使用者决定。
