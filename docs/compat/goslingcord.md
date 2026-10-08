# Goslingcord（Emojicord fork）兼容性说明

兼容状态：**已验证**（检测到 `emojicord` 时字体批处理器整体让位；`./gradlew check` 通过，实机回归通过）
最后更新：2026-10-08

## 验证范围

- 版本：Goslingcord 2.1.1（CurseForge 1207865:7269343，`Goslingcord-1.12.2-2.1.1-universal.jar`，
  mod id 仍为 `emojicord`）
- 相关功能：Actinium 字体批处理渲染器（`BatchingFontRenderer`）与 Goslingcord 的
  emoji 替换渲染（聊天消息、emoji 选择面板、搜索建议）
- 触发环境：issue #204（Cleanroom 1.12.2，光影开启；与光影无关，无光影同样复现）

## 症状：emoji 面板文字全部乱码

### 现象

安装 Goslingcord 后打开聊天框的 emoji 选择面板，面板内文字（分组标题、条目名、搜索框
提示）全部渲染成随机字形/乱码；vanilla 聊天文字与其他模组 GUI 文字正常。禁用 Actinium
即恢复。

### 机制

1. Goslingcord 是纯 ASM coremod（`net.teamfruit.emojicord.asm.FontRendererTransform`），
   把 emoji 替换逻辑直接织入 vanilla `FontRenderer` 本体：
   - `renderStringAtPos` HEAD：`text = EmojiFontRenderer.updateEmojiContext(text)`，
     把 `:name:` / `<:name:id>` 换成 `\u0000` 占位符并记录静态上下文；
   - `renderChar` HEAD：命中 `\u0000` 时绑定对应 emoji 纹理自绘并 `return 10.0F`；
   - `getCharWidth` HEAD：`if (c == 0) return 10;`。
2. Actinium 的 `MixinFontRenderer` 在 `drawString` / `renderString` / `getCharWidth`
   的 HEAD 取消原方法并改走 `BatchingFontRenderer`，该路径完全绕过
   `renderStringAtPos` / `renderChar` → emoji 替换与自绘整条链路失效。
3. 面板条目的绘制字符串含 `\u0000` 占位符；批处理器把 `\u0000` 当普通字符从字体图集
   取格绘制 → 每个占位符画成一个"随机字形"，即面板上的乱码行。
4. DragonCore 的按类名前缀让位机制对它无效：Goslingcord 改的是 vanilla `FontRenderer`
   本体，渲染器类名不变，没有可匹配的类名前缀。

同因先例：DragonCore（按渲染器类名让位）、NeoFontRender（整体让位）。

## 修复：按模组存在性让 batcher 整体让位

兼容策略位于 mixin 包外的 `FontBatcherCompat.isBatcherDisabledFor(Class<?>)`：

- `Mods.EMOJICORD = isModPresent("emojicord")`（GTNHLib `Mods`，启动期扫描缓存）；
- 命中后所有渲染器的 `drawString` / `renderString` / `getCharWidth` 全部回退 vanilla
  路径，与 NeoFontRender 让位行为一致。
- 选择整体让位而非按字符串分流：`:name:` → `\u0000` 的解析是 Goslingcord 内部状态机
  （静态上下文 + 宽度契约 `getCharWidth('\0') == 10`），不宜在 Actinium 侧复制；聊天是
  emoji 的主要载体，整体回退的代价与 NeoFontRender 让位相同，可接受。

验证：分支 `fix/goslingcord-font-corruption` 构建实机回归通过——emoji 面板分组标题、
条目名、搜索框文字与 emoji 图像渲染正常，聊天 `:alias:` 短代码正常，开/关光影（BSL）均
正常，vanilla 聊天与 JourneyMap 文字无回归。

## 后续维护

- 检测只依赖 mod id `emojicord`，不引用任何 Goslingcord 类；上游若改 mod id 需同步
  `Mods.EMOJICORD`。
- 若未来要恢复批处理器与该模组共存，需要在批路径内复刻其占位符语义（替换、宽度、
  纹理绑定时序），属于新增适配层而非本修复的范畴。
