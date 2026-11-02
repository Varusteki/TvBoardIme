# 构建与发布脚本

构建、发布、改版本号、生成词库四件事全部收敛在 `scripts/` 下，与业务代码解耦。
**日常改代码不需要跑这里的任何脚本**，构建时机由使用者自行决定。

## 目录

| 脚本 | 职责 | 产物 |
|---|---|---|
| `env.sh` | 公共变量与函数（版本解析、Gradle 探测、SDK 校验、定位文件） | 否（被 source） |
| `build.sh` | 编译 APK，归档到 `dist/` | `dist/<类型><版本>_<时间>.apk` |
| `release.sh` | 签名构建 + 签名校验 + SHA-256 | 同上，另加 `.sha256` |
| `bump-version.sh` | 改 `versionName` / `versionCode` | 否 |
| `enable-ime.sh` | 在已连接设备上启用并选中本输入法（走 `ime` 系统命令，不点界面） | 否 |
| `lan-bridge.py` | 把局域网请求转发到 `adb forward` 端口，用于模拟器跨屏联调 | 否 |
| `build-dict.py` | 生成 `assets/dict.db` + `dict.version`（拼音词库） | 否（产物是应用资源） |

## 产物命名

**命名规则由 Gradle 定义（`app/build.gradle.kts` 的 `androidComponents`），脚本不再改名。**
这样 Android Studio、命令行、CI 三条路产出的文件名完全一致；只在脚本里改名的话，
Studio 打出来仍然是 `app-debug.apk`，就会出现两套命名。

```
<包名>_<构建类型><版本号>_<打包时间>.apk
     ↑         ↑        ↑         ↑
com.tvboard.ime  debug   0.1.0  2610311006  ← yyMMddHHmm（例：26-10-31 10:06）
              release
```

实际例子：

| 命令 | 产物 |
|---|---|
| `bash scripts/build.sh` | `dist/com.tvboard.ime_debug0.1.0_2610311006.apk` |
| `bash scripts/build.sh release` | `dist/com.tvboard.ime_release0.1.0_2610311006.apk` |
| `bash scripts/release.sh` | 同上 + `.apk.sha256` |

包名取 Gradle 的 `applicationId`（`com.tvboard.ime`），不是 APK 内的 namespace，
后续改包名时文件名会跟着变。

版本号取自 `app/build.gradle.kts` 的 `versionName`，改版本请用 `bump-version.sh`，
不要手改。时间戳精确到分钟：**同一分钟内重复构建会覆盖同名产物**，这是刻意设计——
否则 `dist/` 会堆出十几份同版本的包，反而分不清哪份是最终交付的。

脚本会校验 Gradle 产物名是否符合上面的模式，不符合时打印警告（说明命名规则失效），
但不会中断构建。

## 构建后自动定位产物

脚本跑完会调用系统文件管理器，打开 `dist/` 并选中刚生成的 APK：

- Windows：`explorer /select,<文件>`
- macOS：`open -R <文件>`
- Linux：`xdg-open <目录>`

不需要这个行为时加 `--no-open`（CI、远程终端、批量脚本里建议加）：

```bash
bash scripts/build.sh --no-open
bash scripts/release.sh --no-open
```

非交互验证时也可以用环境变量 `TVBOARD_NO_REVEAL=1`，它只打印将要打开的位置。
定位失败不会让构建失败——脚本的核心职责是产出文件，弹窗口只是顺手交付。

## 前置条件

1. **Gradle**：优先使用仓库根的 `gradlew`；没有时退回系统 `gradle`。
   若两者都没有，用 Android Studio 打开一次本目录即可生成 wrapper。
2. **Android SDK**：`local.properties` 里写 `sdk.dir=...`，或设置环境变量
   `ANDROID_HOME` / `ANDROID_SDK_ROOT`。
3. **签名**（仅 release）：`keystore.properties` 存在且字段完整，模板见
   `keystore.properties.example`。

## 常用命令

```bash
# 调试包
bash scripts/build.sh

# 发布包（自动签名）
bash scripts/build.sh release

# 全量构建
bash scripts/build.sh release --clean

# 正式发布：签名校验 + SHA-256
bash scripts/release.sh

# 构建但不弹窗口
bash scripts/build.sh --no-open

# 改版本号，versionCode 自动 +1
bash scripts/bump-version.sh 0.2.0

# 在已连接设备上启用并选中本输入法（免点界面）
bash scripts/enable-ime.sh

# 装 dist/ 最新包 + 启用选中，一条命令到位
bash scripts/enable-ime.sh --install

# 恢复原状：切回其它输入法
bash scripts/enable-ime.sh --off

# 模拟器跨屏联调（手机扫码连模拟器），详见 docs/02-模拟器跨屏联调.md
# 一条命令搞定 adb forward + 局域网转发 + 二维码地址
C:\python313\python.exe scripts\lan-bridge.py

# 重新生成拼音词库（约 20 秒；首次需联网下载 8MB 数据源）
python scripts/build-dict.py

# 强制重下数据源后再生成
python scripts/build-dict.py --refresh
```

## 词库

`app/src/main/assets/dict.db` 由 `build-dict.py` 生成，**不要手工编辑**。
数据源全部是 MIT 许可（pinyin-data、python-pinyin、jieba），因此产物可以随闭源 APK 分发——
这也是刻意没有采用 rime-ice（GPL-3.0）的原因。

脚本会同时写出 `dict.version`（`sha1-字节数`）。应用只在该戳变化时才把词库从 assets
拷到 `filesDir`，所以改完词库直接跑脚本即可，无需改代码或手动清数据。

数据源缓存在 `scripts/.cache/`（约 8MB），已在 `.gitignore` 中排除。
脚本自带自检：生成后会打印 `nihao / dianshi / 二维码 / 多音字` 等若干条检索结果，
可据此快速判断词库是否正常。

## 启用输入法

Android TV 的系统设置没给第三方应用留任何「直达键盘启用页」的入口：
`Settings.ACTION_INPUT_METHOD_SETTINGS` 落到 `com.android.tv.settings/.inputmethod.KeyboardActivity`，
而里面那行 **Manage keyboards** 只是同一 Activity 内的嵌套 Fragment
（`res/xml/keyboard.xml` 里 `android:fragment="com.android.tv.settings.inputmethod.AvailableVirtualKeyboardFragment"`），
TvSettings 根本没有对应 Activity，实测 `android:show_fragment` 之类的 extra 也不会被采纳。
所以「代码里直接跳到第二层」这条路不存在，手工只能一层层点。

调试时用脚本代替：

```bash
bash scripts/enable-ime.sh
```

它执行的是系统的 `ime enable` / `ime set`，等价于在设置里勾选并选中，不需要点任何界面。
也能反过来用 `--off` 复原，便于反复验证首次安装流程。

## 约定

- `build.sh` 与 `release.sh` **只负责产出** `dist/` 下的文件，**不上传任何地方**。
  分发动作（应用商店、网盘、GitHub Release）另行执行。
- `release.sh` 会顺带把 `docs/安装说明.txt` 拷成 `dist/安装说明.txt`：输入法必须用户手动启用一次，
  这一步没法自动化，面向最终用户的说明要和包一起发。
- `dist/debug*` 仅供本机调试，不要对外分发；对外只发 `dist/release*`。
- `dist/`、`keystore.properties`、`*.jks` 已在 `.gitignore` 中排除，不要提交。
- 版本号只有一处真相：`app/build.gradle.kts`。脚本从那里读取。
