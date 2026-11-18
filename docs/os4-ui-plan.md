# HyperMusicCover OS4 界面更新计划

> 分支：`os4-ui`（2026-11-18 快进到 `main` `086dd05`）
> 状态：第一轮已装机验收；第二轮修复 vc30；对话框对齐 demo vc31；按 demo 收口 vc35 已装机
> 按项目规矩：任何构建、单测、出包都先问；效果由维护者上机看。

## 0. 已定的决策（2026-11-18）

| 项 | 决定 |
|---|---|
| miuix 版本 | `0.9.5-beta01`（2026-11-17 发布），Maven Central，无需认证 |
| 快照/认证问题 | 作废：正式 beta 已带 glass，不需要 GitHub Packages |
| 代码来源 | InstallerX-Revived、Mishka 都是 GPL-3.0，可依 §13 并入 AGPL-3.0；可抄源码，保留版权头，NOTICE 注明 |
| 液态底栏 | 不砍，按新 API 迁移保留 |
| 范围 | 全量：顶栏、底栏、下拉、对话框/弹层、卡片、页面背景 |
| 默认值 | 新装与老用户都默认 OS4；主题页保留切回 OS3 的开关 |
| 工具链 | 一次对齐 Gradle 9.9.1 / AGP 9.5.1 / Kotlin 2.4.21 / Compose BOM 2026.09.00 |

## 1. 上游事实（2026-11-18 拉 0.9.5-beta01 产物与源码核对）

- 新模块：`miuix-material`（`top.yukonga.miuix.kmp.material`）、`miuix-glass`
  （`...glass`）、`miuix-glass-icons`；`miuix-shader` 不再发布。
- 破坏性迁移：
  - `isRuntimeShaderSupported`、`RuntimeShader`、`asBrush` 从 `blur` 移到 `material`。
  - `blur.highlight.*`（`Highlight`、`BloomStroke`、`LightPosition`、`LightSource`）
    删除，改 `material.EdgeStroke` / `EdgeLightSource` / `EdgePeak`（含 `Dual` 峰值），
    `drawBackdrop(highlight=…)` 改 `onDrawFront = { drawEdgeStroke(...) }` 或
    `Modifier.edgeStroke(shape, stroke, alpha)`；倾角用 `material.rememberTiltLight`
    或 `material.sensor.rememberDeviceTilt`（`gravityX/gravityY` 仍在）。
  - `blur.sensor` 移到 `material.sensor`。
  - `Backdrop.drawBackdrop` 的 `downscaleFactor` 从 `Int` 改为 `Float`。
  - `textureBlur` / `progressiveTextureBlur` 去掉了 `highlight` 参数，其余签名不变。
  - `BackdropEffectScope.runtimeShaderEffect` 仍是公开 API（`Lens` 不用改逻辑）。
- glass 组件（`...glass`）：`GlassTopAppBar`、`GlassNavigationBar`/`GlassNavigationItem`、
  `GlassIconButton`、`GlassDropdownPreference`、`GlassDialog`/`GlassDialogButton`、
  `GlassBottomSheet`、`GlassPopupColumn`、`GlassTabRow`/`GlassSegmentedTabRow`、
  `Modifier.glass` / `glassPanel`；材质在 `material`（`GlassDefaults`、`GlassMaterials`、
  `GlassShapes`、`EdgeStrokes`/`GlassEdgeStrokes`）。
- `GlassDialog`、`GlassDropdownPreference`、`GlassBottomSheet` 依赖 miuix `Scaffold`
  的 popup host；`renderInRootScaffold = true` 时挂到最外层 Scaffold。
- 玻璃渲染条件：`isRuntimeShaderSupported()`（SDK 33+）或
  `isRenderEffectSupported()`（SDK 31+）；本项目 minSdk 35，实际恒真，
  保留判断作为兜底。

## 2. 我们现在的 UI 结构

- `MainActivity`：4 页 `HorizontalPager` + 自绘底栏。底栏三种模式：
  - 0 普通 `NavigationBar`
  - 1 `FloatingNavigationBar` + `textureBlur` + `Highlight`
  - 2 `IosLiquidGlassNavigationBar`（自绘，539 行，用 `Highlight`/`Lens`）
- `PageScaffold`（`ui/util/PageScaffold.kt`）：15 个调用点。已统一
  `rememberBlurBackdrop()` + `BlurredBar` + `progressiveTextureBlur` 顶栏，
  top inset 放进 `contentPadding`。`popupHost = {}`，弹层交给根 Scaffold。
