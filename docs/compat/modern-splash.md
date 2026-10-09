# Modern Splash 兼容（modernsplash 1.5.3）

最后更新：2026-10-02。分支：`feat/modern-splash-compat`、`fix/modern-splash-fade-crash`。

## 模组机制（反编译结论）

Modern Splash（CurseForge 项目 629058，文件 8487408，modid `modernsplash`，gkappa 出品）是
纯 ASM coremod，用高版本风格 Mojang logo 启动画面替换 Forge/Cleanroom 的 SplashProgress：

- `FMLCorePlugin`：`gkappa.modernsplash.MSLoadingPlugin`，注册
  `ReplaceSplashTransformer`（`IClassTransformer`）。
- 对 `net.minecraftforge.fml.client.SplashProgress*` 前缀类：从
  `gkappa.modernsplash.CustomSplash*` 读取字节码，用 `SplashRemapper` 把类名映射为
  `SplashProgress*` 后整体替换——运行时 `SplashProgress` 实际是 `CustomSplash` 的代码。
- `CustomSplash` 提供与原版 SplashProgress 完全对齐的静态 API
  （`start/pause/resume/finish/confirm/getMaxTextureSize/drawVanillaScreen/
  clearVanillaResources/isDisplayVSyncForced/mutex`），保证 FML/Minecraft 的反射调用继续有效；
  渲染用直接 GL11（SharedDrawable 线程），自带字体（`SplashFontRenderer extends FontRenderer`）、
  logo、进度条、内存条与日夜双套配色（`config/modernsplash.cfg`）。

## 与 Actinium 的交互（实测结论）

- **替换与 mixin 顺序**：实测（mixin.debug.export 导出最终字节码）mixin transformer
  在 ReplaceSplashTransformer **之后**应用——`MixinSplashProgress` 的三个注入
  （`celeritas$getMaxTextureSize/initSplashTessellator/finishSplash`）与
  `MixinSplashProgressCallable`（`checkContext`）全部保留在替换后的类中；StellarCore 的
  `injectFinish` 同样保留。dev 运行：coremod 加载、启动画面正常、主菜单正常、无异常。
- **FontStrategist** 已内置 `CustomSplash$SplashFontRenderer` 识别（替换后实际命中
  `SplashProgress$SplashFontRenderer`），splash 字体走 `BatchingFontRenderer` 的
  `isSplash` 专用路径。

## 字体颜色修复（本分支新增）

**问题**：Modern Splash 1.5.3 的 `CustomSplash` 调用 `fontRenderer.drawString(text, 0, 0, 0)`
（颜色参数恒为 **0**），意图是"使用 drawString 前 `glColor4f(fontColor)` 设置的 GL 当前色"。
但 1.12.2 `FontRenderer.renderString` 对 color=0 会补全为 `0xFF000000` 并
`GlStateManager.color(0,0,0,1)`——**无论配置 `font`（白天 `0xFFFFFF`）还是 `fontDark`
（夜间 `0xF3F5F8`）都渲染为纯黑**。原版 Forge/Cleanroom SplashProgress 的对应调用传的是
`0xFFFFFF`（已反编译对比确认），因此这是 Modern Splash 自身的缺陷（原版同样黑，且颜色配置
完全不生效）。

**修复**（Actinium 侧，splash 字体专属路径，不影响其他渲染）：

- `BatchingFontRenderer.floatsToArgb`：GL 归一化分量 → ARGB（`Math.round` 四舍五入）；
- `BatchingFontRenderer.readCurrentGlColorAsArgb`：从 **GLSM 状态缓存
  `GLStateManager.getColor()`** 读取当前色——CustomSplash 的 `glColor*` 调用被 GLSM
  重定向并更新缓存，`GL_CURRENT_COLOR` 的 GL 查询在 core profile 下不是有效状态
  （实测返回垃圾值/滞后值）；
- `MixinFontRenderer.angelica$drawStringBatched`：当颜色参数为 `0xFF000000`（调用方传 0）
  且目标渲染器是 splash 字体（`BatchingFontRenderer.isSplash()`）时，改用当前色
  （含淡出 alpha）——恢复"color=0 使用当前色"的 fixed-pipeline 语义；非 splash 字体
  行为不变；
- `MixinFontRenderer.actinium$isFontBatcherDisabled`：`font-batcher-check` 调试日志改为
  仅状态变化时打印（原实现每字符一条，fontDebug 开启时刷爆日志）。

**排查记录**（避免回归）：曾先后尝试 `GL11.glGetFloatv(GL_CURRENT_COLOR)` 与
`RenderBackend.getFloat` 查询，均因 core profile 下该状态不可查询而返回垃圾值
（实测 `0x0`/`0xff89ea00`/`0xff031600` 等杂色）；`GLStateManager.getColor()` 缓存才是
正确来源（`font-flush-end` 日志的 `restoreColor=[1.0,1.0,1.0,1.0]` 佐证）。中间另修复了
调试日志优化引入的 `Boolean` 拆箱 NPE（splash 线程连锁崩溃）。

