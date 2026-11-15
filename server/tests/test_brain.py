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
