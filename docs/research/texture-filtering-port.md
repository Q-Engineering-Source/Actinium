# 纹理过滤改进移植（上游 #2096）

最后更新：2026-10-11。

移植上游 Angelica 提交 `26c161f0`（*Improve upon Texture Filtering Work*，PR #2096）。
上游那一版建立在更早的 `56d7568f`（*Attempt at making mipmaps better*，PR #1952）之上，
而 #1952 **没有**移植进 Actinium，所以本文件除了记录移植内容，也记录由此产生的取舍。

## 特性实质

两件事：

1. **精灵在拼接图集里预留「复制边缘」gutter**，替代 1.7.10 的「纹理放大 16 像素 + UV 内缩 8 像素」
   （`TextureAtlasSprite.useAnisotropicFiltering` 一整套）。gutter 宽度取 `1 << mipmapLevels`：
   每个 mip 层级都至少剩 1 像素边界，且拼接槽宽度仍是 `2^mipmapLevels` 的倍数，
   保证每层原点都落在纹素上。上传时把每一层按 clamp-to-edge 复制扩边再上传。
2. **地形采样器参数（各向异性 + 纹素过滤）从「材质/纹理自己的参数」改为按 pass 绑定的 sampler 对象**，
   并把 uv 质心偏移 hack 从 Iris 顶点编码器里去掉（gutter 已经解决同一件事）。

## 移植内容

### celeritas-common

- `impl/texture/SpritePadding.java`（新增）：`gutterFor(mipmapLevels, terrainAtlas)` /
  `uploadPadded(...)` / `padFrame(...)` / clamp-to-edge 扩边。放在 celeritas-common 而不是根项目，
  是因为 shader 子项目的 PBR 图集也要用，而子项目禁止引用 `com.dhj.actinium`（`DependencyDirectionTest`）。
- `impl/texture/SpritePaddingExtension.java`（新增）：gutter 读写接口。
  上游把它并进根项目的 `SpriteExtension`；这里拆成低层接口，根项目的 `SpriteExtension` 继承它，
  这样 shader 侧的 PBR 代码也能读取 gutter。
- `impl/texture/TexelSampling.java`（新增）：地形采样器 min/mag 过滤（Linear / Nearest）。
- `impl/texture/TextureFilterMode.java`（新增）：纹理过滤模式。上游有 4 个取值
  （`RGSS` / `ANISOTROPIC` / `RGSS_ANISOTROPIC` / `NONE`），这里只有 `ANISOTROPIC` / `NONE`（理由见下）。
- `impl/gui/SodiumGameOptions.java`：`QualitySettings` 新增 `textureFilterMode`、`texelSampling`、
  `anisotropicLevel`，并在 `load()` 里补旧配置的缺省值。

### 根项目（`src/main`）

- `texture/SpriteExtension.java`：继承 `SpritePaddingExtension`。
- `mixin/vintage/features/textures/MixinTextureAtlas.java`：在 `Stitcher.doStitch()` 之前给
  `mapRegisteredSprites` 与 `missingImage` 写 gutter；把 `loadTextureAtlas` 里的
  `TextureUtil.uploadTextureMipmap` 重定向到 `SpritePadding.uploadPadded`。
- `mixin/vintage/features/textures/MixinStitcherHolder.java`（新增）：`Stitcher$Holder.getWidth/getHeight`
  返回时加 `2 * gutter`，让拼接槽真的变宽。
- `mixin/vintage/features/textures/MixinTextureAtlasSprite.java`：保存 gutter；`initSprite` 的
  origin 参数内缩一个 gutter；`updateAnimation` / `updateAnimationInterpolated` 的上传走扩边路径。
- `render/terrain/TerrainTextureFiltering.java`（新增）：从选项 + 驱动能力解析
  「有效过滤模式 / 各向异性倍数 / 是否支持」。
