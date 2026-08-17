# Touch Overlay

A virtual controller overlay for Android that injects real touch, gamepad, and mouse input into any foreground app. The overlay runs as a system-style floating layer and uses Shizuku to synthesize input with shell privileges.

## Key Features

- Overlay touchscreen controls on top of any Android app or game
- Injects true `SOURCE_GAMEPAD` button events for native controller compatibility
- Sends real joystick axis reports with `SOURCE_JOYSTICK | SOURCE_GAMEPAD`
- D-pad supports both key events and hat axis (`AXIS_HAT_X`, `AXIS_HAT_Y`)
- Built-in gyro support for right-stick control using phone orientation
- Layout editor with drag/move/pinch resizing
- Save and export custom control layouts
- Real trackpad emulation with cursor movement and tap/click

## How It Works

The app consists of three main parts:

1. `MainActivity`
   - Provides the launcher UI and app list
   - Starts the overlay service after requesting draw-over-other-apps permission
   - Exposes buttons for layout editing, reset, deadzone settings, gyro toggle, save, and export

2. `OverlayService`
   - Runs as a foreground Android service
   - Adds a fullscreen overlay window with a `ControlOverlayView`
   - Uses `TouchInsetsCompat` to make only control regions touchable and passes other touches through
   - Manages sensor listeners for gyro input and broadcasts for dynamic configuration
   - Binds to Shizuku and holds the input injector instance

3. `ControlOverlayView`
   - Renders all virtual controls and handles touch interaction
   - Supports button, D-pad, joystick, touch joystick, and trackpad elements
   - Converts touch gestures into injected input events
   - Keeps combined gamepad axis state and sends complete axis reports on every change

### Input Injection

The app uses a Shizuku-backed engine to inject input under shell privileges:

- `ShizukuInputEngine` binds to the user service and forwards commands
- `InputInjectorService` builds real Android `MotionEvent` and `KeyEvent` objects
- Touch buttons use `SOURCE_TOUCHSCREEN`
- Gamepad buttons use `SOURCE_GAMEPAD`
- Analog axes use `SOURCE_JOYSTICK | SOURCE_GAMEPAD`
- Trackpad movement uses `SOURCE_MOUSE`

This means the overlay can behave like a real controller for native apps, including cloud gaming services and games that expect physical controller input.

## Controls and Mapping

The default layout includes:

- A/B/X/Y face buttons
- L1/R1 shoulder buttons and L2/R2 triggers
- L3/R3 thumbstick buttons
- SELECT, START, and GUIDE (`KEYCODE_BUTTON_MODE`)
- Left and right true analog sticks
- D-pad with both key and hat-axis support
- Trackpad for cursor control

The app also preserves real stick deadzone and sends both primary and alternate right-stick axes (`AXIS_Z` / `AXIS_RZ` and `AXIS_RX` / `AXIS_RY`) for compatibility.

## Installation

1. Install Shizuku and make sure it is running.
2. Grant the app `Display over other apps` permission.
3. Allow Shizuku permission when prompted.
4. Launch the app, select a target application, and start the overlay.

## Usage

- Tap `Edit Layout` to reposition controls.
- Use pinch gestures to resize controls.
- Tap `Reset Layout to Default` to restore the original profile.
- Adjust `Stick Deadzone` to reduce drift.
- Toggle `Gyro` to enable/disable sensor-driven right-stick input.
- Use `Save Layout` to persist changes locally.
- Use `Export Layout` to write a JSON layout file to internal storage.

## Development

### Requirements

- Android SDK (compileSdk 34, targetSdk 34)
- Kotlin 1.8+
- Gradle
- Shizuku API dependency

### Build

```bash
./gradlew assembleDebug
```

### Project Structure

- `app/src/main/java/com/idk/touchoverlay`
  - `MainActivity.kt` — app UI and workflow
  - `OverlayService.kt` — overlay lifecycle and input coordination
  - `ControlOverlayView.kt` — overlay rendering and touch-to-input translation
  - `ShizukuInputEngine.kt` — service binding and command forwarding
  - `InputInjectorService.kt` — actual input synthesis via hidden `InputManager`
  - `ControlElement.kt` — control model, persistence, and default layout
- `app/src/main/aidl/com/idk/touchoverlay/IInputInjector.aidl`
  - IPC definition for the injected input service

## Notes

- The overlay requires `SYSTEM_ALERT_WINDOW` permission to display above other apps.
- `Shizuku` is required because the app needs `INJECT_EVENTS` privileges to synthesize input globally.
- The app does not rely on the target game knowing the overlay UI; it injects real input events directly into the system.

## Troubleshooting

- If buttons are not recognized in native apps, confirm Shizuku is active and the app is granted overlay permission.
- If the joystick is not detected, check that the controller layout includes true `JOYSTICK_GAMEPAD` elements and that the app sends both axis and hat values.
- For cloud gaming compatibility, ensure D-pad input is emitted as both key presses and hat axis reports.

## License

This project is proprietary and not open source. Any use, modification, or redistribution of this software requires express permission from the original author.

Attribution must credit the original creator:

- Main account: `Brskkkk`
- Alternate accounts: `Brskkkalt`, `Brskkkkalt2`, `Brskkkalt`, `cortinajr-max`, and this current account
