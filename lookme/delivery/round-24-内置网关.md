# round-24 交付

- **内置网关落地**：Agent2API 跑在手机 proot 容器里，127.0.0.1 就是手机自己。
- GatewayContainerInstaller：按 ABI 下载 + sha256 校验 + 容器内解包。
- GatewayProcess.startInContainer/stopInContainer：长驻 proot 会话 + 日志进窗口。
- 自启动内置优先；概览页有安装/启动/停止按钮。
- 实测确认：arm64 产物静态链接（无 glibc 依赖）；proot 共享网络命名空间。
- 未推 CI（按用户指示，全部完成后统一审查）。
