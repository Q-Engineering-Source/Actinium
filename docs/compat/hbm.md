# HBM's Nuclear Tech - Community Edition 兼容性说明

兼容状态:**已验证**（dev 实测确认；生产整合包回归待做）
最后更新:2026-09-21

## 验证范围

- 版本:HBM's Nuclear Tech - Community Edition 2.5.0.5
  （CurseForge `hbm-nuclear-tech-mod-community-edition-1312314:8330665`）
- 环境:Cleanroom + Java 25.0.3,`./gradlew runClient`（dev 依赖集）
- 光影包:Complementary Reimagined r5.5.1（`ComplementaryReimagined_r5.5.1.zip`）,
  同场加载 Distant Horizons
- 相关改动:分支 `fix/hbm-sedna-depth-clear`

## 症状:第一人称手持 Sedna 系武器时画面深度崩坏

### 现象

第一人称手持 HBM Sedna 武器系统的枪械（用户报告为 SPAS-12 霰弹枪）时,画面出现深度崩坏;
换持其他物品或空手即恢复正常。触发不依赖射击、瞄准或移动,只要该枪械在主手且处于第一人称视角。

只有 Sedna 系武器触发 —— 即 `com.hbm.render.item.weapon.sedna` 下继承
`ItemRenderWeaponBase` 的渲染器（SPAS-12、M2、Uzi、G3、Minigun、FatMan、Tesla Cannon、
Missile Launcher 等约 50 把）。老式 `ItemGunBase` 武器（Egon、B93、JShotgun、Chainsaw、
Vortex 等）不经过该基类,因此不受影响。这也是"有的枪有问题、有的没问题"的原因。

### 根因:HBM 在 Iris 手部 pass 内清空了深度缓冲

`ItemRenderWeaponBase.setPerspectiveAndRender` 依据 OptiFine 的光影状态二选一决定手持武器的
深度策略:

```java
boolean shaders = com.hbm.util.ShaderHelper.areShadersActive();
if (!shaders) {
    GlStateManager.clear(GL_DEPTH_BUFFER_BIT);   // 无光影:清深度,让枪不被世界遮挡
}
GlStateManager.matrixMode(GL_PROJECTION);
GlStateManager.pushMatrix();
GlStateManager.loadIdentity();
if (shaders) {
    ShaderHelper.applyHandDepth();               // 有光影:交给 OptiFine 做手部深度压缩
}
Project.gluPerspective(/* 手部 FOV */, /* aspect */, 0.05F, /* far */);
```

`ShaderHelper` 通过 `Class.forName("net.optifine.shaders.Shaders")` 以 MethodHandle 绑定
OptiFine 的 `Shaders.shaderPackLoaded` / `applyHandDepth`。Actinium 自行实现光影管线,
**不提供任何 `net.optifine` 类**,于是这些句柄恒为 `null`、`areShadersActive()` 恒为
`false`（`ModPresence.OPTIFINE` 探测的 `optifine/OptiFineForgeTweaker.class` 同样不存在）,
HBM 每帧都走"无光影"分支。

问题在于这段代码运行的时机。光影激活时第一人称手部由 Iris 的
`net.coderbot.iris.pipeline.HandRenderer` 渲染
（`MixinRenderGlobal.actinium$beginIrisTranslucents` → `HandRenderer.renderSolid` →
`ItemRenderer.renderItemInFirstPerson` → HBM 的 TEISR → `setPerspectiveAndRender`）,
而这个 pass 正是拿世界深度来渲染手部的:

```java
// shader/src/main/java/net/coderbot/iris/pipeline/HandRenderer.java:126-141
GLStateManager.glPushMatrix();
GLStateManager.glDepthMask(true); // actually write to the depth buffer, it's normally disabled at this point
...
mc.entityRenderer.itemRenderer.renderItemInFirstPerson(tickDelta);
```

HBM 在 `glDepthMask(true)` 之后清掉主 framebuffer 的深度,等于把 `depthtex0` 整块写成
远平面。该 pass 之后所有读深度的环节（Iris 的 composite/deferred、雾、阴影、水、景深,
以及 Distant Horizons 的 LOD 深度重建）拿到的都是失效深度,画面即表现为深度崩坏。

