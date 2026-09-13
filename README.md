# Neovim Cursor for JetBrains IDE

> 把 **Neovide 风格的拖尾光标**带到 JetBrains IDE —— 光标移动时留下渐隐的拉伸残影，带辉光光晕。

⚠️ **实验性项目**：核心效果已可用，但受限于 Swing/AWT 的渲染机制，帧率不及 Electron 系的 VS Code 版。
**欢迎有缘人继续优化** —— 文末附完整的踩坑记录与技术分析，希望能帮你少走弯路。

---

## 效果

光标移动时：

- **拖尾**：光标被"拉长"成不规则四边形，前缘紧跟、后缘拖沓（**不是残影叠加**，见下文原理）
- **平滑跟随**：阻尼弹簧驱动，非瞬时跳变
- **辉光**：边缘柔和光晕（模拟 Canvas `shadowBlur`）
- **原生光标隐藏**：避免与拖尾重叠

参数语义与 VS Code 版 `neovide-cursor` **完全对齐**，便于跨平台迁移配置。

---

## 安装

### 方式一：从 Release 下载（推荐）

1. 到 [Releases](../../releases) 下载最新的 `*.zip`
2. IDEA → `Settings` → `Plugins` → `⚙️` → `Install Plugin from Disk...`
3. 选择该 zip，重启 IDE

### 方式二：自行构建

```bash
# 要求：JDK 21、Gradle 9.0+、IntelliJ Platform Gradle Plugin 2.x
./gradlew buildPlugin
# 产物：build/distributions/*.zip
```

若使用本地已安装的 IDE（免下载数 GB 分发包），在 `build.gradle.kts` 中改：

```kotlin
dependencies {
    intellijPlatform {
        local("D:/path/to/IntelliJIdea2025.2")   // 换成你的安装路径
    }
}
```

---

## 使用

- **开关**：`Tools` 菜单 → `Neovide 拖尾光标（开关）`
- **性能诊断**：`Tools` 菜单 → `Neovide 光标：性能诊断（禁用重绘）`
  - 勾选后跳过全部绘制，只跑物理计算。用于区分「绘制慢」还是「调度慢」——**这是个很有用的排查手段**

关闭开关会立即恢复原生光标并移除覆盖层。

---

## 参数配置

全部参数在 `core/NeovideConfig.java`，与 VS Code 版 `neovide-cursor.js` 的 `cursorConfig` 一一对应：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `tailColor` | `#FFC0CB` | 拖尾颜色 |
| `useShadow` / `shadowColor` | `true` / `#FFC0CB` | 辉光开关与颜色 |
| `shadowBlurFactor` | `0.6` | 辉光半径 = 该系数 × 光标较长边 |
| `animationLength` | `0.1` | 常规动画时长（秒），越大拖尾越长 |
| `shortAnimationLength` | `0.05` | 短距离移动的动画时长 |
| `shortMoveThreshold` | `8` | 短距离判定阈值 |
| `trailFactors` | `1 / 0.9 / 0.5 / 0.3` | 四角滞后系数（拖尾拉伸的核心） |
| `useHardSnap` / `leadingSnapThreshold` | `true` / `0.5` | 前缘角硬吸附 |
| `maxTrailDistanceFactor` | `4` | 拖尾最大长度系数（见踩坑 #6） |
| `glowLayers` / `glowOpacity` | `12` / `0.55` | 辉光层数与强度 |

---

## 技术实现（供参考）

### 1. 挂载点：只能挂在 `contentComponent` 上

这是本项目**最重要的结论**。

实测表明，`editor.getContentComponent()`（实际类型 `EditorComponentImpl`）是**唯一安全的挂载点**：

| 特性 | 为什么关键 |
|---|---|
| 它是 `JTextComponent`，**`layout` 为 `null`** | 没有 `LayoutManager` 会在 `revalidate()` 时重新布局并拉伸覆盖层 |
| 坐标系与 `visualPositionToXY()` **天然对齐** | 完全不需要坐标转换 |

**曾经尝试并已证伪的挂载点**（都会导致覆盖层被拉伸到整个编辑器、表现为"全屏变色"）：

