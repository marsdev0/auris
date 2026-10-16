# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""出站 URL 安全闸门(SSRF 防护)——横切能力,消费方按需 import。

消费方:源抓取(sources)、贴链转写预检(asr)、BYOK 校验(byok, P12-1);
域名白名单(P12-1 方案 §2.6,放开与否做成配置)将来也归这里。
2026-10-16 从 engine/sources/base.py 上提(第三个消费方出现时)。
"""
import ipaddress
import socket
from urllib.parse import urlparse


class UrlSafetyError(Exception):
    """URL 未过安全闸门(scheme 非法/无法解析/内网地址)"""


def assert_public_url(url: str) -> None:
    """
    URL 安全校验:仅 http(s) + 解析后所有 IP 必须是公网
    必须在 DNS 解析后逐 IP 校验,而不是字符串匹配
    防 dns rebinding(域名第一次解析过闸、下载时重绑到 127.0.0.1)
    ip.is_global 恰好覆盖:环回/私网/链路本地(169.254)/保留段/0.0.0.0
    """
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise UrlSafetyError(f"仅支持 http/https 链接: {parsed.scheme}")
    host = parsed.hostname
    if not host:
        raise UrlSafetyError("链接缺失主机名")
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        raise UrlSafetyError(f"域名无法解析: {host}")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global:
            raise UrlSafetyError(f"不允许访问内网地址: {host}")
