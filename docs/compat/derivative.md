# Derivative 光影包兼容性说明

兼容状态：**已验证（DoF 与植物摆动场景）**
最后更新：2026-11-07

## 光影包信息

- Derivative Main d24.4.14（`[真实&电影]Derivative Main d24.4.14.zip`，作者 HaringPro）。
- 包内 `world0/composite3.{vsh,fsh}` 转发到 `program/Post/Temporal.{vert,frag}`，负责时序数据（曝光、中心深度）回写。

## 景深编译问题

- Derivative 在 `#ifdef DOF_ENABLED` 下**自行声明** `flat out float centerDepthSmooth;`（vertex）/ `flat in float centerDepthSmooth;`（fragment），并在 vertex 的 `main()` 里自行计算赋值——包自己的中心深度平滑实现，经 colortex5 像素 (1,0) 做跨帧反馈。
- Actinium 的 `CompositeDepthTransformer` 原本只要检测到 `centerDepthSmooth` 的声明（不论限定符），就把所有出现处替换为 `texture2D(iris_centerDepthSmooth, vec2(0.5)).r`——该替换本意是把 OptiFine 约定的 `uniform float centerDepthSmooth;`（引擎供值）重定向到引擎纹理。
- 替换同样作用于赋值目标，vertex 里的 `centerDepthSmooth = mix(...)` 被改写成 `texture2D(...).r = mix(...)`，函数调用结果不是左值，驱动报 `error C1034: assignment to non-lvalue`，`composite3.vsh` 编译失败，整个光影管线创建中止并回退原版渲染。
- 触发条件是**开启景深（DoF）**：包默认 `//#define DOF_ENABLED`，DoF 关闭时相关代码被预处理整段移除，不触发替换，因此首次加载正常、打开 DoF 后每次加载必现。
- 修复（`b40e8427`）：仅当当前 stage 把 `centerDepthSmooth` 声明为 `uniform`（引擎供值路径）时才注入 sampler 并重定向读取；包自有 varying/局部变量保持原样。替换串同时由 `texture2D(...)` 改为 `texture(...)`，与 GTNH Angelica 上游一致（core profile 下不存在 `texture2D`，此前仅靠 NVIDIA 驱动的宽容编译未暴露）。

## 植物材质与摆动

- `Terrain.vert` 通过 `mc_Entity.x - 1e4` 得到植物材质，并通过 `mc_midTexCoord.y` 计算草叶各顶点的摆动权重。普通草和花分别需要 10001、10003，双格植物上下半格需要 10004、10005。
- 版本分支最初把任何非空 `MC_VERSION` 回退段都判为旧版支持，导致原本不支持 1.12.2 的 Derivative 使用残留旧分支。现在只有显式声明 `11202` 的包走旧分支，其余统一读取现代条目并转换到 1.12.2。规则表改为有序映射，保持先声明规则优先。
- 现代 `block.10001` 在 `#if MC_VERSION < 12003` 条件块中使用反斜杠续行，预处理后空白行使 `grass` 或 `short_grass` 脱离属性值。修复会折叠续行后的空白行，同时在下一项 `key=` 前结束当前值，普通草恢复 10001，后续规则键保持独立。
- 带 `half` 等状态属性的条目始终经过 `FlatteningMap`。双格植物上半格在区块构建时从实际状态读取下半格的 `VARIANT`，生成 `0x8 | variant`，不再把 `0x8 | facing` 错当成品种。快速区块构建与 vanilla quad 转换共用 `resolveShaderMetadata`，snowy 等高位状态保持不变。
- 空 `cauldron` 与水 `cauldron` 分别使用元数据 0、1–3；没有 `water_cauldron` 规则时，裸 `cauldron` 仍匹配全部元数据，避免影响只声明方块通配规则的光影包。
- 对原版注册表不存在的现代名称，如果全部已注册方块中只有一个相同 registry path 的模组方块，则使用该唯一提供者。多个同名提供者时不建立映射。`farmers_future_delight:cornflower` 因此可以命中 Derivative 的花材质 10003。

## 验证记录

- 离线回归测试 `CompositeDepthTransformerTest`（红→绿）：
  - 修复前复现的变换输出与生产日志报错逐字吻合（`texture2D ( iris_centerDepthSmooth , vec2 ( 0.5 ) ) . r = mix ( ... )`）；
  - 修复后：包自有 `flat out/in` varying 两阶段均不注入不替换；`uniform float centerDepthSmooth;` 声明的引擎供值路径仍正常重定向；同名局部变量不受影响。
- `IdMapTest` 与 `BlockMaterialMappingTest` 覆盖版本分支、规则顺序、续行、植物材质、上下半格元数据和炼药锅水位；`./gradlew check --no-daemon`、`./gradlew build --no-daemon` 均通过。
- 用户实机确认（2026-10-09，crl_t 整合包生产环境，Cleanroom，NVIDIA）：开启 DoF 后光影正常加载，不再回退原版管线。
- 用户实机确认（2026-11-07，crl_t 整合包生产环境，Cleanroom）：普通草、黄色蒲公英等单格植物正常摆动，双格高草上下段连续；Farmer's Future Delight 1.5.1 的蓝色矢车菊正常摆动。部署 Jar 与完整构建产物的 SHA-256 一致。

## 已知疑点（与本次修复无关）

- 同一日志中 DoF **关闭**、管线创建成功时也出现 `checkpoint=composite:matrices` / `final:quad-end error=1282`（GL_INVALID_OPERATION）以及 "Unexpected; somehow the Opaque + Translucent pass ran with shaders on."，疑似独立问题，如复现请单独反馈。
