"""FastAPI server for Gemini Live ICT/SMC Video Translator (PC & Mobile)."""

from __future__ import annotations

import asyncio
import os
import shutil
import socket
import uuid
from pathlib import Path
from typing import Any, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles

from translator import (
    DEFAULT_ICT_PROMPT,
    download_video,
    extract_pcm16k,
    mux_dubbed_media,
    save_pcm_as_wav,
    translate_audio_live,
)

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent
MEDIA_DIR = BASE_DIR / "media"
STATIC_DIR = BASE_DIR / "static"
MEDIA_DIR.mkdir(parents=True, exist_ok=True)
STATIC_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="ICT/SMC Video Translator (Gemini Live)")
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

JOBS: dict[str, dict[str, Any]] = {}


def get_lan_ip() -> str:
    """Detect local LAN IP address so the user can open the app from mobile."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


@app.on_event("startup")
async def on_startup() -> None:
    lan_ip = get_lan_ip()
    print("\n" + "=" * 56)
    print("  ICT / SMC Video Translator (Gemini Live) is ready!")
    print(f"  PC URL     : http://localhost:8000")
    print(f"  Mobile URL : http://{lan_ip}:8000  (ต่อ Wi-Fi เดียวกัน)")
    print("=" * 56 + "\n")


@app.get("/", response_class=HTMLResponse)
async def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/config")
async def get_config() -> dict[str, Any]:
    return {
        "has_env_key": bool(os.environ.get("GEMINI_API_KEY", "").strip()),
        "default_prompt": DEFAULT_ICT_PROMPT,
        "lan_url": f"http://{get_lan_ip()}:8000",
    }


async def _run_job(
    job_id: str,
    url: Optional[str],
    uploaded_path: Optional[Path],
    api_key: str,
    target_lang: str,
    system_instruction: str,
    orig_volume: float,
) -> None:
    job = JOBS[job_id]

    def update_progress(pct: int, msg: str) -> None:
        job["progress"] = pct
        job["message"] = msg

    try:
        if url:
            update_progress(10, "กำลังดาวน์โหลดวิดีโอจากลิงก์...")
            orig_path = await asyncio.to_thread(download_video, url, MEDIA_DIR, job_id)
        elif uploaded_path is not None:
            orig_path = uploaded_path
        else:
            raise RuntimeError("กรุณาใส่ลิงก์วิดีโอหรือเลือกไฟล์ MP4")

        job["orig_video_url"] = f"/media/{orig_path.name}"

        update_progress(22, "กำลังแยกเสียงจากวิดีโอ...")
        pcm_bytes = await asyncio.to_thread(extract_pcm16k, orig_path)

        update_progress(30, "กำลังเชื่อมต่อ Gemini Live Translate...")
        thai_pcm, transcript = await translate_audio_live(
            pcm_bytes=pcm_bytes,
            api_key=api_key,
            target_lang=target_lang,
            system_instruction=system_instruction,
            progress_cb=update_progress,
        )

        if not thai_pcm:
            raise RuntimeError(
                "Gemini ไม่ได้ส่งเสียงพากย์กลับมา (ลองตรวจสอบ API Key หรือความยาว/เสียงของคลิป)"
            )

        update_progress(92, "กำลังรวมเสียงพากย์ไทยเข้ากับวิดีโอ...")
        thai_wav = MEDIA_DIR / f"{job_id}_thai.wav"
        out_mp4 = MEDIA_DIR / f"{job_id}_dubbed.mp4"
        out_mp3 = MEDIA_DIR / f"{job_id}_thai.mp3"

        await asyncio.to_thread(save_pcm_as_wav, thai_pcm, thai_wav)
        await asyncio.to_thread(
            mux_dubbed_media, orig_path, thai_wav, out_mp4, out_mp3, orig_volume
        )

        job["status"] = "done"
        job["progress"] = 100
        job["message"] = "แปลและพากย์เสียงเสร็จเรียบร้อย!"
        job["transcript"] = transcript
        job["dubbed_video_url"] = f"/media/{out_mp4.name}"
        job["audio_url"] = f"/media/{out_mp3.name}"

    except Exception as exc:
        job["status"] = "error"
        job["message"] = str(exc)
        job["error"] = str(exc)


@app.post("/api/translate")
async def create_translation(
    url: Optional[str] = Form(default=None),
    api_key: Optional[str] = Form(default=None),
    target_lang: str = Form(default="th"),
    system_instruction: str = Form(default=DEFAULT_ICT_PROMPT),
    orig_volume: float = Form(default=0.12),
    file: Optional[UploadFile] = File(default=None),
) -> dict[str, str]:
    resolved_key = (api_key or "").strip() or os.environ.get("GEMINI_API_KEY", "").strip()
    if not resolved_key:
        raise HTTPException(
            status_code=400,
            detail="กรุณาใส่ Gemini API Key (รับฟรีได้จาก Google AI Studio)",
        )

    clean_url = (url or "").strip() or None
    if not clean_url and file is None:
        raise HTTPException(
            status_code=400,
            detail="กรุณาวางลิงก์ YouTube / Shorts / IG หรือเลือกไฟล์ MP4",
        )

    job_id = uuid.uuid4().hex[:10]
    uploaded_path: Optional[Path] = None

    if file is not None and file.filename:
        ext = Path(file.filename).suffix.lower() or ".mp4"
        uploaded_path = MEDIA_DIR / f"{job_id}_orig{ext}"
        with uploaded_path.open("wb") as f:
            shutil.copyfileobj(file.file, f)

    JOBS[job_id] = {
        "job_id": job_id,
        "status": "processing",
        "progress": 5,
        "message": "เริ่มต้นประมวลผล...",
        "transcript": "",
        "dubbed_video_url": None,
        "orig_video_url": None,
        "audio_url": None,
        "error": None,
    }

    asyncio.create_task(
        _run_job(
            job_id=job_id,
            url=clean_url,
            uploaded_path=uploaded_path,
            api_key=resolved_key,
            target_lang=target_lang,
            system_instruction=system_instruction,
            orig_volume=max(0.0, min(1.0, orig_volume)),
        )
    )

    return {"job_id": job_id}


@app.get("/api/jobs/{job_id}")
async def get_job(job_id: str) -> dict[str, Any]:
    if job_id not in JOBS:
        raise HTTPException(status_code=404, detail="ไม่พบงานนี้")
    return JOBS[job_id]


@app.get("/media/{filename}")
async def serve_media(filename: str) -> FileResponse:
    safe_name = Path(filename).name
    target = MEDIA_DIR / safe_name
    if not target.exists():
        cached = MEDIA_DIR / "cache" / safe_name
        if cached.exists():
            target = cached
        else:
            raise HTTPException(status_code=404, detail="ไม่พบไฟล์")
    return FileResponse(target)
