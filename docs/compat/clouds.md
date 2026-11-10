# 云与天气渲染

## 实现范围

F01 引入降水列缓存、实例化天气和分层云网格，目标是在保留 1.12.2 原有视觉与模组接管顺序的前提下减少重复采样和绘制开销。

`WeatherRenderer` 复用每世界的 `WeatherSampler`，`WeatherColumns` 保存降水、温度和光照采样。无光影使用 GLSM 实例化，Iris 使用独立天气程序；关闭实例化、后端不支持或变体构建失败时使用 `BufferBuilder` 回退。Forge `WorldProvider.getWeatherRenderer()` 和 Dynamic Surroundings 的天气接管优先于本地实现。

`CloudRenderer` 读取原版 `clouds=0/1/2` 和实际视距。`CloudLayerRenderer` 在后台构建 CPU 几何，渲染线程负责 VAO/VBO 创建、上传、重排与删除。`CloudVertexMesh` 提供位置、颜色、UV、法线和明暗属性，光影包逐面更新颜色，无需因颜色变化重建网格。

## 生命周期

- `runTick` 重新检查设置，原版 OFF 与光影包 `CloudSetting.OFF` 都释放已建资源。
- `ActiniumWorldRenderer.unloadWorld()` 先释放天气资源，再逐层释放云网格和原生缓冲，最后删除共享程序。
- 上传中的构建被替换时丢弃旧上传，保留可复用的 GL 存储。异步构建或重排失败会记录原因，退出仍继续释放。
- `cloudTime` 通过 shader 侧 provider 获取主实现的纹理宽度与倍率，保留模块依赖方向。

## 模组边界

`CloudLayerProvider` 是其它维度提供云层的公开入口。非该接口的 `IRenderHandler` 继续走 Forge 自定义云路径。GalaxySpace 1.12.2 的云参数由 AsmodeusCore 持有，与上游 1.7.10 的层表不同，本实现不生成该层表，也不把对应场景标为已验证。

## 验证记录

自动化覆盖降水采样、几何与 wrap、实例布局、天气 FFP/Iris 像素、云网格属性与像素、云程序编译、上传协议、释放和 `cloudTime` 周期。最终整批验证以构建日志为准。

仍需实机确认：原版 OFF/FAST/FANCY、雨雪和岩浆粒子、真实纹理、不同视距与云层上下、真实光影包程序选择、雾和深度预 pass、第三方自定义云、传送与反复退出。未安装对应模组的场景不标为通过。
