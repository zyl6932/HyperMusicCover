# 新对话提示词（直接复制下面整段）

---

我在给澎湃 OS4（HyperOS 4）做音乐锁屏的 LSPosed 模块 **HyperMusicCover**，作者 zyl6932。
工程在 `C:\Users\。\Desktop\music lockscreen\MusicCover`，
**已经是个 git 仓库并推到了 <https://github.com/zyl6932/HyperMusicCover>（public，Apache 2.0）**。

**applicationId 现在是 `com.github.zyl6932.HyperMusicCover`**（2026-09-09 改的）。
**Java 包名和探针广播 action 仍然是 `com.os4.musiccover`**——改了没有任何好处，
而文档里每一条 adb 命令都挂在上面。改 applicationId 的代价要记住：
LSPosed 把它当成一个新模块，**必须重新启用、重新勾作用域**，
而且**旧的 `com.os4.musiccover` 必须卸载或停用**，否则两个模块一起 hook SystemUI。
（模块状态文件在 SystemUI 的 filesDir 里，改包名不受影响。）

**现代 Xposed API（libxposed，API 102）**，作用域是
`com.android.systemui` + `com.miui.miwallpaper`（两个都必须勾选）。
模块本体是 Java（`Main.java` / `WallpaperProbe.java` / `Xp.java`），
外面套了一层 Kotlin + Compose + miuix 写的设置界面（见第 15 节）。

同目录参考素材：
- `apple.jpg` / `oppo.jpg` / `oppo2.jpg` / `honor.jpg` / `huawei.jpg` / `Samsung.jpg` — 各家的音乐锁屏
- `661c82581d3259572e124465c4fee013.mp4` — 最初的目标动效（小缩略图放大成大方封面）
- `base.apk` — 别人做的 LSPosed 模块 KeiMi（`os.kei.keimi`），R8 混淆，可参考思路

## 设备与环境

- 2509FPN0BC / HyperOS OS4.0.0.35.XPBCNXM / Android 17 (SDK 37) / 1200x2608 / density 480
- 已 root（KernelSU）+ LSPosed
- 无线 adb：端口会变，用 `adb mdns services` 查当前端口再 `adb connect`
- **SystemUI 和 MiWallpaper 的 dex 已拉到本地解包**，查类名不用上设备（见下方"本地资料"）

### 构建部署

**现在有 gradle wrapper 了**（CI 需要），本地直接 `./gradlew`：

```bash
export ANDROID_HOME=/c/android-sdk
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew --no-daemon assembleDebug     # 或 assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "su -c 'kill \$(pidof com.android.systemui)'"      # 改 Main.java 后
adb shell "su -c 'kill \$(pidof com.miui.miwallpaper)'"      # 改 WallpaperProbe.java 后
```

坑：
- 用户目录含非 ASCII `。`，`gradle.properties` 已加 `android.overridePathCheck=true`
- Git Bash 下所有 adb 命令要 `MSYS_NO_PATHCONV=1`
- `screencap` 必须指定显示器：`screencap -p -d 4630946457447247251 /sdcard/s.png`
- **heredoc 里写 Java 会被 shell 的引号配对搞崩**，改 Java 用 python 补丁脚本写到文件再执行
- `adb shell` 会吃掉 `$`，传内部类名要写 `"a.b.C\$D"`

## 已完成的功能（都实机验证过）

### 1. 时钟收缩接管

OS4 的时钟收缩不是缩放，all_in_one 是可变字体。入口：

```
KeyguardClockContainer.notifStateChange(y, withAnim, type)
  -> KeyguardClockNotifInteractor.setNotifY(y) -> ClockResult
```

**必须 hook `setNotifY`，不能只 hook `notifStateChange`**——系统的回抢直接打进
`setNotifY`，只拦后者会变成两路每 8ms 交替打架。（老文档里说 hook
`notifStateChange` 就够，是错的。）

实测：y=1877(无卡片) → timeHeight 1361；y=1307(有卡片) → 900；
**y<=740 硬 clamp 在 674/337/317**，再往下只有 clockTranslationY 线性上移。

`withAnim=true` 是瞬变。系统自己是逐帧 ramp y，要动画就自己跑弹簧
（miuix Folme：ζ=0.88 response=0.38s，实测 settle ~445ms）。

### 2. 时钟是两棵树（关键）

```
keyguard_background_layer
  KeyguardClockContainer #miui_keyguard_clock_container
    AllInOneHourClock  → time_group → hour_view      ← 小时（壁纸主体后面）
keyguard_foreground_layer
  FrameLayout #miui_keyguard_foreground_clock_container
    AllInOneMinuteClock → time_group → minute_view   ← 分钟（前景）
```

两棵树里 **id 完全相同**，`findViewById` 从 root 只会命中小时那棵。
**任何改时钟几何的操作都必须对两棵树各做一遍**，否则只有一半跟着变。

### 3. 收起成 OPPO 那种小时钟

clamp 下限（字形高 332px）还是太大。再往下靠**给 `time_group` 做 View scale**
（两棵树都要）：`TimeView.onDraw` 画的是 Path，父容器 scale 等于 canvas 叠矩阵，
按最终尺寸光栅化不掉画质，位置和字号一起缩。日期 `text_area` 是兄弟节点不受影响。

**缩放系数从每帧的 notifY 反推，所以 OEM 的弹簧顺带把它也驱动了，零自定义动画**：
`p = (natural - y)/(natural - 740)`，`k = 1 - p*(1-kMin)`，写在 `setNotifY` 钩子里。

`k = 0.335` 与 OPPO 等高（实机差分实测 111px vs OPPO 108px）。字宽仍比 OPPO 窄，
因为 OEM 挤压态本身是横向压缩的。

