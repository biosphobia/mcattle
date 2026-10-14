# MCattle: AI companions for Minecraft 1.20.1

A Fabric mod that adds AI-powered, player-like NPCs to a multiplayer server. NPCs join as real players: they
have skins, show up in the tab list, and carry their own inventory, armor and health. They play survival on
their own, chat, greet people, and take orders by **text chat** or **push-to-talk voice**.

> **Status:** the mod builds (GitHub Actions compiles it against Minecraft 1.20.1 with Fabric Loom and runs
> 24 unit tests; the jar is attached to each run as the `mcattle` artifact). It has **not been played in-game
> yet**, so expect gameplay bugs in pathfinding, building and combat edge cases. Please report what you see.

## What the NPCs do

- **Act like players, within player limits.** They use real player mechanics: walking, sprinting, jumping,
  swimming, ladders, doors, mining with crack animations and tool tiers, eating, sleeping in beds, crafting,
  using furnaces and chests, and picking up drops. They have survival reach (4.5 blocks for blocks, 3 for
  hits) and need line of sight: a hidden ore is dug toward, not mined through walls. Blocks are placed by
  clicking a visible face of a neighbouring block, never in mid-air, and they turn the way a player would to
  get stairs, slabs and logs facing right. Mining takes as long as it would for you with the same tool, hits
  respect the attack cooldown, and they never teleport. Pathfinding is A* with player moves (step up,
  drop, swim, climb, dig through natural blocks, pillar up, bridge across gaps), spread over several ticks.
- **Play on their own.** With no orders they follow the classic progression: wood, wooden then stone tools,
  food, a little hut, iron tools, shield, iron armor, a bed, diamonds. In between they explore, chop, mine coal
  or hang out near players. None of this uses the language model.
- **Interact like players.** Now and then they notice you, look over, do a crouch-spam hello or wave, and say
  hi. They fight back if a mob hits them, and (configurable) if you punch them.
- **Fight properly when asked.** Sprint in, circle-strafe, time hits to the attack cooldown, jump-crit, W-tap,
  raise a shield when you wind up a full swing, zig-zag toward archers, use a bow at range, and back off to eat
  when low. `attack me` is a non-lethal spar by default ("gg, i'll let you live"). `kill me` means it.
- **Build anything.** "Build a mushroom cottage with a porch", "a giant pixel-art creeper", "a 3x3 tic tac toe
  board": Claude designs the build for that exact site, using shapes (walls, roofs, domes, cylinders), exact
  block states (stairs facing, slabs, logs) and pixel-art layers. The NPCs then build it block by block. In
  survival they gather or craft the materials as they go, pillar up to reach high blocks and clean up their
  scaffolding afterwards. Several NPCs share one build and split the work. Without a language model they fall
  back to built-in blueprints.
- **Open-ended activities.** "Let's party", "this block is X, the stone is O, let's play tic tac toe", "hide and
  seek", "put on a fashion show", "race me to the top of that mountain". Anything playful, interactive or
  multi-round becomes an *activity*: a Claude "director" runs it round by round. It keeps notes (rules, board,
  score), watches an area for block changes (your moves), listens to chat, and gives each NPC its next steps:
  place a block, play a note block, set off fireworks, dance, hand out cake, commentate. It then waits for you
  before the next round. Activities end when they're done, when you say stop, or after a time or round limit.
- **Know Minecraft.** "Give me 5 wood blocks" means logs. If the NPC has 5, it walks over and tosses them to
  you; if not, it chops a tree first. `obtain` works out the whole chain: iron pickaxe means logs, planks,
  sticks, a crafting table, a wooden pickaxe, cobblestone, a stone pickaxe, finding iron (or digging down to
  y=16 and branch-mining), a furnace, fuel, smelting, then crafting.
- **Get on with it.** No narrating plans in chat: they answer briefly (or not at all) and start working.
  While a build design is being drawn up, they go chop wood for it.
- **Keep at it.** NPCs remember what you asked for until it's done. If a step fails (no trees nearby, a path
  is blocked) or they die, they quietly ask for another way and carry on, up to 3 times, and only then
  tell you what went wrong.
- **Talk out loud.** On clients with the mod, NPC chat is read aloud with text-to-speech. Each NPC keeps its
  own voice, and lines get quieter with distance and pan to the side the NPC is on.
- **Work as a team.** With no name in the message, every NPC in range takes the order. One language-model call
  plans for all of them at once, with each NPC's current task in view, so they split work, help each other
  (`help CobbleMaya`) or compete when you ask for a race.

## Feelings and relationships

Every NPC has an inner life that persists across restarts:

