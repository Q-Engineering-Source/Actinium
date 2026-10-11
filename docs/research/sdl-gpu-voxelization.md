# SDL GPU 阴影 voxelization（调用方接线）

> 状态：**已落地，未做实机验证**（2026-10-11）。上游 `80e5b544`（#2188
> "Fix voxeliation performance under SDL GPU"）的 API 部分此前已由 `074eca26` 同步；
> 本文档记录**本次补完的部分**：`src/` 侧 voxelization 主体（区域化 range 表 + sink）、
> shader 模块的 voxelization compute、以及 Actinium 自己的**调用方接线**。

## 1. 这个特性解决什么

光影包可以在 **阴影地形 pass 的顶点阶段**写 image（colored lighting 一类效果）。
SDL GPU 后端没有"顶点阶段写 image"这种东西：`RwImageStoreExtractor` 把这些 image
写入从顶点/片元着色器里摘出来，生成一个 **compute 前置 pass**，由它重放该 pass 的顶点。

重放的前提是知道「这次 pass 到底要画哪些顶点」。上游的 `ShadowVoxelizer` 就是干这个的：
它走一遍该 pass 的 chunk render list，把每个 render region 的顶点 run 收集起来；
`sdlgpu` 后端再用 `voxelizeRegion(...)` 把这些 run 一次性派发出去。

`#2188` 的性能修正点：

- 旧实现每个 vertex run 一次 `beginVoxelizationBatch` + `dispatch`（run 数量级 = 上千次
  compute encoder）；
- 新实现把 run 归并成**每个 region 一条 range 表**，一个 pass **只开一个 compute encoder**，
  顶点缓冲重绑从「每次 run」降到「每个 region」，顶点总数超过 `65535*64` 时按
  `invocationBase` 分批（`VoxelizationDispatcher.dispatchRegion`）。

## 2. 调用图

### 上游（Angelica）

```
AngelicaChunkRenderer.render(matrices, commandList, renderLists, renderPass, occlusionCamera, camera)
  └─ voxelizeShadowTerrain(...)                        ← 每个地形 pass 调用一次
       ├─ 门控：isSDLGPU() / ShadowRenderingState.areShadowsCurrentlyBeingRendered()
       │        / renderLists.hasPass(pass) / pipeline 是 DeferredWorldRenderingPipeline
       │        / deferred.getShadowVoxelizationCompute() != null
       ├─ voxelCompute.use(); deferred.prepareShadowVoxelizationCompute(matrices.modelView())
       ├─ shadowVoxelizer.walkPass(renderLists, pass, pass.vertexType().getVertexFormat(),
       │                           camera, occlusionCamera, useBlockFaceCulling(), sink)
       │     └─ sink.region(...) / sink.range(...)      ← 收集，不派发
       ├─ shadowVoxelSink.flush()                        ← 上传 range 表 + 逐 region 派发
       └─ shadowVoxelSink.discard()                      ← 无论成功与否都清空表
```

`DeferredWorldRenderingPipeline` 侧：`createShadowVoxelizationCompute()` 从
`celeritasTerrainPipeline.getShadowVoxelizationComputeSource()`（= SHADOW pass 的
`PatchShaderType.COMPUTE` 源）编译出 compute program，并绑定
`iris_ModelViewMatrix` / `iris_NormalMatrix`；`prepareShadowVoxelizationCompute(modelView)`
在派发前推这些矩阵与 custom uniforms。

compute 内部（`RwImageStoreExtractor` 生成的 CHUNK 模式，已先前同步）：

```
_g = _vg_invocationBase + gl_GlobalInvocationID.x;   if (_g >= _vg_vertexTotal) return;
在 _vg_ranges[rangeBase ..+rangeCount-1] 上二分，找到包含 _g 的 run → id = r.x + (_g - r.y)
_vg_unpack(id) → 重放该顶点的属性 → 原顶点着色器主体 → image 写入
```

