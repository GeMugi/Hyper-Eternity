## Hyper Eternity 7.9.57

### New
- **Spanish (Español)**: in the settings app and in the classic screen (Settings → App language)
- **iOS-style shade (curtain): partial HyperOS 4 support**
  - Pulling down from the home screen and the status bar opens the curtain on HyperOS 4
  - The lock screen clock in the curtain now shrinks and moves up while scrolling on every clock style (before, only a few styles did)
  - With the music player, tall clocks start smaller so the player and notifications are not pushed too low; the clock grows/shrinks smoothly when the player appears or is dismissed
  - Closing the curtain no longer plays the default shade animation on top of it
  - Swiping sideways between the Control Center and the notifications slides the curtain (clock, title, buttons and background) along with the panel, instead of showing the old shade
  - Lock screen shortcut buttons in the curtain use the same icons as the lock screen

### Fixes
- HyperOS 4: stacks and the curtain did not position cards (changed system API); cards no longer pass under the music player
- Curtain: the list no longer jumps at the top/bottom ends
- Curtain: notifications no longer start stacking far above the end of the screen

### Known limitations
- HyperOS 4 support for the curtain clocks is partial: some clock styles may still need per-style tuning

### Install
- Install over the previous version (same package and signature; your settings are kept)
- Restart the System UI or the device
