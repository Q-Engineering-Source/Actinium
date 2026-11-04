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

## 验证

- `./gradlew compileJava --no-daemon`、`./gradlew check --no-daemon`、`./gradlew build --no-daemon` 通过。
- 产物 `Actinium-alpha-0.0.14-8169f5c6.jar` 交给用户在 `outlands-2026.11.04-local`（Outlands 2026.11.04、
  Java 25.0.1、Windows 11）实机启动：判据是越过大写字段读取、日志出现
  `Created OpenGL core profile context: ...` 并进入游戏。
