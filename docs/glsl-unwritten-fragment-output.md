# 未写入的 fragment 输出：GLSL 源码、SPIR-V 与规范语义

以 Derivative Main d24.4.14 在 SDL GPU（Vulkan）后端下的天空绿线为例，说明一个只在 SPIR-V 路径上暴露的未定义行为。

## 结论

`deferred6` 的唯一输出只在一个条件分支里赋值，天空像素不进入该分支，输出从未写入。GLSL 与 OpenGL 对未写入的 fragment 输出只规定 undefined，不规定写 0，也不规定保留帧缓冲原有内容。glslang 生成的 SPIR-V 忠实反映了这一点：条件为假时不产生任何对输出的 `OpStore`。OpenGL 后端看起来正常，是当前 GL 编译器把输出寄存器清成 0 的实现细节。

所以这不是 GLSL 到 SPIR-V 的翻译错误，也不是后端能补的缺口。要让行为有定义，只能在 shader 里给输出一个初值。

## 现场代码

`shaders/world0/deferred6.fsh` 只做转发：

```glsl
#version 450 compatibility

#define IS_OVERWORLD

layout(location = 0) out vec4 reflectionData;

#include "/lib/Head/Common.inc"
#include "/lib/Head/Uniforms.inc"
#include "/lib/Atmosphere/Atmosphere.glsl"
#include "/lib/Head/Material.inc"
#include "/lib/Head/Functions.inc"
#include "/lib/Surface/ScreenSpaceReflections.glsl"
```

实际逻辑在 `shaders/lib/Surface/ScreenSpaceReflections.glsl`：

```glsl
void main() {
	ivec2 texel = ivec2(gl_FragCoord.xy);
	Material material = GetMaterialData(texelFetch(colortex0, texel, 0).xy);

	if (material.hasReflections) {
		vec2 screenCoord = gl_FragCoord.xy * screenPixelSize;
		vec3 normal = GetNormals(texel);
		float depth = GetDepthFix(texel);
		vec3 screenPos = vec3(screenCoord, depth);
		vec3 viewPos = ScreenToViewSpace(screenPos);
		float skyLightmap = cube(texelFetch(colortex7, texel, 0).g);
		reflectionData = CalculateSpecularReflections(normal, screenPos, material, skyLightmap, viewPos);
	}
}

/* DRAWBUFFERS:2 */
```

`reflectionData` 全函数只有这一处赋值。判别条件来自 `shaders/lib/Head/Material.inc`：

```glsl
Material GetMaterialData(in vec2 specTex) {
	Material material;

	material.roughness = specTex.r;
	...
	material.hasReflections = max0(0.625 - material.roughness) + material.isMetal > 5e-3;
	material.isRough = material.roughness > ROUGH_REFLECTIONS_THRESHOLD;
}
```

天空像素的 colortex0 为 `[1, 0, 0, 0]`，得到 `roughness = 1.0`、`isMetal = 0`、`hasReflections = 0`，条件为假，输出一次都没写。

## 运行时 SPIR-V

从 RenderDoc 抓帧取该 fragment 阶段绑定的模块，再用 `spirv-dis` 反汇编：

```python
shader_id = controller.GetPipelineState().GetShader(rd.ShaderStage.Fragment)
reflection = controller.GetPipelineState().GetShaderReflection(rd.ShaderStage.Fragment)
entry = rd.ShaderEntryPoint()
entry.name = reflection.entryPoint
module = controller.GetShader(shader_id, shader_id, entry)
open("deferred6.spv", "wb").write(bytes(module.rawBytes))
```

```sh
spirv-dis.exe deferred6.spv
```

模块大小 39,124 字节，`reflectionData` 相关指令只有装饰与一条存储：

```spirv
OpName %reflectionData "reflectionData"
OpDecorate %reflectionData Location 0
...
%1525 = OpFunctionCall %v4float %CalculateSpecularReflections_...
        OpStore %reflectionData %1525
        OpBranch %1479
%1479 = OpLabel
        OpReturn
        OpFunctionEnd
```

没有 `OpUndef`，也没有无条件写回。条件为假时控制流直接到达合并标签并返回，输出保持未写入状态。模块合法，SPIR-V 不要求 fragment 输出必须被写。

用同一套 glslang（RenderDoc 自带 `plugins/spirv/glslangValidator.exe`）编译一份最小复现，可以得到同样的结构：

```glsl
#version 450

layout(location = 0) out vec4 reflectionData;

void main() {
	if (gl_FragCoord.x > 1e6) {
		reflectionData = vec4(1.0);
	}
}
```

