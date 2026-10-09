package com.os4.musiccover;

/**
 * The back screen's allow-list, and the pickup card's way onto it.
 *
 * `com.xiaomi.subscreencenter` draws a card on the back screen for the packages its own list
 * names - a package and a business together, out of a resource of its own
 * (`<app pkg="..." business="..." group="...">`): 美团 and 淘宝 are `foodDelivery` there, 小爱 is
 * `memory`, and SystemUI is not in it at all. Our pickup notification is posted by SystemUI,
 * because the module lives in that process, so the card reaches the app, is logged, and is
 * dropped before anything is drawn:
 *
 *     postNotification: Package=com.android.systemui, NotificationId=1241, Key=0|com.android.systemui|...
 *     handleNotificationPosted: Package=com.android.systemui, ID=1241
 *     (nothing - no widget for it, while 小爱's own card is built and removed by compositeKey)
 *
 * That list is a compiled resource inside a signed apk, so the only place to say otherwise is in
 * the app's own process: the answer to "may this package draw a widget" is given here, for our
 * package alone.
 *
 * What the app then does with the notification is the pickup card's business, not this file's -
 * `miui.rear.rv` is the card it draws, and [PickupCard] is where the card is built the way a
 * process other than ours can inflate it.
 *
 * The names below are R8's, read off subscreencenter's dex on 2026-10-09 - they move with every
 * release of that app. A lookup that misses costs the card on the back screen and nothing else,
 * which is why each hook is allowed to fail on its own.
 */
final class RearScreen {
    private static final String TAG = "MCRear: ";

    /** Our notification's package. The list has no entry for it; this is the whole problem. */
    private static final String OURS = "com.android.systemui";

    private RearScreen() {
    }

    static void handle(ClassLoader cl) {
        Class<?> resolver;
        try {
            resolver = Xp.findClass("m2.d", cl);
        } catch (Throwable t) {
            Xp.log(TAG + "resolver class not found, back screen will not show the card: " + t);
            return;
        }
        // The two that ask "may this package's widget be built" and hand back a boolean: the
        // (String, String, map) around a LiveUpdate business, and the (String, Set, map) ours goes
        // through. Nothing here names a template or a widget - the answer is only ever yes or no,
        // which is why saying yes is enough.
        allow(resolver, "l");
        allow(resolver, "m");
        // `n`, `o` and `p` are deliberately left alone, though this once answered yes to all five:
        //
        //   n(String)           "is this a quick-access package". `m` asks it internally, so a yes
        //                       here made the app log a block it then had to be talked out of.
        //   o(String, String)   "is this widget protected" - the answer that makes the app *ignore
        //                       a removal*. A yes left every card we ever posted on the back screen
        //                       for good, and had it written into `notification_widget.json` on the
        //                       way, where a restart restores it with no RemoteViews in it - the
        //                       black box. Cards now go out under an id each (PickupCodeIsland's
        //                       ID), and the removal of the one before is what clears its widget,
        //                       so this must answer as the app means it: no.
        //   p(String)           a business (the ringing ones), never a package - it never saw ours.
        Xp.log(TAG + "allow-list hooks in place for " + OURS);
        startTheDrink();
    }

    /**
     * The drink, started.
     *
     * The card's picture is an animated WebP - ColorOS's cup filling - and the platform hands it
     * back as an `AnimatedImageDrawable` that is not running: `ImageDecoder`'s own contract says
     * the caller starts it, and neither `ImageView` nor anything in the RemoteViews path does. The
     * island's copy of the card is started by the module running in SystemUI, which is the process
     * that builds it; this card is *applied here*, so nothing would start this one and the cup
     * would hold its first frame - half full - for as long as the card is up.
     *
     * `setImageIcon` is the one call `RemoteViews.setImageViewIcon` makes, which is why it is what
     * to watch, and the layout's own id is how the two cards are told apart. Guarded throughout:
     * this runs inside the host's own method, and an exception here takes the card down with it -
     * and this app applies its cards on the main thread, so it takes the app down with it too.
     */
    private static void startTheDrink() {
        try {
            Xp.hookAll(android.widget.ImageView.class, "setImageIcon", chain -> {
                Object out = chain.proceed();
                Object view = chain.getThisObject();
                if (view instanceof android.widget.ImageView
                        && ((android.widget.ImageView) view).getId() == R.id.mc_pickup_rear_icon) {
                    try {
                        android.graphics.drawable.Drawable drawable =
                                ((android.widget.ImageView) view).getDrawable();
                        if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                            ((android.graphics.drawable.AnimatedImageDrawable) drawable).start();
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "drink not started: " + t);
                    }
                }
                return out;
            });
            Xp.log(TAG + "watching the card's picture");
        } catch (Throwable t) {
            // The card then holds its first frame. Half a cup is a worse card, not a broken one.
            Xp.log(TAG + "no picture to start: " + t);
        }
    }

    private static void allow(Class<?> resolver, String name) {
        try {
            Xp.hookAll(resolver, name, chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean ours = args.length > 0 && OURS.equals(args[0]);
                Object result = chain.proceed(args);
                if (ours) {
                    Xp.log(TAG + name + "(" + args[0]
                            + (args.length > 1 ? ", " + args[1] : "") + ") was " + result
                            + ", answered true");
                }
                return ours ? Boolean.TRUE : result;
            });
        } catch (Throwable t) {
            // A name that is not there any more is the expected way this ages: the card simply
            // does not appear on the back screen.
            Xp.log(TAG + "no " + name + "() to hook: " + t);
        }
    }
}
