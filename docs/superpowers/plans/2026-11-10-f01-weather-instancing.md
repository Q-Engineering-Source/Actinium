# F01 Weather Instancing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Angelica `f2c07dea` 的天气采样缓存、实例化绘制与 Iris 天气变体适配到 1.12.2，保留原版回退和现有天气选项。

**Architecture:** `WeatherColumns` 负责降水与光照采样缓存；`WeatherRenderer` 负责世界内更新、原版粒子、回退绘制和实例缓冲。glsm 承担无光影天气顶点变体，shader 承担 Iris `gbuffers_weather` 变体，调用方只从现有天气入口接管。

**Tech Stack:** Java 21 字节码、Minecraft 1.12.2、Cleanroom、BufferBuilder、GLSM、Iris、JUnit 5、真实 SDL/OpenGL 测试。

## Global Constraints

- 以上游 `D:/Code/Angelica` 的 `f2c07dea` 为需求来源；接口与依赖分析见 `.superpowers/sdd/f01-weather-analysis.md`。云渲染由独立任务计划覆盖。
- 保留 `weatherQuality` 的半径语义、现有 `EntityRendererIrisMixin` 的 RAIN_SNOW 阶段、天气粒子开关与第三方天气渲染器让位逻辑。
- 无光影、无实例化能力或关闭实例化开关时必须保留完整的 `BufferBuilder` 回退；Iris 开启时使用实际编译链接的天气实例变体。
- 只实现天气需要的实例化能力，禁止为 TEMPLATE/CUBE/PARTICLE 添加占位类型或空实现。实例属性槽避开本地 0..6 的固定管线槽位。
- 禁止业务反射、纯源码 contain 测试、魔法值测试和虚构配置。新增用户开关必须持久化并有 GUI 与语言文案。
- 不移植 DragonAPI、Tracy、flyby、NotFine、SDLGPU 专用代码。新 Mixin 必须加入 `MixinConfigurationTest` 覆盖的配置文件。
- 直接在主仓库 `feat/f01-cloud-weather` 工作。按任务顺序修改并审查，最终集中提交，不按任务创建 PR。

### Task 1: 降水列缓存与温度、光照采样

**Files:**
- Create: `src/main/java/com/dhj/actinium/render/WeatherColumns.java`
- Test: `src/test/java/com/dhj/actinium/render/WeatherColumnsTest.java`

**Interfaces:** 上游 `update(WorldClient, cameraBlockX, cameraBlockZ, radius, tick)`、`revision()`、`covers(x,z)`、`slotAt(x,z)`、按 slot 读取 height/hash/temperature/light/jitter 的访问器。世界采样使用 `WorldClient.getBiome`、`getPrecipitationHeight`、`getCombinedLight`；温度公式使用 `Biome.getTemperature` 在 y=64/65 的样本。测试通过明确的采样入口验证行为，禁止反射或伪造 Forge 启动。

- [ ] 写缓存命中、负坐标环绕、跨半径失效、降水/高度 revision、滚动刷新、温度与光照缓存测试，运行确认 RED。
- [ ] 完整移植上游数组布局与采样缓存，适配 1.12.2 API；使用原版 `Random.nextDouble()` 与原版 int 溢出种子规则，不能引入坐标相关偏差。
- [ ] 重跑测试确认 GREEN，并审查世界切换后弱引用和缓存失效。

### Task 2: 原版天气回退与实例数据写出

**Files:**
- Create: `src/main/java/com/dhj/actinium/render/WeatherRenderer.java`
- Create: `src/main/java/com/dhj/actinium/render/WeatherInstanceWriter.java`
- Modify: `src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/AccessorEntityRenderer.java`
- Test: `src/test/java/com/dhj/actinium/render/WeatherInstanceWriterTest.java`
- Test: `src/test/java/com/dhj/actinium/render/WeatherRendererTest.java`

**Interfaces:** `WeatherRenderer` 消费 Task 1 的缓存与访问器；`WeatherInstanceWriter` 接受列数据与 direct `ByteBuffer`，输出雨实例数、雪实例数和写入位置。`AccessorEntityRenderer` 暴露 `rendererUpdateCount`，不新建平行访问器接口。

- [ ] 测试雨雪分段、跳过空列、STRIDE 字段布局、温度导致的雨雪转换、半径改变与 revision 触发重建，先确认 RED。
- [ ] 用 `PARTICLE_POSITION_TEX_COLOR_LMAP` 实现完整原版回退，保留 UV、透明度、光照、billboard、声音和 `WATER_DROP`/`SMOKE_NORMAL` 粒子行为，补上岩浆分支。
- [ ] 实现实例数据写出与周期重建，保留 20 tick 强制刷新；第三方 `getWeatherRenderer()` 和 Dynamic Surroundings 在位时让位。
- [ ] 重跑测试确认 GREEN，检查回退前后深度写入、混合、剔除、lightmap 状态恢复。

