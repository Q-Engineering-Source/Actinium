# C08 Dispatcher Block Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同步 Celeritas `26ab21758`，让普通快速方块渲染经过 `BlockRendererDispatcher`，同时保留 Actinium 的 addon 绑定和模组兼容路径。

**Architecture:** `ChunkBufferBuilder` 标记区块构建上下文与 render layer，dispatcher mixin 只在该 buffer 与 `ActiniumBlockAccess` 同时出现时进入快速路径。上游的 `renderModel` 重构改为向现有 `renderBlock` 提供已选模型，保留 addon 依赖的方法、字段和指令位置。

**Tech Stack:** Java 21 字节码、Mixin Extras、JUnit 5、ASM、Minecraft 1.12.2、Gradle。

## Global Constraints

- 工作目录固定 `D:/Code/Actinium`，分支 `feat/c08-dispatcher-block-renderer`，基线 `fc39c62e`；禁止创建 worktree。
- 仅同步 C08，不提前实现 C09。完整保留 `VintageBlockRenderer` 私有 `currentState`、`currentBlockAccess`、9 参 `renderQuadList`、`renderBlock` 内两个 `renderQuadList` 调用点及 `shouldSideBeRendered` 调用点。
- `useFastBlockRenderer` 必须继续生效；Snow Real Magic、ArchitectureCraft、Fluidlogged 必须保留原版 dispatcher 路径。shader layer override 的直接快速路径保留 `allowRenderPassOptimization=false`。
- `renderBlock` 的公开签名和返回类型不变。新增 `renderModel` 向它提供模型，禁止把主体移到另一个方法而破坏 addon。
- 先写路由测试并确认失败，再写生产实现。禁止反射、Mockito、纯源码 contains 测试、占位实现和额外依赖。
- 实现者不得提交、推送、创建 worktree 或运行 `runClient`；L1 负责审查、完整构建、部署与 Git 事务。
- 上游补丁在 `.tmp/c08-upstream.json`；`VintageBlockRenderer` 三方合并预览在 `.tmp/c08-VintageBlockRenderer-merged.java`，只作语义参考。

---

### Task 1: Dispatcher 快速渲染与兼容路由

**Files:**
- Create: `src/main/java/com/dhj/actinium/render/terrain/compile/ChunkBufferBuilder.java`
- Create: `src/main/java/com/dhj/actinium/render/terrain/compile/ChunkBlockRendererRouter.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/VintageChunkBuildContext.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer.java`
- Modify: `src/main/java/com/dhj/actinium/render/terrain/compile/task/ChunkBuilderMeshingTask.java`
- Create: `src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinBlockRendererDispatcher.java`
- Modify: `src/main/java/com/dhj/actinium/mixin/mod/betterfoliage/MixinChunkBuilderMeshingTaskBetterFoliage.java`
- Modify: `src/main/java/com/dhj/actinium/mixin/mod/betterfoliage/MixinChunkBuilderMeshingTaskRLFoliage.java`
- Modify: `src/main/resources/mixins.actinium.vintage.json`
- Test: `src/test/java/com/dhj/actinium/render/terrain/compile/ChunkBlockRendererRouterTest.java`
- Test: `src/test/java/com/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRendererBindingContractTest.java`
- Test: `src/test/java/com/dhj/actinium/mixin/MixinConfigurationTest.java`
- Modify after behavior verification: `docs/architecture.md`
- Modify after behavior verification: `docs/sync-celeritas-b2a70f7f28.md:45`

Better Foliage / RLFoliage 两个适配 Mixin 必须一并修改：它们各有一个 `@WrapOperation` 包裹
`execute` 内的 4 参 `VintageBlockRenderer.renderBlock` 调用点，普通 `LAYERS` 分支改走 dispatcher
后该指令不再存在，配置的 `defaultRequire=1` 会让注入失败（装模组时启动崩溃），因此删除该包装；
剩余的 `BlockRendererDispatcher.renderBlock` 包装继续覆盖普通层（含附加几何），五参
`renderBlock` 包装继续覆盖 shader layer override 的直接快速路径，并把外层
`allowRenderPassOptimization` 传给 `tryRenderFastBlock`。

