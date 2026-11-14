"""Translate stage: English segment list -> Chinese segment list.

Chinese sources pass through (_is_mostly_cjk); English sources go batch-numbered translation:
batch failure degrades to per-segment; segment retry exhaustion propagates up (pipeline turns it into FAILED).

Contract with the llm client:
- llm_chat(..., temperature=0.1, json_mode=True) — numbered tasks are temperature-sensitive,
  json_object constrains output at the endpoint;
- When LLM is unconfigured llm_chat raises LlmError; no passthrough fallback here — English tasks must fail loudly
"""
import json
import re

from loguru import logger

from engine.dubbing.timeline import target_chars
from engine.dubbing.types import Seg
from engine.llm.client import get_llm_client, LlmEndpoint

_CJK_RATIO = 0.30  # CJK ratio threshold: above → Chinese source, skip translation
_BATCH_CHAR_LIMIT = 3000  # batching char cap: 300 segments ≈ 10 batches, segments within a batch contextualize each other

_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中。规则:"
    "1.忠实原意,口语化,适合朗读;"
    "2.技术术语保留英文(API、Kubernetes、React、gRPC);"
    "3.人名保留英文;"
    '4.保持编号一一对应,输出 JSON 数组,每项 "编号|译文";'
    '5.不添油加醋,不解释。'
)
_SINGLE_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中:忠实原意,口语化,适合朗读;"
    "技术术语与人名保留英文;只输出译文,不解释。"
)
# Seg variant: adds a sixth rule on top of the base five — translation length obeys the per-line char target
_SYSTEM_PROMPT_SEG = (
    "你是技术视频字幕译者,英译中,译文需与画面时间窗对齐。规则:"
    "1.忠实原意,口语化,适合朗读;"
    "2.技术术语保留英文(API、Kubernetes、React、gRPC);"
    "3.人名保留英文;"
    '4.每项输入格式为 "编号| 原文 |窗口N秒|目标≤M字",输出 JSON 数组,每项 "编号|译文";'
    "5.译文长度服从行内目标字数(±20%),超长宁可精炼不可截断意思;"
    "6.不添油加醋,不解释。"
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
    logger.debug(f"cjk check: ratio {ratio:.0%} (threshold {_CJK_RATIO:.0%}) -> "
                 f"{'chinese source, passthrough' if ratio > _CJK_RATIO else 'non-chinese, translation needed'}")
    return ratio > _CJK_RATIO


def _make_batches(units: list[str]) -> list[list[tuple[int, str]]]:
    """Batching: greedy accumulation, in-batch total chars <= _BATCH_CHAR_LIMIT; an over-limit segment stands alone.

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
    """Prompt construction (skeleton, first round, iterate during integration).

    prompt 构造(骨架,首轮,联调中迭代)。
    """
    items = [f"{i}| {text}" for i, text in batch]
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": json.dumps(items, ensure_ascii=False)},
    ]


def _loads_llm_json(raw: str) -> list:
    """Two-level tolerant parse: direct parse → strip fences then parse; both fail → raise (triggers upstream degradation).

    Under json_object mode some endpoints force a top-level object, the model wraps the array
    (e.g. {"translations": [...]}): valid JSON but not an array, unwrapped uniformly here.
    """

    def _to_arr(v) -> list:
        if isinstance(v, list):
            return v
        if isinstance(v, dict):
            for cand in v.values():
                if isinstance(cand, list):
                    logger.debug("llm output parse: top-level object, unwrapping embedded array")
                    return cand
            raise ValueError(f"no array found in JSON object: keys={list(v)[:5]}")
        raise ValueError(f"JSON top level is neither array nor object: {type(v).__name__}")

    try:
        arr = _to_arr(json.loads(raw))
        logger.debug("llm output parse: bare JSON parsed directly")
        return arr
    except json.JSONDecodeError:
        m = _FENCE_RE.search(raw)
        if not m:
            raise ValueError("LLM output is neither JSON nor has a fence to strip")
        arr = _to_arr(json.loads(m.group(1)))  # failure after stripping still raises naturally
        logger.debug("llm output parse: succeeded after stripping markdown fence")
        return arr


def _parse_translations(raw: str, batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Parse and backfill: raw LLM output -> [(global segment no., translation), ...], order aligned with batch.

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
        logger.warning(f"missing segment numbers (empty-string placeholder): {missing}")
    extras = sorted(set(got) - {i for i, _ in batch})
    if extras:
        logger.warning(f"extra segment numbers in output (ignored): {extras}")
    logger.debug(f"index alignment: expect {len(batch)}, filled {len(batch) - len(missing)}, "
                 f"missing {len(missing)}, extras {len(extras)}")
    return out


async def _translate_batch(batch: list[tuple[int, str]],
                           endpoint: LlmEndpoint | None = None) -> list[tuple[int, str]]:
    """Single batch: prompt -> llm_chat(json_mode) -> parse & backfill. Batch failure raises (degraded upstream).

    单批:prompt → llm_chat(json_mode) → 解析回填。批失败抛错(上游降级)。
    """
    raw = await get_llm_client().llm_chat(_build_messages(batch),
                                          temperature=0.1, json_mode=True, endpoint=endpoint)
    logger.debug(f"batch raw output preview: {raw[:120]!r}")
    pairs = _parse_translations(raw, batch)
    if all(not t for _, t in pairs):
        raise ValueError("batch translations all empty (index misalignment)")
    return pairs


async def _translate_single(idx: int, text: str, endpoint: LlmEndpoint | None = None) -> tuple[int, str]:
    """Degraded path: direct single-segment translation (no numbered protocol).

    llm_chat already has 3 exponential-backoff retries built in; another layer here would mean 9 calls —
    pointless amplification for local/free endpoints; exhaustion propagates and translate turns it into FAILED.

    降级路径:直接单段翻译(无编号协议)。llm_chat 已内置 3 次指数退避重试;这里再叠一层就是 9 次调用——对本地/免费端点
    是无谓放大;耗尽向上传播,translate 转成 FAILED。
    """
    if not text.strip():
        return idx, ""
    msgs = [
        {"role": "system", "content": _SINGLE_SYSTEM_PROMPT},
        {"role": "user", "content": text},
    ]
    raw = await get_llm_client().llm_chat(msgs, temperature=0.1, endpoint=endpoint)
    return idx, raw.strip()


async def translate_segs(segs: list[Seg], *, endpoint: LlmEndpoint | None = None) -> list[Seg]:
    """Video tasks: English Seg[] -> Chinese Seg[] (translation backfilled into original windows, window constraint in prompt).

    Coexists with translate() (audio, str version): batching/numbered alignment/tolerant degradation chain reused,
    the only differences are the per-item "window Ns|target ≤M chars" prompt and window backfill on output.
    """
    units = [s.text for s in segs]
    if _is_mostly_cjk(units):
        logger.info(f"skip translation: CJK source passthrough ({len(segs)} segs)")
        return segs

    batches = _make_batches(units)
    results: dict[int, str] = {}
    for bi, batch in enumerate(batches):
        items = [f"{i}| {text} |窗口{segs[i].end - segs[i].start:.1f}s|"
                 f"目标≤{target_chars(segs[i].end - segs[i].start)}字"
                 for i, text in batch]
        span = f"{batch[0][0]}~{batch[-1][0]}"
        logger.info(f"batch {bi + 1}/{len(batches)} start: segs {span}, window-constrained")
        try:
            results.update(await _translate_batch_seg(items, batch, endpoint))
            logger.info(f"batch {bi + 1}/{len(batches)} done ({len(batch)} segs, windowed)")
        except Exception as e:
            logger.warning(f"batch {bi + 1}/{len(batches)} failed ({e}), degrade to per-seg")
            for i, text in batch:
                logger.info(f"seg {i} single translate (degraded): {text[:40]!r}")
                try:
                    _, t = await _translate_single(i, text, endpoint)
                except Exception as se:
                    raise RuntimeError(f"seg {i} failed (still failing after batch degrade): {se}") from se
                results[i] = t
    if len(results) != len(segs):
        raise RuntimeError(f"translation backfill count mismatch: {len(results)}/{len(segs)}")
    return [Seg(text=results[i], start=segs[i].start, end=segs[i].end)
            for i in range(len(segs))]


async def _translate_batch_seg(items: list[str], batch: list[tuple[int, str]],
                               endpoint: LlmEndpoint | None = None) -> list[tuple[int, str]]:
    """Seg-variant single batch: windowed prompt -> llm_chat(json_mode) -> parse & backfill (reuses the shared parse chain).
    """
    msgs = [
        {"role": "system", "content": _SYSTEM_PROMPT_SEG},
        {"role": "user", "content": json.dumps(items, ensure_ascii=False)}, # 非ASCII字符原样输出，即中文原样保留
    ]
    raw = await get_llm_client().llm_chat(msgs, temperature=0.1, json_mode=True, endpoint=endpoint)
    logger.debug(f"batch raw output preview: {raw[:120]!r}")
    pairs = _parse_translations(raw, batch)
    if all(not t for _, t in pairs):
        raise ValueError("batch translations all empty (index misalignment)")
    return pairs


async def translate(units: list[str], *, endpoint: LlmEndpoint | None = None) -> list[str]:
    """Stage entry: English segment list -> Chinese segment list.

    Chinese sources pass through; English go batch translation, batch failure degrades per-segment, segment exhaustion propagates.
    Returned list length matches input, aligned by global segment number.

    stage 入口:英文分段列表 → 中文分段列表。中文源直通;英文走批量翻译,批失败降级逐段,段耗尽向上传播。
    返回列表与输入等长,按全局段号对齐。
    """
    if _is_mostly_cjk(units):
        logger.info(f"skip translation: CJK source passthrough ({len(units)} segs)")
        return units

    batches = _make_batches(units)
    logger.info(f"translation start: {len(units)} segs -> {len(batches)} batches (limit {_BATCH_CHAR_LIMIT} chars)")
    results: dict[int, str] = {}
    for bi, batch in enumerate(batches):
        span = f"{batch[0][0]}~{batch[-1][0]}"
        logger.info(f"batch {bi + 1}/{len(batches)} start: segs {span}, "
                    f"{sum(len(t) for _, t in batch)} chars")
        try:
            results.update(await _translate_batch(batch, endpoint))
            logger.info(f"batch {bi + 1}/{len(batches)} done ({len(batch)} segs)")
        except Exception as e:
            logger.warning(f"batch {bi + 1}/{len(batches)} failed ({e}), degrade to per-seg")
            for i, text in batch:
                logger.info(f"seg {i} single translate (degraded): {text[:40]!r}")
                try:
                    _, t = await _translate_single(i, text, endpoint)
                except Exception as se:
                    raise RuntimeError(f"seg {i} failed (still failing after batch degrade): {se}") from se
                results[i] = t
    if len(results) != len(units):
        raise RuntimeError(f"translation backfill count mismatch: {len(results)}/{len(units)}")
    return [results[i] for i in range(len(units))]
