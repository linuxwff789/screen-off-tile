<div align="center">

# 关屏磁贴 (Screen Off Tile)

**一键关闭屏幕，但不锁屏、不暂停应用。快捷设置磁贴 · 需 Root**

[![GitHub Release](https://img.shields.io/github/v/release/linuxwff789/screen-off-tile)](https://github.com/linuxwff789/screen-off-tile/releases)
[![Build & Release](https://github.com/linuxwff789/screen-off-tile/actions/workflows/build.yml/badge.svg)](https://github.com/linuxwff789/screen-off-tile/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

## 📖 这是什么

手机屏幕亮着时，某些场景需要**屏幕熄灭但应用继续运行**（例如挂机下载、后台播放、游戏托管、录音、外接显示器等）。普通按电源键锁屏会暂停/限制前台应用，本应用通过直接操作硬件背光和触摸，实现：

- ✅ **只关屏幕** —— 硬件背光熄灭，屏幕全黑
- ✅ **不锁屏** —— 系统仍处于唤醒状态，无锁屏界面
- ✅ **不停应用** —— 前台/后台应用全部继续运行，CPU 不休眠
- ✅ **防误触** —— 触摸屏同时被禁用

一键开关，快捷设置磁贴，下拉即可操作。

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🎛️ **快捷设置磁贴** | 下拉通知栏点击磁贴，一键关屏 / 恢复 |
| 📴 **关屏** | 关闭背光（`bl_power`）+ 禁用触摸（`inhibited`） |
| 🔋 **保持唤醒** | `PARTIAL_WAKE_LOCK` + `svc power stayon true`，系统不进入休眠/Doze |
| 🔊 **音量键恢复** | 关屏后按音量键即恢复，**不调音量**（通过无障碍拦截） |
| 🔒 **不锁屏** | 保持系统 Awake，不触发锁屏 |
| ⚡ **实时状态** | 磁贴图标实时反映当前开/关状态 |
| 🤖 **Root 授权检测** | 主界面显示 root 是否可用 |

## 🧠 工作原理

```
┌─────────────────────────────────────────────────┐
│                快捷设置磁贴 (Tile)               │
│             点击 → 关屏 / 恢复                   │
└──────────────────────┬──────────────────────────┘
                       │
              ┌────────▼────────┐
              │  ScreenController │
              │  (root shell)     │
              └─┬───────────┬────┘
    关屏        │           │
  ┌─────────────▼───┐   ┌───▼─────────────┐
  │ echo 1 > bl_power│   │  echo 1 > inhibited │
  │ (背光硬件关闭)    │   │ (触摸屏禁用)       │
  └─────────────┬───┘   └───┬─────────────┘
              ┌────────▼────────┐
              │  WakeLock 保持   │
              │  CPU 不休眠       │
              └─────────────────┘
              │
  恢复        ▼
  ┌──────────────────────────────────────┐
  │ 按音量键 → 无障碍服务 onKeyEvent 拦截   │
  │ 返回 true（系统不调音量）→ 触发恢复     │
  │ echo 0 > bl_power + echo 0 > inhibited│
  └──────────────────────────────────────┘
```

### 核心机制详解

1. **背光控制**：直接写 `/sys/class/backlight/*/bl_power`，绕过系统电源管理，只灭背光不触发系统睡眠
2. **触摸控制**：写触摸屏 input 设备的 `inhibited` 属性，禁用触摸事件
3. **唤醒保持**：应用持有 `PARTIAL_WAKE_LOCK`，并设置 `svc power stayon true`，防止系统进入 Doze / 锁屏
4. **音量键恢复**（重点）：借助 `AccessibilityService` 的 `FLAG_REQUEST_FILTER_KEY_EVENTS`，在系统处理按键**之前**拦截 `onKeyEvent`，返回 `true` 即"吞掉"事件 —— 系统不调音量，我们同时触发恢复

> ⚠️ 没有开启无障碍服务时，音量键恢复仍可用（getevent 监听），但会顺带调音量。开启无障碍后即可避免。

## 📥 安装与使用

### 要求

- Android 8.0+ (API 26)
- **已 Root**（Magisk / KernelSU / APatch 均可）

### 安装步骤

1. 从 [Releases](https://github.com/linuxwff789/screen-off-tile/releases) 下载最新 APK 并安装
2. 打开应用，确认显示「Root 权限：✅ 已授予」
3. 点击「**添加到快捷设置磁贴**」，在系统中允许
4. 下拉通知栏，在快捷设置里找到磁贴（可能需要编辑磁贴把它拖出来）
5. 点击磁贴 → 屏幕熄灭，应用继续运行
6. 按 **音量键** → 屏幕恢复

### 可选配置

- **开启无障碍服务**（强烈推荐）：点击「开启无障碍服务」→ 在系统设置中找到本应用并开启
  - 作用：按音量键恢复时**不改变系统音量**
  - 无障碍服务仅在关屏激活状态下工作，平时不影响任何功能

## 🏗️ 本地构建

```bash
# 需要 JDK 17+
gradle assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

- 使用仓库内固定的 `release.keystore` 签名（密码 `screenoff`），**保证所有版本签名一致**
- 一致的签名意味着升级安装时 **root 授权不会失效**（KernelSU 按签名绑定授权）

## 🚀 GitHub Actions 自动构建发布

本仓库配置了 [build.yml](.github/workflows/build.yml)：

- 推送 `v*` 标签（如 `v1.1.3`）自动触发
- 自动构建 `assembleRelease`
- 自动发布到 GitHub Releases，附带 APK 下载

手动触发：仓库 Actions → **Build & Release** → **Run workflow**。

## 📁 项目结构

```
screen-off-tile/
├── .github/workflows/build.yml   # CI/CD：构建 + 发布 Release
├── app/
│   └── src/main/
│       ├── java/com/screenoff/tile/
│       │   ├── MainActivity.java         # 主界面：root检测、测试、磁贴添加入口
│       │   ├── ScreenController.java   # 核心逻辑：背光/触摸/唤醒控制
│       │   ├── ScreenOffTileService.java # 快捷设置磁贴
│       │   └── KeyInterceptorService.java # 无障碍：音量键拦截（不调音量）
│       └── res/xml/accessibility_service_config.xml
├── release.keystore   # 固定签名密钥（密码: screenoff）
└── build.gradle / settings.gradle
```

> 注：`MainActivity.java` 使用纯 Java 编写（Android 框架 API），无需额外依赖。

## ❓ 常见问题 (FAQ)

### Q1：显示「Root 权限：❌ 未授予」？
- 确认已安装 Magisk/KernelSU 且应用已获授权
- 若之前授权过又升级安装，检查 KernelSU → 超级用户，确保本应用在授权列表
- **签名变化会导致授权失效**：请务必从本仓库 Release 下载（固定签名），不要用不同工具重签

### Q2：按音量键恢复时会调音量？
- 开启无障碍服务即可避免。无障碍会拦截音量键事件，不让系统调音量

### Q3：为什么不用「锁屏/熄屏」而用背光？
- 系统锁屏会暂停部分应用、触发 Doze 省电、需要解锁。本应用只关背光+触摸，系统认为屏幕仍"亮着"，因此应用完全不受影响

### Q4：关屏后应用会耗电吗？
- 关屏期间持有唤醒锁，CPU 不睡眠，功耗与屏幕亮着时相近。适合短时间挂机场景；长时间不用建议普通锁屏

### Q5：会误触恢复吗？
- 只有按音量键才恢复，正常使用音量键会同时恢复屏幕（无障碍开启时不调音量）。触摸已禁用不会误触

### Q6：root 授权弹窗没出现？
- 部分 KernelSU 配置默认不弹窗，需在 KernelSU → 超级用户 中手动添加本应用并授权

## 🧾 版本历史

| 版本 | 更新内容 |
|------|---------|
| v1.1.3 | 关屏时设置 `svc power stayon true` 防止系统休眠；磁贴状态实时刷新 |
| v1.1.2 | 使用固定 keystore 签名，解决 KernelSU 授权因签名变化失效的问题 |
| v1.1.1 | 修复无障碍 onKeyEvent 线程阻塞问题（状态判断不再执行 su，恢复放后台线程） |
| v1.1.0 | 新增无障碍服务，拦截音量键，恢复屏幕时不调音量 |
| v1.0.0 | 首个版本：快捷设置磁贴 + root 关屏/恢复 |

## 📄 License

[Apache License 2.0](LICENSE)

---

**安全提示**：本应用需要 root 权限以直接控制硬件背光。请在了解风险的前提下使用。
