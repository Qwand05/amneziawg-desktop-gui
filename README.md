# AmneziaWG for Linux

A desktop GUI for [AmneziaWG](https://amnezia.org/learn-more/31_amneziawg) on Linux, built with
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/). It is a port of the
official AmneziaWG Android app, and it drives the `awg` and `awg-quick` command line tools.

It was developed and tested on Ubuntu with KDE Plasma 6 (Wayland). Other desktops and distributions
with the AmneziaWG tools should work too.

> The Android app still lives in this repository and builds as before. Its original README is in
> [READMEold.md](READMEold.md).

## Features

- **Tunnel list** with on/off switches and "Connected" / "Connecting…" status based on the latest
  handshake. Select several tunnels to delete them.
- **Tunnel details**: addresses, DNS, peers, the AmneziaWG obfuscation parameters, and live traffic
  and handshake statistics. Click any value to copy it.
- **Editor** for every `[Interface]` and `[Peer]` field, including all AmneziaWG obfuscation
  parameters (`Jc`, `Jmin`, `Jmax`, `S1`–`S4`, `H1`–`H4`, `I1`–`I5`, …), key generation and the
  "Exclude private IPs" helper.
- **Ways to add a tunnel**:
  - import `.conf` files or `.zip` archives;
  - scan a QR code with a webcam (the menu entry is disabled when no camera is detected);
  - read a QR code from an image file;
  - paste a config from the clipboard;
  - create one from scratch.
- **Export** a single tunnel as a `.conf` file or a QR code, or all tunnels as a `.zip`.
- **System tray icon** (native StatusNotifierItem, so it works on KDE Plasma under Wayland):
  - closing the window keeps the app running in the tray;
  - left-click the icon to show or hide the window;
  - the right-click menu switches tunnels on and off directly;
  - the icon is in color while a tunnel is connected and grey otherwise.
- **Single instance**: launching the app again brings the running window back instead of starting a
  second copy.
- **Settings**:
  - restore tunnels on start;
  - allow multiple simultaneous tunnels;
  - dark theme;
  - custom accent and status colors;
  - close to tray.
- **Log viewer**:
  - the app's own log, which records each connect and disconnect with the full `awg-quick` output,
    errors and crashes, and is also saved to `~/.local/state/amnezia-awg/app.log`;
  - AmneziaWG kernel module and `awg-quick` service entries of the system journal.
- Asks for your password only when needed. Opening the app does not prompt (unless *Restore tunnels
  on application start* is on); switching a tunnel does, once per session.

## Requirements

