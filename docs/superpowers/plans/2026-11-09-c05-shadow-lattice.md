# C05 Shadow Lattice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同步 Celeritas C05，使遮挡 lattice 支持超高世界的 Y 窗口，并在窗口移动后保留指向同一 section 的阴影搜索根。

**Architecture:** 沿用上游 `newDimY = min(worldDimY, newDimXZ)` 的三维窗口和滑动算法，保留本地多相机开窗、双 visitState、快照缓冲与 `SectionGraph.prepareWindow` 并发保护。阴影根保存的世界 section 不变，窗口移动只重算 packed local XYZ 与 lattice index。

**Tech Stack:** Java 21 字节码、JUnit 5、fastutil、JOML、Gradle。

## Global Constraints

- 工作目录固定为 `D:/Code/Actinium`，分支 `feat/c05-shadow-lattice`，基线 `0faa6390`；禁止再创建 worktree。
- 范围只含 `7818b3317`、`c2823be43`、`408881f27` 与 `d3946af4e` 的并发语义；不得提前实现 C08/C09。
- `SectionGraph.prepareWindow` 继续在所有搜索提交前执行；不得把窗口准备移入搜索线程。
- Y 轴只在 `worldDimY <= dimXZ` 时保持整个世界高度；`worldDimY > dimXZ` 时启用 Y 窗口。原版 18 段高的世界在 radius 2/3（`dimXZ` 15/17）下同样启用 Y 窗口，这是上游 `newDimY = min(worldDimY, dimXZ)` 的直接结果，不额外改写。索引继续为 `(lx * dimZ + lz) * dimY + ly`。
- 必须保留多相机 X/Z 行为、`shadowVisitState`、`occluderData`、`occluderBounds`、安装计数和快照语义。
- 先运行新增测试并确认因缺少 Y 窗口或根重映射而失败，再修改生产代码。禁止反射、Mockito、纯源码 contains 测试和魔法值常量测试。
- 实现者不得提交、推送或运行 `runClient`；L1 负责审查、完整构建、部署与后续 Git 事务。
- 上游文件已保存在 `.tmp/c05-SectionLattice-before.java`、`.tmp/c05-SectionLattice-after.java`；原始补丁在 `.tmp/c05-upstream.patch`。

---

### Task 1: Y 窗口与阴影根重映射

**Files:**
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/SectionLattice.java`
- Modify: `celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/ShadowOcclusionCuller.java`（`seed` 注释与根重映射一致）
- Create: `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/SectionLatticeWindowTest.java`
- Create: `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/region/TestRegions.java`（`RenderRegion` 构造函数包可见，occlusion 包内的测试需要该 fixture）
- Modify: `src/test/java/dhj/embeddedt/embeddium/impl/render/chunk/region/SectionLatticeMultiCameraWindowTest.java`（复用 `TestRegions`，删除重复的 arena stride 常量）
- Modify after behavior verification: `docs/architecture.md:329`
- Modify after behavior verification: `docs/sync-celeritas-b2a70f7f28.md:48`

**Interfaces:**
- Consumes: 现有 `SectionLattice.attach`、`ensureWindowCovers`、`findVisible`、`indexOf`、`packXyz`、`sectionAt`、`visibleCells`。
- Produces: `private void remapVisibleCells(int oldBaseX, int oldBaseY, int oldBaseZ)`；`ensureWindowCovers` 的公开签名不变。

- [x] **Step 1: 写窗口移动的根集测试**

从 `.tmp/c05-upstream.patch:499-559` 移植 `windowMoveKeepsVisibleCellsOnTheirSections`，包名改为 `dhj.embeddedt.embeddium`。使用以下参数和真实 `RenderSection`：

```java
@ParameterizedTest
@CsvSource({"14, 8, 7, 5", "8, 14, 7, 6", "40, 8, 7, 0", "14, 14, 7, 4", "8, 14, 14, 1",
        "2, 2, 7, 2", "8, 14, 0, 1", "8, 2, 7, 5"})
