"""HTTP API the tablet talks to."""
from __future__ import annotations

import logging
import os
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .brain import Brain

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("rosey")

app = FastAPI(title="Rosey-Cherry brain")
brain = Brain()


def check_token(x_rosey_token: str | None = Header(default=None)) -> None:
    expected = os.environ.get("ROSEY_TOKEN", "")
    if expected and not secrets.compare_digest(x_rosey_token or "", expected):
        raise HTTPException(status_code=401, detail="bad token")


class ChatIn(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    session: str = Field(default="tablet", max_length=64)


class ChatOut(BaseModel):
    mood: str
    reply: str


@app.get("/health")
def health() -> dict:
    return {"ok": True, "name": "Rosey-Cherry", "fake_brain": brain.fake}


@app.post("/chat", response_model=ChatOut, dependencies=[Depends(check_token)])
async def chat(body: ChatIn) -> ChatOut:
    log.info("Ahilya: %s", body.text)
    mood, reply = await brain.reply(body.text, body.session)
    log.info("Rosey [%s]: %s", mood, reply)
    return ChatOut(mood=mood, reply=reply)


@app.post("/reset", dependencies=[Depends(check_token)])
def reset(body: ChatIn | None = None) -> dict:
    brain.reset(body.session if body else "tablet")
    return {"ok": True}
