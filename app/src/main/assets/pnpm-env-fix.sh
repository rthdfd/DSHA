#!/bin/bash
# DSHA：修 pnpm 在 proot 容器里的顽固故障 + 把配置迁到 pnpm 11 真正会读的地方。
#
# ─────────────────────────────────────────────────────────────────────
# 为什么这个脚本被整体改过一遍（2026-08）
# ─────────────────────────────────────────────────────────────────────
# 它原来把所有修复都写进 /root/.npmrc。而 pnpm 11 有一条破坏性变更：
#
#     .npmrc 只再用于 auth 与 registry，其余设置一概不读，
#     必须写进 pnpm-workspace.yaml 或全局 ~/.config/pnpm/config.yaml
#     （https://pnpm.io/blog/releases/11.0）
#
# 于是这个脚本从 pnpm 11 起变成了**空操作**：写进去的 package-import-method=copy
# 一个字都不生效（实测 pnpm config get packageImportMethod 返回 undefined）。
# 而 DSHA 锁定的正是 pnpm@11.7.0。
#
# 后果是链式的，且没有任何报错指向真正的原因：
#   copy 导入法失效 → pnpm 回到硬链接 → Android 私有目录不支持真硬链接
#   → proot 用 --link2symlink 模拟成 .l2s → 临时文件一清目标悬空
#   → 装插件报各种莫名的 ENOENT
# 更糟的是重试路径也一起死了：App 检测到这类失败会跑本脚本再试一次，
# 而本脚本当时是空操作，第二次以完全相同的方式失败 —— 用户看到的就是
# 「插件市场里的东西怎么都装不上」。
#
# ─────────────────────────────────────────────────────────────────────
# 配置写在哪：两层，各管一段，刻意不重叠
# ─────────────────────────────────────────────────────────────────────
# ① 全局 ~/.config/pnpm/config.yaml ← 本脚本负责，是**基线**。
#    为什么要有它，而不是只靠 App 注入环境变量：容器里的 pnpm 不只被 App 调用 ——
#    用户（或 AI）在内置终端里手敲 `dsh plugin add`、`pnpm install` 同样要正确。
#    基线放全局，谁调都生效。
#
#    刻意**不**写 /root/.dsh/profiles/web/pnpm-workspace.yaml：那个文件是用户的，
#    dsh 自己和 App 的 allowBuilds 授权都在写它。再加一个写入方就是这个项目
#    反复栽过的模式（「同一份文件多个写入方」—— issue #36 里 repair 把
#    cordis.patch.yml 拼成非法 YAML、Web 直接起不来，就是它的产物）。
#
# ② 单次 pnpm_config_* 环境变量 ← App 侧 PnpmEnv.java 负责，只做**临时豁免**
#    （例如用户点了「这个插件我信得过，现在就装」）。无状态、不留痕、下一次
#    安装自动恢复默认防护。
#
# 全部操作幂等，只碰 pnpm 自己的配置与临时目录，不动用户的 .dsh 数据。
set -u
LOG=/root/.dsh/pnpm-env-fix.log
mkdir -p /root/.dsh 2>/dev/null || true
log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

CFG_DIR=/root/.config/pnpm
CFG="$CFG_DIR/config.yaml"
NPMRC=/root/.npmrc
mkdir -p "$CFG_DIR" 2>/dev/null || true
touch "$CFG" 2>/dev/null || true

# ── ① 全局 config.yaml：设一个 key（幂等，且不重排用户已有的行）──
#
# 用逐行处理而不是 YAML 库：容器里不保证有 python3-yaml，而这个文件只有几行
# 顶层标量。只认「行首无缩进的 key:」，因此不会误伤嵌套结构里的同名 key。
set_cfg() {
  key="$1"; val="$2"
  if grep -q "^${key}:" "$CFG" 2>/dev/null; then
    cur=$(grep "^${key}:" "$CFG" | head -1 | sed "s|^${key}:[[:space:]]*||")
    if [ "$cur" = "$val" ]; then
      return 1              # 已经是想要的值
    fi
    # sed 的替换文本里 & 和 | 需转义；这里的值都是 copy/false/1440 这类简单字面量，
    # 但仍然走一次转义，免得将来有人塞进带斜杠的路径时静默写坏
    esc=$(printf '%s' "$val" | sed 's|[&|\\]|\\&|g')
    sed -i "s|^${key}:.*|${key}: ${esc}|" "$CFG"
    log "config.yaml: ${key} ${cur} → ${val}"
    return 0
  fi
  printf '%s: %s\n' "$key" "$val" >> "$CFG"
  log "config.yaml: 新增 ${key}: ${val}"
  return 0
}