### Actinium（本次接线）

Actinium 没有 `AngelicaChunkRenderer`：chunk renderer 是
`com.dhj.actinium.render.terrain.VintageRenderSectionManager` 的私有嵌套类
`ChunkRenderer extends DefaultChunkRenderer`。接线点与上游一一对应：

```
VintageRenderSectionManager.renderLayer(...)   （RenderSectionManager 每 pass 调用）
  └─ DefaultChunkRenderer.render(...)          ← 被 ChunkRenderer 覆写
       ChunkRenderer.render(...)
         ├─ voxelizeShadowTerrain(...)          ← 本次新增
         │     └─ 同上：门控 → prepare → walkPass → flush → discard
         └─ super.render(...)
ChunkRenderer.delete(commandList) → shadowVoxelSink.delete()   ← 释放 range SSBO
```

门的判据与上游一致，`ShadowRenderingState` 用 shader 模块的
`net.coderbot.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()`。

## 3. 数据流

| 步骤 | 代码 | GPU 侧 |
|---|---|---|
| 收集 | `VoxelRangeTable.beginRegion/addRange` | CPU 侧 `uvec2[]`（起点全局顶点号、该 run 的 region 内顶点号） |
| 合并 | `addRange` 里相邻 run 折叠（`vertexStart == runEnd`） | 减少条目数 |
| 上传 | `SdlShadowVoxelizationSink.upload()` | `binding = VG_RANGES_SSBO_BINDING(10)` 的 SSBO，`GL_STREAM_DRAW`，容量按 2 倍增长 |
| 顶点缓冲 | `flush()` 里 `RenderSystem.bindBufferBase(SSBO, VG_VBUF_SSBO_BINDING(9), regionVbuf(i))` | 每个 region 绑一次；`voxelizeRegion(..., rebindVertexBuffer = i > 0, ...)` |
| 派发 | `beginVoxelizationBatch(9)` → 逐 region `voxelizeRegion(...)` → `endVoxelizationBatch(pass)` | 一个 compute encoder 覆盖整 pass |
| 后端 | `SDLGPURenderBackend.voxelizeRegion` | `u_RegionOffset`(vec3) + 4 个 int uniform + `dispatchRegion` |

## 4. 文件与偏差

| 上游 | Actinium | 说明 |
|---|---|---|
| `rendering/voxelization/VoxelRangeTable.java`（新，+125） | `src/main/java/com/dhj/actinium/render/voxelization/VoxelRangeTable.java` | 逐行移植，仅改包名。放在 `com.dhj.actinium.render.*`（Actinium 自有渲染代码的命名空间，与 `render.terrain` 同级） |
| `rendering/voxelization/ShadowVoxelizer.java`（±31） | 同目录 `ShadowVoxelizer.java` | 逐行移植；imports 由 `org.embeddedt.embeddium.impl.*` 改为 Actinium 的 `dhj.embeddedt.embeddium.impl.*` |
| `rendering/voxelization/SdlShadowVoxelizationSink.java`（±105） | 同目录 `SdlShadowVoxelizationSink.java` | **1 处偏差**：上游 `region.getResources()`（无参），Actinium 的 `RenderRegion.getResources(GlVertexFormat)` 需要格式 → 用 `sink.region(...)` 已有的 `format` 参数（`getResources` 按 stride 查找）。见 §6 |
| `rendering/celeritas/AngelicaChunkRenderer.java`（±5，sink 字段/delete/调用） | `render/terrain/VintageRenderSectionManager.java` 的 `ChunkRenderer` | 上游类在 Actinium 对应物是 `ChunkRenderer`；调用点（`render` 覆写）与 sink 生命周期（`delete`）按同一形状落地 |
| `profiling/TracyFramePlots.java`（±4） | — | **跳过**：Actinium 没有 game 侧 plot 聚合器（`SdlFramePlots` 是 SDL 侧 plot，且 sdl-gpu 不能引用 `com.dhj.actinium`）。见 §5 |
| `iris/pipeline/transform/RwImageStoreExtractor.java`（+21） | shader 模块同名类 | **已在此前的同步里落地**（`_vg_rangeBase/_vg_rangeCount/_vg_vertexTotal/_vg_invocationBase` + `_vg_ranges` 二分），本次未改动 |
| `glsm/RenderBackend`、`sdlgpu/SDLGPURenderBackend`、`sdlgpu/compute/VoxelizationDispatcher`（±3/±32/±21） | 同上 | **已由 `074eca26` 落地**；Actinium 版本用后来的 `InvocationDispatch.dispatch(...)` 承载分批上界 |
| 测试 `VoxelizationDispatcherTest`、`MslCrossCompileTest`、`RwImageStoreExtractorTest` | 同 | **已由 `074eca26` 落地**（本次确认其中已含 range 表断言） |
| 删除 `GlsmSdlVoxelRegionBindTest` | — | Actinium 从未有过该 rig 测试，无需动作 |
| 测试 `VoxelRangeTableTest`（新）、`ShadowVoxelizerTest`（改写） | `src/test/java/com/dhj/actinium/render/voxelization/` | 见 §5 |

