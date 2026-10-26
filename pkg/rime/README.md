# rime-daemon (Termux side)

The IME in the app talks JSON over the abstract unix socket lll.bridge.termux.rime to this daemon, which owns
librime + the wanxiang dictionary (the app sandbox cannot link Termux libs).

## Install (inside Termux)

    pkg install librime-with-lua      # TUR repo, brings libRime + opencc + marisa
    ./pkg/rime/build.sh               # build + install ~/rime/daemon/rime-daemon
    mkdir -p ~/rime/wanxiang          # dictionary dir (see layout below)

    # dictionary: base-dicts.zip + wanxiang schemas from
    # https://github.com/amzxyz/rime-wanxiang/releases/tag/dict-nightly
    # layout: ~/rime/wanxiang/{*.yaml,lua/,opencc/,dicts/*.dict.yaml,build/}
    tbridge-rime deploy               # precompile (or let the daemon do it on first start)
    tbridge-rime start

Verify: python3 -c 'import socket;s=socket.socket(socket.AF_UNIX);s.connect("\0lll.bridge.termux.rime");s.sendall(b'{"op":"key","key":110}\n');print(s.recv(4096).decode())'

Optional: register in Termux:Boot for start-on-boot.