### Task 3: 无光影实例化与配置入口

**Files:**
- Create: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/WeatherInstancedAttribs.java`
- Create: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/WeatherQuadMesh.java`
- Create: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/WeatherParams.java`
- Modify: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/VertexKey.java`
- Modify: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/VertexShaderGenerator.java`
- Modify: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/Program.java`
- Modify: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/Uniforms.java`
- Modify: `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/ffp/ShaderManager.java`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gui/SodiumGameOptions.java`
- Modify: `src/main/java/com/dhj/actinium/gui/ActiniumGameOptionPages.java`
- Modify: `src/main/resources/assets/actinium/lang/en_us.lang`
- Modify: `src/main/resources/assets/actinium/lang/zh_cn.lang`
- Modify: `src/main/resources/assets/actinium/lang/ru_ru.lang`
- Test: `src/glIntegrationTest/java/com/gtnewhorizons/angelica/glsm/ffp/WeatherInstancingGLTest.java`

**Interfaces:** `WeatherQuadMesh.vao()` 创建单位 quad 与实例属性指针；`WeatherInstancedAttribs` 持有三个不冲突的槽位并配置 divisor；`WeatherParams.generation` 驱动三个独立 uniform 的上传。天气绘制只能使用实际支持实例化的后端。

- [ ] 先写真实 GL 用例：编译天气变体，画一列雨与一列雪，验证覆盖区域、纹理、透明度和实例属性，不要求源码字符串匹配。
- [ ] 实现天气顶点分支、实例缓冲、generation 上传与完整 GL 资源释放；用 `GLSMVertexInput` 与现有后端绘制入口，保留状态归属。
- [ ] 新增持久化 `useInstancedWeather` 开关、视频设置入口与三种语言。关闭或不支持时使用 Task 2 回退。
- [ ] 运行 GL 测试与相关 FFP 回归确认 GREEN。

### Task 4: Iris 天气实例变体

**Files:**
- Modify: `shader/src/main/java/net/coderbot/iris/pipeline/transform/AttributeParameters.java`
- Modify: `shader/src/main/java/net/coderbot/iris/pipeline/transform/AttributeTransformer.java`
- Modify: `shader/src/main/java/net/coderbot/iris/pipeline/transform/CoreTransformHelper.java`
- Modify: `shader/src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java`
- Modify: `shader/src/main/java/net/coderbot/iris/uniforms/CommonUniforms.java`
- Modify: `shader/src/main/java/net/coderbot/iris/uniforms/IrisInternalUniforms.java`
- Modify: `shader/src/main/java/net/coderbot/iris/gl/shader/ProgramCreator.java`
- Test: `src/test/java/net/coderbot/iris/pipeline/transform/WeatherInstancedVariantTest.java`
- Test: `src/glIntegrationTest/java/net/coderbot/iris/pipeline/WeatherInstancedProgramGLTest.java`

**Interfaces:** 参数键携带天气实例化维；变换器生成与 FFP 相同的 billboard、UV、透明度公式；pipeline 能查询、构建和绑定天气变体，程序缓存键必须区分普通天气和实例天气。接入上游 `f2c07dea` 的实际改动，不复用其他实例化种类的占位接口。

- [ ] 先验证同一普通程序不会因缓存复用而丢失天气实例属性，再验证变体真实编译与 uniform 绑定，确认 RED。
- [ ] 实现参数键、源码变换、变体构建/绑定、程序生命周期与 uniform 接线；失败时记录原因并使用 Task 2 回退。
- [ ] 接入 `WeatherRenderer` 的 Iris 分支，保留原有 RAIN_SNOW 阶段和粒子路径，运行测试确认 GREEN。

### Task 5: 整批审查、构建与实机验证

**Files:**
- Modify: `docs/sync-angelica-8e099e95.md`
- Modify: `docs/compatibility-matrix.md`
- Modify: `docs/architecture.md`

- [ ] 审查所有上游相关 hunk、Mixin 注册、GL 资源所有权、默认与回退路径，不重复实现已有接口。
- [ ] 运行 `./gradlew check glIntegrationTest build --no-daemon`，确保自动化测试、真实 GL 与 remap jar 校验通过。
- [ ] 部署 Cleanroom 与三个 Prism 实例。验证雨、雪、岩浆粒子、FAST/FANCY 半径、关闭实例化、无光影与 Iris、世界切换及第三方天气让位。用户验收后更新台账，集中提交一个 F01 PR。