**pivotX 必须用 `DisplayMetrics.widthPixels/2`**，不能用 `g.getWidth()/2`——
这段从 setNotifY 钩子里跑，可能早于布局，宽度 0 会让时钟缩到左上角。

### 4. 液态玻璃 → 实心 的连续渐变

`clockEffect` 是锁屏编辑器的效果开关（**5=液态玻璃，2=叠加**），两种模式下
`glassData` 42 项**完全相同**，所以模式之间没有可插值的浮点通道。

但 `AllInOneBase.updateGlassValue(float)`（写 `glassData[36]`）是连续的：
0→1 平滑地从透明折射玻璃变成实心填充。挂在同一个 progress 上即可。
注意它在叠加模式下无效（玻璃 shader 没启用），必须在液态玻璃模式下测。

**液态玻璃缩小后会烂**——字腔全透明，缩到 111px 只剩细黑发丝线。所以收起时必须填实。

### 5. 全屏专辑封面当壁纸

**壁纸不在 SystemUI 里**，是独立进程 `com.miui.miwallpaper` 渲染的独立窗口
（`ImageWallpaper`，OpenGL）。**时钟玻璃的折射和媒体卡/通知卡的模糊都采样这个窗口**，
所以在 SystemUI 里加 ImageView 无论放哪层都会穿帮。

真正的注入点：

```
onSurfaceCreated() → mTexture.use(consumer)
  → ImageWallpaperRenderer.lambda$onSurfaceCreated$0(Bitmap)   ← hook 这里
```

选它而不是 `WallpaperTexture.getWallpaperBitmap()`，因为 `thisObject` 是 renderer，
能区分 Keyguard 和 Desktop，只动锁屏。**替换图必须缩放到和原位图完全相同的尺寸**
（本机 596x1296），`updateDimensions`/`updateMatrix` 按位图尺寸算 GL 矩阵。

前提和限制：
- **锁屏必须"自己有一张"壁纸**（存在独立的 `FLAG_LOCK` 记录），否则
  `KeyguardAnimImageWallpaperRenderer` 根本不会被实例化，只有 Desktop 那个，改它会连桌面一起改。
  **注意：内容可以和桌面壁纸一模一样**，要的只是那条独立记录——模块的
  `ensureLockWallpaper()` 就是把桌面壁纸原样复制一份设过去，照样能工作。
  （这里以前写的是"必须是不同的两张图"，是没验证就下的结论，错的。）
- **纹理只在 GL surface 创建时读一次**，息屏亮屏不重读。所以图要先落盘到壁纸进程的
  `getFilesDir()`，在 `Application.onCreate` 读回，**改图后必须 kill 壁纸进程才生效**
- `getBitmap()` 不是上传路径，别 hook
- 跨进程传图用广播带 JPEG 字节（壁纸进程没权限读 MediaSession）

### 6. 景深抠图必须一起隐藏

HyperOS 把壁纸主体抠图画在 **SystemUI** 里：`KeyguardDepthInteractor` →
`ImageView #deducted_image_view`（在 `keyguard_foreground_layer` 内，画在时钟之上，
1.05 缩放做视差）。只换壁纸纹理会留下**旧壁纸的主体浮在封面上**，非常像"壁纸没换干净"。

要 hook `KeyguardDepthInteractor.updateDeductedImageView()` 在 after 里重新隐藏
（系统会重新显示它）。**不要 hook `View.setVisibility`**——那是 SystemUI 最热的方法之一。

**但只挂这一个钩子不够，SystemUI 重启后会失效。** `updateDeductedImageView` 只是**其中一条**
重显路径；同一个 view 上还挂着 Folme 的透明度动画（`setDepthTransitionAlpha`、
`deductedTranslateAlphaFolmeAnimator`，用 `--es op cls --es name
com.android.keyguard.depth.KeyguardDepthInteractor` 能看到），它们够不着我们钩的任何方法。
按第 14 节的所有权模型，这里本来就该是**持续断言**而不是一次性设置。现在的做法：

- `guardDepth()` 在 deducted_image_view 上装一个 `OnPreDrawListener`，
  cover 模式下**每帧**检查一次可见性，被系统翻回来就再按下去。
  代价是锁屏每帧一次 `getVisibility()`；`VISIBLE→INVISIBLE` 只 invalidate 不 requestLayout，
  不会自己把自己循环起来。guard 挂在 view 上，keyguard 重建时跟着 view 一起没，
  下一次 `setDepthHidden(true)` 装新的。
- **抓不到 view 就重试**（12 次 × 250ms）。原来"找不到"只打一行日志就放弃：SystemUI 刚起来时
  前景层不一定已经 inflate 完，正好是重启后失效的另一半原因。
- `ACTION_SCREEN_ON` 里也补一次 `setDepthHidden(true)`（息屏期间 keyguard 可能被重建过）。

**日志里 `system re-showed the cut-out, re-hidden (n)` 就是系统抢回来的次数**（前 5 次
每次都打，之后每 100 次打一行）。下次要查是哪条路径抢的，从这行的时间点往前对日志。

### 7. 壁纸排版：镜像延展 + 模糊（Apple 那种）

方图 center-crop 进 1200x2608 会放大 5 倍砍掉大半。改成：清晰封面按原比例满宽居中，
上下用**垂直镜像的自己**填充再整体模糊——接缝两侧是同一行像素，颜色天然连续。

模糊要**渐进减半降采样 → 小尺寸跑可分离盒式模糊 → 渐进加倍升采样**。
一步降到 1/60 再单次 bilinear 拉回全屏会留下明显块状马赛克。

### 8. 运行时换纹理（不用杀壁纸进程）

`ImageEngineImpl.U()`（"preRender"，跑在 GL 线程上）里有这么一段：

```
if (P() && b) { b = false; mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h); }
```

