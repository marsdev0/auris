"""Translate stage: English segment list -> Chinese segment list.

Chinese sources pass through (_is_mostly_cjk); English sources go batch-numbered translation:
batch failure degrades to per-segment; segment retry exhaustion propagates up (pipeline turns it into FAILED).

Contract with the llm client:
- llm_chat(..., temperature=0.1, json_mode=True) — numbered tasks are temperature-sensitive,
  json_object constrains output at the endpoint (D1-mini §1 compat fallback built in);
- When LLM is unconfigured llm_chat raises LlmError; no passthrough fallback here — English tasks must fail loudly
  (D4 acceptance criterion 4).
"""
import json
import re

from loguru import logger

from engine.dubbing.timeline import target_chars
from engine.dubbing.types import Seg
from engine.llm.client import get_llm_client

_CJK_RATIO = 0.30          # CJK ratio threshold: above → Chinese source, skip translation
_BATCH_CHAR_LIMIT = 3000   # batching char cap: 300 segments ≈ 10 batches, segments within a batch contextualize each other

_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中。规则:①忠实原意,口语化,适合朗读;"
    "②技术术语保留英文(API、Kubernetes、React、gRPC);③人名保留英文;"
    '④保持编号一一对应,输出 JSON 数组,每项 "编号|译文";⑤不添油加醋,不解释。'
)
_SINGLE_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中:忠实原意,口语化,适合朗读;"
    "技术术语与人名保留英文;只输出译文,不解释。"
)
# Seg variant (D5): adds a sixth rule on top of D4's five — translation length obeys the per-line char target
_SYSTEM_PROMPT_SEG = (
    "你是技术视频字幕译者,英译中,译文需与画面时间窗对齐。规则:"
    "①忠实原意,口语化,适合朗读;"
    "②技术术语保留英文(API、Kubernetes、React、gRPC);"
    "③人名保留英文;"
    '④每项输入格式为 "编号| 原文 |窗口N秒|目标≤M字",输出 JSON 数组,每项 "编号|译文";'
    "⑤译文长度服从行内目标字数(±20%),超长宁可精炼不可截断意思;"
    "⑥不添油加醋,不解释。"
)
_FENCE_RE = re.compile(r"```(?:json)?\s*(.+?)\s*```", re.DOTALL)


def _is_mostly_cjk(units: list[str]) -> bool:
    """Chinese-source detection.

    Criterion: among all segments concatenated with whitespace removed, CJK basic block (\u4e00-\u9fff) ratio > threshold.
    Global stat across segments (not per-segment): an occasional Chinese quote in an English video does not misclassify.
    Returns False with no effective chars (empty lists go to the downstream empty check, no special-casing here).
    """
    chars = [c for u in units for c in u if not c.isspace()]
    if not chars:
        return False
    cjk = sum(1 for c in chars if '\u4e00' <= c <= '\u9fff')
    ratio = cjk / len(chars)
    logger.debug(f"cjk 判定: 占比 {ratio:.0%}(阈值 {_CJK_RATIO:.0%}) -> "
                 f"{'中文源,直通' if ratio > _CJK_RATIO else '非中文源,需翻译'}")
    return ratio > _CJK_RATIO


def _make_batches(units: list[str]) -> list[list[tuple[int, str]]]:
    """Batching (§4.2): greedy accumulation, in-batch total chars <= _BATCH_CHAR_LIMIT; an over-limit segment stands alone.

    Greedy is optimal: fill each batch before opening a new one → fewest batches → fewest LLM calls.
    Empty-text segments still occupy a slot (numbered alignment needs it; backfilled as empty translation).
    """
    batches: list[list[tuple[int, str]]] = []
    cur: list[tuple[int, str]] = []
    cur_len = 0
    for i, text in enumerate(units):
        # Batch-close condition: current batch non-empty AND adding one more exceeds the limit.
        # The "cur non-empty" check must not be dropped: when a single segment itself exceeds the limit cur is empty,
        # and closing then would produce an empty batch; an empty batch wastes an LLM call and may trip the endpoint.
        if cur and cur_len + len(text) > _BATCH_CHAR_LIMIT:
            batches.append(cur)
            cur, cur_len = [], 0
        cur.append((i, text))
        cur_len += len(text)
    if cur:  # tail batch: unclosed remainder after the loop
        batches.append(cur)
    return batches