**Interfaces:**
- `ChunkBufferBuilder(int bufferSize, VintageChunkBuildContext context, BlockRenderLayer layer)` provides `getContext()`、`getLayer()`、`isForceVanillaRendering()`、`setForceVanillaRendering(boolean)`、`isAllowRenderPassOptimization()`（默认 `true`）、`setAllowRenderPassOptimization(boolean)`。
- `ChunkBlockRendererRouter.shouldUseFastRenderer(IBlockAccess, BufferBuilder)` returns whether the dispatcher may enter the fast path.
- `VintageBlockRenderer.renderModel(IBakedModel, IBlockState, BlockPos, ActiniumBlockAccess, BlockRenderLayer)` returns whether a quad list was handed to `renderQuadList`；带 `boolean allowRenderPassOptimization` 的重载把它传给 `renderBlock(..., boolean)`。

- [x] **Step 1: 写 dispatcher 资格测试**

在 `ChunkBlockRendererRouterTest` 中创建真实 `ChunkBufferBuilder(64, null, BlockRenderLayer.SOLID)`；该用例只判断路由资格，不访问 context 的 renderer。`PlainBlockAccess` 实现 `IBlockAccess` 的七个方法，`ChunkWorld extends PlainBlockAccess implements ActiniumBlockAccess` 再实现 `getBlockTint`、`getFluidState(int,int,int)`、`getWorld`。测试必须验证：

```java
@Test
void onlyChunkBuffersInActiniumWorldsUseTheFastPath() {
    withFastBlockRenderer(true, () -> {
        assertTrue(ChunkBlockRendererRouter.shouldUseFastRenderer(chunkWorld, chunkBuffer));
        assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(chunkWorld, new BufferBuilder(64)));
        assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(plainWorld, chunkBuffer));
    });
}

@Test
void fluidAndModFallbacksKeepTheOriginalDispatcherCall() {
    withFastBlockRenderer(true, () -> {
        chunkBuffer.setForceVanillaRendering(true);
        assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(chunkWorld, chunkBuffer));
    });
}

@Test
void disabledFastRenderingKeepsTheOriginalDispatcherCall() {
    withFastBlockRenderer(false, () ->
        assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(chunkWorld, chunkBuffer)));
}
```

`withFastBlockRenderer` 必须在 `finally` 恢复 `ActiniumRuntime.options().performance.useFastBlockRenderer` 的原值。测试 stub 返回 `Blocks.AIR.getDefaultState()`、`Biomes.PLAINS`、`WorldType.DEFAULT`、`FluidState.EMPTY`，其它 getter 返回零值、空值或 `false`，不得使用 Mockito 或反射。

- [x] **Step 2: 运行 RED**

Run: `./gradlew :test --tests "com.dhj.actinium.render.terrain.compile.ChunkBlockRendererRouterTest" --no-daemon`

Expected: 因 `ChunkBufferBuilder`、`ChunkBlockRendererRouter` 尚不存在而编译失败。先补齐测试自身的 stub 编译问题，不得提前写生产路由。

- [x] **Step 3: 实现区块 buffer 与路由谓词**

`ChunkBufferBuilder extends BufferBuilder`，保存 context、layer 和默认 `false` 的 `forceVanillaRendering`。路由保持为小函数：

```java
static boolean shouldUseFastRenderer(IBlockAccess world, BufferBuilder buffer) {
    return ActiniumRuntime.options().performance.useFastBlockRenderer
            && world instanceof ActiniumBlockAccess
            && buffer instanceof ChunkBufferBuilder chunkBuffer
            && !chunkBuffer.isForceVanillaRendering();
}
```

`VintageChunkBuildContext.getBufferForLayer` 创建 `new ChunkBufferBuilder(131072, this, layer)`，保留原有 begin/translation 行为。

- [x] **Step 4: 保留所有原版回退**

