# TermuxBridge 1.8.4 — i18n + 文案重写 + Release 交接单

## 0. 环境事实（不要猜，按这个来）
- 仓库: `/data/data/com.termux/files/home/tbridge-app`，包名 `lll.bridge.termux`，AGPL-3.0，remote `https://github.com/AndyerNotFound/TermuxBridge`
- 语言: Java + AGP/Material3（`com.google.android.material:material:1.14.0`），compileSdk 36 / minSdk 28
- 真源: `app/src/main/java`；`src/` 是镜像，改完跑 `./tools/sync-src.sh`
- 构建: `TBRIDGE_KS_PASS=<keystore-password> ./build-apk.sh`（内部带 `TBRIDGE_SKIP_LINT=1`；Gradle 不在 PATH）
- 装机: `cat app/build/outputs/apk/release/app-release.apk | ~/Shizuku/rish -c "pm install -r -S <bytes>"`
- 服务端口: `127.0.0.1:8021`，JSON 行协议；客户端为 `~/bin/tb`（`tbridge` 脚本）
- 已装版本: 1.8.3 (versionCode 14)；下一版: **1.8.4 / versionCode 15**

## 1. 硬规则（违反即返工）
1. 每次 APK 变更必须同步 bump versionCode + versionName（同批）。
2. **一批改动只允许一次 `build-apk.sh`**。编译错误导致的修复重编是唯一例外。禁止"加探针→编译→验证"的循环；诊断一律走运行时 JSON 协议（`trace`/事件流/`overlay.tree`）。
3. 禁 `monkey`、`force-stop`、`pm clear` 等破坏性测试。
4. 改文件前备份（`.bak-<tag>`），改完读回验证；禁止凭印象重写整文件。
5. 未经确认不得增删/升降依赖。
6. 面向用户的文档用英文；本文档内的中文只用于说明。

## 2. 当前代码状态（交接时必须知道）
- 已装 1.8.3 运行时验证通过：
  - 点 overlay `textinput` → `edit_request` → 透明 `TextInputActivity` 起来 + IME 正常
  - 输入 + OK → `change {value}` → `overlay.values` 同步写回
  - 点卡片外 → `cancel`；返回键 → 先收 IME，再 `cancel`；不劫持系统返回
  - 连续点击 → 只产生 1 次 `edit_request`
- 磁盘上**未编译**改动：
  - `OverlayManager.java`：去重守卫由 `EDITOR_OPEN` 布尔量改为 `LAST_EDIT_KEY` + 700ms 时间窗（修"编辑器被别的 App 顶到后台后标志卡死"）
  - `TextInputActivity.java`：已含 `OnBackInvokedDispatcher`（API 33+ 返回回调）、`onDestroy → releaseEditor()`
- 备份文件：`*.bak-v161`、`*.bak-pre-v181`（勿删）

## 3. 本次任务范围

### 3.1 字符串外置（i18n）
现状：`res/values/strings.xml` 只有 1 条（`acc_desc`）。UI 文案硬编码在 Java 里，共 **72 条中文字面量，分布在 35 个文件**。

分布（按条数）：MainActivity 35、OverlayManager 31、BridgeA11yService 12、GestureController 10、CmdsRole/CmdsMedia/CmdsApps 各 8、Dispatcher 7、BridgeImeService 7、CmdsOverlay/CmdsCore/BridgeService 各 6、ScreenshotActivity 5、CmdsPower/CmdsListen/CmdsInteract/ClipCatchActivity 各 4、其余 1–3。

要求：
- 语言目录：`values/` = **英文**（source of truth），`values-zh/` = 中文。
- 命名：`<area>_<element>[_<variant>]`，全小写下划线，例如
  `main_hint_port`、`main_btn_save_port`、`main_toast_port_saved`、`err_overlay_permission`、`overlay_hint_input`、`notif_channel_service`。
- 含变量用位置化占位符：`%1$s` / `%1$d`，禁止裸拼字符串。
- 新增 `res/xml/locales_config.xml` + manifest `android:localeConfig="@xml/locales_config"`（API 33+ 系统语言选择器可见），locales = `en`, `zh`。
- 需要覆盖的非 View 文案：app_name、无障碍服务描述、IME 名称、通知渠道名/描述、前台服务通知、widget label、Activity label、Toast。
- **不本地化**：协议返回的错误字符串（`{"ok":false,"error":...}`）——那是机器接口，保持英文单语，避免脚本侧解析随系统语言漂移。列入 `Cmds*`/`Dispatcher` 的中文错误文本按此规则**改成英文常量**，不进 strings.xml。

