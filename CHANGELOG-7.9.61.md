## Hyper Eternity 7.9.61

### New
- **Curtain rebuilt on the HyperOS shade**: the original notification shade now slides down and up like the iOS curtain, following your finger in both directions. No more second "screen" on top of the system one
  - Closing keeps the blur and the cards in place until the curtain is gone (HyperOS close animation and card retraction are held back)
  - Big clock, flashlight and camera on the native shade; the list starts below the clock and scrolls under it
  - Swiping between the notifications and the control center keeps the curtain look, and the buttons slide along
  - Long notification lists scroll normally; at the end of the list, dragging up closes the curtain
- **ColorOS-style music player (test)**: on the iOS26 Control Center mod music page and in the shade player detail: full-bleed cover fading into a gradient, centered title and artist, smooth progress bar and vibrance on progress and volume
- **Explicit "E" badge**: before the title of explicit songs, in the HyperOS player and in the ColorOS-style player (when the music app marks the song as explicit)
- **Instagram glass tab bar (test)**: the bottom bar becomes a floating glass pill with the feed passing behind it
- **Gboard**
  - Keys become light glass at the source (Gboard theme colors), key presses keep working
  - Background uses the same glass as the HyperOS notifications (system blur + notification color blend)
  - New **Restart Gboard** button (System → Keyboard) to apply key color changes right away
  - "Key opacity" is now **Key glass**: lower = more transparent keys, 100% = Gboard theme colors

### Other
- Instagram and the HyperOS control center plugin (miui.systemui.plugin) are part of the module scope (only used by their options)
- Fixes and code cleanup

### Install
- Install over the previous version (same package and signature; your settings are kept)
- In LSPosed, check **Gboard**, **Instagram** and the **control center plugin** in the module scope if you use those options
- Restart the System UI or the device, then use **Restart Gboard** once
