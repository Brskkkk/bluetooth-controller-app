package com.idk.touchoverlay;

interface IInputInjector {
    /** action: 0=DOWN, 1=MOVE, 2=UP, matching a subset of MotionEvent.ACTION_*. pointerId identifies this touch across a down/move/up sequence for multi-touch. Touch-relay mode: taps wherever this element is drawn, for games with their own on-screen controls. */
    void injectPointer(int pointerId, int action, float x, float y) = 1;

    /** Keyboard key injection (SOURCE_KEYBOARD). */
    void injectKey(int keyCode, boolean down) = 2;

    /** True gamepad button (SOURCE_GAMEPAD) - recognized natively by games/emulators with real controller support, regardless of on-screen layout. */
    void injectGamepadButton(int keyCode, boolean down) = 4;

    /** True analog stick/trigger axes in one combined event (SOURCE_JOYSTICK|SOURCE_GAMEPAD), each in [-1,1] (triggers in [0,1]) - matches how a real physical gamepad reports state. */
    void injectGamepadAxes(float leftX, float leftY, float rightX, float rightY, float triggerL, float triggerR, float hatX, float hatY) = 5;

    /** Mouse cursor (SOURCE_MOUSE): absolute x/y this frame (already accumulated from relative trackpad deltas client-side), hover-only or with the primary button held. */
    void injectMouseMove(float x, float y, boolean buttonDown) = 6;
    void injectMouseClick(float x, float y) = 7;

    int ping() = 3;
    void destroy() = 16777114;
}
