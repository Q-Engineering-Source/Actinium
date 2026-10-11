# Better Biome Blend 兼容（betterbiomeblend 1.2.0 / Better Biome Blend Continued）

最后更新：2026-09-24。分支：`fix/worldslice-biome-bounds`。关联：issue #162（引用 issue #159 评论）。

## 症状

安装 `better-biome-blend-continued-arg.jar`（modid `betterbiomeblend`，1.2.0）与 RLFoliage
（`betterfoliage` 2.5.3）后进世界，区块网格构建线程随机崩溃，崩溃报告标题为
`Encountered exception while building chunk meshes`：

```
java.lang.ArrayIndexOutOfBoundsException: Index -4 out of bounds for length 64
    at com.dhj.actinium.world.WorldSlice.getBiome(WorldSlice.java:453)
    at org.embeddedt.embeddium.impl.asm.WorldSliceLocal.getBiome(Unknown Source)
    at michaelsebero.betterbiomeblend.client.BiomeColor.gatherRawColorsToBlendCache(BiomeColor.java:215)
    at michaelsebero.betterbiomeblend.client.BiomeColor.gatherRawColorsToBlendCache(BiomeColor.java:233)
    at michaelsebero.betterbiomeblend.client.BiomeColor.generateBlendedColorChunk(BiomeColor.java:316)
    at michaelsebero.betterbiomeblend.client.BiomeColor.getBlendedColorChunk(BiomeColor.java:359)
    at net.minecraft.world.biome.BiomeColorHelper.getGrassColorAtPos(SourceFile:553)
    at net.minecraft.client.renderer.color.BlockColors$10.colorMultiplier(BlockColors.java:172)
    at betterfoliage.render.BlockContext$BlockData.<init>(BlockContext.java:130)
    at betterfoliage.render.ModelRenderer.render(ModelRenderer.java:51)
    at com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask.execute(ChunkBuilderMeshingTask.java:151)
```

崩溃报告记录的现场：被渲染方块 `minecraft:grass` 位于 `World: (10095, 85, 9920)`，即 chunk
`(630, 620)` 的局部 `(15, 5, 0)`。

## 模组机制（反编译结论）

BBB 的 `MixinBiomeColorHelper` 在 `BiomeColorHelper#getColorAtPos`、`getGrassColorAtPos`、
`getFoliageColorAtPos`、`getWaterColorAtPos` 上 `@Overwrite`，把原版逐位置查询换成"按 chunk
预计算 16×16 混合颜色表"：

- `BiomeColor.getBlendedColorChunk` 为**被查询坐标所在的 chunk**（`x >> 4` / `z >> 4`）建表；
- `generateBlendedColorChunk` → `gatherRawColorsToBlendCache(..., chunkX, chunkZ, ...)` 遍历该
  chunk 的 **3×3 chunk 邻域**（`BiomeColor.neighbourOffsets` 的 9 个偏移），对每个邻居 chunk
  取其 biome 表并调用 `blockAccess.getBiome(blockPos)`；
- 因此**查询位置可以落在被构建 chunk 之外的一个 chunk 上**：当被查询坐标位于 origin 的
  邻居 chunk 时（区块边界块查询邻块数据即是这种情形），它的 3×3 邻域就会覆盖到 origin−2 chunk。

上游 1.12.2 源码参照：`FionaTheMortal/better-biome-blend` 分支 `1.12-forge` 的
`BiomeColor#gatherRawColorsToBlendCache` 与 `mixin/MixinBiomeColorHelper`（用户所用
`michaelsebero` 包的 Continued 版本行号不同，但调用链形状一致，见上方栈帧的递归自调用）。

## 触发链

1. `ChunkBuilderMeshingTask` 用 `ProxyClassGenerator` 把 `WorldSlice` 包成 `WorldSliceLocal`
   （实现 `ActiniumBlockAccess`），作为 `IBlockAccess` 交给方块渲染；
2. RLFoliage 渲染草方块时经 `BlockColors.colorMultiplier` → `BiomeColorHelper.getGrassColorAtPos`，
   命中 BBB 的 `@Overwrite`（栈中没有 Actinium 自己的 `BiomeColorCache`，说明该颜色请求由 BBB 接管）；
3. BBB 为被查询坐标所在的 chunk 建表并取其 3×3 邻域。渲染 chunk `(630, 620)` 时被查询到的
   chunk `(619, 620)` 是 `WorldSlice` 快照 origin chunk `(630, 620)` 的 −1 邻居，它的邻域再取
   `(618, 620)`，即比快照覆盖范围（origin chunk ±1）多出一个 chunk；
