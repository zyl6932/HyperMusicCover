package com.os4.musiccover;

import android.view.MotionEvent;
import android.view.View;
import com.os4.musiccover.island.MiniPlayerRuntime;

/**
 * Where a swipe up on the lock screen unlocks rather than scrolls the notification stack -
 * corrected for the cards nobody can see (issue #101).
 *
 * On the keyguard the system keeps one band, NotificationStackScrollLayoutInjector.scrollableRegion
 * = (notificationTop, bounds.bottom), and a touch that lands in it belongs to the stack: the stack
 * scrolls (STACK opens into LIST) and the panel does not unlock. notificationTop is
 * bounds.top - scrollY + overScroll (MiuiKeyguardNotificationInteractor.notificationTop ->
 * NotificationContainerViewModel.calculateNotifTop): the laid-out top of the stack's first child,
 * seen or not. The media card the pill stands in for is that first child, kept INVISIBLE and
 * still laid out (MiniPlayerController.updateHeader), so its slot - most of the screen between
 * the clock and the rows - took swipes for the stack while showing nothing (filmed 2026-10-20:
 * from 40% to 70% of the height, a swipe up spread the stack).
 *
 * The band is read in two places, both hooked here: the stack's own
 * NotificationStackScrollLayoutInjector.isInScrollableRegion (its intercept and its scroll), and
 * NotificationPanelViewController.isInContentBounds$2, which on the keyguard answers "not on the
 * notifications" above the band's top - the panel's go-ahead to unlock. Both compare the band to
 * a y in the stack's own coordinates (the panel uses the same figure the stack does), so the
 * corrected top is worked out once, in those coordinates.
 *
 * Only ever turns the system's yes into a no, and only above the top of what is drawn
 * (MiniPlayerRuntime.stackContentTop, the figure the clock already gives way to) less a small
 * margin, so a finger landing just above the first row still takes the stack. Unknown - the mini
 * player is off, the stack not measured - or no row showing at all (MAX_VALUE: folded into
 * "N个通知", or everything in islands) leaves the system's answer alone; those are not this issue
 * and the folded state has touch handling of its own. A drag already under way is the stack's
 * either way: onScrollTouch asks the band only while not yet dragging.
 */
public final class UnlockSwipeRegion {
    private UnlockSwipeRegion() {}

    private static final String CLS_INJECTOR =
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutInjector";
    private static final String CLS_PANEL = "com.android.systemui.shade.NotificationPanelViewController";

    /** Above the first drawn row, still the stack's: a finger a little high should not unlock. */
    private static final float MARGIN_DP = 12f;

    /** The last decision, for the log: system band top, our cut, the y asked about, the answer. */
    private static volatile String sLast = "-";

    static void install(ClassLoader cl) {
        try {
            Class<?> injector = Xp.findClass(CLS_INJECTOR, cl);
            Xp.hookAll("sysui.unlock_region.scrollable", injector, "isInScrollableRegion", chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                // On the touch path of the whole lock screen: whatever goes wrong here, the
                // system's answer stands.
                try {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length != 1 || !(args[0] instanceof MotionEvent)) return result;
                    View stack = (View) Xp.getObjectField(chain.getThisObject(), "layout");
                    float y = ((MotionEvent) args[0]).getY();
                    return aboveDrawnRows(stack, y, "nssl") ? Boolean.FALSE : result;
                } catch (Throwable t) {
                    return result;
                }
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "unlock region (stack) hook failed: " + t);
        }
        try {
            Class<?> panel = Xp.findClass(CLS_PANEL, cl);
            Xp.hookAll("sysui.unlock_region.content_bounds", panel, "isInContentBounds$2", chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                try {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length != 2 || !(args[1] instanceof Float)) return result;
                    Object self = chain.getThisObject();
                    // The system bounds the band from above on the keyguard only; so do we.
                    Object barState = Xp.getObjectField(self, "mBarState");
                    if (!(barState instanceof Integer) || (Integer) barState != 1) return result;
                    Object controller = Xp.getObjectField(self, "mNotificationStackScrollLayoutController");
                    Object nsslInjector = Xp.getObjectField(controller, "mNsslControllerInjector");
                    View stack = (View) Xp.getObjectField(nsslInjector, "layout");
                    return aboveDrawnRows(stack, (Float) args[1], "panel") ? Boolean.FALSE : result;
                } catch (Throwable t) {
                    return result;
                }
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "unlock region (panel) hook failed: " + t);
        }
    }

    /**
     * Whether y, in the stack's coordinates, is above what the stack draws by more than the
     * margin - blank lock screen the system still counts as the stack's.
     */
    private static boolean aboveDrawnRows(View stack, float y, String who) {
        try {
            if (stack == null || !stack.isAttachedToWindow() || !Main.onKeyguardNow()) return false;
            float top = MiniPlayerRuntime.stackContentTop();
            if (Float.isNaN(top) || top == Float.MAX_VALUE || Float.isInfinite(top)) return false;
            int[] at = new int[2];
            stack.getLocationOnScreen(at);
            float margin = MARGIN_DP * stack.getResources().getDisplayMetrics().density;
            float cut = top - at[1] - margin;
            boolean blank = y < cut;
            String line = who + " y=" + (int) y + " cut=" + (int) cut + " top=" + (int) top
                    + " stackY=" + at[1] + " -> " + (blank ? "unlock" : "stack");
            if (!line.equals(sLast)) {
                sLast = line;
                Xp.d("MCUnlockRegion: " + line);
            }
            return blank;
        } catch (Throwable t) {
            return false;
        }
    }

    static String describe() {
        return sLast;
    }
}
