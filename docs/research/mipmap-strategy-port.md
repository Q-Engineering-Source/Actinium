# mipmap 策略与 RGSS 移植（上游 #1952 + #2112 + #2115）

最后更新：2026-10-12。

移植上游 Angelica 三个叠加的提交，最终形态以 `ef68af58` 为准：

- `56d7568f` *Attempt at making mipmaps better*（PR #1952）——主体：mipmap 策略系统、mcmeta 声明、
  材质 mip-bias 管道、RGSS 片元路径与 GUI 档位。
- `02f0b0fc` *Fix mipmaps bleeding*（PR #2112）——出血回归修复。
- `ef68af58` *Fix texture filtering sampling regression*（PR #2115）——采样回归修复。

本文件是 B2 阶段的记录。#2096（纹理过滤 + gutter）那一次的记录在
`docs/research/texture-filtering-port.md`，两次移植的重叠与对账见下文「与 #2096 体系的 reconcile」。

## 特性实质

四件事：

1. **降采样策略系统**。原版 `TextureUtil.generateMipmapData` 只做一次 box 滤波，镂空贴图的透明像素
   会把周围染出一圈「光晕」，而且轮廓会随层级变细直到消失。新的 `MipmapGenerator` 按策略生成：
   `MEAN`（纯加权 box 滤波，等价于既有的 `MipmapHelper` 路径）、`CUTOUT` / `STRICT_CUTOUT`
   （先把透明像素涂成最近的不透明色，再逐层把 alpha 重新标定到基础层的 alpha-test 覆盖率）、
   `DARK_CUTOUT`（涂成最暗不透明色的 3/4，针对树叶）、`AUTO`（按有无全透明像素在 `CUTOUT` / `MEAN`
   之间选）。
2. **每张贴图可以自己声明策略**。`mcmeta` 的 `texture` 段新增 `mipmap_strategy` 键；命名族回退
   （树叶 / 花 / 蘑菇 / 玻璃 / 红石线）只在精灵真的有全透明像素时才生效。
3. **材质 mip-bias 管道**。`MaterialParameters` 的 `useMipmaps` 位一路传到片元着色器：GLSL
   `_material_mip_bias()` → 顶点着色器 `v_MaterialMipBias` varying → 片元的每条采样路径把它折进
   梯度或层级。原版 `CUTOUT` 层不采样 mip，于是该层材质带 -4 的 bias 落回 level 0，单套地形程序
   因此能同时表现「有 mip 的 cutout_mipped」与「无 mip 的 cutout」。
4. **RGSS 采样路径**。`block_layer_opaque.fsh` 增加旋转栅格超采样分支（4 个 tap + 按
   texel/像素比渐入渐出），配合 `USE_TEXEL_SNAP` / `TERRAIN_NO_MIPS` / `USE_ANISOTROPIC` /
   `TERRAIN_GUTTER` 四个 define；GUI 的 `TextureFilterMode` 由 2 档扩到 4 档。

两个回归修复随主体一起进来：

- **#2112 出血**：`GL_TEXTURE_MAX_ANISOTROPY_EXT` 是浮点参数，走 `glSamplerParameteri` 会被驱动以
  `GL_INVALID_ENUM` 丢弃，各向异性实际没生效；改走浮点入口（本树 `RenderSystem.samplerParameterf`）。
  同一提交还重写了 `SpritePadding.padFrame` 的按行拷贝。
- **#2115 采样回归**：`USE_TEXEL_SNAP` 的判定与「无 mip 时退回两参数采样」；以及 `OptionImpl` 的
  flags 改为可按需重算，使纹素混合选项只在过滤模式为 `NONE` 时才要求重建渲染器。

## 移植内容清单

### celeritas-common

- `impl/texture/TextureFilterMode.java`：4 档（`RGSS` / `ANISOTROPIC` / `RGSS_ANISOTROPIC` / `NONE`）、
  `usesRgss()`、`usesAnisotropy()`，驱动不支持各向异性时的可选集合为 `{RGSS, NONE}`。
