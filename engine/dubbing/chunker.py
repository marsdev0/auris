"""Text chunking: ASR segment texts -> TTS narration unit list.

Rules:
1. segment <= limit passes through
2. over-long segment: split at sentence-final punctuation, then greedily aggregate to limit
3. punctuation-free long stream: hard cut at limit
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
            if len(piece) > limit: # single sentence still over (no punctuation): hard cut
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