### 为什么不能只让 HBM 走"有光影"分支了事

HBM 在"有光影"分支里依赖 `ShaderHelper.applyHandDepth()` 保证手持武器不插进方块
（OptiFine 的手部深度压缩）。在 Actinium 下该句柄为 `null`,调用即空操作;而 Iris 对其它第一人称
物品用的是 `HandRenderer.setupGlState` 里的投影压缩:

```java
// shader/src/main/java/net/coderbot/iris/pipeline/HandRenderer.java:42-43
// We need to scale the matrix by 0.125 so the hand doesn't clip through blocks.
GLStateManager.glScalef(1.0F, 1.0F, DEPTH);   // DEPTH = 0.125F
```

HBM 在这之后 `loadIdentity()` 重建了投影,把 Iris 的压缩抹掉了。所以修复必须同时补回压缩,
否则会以"枪穿墙"换取"深度不崩坏"。

## 修复

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/dhj/actinium/compat/hbm/HbmWeaponDepthCompat.java` | 用 Actinium 自己的光影状态回答 HBM 的两个探针:`shouldSkipWeaponDepthClear()` 返回 `IrisApiV0Impl.INSTANCE.isShaderPackInUse()`;`applyShaderHandDepth()` 施加与 `HandRenderer.DEPTH` 一致的投影 Z 压缩 |
| `src/main/java/com/dhj/actinium/mixin/mod/hbm/MixinItemRenderWeaponBase.java` | 两个 `@Redirect`,只作用于 `setPerspectiveAndRender`:①`ShaderHelper.areShadersActive()` → 光影激活时返回 true,跳过深度清理;②`ShaderHelper.applyHandDepth()` → 改为 `HbmWeaponDepthCompat.applyShaderHandDepth()` |
| `src/main/resources/mixins.actinium.hbm.json` | 在 `client` 列表注册新 Mixin |

两个注入点都落在 HBM 自己的类与方法上（`remap = false`）,不触碰 Minecraft 的命名空间,
因此 dev（MCP）与生产（SRG）环境使用同一份匹配串。

行为对应关系:

- 光影激活:不再清深度,武器投影带 0.125 压缩 —— 与 Iris 其它第一人称物品的深度策略一致。
- 无光影:`areShadersActive()` 仍返回 false,保留 HBM 原本的清深度路径,行为不变。
- 非 Sedna 系武器:不经过该基类,行为不变。

## 验证记录

- `./gradlew check --no-daemon` 通过（含 `MixinConfigurationTest` 对新 Mixin 的声明与存在性校验、
  remap jar 结构校验 `verifyDistributedJar`）。
- dev 实机（`runClient`,HBM-CE 2.5.0.5 + Complementary Reimagined r5.5.1 + Distant Horizons）:
  用户确认修复,症状消失;`latest.log` 无 Mixin 应用失败。第一人称手持枪模仍不被地形裁切。
- 关光影路径与老式武器路径未出现回归（按上节行为对应关系设计,dev 回归覆盖）。

## 附注:未处理的相邻缺陷

GLSM 的 `glPushAttrib(GL_DEPTH_BUFFER_BIT)` 只保存/恢复 depth test 与 depth func,
不含 `GL_DEPTH_WRITEMASK`(见 `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/Feature.java:113-118`
的自述注释),与原生 OpenGL 语义不一致。本次 HBM 的武器路径不经由 `pushAttrib` 改动 depth mask,
未被触发,故未在本次改动中处理。

## issue #170：打开 Stardar（系统地图）GUI 直接崩溃

### 现象与环境

2026-09-21 报告：打开 NTM-Space 的 Stardar 机器 GUI（`com.hbmspace.inventory.gui.GUIMachineStardar`）
时客户端立即崩溃：

```text
java.lang.IllegalArgumentException: Unsupported HBM RenderUtil attribute bits: 0x4
	at com.dhj.actinium.compat.hbm.HbmRenderStateCompat.toGlMask
	at com.dhj.actinium.compat.hbm.HbmRenderStateCompat.pushAttrib
	at com.hbm.util.RenderUtil.handler$...$actinium$pushAttrib
	at com.hbmspace.inventory.gui.GUIMachineStardar.drawSystemMap(GUIMachineStardar.java:323)
