# Xaero 小地图 / 世界地图兼容

最后更新：2026-10-23。原始修复分支：`fix/xaero-map-render`。对应
[issue #175](https://github.com/DHJComical/Actinium/issues/175)（两张地图的地形渲染成纯色方块，
以及开启实体雷达时世界地图图标按钮的白底）。

版本：XaeroLib 1.7.3 + Xaero's Minimap 26.5.1 + Xaero's World Map 1.46.0。

追加修复：[issue #237](https://github.com/Q-Engineering-Source/Actinium/issues/237)，分支
`fix/issue237-xaero-pbo-readback`；报告版本为 XaeroLib 1.7.3 + Xaero's Minimap 26.6.0 +
Xaero's World Map 1.47.0，详见下方 PBO 回读崩溃记录。

## 症状与环境

- 环境：Cleanroom 0.6.13-alpha、Java 25、无光影包、NVIDIA GTX 1650 / 616.92（GL 4.6 core profile）。
- 症状：小地图与世界地图的**地形**渲染为每 64×64 方块一块的纯色（颜色仍像真实地形平均色，边界是
  整齐的区块阶梯）；路标图标、方向字、地图边框、按钮等**非地形**元素正常；关闭 Actinium 后正常。
- 无崩溃、无 GL 报错。

## 模组机制（反编译结论）

世界地图用固定管线多纹理绘制地形：

- `xaero.map.gui.GuiMap:87-90` 定义 `TEX_2F_1/2/3`（UV 元素 `index` = 1/2/3），`:3053-3057` 组装
  `POSITION_TEX_TEX_TEX = POSITION_3F + TEX_2F(index 0) + TEX_2F_1/2/3`，即**每个顶点带 4 组 UV，
  分别对应 legacy texture unit 0..3**；`renderTexturedModalRectWithLighting2` (`:2219-2257`) 每顶点
  连写 4 次 `tex()`。
- 逐 unit TexEnv：`setupTextureMatricesAndTextures(brightness)` (`:2495-2508`) 与
  `setupTexture0/2/3` (`:2452-2478`) 对 unit 0/2/3 设 `GL_COMBINE`（unit 0 `SUBTRACT`、
  unit 2 `ADD`、unit 3 `MODULATE`，用 `GL_PRIMARY_COLOR`/`GL_PREVIOUS`/`GL_TEXTURE` 与 alpha 操作数
  组合出 `brightness × texture`）；`bindMapTextureWithLighting3` (`:2420-2446`) 把**同一张区块贴图**
  绑到 unit 0/2/3（unit 1 关闭）。
- 贴图内容：每张 `MapTileChunk` 是 **64×64** 纹理（1 纹素/方块），由 CPU 侧地图数据经
  **pixel-unpack PBO** 上传（`TextureUpload.Normal/SubsequentNormal` 走 `glTexImage2D`/
  `glTexSubImage2D` 的 `long pboOffset` 重载）；`RegionTexture` 另有 pack PBO 回读路径。
- **小地图复用世界地图的绘制代码**：装了世界地图时 `SupportXaeroWorldmap.drawMinimap/renderChunks`
  (`:189/:404/:417`) 直接调用 `GuiMap` 的静态方法绘制 64×64 区块贴图，因此两者同因同果。
- `hasLight=false`（地图数据无光照信息，或关闭 Xaero 的 Lighting）时退回单 UV 的
  `DefaultVertexFormats.POSITION_TEX`，只涉及 unit 0，不触发本缺陷。

## 冲突分析（根因）

`src/main/java/com/dhj/actinium/render/VanillaVertexBufferRenderer` 是所有 BufferBuilder 绘制路径
（`VanillaBufferBuilderRenderer`、`BufferBuilderStreamingDrawer`、`DeferredDrawBatcher`）共用的
「顶点格式元素 → 属性位置」映射，它此前把 UV 元素按 `index` 硬编码为：

```java
case UV -> element.getIndex() == 0 ? 2 : element.getIndex() == 1 ? 3 : -1;
```

Xaero 的格式用到 `index` 2/3 → 返回 `-1` → `setupVertexFormatAttributes` 直接 `continue`，
**unit 2/3 的属性槽既不 enable 也不下发指针**。FFP 顶点着色器因此退回逐次绘制的常量
`u_CurrentTexCoord2/3`（Xaero 从不设置，即 (0,0,0,1)），unit 2/3 只采样到该纹理的一个纹素，再经上面
的 TexEnv 组合 ⇒ **每张 64×64 地图纹理塌成一个纯色**，与症状（区块级纯色、非地形元素正常）完全吻合。

关键点：`BufferBuilderMixin.tex` 按**元素游标**顺序写入（与 `index` 无关），顶点数据本来就落在各自
偏移上，唯一按 `index` 分派的就是属性位置——这解释了根因为何只在这一处。

## 兼容方案

| 冲突 | 方案 | 位置 |
| --- | --- | --- |
| UV `index` 2/3 无属性槽 → 逐顶点纹理坐标丢失 | UV 元素统一走 `Usage.uvAttributeLocation(unit)`（0→2、1→3、2→5、3→6）；FFP 顶点着色器按同一张表声明 `a_TexCoord2/3`（location 5/6），并在存在属性时不再声明 `u_CurrentTexCoord2/3` | `VanillaVertexBufferRenderer.attributeLocation`、`VertexFormatElement.uvAttributeLocation`、`VertexShaderGenerator`、`VertexKey`、`Uniforms` |
| 单元 2/3 的逐顶点可用性判定 | `VertexKey.packFromState` 查询 `VertexAttribState.isAttribEnabled(5/6)`（per-VAO 状态），而不是只有 unit 0/1 的 `VertexFlags` | `VertexKey`、`VertexAttribState` |
| 客户端数组路径的同类缺口 | `glClientActiveTexture` + `glTexCoordPointer` 也走同一张表（unit 2/3 不再被静默丢弃） | `GLStateManager.texCoordAttributeLocation`、`clientStateToAttributeLocation` |
| 自定义 GLSL 程序（CompatShaderTransformer） | `actinium_TexCoord{i}` 用同一张表；`i>3` 无槽时告警并跳过（宁可链接失败，也不静默采错坐标） | `CompatShaderTransformer` |
| 防止同类静默丢弃再现 | UV 单位无槽时一次性告警；位置表由两个测试锁定 | `VanillaVertexBufferRenderer.reportUnsupportedElement`、`VertexAttributeLayoutTest`、`VanillaVertexBufferRendererAttributeLayoutTest` |

## 验证记录

- [x] `./gradlew check --no-daemon` 通过（156 suites / 714 tests，0 失败）。
- [x] 独立审查子代理复核：反编译 Xaero 三件套 + 逐段核对 glsm FFP 链路（`VertexKey` →
  `VertexShaderGenerator` → `FragmentShaderGenerator` 的 TexEnv 链 → `Uniforms`），判定 pass。
- [x] 用户实机（dev 运行，Xaero 三件套、无光影）：小地图与世界地图地形恢复细节，纯色方块消失
  （截图对比：关 Actinium 正常 / 修复前纯色 / 修复后细节）。
- [x] 用户实机（dev 运行，Xaero 三件套）：开启实体雷达后世界地图界面的图标按钮恢复正常，根因与修复见下节。

## 根因：世界地图图标按钮的白底（2026-09-22）

症状：小地图开启「实体雷达」后，世界地图界面内的 `GuiTexturedButton` 图标按钮渲染成**白色不透明底**
（图集透明区的 RGB 为白、alpha 为 0）。关闭实体雷达立即恢复；与是否加载光影包无关，且只有 Actinium
在场时才出现。

证据链（静态 + 运行期，同一次 dev 会话）：

- **触发者**：`xaero.hud.minimap.radar.render.element.RadarRenderer#postRender` 在元素渲染收尾时调用
  `GlStateManager.func_179118_c()`（vanilla `disableAlpha()`）**且不恢复**。该渲染器只在「实体雷达」启用
  时注册，而世界地图复用小地图的元素渲染器（`MinimapElementRendererWrapper` →
  `MapElementRenderHandler.render`），所以泄漏直接落进世界地图 GUI 的绘制流程。
- **运行期定位**：在 `Gui.drawTexturedModalRect` 处记录每次 GUI 精灵绘制的 glsm 跟踪值、驱动值与 FFP
  变体键。世界地图界面内 33855 次绘制**全部**是 `alphaTest=false` + `blend=false`，变体键
  `fk0=0x2080`（bit2=0 ⇒ 该 fragment 变体在结构上不含 alpha test，不可能 discard）；同一批绘制里
  unit0 绑定（408）与驱动 `GL_TEXTURE_BINDING_2D` 一致、`glsmProgram=0` 与 `GL_CURRENT_PROGRAM` 一致
  ⇒ 纹理绑定、程序与顶点布局均无失步，问题纯粹是状态。
- **谁关的**：记录 GUI 打开期间每次 blend / alpha-test 变更及其调用栈后，第一条异常绘制之前 18 行即
  `alphaTest=false caller=[RadarRenderer.postRender:226 <- ... <- MapElementRenderHandler.render]`。
- **为什么原版正常**：vanilla `FontRenderer.drawString` 的**第一行**是 `GlStateManager.enableAlpha()`
  （`net.minecraft.client.gui.FontRenderer:235`，全类唯一的 alpha/blend 调用，且类中没有任何
  `disableAlpha`）。原版每次绘制文字都会把 alpha test 打开并**留着**，Xaero 泄漏的 `disableAlpha` 因此
  总被随后的一段文字顺手修好。
- **为什么只有 Actinium 坏**：`BatchingFontRenderer.flushBatch` 把 alpha test 保存（451 行）后**忠实
  恢复**（554-557 行），抹掉了 vanilla 那个副作用，泄漏第一次真正生效。
- **为什么是白底**：`GuiTexturedButton` 覆写了 `drawButton`，且**不像 vanilla `GuiButton.drawButton`
  那样调用 `enableBlend()`**，因此它同时依赖 alpha test 与 blend 两个出口；两者都被关闭时，图集透明像素
  被当不透明写入。

修复：`BatchingFontRenderer.flushBatch` 收尾不再恢复 alpha test，改为保持启用，对齐 vanilla 的净效果
（改动处有注释记录上述因果）。

**已排除的路径**（不要重复排查）：UV 属性槽（#175 已修）、TexEnv/多纹理与 unit 掩码、跨线程状态、纹理矩阵、
程序状态、纹理内容/绑定、顶点布局与属性偏移，以及"Iris 覆盖窗口内的有效值"——最后这条在本例中被实测证伪：
本次运行中 `deferred=true` 出现 0 次，说明没有任何 alpha/blend 变更被 shader override 吞掉，且加载与不加载
光影包时现象完全一致。

### 调试安全约束（踩过的坑）

诊断代码**不得读取 CPU 侧绘制缓冲**（`BufferBuilder` 的 `ByteBuffer`）——streaming 路径的
`firstVertex` 是持久缓冲内偏移，用它索引上传数据会越界并使客户端崩溃。只需跟踪状态时，一律读 glsm
自身跟踪值（零 GL 调用）；必须做 GL 查询时，需避开显示列表编译期（`DisplayListManager.isRecording()`），
并在客户端内存纹理回读时经 glsm 临时解绑 pack PBO；传入 `long` PBO 偏移的回读必须保留绑定。

## 根因：缩小世界地图时 PBO 回读崩溃（2026-10-23，issue #237）

报告环境：Minecraft 1.12.2、Cleanroom 0.6.13、Java 25、Windows 11、NVIDIA RTX 5060，
无光影，Xaero 三件套版本为 Minimap 26.6.0 / World Map 1.47.0 / XaeroLib 1.7.3。
世界地图缩小到 0.5x 以下后进程短暂停顿并以 `0xc0000005` 退出，没有 Java crash report 或
`hs_err_pid` 文件。

精确版本 World Map 1.47.0 的反编译结果给出以下触发链：

- `GuiMap` 按 `floor(log2(1 / userScale))` 选择纹理层级；从 0.5x 起进入分支层级，
  `BranchLeveledRegion` 创建或更新 `BranchRegionTexture`。
- `BranchRegionTexture.uploadNonCache` 请求合成或下载分支纹理，传入 pack PBO 与偏移 `0`。
  `RegionTexture.bindPackPBO` 为 64×64 RGBA 数据分配 16384 字节，容量与纹理尺寸一致。
- `TextureUpload.BranchUpdate.upload` / `BranchDownload.upload` 绑定该 PBO 后调用
  `GL11.glGetTexImage(..., pboOffset)`，由 GLSM 重定向至 `GLStateManager` 的 `long` 重载。
- 该重载错误地调用 `suspendPixelPackBuffer()`，实际解绑 PBO 后仍原样下传 `0L`。
  此时参数由缓冲区偏移变成空 CPU 指针，驱动回读会触发原生访问冲突。

修复仅移除 `glGetTexImage(..., long)` 周围的 PBO 临时解绑与恢复，使其保留调用者绑定，
与已有 `glReadPixels(..., long)` 保持一致。`ByteBuffer` / `IntBuffer` 客户端内存重载仍保留
临时解绑行为。本修复不新增 Xaero Mixin，也不改变 PBO 容量或纹理生成流程。

验证记录：

- 临时独立 JVM 验证程序通过已有 `ServiceLoader` backend 入口，直接调用真实
  `GLStateManager`，在测试 backend 中检查回读瞬间的绑定；不使用真实驱动向空地址写入。
  修复前零偏移和非零偏移两项均观察到错误绑定 `0`；修复后两项均保留原绑定。
  `ByteBuffer` / direct `IntBuffer` 的解绑与恢复以及 `glReadPixels(long)` 对照均通过，合计 5 项。
  程序位于本地忽略目录 `build/issue237-probe/`，未纳入常规 JUnit 套件。
- `./gradlew check build --no-daemon` 通过；根项目 783 项测试，0 失败、4 跳过，分发 jar 结构校验通过。
- 2026-10-23，用户使用 `Actinium-issue237-pbo-readback.jar` 实机验证后确认通过。

## 已知缺口（与本缺陷无关）

- GTNHLib `TessellatorManager.mapVertexFormat/copyVertex`（DirectTessellator 捕获路径）同样只处理
  UV `index` 0/1，UV≥2 被静默丢弃。该路径只在 GTNHLib 捕获期（如显示列表编译、DirectTessellator
  使用者）可达，Xaero 的绘制不经过它，因此与 #175 无关；修复需要扩展
  `VertexFlags`/`DefaultVertexFormat` 的格式表，建议另开条目处理。
- `org.lwjgl.opengl.EXTSeparateShaderObjects.glActiveProgramEXT` 未登记重定向：小地图/世界地图探测旧式
  能力对象后会走 EXT 分支，`xaero.map.misc.Misc.setShaderProgram(0)` 因此绕过 glsm 直接改真实程序，
  造成"跟踪程序 vs 真实程序"失步。修复方向：把该激活调用映射为 `GLStateManager.glUseProgram`
  （`glUseShaderProgramEXT` 保持未映射是无害的，EXT 语义下只有激活调用决定绘制程序）。已定位未提交。
- `GLStateManager.glPushAttrib()`（无参）只推 `GL_ENABLE_BIT`，而原版 1.12.2 `GlStateManager.pushAttrib()`
  推的是 `8256 = GL_ENABLE_BIT | GL_CURRENT_BIT` ⇒ 经重定向走该入口时会漏恢复"当前颜色 / 当前纹理坐标"。
  修复方向：让无参版本推 `8256`。已定位未提交。

## 复现配方（dev 运行）

`gradle/scripts/dependencies.gradle` 已把三件套按 issue 版本加入 dev 运行时；JourneyMap 同时在场会与其
`world_id`/`world_info` 频道冲突，因此保持 compileOnly：

```
modImplementation 'curse.maven:xaerolib-1417462:8849823'         // XaeroLib 1.7.3
modImplementation 'curse.maven:xaeros-minimap-263420:8863814'    // Minimap 26.5.1
modImplementation 'curse.maven:xaeros-world-map-317780:8849947'  // World Map 1.46.0
modCompileOnly   'curse.maven:journeymap-32274:5172461'          // 与上者不可共用运行时
```

## 参考

- 反编译产物：`%APPDATA%/minecraft-dev-mcp/decompiled-mods/{xaerominimap,xaeroworldmap,xaerolib}/`
  （Xaero 未混淆，可直接读）。
- 相关先例：`docs/compat/voxelmap.md`（另一个小地图模组的固定管线/状态跟踪缺口）。