`b` 就是"surface 待重建"标志位，`u()` 是 OEM 自己置位它的方法，`T(boolean)` 把一帧
post 到 GL 线程。而 `onSurfaceCreated()` 正是 `mTexture.use(...)` →
`lambda$onSurfaceCreated$0(Bitmap)`（我们已经在那儿换图）的那条路。所以换歌只要：

```java
engine.u();                    // 置位
setBooleanField(engine, "b", true);   // u() 是混淆名，这一位是成败关键，直接再写一次
engine.T(false);               // 请求一帧
```

**AnimatorProgram.setup() 里还会顺带 `makeFrosting(bitmap, blurRadius)`**，
所以走这条路刷新，连通知卡/媒体卡拿去模糊的那张磨砂副本也一起更新了。

拿 engine 实例：hook `KeyguardImageEngineImpl` 的构造函数（进程启动时就有）。
前提仍然是"锁屏自己有一张壁纸"（独立的 `FLAG_LOCK` 记录，内容可以和桌面完全相同），
否则这个 engine 根本不会被创建。

实测：`[MCWall] reload requested on KeyguardImageEngineImpl` →
`wallpaper texture REPLACED`，全程不杀进程。

### 9. 跟着媒体卡片自动开关 + 切歌自动换封面

`--es op auto --ez on true`。**开关是媒体卡片，不是播放状态**——暂停不退出，
只有把卡片关掉才退出。

唯一的钩子点：

```
MiuiMediaNotificationControllerImpl.access$setTopMediaData(impl, MediaData)
```

全路径的收口，`onMediaDataLoaded` 和 `onMediaDataRemoved` 最后都走到这里：

- 传进来 `MediaData` = 卡片在（并且告诉你是哪首歌）→ addView + setVisibility(true)
- 传进来 `null` = 卡片没了 → removeView + setVisibility(false)

**播放状态根本到不了这个方法**，所以暂停天然不影响它，正合要求。
`MediaData` 上还有 `token`（直接 `new MediaController(ctx, token)`，比
`pickController()` 那套排序猜测准）、`packageName` / `song` / `artist`（判重用）。

坑：

- **切歌是"先 remove 再 add"**，中间会瞬间传 null。所以"卡片没了"要**延迟 600ms
  才认**，否则每次切歌壁纸都会掉一下再回来
- SystemUI 重启后钩子还没触发过时**不能当成"卡片没了"**，否则会把刚从磁盘恢复的
  cover 立刻拆掉、然后钩子一响又装回来，闪一下。用 `sCardKnown` 挡住
- 取封面的重试链要带**代次号**。重试跨 2 秒多，期间卡片可能已经被关掉了；
  没有代次号的话，那个迟到的重试会在退出 cover 模式之后又把封面贴回去
  （实机日志里抓到过）

session 侧仍然留着 `addOnActiveSessionsChangedListener` + `MediaController.Callback`，
但只负责跟住会话和 metadata，不再决定开关。

**取封面有个坑，实机踩过**：Apple Music 换歌瞬间 session 里**只有 URI 没有 bitmap**，
而 `album_art_image`（媒体卡缩略图）那会儿**还是上一首的图**——结果壁纸换成了上一张
专辑，卡片却已经是新歌了。所以：

- 前几次只认 session 的 bitmap，最后一次才允许退回卡片缩略图
- 拿到的图跟当前壁纸**指纹相同**（8x8 缩略图 hash）就当成"还没更新"，隔 700ms 重试
- 4 次都是同一张 → 那就是同专辑的下一首，直接不动壁纸

合成（整屏位图 + 多趟模糊 + JPEG）**必须挪到 worker 线程**，一次性 adb 命令无所谓，
跟着换歌跑就会掉帧。媒体卡缩略图是 View，读它要 post 回主线程。

### 10. 封面排版的 bias

`composeWallpaper(src, w, h, bias)`：bias=0 贴顶，0.5 居中，1 贴底。
实机比过 0.08 / 0.20 / 0.34，**用户定的 0.34**（0.20 更靠上、封面躲开卡片更多，
备选）。`--es op bias --ef v 0.34` 可以实时调，改完立刻重新合成并刷新。

### 11. 锁屏壁纸必须"自己有一张"——模块现在会自愈

**这是整套机制的地基，而且它会自己塌掉。** 已经踩过一次：功能突然完全失效，
但 SystemUI 侧一切正常（`cover: on=true`、`pushart` 成功、`reload requested` 也发了），
就是屏幕上什么都没变。

原因：锁屏壁纸被重置成"跟随桌面"了——注意准确的说法是**锁屏没有自己的壁纸记录**
（`FLAG_LOCK` 为空、System 那条是 `mWhich=3`），**不是"两张图不能一样"**。
两张图完全可以是同一张，只要锁屏那份是独立存在的。判断方法：

```bash
adb shell dumpsys wallpaper | sed -n '/^Lock wallpaper state:/,+3p'
# 底下是空的 + System 那段是 mWhich=3 / mSystemWasBoth=true  → 就是共用一张
adb shell "su -c 'kill $(pidof com.miui.miwallpaper)'"
adb logcat -d | grep getEngineService
# 只有一行 isLockScreen = false → KeyguardImageEngineImpl 根本没被创建
```

没有独立锁屏壁纸时 MIUI 只建一个 `isLockScreen=false` 的 engine，
`KeyguardAnimImageWallpaperRenderer` 不存在，我们没有任何可以挂的东西，
**而且所有日志都显示成功**，非常容易误判成别的地方坏了。

模块现在会自己修：`ensureLockWallpaper()` 在每次要贴封面前检查
`WallpaperManager.getWallpaperFile(FLAG_LOCK)`，为 null 就把当前桌面壁纸原样复制一份
设成锁屏壁纸（`setStream(..., FLAG_LOCK)`，SystemUI 有 `SET_WALLPAPER` 权限）。
**用户看不出区别**——锁屏还是那张图，只是它自己有了一份，于是 keyguard engine 得以存在。