- 自建脚手架：`AboutPage`（大 logo + 自算收起）、`LicensePage`。
- 下拉：`WindowDropdownPreference` 9 处；Home 的 `OverlayListPopup` 另算。
- 对话框：`WindowDialog` 20 处（独立窗口）。
- 卡片：`Card` 48 处（16 个 UI 文件；`pickup/PickupIsland.kt` 里的是模块自绘类，不动）。
- 现有开关：`AppSettings` 的 `themeMode`、`isFloatingNavbar`、`isLiquidGlass`、
  `isBlurEnabled`、`language`、`welcomeShown`，参与备份 JSON。
- 背景：`BgEffectBackground` 只在 About 和欢迎页用；正文页是纯色 surface。

## 3. 实现步骤

**第 0 步（已完成）**：`os4-ui` 快进到 `main`，本文件按决策重写。

**第 1 步 工具链（单独提交）**

- `gradle-wrapper.properties` → 9.9.1；`libs.versions.toml` → AGP 9.5.1、
  Kotlin 2.4.21、Compose BOM 2026.09.00；miuix 暂留 0.9.4-rc01。
- 验收：OS3 界面零变化；能编过（构建前单独问）。

**第 2 步 依赖迁移 + 破坏性 API 迁回原样**

- 删 `miuix-shader`，加 `miuix-material`、`miuix-glass`、`miuix-glass-icons`；
  miuix 版本 0.9.5-beta01。
- `blur` → `material` 的符号迁移；液态底栏的 `Highlight` 改 `EdgeStroke`
  （旧 `LightPosition(x,y,z)` 与新 `EdgeLightSource(x,y,z)` 同一坐标约定，
  `dualPeak = true` → `EdgePeak.Dual`）；`CombinedBackdrop` 的
  `downscaleFactor` 改 `Float`。
- 验收：OS3 目视与 `086dd05` 一致（允许描边 API 换代带来的细微差别）。

**第 3 步 OS4 开关与 LocalOs4Ui**

- `AppSettings.useOs4Ui`（默认 `true`，含备份 JSON）。
- `AppTheme(themeMode, useOs4Ui)` 提供 `LocalOs4Ui`；
  生效值 = `useOs4Ui && isRenderEffectSupported()`。
- 13 个 `AppTheme` 调用点传入设置；主题页加"界面风格"行
  （OS4 / OS3，设备不支持时不显示 OS4）；OS4 时旧的浮动/液态/模糊三行
  `FoldUnder` 收起。

**第 4 步 PageScaffold 分流（顶栏 + 两块 backdrop）**

- OS3 分支保持原代码路径零改动。
- OS4：`GlassTopAppBar(progressiveBlur = true)` + `GlassIconButton` 返回键
  + `MiuixIcons.Glass.ChevronBackward`；`Scaffold` 用默认 popupHost
  （OS3 仍是 `popupHost = {}`），让下拉/对话框有宿主。
- backdrop 分两块：
  - 内容 backdrop：MainActivity 根（记录 pager 内容，给底栏/顶栏/弹层采样）；
    子 Activity 没有根时由 PageScaffold 自己记录。
  - 页面 backdrop：通过 `LocalPageBackdrop` 传给下拉/对话框。
- `layerBackdrop` 紧跟 `fillMaxSize`，在 inset padding 之前。
- 验收：内容从顶栏下滚过；OS3 零变化。

**第 5 步 底栏**

- OS4：`GlassNavigationBar`（4 项，`GlassNavigationItem`，backdrop = 根内容
  backdrop）；默认窗口 inset 留给自己处理。
- 三种旧底栏保留，OS3 时行为不变；OS4 时盖过三种模式。

**第 6 步 下拉**

- 写 `AppDropdownPreference` 包装：OS4 且选项全为纯文本 → `GlassDropdownPreference`
  （backdrop = `LocalPageBackdrop`，`renderInRootScaffold = true`）；
  否则 `WindowDropdownPreference`。替换 9 处。

**第 7 步 对话框/弹层**

- 写 `AppDialog` 包装：OS4 → `GlassDialog`（backdrop = `LocalPageBackdrop`），
  OS3 → `WindowDialog`。替换 20 处；按钮先保留现有 `TextButton`，
  观感不行再换 `GlassDialogButton`。
- Home 的 `OverlayListPopup` 本轮不动（独立弹层，后续评估）。

**第 8 步 卡片与背景**

- 写 `AppCard`：OS4 时 `glassPanel` + `GlassEdgeStrokes` + 阴影，
  底色作为 fallback；OS3 原样 `Card`。替换 UI 文件里的 48 处。
  - 实现时改了一处：卡片**不采样** `LocalPageBackdrop`。页面记录里包含卡片
    自己，采样会把自己的上一帧糊进去；且页面背景是纯色，采不到东西。
    卡片改用 `glassPanel(backdrop = null)` 的描边 + 阴影分支，半透明底色。
  - HomePage 的可点击状态卡保留原 `Card`（底色是状态色，不是设置面板）。
