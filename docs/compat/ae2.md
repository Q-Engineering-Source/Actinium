# AE2 Unofficial Extended Life（应用能源 2）兼容性说明

兼容状态:**已验证**（dev 实机,用户确认）
最后更新:2026-10-17

## 验证范围

- 版本:AE2 Unofficial Extended Life 0.56.6,CurseForge `ae2-extended-life-570458`
  文件 `5411078`（dev 侧 `modRuntimeOnly 'curse.maven:ae2-extended-life-570458:5411078'`）
- 环境:Cleanroom 0.6.12-alpha + Java 25,`./gradlew runClient`,无光影包
- 上游问题:issue #191 *[Bug] AE2 Controller's Animated Texture unrendering when moving away*

## 症状:控制器彩色光效随距离变淡直至"像没通电"

### 现象

大型已供电 ME 控制器,近处能看到面上彩色（RGB 循环）的光效动画;向后退出若干格后
光效**逐渐变少**,最终只剩 `controller_powered` 底纹,观感与未供电的控制器一致。
动画本身没有报错,也没有崩溃,走回近处又恢复。

### 涉及的素材

- `assets/appliedenergistics2/textures/blocks/controller_lights.png`:16×192,
  12 帧、`frametime: 3` 的 mcmeta 动画（每帧一种色相的迷宫纹）。
- `models/block/controller/controller_block_online.json`:该动画贴图作为**上层整方块**
  的六个面烘焙进模型,与下层 `controller_powered` 整方块完全共面（superimposed quads）。
- `BlockController.getRenderLayer()` 返回 `BlockRenderLayer.CUTOUT`;控制器没有 TESR,
  `uvlightmap` 只把该 quad 重写成带自定义 lightmap 的 BLOCK 顶点,sprite 仍是
  `controller_lights`。

## 根因:CUTOUT 层被并进了 mipmapped 通道

vanilla 1.12.2 的 `EntityRenderer.renderWorldPass` 只给 `CUTOUT_MIPPED` 开 mip 采样,
`CUTOUT` 单独关掉:

```java
renderBlockLayer(BlockRenderLayer.CUTOUT_MIPPED, ...);
this.mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).setBlurMipmap(false, false);
renderBlockLayer(BlockRenderLayer.CUTOUT, ...);
this.mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).restoreLastBlurMipmap();
```

也就是 `CUTOUT` 方块在 vanilla 里**永远按 mip0 + NEAREST 采样**。而 Actinium 之前
（`performance.useRenderPassConsolidation` 开或关都一样）把 `CUTOUT` 与 `CUTOUT_MIPPED`
送进同一个 mipmapped cutout 通道,`Material` 里编码的 `useMip` 位虽然进了顶点数据,
[block_layer_opaque.fsh](https://github.com/Q-Engineering-Source/Actinium/blob/main/src/main/resources/assets/actinium/shaders/blocks/block_layer_opaque.fsh)
从未读取它。

于是 `controller_lights` 的 1px 细线在 mip LOD 上升后被与透明邻居平均,alpha 掉到
`ALPHA_CUTOFF = 0.1` 以下即被 `discard`:细线先变稀、再整片消失,最终只剩底纹——与报告者
描述的"稍微远一点光效减少,最终像没通电"完全一致。斜视（俯视控制器地面）时 LOD 上升更快,
因此在很近的距离就能观察到。

### 为什么不是 "Animate Only Visible Textures"

该选项只在 `TextureMap.updateAnimations` 前按可见集合过滤 sprite
（`MixinTextureAtlas#getFilteredIterator` → `VisibleTextureIterator`）。标记侧
`GenericSectionSpriteTicker` 是 **region 级超集**:`recompute()` 汇总整个 region 内所有
section 的 `animatedSprites`,只要该 region 有任一可见 section 就标记全部 sprite——
控制器可见时它的 sprite 每帧都会被标记,动画从未停止更新,停掉的是像素。

## 修复

1. **`CUTOUT` 材质声明为非 mipped**（`VintageRenderPassConfigurationBuilder`）:
   合并通道里 `CUTOUT` 与 `CUTOUT_MIPPED` 仍共用 `cutout_mipped` pass（不增加 draw pass,
   `ChunkBuildBuffers.get(material)` 按 pass 取 builder）,但 `CUTOUT` 的 `Material`
   `mipped = false`;不合并的分支里独立的 `cutout` pass 也按 `mipped = false` 构建。
2. **着色器消费 `useMip` 位**:`chunk_material.glsl` 新增 `_material_mip_bias()`,把该位换算成
   `-4`（非 mipped）或 `0` 的 mip 偏置;`block_layer_opaque.vsh` 原样传递 `v_MaterialMipBias`,
   `block_layer_opaque.fsh` 的三个采样路径都折叠这个偏置。顶点格式未变
   （`MaterialParameters` bit 0 本来就在编码里）。`LEGACY`/GLSL 120 的 downlevel 分支没有
   `textureLod`,保持两参数 `texture()`,避免老驱动编译失败。

### 影响面

- `CUTOUT` 层（花、火把、AE2 控制器等 1px 细线贴图）恢复 vanilla 的 NEAREST、无 mip 采样,
  远处不再被 alpha test 蚀掉;近处观感不变。
- `CUTOUT_MIPPED`、`SOLID`、`TRANSLUCENT` 的采样行为不变。
- `performance.useRenderPassConsolidation` 不再影响 `CUTOUT` 的采样行为。

## 验证

`./gradlew check --no-daemon` 通过（含 `ShaderConditionalBalanceTest`、
`verifyDistributedJar`、`verifyModuleBoundaries`）。

dev 实机（`./gradlew runClient`,AE2UEL 0.56.6 在依赖集中）:

- 大型已供电控制器,从近走到远:彩色循环在任何距离都不再变淡或消失。
- `Render Pass 合并` 开/关、mipmap 等级 0 与 4 各验证一次,行为一致。
- `CUTOUT_MIPPED`（树叶/草）mip 平滑不变;`CUTOUT` 方块远处比修复前"脆"一点,
  属 vanilla 原样,未见明显闪烁。
- 用户实机确认修复。

## 残余限制

- 启用光影包时地形走 Iris/pack 内 shader,材质位不被包内 GLSL 消费,`CUTOUT` 仍按 mip 采样;
  原版 Sodium + 光影同样是该行为。若要覆盖,需要在 Iris 核心 shader 模板加同样的 LOD0 分支,
  影响面大于本次修复,单独评估。
- GLSL 120 legacy 路径（`ShaderChunkRenderer` 的 downlevel 分支）保持修复前的采样行为,
  仅影响不支持 GL 3.3 的驱动。