changed=0
# packageImportMethod=copy —— 这条是整个脚本存在的原因，见文件头
set_cfg packageImportMethod copy && changed=1
# sideEffectsCache=false —— 它缓存 build 后的产物，同样靠硬链接铺开
set_cfg sideEffectsCache false && changed=1

if [ "$changed" = "1" ]; then
  echo "PNPM_CONFIG_FIXED"
else
  echo "PNPM_CONFIG_ALREADY"
fi

# ── ② 迁移历史遗留：把 .npmrc 里那两行已失效的设置摘掉 ──
#
# 它们不生效也不报错，留着的唯一作用是制造「配了、所以已经修好了」的幻觉 ——
# 这正是这次 bug 藏了这么久的原因。只删本 App 自己写过的这两行，
# registry 与任何 auth 行一字不动（那两类 pnpm 11 仍然读）。
stale=0
if [ -f "$NPMRC" ]; then
  for k in package-import-method side-effects-cache; do
    if grep -q "^${k}=" "$NPMRC" 2>/dev/null; then
      sed -i "/^${k}=/d" "$NPMRC"
      stale=$((stale + 1))
    fi
  done
  [ "$stale" -gt 0 ] && log "从 .npmrc 摘掉 $stale 行 pnpm 11 已不读的设置（已迁到 config.yaml）"
fi
echo "PNPM_NPMRC_STALE_REMOVED=$stale"

# ── ③ 清 store 的临时目录（只有失败残留才会留在这儿；正常安装结束即清）──
#
# pnpm 装 GitHub 来源的插件时会在 store/v*/tmp/_tmp_<pid>_<hash>/ 里 clone、
# 跑 prepare、再入库。中途失败的话这些目录会一直留着，下次可能撞上同名或读到
# 半成品（典型报错："Failed to prepare git-hosted package … ENOENT … /store/v11/tmp/…"）。
cleaned=0
for STORE in /root/.local/share/pnpm/store/v* /root/.pnpm-store/v*; do
  [ -d "$STORE/tmp" ] || continue
  n=$(find "$STORE/tmp" -maxdepth 1 -mindepth 1 2>/dev/null | wc -l)
  if [ "$n" -gt 0 ]; then
    rm -rf "$STORE"/tmp/* 2>/dev/null || true
    cleaned=$((cleaned + n))
    log "清理 $STORE/tmp 残留 $n 项"
  fi
done
echo "PNPM_TMP_CLEANED=$cleaned"

# ── ④ store 里若已有悬空的 .l2s 链（上一次硬链接模拟留下的），一并摘掉 ──
#    它们既读不出内容，又会让后续 tar/复制整体失败
dangling=0
for STORE in /root/.local/share/pnpm/store/v*; do
  [ -d "$STORE" ] || continue
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    if ! head -c 1 "$p" >/dev/null 2>&1; then
      rm -f "$p" 2>/dev/null && dangling=$((dangling + 1))
    fi
  done <<EOF
$(find "$STORE" -name '.l2s.*' -o -name '*.l2s.*' 2>/dev/null | head -500)
EOF
done
[ "$dangling" -gt 0 ] && log "摘除悬空 l2s 链 $dangling 个"
echo "PNPM_DANGLING_REMOVED=$dangling"

# ── ⑤ 自检：把 pnpm 自己报的值打出来 ──
#
# 这一步是这次事故的直接教训：上一版脚本「看起来做了正确的事」，却因为写错了
# 位置而全程无效，且没有任何地方会发现。现在让 pnpm 自己回答一次，
# 结果进日志也进输出 —— 下次再失效，一眼就能看见。
if command -v pnpm >/dev/null 2>&1; then
  got=$(cd /root 2>/dev/null && pnpm config get packageImportMethod 2>/dev/null | tail -1)
  age=$(cd /root 2>/dev/null && pnpm config get minimumReleaseAge 2>/dev/null | tail -1)
  echo "PNPM_VERIFY_IMPORT_METHOD=${got:-unknown}"
  echo "PNPM_VERIFY_MIN_RELEASE_AGE=${age:-unknown}"
  log "自检：packageImportMethod=${got:-unknown} minimumReleaseAge=${age:-unknown}"
  if [ "$got" != "copy" ]; then
    echo "PNPM_VERIFY_WARN=配置没生效（期望 copy，实际 ${got:-空}）"
    log "警告：配置写了但 pnpm 读到的仍不是 copy —— 位置或版本变了，需要人来看一眼"
  fi
else
  echo "PNPM_VERIFY_SKIP=容器里还没有 pnpm"
fi

log "pnpm 环境修复完成"
echo "PNPM_FIX_OK"
