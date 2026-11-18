# Lock-screen capsule spring expansion

Read with dexsql on 2026-11-15. The APKs are inputs only; no vendor code or resources are bundled by this change.

| Input | SHA-256 |
| --- | --- |
| `E:\workspace\colorSystemUI.apk` | `5677dadb2eae2e898aafe286b842b3885cbbb69b572099f52a984f6cfa01ee8e` |
| `E:\workspace\系统界面_17.03.260226.r.apk` | `007b17531c3e115f73c9661dec87674902cf98b9569da6ac2b2594bffd9e3363` |

## ColorOS evidence

Descriptors below are under `com.oplus.systemui.notification.lockscreen`, unless stated otherwise. DEX file IDs are dexsql's zero-based IDs in this session.

| Method | DEX / method index | Behavior |
| --- | --- | --- |
| `capsule.event.CapsuleScrollerVertical.scrollTo(F)` | 3 / 17967 | Accumulates finger distance and calls `COUISpringAnimation.animateToFinalPosition`; the capsule does not receive that distance directly. |
| `effect.ExtStateAnimParams$DraggerParams.<clinit>` | 3 / 18600 | Capsule drag defaults: 40dp maximum action, bounce 0, response 0.1 seconds. |
| `config.CapsuleConfig.loadConfig(Context,String)` | 3 / 18480 | Converts the capsule drag action to pixels with `dpToPx`. |
| `CapsuleScrollerVertical.access$scrollToDiff` | 3 / 17959 | At spring displacement / negative drag limit >= 1, cancels the tracking spring and calls `exchangeToStack(lastVSpringVelocity)`. This happens while the finger is still down. |
| `CapsuleScrollerVertical.onVerticalUp` | 3 / 17966 | Predicts `stackY + velocity * 0.03`. A fast upward release requires absolute velocity > 1000px/s and a negative projection whose magnitude reaches `SCROLL_UP_MAX_TRANSLATION`. The latter reads `StackToCapsule.DRAG_MAX_ACTION` directly (default 50, without dp conversion in this path). Otherwise springs home. |
| `effect.ExtStateAnimSinkingParams$CapsuleToStackParams.<clinit>` | 3 / 18688 | Translation defaults: bounce 0.16, base response 0.35 seconds, final response 0.45 seconds. Label explicitly describes the top card as the first mover. |
| `effect.ExtStateAnimSinkingParams$LevelParam.getSpringLevelInterpolator` | 3 / 18711 | Interpolates response and bounce by non-gone top-level row index. One row uses the base; two use base and midpoint; three or more interpolate over `index / (count - 1)`. There is no row start-delay in this path. |
| `effect.ExtStateAnimSinkingParams$LevelParam.midParam` | 3 / 18712 | Arithmetic midpoint of base/final bounce and response. |
| `effect.ExtStateAnimSinkingParams$SinkingLevelParam.getWidthInterpolator` | 3 / 18719 | Delegates to the same per-row Y interpolator. |
| `com.oplus.systemui.statusbar.notification.stack.OplusStackStateAnimatorExImpl.updatHeightAndWidthInterpolator` | 3 / 46024 | Capsule-to-stack selects these level parameters for `TRANSLATION_Y` and clip width. |
| `OplusStackStateAnimatorExImpl.initAnimationCustomInterpolator` | 3 / 46018 | Installs independent alpha, height, blur, corner and scale interpolators and applies the capsule handoff velocity. |
| `com.coui.appcompat.animation.dynamicanimation.COUISpringForce.setBounce` | 2 / 27865 | `dampingRatio = 1 - bounce`. |

The older-looking `ExtStateAnimParams$CapsuleToStack` block is not the final Y curve selected by `updatHeightAndWidthInterpolator`. Its bounce 0.3 / response 0.45 pair belongs to clip defaults; its alpha defaults are 0.11 / 0.3. Reading only that initializer would miss the level selection above.

## HyperOS integration points

Verified in the provided HyperOS APK:

