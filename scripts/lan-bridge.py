#!/usr/bin/env python3
"""把局域网里的手机请求转发到 adb forward 出来的本机端口。

为什么需要它：
  1. AVD 跑在 QEMU 用户态 NAT 后面，自己只有 10.0.2.15，手机根本没路由到它；
  2. `adb forward` 只监听 127.0.0.1（已实测），手机连 PC 的 IP 也连不上。

所以链路要补一段：手机 → <PC 局域网 IP>:8080 → 127.0.0.1:18080（adb forward 监听）→ 模拟器:8080

用法：
  1) 直接跑起来（会自动执行 adb forward，并把本机地址写进模拟器的 endpoint.txt）：
       C:\\python313\\python.exe scripts\\lan-bridge.py
     换成这个解释器是因为本机防火墙的放行规则是按程序路径记的，见文件末尾提示。
  2) 让模拟器里的键盘弹出并保持可见——HTTP 服务只在键盘可见时存在，
     此时二维码应显示 http://<本机局域网IP>:8080。
  3) 手机连同一个 Wi-Fi，扫码。

可选参数：
  --lan-port 8080     本机监听端口
  --device-port 8080  模拟器里应用的端口（被占用时应用会顺延，按二维码里的改）
  --adb-port 18080    adb forward 在本机的端口
  --no-adb            不自动配 forward / endpoint.txt
  --no-endpoint       只配 forward，不改二维码地址
"""

import argparse
import socket
import sys
import threading

_print_lock = threading.Lock()


def log(msg: str) -> None:
    with _print_lock:
        print(msg, flush=True)


def pump(src: socket.socket, dst: socket.socket) -> None:
    """单向搬运，对端关闭时做半关闭，让 HTTP 的 keep-alive / 分块上传都能正常收尾。"""
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, peer: str, target_host: str, target_port: int) -> None:
    upstream = None
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=10)
        upstream.settimeout(None)
        client.settimeout(None)
        log(f"[+] {peer} 已连接")
        t = threading.Thread(target=pump, args=(client, upstream), daemon=True)
        t.start()
        pump(upstream, client)
        t.join()
        log(f"[-] {peer} 断开")
    except OSError as exc:
        log(f"[!] {peer} 失败: {exc}（检查 adb forward 是否还在）")
    finally:
        for sock in (upstream, client):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


def setup_adb(target_port: int, device_port: int, endpoint: str = None) -> None:
    """顺手把 adb forward（必要时还有端到端地址）配好，省得开两三个终端。

    forward 很容易掉：模拟器重启、adb 重连、USB 拔插都会清掉。放在脚本启动时
    重设一次，用户只要记住「跑这一个脚本」。
    """
    import subprocess

    def adb(*argv: str) -> bool:
        try:
            done = subprocess.run(["adb", *argv], capture_output=True, text=True, timeout=20)
        except (OSError, subprocess.SubprocessError) as exc:
            log(f"[!] 调用 adb 失败: {exc}")
            return False
        if done.returncode != 0:
            msg = (done.stderr or done.stdout or "").strip()
            log(f"[!] adb {' '.join(argv)} 失败: {msg}")
            return False
        return True

    if not adb("forward", f"tcp:{target_port}", f"tcp:{device_port}"):
        log(f"[!] 请手动执行: adb forward tcp:{target_port} tcp:{device_port}")
        return
    log(f"已建立端口映射: tcp:{target_port} → 模拟器 tcp:{device_port}")

    if not endpoint:
        return
    # release 包不读这个文件，推失败也不影响（真机场景本来不需要）。
    #
    # 必须用 run-as 以应用身份写内部目录：Android 11+ 的分区存储里，
    # `adb shell echo > /sdcard/Android/data/<pkg>/...` 写出的文件属主是 shell，
    # 应用自己读会 Permission denied（chmod 也救不回来，FUSE 按属主判定）。
    pkg = "com.tvboard.ime"
    path = f"/data/data/{pkg}/files/endpoint.txt"
    if adb("shell", f"run-as {pkg} sh -c 'echo {endpoint} > {path}'"):
        log(f"已写入二维码地址: {endpoint}（键盘收起再弹出即可生效）")
    else:
        log(f"[!] 写入失败，可手动执行: adb shell \"run-as {pkg} sh -c 'echo {endpoint} > {path}'\"")


def lan_ips() -> list:
    """尽量列出本机可用于局域网访问的 IPv4（排除 127 与 172.x 这类虚拟网卡）。"""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))  # 不会真的发包，只为让系统选出口地址
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip.startswith("172."):
                continue
            if ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def main() -> int:
    parser = argparse.ArgumentParser(description="局域网 → adb forward 端口转发")
    parser.add_argument("--lan-port", type=int, default=8080, help="本机监听端口（默认 8080）")
    parser.add_argument("--device-port", type=int, default=8080, help="模拟器里应用的端口（默认 8080）")
    parser.add_argument("--adb-port", type=int, default=18080, help="adb forward 在本机的端口（默认 18080）")
    parser.add_argument("--target-host", default="127.0.0.1", help="adb forward 监听地址")
    parser.add_argument("--no-adb", action="store_true", help="不自动配置 adb forward / endpoint.txt")
    parser.add_argument("--no-endpoint", action="store_true", help="只配 forward，不写 endpoint.txt")
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", args.lan_port))
    except OSError as exc:
        log(f"绑定 0.0.0.0:{args.lan_port} 失败: {exc}")
        return 1
    server.listen(16)

    ips = lan_ips() or ["<本机局域网IP>"]
    log(f"监听 0.0.0.0:{args.lan_port} → {args.target_host}:{args.adb_port}")
    log(f"转发进程: {sys.executable}")
    for one in ips:
        log(f"手机访问地址: http://{one}:{args.lan_port}")
    log("")
    if not args.no_adb:
        endpoint = None if args.no_endpoint else f"{ips[0]}:{args.lan_port}"
        setup_adb(args.adb_port, args.device_port, endpoint)
    log("")
    log("手机连不上时先看防火墙：Windows 的放行规则是按「程序」记的，")
    log("当前这个解释器未必在内。换一个已有规则的，或用管理员执行一次：")
    log(f'  netsh advfirewall firewall add rule name="tvboard-bridge" dir=in action=allow protocol=TCP localport={args.lan_port}')
    log("")
    log("Ctrl+C 结束")

    try:
        while True:
            client, addr = server.accept()
            threading.Thread(
                target=handle,
                args=(client, f"{addr[0]}:{addr[1]}", args.target_host, args.adb_port),
                daemon=True,
            ).start()
    except KeyboardInterrupt:
        log("已停止")
    finally:
        server.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
