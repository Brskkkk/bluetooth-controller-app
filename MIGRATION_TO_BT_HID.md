# Migration: Overlay (Shizuku) → Bluetooth HID Gamepad

This branch converts Touch Overlay from a Shizuku-based input injection system to a native Bluetooth HID gamepad controller. Here's what changed and why.

## What's Different

### Old System (Shizuku Overlay)
```
[Game UI]
  ↑ 
[Touch Overlay Service] (floating layer)
  ↑
[Shizuku Input Engine] (shell UID injection)
  ↑
[MotionEvent/KeyEvent synthesis]
  ↑
[Android InputManager]
```

**Downsides:**
- Requires Shizuku running + permissions
- App selection workflow (pick a game, start overlay)
- Touch injection latency (~5ms)
- Permission complexity on each new app
- GFN doesn't always see injected input cleanly

### New System (Bluetooth HID)
```
[Game/GFN]
  ↑
[OS Gamepad Input Stack]
  ↑
[Bluetooth HID Device] (your phone)
```

**Upsides:**
- ✅ No Shizuku, no injection
- ✅ No app selection — just fullscreen controls
- ✅ Native gamepad status (OS reports it as a real controller)
- ✅ GFN sees it as a standard Bluetooth gamepad
- ✅ Lower latency (~2–3ms HID report cycle)
- ✅ Works with any game that supports gamepads

## Code Changes

### Removed
- `OverlayService.kt` — no longer needed (still in codebase for reference)
- `ShizukuInputEngine.kt` — no Shizuku binding
- `InputInjectorService.kt` — no input synthesis
- `IInputInjector.aidl` — no IPC needed

### Added
- `BluetoothHIDService.kt` — Bluetooth HID host registration & pairing
- `HIDInputHandler.kt` — converts touch state to HID reports

### Modified
- `MainActivity.kt`
  - Removed app list picker
  - Fullscreen control view always active
  - Settings drawer instead of configuration buttons
  - No Shizuku permission handling

- `ControlOverlayView.kt`
  - No longer sends to `ShizukuInputEngine`
  - Can reuse rendering + touch-to-state logic
  - State updates feed into `HIDInputHandler` instead

- `AndroidManifest.xml`
  - Removed `SYSTEM_ALERT_WINDOW`, `moe.shizuku.manager.permission.API_V23`
  - Added `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
  - Removed Shizuku provider declaration

- `build.gradle.kts`
  - Removed Shizuku dependencies
  - Using native Android Bluetooth (no external deps needed)

## Migration Checklist

If you're forking this project:

- [ ] Test Bluetooth pairing on your target device
- [ ] Verify HID descriptor works (may need tweaks per device)
- [ ] Test with GFN, your favorite games, emulators
- [ ] Calibrate gyro for your device
- [ ] Update layout defaults if needed

## How to Pair

1. Open the app (fullscreen controls appear)
2. Go to **Settings > Bluetooth** on your phone
3. Enable Bluetooth
4. Wait for **"Bluetooth Gamepad Controller"** to appear in available devices
5. Tap to pair (should auto-connect)
6. Confirm pairing
7. Launch a game — it should detect the gamepad immediately

## Testing Workflow

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r build/outputs/apk/debug/app-debug.apk

# Open app
adb shell am start -n com.idk.touchoverlay/.MainActivity

# Check Bluetooth logs
adb logcat | grep BluetoothHIDService
```

## Troubleshooting Pairing

**Phone doesn't show up in Bluetooth list:**
- Make sure `BluetoothHIDService.startForegroundService()` is called
- Check logcat for errors in `BluetoothHIDService`
- Verify Bluetooth permissions are granted

**Pairing fails:**
- Restart Bluetooth on both devices
- Clear Bluetooth cache: Settings > Apps > Clear app data for Bluetooth
- Try a different game/app

**Game doesn't see the controller:**
- Verify the device is paired (Settings > Bluetooth > connected devices)
- Try launching the game *after* pairing
- Some games may need a restart to detect the gamepad

## Performance Notes

- HID reports sent every ~16ms (60Hz)
- Gyro polling at device sensor rate (typically 100–200Hz)
- Stick deadzone applied client-side before HID transmission
- Bluetooth latency: 2–10ms depending on device/BT version

## Future Enhancements

- [ ] Profile switching (save multiple layouts per game)
- [ ] Custom HID descriptors (mouse, keyboard, multi-gamepad)
- [ ] Rumble support (if device Bluetooth supports HID features)
- [ ] Macro recording/replay
- [ ] Cloud layout sync

## Questions?

Check the main README.md for detailed feature docs and troubleshooting.
