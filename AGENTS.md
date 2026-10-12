# Actinium

## 项目概述

Actinium 是 Minecraft 1.12.2（Cleanroom Loader）上的渲染与光影兼容模组，目标是在旧版客户端引入现代化渲染管线，同时让光影包、经典模组内容与性能渲染共存。

渲染栈组成：

- `src/main/java/com/dhj/actinium/`：生命周期、配置、兼容层与 Actinium 自有渲染代码。
- `celeritas-common/`：区块构建、上传、裁剪与绘制。
- `glsm/`：OpenGL 状态跟踪、重定向、固定管线模拟与调试设施。
- `shader/`：Iris 风格的光影包解析、GLSL 变换、render targets、uniform 与渲染管线。
- `GTNHLib/`：内存、buffer、后处理与旧版渲染工具。

改动渲染管线前，先读 `docs/architecture.md`；光影包兼容现状见
`docs/compatibility-matrix.md`。

## 问题处理流程

修 bug、做兼容适配、移植上游补丁都走这条流程。每步末尾括号里是**判据**，判据未达成不进入下一步。

1. **弄清问题**：从用户描述或 GitHub issue 还原症状，明确触发条件、期望行为，以及涉及的光影包、模组、渲染距离等环境。（判据：能用一句话写出"什么条件下、看到什么、应该是什么"。）
   - 联网搜索不可用时用 `gh` 取原文：`gh issue view <n> --comments`、`gh pr view <n> --comments`、 `gh api`；上游或相关仓库的补丁用 `git fetch <remote> refs/pull/<n>/head` 拉到本地再读。
2. **建分支**：从最新 `main` 起 `fix/<主题>`、`feat/<主题>` 或 `docs/<主题>` 等等。
3. **读源码**：反编译对应 Minecraft 版本与相关模组的源码。优先用用户给出的仓库路径或可用的 MC mod 开发 MCP；否则用 `javap -p -c` 反编译 `D:/gradle` 缓存中的 jar，如果没有jar，可以优先使用 maven 坐标在 gradle 里同步然后找缓存，这样也方便用户直接在 dev 环境里测试。（判据：能指名症状对应的类与方法。）
4. **定位触发机制**：先用静态分析给出"症状 → 代码"的因果链。静态分析不足以定论时，用日志 DEBUG 取运行时证据，开关按「架构与开发约束」的 DEBUG 规范走游戏内 GUI 设置，一次性二分实验可用临时系统属性但不提交。（判据：能预测每处改动会让行为怎样变化。）
5. **写代码**：遵循本文件与 `CONTRIBUTING.md`；改渲染管线前先读 `docs/architecture.md`。（判据：`./gradlew check --no-daemon` 通过。）
6. **交用户实测**：把改动连同验证场景（光影包、渲染距离、DH 设置等）交给用户实机确认，不要替用户执行 runClient 。（判据：用户确认症状消失且无回归。）
7. **写文档并提交**：确认解决后，把根因、触发机制与验证记录写进 `docs/compat/<模组>.md` 或 `docs/compatibility-matrix.md`，再按 Conventional Commits 提交。
8. **发 PR**：用户明确要求发 PR 时，按 `CONTRIBUTING.md` 的 PR 章节写英文标题与"改了什么 / 为什么 / 如何验证"描述。

## 构建与验证

- 完整构建：`./gradlew build --no-daemon`
- 快速编译检查：`./gradlew compileJava --no-daemon`
- 测试：JUnit 已启用（`src/test`）；`check` 会验证自动化测试及 remap Jar 结构。
- dev 运行：`./gradlew runClient`。崩溃报告与运行日志在 `run/client/crash-reports/` 和 `run/client/logs/latest.log`；排查崩溃时注意堆栈中的 Mixin 行号只是 mixin 后坐标，首因往往在同时间段更早的日志里。
- 发布前必须运行 `build`。
- 工具链为 Java 25，产出字节码面向 Java 21。

## 架构与开发约束

- 子项目依赖方向固定为 `celeritas-common -> glsm -> GTNHLib`。
- 子项目不得引用 `com.dhj.actinium` 实现类；跨边界行为通过 bridge、provider 或小接口注入。
- 新增 render pass 必须明确 framebuffer、program、texture unit、viewport 与混合/深度状态的归属。
- 新增 Mixin 必须加入 `MixinConfigurationTest` 覆盖的配置文件。
- 兼容代码必须由模组存在性检查保护；引用外部模组类的 Mixin 放在 late/conditional 配置中，避免未安装对应模组时触发类加载。
- 长期 DEBUG 开关一律走游戏内 GUI 设置（`SodiumGameOptions.debug.*`，glsm 侧经 `GLSMDebug.isEnabled()` 门控）；禁止把诊断 JVM 参数固化进 `gradle.properties` 或 run 配置。一次性二分实验允许临时系统属性，但不得提交进构建配置。