- `NotificationContainerViewModel.enterNumState`, `exitNumState`, `notifStackStateToEvent`, `getKeyguardStackStatusInfo`, `positionWithoutMinScrollRange`, and `switchStackStatus` (DEX 2). The existing `NumState` hooks already use these.
- `NotificationStackScrollLayout.setOwnScrollY(I)` delegates to `(ZZZI)` (DEX 1 / 51324–51325). The latter publishes the scroll, requests child updates and informs the injector. It does not clamp to `getScrollRange`, so spring opening uses the existing bounded `NumState.listScroll()` result.
- `com.miui.systemui.widget.PropertiesFrameLayout` exposes `getSuper/setSuperTranslationX/Y`, `getSuper/setSuperScaleX/Y`, and alpha/transition-alpha equivalents (DEX 2). `setSuperTranslationY` preserves the logical property while writing its rendered value. These let the ordinary rows spring without feeding our writes into the OEM's layout target.
- `com.miui.systemui.notification.row.ExpandableViewBase.onPropertiesUpdate` (DEX 2 / 44808) computes `folmeAlpha * numStateAlpha`, then multiplies by logical alpha for `setSuperAlpha`; transition alpha is logical transition alpha times `getExtTransitionAlpha`. Ordinary rows temporarily bypass the NUMBER factor in the rendered product, then restore the current OEM calculation. Stored alpha fields are not replaced.

## Module behavior

`springExpand` is opt-in, survives configuration normalization/backup, and raises the settings schema to 10. Its UI lives in the lock-screen island notification section.

On an upward gesture, `IslandSpringPull` follows the finger with the critical drag spring. A rendered lift of 40dp commits the opening. A fast release uses the APK's separate projection rule. Before commitment, release/cancel returns the island. After handoff, `SpringSpreadSwipe` accepts further direction changes in the same gesture. Notification-island taps enter the same transition directly, with no drag threshold; the existing action that opens an app instead of expanding a single-app stack is retained.

With a notification list, `SpreadMotion` releases the row's islands, waits for the OEM rows, and places the native list at its bounded target. Island morphs and other notification rows then animate independently with the measured level responses. Notifications already in the ordinary list start at their current positions; folded notifications start within the touched island's width and height. The source position and folded state are captured before the pipeline changes them. A group is one top-level row. The module retains its existing island content/material morph. A lone music island uses the same threshold followed by its existing card/cover route.

`SpringSpreadRows` uses native render properties, not an extra animation matrix: the project's recorded ordinary-row glass failure under matrix transforms (#122) applies here. It tracks the last render write separately from the OEM target, reads new OEM writes when present, and restores the latest state on completion, detach, disable, sleep, unlock, or controller retirement. Symbols are resolved for each row; a failure releases only that row.

This is an adaptation to HyperOS's renderer, not a complete port of ColorOS's capsule renderer. Island content/material morphs remain the module's. Each active transition waits for its live springs to settle, with a 1.6-second limit after the latest retarget. The disabled setting retains the original gesture path.

## Recording follow-up: rows beyond the first three

The supplied `Screenrecorder-2026-11-15-01-16-38-900.mp4` shows later notifications appearing almost full-width around 4.2 seconds, before the first rows finish opening. The initial implementation collected only `VISIBLE` rows with positive rendered scale. It could therefore miss rows hidden by the OEM stack, and some retained a full-size origin because their `numStateAlpha` alone did not describe the fold.

dexsql confirmed the matching native rule in `ExpandableViewInjector.updateDimmingAndVisible(NotificationRowStackingInfo)` (DEX 1 / method 49429): stacking progress > 3 sets `shouldHideDueToStacking` and changes a visible ordinary row to `INVISIBLE`. When no longer stacked, the row becomes visible again if its view state is not hidden. The first expanded child of a group uses a threshold of 4. `NotificationStackScrollLayout.requestChildrenUpdate` (DEX 1 / 51260) only sets a flag and invalidates; the already-installed injector pre-draw runs the child/property updates before the module's listener.

The corrected plan includes every non-GONE top-level row, including temporarily invisible and unmeasured ones, and admits rows added during layout. Only the exact View owned by an island morph (or its containing group) is excluded from the ordinary-row animation: the selected island can correspond to the first, middle or last notification. Folded geometry uniformly fits the whole card into both capsule dimensions, avoiding a full-size start for taller rows. Each ordinary row starts its spring when its geometry is valid and it first becomes visible, so late visibility changes cannot skip its growth. A late row can retain its settling tail, with an overall 1.6-second limit.

## Collapse, content blur, and reversal

Further dexsql reads from the same ColorOS APK:

