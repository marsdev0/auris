"""时间轴域(P12-D5 §4.1/4.2):估算器/四态判定/chunk 规划/窗口分配/srt。

本文件随 D5 施工逐步生长(施工顺序见方案 §8 调用树);当前含 target_chars,
estimate/judge/plan_chunks/resplit/write_srt 由作者按附录 C.3 落地。
算法出处与借鉴纪律见 D5 方案附录 B。
"""
import re
from pathlib import Path

from engine.config import DUB
from engine.dubbing.types import Seg

# 四态判定(附录 B.2-2):估算期分流,最便宜的层解决最多问题
NEED_TRIM, NEED_SPEED, SLOW, OK = 2, 1, -1, 0

_PUNCT_MID = re.compile(r"[，；：、,;]")
_PUNCT_END = re.compile(r"[。！？.!?]")

_SENT_SPLIT = re.compile(r"(?<=[。！？.!?,，])\s*")

_TTS_STRIP = re.compile(r"[(（][^()（）]*[)）]")
_LATIN = "A-Za-z0-9"
_HYPHEN = re.compile(rf"(?<![{_LATIN}])-|-(?![{_LATIN}])")


def target_chars(window_s: float) -> int:
    """窗口对应的目标字数(翻译约束用,D5 §4.2):窗长×语速,留 10% 停顿余量。"""
    return max(4, int(window_s * DUB.CPS * 0.9))

def estimate(text: str, cps: float | None = None) -> float:
    """朗读时长估算(不真调 TTS):字数/语速 + 标点停顿显式计入
    纯字数会系统性低估朗读时长，中标点(逗号类)+0.1s、句末标点+0.1s
    """
    cps = cps or DUB.CPS
    n = len(re.sub(r"\s", "", text))
    pauses = 0.1 * len(_PUNCT_MID.findall(text)) + 0.1 * len(_PUNCT_END.findall(text))
    return n / cps + pauses

def resplit(zh: list[Seg]) -> list[Seg]:
    """
    【为什么存在】翻译产出的中文 Seg 自带原英文段的时间窗,但译文长度 ≠ 原文长度:一段 60 字译文配 4 秒窗,TTS 整段朗读约 14 秒
    直接进对齐只能大幅变速(失真)或截断(丢内容)。本函数在原窗内把超窗译文按句再切成多个朗读单元
    内容一字不丢,只是重新分格,让 TTS/对齐，在细粒度上进行(变速压力骤减)

    【三种不切的情况】
    1.不超窗(窗长+借用额度内):切了反而碎;
    2.译文≤20字:太短无两切价值;
    3.无句读可切:硬切会碎语义——
    留给 L2 缩句层(那才是改内容的层,本层只分格不改内容)。

    【分窗规则】子窗时长 = 原窗 × (子句字数/总字数),长句配长时间;
    子窗首尾相接,总时长与原窗守恒(末子单元 end == 原 end)。
    """
    out: list[Seg] = []
    for s in zh:
        win = s.end - s.start
        # 情形1和2:不超窗(含借用额度)或太短 → 原样保留
        if estimate(s.text) <= win + DUB.TOL or len(s.text) <= 20:
            out.append(s)
            continue
        # 按句读切(标点保留在句尾,见 _SENT_SPLIT 的 lookbehind)
        pieces = [p for p in _SENT_SPLIT.split(s.text) if p.strip()]
        if len(pieces) <= 1:          # 情形3:无标点可切 → 留给缩句层
            out.append(s)
            continue
        # 子窗按字数占比瓜分原窗(非均分):20 字句分到的窗是 13 字句的 ~1.5 倍
        total = sum(len(p) for p in pieces)
        cur = s.start
        for p in pieces:
            dur = win * len(p) / total
            out.append(Seg(text=p.strip(), start=round(cur, 3), end=round(cur + dur, 3)))
            cur += dur
    return out


def clean_for_tts(text: str) -> str:
    """上 TTS 前的文本清洗
    _TTS_STRIP正则: 删除括号备注
    例如：（笑声）他走了 -> 他走了

    _HYPHEN: 删除词外连字符，只有前后都是拉丁字符的连字符，才保留，其余情况删除
    例如：
     "- 我们开始吧" → " 我们开始吧"               ← 前面无字符 删除
     "hands-on experience" → 原样保留           ← 前后都是拉丁:词内, 保留
     "3-5 个节点"          → 原样保留            ← 前是 3 后是 5(数字也算):保留,读「三五」
     "中文-English 混排"   → "中文English 混排"  ← 前面是汉字(非拉丁!), 删除
    """
    return _HYPHEN.sub("", _TTS_STRIP.sub("", text)).strip()


def gaps_of(units: list[Seg]) -> list[float]:
    """与下一单元的间隔;末单元按「假定还有 2s」计(仅作切点判定)。"""
    return [max(units[i + 1].start - units[i].end, 0.0) if i + 1 < len(units) else 2.0
            for i in range(len(units))]

def tol_of(u: Seg, gap: float) -> float:
    """许可时长=窗长+借用额度"""
    return (u.end - u.start) + min(gap, DUB.TOL)

def judge(est: float, tol: float, duration: float) -> int:
    """四态(附录 B.2-2)。tol=duration+借用额度(见 tol_of)。"""
    if est / DUB.SPEED_MAX > tol:
        return NEED_TRIM      # 硬顶变速也放不下 → 缩句
    if est > tol:
        return NEED_SPEED     # chunk 合并变速可解
    if est < duration - DUB.TOL:
        return SLOW           # 偏慢:垫静音即可,无恙
    return OK

def plan_chunks(units: list[Seg]) -> list[list[int]]:
    """chunk 规划(附录 B.2-3):gap≥TOL 为天然切点;连续 NEED_SPEED 向下合并(≤3 行)。"""
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
                chunks.append([i])            # 正常/慢/需缩句行:独立成块
                i += 1
                continue
            j = i                              # 快行向下试探合并
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
    """⑧顺产字幕:时间戳用变速后重排的新时间轴(new_sub_times,非原窗)。"""
    with open(path, "w", encoding="utf-8") as f:
        for i, ((st, en), text) in enumerate(zip(times, texts), 1):
            f.write(f"{i}\n{_fmt_ts(st)} --> {_fmt_ts(en)}\n{text}\n\n")
