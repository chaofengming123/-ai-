# Mac 与 Windows 使用同一套项目数据

采用 Mac 运行整套服务、Windows 通过 SSH 隧道访问网页的方式。两端请求最终进入同一个 Spring Boot 后端，使用同一份 MySQL、MinIO 和 Qdrant 数据。

```text
Windows 浏览器 localhost:15173
  → SSH 加密隧道
  → Mac 前端 localhost:5173
  → Vite /api 代理 → Mac 后端 127.0.0.1:8080
  → Mac 的 MySQL / MinIO / Qdrant
```

Windows 无需启动另一套前端、后端或数据库，也无需复制 docker/.env、模型密钥或 JWT。项目网页账号与 Mac 系统账号是两种账号，连接隧道和登录网页分别使用它们。

## 1. Mac 保持项目服务运行

沿用平时的 Docker、后端与前端启动方式，确认 Mac 能打开项目网页。前端保持使用 5173；如果终端显示其他端口，下方隧道命令的最后一个端口也要相应修改。

本次检查（2026-09-22）：前端 5173 和后端 8080 均返回 HTTP 200；Mac 用户名为 `suhang`，局域网 IP 为 `192.168.1.149`。IP 可能随网络变化，需要时在系统设置的网络详情里重新查看。

## 2. 在 Mac 开启远程登录

打开“系统设置 → 通用 → 共享 → 远程登录”，开启该功能，将允许访问的用户限定为你使用的 Mac 账号 `suhang`。本用途无需开启远程用户的完整磁盘访问权限。操作说明见 [Apple 远程登录指南](https://support.apple.com/guide/mac-help/mchlp1066/mac)。

本次未自动修改这项系统设置；检查时本机 22 端口未连通。开启后，远程登录详情会显示可使用的 SSH 地址。

## 3. 在 Windows 建立连接

先让 Windows 和 Mac 处于同一可信局域网。打开 PowerShell，执行：

```powershell
ssh -N -L 127.0.0.1:15173:localhost:5173 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 suhang@192.168.1.149
```

如果提示找不到 ssh，请在 Windows 的“可选功能”安装 OpenSSH 客户端；Windows 不需要安装 SSH 服务端。参见 [Microsoft Windows SSH 说明](https://learn.microsoft.com/en-us/windows/terminal/tutorials/ssh)。

首次连接需要确认主机身份。可以在 Mac 终端运行 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`，核对 Windows 提示中的 ED25519 指纹，再确认连接。随后输入 **Mac 系统账号 suhang 的登录密码**；输入时通常不显示字符。

连接成功后终端保持运行，没有命令提示符是正常现象。让窗口保持打开，Windows 浏览器访问：

<http://localhost:15173/>

使用原来的**项目用户名和密码**登录，就能看到 Mac 上的数据。向量练习集合按项目用户 ID 隔离，所以要使用同一个项目账号。两端登录会话相互独立，页面已有内容可能需要刷新才显示另一端的修改。

## 4. 命令里的关键部分

| 参数 | 作用 |
| --- | --- |
| `-N` | 只建立转发通道，不打开远程交互终端 |
| `-L 127.0.0.1:15173:localhost:5173` | Windows 本机的 15173 转发到 Mac 自己的 localhost:5173 |
| `ExitOnForwardFailure=yes` | 本地转发端口绑定失败时退出，避免误以为连接成功 |
| `ServerAliveInterval` / `ServerAliveCountMax` | 定期检查 SSH 连接，失联后退出 |
| `suhang@192.168.1.149` | Mac 系统用户名与当前局域网地址 |

只转发前端即可：浏览器的 `/api` 请求经隧道到达 Mac 的 Vite，再由其代理给 Mac 后端。聊天流式请求也经过同一条通道。无需开放 MySQL、MinIO 或 Qdrant 的网络端口。

此方式让 Windows 使用 Mac 当前运行的代码。如果你在 Windows 修改本地源码，这个网页不会自动使用该修改；代码仍通过 Git 同步，再由 Mac 更新和运行。

## 5. 常见情况

- **SSH 连接被拒绝或超时**：检查远程登录、IP、两端网络与防火墙；访客 Wi-Fi 可能禁止设备互访。
- **15173 已占用**：关闭旧的隧道窗口，或把命令里的 15173 改为 15174，并访问对应地址。
- **隧道连接正常但网页打不开**：检查 Mac 前端是否仍运行在 5173。SSH 本地端口绑定成功不保证远端前端服务可访问。
- **网页打开但接口失败**：检查 Mac 的 8080 后端和 Docker 服务。
- **Mac 休眠或关机后断开**：这是由 Mac 提供服务的正常限制；恢复后重新建立隧道。停止连接可按 Ctrl+C。
- **两台电脑不在同一局域网**：当前私网 IP 不能直接跨互联网访问，需要另外配置可信的组网 VPN 或共享服务器；不要直接把开发数据库端口映射到公网。

验证时，在 Windows 使用同一项目账号登录，检查知识库和向量练习数量，再检索已保存的文字。本次已验证 Mac 服务可用，但没有 Windows 端会话，跨机连接仍需按以上步骤验收。