def _build_messages(batch: list[tuple[int, str]]) -> list[dict]:
    """Prompt construction (§4.3 skeleton, first round, iterate during integration)."""
    items = [f"{i}| {text}" for i, text in batch]
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": json.dumps(items, ensure_ascii=False)},
    ]


def _loads_llm_json(raw: str) -> list:
    """Two-level tolerant parse (§4.2): direct parse → strip fences then parse; both fail → raise (triggers upstream degradation).

    Under json_object mode some endpoints force a top-level object, the model wraps the array
    (e.g. {"translations": [...]}): valid JSON but not an array, unwrapped uniformly here.
    """

    def _to_arr(v) -> list:
        if isinstance(v, list):
            return v
        if isinstance(v, dict):
            for cand in v.values():
                if isinstance(cand, list):
                    logger.debug("llm 输出解析: 顶层是对象,剥出内嵌数组")
                    return cand
            raise ValueError(f"JSON 对象内无数组: keys={list(v)[:5]}")
        raise ValueError(f"JSON 顶层非数组/对象: {type(v).__name__}")

    try:
        arr = _to_arr(json.loads(raw))
        logger.debug("llm 输出解析: 裸 JSON 直接成功")
        return arr
    except json.JSONDecodeError:
        m = _FENCE_RE.search(raw)
        if not m:
            raise ValueError("LLM 输出非 JSON 且无围栏可剥")
        arr = _to_arr(json.loads(m.group(1)))  # failure after stripping still raises naturally
        logger.debug("llm 输出解析: 剥 markdown 围栏后成功")
        return arr