| What | Why |
| --- | --- |
| [amneziawg-tools](https://github.com/amnezia-vpn/amneziawg-tools) (`awg`, `awg-quick`) | Bringing tunnels up and down |
| The AmneziaWG kernel module ([amneziawg-linux-kernel-module](https://github.com/amnezia-vpn/amneziawg-linux-kernel-module)) | The tunnel itself. `awg-quick`'s userspace fallback (amneziawg-go) may also work, but is untested. |
| polkit with an authentication agent (`pkexec`) | Root access for `awg-quick`. Every desktop environment ships one. |
| JDK 21 | Building and running from source |
| `ffmpeg` (optional) | Scanning QR codes with a webcam |
| A StatusNotifierItem tray (optional) | Tray icon. KDE Plasma supports it natively; GNOME needs the *AppIndicator and KStatusNotifierItem Support* extension. |

On Ubuntu:

```bash
sudo apt install openjdk-21-jdk ffmpeg
```

Install the AmneziaWG tools and kernel module by following the instructions of the
[amneziawg-tools](https://github.com/amnezia-vpn/amneziawg-tools) and
[amneziawg-linux-kernel-module](https://github.com/amnezia-vpn/amneziawg-linux-kernel-module)
projects (an Ubuntu PPA is available).

## Building and running

```bash
git clone --recurse-submodules https://github.com/Qwand05/amneziawg-linux-gui
cd amneziawg-android
```

Gradle 8.13, used by this project, does not run on very new JDKs (it fails on Java 26). Point it at
JDK 21 even if your default `java` is newer:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

Run the app:

```bash
./gradlew :desktop:run
```

Build a `.deb` package (written to `desktop/build/compose/binaries/main/deb/`):

```bash
./gradlew :desktop:packageDeb
```

The project still includes the Android modules, so Gradle needs an Android SDK to configure the
build. Set `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable.

Install it with:

```bash
sudo apt install ./desktop/build/compose/binaries/main/deb/amnezia-awg_1.0.3_amd64.deb
```

The package bundles its own trimmed Java runtime, so it doesn't need a JDK installed. That runtime
only contains the Java modules listed in `desktop/build.gradle.kts`. If you add a dependency and
the packaged app fails with `NoClassDefFoundError` while `:desktop:run` works, run
`./gradlew :desktop:suggestRuntimeModules` and add the modules it lists.

## Using it

### Adding a tunnel

Click the **+** button under the tunnel list and choose how to add the tunnel. Any `awg-quick`
style configuration works, including plain WireGuard ones. Only import configurations from sources
you trust.

Tunnel names must be 1–15 characters from `a-z A-Z 0-9 _ = + . -`. They become the network
interface name.

### Switching tunnels

Use the switch next to a tunnel, or the tunnel's entry in the tray menu. The first time in a session,
polkit asks for your password. The privileged helper then stays running until you quit, so there are
no more prompts. Unless *Allow multiple simultaneous tunnels* is on, switching a tunnel on switches
the app's other tunnels off.

Tunnels are kernel network interfaces, so **they keep running after you quit the app**. Switch them
off first if you want them down.

### Tray

Closing the window hides it to the tray. Use **Quit** in the tray menu to exit. If you prefer closing
to quit, turn off *Keep running in the system tray* in Settings. The setting is greyed out when no
tray is available, and closing the window then always quits.

To start the app in the tray when you log in, create `~/.config/autostart/amnezia-awg.desktop`. The
`Exec` path below is where the `.deb` package installs the launcher.

```ini
[Desktop Entry]
Type=Application
Name=AmneziaWG
Exec=/opt/amnezia-awg/bin/amnezia-awg --hidden
X-GNOME-Autostart-enabled=true
```

`--hidden` starts the app without showing the window. It is ignored when no tray is available.

### Live statistics

Traffic and handshake statistics need root. They appear once you have switched a tunnel during the
session. For a tunnel that was already running when the app started, click **Show live statistics**
on its details page.

## How it works

### Privileges

The GUI runs as your user. Operations that need root go through a small helper script,
[`awg-helper.sh`](desktop/src/main/resources/awg-helper.sh), which is started once with
`pkexec /bin/bash awg-helper.sh` and then answers requests over stdin/stdout. It only accepts a
fixed set of commands. Besides a few housekeeping ones, these are:

- `awg show <name> dump`
- `awg show <name> latest-handshakes`
- `awg-quick up <file>`
- `awg-quick down <file>`
- `journalctl` for the log viewer

Tunnel names and config paths are checked against strict patterns before anything runs.

These read-only queries run without root: `awg show interfaces`, `awg --version` and
`/sys/module/amneziawg/version`.

If `/usr/lib/amnezia-awg/awg-helper.sh` exists (root owned), it is used instead of the copy bundled
in the application.

The `AMNEZIA_AWG_HELPER` environment variable selects how root is reached:

| Value | Behavior |
| --- | --- |
| `pkexec` | Default |
| `sudo` | Uses `sudo -n`; needs a matching sudoers rule |
| `direct` | The app itself runs as root |

### Split tunnels

Tunnels whose AllowedIPs do not include `0.0.0.0/0` or `::/0` (split tunnels, often lists of
thousands of networks) are brought up differently from plain `awg-quick`:

- **Speed:** `awg-quick` needs minutes to parse a long AllowedIPs list and adds routes one at a
  time. The helper runs `awg-quick` with `Table = off` on a copy without AllowedIPs, then applies
  the networks with one `awg set` call and installs all routes with one `ip -batch` call. A tunnel
  with 2,000 networks comes up in well under a second.
- **Endpoint protection:** when an AllowedIPs network covers the server's own address, `awg-quick`
  would route the encrypted traffic back into the tunnel. The tunnel then never connects and the
  covered part of the internet becomes unreachable. The helper keeps the endpoint on the route it
  had before (a `/32` or `/128` route via your normal gateway) and removes that route again on
  disconnect.
- **Rollback:** if the endpoint cannot be reached outside the tunnel, or any step fails, the tunnel
  is taken down again instead of being left half configured.

Full tunnels (`0.0.0.0/0`) use plain `awg-quick`, which already protects the endpoint through
policy routing.

### Storage

Each tunnel is stored as an `awg-quick` configuration file, readable only by you (mode `0600`), in
`~/.config/amnezia-awg/` (or `$XDG_CONFIG_HOME/amnezia-awg/`). Settings are kept in
`settings.properties` in the same directory.

### Tunnels the app did not create

The app only manages tunnels in its own configuration directory. It leaves other interfaces alone,
for example ones started with `awg-quick up` from `/etc/amnezia/amneziawg/`:

- it never brings them down, even with *Allow multiple simultaneous tunnels* off;
- it refuses to create, import or rename a tunnel to the name of an interface that is already running.

## Troubleshooting

| Problem | What to do |
| --- | --- |
| "Administrator authentication was cancelled or failed" | Make sure a polkit agent is running and that you entered your password. |
| "awg-quick was not found" | Install amneziawg-tools and make sure `awg-quick` is on your `PATH`. |
| No tray icon on GNOME | Install the *AppIndicator and KStatusNotifierItem Support* extension. When started from a terminal, the app prints `AmneziaWG: native tray unavailable, falling back: …` if it had to fall back to the legacy tray. |
| "Scan from QR code" is greyed out | No usable camera was detected (`/dev/video*`), or `ffmpeg` is missing. The menu entry says which. You can still import a screenshot of the QR code with **Import from QR code image**. |
| No internet after a tunnel failed to connect or the app was killed | Bring the tunnel down by hand: `sudo awg-quick down <name>`, or `sudo ip link delete <name>` if that fails. Rebooting also works. |
| Need more details about a failure | Open **Settings → View application log**. The *Application* tab has the app's log, including the full `awg-quick` output of every connect and disconnect. The same log is in `~/.local/state/amnezia-awg/app.log` (the previous one in `app.log.1`), which also survives crashes. The *System journal* tab shows the kernel module's messages. |

## Project layout

| Module | Contents |
| --- | --- |
| `core/` | Platform independent configuration parser, key handling and crypto, shared by Android and desktop |
| `tunnel/` | Android tunnel backends (Go userspace and kernel module) |
| `ui/` | Android app |
| `desktop/` | The Linux app |

Inside `desktop/src/main/kotlin/org/amnezia/awg/desktop/`:

| Package | Contents |
| --- | --- |
| `backend/` | `awg-quick` backend and the privileged helper client |
| `model/` | Tunnel state and app state |
| `viewmodel/` | Editor models |
| `ui/` | Compose screens |
| `tray/` | StatusNotifierItem/dbusmenu tray and single-instance handling |
| `theme/` | Colors |
| `util/` | Import/export, cameras, settings |

## Development

```bash
./gradlew :core:test :desktop:test
```

The desktop tests include an offscreen render test that saves screenshots of every screen to
`desktop/build/screens/`. It uses a throwaway configuration directory, never your real one.

## License

Apache License 2.0, see [COPYING](COPYING). Based on the
[WireGuard Android](https://git.zx2c4.com/wireguard-android/) app by WireGuard LLC and the
[AmneziaWG Android](https://github.com/amnezia-vpn/amneziawg-android) app.
