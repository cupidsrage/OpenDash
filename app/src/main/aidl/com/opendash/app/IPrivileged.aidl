package com.opendash.app;

import android.view.MotionEvent;
import android.view.Surface;

// Runs inside Shizuku with shell-level access.
interface IPrivileged {
    // Shizuku's reserved code for shutting a user service down.
    void destroy() = 16777114;

    // OpenDash's private screens. createDisplay returns the new display id.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void setSurface(int displayId, in Surface surface) = 2;
    void resizeDisplay(int displayId, int width, int height, int dpi) = 3;
    void releaseDisplay(int displayId) = 4;

    // Starts "package/activity" on the given display.
    boolean startApp(String component, int displayId) = 5;

    boolean injectMotion(in MotionEvent event, int displayId) = 6;
    boolean pressBack(int displayId) = 7;

    // Turns the phone's own display panel off/on while the system stays awake,
    // so apps on OpenDash's screens keep running (like scrcpy --turn-screen-off).
    boolean setPhoneScreen(boolean on) = 8;

    // The car UI registers here; if it dies, its screens are released and the
    // phone screen is turned back on.
    void attachClient(IBinder token) = 9;

    // When enabled, opens `component` on Android Auto's display as soon as it appears.
    void setAutoOpen(String component, boolean enabled) = 10;
}
