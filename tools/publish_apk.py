#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把构建好的 APK 发布到本地 nginx 站点。

用法：
    python tools/publish_apk.py <apk路径> --commit <sha> --run <编号> --variant release

## 站点布局

    C:\\nginx\\apk\\
      index.html        下载页（读 manifest.json 渲染）
      manifest.json     构建索引
      releases/         APK 文件本体

## 为什么需要 manifest.json 而不是只丢文件

页面是**动态**渲染的：它 fetch manifest.json，按 builds 数组顺序列出。
所以新增一个包必须同时：① 复制文件到 releases/；② 在 builds **首位**插一条。
只做①，页面上看不到；只做②，下载 404。

## 为什么插首位而不是追加

页面把 builds[0] 当作「最新版本」放在顶部大按钮，其余进「历史版本」。
追加到末尾会让新包被埋在历史里。
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
from datetime import datetime, timezone, timedelta

SITE = r"C:\nginx\apk"
RELEASES = os.path.join(SITE, "releases")
MANIFEST = os.path.join(SITE, "manifest.json")

CST = timezone(timedelta(hours=8))


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--commit", default="")
    ap.add_argument("--run", default="")
    ap.add_argument("--variant", default="release", choices=["release", "debug"])
    ap.add_argument("--version", default="1.0.0")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        print("APK 不存在: " + args.apk)
        return 1
    if not os.path.isdir(RELEASES):
        print("站点目录不存在: " + RELEASES)
        return 1

    now = datetime.now(CST)
    stamp = now.strftime("%Y%m%d-%H%M%S")
    name = "praxis-%s-%s-%s.apk" % (args.version, args.variant, stamp)
    dest = os.path.join(RELEASES, name)

    shutil.copy2(args.apk, dest)
    size = os.path.getsize(dest)
    digest = sha256_of(dest)
    print("已复制 -> " + dest)
    print("  大小 %d B (%.2f MB)" % (size, size / 1024 / 1024))
    print("  sha256 " + digest)

    # 读回并插入首位
    with open(MANIFEST, encoding="utf-8") as f:
        doc = json.load(f)
    builds = doc.get("builds", [])
    entry = {
        "file": name,
        "version": "%s (%s)" % (args.version, args.variant),
        "commit": args.commit,
        "runNumber": args.run,
        "size": size,
        "sha256": digest,
        "builtAt": now.isoformat(),
    }
    # 同名条目（极少见）先剔掉，避免重复
    builds = [b for b in builds if b.get("file") != name]
    doc["builds"] = [entry] + builds
    doc["updatedAt"] = now.isoformat()

    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=4)
    os.replace(tmp, MANIFEST)
    print("manifest.json 已更新，共 %d 个构建" % len(doc["builds"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())