**新增的两个 shader 模块接口**（上游在更早的 `8da561d3` 就有，Actinium 未同步）：

- `CeleritasTerrainPipeline.getShadowVoxelizationComputeSource()`：返回 SHADOW pass 的
  `PatchShaderType.COMPUTE` 源。同时补上 `setSource(PatchShaderType.COMPUTE, ...)`
  ——**这是本特性的关键缺口**：Actinium 的 pass 源填充循环此前只写
  VERTEX/GEOMETRY/FRAGMENT，不写 COMPUTE，即使 compute 源已由
  `ShaderTransformer.extractRwImageStores` 生成也会被丢掉。
- `DeferredWorldRenderingPipeline`：`shadowVoxelizationCompute` 字段、
  `createShadowVoxelizationCompute()`、`getShadowVoxelizationCompute()`、
  `bindVoxelizationMatrixUniforms()`、`prepareShadowVoxelizationCompute(Matrix4fc)`、
  `destroy()` 里的销毁。实现按 Actinium 既有 `createShadowComputes` 的写法（
  `ProgramBuilder.beginCompute` + `IrisSamplers`/`IrisImages` + `customUniforms`），
  但 image 绑定保留上游的**按名字排序**顺序：`RwImageStoreExtractor` 生成的
  `layout(binding = i)` 就是按排序后的名字编号的，顺序不一致会把写入落到错误的 image。

## 5. 跳过项与测试

**跳过**：上游 `TracyFramePlots` 的三条 plot（`voxelization.encoders/ranges/regions`）。
它唯一的消费者就是那个 plot 聚合器，Actinium 没有对应物，因此
`SdlShadowVoxelizationSink` 里配套的 `takeEncoders()/takeRegions()/takeRanges()` 计数
也**没有移植**（否则就是无人读取的死代码）。将来若加回 plot 聚合器，需要一并补计数。

**测试**：

- `VoxelRangeTableTest`（新，4 例）：条目打包、跨 region 不合并、连续 run 折叠、
  增长与 `clear()` 复用。纯内存（GTNHLib bytebuf），任何环境都能跑。
