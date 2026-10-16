# SDL GPU 后端

移植中的实验后端(`-Dangelica.sdlgpu.enable=true`,runClientSdl)。本文记录排查过的问题与已知缺陷。

## 已知问题

### readback 路径干扰渲染(低优先级)

SDL 后端的 `glReadPixels`/`glGetTexImage`/`glGetBufferSubData` 实现会执行 mid-frame submit
与 pending-clear 物化(`SDLGPURenderBackend.readback` / `flushAndSubmitMidFrame`)。GL 语义上
readPixels 是只读操作,不应改变渲染结果;SDL 后端目前会扰动正在构建的帧。生产路径不受影响
(readback 在正常游玩中几乎不发生),但调试工具触发时会可见——见下节黑帧事件。

## 排查记录:周期性黑帧(2026-10-16)

**症状**:世界内每秒约一次单帧全屏纯黑(GUI 一并消失)。

**根因**:`actinium-options.json` 中 `debug.enable_actinium_gl_debug=true` 驱动
`IrisGlDebug.logWhiteScreenProbe`(白屏排查遗留打点)每秒执行 5 次 `glReadPixels` + 状态查询;
在 SDL 后端触发上节的 readback 副作用,导致该帧 fbo0 以空内容 present。关闭开关即消失。

**排查方法**(供后来者参考,打点已撤):

- 帧间隔指纹:probe 命中间隔 10-11 帧且当时帧率被压到 10fps,换算为每秒一次 → 周期源是
  秒级事件而非帧级调度。
- 黑帧哨兵:每帧末异步 readback `finalTarget` 中心像素、预填哨兵值区分"download 未执行"与
  "真黑"、只报孤立单帧黑(连续黑是看暗处的正常情况)。证实黑帧时 finalTarget 内容真的为空,
  排除 swapchain/present 层。
- 二分:DH、Xaero 分别移除均不复现差异,排除外部 mod。
- 帧中 `glClear(fbo0)` 肇事栈打点暴露了 MC 帧首 clear,但真正相关线索是日志里与黑帧逐秒
  重合的 `white-screen-probe` 输出。

**结论**:非渲染管线缺陷;`enable_actinium_gl_debug` 是诊断开关,生产保持关闭。长期修复方向是
让 SDL 后端的 readback 不再提交/物化当前渲染帧(独立 CB 或延迟到帧末)。