```bash
--es op lockwp                 # 手动检查/修复（正常不需要：每次贴封面前都会自动查一遍）
--es op lockwp --ez force true # 按屏幕尺寸重设一次（纹理尺寸不对时用）
--es op lockwp --ez clear true # 还原成"锁屏跟随桌面"（会再次让功能失效）
```

注意：复制过来的锁屏壁纸是全分辨率的，纹理尺寸随之变成桌面壁纸的尺寸
（实测从 596x1296 变成 1579x3432）。我们合成的图是按屏幕 1200x2608 出的，
到壁纸进程里会被 `centerCrop` 放大到纹理尺寸——比例一致不变形，只是略软。

### 12. 切歌换壁纸的延迟：750ms → 150~230ms

一开始从"卡片报新歌"到"纹理换掉"要 **750ms**，肉眼能看出慢半拍。用 logcat 时间戳
逐段量出来的账，按收益排序：

**① 广播加 `FLAG_RECEIVER_FOREGROUND`：500ms → 5ms。**
SystemUI 发给壁纸进程那条广播走后台队列，实测**光投递就花 500ms**，比合成加上传加起来
还多。加一个 flag 就没了。这是最大的一笔，而且最不显眼。

**② 短路 `KeyguardAnimImageWallpaperRenderer.getBitmap()`：省 210ms。**
`onSurfaceCreated` 里 OEM 会先把**真正的锁屏壁纸从磁盘解码出来**（1579x3432，实测 210ms），
下一步就被我们替换掉、直接扔了。`getBitmap` 里 `param.setResult(我们的图)` 就能整段省掉。
注意上传路径仍然是 lambda 那个钩子，这里只是短路了**来源**；而且只在纹理尺寸已知后才短路，
冷启动第一次仍然走原路去认尺寸。

**③ 锁屏壁纸按屏幕尺寸设：省 ~100ms。**
`ensureLockWallpaper` 原来是把桌面壁纸原样复制过去，于是纹理变成 1579x3432，
**两边都要缩放**——SystemUI 出 1200x2608，壁纸进程再拉到 1579x3432。
改成复制时就 centerCrop 到屏幕尺寸，纹理正好 1200x2608，两边都不用缩。
日志里会打 `(no rescale)`。这张图只会显示在这块屏上，大过屏幕纯属浪费。
（`--es op lockwp --ez force true` 可以按新尺寸重设一次。）

**④ 取封面的轮询 700ms → 120ms。**
检查一次只是读一下 metadata，很便宜。原来按 700ms 一档等，差 50ms 没赶上也要多等 700ms。
改成 120ms 一档、14 次，覆盖同样的 ~1.6s 窗口。

**⑤ 杂项。** JPEG 落盘挪到上传之后（磁盘那份只在下次冷启动才有用）；
worker 线程提到 `THREAD_PRIORITY_DISPLAY`（默认优先级会被排到小核，同一张图
合成时间在 48ms 和 196ms 之间乱跳）。

现在的账（`pushart ... draw Xms encode Yms` 日志里能直接看）：

| 段 | ms |
|---|---|
| 合成（模糊+镜像+羽化） | 70~150 |
| JPEG 编码 | 20~50 |
| 广播投递 | ~5 |
| 解码 + 上传 | 20~50 |
| **合计** | **150~230** |

还想再快的话，剩下最大的一块是 JPEG 那趟来回（编码+解码 ~60ms）。
可以改用 `SharedMemory`/ashmem 直接传像素——`Bitmap` 走 binder 会炸，但 ashmem 的 fd 不算
在 1MB 事务限制里。合成本身那 70~150ms 里，`saveLayer` 开的离屏层（1200x1200，5.7MB）
和每次重新 `createBitmap` 整屏位图也都还有得省。

### 13. 换锁屏时钟样式后时间位置全乱——一切都要从日期算

**症状**：换时钟样式之后 cover 模式下时间的位置就不对。三种样式三种坏法：
堆叠式（上下两行）跑到屏幕外；单行式太靠下；还有一种时间跑到日期**上面**去了。

**根因**：位置是拿某一种样式量出来的常数算的。每种样式的字形画在 `time_group` 里的
位置完全不同，用 `--es op bounds` 量（`TimeView.getTextBoundsWithPosition()`）：

| | 单行样式 | 堆叠样式 |
|---|---|---|
| 容器高 | 1773 | 2163 |
| hour 字形 top | 351 | 650 |
| minute 字形 top | 354（并排） | 909 |
| y=740 时日期字形屏幕 y | 240 | **179** |

#### 试过的两版错误做法

**① `pivotY = 325f` 写死**。堆叠样式按 325 缩放，字形被拉到局部 434，
加上容器 `ty=-467` 直接出屏幕。

**② 改成按字形自己的 top 当 pivot**（"OEM 把它挤到哪就留在哪"）。
看着讲得通，但**锚错了对象**：OEM 在不同样式下把时钟放哪本来就不一样，
所以单行式太靠下、另一种跑到日期上面。

#### 正确做法：时钟锚在日期上，日期锚在固定位置上

`text_area`（日期）和 `time_group` 是 `clock_animation_container` 里的**兄弟节点**，
所以 OEM 那个挤压平移会同时作用在两者身上、**在计算里自动抵消**，
剩下的是一个跟样式无关的布局关系：

```java
pivotY       = glyphTop()                                   // 两棵树里所有可见 TimeView 字形 top 的最小值
translationY = dateBottom + 10dp - (time_group.getTop() + glyphTop)
```

pivot 取在字形顶边，缩放后顶边不动，所以这个平移量**跟缩放系数 k 无关**，一次算完就是对的。
两棵树用同一个 pivot 和同一个平移，堆叠样式的上下两行才会一起收拢
（各用各的 top 的话中间会留个大空隙）。