### Mixin 组织约定

- **mixin 类只做注入**，业务逻辑放在 mixin 包之外的实现类（`compat/`、功能包或工具类），mixin 类保持薄。
- **注入接口与注入类分离**：访问器/调用器（Accessor/Invoker）按现有 `core.terrain.AccessorEntityRenderer` 模式组织；Accessor/Invoker 这类**自身也是 @Mixin、注册进配置**的接口放对应 `mixinterface/` 子包；而**由目标类 implement 的能力接口**（鸭子类型转换用）不是 mixin，必须放 mixin 包外（如 `com.dhj.actinium.texture.*Extension`），实现留在 mixin 类。
- **mixin 包隔离规则**：mixin 包内的辅助类（含内部类/静态嵌套类）不得被 mixin 目标类或其调用方直接引用——会触发 `IllegalClassLoadError`。出现在目标类签名里（如目标类 implement 的接口）时 **dev 也会立刻崩**（2026-10-12 dev 实例：`mixinterface/TextureMetadataExtension` 作为目标类超接口在 define 阶段被加载）；仅被方法体惰性引用时通常只在非 dev 环境强制（2026-08-12 生产崩溃实例：`MixinGlStateTracker$Saved`）。辅助实现与能力接口一律放 mixin 包外（参照 `com.dhj.actinium.texture.TextureMapExtension` 模式）。

### 第三方 addon 绑定契约

- 旧 Celeritas 兼容桥（`src/compatBridge/`）已移除；Celeritas 系 addon 改为直接适配 Actinium 主实现，不再提供 `celeritas` mod id 与 `org.taumc.celeritas` API 镜像。
- 唯一例外：`org.taumc.celeritas.core.CeleritasLoadingPlugin` 是**无成员的探测标记类**，只为让第三方优化模组的"Celeritas 系是否在位"探测（`Class.forName`）命中，使它们走各自内建的让位路径（CensoredASM/Chibi 据此关闭 on-demand animated textures 与 BakedQuad squasher，见 issue #159）。它不是 API 镜像，任何 addon 都不得引用它，也不得往该包添加其它成员。
- `com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRenderer` 的私有成员（`currentState`、`currentBlockAccess`、9 参 `renderQuadList`）以及 `renderBlock` 内部的 `renderQuadList` 自调用点是 addon 的 Mixin 绑定面（承接原桥契约），由 `VintageBlockRendererBindingContractTest` 锁定；重命名或改签名前必须评估 addon 影响。

---

## 基本要求

- 代码中禁止使用完全限定名，优先使用 `import` 导入。
  - 例外：同一文件需要两个简单名相同的类型，而 Java 的 `import` 只能引入其中一个时，未被引入的一侧允许写全限定名。
  - 典型情形是文件自身声明的类型占用了该简单名：`dhj.embeddedt.embeddium.api.shader.BlockRenderLayer` 内部引用 vanilla 的 `net.minecraft.util.BlockRenderLayer` 时只能写全限定；两个同名类型在使用处相遇时同理。
- 所有文件全局使用 **UTF-8 无 BOM** 编码。
- 本环境在 Windows 上通过 Git Bash 执行命令：路径使用正斜杠，注意 CRLF/LF 换行差异，写入文件时避免意外引入 BOM 或乱码。
- 如环境已配置 IntelliJ IDEA 系列 IDE 的 MCP 服务，优先使用。
- Gradle 缓存路径统一设定在 **D 盘**（`D:/gradle`）。
- 回答和思考过程（thinking）使用中文，专业名词可保留英文原文。

---

## 全局开发规范

### 1. 需求确认原则

- 在实现任何需求前，必须先完整、准确地确认需求范围，不得主观臆断或假定未明确的需求。

### 2. TDD（测试驱动开发）流程限制

- TDD 流程**仅用于代码逻辑验证**，以下行为禁止：
  - 编写纯源码 "contain" 类测试（即仅检查文本包含关系）。必须直接调用目标代码进行完整的逻辑验证。
  - 为"验证某功能已被删除"而编写的测试。如果确定功能要移除，可在一次验证后，将测试连同被删代码一并移除。

