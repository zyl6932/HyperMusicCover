package com.os4.musiccover;

import android.net.Uri;

final class PickupConst {
    static final String MODULE_PACKAGE = "io.github.mio.autopickupisland";
    static final String PROVIDER_AUTHORITY = MODULE_PACKAGE + ".bridge";
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY);

    static final String PKG_WECHAT = "com.tencent.mm";
    static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    static final String PKG_AICR = "com.xiaomi.aicr";
    static final String PKG_VOICE_ASSIST = "com.miui.voiceassist";
    static final String PKG_PRIVILEGED_ASSIST = "com.xiaomi.aiasst.service";
    static final String PKG_SYSTEM_UI = "com.android.systemui";
    static final String PKG_TEST_FIXTURE = "io.github.mio.autopickupfixture";
    static final String ACTION_EVENT = MODULE_PACKAGE + ".ACTION_PICKUP_EVENT_V1";
    static final String ACTION_SYSTEM_EVENT = MODULE_PACKAGE + ".ACTION_SYSTEM_EVENT_V1";
    static final String ACTION_CAPTURE_REQUEST = MODULE_PACKAGE + ".ACTION_CAPTURE_REQUEST_V1";
    static final String ACTION_CONFIG_CHANGED = MODULE_PACKAGE + ".ACTION_CONFIG_CHANGED_V1";
    static final String ACTION_CLEAR_STATE = MODULE_PACKAGE + ".ACTION_CLEAR_STATE_V1";
    static final String ACTION_CLEAR_CLOSED = MODULE_PACKAGE + ".ACTION_CLEAR_CLOSED_V1";
    static final String ACTION_INGRESS = MODULE_PACKAGE + ".ACTION_INGRESS_V1";
    static final String ACTION_ROUTE = MODULE_PACKAGE + ".ACTION_ROUTE_V1";
    static final String ACTION_ROUTE_REQUEST = MODULE_PACKAGE + ".ACTION_ROUTE_REQUEST_V1";
    static final String ACTION_WAKE = MODULE_PACKAGE + ".ACTION_WAKE_AICR_V1";
    static final String ACTION_NATIVE_PUBLISH =
            MODULE_PACKAGE + ".ACTION_VOICEASSIST_NATIVE_PICKUP_V1";
    static final String ACTION_DISMISS = MODULE_PACKAGE + ".ACTION_DISMISS_PICKUP_V1";
    static final String MODULE_RECEIVER = MODULE_PACKAGE + ".BridgeReceiver";
    static final String AICR_WAKE_RECEIVER = "com.xiaomi.aicr.receiver.AICRReceiver";
    static final String VOICE_ASSIST_WAKE_RECEIVER =
            "com.xiaomi.voiceassistant.receiver.LaunchMainPidReceiver";
    static final String PRIVILEGED_ASSIST_RECEIVER =
            "com.xiaomi.aiassistant.common.util.receiver.SystemEventReceiver";
    static final String AICR_OPERATION_SERVICE =
            "com.xiaomi.aireco.focus.notify.action.AirecoOperationService";
    static final String AICR_OPERATION_ACTION = "com.xiaomi.aicr.aireco.OperationService";

    static final String EXTRA_EVENT_JSON = "event_json";
    static final String EXTRA_EVENT_ID = "event_id";
    static final String EXTRA_SOURCE_OPEN_INTENT = "source_open_intent";
    static final String EXTRA_CONTENT_INTENT = "native_content_intent";
    static final String EXTRA_CODE = "pickup_code";
    static final String EXTRA_PRODUCT = "pickup_product";
    static final String EXTRA_DETAIL = "pickup_detail";
    static final String EXTRA_MODEL = "pickup_model";
    static final String EXTRA_ENGINE = "pickup_engine";
    static final String EXTRA_NOTIFICATION_TAG = "pickup_notification_tag";
    static final String EXTRA_NOTIFICATION_ID = "pickup_notification_id";
    static final String EXTRA_SOURCE_PACKAGE = "source_package";
    static final String EXTRA_SOURCE_ACTIVITY = "source_activity";
    static final String EXTRA_OBSERVED_AT = "observed_at";
    static final String EXTRA_CONTENT_CHANGED = "content_changed";
    static final String RULE_ASSET = "coloros/sys_aifluid_config_list.xml";
    static final String TEST_RULE_ASSET = "coloros/test_fixture_rules.xml";
    static final String PCR_PLUGIN_ASSET = "coloros/pcr_plugin.apk";
    static final String IMAGE_ROOT = "coloros/pickupcode/";
    static final String BUILTIN_RULE_VERSION = "20260225";

    static final long EVENT_MAX_AGE_MS = 10 * 60_000L;
    static final long NAV_MAX_AGE_MS = 2 * 60 * 60_000L;
    static final int MAX_CONTENT_LENGTH = 40_000;

    static boolean isSourcePackage(String packageName) {
        return switch (PickupEvent.clean(packageName)) {
            case PKG_WECHAT, PKG_ALIPAY, PKG_TEST_FIXTURE,
                    "com.lucky.luckyclient", "com.heyteago", "com.mxbc.mxsa",
                    "com.yek.android.kfc.activitys", "com.mcdonalds.gma.cn",
                    "com.starbucks.cn" -> true;
            default -> false;
        };
    }

    private PickupConst() {
    }
}
