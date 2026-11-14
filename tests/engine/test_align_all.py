"""align_all 单测——用 ffmpeg 合成指定时长的音频,精确复现《对齐与变速详解》里的推演数字。

四个用例对应四态判定的三种下场(OK 不动 / NEED_SPEED 变速 / NEED_TRIM 截断)
外加文档 §5.2 的「块内间隙重复记账」超顶场景,调试时按断点逐行走即可看到:
  probe 实测 → speed 公式 → 顺序铺放 cur 累加 → 块尾死线 → L5 倒序截断

跑法(仓库根目录):
  uv run pytest tests/engine/test_align_all.py -v
单步调试推荐:
  uv run pytest tests/engine/test_align_all.py -k need_speed -x --pdb
"""
import asyncio
import shutil
import subprocess
from pathlib import Path

import pytest

from engine.dubbing.align import align_all
from engine.dubbing.types import Seg

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg 未安装")

TOL = 0.15  # mp3 编码有帧 padding,时长断言统一留 0.15s 容差


def synth_mp3(path: Path, duration_s: float, freq: int = 440) -> Path:
    """合成指定时长的正弦波 mp3(模拟 TTS 产物;probe_duration 读到的就是它)。"""
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostdin", "-y",
         "-f", "lavfi", "-i", f"sine=frequency={freq}:sample_rate=24000",
         "-t", f"{duration_s:.3f}", "-q:a", "9", str(path)],
        check=True, capture_output=True)
    return path