- **Emotions.** A mood built from the eight primary emotions (joy, sadness, anger, fear, trust, disgust,
  surprise, anticipation). Their blends cover the rest: love, jealousy, pride, guilt, embarrassment,
  loneliness, awe and so on. Moods flare up and settle over minutes. Each NPC has its own temperament
  (short-tempered or hard to anger, sensitive, brave or easily scared, forgiving or grudge-holding, warm,
  upbeat or moody), so the same thing affects two NPCs differently.
- **Relationships** with every player and every other NPC: like, trust, respect, fear and familiarity, a label
  (stranger, pal, best friend, rival, enemy, nemesis...), a one-line bond the AI keeps up to date ("frenemies
  since the diamond incident"), and memories of what they did: "gave me 3 diamonds", "hit me (x4)", "saved
  me from a zombie", "killed someone i care about (CobbleMaya)". Strong bonds are hard to move. Grudges
  and fear fade over real-time days. Trust comes back slower than liking.
- **What changes them.** Hits and kills, being beaten in a fight, gifts (throw an item to an NPC: diamonds
  count more than dirt), rescues (kill the mob that's attacking it), working, playing and spending time
  together, being alone for too long, and the tone of what you say. Claude judges insults, praise, apologies,
  teasing, threats, flirting and betrayal in context, and the built-in parser guesses when Claude isn't used.
  NPCs also take it personally when someone hurts or kills an NPC they care about.
- **What they change.** Speech: the AI sees the mood and the relationship with whoever is involved, so friends
  get warmth and inside jokes, and someone who wronged an NPC gets coldness, sarcasm or demands for an apology.
  Canned lines change too ("{player}?? i thought we were friends", "oh. it's you"). Behavior: warmer and more
  frequent greetings for friends and cold stares for enemies. They seek out friends to hang out with and
  bring them gifts. They keep away from people they fear. A friend gets a pass for the first hit, but someone
  hated can get jumped when the NPC is angry enough. A sad NPC sulks and an ecstatic one dances. They may
  refuse orders from someone they resent ("maybe if you apologized first"). Emotion particles show it too:
  angry puffs, tears, sparkles, hearts near favourite people.
- `/npc feelings <npc>` shows the mood, temperament and relationships.

## Memory

Each NPC keeps a long-term memory that survives restarts:

- **Instructions and facts.** "OakJames, from now on talk like a medieval knight", "always bring me food when
  I'm low", "never break the trees near my house", "my base is here", "call me boss". Claude stores a short
  note ("Speaks like a medieval knight (Steve asked)") and every later request about that NPC includes it: chat,
  plans, activities and banter all follow it. "Forget that" / "talk normally again" removes it.
- **Behavior settings** that the model-free parts of the NPC obey too: "stop greeting me" (greetings off), "don't
  fight back", "always follow me" / "always protect me" (what it does when it has no orders), "this is your home
  now", "your name is Bob" (you can then address it as Bob), more or less small talk.
- **A voice that stays in character.** When a style is remembered, one small call rewrites the NPC's instant
  replies ("on it", "gg", "huh?", greetings) in that style, so even free, instant responses sound right.

Phrases like "from now on", "always", "never", "remember", "call me" always go to Claude so they get recorded.
`/npc memory <npc>` lists what it remembers; `/npc remember <npc> <text>` and `/npc forget <npc> <n|text|all>`
edit it by hand.

## Keeping token use low

| Layer | Cost | Handles |
|---|---|---|
| Built-in parser | free, instant | "stop", "follow me", "come here", "give me 5 wood", "attack me", "build a stone house", "mine 10 iron", "craft a diamond pickaxe", emotes... |
| Skill engine | free | Pathfinding, mining, crafting trees, combat, building: all the spatial work |
| Autonomous planner | free | Free play when nobody gives orders |
| Claude API | paid | Messages with several steps, questions, banter, anything nuanced; one design call per build; one call per activity round |

- A Claude call sends only a compact situation report: who spoke, time of day, and for each addressed NPC its
  inventory, current task, what's nearby (trees, exposed ores, water, crafting tables, mobs), its persona and
  recent memory. That's a few hundred tokens. The large instructions and skill list are a **cached** system
  prompt, and the answer is schema-constrained JSON at low reasoning effort.
- Hard limits apply: `maxRequestsPerMinute` and `maxTokensPerDay`. When a limit is reached, NPCs fall back to
  the free parser. `/npc usage` shows the spend.
- Idle banter between NPCs is rare (one short call every ~7 minutes server-wide, only when NPCs are near each
  other or a player) and can be turned off.
- The default model is `claude-opus-5-5`. Set `anthropicModel` to `claude-haiku-5-5` for a much cheaper (~40x)
  but less clever planner.

You can also run fully local: set `"provider": "openai"` and point `openaiBaseUrl` at Ollama, LM Studio,
llama.cpp, vLLM or any other OpenAI-compatible server. That costs no tokens, but small models plan worse.

## Install

Server (required): Fabric Loader 0.16+ for 1.20.1 and `mcattle-<version>.jar` in `mods/`. That's it: the jar is
standalone and bundles the parts of Fabric API it needs (having the full Fabric API installed as well is fine).
Clients can join **without** the mod and still use text commands.

Clients (optional): put the same jar in your `mods/` for push-to-talk and to hear NPCs speak. Bind *"Talk to NPCs (push to talk)"*
(default **B**) under Controls. While you hold it, a mic badge with a level meter appears and nearby NPCs stop
and turn to listen.

Build from source with `./gradlew build` (JDK 17+). The jar ends up in `build/libs/`.

## Configuration (`config/mcattle.json`)

Created on first start. The most important fields:

```jsonc
{
  "llm": {
    "provider": "anthropic",            // "anthropic", "openai" (any OpenAI-compatible/local server) or "none"
    "anthropicApiKey": "",              // or set the ANTHROPIC_API_KEY environment variable
    "anthropicModel": "claude-opus-5-5",
    "effort": "low",
    "designEffort": "low",              // keep design calls quick so NPCs aren't left waiting
    "maxDesignBlocks": 2500,
    "activityMaxRounds": 40, "activityMaxMinutes": 30,
    "maxRequestsPerMinute": 12,
    "maxTokensPerDay": 400000,
    "localParserFirst": true,           // simple orders never reach the API
    "banter": true,
    "banterIntervalSeconds": 420
  },
  "speech": {
    "enabled": true,
    "endpoint": "http://127.0.0.1:8080/v1/audio/transcriptions",
    "model": "whisper-1",
    "language": "en",
    "textToSpeech": true,               // send NPC lines to clients with the mod to be read aloud
    "textToSpeechRange": 48
  },
  "npc": {
    "maxNpcs": 8, "hearingRange": 32, "voiceRange": 64,
    "allowXray": false, "retaliate": true, "protectBuilds": true,
    "chatRange": -1, "socialness": 1.0,
    "skinPresets": { "steve": "steve", "alex": "alex", "herobrine": "account:MHF_Herobrine", "...": "..." }
  }
}
```

`/npc reload` re-reads the file.

### Voice (speech-to-text)

Push-to-talk works out of the box, in singleplayer too. By default speech is recognized **on the player's own
computer** with [Vosk](https://alphacephei.com/vosk/), offline and free. The first time you hold the key, the
client downloads the Vosk engine (~26 MB, from Maven Central, checksum-checked) and a small English model
(~40 MB) into `config/mcattle/speech/`. The HUD shows the progress. After that it needs no internet. Only the
recognized text is sent to the server.

Pick another way in `config/mcattle-client.json` with `"speechToText"`:

| value | where speech is recognized | what you need |
|---|---|---|
| `"local"` (default) | your computer, offline (Vosk) | nothing |
| `"http"` | an OpenAI-compatible transcription API, called from your computer | `sttEndpoint` + `sttApiKey`, e.g. OpenAI (`https://api.openai.com/v1/audio/transcriptions`, model `whisper-1`) or Groq (`https://api.groq.com/openai/v1/audio/transcriptions`, model `whisper-large-v3`) |
| `"server"` | the Minecraft server sends the audio to `speech.endpoint` in `config/mcattle.json` | a whisper server the server can reach, e.g. [whisper.cpp](https://github.com/ggerganov/whisper.cpp): `./whisper-server -m models/ggml-base.en.bin --port 8080 --inference-path /v1/audio/transcriptions` |

Other languages: set `localModelUrl` to another model from the [Vosk model list](https://alphacephei.com/vosk/models).
`http` is the most accurate. `local` is good for short commands and may mishear unusual NPC names, but
voice messages don't need a name anyway: every NPC within `voiceRange` hears them. The transcript appears
in chat as `<You> ... [voice]` and is handled like a typed message.

macOS: the Minecraft launcher needs microphone permission (System Settings → Privacy & Security → Microphone).

### Text-to-speech (client)

Each player with the mod picks how NPCs sound in `config/mcattle-client.json` (created on first launch):

```jsonc
{
  // ... speechToText settings (see above), then:
  "enabled": true,
  "engine": "system",       // "system" (free, offline), "http" (OpenAI-compatible /v1/audio/speech) or "off"
  "volume": 1.0,            // also follows Minecraft's Master and Players sliders
  "maxDistance": 48,
  "httpEndpoint": "https://api.openai.com/v1/audio/speech",
  "httpApiKey": "",
  "httpModel": "tts-1",
  "httpVoices": ["alloy", "echo", "fable", "onyx", "nova", "shimmer"]
}
```

`system` uses the voices built into the OS: Windows speech (via PowerShell), `say` on macOS, `espeak-ng` or
`espeak` on Linux (install one of those first). For more natural voices, point `http` at OpenAI or at a
local server such as [Kokoro-FastAPI](https://github.com/remsky/Kokoro-FastAPI)
(`http://127.0.0.1:8880/v1/audio/speech`, model `kokoro`, voices like `af_bella`, `am_adam`).

## Talking to NPCs

Say an NPC's name (the full name or just the "real name" half: `maya` for `CobbleMaya`) in chat, or `everyone`
/ `guys` for all of them. Plain chat with no name only counts if it sounds like an order and an NPC is within
`hearingRange`, so normal conversation between players doesn't trigger anything.

Examples:
- `OakJames give me 5 wood blocks`
- `maya follow me` · `stop` · `come here` · `ok you're free` (back to free play)
- `everyone build me a big cobblestone house`
- `CreeperJack attack me` · `guys guard me` · `kill 5 zombies`
- `get me a diamond pickaxe` · `smelt this iron` · `put everything in the chest`
- `Jack and Maya, race to get 10 diamonds, winner gets a cookie` (goes to Claude)
- `what are you doing?` · `tell me a joke` (goes to Claude)
- `guys let's party` · (looking at a block) `this block is x, stone is o, let's play tic tac toe` (activities)

## Commands

| Command | |
|---|---|
| `/npc spawn [name\|random] [skin]` | Spawns an NPC. Skins: `steve`, `alex`, `herobrine`, `villager`, `zombie`, `skeleton`, `creeper`, `enderman`, `pigman`, `golem`, `blaze`, `witherskeleton`, `random`, or `account:<username>` for any player's skin. Random names look like `CobbleMaya` or `CreeperJack` (about 22,000 combinations). |
| `/npc smite [npc\|all]` | Strikes NPCs with (harmless) lightning and deletes them. No argument = all of yours. Owner or op only. |
| `/npc remove <npc\|all>` | Same as smite |
| `/npc list` | Health, position and current activity |
| `/npc tell <npc\|all> <message>` | Talk without chat |
| `/npc stop <npc\|all>` / `/npc free <npc\|all>` | Stand still / go back to free play |
| `/npc inventory <npc>` | Opens the NPC's inventory as a chest (give or take items) |
| `/npc tphere <npc>` | Teleports the NPC to you |
| `/npc gamemode <npc> survival\|creative` | Op only. Creative NPCs build instantly, without gathering. |
| `/npc feelings <npc>` | Mood, temperament and relationships (with memories of what people did) |
| `/npc memory <npc>` | What it remembers (instructions, facts, settings) |
| `/npc remember <npc> <text>` · `/npc forget <npc> <n\|text\|all>` | Edit its memory by hand |
| `/npc usage` | Language-model token usage |
| `/npc reload` | Op only. Reloads the config. |

NPCs persist: they log out with the server and rejoin on the next start with the same inventory, position and
skin. They don't count toward the "players sleeping" requirement, so they never block skipping the night.

## How it fits together

```
chat / voice ─► CommandRouter ─► LocalIntentParser ──(simple)──────────────┐
                     │                                                      ▼
                     └─(complex)─► Situation report ─► Claude (cached prompt, JSON) ─► Orders
                                                                                      │
NpcBrain (per NPC): reflexes (eat, flee lava/creepers, fight back, swim up)            ▼
                    order queue ─► Tasks (obtain, craft, smelt, chop, mine, build, attack, give, ...)
                    autonomous planner when idle            │
                    social gestures                         ▼
                    Navigator (A* + player moves) · InteractionControl (break/place/use/attack) · InventoryControl
                                                            │
NpcPlayer ── a real ServerPlayerEntity with a fake connection (Carpet-style)
```

Key packages: `dev.mcattle.core` (Minecraft-independent: parser, LLM clients, blueprints, names, speech),
`control` (movement, pathfinding, block and inventory handling, knowledge base), `task` (skills),
`npc` (entity, brain, manager), `ai` (routing, situation reports, banter), `client` (push-to-talk, text-to-speech).

## Limitations

- The Minecraft-facing code has not been compiled or run yet (see Status).
- Skins are copied from existing Minecraft accounts, because vanilla clients only render Mojang-signed
  textures. The server needs internet access the first time each skin is fetched.
- NPCs don't use portals by themselves, so they can't follow you into another dimension.
- Free-form designs depend on the model's spatial skill. Ambitious requests can come out quirky, which fits
  the spirit of the mod. If no spot gives a block the exact orientation the design wants, it is placed the closest way a player could.
