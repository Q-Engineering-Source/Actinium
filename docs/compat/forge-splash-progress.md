# Forge SplashProgress 启动崩溃（issue #216）

最后更新：2026-10-19。修复提交：`7a5b9361`。

## 现象与报告环境

Forge splash 在绘制内存条时崩溃，异常为 `VertexAttribState.current` 为 null，调用链为：

`SplashProgress$2.drawMemoryBar` → `BatchingFontRenderer.drawString` → `flushBatch` →
`GLStateManager.glBindVertexArray` → `VertexAttribState.onBindVertexArray` →
`recomputeClientSideCount`。

issue 报告环境为 Enigmatica 2 Expert Unofficial 1.3.9.2 / Prism Launcher、Minecraft 1.12.2、
Cleanroom 0.6.13-alpha、CleanMix 0.7.2、MixinBooter 11.8、Java 25.0.1、Windows 11；报告中的
Actinium 版本是 `alpha-0.0.12`。移除 Actinium 后该实例可进入主菜单。

## 根因与修复

`GLStateManager.reset()` 会清空顶点属性跟踪，并把 `VertexAttribState.current` 设为 null。启动期间，
游戏线程可在 splash 线程绑定 VAO 的同时执行 reset。旧版 `onBindVertexArray` 把 `current` 设为 map 中的
属性数组后，再通过共享字段 `current[i]` 重算计数；若 reset 恰在两步之间清空该字段，重算就会抛出 NPE。

修复将 VAO map 的初始化、绑定、删除和 reset 操作串行化。reset 会为传入的当前绑定 VAO 创建新的空属性数组，
因此后续状态更新仍有有效目标；绑定时的计数重算直接使用本次绑定取得的数组快照。无参 `reset()` 保留，供原有
调用方使用。

## 验证记录

- 回归用例 `VertexAttribStateTest.resetKeepsCurrentVaoReadyForAttributeUpdates` 在旧实现上于 reset 后更新属性时抛出 NPE；修复后通过。
- `./gradlew check --no-daemon` 通过。
- 用户确认修复后 dev 客户端可正常启动；issue 报告中的 E2Eu 整合包完整复测情况未单独报告。