日期本身则被拉到一个**固定目标**上：`DATE_TOP_DP = 76f`（屏幕顶往下 76dp = 228px，
字形落在 240px）。这是单行样式本来就落的位置，堆叠样式原本高了 61px。
**不要用 `status_bar_height` 去推**——这台机器上它读出来是 182px，
是整个挖孔带的高度，不是眼睛会去对齐的那条线。

偏移量按 `p` 淡入（`sNudge * p`），进 cover 模式时是一段连续动作而不是开头闪一下。

#### 时序坑：钩子里量到的坐标永远慢一帧（踩了两次）

我们跑在 `setNotifY` 的钩子里，而 **OEM 是在 setNotifY 返回之后才把这一帧的平移写下去的**。
所以在这里 `getLocationOnScreen()` 量到的永远慢一帧，
**从 AOD 亮屏的头几帧量到的还是 AOD 的布局**。拿这个读数去纠正，
就是"时钟先跳到顶端再滑下来"的成因。

所以偏移量**不是每帧重算**的（`updateDateOffset()`）：
只在 `p >= 0.999`（完全收起）时采样，而且**连续两次读数一致才采纳**——
一致说明 OEM 已经不动了，量到的是稳定态。在那之前一直用已经在用的值，
那个值本来就是对的（息屏前在同一个时钟同一个状态下量的）。
时钟容器重新 attach（换样式 / keyguard 重建）时把 `sNudgeSample` 清成 NaN 重新量。

**实测**：堆叠样式偏移 195，日期字形从 179 落到 239；单行样式算出来 ≈0，行为不变。
两种样式的日期现在对齐在同一条线上。

### 14. 所有权模型（贯穿全部功能）

系统会抢回一切。凡是接管都要**持续断言**，不能一次性设置。同时：

- `abandonHold()` 是唯一放手出口，挤压/缩放/玻璃/着色当成**一个状态**一起收回
- **`onDetachedFromWindow` 在息屏亮屏时不触发**（keyguard 不是每次都重建），
  靠 `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT` 兜底
- **cover 模式下息屏不释放**，否则亮屏时会从大时钟瞬变到小时钟（就是"AOD 点亮跳变"）
- **SystemUI 会自己重启**（实测有一次，无任何崩溃记录），所以 cover 状态必须落盘到
  `getFilesDir()/mc_cover_state`

### 15. 设置界面（HyperMusicCover app）