void windowMoveKeepsVisibleCellsOnTheirSections(int cameraX, int cameraY, int cameraZ, int retained) {
    SectionLattice lattice = new SectionLattice(0, 16, true, false);
    Map<Long, RenderRegion> regions = new HashMap<>();
    for (int x = 0; x <= 30; x++) {
        for (int y = 0; y <= 15; y++) {
            for (int z = 0; z <= 14; z++) {
                int rx = x >> RenderRegion.REGION_WIDTH_SH;
                int ry = y >> RenderRegion.REGION_HEIGHT_SH;
                int rz = z >> RenderRegion.REGION_LENGTH_SH;
                RenderRegion region = regions.computeIfAbsent(RenderRegion.key(rx, ry, rz),
                        key -> TestRegions.create(rx, ry, rz, regions.size()));
                lattice.attach(createSection(region, x, y, z));
            }
        }
    }
    lattice.ensureWindowCovers(new Vector3i(8, 8, 7), 32.0F);
    long[] cells = lattice.ensureVisibleCellsCapacity(ROOTS.length);
    for (int i = 0; i < ROOTS.length; i++) {
        int[] root = ROOTS[i];
        int idx = lattice.indexOf(root[0], root[1], root[2]);
        cells[i] = ((long) lattice.packXyz(root[0], root[1], root[2]) << 32) | (idx & 0xFFFFFFFFL);
    }
    lattice.visibleCount = ROOTS.length;
    lattice.ensureWindowCovers(new Vector3i(cameraX, cameraY, cameraZ), 32.0F);
    Set<RenderSection> expected = new HashSet<>();
    for (int[] root : ROOTS) {
        int idx = lattice.indexOf(root[0], root[1], root[2]);
        if (idx >= 0) {
            expected.add(lattice.sectionAt(idx));
        }
    }
    assertEquals(retained, expected.size());
    assertEquals(retained, lattice.visibleCount);
    Set<RenderSection> seen = new HashSet<>();
    for (int i = 0; i < lattice.visibleCount; i++) {
        long entry = lattice.visibleCells[i];
        RenderSection section = lattice.sectionAt((int) entry);
        assertTrue(expected.contains(section));
        assertTrue(seen.add(section));
        assertEquals(lattice.packXyz(section.getChunkX(), section.getChunkY(), section.getChunkZ()),
                (int) (entry >>> 32));
    }
}
```

`ROOTS` 使用上游七组坐标。区域经 `TestRegions.create` 构造，使用 compact vertex stride（上游零 stride 替身不用）；`createSection` 写入 `VisibilityEncoding.EVERYTHING`。除上游三组单轴移动外另加五行：`14, 14, 7, 4`（X/Y 正向同移，保留 4 个根）与 `8, 14, 14, 1`（Z/Y 正向同移，保留 1 个根）覆盖 `shiftContentsWithY` 的列间搬移；`2, 2, 7, 2`（新原点 (-5,-5,0)，安装区 X/Y -4..8、Z 1..13，保留 (2,8,7)、(8,8,7) 共 2 个）、`8, 14, 0, 1`（新原点 (1,7,-7)，仅 (9,8,6) 留存）与 `8, 2, 7, 5`（新原点 (1,-5,0)，安装区 X 2..14、Y -4..8、Z 1..13，保留 5 个）覆盖负方向移动与同列重叠复制。

- [x] **Step 2: 写超高世界搜索测试**

```java
@Test
void tallWorldSearchesNearTheCameraWithoutAllocatingItsWholeHeight() {
    SectionLattice lattice = assertDoesNotThrow(() -> new SectionLattice(-1024, 1023, true, false));
    RenderRegion region = TestRegions.create(0, 0, 0, 0);
    RenderSection root = createSection(region, 0, 0, 0);
    lattice.attach(root);
    Viewport viewport = viewportAt(root);
    lattice.ensureWindowCovers(viewport.getChunkCoord(), SEARCH_DISTANCE);
    assertEquals(Set.of(root), visitedSections(lattice, viewport, 1));
}
```

`viewportAt` 使用 `SectionLatticeMultiCameraWindowTest.java:78-81` 的真实 `Viewport` 构造。`visitedSections` 收集 visitor 报告的 `sectionAt(latticeIndex)`（真实 `RenderSection`）而不是 `sectionIndex`：packed 局部索引只有 2 位 Y，同列不同 Y 的 section 会折叠成同一个值，用索引组成集合无法区分具体 section。

- [x] **Step 2b: 写多相机 Y 跨度测试**

```java
@Test
void twoCamerasKeepTheirWholeVerticalSpanSearchable() {
    SectionLattice lattice = new SectionLattice(0, 255, false, false);
    RenderRegion region = TestRegions.create(0, 0, 0, 0);
    // 6..20 连成一条竖列并全部挂上：遍历只进入已安装单元格，跨过空单元格的搜索到不了列两端。
    // 相机取 (8, 8, 7) 与 (8, 18, 7)，列两端 (8, 6, 7)、(8, 20, 7) 即断言必须覆盖的边缘 section。
    ...
    lattice.ensureWindowCovers(SEARCH_DISTANCE, lowerViewport.getChunkCoord(), upperViewport.getChunkCoord());
    assertEquals(span, visitedSections(lattice, lowerViewport, 1));
    assertEquals(span, visitedSections(lattice, upperViewport, 2));
}
```

全部 section 写入 `VisibilityEncoding.EVERYTHING`。未把 Y 跨度纳入 allowance 时 `dimY` 为 15、`baseY` 为 6，纵向安装区只覆盖世界 Y 7..19，(8, 6, 7) 与 (8, 20, 7) 不参与搜索，两次搜索的访问集合都会缺这两端。断言必须来自 `findVisible` 的 visitor 结果，不能只断言相机根 `indexOf >= 0`。

- [x] **Step 3: 运行 RED**

Run: `./gradlew test --tests "dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLatticeWindowTest" --no-daemon`

Expected: 超高世界在旧构造函数的 `World height ... exceeds occlusion lattice limit` 处失败；根集移动后的 `visibleCount` 与新窗口内预期数量不一致。若测试编译错误或意外通过，先修正测试再继续。Step 1 与 Step 2b 中后加的五行与多相机用例不依赖旧实现缺失，用临时定向 mutation 取 RED：去掉 `shiftContentsWithY` 里 `(lx + dX) * strideX + (lz + dZ) * strideZ` 的列偏移，四行多轴移动（`14,14,7,4`、`8,14,14,1`、`2,2,7,2`、`8,14,0,1`）失败，纯 Y 的 `8,14,7,6`、`8,2,7,5` 与单轴行仍通过；注释掉 Y 跨度 allowance 分支，多相机用例的访问集合缺列两端。mutation 随后必须还原。

- [x] **Step 4: 三方合并上游实现并解决两个语义冲突**

```bash
git merge-file -p celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/occlusion/SectionLattice.java .tmp/c05-SectionLattice-before.java .tmp/c05-SectionLattice-after.java > .tmp/c05-SectionLattice-resolved.java
```

把预览作为合并输入，保留本地快照与多相机逻辑。核心字段和分配规则为：

```java
private final int worldDimY;
private boolean windowedY;
int dimX, dimY, dimZ;
int baseX, baseY, baseZ;

