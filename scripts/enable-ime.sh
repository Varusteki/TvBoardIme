#!/usr/bin/env bash
# 在已连接的设备上启用并选中本输入法（走系统命令，不点界面）。
#
# 为什么需要它：Android TV 的系统设置没给第三方应用留任何"直达键盘启用页"的入口。
# TvSettings 里 "Manage keyboards" 只是 KeyboardActivity 内部的一个嵌套 Fragment
# （android:fragment="com.android.tv.settings.inputmethod.AvailableVirtualKeyboardFragment"），
# 系统里没有任何对应 Activity，因此任何 Intent 都跳不到那一层。
# 开发/调试阶段用一条命令代替手工点三层菜单。
#
#   bash scripts/enable-ime.sh             # 启用并选中
#   bash scripts/enable-ime.sh --off       # 停用并切回其它输入法
#   bash scripts/enable-ime.sh --install   # 先装 dist/ 下最新的 APK，再启用选中

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

IME_ID="${APP_ID}/.ime.TvImeService"

ACTION="on"
DO_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --off)     ACTION="off" ;;
    --install) DO_INSTALL=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *)         echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null 2>&1 || { echo "错误：PATH 里找不到 adb" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || {
  echo "错误：没有已连接的设备（用 adb devices 看看）" >&2
  exit 1
}

if [ "$DO_INSTALL" -eq 1 ]; then
  apk="$(latest_apk "$DIST_DIR")"
  if [ -z "$apk" ]; then
    echo "错误：dist/ 下没有 APK，先跑 bash scripts/build.sh" >&2
    exit 1
  fi
  echo "安装 $(basename "$apk")"
  adb install -r "$apk" >/dev/null
fi

if [ "$ACTION" = "off" ]; then
  other="$(adb shell ime list -s | tr -d '\r' | grep -v "^${APP_ID}/" | head -n 1)"
  if [ -n "$other" ]; then
    adb shell ime set "$other" >/dev/null
    echo "已切回：$other"
  fi
  adb shell ime disable "$IME_ID" >/dev/null
  echo "已停用：$IME_ID"
else
  adb shell ime enable "$IME_ID" >/dev/null
  adb shell ime set "$IME_ID" >/dev/null
  echo "已启用并选中：$IME_ID"
fi

echo
echo "当前默认输入法：$(adb shell settings get secure default_input_method | tr -d '\r')"
echo "已启用输入法："
adb shell ime list -s | tr -d '\r' | sed 's/^/  /'
