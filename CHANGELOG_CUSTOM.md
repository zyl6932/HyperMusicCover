# HyperMusicCover 定制版更新日志

## 当前已知问题（0.5.1-custom.4 目标设备复测）

- **系统自动／柔光玻璃的锁屏组件圆角锯齿仍未解决。** `custom.4` 的原生轮廓与 SDF 调整已进入源码和 APK，但目标设备复测仍出现锯齿；不能将本版视为该问题的修复版。状态变化及前几版尝试见 `docs/glass-edge-handoff.md`。
- `custom.2` 的自定义息屏组件残留修复已由目标设备复测确认有效。

## 0.5.1-custom.4（versionCode 505）

- 修正玻璃材质的原生轮廓状态：媒体卡材质回放后恢复迷你播放器和快捷圆盘自己的轮廓，重新启用 HyperOS 玻璃轮廓标志，并按各材质视图的实际像素尺寸更新 SDF 上限。尺寸形变时同步更新。
- 撤回 `custom.3` 的 Canvas 遮罩和额外模糊容器。它们未触及原生玻璃的轮廓与 SDF 状态，在目标设备上未解决锯齿。
- 自定义息屏组件隐藏逻辑延续 `custom.2`。

APK 仍使用 Android Debug 证书签名，包名保持 `com.github.zyl6932.HyperMusicCover`。

后续确认：目标设备复测显示玻璃边缘锯齿依旧存在；本版的轮廓与 SDF 调整未修复该问题。

## 0.5.1-custom.3（versionCode 504）

- 修复息屏亮屏、解锁上滑和迷你播放器交互后，系统自动与柔光玻璃材质的圆角锯齿反复出现：将模糊容器纳入组件的合成结果，再按当前尺寸和圆角加入抗锯齿遮罩，遮罩随形变同步更新。
- 纯色、高级材质及自定义息屏显示策略延续 `0.5.1-custom.2` 的行为。

验证：执行显示策略与材质配置的针对性单元测试及一次 Release 构建。HyperOS 原生玻璃的实际边缘仍需在目标设备上目视验收。

APK 使用 Android Debug 证书签名，包名保持 `com.github.zyl6932.HyperMusicCover`。

后续确认：该版本在目标设备上未解决玻璃圆角锯齿，见 `0.5.1-custom.4`。

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