### 3. 错误处理与日志（Fail Fast）

- 所有代码实现必须遵循 **Fail Fast** 原则，不应出现为了兼容或兜底而刻意编写的防御代码。
- 一旦出现异常或问题，必须输出日志，严禁出现空的 `catch` 块。
- **例外**：对于核心业务逻辑代码，应避免因未捕获异常而导致整个进程崩溃。

### 4. 代码质量与协作禁止项

- 禁止任何形式的**占位实现**，除非用户明确要求（且必须留下 `TODO` 标签标记）。
- 禁止刻意编写**最小实现**来应付任务，这视为偷懒。实现时应一次性全面覆盖需求范围和潜在风险。
- 禁止子代理之间的互相包庇行为，包括但不限于：绕弯实现、向上级代理谎报结果等。

### 5. 图片读取细节限制

- 读取图片默认使用降采样视图（`ReadMediaFile` 默认行为），常规查看不得开启 `full_resolution`。
- 若确需读取原始分辨率细节（如细小文字、密集 UI），必须事先取得用户授权；可优先用
  `region` 参数裁剪局部区域查看。

---

## JVM 系项目开发规范

### 反射使用禁令

1. **严禁**使用反射来实现业务功能或编写测试，除非用户明确要求。此禁令同样适用于代码审查环节。
2. 仅在以下情形允许使用反射：
   - 通用逻辑框架（如动态通信系统、注入式框架等）。
   - 依赖开发过程中遇到无法获取的对象，但必须在反射前说明对象不可达的原因，并完整验证是否已穷尽所有正常入口点。
3. 若 TDD 测试遇到实现瓶颈，应优先引入测试框架解决，不得使用反射或绕过开发规范。
4. **面向接口编程**：所有逻辑层应优先依赖接口，而非具体实现。接口的实现类仅在自身内部使用。
   - 命名规范：接口命名为 `Xxx`，实现类命名为 `XxxImpl`。
5. 接口注释要求：
   - 接口类应有简介。
   - 每个类/方法/字段首次出现时，需说明其动机和需求。
   - 说明每个成员的作用。
6. 对于大对象构造，优先采用 **Builder 模式**。
7. 禁止创建 `XxxService` / `XxxManager` / `XxxController` 等高度可能在后期膨胀为"超级对象"的类。处理逻辑应置于接口实现层或独立逻辑层。

---

## 代理协作架构

采用**两层为主、三层为辅**的协作模式：

- **L1 主代理**：当前主对话，负责规划、调度、审阅与收口。
- **L2 专业子代理**：负责具体领域的分析、实现、测试或文档。
- **L3 执行子代理**：仅在复杂跨模块、多风险、多阶段任务时引入，承接 L2 拆出的明确执行项。

### L1：规划层（主对话代理）

- **身份**：主代理。若未显式作为子代理被派发，则默认以此身份工作。
- **职责**：
  1. 理解用户需求及真实目标。
  2. 拆解任务，生成可执行计划。
  3. 决定哪些任务需 L2 专业层处理，以及复杂任务是否需引入 L3 执行层。
  4. 汇总子代理结果，审阅变更质量与风险。
  5. 处理 Git 分支、暂存、提交等事务。
- **上下文策略**：
  - 保持全局视野。
  - 只长期保留计划大纲、关键决策、风险和子任务结果摘要，不堆积完整代码细节。
- **禁止事项**：
  - 在未获用户确认前，分发未经审查的总体方案。
  - 推送远程仓库，除非用户明确授权。
- **直接执行的例外**：小型单点任务（单文件修改、局部修复、一次性查询）可由 L1
  直接完成；多模块、多阶段任务必须拆解派发，避免主上下文被实现细节占满。

### L2：专业层（coder / explore 子代理）

- **工具形态**：实现、测试、文档类任务派发 `coder` 子代理；只读分析、代码勘察派发
  `explore` 子代理。
- **职责**：
  1. 负责一个模块或领域（如规则 DSL、核心 Hook、Mixin、文档、测试、兼容性研究）。
  2. 阅读该领域的必要代码与文档。
  3. 在 L1 授权范围内，完成该领域的分析、实现、测试或文档任务。
  4. 输出技术方案、接口定义、风险点、建议文件列表或实际变更报告。
  5. 仅在复杂跨模块任务中，可将明确可执行的小任务进一步拆给 L3。
