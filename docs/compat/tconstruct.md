# Tinkers' Construct 系兼容（tconstruct 2.13.0.183 + mantle 1.3.3.55 + conarm 1.2.5.10）

最后更新：2026-10-14。分支：`research/sdl-gpu-port`。

## 模组机制（反编译结论）

TiCon 系的工具/护甲材质不是静态 PNG，而是运行时用自定义 sprite loader 染色生成：

- `AbstractColoredTexture`（TiCon 本体，conarm 继承使用）声明 `hasCustomLoader()=true` 和
  `getDependencies()`（基础纹理，如 `broken_core.png`）。1.12.2 的 `TextureMap` 注册循环保证
  **先载依赖、后载依赖方**，loader 的 `textureGetter` 契约就是"依赖的帧已就绪"。
- loader 的 `load()` 读基础纹理的帧数据做逐像素染色；读不到帧时走兜底分支：生成
  `width=height=1`、`frameCount=0` 的空壳（`AbstractColoredTexture.java:43-49`），而不是报错。

## 问题：动态纹理空壳崩溃（生产 jar `53fad534`）

该版本新增的并行图集加载把 1.12.2 私有 `generateMipmaps`（载帧 + 建 mip 链捆在同一方法）
**整个推迟**，破坏了上述加载顺序契约：

1. 注册循环轮到基础纹理时，帧解码被推迟，基础纹理暂时无帧；
2. 随后轮到 conarm 派生纹理，`AbstractColoredTexture.load()` 读基础纹理帧拿到空数据，生成
   1×1、0 帧的空壳（如 `conarm:items/boots/broken_core_advanced_alloy`）；
3. `TextureMap.finishLoading()` 的上传循环读每个精灵第 0 帧，空壳抛
   `IndexOutOfBoundsException`，游戏在"Stitching texture atlas"阶段崩溃。

## 修复（Actinium 侧，通用路径非 TiCon 特例）

帧装载回到原版位置（注册循环内由 vanilla 的私有 helper 即时完成，依赖顺序不变），只把
**mip 链构建**这一步分流：

- `AtlasLoadPlan`（新增 record）：循环开始前从注册表读出两件事——`decodeOrder`（按依赖先序的
  DFS 顺序，解码通道按此播种，否则 desync）和 `deferredChains`（所有精灵声明的依赖名字集合，
  即自定义 loader 可能读到的精灵）。
- `AtlasLoadSession.takeGeneration`：`deferredChains` 内的精灵不上 worker，其 mip 链推迟到
  `prepare`（循环结束后、主线程）构建；其余精灵照旧甩 worker。级链构建会原地重绘 base level，
  与 loader 读帧并发会读到半成品，这是 deferred 链必须留主线程的原因。
- `prepare` 对无帧精灵打 WARN（Fail Fast 诊断）：loader 声称了精灵却没给出像素是 mod bug，
  点名替代 vanilla 在上传循环里抛裸异常。

效果：loader 读依赖时帧必然就绪（恢复 vanilla 契约），并行解码/mip/批量上传的收益保留。

## 文件清单

- `src/main/java/com/dhj/actinium/texture/atlas/AtlasLoadPlan.java`（新增）
- `src/main/java/com/dhj/actinium/texture/atlas/AtlasLoadSession.java`
  （`beginRegistration`/`takeGeneration`/`prepare`，无帧 WARN）
- `src/main/java/com/dhj/actinium/mixin/vintage/features/textures/MixinTextureAtlasParallelLoad.java`
  （删整方法短路；注册循环 hook 改 `@WrapOperation` 拿注册表快照；offload 点移到
  `TextureAtlasSprite.generateMipmaps(I)` 调用处）
- `src/test/java/com/dhj/actinium/texture/atlas/AtlasLoadPlanTest.java`（新增，6 例）
- `src/test/java/com/dhj/actinium/texture/atlas/AtlasLoadSessionTest.java`（+2 例）
- `gradle/scripts/dependencies.gradle`（dev 环境 TiCon 三件套）
- `docs/research/atlas-parallel-loading-port.md`（移植设计记录同步更新）

## 验证记录

- [x] `./gradlew check --no-daemon` 通过（1213 测试）；`./gradlew build --no-daemon` 通过。
- [x] 用户实机（dev，TiCon 三件套经 extrapath 注入）：成功进入世界，不再抛
  `Stitching texture atlas` / `IndexOutOfBoundsException`。
- [ ] F3+T 重载资源场景回归（机制上同一路径，未单独确认）。

## 参考

- 上游同机制移植的设计依据：`docs/research/atlas-parallel-loading-port.md`。
