# WynnIris

**This is NOT official Iris.** WynnIris is an independent fork of [Iris Shaders](https://github.com/IrisShaders/Iris), published with permission from the Iris developers. The Iris team is not involved in the development or maintenance of this mod.

WynnIris adds native support for all 31 Wynncraft item glint effects, rendered directly in the shader pipeline. Effects work with any Iris-compatible shader pack, no resource pack required.

If you are not playing on Wynncraft, use [official Iris](https://modrinth.com/mod/iris) instead.

## Features

- **31 glint effects** — Tint, Rainbow, Glitch, Enchant, Fire, Ice, Shadow, Aurora, Reflection, Plasma, Distort, Chrome, Shiny, and more
- **Shader pack compatible** — Effects work on top of any Iris-compatible shader pack
- **Configurable brightness** — Adjust glint effect brightness via the Sodium settings slider (50%–200%)
- **Drop-in replacement** — Uses the same config files and shader pack folder as Iris

## Requirements

- Minecraft 1.21.5+
- [Sodium](https://modrinth.com/mod/sodium)
- Fabric (NeoForge build available on GitHub releases)

## Support

**Do not report WynnIris bugs to the Iris developers.** They are not responsible for this mod.

- **Discord:** `cam_zzz`
- **Issues:** [GitHub Issues](https://github.com/clpotvin/WynnIris/issues)

## Building

```
./gradlew :fabric:build
```

Requires Java 21. Output jar is in `build/libs/`.

## Credits

WynnIris is built on top of [Iris Shaders](https://github.com/IrisShaders/Iris). All credit for the base shader mod goes to the Iris team — coderbot, IMS212, and all Iris contributors.

## License

Licensed under [GNU LGPLv3](LICENSE.md), same as Iris.

glsl-transformer is licensed under the GNU Affero General Public License version 3.
