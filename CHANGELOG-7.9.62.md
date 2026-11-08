## Hyper Eternity 7.9.62

### New
- **OriginOS-style player**: big cover on the left with the app icon in the corner, progress bar next to the cover and controls centered below
- **Lyric video style lyrics** on the lock screen (HyperMusicCover): one line at a time, centered
  - **Brush**: brush lettering that inks in, slowly moves closer and drifts down as it fades
  - **Neon**: words light up as they are sung, in the album color, on rows curved like a bubble
  - **Rain on glass** (Neon): drops hit the glass, merge and run down with a lens effect; adjustable amount and sliding
  - **Neon fonts**: each of the 8 fonts can be replaced with any .ttf/.otf file
  - **Dark background with lyrics**: darkens the whole lock screen while lyrics are open, with adjustable strength
  - **Also in StandBy**: the same style on the StandBy screen of HyperImmersive (HyperMusicCover extension)
- **HyperMusicCover-Enhanced on HyperOS 3, no modified APK**: Hyper Eternity now fixes the official app at runtime
  - Frosted glass on the island and the flashlight/camera buttons
  - Island and button color follows the album art or the lock screen wallpaper
  - Lyric sync (the stored playback position is refreshed) and **Lyrics ahead** applies to every lyric style
  - The official signature is kept, so extensions like HyperImmersive (StandBy) keep working
- **Lock screen buttons and island** use the same glass as the player when "Same background as notifications" is on
- **Instagram glass style**: liquid or frosted
- Vibrance on the explicit **"E" badge** together with artist and buttons

### App
- Shorter descriptions: one sentence per option (Portuguese, English and Spanish)
- Music tab reorganized: Player, Artwork on track change, Progress bar, Lyrics and Neon fonts
- Fine-tuning options only show up when their option is on
- Extensions opens the official HyperMusicCover-Enhanced settings

### Other
- HyperImmersive is part of the module scope (only used by "Also in StandBy")
- Code cleanup: about 600 lines of unused code removed, and a hook that ran on every touch without doing anything is gone

### Install
- Install over the previous version (same package and signature; your settings are kept)
- Use the **official HyperMusicCover-Enhanced**. If you have the adapted one, uninstall it first (different signature)
- For StandBy lyrics, check **HyperImmersive** in the module scope in LSPosed and turn on **Also in StandBy**
- Restart the System UI or the device