- `impl/gui/SodiumGameOptions.java`：新增 `recordAtlasMipmapLevels(int)` / `getAtlasMipmapLevels()`
  ——方块图集加载完之后的**实际** mip 层数（`TextureMap.loadTextureAtlas` 会因为资源包贴图尺寸把
  `mipmapLevels` 调低，唯一权威值只有事后才知道）；`QualitySettings.textureFilterMode` 默认值与
  `load()` 的 `null` 兜底改为 `RGSS_ANISOTROPIC`。该文件仍不引入任何 Minecraft / GL 依赖。
- `api/options/structure/OptionImpl.java`：`flags` 字段改为 `Supplier<EnumSet<OptionFlag>>`，新增
  `Builder.setFlags(Supplier<EnumSet<OptionFlag>>)`；原 `setFlags(OptionFlag...)` 保留（默认 supplier
  返回那一组常量 flags）。

### glsm

- `RenderSystem.java`：新增 `samplerParameterf(int, int, float)`（#2112 的根因修复）。
- `GLStateManager.java`：`glGetTexLevelParameteri` 的 `GL_TEXTURE_WIDTH` / `GL_TEXTURE_HEIGHT`
  由「恒返回 0 级尺寸」改为 `max(info.getWidth() >> level, 1)`（#1952 自带；`TextureManipulationUtil`
  按 level 清 mip 层、以及 mod 侧按 level 查询都要用到）。

### 根项目（`src/main`）

- `texture/mipmap/MipmapStrategy.java`（新）：策略枚举 + `byName`（mcmeta 解析）+ `conflictPrecedence`
  （多规则命中时的取舍）。
- `texture/mipmap/MipmapStrategies.java`（新）：命名族回退规则表。
- `texture/mipmap/MipmapGenerator.java`（新）：层级生成器（solidify / 最暗色填充 / 覆盖率标定 /
  逐层下采样，下采样复用 `celeritas-common` 的 `MipmapHelper.weightedAverageColor`）。
- `texture/mipmap/SpriteMipmaps.java`（新）：精灵帧的透明分类（`Transparency`）与策略判定
  （`resolve`）——两部分逻辑都放在 mixin 包外，mixin 只做转发。
- `mixin/vintage/features/mipmaps/MixinTextureAtlasSprite.java`：重写为三个注入点：
  `loadSpriteFrames` HEAD 读策略（mcmeta 声明优先，否则命名族回退）、`generateMipmaps` HEAD 分类、
  `generateMipmaps` 里 `TextureUtil.generateMipmapData` 的 `@Redirect` 改走 `MipmapGenerator`。
- `mixin/vintage/features/mipmaps/MixinTextureMetadataSection.java`（新）：在
  `TextureMetadataSection` 上挂 `TextureMetadataExtension` 的实现。
- `mixin/vintage/features/mipmaps/mixinterface/TextureMetadataExtension.java`（新）：注入接口，
  按本树 `mixinterface` 约定放置。
- `mixin/vintage/features/mipmaps/MixinTextureMetadataSectionSerializer.java`（新）：从 `mcmeta` 的
  `texture` 段读 `mipmap_strategy`。
- `mixin/vintage/features/mipmaps/MixinTextureUtil.java`（删）：`TextureUtil.generateMipmapData` 在
  1.12.2 全树只有 `TextureAtlasSprite.generateMipmaps` 一个调用点，已被上述 `@Redirect` 整体接管，
  `blendColors` 因此不可达（上游 #1952 同样删除）。
- `mixin/vintage/features/textures/MixinTextureAtlas.java`：`loadTextureAtlas` RETURN 时记录方块图集
  的实际 mip 层数。
- `render/terrain/TerrainTextureFiltering.java`：`resolveFilterMode` 的默认值与回退（各向异性不支持时
  `RGSS_ANISOTROPIC` → `RGSS`，而不是一律 `NONE`）、`usesTerrainTexelSnap()`、
  `terrainMipmapLevels()`。
- `render/terrain/VintageRenderPassConfigurationBuilder.java`：每个地形 pass 加过滤 defines
  （`USE_RGSS` / `USE_TEXEL_SNAP` / `USE_ANISOTROPIC` + `TERRAIN_GUTTER` / `TERRAIN_NO_MIPS`）；
  `CUTOUT_MIPPED` 材质 cutoff 由 `ONE_TENTH` 改为 `HALF`（与 alpha 覆盖率标定用的 0.5 参考一致）；
  `CUTOUT` 材质改为 `mipped=false`（bias -4 落回 level 0）。
