# Storage Drawers 兼容性说明

兼容状态：**已验证**（抽屉内方块物品恢复正常光照）

最后更新：2026-09-24

## 验证范围

- Minecraft 1.12.2、Cleanroom Loader 0.6.12-alpha
- Storage Drawers 5.5.3、Chameleon 4.1.3
- 无光影；使用 Grass 与 Grass Block 放入 Basic Drawer 复现
- 相关 issue：[#118](https://github.com/DHJComical/Actinium/issues/118)

## 症状与根因

抽屉正面显示的方块物品光照不均，创造模式背包中的同一物品正常。问题来自 Storage Drawers 的
`TileEntityDrawersRenderer.renderFastItem`：它在抽屉局部缩放和旋转矩阵仍生效时调用
`RenderHelper.enableStandardItemLighting()`，之后才恢复矩阵并绘制物品。

OpenGL 在设置方向光时会用当时的 model-view 矩阵变换灯光方向。弹出抽屉局部矩阵不会撤销已保存的灯光方向，
所以物品绘制沿用了受抽屉变换影响的光照；创造背包在不同矩阵上下文设置标准物品灯光，因此不受影响。

## 修复

由 `mixins.actinium.storagedrawers.json` 在 `storagedrawers` 模组存在时加载兼容 Mixin。Mixin 保存
进入 `renderFastItem` 时的 model-view 矩阵；Storage Drawers 设置标准物品灯光时，Actinium 临时恢复该矩阵，
然后再恢复抽屉的渲染矩阵。抽屉中绘制的物品继续使用原有变换，方向光使用视图空间方向。

## 验证记录

- `./gradlew check --no-daemon` 通过。
- 用户实机确认：在无光影的 issue #118 场景中，Basic Drawer 内 Grass 与 Grass Block 的光照恢复正常；
  创造模式背包物品显示正常。
