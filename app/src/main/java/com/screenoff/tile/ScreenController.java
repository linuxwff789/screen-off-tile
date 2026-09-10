package com.screenoff.tile;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
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

    /** 触摸屏 input 设备的 name 匹配模式（用于 case 语句） */
    private static final String TOUCH_MATCH =
        "*goodix*|*Goodix*|*synaptics*|*Synaptics*|*touchscreen*|*Touch*|*tsc*" +
        "|*himax*|*novatek*|*focal*|*fts*|*silead*|*raydium*|*ektf*|*ilitek*" +
        "|*chipone*|*egalax*|*aw8*|*ist*|*lcd*";

    /** root 侧 watchdog 脚本 / PID 文件 / stayon 备份文件 */
    private static final String WATCHDOG_SCRIPT = "/data/local/tmp/.screenoff_watchdog.sh";
    private static final String WATCHDOG_PID = "/data/local/tmp/.screenoff_watchdog.pid";
    private static final String STAYON_FILE = "/data/local/tmp/.screenoff_stayon";

    private final Context app;
    private final SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread volMonitor;
    /** 监听屏幕自动亮起（如按电源键），自动恢复触摸 */
    private final android.content.BroadcastReceiver screenOnReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            turnScreenOn();
        }
    };

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

    /** 生成遍历 input 设备、写入 inhibited 的 shell 片段（value 为 1 禁用 / 0 启用） */
    private static String touchLoop(String value) {
        return
            "for d in /sys/class/input/input*/; do\n" +
            "  n=$(cat \"$d/name\" 2>/dev/null)\n" +
            "  case \"$n\" in\n" +
            "    " + TOUCH_MATCH + ")\n" +
            "      echo " + value + " > \"$d/inhibited\" 2>/dev/null\n" +
            "      ;;\n" +
            "  esac\n" +
            "done\n";
    }

    /** 关闭屏幕：背光灭 + 触摸禁用 + 保持唤醒（系统不超时、不锁屏） */
    public boolean turnScreenOff() {
        String script =
            "PREV=$(settings get global stay_on_while_plugged_in 2>/dev/null)\n" +
            "echo \"$PREV\" > " + STAYON_FILE + " 2>/dev/null\n" +
            "svc power stayon true\n" +
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 1 > \"$BL\"\n" +
            touchLoop("1") +
            "echo DONE_OFF\n";
        String out = runRoot(script);
        if (out.contains("DONE_OFF")) {
            prefs.edit().putBoolean(KEY_OFF, true).apply();
            acquireWakeLock();
            startVolumeMonitor();
            startWatchdog(android.os.Process.myPid());
            // 监听屏幕自动亮起（如按电源键），自动恢复触摸
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 34) {
                app.registerReceiver(screenOnReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(screenOnReceiver, filter);
            }
            ScreenOffTileService.notifyStateChanged(app);
            return true;
        }
        return false;
    }

    /** 恢复屏幕：背光亮 + 触摸启用 + 恢复之前的 stayon 设置 */
    public boolean turnScreenOn() {
        String script =
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 0 > \"$BL\"\n" +
            touchLoop("0") +
            "PREV=$(cat " + STAYON_FILE + " 2>/dev/null)\n" +
            "if [ \"$PREV\" != \"\" ] && [ \"$PREV\" != \"0\" ] && [ \"$PREV\" != \"null\" ]; then\n" +
            "  svc power stayon true\n" +
            "else\n" +
            "  svc power stayon false\n" +
            "fi\n" +
            "rm " + STAYON_FILE + " 2>/dev/null\n" +
            "P=$(cat " + WATCHDOG_PID + " 2>/dev/null)\n" +
            "[ -n \"$P\" ] && kill \"$P\" 2>/dev/null\n" +
            "rm -f " + WATCHDOG_PID + " " + WATCHDOG_SCRIPT + " 2>/dev/null\n" +
            "echo DONE_ON\n";
        String out = runRoot(script);
        // 取消屏幕亮起监听，避免重复调用
        try { app.unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
        releaseWakeLock();
        stopVolumeMonitor();
        prefs.edit().putBoolean(KEY_OFF, false).apply();
        ScreenOffTileService.notifyStateChanged(app);
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

    /**
     * 启动 root 侧 watchdog：独立于应用进程运行（setsid 脱离会话），
     * 轮询应用进程 PID 的 /proc/<pid>；一旦应用进程死亡（崩溃/被强杀/内存回收），
     * 自动恢复背光 + 触摸 + 关闭 stayon，避免用户被"触摸永久禁用"锁死。
     * 正常恢复时 turnScreenOn 会 kill 它。
     */
    private void startWatchdog(int pid) {
        String body =
            "echo $$ > " + WATCHDOG_PID + "\n" +
            "P=/proc/" + pid + "\n" +
            "while [ -d \"$P\" ]; do sleep 1; done\n" +
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 0 > \"$BL\"\n" +
            touchLoop("0") +
            "svc power stayon false\n" +
            "rm -f " + STAYON_FILE + " " + WATCHDOG_SCRIPT + " " + WATCHDOG_PID + "\n";
        String script =
            "P=$(cat " + WATCHDOG_PID + " 2>/dev/null)\n" +
            "[ -n \"$P\" ] && kill \"$P\" 2>/dev/null\n" +
            "rm -f " + WATCHDOG_PID + " " + WATCHDOG_SCRIPT + "\n" +
            "cat > " + WATCHDOG_SCRIPT + " <<'EOF'\n" +
            body +
            "EOF\n" +
            "chmod 700 " + WATCHDOG_SCRIPT + "\n" +
            "setsid sh " + WATCHDOG_SCRIPT + " >/dev/null 2>&1 </dev/null &\n" +
            "echo WATCHDOG_STARTED\n";
        runRoot(script);
    }
}
