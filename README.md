# Neovim Cursor for JetBrains IDE

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
[![Stars](https://img.shields.io/github/stars/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains?style=flat)](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/stargazers)
[![Downloads](https://img.shields.io/github/downloads/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/total?style=flat)](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/releases)

> Brings the **Neovide-style trailing cursor** to JetBrains IDEs — the caret leaves a fading, stretched trail as it moves, wrapped in a soft glow.
>
> **Works with** IntelliJ IDEA / CLion / PyCharm and the rest of the JetBrains family on **2024.2 – 2026.2+** (all tested — see [Compatibility](#compatibility)).

> **A note on frame rate**: Swing/AWT rendering can't match the Electron-based VS Code version. Root-cause analysis and benchmarks live in [Known Limitations](#known-limitations). Features and stability are ready for daily use.
>
> The [Lessons Learned](#lessons-learned) section at the end preserves every key decision and analysis from development.

---

<div align="center">

**Language / 语言 / 語言 / 言語 / 언어**

[**English**](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja-JP.md) | [한국어](README.ko-KR.md)

</div>

---

## Features

While the caret moves:

- **Trail** — the caret stretches into an irregular quadrilateral (spring model)
- **Smooth follow** — driven by damped springs, not instant jumps
- **Glow** — a soft halo along the edges (approximating Canvas `shadowBlur`), which **lights up as the trail unfolds and fades out once the caret stops**
- **Native caret hidden** — so it doesn't overlap the trail

Parameter semantics match the VS Code `neovide-cursor` extension, so configs stay portable across platforms (one deliberate exception — see [Configuration](#configuration)).

### Two trail models (switchable from the menu at any time)

| Model | How it works | Notes |
|---|---|---|
| **Spring** (default) | Each of the caret rectangle's four corners carries its own damped spring; lag factors are assigned by movement direction → the rectangle gets stretched | The original Neovide effect; depends on inter-frame interpolation |
| **Afterimage** | Records the caret's true position every frame and draws a chain of fading ghosts | **Stays accurate when frames drop** — steadier at low frame rates, and the glow reads more strongly than in spring mode |

Both share the same infrastructure (overlay mounting, native caret hiding, high-precision scheduling, partial repaint). Only the position calculation differs.

---

## Installation

### Option 1 — Download from Releases (recommended)

1. Grab the latest `*.zip` from [Releases](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/releases)
2. In your IDE: `Settings` → `Plugins` → `⚙️` → `Install Plugin from Disk...`
3. Pick the zip and restart the IDE

### Option 2 — Build from source

```bash
# Requirements: JDK 21, Gradle 9.0+, IntelliJ Platform Gradle Plugin 2.x
./gradlew buildPlugin
# Output: build/distributions/*.zip
```

To use an already-installed IDE instead of downloading a multi-GB distribution, edit `build.gradle.kts`:

```kotlin
dependencies {
    intellijPlatform {
        local("D:/path/to/IntelliJIdea2025.2")   // point this at your install
    }
}
```

---

## Compatibility

**Supported range: 2024.2 through 2026.2 and beyond**

| IDE | Version | Status |
|---|---|---|
| IntelliJ IDEA | 2024.2 – 2026.2 | ✅ Tested |
| CLion | 2025.2 | ✅ Tested |
| PyCharm | 2025.2 | ✅ Tested |

**Why it works across every JetBrains IDE**: the plugin depends only on `com.intellij.modules.platform` — the platform core module. It touches no language-specific APIs and no IDEA Ultimate-only features. So it runs on any 2024.2+ JetBrains IDE: WebStorm / PhpStorm / GoLand / RubyMine / Rider / DataGrip, and the rest.

### Why the lower bound is 2024.2

2024.2 is where the platform moved to **JBR 21**, matching this plugin's compile target (Java 21). 2024.1 and earlier run on **JBR 17**, and loading Java 21 bytecode there throws `UnsupportedClassVersionError` immediately. That's a hard bytecode-level constraint, unrelated to APIs.

### Why there's no upper bound

`<idea-version since-build="242" />` **deliberately omits `until-build`**. The plugin contains no version-specific code, so it's naturally compatible with future releases; leaving it empty means "applies to all newer versions".

> This follows JetBrains' official recommendation. Pinning an upper bound (as an earlier release did with `252.*`) means **every IDE upgrade marks the plugin incompatible**, leaving users stuck until a new release ships. This project fell into exactly that trap — see Lesson #17.

### Want to support older versions?

You'd need to lower the compile target to whatever JBR ships with the target platform:

| IDE version | build | Bundled JBR | Required target |
|---|---|---|---|
| 2022.x | 221–223 | 11 | Java 11 |
| 2023.x | 231–233 | 17 | Java 17 |
| 2024.1 | 241 | 17 | Java 17 |
| **2024.2+** | **242+** | **21** | **Java 21 (current)** |

The good news: **lower bytecode loads fine on newer JBRs** — lowering the target won't break the currently supported 2024.2–2026.2 range. It's purely additive.

---

## Usage

- **Toggle**: `Tools` menu → `Neovide 拖尾光标（开关）`
- **Switch trail model**: `Tools` menu → `Neovide 光标：残影模式（低帧率更稳）`
  - **Checked** = afterimage mode; **unchecked** = spring mode (the original Neovide effect)
  - **Takes effect immediately**, no restart needed
- **Performance diagnostics**: `Tools` menu → `Neovide 光标：性能诊断（禁用重绘）`
  - Skips all drawing and runs only the physics. Use it to tell "slow drawing" apart from "slow scheduling" — **a genuinely useful troubleshooting tool**

Turning the toggle off instantly restores the native caret and removes the overlay.

---

## Configuration

Every parameter lives in `core/NeovideConfig.java`, named and behaving identically to `cursorConfig` in the VS Code `neovide-cursor.js`:

| Parameter | Default | Description |
|---|---|---|
| `tailColor` | `#FFC0CB` | Trail color |
| `useShadow` / `shadowColor` | `true` / `#FFC0CB` | Glow toggle and color |
| `shadowBlurFactor` | `0.2` | Glow expansion radius = factor × the caret's longer edge (**deviates from the original 0.6 — see Lesson #15**) |
| `animationLength` | `0.1` | Normal animation duration (seconds); larger = longer trail |
| `shortAnimationLength` | `0.05` | Duration for short moves |
| `shortMoveThreshold` | `8` | Short-move threshold |
| `trailFactors` | `1 / 0.9 / 0.5 / 0.3` | Per-corner lag factors (the heart of trail stretching) |
| `useHardSnap` / `leadingSnapThreshold` | `true` / `0.5` | Hard snap for leading corners |
| `maxTrailDistanceFactor` | `60` | Max trail length factor, matching the original (see Lesson #6) |
| `glowLayers` / `glowOpacity` | `12` / `0.55` | Glow layer count and peak intensity (**layer count doesn't change brightness** — see "Simulating the glow" below) |

> Apart from `shadowBlurFactor`, this table matches the original `cursorConfig` **item for item**.
> If the halo feels too large or too small, tune that single number:
> `0.15` ≈ 12px total glow width (hugging the caret) / `0.2` default ≈ 15px / `0.3` ≈ 21px / `0.6` (the original value) ≈ 40px.

---

## Implementation Notes

### 1. The mount point: `contentComponent` only

This is the project's **single most important finding**.

Testing showed `editor.getContentComponent()` (actual type `EditorComponentImpl`) is the **only safe mount point**:

| Property | Why it matters |
|---|---|
| It's a `JTextComponent` with **`layout == null`** | With no `LayoutManager`, nothing re-lays-out and stretches the overlay during `revalidate()` |
| Its coordinate system **already matches `visualPositionToXY()`** | No coordinate conversion needed at all |

**Mount points tried and disproven** (each one stretches the overlay across the whole editor, showing up as "the screen changes color"):

- `contentComponent.getParent()` (the `JBViewport`)
- `editor.getComponent()` (the editor panel)
- An ancestor `JLayeredPane` (the community-suggested approach — it does exist, as `EditorImpl$PanelWithFloatingToolbar`, but **cannot be used for this**)
- The window's root `LayeredPane`

### 2. How the trail works: stretching, not ghosting

The original's mechanism (and this project's) is **not** "stack up multiple afterimages". It's:

1. The caret rectangle has **4 corners**, each carrying its own X/Y damped spring
2. As the caret moves, the corners are **ranked** by the angle between the movement direction and each corner's direction (`assignRanks`)
3. Corners **ahead** of the movement get large factors (they keep up, and may hard-snap); corners **behind** get small factors (they lag)
4. The rectangle is thereby **stretched into an irregular quadrilateral** — visually, that's the trail

This design is cheaper than ghosting (only 4 points), but it **demands accurate per-frame interpolation**, and that shows at low frame rates (see below).

### 3. Implementing the afterimage model

The afterimage model looks simpler than the spring model (record positions, draw them), but two details make or break it:

**(a) Interpolate between samples**

At low frame rates each frame moves a long way (a single jump can span 200px+ at 20fps). Recording only the sampled endpoints yields a few **isolated blocks** dozens or hundreds of pixels apart — it reads as "the caret is teleporting", not as a trail.

Filling in points at a fixed spacing (`MAX_GAP_PX = 5`) makes the path continuous. **Timestamps must be interpolated too**, or the inserted points share one brightness and form "a string of stiff blocks".

**(b) Draw a permanent caret on top**

Ghosts have a lifetime and get evicted on expiry — **after the caret sits still for a moment, every ghost fades and the screen is empty**. So each frame must also draw the caret itself at full alpha. (Skip samples that coincide with the current position when drawing the tail, or the two overlap and darken that spot.)

> The spring model has no such problem: its four corners always rest at the target position.

### 4. Simulating the glow: concentric expansion in layers

Canvas's `shadowBlur` is a true Gaussian blur, and Swing has no equivalent. This project approximates it with **layers of concentric expansion, filled**: draw from the outermost layer (largest expansion) inward, letting SrcOver accumulate naturally between layers — producing a continuous "bright inside, dim outside" gradient.

**Each layer's alpha is solved from the layer count.** Let each layer's independent opacity be A; after N layers the accumulated opacity at the innermost layer is `1 - (1-A)^N`. Setting that equal to `glowOpacity` gives:

```
A = 1 - (1 - peak)^(1/N)
```

This keeps "layer count" and "intensity" independent — adding layers only smooths the falloff, it **never brightens the halo overall**.

**Radii are distributed linearly.** Accumulated opacity at radius r is `1-(1-A)^k`, where k is the number of layers covering that point; with linear radii, k is linear too, and the falloff works out to roughly 60% of peak at `r ≈ glowWidth/2` — a good match for a Gaussian curve.

**The halo must hug the quadrilateral itself.** This took three attempts (Lessons #12 → #13 → #14):

| Attempt | Why it failed |
|---|---|
| Multi-layer **centered stroke** | A stroke path is the rectangle's *outline*, not its interior — the brightest layer expands less than 1px and is covered by the opaque body |
| Using the **bounding box** as the shape | During a trail the corners are pulled into a long thin diagonal, while the bounding box can cover most of the editor → it balloons into a large square on line jumps |
| **Exact expansion** (final) | Offset each edge along its outward normal, intersect adjacent edges; clamp to √2 at sharp corners, or long spikes shoot out |

**The glow only lights up while moving.** Its intensity is driven by the largest offset of any corner from its target — at rest the offset is 0 and drawing is skipped entirely; any real movement produces an offset of at least one character width (~8px) or one line height (~32px), so a 2.5px full-intensity threshold leaves **the in-motion look pixel-identical to before**. See Lesson #16.

### 5. Hiding the native caret

IntelliJ has **no** public API for hiding the caret — verified by decompiling the `Editor` / `CaretModel` / `Caret` interfaces with `javap` (`Editor` exposes only `getColorsScheme()`, no setter, so there's no way to hide just one editor's caret either).

The only viable path is modifying the **global color scheme**:

```java
EditorColorsManager.getInstance().getGlobalScheme()
        .setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
```

⚠️ **This is a global operation, and the cost is ours to absorb**: once hidden, **every** editor's native caret in the IDE disappears — including ones we never intended to take over (output areas like Build / Run / Services). **Whatever gets hidden must be given a replacement caret**, or those carets vanish entirely. See Lesson #8.

**⚠️ A counterintuitive persistence path**: `setColor` does only touch memory and doesn't write files — but **the in-memory state can be persisted by the IDE itself**. If the user changes any editor setting in Settings and clicks OK, IntelliJ saves the **entire** color scheme, writing the transparent caret into `colors/_@user_<scheme>.icls`. From then on **even uninstalling the plugin won't heal it**, because the bad value is on disk.

The plugin now guards against this: `sanitizeCaretColor()` refuses to store "fully transparent" as the original value, falling back to the editor's default foreground color on restore. See Lesson #18.

### 6. High-precision scheduling: don't use `javax.swing.Timer`

Measured on Windows 11 + JDK 21:

| Timing mechanism | Measured precision |
|---|---|
| `javax.swing.Timer` (uses `Object.wait` internally) | **15.61 ms** ❌ |
| `LockSupport.parkNanos` | 15.35 ms ❌ |
| **`Thread.sleep`** | **1.56 ms** ✅ |
| System clock granularity | 1.08 ms |

The system clock itself is precise, but `javax.swing.Timer`'s `Object.wait` can't reach it — it pins the frame rate to **64fps**.

So the plugin uses: **a background thread with `Thread.sleep` for scheduling, plus `invokeLater` to draw back on the EDT**.

---

## Lessons Learned

> Every item below was learned the hard way. Recorded for whoever comes next.

### #1 Don't call `container.revalidate()`

On a container with a `LayoutManager`, `revalidate()` makes the layout manager re-lay-out **all** children, stretching the overlay from 10×24 to the whole editor → **the screen changes color**.

**Use `repaint()` only.**

### #2 Don't paint a background in `paintComponent`

The overlay draws polygons and **never any rectangular background**. That way, even if it gets stretched accidentally, only the polygon shows.

### #3 Don't use `paintImmediately` (important)

Trying to "reduce EDT scheduling latency", I swapped `repaint` for `paintImmediately` — and **performance dropped sharply**:

| Version | Peak fps |
|---|---|
| `repaint` (async) | **105.6** |
| `paintImmediately` (sync) | **36.9** |

The reason: besides synchronously painting its own region, `paintImmediately` also **flushes every queued dirty region in the `RepaintManager` at once** — when operations like deleting text produce many dirty regions, the EDT gets blocked for a long time.

**`repaint`'s asynchrony isn't a flaw — it's what gives `RepaintManager` room to coalesce and optimize.**

### #4 Don't touch the spring's decay factor `c`

In the spring update formula, `c` isn't just a decay coefficient — **it participates in the velocity integral**:

```java
position = (a + b * dt) * c;    // b already contains "displacement from velocity"
```

I once tried giving `c` a lower bound to improve the low-frame-rate look, and positions got **amplified instead**:

```
Normal:  c=0.135 → (100 + 4000×0.05) × 0.135 = 40.5   ✓ decays
Broken:  c=0.5   → (100 + 4000×0.05) × 0.5   = 150    ✗ amplified 1.5×!
```

Accumulated over frames, the animation goes berserk.

**To limit per-frame displacement, clamp the change in `position` *after* computing it.**

### #5 Don't rely on the timing of `caretPositionChanged`

In scenarios like deleting text, the caret event may fire **before** `visualPositionToXY()`'s data updates — so you read a stale position, and no further event arrives, leaving the trail **stuck at the old position forever**.

**The fix**: re-read the caret position every tick, and use events only to "wake up" into a high frame rate. Also use the cheap `getVisualPosition()` as a fast path, so you're not doing an expensive coordinate conversion every frame.

### #6 Tighten the maximum trail length

The original JS uses `maxTrailDistanceFactor = 60` (~1920px), which is fine when Canvas repaints the whole screen.

But a Swing overlay **must repaint partially** — the longer the trail, the larger the bounding box to repaint, degrading into a near-full-screen repaint on line jumps.

**Tightened to 4 (~128px), normal movement is completely unaffected.**

### #7 Submit repaint regions separately, never unioned

Unioning "last frame's region" and "this frame's region" into one big rectangle covers most of the editor on a line jump.

**Submit two separate `repaint` calls.**

### #8 Console editors: don't filter them out wholesale, and don't take them over wholesale

This one was wrong twice. Recording the full conclusion.

**First mistake**: filtering everything with `EditorKind.CONSOLE`, reasoning that "the terminal draws its own caret, so mounting here just adds a fake caret that doesn't follow". **That reasoning holds for the terminal, but not for output consoles** — the output areas of Build / Run / Services / Problems are also `CONSOLE`, yet their carets **are** controlled by `CARET_COLOR`. Global hiding + wholesale filtering = every one of those carets disappears.

**Second mistake**: since filtering is wrong, draw a caret for every `CONSOLE`. Result: **the terminal got a double caret** — the terminal's own caret was never hidden, so we added a second one, at a position that doesn't even sync (we read the Editor's caret while the terminal's real caret is maintained by the emulator; measured one character off).

**The right approach: handle the two separately.**

| Editor | Caret source | Treatment |
|---|---|---|
| Code editor (`MAIN_EDITOR`) | `CARET_COLOR` | Hide + trail |
| Output console (`CONSOLE`) | `CARET_COLOR` | **Draw a static caret** (no trail) |
| Terminal (`CONSOLE`) | Drawn by the terminal emulator | **Skip entirely** |

**The hard part is that the latter two are the same type**: `TerminalEditorFactory.createOutputEditor()` returns a plain `EditorImpl`, and both have `EditorKind.CONSOLE` — type alone can't tell them apart. The solution is **walking up the component tree** and matching terminal package names:

```java
Component c = editor.getContentComponent();
while (c != null) {
    String name = c.getClass().getName();
    if (name.startsWith("com.intellij.terminal")
            || name.startsWith("org.jetbrains.plugins.terminal")) {
        return true;   // terminal, skip
    }
    c = c.getParent();
}
```

Matching **package prefixes** rather than concrete class names matters because the terminal implementation has changed across releases (`org.jetbrains.plugins.terminal` → `com.intellij.terminal.frontend`) while the package names stayed stable.

> **Lesson**: when an operation has **global side effects**, "skipping a class of objects" and "leaving a class of objects alone" are two entirely different things. The filter must be checked against the side effect's **actual scope**, not just against "does this object itself need handling".

### #9 Afterimages must interpolate, or it's just "the caret teleporting"

See "Implementing the afterimage model", point (a). This is why the first version of afterimage mode failed completely — at low frame rates there were only a few sample points hundreds of pixels apart, which just looks like the caret jumping.

### #10 Afterimage mode must draw a permanent caret

See "Implementing the afterimage model", point (b). This is the easiest difference to miss when porting from the spring model — the spring's corners always rest at the target, while afterimages fade with time, leaving the screen empty after a moment.

### #11 Set the max trail length by "repaint cost", not "visual effect"

See #6 above. One addition: **line jumps are the harshest scenario** — they amplify both "trail stretching" and "repaint area" at once. Tune against them specifically, not just movement within a line.

### #12 The glow can't be done with a "centered stroke"

`BasicStroke`'s path is the rectangle's **outline**, not its interior — each layer expands outward by only `strokeWidth/2`.

The IntelliJ caret is extremely narrow (2×32), so the brightest layer (smallest strokeWidth) expands less than 1px and is immediately covered by the opaque body filled afterward; meanwhile the layers that do expand far enough have alpha decayed below 2%.

**The result is "blur without glow"** — the exact root cause of the original complaint that "spring mode has no visible glow".

**The fix**: multi-layer concentric **expansion** (filled), not stroking.

### #13 Don't use the bounding box as the halo shape

After switching to expansion, I lazily used `polygon.getBounds()` as the halo shape, reasoning that "the halo is blurry anyway, the bounding box error shouldn't be visible".

**That judgment was completely wrong.** During a trail the four corners stretch into a long diagonal quadrilateral, while its **bounding box** can cover most of the editor — at the instant of a line jump the halo balloons into a huge square before contracting with the quadrilateral, having nothing to do with "glowing along the trail".

**Lesson**: for shape approximations, verify the **worst case** (line jumps) — don't just look at the resting state.

### #14 Exact expansion goes out of control at sharp corners

In the exact solution for polygon expansion, corner offset ∝ `1/cos(θ/2)` — the sharper the angle, the farther the intersection point.

During a trail the quadrilateral is pulled into a long thin strip, and **the two ends are exactly the sharp corners**; measured offsets reached **2.4×** the target value, sending long spikes out from both ends.

**The fix**: clamp the offset vector's magnitude to `√2`. At right angles (a resting rectangle) the offset is exactly √2 already, so nothing changes there; sharp corners get pulled back into a sane range — which also better matches how a real Gaussian blur "rounds off" sharp corners.

> This correction's validity is confirmed by `tools/OffsetTest.java`, a standalone check.
> It verifies: exact expansion of a resting rectangle, zero center drift, √2 clamping at sharp corners, and convexity preserved.
> Usage: `javac -encoding UTF-8 tools/OffsetTest.java && java OffsetTest`

### #15 The glow size can't reuse the original's `shadowBlurFactor`

The original's `shadowBlurFactor = 0.6` describes Canvas's `shadowBlur` — the **diameter** of a Gaussian blur.

Blur spreads energy around, and **the narrower the shape, the more the peak gets diluted**: VS Code's caret is 8px wide and can carry it; IntelliJ's caret is only 2px wide, so reusing the same number as an expansion radius yields a ~40px-wide elliptical fog (20× the caret width) — nothing like "glowing against the caret".

**The fix**: lower it to `0.2`, giving a total glow width around 15px.

> This is the project's **only deliberate deviation** from the original parameters. To restore that exaggerated wide haze, set it back to `0.6`.

### #16 A resting caret shouldn't glow

Glow is an "in motion" effect. A resting caret wearing a ring of light looks like it's boxed in by a pink square.

**The approach**: drive glow intensity by the largest offset of any corner from its target (i.e. how far the trail has unfolded) — offsets ≤ 0.5px count as resting and skip drawing entirely; ≥ 2.5px is full intensity.

The transition band is only 2px wide because the two states are inherently far apart: at rest the offset is 0, while any real movement produces an offset of at least one character width (~8px) or one line height (~32px). **That narrow band guarantees the in-motion glow is pixel-identical to before**, while letting the fade-out on stopping arrive gently.

### #17 Don't pin `untilBuild`, or the plugin "only works on one IDE version"

An early `build.gradle.kts` had:

```kotlin
ideaVersion {
    sinceBuild.set("252")
    untilBuild.set("252.*")     // ← the culprit
}
```

The consequence: the plugin **only worked on 2025.2** — 2024.x, 2025.1, 2025.3, and 2026.x were all turned away by the IDE, and all the user saw was "plugin incompatible with this IDE", with no way to tell why.

**The mechanism**: at startup the IDE compares its build number from `build.txt` against the plugin's `since-build` / `until-build` range and **refuses to load** anything outside it — code never even runs. So this **isn't an API compatibility problem, it's a metadata declaration problem**: a one-word config with the effect of "this plugin supports exactly one version".

**The fix**: if the plugin contains no version-specific code, **omit `untilBuild`** (empty means it applies to all newer versions). This is JetBrains' official recommendation.

**How to tell whether you "contain version-specific code"**: look at whether the APIs you use are all platform core interfaces. This plugin uses `Editor` / `CaretListener` / `EditorColors` / `EditorFactoryListener` and friends; the "youngest" of them, `EditorKind`, dates to 2016.2 — that kind of dependency needs no upper bound at all.

| Approach | Consequence |
|---|---|
| Pinning `untilBuild` | Every IDE upgrade requires a fresh release before users can continue |
| Omitting `untilBuild` | One config, valid long-term; narrow it later if a breaking change actually lands |

**The lower bound, though, must be tested.** `sinceBuild` can't be loosened on a hunch. Beyond API differences, watch the **bytecode version** — in this case 2024.1 failed because JBR 17 can't load Java 21 bytecode, not because an API was missing.

### #18 "Memory-only" doesn't mean "never persisted" — the color scheme got silently polluted

Hiding the native caret requires modifying the global scheme's `CARET_COLOR`. There was a **plausible-sounding inference**:

> `setColor()` only touches memory and writes no files → the plugin can't leave traces if it crashes → risk is under control.

The first half is true. **The second half is false.** Here's the actual chain:

```
① The plugin sets the in-memory CARET_COLOR to fully transparent
        ↓
② The user changes any editor setting in Settings (in this case, the font) and clicks OK
        ↓
③ IntelliJ saves the color scheme, persisting 【the in-memory state】 wholesale
   → colors/_@user_Dark.icls gains a line:
      <option name="CARET_COLOR" value="00000000" />
        ↓
④ From then on, the caret is invisible whether or not the plugin is installed — the bad value is on disk
```

**Why it's hard to notice**: it requires "plugin running" and "user clicked OK" to coincide. And a `partialSave="true"` scheme file records only the **differences** from its parent, so that line sits among ordinary font settings and looks innocuous. In this case the file's *only* color difference was `CARET_COLOR` — which is precisely the key evidence: the user changed only the font, and the caret color was written along for the ride.

**Why it's especially dangerous**: **uninstalling the plugin won't heal it**. The user's symptom is "I don't even have the plugin installed and the caret is still invisible", sending troubleshooting in entirely the wrong direction.

**The fix**: delete that line so the scheme falls back to its parent's value:

```xml
<scheme name="_@user_Dark" version="142" parent_scheme="Darcula">
  <colors>
    <option name="CARET_COLOR" value="00000000" />   <!-- ← delete this line -->
  </colors>
</scheme>
```

Each IDE version keeps its own copy (`IntelliJIdea2025.2` / `IntelliJIdea2026.2` / …) — **all of them need fixing**. Close the IDE first, or it will overwrite your edit from memory on exit.

**The plugin-side guard**: `sanitizeCaretColor()` — on reading fully transparent (or `null`), it no longer stores that as the "original value", since "restoring" would just hand the invisibility back. It falls back to `getDefaultForeground()` so the user can at least see a caret.

| Approach | After the plugin is switched off |
|---|---|
| `savedCaretColor = scheme.getColor(...)` | Restores transparent — **the caret still doesn't appear** |
| Passing through `sanitizeCaretColor()` first | Restores the default foreground color — the caret works |

**Lesson**: when judging whether a change will persist, don't only ask **whether you wrote a file** — ask **whether the object has any other path to disk**. An in-memory global singleton can be carried off by the IDE's own save actions at any time.

---

## Known Limitations

### The frame-rate ceiling

**This is the project's core unsolved problem.**

Measured frame rate fluctuates between **20–50fps** (peaking at 105fps).

**The bottleneck is not drawing.** A decisive experiment: add a switch that **skips all drawing** and runs only the physics:

```
fps=10.9   (redraw fully skipped)
fps=4.1
fps=4.0
fps=2.0
fps=0.4
```

**With no drawing at all, the frame rate still sat at 2–43** — proving the bottleneck is **EDT scheduling**, unrelated to drawing.

**The mechanism**: in AWT's `EventQueue`, **native input events (keyboard/mouse) take priority over tasks submitted via `invokeLater`**. When the caret moves quickly, keyboard events and the editor's own repaints saturate the EDT, and our render tasks queue up behind them.

This also explains a counterintuitive observation: **"the editor itself isn't laggy, only the caret is"**.

### Another counterintuitive observation

**Long jumps feel smoother than moving between adjacent lines.**

Two reasons:

1. **Perceptual**: at the same frame count, larger displacements produce more visible change per frame, so stutter is less noticeable
2. **Algorithmic**: at low frame rates `dt` is large, and short animation durations are close to it, so the early-exit condition `animationLength <= dt` at the top of `DampedSpring.update()` **swallows the animation entirely** — the caret snaps to the target with no animation at all

Point 2 is fixed by **low-frame-rate animation compensation** (guaranteeing at least 6 frames are spanned).

---

## Roadmap

- [x] **Afterimage trail model** — implemented; switchable from the `Tools` menu
- [x] **Glow** — implemented; lights up as the trail unfolds, fades at rest (Lessons #12–#16)
- [x] **Wider version compatibility** — 2024.2 – 2026.2+; see [Compatibility](#compatibility)
- [x] **Console vs. terminal handling** — static caret in output areas, terminals skipped (Lesson #8)
- [x] **Color-scheme pollution guard** — refuses to store fully-transparent as the original value (Lesson #18)
- [ ] **Settings UI**: `Settings | Editor | Neovide Cursor`, tune parameters without editing code
- [ ] **Support 2023 and earlier**: requires lowering the compile target to Java 17 / 11
- [ ] **Terminal support**: needs a separate adaptation to the terminal's caret model, plus solving its native block cursor
- [ ] **Cross-platform verification**: so far only tested on Windows
- [ ] **Multi-editor optimization**: finer scheduling for split/multi-tab scenarios

---

## Credits

- **Original effect**: Neovide ([neovide.dev](https://neovide.dev/))
- **Algorithm reference**: the VS Code `neovide-cursor.js` — this project's `DampedSpring` / `TrailCorner` are ported from it line by line
- **Community references**: `intellij-smooth-caret` (smooth caret), and JetBrains' official Smooth Caret (Snappy / Gliding) in 2026.1

> Note: both the official and community "smooth caret" plugins offer **smooth movement only, with no trail**, so this project is an independent implementation rather than an adaptation.

---

## License

[MIT](LICENSE)

### On the algorithm's provenance

This project's trail algorithm (`DampedSpring`, `TrailCorner`) is ported line by line from the VS Code `neovide-cursor` extension. The original project ships no explicit license file; its author has publicly stated that "anyone may freely modify and distribute it further, as long as it complies with open-source licenses".

If the original author disagrees with this arrangement, please open an issue and it will be adjusted.
