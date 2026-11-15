# Voice Vanish (Fabric, Minecraft 1.21.1)

Say the name of a block or mob in Simple Voice Chat. Five seconds later (configurable) it is deleted
from every loaded chunk within 30 chunks of the speaker (configurable), in every direction and at every
height. Works for every player on the server, in normal conversation, no wake word.

- Say **"stone"** -> every stone block around you vanishes.
- Say **"zombie"**, **"pigs"**, **"creeper"**, **"wither"**, **"ender dragon"** (or just **"dragon"**) -> those mobs vanish.
- Plurals work ("pigs"), multi-word names work ("oak log", "wither skeleton"), and spaces do not matter
  ("deep slate" = "deepslate").
- Modded blocks and mobs work too, using their registry name.
- A short glassy "poof" sound plays when things vanish.

## What you need

1. Java 21 and Minecraft 1.21.1 with Fabric Loader.
2. **Fabric API** for 1.21.1.
3. **Simple Voice Chat** (Fabric, 1.21.1) on the server AND on every player's client.
4. The built `voicevanish-1.0.0.jar` in the server's `mods` folder (clients do not need it).
5. Speech recognition: **nothing to install by hand.** The first time the server starts, the mod downloads
   the Whisper speech model (about 180 MB, once) into `config/voicevanish/whisper`. Watch the server log for
   "Whisper speech model ... loaded". It needs roughly 1 GB of free RAM and a CPU from the last ~8 years.
   If the server cannot download it, get `ggml-small.en-q5_1.bin` from
   https://huggingface.co/ggerganov/whisper.cpp/tree/main and put it in `config/voicevanish/whisper`.

## Server settings that matter

- Only chunks the server has **loaded** are affected, nothing is generated. For a 30 chunk radius set
  `view-distance=32` (the maximum) in `server.properties`. Large view distances use more server RAM and CPU.
  The mod tells you in chat if the server loads fewer chunks than the radius.
- Everyone needs Simple Voice Chat working (microphone on, voice chat connected).

## Commands (operators)

| Command | What it does |
|---|---|
| `/voicevanish on` / `off` | Master switch. Turn it off while you are building things! |
| `/voicevanish status` | Shows settings and which speech engine is active |
| `/voicevanish radius <1-64>` | Radius in chunks (default 30) |
| `/voicevanish delay <seconds>` | Wait between hearing a name and vanishing it (default 5) |
| `/voicevanish sound on/off` | The vanish sound |
| `/voicevanish heard on/off` | Show what was heard on your action bar when no name was in it |

Settings are saved in `config/voicevanish.json`.

## Speech engines

- **Whisper (default):** accurate general speech recognition, runs on your own server, no account.
  Change `whisperModel` in the config: `base.en-q5_1` (smaller, faster, less accurate),
  `small.en-q5_1` (default), `small.en` (most accurate, 466 MB, needs more RAM).
- **Vosk (backup):** used only if Whisper cannot start. Needs the `vosk-model-small-en-us-0.15` model
  extracted into `config/voicevanish/model` (folders `am`, `conf`, ... directly inside `model`).
- Set `engine` to `whisper` or `vosk` in the config to force one.

Whisper listens for a pause, then transcribes the sentence in the background, so a name is heard a
moment after you finish speaking; the delay setting counts from that moment.

## Strongly recommended

- **Back up your world** before trying it. Deleting is permanent.
- Words like "water", "grass", "dirt", "sand", "fire" are blocks, so normal chat will trigger them.
  Use `/voicevanish off` when you want to talk freely.
- A clip containing more than 6 names (`maxNamesPerSentence`) is ignored as a likely mishearing.

## Building the mod

Push to GitHub: the included workflow builds the jar and publishes it under **Releases**.
Locally: install Gradle 8.12 or newer and run `gradle build`; the jar is `build/libs/voicevanish-1.0.0.jar`.

## Known limits

- Blocks are removed a few chunks per tick (`chunksPerTick`, default 8) so the server does not lag; a
  30 chunk clear can take several seconds to spread outward. The position is where the speaker stood
  when the name was heard.
- Chests and similar blocks may drop their items when removed.
- Air, items on the ground, players, XP orbs and a few technical entities are ignored on purpose.
- The sound uses built-in Minecraft sounds layered together. A fully custom audio file would need every
  player to install a resource pack.
- Whisper's native code is bundled for Windows (x64), Linux (x64/arm64) and macOS. Windows needs a CPU
  with AVX2.
