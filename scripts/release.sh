#!/usr/bin/env bash
# 发布：签名构建 → 归档到 dist/ → 生成 SHA-256 → 打开目录并选中 APK。
#
# 本脚本只做"产出可分发文件"这一步，不做任何上传（应用商店 / 网盘 / GitHub Release）。
# 上传属于独立动作，需要时另行执行。
#
# 产物命名由 Gradle 决定: dist/release<版本号>_<打包时间>.apk
# 例:                   dist/release0.1.0_2610310945.apk
#
# 用法:
#   bash scripts/release.sh
#   bash scripts/release.sh --no-open    # 不打开窗口（CI / 远程终端）

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

OPEN=1
for arg in "$@"; do
  case "$arg" in
    --no-open) OPEN=0 ;;
    -h|--help)
      sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数: $arg（可用: --no-open）" >&2
      exit 1
      ;;
  esac
done

detect_gradle
check_sdk
read_version

cd "$ROOT_DIR"

if [ ! -f "$ROOT_DIR/keystore.properties" ]; then
  cat >&2 <<'EOF'
错误：缺少 keystore.properties，release 包无法签名。

  1) 复制模板：
       cp keystore.properties.example keystore.properties
  2) 生成密钥（首次）：
       mkdir -p release
       keytool -genkeypair -v -keystore release/tvboard.jks \
         -alias tvboard -keyalg RSA -keysize 2048 -validity 10000
  3) 在 keystore.properties 中填入 storePassword / keyPassword
EOF
  exit 1
fi

echo "==> 版本: ${VERSION_NAME} (${VERSION_CODE})"
echo "==> clean assembleRelease"
"$GRADLE_CMD" clean assembleRelease

SRC="$(latest_apk "$ROOT_DIR/app/build/outputs/apk/release")"
if [ -z "$SRC" ]; then
  echo "错误：未找到 release APK" >&2
  exit 1
fi

# 兜底校验：没签名说明 signingConfig 没生效，发出去也装不上
if ! unzip -l "$SRC" 2>/dev/null | grep -qE 'META-INF/.*\.(RSA|DSA|EC)'; then
  echo "错误：产物未签名，请检查 keystore.properties 字段是否完整" >&2
  exit 1
fi

# 校验签名身份：签名文件存在 ≠ 用的是本项目那把 jks。
# 换了密钥库打出来的包同样"已签名"，但装到已有旧包的设备上会报
# INSTALL_FAILED_UPDATE_INCOMPATIBLE，这种错误发出去才被发现代价最高。
EXPECTED_CERT="${TVBOARD_CERT_SHA256:-2A:01:88:1B:C9:2B:CC:2C:0E:5D:1B:56:A5:81:D9:69:FE:B3:5F:E5:1D:24:EA:04:B1:92:A4:C6:A1:B4:93:BD}"
CERT="$(apk_cert_sha256 "$SRC" || true)"
if [ -n "$CERT" ] && [ "$CERT" != "$EXPECTED_CERT" ]; then
  cat >&2 <<EOF
错误：签名指纹与项目密钥不一致，说明用错了密钥库。

  期望: $EXPECTED_CERT
  实际: $CERT

若确实换了新密钥（例如旧 jks 丢失重建），设 TVBOARD_CERT_SHA256 覆盖期望值：
  TVBOARD_CERT_SHA256="$CERT" bash scripts/release.sh
并记住：新签名的包无法覆盖安装旧包，设备上必须先卸载。
EOF
  exit 1
fi

mkdir -p "$DIST_DIR"
check_apk_name "$SRC" || true
TARGET="$DIST_DIR/$(basename "$SRC")"
cp -f "$SRC" "$TARGET"

HASH="$(sha256_of "$TARGET")"
echo "$HASH  $(basename "$TARGET")" > "$TARGET.sha256"

# 随包带一份面向最终用户的启用说明：输入法必须用户手动启用一次，
# 这一步没法自动化，只能把话说到位，否则用户装完会以为没生效。
GUIDE="$ROOT_DIR/docs/安装说明.txt"
if [ -f "$GUIDE" ]; then
  cp -f "$GUIDE" "$DIST_DIR/安装说明.txt"
fi

SIZE="$(du -h "$TARGET" | cut -f1)"
echo ""
echo "==> 发布产物: $TARGET"
echo "==> 体积:     $SIZE"
echo "==> SHA256:   $HASH"
if [ -n "$CERT" ]; then
  echo "==> 签名:     $CERT"
fi
if [ -f "$GUIDE" ]; then
  echo "==> 说明:     $DIST_DIR/安装说明.txt（可一并分发）"
fi
echo ""
echo "提示：电视上安装可用 U 盘拷贝后从文件管理器打开，或直连 adb："
echo "        adb install -r \"$TARGET\""

if [ "$OPEN" = "1" ]; then
  reveal_file "$TARGET"
fi