4. `WorldSlice#getBiome(BlockPos)` 用 `(pos - base) >> 4` 得到**相对 chunk 索引**，并把它**直接**
   当数组下标：chunk `(618, 620)` 相对基准 chunk `(619, 620)` 是 z 偏移 −1，于是
   `getLocalChunkIndex(0, -1) = (-1 << 2) | 0 = -4`，而 `sections` 长度 64 →
   `ArrayIndexOutOfBoundsException: Index -4 out of bounds for length 64`。

坐标自洽：origin chunk 620 的 `baseZ = (620 - 1) << 4 = 9904`；BBB 取 chunk 618 时查询的 z
落在 `[9888, 9903]`，`(z - 9904) >> 4 = -1`，正是索引 −4 的来源。

与 Actinium 自身 biome blend 的关系：Actinium 也有 biome 混合（`legacyBiomeBlendRadius` +
`BiomeColorCache`），但它同样注入 `BiomeColorHelper`；BBB 在场时 `getGrassColorAtPos` 等三个
方法只由 BBB 覆盖，因此**修复必须落在 `WorldSlice` 的 biome 查询上，而不是注入层面**。

## 修复

把快照内 biome 查询抽成 `BiomeLookup`（`com.dhj.actinium.world`），由 `WorldSlice` 持有：

- biome 表按 **chunk** 保存。此前按 section 保存 27 份，实际只是 9 个 chunk 的数据（同一 chunk
  的各 section 持有内容相同的 biome 表），现在按列只发布 9 份；
- 查询坐标先转成快照内块偏移，**越界时夹到快照边缘**，不再用相对索引直接寻址：最近处的 biome
  比固定 `PLAINS` 兜底更接近真实值，且 biome 数据跨 chunk 边界连续，混合颜色的边缘不会混入异色；
- 未持有快照（`copyData` 之前、`reset` 之后）与缺失 biome 表统一返回 `Biomes.PLAINS`；
- 坐标以 `long` 做差，避免 `Integer.MIN_VALUE` / `Integer.MAX_VALUE` 这类极端坐标回绕成"看似在
  范围内"的偏移；
- `y` 参数不再参与寻址：biome 数据按 chunk 列存储，此前用 `y` 选 section 槽位既无意义
  （同列各 section 数据相同），越界时还可能命中错误槽位。

## 文件清单

- `src/main/java/com/dhj/actinium/world/BiomeLookup.java`（新增，快照 biome 查询与边界语义）
- `src/main/java/com/dhj/actinium/world/WorldSlice.java`（biome 快照发布、两个 `getBiome` 委托）
- `src/main/java/com/dhj/actinium/world/cloned/ClonedChunkSection.java`（删除不再被引用的
  `getBiomeForNoiseGen`）
- `src/test/java/com/dhj/actinium/world/WorldSliceBiomeLookupTest.java`（新增回归测试）
- `docs/architecture.md`（`world/` 包结构补充 `BiomeLookup`）

## 行为差异

- 快照覆盖范围（origin chunk ±1）之外的 biome 采样改用快照边缘 biome（近似值），不再崩溃。
  Actinium 自身的混合半径上限为 14（`BiomeColorCache` 的 clamp），本就在覆盖范围内；
  BBB 的 3×3 chunk 邻域遍历是唯一会落到 clamp 分支的调用者。
- 崩溃只影响越界查询：快照内 biome 解析结果与修复前一致。

## 验证记录

- [x] `./gradlew :test --tests "com.dhj.actinium.world.WorldSliceBiomeLookupTest"` 通过（9 个用例）。
- [x] 红-绿验证：把 `BiomeLookup#getBiome` 临时退回"相对索引直接寻址"后，
      `queryBehindTheSnapshotClampsInsteadOfThrowing` / `queryBeyondTheSnapshotClampsToItsEdge` /
      `extremeCoordinatesDoNotWrapAround` 三个用例以 `ArrayIndexOutOfBoundsException` 失败，
      确认测试确实覆盖本次回归。
- [ ] 实机验证（待用户确认）：BBB 1.2.0 + RLFoliage 2.5.3 + Actinium，进世界后在 biome 交界
      区域长时间构建区块，确认不再出现 `Index -4 out of bounds for length 64`，且植被/草地颜色无回归。

## 参考

- issue #162 正文与崩溃报告（mclo.gs `wm4ahBu`）、其引用的 issue #159 评论
- 用户环境 `debug.log`（mclo.gs `C0bxI2Y`）：BBB `MixinBiomeColorHelper` 载入记录、崩溃栈
- [docs/compat/betterfoliage.md](betterfoliage.md)（同一崩溃链上的 RLFoliage 适配）
