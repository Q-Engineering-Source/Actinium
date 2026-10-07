# GVCLib / GVCReversion2 兼容性说明

兼容状态：**已验证**（第一人称持枪模型、贴图与亮度在光影/无光影路径均正常）

最后更新：2026-10-07

## 验证范围

- Minecraft 1.12.2、Cleanroom Loader 0.6.13-alpha（crl_t 整合包实例）
- GVCLib 1.12.2-9.10、GVCReversion2 1.12.2-alpha.10.2
- 光影包开启与关闭两条路径
- 相关 issue：[#198](https://github.com/Q-Engineering-Source/Actinium/issues/198)

## 症状与根因

### 持枪模型/贴图错误（issue #198 原症状）

三个叠加的机制问题：

1. `objmodel.Tessellator2.draw()` 为基础 UV 发 `glTexCoordPointer` 前不选定 client texture
   unit，沿用环境残留单元；残留非 0 单元时贴图坐标落到错误的单元上，模型贴图错乱。
2. 光影开启时 `RenderHandEvent` 每帧触发两次（原版 `EntityRenderer.renderHand` 与 Iris
   `HandRenderer` 各一次），GVCLib 的 `GVCEventsGunRender_First.rendergun` 从不取消事件，
   导致枪在光影 pass 之外以错误的 program/framebuffer 重复绘制。
3. Iris 手部 pass 原先在取消检查（即事件触发）之前没有 `enableLightmap()`：纹理单元 1 的
   `GL_TEXTURE_2D` 未启用时选中的 program 变体没有 lightmap 输入，`gl_MultiTexCoord1`
   被 shader 变换替换成硬编码常量 240/240（全亮）。

### 光影下手持枪械亮度突变（后续症状）

GVCLib 的第一人称枪体（`.mqo` 模型的 `mat*` 部件）与手臂部件从不写逐顶点亮度
（`objmodel.Tessellator2.setBrightness` 在全模组源码中无调用者，`hasBrightness` 恒为
false）。GLSM 在每次 draw 前把 lightmap 顶点属性（location 3）的默认值刷成全局
`GLSMConfig.lastBrightnessX/Y`，而该全局值是"上一个渲染方"的残留：原版实体渲染逐个写入
`entity.getBrightnessForRender()`、GVCLib 的子弹/枪口火光/boss 实体硬编码全亮
`15728880`（240,240）、容器 GUI 写 240/240。手部 pass 画枪之前没有任何人写入本帧玩家亮度，
于是枪的亮度等于环境残留值，随实体进出视野、开火、GUI 开关而跳变。

运行时证据：1000 个采样事件里玩家真实亮度恒定 `0xf00000`，而枪体 draw 时刻的亮度值在
`0/240`、`240/240`、`120/240` 之间漂移（`120` 一项非 16 倍数，来源未归因，修复机制使其无害化）。

曾尝试在 display list 编译时经 `Tessellator2.setNormal` 注入 `setBrightness(玩家亮度)`，但
`objmodel.GroupObject_mqo` 的 display list 每个进程终身只编译一次，亮度被永久烘焙进 VBO：
光照变化后不更新，换武器触发重编译时又跳变。该路线被否决。

原版对照：`ItemRenderer.setLightmap()` 每帧把玩家眼位 `getCombinedLight` 写入
`OpenGlHelper.setLightmapTextureCoords` 全局状态，手持物品（顶点格式不含 lightmap 元素）全部
取这个逐帧值。

## 修复

由 `mixins.actinium.gvclib.json` 在 `gvclib` 模组存在时加载兼容 Mixin：

- `MixinGVCFirstPersonArmLighting`：`@WrapMethod` 包裹
  `GVCEventsGunRender_First.rendergun`——光影开启时只在 Iris 手部 pass 内绘制（抑制原版
  pass 的重复绘制）；进入 scope 时逐帧把玩家当前亮度钉进 GLSM 当前 lightmap 坐标
  （`GLStateManager.setLightmapTextureCoords`，拆包 `packed & 0xFFFF / >>> 16`，与原版
  `ItemRenderer.setLightmap` 逐位一致），此后本帧内枪体/手臂的所有 draw（display list 重放
  或逐帧 tessellate）都取到逐帧正确的玩家亮度。手臂 `renderPart("leftarm"/"rightarm")` 由
  嵌套 scope 覆盖；无光影路径下 scope 代管 `enableLightmap()/disableLightmap()`。
- `MixinGVCTessellatorClientUnitGuard`：`Tessellator2.draw()` 期间把 client texture unit
  钉到 `GL_TEXTURE0` 并在结束后恢复原值，消除贴图坐标错乱。

`shader` 子项目的 `HandRenderer` 把 `enableLightmap()` 前移到 `RenderHandEvent` 触发之前，
取消检查只跳过原版物品绘制，保证事件内绘制的模组模型拿到正确的 lightmap program 变体与
unit-1 纹理矩阵。

## 验证记录

- `./gradlew check --no-daemon` 与 `./gradlew build --no-daemon` 通过。
- 用户实机确认（光影开启）：白天/黑夜/火把旁持枪亮度与环境一致且稳定；开火（产生全亮 flash
  实体）后枪不被污染成全亮；GVCR2 NPC/实体进出视野与转动镜头无突变；切换武器（触发新
  display list 编译）亮度随环境正确；打开/关闭物品栏后亮度保持；无光影路径持枪与手持普通
  物品回归正常。
