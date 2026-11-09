package com.opendash.app

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Runs in its own process with shell-level access, started by Shizuku.
 *
 * It creates OpenDash's private screens (virtual displays that draw straight
 * into the car-screen surface), starts apps on them, and injects touches. The
 * displays are "always unlocked", so apps keep running with the phone locked.
 * Same technique as scrcpy's --new-display. It also stays running in the
 * background to open OpenDash on the car as soon as Android Auto connects.
 */
class PrivilegedService() : IPrivileged.Stub() {

    private var baseContext: Context? = null

    /** Shizuku passes a Context when this constructor exists. */
    constructor(context: Context) : this() {
        baseContext = context
    }

    // OpenDash's private screens, by display id.
    private val displays = HashMap<Int, VirtualDisplay>()
    private val dm: DisplayManager by lazy { shellDisplayManager() }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int): Int {
        var flags = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
            ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL or SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
        }
        val vd = dm.createVirtualDisplay("OpenDash", width, height, dpi, surface, flags) ?: return -1
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        return id
    }

    private fun display(id: Int): VirtualDisplay? = synchronized(displays) { displays[id] }

    override fun setSurface(displayId: Int, surface: Surface?) {
        display(displayId)?.surface = surface
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        display(displayId)?.resize(width, height, dpi)
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    private fun releaseAll() {
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        all.forEach { it.release() }
        setPhoneScreen(true)
    }

    override fun startApp(component: String, displayId: Int): Boolean {
        return try {
            val proc = ProcessBuilder(
                "am", "start",
                "--display", displayId.toString(),
                "-f", "0x10000000", // FLAG_ACTIVITY_NEW_TASK
                "-n", component
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0 && !out.contains("Error", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ safety net

    override fun attachClient(token: IBinder?) {
        // If the car UI's process dies, don't leave screens running or the phone panel off.
        try { token?.linkToDeath({ releaseAll() }, 0) } catch (_: Exception) { releaseAll() }
    }

    // ------------------------------------------------------------ auto-open in the car

    @Volatile
    private var autoOpenComponent: String? = null
    private var watching = false
    private val watcher: Handler by lazy {
        Handler(HandlerThread("OpenDashWatcher").apply { start() }.looper)
    }

    override fun setAutoOpen(component: String?, enabled: Boolean) {
        autoOpenComponent = if (enabled) component else null
        if (enabled && !watching) {
            watching = true
            dm.registerDisplayListener(displayWatcher, watcher)
        }
    }

    private val displayWatcher = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (autoOpenComponent == null) return
            // Give Android Auto a moment to finish setting its screen up.
            watcher.postDelayed({
                val comp = autoOpenComponent
                if (comp != null && isAndroidAutoDisplay(displayId)) startApp(comp, displayId)
            }, 2500)
        }

        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun isAndroidAutoDisplay(displayId: Int): Boolean {
        if (display(displayId) != null) return false
        val d = dm.getDisplay(displayId) ?: return false
        val owner = try {
            Display::class.java.getMethod("getOwnerPackageName").invoke(d) as String?
        } catch (_: Exception) {
            null
        }
        if (owner != null) return owner == ANDROID_AUTO_PACKAGE
        val name = d.name.lowercase()
        return "android auto" in name || "projection" in name || "gearhead" in name
    }

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean {
        if (event.source == InputDevice.SOURCE_UNKNOWN) event.source = InputDevice.SOURCE_TOUCHSCREEN
        return inject(event, displayId)
    }

    override fun pressBack(displayId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return inject(down, displayId) && inject(up, displayId)
    }

    override fun setPhoneScreen(on: Boolean): Boolean = try {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val sc = Class.forName("android.view.SurfaceControl")
        val setMode = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        val tokens = physicalDisplayTokens(sc)
        tokens.forEach { setMode.invoke(null, it, mode) }
        tokens.isNotEmpty()
    } catch (e: Throwable) {
        false
    }

    /** Tokens for the phone's built-in screen(s). Android 14 moved these into services.jar. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun physicalDisplayTokens(sc: Class<*>): List<IBinder> {
        val source: Class<*> = if (Build.VERSION.SDK_INT >= 34) displayControl else sc
        return try {
            val ids = source.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val tokenOf = source.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            ids.toList().mapNotNull { tokenOf.invoke(null, it) as IBinder? }
        } catch (e: NoSuchMethodException) {
            listOfNotNull(sc.getMethod("getInternalDisplayToken").invoke(null) as IBinder?)
        }
    }

    private val displayControl: Class<*> by lazy { loadDisplayControl() }

    // Only ever runs inside Shizuku's shell-level process, where these calls are allowed.
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    private fun loadDisplayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null
        ) as ClassLoader
        val cls = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary.isAccessible = true
        loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers")
        return cls
    }

    // ------------------------------------------------------------ hidden-API plumbing

    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        // Android 14+ moved injection to InputManagerGlobal.
        val cls = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (e: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        val instance = cls.getMethod("getInstance").invoke(null)!!
        val method = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        instance to method
    }

    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        setDisplayId.invoke(event, displayId)
        val (instance, method) = injector
        method.invoke(instance, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) as Boolean
    } catch (e: Exception) {
        false
    }

    /** A DisplayManager that talks to the system as the shell user. */
    @SuppressLint("PrivateApi")
    private fun shellDisplayManager(): DisplayManager {
        val base = baseContext ?: systemContext()
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(ShellContext(base))
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** Presents itself as com.android.shell so the system accepts our requests. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            } else {
                super.getAttributionSource()
            }
    }

    companion object {
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden)
        private const val PUBLIC = 1 shl 0
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