- `contentComponent.getParent()`（`JBViewport`）
- `editor.getComponent()`（编辑器面板）
- 祖先 `JLayeredPane`（社区方案所称，实测它确实存在——`EditorImpl$PanelWithFloatingToolbar`——但**不能用于此目的**）
- 窗口根 `LayeredPane`

### 2. 拖尾原理：拉伸，不是残影

原版（以及本项目）的核心机制**不是叠加多张残影**，而是：

1. 光标矩形有 **4 个角点**，每个角点各挂一组 X/Y 阻尼弹簧
2. 光标移动时，按「运动方向与角点方向的夹角」给四角**排序**（`assignRanks`）
3. 位于运动**前方**的角拿到大系数（跟得紧，甚至硬吸附），**后方**的角拿到小系数（拖沓）
4. 于是矩形被**拉长成不规则四边形** —— 视觉上就是拖尾

这个设计比残影方案更省（只需 4 个点），但**对单帧插值精度要求高**，在低帧率下会暴露问题（见下文）。

### 3. 隐藏原生光标

IntelliJ **没有**隐藏光标的公开 API（已实测：`CaretModel` 上不存在 `setCaretsVisible` 之类的方法）。

唯一可行路径是改配色方案：

```java
EditorColorsManager.getInstance().getGlobalScheme()
        .setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
```

**关键**：`setColor` 只改内存对象、**不写入配置文件**，所以即使插件异常退出，重启 IDE 即自动复原 —— 风险可控。

### 4. 高精度调度：不要用 `javax.swing.Timer`

实测数据（Windows 11 + JDK 21）：

| 定时机制 | 实测精度 |
|---|---|
| `javax.swing.Timer`（内部走 `Object.wait`） | **15.61 ms** ❌ |
| `LockSupport.parkNanos` | 15.35 ms ❌ |
| **`Thread.sleep`** | **1.56 ms** ✅ |
| 系统时钟粒度 | 1.08 ms |

系统时钟本身是高精度的，但 `Swing Timer` 用的 `Object.wait` 拿不到 —— 它把帧率钉死在 **64fps**。

因此改为：**后台线程 `Thread.sleep` 调度 + `invokeLater` 切回 EDT 绘制**。

---

## 踩坑记录

> 以下每一条都是实测踩出来的，供后来者参考。

### #1 不要调用 `container.revalidate()`

对带 `LayoutManager` 的容器，`revalidate()` 会让布局管理器重新布局**所有**子组件，把覆盖层从 10×24 拉伸到整个编辑器 → **全屏变色**。

**只用 `repaint()`。**

### #2 不要在 `paintComponent` 里绘制背景

覆盖层只画多边形，**绝不画任何矩形背景**。这样即使被意外拉伸，也只会显示多边形本身。

### #3 不要用 `paintImmediately`（重要）

曾为了"减少 EDT 调度延迟"把 `repaint` 改成 `paintImmediately`，结果**性能反而大幅下降**：

| 版本 | 峰值 fps |
|---|---|
| `repaint`（异步） | **105.6** |
| `paintImmediately`（同步） | **36.9** |

原因：`paintImmediately` 除了同步绘制本区域，还会**顺带把 `RepaintManager` 中所有排队的脏区一次性处理掉** —— 删除文本等操作产生大量脏区时，EDT 被长时间阻塞。

**`repaint` 的"异步"不是缺陷，而是让 `RepaintManager` 有机会合并优化。**

### #4 不要改弹簧的衰减因子 `c`

弹簧的更新公式中，`c` 不只是衰减系数，**它还参与速度积分**：

```java
position = (a + b * dt) * c;    // b 里已含"速度带来的位移"
```

曾试图给 `c` 设下界来改善低帧率观感，结果位置被**反向放大**：

```
正常：c=0.135 → (100 + 4000×0.05) × 0.135 = 40.5   ✓ 衰减
改坏：c=0.5   → (100 + 4000×0.05) × 0.5   = 150    ✗ 放大 1.5 倍！
```

逐帧累积后动画直接**鬼畜**。

**要限制单帧位移，必须在算出结果后限制 `position` 的变化量。**

### #5 不要依赖 `caretPositionChanged` 的时序

