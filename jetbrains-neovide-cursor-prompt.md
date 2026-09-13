# 任务：为 JetBrains IDE 实现 Neovide 风格的「拖尾光标动画」插件

> 把本文件整段粘贴到新对话即可开始。这是一个**研究与实现并重**的任务，第一步不是写代码，而是核实情报。

## 一、任务目标

在 JetBrains IDE（IntelliJ IDEA / PyCharm 等）上实现 **Neovide 风格的拖尾光标动画**——
光标移动时留下渐隐的"残影/拖尾"，带辉光效果，可配置颜色与动画参数。

⚠️ 注意：**核心诉求是"拖尾特效"，不是"平滑移动"**。官方后续版本与社区插件提供的是
平滑移动（无拖尾），若已能满足则任务降级为"确认现状"；否则需要开发原生插件。

## 二、背景

用户已完成一个 VS Code / Cursor 的同类插件（**neocursor-fix**），效果如视频：
https://www.bilibili.com/video/BV1XtviBkEMr/

- 仓库：https://github.com/yyyyolo7a79-sketch/neocursor-fix
- 其原理是**注入式**：把 `neovide-cursor.js` 复制到编辑器安装目录并往 `workbench.html`
  追加一个 `<script>` 标签，靠编辑器自身的浏览器渲染层执行 JS 动画
- **JetBrains 架构完全不同**：Swing（Java/Kotlin）而非 HTML/DOM，注入式方案完全不适用，
  必须**重写为原生插件**（Java/Kotlin + IntelliJ Platform SDK）
- 本地源码：`d:/develop/github/github上传/neo-cursor-fix/`（`需求.md` 记录了完整项目沿革）

## 三、已有情报（来自一次 DeepSeek 对话，⚠️ **均需逐条核实**）

> 出处：https://chat.deepseek.com/share/pdspgqg82w5saa01pb
> 教训：该 AI 在同类任务（Cursor 适配）中曾**半真半假**（引用的文章真实存在，但技术细节
> 张冠李戴）。以下每条都必须用 WebSearch / 官方文档 / 本机实测独立验证后再采信。

1. 官方平滑光标：DS 称 JetBrains 从 **2026.1** 起内置 "Use smooth caret movement"
   （Settings | Editor | General | Appearance），有 **Snappy**（快速到达+安定）和
   **Gliding**（平滑滑行）两种模式 + 平滑闪烁 + 圆角光标 —— **无拖尾特效**
2. 社区插件：**intellij-smooth-caret**（模仿 VS Code 平滑光标，配置项为"平滑度/追赶速度"）
   —— 同样**无拖尾**
3. DS 给出的自研技术路径（未验证）：
   - 用 `CaretListener#caretPositionChanged` 监听光标位置
   - 用 `editor.visualPositionToXY()` 做逻辑坐标 → 像素坐标转换
   - 取编辑器组件的 `JLayeredPane`，把透明自绘 `JComponent` 叠到 `DEFAULT_LAYER`
   - 用 `ScheduledExecutorService` / Swing Timer 驱动重绘，在旧位置与新位置间画渐隐残影
4. DS 提示的风险：依赖内部 API（JLayeredPane 等），IDE 升级可能失效；Swing 实时重绘
   对性能敏感；官方功能挤压第三方空间

## 四、本机环境（已实测，可直接使用）

| 项 | 值 |
|---|---|
| 主力 IDE | IntelliJ IDEA 2025.2.1（`D:/develop/idea/IntelliJIDEA2025.2.1`，build IU-252.25557.131） |
| 其他 IDE | PyCharm 2025.2.1.1（build PY-252.25557.178）、CLion 2025.2、WebStorm 2025.2 |
| 旧版（备用测试） | IntelliJ IDEA 2024.1、PyCharm 2023.1.4 |
| JDK | IDEA 自带 OpenJDK **25.0.2**（`D:/develop/idea/jdk`）；另有 jdk-21 安装包 |
| Gradle | PATH 无 gradle，但 `~/.gradle` 有缓存 → 用 Gradle Wrapper（插件开发标准做法） |
| 系统 | Windows 11 + Git Bash（Claude Code 环境，可用 WebSearch / context7 / Playwright 等工具） |

