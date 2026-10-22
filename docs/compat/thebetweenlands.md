# The Betweenlands

## 启动时 GeometryBuffer 纹理注册崩溃（#235）

- 问题：[issue #235](https://github.com/Q-Engineering-Source/Actinium/issues/235)。
- 报告环境：Actinium alpha-0.0.13、The Betweenlands 3.7.3、Minecraft 1.12.2、
  Cleanroom 0.6.13-alpha、Java 25.0.4.1、Windows 11、NVIDIA RTX 4090 Laptop。
- 触发条件：客户端 tick 初始化 Betweenlands 天空渲染资源；`use_shader=false` 仍会触发。
- 状态：代码修复和自动化验证完成，实机启动及渲染回归待确认。

### 根因与触发链

Betweenlands 3.7.3 的 `GeometryBuffer` 构造器调用 `TextureManager.loadTexture(name, this)`
注册自身，但其 `loadTexture` 是空实现。真正的 framebuffer 在后续
`updateGeometryBuffer` 中分配；在此之前，覆盖的 `getGlTextureId()` 返回 `-1`。
此生命周期可在对应版本的
[官方源码](https://github.com/Angry-Pixel/The-Betweenlands/blob/v3.7.3-release/src/main/java/thebetweenlands/client/render/shader/GeometryBuffer.java)
中核对。

Actinium 的 `MixinTextureManagerDebugLabels` 在注册返回时给纹理添加 GL 调试标签，
原先无条件绑定 `getGlTextureId()` 的结果。alpha-0.0.13 中，提交 `8c137889` 为防止
非 2D 纹理被延迟删除逻辑错误缩减，在 `GLStateManager.glBindTexture` 中新增了纹理
target 记录。`TextureInfoCache.getInfo(-1)` 返回 `null`，紧接着的 `setTarget` 因此抛出 NPE。

### 修复

调试标签实现移至 Mixin 包外的 `TextureDebugLabels`。当纹理 owner 返回负数 ID 时，
跳过绑定和命名操作，保持 framebuffer 的分配归属；有效 ID 继续使用原标签流程。
保留无 GL context、display-list 录制和缺失纹理门控，并在命名后通过 `finally`
恢复原纹理绑定。GLSM 的 target 跟踪与缓存语义保持原样。

### 验证记录

- 基于 `main` 的 `b5f92339`，使用 Java 25 工具链。
- `TextureDebugLabelsTest` 直接调用生产 helper，以真实的
  `TextureInfoCache.getInfo(id).setTarget(...)` 重现同一 NPE；同时验证
  `-1 -> 42 -> -1 -> 73` 的附件分配、释放和重建过程。
- 未加负 ID 判断时，两项新增测试均失败；加入修复后均通过。
- `./gradlew check build --no-daemon` 通过：785 项测试，0 失败、4 跳过；
  发布 Jar 结构校验通过。
- 未运行 `runClient`。待实机检查：Cleanroom 0.6.13-alpha + Betweenlands 3.7.3，
  分别使用 `use_shader=false` 和 `true` 启动到主菜单、进入 Betweenlands 维度并检查天空，
  再检查窗口尺寸改变或资源重载后的画面。自动化测试未覆盖真实 GL 绑定恢复与画面表现。
