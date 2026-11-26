"""Core video download, audio extraction, Gemini Live translation, and dubbing."""

from __future__ import annotations

import asyncio
import hashlib
import os
import shutil
import subprocess
import wave
from pathlib import Path
from typing import Callable, Optional

import imageio_ffmpeg
from google import genai
from google.genai import types
import yt_dlp

MODEL = "models/gemini-3.5-live-translate-preview"
INPUT_SAMPLE_RATE = 16000
OUTPUT_SAMPLE_RATE = 24000
CHUNK_BYTES = 4096  # 2048 samples of 16-bit mono PCM (128 ms at 16 kHz)

DEFAULT_ICT_PROMPT = (
    "Translate into natural spoken Thai like a friendly trading mentor explaining "
    "to a friend, NOT formal essay Thai. Keep all ICT and SMC trading terms in "
    "English without translating them (such as: gap, FVG, Fair Value Gap, "
    "Order Block, OB, Breaker, Mitigation, BOS, CHoCH, MSS, CISD, Liquidity, "
    "Sweep, Raid, SMT, Killzone, Silver Bullet, PO3, AMD, HTF, LTF, Bias, "
    "Draw on Liquidity, DOL, Premium, Discount, OTE, EQH, EQL, BPR, IFVG, "
    "NWOG, NDOG, Stop Hunt, Inducement, Range, High, Low)."
)


