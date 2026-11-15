## Hyper Eternity 7.9.63

### New
- **HyperOS 4 clocks extension (Hyper Eternity Relógios / Hos4Clocks)**: a separate app and LSPosed module, downloaded from the Extensions tab
  - HyperOS 4 All in One clocks on the HyperOS 3 lock screen and in the Hyper Eternity curtain; the lock screen editor offers the new styles
  - The clock shrinks and moves up as notifications get close, like on HyperOS 4
  - **System library patcher**: reads the device's own library, checks the function is exactly the expected one and builds a Magisk / KernelSU / APatch module. First patch: native variable font, which makes the clock resize smoothly. The module turns itself off if the system is updated
  - Digit effects (test): wallpaper inside the digits, two-tone, holographic, grain, RGB split, poster, trail, and blending with the blurred background
  - Wallpaper bounce (test): on the lock screen, the whole wallpaper or a painted area wobbles when you shake the device or turn the screen on
  - On HyperOS 4 the extension stays idle (everything is native there)
  - Needs the HyperOS 4 always-on display app (MIUIAod) installed; nothing from Xiaomi ships inside the extension
- **Extensions tab**: card to download, update and open the clocks extension from the GitHub releases
- **Apps on the speaker with Bluetooth**: with Bluetooth audio connected, chosen apps play on the phone speaker
- **Canvas behind the player**: with HyperCanvas, the player glass shows the Canvas video
- **Treat as a flagship**: turns on effects HyperOS cuts on entry-level devices
- **Keep apps in the background**: Xiaomi's preventive cleanup stops closing recent apps while there is spare memory

### Lock screen
- The Do Not Disturb card follows the notification list like any other card
- Notifications kept in the HyperMusicCover island no longer leave an empty or translucent card in the open list

### Other
- **Crash protection** now also covers features without a switch: after 3 System UI restarts in a row, the last change is turned off
- The clock code moved out of the main module into the extension; the always-on display app is no longer in the main module's scope

### Install
- Install over the previous version (same package and signature; your settings are kept)
- For the clocks: Extensions → HyperOS 4 clocks → Download, then enable **Hyper Eternity Relógios** in LSPosed (System UI and Always-on display) and turn on what you want in its app (everything is off by default)
- Restart the System UI or the device
