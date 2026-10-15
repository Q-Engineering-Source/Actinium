# Chunk Animator 兼容（chunkanimator 1.12.2-1.2.1）

最后更新：2026-09-18。分支：`feat/chunk-animator-compat`。

## 模组机制（反编译结论）

Chunk Animator（CurseForge 项目 236484，文件 3850023，modid `chunkanimator`）是
Lumien 出品的纯 ASM coremod（无 Mixin）：

- `FMLCorePlugin`：`lumien.chunkanimator.asm.LoadingPlugin`，注册
  `ClassTransformer`（`IClassTransformer`）。
- 注入点一：`net.minecraft.client.renderer.chunk.RenderChunk#func_189562_a`
  （`setOrigin(int,int,int)`）——在方法体内 `func_178585_h`（`setPosition`）调用**之前**
  插入 `AsmHandler.setOrigin(renderChunk, x, y, z)`，记录该 chunk 的动画开始时间戳
  （`AnimationHandler` 内部 `WeakHashMap<Object, AnimationData>`，key 为 RenderChunk 实例）。
- 注入点二：`net.minecraft.client.renderer.ChunkRenderContainer#func_178003_a`
  （`preRenderChunk(RenderChunk)`）——在方法体内第一个 `GlStateManager.translate`
  （`func_179109_b`）调用**之后**插入 `AsmHandler.preRenderChunk(renderChunk)`，
  按动画模式与时间差计算偏移并 `glTranslate`（下方升起/上方落下/径向滑入，支持
  11 种 easing，时长默认 1000ms）。
- 动画从 `setOrigin` 起算；首次渲染（`getOffset`）时若时间戳为 -1 则惰性置为当前时间。

## 冲突分析

Actinium 的 celeritas 区块管线接管后：

1. `MixinRenderGlobal.loadRenderers` 将 `GameSettings.renderDistanceChunks` 第二次读取
   重定向为 0，原版 `BuiltChunkStorage` 不再分配可用的 `RenderChunk` 阵列；
2. `renderBlockLayer`（四参数入口）/`setupTerrain` 被整体替换为
   `ActiniumWorldRenderer`（单参数重载保留原版、仅 TRANSLUCENT 时被显式调用，但其
   `renderContainer` 通道为空），`ChunkRenderContainer.preRenderChunk` 不再被调用。

结论：Chunk Animator 的 ASM 注入仍然加载（不崩溃），但**两个 hook 都不会在渲染路径上
生效**，动画完全不显示。

## 兼容方案

在 Actinium 区块管线中提供与两个原版 hook 等价的钩子，并直接调用 Chunk Animator 的
`AnimationHandler`（其 `setOrigin`/`getOffset` 参数均为 `Object` 令牌，可传入 celeritas
的 `RenderSection`，无需构造 `RenderChunk`）：

| Chunk Animator 原版 hook | Actinium 等价钩子 | 位置 |
| --- | --- | --- |
| `RenderChunk.setOrigin` | `ChunkAnimationProvider.onSectionAdded(RenderSection)` | `RenderSectionManager.onSectionAdded`（渲染线程，section 进入渲染距离） |
| `ChunkRenderContainer.preRenderChunk` | `ChunkAnimationProvider.getSectionOffset(RenderSection, float[3])` | `DefaultChunkRenderer.fillCommandBuffer`（渲染线程，每 pass 每可见 section） |

绘制拆分：动画中的 section 不能共享 region 的批量变换（`u_RegionOffset` 是 region 级
uniform），因此 `fillCommandBuffer` 将其从 region 多批绘制中排除，region 批绘制结束后用
独立 `IndividualDrawEmitter` 逐个绘制，绘制前把 region 偏移叠加动画偏移写入
`shader.setRegionOffset`，绘制后恢复。动画结束后 provider 返回 `false`，section 自动
回到 region 批，无持续开销。