- 页面背景：OS4 用仓库现成的 `BgEffectBackground`（OS3 渐变预设、**静态**，
  `dynamicBackground = false`），画在 PageScaffold 的正文记录**内部**——
  顶栏/底栏玻璃采样到的就是真正在页面上的背景，而不是一层纯色。
  子 Activity 走同一个 PageScaffold，License 单独接。About 页本来就有
  （且只在当前页时动画）。

**实现中和计划不同的地方（2026-11-18）**

- `miuix 0.9.5-beta01` 的 AAR 元数据要求 compileSdk 37.2，本机补装了
  `platforms;android-37.2`，`app/build.gradle.kts` 加 `compileSdkMinor = 2`
  （targetSdk 仍是 37）。单独一个提交。
- 增加了 `miuix-glass-icons`：OS4 返回键用 `MiuixIcons.Glass.ChevronBackward`。
- OS4 判定统一用 `isRuntimeShaderSupported()`（SDK 33），与
  `rememberBlurBackdrop` 同一个门，避免 31/32 上出现"半套玻璃"。
- About / License 两个自建脚手架也走 OS4：共享 `GlassPageBar`；
  About 不开 large title，标题仍按页面自己的 `scrollProgress` 渐显。
- 页面背景按"全量"落地：静态渐变，跑帧动画的只有 About 页（且只在该页
  在当前页时）。
- 弹层宿主结论：OS4 的 PageScaffold 用默认 `popupHost`；MainActivity 的页
  由根 Scaffold 托管（`renderInRootScaffold = true`），子 Activity 由页
  Scaffold 自己托管。OS3 仍是 `popupHost = {}` + 独立窗口。
- 解析到的新 API 映射：`Highlight`/`BloomStroke` → `EdgeStroke`/
  `EdgeLightSource`（同一 UV 原点，`dualPeak` → `EdgePeak.Dual`）；
  `drawBackdrop(highlight=)` → `onDrawFront = { drawEdgeStroke(...) }`；
  `CombinedBackdrop.downscaleFactor` 改 `Float`。

**第 9 步 编译上机**：构建前问；维护者验收。

**第 10 步 文档与行动计划表同步**；提交/推送按指示。

## 3.5 第一轮上机后的修复（2026-11-18，vc26–vc30）

- **顶栏/浮动玻璃缺失**：miuix 的浮动材质只在列表 `contentOffset < 0` 时出现；
  且我们原来的带是“对页面背景做模糊”，白页上等于白。OS4 的带改
  `BlurredBar(tint = surfaceContainer, alpha = 0.55)`，静止也有玻璃感；
  按钮仍按库的滚动规则浮动（没有改成常驻）。
- **页面背景**：按维护者要求回到 OS3 同款——普通页面纯色，只有 About 保留
  原有渐变；上一轮的 `BgEffectBackground` 从 PageScaffold/License 撤掉。
- **TabRow**：三处（功能分组、捐赠渠道、歌词声部）改
  `GlassSegmentedTabRow`，采样只画页面底色的 `SurfaceBackdrop`（页面记录
  含 tab 自己，不能自采样）。
- **首页重启菜单**：`GlassIconDropdownMenu` 的内联全屏层被放进了顶栏 icon
  大小的小 Box，菜单开在看不见的尺寸里。已把 anchor/状态提升到页面根 Box，
  菜单在整页范围展开；触发器用 `GlassIconButton`（菜单要从它的 surface 长出来）。
- **指纹快捷「+」无反应**：`AppDialog` 是在 `PageScaffold` 之外组合的，玻璃
  对话框需要祖先 Scaffold 的 popup/dialog host；没有宿主时状态进了一个没人
  渲染的列表。`AppTheme` 在 OS4 下给每个 Activity 包一层根 `Scaffold`。
- **页面外的对话框是白板**：它们拿不到 `LocalPageBackdrop`。`AppTheme` 现在
  提供只画页面底色的 `SurfaceBackdrop` 作为兜底，页面内部仍用自己的记录。
- **About 卡片颜色**：`AppCard` 的底改回卡片本色（上一版用 0.72 透明叠色，
  在 About 渐变上发暗）。About 页本身按要求暂停。
- **通控中心返回键**：短页面列表滚不动，库的 `isContentScrolled` 恒为 false，
  返回键就永远不浮动；这是库的规则，不是我们的 bug。第一轮曾用
  `isContentScrolled = true` 强行常驻，维护者否掉，已撤回。

## 3.6 第二轮：对话框对齐 miuix demo（2026-11-18，vc31）

维护者要求：所有 dialog/bottomsheet 的材质与 miuix demo「Glass Component」页
（`example/.../GlassPage.kt` + `GlassDemoDefaults`）完全一致。核对结果与改动：

