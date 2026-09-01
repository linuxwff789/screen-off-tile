package com.screenoff.tile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/**
 * Core control logic:
 *  - Turn off screen backlight (/sys/class/backlight/panel0-backlight/bl_power)
 *  - Disable touch (inhibited attribute of input device)
 *  - Keep system awake (PARTIAL_WAKE_LOCK + svc power stayon true), no lock, no app kill
 *  - Requires root
 */
public class ScreenController {
    private static final String PREFS = "screenoff";
    private static final String KEY_OFF = "is_off";

    private final Context app;
    private final SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread volMonitor;

    public ScreenController(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isRootAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行一段 root shell 脚本，返回输出 */
    private String runRoot(String script) {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(script + "\nexit\n");
            os.flush();
            os.close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "ERR:" + e;
        }
    }

    /** 关闭屏幕：背光灭 + 触摸禁用 + 保持唤醒 */
    public boolean turnScreenOff() {
        String script =
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 1 > \"$BL\"\n" +
            "for d in /sys/class/input/input*/; do\n" +
            "  n=$(cat \"$d/name\" 2>/dev/null)\n" +
            "  case \"$n\" in\n" +
            "    *goodix*|*Goodix*|*synaptics*|*Synaptics*|*touchscreen*|*Touch*|*tsc*|*himax*|*novatek*|*focal*|*fts*|*silead*|*raydium*|*ektf*|*ilitek*|*chipone*|*egalax*|*aw8*|*ist*|*lcd*)\n" +
            "      echo 1 > \"$d/inhibited\" 2>/dev/null\n" +
            "      ;;\n" +
            "  esac\n" +
            "done\n" +
            "echo DONE_OFF\n";
        String out = runRoot(script);
        if (out.contains("DONE_OFF")) {
            prefs.edit().putBoolean(KEY_OFF, true).apply();
            acquireWakeLock();
            startVolumeMonitor();
            return true;
        }
        return false;
    }

    /** 恢复屏幕：背光亮 + 触摸启用 */
    public boolean turnScreenOn() {
        String script =
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 0 > \"$BL\"\n" +
            "for d in /sys/class/input/input*/; do\n" +
            "  n=$(cat \"$d/name\" 2>/dev/null)\n" +
            "  case \"$n\" in\n" +
            "    *goodix*|*Goodix*|*synaptics*|*Synaptics*|*touchscreen*|*Touch*|*tsc*|*himax*|*novatek*|*focal*|*fts*|*silead*|*raydium*|*ektf*|*ilitek*|*chipone*|*egalax*|*aw8*|*ist*|*lcd*)\n" +
            "      echo 0 > \"$d/inhibited\" 2>/dev/null\n" +
            "      ;;\n" +
            "  esac\n" +
            "done\n" +
            "svc power stayon false 2>/dev/null\n" +
            "echo DONE_ON\n";
        String out = runRoot(script);
        releaseWakeLock();
        stopVolumeMonitor();
        prefs.edit().putBoolean(KEY_OFF, false).apply();
        return out.contains("DONE_ON");
    }

    public boolean isScreenOff() {
        // 以持久化状态为主，兜底读取真实背光
        String bl = runRoot("ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1 | xargs cat 2>/dev/null; echo");
        boolean realOff = bl != null && bl.trim().equals("1");
        return prefs.getBoolean(KEY_OFF, false) || realOff;
    }

    /**
     * 仅读 SharedPreferences 判断是否处于关屏状态（毫秒级，不执行 su）。
     * 供无障碍 onKeyEvent（系统输入线程）使用，避免阻塞。
     */
    public static boolean isScreenOffPrefsOnly(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OFF, false);
    }

    /** 保持 CPU 唤醒，防止系统休眠（应用持续运行） */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenoff:tile");
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    /** 监听音量键作为“恢复”快捷键（触摸已被禁用时的唯一物理入口） */
    private void startVolumeMonitor() {
        stopVolumeMonitor();
        final ScreenController self = this;
        final Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", "getevent").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = r.readLine()) != null) {
                    // 音量键 code: 0x72=VOLUMEDOWN 0x73=VOLUMEUP，按下 value=1
                    if ((line.contains(" 0072 ") || line.contains(" 0073 ")) && line.trim().endsWith("00000001")) {
                        p.destroy();
                        self.turnScreenOn();
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }, "vol-monitor");
        t.setDaemon(true);
        volMonitor = t;
        t.start();
    }

    private void stopVolumeMonitor() {
        Thread t = volMonitor;
        if (t != null) {
            t.interrupt();
            volMonitor = null;
        }
    }
}
