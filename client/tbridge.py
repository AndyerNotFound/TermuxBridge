"""TermuxBridge 客户端库(纯标准库, 无第三方依赖)。

    import tbridge
    b = tbridge.Bridge()
    b.toast("构建完成")
    print(b.battery()["level"])
    open("s.png","wb").write(b.screenshot())

传输: 显式广播 lll.bridge.termux.CMD 下发请求, APP 连回本进程的 127.0.0.1
临时端口回包。所有方法返回 dict; 失败时抛 tbridge.BridgeError。
"""

import base64
import json
import os
import socket
import subprocess

__all__ = ["Bridge", "BridgeError"]

DEFAULT_TOKEN = os.environ.get("TBRIDGE_TOKEN", "")
RECEIVER = "lll.bridge.termux/.BridgeReceiver"
ACTION = "lll.bridge.termux.CMD"


class BridgeError(RuntimeError):
    pass


class Bridge:
    def __init__(self, sock=None, token=DEFAULT_TOKEN, timeout=30.0, receiver=RECEIVER):
        # sock kept for backwards compatibility; the transport is broadcast-based
        self.token, self.timeout, self.receiver = token, timeout, receiver

    # ---------- 底层 ----------
    def call(self, cmd, **params):
        req = {"cmd": cmd}
        if self.token:
            req["token"] = self.token
        req.update(params)

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        buf = b""
        try:
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            srv.settimeout(self.timeout)
            callback = "127.0.0.1:%d" % srv.getsockname()[1]
            try:
                subprocess.run(["am", "broadcast", "-n", self.receiver, "-a", ACTION,
                                "--es", "json", json.dumps(req, ensure_ascii=False),
                                "--es", "res", callback],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=min(self.timeout, 30))
            except Exception as e:
                raise BridgeError(f"广播发送失败: {e}") from e
            try:
                conn, _ = srv.accept()
            except socket.timeout as e:
                raise BridgeError(
                    "无响应 — 确认 TermuxBridge 已启动 (tbridge-service start), token 是否匹配") from e
            with conn:
                conn.settimeout(self.timeout)
                while not buf.endswith(b"\n"):
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
        finally:
            srv.close()

        if not buf.strip():
            raise BridgeError("空响应(APP 未回包)")
        resp = json.loads(buf.decode())
        if not resp.get("ok"):
            raise BridgeError(resp.get("error", "unknown error"))
        return resp

    # ---------- 元 ----------
    def ping(self):        return self.call("ping")
    def features(self):    return self.call("features")
    def feature(self, name, enable=True):
        return self.call("feature", name=name, enable=bool(enable))

    # ---------- 消息 ----------
    def toast(self, text, duration="short"):
        return self.call("toast", text=text, duration=duration)

    def notify(self, title, text, id=None, channel="bridge"):
        kw = dict(title=title, text=text, channel=channel)
        if id is not None:
            kw["id"] = id
        return self.call("notify", **kw)

    def vibrate(self, ms=300):        return self.call("vibrate", ms=ms)
    def beep(self, type="beep"):      return self.call("beep", type=type)
    def tts(self, text, lang="zh-CN", rate=1.0, pitch=1.0):
        return self.call("tts", text=text, lang=lang, rate=rate, pitch=pitch)

    # ---------- 剪贴板 ----------
    def clip(self):
        return self.call("clip.read").get("text", "")

    def clip_set(self, text):
        return self.call("clip.write", text=text)

    # ---------- 状态 ----------
    def battery(self):   return self.call("battery")
    def net(self):       return self.call("net")
    def bright(self, value=None):
        return self.call("bright") if value is None else self.call("bright", action="write", value=value)
    def volume(self, value=None, stream="notification"):
        if value is None:
            return self.call("volume", stream=stream)
        return self.call("volume", action="write", value=value, stream=stream)
    def torch(self, on=True):
        return self.call("torch", action="on" if on else "off")
    def location(self):  return self.call("location")

    def sensor(self, name="accelerometer"):
        return self.call("sensor.read", sensor=name).get("values", [])
    def sensors(self):   return self.call("sensor.list").get("sensors", [])

    # ---------- 交互 ----------
    def share(self, text):   return self.call("share", text=text)
    def open(self, url):     return self.call("open", url=url)
    def dial(self, tel):     return self.call("dial", tel=tel)
    def sms(self, tel, text=""): return self.call("sms", tel=tel, text=text)

    # ---------- 屏幕 ----------
    def screenshot(self, path=None, via="a11y"):
        """a11y = 无障碍接口(无弹窗); mp = MediaProjection(需授权, 见 tb shot --mp)"""
        if via == "a11y":
            data = base64.b64decode(self.call("shot")["png_b64"])
        else:
            raise BridgeError("MediaProjection 需人工授权, 请用 CLI: tb shot --mp")
        if path:
            with open(path, "wb") as f:
                f.write(data)
        return data

    def tap(self, x, y):     return self.call("tap", x=x, y=y)
    def swipe(self, x1, y1, x2, y2, ms=300):
        return self.call("swipe", x1=x1, y1=y1, x2=x2, y2=y2, ms=ms)
    def ui(self, depth=12): return self.call("ui", depth=depth).get("nodes", [])

    # ---------- 录音 ----------
    def record(self, ms=2000, path=None):
        data = base64.b64decode(self.call("record", ms=ms)["wav_b64"])
        if path:
            with open(path, "wb") as f:
                f.write(data)
        return data


if __name__ == "__main__":
    import sys
    b = Bridge()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "ping"
    if cmd == "ping":
        print(json.dumps(b.ping(), ensure_ascii=False))
    elif cmd == "features":
        print(json.dumps(b.features(), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(b.call(cmd, **json.loads(sys.argv[2] or "{}")), ensure_ascii=False))
