# -*- coding: utf-8 -*-
"""
真机验证：终端连的是不是容器里的登录 shell。

## 背景（2026-11-03）

用户报了两个现象：
  1. 终端连的应该是内置 Linux 系统，而不是 /bin/sh
  2. stty: 'standard input': Inappropriate ioctl for device

根因是 ProotTerminalSessionPort.create() 里写的是：
    sessionArgs(env, listOf("/bin/sh", "-i"))

其中 /bin/sh 绕过了 loginShell() 的探测（容器里装了 bash 也用不上），
-i 在非 tty 路径上会刷 stty 报错。

改成 loginTail(env)（= loginShell(env) + "-l"）之后，需要真机确认：
  - 不再出现 stty 报错
  - 不再出现 can't access tty
  - 能出提示符

## 为什么必须真机

APK 只打 arm64-v8a，模拟器（x86_64）装不上；
而终端依赖 libpty.so / libproot.so，单测里跑不起来。

## 用法

    python tools/verify_device_terminal.py
"""
import os
import re
import subprocess
import time

SDK = os.environ.get("ANDROID_SDK_ROOT", r"C:\Users\sxd\AppData\Local\Android\Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
DEVICE = os.environ.get("PRAXIS_DEVICE", "192.168.1.199:5555")


def sh(cmd, timeout=90):
    try:
        r = subprocess.run([ADB, "-s", DEVICE, "shell", cmd],
                           capture_output=True, timeout=timeout)
        return (r.stdout.decode("utf-8", "replace")
                + r.stderr.decode("utf-8", "replace")).strip()
    except subprocess.TimeoutExpired:
        return "<timeout>"


def dump(tag):
    sh("uiautomator dump --compressed /data/local/tmp/%s.xml 2>&1 >/dev/null" % tag)
    return sh("cat /data/local/tmp/%s.xml 2>/dev/null" % tag)


def parse(xml):
    """把 dump 里的节点解析成 (label, cx, cy)。"""
    out = []
    for m in re.finditer(r"<node[^>]*/?>", xml):
        s = m.group(0)
        tx = re.search(r'text="([^"]*)"', s)
        cd = re.search(r'content-desc="([^"]*)"', s)
        bd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not bd:
            continue
        l, t, r2, b = map(int, bd.groups())
        lab = (tx.group(1) if tx else "") or (cd.group(1) if cd else "")
        out.append((lab, (l + r2) // 2, (t + b) // 2))
    return out


def find(xml, want):
    for lab, cx, cy in parse(xml):
        if lab == want:
            return (cx, cy)
    return None


print("=" * 58)
print("步骤 0：设备状态")
print("=" * 58)
print("  时间:", sh("date +%H:%M"))
print("  pid :", sh("pidof dev.praxis.debug") or "未运行")

print()
print("=" * 58)
print("步骤 1：终端连的是不是容器登录 shell")
print("=" * 58)
sh("am force-stop dev.praxis.debug")
time.sleep(2)
sh("am start -n dev.praxis.debug/dev.praxis.MainActivity")
time.sleep(18)

xml = dump("t0")
tab = find(xml, "终端")
if tab:
    sh("input tap %d %d" % tab)
    time.sleep(6)
    print("  已切到终端页")
else:
    labels = [n[0] for n in parse(xml) if n[0]]
    print("  [警告] 没找到终端 tab，页面文字:", labels[:12])

xml = dump("t1")
labels = [n[0] for n in parse(xml) if n[0]]
alltext = " ".join(labels)
print("  终端页前 12 个文字节点:", labels[:12])
print()
print("  >>> stty 报错？        ", "Inappropriate ioctl" in alltext)
print("  >>> can't access tty？ ", ("tty" in alltext and "access" in alltext))
print("  >>> 有提示符？         ", ("#" in alltext) or ("$" in alltext))
print()
print("  判定：以上三个都应为「否/否/是」才算修复生效。")
