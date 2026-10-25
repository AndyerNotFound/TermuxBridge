# TermuxBridge

Termux 与 Android 系统能力之间的"跳板":一个常驻前台服务 + 本地 TCP JSON 接口 + 命令行客户端 `tb`。
补齐纯 CLI 拿不到的能力(截图、通知、剪贴板、传感器、TTS/STT、模拟触控、UI 树…)。

- 包名:`lll.bridge.termux`
- 默认端口:`8021`(APP 内可改,改后自动重启监听)
- 监听地址:仅 `127.0.0.1`

## 安装 / 启动

```bash
cd ~/tbridge-app && ./build.sh          # 产物: build/TermuxBridge.apk
# 安装(需 Shizuku):
cat build/TermuxBridge.apk | ~/Shizuku/rish -c "pm install -r -S $(stat -c%s build/TermuxBridge.apk)"
```

启动服务(任选其一):

```bash
~/Shizuku/rish -c "am start-foreground-service -n lll.bridge.termux/.BridgeService -a lll.bridge.START"
am start-foreground-service -n lll.bridge.termux/.BridgeService -a lll.bridge.START   # 无 Shizuku 时(需 service 已导出)
```

或直接打开 APP 点「启动服务」,并在里面授予权限、逐功能开关。

无障碍能力(截图/触控/UI 树)需开启一次:

```bash
tb a11y-on    # 打开系统无障碍设置, 找到 TermuxBridge 打开
```

## 协议

一行 JSON 请求 → 一行 JSON 响应,连接即断。

```bash
echo '{"cmd":"battery","token":"可选"}' | nc -w 5 127.0.0.1 8021
# {"ok":true,"level":22,"charging":false,"status":"discharging","temp":39.3,"voltage":3.42}
```

失败一律 `{"ok":false,"error":"..."}`。

## 命令

| 类别 | 命令 |
|---|---|
| 元 | `ping` `features` `feature` `proto` |
| 消息 | `toast` `notify` `vibrate` `beep` |
| 剪贴板 | `clip.read` `clip.write`(读取需焦点,走 `ClipCatchActivity`) |
| 信息 | `battery` `net` `bright` `volume` |
| 硬件 | `torch` |
| 定位/传感器 | `location` `sensor.list` `sensor.read` |
| 语音 | `tts` `record`(PCM→WAV) `stt.poll` |
| 交互 | `share` `open` `dial` `sms` |
| 截图 | `shot`(无障碍) `shot.poll`(MediaProjection) |
| 触控/UI | `tap` `swipe` `ui` `a11y` |

## Termux 包

```bash
cd ~/tbridge-app && ./package.sh          # → dist/tbridge_1.0.0_all.deb
dpkg -i dist/tbridge_1.0.0_all.deb        # 或: apt install ./dist/tbridge_1.0.0_all.deb
```

安装后得到 31 个命令(Termux:API 风格,不与 `termux-*` 冲突):

| 命令 | 说明 |
|---|---|
| `tbridge-toast TEXT` | 弹 Toast,`-d long` 长时 |
| `tbridge-notification -t 标题 -c 内容` | 通知栏,`-i id` 覆盖 |
| `tbridge-vibrate [-d ms]` / `tbridge-beep` | 振动 / 提示音 |
| `tbridge-tts-speak TEXT` | 朗读 |
| `tbridge-clipboard-get` / `tbridge-clipboard-set` | 剪贴板(自动焦点捕获;支持 stdin) |
| `tbridge-battery-status` | 电池 JSON |
| `tbridge-net-status` / `tbridge-wifi-connectioninfo` | 网络 |
| `tbridge-location` | 定位 |
| `tbridge-sensor -l` / `-s NAME` | 传感器列表 / 单次采样(CSV) |
| `tbridge-torch on|off` | 手电筒 |
| `tbridge-brightness [0-255]` | 屏幕亮度 |
| `tbridge-volume [STREAM [0-MAX]]` | 音量 |
| `tbridge-share [TEXT|-]` / `tbridge-open URL` / `tbridge-dial NUM` / `tbridge-sms-send NUM [TEXT]` | 系统交互 |
| `tbridge-screenshot FILE` | 截图(无障碍优先,自动回退投影授权) |
| `tbridge-tap X Y` / `tbridge-swipe X1 Y1 X2 Y2 [MS]` / `tbridge-ui [DEPTH]` | 触控 / 界面树 |
| `tbridge-record SECONDS [FILE]` / `tbridge-stt` | 录音 / 语音识别 |
| `tbridge-features` / `tbridge-feature on|off NAME` | 模块开关 |
| `tbridge-service start|stop|status` / `tbridge-ping` | 服务控制 |

任意命令加 `--json` 输出原始 JSON。`tb` 是同一程序,支持短名:
`tb battery` `tb clip` `tb shot` `tb notify ...`。

## 客户端

`tb`(已链接到 `~/bin/tb`),环境变量:`TBRIDGE_PORT` `TBRIDGE_HOST` `TBRIDGE_TOKEN` `TBRIDGE_TIMEOUT`。

