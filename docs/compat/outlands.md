# Outlands 早期 OpenGL 配置字段改名

## 现象

Outlands `2026.11.04` 上启动即崩溃，栈顶在 Actinium 创建上下文的位置：

```
java.lang.NoSuchFieldError: Class net.minecraftforge.common.ForgeEarlyConfig does not have member field 'int OPENGL_VERSION_MAJOR'
  at com.dhj.actinium.startup.CoreProfileDisplayCreator.create(CoreProfileDisplayCreator.java:41)
  at net.minecraft.client.Minecraft.redirect$zzl000$actinium$celeritas$createCoreProfileDisplay(Minecraft.java:4363)
  at net.minecraft.client.Minecraft.func_175609_am(Minecraft.java:620)
```

## 根因

Outlands `origin/main` 的提交 `285191ab`（`refactor(config): Move ForgeEarlyConfig into one forge.cfg`，
2026-11-03）把 `ForgeEarlyConfig` 从 `@Config` 类改成 `config/forge.cfg` 的 `[early]` 分类，早期 OpenGL
字段随之小写化：

| Cleanroom（Actinium 编译目标 `0.6.12-alpha`） | Outlands 2026.11.04 |
| --- | --- |
| `OPENGL_VERSION_MAJOR` / `OPENGL_VERSION_MINOR` | `openglVersionMajor` / `openglVersionMinor` |
| `OPENGL_COMPAT_PROFILE` | `openglCompatProfile` |
| `OPENGL_DEBUG_CONTEXT` | `openglDebugContext` |

Actinium 编译时绑定的是 Cleanroom 的大写字段名，运行到新版 Outlands 上时 JVM 在第一条 `getstatic` 处抛
`NoSuchFieldError`，`createDisplay` 还没有走到建窗口就中止了。

同一提交也删掉了 `ConfigManager` 对 `ForgeEarlyConfig` 的注册，所以恢复兼容 profile 时调用的
`ConfigManager.sync(ForgeEarlyConfig.class)` 在 Outlands 上同样不成立。

## 两种窗口 shim 的上下文来源不同

- **Cleanroom（`lwjglxx`，GLFW）**：`org.lwjglx.opengl.Display.create()` 的字节码先读
  `ForgeEarlyConfig.OPENGL_VERSION_MAJOR/MINOR`，`Display.create(PixelFormat, ContextAttribs)`
  只是记录 attribs 后转调无参 `create()`，版本、兼容 profile、KHR_debug 全部来自配置字段。Actinium
  必须在每次探测前把请求写进这些字段，否则拿到的是配置里的兼容 profile 上下文。
- **Outlands（`org/lwjgl/opengl/Display.java`，SDL3）**：`resolveContextMajor` / `resolveContextMinor` /
  `GL_CONTEXT_PROFILE_MASK` 全部以 `hasContextAttribs` 优先，只有调用方没传 attribs 时才回退
  `ForgeEarlyConfig`。`Display.create(PixelFormat, ContextAttribs)` 本身就是完整的“按显式属性创建上下文”
  入口。

因此 Outlands 上需要的是“不写配置字段，只传 attribs”，而“是否需要写字段”由运行时是否提供这些字段决定。

## Actinium 的处理

`startup/ForgeEarlyGlConfig` 集中承载全部 `ForgeEarlyConfig` 字段访问：

- `available()` 在首次调用时读取 `OPENGL_VERSION_MAJOR`，捕获 `NoSuchFieldError` 判定该运行时不提供早期
  OpenGL 字段，结果缓存并各记录一条 INFO。
- 字段存在时 `capture()` / `apply(major, minor, debug)` 逐次探测写入 core-profile 请求，
  `restoreAndPersist()` 恢复兼容 profile 并同步回 `forge_early.cfg`。
- 字段不存在时 `CoreProfileDisplayCreator` 传入空实例，全程不读写配置，也不调用 `ConfigManager.sync`。

`CoreProfileContextAttributes` 与 `CoreProfileDisplayCreator` 一并归入 `startup` 包，`debug` 只保留诊断配置与
`OpenGlVersion` 解析。

## 后续暴露的两个问题

字段问题修掉后，Outlands 上又暴露两处与显示无关的缺陷，都已在同一分支修掉：

- **内嵌库注入时机**：`antlr4-runtime` 与 `glsl-transformation-lib` 原本以 `ContainedDeps` 分发，
  加载器把它们解压到 `mods/<mcversion>/` 之后才注入 classpath，而 splash 线程已经先用字体批处理编译了
  着色器，于是启动即 `NoClassDefFoundError: org/antlr/v4/runtime/CharStream`。改为通过 `shadowBundle`
  打进 jar 本体后，类随 mod jar 一起进入 classpath，不再依赖注入顺序。
- **栈 ID 计数**：首次 glsm 上下文初始化在 `Display.create` 内部进行时会嵌套构造另一个上下文状态，
  `StackIdAllocator` 的全局计数游标被嵌套帧清成 `-1`，外层 `endContext()` 抛
  `stack id count changed across contexts: 421 -> -1`。回退到下一个版本恰好绕开这段，看起来像"4.6 不被
  支持"。改为每个 `beginContext()` 帧独立计数后，4.6 core 直接创建成功。

## 验证

- `./gradlew check --no-daemon`、`./gradlew build --no-daemon` 通过。
- Outlands 2026.11.05 + Foundation 1.0.3、Java 25.0.4.1、Windows 11、NVIDIA RTX 5070 Laptop（驱动
  621.01）：客户端进入主菜单并进入世界，日志为
  `Created OpenGL core profile context: requested=4.6 (debug=false), actual=4.6 (4.6.0 NVIDIA 621.01)`，
  `Max GLSL version: 4600`，无被拒版本行。
- `@ Pre startup` 的 `1280: Invalid enum` 在本次修复之前带 Actinium 的运行里就已经存在，与本文记录的
  三项修复无关。
