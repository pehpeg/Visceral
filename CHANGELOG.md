# Changelog

## 0.2.0
- **Wounds:** hurt creatures get bloody in four levels as they lose health, on any model. Corpses
  and creatures missing a limb are soaked worst.
- **Stumps:** torn-off limbs and heads leave a raw stump of meat and bone. Gibs are bloody.
- **Death styles by cause:** explosions blow creatures apart and fling the parts high, fire chars
  them, long falls splatter them, projectiles spray out the far side, and some melee kills take the
  head or a limb off instead of a burst.
- **Footprints**, **flies** over old gore, **blood on your weapon and hand**, and a **body-fall**
  thud.
- Gibs can be kicked, float in water (with a red slick on the surface) and char in lava.
- Long parts (spider legs, tails) fall flat instead of landing upright.
- Pools and big splats spread across neighbouring blocks.
- **Settings screen** with presets (Low/Medium/High/Insane), a switch for every feature, sliders,
  a blood colour button, reload and clear buttons. Also in the pause menu.
- **Mild mode:** dust and bits, no red.
- **Per-creature options** in `visceral-blood.json`.
- F3 line with counts, frame cost and the last death.
- Blood textures rebuilt (thickness, gloss, dried edges); new sounds (organic, meaty).
- Blood on a block goes when the block is broken.
- Stains, droplets and gibs out of view aren't drawn.
- Every part guards itself: a failure is logged and that part switches off, the game keeps running.
- Fixed: a crash when droplets fell into water, gravity too strong, the vanilla death puff after a
  burst.

## 0.1.0
- First version: blood sprays and stains, body-part gibs from any model, limb loss, bleeding,
  blood pools, screen blood, sounds, `visceral-blood.json`.
