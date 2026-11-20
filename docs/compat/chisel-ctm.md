# Chisel / CTM 区块渲染兼容

## 触发条件与根因

Chisel 充能虚空石的金属边框变体使用 CTM 生成底纹和边框，贴图声明的源层是 `CUTOUT`。当光影包通过 `block.properties` 的 `layer.solid` 将方块移到 `SOLID` 时，旧的区块构建流程也用 `SOLID` 查询模型。CTM 按 Forge 当前层过滤贴图，导致对应的面在生成网格前被丢弃。

原版 `BufferBuilder` 的材质优化也会影响叠层顺序：同一方块同一面的底层留在原 pass 时，后续叠层如果移到更早的 pass，会改变绘制顺序。

## 修复

- CTM 模型按各自支持的 Forge 源层生成面，区块构建器把这些面写入光影包指定的目标层。快速方块渲染、原版方块渲染和延迟区块捕获共用这个规则。
- 原版顶点缓冲转码时，若同一方块同一面已有 quad 留在原 pass，后续叠层保留原材质。光影包显式指定的 pass 禁用材质降级。

## 验证

`gradlew check build --no-daemon` 通过。2026-11-20，用户在 Minecraft 1.12.2、Cleanroom 0.6.13-alpha、Chisel 1.0.2.45 和 CTM 1.0.2.31 的实例中试跑，反馈未发现问题。该次日志记录了关闭光影和启用 `Revelation-dev.zip` 的会话。
