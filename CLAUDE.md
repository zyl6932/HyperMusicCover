# HyperMusicCover

HyperOS 4 的 LSPosed 模块（libxposed API 102），跑在 `com.android.systemui` 和 `com.miui.miwallpaper` 里；外面是 Kotlin + Compose + miuix 的设置 App。
applicationId 是 `com.github.zyl6932.HyperMusicCover`，Java 包名和探针广播 action 仍是 `com.os4.musiccover`。

## 命令

- 发布版构建：`./gradlew assembleRelease`（debug 没有 R8，在 SystemUI 里卡，判断不了手感）
- 单测：`./gradlew testDebugUnitTest`；只跑一个类：`./gradlew testDebugUnitTest --tests '*DateStatusLayoutTest'`
- Lint：`./gradlew lintDebug`
- 探针：`adb shell am broadcast -a com.os4.musiccover.PROBE --es op <op>`，再 `adb logcat -d | grep MCProbe`
- 别用 `-q` 跑 gradle 再 grep 错误：构建失败时旧 APK 还在 outputs 里，会被装上去。看 `BUILD SUCCESSFUL`。
- 装完不会重新加载：改了 SystemUI 侧要重启 SystemUI，改了 `WallpaperProbe` 要重启 MiWallpaper。
- 交回工作时贴出跑了什么和输出，只说"好了"不算证据。

## 来自真实崩溃的规则

- 每个 hook 单独失败。别的系统版本上找不到的符号只能丢它自己的功能：每处安装单独包住、记下丢了哪个功能，不要从 `onPackageLoaded` 里 `return`。能按签名形状找就别按名字找；lambda 用 `Xp.hookAllLambdas`（D8 可能追加类名后缀）。
- 在宿主类加载器里找的类名运行时拼（`listOf("kotlin", "Unit").joinToString(".")`）。`Class.forName` 里写常量会被 R8 改写成我们自己的混淆名，只在 release 里失败。
- 遍历一个集合时，不要调用可能同步回调、回调里又会改这个集合的东西。`Tween.to` 在 `from == target` 时同步调 `end`，回调从正在遍历的列表里删元素，让 SystemUI 连崩（#80）。遍历副本。
- 宿主主线程上不解码位图、不 release GL 资源、不连发 binder。OEM 每个动画帧都会触发的入口要节流（见 `PaletteThrottle`）。
- 每个自我重投的 `post`/`postDelayed` 都要有停止条件，锁屏亮着的时候尤其要注意。
- 用户关掉的开关要让行为真的停下，包括旁支路径。
- 我们改过的 OEM View 状态（alpha、visibility、layout）交还时要恢复；交还那一帧不要清掉恢复要用的记录。我们自己按住的东西（hold、pin、加进 OEM 布局的 View）在每条退出路径上都要放掉，包括离开锁屏和用户开始手势。
- 同一个值有几条来源（实时路径、记录下来的、兜底的）时，每条给的必须是同一个量：同一条边、同一个坐标系、同一套材质。来源切换时值不能跳，兜底也不能随手用最深的那套配方（#91）。
- 新功能默认关闭，先进 nightly，再进正式版。

## 坑

- `Main`、`WallpaperProbe`（`assets/xposed_init` 里写着、proguard keep）、`MainActivity` 和 `LauncherIcon`（组件名按包名拼）不能换包。
- `gradle.properties` 里的 `android.overridePathCheck=true` 不能删：仓库路径里有非 ASCII 字符。

## HyperOS 的坑（读代码看不出来，都在真机上踩过）

- 时钟玻璃和媒体卡片的模糊采样的是壁纸窗口，不是 SystemUI 的视图树。在 SystemUI 里加 View 压暗或遮挡，玻璃和模糊都看不到；要变就变进壁纸纹理（#90/#96）。
- 给 time_group 设 scale 是 RenderNode 属性，不触发重绘；玻璃边缘宽度在 TimeView.onDraw 里按缩放算，所以缩放变了（包括交还时回到 1）要让玻璃重画（`ClockCollapse.glassFollowsScale`）。
- 在 `setNotifY` 钩子里量位置（`getLocationOnScreen`）量到的是上一帧：OEM 在它返回后才应用这一帧的位移；刚从 AOD 醒来的头几帧还是 AOD 布局。
- 时钟收缩要钩 `setNotifY`，只钩 `notifStateChange` 会被系统直接打进 `setNotifY` 的回抢顶掉。
- SystemUI 刚重启时时钟容器是 0x0。只在 OEM 帧里执行的放置，量不到就返回，而 hold notifY 又会让 OEM 不再出帧，于是一次放弃就是永久放弃；要自己补帧重试。
- 锁屏通知列表 LIST 静止时 scrollY 就是正的（把列表放到时钟下面）；判断"在顶部"要和静止偏移比，不是和 0 比。
- 锁屏上的媒体卡片可能被通知顶到屏幕上方 1/3 以上；不能靠屏幕位置区分锁屏卡片和下拉面板里的副本，要看父链（在 NotificationStackScrollLayout 里、不在岛布局下）（#78/#82）。
- 锁屏上只有焦点通知和音乐时，永远录不到普通通知行；"等录到再用"的值要有不依赖录制的默认值（#91）。

## 审代码

分三类报：Important（本次改动引入、会造成下面那些问题）、Nit（最多 5 条，多的写"另有 N 条类似"）、Pre-existing（改动之前就有，单独列，不卡合并）。开头先写各类数量；没有要改的就直接说没有。每条都要有 `文件:行号` 和触发它的状态。同一类问题报一次，列出所有位置。

这里的 Important 指：宿主进程崩溃或卡死；一个符号找不到就连带别的功能；只在 release 里坏；每帧都跑或永不停止的开销；无视用户开关；OEM 状态没恢复；注释或文档说的和代码做的不一样；用户看得见的错——在这次改动针对的场景里画错、跳、闪、卡住或认错（要写出是哪个状态、用户看到什么）。

改动碰到时钟、玻璃、壁纸、锁屏通知列表、媒体卡片或岛时，把上面「HyperOS 的坑」逐条对着 diff 过一遍：新代码有没有踩到其中一条。这些坑读代码看不出来，只能靠对照。

不报：CI 已经检查的（编译、lint、单测）、命名和格式、给不出具体交错顺序的并发担忧。

复审只看上一轮以来的 diff，已经报过的不再重复。第三轮还有 Important，就停下来质疑方案，不要继续逐行修。

GitHub 上的 Code Review 另外读 [REVIEW.md](REVIEW.md)。

## 风格

代码、注释、提交信息用英文，照周围代码写。注释写为什么，尤其是踩过的坑。