- `render/terrain/VintageRenderSectionManager.java`：各向异性改用浮点采样参数入口。
- `gui/ActiniumGameOptionPages.java`：过滤模式选项加
  `REQUIRES_ASSET_RELOAD, REQUIRES_RENDERER_RELOAD`；纹素混合选项加动态 flags（过滤模式为 `NONE` 时
  才要求重建渲染器）。
- `resources/assets/actinium/shaders/blocks/block_layer_opaque.vsh`：新增 `v_MaterialMipBias` 输出。
- `resources/assets/actinium/shaders/include/chunk_material.glsl`：新增 `_material_mip_bias()`。
- `resources/assets/actinium/shaders/blocks/block_layer_opaque.fsh`：新增 texel-snap 采样与 RGSS 分支，
  `main()` 按 `USE_TEXEL_SNAP` 在「着色器自己选层级」与「纹理自身过滤状态选层级」之间切换。
- `resources/assets/celeritas/lang/{en_us,ru_ru,zh_cn}.lang`：`rgss` / `rgss_anisotropic` 两个键。
- `resources/mixins.actinium.vintage.json`：删 `features.mipmaps.MixinTextureUtil`，加
  `MixinTextureMetadataSection` 与 `MixinTextureMetadataSectionSerializer`。

### 测试

- `src/test/java/com/dhj/actinium/texture/mipmap/MipmapGeneratorTest`（11 例）：逐像素断言
  MEAN 的加权平均结果、AUTO 的两种解析、DARK_CUTOUT 的原地重涂、CUTOUT 的 solidify、已有层级原样沿用、
  逐层尺寸、覆盖率模型的两个 alpha 参考、`scaleAlphaToCoverage` 的收敛与 clamp。
- `.../MipmapStrategiesTest`（7 例）：五条命名族规则、物品贴图不被 `blocks/` 作用域外的前缀误伤、
  无命名空间前缀、多规则命中的 precedence、无规则命中返回 null。
- `.../MipmapStrategyTest`（3 例）：`byName` 的大小写与未知名、`conflictPrecedence` 的严格序。
- `.../SpriteMipmapsTest`（6 例）：透明分类的四种组合（全不透明 / 半透明 / 全透明 / 混合）、
  多帧取并集、无帧时的保守值、策略判定（声明值恒生效，命名族回退只在真有全透明像素时生效）。
- `src/test/java/com/dhj/actinium/render/terrain/TerrainTextureFilteringTest`（12 例）：模式回退、
  默认值、可选集合、倍数 clamp，以及图集层数记录。
- `src/test/java/dhj/embeddedt/embeddium/api/options/structure/OptionImplTest`（3 例）：supplier flags
  每次查询都重算、常量 flags 仍可读回、未声明时为空的语义。

## 与 #2096 现有体系的 reconcile

#2096 已经在树里的东西：`SpritePadding`（gutter 与扩边上传统一入口）、`SpritePaddingExtension`、
`AtlasAssembler` / `AtlasLoadSession`（图集并行加载，来自 #2167）、`TextureFilterMode`、
`TexelSampling`、`TerrainTextureFiltering`、地形 sampler、`block_layer_opaque.fsh`。逐项对账：

- **不双套 mipmap 生成逻辑**：`celeritas-common` 的 `MipmapHelper.weightedAverageColor` 保留不动，
  它是**单次两像素加权平均**这一原语；`MipmapGenerator` 是它上层的层级生成器，逐层下采样直接调用它。
  也就是说 #1952 的生成器建立在 #2096 已有的原语之上，而不是另起一套。
- **`SpritePadding` 无需再改**：上游 #2112 重写 `padFrame`（按 `stride` 逐行写、`remaining` 处理被截断
  的层数据、去掉原地两趟扩张），本树对应的实现是 `writeLevel`（数组分支与直接写显存分支共用）：

  | 上游 #2112 修的点 | 本树现状 |
  |---|---|
  | 层数据按 `stride` 逐行放到 `levelGutter` 偏移处 | `writeLevel` 里 `rowOffset = dstY*stride + dstX`，左/右边界用 `Arrays.fill` 补，正是同一件事（`SpritePadding.java:86-89`） |
  | 被截断的层数据只写有效行 | `availableRows = min(levelHeight, levelData.length / levelWidth)`，超出部分垂直 clamp 到最后一个有效行（`SpritePadding.java:172-174`、`:74-80`） |
  | 去掉原地两趟扩张（先整块拷贝再就地移位） | 本树从一开始就是「直接从 `src` 取像素写到目标位置」，没有中间拷贝 |
  | clamp-to-edge 扩边 | 由 `writeLevel` 的行内 fill + `clampCoord` 完成（`SpritePadding.java:74,125-133`） |

  结论：上游那次重写修的缺陷在本树不存在，`SpritePadding` 一行未动；`SpritePaddingTest`
  （既有）继续锁定该行为。