| Method | DEX / method index | Behavior |
| --- | --- | --- |
| `ExtStateAnimSinkingParams$StackToCapsuleParams.<clinit>` | 3 / 18724 | Same 0.16 bounce and 0.35–0.45 response endpoints as expansion. `updatHeightAndWidthInterpolator` selects `getYInterpolator(index,count,true)` for stack-to-capsule, reversing the level order. |
| `ExtStateAnimParams$StackToCapsule.<clinit>` | 3 / 18673 | Opacity uses bounce 0.11 / response 0.9; blur uses 0.11 / 0.4. The expansion counterparts use 0.11 / 0.3 for both. |
| `CapsuleUpdater.updateToExt` | 3 / 19943 | The capsule destination sets `blurRadio` to **70 raw pixels**. |
| `OplusExpandableViewExImpl.resetViewState` | 3 / 44964 | The ordinary row destination resets `blurRadio` to 0. |
| `OplusExpandableViewExImpl.setBlurRadio` | 3 / 44969 | Applies blur to content views, ignoring radius changes up to 2px except clearing at zero. |
| `OplusExpandableViewExImpl.setupBlurRadio` | 3 / 44989 | Builds `RenderEffect.createBlurEffect(radius,radius,CLAMP)` or clears it at zero. |
| `CapsuleItemView.capsuleBlurStep` | 3 / 17134 | Capsule content also changes focus: `70 * (1 - progress)`, cleared at progress >= 0.99. |
| `ExtStateTouchHandlerEx.onTouchEvent` | 3 / 20148 | After a state change, accumulates raw Y movement in one direction. Sign changes reset accumulation; at 100px it invokes the new swipe direction, suppressing repeated triggers in the same direction. |

`SpringSpreadState` stores position, velocity and target separately for geometry, opacity and sharpness. A reversal changes their targets and response values without resetting their current values or velocities. Both continuous reversal and a new gesture on a moving row are accepted. The original island's home remains a catch area during the transition, so a second gesture need not chase a moving card. `CardTouch` allows either vertical direction only for an animation already moving; ordinary list scrolling retains its existing top-of-list rule.

The HyperOS gesture adapter also accepts a release faster than 1000px/s as a direction choice after it has caught the drag. This fling affordance is an adaptation; ColorOS's continuous-swipe handler itself only uses the accumulated-distance rule and resets on release.

Collapse keeps the OEM list laid out until the rows reach their capsule positions, then sets NUMBER. A bounded 600ms handoff retains the ordinary rows at their folded pose while the native fold applies; a new opening, scene exit, configuration change or retirement releases that hold first. The captured upward lift also decays to zero on reversal so the island returns to its resting position.

The original `NumState.handsUp` override is bypassed while these springs run: the CANCEL sent to the OEM when a moving row is caught must not force NUMBER under them. If an OEM settling callback restarts scrolling, the active transition restores its reserved list scroll before drawing the next spring frame.

`NotificationContentBlur` applies the verified 70px range to notification templates, including grouped content, without blurring the wallpaper glass background. It uses DECAL rather than ColorOS's CLAMP because this project's existing morph recorded a rectangular edge smear with CLAMP. Island morphs receive the same independent blur progress. Completion, cancellation, disabled settings and retirement clear all effects installed by this transition.

## Recording follow-up: aggregate content, stacking, and repeated gestures

The recordings `VID_20261115_094455.mp4` and `VID_20261115_095146.mp4` show aggregate-island content remaining on a notification-sized frame, changing after the visible flight, and inconsistent overlap on collapse. The reviewed implementation had lost the independent opacity/sharpness inputs to `MiniCardMorph`, disabled its occlusion masks while `SpringSpreadRows` ran, and overwritten native stacking Z on every controller pre-draw. These paths now use one per-row pose and native drawing metadata.

Additional HyperOS evidence:

| Method | DEX / method index | Behavior |
| --- | --- | --- |
| `ExpandableViewBase.onPropertiesUpdate` | 2 / 44808 | Composes `folmeAlpha * numStateAlpha` for rendered alpha and `combineFolmeTranslationZ() + stackingTransZ` for external Z. Logical properties and rendered products are distinct. |
| `ExpandableViewInjector.applyViewStackingInfo` | 1 / 49423 | Applies stacking translation/scale through `setSuper*`, sets the native external bottom clip, and sets external Z. The module must preserve that metadata. |
| `ExpandableViewInjector$stackingOutlineProvider$1.getOutline` | 1 / 49419 | Uses `actualHeight - max(clipBottomAmount, extClipBottomAmount)`, subject to the native minimum clipping height. |
| `ExpandableNotificationRow.onFinishInflate` | 1 / 49020 | Holds separate private/public `NotificationContentView` subtrees and a lazily inflated children container. Row content cannot be treated as one background-free parent. |