Kotlin + Compose + [miuix](https://github.com/miuix-kotlin-multiplatform/miuix)，
**界面整套抄的 [HyperNavBar](https://github.com/HyperNavBar/HyperNavBar)**（Apache 2.0，
搬过来的 20 个文件都加了出处头注释，仓库里有 NOTICE）。

四个页签：

| 页签 | 内容 |
|---|---|
| 主页 | 模块是否生效的状态卡（配色/尺寸抄 KernelSU 的 `HomeMiuix.kt`：110dp 图标 offset(27,31)、16×14 内边距、22sp 标题）+ 设备信息 |
| 功能 | 当前曲目、封面纵向位置、时钟缩放、玻璃强度、重启系统界面 |
| 设置 | 主题模式（含 Monet）、悬浮导航栏、液态玻璃效果、背景模糊、语言、导入导出 |
| 关于 | 项目地址、反馈、Apache 2.0、第三方许可证（带 OS3 动态背景效果） |

**app 和模块之间怎么通信**：模块跑在 SystemUI 进程里，跟 app 没有共享存储，
所以 **app 不读磁盘，直接问**——复用了 adb 探针那套广播协议（`ModuleBridge.kt`）：

- 改设置 = 发一条普通广播（`auto` / `bias` / `clockscale` / `glassend` / `lockwp`…）
- 读状态 = 发**有序广播** `op=query`，模块在 `setResultExtras()` 里回
- **回不回得来，本身就是"模块有没有生效"的判据**（1.5s 超时 = 未生效）

**启动白屏 / splash**：原来主题是 `android:Theme.Material.Light.NoActionBar`，
`windowBackground` 恒为白色，冷启动从"系统 splash 消失"到"Compose 画出第一帧"这一段就是白屏。
两半修法：

- **窗口底色**：`themes.xml` / `values-night/themes.xml` 给 `windowBackground` 和
  `windowSplashScreenBackground` 一个 `@color/window_background`（浅 `#F7F7F7` / 深 `#000000`），
  splash 图标用 `@mipmap/ic_launcher`。
  但静态资源盖不全——**用户可以在 app 里把主题强制成跟系统相反的深浅，还有 Monet 动态取色**。
  所以 `LaunchBackground`（SharedPreferences）**把 app 真正画的 `MiuixTheme.colorScheme.surface`
  记下来**，按「主题模式 + 系统深浅」做 key，下次冷启动在 `onCreate` 里
  `window.setBackgroundDrawable()` 直接用它。静态那个颜色只有"某个组合第一次启动"才会看到。
- **splash 的深浅要跟 app 自己的主题走，不是跟系统走**。踩过：系统开了定时深色
  （HyperOS「19:00–07:00」，`dumpsys uimode` 里 `mComputedNightMode=true`），
  而用户在 app 里选了浅色 —— 结果 splash 是黑的、app 是白的。
  `windowSplashScreenBackground` 是**主题属性，系统在我们任何代码跑起来之前就解析完了**，
  activity 里做什么都救不回来。唯一的杠杆是"拿哪份 configuration 去解析主题"，
  也就是 `UiModeManager.setApplicationNightMode()`（`AppNightMode.kt`）:
  Light/MonetLight → `MODE_NIGHT_NO`，Dark/MonetDark → `MODE_NIGHT_YES`，
  System/MonetSystem → `MODE_NIGHT_AUTO`（框架把 YES/NO 以外的都映射成
  `UI_MODE_NIGHT_UNDEFINED`，也就是"听系统的"）。
  **改这个值会触发 configuration change、重建 activity**，所以只在值真的变了时才调用，
  用 `launch_background` 里的 `night_mode` 记住上次设过的值。
- **把 splash 留到内容画好**：Android 12+ 的 splash 是"app 画出第一帧就撤"，而 Compose 的
  第一帧是空窗口。`holdSplashUntilContentIsReady()` 在 `android.R.id.content` 上挂
  `OnPreDrawListener`，首次组合完成前一律返回 false，splash 就一直留着，
  于是变成 splash → UI，中间没有空白。返回 false 时 ViewRootImpl 会自己再排一次 traversal，
  所以不用手动 invalidate 去轮询。`SPLASH_HOLD_MAX_MS = 1500` 是兜底。
  **`savedInstanceState != null` 时不设最小停留**——转屏/换语言重建 activity 时后面没有 splash，
  硬停 700ms 只会让用户看见一段空白。

**动效图标**（`drawable/splash_icon.xml` + `splash_icon_animated.xml`）：唱片转起来再停住，
700ms，一次。几个不显然的点：

- **splash 图标不是自适应图标，没人替你裁圆**。所以渐变底盘是**画在 vector 里**的一个
  r=36 的圆（108 viewport 居中），不是靠 `windowSplashScreenIconBackgroundColor`。
  Android 的规范是内容落在中间三分之二，r=36 正好。
- **但那个方框会裁**：第一版让底盘 scale 0.62→1（overshoot）弹进来，实机上底盘边缘被
  图标方框切掉了。**能动的东西必须始终待在 r=36 那个圆里**，所以现在整套动画只剩唱片旋转，
  底盘和套子都是静止的。（唱片"从套里滑出来"那段也一并去掉了。）
- 启动图标的原始画法整体套在 `<group name="art" scale=0.74>` 里，**路径坐标和 launcher 图标一字不差**，
  两个图标是同一张画。0.74 是让套子最远的那个角（离中心 39.2）刚好落进圆里。
- **唱片上必须有个不对称的东西，否则转了等于没转**：纹路是同心圆，旋转在屏幕上没有任何变化。
  所以加了两道对置的弧线当反光。**弧线是深色的**——唱片本体是白的，白色反光在白盘上什么都不是
  （第一版就是白的，本地渲染帧序列才看出来）。
- `windowSplashScreenAnimationDuration=700`，**平台上限 1000ms**，超过就不等了。
  `MainActivity.SPLASH_ANIMATION_MS` 要跟它保持一致：Compose 通常比动画先就绪，
  不设这个最小停留的话动画会被拦腰切断。

**改图标不用上机验**：`splash_icon.xml` 里全是圆、圆角矩形和圆弧，用 Pillow 按同样的变换和
插值器画几帧出来看构图和动效姿势就够了（脚本在 scratchpad，`splash_frames*.png`）。
这不算"截图测效果"，画的是自己的图，不碰手机。

**故意没有做成开关的东西**（改过一轮又删掉了，别再加回来）：

- **跟随媒体卡片**：关掉之后模块什么都不做，那不是一个值得给的选项。
  无条件开启，状态文件不再存 `auto`，adb 的 `auto` op 只留给调试临时关。
- **隐藏景深抠图**：不隐藏就是渲染错的（旧壁纸主体浮在封面上）。
  cover 模式无条件执行，字段删了——以前存盘的 `depth=0` 会让它启动就错，界面上还没法解释。
- **修复锁屏壁纸**：改成**每次贴封面前都查一遍**（原来是每进程只查一次的缓存标志）。
  用户随时可能把壁纸改回「同时应用到桌面和锁屏」，缓存说"已经没问题"就永远发现不了，
  只能靠人去点修复。实测：清掉锁屏壁纸后触发一次贴封面，模块自己查、自己复制、自己修好。
- **立即应用 / 恢复原壁纸**：跟随卡片之后没意义了，adb 还留着 `pushart`。

## 现代 API（102）——2026-09-09 从 classic 迁过来的

依赖是 `compileOnly("io.github.libxposed:api:102.0.0")`（Maven Central；
classic 那个 `api.xposed.info` 仓库最高只有 **82**，根本没有 102，
"用 102" 指的就是 libxposed 这套）。**API 版本跟 APK 体积无关**，那个 jar 一个字节都不进包。

**入口和注册全变了**：

| classic | API 102 |
|---|---|
| `implements IXposedHookLoadPackage` | `extends XposedModule` |
| `handleLoadPackage(lpp)` | `onPackageLoaded(PackageLoadedParam)` |
| `lpp.classLoader` | `param.getDefaultClassLoader()` |
| `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| manifest 的 `xposedscope` | `META-INF/xposed/scope.list` |
| manifest 的 `xposedminversion` | `META-INF/xposed/module.prop` 的 `minApiVersion` |

这些文件放在 `app/src/main/resources/META-INF/xposed/`，Gradle 自动打进 APK。
manifest 里那几条 meta-data **留着**（管理器还靠它列出模块），
`xposedminversion` 改成 102——只懂 classic 的框架会直接拒绝，
而不是加载到一半去找已经不存在的 `assets/xposed_init`。

**hook 模型从 before/after 变成了拦截器链**（像 OkHttp 的 interceptor）：

```java
Xp.hookAll(cls, "name", chain -> {
    Object result = chain.proceed();   // after 钩子：先跑原方法
    ...
    return result;
});
```

`Hooker` 是函数式接口，所以原来 13 个匿名 `XC_MethodHook` 全变成了 lambda。三种映射：

- 纯 after → `proceed()` 再干活；
- **改参数**（`setNotifY` 挟持时钟 Y、壁纸上传换图）→ `chain.proceed(args)`，
  比原来 `param.args[0] = x` 更直白；
- **短路**（`getBitmap` 省掉 210ms 的磁盘解码）→ **直接 return，不 proceed**。

**`XposedHelpers` 没了**，现代 API 故意不提供任何 helper。
`Xp.java` 是把这个模块用到的那几个（`findClass` / `getObjectField` / `callMethod` /
`setBooleanField` / `hookAll` / `log`）用普通反射重写了一遍。
**`Xp.hookAll` 只看 `getDeclaredMethods()`，不往父类走**——classic 的 `hookAllMethods` 就是这个语义，
而且这里是性命攸关的：哪天 OEM 类不再 override `onAttachedToWindow`，
往上走就会 hook 到 `View.onAttachedToWindow`，等于给 SystemUI 里每一个 View 都挂钩子。

**LSPosed 1.11.0 确认支持**：`/data/adb/modules/zygisk_lsposed/framework.dex` 里能搜到
`XposedModule` / `attachFramework` / `HookBuilder` / `Chain` / `intercept`
（102 的拦截器模型），而且日志里有 `New modules detected, hook preferences: pkg=...`。
`java_init.list` 这些字符串在 framework.dex / daemon.apk / .so 里都搜不到，
应该是在原生 daemon 里，**没验证到，只是按官方 spec 写的**。

**换 API 之后必须做的事**：LSPosed 里重新启用、重新勾作用域，
**并且卸载或停用旧的 `com.os4.musiccover`**，否则两套都在 hook SystemUI。

## 发布：签名、R8、CI

**release 签名**：`keystore.properties`（**git 忽略，只在本机**）指向 `release.jks`。
两个文件都不进仓库；CI 从 `SIGNING_*` secrets 读同样的值。
两者都没有时 release 会**退回 debug 签名**——能装，但跟正式签名的包**签名不一致，互相覆盖不了**。

**R8 是开着的**（`isMinifyEnabled` + `isShrinkResources`）。
**不开的话 APK 是 47MB，开了 2.7MB**——大头是 `material-icons-extended`，
那是几十兆生成出来的图标代码，这个 app 只用了几个。模块自己的代码在哪边都是零头。
`app/proguard-rules.pro` 里 keep 了 `Main` 和 `WallpaperProbe`：
**LSPosed 是从 `assets/xposed_init` 读类名反射加载的，R8 眼里整个模块都不可达**，
不 keep 会被削光。改完 release 记得验一下：

```bash
unzip -p app-release.apk assets/xposed_init          # 应该打印 com.os4.musiccover.Main
unzip -p app-release.apk classes.dex | grep -c "com/os4/musiccover/Main"
```

**Xposed API 的版本跟体积无关**——那个 jar 是 `compileOnly`，一个字节都不进 APK。

**CI**（`.github/workflows/`）：
- `ci.yml`：push / PR 都跑，build debug + release + `lintDebug`，传 debug APK 当 artifact。
  `lintDebug` 曾经红过 6 条 `MissingPermission`——`Main.java` 调 `WallpaperManager` 的地方
  跑在 SystemUI 进程里（那边有权限），这个 APK 自己没有也不需要，已经就地
  `@SuppressLint("MissingPermission")` 并写了原因。
- `nightly.yml`：每天 18:00 UTC（北京时间凌晨 2 点）出一版 pre-release，tag `nightly-YYYYMMDD`。
  **上次 nightly 之后没有新提交就自己跳过**，所以 release 列表是"改动"而不是"日历"。
  只保留最近 7 个 nightly，其余连 tag 一起删；**只认 `nightly-` 开头的 tag，正式 release 不碰**。
  用的是 runner 自带的 `gh`，没有第三方 action。

要让 nightly 用正式签名，在仓库 Settings → Secrets 里加：
`SIGNING_KEYSTORE_BASE64`（`base64 -w0 release.jks`）、`SIGNING_STORE_PASSWORD`、
`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。没加也能跑，只是会退回 debug 签名并在 release 说明里写明。

**README.md 已经从仓库里撤掉了**（`git rm --cached` + 写进 `.gitignore`），
本地那份还在，是用户要自己重写的草稿，**不要再提交上去**。

## 探针命令

SystemUI 侧 `-a com.os4.musiccover.PROBE`：

```bash
# 跟着媒体卡片自动开关：卡片在就是音乐锁屏，暂停不影响。
# 现在是无条件开启的（进程起来就打开，状态文件不再存 auto），这条只在调试时用来临时关掉
adb shell am broadcast -a com.os4.musiccover.PROBE --es op auto --ez on false

# 一键开关整套效果（封面壁纸 + 景深隐藏 + 小时钟 + 玻璃渐变），手动模式
adb shell am broadcast -a com.os4.musiccover.PROBE --es op pushart --ez on true
# 不用再杀壁纸进程了，art 广播自带 reload

--es op bias --ef v 0.34            # 清晰封面的纵向位置，0=贴顶 0.5=居中
--es op reload                      # 只让壁纸进程重新上传一次纹理

# 单项调试
--es op state                       # 查接管状态
--es op collapse --ef k 0.335       # 时钟缩放系数
--es op glassmorph --ef v0 0.0 --ef v1 0.75
--es op hold --ef y 740 [--ez anim false] [--ei ms 370]
--es op release / abandon
--es op depth --ez on false         # 单独隐藏景深抠图
--es op views [--ez root true]      # dump 视图树
--es op bounds                     # 时钟各部件的屏幕坐标 + 字形 bbox（换样式排错必用）
--es op api --es id hour_view|clock # dump 某个 View 的接口
--es op cls --es name <fqcn> [--es grep x]
--es op params --ef h/--ef weight/--ef sizei   # 直接驱动 TimeView 字体轴
--es op gdata [--ei idx N --ef v X] # 读/改 glassData 42 项
--es op callclock --es m updateGlassValue --ef f 0.5
--es op verbose --ez on true        # 打开逐帧日志（默认关，很吵）
```

壁纸进程侧 `-a com.os4.musiccover.WPROBE`：`cls` / `bmp` / `art` / `reload` / `state`

日志：`adb logcat -d | grep -E "MCProbe|MCWall"`

## 当前待办（按建议优先级）

1. **时钟取色**（唯一还没动的大件）。MIUI 的 `isAutoPrimaryColor` 从**原始壁纸**取色，
   我们绕过它换了纹理，取色逻辑不知道封面是什么颜色，结果小时钟压在封面上经常看不清
   （浅色封面尤其明显，实机上 Taylor Swift *Lover* 那张几乎读不出时间）。
   入口线索：`AllInOneBase.updateClockGlassColor(int,int)`、`TimeView.setGlassColor(int,boolean)`。
   注意从 `TimeView.onDraw` 前写 `glassData` **不生效**，uniform 不是在 draw 时读的。
   取色本身好办，`pushart` 已经拿到完整位图了——而且现在换歌会自动重算，取色要挂在
   同一条路上（`pushArtToWallpaper` 里合成完顺手取）。

2. **收起时钟会让通知区折叠**（notifY 按在 740，系统以为那里有内容）。副作用，待定要不要一起接管。

3. **换封面是硬切**。纹理一帧换掉，没有过渡。OEM 自己换壁纸时走的是
   `AnimatorProgram.startRevealAnim(boolean)` / `mRevealAnimator`，可以试试在 reload
   前后驱动它，做成 Apple 那种交叉淡入。

5. **再砍延迟**（现在 150~230ms，够用了，想更快见第 12 节末尾）：
   JPEG 来回改成 ashmem 传像素、合成时复用位图、干掉 `saveLayer`。

4. **卡片关掉的 600ms 防抖**是按"切歌 remove/add 间隔"拍的，实际用久了如果发现
   切歌还是会闪一下，就加大它（`CARD_GONE_MS`）。

6. **`DATE_TOP_DP = 76f` 是按这台机器量的**（1200x2608 / density 3.0）。
   换设备大概率要重新量：`--es op bounds` 看 `text_area` 的屏幕 y。

7. **支持另一种封面样式**（用户提过要加）。现在 `composeWallpaper()` 只有"满宽居中 + 镜像模糊"
   一种排版，bias 控制纵向位置。要加样式的话，模块侧加个 `style` 参数存进状态文件，
   app 的「功能」页加个 `WindowDropdownPreference`，走 `ModuleBridge` 那条广播就行。

## 本地资料（scratchpad，避免重复上设备查）

`C:\Users\。\AppData\Local\Temp\claude\C--Users---Desktop-music-lockscreen\<session>\scratchpad\`
- `sysui/` — MiuiSystemUI.apk 的 dex + `allstrings.txt`（全部字符串，可 grep 类名/方法名）
- `miwp/` — MiWallpaper.apk 的 dex + `w.txt`
- `keimi/` — base.apk 的 dex + `k.txt`

新会话如果这些没了，重新拉：
```bash
adb pull /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk
adb pull /product/app/MiWallpaper/MiWallpaper.apk
# 解 classes*.dex 后用 python 正则抽 [\x20-\x7e]{8,} 生成字符串表（Git Bash 没有 strings）
```

## ⚠️ 手机当前被改过的状态，务必还原

**锁屏壁纸会被换成专辑封面，且重启不会自己恢复**（图落在
`/data/user/0/com.miui.miwallpaper/files/mc_art.jpg`，状态落在
`/data/user*/0/com.android.systemui/files/mc_cover_state`，现在是
`cover=` / `bias=` / `clock=` / `glass=` 四行；`auto` 和 `depth` 已经不存了）。

**跟随媒体卡片现在是无条件开启的**，所以光关 cover 没用——媒体卡片一出现它自己就回来了。
要彻底还原：

```bash
adb shell am broadcast -a com.os4.musiccover.PROBE --es op auto --ez on false
adb shell am broadcast -a com.os4.musiccover.PROBE --es op pushart --ez on false
adb shell am broadcast -a com.os4.musiccover.PROBE --es op lockwp --ez clear true  # 可选
# 不需要杀壁纸进程了
```

**另外：锁屏壁纸现在是模块自己设的一张（桌面壁纸的副本，肉眼看不出）**，
因为原来的被重置成"跟随桌面"了。功能要求的是这条独立记录，不是两张图长得不一样，
所以副本完全够用——但别在系统设置里把壁纸"同时应用到桌面和锁屏"，那会把这条记录抹掉。

为了测试不息屏做的改动**已经还原**（`svc power stayon false` /
`dumpsys battery reset` / `screen_off_timeout 600000`），充电状态现在是真实的。



## 工作方式上的提醒

这个项目里我（模型）已经犯过几次同类错误，新会话请注意：

- **别急着下结论说"系统不会这样做"**。我说过两次"系统从不重新显示景深层"，
  两次都被实机推翻。测试覆盖不足就别断言，先说"没观察到"。
- **改完自己 kill SystemUI，会清掉所有静态状态**，很容易把自己造成的现象误判成 bug。
- **量尺寸要用差分法**（截一张隐藏目标的底图再截一张，相减取 bbox），
  直接对壁纸做阈值分割会被背景污染。
- **测试是用户的事，不要自己截图验证效果。** 改完代码 → 构建 → 安装 → kill 对应进程 →
  然后**停下来**，说清楚装了什么、要看哪里，让用户在手机上看。截图只用来**量尺寸/坐标**
  （差分法量 bbox 那种），不用来"看看效果对不对"——静态图看不出动效，而且手机是用户在用的。
  日志不打扰人，`adb logcat` 可以随便抓。