`beginVanillaBlockRender` 在任何 extension 提前返回前设置 `forceVanillaRendering = SnowRealMagicCompat.shouldForceVanillaRender(state.getBlock()) || ArchitectureCraftCompat.shouldForceVanillaRender(state.getBlock())`。`beginVanillaFluidRender` 设置 `forceVanillaRendering=true`。`endVanillaRender` 清除该标记并保留原 extension 清理。

普通 `LAYERS` 分支统一执行 `getBufferForLayer`、`beginVanillaBlockRender`、`dispatcher.renderBlock`、`endVanillaRender`（`try/finally`）。shader layer override 的快速分支继续调用 `renderBlock(..., false)`；Snow/ArchitectureCraft 仍由 marker 走原版路径。Fluidlogged 的 `beginVanillaFluidRender` 防止 MODEL 类型流体误进快速路径。

- [x] **Step 5: 让现有 renderBlock 接受 dispatcher 已选模型**

新增 `IBakedModel preparedModel` 和 `boolean renderedAnyQuad` 字段。`renderBlock` 主体保持原位置；仅在没有 prepared model 时执行原 `getActualState` 与 `shapes.getModelForState`。在两个实际 `renderQuadList` 调用后设置 `renderedAnyQuad=true`，每次 `renderBlock` 开始设置 `false`。

新增方法：

```java
public boolean renderModel(IBakedModel model, IBlockState state, BlockPos pos,
                           ActiniumBlockAccess blockAccess, BlockRenderLayer layer) {
    IBakedModel previousModel = this.preparedModel;
    boolean previousRendered = this.renderedAnyQuad;
    this.preparedModel = model;
    try {
        this.renderBlock(state, pos, blockAccess, layer);
        return this.renderedAnyQuad;
    } finally {
        this.preparedModel = previousModel;
        this.renderedAnyQuad = previousRendered;
    }
}
```

`renderBlock` 仍在计算 shader metadata 后调用 `getExtendedState`，`MissingModelCompat`、水面材质、quad 转换、编码器清理和 addon 调用点均保留。不得改变 `renderBlock` 的返回类型或把 `renderQuadList` 调用移到 `renderModel`。

- [x] **Step 6: 接入 dispatcher Mixin**

`MixinBlockRendererDispatcher` 使用 Mixin Extras `@WrapOperation`，目标是 `BlockRendererDispatcher.renderBlock` 内：

```text
BlockModelRenderer.renderModel(
    IBlockAccess, IBakedModel, IBlockState, BlockPos, BufferBuilder, boolean
)Z
```

命中路由谓词时调用 `chunkBuffer.getContext().getBlockRenderer().renderModel(model, state, pos, (ActiniumBlockAccess) world, chunkBuffer.getLayer())`；其它情况调用 `original.call` 的全部原参数。Mixin 只做注入与分派，路由逻辑留在 mixin 包外。把 `core.terrain.MixinBlockRendererDispatcher` 加入 `mixins.actinium.vintage.json` 的 client 列表。

- [x] **Step 7: 运行 GREEN 与绑定回归**

```bash
./gradlew :test --tests "com.dhj.actinium.render.terrain.compile.ChunkBlockRendererRouterTest" --no-daemon
./gradlew :test --tests "com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRendererBindingContractTest" --tests "com.dhj.actinium.mixin.MixinConfigurationTest" --no-daemon
```

Expected: 全部通过。若现有 addon 绑定测试失败，必须调整实现以保留绑定，不得删除或放宽该测试。更新架构文档与 C08 台账，注明本地 prepared-model 适配原因。

- [x] **Step 8: 完整验证并交付实机测试**

```bash
./gradlew check glIntegrationTest build -Pversion=alpha-0.0.22-c08 --no-daemon
```

Expected: `BUILD SUCCESSFUL`，无测试失败或错误。部署到 Cleanroom 和三个 Prism 实例，验证普通方块、光影水面、Snow Real Magic、ArchitectureCraft、Fluidlogged 与快速渲染开关。用户确认后再提交并创建 PR。
