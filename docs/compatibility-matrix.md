# Actinium 兼容性矩阵

最后更新：2026-10-02。

状态定义：`已验证` 表示在记录的版本和场景中通过；`部分` 表示能运行但存在已知缺口；
`无法启用` 表示光影包不能成功开启；`未验证` 不代表不兼容。更新记录时必须填写 Actinium commit、
光影/模组版本和测试环境。

本轮验证环境：Actinium `30c7ffb`、Java 25.0.3、Cleanroom 0.5.12-alpha、Distant Horizons 3.1.2-b、
Windows 10、NVIDIA GeForce RTX 5070 Laptop GPU（驱动 610.74）。

> 2026-10-02 追加：ScalingGUIs 1.12.2-1.0.3.1 与 RSO 页 GUI Scale 滑块的冲突已做兼容——检测到该
> mod 时，Actinium 将滑块替换为复用其 "GUI Scales" 文本的无背景按钮，并直接打开其配置 GUI；未加载时
> 维持原滑块。实机验证待用户确认，详见 [docs/compat/scalingguis.md](compat/scalingguis.md)。

> 2026-09-30 追加：Immersive Engineering 0.12-98 Garden Cloche 在 shader 下玻璃不透明（issue #197）已修复；
> 用户确认 Solas Shader V3.7b 下玻璃恢复半透明。根因是 `ProgramId.BlockTrans` 默认强制关闭 blend，
> 锁住 GLSM 后延迟了方块实体自身的标准 alpha blend 请求；移除该默认值后，shader pack 显式 blend directive
> 仍优先。ITT 3.2 与 Photon v1.3b 仍受其 1.12.2 block shader fallback 限制，见
> [docs/compat/immersiveengineering.md](compat/immersiveengineering.md)。验证基于分支
> `fix/ie-cloche-transparency`（`c318ed54`）。

> 2026-09-29 追加：LittleTiles 1.5.87 在 shader 下与普通整块光照不一致（issue #193）已由用户在
> Photon v1.3b dev 环境确认修复；验证场景含相邻原版橡木木板、ArchitectureCraft shape 与 LittleTiles
> 方块。根因、quad 光照拆分及缓存期间保留 shader block context 的细节见
> [docs/compat/littletiles.md](compat/littletiles.md)；ArchitectureCraft 1.12-3.108 的同类路径见
> [docs/compat/architecturecraft.md](compat/architecturecraft.md)。原 issue 也报告 Solas 与其他 shader
> pack，跨包回归尚未完成，因此两项只记录本次已测场景。

