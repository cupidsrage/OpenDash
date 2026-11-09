package com.opendash.app;

import android.view.MotionEvent;
import android.view.Surface;

// Runs inside Shizuku with shell-level access.
interface IPrivileged {
    // Shizuku's reserved code for shutting a user service down.
    void destroy() = 16777114;

    // Creates OpenDash's private screen drawing into `surface`. Returns its display id.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void setSurface(in Surface surface) = 2;
    void resizeDisplay(int width, int height, int dpi) = 3;
    void releaseDisplay() = 4;

    // Starts "package/activity" on the given display.
    boolean startApp(String component, int displayId) = 5;

    boolean injectMotion(in MotionEvent event, int displayId) = 6;
    boolean pressBack(int displayId) = 7;
}
