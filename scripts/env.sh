#!/usr/bin/env bash
# 公共环境与工具函数。由 build.sh / release.sh / bump-version.sh source 引入。
# 只定义变量与函数，不执行任何构建动作。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_FILE="$ROOT_DIR/app/build.gradle.kts"
DIST_DIR="$ROOT_DIR/dist"
APP_ID="com.tvboard.ime"

VERSION_NAME=""
VERSION_CODE=""
GRADLE_CMD=""

# 从 build.gradle.kts 读取版本，避免版本号出现两份真相
read_version() {
  if [ ! -f "$BUILD_FILE" ]; then
    echo "错误：找不到 $BUILD_FILE" >&2
    exit 1
  fi
  VERSION_NAME="$(grep -m1 'versionName' "$BUILD_FILE" | sed -E 's/.*"([^"]+)".*/\1/')"
  VERSION_CODE="$(grep -m1 'versionCode' "$BUILD_FILE" | sed -E 's/[^0-9]//g')"
  if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "错误：无法从 build.gradle.kts 解析 versionName / versionCode" >&2
    exit 1
  fi
}

# 优先用 wrapper；没有 wrapper 时退回系统 gradle
#
# TVBOARD_GRADLE 可显式指定 Gradle 可执行文件，优先级最高。
# 需要它是因为 PATH 上的 Gradle 未必与 AGP 兼容：AGP 8.5.x 跑不了 Gradle 9.x，
# 而系统里往往装着更新的版本。用法：
#   TVBOARD_GRADLE=/path/to/gradle-8.7/bin/gradle bash scripts/build.sh
detect_gradle() {
  if [ -n "${TVBOARD_GRADLE:-}" ]; then
    if [ ! -x "$TVBOARD_GRADLE" ] && ! command -v "$TVBOARD_GRADLE" >/dev/null 2>&1; then
      echo "错误：TVBOARD_GRADLE 指向的 Gradle 不可执行：$TVBOARD_GRADLE" >&2
      exit 1
    fi
    GRADLE_CMD="$TVBOARD_GRADLE"
  elif [ -x "$ROOT_DIR/gradlew" ]; then
    GRADLE_CMD="$ROOT_DIR/gradlew"
  elif [ -f "$ROOT_DIR/gradlew.bat" ]; then
    GRADLE_CMD="$ROOT_DIR/gradlew.bat"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
  else
    echo "错误：未找到 Gradle。" >&2
    echo "  方案一：用 Android Studio 打开本目录，会自动生成 gradle wrapper" >&2
    echo "  方案二：已装 Gradle 时执行  gradle wrapper --gradle-version 8.7" >&2
    exit 1
  fi
}

# SDK 位置：local.properties 或环境变量，两者至少有其一
check_sdk() {
  if [ -f "$ROOT_DIR/local.properties" ]; then
    return 0
  fi
  if [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    return 0
  fi
  echo "错误：未找到 Android SDK。" >&2
  echo "  请在 $ROOT_DIR/local.properties 中写入：sdk.dir=你的SDK路径" >&2
  exit 1
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "unsupported"
  fi
}

# ---------------------------------------------------------------- 产物命名

# 产物名由 Gradle 侧的 androidComponents 决定
# （<applicationId>_<构建类型><versionName>_<yyMMddHHmm>.apk，见 app/build.gradle.kts）。
# 脚本不再自行改名：否则 Studio 打出来的仍叫 app-debug.apk，会出现两套命名。
#
# 这里只做一次约定校验，命名规则失效时给出提示而不是静默放过。
check_apk_name() {
  local name
  name="$(basename "$1")"
  case "$name" in
    "${APP_ID}"_debug*.apk|"${APP_ID}"_release*.apk) return 0 ;;
    app-*.apk)
      echo "警告：Gradle 仍在使用默认文件名（$name），命名规则未生效。" >&2
      echo "      请检查 app/build.gradle.kts 里的 androidComponents.outputFileName 配置。" >&2
      return 1
      ;;
    *)
      echo "警告：Gradle 产物未按约定命名（$name）。" >&2
      echo "      约定为 ${APP_ID}_<debug|release><版本号>_<yyMMddHHmm>.apk" >&2
      return 1
      ;;
  esac
}

# 取构建目录里最新的 APK。
# 不能用 head -1：目录里可能残留上一次构建的同名/多 flavor 产物，
# ls 的默认顺序是字母序而非时间序，会挑到旧的。
latest_apk() {
  local dir="$1"
  { ls -t "$dir"/*.apk 2>/dev/null || true; } | head -n1
}

# 读出 APK 内签名证书的 SHA-256 指纹（来自 v1 签名段）。
# 未签名或读不出时输出空串——调用方据此判断，不要把空串当成"校验通过"。
apk_cert_sha256() {
  keytool -printcert -jarfile "$1" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
    | head -n1
}

# 在系统文件管理器里打开目录并选中该文件。
# 定位失败不算构建失败——脚本的核心职责是产出文件，打开窗口只是顺手交付。
reveal_file() {
  local file="$1"
  local dir base win
  dir="$(cd "$(dirname "$file")" && pwd)"
  base="$(basename "$file")"

  if [ "${TVBOARD_NO_REVEAL:-0}" = "1" ]; then
    echo "==> [dry-run] 不打开窗口（TVBOARD_NO_REVEAL=1）：$dir → $base"
    return 0
  fi

  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      win="$(cygpath -w "$dir/$base" 2>/dev/null || echo "$dir\\$base")"
      # MSYS 会把以 / 开头的参数当 Unix 路径改写，必须关掉这层转换，
      # 否则 explorer 收到的是 C:/Program Files/Git/select,... 这种无效参数。
      # 路径含空格时 /select 的引号处理不可靠，退化为只打开目录。
      if [[ "$win" == *" "* ]]; then
        MSYS2_ARG_CONV_EXCL='*' explorer.exe "$(cygpath -w "$dir")" >/dev/null 2>&1 || true
      else
        MSYS2_ARG_CONV_EXCL='*' explorer.exe "/select,$win" >/dev/null 2>&1 || true
      fi
      ;;
    Darwin)
      open -R "$file" >/dev/null 2>&1 || true
      ;;
    *)
      xdg-open "$dir" >/dev/null 2>&1 || true
      ;;
  esac
  echo "==> 已在文件管理器中定位: $base"
}