def get_ffmpeg_exe() -> str:
    """Return path to ffmpeg executable (system PATH or bundled imageio-ffmpeg)."""
    system_ffmpeg = shutil.which("ffmpeg")
    if system_ffmpeg:
        return system_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def download_video(url: str, out_dir: Path, job_id: str) -> Path:
    """Download video from YouTube, Shorts, Instagram, TikTok, etc. (cached by URL)."""
    cache_dir = out_dir / "cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    url_hash = hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]
    cached_mp4 = cache_dir / f"{url_hash}.mp4"
    if cached_mp4.exists() and cached_mp4.stat().st_size > 0:
        return cached_mp4

    out_template = str(cache_dir / f"{url_hash}.%(ext)s")
    ffmpeg_exe = get_ffmpeg_exe()

    ydl_opts = {
        "format": "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[ext=mp4]/best",
        "merge_output_format": "mp4",
        "outtmpl": out_template,
        "ffmpeg_location": ffmpeg_exe,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        filename = ydl.prepare_filename(info)

    if cached_mp4.exists():
        return cached_mp4
    if Path(filename).exists():
        return Path(filename)

    matches = list(cache_dir.glob(f"{url_hash}.*"))
    if not matches:
        raise RuntimeError("ไม่สามารถดาวน์โหลดวิดีโอจากลิงก์นี้ได้")
    return matches[0]


def extract_pcm16k(video_path: Path) -> bytes:
    """Extract mono 16kHz 16-bit signed PCM audio from a video/audio file."""
    ffmpeg_exe = get_ffmpeg_exe()
    cmd = [
        ffmpeg_exe,
        "-y",
        "-i",
        str(video_path),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(INPUT_SAMPLE_RATE),
        "-f",
        "s16le",
        "pipe:1",
    ]
    proc = subprocess.run(cmd, capture_output=True, check=False)
    if proc.returncode != 0 or not proc.stdout:
        err = proc.stderr.decode("utf-8", errors="ignore")[-400:]
        raise RuntimeError(f"ไม่สามารถแยกเสียงจากไฟล์ได้: {err}")
    return proc.stdout


def has_audio_stream(video_path: Path) -> bool:
    """Check whether the input file contains a video stream (vs audio-only)."""
    ffmpeg_exe = get_ffmpeg_exe()
    cmd = [ffmpeg_exe, "-i", str(video_path)]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    return "Audio:" in proc.stderr


def has_video_stream(media_path: Path) -> bool:
    """Check whether the input file contains a video stream."""
    ffmpeg_exe = get_ffmpeg_exe()
    cmd = [ffmpeg_exe, "-i", str(media_path)]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    return "Video:" in proc.stderr


async def _run_live_session(
    client: genai.Client,
    pcm_bytes: bytes,
    config: types.LiveConnectConfig,
    progress_cb: Optional[Callable[[int, str], None]],
    pct_start: int,
    pct_end: int,
    seg_label: str,
) -> tuple[bytes, str]:
    audio_chunks: list[bytes] = []
    text_parts: list[str] = []
    send_done = asyncio.Event()
    last_rx_time = asyncio.get_event_loop().time()
    total_bytes = len(pcm_bytes)

    async with client.aio.live.connect(model=MODEL, config=config) as session:

        async def sender() -> None:
            sent = 0
            for i in range(0, total_bytes, CHUNK_BYTES):
                chunk = pcm_bytes[i : i + CHUNK_BYTES]
                await session.send(input={"data": chunk, "mime_type": "audio/pcm"})
                sent += len(chunk)
                if progress_cb and (i // CHUNK_BYTES) % 10 == 0:
                    pct = pct_start + int((sent / total_bytes) * (pct_end - pct_start))
                    progress_cb(min(pct, pct_end), f"กำลังแปลเสียง {seg_label}...")
                # Pace at ~5x realtime (25ms per 128ms audio chunk)
                await asyncio.sleep(0.025)

            # Send 1.5s of trailing silence so the model flushes the final sentence
            silence = b"\x00" * int(INPUT_SAMPLE_RATE * 2 * 1.5)
            for i in range(0, len(silence), CHUNK_BYTES):
                await session.send(
                    input={"data": silence[i : i + CHUNK_BYTES], "mime_type": "audio/pcm"}
                )
                await asyncio.sleep(0.02)

            send_done.set()

        async def receiver() -> None:
            nonlocal last_rx_time
            while True:
                turn = session.receive()
                async for response in turn:
                    last_rx_time = asyncio.get_event_loop().time()
                    if data := response.data:
                        audio_chunks.append(data)
                    if text := response.text:
                        text_parts.append(text)
                if send_done.is_set():
                    break

        send_task = asyncio.create_task(sender())
        recv_task = asyncio.create_task(receiver())

        await send_task
        if progress_cb:
            progress_cb(pct_end, f"กำลังรอเสียงพากย์ส่วนสุดท้าย {seg_label}...")

        while not recv_task.done():
            await asyncio.sleep(0.3)
            if send_done.is_set():
                idle = asyncio.get_event_loop().time() - last_rx_time
                if idle > 3.5:
                    recv_task.cancel()
                    break

        try:
            await recv_task
        except asyncio.CancelledError:
            pass

    return b"".join(audio_chunks), "".join(text_parts).strip()


async def _translate_segment_live(
    client: genai.Client,
    pcm_bytes: bytes,
    target_lang: str,
    system_instruction: Optional[str],
    progress_cb: Optional[Callable[[int, str], None]],
    pct_start: int,
    pct_end: int,
    seg_label: str,
) -> tuple[bytes, str]:
    """Stream one PCM segment (<= 3 min) to Gemini Live Translate with config fallback."""
    configs_to_try: list[types.LiveConnectConfig] = []

    if system_instruction and system_instruction.strip():
        configs_to_try.append(
            types.LiveConnectConfig(
                response_modalities=["AUDIO"],
                translation_config=types.TranslationConfig(
                    target_language_code=target_lang,
                ),
                system_instruction=system_instruction.strip(),
            )
        )

    # Minimal config matching Google AI Studio's live-translate schema
    configs_to_try.append(
        types.LiveConnectConfig(
            response_modalities=["AUDIO"],
            translation_config=types.TranslationConfig(
                target_language_code=target_lang,
            ),
        )
    )

    last_err: Optional[Exception] = None
    for cfg in configs_to_try:
        try:
            return await _run_live_session(
                client=client,
                pcm_bytes=pcm_bytes,
                config=cfg,
                progress_cb=progress_cb,
                pct_start=pct_start,
                pct_end=pct_end,
                seg_label=seg_label,
            )
        except Exception as exc:
            last_err = exc
            if "1007" in str(exc) or "invalid argument" in str(exc).lower():
                continue
            raise

    assert last_err is not None
    raise last_err


async def translate_audio_live(
    pcm_bytes: bytes,
    api_key: str,
    target_lang: str = "th",
    system_instruction: Optional[str] = None,
    progress_cb: Optional[Callable[[int, str], None]] = None,
) -> tuple[bytes, str]:
    """Stream 16kHz PCM audio to Gemini Live Translate in <=3-min segments."""
    total_bytes = len(pcm_bytes)
    if total_bytes == 0:
        raise RuntimeError("ไม่พบข้อมูลเสียงในคลิป")

    client = genai.Client(
        http_options={"api_version": "v1beta"},
        api_key=api_key,
    )

    segment_bytes = INPUT_SAMPLE_RATE * 2 * 180  # 3 minutes per Live session
    segments = [
        pcm_bytes[i : i + segment_bytes] for i in range(0, total_bytes, segment_bytes)
    ]

    all_audio: list[bytes] = []
    all_texts: list[str] = []
    n_segs = len(segments)

    for idx, seg in enumerate(segments):
        pct_start = 30 + int((idx / n_segs) * 58)
        pct_end = 30 + int(((idx + 1) / n_segs) * 58)
        label = f"(ช่วงที่ {idx + 1}/{n_segs})" if n_segs > 1 else ""
        seg_audio, seg_text = await _translate_segment_live(
            client=client,
            pcm_bytes=seg,
            target_lang=target_lang,
            system_instruction=system_instruction,
            progress_cb=progress_cb,
            pct_start=pct_start,
            pct_end=pct_end,
            seg_label=label,
        )
        if seg_audio:
            all_audio.append(seg_audio)
        if seg_text:
            all_texts.append(seg_text)

    return b"".join(all_audio), "\n".join(all_texts).strip()


def save_pcm_as_wav(pcm_bytes: bytes, wav_path: Path, sample_rate: int = OUTPUT_SAMPLE_RATE) -> None:
    """Write raw mono 16-bit PCM bytes to a WAV file."""
    with wave.open(str(wav_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_bytes)


def mux_dubbed_media(
    orig_path: Path,
    thai_wav_path: Path,
    out_mp4_path: Path,
    out_mp3_path: Path,
    orig_volume: float = 0.12,
) -> None:
    """Create standalone Thai MP3 and a Thai-dubbed MP4 video."""
    ffmpeg_exe = get_ffmpeg_exe()

    # 1. Save standalone Thai MP3 for audio-only listening
    subprocess.run(
        [
            ffmpeg_exe,
            "-y",
            "-i",
            str(thai_wav_path),
            "-codec:a",
            "libmp3lame",
            "-qscale:a",
            "3",
            str(out_mp3_path),
        ],
        capture_output=True,
        check=True,
    )

    if not has_video_stream(orig_path):
        # Audio-only input: wrap Thai audio in MP4 container or just skip video mux
        subprocess.run(
            [
                ffmpeg_exe,
                "-y",
                "-i",
                str(thai_wav_path),
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                str(out_mp4_path),
            ],
            capture_output=True,
            check=True,
        )
        return

    # 2. Mux Thai audio with original video
    if orig_volume > 0.0 and has_audio_stream(orig_path):
        filter_complex = (
            f"[0:a]volume={orig_volume:.2f}[a0];"
            f"[1:a]volume=1.35,apad[a1];"
            f"[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[aout]"
        )
        cmd = [
            ffmpeg_exe,
            "-y",
            "-i",
            str(orig_path),
            "-i",
            str(thai_wav_path),
            "-filter_complex",
            filter_complex,
            "-map",
            "0:v:0",
            "-map",
            "[aout]",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-movflags",
            "+faststart",
            str(out_mp4_path),
        ]
    else:
        cmd = [
            ffmpeg_exe,
            "-y",
            "-i",
            str(orig_path),
            "-i",
            str(thai_wav_path),
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-shortest",
            "-movflags",
            "+faststart",
            str(out_mp4_path),
        ]

    proc = subprocess.run(cmd, capture_output=True, check=False)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="ignore")[-400:]
        raise RuntimeError(f"รวมเสียงพากย์เข้าวิดีโอไม่สำเร็จ: {err}")
