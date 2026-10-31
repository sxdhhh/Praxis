#!/usr/bin/env python3
"""
浏览器实时预览的 dev server。

## 它解决什么问题

Paparazzi 渲染出 PNG 之后，你需要一个地方看它、并且在改代码之后自动刷新。
这就是全部职责 —— 一个**零依赖**的本地 HTTP 服务：

  - 静态托管 `preview/build/reports/paparazzi/images/` 里的 PNG；
  - 首页是一个看板，按名字列出所有界面（chat / model …），可点开大图；
  - 监听源码目录的 mtime，一变就重新跑 Gradle 渲染，然后 SSE 推给浏览器刷新。

不引入任何 pip 包：只用标准库。理由很实在 —— 这个仓库跑在 proot 里，
装依赖会把「一条命令就能预览」变成「先配环境」。标准库够用。

## 为什么监听 mtime 而不是跑一堆 Gradle --continuous

`--continuous` 会常驻一个 Gradle daemon 并反复重新配置整个工程（18 个模块），
在我们这种「只想看 UI」的场景里太重，而且它输出的是文本日志而不是
「哪张图变了」。自己管节奏更可控：源文件变了才跑，跑完只推「刷新」信号。

## ⚠️ 它在 aarch64 上渲染会失败

layoutlib 的 native 库只有 x86_64。所以本机跑起来时：
  - 服务能正常启动、能看到上次的图；
  - 但**重新渲染会失败**（并把这个事实显示在页面上，而不是静默）。

真正的渲染请走 CI（`.github/workflows/preview.yml`），或在一台 x86_64
机器上跑本脚本。

用法：
    python3 tools/preview_server.py                # 默认 8793 端口
    python3 tools/preview_server.py --port 9000
    python3 tools/preview_server.py --no-render    # 只看图，不自动重新渲染
"""

from __future__ import annotations

import argparse
import http.server
import json
import mimetypes
import os
import socketserver
import subprocess
import sys
import threading
import time
from pathlib import Path

# ── 路径 ────────────────────────────────────────────────────
REPO = Path(__file__).resolve().parent.parent
IMAGES = REPO / "preview" / "build" / "reports" / "paparazzi" / "images"

# ## 为什么监听这些目录
#
# 预览想看的就是 UI。把 core/ 也算进来会让「改了数据库层」也触发渲染 ——
# 那是无用功，而且会让人以为预览在乱刷新。
WATCHED = [
    REPO / "feature" / "chat" / "src",
    REPO / "feature" / "settings" / "src",
    REPO / "ui" / "design" / "src",
    REPO / "ui" / "widgets" / "src",
    REPO / "ui" / "theme" / "src",
    REPO / "preview" / "src",
]

# 轮询间隔。1.5 秒足够快（改完按回浏览器就刷了），又不会把 CPU 吃满。
POLL_SECONDS = 1.5


# ── 渲染 ────────────────────────────────────────────────────

class Renderer:
    """
    串行执行 Gradle 渲染，并把状态广播给所有 SSE 客户端。

    ## 为什么要串行 + 去抖

    保存一次文件常常触发多次 mtime 变化（编辑器先写临时文件再 rename）。
    不去抖就会连着起好几个 Gradle，互相抢 daemon 锁。这里记一个
    `pending` 标记：渲染中再来新变更，只在结束后补跑一次。
    """

    def __init__(self, enabled: bool = True) -> None:
        self.enabled = enabled
        self.lock = threading.Lock()
        self.state = "idle"        # idle | rendering | error | unsupported
        self.detail = ""
        self.version = 0           # 每渲染完一次 +1，浏览器据此判断要不要刷新
        self._pending = False
        self._thread: threading.Thread | None = None

    def status(self) -> dict:
        return {
            "state": self.state,
            "detail": self.detail,
            "version": self.version,
        }

    def request(self, reason: str) -> None:
        """请求一次渲染（带去抖）。"""
        if not self.enabled:
            return
        with self.lock:
            if self.state == "rendering":
                self._pending = True
                return
            self.state = "rendering"
            self.detail = reason
            self.version += 1
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        cmd = [
            str(REPO / "gradlew"),
            ":preview:testDebugUnitTest",
            "--tests", "dev.praxis.preview.*",
            "--rerun-tasks",     # 强制重渲染，见 build.gradle.kts 里的说明
        ]
        ok = True
        detail = ""
        try:
            proc = subprocess.run(
                cmd,
                cwd=str(REPO),
                capture_output=True,
                text=True,
                timeout=900,
                env={**os.environ, "PROOT_TMP_DIR": "/tmp", "TMPDIR": "/tmp"},
            )
            if proc.returncode != 0:
                ok = False
                detail = _summarize_failure(proc.stdout + proc.stderr)
        except subprocess.TimeoutExpired:
            ok = False
            detail = "渲染超时（900s）"
        except Exception as exc:                      # noqa: BLE001
            ok = False
            detail = f"{type(exc).__name__}: {exc}"

        with self.lock:
            if ok:
                self.state = "idle"
                self.detail = "已更新"
            else:
                # ## 为什么单独识别 aarch64
                #
                # 本机就是 aarch64，.layoutlib 没有对应的 native 库。
                # 如果不把这种情况单独标出来，页面只会显示一大段 Gradle
                # 报错，让人以为是代码坏了。实际是平台不支持。
                if "UnsatisfiedLinkError" in detail or "no layoutlib" in detail.lower():
                    self.state = "unsupported"
                    self.detail = "当前平台无法渲染（layoutlib 仅有 x86_64 native 库）"
                else:
                    self.state = "error"
                    self.detail = detail
            self.version += 1
            pending = self._pending
            self._pending = False

        if pending:
            self.request("检测到后续变更")