- `render/terrain/VintageRenderSectionManager.java`：`ChunkRenderer` 新增地形 sampler
  （`begin` 绑定、`end` 解绑、`delete` 销毁），普通 pass 用 `texelSampling` 决定 min/mag，
  光影包 pass 固定 nearest + 各向异性（包自己处理过滤）。
- `gui/ActiniumGameOptionPages.java`：Mipmaps 分组新增「纹理过滤」「各向异性过滤」「纹素混合」三项。
- `resources/assets/celeritas/lang/{en_us,ru_ru,zh_cn}.lang`：新增 10 个键。
- `resources/mixins.actinium.vintage.json`：登记 `features.textures.MixinStitcherHolder`。

### shader

- `net/coderbot/iris/celeritas/vertices/ExtendedChunkVertexEncoder.java`：删除 uv 质心偏移
  （`TEX_CENTROID_BIAS` 与 `a_TexCoord` 偏移）。上游在该提交里同样删除。
- `net/coderbot/iris/texture/pbr/PBRAtlasTexture.java`、`.../loader/AtlasPBRLoader.java`：
  PBR 图集按主图集的 gutter 上传，PBR 精灵把自己的 origin 退回到槽原点后交给共享的
  `initSprite` 内缩，使两张图集的内容坐标一致。上游那段 `padAnisotropic` 硬编码 16 像素扩边的
  hack 一并删除（Actinium 版本本就没有它）。

### sdl-gpu

补齐 #2096 的后端部分（Phase 2 只落地了 `ResourceManager.ensureTextureLevels` 与
anisotropy 采样参数追踪，调用侧一直缺失）：

- `SDLGPURenderBackend`：`requestedMipLevels` / `fullMipLevels` / `growForLevel`；
  `ensureLevel0Storage` 简化；`canReuseTexture` 由 `levels ==` 放宽为 `levels >=`；
  `texImage2D`（ByteBuffer / PBO 两条路径）、`copyTexImage2D`、`textureImage2DEXT` 的非 0 层级
  改为按需扩容；`generateTextureMipmap` 先把层级链补到完整再调 `SDL_GenerateMipmapsForGPUTexture`；
  `getInteger` / `getFloat` 回答 `GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 16`，
  并给未处理的 `getFloat` pname 加去重告警（此前静默返回 0）。

## 与上游的偏差及理由

- **`MipmapHelper` 与放置位置**：`celeritas-common` 的 `MipmapHelper.weightedAverageColor` 保留不动。
  上游的 `MipmapGenerator` 是它**上层**的策略层（`MipmapStrategy` 决定 blend/cutout/dark-cutout，
  内部调用 `MipmapHelper.weightedAverageColor`），两者职责不重叠，不构成双套逻辑。
  上游 #2096 对 `MipmapGenerator` 的改动（去掉 `border` 参数、`STRICT_CUTOUT_ALPHA_REF` 调整）
  只对那一套策略系统有意义，Actinium 没有它，故不移植（见下）。
- **`TextureFilterMode` 只有两个取值**：上游的 `RGSS` / `RGSS_ANISOTROPIC` 依赖
  `block_layer_opaque.fsh` 的 RGSS 分支，而该分支读 `v_MaterialMipBias`——这个 varying 由
  #1952 引入的材质 mip-bias 管道产生。Actinium 的 `block_layer_opaque.vsh` 与
  `MaterialParameters` 都没有它（`Material` 只有 `mipped` 布尔），移植 RGSS 等于把 #1952 的
  材质管道也搬过来，超出本提交范围。因此**只保留 Actinium 真能兑现的模式**，不保留空分支。
- **各向异性倍数的存储位置**：上游读 `mc.gameSettings.anisotropicFiltering`（1.7.10 的原版滑块）。
  **1.12.2 原版已无该设置**（`GameSettings` 里没有该字段，全局搜不到 anisotropic），
  所以倍数由 Actinium 自己的选项 `quality.anisotropicLevel`（log2，1..4 → 2x..16x）持有，
  默认 1（2x），与上游「clamp 到至少 2x」的有效默认一致。