def zh(n_chars: int, n_mid_punct: int) -> str:
    """构造 est ≈ n_chars/4.5 + 0.1*n_mid_punct 的中文文本(est 只看字数和标点数)。"""
    body = "字" * (n_chars - n_mid_punct)
    parts, step = [], max((n_chars - n_mid_punct) // n_mid_punct, 1) if n_mid_punct else 0
    if not n_mid_punct:
        return body
    for i in range(0, len(body), step):
        parts.append(body[i:i + step])
        if len(parts) > n_mid_punct:
            break
    s = "，".join(parts[:n_mid_punct])
    return s[:n_chars - 1] if len(s) >= n_chars else s + "字" * (n_chars - len(s) - 1)


# ---------- 用例 1:OK,装得下,零变速 ----------
def test_ok_units_no_stretch(tmp_path: Path):
    """两行都装得下:各自单独成 chunk,speed=1.0,时间窗原样保留。"""
    units = [
        Seg(text=zh(8, 0), start=0.0, end=3.0),    # est≈1.78 ≤ tol
        Seg(text=zh(8, 0), start=5.0, end=8.0),    # gap=2.0≥TOL → 天然切点
    ]
    mp3s = [synth_mp3(tmp_path / "0000.mp3", 1.8),
            synth_mp3(tmp_path / "0001.mp3", 1.9)]
    new_times, log = asyncio.run(align_all(units, mp3s, tmp_path / "aligned"))

    assert len(log) == 2                       # 两行各自独立 chunk
    assert all(e["speed"] == 1.0 for e in log)  # 不变速
    assert new_times[0] == pytest.approx([0.0, 1.8], abs=TOL)
    assert new_times[1] == pytest.approx([5.0, 6.9], abs=TOL)  # 从自己窗口起点铺


# ---------- 用例 2:NEED_SPEED 单行,变速无损装下(文档 03 §四 例1) ----------
def test_need_speed_single_fits(tmp_path: Path):
    """4s 窗 + 借时 1.5 → tol=5.5;实测 7.0s → speed≈1.296,变速后 5.4s 装下,零截断。"""
    units = [Seg(text=zh(30, 3), start=0.0, end=4.0)]   # est ≈ 30/4.5+0.3 ≈ 7.0
    mp3s = [synth_mp3(tmp_path / "0000.mp3", 7.0)]
    new_times, log = asyncio.run(align_all(units, mp3s, tmp_path / "aligned"))

    speed = log[0]["speed"]
    assert speed == pytest.approx(7.0 / 5.4, abs=0.02)  # 1.296
    st, en = new_times[0]
    assert st == pytest.approx(0.0, abs=TOL)
    assert en == pytest.approx(5.4, abs=TOL)            # 7.0/1.296 ≈ 5.4 ≤ 死线 5.5
    assert en <= 4.0 + 1.5 + 0.05                       # 没超块尾死线 → 没触发 L5


# ---------- 用例 3:连续 NEED_SPEED 合并 + 间隙重复记账超顶(文档 03 §四 例2 / §5.2) ----------
def test_merged_chunk_gap_overrun_truncates(tmp_path: Path):
    """双 NEED_SPEED 合并成 chunk;变速公式分母没扣块内 gap → 必然小超 → L5 截 u1 尾。

    推演(文档 03 §5.2):u0 窗 2s、u1 窗 3→5s(gap=1.0),块尾借时 1.5 → 死线 6.5
    tol=[3.0,3.5] budget=6.5 speed=min(7.4/6.4,1.4)=1.156
    铺放:ad0≈2.85 → +gap/speed 0.87 → 3.72 → ad1≈3.55 → cur≈7.27 > 6.55 超顶≈0.77
    L5:u1 只保 6.5−3.72≈2.78s(截掉≈0.77s),u0 完整保留。
    """
    units = [
        Seg(text=zh(15, 1), start=0.0, end=2.0),   # est≈3.43>tol0=3.0,/1.4=2.45≤3.0 → NEED_SPEED
        Seg(text=zh(18, 1), start=3.0, end=5.0),   # est≈4.10>tol1=3.5,/1.4=2.93≤3.5 → NEED_SPEED
    ]
    mp3s = [synth_mp3(tmp_path / "0000.mp3", 3.3),
            synth_mp3(tmp_path / "0001.mp3", 4.1)]
    new_times, log = asyncio.run(align_all(units, mp3s, tmp_path / "aligned"))

    assert len(log) == 1                           # 两行合并成一个 chunk
    assert log[0]["chunk"] == [0, 1]
    assert log[0]["speed"] == pytest.approx(7.4 / 6.4, abs=0.02)  # ≈1.156
    assert log[0]["over"] == pytest.approx(7.4 / 1.15625 + 1.0 / 1.15625 - 6.5, abs=TOL)

    # u0 完整保留(≈3.3/1.156≈2.85)
    assert new_times[0][1] - new_times[0][0] == pytest.approx(3.3 / (7.4 / 6.4), abs=TOL)
    # u1 被 L5 截到死线:≈2.78s(截掉≈0.77s ≈ 两个字的朗读时长)
    assert new_times[1][1] - new_times[1][0] == pytest.approx(6.5 - 3.719, abs=0.25)
    assert new_times[1][1] == pytest.approx(6.5, abs=0.1)   # 截完正好压死线


# ---------- 用例 4:NEED_TRIM 单行,顶格变速仍超 → 尾部截断 ----------
def test_need_trim_truncates_to_deadline(tmp_path: Path):
    """4s 窗,实测 10s:speed 顶到 1.4 后仍 7.1s > 死线 5.5 → 截到 5.5s,丢≈1.6s。"""
    units = [Seg(text=zh(44, 2), start=0.0, end=4.0)]   # est≈10.0 > 1.4*5.5 → NEED_TRIM
    mp3s = [synth_mp3(tmp_path / "0000.mp3", 10.0)]
    new_times, log = asyncio.run(align_all(units, mp3s, tmp_path / "aligned"))

    assert log[0]["speed"] == pytest.approx(1.4, abs=0.01)   # 顶格
    st, en = new_times[0]
    assert st == pytest.approx(0.0, abs=TOL)
    assert en == pytest.approx(5.5, abs=0.1)                 # L5 截到死线(窗口4+借时1.5)
