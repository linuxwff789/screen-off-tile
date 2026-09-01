package com.screenoff.tile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：拦截音量键。
 * FLAG_REQUEST_FILTER_KEY_EVENTS 让 onKeyEvent 在系统处理之前被调用，
 * 返回 true 即"吞掉"事件 -> 系统不会调节音量。
 *
 * 注意：onKeyEvent 运行在系统输入分发线程，绝不能在这里执行 su 阻塞操作。
 * 因此状态判断只用 SharedPreferences（毫秒级），恢复操作在后台线程执行。
 */
public class KeyInterceptorService extends AccessibilityService {

    private final Handler bg = new Handler(Looper.getMainLooper());

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
            if (isVol && ScreenController.isScreenOffPrefsOnly(this)) {
                // 吞掉事件：系统不会调节音量
                final Context c = this;
                new Thread(() -> new ScreenController(c).turnScreenOn(),
                        "restore-from-a11y").start();
                return true;
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