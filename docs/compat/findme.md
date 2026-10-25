# FindMe 兼容性说明

兼容状态：**已验证**（延迟粒子批处理保留深度测试状态，用户实机验证通过）
最后更新：2026-10-19

## 复现范围

- Issue：[#202](https://github.com/Q-Engineering-Source/Actinium/issues/202)。
- FindMe：1.1.0-8（CurseForge `291936:2682853`）。
- HEI：4.35.0，提供 FindMe 使用的 JEI 接口。
- 原始报告：Minecraft 1.12.2，关闭光影，Actinium alpha-0.0.11。
- 场景：箱子中放入钻石，在背包中悬停同种物品并按 Y 搜索附近库存。
- 症状：开启 Actinium 后，指向箱子的定位粒子几乎不可见；预期定位提示能够穿过遮挡物显示。

## 根因与触发机制

FindMe 的 `ParticlePosition.renderParticle` 先调用 `Tessellator.draw` 提交已有粒子，
然后关闭深度测试，单独绘制定位粒子，再重新开启深度测试并开始下一批顶点。
这一调用顺序已对照 1.1.0-8 发布版 jar 的字节码核实。

Actinium 的 `ParticleManagerIrisMixin` 在原版粒子层的顶点批次开始后启用
`DeferredDrawBatcher`。`MixinTessellator` 将 FindMe 内部的 `draw` 捕获为延迟范围，
直到原版粒子层末尾才统一回放。

批处理原先记录了纹理、混合与深度写入开关，却没有记录 `GL_DEPTH_TEST`。
FindMe 恢复深度测试后，回放沿用已经开启的深度测试，导致原本应穿墙显示的定位粒子
被箱体或其他遮挡物挡住。

## 修复

- `DeferredDrawBatcher.captureStateKey` 记录深度测试开关，使用现有 long key 的第 48 位。
- 深度测试不同的范围分别分组，`applyStateKey` 在绘制前通过 GLSM 恢复对应开关。
- `exitAndFlush` 保存刷新入口的深度测试状态，并在 `finally` 中恢复。
  这覆盖了只有 FindMe 定位粒子、末尾原版批次为空的情况，避免关闭深度测试的状态
  泄漏到后续渲染。
- dev 运行依赖加入 FindMe 1.1.0-8 与 HEI 4.35.0，方便重复验证。

修复位于通用批处理逻辑中，不引用 FindMe 实现类，也不需要新增模组专用 Mixin。

## 验证记录

- 2026-10-19：`./gradlew check --no-daemon` 与 `./gradlew build --no-daemon` 通过。
- FindMe 与 HEI 已成功解析、完成 MCP remap 并进入 dev 的 `runtimeClasspath`。
- 用户在加入 dev 运行依赖后确认验证通过，原问题解决。
- 本次实机确认未细分光影包、预设与 GPU 配置。

后续回归建议：关闭光影按原步骤搜索箱子；用实心方块遮住箱子；同时观察普通粒子与
定位粒子的遮挡差异；定位粒子结束后确认后续世界渲染的深度测试正常。
