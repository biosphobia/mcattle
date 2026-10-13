# MCattle: AI companions for Minecraft 1.20.1

A Fabric mod that adds AI-powered, player-like NPCs to a multiplayer server. NPCs join as real players: they
have skins, show up in the tab list, and carry their own inventory, armor and health. They play survival on
their own, chat, greet people, and take orders by **text chat** or **push-to-talk voice**.

> **Status:** written without access to the Minecraft jars, because the build sandbox couldn't reach Mojang or
> Fabric's maven. The Minecraft-independent core is unit-tested (17 tests). The Minecraft-facing code was
> checked by hand against the Yarn 1.20.1 mappings and the Fabric API sources, but it has **never been
> compiled or run inside the game**. Expect to fix a few compile errors on the first `./gradlew build`.

## What the NPCs do

- **Act like players.** They use real player mechanics: walking, sprinting, jumping, swimming, ladders,
  doors, mining with crack animations and tool tiers, placing blocks against faces, eating, sleeping in beds,
  crafting, using furnaces and chests, and picking up drops. Pathfinding is A* with player moves (step up,
  drop, swim, climb, dig through natural blocks, pillar up, bridge across gaps), spread over several ticks.
- **Play on their own.** With no orders they follow the classic progression: wood, wooden then stone tools,
  food, a little hut, iron tools, shield, iron armor, a bed, diamonds. In between they explore, chop, mine coal
  or hang out near players. None of this uses the language model.
- **Interact like players.** Now and then they notice you, look over, do a crouch-spam hello or wave, and say
  hi. They fight back if a mob hits them, and (configurable) if you punch them.
- **Fight properly when asked.** Sprint in, circle-strafe, time hits to the attack cooldown, jump-crit, W-tap,
  raise a shield when you wind up a full swing, zig-zag toward archers, use a bow at range, and back off to eat
  when low. `attack me` is a non-lethal spar by default ("gg, i'll let you live"). `kill me` means it.
- **Build.** House, hut, cabin, tower, wall, bridge, pyramid, platform, pillar, fort, igloo and a giant
  statue, in the material you ask for. In survival they gather the materials as they go, pillar up to reach
  high blocks, and clean up their scaffolding afterwards. Several NPCs share one build job and split the work.
- **Know Minecraft.** "Give me 5 wood blocks" means logs. If the NPC has 5, it walks over and tosses them to
  you; if not, it chops a tree first. `obtain` works out the whole chain: iron pickaxe means logs, planks,
  sticks, a crafting table, a wooden pickaxe, cobblestone, a stone pickaxe, finding iron (or digging down to
  y=16 and branch-mining), a furnace, fuel, smelting, then crafting.
- **Work as a team.** With no name in the message, every NPC in range takes the order. One language-model call
  plans for all of them at once, with each NPC's current task in view, so they split work, help each other
  (`help CobbleMaya`) or compete when you ask for a race.

## Keeping token use low

| Layer | Cost | Handles |
|---|---|---|
| Built-in parser | free, instant | "stop", "follow me", "come here", "give me 5 wood", "attack me", "build a stone house", "mine 10 iron", "craft a diamond pickaxe", emotes... |
| Skill engine | free | Pathfinding, mining, crafting trees, combat, building: all the spatial work |
| Autonomous planner | free | Free play when nobody gives orders |
| Claude API | paid | Messages with several steps, questions, banter, anything nuanced |

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

Server (required): Fabric Loader 0.16+, Fabric API for 1.20.1, and `mcattle-<version>.jar` in `mods/`.
Clients can join **without** the mod and still use text commands.

Clients (optional): put the same jar in your `mods/` for push-to-talk. Bind *"Talk to NPCs (push to talk)"*
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
    "language": "en"
  },
  "npc": {
    "maxNpcs": 8, "hearingRange": 32, "voiceRange": 64,
    "allowXray": false, "assistedPlacement": true, "retaliate": true, "protectBuilds": true,
    "chatRange": -1, "socialness": 1.0,
    "skinPresets": { "steve": "steve", "alex": "alex", "herobrine": "account:MHF_Herobrine", "...": "..." }
  }
}
```

`/npc reload` re-reads the file.

### Voice (speech-to-text)

The Claude API doesn't transcribe audio, so push-to-talk audio goes to the **server**, which sends it to any
OpenAI-compatible `/v1/audio/transcriptions` endpoint. The simplest free option is
[whisper.cpp](https://github.com/ggerganov/whisper.cpp)'s server on the Minecraft server machine:

```sh
./whisper-server -m models/ggml-base.en.bin --port 8080 --inference-path /v1/audio/transcriptions
```

faster-whisper-server/speaches, LocalAI, Groq or OpenAI work as well (set `speech.apiKey` if needed). The
transcript appears in chat as `<You> ... [voice]` and is handled like a typed message. Voice messages don't
need a name: everyone within `voiceRange` takes the order.

macOS: the Minecraft launcher needs microphone permission (System Settings → Privacy & Security → Microphone).

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

## Commands

| Command | |
|---|---|
| `/npc spawn [name\|random] [skin]` | Spawns an NPC. Skins: `steve`, `alex`, `herobrine`, `villager`, `zombie`, `skeleton`, `creeper`, `enderman`, `pigman`, `golem`, `blaze`, `witherskeleton`, `random`, or `account:<username>` for any player's skin. Random names look like `CobbleMaya` or `CreeperJack` (about 22,000 combinations). |
| `/npc remove <npc\|all>` | Owner or op only |
| `/npc list` | Health, position and current activity |
| `/npc tell <npc\|all> <message>` | Talk without chat |
| `/npc stop <npc\|all>` / `/npc free <npc\|all>` | Stand still / go back to free play |
| `/npc inventory <npc>` | Opens the NPC's inventory as a chest (give or take items) |
| `/npc tphere <npc>` | Teleports the NPC to you |
| `/npc gamemode <npc> survival\|creative` | Op only. Creative NPCs build instantly, without gathering. |
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
`npc` (entity, brain, manager), `ai` (routing, situation reports, banter), `client` (push-to-talk).

## Limitations

- The Minecraft-facing code has not been compiled or run yet (see Status).
- Skins are copied from existing Minecraft accounts, because vanilla clients only render Mojang-signed
  textures. The server needs internet access the first time each skin is fetched.
- NPCs don't use portals by themselves. If they're left far behind or in another dimension while following
  you, they catch up by teleporting.
- Builds are procedural blueprints (12 structures with options), not free-form designs.
