# Outlands 开发环境与 Cleanroom 分发兼容

开发环境使用 `top.outlands.unimined:1.4.48` 和 Outlands `2026.11.03-2`，构建模板参照 `D:/Code/MDK-Unimined/stacks/unimined`。工具链使用 Java 25，分发字节码保持 Java 21。Gradle 缓存使用 `D:/gradle`。

## 加载与打包

开发运行通过 `outlands.dev.extrapath` 装载 `devShadowJar`，使 Actinium 的类由模组类加载器加载。早期 Mixin 继续由 `MixinEarly` 注册，条件 Mixin 继续由 `MixinLate` 按模组检查注册。分发 jar 保留 `FMLCorePlugin`、`FMLCorePluginContainsFMLMod`、`FMLAT`、`ContainedDeps` 和 `NonModDeps`，供旧 Cleanroom 加载。

必须保留旧式模组元数据与 manifest 声明。Actinium 同时使用 LWJGL2 兼容入口和 LWJGL3 API；Outlands 的顶层 `jar` 元数据会将其标记为只使用 LWJGL3 的模组，在 `outlands.lwjgl2.filter=skip` 时跳过必需的调用转换。

Access transformer 源文件为 `src/main/resources/actinium_at.cfg`，构建后位于 `META-INF/actinium_at.cfg`。GLFW 编译依赖显式声明为 `compileOnly`，供旧 Cleanroom 窗口路径编译。

## 启动配置

Outlands 的 `ForgeEarlyConfig` 使用 `openglVersionMajor` 等 lower camel case 字段，旧 Cleanroom 使用 `OPENGL_VERSION_MAJOR` 等字段。两代没有共同的字段访问接口；`ForgeEarlyConfigCompat` 隔离这组名称差异，使分发类不直接链接任一代字段名。

Outlands 将早期配置保存在 `forge.cfg` 的 `early` 区段；旧 Cleanroom 使用 `forge_early.cfg`。创建 OpenGL core context 后恢复配置，避免临时请求影响随后移除 Actinium 的启动。

## 验证

2026-11-11：`./gradlew check build --no-daemon --offline` 通过，根项目 1233 项测试无失败（4 项跳过），GTNHLib 8 项测试通过。分发 jar 保留数组形式的 `mcmod.info`、旧式 manifest 与 `META-INF/actinium_at.cfg`。

已用缓存中的 Cleanroom 0.6.12-alpha MCP jar 替换 Outlands 编译 classpath，重新编译根项目全部源码通过。真实 Cleanroom 0.6.13-alpha universal 配置测试使用同一份 Outlands 编译字节码，验证保存临时 core 请求、恢复 compatibility 设置并重新读取 `forge_early.cfg`，四项属性一致。

开发任务配置检查确认入口为 `top.outlands.foundation.boot.Foundation`，`Actinium-dev.jar` 位于 `outlands.dev.extrapath`，应用类加载器 classpath 中没有该 jar。

客户端验收场景：Outlands 开发启动与分发安装、旧 Cleanroom 分发安装、无光影与光影切换、退出后移除 Actinium 再次启动。客户端由用户运行。
