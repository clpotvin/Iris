# WynnIris — Modrinth Listing Copy

---

## Short Summary (2 sentences)

An Iris fork with built-in Wynncraft glint shader effects. Renders Wynncraft's item glint effects directly in the shader pipeline — no resource pack needed.

---

## Main Description (Body)

# WynnIris

> **This is NOT official Iris.** WynnIris is an independent fork published with permission from the Iris developers. Please do **not** report WynnIris bugs to the Iris team — they are not responsible for this mod.

WynnIris is a fork of [Iris Shaders](https://modrinth.com/mod/iris) that adds native support for Wynncraft's item glint effects. Instead of relying on a resource pack to render glint effects, WynnIris detects Wynncraft's color-encoded glint signals and renders them directly in the shader pipeline, meaning they work seamlessly with any shader pack.

### Features

- **19+ glint effects** — Tint, Rainbow, Glitch, Enchant, Fire, Ice, Shadow, Aurora, Reflection, Plasma, Distort, Chrome, Shiny, and more
- **Shader pack compatible** — Effects work on top of any Iris-compatible shader pack
- **No resource pack needed** — Glint rendering is handled entirely in the mod
- **Configurable brightness** — Adjust glint effect brightness via the Sodium settings slider
- **Drop-in replacement** — Uses the same config files and shader pack folder as Iris, so your existing settings carry over automatically

### Important

**This is a fork, not official Iris.** It is published with the explicit permission of the Iris development team, but they are not involved in its development or maintenance.

- **Bugs and issues:** Please report them to me, **not** to the Iris developers
- **Discord:** `cam_zzz` — reach out for feedback, bug reports, or questions
- **Source:** [GitHub](https://github.com/clpotvin/WynnIris)

If you are not playing on Wynncraft, you should use [official Iris](https://modrinth.com/mod/iris) instead — WynnIris provides no benefit outside of Wynncraft.

### Compatibility

- Minecraft 1.21.5+
- Requires [Sodium](https://modrinth.com/mod/sodium)
- Fabric (NeoForge build available on GitHub)

---

> **Reminder:** This mod is not affiliated with or supported by the Iris Shaders team. For issues, contact `cam_zzz` on Discord.

---

## Version Copy (for first release)

### WynnIris 1.10.6

Initial public release of WynnIris.

**What's included:**
- Wynncraft glint effects rendered natively in the shader pipeline
- Configurable glint brightness slider in Sodium settings (50%–200%)
- Automatic detection of Wynncraft's color-encoded glint signals
- Continuous UV effects that work correctly across multi-part items (custom helmets, armor sets)
- Shadow sweep, aurora, chrome, plasma, and other spatial effects with proper atlas/dedicated texture handling

**Note:** This is an Iris fork — not official Iris. Published with permission from the Iris developers. Report bugs to `cam_zzz` on Discord, not to the Iris team.

---

## Suggested Modrinth Fields

- **Project Name:** WynnIris
- **Slug:** wynniris
- **Categories:** Optimization, Rendering
- **Client/Server:** Client-side only
- **License:** LGPL-3.0-only (same as Iris)
- **Game Versions:** 1.21.5
- **Loaders:** Fabric
- **Dependencies:** Sodium (required)
- **Source Code:** https://github.com/clpotvin/WynnIris
- **Discord:** cam_zzz
- **Icon:** Use the wynniris logo already in the repo (`common/src/main/resources/assets/iris/textures/gui/iris-logo.png`)
