# HyperMusicCover

HyperOS 4 (Android 17) 的音乐锁屏 LSPosed 模块：**播歌时把专辑封面变成锁屏壁纸，并把时钟收成 OPPO 那种小尺寸**。

作者：[zyl6932](https://github.com/zyl6932)

## 它做了什么

- **封面即壁纸**。封面按原比例满宽居中，上下用垂直镜像的自己填充再整体模糊（Apple 那种排版），
  直接替换 `com.miui.miwallpaper` 的 GL 纹理——所以时钟的液态玻璃折射、媒体卡和通知卡的模糊
  全都会正确采样到封面上，而不是穿帮。
- **时钟收起**。接管 OS4 的可变字体挤压通道，再叠一层 View scale，收到与 OPPO 等高；
  收起过程中液态玻璃连续填实，否则小字号只剩发丝线。缩放系数从每帧的 notifY 反推，
  所以由系统自己的弹簧驱动，没有一行自定义动画。
- **跟着媒体卡片自动开关**。锁屏出现媒体卡片就进，卡片关掉才退，**暂停不受影响**。
- **切歌自动换封面**，全程不需要杀壁纸进程，端到端约 150~230ms。

## 界面

miuix 写的 Compose 界面，四个页签：

| 页签 | 内容 |
|---|---|
| 主页 | 模块是否生效（直接问 SystemUI 里的钩子，不是猜的）+ 设备信息 |
| 功能 | 当前曲目、封面纵向位置、时钟缩放、玻璃强度、重启系统界面 |
| 设置 | 主题模式、悬浮导航栏、液态玻璃效果、背景模糊、语言、导入导出 |
| 关于 | 项目地址、反馈、Apache 2.0、第三方许可证 |

## 安装

1. 需要 HyperOS 4 / Android 17、Root、LSPosed。
2. 装上 APK，在 LSPosed 里启用，**作用域必须同时勾选「系统界面」和「壁纸」**。
3. 重启系统界面。
4. **锁屏必须「自己有一张」壁纸**——不是说要和桌面壁纸长得不一样，而是系统里得存在一条
   独立的锁屏壁纸记录（`FLAG_LOCK`）。共用一张时 MIUI 根本不会创建 keyguard 的壁纸渲染器，
   整套机制无处可挂。**内容可以和桌面完全相同**：模块的自动修复就是把桌面壁纸原样复制一份设成
   锁屏壁纸，肉眼看不出区别，功能照常工作。也可以在「功能 → 修复锁屏壁纸」手动触发。

## 调试

模块保留了完整的 adb 探针接口，见 `HANDOFF.md`：

```bash
adb shell am broadcast -a com.os4.musiccover.PROBE --es op state
adb shell am broadcast -a com.os4.musiccover.PROBE --es op auto --ez on true
adb shell am broadcast -a com.os4.musiccover.PROBE --es op bias --ef v 0.34
adb logcat -d | grep -E "MCProbe|MCWall"
```

## 致谢

- 界面（主题模式 / 悬浮导航栏 / 液态玻璃 / 背景模糊 / 多语言 / 关于页）大量参考并复用了
  [HyperNavBar](https://github.com/HyperNavBar/HyperNavBar) 的实现。
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 提供 Compose 组件。

## 许可证

[Apache License 2.0](LICENSE)