def _summarize_failure(log: str) -> str:
    """
    从 Gradle 日志里抠出最有用的几行。

    整段日志上千行，直接塞进网页等于没给信息。这里优先取 Kotlin 编译错误
    与测试断言失败，都没有才退回最后 20 行。
    """
    lines = log.splitlines()
    picked = [l for l in lines if l.startswith("e: ") or "FAILED" in l or "AssertionError" in l]
    if picked:
        return "\n".join(picked[:12])
    return "\n".join(lines[-20:])


# ── 文件监听 ────────────────────────────────────────────────

def snapshot_mtimes() -> dict[str, float]:
    """当前所有被监听文件的 mtime 快照。"""
    out: dict[str, float] = {}
    for root in WATCHED:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.is_file() and path.suffix in {".kt", ".xml", ".kts"}:
                try:
                    out[str(path)] = path.stat().st_mtime
                except OSError:
                    pass
    return out


def watch_loop(renderer: Renderer) -> None:
    """
    ## 为什么要先睡一轮再开始比较

    服务刚起来时页面可能还是空的，此时渲染一次是有意义的；但更常见的情况
    是「上次的图还在，先让人看到」。所以初始状态不动，只在检测到**真实变更**
    时才渲染。想立刻渲染就改一下任意 .kt 文件。
    """
    prev = snapshot_mtimes()
    while True:
        time.sleep(POLL_SECONDS)
        cur = snapshot_mtimes()
        if cur != prev:
            changed = [k for k in cur if prev.get(k) != cur[k]]
            prev = cur
            names = ", ".join(os.path.basename(c) for c in changed[:3])
            renderer.request(f"变更：{names}")


# ── HTTP ────────────────────────────────────────────────────

PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Praxis UI 预览</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 24px;
    font: 14px/1.6 -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    background: #f6f7f9; color: #1b1d21;
  }
  @media (prefers-color-scheme: dark) {
    body { background: #17181b; color: #e8eaed; }
    .card { background: #212328 !important; border-color: #2c2f36 !important; }
    header .pill { background: #212328 !important; }
  }
  header {
    display: flex; align-items: center; gap: 12px;
    flex-wrap: wrap; margin-bottom: 20px;
  }
  h1 { font-size: 18px; margin: 0; font-weight: 650; }
  .pill {
    padding: 4px 10px; border-radius: 999px; font-size: 12px;
    background: #fff; border: 1px solid #e3e5e9; white-space: nowrap;
  }
  .pill.rendering { color: #8a5a00; }
  .pill.error { color: #b3261e; }
  .pill.unsupported { color: #7a4b00; }
  .grid {
    display: grid; gap: 16px;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  }
  .card {
    background: #fff; border: 1px solid #e3e5e9; border-radius: 14px;
    overflow: hidden; display: block; text-decoration: none; color: inherit;
    transition: transform .12s ease, box-shadow .12s ease;
  }
  .card:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(0,0,0,.08); }
  .card img { display: block; width: 100%; height: auto; background: #ddd; }
  .card .meta { padding: 10px 12px; font-size: 13px; }
  .card .meta small { opacity: .6; display: block; margin-top: 2px; }
  .empty { opacity: .7; padding: 40px 0; text-align: center; }
  /* 空态清单：还没渲染时，至少说清「本该有哪几屏」 */
  .expected { margin-top: 28px; text-align: left; display: inline-block; }
  .expected h2 { font-size: 13px; font-weight: 600; opacity: .55; margin: 0 0 10px; letter-spacing: .02em; }
  .expected ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 6px; }
  .expected li { display: flex; gap: 10px; align-items: baseline; font-size: 13px; }
  .expected code {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px; padding: 1px 6px; border-radius: 5px;
    background: #00000010; white-space: nowrap;
  }
  @media (prefers-color-scheme: dark) {
    .expected code { background: #ffffff14; }
  }
  .expected span { opacity: .65; }
  dialog {
    border: none; padding: 0; background: transparent;
    max-width: 96vw; max-height: 96vh;
  }
  dialog::backdrop { background: rgba(0,0,0,.72); }
  dialog img { max-width: 96vw; max-height: 92vh; display: block; border-radius: 10px; }
  pre {
    white-space: pre-wrap; font-size: 12px; opacity: .8;
    background: #00000008; padding: 10px; border-radius: 8px; margin: 8px 0 0;
  }
</style>
</head>
<body>
<header>
  <h1>Praxis UI 预览</h1>
  <span class="pill" id="status">连接中…</span>
  <span class="pill" id="count"></span>
  <span class="pill">改任意 .kt 保存即自动重新渲染</span>
</header>
<pre id="detail" hidden></pre>
<div class="grid" id="grid"></div>
<div class="empty" id="empty" hidden>
  <div>还没有渲染产物。</div>
  <div class="expected">
    <h2>这套管线覆盖的界面</h2>
    <ul>
      <li><code>chat-empty</code><span>空对话，首启态</span></li>
      <li><code>chat-conversation</code><span>真实多轮 + 流式中</span></li>
      <li><code>chat-conversation-dark</code><span>同上，深色</span></li>
      <li><code>chat-scroll-affordance</code><span>长列表，看滚动条与回底按钮</span></li>
      <li><code>model-settings</code><span>模型设置页</span></li>
    </ul>
  </div>
  <div style="margin-top:22px;font-size:13px">
    在一台 x86_64 机器上跑 <code>./gradlew :preview:testDebugUnitTest</code>，<br>
    或让 CI 的 <b>preview</b> workflow 产出后，把 <code>images/</code> 放进<br>
    <code>preview/build/reports/paparazzi/images/</code> —— 刷新即可，不用重启。
  </div>
</div>
<dialog id="viewer"><img id="viewerImg" alt=""></dialog>
<script>
const grid = document.getElementById('grid');
const statusEl = document.getElementById('status');
const detailEl = document.getElementById('detail');
const countEl = document.getElementById('count');
const emptyEl = document.getElementById('empty');
const viewer = document.getElementById('viewer');
const viewerImg = document.getElementById('viewerImg');

let lastVersion = -1;

function human(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function title(name) {
  return name.replace(/\\.png$/, '').replace(/-/g, ' ');
}

async function refresh() {
  const res = await fetch('/api/images');
  const list = await res.json();
  grid.innerHTML = '';
  emptyEl.hidden = list.length > 0;
  countEl.textContent = list.length + ' 张';
  for (const item of list) {
    const a = document.createElement('a');
    a.className = 'card';
    a.href = '#';
    a.onclick = (e) => {
      e.preventDefault();
      viewerImg.src = item.url + '?v=' + item.mtime;
      viewer.showModal();
    };
    const img = document.createElement('img');
    // 加 mtime 作为查询串：绕开浏览器缓存，否则图变了还显示旧的。
    img.src = item.url + '?v=' + item.mtime;
    img.loading = 'lazy';
    img.alt = item.name;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.innerHTML = '<strong>' + title(item.name) + '</strong>' +
                     '<small>' + human(item.size) + ' · ' +
                     new Date(item.mtime * 1000).toLocaleTimeString() + '</small>';
    a.append(img, meta);
    grid.append(a);
  }
}

function setStatus(st) {
  statusEl.className = 'pill ' + st.state;
  statusEl.textContent = ({
    idle: '就绪',
    rendering: '渲染中…',
    error: '渲染失败',
    unsupported: '平台不支持渲染',
  })[st.state] || st.state;
  if (st.detail && st.state !== 'idle') {
    detailEl.hidden = false;
    detailEl.textContent = st.detail;
  } else {
    detailEl.hidden = true;
  }
}

// ## 用 SSE 而不是轮询 /api/status
//
// 轮询要么太慢（看不到「渲染中」）、要么太吵（一秒好几十个请求）。
// SSE 是单向推送，正好匹配「服务端知道何时该刷新」这个形态，
// 而且浏览器原生支持、不用引入任何库。
const es = new EventSource('/events');
es.onmessage = (e) => {
  const st = JSON.parse(e.data);
  setStatus(st);
  if (st.version !== lastVersion) {
    lastVersion = st.version;
    refresh();
  }
};
es.onerror = () => { statusEl.textContent = '连接断开（刷新页面重连）'; };

viewer.onclick = () => viewer.close();
refresh();
</script>
</body>
</html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    renderer: Renderer

    def log_message(self, fmt: str, *args) -> None:
        # 默认会为每个请求打一行；SSE 长连接会让日志刷屏，这里只留错误。
        pass

    def do_GET(self) -> None:                        # noqa: N802
        if self.path == "/" or self.path.startswith("/?"):
            return self._send_bytes(PAGE.encode("utf-8"), "text/html; charset=utf-8")
        if self.path == "/api/images":
            return self._send_json(self._image_list())
        if self.path == "/events":
            return self._sse()
        if self.path.startswith("/images/"):
            return self._serve_image(self.path[len("/images/"):])
        self.send_error(404)

    # ── 内部 ──

    def _image_list(self) -> list[dict]:
        if not IMAGES.exists():
            return []
        out = []
        for p in sorted(IMAGES.glob("*.png")):
            st = p.stat()
            out.append({
                "name": p.name,
                "url": f"/images/{p.name}",
                "size": st.st_size,
                "mtime": st.st_mtime,
            })
        return out

    def _serve_image(self, name: str) -> None:
        # 防目录穿越：只允许纯文件名。
        if "/" in name or "\\" in name or name.startswith("."):
            return self.send_error(400)
        path = IMAGES / name
        if not path.exists():
            return self.send_error(404)
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        self._send_bytes(path.read_bytes(), ctype)

    def _send_json(self, obj) -> None:
        self._send_bytes(json.dumps(obj).encode("utf-8"), "application/json; charset=utf-8")

    def _send_bytes(self, body: bytes, ctype: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        # 预览图必须禁缓存，否则改完看不到变化。
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _sse(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        last_version = -1
        last_state = None
        try:
            while True:
                st = self.renderer.status()
                key = (st["version"], st["state"])
                if key != (last_version, last_state):
                    last_version, last_state = st["version"], st["state"]
                    self.wfile.write(f"data: {json.dumps(st)}\n\n".encode())
                    self.wfile.flush()
                time.sleep(0.5)
        except (BrokenPipeError, ConnectionResetError):
            pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    ap = argparse.ArgumentParser(description="Praxis UI 浏览器预览")
    ap.add_argument("--port", type=int, default=8793,
                    help="端口（默认 8793；8791 被 MT APK MCP 占用、8792 是 pk-node）")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--no-render", action="store_true",
                    help="只托管已有图片，不监听源码、不触发 Gradle")
    args = ap.parse_args()

    renderer = Renderer(enabled=not args.no_render)
    Handler.renderer = renderer

    if not IMAGES.exists():
        print(f"[preview] 尚无产物目录：{IMAGES}")
        print("[preview] 页面会显示空态，直到有人（或 CI）产出一批 PNG。")

    srv = Server((args.host, args.port), Handler)

    if not args.no_render:
        threading.Thread(target=watch_loop, args=(renderer,), daemon=True).start()

    print(f"[preview] http://127.0.0.1:{args.port}/")
    print(f"[preview] 图片目录：{IMAGES}")
    if args.no_render:
        print("[preview] --no-render：不监听源码变更")
    else:
        print("[preview] 监听源码变更，保存即重新渲染")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[preview] 停止")
    return 0


if __name__ == "__main__":
    sys.exit(main())