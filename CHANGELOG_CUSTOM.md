# HyperMusicCover 定制版更新日志

## 0.5.1-custom.2（versionCode 503）

- 修复系统自动和柔光玻璃模式下锁屏岛、快捷圆盘的重复圆角裁剪，使原生玻璃效果只在外层轮廓裁剪一次。
- 修复自定义息屏显示时锁屏岛、迷你播放器及快捷功能残留：仅全屏 AOD 延续底部组件显示，自定义息屏交还系统原生快捷按钮的可见性控制，亮屏后恢复锁屏布局。
- 增加自定义息屏与过渡状态的显示策略测试。厂商玻璃边缘和实际息屏画面仍需在目标 HyperOS 设备上目视确认。

验证：`MiniPlayerPresentationPolicyTest`、`MiniMaterialConfigTest` 和 `:app:assembleRelease` 均通过；APK 元数据为 `0.5.1-custom.2`／`503`，`apksigner verify` 通过。

APK 使用 Android Debug 证书签名，包名保持 `com.github.zyl6932.HyperMusicCover`；不同签名的既有安装不能直接覆盖。

## 0.5.1-custom.1（versionCode 502）

- 新增锁屏岛、迷你播放器与快捷按钮的系统自动、纯色、高级材质、柔光玻璃背景模式和相关参数。
- 旧版 `minicfg` 自动补齐材质设置，并沿用现有广播、持久化及设置备份。
- 材质参数和 Xiaomi API 调用参考 HyperChanger 1.1.3，来源与许可见 `NOTICE`。
