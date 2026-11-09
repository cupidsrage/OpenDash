package com.opendash.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/** Connects to Shizuku and keeps [PrivilegedService] bound while it's available. */
object ShizukuBridge {

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4242

    @Volatile
    var service: IPrivileged? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private val main = Handler(Looper.getMainLooper())
    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    private lateinit var app: Context
    private var binding = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { bind() }
        Shizuku.addBinderDeadListener {
            service = null
            binding = false
            notifyListeners()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bind()
            notifyListeners()
        }
    }

    fun state(ctx: Context): State {
        if (service != null) return State.READY
        val installed = try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!Shizuku.pingBinder()) return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        if (!hasPermission()) return State.NEEDS_PERMISSION
        bind()
        return State.CONNECTING
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !hasPermission()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    fun openShizukuApp(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, PrivilegedService::class.java.name))
            // Keeps running in the background so it can open OpenDash when the car connects.
            .daemon(true)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) IPrivileged.Stub.asInterface(binder) else null
            service?.let { svc ->
                try {
                    svc.attachClient(clientToken)
                    svc.setAutoOpen("${app.packageName}/${CarActivity::class.java.name}", Prefs.autoOpen(app))
                } catch (_: Exception) {}
            }
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            notifyListeners()
        }
    }

    /** Lives as long as this app process; the service watches it to clean up if we die. */
    private val clientToken = android.os.Binder()

    fun updateAutoOpen() {
        try {
            service?.setAutoOpen("${app.packageName}/${CarActivity::class.java.name}", Prefs.autoOpen(app))
        } catch (_: Exception) {}
    }

    private fun bind() {
        if (!::app.isInitialized || service != null || binding) return
        if (!Shizuku.pingBinder() || !hasPermission()) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (_: Exception) {
            binding = false
        }
    }
}