```bash
tb ping
tb toast "构建完成"
tb notify "备份" "已完成 $(date +%H:%M)"
tb clip                      # 读剪贴板(自动拉起焦点捕获)
tb clip-set "文本"
tb battery | jq .level
tb sensor light | jq .values
tb tts "构建成功"
tb shot ~/screen.png         # 无障碍, 瞬间完成, 无弹窗
tb shot --mp ~/screen.png    # MediaProjection, 需授权一次
tb ui 8 | jq -r '.nodes[] | select(.clickable) | "\(.text) \(.bounds)"'
tb tap 640 1600
tb swipe 640 2000 640 800 300
tb record 3                  # 生成 rec_*.wav
```


## 从源码构建

依赖(全部本地、无需网络):`javac`、`aapt2`、`d8`、`zipalign`、`apksigner`、`keytool`。
Termux 下 `pkg install openjdk-21 aapt aapt2 apksigner zipalign d8` 即可,Android SDK 仅需 `android.jar`。

```bash
export ANDROID_SDK=$HOME/android-sdk        # 含 platforms/android-36/android.jar
export TBRIDGE_KS_PASS=your-pass            # 首次会生成 ~/.android/bridge.keystore
./build.sh                                  # → build/TermuxBridge.apk
./package.sh                                # → dist/tbridge_1.0.0_all.deb
```

安装(需 Shizuku 或手动点安装页):

```bash
cat build/TermuxBridge.apk | ~/Shizuku/rish -c "pm install -r -S $(stat -c%s build/TermuxBridge.apk)"
```

## 兼容性

- minSdk 28 / targetSdk 36(Android 9+,在 Android 16 实机验证)
- 截图默认走无障碍 API(Android 11+ 无弹窗);MediaProjection 作为回退
- 剪贴板读写需窗口焦点 → 透明 Activity 捕获(Android 10+ 系统限制)
- 后台启动服务受 Android 12+ 限制 → 提供 Shizuku(`rish`)回退

## 已知限制

未实现:通知监听、悬浮窗、VPN 服务、USB 独占。


## 自定义悬浮窗

用 JSON 描述布局，服务端动态构建 View 并叠加到屏幕。需要悬浮窗权限（设置 → 特殊应用权限 →
显示在其他应用上层，或用 Shizuku：`appops set lll.bridge.termux SYSTEM_ALERT_WINDOW allow`）。

```bash
tbridge-overlay show card.json     # 或 tbridge-overlay demo 看示例
tbridge-overlay update card.json   # 原地更新(不重建窗口)
tbridge-overlay bounds             # 查窗口真实屏幕坐标
tbridge-overlay events             # 取点击事件(取走即清空)
tbridge-overlay values <id>        # 读输入框内容
tbridge-overlay tree demo          # 视图树快照(调试坐标/尺寸)
tbridge-overlay hide [id|all]
```

布局节点：

| type | 说明 |
|---|---|
| `col` / `row` | 垂直/水平容器，`children` 数组 |
| `text` | 文本，`size` `color` `align` |
| `button` | 按钮，`id` 用于回传事件，`text` |
| `input` | 输入框，`hint` `multiline`，内容用 `overlay.values` 取 |
| `progress` | 进度条，`max` `value` |
| `divider` | 分隔线（默认高 1dp、宽填满） |
| `spacer` | 空白占位（默认高 10dp） |

通用属性：`w` `h`（数字=px，或 `wrap`/`match`）、`m` 外边距、`pad` 内边距、
`bg` `radius` `alpha` `weight` `gravity`（窗口级：`top|start` 等）。
点击事件形如 `{"type":"click","window":"demo","id":"ok","text":"确认","ts":...}`。

注意：含 `divider`/`spacer` 的卡片若未指定 `w`，窗口宽度默认取 300dp，
避免这类无固有宽度的节点把 `WRAP_CONTENT` 容器撑满屏幕。



## 通知: 渠道 / 进度 / Live Update / 动作按钮

```json
{"cmd":"notify","id":9001,"channel":"live","channel_name":"进度",
 "title":"备份中","text":"6/10","ongoing":true,
 "progress":{"value":60,"max":100,"live":true,"segments":["FF6750A4"]},
 "actions":[{"label":"暂停","json":"{\"cmd\":\"toast\",\"text\":\"已暂停\"}"}]}
```

- `progress.live=true` 在 Android 16 上走 `Notification.ProgressStyle` 并请求提升为
  Live Update（状态栏胶囊）。需要 appop: `appops set lll.bridge.termux POST_PROMOTED_NOTIFICATIONS allow`，
  否则 `can_promote` 为 false（系统会照常显示普通通知）。
- `actions[].json` 是动作按钮要执行的桥命令，点按后由 `BridgeReceiver` 执行。

```bash
tbridge-notification -t 标题 -c 内容     # 简易通知
tbridge-notif status|recent [N]|clear|perm|dismiss KEY
```