```spirv
%19 = OpAccessChain %_ptr_Input_float %gl_FragCoord %uint_0
%20 = OpLoad %float %19
%23 = OpFOrdGreaterThan %bool %20 %float_1000000
      OpSelectionMerge %25 None
      OpBranchConditional %23 %24 %25
%24 = OpLabel
      OpStore %reflectionData %29
      OpBranch %25
%25 = OpLabel
      OpReturn
      OpFunctionEnd
```

这是编译器的稳定行为，不是某个包的偶然产物。

## 规范语义

GLSL 与 OpenGL 对 fragment 输出的规定是未写入即 undefined。规范既不承诺写 0，也不承诺保留附件原有内容，实现可以给出任意值，包括寄存器残留、NaN 或 Inf。Vulkan 与 SPIR-V 的约束相同：只要求模块合法，不要求输出被写，具体写入附件的值同样是 undefined。

由此得到三点：

- OpenGL 后端画面正常，依赖的是当前 GL 编译器把输出寄存器清成 0。换驱动、换 shader 变体、改寄存器分配都可能改变结果。
- 在 SPIR-V 路径上，同一像素写入的残留会参与后续运算，被放大成 Inf，落入 RGBA16F 目标后截断成 65504。这类"垃圾值"往往比 0 更刺眼，也更容易连锁。
- 后端无法补救：SPIR-V 里没有对输出的写操作，运行时无从补值。要得到确定结果只能在 shader 侧初始化。

## 为什么表现为天空里的断续绿线

单帧内的传播链（RenderDoc 抓帧，854x480，探针像素 (851,4)）：

1. `deferred6` 把 colortex2 写成 `[0, 65504, 0, 0]`，65504 是 RGBA16F 的上限，来源于 Inf 截断。
2. `deferred7`（`program/ReflectionFilter.frag`）读取 colortex2 后过滤写回。
3. `deferred2`（`program/Deferred2.glsl`）的时域上采样把它混进 colortex1 历史缓冲。棋盘格分支在非刷新相位直接取 `skyboxData = prevData`，污染被原样保留。

结果只在天空出现，且只在转动视角时可见，形态是网格状断续线。兼容记录与验证参见 `docs/compat/derivative.md`。

## 修复

修复落在光影包侧，`main()` 开头给输出一个确定初值：

```glsl
void main() {
	reflectionData = vec4(0.0);
	ivec2 texel = ivec2(gl_FragCoord.xy);
	...
}
```

## 同类写法

`shaders/program/Deferred0.glsl` 的 `main()` 里有两处同样的模式：

```glsl
void main() {
	if (int(gl_FragCoord.x) == skyCaptureRes.x) {
		switch (int(gl_FragCoord.y)) {
		case 0: colortex5Out = directIlluminance; break;
		case 1: colortex5Out = skyIlluminance; break;
		case 2: colortex5Out = sunIlluminance; break;
		case 3: colortex5Out = moonIlluminance; break;
		}
	} else if (gl_FragCoord.y < skyCaptureRes.y + 2.0) {
		vec3 worldDir = UnprojectSky(gl_FragCoord.xy * rcp(skyCaptureRes));
		colortex5Out = GetSkyRadiance(atmosphereModel, worldDir, worldSunVector, transmittanceOut) * 20.0;
	} else {
		vec3 worldDir = UnprojectSky(gl_FragCoord.xy * rcp(skyCaptureRes) - vec2(0.0, 1.0));
		colortex5Out = RenderSkybox(worldDir, GetGlobalCloudProperties());
	}
}
```

`colortex5Out` 在 `x == skyCaptureRes.x` 且 `y` 不属于 0 到 3、5 时不赋值；`transmittanceOut` 只在原始天空图分支里由 `GetSkyRadiance` 写出。当前没有可见异常，因为受影响像素后续被 `deferred5` 覆盖，但这两处与 `deferred6` 属于同一类写法。

## 检测方法

对任何光影包都可以用同样的流程确认这类问题：

1. RenderDoc 抓一帧，按 pass 顺序在可疑附件上采样探针像素，找到首个出现异常值的 pass。
2. 取出该 pass 的 fragment SPIR-V，`spirv-dis` 后对该 pass 的每个输出变量统计 `OpStore` 数量与位置。只有条件分支内的单条 `OpStore`，且该分支在实际像素上为假，就是这个问题。
3. 给输出补零初始化后重测同一像素，异常值消失即可确认。
