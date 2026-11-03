# Review instructions

这个模块跑在别人的进程里。SystemUI 崩一次就是黑屏；连崩几次 LSPosed 进安全模式，停用模块之前手机没法用。看每个改动先按这一点判断。

## What Important means here

只有会造成下面这些后果的问题才标 Important：

- SystemUI 或 MiWallpaper 崩溃、卡死：遍历中改集合、异常穿出 hook、主线程上的长任务、没有终点的重投循环
- 一个类或方法找不到就连带别的功能：新 hook 和别的 hook 共用一个 try/catch，或从 `onPackageLoaded` 里 `return`
- 只在 release 里坏：`Class.forName` 里的常量字符串、R8 会削掉的反射目标、缺 keep 规则
- 永不停止或每帧都跑的开销：被 OEM 动画回调驱动的反射、取色、位图解码、binder 调用
- 无视用户关掉的开关，或改变已有用户默认看到的样子
- 交还 OEM View 后，它的 alpha、visibility、layout 没恢复
- 注释或文档说的和代码做的不一样
- 用户看得见的错：在这次改动针对的场景里画错、跳、闪、卡住或认错。要写出触发的状态和用户看到什么，说不出来就不算

风格、命名、重构建议最多算 Nit。

## Cap the nits

每次 review 最多报 5 条 Nit。多出来的在总结里写"另有 N 条类似"，不要逐条贴。如果找到的全是 Nit，总结开头写"没有阻断问题"。

## Require evidence

关于行为的结论要引用 `文件:行号`，并写出触发问题的状态，不能从名字推断。并发问题要给出具体的交错顺序；这里大部分代码都跑在主线程上。

同一个问题出现在多处时，只发一条，列出所有位置。

## Do not report

- CI 已经检查的：编译、lint、单测
- `build/`、`screenshot/`、`docs/*.patch`
- `app/src/test/resources/` 下的样本数据，以及 `gen-baseline.txt`、`hook-ids.txt` 里单纯增删的行

## Always check

- 遍历的列表，其元素的回调会不会从这个列表里删东西：`Tween.to(...)` 已经在目标值时会同步调 `end`（#80，c959d45 改成遍历 `toList()`）。
- 每个新 hook 的安装单独失败，并记下丢了哪个功能（一台 OS3 设备因为手动收缩用的一个枚举找不到，整个模块失效）。
- 在宿主类加载器里找的类名运行时拼，不写成常量（R8 把 `"kotlin.Unit"` 改成了 `"hc2"`）。
- OEM 每个动画步都会触发的入口，hook 后要节流或缓存（`triggerColorExtraction` 一分钟解码几百次壁纸；见 `PaletteThrottle`、`PaletteCache`）。
- 不要把大块数据放进 MediaSession 元数据或别的 binder 载荷（Apple 元数据里塞整份歌词撑满缓冲，系统永久删掉了我们的回调；c23714a）。
- 自我重投的 runnable 在理由消失时停下，包括亮屏且封面在显示的时候。
- 功能代码在每条路径上都读用户开关，不只是主路径。
- 改动碰到时钟、玻璃、壁纸、锁屏通知列表、媒体卡片或岛时，把 CLAUDE.md「HyperOS 的坑」逐条对着 diff 过一遍。这些是 OEM 行为，读代码看不出来，只能对照（比如在 SystemUI 里加一个压暗 View，玻璃和卡片模糊根本采不到它）。
- 同一个值的几条来源（实时、记录、兜底）给的是不是同一个量：同一条边、同一个坐标系、同一套材质；来源切换时会不会跳。兜底的颜色和材质要和 OEM 本来会画的一致，不要随手用最深的那套配方（#91，10e590a）。
- 我们按住的 hold、pin、加进去的 View，在每条退出路径上（离开锁屏、用户开始手势）都放掉了吗。
- 改歌词解析要让 `app/src/test/` 里的样本继续通过，配对窗口不能把真歌词吞成翻译（#113，6cee16b）。
- 高风险区域合并前要维护者在手机上确认 release 包：hook 安装和 `onPackageLoaded`、壁纸 GL 和纹理、遍历或修改 OEM 集合的代码、热重载、自更新、`.github/workflows/`。