- **gutter 宽度来源**：#2096 用 `SpritePadding.gutterFor(this.mipmapLevels, ...)`（图集加载时），
  本次着色器的 `TERRAIN_GUTTER` 用 `gutterFor(terrainMipmapLevels(), true)`（渲染器构建时）。两者必须
  相等：前者用的是 `TextureMap` 当场、已经因资源包被调低过的 `mipmapLevels`，后者读的是同一个值在
  图集加载完后记录下来的快照，所以一致；这也是本次要新增 `recordAtlasMipmapLevels` 的原因。
- **`Mixins.java` → mixin json**：上游把新 mixin 加进 `Mixins.java` 枚举，本树走
  `mixins.actinium.vintage.json`，由 `MixinConfigurationTest` 校验。
- **`AtlasPBRLoader`**：上游 #1952 让 PBR 精灵的 `loadSprite` 布尔位带上
  `needsForcedSpritePadding()`。1.12.2 的 `TextureAtlasSprite` 没有那个签名、也没有
  `useAnisotropicFiltering` 字段（本树 PBR 精灵自己扩边并复用 `SpritePadding.uploadPadded`），注入点
  无对象，不改。
- **`notfine` / `ReeseSodiumVideoOptionsScreen` / `SodiumControlElement` 的划线渲染**：GTNH 生态专有或
  纯外观，本树无对应物或不属本次范围。本树禁用态由既有的 `setEnabledPredicate` 机制处理。

## 与上游的偏差及理由

### 策略声明与回退（1.12.2 无 `Block.registerBlockIcons`）

上游按**注册该精灵的方块类**归因（`BlockLeaves` → `DARK_CUTOUT`、`BlockFlower`/`BlockMushroom` →
`STRICT_CUTOUT`、`BlockGlass`/`BlockRedstoneWire` → `MEAN`），靠 `TextureMap.registerIcons` 里对
`Block.registerBlockIcons` 的 `@Redirect` 建栈。1.12.2 里这套机制整体不存在（1.8 起方块模型接管，
`IIconRegister` / `registerBlockIcons` 已删除，`TextureMap` 也没有 `textureType`），没有任何时点能把
精灵归因到方块类。因此 `MipmapStrategies` 改写成**按精灵路径的命名族**表达同一组规则，并只对
`blocks/` 前缀生效（1.12.2 方块贴图与物品贴图共用一张图集，`items/glass_bottle` 不能被玻璃规则命中）。

### 策略赋值点（`loadSpriteFrames` 而不是 `loadTextureAtlas`）

上游在 `TextureMap.loadTextureAtlas` 里对 `TextureAtlasSprite.loadSprite(BufferedImage[], …, boolean)`
的调用点用 `@Local TextureMetadataSection` 取声明值。1.12.2 的 `loadTextureAtlas` 不调用那个签名
（贴图帧由稍后的 `generateMipmaps(IResourceManager, TextureAtlasSprite)` 加载），所以改挂在精灵自己的
`loadSpriteFrames(IResource, int)` 上——这是**唯一**同时拿到「该精灵的 `IResource`」与「精灵自身」、
且在 mipmap 生成之前、且在主线程上的点（#2167 的并行图集加载会把 `generateMipmaps(int)` 挪到 worker，
`AtlasLoadSession.prepare` 提前跑的仍是 `TextureMap.generateMipmaps`，仍然经过 `loadSpriteFrames`）。
`IResource.getMetadata("texture")` 直接从已打开的包读取，不产生额外资源查找。

损坏的 `texture` 段会让 `getMetadata` 抛异常并使图集加载失败——这是刻意的 fail-fast（不 catch 成兜底），
与既有行为相比是把原本「没人读这个段」的静默错误提前暴露。

