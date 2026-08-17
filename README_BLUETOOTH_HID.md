# Bluetooth Gamepad Controller (HID Mode)

A fullscreen Bluetooth HID gamepad app for Android. Your phone becomes a wireless game controller—no overlay tricks, no Shizuku, no app selection. Works natively with games, GFN, emulators, anything that accepts a gamepad.

## Features

- **Bluetooth HID**: Phone registers as a native gamepad to the OS. Games/GFN see it as a real controller.
- **No overlay, no permissions**: Just fullscreen controls. No `SYSTEM_ALERT_WINDOW`, no Shizuku, no input injection.
- **Full gamepad profile**: Dual analog sticks (L/R with independent deadzone), D-pad (hat switch), 8 face buttons, dual triggers (analog), start/select, mode button.
- **Gyro support**: Device gyro feeds into right-stick input (aiming in FPS).
- **Customizable layout**: Edit mode with drag-to-move, pinch-to-zoom. Save/export/load as JSON.
- **GFN optimized**: Works seamlessly with GeForce Now since it's a standard Bluetooth HID device.

## How It Works

1. **Open the app** → fullscreen control display appears.
2. **Pair via Bluetooth** → Settings > Bluetooth > pair this device as a gamepad.
3. **Launch any game** → game sees a native controller (no app selection, no setup).
4. **Adjust deadzone/gyro** → tap Settings in the top bar.

The phone advertises itself as a Bluetooth HID Host device with a standard gamepad descriptor. The OS handles HID pairing; games receive input as if a real gamepad is connected.

## Building

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Install:
```bash
adb install -r build/outputs/apk/debug/app-debug.apk
```

## Controls

| Element | Function |
|---------|----------|
| **Left Stick** | Analog X/Y axis (AXIS_X / AXIS_Y) |
| **Right Stick** | Analog X/Y axis (AXIS_Z / AXIS_RZ) + Gyro overlay |
| **D-Pad** | 4-directional hat switch |
| **Face Buttons** | A, B, X, Y (true `SOURCE_GAMEPAD` buttons) |
| **Shoulder** | LB, RB (buttons) |
| **Triggers** | L2/R2 (analog axes 0–255) |
| **Select/Start** | Mode button (KEYCODE_BUTTON_MODE) |

## Layout Customization

**Edit Mode:**
- Tap **"Edit Layout"** in settings
- Drag controls to reposition
- Pinch-to-zoom to resize
- Tap **"Done"** to save

**Deadzone:**
- Adjust stick deadzone (0–50%) in settings
- Applies to both analog sticks
- Filters out drift near center

**Gyro:**
- Toggle gyro on/off in settings
- Uses device accelerometer/gyroscope
- Right-stick input is gyro-driven when enabled

**Export/Load:**
- Export your layout as JSON to `Documents/OverlayControls/`
- Load layouts from the app settings

## Pairing

1. Open **Settings > Bluetooth**
2. Enable Bluetooth if not already on
3. Tap the app when it appears in "Available devices"
4. Confirm pairing
5. Launch your game — it will detect the gamepad automatically

No app-level permissions needed. No Shizuku. The OS handles everything.

## GFN Setup

1. Install GeForce Now on your Android device.
2. Pair the gamepad via Bluetooth settings.
3. Open GFN, stream a game.
4. Game should recognize the controller immediately (no GFN-specific config).

## Tested On

- **Device:** Infinix Hot 40i (Android 13, T606 SoC)
- **GFN:** ✅ Full support
- **Emulators:** RetroArch, Dolphin (standard gamepad input)
- **Games:** Any game supporting Android gamepads

## Advanced

### HID Report Format

The app sends standard HID gamepad reports (12 bytes):
```
[LeftX, LeftY, RightX, RightY, DPad(4bits)|Padding(4bits), Buttons(8bits), LeftTrigger, RightTrigger]
```

### Deadzone Logic

Deadzone is applied **per-stick** before sending HID reports. A stick must exceed the deadzone radius to register movement.

### Gyro Mapping

When enabled, device gyroscope rotation maps to right-stick X/Y:
- Pitch (rotate forward/back) → RightY
- Roll (rotate left/right) → RightX

## Project Structure

```
app/src/main/java/com/idk/touchoverlay/
├── MainActivity.kt           — fullscreen gamepad UI, settings, layout editor
├── BluetoothHIDService.kt    — Bluetooth HID registration & pairing
├── ControlOverlayView.kt     — rendering + touch-to-gamepad conversion (reused)
├── ControlElement.kt         — control model & persistence (reused)
└── OverlayService.kt         — legacy (kept for backward compat)
```

## Known Limitations

- Bluetooth pairing is device-specific. You may need to re-pair after rebooting.
- Some games may not recognize gyro (depends on game implementation).
- HID reports are sent at ~60Hz (limited by Android sensor polling rate).
- Works best with devices supporting Bluetooth HID Host (most modern Android devices).

## Troubleshooting

**Game doesn't see the controller:**
- Restart Bluetooth on both devices
- Re-pair the gamepad in Settings
- Try a different game to rule out game-specific issues

**Sticks are drifting:**
- Increase deadzone in settings (go to 15–20%)
- Calibrate your device's sensor (varies by manufacturer)

**Gyro feels off:**
- Disable gyro and use right stick normally
- Some games don't support gyro input

## Comparison: Overlay vs Bluetooth HID

| Feature | Overlay (Shizuku) | Bluetooth HID |
|---------|-------------------|---------------|
| App selection | ✅ Yes | ❌ No (native gamepad) |
| Permissions | ⚠️ Complex (Shizuku) | ✅ Simple (Bluetooth only) |
| GFN support | ⚠️ Works with workarounds | ✅ Native |
| Input latency | ~5ms (injection) | ~2–3ms (HID) |
| Works offline | ✅ Yes | ❌ Requires pairing |
| Setup complexity | ⚠️ Medium | ✅ Simple |

## License

MIT