> 2026-09-24 追加：Better Biome Blend Continued 1.2.0 + RLFoliage 2.5.3（issue #162）区块构建线程
> 崩溃已修复——BBB 在 `BiomeColorHelper#getGrassColorAtPos` 等入口按 chunk 预计算混合颜色，并遍历该
> chunk 的 3×3 chunk 邻域读取 biome；构建 origin 邻居 chunk 时这一遍历会伸到快照（origin chunk ±1）
> 之外，而 `WorldSlice#getBiome(BlockPos)` 把相对 chunk 索引直接当数组下标，chunk 618 相对基准
> 619 的 z 偏移 −1 遂变成 `sections[-4]`（`Index -4 out of bounds for length 64`）。修复为把 biome
> 查询抽成 `BiomeLookup`：越界坐标夹到快照边缘（最近处的 biome 优于固定 PLAINS 兜底），未持有快照或
> 缺失数据返回 PLAINS，坐标以 long 做差避免极端坐标回绕。回归测试与红-绿验证通过，实机验证待用户
> 确认，详见 [docs/compat/betterbiomeblend.md](compat/betterbiomeblend.md)。
> 
> 2026-09-25 追加：ReplayMod 1.12.2-2.6.13 使用 BSL_v10.1p1 导出视频时崩溃已修复并由用户实机确认——
> ReplayMod 在视频捕获时取消 `RenderGlobal.drawSelectionBox`，旧 outline HEAD/RETURN 注入因此漏掉清理；
> PBO 捕获调用的 `glReadPixels(..., long)` 也缺 GLSM 路由。修复细节及 Cleanroom 实测环境见下方
> [ReplayMod 行](#模组与环境)和 [docs/compat/replaymod.md](compat/replaymod.md)。

> 2026-09-23 追加：GalaxySpace + AsmodeusCore 1.0.4 夜晚自定义天空显示错误（issue #164）的修复——
> 见下方 [模组与环境](#模组与环境) 的 GalaxySpace 行与
> [docs/compat/galaxyspace.md](compat/galaxyspace.md)。根因在 glsm 的颜色缓存语义：GS 的
> `SkyProviderBase#render` 用 `glColor3f(skyColor - playerY/400)` 画天球，夜晚该值为负，而
> GL 规范要求 `glColor*` 在 API 边界把分量钳到 [0,1]（原版即得黑色），`changeColor` 却把负值
> 原样缓存，FFP 的 `sanitizeUniformColor` 遂把合法夜色误判为 `clearCurrentColor` 的 dirty
> sentinel 而上传不透明白色，夜空整体发白、银河贴图呈灰块。修复为 `changeColor` 入口统一
> `Color4.clamp01`（sentinel 直写缓存不经过该路径，既有契约测试不变），
> `./gradlew check` 通过，用户实机确认夜晚天空恢复正常、白天与 JourneyMap/GUI 无回归。

> 2026-09-23 追加：LagGoggles 5.9 + TickCentral 3.2（issue #166）共存启动崩溃已修复——其
> `RenderManagerTransformer` 把 `RenderManager.renderEntity` 的方法体搬进 `laggoggles_trueRender`、
> 只在原方法留下转发桩，`RenderManagerIrisMixin` 原有 `@Redirect` 的调用点因此消失，`require = 1`
> 校验失败让 `RenderManager` 类变换整体失败（下游表现为 ContentTweaker `NoClassDefFoundError` 与
> 启动崩溃）；修复为把实体上下文 hook 的锚点从调用点移到方法入口（`@WrapMethod` 包裹
> `renderEntity` / `renderMultipass`），并删除同样依赖调用点的透传 `@Redirect`。字节码锚点契约测试
> （复刻 TickCentral 搬迁变换）与 `./gradlew check` 通过，实机验证待用户确认，详见
> [docs/compat/laggoggles.md](compat/laggoggles.md)。

> 2026-09-22 追加：Xaero's Minimap 26.5.1 / World Map 1.46.0 / XaeroLib 1.7.3（issue #175）地图地形
> 渲染成 64×64 纯色方块的修复——见下方 [模组与环境](#模组与环境) 的 Xaero 行与
> [docs/compat/xaero.md](compat/xaero.md)。Xaero 用 `POSITION_TEX_TEX_TEX`（每个顶点 4 组 UV，分别
> 对应 legacy texture unit 0..3）配合 unit 0/2/3 的固定管线 `GL_COMBINE` 绘制地形，而 Actinium 的
> BufferBuilder 顶点格式映射把 UV `index`≥2 解析为 `-1`（只有 unit 0/1 有属性槽），unit 2/3 的属性槽
> 既不 enable 也不下发指针，FFP 顶点着色器遂退回逐次绘制常量 `u_CurrentTexCoord2/3`（Xaero 从不设置，
> 即 (0,0,0,1)），每张 64×64 区块贴图只采到一个纹素。修复为 UV 元素统一走
> `Usage.uvAttributeLocation`（0→2、1→3、2→5、3→6），与顶点着色器声明的槽位同表；无槽单位改为
> 一次性告警而非静默丢弃。dev 实机确认小地图与世界地图地形恢复细节（用户截图对比）。
> 2026-09-22 追加：同一 issue 下世界地图图标按钮的白底也已修复——Xaero 的
> `RadarRenderer#postRender`（仅「实体雷达」启用时注册，世界地图复用小地图的元素渲染器）关闭 alpha test
> 且不恢复；而 vanilla `FontRenderer.drawString` 第一行的 `enableAlpha()`
> （`FontRenderer.java:235`，全类唯一的 alpha/blend 调用，且无任何 `disableAlpha`）从不恢复，原版因此
> 总能把该泄漏顺手盖回去。Actinium 的 `BatchingFontRenderer` 忠实恢复 alpha test，抹掉了这个副作用，
> 泄漏遂第一次生效，令 `GuiTexturedButton`（覆写 `drawButton`，不像 vanilla `GuiButton` 那样自己
> `enableBlend()`）在 `alphaTest=false` + `blend=false` 下把图集透明像素写成不透明白底。修复为让批量
> 字体渲染收尾保持 alpha test 启用、对齐 vanilla 的净效果。用户实机确认，详见
> [docs/compat/xaero.md](compat/xaero.md) 的「根因」小节。

> 2026-09-19 追加：CensoredASM / Chibi 5.33（issue #159）共存启动崩溃已修复——其
> on-demand animated textures 在 `TextureMap.updateAnimations` 与 `BufferBuilder.tex` 上
> 与 Actinium 双重 overwrite（同优先级注入冲突导致 `TextureMap` 类变换失败），其
> `squashBakedQuads` 又会改写 `MixinBakedQuad` 所 `@Shadow` 的 `BakedQuad` 字段。修复为提供
> `org.taumc.celeritas.core.CeleritasLoadingPlugin` 探测标记类，让 LoliASM 走它自带的
> Celeritas 让位路径（两组特性一起关闭）；dev 实机验证通过，详见
> [docs/compat/censoredasm.md](compat/censoredasm.md)。

> 2026-09-18 追加：Distant Horizons 3.3.0-1.12.2（`maven.modrinth:uCdwusMi:Sa0ttGJr`，Actinium
> `6e66a3c7`）实机回归通过——六包光影 + DH LOD、无光影 LOD/雾色/天空盒、进出世界/维度切换
> （用户实机确认，详见 [docs/compat/dh.md](compat/dh.md)）。

> 2026-08-31 追加：Photon v1.3b 水面不生效（水面保持原版贴图、仅余微弱反光）的修复——
> 根因不在水面渲染路径，而在 block.properties 的版本条件求值：Photon 把全部 modern 方块映射
> 放在 `#if MC_VERSION >= 11300` 段内，`#else`（1.12 段）为空；`IdMap#hasLegacySection` 此前
> 只要见到 MC_VERSION 条件的 `#else` 分支就判定包内含 legacy 段，于是以真实 MC_VERSION=11202
> 交给 jcpp 求值，modern 映射段被整体剔除，方块 ID 映射表为空（`block-meta-map present=false`、
> 全部方块 `shaderBlockId=-1`），`mc_Entity` 全部失效，水面因此不被识别为 `MATERIAL_WATER`。
> 修复后 `#else` 分支必须含实际映射行才判 legacy 段（空 fallback 的包如 Photon 继续走
> MC_VERSION=260101 的 modern 伪装路径 + legacy 名展平）；Complementary 的 `#elif >= 10800`
> 实质段路径不受影响。验证：dev 运行，`block-meta-map present=true`、水面采样
> `blockId=10001`（water 与 flowing_water 双映射），水面效果正常（用户实机确认）。

> 2026-08-24 追加：改 mipmap 后地形方块概率性消失（无光影与光影下均出现）的修复——见下方
> [mipmap 与地形渲染](#mipmap-与地形渲染)。根因有两点：(1) terrain shader 用三参数 `texture()`
> 按片元偏导选 mip 层，mip 级别改变后 UV 接缝处偏导取到未定义层返回 alpha≈0，被 cutout 的
> alpha 测试裁掉；(2) 地形材质 `mipped` 位硬编码为 true，mipmap 关闭时仍请求 mip 采样。修复后
> 采样改为由 GL 过滤状态决定 mip 层，材质位随实时 mipmap 级别生成，并与 atlas 过滤状态一致。
> 验证：RSO 界面反复切换 mipmap 0↔4 共 10 次，无方块消失（commit `64b9c32`、`f94dfa6`、
> `43aefae`，dev 运行）。

> 2026-08-16 追加：VoxelMap 1.9.25（分支 `fix/voxelmap-minimap-black`）小地图黑屏修复验证——见下方
> [模组与环境](#模组与环境) 的 VoxelMap 行与 [docs/compat/voxelmap.md](compat/voxelmap.md)。

> 2026-08-18 追加：Depths Update（issue #68）扩展世界高度（默认 -64..320，可配 -256..512）下
> Y 范围 0-255 之外方块不渲染的修复——见下方 [模组与环境](#模组与环境) 的 Depths Update 行。
> 根因是渲染器硬编码 0-255 的 section 范围，且 Depths 自带的 celeritas 兼容 mixin 指向
> 重构前的 `org.taumc.celeritas.impl.*` 类路径而不生效（该类路径已随 2026-09-14 兼容桥移除
> 彻底删除）；修复改为从 Depths 公开 API
> （`DepthsUpdateAPI.getHeightInfo`）推导 section 范围，并按其 storage 布局映射读取（commit
> `6d8fc24`，dev 实测 Y<0 与 Y>255 区域正常渲染）。
> 
> 2026-08-18 追加：EnderIO CEu 5.4.2 流体罐在光影开启时罐内液体不渲染的修复（#58）——见下方
> [模组与环境](#模组与环境) 的 EnderIO 行。根因是 Iris celeritas 地形接口对所有 terrain pass
> 无条件强制 `glDepthMask(true)`，translucent 层里的罐体玻璃窗因此写出深度，遮挡了其后绘制的
> TESR 液体；修复后 translucent terrain pass 在主 pass 不再写深度（与 vanilla 语义一致），
> 阴影图 pass 与不透明 pass 保持写深度。

> 2026-09-14 追加：Celeritas 兼容桥（`celeritas` mod id 与 `org.taumc.celeritas` API 镜像）已
> 整体移除，Celeritas 系 addon 改为直接适配 Actinium 主实现（renderer 绑定面由
> `VintageBlockRendererBindingContractTest` 锁定）。已知回归：外部已发布的 **celeritas-extra**
> 在 mcmod.info 硬依赖 `celeritas` mod id，将拒绝加载，需其作者发布 Actinium 适配版；
> celeritas-dynamic-lights / celeritasleafculling 的已发布版本失去选项页与 renderer 增强，
> 但其 vanilla 注入的核心逻辑仍生效，二者本地源码的 Actinium 适配方案已立项。
>
> 2026-09-02 追加：上述修复曾被 #85（`da83c59`）回潮——该提交把 translucent terrain pass
> 翻转为写深度，依据的"vanilla 半透明阶段保持写深度"前提不实：vanilla 1.12.2 将整个
> translucent 阶段（半透明地形 + pass-1 方块实体重绘）包在 `depthMask(false)` 内
> （`EntityRenderer` 约 1539/1564 行），储罐玻璃先写深度便遮挡了其后渲染的流体 TESR，
> 有无光影均不显示。修复为 translucent pass 恢复不写深度
> （`VintageRenderPassConfigurationBuilder`），水 pass 保持写深度；光影路径由
> Iris celeritas 接口对 translucent 语义 pass 统一不写深度兜底。无光影/光影双路径、
> 水与玻璃叠层、mipmap 切换均回归通过。
> 
> 2026-08-18 追加：Snow! Real Magic! 0.7.4（issue #35）带雪栅栏不渲染的修复——见下方
> [模组与环境](#模组与环境) 的 Snow! Real Magic! 行。根因是 SRM 把被雪覆盖的方块替换为携带
> `SnowTile` 的 `snow_layer`，并只在 `BlockRendererDispatcher.renderBlock` 内重绘被覆盖方块，
> 而 Actinium 的快速区块渲染路径直接走 baked model、从不调用该入口；修复让 SRM 的
> `snow_layer` 块退回 vanilla dispatcher 路径（`6aee395`，dev 运行验证通过）。

> 2026-08-20 追加：TC4 Research Port: Reborn（issue #74）旧版 Tessellator 的 GUI 卡死修复——见下方
> [模组与环境](#模组与环境) 的 TC4 Research Port: Reborn 行与
> [docs/compat/oldresearch.md](compat/oldresearch.md)。dev 人工回归确认研究笔记 GUI 不再卡死，
> 优化后约 500+ FPS，与背包界面同量级；研究树视觉回归仍待补充。

> 2026-08-29 追加：HBM's Nuclear Tech - Community Edition 2.5.0.5 的机器黑色剪影、
> lightmap 和深度状态异常修复——见下方 [模组与环境](#模组与环境) 的 HBM 行。
> dev 实际场景回归覆盖 FENSU 与其他 HBM 机器，模型不再显示为黑色剪影。

> 2026-09-02 追加：BetterPortals Refitted 0.4.1 末地传送门无看穿效果（洞口只见星野贴图）+
> 视觉位置低约一格 + 修复后星野被拉成竖直条纹的修复——见下方 [模组与环境](#模组与环境) 的
> BetterPortals Refitted 行与 [docs/compat/betterportals.md](compat/betterportals.md)。
> 三层根因叠加：compat shader 预处理器条件求值 bug 致 `render_portal` 的 `sampler` 失活；
> `TileEntityEndPortalRendererIrisMixin` 无条件劫持使 BPR 合成 TE 的星野叠加失去原版
> blend 钩子语义；glsm texgen 顶点着色器的逐分量写入模式被 NVIDIA 驱动 DCE 掉
> `u_TexGenEyePlaneS`。

> 2026-09-07 追加：Gnetum 1.4.3 HUD 分帧缓存与 Actinium 共存时半透明 HUD 元素（聊天背景、
> 字幕、BossBar 等）透明度错误并随缓存 pass 轮转隔帧闪烁的修复——见下方 [模组与环境](#模组与环境)
> 的 Gnetum 行与 [docs/compat/gnetum.md](compat/gnetum.md)。根因与 StellarCore HudCaching
> 同族：glsm 重定向架空了 Gnetum 挂在 vanilla `GlStateManager.blendFunc` /
> `OpenGlHelper.glBlendFunc` 上的预乘 alpha 覆盖钩子，缓存 FBO 写入直 alpha 而 blit 按预乘
> 合成；修复为 `GnetumHudCachingCompatTransformer` 把 `Gnetum.rendering` 窗口镜像到
> `GLSMConfig.hudCacheOverride`（commit `5d820b0e`，生产实机回归已确认）。
>
> 2026-09-07 再追加：Gnetum 与 Revo UI 共存时打开/关闭 GUI 背景渐变随缓存 pass 轮转
> 闪烁——Gnetum 把 uie 的 `RenderGameOverlayEvent.Post` 监听器收入分帧缓存，Actinium 的
> 渐变 defer/replay 管线因此被降为 1/3 帧率；修复为缓存捕捉窗口内
> （`hudCacheOverride`）渐变直接画入缓存 FBO 而非 defer（commit `edf7c6f2`，复刻无
> Actinium 时的已知良好路径），build 全绿、生产实机回归待确认。注：Gnetum 的 modid
> 解析依赖 legacy Forge `ASMEventHandler.toString()` 前缀，在 Cleanroom 下整体失效
> （全部落入 `gnetum_unknown` 桶），按 modid 排除的方案不可行。

> 2026-09-21 追加：HBM's Nuclear Tech - Community Edition 2.5.0.5 第一人称手持 Sedna 系武器
> （SPAS-12、M2、Uzi 等）时画面深度崩坏——见下方 [模组与环境](#模组与环境) 的 HBM 行与
> [docs/compat/hbm.md](compat/hbm.md)。根因为 HBM 只认 OptiFine 的光影探测
> （`net.optifine.shaders.Shaders`，Actinium 不提供故恒为 false），于是在 Iris 的手部 pass
> 内走"无光影"分支清空主 framebuffer 深度，破坏 `depthtex0`;修复为把该探测接到 Actinium
> 自己的光影状态，并用 `HandRenderer.DEPTH`（0.125）的投影压缩替代 OptiFine 的
> `applyHandDepth`。Complementary Reimagined r5.5.1 + Distant Horizons 实测确认。

> 2026-09-21 追加：NTM-Space 0.9.2 的 Stardar（系统地图）GUI 一打开即崩溃的修复
> （issue #170，`Unsupported HBM RenderUtil attribute bits: 0x4`）——见下方
> [模组与环境](#模组与环境) 的 HBM 行与 [docs/compat/hbm.md](compat/hbm.md)。
> `HbmRenderStateCompat.toGlMask` 将未映射的 HBM attribute 位按 HBM 原有语义忽略；
> 星图实机确认待补充。

> 2026-09-22 追加：MMCE 使用的方块隐藏（Component Model Hider 1.0，modid
> `component_model_hider`）在 Actinium 下整体失效的修复——见下方
> [模组与环境](#模组与环境) 的 Component Model Hider 行与
> [docs/compat/component-model-hider.md](compat/component-model-hider.md)。该模组把隐藏实现挂在
> `RenderChunk.rebuildChunk` 上（`@Redirect` 掉 `BlockRendererDispatcher.renderBlock` 并在其前后
> 置位 `MultiblockWorldSavedData.isBuildingChunk`），而 Actinium 的区块网格由
> `ChunkBuilderMeshingTask` 生成、从不调用该方法，于是 `isBuildingChunk` 永不置位、
> `isModelDisabled` 恒为 false：隐藏方块照旧入网格，其邻面 redirect 与 CCL 钩子也全部空转。
> 另外 1.12.2 的 `Block.doesSideBlockRendering` 并不存在（该模组的 `BlockVisitor` 在 1.12.2
> 静默跳过），邻面规则只由 `BlockModelRenderer` 系列 redirect 承担，而 Actinium 快速路径自己
> 调用 `shouldSideBeRendered`、不经过它——因此修复分两半：网格构建期置位 `isBuildingChunk`，
> 隐藏位置跳过模型渲染，并由 `VintageBlockRenderer` 补上"邻格隐藏则该面仍绘制"的规则；
> 隐藏方块仍照原版语义参与 section 可见性（原版 `VisGraph.setOpaqueCube` 本就在 redirect 之外）。
> TESR 那一半本就幸存（其 `getRenderer` 注入不经门控）。实机验证待做。

## 光影包

| 光影包                                | 版本            | 状态   | 已验证范围                                                          | 已知缺口      | Actinium 基线 |
|------------------------------------|---------------|------|----------------------------------------------------------------|-----------|-------------|
| MakeUp Ultra Fast                  | 9.1f          | 已验证  | 世界加载、维度切换、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载、Distant Horizons LOD | -         | `f261611`   |
| BSL                                | 10.0          | 已验证  | 世界加载、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载、Distant Horizons LOD      | -         | `f261611`   |
| Complementary Reimagined / Unbound | r5.5.1        | 已验证  | 开启、世界渲染、Distant Horizons LOD、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载   | -         | `f261611`   |
| Bliss                              | 2.1.2         | 已验证  | 开启、世界渲染、Distant Horizons LOD、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载   | -         | `28d976d`   |
| iterationT                         | 3.2.0         | 部分    | 开启、世界渲染、Distant Horizons LOD、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载   | Immersive Engineering Garden Cloche 玻璃泛白；该包 block shader 将输出 alpha 固定为 1 | `fix/ie-cloche-transparency` (`c318ed54` 基线) |
| iterationRP                        | 0.7.7 / 0.8.7 | 已验证  | 开启、世界渲染、Distant Horizons LOD、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载   | -         | `28d976d`   |
| Photon                             | v1.3b         | 部分    | 开启、世界渲染、地形、水（2026-08-31 水面修复后）、GUI、LittleTiles/ArchitectureCraft 相邻方块光照（issue #193，2026-09-29） | IE Garden Cloche 玻璃发黑：1.12.2 配置禁用专用 `gbuffers_block_translucent` 程序并回退 solid block shader；阴影/实体/维度切换/重载等场景待补充验证；选项菜单部分元素缺失（GTAO 等 profile 项告警，与水面无关） | `fix/ie-cloche-transparency` (`c318ed54` 基线) |
| Solas Shader                      | V3.7b         | 部分    | Immersive Engineering 0.12-98 Garden Cloche（泥土 + 种子）玻璃半透明，issue #197，用户确认（2026-09-30） | 其他渲染路径未验证 | `fix/ie-cloche-transparency` (`c318ed54` 基线) |
| SEUS PTGI HRR                      | Test 2.1      | 无法启用 | -                                                              | 光影包不能成功开启 | `f261611`   |

## 模组与环境

| 组件               | 状态   | 接入方式                               | 备注               |
|------------------|------|------------------------------------|------------------|
| Cleanroom Loader | 必需   | Forge/Cleanroom 启动与 MixinBootstrap | 当前目标运行环境         |
| Immersive Engineering | 部分（Solas V3.7b 场景已验证） | 无 Mixin（Iris `BlockTrans` 默认 blend 状态修正） | 0.12-98：Garden Cloche 玻璃不透明（issue #197）已由用户在 Solas V3.7b 下确认修复；ITT 3.2 与 Photon v1.3b 因各自 1.12.2 shader fallback 仍呈现泛白/发黑，详见 [docs/compat/immersiveengineering.md](compat/immersiveengineering.md) |
| LittleTiles      | 部分（Photon v1.3b 实测通过） | 条件 Mixin（shader quad context + 独立 AO 顶点通道） | 1.5.87 + CreativeCore 1.10.71（issue #193）：放置的微型方块在光影下比相邻原版方块暗；已修复并经用户确认，Solas 与其他 shader pack 待回归，详见 [docs/compat/littletiles.md](compat/littletiles.md) |
| ArchitectureCraft | 部分（Photon v1.3b 实测通过） | 条件 Mixin（`RenderTargetWorld#setLight` 的 AO / 方向着色拆分） | 1.12-3.108：用户发现放置 shape 有同类偏暗现象；与原版木板、LittleTiles 同场验证，其他 shader pack 待回归，详见 [docs/compat/architecturecraft.md](compat/architecturecraft.md) |
| ReplayMod        | 已验证（视频渲染） | 高优先级 selection-box outline scope + GLSM PBO offset readback | 1.12.2-2.6.13 在 BSL_v10.1p1 视频导出时的 G-buffer 状态崩溃及 PBO `glReadPixels(..., long)` 缺失重载已修复；Cleanroom 0.6.13-alpha 整合包实测确认，详见 [docs/compat/replaymod.md](compat/replaymod.md) |
| Celeritas        | 内嵌   | Gradle 子项目、最终 Jar 合并               | Actinium 的区块渲染器  |
| GLSM             | 内嵌   | Gradle 子项目、service provider        | 管理 GL 状态和固定管线兼容  |
| GTNHLib          | 内嵌   | Gradle 子项目、bridge API              | 提供底层渲染与内存工具      |
| Distant Horizons | 部分   | DH 公开 API + Iris LOD override programs（不注入 DH）   | 版本变化敏感，必须按指定版本验证；`IIrisAccessor` 注册与延迟透明 LOD 开关均由 DH 持有（上游 `b15b57cf` 起），搭配更早版本的 DH 会缺失光影 LOD 集成；3.3.0-1.12.2 实机回归通过（2026-09-18，见 [docs/compat/dh.md](compat/dh.md)） |
| Lumenized        | 已验证（启动） | 条件 Mixin（bloom 兼容层，类探测门控 `class:gregtech.client.utils.BloomEffectUtil`） | 1.0.3：bloom 兼容层使其泛光真实生效（depth 共享 + FBO 清理 + composite 深度测试 + GL 状态守护，取代已移除的 bloomStyle=0 safe mode）；第一人称手部/所持物品全黑已由 `BloomStateGuard` 修复并实机确认（真因为 Unreal 管线对 2..4 号纹理单元的 TEXTURE_2D 使能泄漏，守护覆盖全部纹理单元），详见 [docs/compat/lumenized.md](compat/lumenized.md) |
| StellarCore      | 已验证  | 无（不再需要配置规避） | HUD 缓存相关 GUI/HUD 症状实为 Draconic Evolution 引起（2026-08-12 实测归因修正）；DE 兼容桥修复后 HUD 正常，`HudCaching`/`HUDFramebuffer` 可恢复开启，详见 [docs/compat/stellarcore.md](compat/stellarcore.md) |
| Draconic Evolution | 已验证 | 条件 Mixin（CCL GlStateTracker 兼容桥） | DE 2.3.28.354 在场时云异常/草方块侧面偏绿/主菜单消失；根因为 DE 每帧 HUD 经 CCL GlStateTracker 基于冻结的原版 GlStateManager 字段重置 GL 状态，已由 `mixins.actinium.ccl.json` 兼容桥修复（dev 回归通过，生产整合包全量回归待做），详见 [docs/compat/draconic-evolution.md](compat/draconic-evolution.md) |
| ScalingGUIs | 待实机验证 | `Mods.SCALINGGUIS` 门控 + RSO 外部按钮 | 1.12.2-1.0.3.1：RSO 页的 GUI Scale 滑块替换为打开其配置 GUI 的无背景按钮；未加载时保留原滑块，详见 [docs/compat/scalingguis.md](compat/scalingguis.md) |
| Fluidlogged API  | 代码支持 | compile-only API、条件调用              | 尚缺当前运行时验证记录      |
| Gibbed           | 代码支持 | late Mixin、模型批处理路径                 | 尚缺当前运行时验证记录      |
| Chunk Animator   | 部分 | 条件桥（ChunkAnimationProvider）+ 动画 section 单独绘制 | 1.12.2-1.2.1（236484:3850023）dev 运行通过（coremod 加载、兼容层启用、进世界无异常）；动画视觉确认待补，详见 [docs/compat/chunkanimator.md](compat/chunkanimator.md) |
| VoxelMap         | 部分 | 条件 Mixin（CPU 纹理路径 + 线性过滤 + scissor 重路由 + HudCaching alpha 保护） | 1.9.25 小地图黑屏/黑块已修复（dev 验证圆内正常显示地图内容、HUD 不被缓存隐藏，见 [docs/compat/voxelmap.md](compat/voxelmap.md)）；已知缺口：与 StellarCore `HudCaching` 组合时小地图圆周仍可能残留黑块（VoxelMap 全屏清 alpha + DST_ALPHA 混合与 HUD 缓存 FBO 的第三方冲突，`HudCaching=false` 即消失，非本模组缺陷）；验证 VoxelMap 需停用 JourneyMap（二者频道冲突） |
| ModernUI         | 代码支持 | GUI scale hook                     | 尚缺当前运行时验证记录      |
| Mekanism-CE-Unofficial | 部分 | 条件 Mixin（Mekanism glow pass 的 deferred alpha blend 与 Iris blend lock 桥接） | 10.0.5.021（CurseForge `840735:8862568`）：MakeUp UltraFast 9.1f 下大型化学清洗机贴图已恢复，详见 [docs/compat/mekanism.md](compat/mekanism.md)；其他光影包及手持模型回归待验证 |
| HBM's Nuclear Tech - Community Edition | 已验证 | 条件 Mixin（RenderUtil 状态栈 + Sedna 武器手部深度）+ early Mixin（TileEntityRendererDispatcher 世界 lightmap 同步，注入体按 `isHbmInstalled()` 门控） | 2.5.0.5（CurseForge 1312314:8330665）：FENSU 与其他 HBM 机器的 WaveFront raw VAO 模型在实际场景中正常显示；修复前的 stale lightmap、黑色剪影和 depth 恢复异常不再复现；第一人称手持 Sedna 系武器在光影下清空世界深度导致的深度崩坏已修复（2026-09-21，Complementary Reimagined r5.5.1 + Distant Horizons 实测确认，详见 [docs/compat/hbm.md](compat/hbm.md)）；Java 25.0.3、Cleanroom 0.6.12-alpha dev 回归通过；2.6.1.0 + NTM-Space 0.9.2：issue #170 的 Stardar GUI 未映射 attribute 位崩溃已修复，星图实机确认待补充（详见 [docs/compat/hbm.md](compat/hbm.md)） |
| Depths Update    | 已验证 | 兼容门控（`compat/depthsupdate`：公开 API 推导 section 范围 + storage 索引映射） | 1.0.0-a10：扩展世界高度（默认 -64..320）下 Y<0 与 Y>255 的方块不再缺失（渲染器原先硬编码 0-255）；dev 实测正常；无 Depths 时回退 vanilla 行为 |
| EnderIO CEu / EnderCore CEu | 已验证 | 无（核心渲染语义修复，非模组接入） | 5.4.2 + EnderCore 0.5.81：光影开启时流体罐内液体被罐体玻璃窗深度遮挡的问题已修复（`cb4feaa5`，translucent terrain pass 不再写深度）；MakeUp Ultra Fast 9.4c + Cleanroom 0.5.17-alpha 实测通过；2026-09-02 修复 #85（`da83c59`）引入的回潮——translucent pass 被错误翻转为写深度导致有无光影流体均被玻璃遮挡，已恢复 vanilla 深度语义，双路径实测通过 |
| Snow! Real Magic! | 已验证 | 兼容门控（SRM 的 snow_layer 块退回 vanilla dispatcher 路径） | 0.7.4：带雪栅栏不渲染已修复（SRM 把被覆盖方块替换为带 SnowTile 的雪层、仅在 `BlockRendererDispatcher.renderBlock` 内重绘，快速区块路径已绕过）；`6aee395`，dev 运行验证通过（MakeUp Ultra Fast 下无光影 + 光影各验一次） |
| TC4 Research Port: Reborn | 部分 | 条件 Mixin（Old Research Tessellator 转发到 streaming drawer） | 1.0.1-release（1632015:8642028）：已修复 splash 结束后 repack capacity 为 0 导致的 GUI Client thread 无限循环；dev 人工回归确认研究笔记 GUI 不再卡死，优化后约 500+ FPS，与背包界面同量级；研究树视觉回归待补，详见 [docs/compat/oldresearch.md](compat/oldresearch.md) |
| Modern Splash    | 部分 | 无侵入（替换类与 mixin 注入天然兼容）+ splash 字体 color=0 修复 | 1.5.3（629058:8487408）dev 运行通过（coremod 加载、mixin 注入保留、字体颜色按配置生效）；光影场景回归待做，详见 [docs/compat/modern-splash.md](compat/modern-splash.md) |
| Reese's Sodium Options（内嵌） | 代码支持 | 内嵌移植 UI（`me.flashyreese.mods.reeses_sodium_options`，MIT）+ embeddium 选项数据层（`dhj.embeddedt.embeddium.api.options.*` 自研扩展） | RSO 界面作为视频设置入口（`MixinGuiOptions` 拦截按钮 101，`enabled=false` 回退原版 `GuiVideoSettings`）；`net.caffeinemc` 设置界面与配置模型已整体删除；编译与 349 项单元测试通过，**运行期视觉对比待人工验证**，详见 [docs/rso-port.md](rso-port.md) |
| Extra Utilities 2 | 已验证 | `ModdedBlockRenderCompat` 在完整 block-render 生命周期内按 block 实例串行化 | `extrautils2@1.0`：Java 25 dev 客户端启动 10 个 chunk-builder worker，进入已有世界并触发区块重载后未复现 Issue #36 的 CME；代码提交 `44f4295`，详见 [docs/compat/extrautils2.md](compat/extrautils2.md) |
| AgriCraft | 部分 | `ModdedBlockRenderCompat` 使用共享 renderer 锁保护 crop 缓存 | 与 XU2 相同的异步第三方缓存访问模式已加入兼容层；dev 运行验证待补，详见 [docs/compat/extrautils2.md](compat/extrautils2.md) |
| Kirino Engine（Cleanroom 内建） | 部分（Headless） | early 配置 + `IMixinConfigPlugin` 门控，钉死 `isEnableRenderDelegate()` 为 false（`MixinKirinoConfigHub`） | Kirino Graphics 模式会整体替换 `EntityRenderer#renderWorld`，使 Actinium 全部渲染注入点失效；共存的唯一路径是 Kirino Headless 模式：本兼容层强制其渲染委托关闭、保留 ECS/分析运行时，Actinium 独掌渲染管线。Cleanroom 0.6.7-alpha（kirino epoch-1.a5）dev 运行通过（early 配置注册、headless installer、兼容层日志、渲染循环正常），详见 [docs/compat/kirino.md](compat/kirino.md) |
| Scannable | 已验证 | 条件 Mixin（接管 `ProxyOptiFine` 探针，扫描波走其 overlay 路径） | 1.6.3.26（266784:3146549）：使用扫描器后无光影透视 / 光影全白拖影的根因是其 INJECT 路径换装主 FBO 深度 attachment（Actinium 下 `Framebuffer.depthBuffer` 为 0，"恢复"即卸下深度）；已引导其走 OptiFine 式 overlay 渲染路径，详见 [docs/compat/scannable.md](compat/scannable.md)；dev 运行验证通过（无光影透视与光影全白均消失、扫描波区域正确；相邻结果合并为聚类大框为 Scannable 固有设计） |
| BetterPortals Refitted | 已验证 | `EndPortalRenderPolicy` 按调用来源分流（真实 TE 走替代渲染器；合成 TE 无光影走 glsm FFP/texgen、光影走替代渲染器并复刻 CONSTANT_ALPHA 淡出钩子）+ 管线按维度缓存消除看穿双 pass 的重载风暴 | 0.4.1：末地传送门看穿失效/星野条纹/光影卡顿地形消失/光影星野旁路均已修复（四层根因见 [docs/compat/betterportals.md](compat/betterportals.md)）；无光影与光影（BSL）场景看穿+星野+淡出+换维度均实机确认正常 |
| Chocolate Quest Repoured | 已验证 | 无侵入（glsm compat shader 转换器修复：6 个 parse-breaking 旧式采样函数 pre-parse 改名 + 语法错误 Fail Fast 兜底） | 2.8.0B：共存启动 preinit 崩溃（`Failed to compile shader: 0`）已修复，根因为 glsl-transformation-lib 文法将 textureCube 等词法化为关键字 token 且 ANTLR 静默恢复产出畸形 GLSL（issue #123，见 [docs/compat/chocolate-quest-repoured.md](compat/chocolate-quest-repoured.md)）；dev 实机验证通过（CQR 2.8.0B + geckolib 3.0.31 + ReachFix 1.1.3 共存启动到标题界面，SphereRenderer 着色器编译正常；进世界时 CQR 在 Server thread 重载纹理集亦通过（调试命名注入无 GL 上下文时跳过，见同文档次生问题一节） |
| Gnetum | 部分 | launchwrapper transformer（`GnetumHudCachingCompatTransformer` 镜像 `Gnetum.rendering` 窗口到 `GLSMConfig.hudCacheOverride`，复用 StellarCore HudCaching 的 GLSM 覆盖路径）+ revoui 渐变重定向在缓存窗口内改道（直接画入缓存 FBO） | 1.4.3（CurseForge 1220460 / Modrinth）：①HUD 分帧缓存致半透明 HUD 元素随 pass 轮转闪烁已修复并实机确认（根因同 StellarCore 模式 A：预乘覆盖钩子被 glsm 重定向架空），commit `5d820b0e`；②与 Revo UI 共存时 GUI 背景渐变随 pass 轮转闪烁已修复（缓存使渐变 defer 管线降为 1/3 帧率；修复为缓存窗口内直接画入缓存 FBO，commit `edf7c6f2`），build 全绿、生产实机回归待确认；已知缺口：手部缓存（`gnetum:minecraft_hand`，默认关）未适配、危险混合检测在 Actinium 下不生效、Gnetum 的 modid 解析在 Cleanroom 下整体失效（上游缺陷，见文档），详见 [docs/compat/gnetum.md](compat/gnetum.md) |
| CubicChunks | 部分 | 注入共存（天空距离兜底改为 `ModifyExpressionValue` 链式组合，无模组类引用） | 0.0.1301（292243:3546640）：共存启动 `EntityRenderer` invalid classes 崩溃已修复——其 vertviewdist `MixinEntityRenderer` 的 @Redirect 与 `EntityRendererIrisMixin` 竞争同一批 `GameSettings.renderDistanceChunks` 读取，冲突跳过叠加 `defaultRequire=1` 校验失败使类变换整体失败；改为 MEV + `require=0` 后两种应用顺序均不崩溃（Actinium 先应用时与 CC 值链式生效，CC 先应用时天空距离兜底让位于其垂直视距）；进世界渲染回归未验证（未 runClient） |
| GregTech CEu | 部分 | 注入容差（translucent 层 debug 标记放宽为 `require=0`）+ bloom 兼容层（与 Lumenized 共用 `mixins.actinium.lumenized.json`，类探测门控） | 2.8.10-beta（557242:5519022）：共存启动 `EntityRenderer` invalid classes 崩溃已修复并实机确认——`GregTechTransformer` 用 ASM 把 `renderWorldPass` 第 4 处 `renderBlockLayer`（TRANSLUCENT）替换为 `BloomEffectUtil.renderBloomBlockLayer`，ordinal=3 注入 0 命中触发 require 校验失败；bloom 兼容层对 GTCEu 生效（同包同名 bloom 类，`renderBloomInternal` 拆分结构已适配），泛光下第一人称手部/所持物品全黑已由 `BloomStateGuard` 修复并实机确认（真因为 Unreal 管线对 2..4 号纹理单元的 TEXTURE_2D 使能泄漏，守护覆盖全部纹理单元），详见 [docs/compat/lumenized.md](compat/lumenized.md)；其对 RenderChunk/RegionRenderCacheBuilder 等的其余 ASM 改写未审计 |
| Obscure Tooltips | 已验证 | 条件 Mixin（`mixins.actinium.obscuretooltips.json`：tooltip 盔甲预览的实体渲染包裹进 GUI entity surface，Iris 盔甲 item ID/glint 钩子在该 surface 内跳过） | 3.10.2（CurseForge 715660:8522661）：tooltip 内渲染盔甲架实体时 Iris 盔甲钩子把世界渲染 GBuffer/item ID 状态带进 GUI pass，可产生全屏黑罩（PR #142）；修复仅在该 surface 激活期间跳过盔甲钩子，世界盔甲渲染不变；dev 实机回归通过（光影下悬停盔甲 tooltip 无黑罩，世界盔甲渲染正常） |
| CensoredASM / Chibi（LoliASM） | 已验证（dev） | 无侵入（提供 `org.taumc.celeritas.core.CeleritasLoadingPlugin` 探测标记类，触发 LoliASM 自带的 Celeritas 让位路径） | 5.33（CurseForge 460609:8225778，issue #159）：共存启动崩溃已修复——其 on-demand animated textures 与 Actinium 在 `TextureMap.updateAnimations` / `BufferBuilder.tex` 上双重 overwrite，`squashBakedQuads` 亦与 `MixinBakedQuad` 的 `@Shadow` 字段冲突；LoliASM 本就会在探测到 Celeritas 系时关闭两者，Actinium 移除 Celeritas 桥后该探测失效。dev 实机验证：LoliASM 两条让位日志出现、无 Mixin 失败、进世界正常；生产整合包回归待用户确认，详见 [docs/compat/censoredasm.md](compat/censoredasm.md) |
| NeverEnoughAnimation | 已验证 | 顶点 alpha 覆写扩展点（`ItemVertexAlphaOverrides`：外部缩放激活时，快速物品路径跳过 raw append 与 display list 缓存，改走 `renderQuads`） | 1.0.7（CurseForge 1062347:7289408，issue #145）：GUI 开/关淡入的顶点 alpha 被 display list 缓存烘焙成永久透明（raw append 分支则整条丢弃该缩放），导致箱子／背包 GUI 物品不可见；dev 实机回归通过（物品随 GUI 淡入并最终完全可见，背包与世界物品无回归）。附带归因记录：NEA 的 dev-only `drawScreenDebug` 会在 `BackgroundDrawnEvent` 留下标准物品光照，使 `GuiChest` 面板变暗，属上游调试代码缺陷，详见 [docs/compat/neverenoughanimation.md](compat/neverenoughanimation.md) |
| Xaero's Minimap / World Map / XaeroLib | 部分 | 无 Mixin（glsm 顶点格式映射修复：UV 元素按 legacy texture unit 分配属性槽） | 26.5.1 / 1.46.0 / 1.7.3（issue #175）：两张地图的地形渲染成 64×64 纯色方块已修复——Xaero 的地形格式是 `POSITION + 每个纹理单元一组 UV`（unit 0..3）并配合 unit 0/2/3 的固定管线 `GL_COMBINE`，而 glsm 的顶点格式映射只认 UV `index` 0/1，unit 2/3 的属性槽从未下发，FFP 退回常量 `u_CurrentTexCoord2/3` 导致每张贴图只采一个纹素；装了世界地图时小地图复用其绘制路径，故两者同因。dev 实机确认地形细节恢复；已知缺口：世界地图界面内的图标按钮仍渲染异常（非本次修复引入、与 TexEnv/多纹理路径无关，待单独处理），详见 [docs/compat/xaero.md](compat/xaero.md) |
| Component Model Hider | 代码支持（实机待验） | 兼容门控（`compat/componentmodelhider`：网格构建期置位模组的 `isBuildingChunk`，隐藏位置跳过模型渲染，快速路径自行补上"邻格隐藏则仍绘制该面"规则） | 1.0（CurseForge 940949:4885858，modid `component_model_hider`）：其隐藏机制挂在 `RenderChunk.rebuildChunk` 上，Actinium 的 mesher 从不走该路径，导致 `isBuildingChunk` 永不置位、隐藏方块照旧渲染且仍剔除邻面；详见 [docs/compat/component-model-hider.md](compat/component-model-hider.md) |
| LagGoggles（TickCentral） | 部分 | 无侵入（实体上下文 hook 的锚点由 `renderEntity` 内调用点改为方法入口，`@WrapMethod`） | 5.9（CurseForge 283525）+ TickCentral 3.2（issue #166）：共存启动必崩——`com.github.terminatornl.laggoggles.tickcentral.RenderManagerTransformer` 把 `RenderManager.renderEntity` 方法体搬进 `laggoggles_trueRender`、原方法只剩转发桩，Actinium 原有 `@Redirect` 在方法内找不到 `Render.doRender` 调用点，`require = 1` 失败使 `RenderManager` 类变换整体失败（下游 ContentTweaker `NoClassDefFoundError`）；修复后入口方法在两种布局下都命中，`RenderManagerIrisAnchorTest` 复刻该搬迁变换锁定锚点（含变异校验），`./gradlew check` 通过，**实机验证待用户确认**，详见 [docs/compat/laggoggles.md](compat/laggoggles.md) |
| DragonCore 自定义字体 | 部分 | 无侵入（字体批处理器按渲染器类名让位，`Mods.DRAGONCORE` 门控） | 2.0.1（元素之诗整合包）：服务器 FontConfig 下发的自定义字体无法渲染已修复——DragonCore 用 `bt`（extends `FontRenderer`）替换 `fontRendererObj` 并在覆写的 `renderStringAtPos` 中绘制自字形，而 Actinium 批处理路径绕过该方法；检测 `eos.moe.dragoncore.` 包前缀后 batcher 让位（同 NeoFontRender 逻辑），详见 [docs/compat/dragoncore.md](compat/dragoncore.md) |
| Mobends / DragonCore | 已验证 | 无侵入（glsm 纹理绑定回调 + 根项目回写 vanilla `GlStateManager.TEXTURES[].textureName` 镜像） | 1.0 / 2.0.1（元素之诗整合包，无光影）：所有玩家皮肤纯白已修复——Mobends `ModelPart` 皮肤覆盖层反射读 vanilla 纹理镜像做恢复绑定，而 GLSMRedirector 使 vanilla `bindTexture` 方法体从不执行、镜像恒 0，恢复时 `bindTexture(0)` 解绑 unit0 导致后续 display list 回放无纹理；修复在每次 GL_TEXTURE_2D 绑定后（含缓存命中）同步镜像，详见 [docs/compat/mobends.md](compat/mobends.md) |
| GalaxySpace（AsmodeusCore 天空） | 已验证 | 无（glsm 核心颜色语义修复，非模组接入） | `dev_1.12.2` 分支 + AsmodeusCore 1.0.4（issue #164）：夜晚 Overworld 自定义 skybox 发白、银河贴图呈灰块已修复——`SkyProviderBase#render` 以 `glColor3f(skyColor - playerY/400)` 画天球，夜晚为负值，GLSM `changeColor` 未按 GL 规范钳到 [0,1]，FFP `sanitizeUniformColor` 把负色误判为 dirty sentinel 洗成白色；修复为入口 `Color4.clamp01`，`./gradlew check` 通过，用户实机确认夜晚天空恢复正常、白天与 JourneyMap 网格/GUI 字体颜色无回归，详见 [docs/compat/galaxyspace.md](compat/galaxyspace.md) |
| Goslingcord（Emojicord fork） | 已验证 | 无侵入（检测到 `emojicord` 时字体批处理器整体让位，`Mods.EMOJICORD` 门控） | 2.1.1（CurseForge 1207865:7269343，issue #204）：emoji 选择面板文字全部乱码已修复——Goslingcord 用 ASM 把 emoji 替换织入 vanilla `FontRenderer` 本体的 `renderStringAtPos`/`renderChar`/`getCharWidth`（`\u0000` 占位符 + 每字符绑定 emoji 纹理），而 `MixinFontRenderer` 在 `drawString`/`renderString` HEAD 改走 `BatchingFontRenderer` 绕过这些方法，占位符被当普通字符从字体图集随机取格；渲染器类名不变故无法按类名让位，改为按模组存在性整体让位（同 NeoFontRender）；实机确认面板/聊天 emoji 与文字正常，开/关光影无回归，详见 [docs/compat/goslingcord.md](compat/goslingcord.md) |

## 验证记录模板

```text
日期：
Actinium commit：
Java / Cleanroom：
GPU / 驱动 / OS：
光影包与预设：
模组列表：
场景：世界加载、维度切换、地形、实体、方块实体、水、天空、天气、阴影、手部、GUI、重载
结果：
日志与截图：
```