- **输入**：L1 给出的子任务、相关上下文片段、明确的禁止事项及验收标准。
- **输出**：简洁技术方案、需修改/新增的文件列表、关键风险与验证建议。
- **限制**：
  - 仅在 L1 明确授权的任务边界内修改文件。
  - 不得自行扩大任务范围。
  - 遇阻塞问题立即报告。

### L3：执行层（L2 派生的 coder / explore 子代理）

- **职责**：
  1. 依据 L1/L2 给出的具体方案执行文件改动。
  2. 一次只处理一个文件或一组紧密相关的小文件。
  3. 保持改动最小、可审阅、可回滚。
- **输入**：L2 的技术方案、目标文件路径、必要内容或局部上下文、明确验收条件。
- **输出**：修改了哪些文件、具体行为变化、测试/验证结果、残余风险。
- **限制**：
  - 禁止自行扩展任务范围。
  - 禁止跨模块顺手重构。
  - 禁止处理 Git 事务。
  - 若发现方案与代码实际情况不符，立即停止并向上层报告。

### 调度流程

```
L1 规划层
  → 拆解任务与验收标准
  → 派发至 L2 专业层
L2 专业层
  → 执行常规任务，或为复杂跨模块任务细化模块方案、文件列表及风险
  → 仅复杂跨模块任务中，进一步派发给 L3 执行层
L3 执行层
  → 修改明确文件
  → 返回变更与验证结果
L1 规划层
  → 汇总、审阅、补充验证、处理 Git
```

### 并行调度策略

L1 采用**异步非阻塞并行调度**，提升多任务场景下的资源利用率与整体吞吐。此策略不改变
层次职责，只改变 L1 对 L2/L3 子代理的触发与管理方式。

#### 1. 任务分组与依赖解析

- L1 拆解计划时必须显式标注子任务间的**依赖关系**。
- 将无依赖或依赖已就绪的子任务归入待执行池，作为可并行派发的候选集。
- 同一依赖链上的任务保持串行顺序，不同链之间可交叉并行。

#### 2. 并发控制

- **最大并行子代理数**：默认为 `5`，所有类型（explore / coder / review）合计计算。
- 同构批量任务优先使用 AgentSwarm 一次派发；异构独立任务用多个后台 Agent 调用派发。

#### 3. 派发与完成处理

- 派发操作**不阻塞**：优先 `run_in_background`，L1 继续派发其他就绪任务或处理本地工作；仅在后续步骤强依赖其结果时才前台等待。
- 子代理完成由系统通知，不做主动轮询；收到通知后立即审阅输出：
  - **验收通过**：合并成果至全局计划。
  - **失败**：按失败原因决定重新入队（重试最多 2 次）或标记为阻塞项并调整后续计划。

#### 4. 复用与回收

- 同模块连续任务优先 resume 同一子代理，保留其已有上下文。
- 任务彻底结束后不再持有该子代理；explore 类一次性任务完成后不复用。

#### 5. 终止条件

整体任务完成的唯一标准为：待执行池为空**且**活跃子代理数量为 0。

#### 6. 异常隔离

- 单个子代理的执行失败**不得影响**其他并行任务的正常运行。
- L1 必须完整记录所有失败任务的上下文、错误信息及重试轨迹，以便最终人工介入或全局
  补偿决策。

### 子代理协作规范

1. **explore 子代理**：任务执行完毕且已完整阅读其输出后，不再复用。
2. **coder 子代理**：同模块连续任务优先 resume 复用；不再使用时即结束，不长期持有。
3. **review 类子代理**：验收不通过时 resume 原代理继续修改，直至验收通过。
4. **超时参考**：子代理协作的参考超时为 **10 分钟**，大任务可酌情协商延长。

---

## Commit style

- 采用 Conventional Commits 格式：`type(scope): subject`，例如 `fix(shader): keep option sliders visible`。
- `type` 使用小写英文：`feat`、`fix`、`docs`、`refactor`、`test`、`chore`、`perf`、`build`、`ci`、`revert`。
- `scope` 为可选的模块名（如 `shader`、`glsm`、`compat`、`bridge`、`terrain`），不影响理解时可省略。
- `subject` 保持简短、英文、祈使句，不使用 emoji 或中文。
- 破坏性变更在 type 后加 `!`（如 `feat!: ...`），并在提交正文写明 `BREAKING CHANGE` 说明。
