import os

os.environ["ROSEY_FAKE_BRAIN"] = "1"
os.environ["ROSEY_TOKEN"] = "test-token"

from fastapi.testclient import TestClient  # noqa: E402

from rosey.app import app  # noqa: E402
from rosey.brain import is_safe, parse_reply  # noqa: E402

client = TestClient(app)


def test_parse_reply_mood_and_cleanup():
    assert parse_reply("[excited] **Wow!** The moon 🌙 is big.") == ("excited", "Wow! The moon is big.")
    assert parse_reply("No tag here") == ("happy", "No tag here")
    assert parse_reply("[grumpy] hi") == ("happy", "hi")


def test_safety_filter():
    assert is_safe("Octopuses have three hearts!")
    assert not is_safe("visit https://example.com")
    assert not is_safe("blood everywhere")
    assert is_safe("The dinosaurs died out a long, long time ago. Killer whales are dolphins!")


def test_health():
    assert client.get("/health").json()["ok"] is True


def test_chat_requires_token():
    assert client.post("/chat", json={"text": "hi"}).status_code == 401


def test_chat_fake_brain():
    r = client.post("/chat", json={"text": "why is the sky blue"}, headers={"X-Rosey-Token": "test-token"})
    assert r.status_code == 200
    body = r.json()
    assert body["mood"] == "happy"
    assert "why is the sky blue" in body["reply"]


def test_split_for_speech():
    from rosey.voice import split_for_speech

    chunks = split_for_speech("Wow! The moon is big. It goes round the Earth. Look up tonight! " + "la " * 120)
    assert chunks[0] == "Wow!"
    assert all(len(c) <= 230 for c in chunks)
    assert " ".join(chunks).replace("  ", " ").startswith("Wow! The moon is big.")


def test_voice_chunks_fake_engine():
    import asyncio
    import time

    from rosey.voice import Voice

    v = Voice(fake=True)
    for _ in range(100):
        if v.ready:
            break
        time.sleep(0.02)
    chunks = v.start("Hello there. How are you today?")
    assert [c["text"] for c in chunks] == ["Hello there.", "How are you today?"]
    jid, n = chunks[0]["url"].split("/")[2:4]
    wav = asyncio.run(v.get(jid, int(n)))
    assert wav[:4] == b"RIFF"