- `ShadowVoxelizerTest`（改写，10 例）：多 section 的 run 提取、camera 相对的 region
  偏移、region 原点（128 格）而非其它网格、连续 facing 折叠、facing 空洞切分 run、
  空 section 不宣告 region、缺 storage 跳过、region 拒绝时整段不发、背面剔除收窄、
  FULL/COMPACT 两条布局给出相同 span。
  **相对上游测试的改写**：上游用 `sun.misc.Unsafe.allocateInstance` +
  `setAccessible` 反射塞私有字段（`AGENTS.md` 禁止测试用反射）。这里全部换成公开 API：
  `RenderRegion.createStorage(pass, config)` 建 storage、`storage.setMeshes(...)`
  写行数据（配一个不被 arena 拥有的 `GlBufferSegment` 子类，避免 delete 时 NPE）、
  `ChunkRenderList.add(sectionIndex, flags)` 填 render list，
  `RenderRegion` 的包私有构造器由一个测试工厂
  `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/region/TestRenderRegions.java`
  提供（与既有 region 测试同包，仓库惯例）。
  **运行前提**：storage 的元数据堆来自 `LWJGLServiceProvider` 的原生分配器，而
  GL 版服务在无 GL context 的测试 JVM 里会初始化失败。测试在 `@BeforeAll` 里
  安装 SDL 后端的伪造 GLCapabilities（`Lwjgl3GLCapabilitiesShim`，
  不触发任何 GL 调用）让服务可用，`@AfterAll` 清除；若同 JVM 中更早的测试已经把
  服务类初始化失败（`SectionRenderDataUnsafeTest` 的检测路径），则整体 assume 跳过
  ——与 `SectionRenderDataUnsafeTest` 同一惯例。根工程因此新增
  `testImplementation project(':sdl-gpu')`。

## 6. 与 celeritas-common 的对齐（3 处）

上游 Angelica 依赖的 Celeritas 2.5.12 里这几个成员是公开的，Actinium 的 vendored
副本私有化/遗漏了，voxelizer 需要它们：

| 文件 | 改动 | 原因 |
|---|---|---|
| `render/chunk/multidraw/BatchAssembler` | `packRuns` / `runCount` / `runFirst` / `runLast` 由 `private` 改 `public` | voxelizer 用同一套 run 打包（`javap` 确认 Celeritas 2.5.12 为 public）；否则要复制位打包算法 |
| `render/chunk/lists/ChunkRenderListIterable` | 新增 `ChunkRenderList getRegion(int index)` | voxelizer 需按 pass 顺序（正序/逆序）索引 region |
| `render/chunk/lists/SortedRenderLists` | 实现 `getRegion(int)`（`lists.get(index)`） | 同上；唯一实现类 |

## 7. 遗留风险与实机验证建议

- **未做运行时验证**：整条路径只有在「SDL 后端 + 写 image 的光影包 + 阴影开启」时才激活。
  实机建议：用 SDL 后端加载一个会在 shadow terrain 阶段写 image 的光影包（colored
  lighting 类），确认 ①日志无 `shadow voxelization skipped:` 、②阴影/彩色光照正确、
  ③Tracy 或 `sdl.voxelization*` 计数符合预期、④进出世界与切包不崩。
- **`prepareShadowVoxelizationCompute` 的矩阵来源**：传的是 `ChunkRenderMatrices.modelView()`，
  与上游相同；但 Actinium 的阴影矩阵来源（`ShadowRenderer` / `Iris` 的 composite
  matrices）与上游一致与否未实测，若彩色光照位置不对优先怀疑这里。
- **compute 源是否真的生成**：若光影包没有在 CELERITAS_TERRAIN/SHADOW 阶段写 image，
  `getShadowVoxelizationComputeSource()` 为空 → `getShadowVoxelizationCompute()` 返回 null
  → 门控记录一次 `pack declares no shadow voxelization` 并跳过。这是预期行为，不是错误。
- **range 表容量**：`upload()` 用 2 倍增长 + `GL_STREAM_DRAW`，每 pass 一次 `glBufferSubData`；
  极端场景（几千个 region）下条目数 = region 的 run 数之和，未做上限保护。
- **`voxelizeShadowTerrain` 每个 pass 都会判门**（含非阴影 pass），门控顺序已按
  低成本优先排列（SDL 标志 → 阴影状态 → render list → pipeline → compute）。