```

报告者环境：NTM-CE 2.6.1.0 + NTM-Space 0.9.2 + Actinium alpha-0.0.9。

### 根因

`HbmRenderStateCompat.toGlMask` 把 HBM 掩码里**未映射的位**当作错误，抛
`IllegalArgumentException`。这与宿主 HBM 自己的语义相反：`RenderUtil.pushAttrib(int)`
的字节码是每个 attribute 组一个 `if ((mask & bit) != 0)` 分支，**对不认识的位什么都不做**，
既不报错也不捕获。也就是说 Actinium 把一个"HBM 本来会静默忽略"的位升级成了崩溃。

该崩溃点的实参在 NTM-Space 源码里是：

```java
// com/hbmspace/inventory/gui/GUIMachineStardar.java:323（v0.9.2 与 nightly 一致）
RenderUtil.pushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
```

其中 `GL11.GL_LINE_BIT == 0x4` —— 正是报错里的 `0x4`。`0x4` 既不在 HBM 的 7 个捕获位
（`0x2000 0x40 0x40000 0x4000 0x100 0x8 0x80`）里，也不在 Actinium 的映射表里，
于是走进抛异常分支。

> 旁证：`HbmRenderStateCompat` 原有的 `rejectsUnknownHbmAttributeBits` 单测把"对未知位
> 抛异常"固定成了期望行为，所以这个缺陷在 #170 之前不会被单测拦下。

### 触发链

| 环节 | 位置 | 行为 |
| --- | --- | --- |
| 1 | `GUIMachineStardar.drawSystemMap` | `pushAttrib(0x204)`，其中含 `GL_LINE_BIT = 0x4` |
| 2 | `MixinRenderUtil.actinium$pushAttrib` | `HEAD` 取消原方法，转交 compat 层 |
| 3 | `HbmRenderStateCompat.pushAttrib` → `toGlMask` | `0x4` 未映射 → 抛异常 |
| 4 | 异常穿透 GUI 渲染 | 客户端崩溃（"Rendering screen"） |

### 修复

`HbmRenderStateCompat.toGlMask` 改为**与 HBM 对齐**：只映射自己认识的位，未映射的位直接
忽略，不再抛异常；`HBM_ALL_BITS` 哨兵值的展开语义保持不变（`0xFFFFF` → `0x461C8`）。
常量 `HBM_SUPPORTED_BITS` 随之更名 `HBM_MAPPED_BITS`，因为它的含义是"已映射的位"而非
"受支持的白名单"。

代价与 HBM 原行为一致：被忽略的位所代表的 GL 状态不会被恢复——而 HBM 自己在该位上也
不恢复它，所以这不是回退。

### 验证

- 单测（`HbmRenderStateCompatTest`，8 项全通过）覆盖：`0x4` 单独出现只保存 shade model
  （`GL_LIGHTING_BIT`）；`0x6004` 这类"已映射位 + 未知位"混合掩码按已映射位展开；
  `0x100000` 这类 `HBM_ALL_BITS` 之外的高位不影响其它映射；`0xFFFFF` 哨兵展开不变。
- `./gradlew check --no-daemon` 通过（含 `MixinConfigurationTest` 与 remap Jar 结构校验）。
- 待实机确认：NTM-Space 0.9.2 + NTM-CE 2.6.1.0 下打开 Stardar GUI 的星图不再崩溃，
  且退出该 GUI 后后续绘制无状态串扰。

### 备注（版本交叉核对）

查证时对 NTM-CE **2.5.0.5 与 2.6.1.0** 两版的 `pushAttrib(int)` 做了逐常量比对，7 个捕获位
与两个哨兵常量完全一致，均无 `0x4` 分支，所以本修复对两个版本都成立。NTM-Space 0.9.2
的字节码中该调用点常量按 `sipush` 解出为 `0x6004`，而崩溃报告是 `0x4`（源码为 `0x204`）——
三者都含 `0x4` 且都不在 Actinium 映射表内，因此该差异不影响修复结论，仅记录备查。
