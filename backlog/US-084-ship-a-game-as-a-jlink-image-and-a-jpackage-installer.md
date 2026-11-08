# US-084 — Ship a game as a jlink image and a jpackage installer

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-075 (the notices to ship), US-082 (the sample game) · **Blocks:** none

## Story
As a JavaFX game developer,
I want to ship my game as a runtime image and as a native installer on each desktop target,
so that players can install and run it without installing Java or JavaFX.

## Problem
Paths are relative to the repository root.
- The SDK holds the module jars, `javafx.properties` and the graphics and media native libraries, but no jmods and
  no legal notices (`README.md:77-80`; `sdk/pom.xml:107-113`, `:203-212`). jlink takes native libraries and legal
  files into an image only from jmods (knowledge).
- In a runtime image, `NativeLibLoader` looks for the JavaFX native libraries first in `bin/javafx` under
  `java.home` on Windows and in `lib` on macOS and Linux
  (`modules/javafx.graphics/src/main/java/com/sun/glass/utils/NativeLibLoader.java:113-116`, `:323-341`,
  `:378-379`).
- The fork's own test runs grant native access to the JavaFX modules on the command line, for example
  `--enable-native-access=javafx.graphics` (`modules/javafx.graphics/pom.xml:344`). The game toolkit's modules get
  no native access, and a native physics engine's module asks the application for it
  (`.claude/skills/openjfx-conventions/SKILL.md:157-164`).
- jlink's `--bind-services` adds every provider of every service the linked modules use (knowledge).
- No pom and no workflow under `.github/` mentions `jlink`, `jpackage` or `jmod`.

## Constraints
- "Each desktop target" means the five that CI builds: Windows x64, macOS x64 and aarch64, Linux x64 and aarch64
  (`README.md:22-23`).
- No logic change and no new or changed public API in the existing `javafx.*` modules outside javafx.media unless
  this story names the change (`README.md:20-21`).
- Running a game from the SDK's jars keeps working as it does today.
- Agents install no software and never run `USE_ROBOT` or `tests/system` runs. Installing and starting an installer
  is done by the owner.

## Out of scope
- Signing and notarising installers, app stores and automatic updates.
- Building an image or an installer for one target on another.

## Acceptance criteria
1. **Image.** On each desktop target, a game builds into a jlink image and runs from the image's launcher with
   nothing outside the image on its module path, class path or library path.
2. **Installer.** On each desktop target, the same game builds into a jpackage installer. On Windows, macOS and
   Linux, the owner installs one and the game starts.
3. **Native libraries.** The JavaFX native libraries load from the image.
4. **Engine.** The physics engine the game chose is found at run time, and the image holds no service provider that
   the game did not ask for.
5. **Class path.** A game run from the class path, not as a module, finds its engine.
6. **Native engine.** For a game on a native physics engine, the engine's library ships in the image and the
   installer, and the native-access grant its module needs reaches the launcher.
7. **No warnings.** The game starts from the image and from the installed application with no restricted-method
   warning.
8. **Legal.** The image's legal directory holds every notice of the fork's modules in the image and of the engine,
   plus the fork's `LICENSE`, `ADDITIONAL_LICENSE_INFO` and `ASSEMBLY_EXCEPTION`.
9. **CI.** Every job of `.github/workflows/submit.yml` builds a jlink image of the samples and runs one headless
   scenario from it.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- `game-developer` packaged the sample as an application developer would; its friction list is kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner installs and starts the installers.

## Notes
- Related: US-075 (the notices), US-080 (how engines are found), US-082 (the samples), US-083 and US-085 (native
  engines).
