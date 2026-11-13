# SDL-GPU 同步与宿主适配

## 来源与运行条件

同步源为本地 `D:/Code/Angelica` 的 `master`，完整 SHA 为 `8e099e952921a53ea5db88414e1071135f716ed4`，核对时工作树干净，`origin/master` 指向相同提交。导入使用此提交的完整 `sdl-gpu/src/main`，包括初次引入后对纹理释放、FBO 附件、采样器、buffer mapping 和线程呈现的修复。

开发环境使用 Outlands 本地 `2026.11.11-2`，由 `D:/Code/Outlands` 的 `feat/non-gl-render-backend-lifecycle` 分支提供。`a7420256` 添加能力覆盖与共享 drawable 来源入口，`1948c780` 添加窗口事件和无 GL 生命周期。Outlands 的改动限于 lwjgl3ify 对应的通用窗口能力，Angelica 的 device、pipeline、resource、shader 与 presenter 实现在 Actinium 内。

LWJGL Java 绑定和 shaderc、SPIRV-Cross native 版本统一为 `3.4.3`。普通启动默认 OpenGL。视频设置的高级页面提供 SDL GPU、驱动、排队帧数与独立呈现线程选项，修改后重启。旧 CRL 保留 OpenGL 路径；缺少 Outlands 窗口事件接口时不会创建 SDL backend。

## 与上游的适配差异

- Outlands 使用 Foundation 的客户端主线程管理窗口。Presenter 在窗口线程直接执行，并能单次认领工作线程已排队的任务，避免等待自身消息队列；Angelica 的 RFB 使用独立窗口线程。
- lwjgl3ify 的 `DisplayEvents`、能力覆盖与共享 drawable 行为由 Outlands 的对应公开接口提供。Actinium 只保留 backend 专用的 Display/shared drawable 派发。
- SDL backend 在宿主依赖检查成功后通过 factory 注册；分发 ServiceLoader 清单只保留 OpenGL provider，避免旧 CRL 提前解析 SDL 宿主类。
- Celeritas 顶点格式由本地 `ExtendedChunkVertexType` 定义。CHUNK compute 从实际 format 读取 stride 和每项 attribute offset；所需 image 写入在 shadow terrain 绘制前由 compute 执行，程序及借用的 SSBO base/range 状态在退出时恢复。
- Iris 提前同步 sampler alias 合并、整数 sampler 转 storage image、局部初始化、vertex replay、composite prepass 与 shadow voxelization。原有光影兼容修补和磁盘缓存保留；shared uniform block 预热优化后续处理。
- Actinium 的后端接口包含上游 SDL 未实现的 DSA、压缩纹理和 64 位 indexed range 查询，本次补齐实际上传、回读和状态恢复。SDL 缺少原生 signed BC4/BC5 格式时明确报错。
- 长期诊断使用 `GLSMDebug.isEnabled()`，原生 Tracy 采集继续关闭。

审查还修复了上游继承的两类等待错误：SDL fence 等待失败不再报告已完成；上传提交等待和传输线程退出收到中断后继续等待完成，再恢复中断标记。真实 GLSL 编译补获并修复了保留名称矩阵 uniform、零参调用识别和 integer sampler 的 `textureSize` 转换问题。

## 验证场景

自动化验证覆盖 SDL device 上传/回读、生产 shader 编译与离屏像素、scissor、压缩块原始回读和解码、上下文失败回滚、terrain 范围、indexed SSBO 状态恢复与 shader image 写入变换。2026-11-11 执行 `./gradlew check glIntegrationTest :sdl-gpu:gpuTest build --no-daemon --offline` 全部通过：根项目 1268 项测试无失败（4 项跳过），SDL 逻辑测试 292 项、GTNHLib 8 项通过；原生 GL 回归 272 项、真实 SDL GPU 测试 4 项通过。

根项目全部源码替换为 CRL 0.6.12-alpha 编译 classpath 后再次编译通过。真实 CRL 0.6.13-alpha 配置测试确认 Outlands 宿主检查关闭、core 请求及恢复保存成功。完整客户端场景由用户验收。

用户实机检查：Outlands 安装本地宿主产物后启用 SDL GPU，检查启动与加载画面、无光影世界、目标光影包、资源重载、维度切换、窗口缩放/全屏和退出。CRL 使用同一个 Actinium 分发 jar，验证默认 OpenGL 与原有光影路径。Vulkan、Direct3D 12 与 Metal 的实际支持取决于操作系统和驱动。
