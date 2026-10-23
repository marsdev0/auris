"""文本切断： ASR段文本 -> TTS朗读单元列表

规则：
1. 单段 <= limit 直接过
2. 超长段：按句末标点切开再谈心聚合到limit
3. 无标点长流：按limit硬切
"""

import re

_SENT_SPLIT = re.compile(r"(?<=[。.!?!?…])\s*")


def chunk(units: list[str], limit: int = 500) -> list[str]:
    out: list[str] = []
    for raw in units:
        u = raw.strip()
        if not u:
            continue
        if len(u) <= limit:
            out.append(u)
            continue
        buf = ""
        for piece in (s for s in _SENT_SPLIT.split(u) if s):
            if len(piece) > limit: # 单句仍超（无标点）：硬切
                if buf:
                    out.append(buf)
                    buf = ""
                out.extend(piece[i:i + limit] for i in range(0, len(piece), limit))
            elif len(buf) + len(piece) > limit:
                out.append(buf)
                buf = piece
            else:
                buf += piece
        if buf:
            out.append(buf)
    return out