# F01 Cloud Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同步 Angelica 的云盘几何、分层云网格和渲染生命周期，保留 1.12.2 的原版云选项、Iris 云颜色、Forge 自定义云与既有天空投影。

**Architecture:** 纯几何类型生成可复用的云盘与分层形状；GL 网格负责上传、索引与顶点属性；`CloudLayerRenderer` 负责单层构建和绘制；`CloudRenderer` 负责设置、世界生命周期和渲染入口。天气列缓存与实例化在独立计划中实现，最后集中验收。

**Tech Stack:** Java 21 字节码、Minecraft 1.12.2、GLSM、GTNHLib native buffers、Iris、JUnit 5、真实 SDL/OpenGL 测试。

## Global Constraints

- 上游来源为 `939e3146`、`c578970a`、`888a4de0`、`7f3fb35e`、`21a054a4`、`ea50e0a0` 的云部分；接口核对见 `.superpowers/sdd/f01-cloud-analysis.md`。
- 不引入 NotFine、SDLGPU、Tracy、flyby 专用代码。包名与 GL 调用适配项目已有 `dhj.embeddedt.embeddium`、GLSM 和 `ViewPortStateStack`。
- `CloudVertexMesh` 必须为 Iris 提供 location 1 的 packed COLOR 属性，并同步所有 stride/offset；不能在光影开启时简单退回原版。
- 保留三态云设置、实际 render distance、低视距天空投影、RAIN/CLOUD 阶段、anaglyph、Forge `world.provider.getCloudRenderer()` 优先接管和第三方天空行为。
- 后台只做 CPU emit，GL 对象创建与上传在渲染线程。退出世界必须释放程序、VAO、EBO 与 native emit/sorted buffers。
- GalaxySpace 的 1.12.2 云数据由 AsmodeusCore 持有，不伪造上游四层参数表；提供公开扩展点并保留其接管路径。
- 禁止反射测试、纯源码 contain 测试和虚构 GL 验证。新 Mixin 必须加入 `MixinConfigurationTest` 覆盖的配置文件。

### Task 1: 云盘与分层几何

**Files:**
- Create: `src/main/java/com/gtnewhorizons/angelica/api/clouds/CloudLayer.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/api/clouds/CloudLayerProvider.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudDisc.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudShape.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudPlateCover.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudView.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudDiscTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudShapeTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudPlateCoverTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudViewTest.java`

**Interfaces:** `CloudLayerProvider` 提供云层集合；`CloudShape` 读取纹理单元；`CloudPlateCover` 生成不重叠的矩形；`CloudView` 提供相机相对偏移与视野范围；`CloudDisc` 负责扇区、墙面与圆盘 LOD 坐标。

- [ ] 用真实 ByteBuffer 与 joml 写扇区归属、负锚点、透明格剔除、粗化偶数边、矩形覆盖、透视与上下云面测试，确认 RED。
- [ ] 移植完整几何算法及后续 wrap 修正，适配 1.12.2 的投影矩阵来源，确认 GREEN。

### Task 2: GL 网格、程序与颜色属性

**Files:**
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudUniforms.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudRenderResources.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudVertexMesh.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudFaceMesh.java`
- Create: `src/main/resources/assets/angelica/shaders/cloud.vert`
- Create: `src/main/resources/assets/angelica/shaders/cloud.frag`
- Create: `src/main/resources/assets/angelica/shaders/cloud_faces.vert`
- Create: `src/main/resources/assets/angelica/shaders/cloud_faces.frag`
- Create: `src/main/resources/assets/angelica/shaders/include/cloud_fog.glsl`
- Test: `src/glIntegrationTest/java/com/gtnewhorizons/angelica/render/CloudVertexMeshGLTest.java`
- Test: `src/glIntegrationTest/java/com/gtnewhorizons/angelica/render/CloudRenderResourcesGLTest.java`

**Interfaces:** `CloudVertexMesh` 提供上传、排序、绑定与释放；`CloudFaceMesh` 提供整数面属性与 divisor；`CloudRenderResources` 管理 shader、纹理与 framebuffer 状态。颜色使用本地 `VertexFormatElement.Usage.COLOR` 的 attribute location 1，position/UV/normal/shape 使用既有约定。

- [ ] 写真实 GL 属性布局、索引分段、颜色读取、程序编译链接与 native 释放测试，确认 RED；覆盖上游 `ea50e0a0` 的 4 字节对齐。
- [ ] 实现网格上传、shader 和资源生命周期。CPU 端可使用 `MemoryUtilities`，退出必须 `memFree`，不能只释放 GL 名称。
- [ ] 运行真实 GL 测试与相关 VAO/texture 回归，确认 GREEN。

### Task 3: 层渲染器、主机与世界入口

**Files:**
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudLayerRenderer.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudLayerPlan.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudWedgeArc.java`
- Create: `src/main/java/com/gtnewhorizons/angelica/render/CloudRenderer.java`
- Create: `src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinRenderGlobalClouds.java`
- Create: `src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinEntityRendererClouds.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/ActiniumWorldRenderer.java`
- Modify: `src/main/java/com/dhj/actinium/mixin/vintage/core/MixinMinecraft.java`
- Modify: `src/main/resources/mixins.actinium.vintage.json`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudLayerPlanTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudWedgeArcTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudLayerRendererTest.java`
- Test: `src/test/java/com/gtnewhorizons/angelica/render/CloudRendererTest.java`
- Test: `src/test/java/net/coderbot/iris/uniforms/CloudTimeUniformTest.java`

**Interfaces:** `CloudLayerRenderer.render(float partialTicks, CloudView view)`；`CloudRenderer` 提供 `render(...)`、`invalidateWorld()` 与 `getRequiredFarPlaneDistance()`；世界退出调用 `invalidateWorld()`。CPU 构建产物与 GL 上传之间必须有明确所有权。

- [ ] 先验证三态云开关、低视距、颜色、纹理 wrap、不同相机位置与重复退出；测试用例先 RED。
- [ ] 实现分层构建和渲染，保留正确 camera、far-plane 与 Iris 云颜色；不覆写 anaglyph 的外层 colorMask，不改变既有天气阶段。
- [ ] 接入 `RenderGlobal.renderClouds(FIDDD)V`、`renderCloudsCheck` 和 `ActiniumWorldRenderer.unloadWorld()`；保留 Forge 自定义云优先权，非 MobileGlues 的 GLES 走原版路径。
- [ ] 运行全部相关单元与真实 GL 测试确认 GREEN。

### Task 4: 审查、文档与实机验收

**Files:**
- Modify: `docs/sync-angelica-8e099e95.md`
- Modify: `docs/compatibility-matrix.md`
- Modify: `docs/architecture.md`
- Create: `docs/compat/clouds.md`

- [ ] 审查上游六个提交的云 hunk、GL 状态归属、后台构建与退出释放，确认没有把不适用于 1.12.2 的 GalaxySpace 参数硬编码进本地。
- [ ] 运行 `./gradlew check glIntegrationTest build --no-daemon`，验证 remap jar 与全部自动化测试。
- [ ] 部署四个实例，验证 OFF/FAST/FANCY、2/3/8/16 视距、云层上下、传送与退出、光影云颜色和已有 GalaxySpace 环境。用户验收后更新台账，与天气改动集中提交一个 F01 PR。