- **解析逻辑放根项目**：上游把这些 static 方法放在 sodium 选项类上。Actinium 的
  `SodiumGameOptions` 在 celeritas-common 且刻意不依赖 Minecraft（读 `Minecraft.getMinecraft()`
  与 GL 查询都会破坏该边界），因此解析放进根项目的 `TerrainTextureFiltering`。
  其中纯策略部分（模式回退、倍数 clamp）拆成无 GL 的重载以便单测。
- **采样器 min filter 按图集实际 mip 层数选择**：上游的 `configureTerrainSampler` 无条件用
  `*_MIPMAP_LINEAR`。绑定的 sampler 对象会覆盖纹理自己的过滤状态，而在 Actinium 里
  `VintageRenderPassConfigurationBuilder` 会在每个 pass 前用 `setBlurMipmapDirect(false, mipped)`
  重置图集过滤（`Mipmap Levels = 0` 时是 NEAREST）。若无条件请求 mipmap 过滤，关闭 mipmap 的
  玩家会从 NEAREST 变成 LINEAR（观感变糊），而且单层图集上的 mipmap 过滤会读到不完整纹理。
  因此 min filter 按 `TextureMap.getMipmapLevels() > 0` 决定（无 mip 时退回 LINEAR/NEAREST），
  并把该状态与各向异性、纹素过滤一起纳入 sampler 重建条件。
- **gutter 接口保留上游的 `angelica$` 前缀**：Actinium 自己的注入接口用 `celeritas$` 前缀
  （`SpriteExtension.celeritas$markActive` 等），但 gutter 这两个方法沿用上游命名，
  以便后续提交（`c10d30b2` 图集并行加载继续改 `SpritePadding`）对照时不必做名字翻译。
- **`@Local` 改为实例字段**：上游 `MixinTextureMap` 用 `@Local(ordinal = 0) TextureAtlasSprite`
  取正在上传的精灵。1.12.2 的 `loadTextureAtlas` 里同一位置存在多个 `TextureAtlasSprite` 局部变量，
  解析容易随编译器重排漂移，所以改为在 doStitch 注入点把 gutter 记在 mixin 的实例字段上
  （gutter 本就是整个图集统一的值）。
- **上游 `Mixins.java` 枚举 → Actinium 的 mixin json**：上游把新 mixin 加进 `Mixins.java` 枚举；
  Actinium 走配置文件，故 `MixinStitcherHolder` 登记进 `mixins.actinium.vintage.json`
  （由 `MixinConfigurationTest` 校验「编译出的每个主 mixin 恰好声明一次」）。
- **`initMissingImage` 注入未移植**：上游重写它来关掉 1.7.10 的未命中纹理各向异性放大。
  1.12.2 的原版实现本来就没有放大（也没有 `useAnisotropicFiltering` 字段），注入点无对象。
- **`InterpolatedIcon`（notfine）未移植**：Actinium 没有 notfine。
- **PBR 调试导出文件名**：上游按 blocks/items 区分导出名；Actinium 的导出名走
  `id.getNamespace() + "_" + path` 的既有方案，语义等价，不重复改。
- **sdl-gpu 的偏差**：与 Phase 2 记录一致（模块未注册进 service 文件、测试台未接）。
  本次只是把调用侧补齐，使 `ensureTextureLevels` 不再是无调用者的死代码。

## 跳过项与理由

- **`utils/MipmapGenerator` / `MipmapStrategies` / `MipmapStrategy`、`MixinTextureMetadataSection(Serializer)`**：
  #1952 的策略系统整体不在 Actinium。移植 #2096 的两处常量/签名调整需要连同 #1952 一起搬，
  属于另一个提交的范围；Actinium 当前走原版 `TextureUtil.generateMipmapData` + 我们覆写的
  `blendColors`。
