# 桥（Bridge）与宿主能力注入清单

最后更新：2026-08-12

## 目的

渲染栈（`glsm`/`GTNHLib`/`celeritas-common`/`shader`）不得反向引用宿主（根项目
`com.dhj.actinium`）。需要宿主能力（配置、运行时状态、原版字段访问）时，由底层模块
定义**桥接口 + 静态注册门面**，根项目在 `Actinium.onConstruct` 注册实现。本文档是
全部桥的登记表，新增桥必须遵循末尾的约定。

## 桥清单

### 跨模块桥（底层模块定义，根项目注册）

| 桥 | 位置（接口/门面） | 注入的能力 | 注册点 | 调用方 |
|----|------------------|-----------|--------|--------|
| `PostProcessingBridge` | GTNHLib `postprocessing` | 深度纹理 id、lightmap 颜色/纹理、night vision 亮度（经 mixin accessor 访问原版字段） | `Actinium.onConstruct` | shader |
| `RuntimeOptionsBridge` | GTNHLib `renderer` | 直接内存访问开关 | `Actinium.onConstruct` | GTNHLib 内部 |
| `IrisDebugOptions.Bridge` | shader `net.coderbot.iris.debug` | debug 开关（8 项）、功能开关（enableIris/enableCeleritas 等 6 项）、`cycleAnimationsMode` | `Actinium.onConstruct` | shader |
| `WorldRendererCompatBridge` | shader `net.coderbot.iris.celeritas` | 阴影 pass 的地形渲染面（绘制/视口/图脏/统计） | `Actinium.onConstruct` | shader |
| `EmbeddiumRuntimeOptions` | celeritas-common | chunk multi-draw 模式 | `Actinium.onConstruct` | celeritas |
| `GLSMPerfDebugHooks` | glsm `hooks` | perf 统计、开关与监听 | `Actinium.onConstruct` | glsm |

### 服务提供（ServiceLoader 模式）

| 提供者 | 位置 | 提供内容 |
|--------|------|---------|
| `GLStateManagerServiceProvider` / `RenderSystemServiceProvider` | glsm | GLSM/RenderSystem 服务实例 |
| `LWJGLServiceProvider` | lwjglCommon | LWJGL 服务 |

### 模块内桥（不跨模块，登记备查）

| 桥 | 位置 | 职责 |
|----|------|------|
| `IrisGLSMBridge` | 根项目 `com.gtnewhorizons.angelica.iris` | Iris 延迟状态机 ↔ GLSM DeferredHandler 接线 |
| `PortalViewportProvider` / `OptionGUIConstructionBridge` | 根项目 compat | 各模组专用注入 |

## 新增桥的约定

1. **接口定义放调用方模块**（谁需要能力，谁定义接口与静态门面）；注册门面与被调用方
   零依赖。
2. **注册实现放根项目 `Actinium.onConstruct`**（与现有注册集中在一处，便于审阅）。
3. **门面提供「未注册」的确定行为**：功能开关返回配置默认值（保证早期类加载时序
   无回归），调试开关返回 false（关闭）。
4. **不暴露被注入方的具体类型**：接口方法返回最小类型（如 `WorldRendererCompat`
   用 `markSectionGraphDirty()` 压平返回根项目类型的 `getRenderSectionManager()`）。
5. **新增桥必须有单元测试**：验证默认行为与注册后的转发（参照
   `ForgeEarlyGlConfigTest` 的保存/恢复模式）。
