# 光影下的发光实体描边

在 Minecraft 1.12.2 中，用光灵箭射中生物后，原版会在独立的描边缓冲里重画该实体，并使用 `GL_ALWAYS` 生成可穿墙显示的轮廓遮罩。启用光影时，重画触发的状态变化会让 Iris 重新绑定主场景 G-buffer，描边几何随之覆盖正常绘制的实体本体。

进入描边流程时暂停 Iris 的 pass 选择，由原版负责描边缓冲、遮罩绘制和后处理。原版重新绑定 Minecraft 主 framebuffer 后，恢复 Iris 的世界 pass。描边重画保持即时提交，跳过普通实体的着色阶段与批处理资格记录；正常的实体绘制仍使用原有路径。

`gradlew check build --offline --no-daemon` 通过。回归测试覆盖描边过程中保持独立绘制目标，以及异常退出后恢复世界 pass。2026-11-20 的 Cleanroom 1.12.2 游戏日志记录了安装修复版、加载 `MakeUp-UltraFast-9.4c.zip` 并正常退出。
