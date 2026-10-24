# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""translate stage 调试/冒烟(P12-D4 ②)。用法:仓库根目录

  uv run python tests/test_translate.py        # 三个场景全跑
  uv run python tests/test_translate.py 2      # 只跑场景 2

场景1: 中文源直通(零 LLM 调用)
场景2: 英文批翻(真实 LLM)
场景3: 模拟批失败 → 降级逐段(monkeypatch,观察降级链)
"""
import asyncio
import sys

import engine.dubbing.translate as tr


async def scene1_passthrough():
    """中文源 → 直通,原样返回,看 cjk 判定日志。"""
    zh = ["今天我们聊聊 Kubernetes 集群的滚动升级", "先看一个常见的配置错误"]
    out = await tr.translate(zh)
    print("直通返回(应原样):", out)


async def scene2_batch():
    """英文批翻:真实 LLM,看聚批/编号对齐/回填。"""
    en = [
        "And so my fellow Americans, ask not what your country can do for you.",
        "Ask what you can do for your country.",
        "We choose to go to the moon in this decade.",
        "We deployed a gRPC API on Kubernetes across three nodes.",
    ]
    out = await tr.translate(en)
    print("批翻结果:")
    for i, t in enumerate(out):
        print(f"  [{i}] {t}")


async def scene3_degrade():
    """monkeypatch 批函数抛错,强制走降级逐段链路。"""
    async def boom(batch):
        raise RuntimeError("模拟批失败(如网络抖动)")

    orig = tr._translate_batch
    tr._translate_batch = boom
    try:
        out = await tr.translate(["And so my fellow Americans, ask not what your country can do for you.",
                                  "Ask what you can do for your country."])
    finally:
        tr._translate_batch = orig
    print("降级逐段结果:")
    for i, t in enumerate(out):
        print(f"  [{i}] {t}")


async def main():
    scenes = {"1": ("中文源直通", scene1_passthrough),
              "2": ("英文批翻(真实 LLM)", scene2_batch),
              "3": ("批失败降级(模拟)", scene3_degrade)}
    pick = sys.argv[1] if len(sys.argv) > 1 else None
    for k, (name, fn) in scenes.items():
        if pick and k != pick:
            continue
        print(f"---- 场景{k}: {name} ----")
        await fn()
        print()


if __name__ == "__main__":
    asyncio.run(main())
