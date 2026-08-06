# Plus Key Remapper - OnePlus 15

Remap the physical Plus (alert slider / side) key on the OnePlus 15 to any action: open an app, trigger a shortcut, send a broadcast, control media, and more.

## Features
- Single press, long press, and double press gesture detection
- Per-gesture action assignment (launch app, send broadcast, custom intent, media controls)
- Screen-on and screen-off support
- Preset actions for common use cases
- Keepalive worker to survive OxygenOS background kills
- Boot receiver for auto-start after reboot

## Requirements
- OnePlus 15 running OxygenOS (Android 15+)
- Accessibility Service permission
- Input Method (IME) permission for key capture fallback

## Build
```
./gradlew assembleRelease
```

## Package
`com.pluskeymap.app`

## Developer
Selenium Studio - seleniumstudio.app@gmail.com
