# HyperMusicCover 定制版更新日志

## 0.5.1-custom.8-flow-cards-fix（versionCode 509）

- 修复 `custom.7` 中卡片流光正常、整屏流光却变为纯黑的问题：共用绘制器的根层调用误将新增的遮黑参数设为 100%，现由根层专用入口固定为 0%。
- 媒体卡与通知卡继续同步实时流光；歌词页仍通过原有独立绘制层渐进压暗，不改变卡片覆盖率或壁纸进程。

验证：流光场景定向测试和 Release 构建通过；APK 元数据为 `0.5.1-custom.8-flow-cards-fix`／`509`，`apksigner verify` 通过（v2 签名）。APK 使用本地 Android Debug 证书；目标 HyperOS 仍需目视确认整屏流光、歌词及卡片最终合成效果。

## 0.5.1-custom.7-flow-cards（versionCode 508）

- 卡片样式的动态流光现在同步绘制在展开后的原生媒体卡和锁屏通知卡背景上：共享同一封面处理结果、动画时间与换曲过渡，按卡片当前形变位置取流光颜色，不逐帧截图。
- 卡片流光以最高 65% 不透明度叠在原生材质上，保留玻璃染色和轮廓；歌词压暗、进入/退出进度与底层流光同步。
- 关闭流光、进入息屏、解锁、卡片脱离或着色器不可用时移除叠层并恢复原生背景。无法识别的通知背景跳过并限量记录日志。

验证：`CoverFlowSceneTest` 与 `FlowCardGeometryTest` 通过，Release 构建通过；APK 元数据为 `0.5.1-custom.7-flow-cards`／`508`，`apksigner verify` 通过（v2 签名）。APK 使用本地 Android Debug 证书；目标 HyperOS 的最终卡片合成效果仍需实机目视确认。

## 0.5.1-custom.6-flow-fixes（versionCode 507）

- 修复音乐锁屏流光背景进入、退出时的突现：透明度跟随卡片进度，首次着色延迟完成时渐显；息屏继续跟随卡片明暗过渡。
- 卡片样式的锁屏歌词页延续同一专辑流光，按歌词显示进度平滑叠加最多 40% 黑色压暗。
- 将流光层放在快捷方式圆盘背景下方，修复开启背景时圆盘消失；视频壁纸的静态底图与流光渐变配合。
- 软件画布截图不再将流光渲染标记为永久失败；增加场景、首次着色、歌词压暗及图层附着的限量诊断日志。

验证：`CoverFlowSceneTest` 4 项通过，Release 构建通过；APK 元数据为 `0.5.1-custom.6-flow-fixes`／`507`，`apksigner verify` 通过（v2 签名）。目标 HyperOS 设备的最终合成视觉效果仍需实机目视复测。

APK 使用本地 Android Debug 证书签名，包名保持 `com.github.zyl6932.HyperMusicCover`。

## 0.5.1-custom.5-flow（versionCode 506）

- 卡片样式音乐锁屏新增专辑封面取色动态流光背景；默认仍为静态模糊，完整封面样式不启用流光。
- 增加 Apple Music 风格、柔和氛围和鲜明律动三档预设，以及强度、速度、模糊次数调节。换曲时流光与封面同步交叉淡化，暂停时流动逐渐停下；设置页预览同步显示。
- 流光设置随 SystemUI 状态保存、广播查询和设置备份传递；旧配置与旧备份保持静态模糊。着色器未准备好或渲染失败时使用现有静态壁纸。
- 引入 Kawarp-AGSL 的 AGSL 引擎源码及其 LGPL-3.0、原版 Kawarp MIT 声明；没有新增 JitPack 依赖。

验证：`CoverFlowConfigTest` 4 项通过，Release 构建通过；APK 元数据为 `0.5.1-custom.5-flow`／`506`，`apksigner verify` 通过（v2 签名）。目标 HyperOS 设备视觉效果尚未实测；原生玻璃组件可能仍从底层静态壁纸取模糊样本。

APK 使用本地 Android Debug 证书签名，包名保持 `com.github.zyl6932.HyperMusicCover`。

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