- **`block_layer_opaque.fsh` 的 RGSS / texel-snap 改写**：文件在 Actinium 是存在的
  （`assets/actinium/shaders/blocks/`），但上游版本的新 `sampleTexelSnapped` 与 `sampleRGSS`
  都用 `textureGrad` + `v_MaterialMipBias`（同上：该 varying 不存在）。另外 Actinium 的这个 fsh
  刻意用两参数 `texture()` 采样（注释说明：避免 alpha test 在 mip 边界被导数选择的层级丢掉），
  改成显式梯度会推翻那个决定。故整段跳过。
- **`MixinMinecraft` 的 grassQuality（fancyGraphics 覆写）**：1.7.10 的注入点是
  `Minecraft.runGameLoop` 里读 `GameSettings.fancyGraphics`；1.12.2 的 `runGameLoop` 根本不读它
  （只有 `Minecraft.isFancyGraphicsEnabled()` 方法），草方块走模型管线，注入点无对象。
- **`AngelicaChunkBuildContext` 的 `facesAtBaseMaterial` 位掩码**：那是上游「精灵→材质」缓存命中
  路径上的快捷位；Actinium 的 `VintageChunkBuildContext.copyBlockData` 没有那个缓存（每个 quad
  直接算 `selectMaterial`），没有可优化的对象。该位掩码本身是按 facing 记忆「保持基础材质」，
  对同一 facing 上精灵不同的模型会把 quad 分错 pass，故不新造一套。
- **README / 第三方署名**：上游在 README 增加了 RGSS 的参考实现署名（Indiesoft 的
  defold-sharp-sprite，MIT）。本次没有移植 RGSS，故不添加该署名，也不动 `THIRD_PARTY_NOTICES.md`。

## 验证

- `./gradlew check --no-daemon` 通过（含 `DependencyDirectionTest`、`MixinConfigurationTest`、
  remap Jar 结构校验）。
- `./gradlew build --no-daemon` 通过；产物 `mixins.actinium-refmap.json` 里，本次新增的注入点
  全部解析到真实 SRG 名，可以确认目标存在且签名一致：
  - `MixinTextureAtlas`：`doStitch` → `func_94305_f`、`loadTextureAtlas` → `func_110571_b`、
    `uploadTextureMipmap([[IIIIIZZ)V` → `func_147955_a`。
  - `MixinTextureAtlasSprite`：`initSprite` → `func_110971_a(IIIIZ)V`（即 4 个 int + boolean，
    与 `ordinal = 2/3` 取 originX/originY 的假设一致）、`updateAnimation` → `func_94219_l`、
    `updateAnimationInterpolated` → `func_180599_n`。
  - `MixinStitcherHolder`：`Stitcher$Holder`、`getWidth` → `func_94197_a`、
    `getHeight` → `func_94199_b`。
  （refmap 只记录需要重映射的成员；`@Shadow` 字段与既有 `mapUploadedSprites` 一样不出现，
  走的是与上游相同的既有机制。）
- 新增/修改的自动化测试：
  - `dhj.embeddedt.embeddium.impl.texture.SpritePaddingTest`：gutter 取值、每层对齐、
    clamp-to-edge 扩边内容（逐像素断言）、源数据不被改写、scratch 复用不残留上一帧。
  - `com.dhj.actinium.render.terrain.TerrainTextureFilteringTest`：各向异性不可用时的模式回退、
    旧配置缺省值、可选模式列表、倍数 clamp。
  - `sdlgpu.ProxyTextureLimitsTest`：新增各向异性上限（`deviceLimit` / `getInteger` / `getFloat`）
    与 `canReuseTexture` 的 `levels >=` 语义。
- **未验证（待实机）**：图集 gutter 的视觉结果、各向异性/纹素过滤在两种过滤模式下的观感、
  光影包 pass 的 sampler 绑定与解绑是否干扰 Iris 自己的 sampler 绑定、以及图集尺寸/显存增长
  （gutter = `1 << mipmapLevels`，mip 4 时 16x16 精灵的槽位从 16 变 48，块图集面积约增至 9 倍）。
  sdl-gpu 的 mip 扩容路径需要真设备测试台，本次只覆盖了纯函数部分。
