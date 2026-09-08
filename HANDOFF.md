# 新对话提示词（直接复制下面整段）

---

我在给澎湃 OS4（HyperOS 4）做音乐锁屏的 LSPosed 模块。工程在
`C:\Users\。\Desktop\music lockscreen\MusicCover`，包名 `com.os4.musiccover`，
classic Xposed API，**已安装并在 LSPosed 中启用，作用域是
`com.android.systemui` + `com.miui.miwallpaper`（两个都必须勾选）**。

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

```bash
export ANDROID_HOME=/c/android-sdk
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
GR=$(ls -d ~/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle)
"$GR" --no-daemon assembleDebug
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

### 13. 换锁屏时钟样式后时间跑到屏幕外——pivot 不能写死

**症状**：换了一种锁屏时钟样式（上下两行的堆叠式，"00" 在上 "29" 在下）之后，
进入 cover 模式时间整个跑到屏幕顶上看不见了，日期也顶到状态栏里。

**原因有两个，都是把某一种样式量出来的常数当成了通用值：**

**① `pivotY = 325f` 写死了。** 每种时钟样式的字形画在 `time_group` 里的位置完全不同。
用 `--es op bounds` 量出来（`TimeView.getTextBoundsWithPosition()`）：

| | 单行样式 | 堆叠样式（natural） | 堆叠样式（y=740） |
|---|---|---|---|
| hour 字形 top | ~325 | 650 | 650 |
| minute 字形 top | ~325（并排） | 1373 | 909 |

按 325 缩放堆叠样式，字形被拉到 `325 + (650-325)*0.335 ≈ 434` 局部坐标，
再加上容器 `ty=-467`，直接跑到屏幕外。

**改成从字形自己的 top 推**：`glyphTop()` 取两棵树里所有可见 `TimeView` 的
`getTextBoundsWithPosition().top` 的最小值当 pivot。这样缩放后字形顶边**不动**，
OEM 把它挤到哪它就留在哪，跟样式无关。两棵树用**同一个** pivot，
堆叠样式的上下两行才会一起收拢（各用各的 top 的话中间会留个大空隙）。

实测这个样式推出来 `pivotY=650.02`。而单行样式的 clamp 值是 674/337/317、字形高 332，
说明它的字形 top 差不多就是 325——也就是新算法在老样式上会得出跟原常数一样的结果。

**② `SQUEEZE_FLOOR = 740` 也是按单行样式定的。** 堆叠样式在 y=740 时 OEM 把整组
往上搬 `ty=-467`，日期落到 y=65，**压在状态栏底下**（单行样式落在 ~260，没事）。

加了个兜底：`keepClockClearOfStatusBar()` 量日期实际的屏幕 y，低于
`status_bar_height + 24` 就把整组往下推回去，**只在压到状态栏时才动**，
单行样式算出来是 0，行为不变。推的是 `time_group` 和 `text_area` 的 `translationY`
（两棵树都要），**不能推它们的父容器** `clock_animation_container`——那是 OEM
自己的动画通道，写它会跟弹簧打架。用 `sClockNudge` 记住推了多少，
下一帧减掉再算，避免累加成正反馈。

实测这个样式推了 103px，日期从 65 回到 168。

**注意**：只在堆叠样式上实测过。单行样式按上面的推理应该行为不变，但没回去验证过。

### 14. 所有权模型（贯穿全部功能）

系统会抢回一切。凡是接管都要**持续断言**，不能一次性设置。同时：

- `abandonHold()` 是唯一放手出口，挤压/缩放/玻璃/着色当成**一个状态**一起收回
- **`onDetachedFromWindow` 在息屏亮屏时不触发**（keyguard 不是每次都重建），
  靠 `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT` 兜底
- **cover 模式下息屏不释放**，否则亮屏时会从大时钟瞬变到小时钟（就是"AOD 点亮跳变"）
- **SystemUI 会自己重启**（实测有一次，无任何崩溃记录），所以 cover 状态必须落盘到
  `getFilesDir()/mc_cover_state`

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
`cover=` / `bias=` / `clock=` / `glass=` 四行）。

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
- 用户在用手机的时候不要连续截屏。