Spring island-to-notification morphs use `NativeRowMorph` to place the native row through render properties, preserving its wallpaper glass while fading the island material during movement. This also applies the spring's opacity without the NUMBER-state alpha factor. It tracks OEM destinations separately from its last write and restores the latest destination on all exits. The existing matrix-based path remains for other morph types. The island and native content receive complementary blur from the same sharpness spring; the island's content crosses during the flight instead of waiting for a material handoff. Native blur radii compensate for the current rendered scale.

`NotificationBlurTargets` selects disjoint content subtrees. It includes standalone image/text views and grouped headers outside `NotificationContentView`, stops below selected template roots, and excludes notification backgrounds, fake shadows and guts. A following animation can take blur ownership without an older animation's cleanup clearing it.

`MorphStackLayer` reads actual native Z and bottom clipping after geometry writers have run. Ordinary notifications continue to occlude root-hosted island replicas while their springs run. Equal-Z order follows Android sibling order. In stacked island style, the current front/rear seats determine order near the capsule destination, blending back to native order toward the list. Only participating rows receive temporary rendered Z for this convergence; no native injector or Folme stacking fields are rewritten, and those render overrides are restored on exit. The root replica's content and material share its mask. Native geometry retains full card height separately from visible bottom clipping.

A new DOWN on a moving row or either island's home reserves the live transition before touch slop. It retains its springs while that gesture chooses direction. A reversal during the pipeline wait changes the pending destination rather than destroying the transition. New directions preserve geometry, opacity, blur and velocity. Release/cancel, configuration changes and scene exits release ownership.

## Closing capsule corners

`IMG_20261115_11035468.jpeg` shows a full-width rounded notification rectangle protruding around the front capsule near the end of a stacked-island collapse. The native row's parent outline alone does not shape its wallpaper glass.

dexsql confirms that `NotificationBackgroundViewInjectorImpl.ensureOutlineProvider` (DEX 1 / 49658) installs a separate background outline, disables ordinary outline clipping, and enables either the blur or glass outline on that background. Its `outlineProvider$1.getOutline` (DEX 1 / 49657) uses the notification radius from `RoundRectDimensCache`, with separate actual-height/bottom-clip calculations. That provider also updates the background RenderNode's height; replacing it without delegating would lose those updates.

`NotificationMorphClip` delegates to the current native provider for its RenderNode sizing, then supplies the same rounded shape used by the morphing row in the background's local coordinates. Its output stays a native `setRoundRect` outline. Native background clipping flags remain the OEM's, while the containing row owns the ordinary content clip. Grouped children retain their own material outlines. It reapplies after OEM property updates and restores the latest native outline and owned clipping flags on handoff, cancellation or scene exit. `NativeRowMorph` holds this through its closing opacity tail.

A short native card does not shorten the capsule's outline: the native RenderNode bounds and containing row constrain its pixels. Otherwise the reduced outline height also reduces its round corner, leaving pixels outside the taller island near its top corners.

Ordinary spring rows also interpolate from a capsule radius to their native notification radius. Their existing 600ms fold handoff retains that shape until the row is returned to SystemUI. Occlusion masks read the current rendered radius. The geometry tests sample folded outline boundaries against the island capsule and check a reversal preserves the current corner before returning to the native radius.

## Long notification lists

The list's resting scroll is derived from the native LIST destination, not the last idle user scroll. Previously `watchOpen` stored each stopped scroll as `cardsY`; the next DOWN then incorrectly appeared to begin at the top. It also had an OEM fallback that inferred a fold from movement alone, bypassing the touch classifier.

`SpreadListGesture` observes open-list gestures before dispatch, including touches in gaps between rows. A gesture begun away from the top, an upward read gesture, or a horizontal dismissal stays the list's through reversal, return to the top, and the OEM fling/hands-up callback. A new downward gesture begun at the top may fold. `NumState.handsUp` no longer applies the legacy pile-to-NUMBER shortcut to an open spread list. The spring reversal route remains available while a transition is actually moving.

## Independently displayed cards

`VID_20261115_113420.mp4` shows the notifications already folded around 3.33 seconds, while the independently displayed media card remains near its LIST position and only moves down afterward. The spring transition deliberately reserves the native LIST scroll until the fold completes. The independent media header was outside both the island morphs and the ordinary-row springs, so it waited for the final NUMBER scroll and then ran a second OEM animation.