### 分类与判定的落点

透明分类（`SpriteMipmaps.scan`）与策略判定（`SpriteMipmaps.resolve`）放在
`com.dhj.actinium.texture.mipmap`，mixin 只做转发，符合「mixin 类只做注入」。分类循环保持纯 int 运算
与静态调用：早期形态曾逐像素调用多态方法，把 JDK 25 的 C2 SuperWord 打进不收敛的编译并持续分配直到
JVM 中止，这条约束在实现里以注释固定。

### 图集 mip 层数的记录方式

上游另有一套「资源包 mip 上限」记录（`recordAtlasMipmapClamp` + `getResourcePackMipmapCeiling`），
本树改为只留一个**实际层数**记录：`terrainMipmapLevels()` 在图集加载过后读实际值，加载之前退回
`gameSettings.mipmapLevels`。上游那套上限在本树的唯一消费点是那个「加载之前」的估算路径，两个来源
记同一件事只会带来不一致风险，故删去（相应测试改为断言实际层数的记录语义）。

### 采样器在各过滤模式下仍然绑定

上游 #2115 让 `bindTerrainSampler()` 在「不需要着色器自己 snap」时直接返回，理由是他们那个 sampler
无条件请求 mipmap 过滤，单层图集上会读到不完整纹理。本树的 `configureTerrainSampler` 已经按
`TextureMap.getMipmapLevels() > 0` 决定 min filter（#2096 时就有的偏差），不存在该风险；若照搬那个
提前返回，反而会让「纹素混合 = Nearest」的选择静默失效（sampler 一旦不绑定，纹理自身的
`GL_LINEAR`/`GL_NEAREST` 状态就会接管）。

### 各向异性倍数来源

上游读 1.7.10 原版的 `gameSettings.anisotropicFiltering`；1.12.2 无该设置，倍数继续由本树自己的
`quality.anisotropicLevel`（log2）持有（#2096 已有的偏差，本次不动）。

### `OptionImpl` 的 flags

上游用 `Supplier<EnumSet<OptionFlag>>`。本树同样改造，但保留 `setFlags(OptionFlag...)` 重载（树里有
20 多处调用），默认 supplier 返回那组常量 flags，语义与改动前一致。

## 跳过项与理由

- **`angelica$alwaysTranslucent`（上游用 `AngelicaConfig.alwaysTranslucentSprites` 强制某类精灵为
  TRANSLUCENT，并在策略上强制 `MEAN`）**：本树没有该配置项（全局搜不到），也没有等价物，故不引入；
  1.12.2 的等价诉求可由资源包在贴图的 `mcmeta` 里写 `"mipmap_strategy": "mean"` 表达。
- **`MixinTextureMap` 的 `applyAtlasSettings` / `needsForcedSpritePadding` / `initMissingImage` 补边**：
  本树 gutter 由 `gutterFor(mipCount, terrainAtlas)` 统一决定，并由 `MixinTextureAtlas` 在
  `doStitch` 前写到每个精灵与 `missingImage` 上（#2096 已有），不需要「按过滤模式强制扩边」这条路径。
- **`TextureFilterMode.needsSpritePadding()`**：同上，本树的扩边不依赖过滤模式。
- **`effectiveTextureFilterMode()` 在无 mip 时返回 `NONE` 的上游门控**：本树不套这层门控。无 mip 时
  着色器走 `TERRAIN_NO_MIPS` 分支（RGSS 退化成 level 0 的 4 个 tap，texel-snap 走 `textureLod(…, 0)`），
  采样器的 min filter 也不带 mipmap（见上），不存在读不完整纹理的风险，而保留 RGSS 档位能让
  「关掉 mipmap 但仍想要远景抗锯齿」成为可选项。
- **`GLStateManager.GL_TEXTURE_WIDTH/HEIGHT`** 之外，#1952 对 glsm 没有别的改动。
- **GUI 的划线/禁用态外观**（`SodiumControlElement.formatValue` 等）：纯外观且本树 GUI 结构不同，
  不移植。
- **`InterpolatedIcon` / `NotFine` / `ReeseSodiumVideoOptionsScreen` 专有项**：本树无对应物。

## 验证

