# Intel 核显上的呈现阻塞（`glfwSwapBuffers` 占满帧时间）

## 症状

采样火焰图里 `Minecraft.runGameLoop` → `Minecraft.updateDisplay` → `Display.update` → `glfwSwapBuffers` 占 50% 以上的采样时间，`JNI` 帧沉在 `Display.swapBuffers` 内部。表现是帧时间被呈现调用吃掉，用户看到的是卡顿与输入延迟，帧率数字可能仍然接近刷新率。

## 机理

这个调用栈本身是 vsync 开启时的正常路径。驱动在 `SwapBuffers` 里阻塞到下一个 vblank，CPU 在阻塞期间无法提交下一帧。Intel 驱动在窗口模式下还会经过 DWM 合成，阻塞时长同时受合成队列与 GPU 完成时间影响，因此同一配置下每次 swap 的阻塞时间并不固定。

Intel 驱动还有第二个等待点：帧首对默认帧缓冲的第一次 `glClear` 会等待上一帧的呈现完成。只统计 swap 会低估驱动的节流程度，所以 Actinium 把这次 clear 单独计时并计入同一份呈现样本。

这套机制不能让 vsync 下的帧率超过刷新率。它做的是把驱动内部的隐式等待变成可测量、可预测的等待，并在驱动没有真正节流时由软件接管节奏。

## Actinium 的判定与处理

每个 `Display.update` 被包装为一次呈现样本（gate），记录阻塞时长与返回时刻。帧首第一次 `glClear` 同样计入样本，两者取较长者。帧节奏器 `FramePacer` 在每帧末尾读取样本并进入三种状态之一：

- **hardware paced**：连续样本超过 `cadence/32` 且相邻样本间隔稳定在刷新周期上。判定驱动在节流，软件不再叠加任何睡眠，只做输入事件处理。
- **probing**：vsync 开启但尚未确认驱动节流。每帧把提交时刻提前 `1/8` 个周期，最多提前两个周期，用来寻找 vblank 边界。找到后转入 hardware paced。
- **paced by wall clock**：探测耗尽说明驱动没有阻塞，此时由 `PacerSleeper` 的自适应 park 加 spin 补齐节奏，精度在几十微秒量级。

三种状态各输出一条日志：

```
Frame pacing: vsync 60Hz, hardware paced
Frame pacing: vsync 60Hz, probing
Frame pacing: vsync 60Hz, driver not blocking, paced by wall clock
```

开启 vsync 但驱动没有阻塞时，第三条是 `warn` 级别。刷新率读不到时另有一条提示，要求用户设置 Max Framerate 作为上限。

原版帧率限制器 `Display.sync` 被置空。lwjglx 的实现是一毫秒 sleep 加 yield 循环，与驱动的 vblank 等待叠加后只会放大抖动。

## 用户侧排查

1. 先看 `logs/latest.log` 里的 `Frame pacing:` 行，确认当前是三种状态中的哪一种。
2. 出现 `paced by wall clock` 且用户没有关闭 vsync 时，检查 Intel 显卡控制中心的垂直同步设置。该设置会覆盖应用请求的 swap interval，应用侧无法关闭。
3. 出现 `hardware paced` 但帧率低于刷新率时，瓶颈在 GPU 渲染时间，不在呈现路径，需要降低渲染距离、阴影或光影包配置。
4. 刷新率日志显示 0Hz 时，说明窗口系统没有报告刷新率。此时设置 Max Framerate 可以给 pacer 一个上限。

## 验证

- `PacerCoreTest`、`PacerSleeperTest` 覆盖锁定、探测、探测耗尽后的时钟节流、睡眠精度与统计窗口。
- `BackendPolicyTest` 覆盖 vsync 模式解析。
- 实机验证待补：Intel 核显、60Hz、vsync 开启与关闭各一次采样，记录 `Frame pacing` 日志与 `swapBuffers` 占比。
