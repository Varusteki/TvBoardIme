#!/usr/bin/env bash
# 修改版本号：默认 versionCode 自增 1。
#
# 用法:
#   bash scripts/bump-version.sh 0.2.0
#   bash scripts/bump-version.sh 0.2.0 --code 12

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

if [ $# -lt 1 ]; then
  echo "用法: bash scripts/bump-version.sh <newVersion> [--code N]" >&2
  exit 1
fi

NEW_VERSION="$1"
shift
NEW_CODE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --code)
      shift
      NEW_CODE="${1:-}"
      ;;
    *)
      echo "未知参数: $1" >&2
      exit 1
      ;;
  esac
  shift
done

if ! printf '%s' "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号需形如 1.2.3" >&2
  exit 1
fi

read_version

if [ -z "$NEW_CODE" ]; then
  NEW_CODE=$((VERSION_CODE + 1))
fi

TMP="$(mktemp)"
sed -E \
  -e "s/versionCode = [0-9]+/versionCode = ${NEW_CODE}/" \
  -e "s/versionName = \"[^\"]+\"/versionName = \"${NEW_VERSION}\"/" \
  "$BUILD_FILE" > "$TMP"
mv "$TMP" "$BUILD_FILE"

echo "已更新: versionName ${VERSION_NAME} → ${NEW_VERSION}，versionCode ${VERSION_CODE} → ${NEW_CODE}"