- `./gradlew check --no-daemon` 通过（含 `DependencyDirectionTest`、`MixinConfigurationTest`、
  remap Jar 结构校验与全部单测：1172 例 0 失败）。
- `./gradlew build --no-daemon` 通过。
- 注入目标按 SRG 命名逐一核对（用 Cleanroom 的 `*-sources-searge` 源核对，避免只在 dev 命名下成立）：

  | 本树写法 | 运行期（SRG）名 |
  |---|---|
  | `TextureAtlasSprite.generateMipmaps(int)` | `func_147963_d(I)V` |
  | `TextureAtlasSprite.loadSpriteFrames(IResource, int)` | `func_188539_a(Lnet/minecraft/client/resources/IResource;I)V` |
  | `TextureUtil.generateMipmapData(int, int, int[][])` | `func_147949_a(II[[I)[[I` |
  | `TextureMetadataSectionSerializer.deserialize(…)` | 同名（覆写 Gson 的 `JsonDeserializer.deserialize`，不被混淆） |
  | `TextureMap.loadTextureAtlas(IResourceManager)` | `func_110571_b(Lnet/minecraft/client/resources/IResourceManager;)V` |

  上表与 remap 产物 `mixins.actinium-refmap.json` 的条目一致。
- 本次新增/修改的测试见上文清单，全部直接调用被测逻辑，无反射、无 GL 上下文依赖。
- **未验证（待实机）**：RGSS 档的远景观感与性能、gutter 带来的显存/图集尺寸增长、mipmap 边界出血、
  各向异性在真实驱动上的生效情况、以及 `mipmap_strategy` 键在资源包中的实际解析。这些都需要 GL 与
  真实资源加载，单测覆盖不到。
- 一个静态结论：`ShaderChunkRenderer.enableLegacyGLPatches`（< GL 3.2 时把着色器降级为 GLSL 120）在本
  树不可达——`MixinMinecraftCoreProfileDisplay` 在启动时强制要求 GL 3.3+；否则新的
  `textureGrad` / `textureLod` / `textureSize` 在 GLSL 120 下无法编译。上游同样如此。

## 实机验证建议（需用户执行）

1. **RGSS 档的远景质量**：纹理过滤选 RGSS 与 RGSS & Anisotropic，观察远处地面/树叶/栅栏：应比
   `NONE` 更稳定（旋转栅格抗锯齿），不应出现取样点越到相邻精灵（表现为方块边缘被邻居贴图染色）。
   将纹素混合在 Linear / Nearest 之间切换，确认两种组合都可用且切换后立即生效（Nearest 且过滤为
   `NONE` 时才会要求重建渲染器）。
2. **gutter 的显存占用**：gutter = `1 << mipmapLevels`（mip 4 时 16×16 精灵的槽位从 16 变 48），
   方块图集面积约 9 倍。用 GPU 显存监视对比开关前后的图集占用，并确认 mip 4 下 16×16 小贴图
   没有把图集顶到 `GL_MAX_TEXTURE_SIZE` 之上。
3. **mipmap 边界出血**：mipmap 开到 4，站远看树叶（应为暗色光晕而非亮边）、花/农作物（轮廓不应在
   远处消失）、玻璃与红石线（应保持 MEAN 的平滑）、以及 CUTOUT 与 CUTOUT_MIPPED 混合的场景
   （例如带 cutout 的方块旁边有 cutout_mipped 方块，确认 -4 bias 的层没有串到别的层）。
4. **贴图清单中的 `mipmap_strategy`**：准备一个资源包，给一张方块贴图写
   `{"texture": {"mipmap_strategy": "dark_cutout"}}`（`.mcmeta`），确认该贴图的远景光晕变成暗色，
   并确认写一个非法值时图集加载**失败并报错**（fail-fast），而不是静默忽略。
5. **各向异性**：在斜视的砖块/木板地面确认锐利度随 Anisotropic 档位变化——修复前因为用整数入口提交
   该参数，驱动会丢弃它，几乎看不出差别。
6. **回归面**：资源包重载、切换资源包、改 mipmap 级数、改过滤模式后各一次（覆盖 `REQUIRES_ASSET_RELOAD`
   与 `REQUIRES_RENDERER_RELOAD` 的组合路径），确认地形不消失、贴图不错位。