渠道管理（渠道属性创建后不可改，只能新建）：

```bash
tbridge ping  # 或直接调用协议
# notify.channel {id,name,importance,sound,vibration}
# notify.channels → 列表
```

## 通知监听

`BridgeNotifListener` 缓存最近 80 条通知（`notif.recent`），可读标题/正文/进度/动作/包名。

```bash
rish -c "cmd notification allow_listener lll.bridge.termux/lll.bridge.termux.BridgeNotifListener"
tbridge-notif status   # connected/access
tbridge-notif recent 10
```

## Material You 取色 / 模糊

```bash
tbridge-theme colors   # system_accent1..3 / neutral1..2 + 壁纸主色
tbridge-theme blur     # {supported, cross_window_blur_enabled}
```

悬浮窗模糊：`{"id":"x","blur":30,"view":{...}}` → `FLAG_BLUR_BEHIND` + `blurBehindRadius`
（实测窗口属性生效，需设备支持跨窗口模糊）。

## 应用启动 / 深链 / 关联启动

```bash
tbridge-app list [关键词]          # 可启动应用(235 个)
tbridge-app launch PKG [URI]       # 启动应用, 可带深链
tbridge-app info PKG               # 版本/UID/targetSdk
tbridge-app settings [PKG]         # 应用详情页(打开关联启动/自启动开关)
tbridge-open URL                   # 系统选择器打开链接
```

ColorOS 的"关联启动/自启动"是 OEM 私有开关，只能跳到应用详情页由用户手动开启；
被拦截时 `app.launch` 返回 `启动失败(可能被关联启动限制)`。

## 桌面小组件

```bash
tbridge-widget info      # 已放置实例数
tbridge-widget update    # 刷新内容(电量/端口/时间)
tbridge-widget pin       # 请求固定到桌面(需桌面支持, 实测返回 true)
```

## 悬浮窗手势

带 `id` 的节点（按钮/文本）支持五种触控，事件写入同一队列：

| 手势 | 事件 |
|---|---|
| 单击 | `{"type":"click","gesture":"tap"}` |
| 双击 | `{"type":"gesture","gesture":"double"}` |
| 三击 | `{"type":"gesture","gesture":"triple"}` |
| 长按 500ms | `{"type":"gesture","gesture":"long"}` |
| 拖动 | `drag:start` → `drag:move`(约 40ms 一条) → `drag:end`，带 `dx/dy/ms` |
| 快速划动 | `{"type":"gesture","gesture":"swipe:left|right|up|down"}` |

单击延迟 280ms 发出（用于区分双击）。移动超过 20dp 判为拖动，
释放时若 <250ms 且位移 >56dp 才算划动，否则是 `drag:end`。

窗口拖移：`{"id":"x","drag_window":true,...}`，按住窗口空白处即可拖动整个窗口。

### 原地更新

`overlay.update` 对同 id 窗口做视图树 diff：文本/颜色/进度/内边距/子节点增删就地改，
不 remove/add 窗口（不闪、不夺焦）。结构不兼容时才整树重建，响应里 `in_place` 标明走了哪条路。

实测（同一张 3 节点卡片，连续 5 次刷新）：

| 路径 | 设备侧耗时 |
|---|---|
| 重建 `show` | 29 / 12 / 20 / 19 ms |
| 原地 `update` | 4 / 1 / 1 / 0 ms |

## 电池优化豁免

Android 的 Doze 会在息屏后掐断后台网络, 常驻服务可能被冻结。授权后不再受限制：

```bash
tbridge-power status      # {ignoring, save_mode, interactive, doze_idle}
tbridge-power request     # 弹系统授权框, 点「Allow」
tbridge-power settings    # 打开系统电池优化列表
```

也可以用 Shizuku 免弹窗:

```bash
rish -c "cmd deviceidle whitelist +lll.bridge.termux"
```

对应权限 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（仅系统对话框使用，不上架应用商店所以无 Play 政策问题）。

## 安全

- 只监听 `127.0.0.1`,不暴露到局域网
- 可选 token(APP 内设置 `TBRIDGE_TOKEN` 后,所有请求必须带 `"token"`)
- 广播备用通道只允许无 UI 依赖的命令
- 不做任何联网上传;无第三方 SDK/依赖

## 结构

```
src/lll/bridge/termux/
  BridgeService.java   前台服务 + TCP server
  Dispatcher.java      命令路由 + 功能开关
  Cmds*.java           各功能模块
  BridgeA11yService.java  无障碍: 截图/手势/UI 树
  *Activity.java       透明 Activity(剪贴板焦点/投影授权/语音识别)
  BridgeReceiver.java  广播备用通道
```

## 许可证

本项目采用 **GNU Affero General Public License v3.0 (AGPL-3.0)**。

```
Copyright (C) 2026 AndyerNotFound

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published
by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

全文见 [LICENSE](LICENSE)。注意 AGPL 第 13 条:若你将本软件作为网络服务提供,
必须向使用者提供对应的完整源代码。
