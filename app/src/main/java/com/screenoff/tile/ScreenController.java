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

    /** root 侧 watchdog 脚本 / PID 文件 / 备份文件 */
    private static final String WATCHDOG_SCRIPT = "/data/local/tmp/.screenoff_watchdog.sh";
    private static final String WATCHDOG_PID = "/data/local/tmp/.screenoff_watchdog.pid";
    private static final String STAYON_FILE = "/data/local/tmp/.screenoff_stayon";
    private static final String TIMEOUT_FILE = "/data/local/tmp/.screenoff_timeout";
    private static final String DREAM_FILE = "/data/local/tmp/.screenoff_screensaver";
    private static final String FREEZER_FILE = "/data/local/tmp/.screenoff_freezer";
    /** 关屏/恢复过程日志（root 写），出问题时直接看这个文件 */
    private static final String LOG_FILE = "/data/local/tmp/.screenoff.log";

    /** 统一走绝对路径：从磁贴/无障碍回调里起的 su 环境 PATH 不一定完整 */
    private static final String BIN_SETTINGS = "/system/bin/settings";
    private static final String BIN_SVC = "/system/bin/svc";
    private static final String BIN_AM = "/system/bin/am";
    private static final String BIN_INPUT = "/system/bin/input";

    /** root 持有的内核 wakelock 名称（独立于应用进程，冻结/被杀也不释放，直到恢复） */
    private static final String KERNEL_WAKELOCK = "screen_off_tile";
    /** 关屏期间把 framework 的熄屏超时顶到最大，避免它误判“无操作”而真正熄屏休眠 */
    private static final int MAX_TIMEOUT = 2147483647;

    private final Context app;
    private final SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread volMonitor;
    /** getevent 子进程，恢复时必须显式销毁，否则每次关屏漏一个 root 进程 */
    private volatile Process volProc;
    /**
     * 关屏脚本自己把 framework 从 Dozing 唤醒时会发出 SCREEN_ON，
     * 这个广播不能当成"用户亮屏"来处理，否则刚关屏就被自己恢复掉。
     */
    private volatile long ignoreScreenOnUntil = 0;
    /** 监听屏幕自动亮起（如按电源键），自动恢复触摸 */
    private final android.content.BroadcastReceiver screenOnReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (System.currentTimeMillis() < ignoreScreenOnUntil) return;
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

    /** 按关屏前备份精确恢复 stay_on_while_plugged_in（svc stayon true 只会粗粒度写回 7，丢失原值）。
     *  幂等：备份文件不存在说明另一恢复路径(app/watchdog)已恢复过，什么都不做。
     *  旧版 else 分支 svc stayon false 会在双路径竞争时把用户原值抹成 0，已删。 */
    private static String restoreStayon() {
        return
            "if [ -f " + STAYON_FILE + " ]; then\n" +
            "  PREV=$(cat " + STAYON_FILE + " 2>/dev/null)\n" +
            "  if [ -n \"$PREV\" ] && [ \"$PREV\" != \"null\" ]; then\n" +
            "    " + BIN_SETTINGS + " put global stay_on_while_plugged_in \"$PREV\" 2>/dev/null\n" +
            "  fi\n" +
            "  rm -f " + STAYON_FILE + " 2>/dev/null\n" +
            "fi\n";
    }

    /** 进程被杀后由 watchdog 代应用把持久化状态改回"未关屏"，避免下次启动状态残留 */
    private static String syncPrefsOff() {
        return
            "for pf in /data/data/com.screenoff.tile/shared_prefs/screenoff.xml " +
            "/data_mirror/data_ce/null/0/com.screenoff.tile/shared_prefs/screenoff.xml; do\n" +
            "  [ -f \"$pf\" ] && sed -i 's/name=\"" + KEY_OFF + "\" value=\"true\"/name=\"" + KEY_OFF + "\" value=\"false\"/' \"$pf\" 2>/dev/null\n" +
            "done\n";
    }

    /** 还原关屏前的 screen_off_timeout（幂等：文件不在说明已恢复过） */
    private static String restoreTimeout() {
        return
            "if [ -f " + TIMEOUT_FILE + " ]; then\n" +
            "  T=$(cat " + TIMEOUT_FILE + " 2>/dev/null)\n" +
            "  if [ -n \"$T\" ] && [ \"$T\" != \"null\" ]; then\n" +
            "    " + BIN_SETTINGS + " put system screen_off_timeout \"$T\" 2>/dev/null\n" +
            "  fi\n" +
            "  rm -f " + TIMEOUT_FILE + " 2>/dev/null\n" +
            "fi\n";
    }

    /** 还原关屏前的 cached_apps_freezer（原值 null 则直接删掉这条设置） */
    private static String restoreFreezer() {
        return
            "if [ -f " + FREEZER_FILE + " ]; then\n" +
            "  F=$(cat " + FREEZER_FILE + " 2>/dev/null)\n" +
            "  if [ -z \"$F\" ] || [ \"$F\" = \"null\" ]; then\n" +
            "    " + BIN_SETTINGS + " delete global cached_apps_freezer 2>/dev/null\n" +
            "  else\n" +
            "    " + BIN_SETTINGS + " put global cached_apps_freezer \"$F\" 2>/dev/null\n" +
            "  fi\n" +
            "  rm -f " + FREEZER_FILE + " 2>/dev/null\n" +
            "fi\n";
    }

    /** 还原关屏前的 screensaver（dream）开关（幂等） */
    private static String restoreDream() {
        return
            "if [ -f " + DREAM_FILE + " ]; then\n" +
            "  S=$(cat " + DREAM_FILE + " 2>/dev/null)\n" +
            "  if [ -n \"$S\" ] && [ \"$S\" != \"null\" ]; then\n" +
            "    " + BIN_SETTINGS + " put secure screensaver_enabled \"$S\" 2>/dev/null\n" +
            "  fi\n" +
            "  rm -f " + DREAM_FILE + " 2>/dev/null\n" +
            "fi\n";
    }

    /**
     * 把 framework 从 Dozing/AOD 拉回 Awake。
     * 注意：实测 KEYCODE_WAKEUP(224) 在 AOSPA/fuxi 上对 Dozing 无效，必须用
     * KEYCODE_POWER(26) 做切换（先判状态，避免把已经 Awake 的机器按睡）。
     */
    private static String wakeIfNeeded() {
        return
            "W0=$(dumpsys power 2>/dev/null | sed -n 's/^  mWakefulness=//p' | head -n1)\n" +
            "if [ \"$W0\" != \"Awake\" ]; then\n" +
            "  " + BIN_INPUT + " keyevent 26\n" +
            "  sleep 1\n" +
            "  W1=$(dumpsys power 2>/dev/null | sed -n 's/^  mWakefulness=//p' | head -n1)\n" +
            "  if [ \"$W1\" != \"Awake\" ]; then " + BIN_INPUT + " keyevent 26; sleep 1; fi\n" +
            "fi\n";
    }

    /**
     * 把一段 root 脚本的输出同时写进日志文件（超过 256KB 轮转一次）。
     * 出问题时 /data/local/tmp/.screenoff.log 里能看到每条命令的实际结果。
     */
    private static String withLog(String script) {
        return
            "S=$(/system/bin/stat -c %s " + LOG_FILE + " 2>/dev/null)\n" +
            "if [ -n \"$S\" ] && [ \"$S\" -gt 262144 ]; then mv " + LOG_FILE + " " + LOG_FILE + ".1; fi\n" +
            "echo \"---- $(/system/bin/date +%F_%H:%M:%S) ----\" >> " + LOG_FILE + "\n" +
            "( " + script + " ) 2>&1 | /system/bin/tee -a " + LOG_FILE + "\n";
    }

    /** 恢复时的公共收尾：释放内核 wakelock、还原熄屏超时/屏保、停掉前台保持服务。
     *  幂等，可被 app 的 turnScreenOn 与 root watchdog 两条路径重复执行。 */
    private static String recoverCommon() {
        return
            "echo " + KERNEL_WAKELOCK + " > /sys/power/wake_unlock 2>/dev/null\n" +
            restoreTimeout() +
            restoreDream() +
            restoreFreezer() +
            BIN_AM + " stopservice com.screenoff.tile/.KeepAliveService >/dev/null 2>&1\n";
    }

    /** 关闭屏幕：背光灭 + 触摸禁用 + 保持唤醒（系统不超时、不锁屏） */
    public boolean turnScreenOff() {
        String script =
            // framework 若还在 Dozing/AOD（锁屏后磁贴仍可点），只写 bl_power 系统会继续 Doze，
            // 应用照样被冻结 —— 先强制把它拉到 Awake，关屏态才有意义
            wakeIfNeeded() +
            "PREV=$(" + BIN_SETTINGS + " get global stay_on_while_plugged_in 2>/dev/null)\n" +
            "echo \"$PREV\" > " + STAYON_FILE + " 2>/dev/null\n" +
            BIN_SVC + " power stayon true\n" +
            // 关键：framework 仍以为屏幕亮着，必须把它的熄屏超时顶到最大，
            // 否则 30s 后 framework 自己熄屏 -> 休眠 -> cached app freezer 冻结应用
            "T=$(" + BIN_SETTINGS + " get system screen_off_timeout 2>/dev/null)\n" +
            "echo \"$T\" > " + TIMEOUT_FILE + " 2>/dev/null\n" +
            BIN_SETTINGS + " put system screen_off_timeout " + MAX_TIMEOUT + "\n" +
            "SS=$(" + BIN_SETTINGS + " get secure screensaver_enabled 2>/dev/null)\n" +
            "echo \"$SS\" > " + DREAM_FILE + " 2>/dev/null\n" +
            BIN_SETTINGS + " put secure screensaver_enabled 0\n" +
            // Android 14 的 cached app freezer 不看屏幕状态（亮屏时也照样冻 cached 进程），
            // 所以"framework 不休眠"并不能保护挂机应用；关屏期间直接全局关掉，恢复时还原
            "FZ=$(" + BIN_SETTINGS + " get global cached_apps_freezer 2>/dev/null)\n" +
            "echo \"$FZ\" > " + FREEZER_FILE + " 2>/dev/null\n" +
            BIN_SETTINGS + " put global cached_apps_freezer disabled\n" +
            // 由 root 直接持有的内核 wakelock：不依赖应用进程，冻结/被杀都不影响，确保不休眠
            "echo " + KERNEL_WAKELOCK + " > /sys/power/wake_lock 2>/dev/null\n" +
            // targetSdk 35 下从磁贴回调里 startForegroundService 会被 AMS 拒绝
            // (Background started FGS: Disallowed, uidState: CAC)
            // 改由 root 拉起，uid 0 在 allowlist 里，实测可成功
            BIN_AM + " start-foreground-service -n com.screenoff.tile/.KeepAliveService >/dev/null 2>&1\n" +
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 1 > \"$BL\"\n" +
            touchLoop("1") +
            "sleep 2\n" +
            "W1=$(dumpsys power 2>/dev/null | sed -n 's/^  mWakefulness=//p' | head -n1)\n" +
            "FG=$(dumpsys activity services com.screenoff.tile 2>/dev/null | grep -c 'isForeground=true')\n" +
            "echo \"wake=$W1 fgs=$FG freezer=$(" + BIN_SETTINGS + " get global cached_apps_freezer) bl=$(cat $BL 2>/dev/null)\"\n" +
            "echo DONE_OFF\n";
        // 脚本里若用 input keyevent 224 把 framework 拉起来, SCREEN_ON 会跟着来, 先屏蔽 8s
        ignoreScreenOnUntil = System.currentTimeMillis() + 8000;
        String out = runRoot(withLog(script));
        if (out.contains("DONE_OFF")) {
            prefs.edit().putBoolean(KEY_OFF, true).apply();
            acquireWakeLock();
            // 兜底：root 那条 am 失败时才由应用自己尝试（Android 14 多半会被拒）
            startKeepAlive(app);
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
            // framework 若被电源键按下去了(AOD/Doze), 只写 bl_power=0 屏幕是黑的, 先唤醒
            wakeIfNeeded() +
            // 先杀 watchdog 再恢复, 避免其恢复分支与本函数竞争 stayon 备份文件
            "P=$(cat " + WATCHDOG_PID + " 2>/dev/null)\n" +
            "[ -n \"$P\" ] && kill \"$P\" 2>/dev/null\n" +
            "rm -f " + WATCHDOG_PID + " " + WATCHDOG_SCRIPT + " 2>/dev/null\n" +
            recoverCommon() +
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 0 > \"$BL\"\n" +
            touchLoop("0") +
            restoreStayon() +
            "echo \"ON wake=$(dumpsys power 2>/dev/null | sed -n 's/^  mWakefulness=//p' | head -n1)\"\n" +
            "echo DONE_ON\n";
        String out = runRoot(withLog(script));
        // 取消屏幕亮起监听，避免重复调用
        try { app.unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
        releaseWakeLock();
        stopKeepAlive(app);
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

    /** 关屏期间启动前台服务，避免进程被 cached app freezer 冻结（音量键拦截/磁贴状态刷新需要进程活着） */
    public static void startKeepAlive(Context context) {
        try {
            Intent i = new Intent(context, KeepAliveService.class)
                    .setAction(KeepAliveService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {}
    }

    public static void stopKeepAlive(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {}
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

    /** 监听音量键/电源键作为"恢复"快捷键（触摸已被禁用时的唯一物理入口） */
    private void startVolumeMonitor() {
        stopVolumeMonitor();
        final ScreenController self = this;
        final Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", "getevent").redirectErrorStream(true).start();
                volProc = p;
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = r.readLine()) != null) {
                    boolean pressed = line.trim().endsWith("00000001");
                    // 音量键 code: 0x72=VOLUMEDOWN 0x73=VOLUMEUP，电源键 0x74=KEY_POWER
                    // 电源键必须处理：framework 认为屏幕还亮着，按电源键它会"睡觉"(AOD)，
                    // 而不会唤醒 —— 用户按电源键的本意基本都是要屏幕回来
                    boolean vol = pressed && (line.contains(" 0072 ") || line.contains(" 0073 "));
                    boolean power = pressed && line.contains(" 0074 ");
                    if (vol || power) {
                        p.destroy();
                        if (power) {
                            // 等 framework 先把这次电源键处理成"休眠"，否则 turnScreenOn 里的
                            // 状态判断可能还看到 Awake，导致该唤醒的时候没唤醒
                            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                        }
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
        // 只 interrupt 线程不会杀掉 getevent 子进程，否则每关一次屏就漏一个 root 进程
        Process p = volProc;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
            volProc = null;
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
            "echo \"$(/system/bin/date +%H:%M:%S) watchdog start pid=$$\" >> " + LOG_FILE + "\n" +
            "P=/proc/" + pid + "\n" +
            "BLF=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "sleep 2\n" +
            "while [ -d \"$P\" ]; do\n" +
            "  # 应用被冻结(cached app freezer)时 /proc 永不消失, 但 root watchdog 不受影响;\n" +
            "  # 用户亮屏(用户按电源键, bl=0)时应用自身恢复路径已死, 由本脚本代为恢复触摸\n" +
            "  B=$(cat \"$BLF\" 2>/dev/null)\n" +
            "  # 只在真正全亮(bl=0)时代为恢复; AOD(bl=4)仍属灭屏, 不能提前释放触摸\n" +
            "  if [ \"$B\" = \"0\" ]; then\n" +
            "    echo \"$(/system/bin/date +%H:%M:%S) watchdog restore: bl=0\" >> " + LOG_FILE + "\n" +
            touchLoop("0") +
            recoverCommon() +
            restoreStayon() +
            syncPrefsOff() +
            "    rm -f " + WATCHDOG_SCRIPT + " " + WATCHDOG_PID + "\n" +
            "    exit 0\n" +
            "  fi\n" +
            "  sleep 1\n" +
            "done\n" +
            "echo \"$(/system/bin/date +%H:%M:%S) watchdog restore: app gone\" >> " + LOG_FILE + "\n" +
            "BL=$(ls /sys/class/backlight/*/bl_power 2>/dev/null | head -n1)\n" +
            "[ -n \"$BL\" ] && echo 0 > \"$BL\"\n" +
            touchLoop("0") +
            recoverCommon() +
            restoreStayon() +
            syncPrefsOff() +
            "rm -f " + WATCHDOG_SCRIPT + " " + WATCHDOG_PID + "\n";
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
