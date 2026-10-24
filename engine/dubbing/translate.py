"""translate stage:英文段列表 → 中文段列表

中文源直通(_is_mostly_cjk 判定);英文源聚批编号翻译:
批级失败降级逐段,逐段重试耗尽才上抛(pipeline 层转 FAILED)。

与 llm client 的对接口径:
- llm_chat(..., temperature=0.1, json_mode=True)——编号任务对温度敏感,
  json_object 由端点约束输出(D1-mini §1 兼容降级已内建);
- LLM 未配置时 llm_chat 抛 LlmError,这里不做兜底直通——英文任务必须失败得明确
  (D4 验收标准 4)。
"""
import json
import re

from loguru import logger

from engine.llm.client import get_llm_client

_CJK_RATIO = 0.30          # CJK 占比阈值:超过判中文源,跳过翻译
_BATCH_CHAR_LIMIT = 3000   # 聚批字符上限:300 段 ≈ 10 批,批内段落互为上下文

_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中。规则:①忠实原意,口语化,适合朗读;"
    "②技术术语保留英文(API、Kubernetes、React、gRPC);③人名保留英文;"
    '④保持编号一一对应,输出 JSON 数组,每项 "编号|译文";⑤不添油加醋,不解释。'
)
_SINGLE_SYSTEM_PROMPT = (
    "你是技术视频字幕译者,英译中:忠实原意,口语化,适合朗读;"
    "技术术语与人名保留英文;只输出译文,不解释。"
)
_FENCE_RE = re.compile(r"```(?:json)?\s*(.+?)\s*```", re.DOTALL)


def _is_mostly_cjk(units: list[str]) -> bool:
    """中文源判定

    口径:全部段拼接、去空白后的字符里,CJK 基本区(\\u4e00-\\u9fff)占比 > 阈值。
    跨段全局统计(非逐段判):英文视频里偶尔引一句中文不会误判为中文源。
    无有效字符返回 False(空列表交给下游空判,不在这里搞特殊分支)。
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
    """聚批(§4.2):贪心累加,批内总字符 <= _BATCH_CHAR_LIMIT;单段自身超限独立成批。

    贪心即最优:每批尽量装满再开新批 → 批数最少 → LLM 调用次数最少。
    空文本段照常占位(编号对齐需要它,回填时对应空译文)。
    """
    batches: list[list[tuple[int, str]]] = []
    cur: list[tuple[int, str]] = []
    cur_len = 0
    for i, text in enumerate(units):
        # 封批条件:当前批非空 且 再加就超限。
        # 「cur 非空」的检查不能省:单段自身超限时 cur 为空,若直接封批会产生空批;
        # 空批进 LLM 调用既浪费又可能触发端点报错。超限单段让它独自入批,下一段自然封它。
        if cur and cur_len + len(text) > _BATCH_CHAR_LIMIT:
            batches.append(cur)
            cur, cur_len = [], 0
        cur.append((i, text))
        cur_len += len(text)
    if cur:  # 尾批:循环结束剩余的未封批
        batches.append(cur)
    return batches


def _build_messages(batch: list[tuple[int, str]]) -> list[dict]:
    """prompt 构造(§4.3 骨架,首轮,联调迭代)。"""
    items = [f"{i}| {text}" for i, text in batch]
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": json.dumps(items, ensure_ascii=False)},
    ]


def _loads_llm_json(raw: str) -> list:
    """两级容错 parse(§4.2):直接 parse → 剥围栏 parse;均失败 raise(触发上层降级)。

    json_object 模式下部分端点强制顶层为对象,模型会把数组包一层
    (如 {"translations": [...]}):合法 JSON 但非数组,统一在此剥出。
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
        arr = _to_arr(json.loads(m.group(1)))  # 剥后仍失败则自然 raise
        logger.debug("llm 输出解析: 剥 markdown 围栏后成功")
        return arr


def _parse_translations(raw: str, batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """解析回填(§4.2):LLM 原始输出 → [(全局段号, 译文), ...],顺序与 batch 一致。

    漏号:空串占位并 warning(不静默);多号/无效项:忽略并告警。
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
            out.append((i, ""))  # 漏号占位
    if missing:
        logger.warning(f"翻译漏号(空串占位): {missing}")
    extras = sorted(set(got) - {i for i, _ in batch})
    if extras:
        logger.warning(f"翻译输出多号(忽略): {extras}")
    logger.debug(f"编号对齐: 期望 {len(batch)}, 回填 {len(batch) - len(missing)}, "
                 f"漏号 {len(missing)}, 多号 {len(extras)}")
    return out


async def _translate_batch(batch: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """单批翻译:prompt → llm_chat(json_mode) → 解析回填。批级失败 raise(由上层降级)。"""
    raw = await get_llm_client().llm_chat(_build_messages(batch),
                                          temperature=0.1, json_mode=True)
    logger.debug(f"批原始输出预览: {raw[:120]!r}")
    pairs = _parse_translations(raw, batch)
    if all(not t for _, t in pairs):
        raise ValueError("批译文全空(编号未对上)")
    return pairs


async def _translate_single(idx: int, text: str) -> tuple[int, str]:
    """降级路径:单段直翻(无编号协议)。

    llm_chat 已内建 3 次指数退避重试,段级不再包一层——再包等于 9 次调用,
    对本地/免费端点是无意义放大;重试耗尽上抛由 translate 转 FAILED。
    """
    if not text.strip():
        return idx, ""
    msgs = [
        {"role": "system", "content": _SINGLE_SYSTEM_PROMPT},
        {"role": "user", "content": text},
    ]
    raw = await get_llm_client().llm_chat(msgs, temperature=0.1)
    return idx, raw.strip()


async def translate(units: list[str]) -> list[str]:
    """stage ③ 入口:英文段列表 → 中文段列表(§4.2)。

    中文源直通;英文源聚批批翻,批失败该批降级逐段,段失败(重试耗尽)整任务上抛。
    返回列表长度与输入一致,按全局段号对齐。
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
