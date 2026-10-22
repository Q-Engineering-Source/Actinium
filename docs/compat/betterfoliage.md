# Better Foliage 兼容性说明

兼容状态：**既有 Hook 适配曾经 dev 实测通过；本次普通方块额外 cutout fallback 修复已自动验证，等待用户实机确认**
最后更新：2026-10-22

## 模组信息

- 原版 Better Foliage（modid `betterfoliage`），Minecraft 1.12.2；检查了 CurseForge 文件 3393955（JAR 元数据版本 2.3.2）。
- RLFoliage 使用相同 modid，但提供不同的渲染 API，不能共用原版适配 Mixin。
- 两种实现分别由其入口类存在性加载：原版 `mods.betterfoliage.client.Hooks`、RLFoliage 的 `betterfoliage.render.feature.RenderingHandler`。

## 根因与机制

- 原版 Better Foliage 的 `BetterFoliageTransformer` 会改写 vanilla `RenderChunk.rebuildChunk`：把 `BlockRendererDispatcher.renderBlock` 转为 `Hooks.renderWorldBlock`，并把方块层检查转为 `Hooks.canRenderBlockInLayer`。
- Actinium 使用自己的 `ChunkBuilderMeshingTask.execute`，不会经过上述 vanilla 调用点；已有适配器调用的是 RLFoliage 的 `RenderingHandler`，原版 Better Foliage 不包含这个类。
- 原版适配现在在快速路径中只对可处理的 Better Foliage feature renderer 调用原版 Hook；普通方块仍走 Actinium 快速 renderer。走 vanilla dispatcher 的分支直接经过原版 Hook。RLFoliage 保留独立适配和独立条件。
- HBM 2.5.0.5 的必需 `MixinRenderChunk` 也包裹 vanilla `BlockRendererDispatcher.renderBlock`。Better Foliage 的 coremod 先把该调用换成 `Hooks.renderWorldBlock`，使 HBM 的注入命中数变为 0。Actinium 在这两个 mod 同时存在时，于 Better Foliage 转换之后恢复该 vanilla 调用点；Actinium 自己的 meshing task 仍走上面的 Better Foliage Hook。

### 普通方块在额外 cutout 层的重复构建（2026-10-22）

Better Foliage 的 `Hooks.canRenderBlockInLayer` 会为目标 cutout 层放行额外 feature，即使原始 state 不属于请求层。Actinium 的快速路径先让 Better Foliage feature renderer 处理；此前当 feature 未处理普通方块时，mixin 仍无条件调用原版 dispatcher，使仅属于 `SOLID` 的 state 也在额外 cutout 层再次构建普通模型。`BakedQuadGroupAnalyzer.chooseOptimalMaterial` 会将可优化且 sprite 不透明的 quad 归入 `SOLID` material，因此重复几何可能累加到 solid mesh。

现在 feature 未处理后，原版模型只在 `Utils.canRenderInLayer(state, layer)` 成立，或当前为 Better Foliage 目标 cutout 层且 state 属于另一 cutout 层时才进入 fallback。该 predicate 与 feature eligibility 共用，保留 `addToCutout` feature 的优先级和两个 cutout 层的合并语义。Better Foliage 实现仍只由同时检查 `betterfoliage` mod id 与 `Hooks` 类存在性的 conditional mixin 使用。

修复前 PID 5052 的采样中，128 个 builder output trace 都有唯一且一一对应的 accepted-for-upload 记录；71 个负 Y section 样本里有 69 个以 `botania:biomestoneb5slab` 为 top state。trace 125（section 12, -2, 2）包含 3,710 个该 slab，solid pass 有 44,930 个 quad，cutout_mipped pass 有 44 个。该输入与输出规模符合跨层重复 fallback 的机制，但 section 总数不能把所有 solid quad 归因到这些 slab；accepted-for-upload 也只代表通过过滤并到达 upload 入口，不能证明 GPU 上传成功。

## 验证记录

- 原版 Better Foliage 1.12.2 JAR 已反编译核对 Hook 签名和 transformer 目标；RLFoliage JAR 已核对其使用独立 API 类。
- dev 启动的首个崩溃是缺少 `net.shadowfacts.forgelin.KotlinAdapter`；后续运行日志已显示 Forgelin 和 Better Foliage 均加载，并推进到 HBM 的 `RenderChunk` Mixin 冲突。
- HBM 与 Better Foliage 的 RenderChunk 注入冲突已按两者的实际字节码路径适配。
- `./gradlew check --no-daemon`：BUILD SUCCESSFUL。
- dev 运行验证（用户确认）：HBM 正常，树叶正常。
- `BetterFoliageCompatImplTest` 使用真实 `Block` state 直接验证共享生产 predicate 的 cutout 双向配对和短路，并通过 `ChunkModelRoutingImpl` 回放 SOLID-only 六面模型跨原始层与合成目标层的路由，断言只发出 6 个 quad。`BetterFoliageMixinCallContractTest` 检查 fallback wrapper 调用该 predicate；dev jar 字节码检查也确认 BF handler 未处理后先检查 predicate，false 时直接返回。
- 本轮 `check` 与 `devShadowJar` 均成功：266 suites，1,254 tests，0 failures，0 errors，4 skipped。CPU routing 与 mixin 调用边界覆盖没有运行 Better Foliage feature registry 的完整渲染路径；本次普通方块 cutout fallback 修复仍等待用户实机确认。