动画身份令牌直接复用 `RenderSection` 实例；`AnimationHandler` 用 `WeakHashMap` 弱引用
key，section 销毁后自动回收，无需显式清理。

安装时机：`Actinium.onInit`（FML 保证所有 mod 的 pre-init 先于任意 mod 的 init 完成，
此时 `ChunkAnimator.INSTANCE.animationHandler` 必已构造）。

## 文件清单

- `celeritas-common/.../api/render/chunk/ChunkAnimationProvider.java`（新增接口）
- `celeritas-common/.../api/render/chunk/ChunkAnimationProviderHolder.java`（新增静态注册点）
- `celeritas-common/.../impl/render/chunk/RenderSectionManager.java`（onSectionAdded 通知）
- `celeritas-common/.../impl/render/chunk/DefaultChunkRenderer.java`（动画拆分单独绘制）
- `src/main/java/com/dhj/actinium/compat/chunkanimator/ChunkAnimatorCompat.java`（新增桥）
- `src/main/java/com/dhj/actinium/Actinium.java`（onInit 安装）
- `gradle/scripts/dependencies.gradle`（`modImplementation curse.maven:chunk-animator-236484:3850023`）
- `build.gradle`（`prepareChunkAnimatorMcpMappings` 任务，dev 环境 MCP 映射供给）
- `src/test/java/dhj/embeddedt/embeddium/api/render/chunk/ChunkAnimationProviderHolderTest.java`

## 行为差异（相对原版）

- 触发时机：原版 `setOrigin` 仅在世界加载（BuiltChunkStorage 构造）与玩家跨 chunk 边界
  重定位时触发；Actinium 在 section 进入渲染距离时触发，语义一致（区块首次可见时动画）。
- 阴影 pass：与 Iris 兼容模式一致，动画同样作用于 shadow 渲染。
- `DisableAroundPlayer`、动画模式、easing、时长等全部由 Chunk Animator 自身配置决定。

## 验证记录

- [x] `compileJava` / `test` / `build` 通过（分支 `feat/chunk-animator-compat`）。
- [x] dev 运行验证（2026-08-15，`runClient`）：
  - Chunk Animator coremod 正常加载（`MCPNames` 不再崩溃）；
  - 日志确认 `Chunk Animator compatibility layer enabled`；
  - 客户端进入世界、正常退出，无渲染异常。
- [x] 动画视觉效果确认（区块升起/滑入动画肉眼可见，可切换动画模式验证）。

### dev 环境说明

Chunk Animator 的 `MCPNames` 在 dev 环境（`IN_MCP=true`）静态初始化时读取
`./../mcp/methods.csv` 与 `./../mcp/fields.csv`（相对 `runClient` 工作目录 `run/client`，
即 `run/mcp/`），缺失会导致其 coremod 加载崩溃（`ExceptionInInitializerError`，
2026-08-15 实测）。`prepareChunkAnimatorMcpMappings` 任务会在 `runClient`/`runClientSdl` 前把
MCP stable_39 映射（`de.oceanlabs.mcp:mcp_stable:39-1.12`，与构建管线同源）复制到
`run/mcp/`，无需手工干预。

> 注：曾尝试 `fg.deobf(...)` 声明依赖（kappa 版 Unimined 未注册 `fg` 扩展，
> `Could not get unknown property 'fg'`，已探测确认），最终采用映射复制方案，
> 效果等同——复用 Unimined 反混淆管线产物。依赖保持 `modImplementation`，
> dev 运行自动加载 Chunk Animator（生产环境由用户自行安装）。

## 参考

- 反编译产物：`.tmp/chunk-animator-src/`（CFR 0.152，临时目录不提交）。
- 原版 1.12.2 映射：`RenderChunk.func_189562_a=setOrigin`、
  `func_178585_h=updateChunkPosition`、`func_178568_j=getPosition`、
  `ChunkRenderContainer.func_178003_a=preRenderChunk`、`GlStateManager.func_179109_b=translate`。
