# Rosey-Cherry 🍒🤖

A friendly robot for Ahilya (age 4). Rosey-Cherry is a happy cherry face on an Android tablet.
When Ahilya says **"Rosey-Cherry!"**, Rosey listens to her and answers out loud. She can:

- tell short, gentle stories
- answer questions about the world in a way that makes Ahilya want to explore and experiment (safely!)
- tell silly, kid-friendly jokes

```
 ┌──────────── tablet (Android app) ───────────┐          ┌──────── server ─────────┐
 │ listens for "Rosey-Cherry" (on-device STT)  │  HTTP    │ FastAPI  /chat          │
 │ animated face + text-to-speech voice        │ ───────▶ │  └─ claude -p (Claude   │
 └─────────────────────────────────────────────┘ ◀─────── │     Code = the brain)   │
                                                  mood+reply └─────────────────────────┘
```

- `server/` – the brain. A small Python web server that turns what Ahilya said into a reply by running
  Claude Code headless (`claude -p`) with Rosey's persona (`server/rosey/persona.md`).
- `android/` – the tablet app (Kotlin, no extra libraries). Draws the face, listens, speaks.
- `scripts/` – one-command installers for the server and for the tablet.

## Keeping it safe for a 4-year-old

- `server/rosey/persona.md` is Rosey's whole personality and her safety rules (simple words, nothing scary,
  only safe "try this with a grown-up" experiments, sends sensitive questions to Mummy/Daddy, no personal
  info, no links, no buying things). Edit it to change how Rosey behaves – it is plain English.
- Claude Code runs with **all tools disabled**, no MCP servers, no settings/hooks, in an empty temporary
  folder – it can only talk, never touch files or the internet.
- Every reply passes a last-chance word filter (`server/rosey/brain.py`); anything that trips it is replaced
  with a safe, fun redirect.
- The tablet only talks to your server, protected by a token.
- Replies are short and spoken; nothing is shown as text for her to read.

## 1. Install the brain server (machine with Claude Code)

Requirements: Python 3.10+, [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and logged in
(run `claude` once by hand to log in).

```bash
git clone git@github.com:SteliosKyriacou/Ahis-robot.git
```

```bash
cd Ahis-robot && ./scripts/install_server.sh
```

This creates a virtualenv, writes `server/.env` with a random tablet token, checks that Claude Code answers,
and starts the server as a background service (systemd user service on Linux, launchd on macOS) on port
**8765**. At the end it prints the **server address** and **token** to put into the tablet.

To update later: `git pull && ./scripts/install_server.sh`.

Settings in `server/.env`:

| variable | default | meaning |
|---|---|---|
| `ROSEY_TOKEN` | random | shared secret the tablet must send |
| `ROSEY_PORT` | 8765 | port |
| `ROSEY_MODEL` | `sonnet` | Claude model Claude Code uses |
| `ROSEY_CLAUDE_BIN` | detected | path to the `claude` binary |
| `ROSEY_TIMEOUT` | 60 | seconds before giving up on an answer |
| `ROSEY_FAKE_BRAIN` | – | `1` = echo replies without Claude (testing) |

Quick test from the server:

```bash
curl -s -H "X-Rosey-Token: $(grep ROSEY_TOKEN server/.env | cut -d= -f2)" -H 'Content-Type: application/json' -d '{"text":"why is the sky blue?"}' http://localhost:8765/chat
```

The tablet and the server need to be on the same network (or reachable over e.g. Tailscale). If the server
has a firewall, allow TCP 8765.

## 2. Install the app on the tablet (USB)

1. On the tablet: **Settings → About tablet → tap "Build number" 7 times**, then
   **Settings → Developer options → USB debugging: on**.
2. Plug the tablet into a computer with `adb` (`brew install --cask android-platform-tools` on macOS) and
   tap **Allow** on the tablet.
3. Run, using the address and token printed by the server installer:

```bash
./scripts/install_tablet.sh http://SERVER_IP:8765 YOUR_TOKEN
```

The script downloads the latest APK that GitHub Actions builds on every push to `main` (release
`tablet-latest`, needs the `gh` CLI logged in), installs it, grants the microphone permission and saves the
server settings into the app. You can also download `rosey-cherry.apk` from the repo's Releases page by hand.

### Building the APK yourself (optional)

Needs JDK 17, the Android SDK (`ANDROID_HOME` set) and Gradle 8.11:

```bash
cd android && gradle assembleDebug
```

Then `./scripts/install_tablet.sh ...` will pick up `android/app/build/outputs/apk/debug/app-debug.apk`.

## 3. Optional: give Rosey a grown-up's voice

Rosey can speak in a clone of a parent's voice, made **locally on the server** with the open-source
XTTS-v2 model (nothing is sent to a cloud service). On the server:

```bash
sudo apt install ffmpeg
```

```bash
./scripts/install_voice.sh
```

This installs the model software (~3 GB), downloads the audio of the "Stories for my daughter" playlist,
cuts ~40 seconds of clean voice into `server/voice/` (git-ignored: the recordings never go into this
public repo), makes `server/voice/preview.wav` to listen to, and restarts the server with the voice on.
The first run shows the model's licence (Coqui Public Model Licence, non-commercial use) for you to accept.

Not happy with the sample? Pick exact clips (`--clip 3:40.5-52` = video 3, 40.5 s to 52 s) or use your own
recording (`--from-file me.wav`, 1–2 minutes of you reading, no music). See `python -m rosey.voice_setup --help`.

Speech is made one sentence at a time, so Rosey starts talking after the first sentence is ready while the
rest are generated. Without a graphics card expect a few seconds per sentence. If the voice isn't ready or
fails, the tablet uses its own built-in voice. Settings in `server/.env`: `ROSEY_VOICE=1`,
`ROSEY_VOICE_SPEED` (1.0), `ROSEY_VOICE_THREADS`.

## Using Rosey-Cherry

- Say **"Rosey-Cherry"** (or "Hey Rosie") – she says "Yes, Ahilya?" and her face glows blue while listening.
- Or say it all in one go: **"Rosey-Cherry, why do cats purr?"**
- After answering she listens for a follow-up for a few seconds, then goes back to waiting for her name.
- **Tap her face** to talk without saying her name; tap while she's talking to interrupt.
- **Grown-ups:** hold her face for **3 seconds** to open settings (server address and token).
- Tip: use Android's *screen pinning* (Settings → Security → App pinning) so Ahilya stays in the app.

Her face changes with her mood: happy, excited (sparkles!), curious, silly (wink + tongue), calm,
surprised and – very briefly – sad. Three bouncing dots mean she's thinking.

### Notes / known limits

- Wake-word detection uses Android's built-in speech recognizer in a loop, so the Google app (or another
  speech service) must be installed. For best results download the offline English speech pack
  (Settings → Google → Voice → Offline speech recognition).
- While waiting for her name the app mutes the media volume to hide the recognizer's "beep", and unmutes
  it to talk. If Rosey is ever silent, check the media volume.

## Development

```bash
cd server && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/python -m pytest -q
```

Run the server without Claude (echo mode): `ROSEY_FAKE_BRAIN=1 ./server/run.sh`.
