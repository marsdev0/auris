/**
 * 浏览器音频文件 → PCM 16bit LE mono @16k 分帧。
 *
 * AudioContext.decodeAudioData 解码任意格式(wav/mp3/m4a/ogg...),
 * OfflineAudioContext 重采样到 16k,再转 int16。
 * 与 engine 侧 ws_stream_client.py 的 librosa 处理同口径。
 */

export const SAMPLE_RATE = 16000
export const FRAME_MS = 100
const FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS) / 1000 // 1600 样本/帧

export interface PcmData {
  pcm: Int16Array
  durationMs: number
}

export async function decodeToPcm16k(file: File): Promise<PcmData> {
  const raw = await file.arrayBuffer()
  // 先用临时 AudioContext 解码(采样率任意),再离线重采样到 16k
  const decodeCtx = new AudioContext()
  let buffer: AudioBuffer
  try {
    buffer = await decodeCtx.decodeAudioData(raw)
  } finally {
    void decodeCtx.close()
  }

  // OfflineAudioContext(channels=1) 一步完成 downmix + 重采样
  const offline = new OfflineAudioContext(1, Math.ceil(buffer.duration * SAMPLE_RATE), SAMPLE_RATE)
  const src = offline.createBufferSource()
  src.buffer = buffer
  src.connect(offline.destination)
  src.start()
  const resampled = await offline.startRendering()

  // float32 [-1,1] → int16 LE
  const floats = resampled.getChannelData(0)
  const pcm = new Int16Array(floats.length)
  for (let i = 0; i < floats.length; i++) {
    const s = Math.max(-1, Math.min(1, floats[i]))
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }
  return { pcm, durationMs: (pcm.length / SAMPLE_RATE) * 1000 }
}

export function framePcm(pcm: Int16Array): ArrayBuffer[] {
  const frames: ArrayBuffer[] = []
  for (let i = 0; i < pcm.length; i += FRAME_SAMPLES) {
    const slice = pcm.subarray(i, Math.min(i + FRAME_SAMPLES, pcm.length))
    // 拷贝出独立 buffer(subarray 是视图,不能直接 send)
    const copy = new Int16Array(slice.length)
    copy.set(slice)
    frames.push(copy.buffer)
  }
  return frames
}