- **材质**：`AppDialog` 显式传 `GlassDialogDefaults.material()`（= `dialogGlass`），
  与 demo 的 "Component default" 相同。
- **描边**：demo 默认 `strokeIndex = 2`（Middle）；`GlassDialogDefaults.stroke()`
  是 Small。`AppDialog` 改为按背景亮度取 `GlassEdgeStrokes.MiddleLight/MiddleDark`。
- **按钮**：新增 `AppDialogButton`（Normal/Primary/Destructive）。OS4 用
  `GlassDialogButton`，OS3 把三种角色映射回 `TextButton` 的三套颜色；21 个对话框的
  24 个按钮全部改用它。
- **采样**：`PageScaffold` 新增 `dialogs` 槽，槽在 `LocalPageBackdrop` 作用域内、
  录制层之外渲染。歌词样式页（2 个）和指纹快捷页（8 个）原先在 PageScaffold 之外，
  只能采到主题的纯色 `SurfaceBackdrop`；已搬进槽里，和页面内对话框一样能采到页面内容。
- **底部弹窗**：全仓库没有 `GlassBottomSheet`，本轮不新增。demo 的 sheet 材质
  （`GlassBottomSheetDefaults.material()` + Middle 描边）本身就是库默认值，
  将来加 sheet 直接按 demo 调用即可。

## 3.7 第三轮：按 miuix demo 收口（2026-11-18，vc32–vc35）

- **界面风格行**：去掉副标题，两个选项就是 `OS4` / `OS3`（中英文都是这两个名字）。
- **顶栏**：不再自绘模糊带/自建采样场景，改成 demo 的
  `GlassTopAppBar(progressiveBlur = true, stroke = Middle)`。按钮采样的是库的
  `progressiveBackdrop`（渲染后的带场景），underlay 材质路径也和 demo 一致。
  代价：带只在滚动/上拉有位移时出现，且带内有库内置的 30% `surface` 混合。
- **短页面浮动**：`pageScrollModifiers` 按 HyperLyric 把 `overScrollVertical`
  的阻尼位移写进 `TopAppBarState.overscrollOffset`；内容撑不满一屏的页面
  上拉时按钮也能浮起（松手回弹）。
- **对话框**：`AppDialog` 已用 demo 的 `dialogGlass` + Middle；「获取更新」
  对话框在 OS4 去掉内部的 `AppCard`（不透明卡会盖住对话框玻璃）。
- **卡片**：About 页四张卡按 demo 用普通 `Card` + `textureBlur`（透渐变），
  不再套 `AppCard` 的玻璃描边；About 卡颜色与其它页不同是 demo 的设计。
  首页状态卡保留状态底色和点击，OS4 下额外取玻璃卡的描边+阴影；Tilt 改由
  外层 `pressable` 包住整张卡（含描边/阴影），按压时一起动。
- 浅色模式描边是 `GlassEdgeStrokes.MiddleLight` 的白色高光，属设计行为，
  维护者决定保留（2026-11-18）。

## 4. 风险

- **GPU 成本**：卡片数量多，每块玻璃都有 RenderEffect；先按全量做，
  上机看掉帧再收敛。
- **弹层宿主**：OS4 的 PageScaffold 必须有 popup host（默认 `MiuixPopupHost`）；
  根 Scaffold 与页 Scaffold 的双重宿主关系要实测下拉/对话框。
- **`GlassTopAppBar` 的 band 会随滚动渐显**：短列表（没得滚）只显示标题，
  这是库行为；若验收要"常驻模糊"，改用 `progressiveTextureBlur` 自绘 band
  （InstallerX 的做法）。
- **液态底栏新描边**：旧 `Highlight` 的光照参数已按同一坐标约定映射，
  但渲染实现换了，观感可能有细微差别。
- **R8**：glass 是新模块，首包检查 `NoSuchMethodError`。
- **许可证**：miuix Apache-2.0；InstallerX/Mishka GPL-3.0 并入 AGPL-3.0，
  NOTICE 增补。
- **不支持设备**：`isRenderEffectSupported()` 为假时 `LocalOs4Ui` 整体为假，
  所有 OS4 分支不进入，完整退回 OS3。

## 5. 验收清单

- OS4 开：全部页面顶栏玻璃、滚动穿透、玻璃返回键、底栏玻璃、
  纯文本下拉玻璃、对话框玻璃、卡片玻璃；深色/浅色、Monet、
  字体放大 1.6、横竖屏正常。
- OS4 关：界面与 `086dd05` 目视一致；三种旧底栏都还能用。
- 底栏切换、返回第一页、下拉选择、对话框确认/取消、导入导出、语言切换、
  更新对话框不回归。
- 启动页、欢迎页、隐藏图标、主题切换不回归。
- 静止页面没有持续重绘；滑动帧率不低于 OS3。
