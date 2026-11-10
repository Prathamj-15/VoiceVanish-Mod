# Voice Vanish (Fabric, Minecraft 1.21.1)

Say the name of a block or mob in Simple Voice Chat and it is deleted from every loaded chunk
within 12 chunks of the speaker (configurable). Works for every player on the server.

- Say **"stone"** -> every stone block around you becomes air.
- Say **"zombie"**, **"creeper"**, **"wither"**, **"ender dragon"** (or just **"dragon"**) -> those mobs are removed.
- Plurals work ("zombies"), multi-word names work ("oak log", "wither skeleton", "iron golem").
- Modded blocks and mobs work too, using their registry name.

## What you need

1. Java 21 and Minecraft 1.21.1 with Fabric Loader.
2. **Fabric API** for 1.21.1.
3. **Simple Voice Chat** (Fabric, 1.21.1) on the server AND on every player's client.
4. The built `voicevanish-1.0.0.jar` in the server's `mods` folder (clients do not need it).
5. A Vosk speech model (free, offline, no account):
   - Download **vosk-model-small-en-us-0.15** from https://alphacephei.com/vosk/models
   - Extract it so these folders exist: `config/voicevanish/model/am`, `config/voicevanish/model/conf`, ...
     (the contents of the model folder go directly inside `model`, not another subfolder).

## Building the mod

This project has no Gradle wrapper jar (it could not be generated here), so do one of these:

- **IntelliJ IDEA**: File -> Open -> select the project folder, let it import, then run the Gradle task `build`.
- **Command line**: install Gradle 8.10 or newer, then run `gradle build` inside the project folder.

The jar appears in `build/libs/voicevanish-1.0.0.jar` (use the one WITHOUT `-sources`).

If Gradle cannot find a dependency version, edit `gradle.properties`:
- `voicechat_api_version`: pick the newest 2.5.x at
  https://maven.maxhenkel.de/repository/public/de/maxhenkel/voicechat/voicechat-api/
- `fabric_version`, `yarn_mappings`: see https://fabricmc.net/develop/

## Server settings that matter

- Only chunks the server has **loaded** are affected, nothing is generated. In `server.properties` set
  `view-distance=12` (or higher) and `simulation-distance=12`, or blocks past your loaded range will not vanish.
- Everyone needs Simple Voice Chat working (microphone on, voice chat connected).

## Commands (operators)

| Command | What it does |
|---|---|
| `/voicevanish on` / `off` | Master switch. Turn it off while you are building things! |
| `/voicevanish status` | Shows settings and whether the speech model loaded |
| `/voicevanish radius <1-64>` | Change the radius in chunks (default 12) |
| `/voicevanish wakeword <word>` | Require a word first, e.g. wake word `delete` -> say "delete stone" |
| `/voicevanish wakeword off` | Remove the wake word |
| `/voicevanish heard on/off` | Show what the mod heard on your action bar |

Settings are saved in `config/voicevanish.json`.

## Strongly recommended

- **Back up your world** before trying it. Deleting is permanent.
- Speech recognition misheard words will trigger deletions. Set a wake word, for example
  `/voicevanish wakeword delete`, so only "delete stone" works and normal talking is safe.
- Words like "water", "grass", "dirt", "sand", "fire" are blocks, so casual chat will trigger them
  without a wake word.

## Known limits

- Blocks are removed a few chunks per tick (`chunksPerTick`, default 6) so the server does not lag; a big
  clear can take a few seconds to spread outward.
- Chests and similar blocks may drop their items when removed.
- Air, items on the ground, players, XP orbs and a few technical entities are ignored on purpose.
- Vosk's small model is decent but not perfect; unusual names ("deepslate", "tuff") may be misheard.
- Vosk's native libraries are loaded from inside the mod jar. If the log says the native library
  could not load, tell me the error and I will adjust how it is bundled.
