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
