# ReplayMod 兼容性说明

兼容状态：**已验证（视频渲染）**
最后更新：2026-09-25

## 验证环境

- Minecraft 1.12.2 / Cleanroom 0.6.13-alpha
- ReplayMod 1.12.2-2.6.13
- 光影包：BSL_v10.1p1.zip
- Java 25.0.3、Windows 11、NVIDIA GeForce RTX 5070 Laptop GPU（驱动 617.14）
- 用户整合包实例：`1.12.2-Cleanroom`

用户实测确认：在以上实例启用 BSL 光影并使用 ReplayMod 导出视频后，崩溃不再复现。

## 症状与根因

ReplayMod 的视频导出触发 `Rendering video` 崩溃。最初表现为
`GbufferPrograms in weird state`，区块实体阶段开始时 G-buffer 仍处于 outline 阶段；修复该状态泄漏后，
测试继续暴露了 ReplayMod 的 PBO 帧读取与 GLSM API 之间缺少 `long` 重载。

### Selection box outline 阶段未清理

ReplayMod 的 `Mixin_SkipBlockOutlinesDuringRender` 在 `RenderGlobal.drawSelectionBox` 的 HEAD 注入中，
视频捕获 handler 存在时调用 `ci.cancel()` 跳过方法体。Actinium 原先分别在 HEAD 调用
`GbufferPrograms.beginOutline()`、在 RETURN 调用 `endOutline()`；取消方法体会提前返回，绕过 RETURN
注入，留下 `outline=true`。之后 Actinium 的 `MixinRenderGlobal.sodium$renderTileEntities` 调用
`beginBlockEntities()`，触发 G-buffer 的重入检查。崩溃后的
`Called beginLevelRendering but level rendering appears to still be in progress` 是首次异常打断渲染清理后的
连锁错误。

修复将 outline 生命周期改为包住完整 `drawSelectionBox` 调用的 MixinExtras `@WrapMethod`，清理放进
`finally`。专用 Mixin priority 为 1100，高于 ReplayMod 默认的 1000，使 wrapper 在 ReplayMod 取消注入之后
应用，从而让取消路径也经过 `finally`。

### PBO readback 缺失 long-offset 路由

ReplayMod 的 `PboOpenGlFrameCapturer.captureFrame` 先绑定 pixel-pack buffer，再以 `long` 偏移调用
`GL11.glReadPixels`。GLSMRedirector 将调用转到 `GLStateManager`，但当时 GLSM 只有 `ByteBuffer`、
`FloatBuffer` 和 `IntBuffer` 重载，导致 `NoSuchMethodError: GLStateManager.glReadPixels(..., long)`。

修复为 `GLStateManager`、`RenderBackend` 和 LWJGL 3 backend 增加 PBO offset 重载；该路径把 byte offset
直接传给 `GL11C.glReadPixels`，并保留调用方已经绑定的 pixel-pack buffer，不经过 NIO buffer 路径中的
PBO suspend/restore。

## 前置排查记录

初次启用 ReplayMod Quick Mode 时，实例中由 FML 注入 classpath 的独立 Guava 33.6.0 jar 导致
`Futures.addCallback(ListenableFuture, FutureCallback)` 缺失。该问题发生在 ReplayMod 的 Quick Mode 初始化，
与 Actinium 的渲染修复不同；用户解决该运行时依赖问题后，才复现并验证了上面的 shader video-render 路径。

## 改动文件

- `src/main/java/com/dhj/actinium/mixin/features/iris/RenderGlobalSelectionOutlineMixin.java`
- `src/main/java/com/dhj/actinium/render/iris/SelectionBoxOutlinePhase.java`
- `src/main/java/com/dhj/actinium/mixin/features/iris/RenderGlobalIrisMixin.java`
- `src/main/resources/mixins.actinium.iris.json`
- `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java`
- `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/backend/RenderBackend.java`
- `src/lwjgl3/java/com/gtnewhorizons/angelica/lwjgl3/Lwjgl3GLRenderBackend.java`

## 验证记录

- [x] `shadowRemapJar` 构建成功；产物中的 `GLStateManager.glReadPixels` 含 `(IIIIIIJ)V` 重载，
  refmap 将 `drawSelectionBox` 映射到 `func_72731_b`。
- [x] `./gradlew check --no-daemon` 通过。
- [x] 用户在上述 Cleanroom 实例中加载 `Actinium-alpha-0.0.10-504c2759-pbo-readpixels.jar`，
  启用 BSL_v10.1p1 并完成 ReplayMod 视频导出；用户确认修复。
