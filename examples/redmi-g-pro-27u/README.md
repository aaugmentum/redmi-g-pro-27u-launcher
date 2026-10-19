# Redmi G Pro 27U

Xiaomi's 27-inch 4K mini-LED monitor with a built-in TV system: model MiTV-MFFU1 (codename beekeeper),
China HyperOS 3 on Android 14, MediaTek. It has DisplayPort, USB-C (DP alt mode) and two HDMI inputs, and
ships with a Chinese-only home screen, app store and voice assistant, and no Google services.

This folder and the launcher changes in this fork were made for this monitor by
[@aaugmentum](https://github.com/aaugmentum), with Claude Code. Other China HyperOS TVs from Xiaomi
probably behave the same, but only this monitor has been tested.

## What's here

| File | What it does |
|---|---|
| `debloat.txt` | Packages that are safe to disable, in four batches, plus the ones to keep and why |
| `install-launcher.sh` | Builds the launcher and installs it without disabling the stock home (see below) |
| `restore.sh` | Re-enables everything from `debloat.txt` and hands Home back to Xiaomi's launcher |
| `shizuku-start.sh` | Starts Shizuku, so Aurora Store can install apps (no Google Play on this ROM) |

## Setup

1. On the monitor, open the developer options and switch on ADB debugging over the network. Note the
   monitor's IP address.
2. Debloat one batch at a time, checking the inputs, picture settings and the remote after each:
   `TV_HOST=<ip> tools/disable.sh examples/redmi-g-pro-27u/debloat.txt` (comment out the batches you
   haven't reached yet).
3. Install the launcher: `TV=<ip> examples/redmi-g-pro-27u/install-launcher.sh`. Don't use
   `launcher/build-and-install.sh` on this monitor: it disables the stock home, which leaves a black screen.
4. Switch ADB debugging off again when you're done (see the warning below).

To undo everything: `TV=<ip> examples/redmi-g-pro-27u/restore.sh`.

## What the launcher does differently here

- **Home button.** HyperOS refuses any third-party default home ("Don't set 3-app default home"), and with
  `com.mitv.tvhome` disabled Home lands on FallbackHome, a black screen. So the stock home stays, and the
  launcher's accessibility service (`HomeKeyService`) takes the Home key and bounces the stock home screen
  whenever the system shows it.
- **Standby.** Every standby (the power button, or auto standby with no signal) force-stops every
  third-party app, and Android then switches the accessibility service off. A notification listener
  (`HomeKeyRestorer`) gets the launcher restarted within a second, and with `WRITE_SECURE_SETTINGS`
  (granted by the install script) it turns the service back on. Without it, Home goes to Xiaomi's home
  after the first standby.
- **Inputs.** Nothing on this ROM opens Android's passthrough-input URIs, so the Inputs menu switches
  through Xiaomi's own player with its source ids: DisplayPort 29, USB-C 30, HDMI 1-2 23-24.
- **Brightness.** The remote's TV button (it sends keycode 317, Xiaomi's MACRO_4) opens a brightness bar,
  a mirror of the firmware's volume bar on the left edge. Up/Down change the backlight through Xiaomi's
  TV middleware; OK, Back or a few seconds close it. The volume keys can't be used for this, because the
  firmware takes them before any accessibility service sees them. The bar only works with the launcher's
  `targetSdk 29` (see `launcher/app/build.gradle`).
- **Input picker.** Hold the TV button for half a second, from any app or input, for a list styled like
  the brightness bar: Media Hub (the launcher), then the ports, with a dot on the ones with a signal.
  Up/Down pick, OK switches; Back, a few idle seconds or any key but the arrows close it.
- **Settings.** The Settings pill opens Xiaomi's settings app, which has the picture, local dimming and
  sound settings.
- **Dock.** Hold OK (or press Menu) on a tile to add it to the dock, move it, remove it, or open its app info.

## Quirks worth knowing

- **ADB has no authentication on this firmware** (`ro.adb.secure=0`): anyone on your network can
  control the monitor while network debugging is on. HyperOS switches it off at every reboot and every
  standby; leave it off when you're not using it.
- Shizuku also stops at every standby and reboot; run `shizuku-start.sh` again when Aurora needs it.
- `adb shell input keyevent HOME` skips accessibility key filters, so it doesn't test the Home redirect;
  press Home on the remote instead.
- The monitor does not answer DDC/CI over DisplayPort.
