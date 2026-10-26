# BitChord Desktop

BitChord has a Kotlin Multiplatform shared module and a Compose Multiplatform
desktop application for macOS, Linux, and Windows. Desktop uses the JVM target, which is
the supported Compose Multiplatform desktop model.

The desktop target uses Java 21.

## Desktop interface

The desktop interface uses a Spotify-inspired layout with BitChord's original
dark-gray surfaces, red/pink brand accent, and playback features. The header uses
the original red-gradient app icon; active navigation, primary playback controls,
filters, and sliders share the red/pink accent used for currently-playing tracks.

- A global search field and account switcher in the top bar.
- A left sidebar for navigation, library, playlists, downloads, and local music.
- A fixed bottom player with artwork, likes, transport controls, seeking, volume,
  audio output, lyrics, and queue.
- Dark content panels and hover-to-play shelf cards in BitChord's own colors.

Below 980 dp wide, the sidebar becomes a compact navigation row above the player.
The search field and player remain available, and page content scrolls above them
instead of underneath them. The appearance settings, including system backdrops
and the volume-slider visibility setting, remain available.

This layout is isolated to `desktopApp` and `desktopUi`; Android, TV, and web
interfaces are unchanged.

### Fork updates

Desktop update checks use releases from `EPasini1/BitChord`, not the upstream
repository, so updating does not switch back to the upstream interface. If the
fork has no published release, no update prompt is shown. Update-check failures
are recorded in the desktop log, and update dialogs use the desktop's dark theme.

`DesktopUpdateCheckerTest` checks fork-only requests and download links, missing
releases, version ordering, network failures, timeouts, and cancellation without
contacting GitHub:

```bat
gradlew.bat :desktopApp:test --tests "com.music.bitchord.desktop.DesktopUpdateCheckerTest"
```

### Keeping the fork based on upstream main

Keep `origin` pointing to `EPasini1/BitChord` and `upstream` pointing to
`kushagrasinghx/BitChord`. Keep the custom desktop changes committed in the
fork's published `main`; do not replace that branch with upstream or install upstream
binaries to update this interface.

The [Sync upstream main workflow](.github/workflows/sync-upstream.yml) runs
weekly, or manually from Actions. It merges upstream's `main` into
`automation/sync-upstream-main` and opens or updates a PR targeting the fork's
`main`. It never force-pushes or directly updates `main`. Conflicting merges
abort and fail the run with the affected files listed; they must be resolved
without dropping the custom UI.

The workflow directly calls [Check](.github/workflows/check.yml) with the
synchronized commit SHA, including desktop UI and updater regression tests.
This is explicit because PRs created with `GITHUB_TOKEN` do not trigger other
workflows automatically. The validation does not inherit repository secrets.
An aggregate `Upstream sync / validation` commit status is posted on the PR's
exact head SHA; the scheduled workflow's own SHA is not the PR commit.
This status can be required in the fork's branch-protection rules.
Use **Create a merge commit** for sync PRs, not squash/rebase, so Git retains
the upstream ancestry.

To activate this on GitHub, first publish the custom desktop code and these
workflow files in the fork's `main`.
Sync and release workflows refuse a baseline missing the custom shell or its regression tests.
Enable Actions in the fork and, under
Settings > Actions > General > Workflow permissions, allow GitHub Actions to
create pull requests. Add the repository secret `UPSTREAM_SYNC_TOKEN`: a
fine-grained PAT restricted to this fork with **Contents: write** and
**Workflows: write**. The latter is needed when upstream changes workflow
files; `GITHUB_TOKEN` alone cannot reliably publish those changes. The PAT is
used for the synchronization's Git operations, while the PR uses `GITHUB_TOKEN`. No token
belongs in a committed file. A workflow that exists only locally is not scheduled.
Review and merge successful sync PRs, then run the existing Release workflow
with a new version or push a version tag in this fork. Increment
`desktopVersionCode` before publishing a new Windows installer so Windows
recognizes it as an upgrade. The desktop updater continues to download only
this fork's published releases, not arbitrary upstream commits.

For a clean local checkout matching the fork's published `main`, the same synchronization logic can be inspected
or run manually. It refuses tracked or untracked pending changes and restores
the original checked-out branch after preparing the sync branch:

