# Derivative 光影包兼容性说明

兼容状态：**已验证（开启 DoF 场景）**
最后更新：2026-10-09

## 光影包信息

- Derivative Main d24.4.14（`[真实&电影]Derivative Main d24.4.14.zip`，作者 HaringPro）。
- 包内 `world0/composite3.{vsh,fsh}` 转发到 `program/Post/Temporal.{vert,frag}`，负责时序数据（曝光、中心深度）回写。

## 根因与机制

- Derivative 在 `#ifdef DOF_ENABLED` 下**自行声明** `flat out float centerDepthSmooth;`（vertex）/ `flat in float centerDepthSmooth;`（fragment），并在 vertex 的 `main()` 里自行计算赋值——包自己的中心深度平滑实现，经 colortex5 像素 (1,0) 做跨帧反馈。
- Actinium 的 `CompositeDepthTransformer` 原本只要检测到 `centerDepthSmooth` 的声明（不论限定符），就把所有出现处替换为 `texture2D(iris_centerDepthSmooth, vec2(0.5)).r`——该替换本意是把 OptiFine 约定的 `uniform float centerDepthSmooth;`（引擎供值）重定向到引擎纹理。
- 替换同样作用于赋值目标，vertex 里的 `centerDepthSmooth = mix(...)` 被改写成 `texture2D(...).r = mix(...)`，函数调用结果不是左值，驱动报 `error C1034: assignment to non-lvalue`，`composite3.vsh` 编译失败，整个光影管线创建中止并回退原版渲染。
- 触发条件是**开启景深（DoF）**：包默认 `//#define DOF_ENABLED`，DoF 关闭时相关代码被预处理整段移除，不触发替换，因此首次加载正常、打开 DoF 后每次加载必现。
- 修复（`b40e8427`）：仅当当前 stage 把 `centerDepthSmooth` 声明为 `uniform`（引擎供值路径）时才注入 sampler 并重定向读取；包自有 varying/局部变量保持原样。替换串同时由 `texture2D(...)` 改为 `texture(...)`，与 GTNH Angelica 上游一致（core profile 下不存在 `texture2D`，此前仅靠 NVIDIA 驱动的宽容编译未暴露）。

## 验证记录

- 离线回归测试 `CompositeDepthTransformerTest`（红→绿）：
  - 修复前复现的变换输出与生产日志报错逐字吻合（`texture2D ( iris_centerDepthSmooth , vec2 ( 0.5 ) ) . r = mix ( ... )`）；
  - 修复后：包自有 `flat out/in` varying 两阶段均不注入不替换；`uniform float centerDepthSmooth;` 声明的引擎供值路径仍正常重定向；同名局部变量不受影响。
- `./gradlew check --no-daemon`、`./gradlew build --no-daemon`：BUILD SUCCESSFUL。
- 用户实机确认（2026-10-09，crl_t 整合包生产环境，Cleanroom，NVIDIA）：开启 DoF 后光影正常加载，不再回退原版管线。

## 已知疑点（与本次修复无关）

- 同一日志中 DoF **关闭**、管线创建成功时也出现 `checkpoint=composite:matrices` / `final:quad-end error=1282`（GL_INVALID_OPERATION）以及 "Unexpected; somehow the Opaque + Translucent pass ran with shaders on."，疑似独立问题，如复现请单独反馈。
