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

- **Wounds.** Hurt creatures get bloody: the blood soaks into their skin, fur or scales in four
  levels as their health drops, on every model, modded ones included. Corpses and creatures missing a
  limb are soaked worst.
- **Deaths that fit the kill.** Explosions blow creatures apart and fling the parts high. Fire chars
  them black and they smoke. A long fall splatters them flat. Arrows and other projectiles spray out
  the far side. Some sword kills take the head off or a limb, and the rest of the body drops.
- **Gibs you can kick.** In water they float and wash a red slick across the surface; in lava they
  char and burn up.
- **Footprints.** Walk through blood and you leave red prints for a few steps, with a wet squelch.
- **Flies.** Gore that's been lying around a while draws a buzzing swarm.
- **Blood on your weapon.** Hits that draw blood leave it on your weapon and hand, building up over
  four levels in that blood's colour. It wears off slowly and washes off in water or rain. Works with
  any item, modded weapons included.
- **Bodies hit the ground** with a thud.

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
A creature can also get its own options instead of just a blood type:
```json
"creatures": {
  "somemod:big_boss": { "blood": "red", "gibs": 0.25, "limbs": false, "styles": false },
  "othermod:robot":   { "blood": "metal", "wounds": false, "chunks": 2 }
}
```
`gibs` scales how likely its deaths are to burst (0 never, 1 as configured), `chunks` scales how many chunks
fly, and `limbs`, `styles` and `wounds` switch limb loss, the death styles and wounds off for it.

The styles are `liquid`, `slime`, `dust`, `sparks`, `embers`, `ecto` and `none` (no gore at all).
Press **F3+T** in game to reload the file.

## Settings
Mods → Visceral → Config, the **Visceral...** button in the pause menu, or `config/visceral.cfg`.
Changes apply at once.
- **Presets:** Low, Medium, High and Insane set the amounts in one click (moving an amount yourself
  switches to Custom).
- **Switches** for every feature, sliders for blood amount, gib force, volume, stain and gib
  lifetime and the burst chance, a blood colour button (natural, green, black, purple, yellow, blue),
  and buttons to reload the blood list and clear all gore.
- **Mild mode:** no red at all. Creatures puff into dust and bits instead, and wounds, flies and the
  bloody weapon are off. Good for streams and younger players.
- **All settings...** opens the full list: the blood amount, stains and their lifetime and limits,
  the chance a death bursts, only your kills, chunk count, force, limb loss (the damage share
  needed, and the chance), whether players gib, bleeding and where it starts, screen blood
  strength, volume and the rest.

Press **F3** to see Visceral's line: how many stains, droplets, gibs and flies there are, what it
costs per frame, and what the last death was and why.

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
