# DSHA

**DeepSeek Harness 安卓启动器** —— 在手机上跑 deepseek-harness 的一体化方案，无需 Termux、无需 ROOT。

**DeepSeek Harness Android Launcher** — run deepseek-harness on a stock Android phone. No Termux, no ROOT.

内置 proot + Ubuntu rootfs，一键（或分步）安装 deepseek-harness，内嵌 WebView 直接使用 Web UI。
Ships proot + an Ubuntu rootfs, installs deepseek-harness in one tap (or step by step), and hosts the Web UI in a built-in WebView.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📖 目录 / Contents

- [中文](#-中文)
- [English](#-english)

---

# 🇨🇳 中文

## ✨ 功能

| 功能 | 说明 |
|---|---|
| **分步安装** | 6 个步骤（rootfs / 基础工具 / Node.js / pnpm / deepseek-harness / 安全补丁），每步可单独重装、更新，不重复下载 |
| **多源测速** | 每个下载源都有多个镜像（清华/阿里云/华为云/腾讯云/南大/哈工大/npmmirror…），并行测速后弹窗自选 |
| **直连源码构建** | 不依赖预构建包，直接从 GitHub 克隆源码 + pnpm 本地构建，自动修复 node-pty 等原生模块编译问题 |
| **Web UI 预览** | 启动后自动检测就绪并弹出全屏预览，支持重启/停止（软重启，不杀 App 进程） |
| **免 ROOT 文件共享** | 集成 MT 管理器官方文件提供器，直接浏览/编辑 App 私有数据（工作区、配置、日志） |
| **配置备份/重置** | 一键备份配置到 Download/DSHA（防死机），重置配置时保留对话记录 |
| **设备 Shell 桥接** | 通过 Shizuku 让智能体直接在设备上执行 shell 命令（危险命令需用户确认） |
| **ADB 无线配对** | 免 Shizuku 直连 adbd，通知栏就地输入 6 位配对码 |
| **局域网访问** | App 侧 0.0.0.0:3081 桥接转发，Host 头自动重写，同 WiFi 设备可直接访问 |
| **WebUI 移动端适配** | 自动移除侧边栏开关等移动端无效功能 |

## 🚀 快速上手

1. 安装 APK（仅 arm64；GitHub Actions 产物已内置完整 Linux 环境）
2. 首次启动会解压内置环境（数分钟，只需一次）
3. 「配置」页填入 DeepSeek API key
4. 「启动」页启动 Web UI，自动打开预览

## 🧰 Agent Skills（智能体技能包）

配套的智能体技能，位于 [`agent-skills/`](agent-skills/)：

| 技能 | 说明 |
|---|---|
| `device-shell` | 通过 ADB 或本地 Shizuku HTTP 桥（127.0.0.1:3090）在安卓设备上执行 shell 命令 |
| `screen-ocr-operator` | 指挥官模式：OCR/视觉模型 + ADB 批量操作屏幕，最少往返 |

复制到 agent 技能目录即可使用：

```bash
cp -r agent-skills/device-shell ~/.agents/skills/
cp -r agent-skills/screen-ocr-operator ~/.agents/skills/
```

## 🔧 构建

### GitHub Actions（推荐，不需要电脑、不需要 Termux）

1. 推送到 `main`（或在 Actions 页点 Run workflow）
2. 流水线分两段：
   - `ubuntu-24.04-arm`：原生 arm64 chroot 预装 Ubuntu + Node + dsh（node-pty 必须原生 arm64 编译，**禁止 qemu**）
   - `ubuntu-latest`：把离线包打进 APK
3. 在 Actions 的 Artifacts 下载 `dsha-debug-apk`（GitHub 会把产物包一层 ZIP，需先解压再安装）

### 本地构建

| 项 | 要求 |
|---|---|
| JDK | **17** |
| Android SDK | SDK **34**（build-tools 34） |
| Android NDK | **26** |
| Gradle | **8.5** |

```sh
# 1. 新建 local.properties：sdk.dir=/绝对路径/Android/Sdk
# 2. 准备离线包 app/src/main/assets/offline-rootfs.tar.gz
#    （从 Actions 的 offline-rootfs-bundle 产物拷贝；缺失会直接构建失败）
./build.sh
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建需联网下载约 100MB+ 依赖（GeckoView 内核等）。版本号在 `app/build.gradle`（`versionName` / `versionCode`），根目录 `VERSION` 与 tag 保持一致。

### 打包保护（勿删）

- `protectOfflineBundle` 在打包前把 `assets/offline-rootfs.tar.gz` 改名为 `.bin` —— aapt 会静默解压 `.tar.gz`，导致 300MB+ 的包失效
- `app/build.gradle` 末尾的 `verifyArm64Apk` 校验 APK 含离线包且只有 arm64-v8a 原生库
- `app/src/main/jniLibs/arm64-v8a/libproot.so` 是**已修复**的 proot（移除 `canonicalize` 中 `/proc/self/fd` 误杀断言，会带崩 WebUI），**不要**用 stock proot 覆盖

## 🧱 技术架构

### 分层（自底向上）

```
native UI（Activities/Fragments，纯 Java 17，无 Kotlin）
   → HarnessController      业务核心：分步安装、配置、Web 启停、插件、下载
   → ProotBootstrap         proot exec、rootfs 下载/解压、离线包处理
   → libproot.so + Ubuntu 24.04 arm64 rootfs → Node 24 + pnpm + @deepseek-ai/dsh
```

- **UI**：原生 Android + Material3 + BottomNavigationView；`applicationId com.dsh.client`，包名 `com.deepseekharness.app`；minSdk 26 / target 34；**仅 arm64-v8a**
- **执行层**：Termux 官方 proot 伪装成 `libproot.so` 放入 jniLibs（安装时落到可执行的 nativeLibraryDir，绕过私有目录 noexec），用 `/system/bin/linker64` + `PROOT_LOADER`/`LD_LIBRARY_PATH` 引导，绕过 Android 10+ W^X
- **rootfs**：离线包随 APK 内置，首启解压到 `files/linux/ubuntu/`；完成标记 `files/linux/.offline-extracted`
- **运行时**：默认 RC6 预装（`use_rc6=true`），可选直连 GitHub 源码构建

### 关键文件（app/src/main/java/com/deepseekharness/app/）

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 启动门禁、崩溃日志、更新检查、备份提醒、升级迁移 |
| `HarnessController.java` | ~2700 行业务核心，改动请打最小补丁 |
| `ProotBootstrap.java` | proot env/exec、rootfs 解压、多镜像下载测速 |
| `ExtractActivity.java` | 首启解压页（找不到包就停在本页打诊断，绝不偷跳） |
| `HarnessService.java` | 前台保活 + WebUI 失联自动重启（带冷却） |
| `HttpShellService.java` | `127.0.0.1:3090` 命令桥（危险命令弹窗/通知确认） |
| `ShizukuShell.java` / `DeviceBridgeService.java` | Shizuku UserService 设备 shell / ADB 无线配对监听 |
| `LanProxyService.java` | 局域网转发桥 0.0.0.0:3081 → 127.0.0.1:3080 |
| `BackupManager.java` | 备份/恢复到 Download/DSHA（Android 10+ 走 MediaStore） |
| `TarGzipExtractor.java` | 纯 Java tar/tar.gz 解压（自动嗅探 gzip 魔数） |
| `*Fragment.java` | 各 Tab UI：启动/终端/市场 + 设置下的安装/配置/工作区 |

### 启动契约（勿破坏）

- `welcomed == false` → `WelcomeActivity`（3 页）→ `ExtractActivity`（强制）
- `welcomed == true` 但 `!isOfflineExtracted()` → `ExtractActivity`（强制）
- `ExtractActivity → MainActivity` **必须**带 `skip_extract=true`，否则循环重开解压页
- 进入主界面只认 `.offline-extracted` 标记（而非旧的 `.installed`）
- 默认 RC6 运行时；**不要**在 rootfs 里放半成品源码树 —— `startWeb` 优先源码树，半棵树会带崩启动

### 进程/线程模型（Web 启停）

- `HarnessController.IO` 单线程 executor 只跑短任务（install/stop/start 排队执行，天然串行）
- Web 进程的保活阻塞在独立 `dsha-web-watcher` 线程（`drainOutput` 读到 EOF 即收尾上报）——**不要**把阻塞读放回 IO executor，否则 stop/restart 永远排不进队列
- 停止/重启 = 深停（destroy + pkill 看门狗 + 等端口关透 + 宽杀兜底）→ （重启时）重新拉起；主动销毁的进程登记在 `expectedWebExit`，watcher 不误报「意外退出」
- rootfs 内看门狗 `dsh-watchdog.sh`（失联 3 次自动重启）+ App 侧 keepAlive（15s 探测、2 分钟冷却）；手动停止置 `keepalive_paused`，两者都会尊重

### rootfs 内部路径

- 用户数据：`/root/.dsh`（profile 配置、对话记录、插件）；`/root/<workdir>/.env`（默认 workdir `deepseek-harness`）
- 日志：`/root/dsh-web.log`（WebUI）、`/root/dsh-watchdog.log`、`/root/dsh-lan.log`
- 危险命令包装器：`/root/dsh-bin`（PATH 前置，`DSH_CONFIRM=1` 时拦截 rm/dd 等）

### 插件机制

- 插件目录 `/root/.dsh/profiles/web/node_modules`，声明在 `package.json` `dependencies` 与 `dsh.profile.bundles`
- 禁用 = 目录改名 `name` → `name.disabled` + 删依赖 + 源码暂存 `.dsha-src-<name>`；启用时从暂存恢复
- `toggleScript()` **绝不能**往 `dependencies` 写 `"*"`/`"null"`/空串 —— `"*"` 依赖非 npm 插件名会带崩 `pnpm install`
- 市场索引为 `awesome-dsh-plugins` 的 `PLUGINS-ALL.md`，多镜像拉取 + 本地缓存兜底

### 升级迁移

- `MainActivity.maybeRunUpgradeMigration()`：版本变化时后台打包 `.dsh` + `.env` + 日志到 `Download/DSHA/DSHA-migration-*.tar.gz`；全新 rootfs 且存在历史快照时提示恢复（按版本记忆「暂不」）
- `HarnessController.upgradeGuard()`：`versionCode` 上升时自动备份；`maybePromptRestore()` 只在 `skip_extract=true`（rootfs 已解压）时提示恢复

### 已知陷阱

- **定位离线包**：枚举 APK zip 找 `offline-rootfs.{tar.gz,tar,bin,tgz}` —— `AssetManager.open` 开不了 300MB+ 条目
- Android 10+ 外部写走 MediaStore；直接写 `Download/DSHA` 只是旧机型的兜底
- GitHub 系下载统一走 `HarnessController.gitHubProxy()` 代理前缀加速

## ⚠️ 注意

- 仅支持 arm64-v8a 设备，Android 8.0+
- 环境存储在 App 私有空间，卸载即清除（可先用「备份配置」）
- 设备 Shell 能力需要安装并授权 [Shizuku](https://shizuku.rikka.app/)
- QQ交流群960636357🐧可实时跟进体验最新测试版

## 开发约定

- 注释与 UI 文案用**中文**；commit 信息中文 + `type:` 前缀，说明**为什么**这么改
- `HarnessController.java` 体量巨大 —— 打**最小补丁**，不要重写
- 风险操作一律 try/catch + 合理降级 + toast 告知用户
- 不要以"清理"为名删掉打包保护任务与解压不变量 —— 它们各有踩坑背景

---

# 🇬🇧 English

**DeepSeek Harness Android Launcher** — runs the `@deepseek-ai/dsh` Web UI on a stock, non-rooted **arm64 Android 8+** phone. No ROOT, no Termux.

The APK ships the Termux `proot` binary (as `libproot.so`) plus an offline **Ubuntu 24.04 arm64** rootfs. On first run the rootfs is extracted into app-private storage; then proot chroots into it, where **Node 24 + pnpm + `@deepseek-ai/dsh`** run the harness. The native UI (pure Java, Material3, bottom nav) drives install/start/stop and hosts the Web UI preview in a system WebView.

## ✨ Features

| Feature | Description |
|---|---|
| **Step-by-step install** | 6 steps (rootfs / base tools / Node.js / pnpm / deepseek-harness / safety patches), each independently reinstallable |
| **Multi-mirror speed test** | Parallel latency probing across Chinese mirror mirrors + manual source pick |
| **Source build** | No prebuilt packages: clones from GitHub and builds with pnpm, auto-fixing native modules like node-pty |
| **Web UI preview** | Auto-detects readiness and opens a fullscreen preview; soft restart/stop without killing the app process |
| **ROOT-less file sharing** | MT Manager's official `MTDataFilesProvider` exposes app-private data (workspace, config, logs) |
| **Config backup/reset** | One-tap backup to Download/DSHA; reset keeps conversations |
| **Device shell bridge** | Agent executes device shell commands via Shizuku (dangerous commands need user confirmation) |
| **ADB wireless pairing** | Direct adbd pairing without Shizuku; enter the 6-digit code right in the notification |
| **LAN access** | App-side bridge 0.0.0.0:3081 with Host-header rewrite |
| **Mobile-adapted WebUI** | Strips mobile-useless features like sidebar toggles |

## 🚀 Quick start

1. Install the APK (arm64 only; GitHub Actions artifacts bundle the full Linux environment)
2. First launch extracts the bundled environment (a few minutes, one-time)
3. Enter your DeepSeek API key on the Config page
4. Start the Web UI from the Launch page — the preview opens automatically

## 🧰 Agent Skills

Companion agent skills in [`agent-skills/`](agent-skills/):

| Skill | Description |
|---|---|
| `device-shell` | Execute shell commands on the device via ADB or a local Shizuku HTTP bridge (127.0.0.1:3090) |
| `screen-ocr-operator` | Commander mode: OCR/vision model + batched ADB screen operations |

```bash
cp -r agent-skills/device-shell ~/.agents/skills/
cp -r agent-skills/screen-ocr-operator ~/.agents/skills/
```

## 🔧 Build

### GitHub Actions (recommended — no PC, no Termux)

1. Push to `main` (or Run workflow manually)
2. Two-stage pipeline:
   - `ubuntu-24.04-arm`: native arm64 chroot provisions Ubuntu + Node + dsh (node-pty **must** be built on native aarch64 — never qemu)
   - `ubuntu-latest`: packs the offline bundle into the APK
3. Download `dsha-debug-apk` from Artifacts (GitHub wraps it in a ZIP — unzip before installing)

### Local build

Requires **JDK 17**, **Android SDK 34** (build-tools 34), **NDK 26**, **Gradle 8.5**:

```sh
# 1. Create local.properties: sdk.dir=/absolute/path/to/Android/Sdk
# 2. Supply app/src/main/assets/offline-rootfs.tar.gz
#    (copy from the Actions offline-rootfs-bundle artifact; the build fails hard without it)
./build.sh
# Output: app/build/outputs/apk/debug/app-debug.apk
```

First build downloads ~100MB+ of dependencies (GeckoView etc.). Version lives in `app/build.gradle` (`versionName` / `versionCode`); the root `VERSION` file mirrors the tag.

### Packaging protections (do not remove)

- `protectOfflineBundle` renames `assets/offline-rootfs.tar.gz` → `.bin` before packaging — aapt silently untars `.tar.gz`, breaking the 300MB+ bundle
- `verifyArm64Apk` (end of `app/build.gradle`) asserts the APK contains the bundle and only arm64-v8a native libs
- `app/src/main/jniLibs/arm64-v8a/libproot.so` is a **patched** proot (removed the `canonicalize` assertion that killed `/proc/self/fd` and crashed the WebUI). **Never** overwrite it with a stock proot build

## 🧱 Architecture

### Layers (bottom-up)

```
native UI (Activities/Fragments — pure Java 17, no Kotlin)
   → HarnessController      business core: install steps, config, Web start/stop, plugins, downloads
   → ProotBootstrap         proot exec, rootfs download/extract, offline-bundle handling
   → libproot.so + Ubuntu 24.04 arm64 rootfs → Node 24 + pnpm + @deepseek-ai/dsh
```

- **UI**: stock Android + Material3 + BottomNavigationView; `applicationId com.dsh.client`, package `com.deepseekharness.app`; minSdk 26 / target 34; **arm64-v8a only**
- **Execution layer**: proot disguised as `libproot.so` in jniLibs (extracted to the executable nativeLibraryDir, bypassing noexec), bootstrapped via `/system/bin/linker64` + `PROOT_LOADER`/`LD_LIBRARY_PATH` (works around Android 10+ W^X)
- **rootfs**: bundled in the APK, extracted to `files/linux/ubuntu/` on first run; completion marker `files/linux/.offline-extracted`
- **Runtime**: RC6 preinstall by default (`use_rc6=true`); optional direct-from-GitHub source build

### Key files (app/src/main/java/com/deepseekharness/app/)

| File | Responsibility |
|---|---|
| `MainActivity.java` | Startup gates, crash log, update check, backup reminder, upgrade migration |
| `HarnessController.java` | ~2700-line business core — patch minimally, never rewrite |
| `ProotBootstrap.java` | proot env/exec, rootfs extraction, multi-mirror downloads |
| `ExtractActivity.java` | First-run extraction screen (shows diagnostics instead of bailing out) |
| `HarnessService.java` | Foreground keep-alive + auto-restart on WebUI loss (with cooldown) |
| `HttpShellService.java` | `127.0.0.1:3090` command bridge (dangerous commands need confirmation) |
| `ShizukuShell.java` / `DeviceBridgeService.java` | Shizuku UserService device shell / ADB wireless pairing watcher |
| `LanProxyService.java` | LAN forward bridge 0.0.0.0:3081 → 127.0.0.1:3080 |
| `BackupManager.java` | Backup/restore to Download/DSHA (MediaStore on Android 10+) |
| `TarGzipExtractor.java` | Pure-Java tar/tar.gz extraction (gzip-magic sniffing) |
| `*Fragment.java` | Per-tab UI: Launch / Terminal / Market, plus Install / Config / Workspace under Settings |

### Startup contract (do not break)

- `welcomed == false` → `WelcomeActivity` (3 pages) → `ExtractActivity` (mandatory)
- `welcomed == true` but `!isOfflineExtracted()` → `ExtractActivity` (mandatory)
- `ExtractActivity → MainActivity` **must** pass `skip_extract=true`, or Main re-launches Extract forever
- Entry into the main UI keys **only** on `.offline-extracted` (not the legacy `.installed` marker)
- Default runtime is RC6; do **not** place a half-cloned source tree in the rootfs — `startWeb` prefers a source tree and half a tree breaks startup

### Process/thread model (Web start/stop)

- `HarnessController.IO` is a single-thread executor for **short** tasks only (install/stop/start run serially by design)
- The web-process keep-alive block lives on its own `dsha-web-watcher` thread (`drainOutput` returns at EOF, then cleanup/report). **Never** put a blocking read back on the IO executor — that once made stop/restart starve forever and forced the old "kill the whole app process" restart
- Stop/restart = deep stop (destroy + pkill watchdog + wait for port close + broad fallback kill) → (restart only) relaunch; intentionally destroyed processes are registered in `expectedWebExit` so the watcher never reports a false "unexpected exit"
- Two keep-alives: in-rootfs `dsh-watchdog.sh` (3 misses → restart) and App-side keepAlive (15s probe, 2min cooldown); manual stop sets `keepalive_paused`, honored by both

### Rootfs paths

- User data: `/root/.dsh` (profile config, conversations, plugins); `/root/<workdir>/.env` (default workdir `deepseek-harness`)
- Logs: `/root/dsh-web.log`, `/root/dsh-watchdog.log`, `/root/dsh-lan.log`
- Danger wrappers: `/root/dsh-bin` (PATH-prepend; intercepts rm/dd etc. when `DSH_CONFIRM=1`)

### Plugin machinery

- Plugins live in `/root/.dsh/profiles/web/node_modules`, declared in `package.json` `dependencies` and `dsh.profile.bundles`
- Disable = rename `name` → `name.disabled`, drop the dependency, stash source at `.dsha-src-<name>`; enable restores from the stash
- `toggleScript()` must **never** write `"*"`, `"null"`, or empty into `dependencies` — a `"*"` dependency on a non-npm plugin name breaks `pnpm install`
- Market index: `PLUGINS-ALL.md` from `awesome-dsh-plugins`, fetched via mirrors with local cache fallback

### Upgrade migration

- `MainActivity.maybeRunUpgradeMigration()`: on version change, packs `.dsh` + `.env` + log to `Download/DSHA/DSHA-migration-*.tar.gz`; offers restore on a fresh rootfs (per-version "later" persists)
- `HarnessController.upgradeGuard()`: auto-backs-up when `versionCode` rises; `maybePromptRestore()` only fires with `skip_extract=true` (rootfs already extracted)

### Known traps

- **Locating the offline bundle**: enumerate the APK zip for `offline-rootfs.{tar.gz,tar,bin,tgz}` — `AssetManager.open` can't open 300MB+ entries
- Android 10+ external writes go through MediaStore; direct `Download/DSHA` paths are only a legacy fallback
- All GitHub-family downloads go through `HarnessController.gitHubProxy()` prefix acceleration

## ⚠️ Notes

- arm64-v8a only, Android 8.0+
- The environment lives in app-private storage; uninstalling wipes it (use config backup first)
- Device shell requires [Shizuku](https://shizuku.rikka.app/) installed and granted
- QQ group 960636357 🐧 for feedback and latest test builds

## Development conventions

- Comments and UI strings in **Chinese**; commit messages Chinese with a `type:` prefix explaining **why**
- `HarnessController.java` is huge — apply the smallest patch that works
- Wrap risky work in try/catch, degrade to a sensible fallback, toast failures
- Don't remove the packaging-protection Gradle tasks or extraction invariants — each has a battle scar behind it
