package com.screenoff.tile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：拦截音量键。
 * FLAG_REQUEST_FILTER_KEY_EVENTS 让 onKeyEvent 在系统处理之前被调用，
 * 返回 true 即"吞掉"事件 -> 系统不会调节音量，我们在这里触发恢复屏幕。
 */
public class KeyInterceptorService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 0;
        setServiceInfo(info);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            boolean isVol = code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN;
            if (isVol) {
                ScreenController sc = new ScreenController(this);
                if (sc.isScreenOff()) {
                    // 吞掉事件，防止音量被调节，并触发恢复
                    sc.turnScreenOn();
                    return true;
                }
            }
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    /** 无障碍服务是否已启用 */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String flat = new ComponentName(context, KeyInterceptorService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(flat)) return true;
        }
        return false;
    }
}
