#!/usr/bin/env bash
# 仅构建，不做发布、不上传。
#
# 产物名由 Gradle 决定，脚本原样归档到 dist/：
#   dist/debug0.1.0_2610310945.apk
#   dist/release0.1.0_2610310945.apk
# 构建完成后自动打开 dist/ 并选中该 APK。
#
# 用法:
#   bash scripts/build.sh                  # 调试包
#   bash scripts/build.sh release          # 发布包（有 keystore.properties 时自动签名）
#   bash scripts/build.sh release --clean  # 先 clean 再全量构建
#   bash scripts/build.sh --no-open        # 构建后不打开窗口（CI / 远程终端）

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

MODE="debug"
CLEAN=0
OPEN=1

for arg in "$@"; do
  case "$arg" in
    debug|release) MODE="$arg" ;;
    --clean) CLEAN=1 ;;
    --no-open) OPEN=0 ;;
    -h|--help)
      sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数: $arg（可用: debug | release | --clean | --no-open）" >&2
      exit 1
      ;;
  esac
done

detect_gradle
check_sdk
read_version

cd "$ROOT_DIR"

if [ "$MODE" = "release" ]; then
  TASK="assembleRelease"
else
  TASK="assembleDebug"
fi

echo "==> 版本: ${VERSION_NAME} (${VERSION_CODE})"
echo "==> 模式: ${MODE}"
echo "==> 任务: ${TASK}"

if [ "$CLEAN" = "1" ]; then
  echo "==> clean"
  "$GRADLE_CMD" clean
fi

"$GRADLE_CMD" "$TASK"

SRC="$(latest_apk "$ROOT_DIR/app/build/outputs/apk/$MODE")"
if [ -z "$SRC" ]; then
  echo "构建结束但未找到 APK，请检查目录：$ROOT_DIR/app/build/outputs/apk/$MODE" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
check_apk_name "$SRC" || true
TARGET="$DIST_DIR/$(basename "$SRC")"
cp -f "$SRC" "$TARGET"

SIZE="$(du -h "$TARGET" | cut -f1)"
echo ""
echo "==> 产物: $TARGET"
echo "==> 体积: $SIZE"
echo "==> 提示: 本脚本只构建，不上传；需要校验和与签名检查请走 release.sh"

if [ "$OPEN" = "1" ]; then
  reveal_file "$TARGET"
fi