删除文本等场景下，光标事件可能在 `visualPositionToXY()` 数据更新**之前**触发 —— 此时读到旧位置，而之后不会再有新事件，拖尾会**永久卡在旧位置**。

**正确做法**：每个 tick 都重新读取光标位置，事件仅用于"唤醒"到高帧率。同时用轻量的 `getVisualPosition()` 做快速路径，避免每帧都做昂贵的坐标换算。

### #6 拖尾最大长度要收紧

原版 JS 的 `maxTrailDistanceFactor = 60`（约 1920px），在 Canvas 全屏重绘下没问题。

但 Swing 覆盖层必须**局部重绘** —— 拖尾越长，需要重绘的包围盒越大，跨行跳转时会直接退化成接近全屏的重绘。

**收紧到 4（约 128px）后，正常移动完全不受影响。**

### #7 重绘区域要分别提交，不能 union

把「上一帧区域」和「当前帧区域」union 成一个大矩形再重绘，在跨行跳转时会覆盖大半个编辑器。

**分开提交两次 `repaint`。**

### #8 过滤控制台/终端编辑器

终端的光标由**终端模拟器自己绘制**（不受 `CARET_COLOR` 控制），caret 模型也与代码编辑器完全不同。

挂上去只会多出一个不跟随的假光标。**用 `EditorKind.CONSOLE` 过滤掉。**

---

## 已知限制

### 帧率天花板

**这是本项目最核心的未解问题。**

实测帧率在 **20~50fps** 之间波动（峰值可达 105fps）。

**根因不在绘制**。曾做过决定性实验——加一个开关**完全跳过所有绘制**，只跑物理计算：

```
fps=10.9   （重绘已完全跳过）
fps=4.1
fps=4.0
fps=2.0
fps=0.4
```

**完全不绘制，帧率依然只有 2~43** —— 证明瓶颈在 **EDT 调度**，与绘制无关。

**机制**：AWT 的 `EventQueue` 中，**原生输入事件（键盘/鼠标）的优先级高于 `invokeLater` 提交的任务**。快速移动光标时，键盘事件与编辑器自身重绘占满了 EDT，我们的渲染任务只能排在后面。

这也解释了那个反直觉的现象：**"编辑器本身不卡，只有光标卡"**。

### 另一个反直觉现象

**大跨度跳转反而比相邻两行流畅。**

原因有两层：

1. **视觉错觉**：同样的帧数下，位移越大，每帧的视觉变化越明显，越不容易察觉顿挫
2. **算法层**：低帧率时 `dt` 较大，而短距离动画时长恰好与之接近，会被 `DampedSpring.update()` 开头的短路条件 `animationLength <= dt` **整体吃掉** —— 光标瞬间闪到目标，完全没有动画

第 2 点已通过**低帧率动画补偿**（保证至少跨越 6 帧）修复。

---

## Roadmap / 欢迎接手

- [ ] **残影轨迹模型**：作为可选方案与当前弹簧模型并存
  - 思路：记录光标历史位置，绘制 N 个渐隐残影
  - **优势**：不依赖单帧插值精度，低帧率下天生更稳
  - 代价：观感与原版 Neovide 不同
- [ ] **配置界面**：`Settings | Editor | Neovide Cursor`，免改代码调参
- [ ] **终端支持**：需要单独适配终端的 caret 模型，并解决其原生方块光标
- [ ] **跨平台验证**：目前仅在 Windows 上实测过
- [ ] **多编辑器优化**：为分屏/多标签场景做更精细的调度

---

## 致谢

- **原始效果**：Neovide（[neovide.dev](https://neovide.dev/)）
- **算法蓝本**：VS Code 版 `neovide-cursor.js` —— 本项目的 `DampedSpring` / `TrailCorner` 均逐行移植自它
- **社区参考**：`intellij-smooth-caret`（平滑光标）、JetBrains 官方 2026.1 的 Smooth Caret（Snappy / Gliding）

> 说明：JetBrains 官方与社区插件的"平滑光标"都**只有平滑移动、没有拖尾**，因此本项目是独立实现而非适配。

---

## License

尚未指定。如需开源分发，建议补充（原作者 VS Code 版为 Apache-2.0）。