def _parse_translations(raw: str, batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Parse and backfill (§4.2): raw LLM output -> [(global segment no., translation), ...], order aligned with batch.

    Missing numbers: empty-string placeholder + warning (never silent); extras/invalid items: ignored + warning.
    """
    got: dict[int, str] = {}
    for item in _loads_llm_json(raw):
        if not isinstance(item, str):
            continue
        num_s, sep, text = item.partition("|")
        if not sep:
            continue
        try:
            num = int(num_s.strip())
        except ValueError:
            continue
        got[num] = text.strip()

    out: list[tuple[int, str]] = []
    missing = []
    for i, _ in batch:
        if i in got:
            out.append((i, got[i]))
        else:
            missing.append(i)
            out.append((i, ""))  # missing-number placeholder
    if missing:
        logger.warning(f"翻译漏号(空串占位): {missing}")
    extras = sorted(set(got) - {i for i, _ in batch})
    if extras:
        logger.warning(f"翻译输出多号(忽略): {extras}")
    logger.debug(f"编号对齐: 期望 {len(batch)}, 回填 {len(batch) - len(missing)}, "
                 f"漏号 {len(missing)}, 多号 {len(extras)}")
    return out


async def _translate_batch(batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Single batch: prompt -> llm_chat(json_mode) -> parse & backfill. Batch failure raises (degraded upstream)."""
    raw = await get_llm_client().llm_chat(_build_messages(batch),
                                          temperature=0.1, json_mode=True)
    logger.debug(f"批原始输出预览: {raw[:120]!r}")
    pairs = _parse_translations(raw, batch)
    if all(not t for _, t in pairs):
        raise ValueError("批译文全空(编号未对上)")
    return pairs


async def _translate_single(idx: int, text: str) -> tuple[int, str]:
    """Degraded path: direct single-segment translation (no numbered protocol).

    llm_chat already has 3 exponential-backoff retries built in; another layer here would mean 9 calls —
    pointless amplification for local/free endpoints; exhaustion propagates and translate turns it into FAILED.
    """
    if not text.strip():
        return idx, ""
    msgs = [
        {"role": "system", "content": _SINGLE_SYSTEM_PROMPT},
        {"role": "user", "content": text},
    ]
    raw = await get_llm_client().llm_chat(msgs, temperature=0.1)
    return idx, raw.strip()


async def translate_segs(segs: list[Seg]) -> list[Seg]:
    """D5 video tasks: English Seg[] -> Chinese Seg[] (translation backfilled into original windows, window constraint in prompt).

    Coexists with translate() (audio, str version): batching/numbered alignment/tolerant degradation chain reused,
    the only differences are the per-item "window Ns|target ≤M chars" prompt and window backfill on output.
    """
    units = [s.text for s in segs]
    if _is_mostly_cjk(units):
        logger.info(f"跳过翻译:CJK 源直通({len(segs)} 段)")
        return segs

    batches = _make_batches(units)
    results: dict[int, str] = {}
    for bi, batch in enumerate(batches):
        items = [f"{i}| {text} |窗口{segs[i].end - segs[i].start:.1f}s|"
                 f"目标≤{target_chars(segs[i].end - segs[i].start)}字"
                 for i, text in batch]
        span = f"{batch[0][0]}~{batch[-1][0]}"
        logger.info(f"批 {bi + 1}/{len(batches)} 开始: 段 {span}, 带窗约束")
        try:
            results.update(await _translate_batch_seg(items, batch))
            logger.info(f"批翻译 {bi + 1}/{len(batches)} 完成({len(batch)} 段,带窗)")
        except Exception as e:
            logger.warning(f"批 {bi + 1}/{len(batches)} 失败({e}),降级逐段")
            for i, text in batch:
                logger.info(f"段 {i} 直翻(降级): {text[:40]!r}")
                try:
                    _, t = await _translate_single(i, text)
                except Exception as se:
                    raise RuntimeError(f"段 {i} 翻译失败(批降级后仍失败): {se}") from se
                results[i] = t
    if len(results) != len(segs):
        raise RuntimeError(f"翻译回填数不一致: {len(results)}/{len(segs)}")
    return [Seg(text=results[i], start=segs[i].start, end=segs[i].end)
            for i in range(len(segs))]


async def _translate_batch_seg(items: list[str], batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Seg-variant single batch: windowed prompt -> llm_chat(json_mode) -> parse & backfill (reuses D4 parse chain)."""
    msgs = [
        {"role": "system", "content": _SYSTEM_PROMPT_SEG},
        {"role": "user", "content": json.dumps(items, ensure_ascii=False)},
    ]
    raw = await get_llm_client().llm_chat(msgs, temperature=0.1, json_mode=True)
    logger.debug(f"批原始输出预览: {raw[:120]!r}")
    pairs = _parse_translations(raw, batch)
    if all(not t for _, t in pairs):
        raise ValueError("批译文全空(编号未对上)")
    return pairs


async def translate(units: list[str]) -> list[str]:
    """Stage ③ entry: English segment list -> Chinese segment list (§4.2).

    Chinese sources pass through; English go batch translation, batch failure degrades per-segment, segment exhaustion propagates.
    Returned list length matches input, aligned by global segment number.
    """
    if _is_mostly_cjk(units):
        logger.info(f"跳过翻译:CJK 源直通({len(units)} 段)")
        return units

    batches = _make_batches(units)
    logger.info(f"翻译启动: {len(units)} 段 → {len(batches)} 批(批上限 {_BATCH_CHAR_LIMIT} 字符)")
    results: dict[int, str] = {}
    for bi, batch in enumerate(batches):
        span = f"{batch[0][0]}~{batch[-1][0]}"
        logger.info(f"批 {bi + 1}/{len(batches)} 开始: 段 {span}, "
                    f"{sum(len(t) for _, t in batch)} 字符")
        try:
            results.update(await _translate_batch(batch))
            logger.info(f"批翻译 {bi + 1}/{len(batches)} 完成({len(batch)} 段)")
        except Exception as e:
            logger.warning(f"批 {bi + 1}/{len(batches)} 失败({e}),降级逐段")
            for i, text in batch:
                logger.info(f"段 {i} 直翻(降级): {text[:40]!r}")
                try:
                    _, t = await _translate_single(i, text)
                except Exception as se:
                    raise RuntimeError(f"段 {i} 翻译失败(批降级后仍失败): {se}") from se
                results[i] = t
    if len(results) != len(units):
        raise RuntimeError(f"翻译回填数不一致: {len(results)}/{len(units)}")
    return [results[i] for i in range(len(units))]