## 启动末尾崩溃修复（issue #200，本分支新增）

**症状**：安装 Modern Splash 后偶发在加载末尾 JVM 直接中止：
`FATAL ERROR in native method: No context is current...`，栈顶为
`GL11.glPushAttrib` ← `ModernSplash.drawFadeOverlay` ← `onGuiDrawPost`
（`GuiScreenEvent.DrawScreenEvent.Post`，主菜单打开后 500ms 淡出窗口内）。

**根因**（运行时因果链已定位）：

1. Actinium 使用 OpenGL core profile 上下文，`glPushAttrib`/`glBegin` 等 FFP 入口
   不存在；LWJGL 3 对空函数指针的调用是 native fatal error，不可捕获。
2. 正常情况下 `AngelicaRedirectorTransformer`（`AngelicaLateTweaker` 注册）会把
   该类的 `GL11.xxx` 改写为 `GLStateManager.xxx`（GLSM 模拟，映射表完整覆盖
   `drawFadeOverlay` 的全部调用）。
3. 但 transformer 只改写**注册之后**加载的类。`MSLoadingPlugin` 构造函数在
   coremod 阶段（无 SortingIndex，先于 Actinium 的 1500）调用
   `TimeHistory.getEstimateTime()`，其 `IOException` 分支引用
   `ModernSplash.LOGGER`——当 `config/time.history` 缺失或读取失败（首跑、
   Windows AV/索引锁竞态）时，`gkappa.modernsplash.ModernSplash` **抢在重定向器
   注册之前完成类加载**，永久保留裸 GL 字节码。
4. 偶发性 = 「`time.history` 读取失败」与「首帧落在淡出窗口内」两个时序条件叠加。

**修复**（运行时手段，字节码路线对先行定义的类物理无解）：

- `AngelicaLateTweaker.getLaunchArguments()` 注册重定向器**之前**调用
  `ModernSplashOverlayCompat.markIfLoadedBeforeRedirector()`，用
  `Launch.classLoader.isClassLoaded(...)`（`findLoadedClass` 语义，不触发加载）
  探测该类是否已逃逸；
- 逃逸时，`MixinSplashProgress.celeritas$finishSplash`（splash finish，主菜单打开前）
  调用 `ModernSplashOverlayCompat.neutralizeLogoOverlayIfEscaped()`：经 GLSM 删除
  splash logo 纹理并把 `ModernSplash.logoGlTextureName` 置 0，使淡出层跳过裸 GL 块
  （背景淡出仍走已转换的 vanilla 路径）。未逃逸时完全不干预，logo 淡出动画保留。

## 文件清单

- `src/main/java/com/gtnewhorizons/angelica/client/font/BatchingFontRenderer.java`
  （`floatsToArgb`/`readCurrentGlColorAsArgb`/`isSplash()`）
- `src/main/java/com/dhj/actinium/mixin/vintage/fontrenderer/MixinFontRenderer.java`
  （splash 字体 color=0 修复 + 调试日志刷屏修复）
- `src/test/java/com/gtnewhorizons/angelica/client/font/BatchingFontRendererColorTest.java`
  （新增，5 用例）
- `gradle/scripts/dependencies.gradle`（`modImplementation curse.maven:modern-splash-629058:8487408`）
- `src/main/java/com/dhj/actinium/compat/modernsplash/ModernSplashOverlayCompat.java`
  （issue #200：逃逸探测 + 淡出层中和）
- `src/main/java/com/gtnewhorizons/angelica/loading/fml/tweakers/AngelicaLateTweaker.java`
  （注册重定向器前探测逃逸）
- `src/main/java/com/dhj/actinium/mixin/vintage/core/startup/MixinSplashProgress.java`
  （splash finish 时中和逃逸类的 logo 淡出层）

## 验证记录

- [x] `compileJava` / `test` / `build` 通过。
- [x] dev 运行：Modern Splash coremod 加载、启动画面正常、mixin 注入保留
  （`mixin.debug.export` 字节码确认）、主菜单正常、无异常。
- [x] 字体颜色修复人工确认（白天 `0xFFFFFF` 白 / 夜间 `0xF3F5F8` 浅灰白生效；
  `font-draw-zero` 调试日志确认全部 splash 文字 `glColor=0xffffffff`）。
- [ ] 光影开启场景回归（启动画面阶段不涉及光影，待确认无副作用）。
- [x] issue #200：`compileJava` / `check` 通过。
- [ ] issue #200 逃逸场景实测：删除实例 `config/time.history` 后启动，不再崩溃、
  日志出现 `ActiniumModernSplashCompat` 的 WARN/INFO，主菜单背景淡出正常无 logo。
- [ ] issue #200 回归场景实测：`time.history` 存在时启动，logo 淡出动画与修复前一致。

## 参考

- 反编译产物：`.tmp/modern-splash-src/`（CFR 0.152，临时目录不提交）。
- 对比基准：Cleanroom 0.6.7 原版 `SplashProgress` 的字体调用为
  `drawString(String, 0, 0, 0xFFFFFF)`（反编译 `SplashProgress$2` 确认）。