```bat
node .github\scripts\sync-upstream.cjs --check
node .github\scripts\sync-upstream.cjs --apply
node --test .github\scripts\sync-upstream.test.cjs
```

Add `--push` to `--apply` only to publish the synchronization branch.
Neither mode creates a release or changes the installed application.
Windows CI builds the native libraries with MSVC and the Windows SDK before
running Gradle; skipping the Gradle native-build tasks there does not omit
the native libraries from the packages.

### UI regression tests

`DesktopShellTest` renders the actual Compose components offscreen, checks
contrast and normal/compact layouts, and exercises search, transport controls,
seeking, muting, hover-to-play, and update-dialog actions and release-note
formatting without signing in or streaming music.

```bat
gradlew.bat :desktopApp:test --tests "com.music.bitchord.desktop.DesktopShellTest"
```

Rendered previews use test data with placeholder artwork and are written to
`desktopApp/build/reports/desktop-ui`.

## Running from source

On macOS (Apple Silicon or Intel), run it with a Java 21 JDK available in your environment:

```bash
./gradlew :desktopApp:run
```

On Linux, run it with the JDK and native libraries available in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Use this fork's [releases page](https://github.com/EPasini1/BitChord/releases)
when a desktop release is available, or build an installer as described below.
Upstream installers do not include this fork's customized desktop interface.

Release packages carry their own Java runtime, the FFmpeg and ONNX natives,
the Automix analyser and its models.
Nothing else has to be installed first — no JDK, no codec pack.

| Platform | Download | Notes |
|---|---|---|
| macOS | `BitChord-<version>-macos-arm64.dmg` | For Apple Silicon Macs (M1/M2/M3/M4/later). Drag to Applications. |
| macOS | `BitChord-<version>-macos-x64.dmg` | For Intel Macs. Drag to Applications. |
| macOS | `BitChord-<version>-macos-*.pkg` | Standard macOS installer package for automated or managed installations. |
| Linux | `BitChord-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `BitChord-<version>-linux-amd64.deb` | `sudo apt install ./BitChord-*.deb` on Debian, Ubuntu and derivatives. |
| Linux | `BitChord-<version>-linux-x86_64.rpm` | `sudo dnf install ./BitChord-*.rpm` on Fedora, RHEL and openSUSE. |
| Windows | `BitChord-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `BitChord-<version>-windows-x64.msi` | The same thing for anyone who deploys by MSI. |
| Windows | `BitChord-<version>-windows-x64-portable.zip` | Unzip anywhere and run `BitChord.exe`. Writes nothing outside the folder. |

**macOS Gatekeeper.** Unsigned binaries built locally or downloaded outside the Mac App Store
may trigger a macOS security prompt ("BitChord is damaged and can't be opened" or "unidentified developer").
To clear the quarantine flag, run:

```bash
xattr -cr /Applications/BitChord.app
```

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows and the Visual C++ runtime.** The Automix analyser is linked against a
static C runtime, so on almost every machine there is nothing to install. If
BitChord starts but Automix never analyses anything, install the
[Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe)
and restart it — that is the one dependency the bundled runtime cannot carry
itself, because Windows expects it to be a system component.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On macOS:

```bash
# Builds the disk image (.dmg) installer
./gradlew :desktopApp:packageDmg

# Builds the .pkg installer
./gradlew :desktopApp:packagePkg

# Standalone BitChord.app bundle
./gradlew :desktopApp:createDistributable
# Output is at: desktopApp/build/compose/binaries/main/app/BitChord.app
```

On Linux — `rpm`, `fakeroot` and `binutils` must be installed for the packages,
and `file` for the AppImage:

```bash
bash ./gradlew :desktopApp:packageDeb
bash ./gradlew :desktopApp:packageRpm

# The AppImage is wrapped around the app image jpackage produces.
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/BitChord \
  dist/BitChord-linux-x86_64.AppImage
```

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds both the `.exe` and the `.msi` with:

```bat
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

Pass `-Pbitchord.version=1.2.3` to stamp a version other than the one in
`desktopApp/build.gradle.kts`; a release build takes it from the tag.
