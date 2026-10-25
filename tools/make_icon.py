#!/usr/bin/env python3
"""生成应用图标: 深色圆角底 + 青色终端提示符 >_ (几何绘制, 无字体依赖)"""
from PIL import Image, ImageDraw
import os

BG = (11, 16, 21, 255)
FG = (77, 208, 225, 255)

def draw(n):
    S = n * 8
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    r = int(S * 0.22)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=BG)
    w = int(S * 0.085)
    # >
    d.line([(S*0.26, S*0.34), (S*0.47, S*0.50), (S*0.26, S*0.66)],
           fill=FG, width=w, joint="curve")
    # _
    y = S * 0.68
    d.line([(S*0.54, y), (S*0.78, y)], fill=FG, width=w)
    return im.resize((n, n), Image.LANCZOS)

for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    d = f"res/mipmap-{dpi}"
    os.makedirs(d, exist_ok=True)
    draw(px).save(f"{d}/ic_launcher.png")
    print(d, px)
draw(420).save("/data/data/com.termux/files/home/new_icon.png")
