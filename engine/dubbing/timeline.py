"""Timeline domain (P12-D5 §4.1/4.2): estimator / four-state verdict / chunk planning / window split / srt.

This file grew alongside D5 construction (order per plan §8 call tree); currently has target_chars,
estimate/judge/plan_chunks/resplit/write_srt are to be implemented per appendix C.3.
Algorithm provenance and borrowing discipline in D5 plan appendix B.
"""
import re
from pathlib import Path

from engine.config import DUB
from engine.dubbing.types import Seg

# Four-state verdict (appendix B.2-2): triage at estimation time, the cheapest layer solves the most
NEED_TRIM, NEED_SPEED, SLOW, OK = 2, 1, -1, 0

_PUNCT_MID = re.compile(r"[，；：、,;]")
_PUNCT_END = re.compile(r"[。！？.!?]")

_SENT_SPLIT = re.compile(r"(?<=[。！？.!?,，])\s*")

_TTS_STRIP = re.compile(r"[(（][^()（）]*[)）]")
_LATIN = "A-Za-z0-9"
_HYPHEN = re.compile(rf"(?<![{_LATIN}])-|-(?![{_LATIN}])")


def target_chars(window_s: float) -> int:
    """Target char count for a window (translation constraint, D5 §4.2): window × speech rate, 10% pause margin."""
    return max(4, int(window_s * DUB.CPS * 0.9))

def estimate(text: str, cps: float | None = None) -> float:
    """Narration duration estimate (no real TTS call): chars / rate + punctuation pauses counted explicitly.
    Plain char count systematically underestimates; mid punctuation (comma-like) +0.1s, sentence-final +0.1s
    """
    cps = cps or DUB.CPS
    n = len(re.sub(r"\s", "", text))
    pauses = 0.1 * len(_PUNCT_MID.findall(text)) + 0.1 * len(_PUNCT_END.findall(text))
    return n / cps + pauses

def resplit(zh: list[Seg]) -> list[Seg]:
    """
    [Why it exists] Chinese Segs carry the original English time window, but translated length ≠ original length: 60-char translation with a 4s window, full-segment TTS reads ~14s.
    Going straight to alignment means heavy time-stretch (distortion) or truncation (content loss). This function re-splits over-window translations into multiple narration units within the original window.
    Not a single character is dropped — only re-grided — so TTS/alignment operate at fine granularity (stretch pressure drops sharply).

    [Three no-split cases]
    1. not over window (window + borrow allowance): splitting would only fragment;
    2. translation ≤ 20 chars: too short to be worth splitting;
    3. no punctuation to split on: hard cuts break semantics —
    leave it to the L2 trim layer (that is the content-changing layer; this layer only re-grids).

    [Window split rule] sub-window duration = original window × (sub-sentence chars / total chars); long sentence gets long time;
    sub-windows are contiguous, total duration conserves with the original window (last sub-unit end == original end).
    """
    out: list[Seg] = []
    for s in zh:
        win = s.end - s.start
        # Cases 1&2: not over window (incl. borrow allowance) or too short → keep as-is
        if estimate(s.text) <= win + DUB.TOL or len(s.text) <= 20:
            out.append(s)
            continue
        # Split at punctuation (punctuation stays at sentence end, see _SENT_SPLIT lookbehind)
        pieces = [p for p in _SENT_SPLIT.split(s.text) if p.strip()]
        if len(pieces) <= 1:          # Case 3: nothing to split on → leave to the trim layer
            out.append(s)
            continue
        # Sub-windows share the original window by char proportion (not evenly): a 20-char sentence gets ~1.5x the window of a 13-char one
        total = sum(len(p) for p in pieces)
        cur = s.start
        for p in pieces:
            dur = win * len(p) / total
            out.append(Seg(text=p.strip(), start=round(cur, 3), end=round(cur + dur, 3)))
            cur += dur
    return out


def clean_for_tts(text: str) -> str:
    """Text cleanup before TTS.
    _TTS_STRIP regex: drop parenthetical remarks,
    e.g. (laughter) he left -> he left

    _HYPHEN: drop non-word hyphens; only hyphens with Latin characters on both sides are kept, all others removed.
    Examples:
     "- 我们开始吧" → " 我们开始吧"               ← 前面无字符 删除
     "hands-on experience" → 原样保留           ← 前后都是拉丁:词内, 保留
     "3-5 个节点"          → 原样保留            ← 前是 3 后是 5(数字也算):保留,读「三五」
     "中文-English 混排"   → "中文English 混排"  ← 前面是汉字(非拉丁!), 删除
    """
    return _HYPHEN.sub("", _TTS_STRIP.sub("", text)).strip()


def gaps_of(units: list[Seg]) -> list[float]:
    """Gap to the next unit; the last unit counts as "assume 2s more" (for cut-point detection only)."""
    return [max(units[i + 1].start - units[i].end, 0.0) if i + 1 < len(units) else 2.0
            for i in range(len(units))]

def tol_of(u: Seg, gap: float) -> float:
    """Allowed duration = window + borrow allowance"""
    return (u.end - u.start) + min(gap, DUB.TOL)

def judge(est: float, tol: float, duration: float) -> int:
    """Four states (appendix B.2-2). tol = duration + borrow allowance (see tol_of)."""
    if est / DUB.SPEED_MAX > tol:
        return NEED_TRIM      # even max stretch cannot fit → trim
    if est > tol:
        return NEED_SPEED     # solvable by chunk-level stretch
    if est < duration - DUB.TOL:
        return SLOW           # slow: silence padding is enough, harmless
    return OK

def plan_chunks(units: list[Seg]) -> list[list[int]]:
    """Chunk planning (appendix B.2-3): gap≥TOL is a natural cut point; consecutive NEED_SPEED merge downward (≤3 lines)."""
    gaps = gaps_of(units)
    tols = [tol_of(u, g) for u, g in zip(units, gaps)]
    flags = [judge(estimate(clean_for_tts(u.text)), t, u.end - u.start)
             for u, t in zip(units, tols)]

    cut = [i for i, g in enumerate(gaps) if g >= DUB.TOL] + [len(units) - 1]
    chunks, start = [], 0
    for end in cut:
        i = start
        while i <= end:
            if flags[i] != NEED_SPEED or i == end:
                chunks.append([i])            # normal/slow/needs-trim lines: standalone chunks
                i += 1
                continue
            j = i                              # fast line probes downward to merge
            while j < end and j - i < 2 and flags[j + 1] == NEED_SPEED:
                j += 1
            chunks.append(list(range(i, j + 1)))
            i = j + 1
        start = end + 1
    return chunks

def _fmt_ts(t: float) -> str:
    ms = int(round(t * 1000))
    return f"{ms // 3600000:02d}:{ms % 3600000 // 60000:02d}:{ms % 60000 // 1000:02d},{ms % 1000:03d}"


def write_srt(times: list[list[float]], texts: list[str], path: Path) -> None:
    """⑧ Subtitle byproduct: timestamps use the re-arranged new timeline after time-stretch (new_sub_times, not original windows)."""
    with open(path, "w", encoding="utf-8") as f:
        for i, ((st, en), text) in enumerate(zip(times, texts), 1):
            f.write(f"{i}\n{_fmt_ts(st)} --> {_fmt_ts(en)}\n{text}\n\n")