⚠️ 用户当前 IDE 是 **2025.2.x**——若官方平滑光标确实在更高版本才引入，需要确认：
用户是否愿意升级 IDE？还是插件方案要兼容 2025.2？

## 五、可直接复用的资产（本任务的最大捷径）

**动画算法蓝本**：`d:/develop/github/github上传/neo-cursor-fix/extension/assets/neovide-cursor.js`
（539 行，纯 JS，分段清晰）——**请先完整读一遍**，它的动画模型可以逐段翻译成 Kotlin：

- 阻尼弹簧动画（`DampedSpringAnimation`：位置/速度/目标，用于光标平滑逼近）
- 拖尾分段模型（`rank0TrailFactor` ~ `rank3TrailFactor`：多级残影的强度/长度递减）
- 视觉参数体系（`tailColor` / `useShadow` / `shadowBlurFactor` / `animationLength` /
  `cursorDisappearDelay` / `cursorFadeOutDuration` / `useHardSnap` 等一套完整配置）
- 全局状态追踪（跨编辑器实例/分屏时的动画起始点处理）

这套参数模型是用户在 VS Code 版上验证过的效果配方，**JetBrains 版应尽量保持参数语义一致**，
方便用户迁移配置和对比效果。

## 六、建议的工作顺序

1. **核实情报**（必做第一步）：验证第三节的 4 条信息——官方平滑光标真实版本与效果、
   intellij-smooth-caret 的真实性、JLayeredPane 路径的可行性、以及 2026 年的最新官方动态
   （DS 说 2026.1，现在已 2026-09，可能有更新版本/变化）
2. **环境盘点**：确认本机插件开发链路（Gradle Wrapper、JDK 版本要求——2025.2 平台通常要求
   JDK 21，JDK 25 是否可用需实测；IntelliJ Platform Plugin Template 是否可用）
3. **最小 POC**：写一个最小插件，验证"在编辑器上叠加透明自绘层"这一核心假设——
   只要能在编辑器区域画出一个能随光标位置更新的方块，路径就通了
4. **接线**：`CaretListener` 取光标位置 → 坐标转换 → 驱动叠加层重绘
5. **动画**：把 neovide-cursor.js 的弹簧 + 拖尾模型翻译为 Kotlin，跑通"残影拖尾"
6. **打磨**：性能（避免 EDT 卡顿）、配置界面、打包发布（Gradle 打 zip）

## 七、参考资源（请自行验证链接有效性并扩展）

- IntelliJ Platform SDK 文档：https://plugins.jetbrains.com/docs/intellij/
  （重点：CaretListener、Editor、EditorCustomElementRenderer、EditorComponent）
- 官方博客（DS 引用）：Editor Improvements: Smooth Caret Animation and New Selection Behavior
- 社区插件（DS 引用）：intellij-smooth-caret
- 插件模板：https://github.com/JetBrains/intellij-platform-plugin-template
- 动画算法原型来源（VS Code 版血统）：https://github.com/Jenlybein/vision-smash-code
- 官方功能对照参考（效果目标）：https://neovide.dev/（Neovide 拖尾效果）

## 八、验收标准

- 在用户的 **IDEA 2025.2.1 实机**上跑通：光标移动时有可见的渐隐拖尾 + 辉光
- 参数可配置（至少：颜色、拖尾长度、动画速度——语义对齐 neovide-cursor.js 的配置项）
- 不显著影响编辑器性能（打字/滚动流畅）
- 能打包成 zip 供用户本地安装（`Settings → Plugins → Install Plugin from Disk`）

## 九、工作方式

- 用户是中文母语者，全程用中文交流
- **先调研核实、再动手实现**——不要基于未经核实的 DS 情报直接写代码
- 重视实测：每个阶段都要在真实 IDE 里验证，不做"纸面方案"
- 遇到反复失败的错误时，可查阅本机陷阱库（`/trap` 技能）；经验沉淀用 `/retrospect`