### 3.2 文案重写（两种语言都要重写，不是翻译）
规则：
- 句首动词；不出现"请/您/亲"；不出现感叹号；不写"成功啦"这类填充。
- 按钮 2–4 字（中文）/ 1–2 词（英文），例如 `保存` / `Save`。
- 空状态 = 一行说明 + 一个动作；错误 = 现象 + 下一步，例如
  `悬浮窗权限未授予` + `去授权` / `Overlay permission required` + `Grant`。
- 权限类提示必须给出可执行的路径，例：`设置 > 应用 > 特殊权限 > 显示在其他应用上层`。
- 术语统一表（中/英）：悬浮窗 overlay、无障碍 accessibility、服务 service、输入法 IME、端口 port、token 保持 token。

### 3.3 文档
- `docs/PROTOCOL.md`（若不存在则新建）：补 `overlay` 事件 `edit_request` / `change` / `cancel` / `edit_failed` 的字段与触发条件；补 `textinput` 节点的 `title` 字段。
- `README.md`：Language 小节说明 en/zh，其余不动。

### 3.4 版本与发布
- `app/build.gradle`：`versionCode 15`、`versionName "1.8.4"`（当前磁盘为 14 / 1.8.3）。
- Release 资产：`TermuxBridge-1.8.4.apk`、`tbridge_1.8.4_all.deb`、`SHA256SUMS`。
- Release notes 英文，结构固定：
  ```
  ### Added
  ### Changed
  ### Fixed
  ```
  内容至少含：en/zh 资源化、文案重写、编辑器去重守卫改为时间窗、返回键 `cancel` 事件、协议文档补事件。

## 4. 交付清单（文件级）
| 动作 | 路径 |
|---|---|
| 新建 | `app/src/main/res/values-zh/strings.xml` |
| 重写 | `app/src/main/res/values/strings.xml`（英文全量） |
| 新建 | `app/src/main/res/xml/locales_config.xml` |
| 改 | `app/src/main/AndroidManifest.xml`（`android:localeConfig`） |
| 改 | `MainActivity.java`（35 处 → `getString`） |
| 改 | `OverlayManager.java`（31 处 UI 文案 → 资源；协议错误 → 英文常量） |
| 改 | 其余 33 个含中文的 Java 文件（同规则） |
| 改 | `app/build.gradle`（版本号） |
| 新建/改 | `docs/PROTOCOL.md`、`README.md` |

## 5. 验收（全部运行时，不再编译）
```bash
# 1 资源不缺失 / 无硬编码残留
grep -rnP '"[^"]*[\x{4e00}-\x{9fff}]' app/src/main/java/lll/bridge/termux/*.java | grep -v '^.*//'   # 期望 0 行（注释除外）

# 2 中英切换
~/Shizuku/rish -c "settings put system system_locales zh-CN"   # 重启 MainActivity 后看中文
~/Shizuku/rish -c "settings put system system_locales en-US"   # 看英文

# 3 功能回归（1.8.3 已验证的四条路径，重跑一遍）
tb ping
tb overlay show '{"id":"t","x":0,"y":0,"w":240,"view":{"type":"col","children":[{"type":"textinput","id":"n","hint":"name","title":"Name"}]}}'
# 点击输入框坐标: tb overlay tree / bounds 取 px，不要用 dp 换算
# 期望: edit_request → 输入 → OK → change{value} → overlay values 同步
# 期望: 点卡片外 → cancel；返回键 → 先收 IME 再 cancel
```
- APK 体积对比 1.8.3（2.6 MB 量级），资源化后不应明显变大。
- `aapt2 dump resources` 或 `unzip -l` 确认 `values-zh` 打包进 APK。

## 6. 执行顺序（一次编译）
1. 抽词 → 写 `values/strings.xml`(en) + `values-zh/strings.xml`(zh) → 改 Java 引用 → 改 Manifest → 改 gradle 版本
2. 全仓 grep 复核 0 残留 → `./tools/sync-src.sh`
3. **唯一一次** `TBRIDGE_KS_PASS=<keystore-password> ./build-apk.sh`
4. `pm install -r` 一次
5. 跑第 5 节验收
6. 文档 + commit + push（`git -c http.proxy=http://127.0.0.1:7890 push`）→ `gh release create v1.8.4`

## 7. 未决/风险
- 协议错误信息改英文是**破坏性变更**（脚本可能匹配中文文案）。若担心，可选：保留中文但加 `error_code` 字段。默认采用英文 + 后续加 code。
- 72 条里有一部分是 `toast` 拼接（`"端口已保存: " + p`），必须改占位符，否则 i18n 失效。
- `OverlayManager` 的 31 条里含 `overlay` 节点默认 `title`（`"输入"`），需用 `getString`，注意它当前取的是 `ctx`（可能是 `ContextThemeWrapper`），确保能解析资源。
