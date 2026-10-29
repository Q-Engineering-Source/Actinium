# Shader 磁盘缓存启动配置

最后更新：2026-10-22

## 根因与触发条件

Iris 启用时，`AngelicaJar` 原先从 Java `CodeSource` 的 URL 解析当前 Jar 路径。Cleanroom 的一次实际启动返回了 `jar:` CodeSource URI；将它交给 `Paths.get(uri)` 时抛出 `FileSystemNotFoundException`，因此 shader disk cache 没有配置，全量 shader compile 被跳过。这个问题会在启用 Iris、启动时定位已加载 Actinium artifact 的路径采用该 URI 形式时触发。

启动逻辑现从 FML indexed mod container 的 `getSource()` 取得 Actinium artifact `File`，并把 Minecraft `gameDir` 显式传给 `AngelicaJar.configureShaderDiskCache`。规则 Jar 的缓存根目录为 `<gameDir>/angelica/shadercache`，build salt 由 mod version、Jar 大小和修改时间组成；classes directory 开发环境不启用磁盘缓存。

## 验证记录

- `AngelicaJarCacheConfigurationTest` 使用隔离的 artifact 与 game directory，通过真实 `ShaderDiskCache` 配置完成 blob 写入和读取，并验证 classes directory 不会创建缓存。
- 新增缓存专项测试 2 项；`IrisFullShaderCompileTest` 的 9 项既有验证也通过。包含本轮诊断改动的开发工作区统一检查为 1,254 tests、0 failures、0 errors、4 skipped；该总数不是仅包含此修复提交的 clean-tree 测试基线。
- 用户实机日志在 2026-10-22 12:33:40 开始、12:34:02 结束全量编译，记录 `complete=true`、3 folders、21,782 ms；`run/client/angelica/shadercache` 实际有 864 个文件。这确认当次运行配置了缓存并完成全量编译。
- 同一运行在 12:34:54 仍出现 HotSpot native malloc 2 MiB 失败，随后触发 G1 Full GC。shader cache 配置和全量编译恢复不代表 native OOM 已解决。
