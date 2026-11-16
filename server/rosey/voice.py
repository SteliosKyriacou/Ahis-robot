"""Rosey-Cherry's own voice: a clone of a grown-up's voice made with XTTS-v2 (runs locally, CPU is fine).

The reference recordings live in server/voice/ (never committed; see scripts/install_voice.sh).
Speech is generated one sentence-chunk at a time on a single worker thread, so the tablet can
start playing the first sentence while the rest are still being made.
"""
from __future__ import annotations

import asyncio
from array import array
import io
import logging
import math
import os
import re
import sys
import threading
import time
import uuid
import wave
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path

log = logging.getLogger("rosey.voice")

VOICE_DIR = Path(os.environ.get("ROSEY_VOICE_DIR", Path(__file__).resolve().parent.parent / "voice"))
SAMPLE_RATE = 24000
MAX_CHUNK = 220  # XTTS English limit is ~250 characters per call

# Phrases the tablet says often; made once at start-up so they play instantly.
COMMON_PHRASES = [
    "Hi Ahilya! I'm Rosey-Cherry! Say my name whenever you want to talk to me.",
    "Yes, Ahilya?",
    "I'm listening!",
    "Hi Ahilya! What shall we talk about?",
    "Hello! I'm all ears. Well, robot ears!",
    "Hmm, can you ask me that again?",
    "Oops, my robot brain got a little sleepy for a second. Can you ask me again?",
]


def reference_clips(voice_dir: Path = VOICE_DIR) -> list[Path]:
    return sorted(voice_dir.glob("ref_*.wav"))


def split_for_speech(text: str) -> list[str]:
    """Split into sentence chunks. The first chunk is kept short so speaking starts sooner."""
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()]
    chunks: list[str] = []
    for s in sentences:
        while len(s) > MAX_CHUNK:  # very long sentence: break at a comma or space
            cut = max(s.rfind(",", 0, MAX_CHUNK), s.rfind(" ", 0, MAX_CHUNK))
            cut = cut if cut > 40 else MAX_CHUNK
            chunks.append(s[:cut + 1].strip())
            s = s[cut + 1:].strip()
        if chunks and len(chunks) > 1 and len(chunks[-1]) + len(s) < 120:
            chunks[-1] += " " + s  # merge short sentences (but never into the first chunk)
        elif s:
            chunks.append(s)
    return chunks


def to_wav(samples, rate: int = SAMPLE_RATE) -> bytes:
    """float samples in [-1, 1] -> 16-bit mono WAV bytes."""
    pcm = array("h", (int(max(-1.0, min(1.0, float(x))) * 32767) for x in samples))
    if sys.byteorder == "big":
        pcm.byteswap()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


class XttsEngine:
    """Thin wrapper round Coqui XTTS-v2 with the speaker conditioning computed once."""

    def __init__(self, clips: list[Path]):
        import torch
        from TTS.api import TTS

        threads = int(os.environ.get("ROSEY_VOICE_THREADS", "0")) or os.cpu_count() or 4
        torch.set_num_threads(threads)
        device = "cuda" if torch.cuda.is_available() else "cpu"
        log.info("loading XTTS-v2 on %s (%d threads)...", device, threads)
        self.model = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to(device).synthesizer.tts_model
        self.gpt_cond, self.speaker = self.model.get_conditioning_latents(
            audio_path=[str(c) for c in clips], gpt_cond_len=30, max_ref_length=60,
        )
        log.info("voice ready (%d reference clips)", len(clips))

    def synth(self, text: str) -> bytes:
        out = self.model.inference(
            text, "en", self.gpt_cond, self.speaker,
            temperature=0.65, repetition_penalty=5.0, speed=float(os.environ.get("ROSEY_VOICE_SPEED", "1.0")),
        )
        wav = out["wav"]
        wav = wav.tolist() if hasattr(wav, "tolist") else list(wav)
        return to_wav(wav)


class FakeEngine:
    """Beeps instead of talking. For tests and for trying the tablet without a voice."""

    def synth(self, text: str) -> bytes:
        n = int(SAMPLE_RATE * min(2.0, 0.05 * len(text) + 0.2))
        return to_wav([0.2 * math.sin(2 * math.pi * 440 * i / SAMPLE_RATE) for i in range(n)])


class Voice:
    def __init__(self, enabled: bool | None = None, fake: bool | None = None):
        if enabled is None:
            enabled = os.environ.get("ROSEY_VOICE", "0") == "1"
        self.fake = fake if fake is not None else os.environ.get("ROSEY_FAKE_VOICE") == "1"
        self.enabled = enabled or self.fake
        self.engine = None
        self.error: str | None = None
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="voice")
        self._jobs: dict[str, dict] = {}
        self._cache: dict[str, Future] = {}
        self._lock = threading.Lock()
        if self.enabled:
            threading.Thread(target=self._load, daemon=True).start()

    @property
    def ready(self) -> bool:
        return self.engine is not None

    def _load(self) -> None:
        try:
            if self.fake:
                self.engine = FakeEngine()
            else:
                clips = reference_clips()
                if not clips:
                    raise RuntimeError(f"no reference clips in {VOICE_DIR} (run scripts/install_voice.sh)")
                self.engine = XttsEngine(clips)
            for phrase in COMMON_PHRASES:
                self._cached(phrase)
        except Exception as e:  # keep the server running; the tablet falls back to its own voice
            self.error = str(e)
            log.exception("voice failed to load")

    def _cached(self, text: str) -> Future:
        with self._lock:
            fut = self._cache.get(text)
            if fut is None:
                fut = self._cache[text] = self._pool.submit(self.engine.synth, text)
            return fut

    def start(self, text: str, interrupt: bool = True) -> list[dict]:
        """Queue speech for `text`; returns [{url, text}] for the tablet to fetch in order."""
        if not self.ready:
            return []
        now = time.time()
        with self._lock:
            for jid in [j for j, job in self._jobs.items() if now - job["created"] > 600]:
                del self._jobs[jid]
            if interrupt:  # a new answer makes any unfinished old one pointless
                for job in self._jobs.values():
                    for f in job["own"]:
                        f.cancel()
        chunks = split_for_speech(text)
        futures, own = [], []
        for chunk in chunks:
            if chunk in COMMON_PHRASES or len(chunk) <= 40:
                futures.append(self._cached(chunk))
            else:
                fut = self._pool.submit(self.engine.synth, chunk)
                futures.append(fut)
                own.append(fut)
        jid = uuid.uuid4().hex[:12]
        with self._lock:
            self._jobs[jid] = {"created": now, "futures": futures, "own": own}
        return [{"url": f"/speech/{jid}/{i}", "text": c} for i, c in enumerate(chunks)]

    async def get(self, jid: str, n: int, timeout: float = 120) -> bytes | None:
        job = self._jobs.get(jid)
        if not job or not 0 <= n < len(job["futures"]):
            return None
        fut = job["futures"][n]
        if fut.cancelled():
            return None
        try:
            return await asyncio.wait_for(asyncio.wrap_future(fut), timeout)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            return None
