# MobileGlues / Mali：`GL_EXT_clip_cull_distance` 与裁剪面着色器注入

> 对应 issue：[#214](https://github.com/Q-Engineering-Source/Actinium/issues/214)。
> 当前状态：修复已实现并通过自动化测试，实机验证待确认。

## 症状

在 Android 上通过 MobileGlues 2.0.0（ANGLE/Vulkan 后端，Mali-G720，POCO X7 Pro）运行并开启光影包后：

1. 所有 `gbuffers_*` 与 shadow 顶点着色器编译失败，日志中出现：
   ```text
   ERROR: 0:x: 'GL_EXT_clip_cull_distance' : extension is not supported
   ```
2. 实体不渲染（程序链接失败后被跳过）。
3. 随后 `DeferredWorldRenderingPipeline` 的阴影供应器返回 `null`，
   `CeleritasTerrainPipeline.initTerrainSamplers` 抛出 NPE，客户端崩溃。

## 根因链

1. **Actinium 无条件注入裁剪面代码**。`CommonTransformer`（`shader/.../pipeline/transform/CommonTransformer.java`）
   在 `Patch.ATTRIBUTES` + VERTEX 时向每个顶点着色器注入
   `uniform vec4 actinium_ClipPlane[8]` 和 8 条 `gl_ClipDistance[i] = dot(...)` 写入，
   仅靠运行时 uniform 门控，编译期必然出现 `gl_ClipDistance`。
2. **能力位误判**。MobileGlues 把 `GL_VERSION` 伪装成桌面格式（`"4.3.0 MobileGlues 2.0.0"`），
   不含 `"OpenGL ES "` 前缀，因此 `RenderSystem.isGLES()` 为 false；原判定
   `hasClipCullDistance = !isGLES || hasExtension("GL_EXT_clip_cull_distance")`
   把它当作桌面 GL，判定支持 `gl_ClipDistance`（桌面 GLSL 130+ 为核心特性）。
3. **转译后端没有该扩展**。MobileGlues 内部走 glslang → SPIRV-Cross 转 ESSL；
   SPIRV-Cross 见到 `gl_ClipDistance` 会输出 `#extension GL_EXT_clip_cull_distance : require`
   （`MobileGlues-cpp/3rdparty/SPIRV-Cross/spirv_glsl.cpp`），而 ANGLE/Mali 后端不支持该扩展，
   于是所有含注入代码的程序编译失败。

## 修复：双侧契约

### Actinium 侧（本仓库）

- `RenderSystem.initRenderer` 新增 MobileGlues 探测（`GL_VERSION` 含 `"MobileGlues"`，或扩展表含
  `GL_MG_mobileglues`），能力判定改为
  `resolveClipCullDistanceSupport(frontendHasExtension, isGLES, isMobileGlues)`
  = `frontendHasExtension || (!isGLES && !isMobileGlues)`。
  即：MobileGlues 环境下只看扩展表是否声明 `GL_EXT_clip_cull_distance`，不再被桌面版本号误判。
- `CommonTransformer` 的裁剪面注入改为仅在 `RenderSystem.hasClipCullDistance()` 为真时执行。
- `VertexKey` 的 `BIT_CLIP_PLANES` 置位同样加 `hasClipCullDistance()` 门控，保证 FFP
  着色器 key 与注入行为自洽（同时修复真实 GLES 无扩展时的同构隐患）。

### MobileGlues 侧（`D:\Code\MobileGlues`，分支 `feat/declare-clip-cull-distance`）

`MobileGlues-cpp/gles/loader.cpp` 的 `InitGLESCapabilities()` 中，当真实 ES 后端支持
`GL_EXT_clip_cull_distance` 时把它 AppendExtension 进伪装桌面扩展表：

```cpp
if (g_gles_caps.GL_EXT_clip_cull_distance) {
    AppendExtension("GL_EXT_clip_cull_distance");
}
```

这样 Actinium 的 `hasExtension("GL_EXT_clip_cull_distance")` 在后端真支持时直接命中，
裁剪面注入恢复启用。**无条件声明没有意义**：后端不支持时，SPIRV-Cross 输出的
`#extension ... : require` 仍会被 ANGLE 拒绝。

## 降级行为

后端不支持 `GL_EXT_clip_cull_distance` 时（issue #214 的 Mali-G720 + ANGLE 环境）：

- 着色器不再包含 `gl_ClipDistance` 写入，全部程序正常编译，实体渲染与崩溃消失。
- 固定管线裁剪面（`glClipPlane` 语义）不可用。vanilla 1.12.2 不调用 `glClipPlane`
  （MCP 反编译检索 0 结果），该特性只服务极少数 mod，降级影响可忽略。
- glsm 侧 `CompatUniformManager.uploadClipPlanes` 对 location == -1 已容错，
  着色器不声明 uniform 时上传是自然空操作，无需额外改动。

## 已知限制

- MobileGlues 的 `hideMGEnvLevel ≥ 1` 会同时隐藏 `GL_VERSION` 中的 `"MobileGlues"` 标记和
  `GL_MG_mobileglues` 扩展。此时 Actinium 无法识别 MobileGlues，会按桌面 GL 判定并恢复注入——
  该组合属于 MobileGlues 的文档化限制，不在本修复覆盖范围内。

## 验证记录

- Actinium：`ClipCullDistanceSupportTest`（能力判定三用例矩阵：桌面 / GLES / MobileGlues）、
  `ClipPlaneInjectionTest`（`TransformPatcher.patchAttributes` 双向验证注入有无）；
  `./gradlew check --no-daemon` 通过。
- MobileGlues：NDK 27.3.13750724 clang++ `--target=aarch64-linux-android21 -fsyntax-only`
  编译 `gles/loader.cpp` 通过。
- 桌面回归与实机（POCO X7 Pro）验证待用户确认。