int newDimY = Math.min(this.worldDimY, newDimXZ);
this.windowedY = newDimY < this.worldDimY;
```

多相机窗口计算同时统计 `minY/maxY`。当世界需要 Y 窗口时，把 `maxY - minY` 纳入现有 spread allowance；`windowHoldsEveryCamera` 对每个相机增加：

```java
int ly = camera.y() - this.baseY;
if (this.windowedY && (ly - reach < 1 || ly + reach > this.dimY - 2)) {
    return false;
}
```

新 Y 原点取所有相机的中心：

```java
int newBaseY = this.windowedY
        ? minY + (maxY - minY) / 2 - this.dimY / 2
        : this.minSectionY - 1;
```

`shiftRebase`、`dropLeaving`、`fillNewBand` 按上游扩展为三轴；使用上游 `shiftContentsWithY` 与 `moveCells` 移动全部平行数组。禁止只移动 `latticeSection` 或漏掉 `shadowVisitState`。

- [x] **Step 5: 在每次实际窗口变化后重映射根集**

在任何 allocate 之前保存旧原点，在 shift/rebase 后、`established = true` 前调用：

```java
private void remapVisibleCells(int oldBaseX, int oldBaseY, int oldBaseZ) {
    int retained = 0;
    for (int i = 0; i < this.visibleCount; i++) {
        int xyz = (int) (this.visibleCells[i] >>> 32);
        int x = oldBaseX + ((xyz >>> (2 * XYZ_SHIFT)) & XYZ_LOCAL_MASK);
        int y = oldBaseY + ((xyz >>> XYZ_SHIFT) & XYZ_LOCAL_MASK);
        int z = oldBaseZ + (xyz & XYZ_LOCAL_MASK);
        int idx = this.installedIndex(x, y, z);
        if (idx >= 0) {
            this.visibleCells[retained++] = ((long) this.packXyz(x, y, z) << 32) | (idx & 0xFFFFFFFFL);
        }
    }
    this.visibleCount = retained;
}
```

窗口未变化时直接返回，不触碰根集。清除合并冲突标记，保留本地普通世界、双 pass 与快照行为。

- [x] **Step 6: 运行 GREEN 和相邻回归**

```bash
./gradlew test --tests "dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLatticeWindowTest" --no-daemon
./gradlew test --tests "dhj.embeddedt.embeddium.impl.render.chunk.region.SectionLatticeMultiCameraWindowTest" --tests "dhj.embeddedt.embeddium.impl.render.chunk.lists.SectionGraphWindowPreparationTest" --no-daemon
```

Expected: 全部通过。更新 `docs/architecture.md` 的 Y 窗口与根重映射说明，将 C05 台账标为已实现、等待实机验证。

- [x] **Step 7: 完整验证并交付实机测试**

```bash
./gradlew check glIntegrationTest build -Pversion=alpha-0.0.21-c05 --no-daemon
```

结果为 `BUILD SUCCESSFUL`，1156 项普通测试与 264 项真实 GL 测试无失败或错误，4 项跳过。jar 已部署到 Cleanroom 与三个 Prism 实例，用户确认运行无崩溃且未见渲染回归。额外高度模组场景由用户决定留待后续问题反馈，临时安装的 Depths Update 已移出 `mods`；超高世界路径由 `SectionLatticeWindowTest` 覆盖。验证通过后提交并创建 PR。