dexsql confirms `NotificationContainerViewModel.calculateNotifTop` (DEX 2 / 45858) returns `base - scrollY + overScroll`. `MiuiMediaHeaderView.<init>` (DEX 1 / 47877) calls the `ExpandableView` constructor, so its rendered translation can be controlled through the same `PropertiesFrameLayout` API as the notification rows. Its native ViewState destination is read through the existing `mYTranslation` integration.

`SpringSpreadCompanions` captures visible media/focus cards outside the island transition before the list changes. It moves only their rendered Y, using the spread's geometry spring between the recorded folded placement and the live native LIST destination. The folded bottom is recorded on screen and converted back into the current parent's coordinates every frame, so container movement cannot shift the endpoint; it remains the anchor if the card's content height changes. These views are excluded from the ordinary shrink/opacity/blur path, and their current top is used in the stack's clock-room measurements. Music that belongs to an island morph remains that morph's responsibility.

On an open list the position override is released, retaining only the folded endpoints for a future collapse. During collapse it reaches home alongside the other rows; the existing bounded 600ms handoff holds that endpoint until the native Y catches up. A new opening can reuse a pending handoff without briefly restoring the old Y. Configuration changes, detach, sleep/scene exit and retirement release the override. No extra persistent frame loop is introduced.

## Transient hiding and glass outline regression

`Screenrecorder-2026-11-15-12-04-21-710.mp4` shows lower ordinary rows missing during the opening flight, then arriving almost fully sized, and vanishing at the start of collapse. Being present in the animation plan was insufficient: `ExpandableViewInjector.updateDimmingAndVisible` (DEX 1 / 49429) can still set these rows `INVISIBLE` while their native stacking progress exceeds 3. `SpringRowVisibility` temporarily overrides only this documented stacking hide when the ViewState itself is not hidden. It runs before the row's first spring frame and before drawing subsequent frames, and returns visibility to the current native state on release. It does not override GONE, unrelated invisibility, or ViewState hiding.

The same recording exposed a material regression in the previous corner/occlusion fix. Intersecting round rectangles as `Path` objects and passing them to `Outline.setPath` removed the outline's native rectangle/radius representation. That was also being passed to the island's material layer, and the background clip flag was forced to true even though `ensureOutlineProvider` (DEX 1 / 49658) explicitly sets it false for the native glass renderer. Moving notifications changed to a flatter tinted appearance.

Material outlines now use `setRoundRect` throughout the transition, with native background clip flags preserved. The island's overlap mask belongs to the containing view and draw clip; its material layer receives a separate rounded-rectangle outline. Ordinary-row clipping blends the native bottom clip with spring progress, and occlusion reads the same visible height and radius. The native material effect/recipe is not replaced or replayed each frame. Rounded corners remain in place during the closing tail without converting the glass shape to a generic path.

## Validation

`IslandSpringMotionTest` covers drag lag, commitment while held, reversal/cancel after commitment, short/cancelled pulls, fling speed/projection, 60/120/240Hz agreement, response ordering, overshoot/settling, and render writes not becoming future OEM destinations. `MiniPlayerConfigTest` covers opt-in defaults and persistence.

`SpringSpreadPlanTest` covers seven rows with only the first three visible, every possible selected-island row, grouped children, layout arrivals, uniform fitting into both pill and small-island dimensions, and missing geometry.

`SpringSpreadStateTest` checks equivalence with the former opening curve, position/velocity continuity on reversal, repeated fast reversals, independent blur/opacity curves, late-visible rows, reversed collapse ordering, the 100px continuous-swipe rule and cancellation.

Follow-up tests cover content crossing before geometry settles, scaled blur, template/group/icon blur coverage, native-Z and equal-Z order, ordinary-row occlusion during springs, swapped stacked-island seats, and long-list gesture ownership across return to the top and fling completion.

`SpringCompanionPositionTest` covers independent-card selection, concurrent return before the notification springs settle, delayed native writes during handoff, direction reversal, reopening and content-height changes. Host glass and lyric placement still require visual device review.

`SpringRowVisibilityTest` covers native stacking repeatedly hiding a moving row, the first rendered frame of a formerly invisible row, respecting removal/other hiding, and returning to the current native visibility after completion or cancellation.

Build with `testDebugUnitTest assembleRelease lintDebug`. Device review should cover both island styles, 1/2/many notifications, grouped notifications, the ordinary-notifications-in-list setting, threshold hold/reversal/cancel, opening into a shrinking clock, and interruption by AOD/unlock/config changes. Static APK inspection and unit tests do not establish visual equivalence or glass behavior on a live device.
