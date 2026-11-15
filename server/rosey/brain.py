"""Rosey-Cherry's brain: asks Claude Code (headless `claude -p`) for a reply."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import tempfile
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("rosey.brain")

PERSONA_FILE = Path(__file__).with_name("persona.md")
MOODS = {"happy", "excited", "curious", "silly", "calm", "surprised", "sad", "thinking"}
MOOD_RE = re.compile(r"^\s*\[(\w+)\]\s*")

FALLBACK_REPLY = (
    "happy",
    "Oops, my robot brain got a little sleepy for a second. Can you ask me again?",
)

# Words that should never be spoken to a 4-year-old. If the model ever produces
# one, we throw the answer away and say something safe instead.
_BLOCKED = [
    # (Kept deliberately short: words like "died" are needed for dinosaur questions
    # and "killer whale" is an animal. The persona prompt does the real work.)
    r"\bblood\w*", r"\bguns?\b", r"\bsex\w*", r"\bdrugs?\b", r"\bdamn\b", r"\bhell\b",
    r"\bshit\w*", r"\bfuck\w*", r"\bbitch\w*", r"\bmurder\w*", r"https?://", r"\bwww\.",
]
_BLOCKED_RE = re.compile("|".join(_BLOCKED), re.IGNORECASE)
SAFE_REDIRECT = (
    "silly",
    "Hmm, let's talk about something else! Did you know octopuses have three hearts? "
    "What's your favourite animal?",
)


def parse_reply(raw: str) -> tuple[str, str]:
    """Split "[mood] text" into (mood, clean speakable text)."""
    text = raw.strip()
    mood = "happy"
    m = MOOD_RE.match(text)
    if m:
        if m.group(1).lower() in MOODS:
            mood = m.group(1).lower()
        text = text[m.end():]
    # Strip anything a TTS voice would read weirdly.
    text = re.sub(r"\[[a-z]+\]", "", text)               # stray mood tags
    text = re.sub(r"[*_#`>~|]", "", text)                 # markdown
    text = re.sub(r"[\U0001F000-\U0001FAFF☀-➿]", "", text)  # emoji
    text = re.sub(r"\s+", " ", text).strip()
    return mood, text


def is_safe(text: str) -> bool:
    return not _BLOCKED_RE.search(text)


@dataclass
class Conversation:
    turns: deque = field(default_factory=lambda: deque(maxlen=12))
    last_seen: float = field(default_factory=time.time)


class Brain:
    def __init__(
        self,
        claude_bin: str | None = None,
        model: str | None = None,
        timeout: float | None = None,
        fake: bool | None = None,
    ):
        self.claude_bin = claude_bin or os.environ.get("ROSEY_CLAUDE_BIN", "claude")
        self.model = model or os.environ.get("ROSEY_MODEL", "sonnet")
        self.timeout = timeout or float(os.environ.get("ROSEY_TIMEOUT", "60"))
        self.fake = fake if fake is not None else os.environ.get("ROSEY_FAKE_BRAIN") == "1"
        self.persona = PERSONA_FILE.read_text()
        self.conversations: dict[str, Conversation] = {}
        # Run claude in an empty dir so it never sees any project files.
        self.workdir = tempfile.mkdtemp(prefix="rosey-brain-")
        self._lock = asyncio.Semaphore(2)

    def _conversation(self, session: str) -> Conversation:
        # Forget conversations idle for more than 15 minutes.
        now = time.time()
        for key in [k for k, c in self.conversations.items() if now - c.last_seen > 900]:
            del self.conversations[key]
        conv = self.conversations.setdefault(session, Conversation())
        conv.last_seen = now
        return conv

    def _build_prompt(self, conv: Conversation, text: str) -> str:
        history = "\n".join(f"{who}: {what}" for who, what in conv.turns)
        parts = []
        if history:
            parts.append("Our conversation so far:\n" + history)
        parts.append(f"Ahilya just said (from speech recognition): \"{text}\"")
        parts.append("Reply as Rosey-Cherry, starting with a mood tag.")
        return "\n\n".join(parts)

    async def _ask_claude(self, prompt: str) -> str:
        if self.fake:
            return f"[happy] You said {prompt.split(chr(34))[-2]}! I'm Rosey-Cherry, and I'm in test mode."
        cmd = [
            self.claude_bin, "-p",
            "--model", self.model,
            "--system-prompt", self.persona,
            "--tools", "",
            "--strict-mcp-config",
            "--no-session-persistence",
            "--setting-sources", "",
            "--output-format", "json",
        ]
        async with self._lock:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=self.workdir,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                out, err = await asyncio.wait_for(proc.communicate(prompt.encode()), self.timeout)
            except asyncio.TimeoutError:
                proc.kill()
                raise RuntimeError("claude timed out")
        try:
            data = json.loads(out.decode())
        except json.JSONDecodeError:
            raise RuntimeError(f"claude exited {proc.returncode}: {err.decode()[:500]} {out.decode()[:500]}")
        if data.get("is_error") or proc.returncode != 0:
            raise RuntimeError(f"claude error: {data.get('result')}")
        return data.get("result", "")

    async def reply(self, text: str, session: str = "tablet") -> tuple[str, str]:
        text = text.strip()[:500]
        conv = self._conversation(session)
        try:
            raw = await self._ask_claude(self._build_prompt(conv, text))
            mood, answer = parse_reply(raw)
        except Exception:
            log.exception("brain failure")
            return FALLBACK_REPLY
        if not answer:
            return FALLBACK_REPLY
        if not is_safe(answer):
            log.warning("blocked unsafe reply: %r", answer)
            mood, answer = SAFE_REDIRECT
        conv.turns.append(("Ahilya", text))
        conv.turns.append(("Rosey-Cherry", answer))
        return mood, answer

    def reset(self, session: str = "tablet") -> None:
        self.conversations.pop(session, None)
