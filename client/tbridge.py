"""TermuxBridge 客户端库(纯标准库, 无第三方依赖)。

    import tbridge
    b = tbridge.Bridge()                 # 默认抽象 unix socket lll.bridge.termux
    b.toast("构建完成")
    print(b.battery()["level"])
    open("s.png","wb").write(b.screenshot())

所有方法返回 dict; 失败时抛 tbridge.BridgeError。
"""

import base64
import json
import os
import socket

__all__ = ["Bridge", "BridgeError"]

DEFAULT_SOCK = "\0" + os.environ.get("TBRIDGE_SOCK", "lll.bridge.termux")
DEFAULT_TOKEN = os.environ.get("TBRIDGE_TOKEN", "")


class BridgeError(RuntimeError):
    pass


class Bridge:
    def __init__(self, sock=DEFAULT_SOCK, token=DEFAULT_TOKEN, timeout=30.0):
        self.sock, self.token, self.timeout = sock, token, timeout

    # ---------- 底层 ----------
    def call(self, cmd, **params):
        req = {"cmd": cmd}
        if self.token:
            req["token"] = self.token
        req.update(params)
        payload = (json.dumps(req, ensure_ascii=False) + "\n").encode()
        try:
            sk = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            with sk:
                sk.settimeout(self.timeout)
                sk.connect(self.sock)
                sk.sendall(payload)
                buf = b""
                while not buf.endswith(b"\n"):
                    chunk = sk.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
        except OSError as e:
            raise BridgeError(f"连不上 {self.sock!r} — {e}") from e
        if not buf.strip():
            raise BridgeError("空响应(服务未运行? 端口/token 不对?)")
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
