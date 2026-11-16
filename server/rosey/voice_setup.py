"""Make Rosey-Cherry's voice sample from a grown-up's recordings.

    python -m rosey.voice_setup                      # download the default YouTube playlist, pick clips
    python -m rosey.voice_setup --from-file me.wav   # or use your own recording(s) instead
    python -m rosey.voice_setup --clip 3:40.5-52     # pick exact clips: video number:start-end (seconds)
    python -m rosey.voice_setup --preview-only       # just re-make preview.wav

Writes server/voice/ref_*.wav (the voice sample) and server/voice/preview.wav (listen to this!).
Everything in server/voice/ stays on this machine; it is git-ignored.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

from .voice import SAMPLE_RATE, VOICE_DIR, reference_clips

PLAYLIST = "https://www.youtube.com/playlist?list=PLTlvbpeSIpeJ0KBlQQWpujwy7iLQG90HD"
RAW = VOICE_DIR / "raw"
SKIP_EDGES = 12.0      # seconds skipped at the start/end of each video (intro/outro music)
CLIP_MIN, CLIP_MAX = 4.0, 11.0
TARGET_TOTAL = 40.0    # seconds of voice to keep
PREVIEW_TEXT = (
    "Hi Ahilya! I'm Rosey-Cherry. Did you know that octopuses have three hearts? "
    "What a wonderful world we live in!"
)


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def need(tool: str) -> None:
    if not shutil.which(tool):
        sys.exit(f"'{tool}' is needed. On Ubuntu/Debian: sudo apt install {tool}")


def download(url: str) -> list[Path]:
    import yt_dlp

    RAW.mkdir(parents=True, exist_ok=True)
    opts = {
        "format": "bestaudio/best",
        "outtmpl": str(RAW / "%(playlist_index)02d-%(id)s.%(ext)s"),
        "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "wav"}],
        "quiet": True,
        "no_warnings": True,
    }
    print(f"Downloading audio from {url} ...")
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download([url])
    files = []
    for f in sorted(RAW.glob("*.wav")):
        if f.stem.endswith("-24k"):
            continue
        out = f.with_name(f.stem + "-24k.wav")
        if not out.exists():
            run(["ffmpeg", "-y", "-i", str(f), "-ac", "1", "-ar", str(SAMPLE_RATE), str(out)])
        files.append(out)
    print(f"  {len(files)} recordings ready")
    return files


def duration(path: Path) -> float:
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)])
    return float(out.stdout.strip() or 0)


def speech_regions(path: Path) -> list[tuple[float, float]]:
    """Stretches of continuous voice between pauses (ffmpeg silencedetect)."""
    err = run(["ffmpeg", "-i", str(path), "-af", "silencedetect=noise=-32dB:d=0.3", "-f", "null", "-"]).stderr
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", err)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", err)]
    total = duration(path)
    regions, cursor = [], 0.0
    for s, e in zip(starts, ends + [total]):
        if s > cursor:
            regions.append((cursor, s))
        cursor = e
    if cursor < total:
        regions.append((cursor, total))
    return [(a, b) for a, b in regions if a >= SKIP_EDGES and b <= total - SKIP_EDGES]


def pick_clips(files: list[Path]) -> list[tuple[Path, float, float]]:
    """A few clean sentences from several different videos."""
    picks, total = [], 0.0
    per_file = {f: [r for r in speech_regions(f) if CLIP_MIN <= r[1] - r[0] <= CLIP_MAX] for f in files}
    if not any(per_file.values()):
        # Probably background music under the voice (no pauses found): take fixed windows instead.
        print("  No clear pauses found (background music?). Using fixed 10 s windows.")
        per_file = {f: [(30.0, 40.0), (60.0, 70.0)] for f in files if duration(f) > 80}
    # Prefer clips from the middle of each story, taking turns between videos.
    queues = {}
    for f, regions in per_file.items():
        mid = duration(f) / 2
        queues[f] = sorted(regions, key=lambda r: abs((r[0] + r[1]) / 2 - mid))
    while total < TARGET_TOTAL and any(queues.values()):
        for f, queue in queues.items():
            if queue and total < TARGET_TOTAL:
                a, b = queue.pop(0)
                picks.append((f, a, b))
                total += b - a
    return picks


def write_refs(clips: list[tuple[Path, float, float]]) -> None:
    for old in reference_clips():
        old.unlink()
    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    for i, (f, a, b) in enumerate(clips):
        out = VOICE_DIR / f"ref_{i:02d}.wav"
        run([
            "ffmpeg", "-y", "-ss", f"{a:.2f}", "-to", f"{b:.2f}", "-i", str(f),
            "-ac", "1", "-ar", str(SAMPLE_RATE), "-af", "loudnorm", str(out),
        ])
        print(f"  {out.name}: {f.name} {a:.1f}s-{b:.1f}s")


def make_preview() -> Path:
    from .voice import XttsEngine

    clips = reference_clips()
    if not clips:
        sys.exit("No voice sample yet.")
    print("Loading the voice model (the first time it downloads ~2 GB and asks you to accept its licence)...")
    engine = XttsEngine(clips)
    out = VOICE_DIR / "preview.wav"
    out.write_bytes(engine.synth(PREVIEW_TEXT))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default=PLAYLIST, help="YouTube video or playlist with the grown-up's voice")
    ap.add_argument("--from-file", nargs="+", type=Path, help="use these recordings instead of YouTube")
    ap.add_argument("--clip", action="append", default=[], help="exact clip: VIDEO_NUMBER:START-END in seconds")
    ap.add_argument("--preview-only", action="store_true")
    ap.add_argument("--no-preview", action="store_true")
    args = ap.parse_args()
    need("ffmpeg")
    need("ffprobe")

    if not args.preview_only:
        if args.from_file:
            files = []
            RAW.mkdir(parents=True, exist_ok=True)
            for src in args.from_file:
                out = RAW / f"own-{src.stem}-24k.wav"
                run(["ffmpeg", "-y", "-i", str(src), "-ac", "1", "-ar", str(SAMPLE_RATE), str(out)])
                files.append(out)
        else:
            files = download(args.url)
        if not files:
            sys.exit("No recordings found.")

        if args.clip:
            clips = []
            for spec in args.clip:
                m = re.fullmatch(r"(\d+):([\d.]+)-([\d.]+)", spec)
                if not m:
                    sys.exit(f"Bad --clip {spec!r}; use e.g. 3:40.5-52")
                clips.append((files[int(m.group(1)) - 1], float(m.group(2)), float(m.group(3))))
        else:
            clips = pick_clips(files)
        print(f"Voice sample: {len(clips)} clips, {sum(b - a for _, a, b in clips):.0f} seconds")
        write_refs(clips)

    if not args.no_preview:
        out = make_preview()
        print(f"\nListen to the result: {out}")
        print("Not right (music, wrong bits)? Re-run with --clip VIDEO:START-END, or --from-file your-recording.wav")


if __name__ == "__main__":
    main()
