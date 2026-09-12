"""
URL -> 抓取音频
"""
import ipaddress
import socket
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse
from loguru import logger

import httpx

_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
_MAX_BYTES = 500 * 1024 * 1024      # 与 task/start 的 body 护栏同口径
_CONNECT_TIMEOUT = 10.0             # 连接 10s
_TOTAL_TIMEOUT = 600.0              # 下载总时长 10min(播客大文件留余量)
_MAX_REDIRECTS = 5

# 临时文件固定在项目根 tmp/(不用系统 tempfile 目录:位置因平台而异不便观察,
# 残留也集中可见——成功/失败路径都会清理,万一漏了肉眼看得到)
TMP_DIR = Path(__file__).resolve().parent.parent.parent / "tmp"
TMP_DIR.mkdir(parents=True, exist_ok=True)

class SourceError(Exception):
    """抓取失败"""

@dataclass
class SourceResult:
    """一次成功抓取的产物。audio_path指向临时文件，消费方用完即删"""
    audio_path: str
    title: str
    site: str  # "xiaoyuzhou" / "bilibili" / "direct"

# --- Server-Site Request Forgery(SSRF) 闸门 ---
def assert_public_url(url: str) -> None:
    """
    URL 安全校验：仅 http(s) + 解析后所有 IP 必须是公网
    必须在 DNS 解析后逐 IP 校验，而不是字符串匹配
    防 dns rebinding(域名第一次解析过闸、下载时重绑到 127.0.0.1)
    ip.is_global 恰好覆盖:环回/私网/链路本地(169.254)/保留段/0.0.0.0
    """
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise SourceError(f"仅支持 http/https 链接: {parsed.scheme}")
    host = parsed.hostname
    if not host:
        raise SourceError("链接缺失主机名")
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        raise SourceError(f"域名无法解析: {host}")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global:
            raise SourceError(f"不允许访问内网地址: {host}")

# ---------- 下载器(手动逐跳重定向,每跳过闸) ----------
def download_to_temp(url: str, *, referer: str | None = None) -> Path:
    """
    流式下载到临时文件。Content-Length 预检 + 流式截断双保险
    follow_redirects=False + 手动循环:每一跳都重新过 SSRF 闸门,
    不给"自动重定向落到内网"留口子
    """
    assert_public_url(url)
    headers = {"User-Agent": _UA}
    if referer:
        headers["Referer"] = referer

    tmp = tempfile.NamedTemporaryFile(prefix="auris-src-", suffix=".bin", delete=False, dir=TMP_DIR)
    logger.info(f"下载落盘: {tmp.name}")
    try:
        with httpx.Client(timeout=httpx.Timeout(_TOTAL_TIMEOUT, connect=_CONNECT_TIMEOUT), follow_redirects=False) as client:
            for _hop in range(_MAX_REDIRECTS + 1):
                resp = client.get(url, headers=headers)
                if resp.status_code in (301, 302, 303, 307, 308):
                    url = str(resp.next_request.url) if resp.next_request else resp.headers["Location"]
                    assert_public_url(url)  # 重定向后，也必须过闸门
                    continue
                resp.raise_for_status()
                if (cl := resp.headers.get("Content-Length")) and int(cl) > _MAX_BYTES:
                    raise SourceError(f"文件过大: {int(cl) // 1024 // 1024}MB 超上限")
                written = 0
                for chunk in resp.iter_bytes(1024 * 256):
                    written += len(chunk)
                    if written > _MAX_BYTES:
                        raise SourceError("下载超过大小上限, 已截断")
                    tmp.write(chunk)
                return Path(tmp.name)   # 成功:文件交给调用方,用完由 _run_url unlink
            raise SourceError("重定向次数过多")
    except httpx.TimeoutException:
        Path(tmp.name).unlink(missing_ok=True)
        raise SourceError("下载超时")
    except httpx.HTTPStatusError as e:
        Path(tmp.name).unlink(missing_ok=True)
        raise SourceError(f"下载失败: HTTP {e.response.status_code}")
    except SourceError:
        Path(tmp.name).unlink(missing_ok=True)   # 截断/重定向过多:半截文件不留
        raise
    except Exception:
        Path(tmp.name).unlink(missing_ok=True)
        raise SourceError("下载失败: 未知错误")
    finally:
        tmp.close()










