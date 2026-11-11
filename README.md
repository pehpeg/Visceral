# Visceral (Minecraft 1.12.2 / Forge)

Gore for every creature, modded ones included. Client-side only: servers don't need it, and it
works in single player and on any server.

## What it does
- **Blood on hits.** A spray out the far side of the blow and a little back toward you, a mist where
  the hit lands. Harder hits (measured from the creature's real health drop) spray more.
- **Stains.** Droplets of every size (mostly fine spray, some fat drops) fly with gravity and drag
  and stain whatever they land on: floors, walls, ceilings, slabs, fence tops. A drop landing straight
  on leaves a round splat with a crown of spines; one landing at an angle leaves a stretched splat
  pointing the way it flew. Splats on walls can trickle down over a few seconds, and blood on a
  ceiling drips back down onto the floor below. Fresh blood is glossy; it loses its shine and dries
  to a dark maroon-brown. Stains take the world's light and fade after `stainSeconds`.
- **Gibs.** When a creature dies it can burst into **its own body parts**: the real head, arms,
  legs, wings and tail from its model, with its texture, in the pose it died in. They tumble, bounce,
  trail blood, leave a splat where they land, pool, and sink away after `gibSeconds`. Meat chunks
  (or bone, metal, slime) fly out with them. Heads pop higher.
- **Limbs torn off.** A heavy enough hit can tear a limb off a creature that survives it. The limb
  flies off, the stump spurts with each heartbeat, and the creature keeps fighting without it. Its
  armour piece and held item go with the arm.
- **Bleeding.** Badly hurt creatures drip as they move and leave a trail.
- **Blood pools.** A creature that dies without bursting leaves a pool spreading under it.
- **Screen blood.** Splats round the edges of your screen when you're hurt, and a spray across it
  when you burst something right in front of you. It's drawn under the HUD.
- **Sounds.** Visceral ships its own gore sounds, synthesised for this mod: wet squelches, meaty
  thuds, flesh ripping, joints snapping, bodies bursting, stumps gushing, ceiling drips and blood
  hitting your face. Slime, bone, metal, ember and ghost creatures each have their own set. Every
  sound has several takes, and each play is pitched slightly differently. A resource pack can
  replace any of them (`assets/visceral/sounds.json`).

## Modded creatures
Nothing has to be set up per mod:
- **Hits and deaths** come from what the game already tells every client: the hurt timer, the
  health drop and the death timer. That works the same for every creature.
- **Body parts** are read from the creature's own model at runtime. The parts that turn when it
  looks around are its head, the parts that swing when it walks are its limbs, and the part in the
  middle of its mass is its body. Creatures drawn some other way (GeckoLib, OBJ models) burst into
  chunks instead.
- **Blood colour** comes from `config/visceral-blood.json`. Creatures that aren't listed get blood
  from what they are and then from words in their id. Skeletons of any mod are bone dust, slimes
  are slime, golems are metal and sparks, and anything called ghost/wraith/spirit is glowing ecto.
  Blazes, fire creatures and magma give embers and scorch marks. Spiders and bugs get yellow-green
  ichor, zombies and ghouls dark rotten blood, and ender creatures purple. Everything else bleeds
  red.

### visceral-blood.json
```json
{
  "types": {
    "red":    { "color": "#8A0B0B", "chunkColor": "#9C2A2A", "style": "liquid" },
    "acid":   { "color": "#B8FF2A", "chunkColor": "#4A6A10", "style": "liquid" }
  },
  "creatures": {
    "minecraft:zombie": "rotten",
    "somemod:acid_crawler": "acid",
    "othermod:*": "red",
    "*_golem": "metal",
    "mymod:training_dummy": "none"
  }
}
```
The styles are `liquid`, `slime`, `dust`, `sparks`, `embers`, `ecto` and `none` (no gore at all).
Press **F3+T** in game to reload the file.

## Settings
Mods → Visceral → Config, or `config/visceral.cfg`. Changes apply at once.
- **Blood:** amount, stains on/off, stain lifetime, the maximum number of stains and droplets, and the
  range around you.
- **Gibs:** death gibs on/off, the chance a death bursts, only your kills, chunks and how many, force,
  gib lifetime, the maximum number of gibs, limb loss (on/off, the damage share needed, and the
  chance), and whether players gib.
- **Extras:** bleeding and the health share below which it starts, blood pools, screen blood and its
  strength, and sounds and their volume.

Bosses (the dragon, the wither, and modded bosses that mark themselves as bosses) bleed but keep
their own death animations. You never gib yourself.

## With Firm Grip
They run side by side, and neither needs the other. Firm Grip's hit flash, recoil, impact sparks,
damage numbers and death wisps all still play. When a creature bursts, Visceral hides its corpse and
the gibs take its place. A creature with a lost limb still gets Firm Grip's flash, and the flash
leaves the limb hidden.

## Building the jar
**On GitHub (no setup):** every push builds the mod (`.github/workflows/build.yml`). Open the
**Actions** tab, then the latest **Build** run, and download **visceral-jar** under *Artifacts*
(a zip with the jar inside).

**Locally:** you need JDK 17 or newer to run the build (it fetches Java 8 for Minecraft itself).
```
gradlew.bat build        (Windows)
./gradlew build          (macOS/Linux)
```
The jar is in `build/libs/` (use the one not ending in `-sources` or `-dev`).
`gradlew runClient` starts the game with the mod loaded.

The build setup is the [GregTechCEu Buildscripts](https://github.com/GregTechCEu/Buildscripts)
template (RetroFuturaGradle). Mod name, id and version are in `buildscript.properties`.
