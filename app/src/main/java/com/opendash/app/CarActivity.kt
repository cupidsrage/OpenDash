package com.opendash.app

import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Intent
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The car screen. Android Auto (Android 15+) shows this activity directly on
 * the car display. Opened on the phone itself, it forwards to the setup screen.
 */
class CarActivity : AppCompatActivity() {

    enum class Page { HOME, APPS, MIRROR }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var apps: AppRepository

    private lateinit var railClock: TextView
    private lateinit var btnHome: ImageButton
    private lateinit var btnApps: ImageButton
    private lateinit var btnScreen: ImageButton
    private lateinit var pageHome: View
    private lateinit var homeClock: TextView
    private lateinit var homeDate: TextView
    private lateinit var homeStatus: TextView
    private lateinit var recentRow: RecyclerView
    private lateinit var pageApps: RecyclerView
    private lateinit var pageMirror: FrameLayout
    private lateinit var mirrorView: TextureView
    private lateinit var mirrorHint: TextView
    private lateinit var drivingBlock: View
    private lateinit var bubble: TextView

    private val recentAdapter = AppAdapter(fillWidth = true) { launch(it.packageName) }
    private val gridAdapter = AppAdapter(fillWidth = true) { launch(it.packageName) }

    private lateinit var voice: VoiceController
    private lateinit var guard: DrivingGuard

    private var page = Page.HOME
    private var mirrorSurface: Surface? = null
    private var onCarDisplay = false

    /** What the "screen" page shows: the phone mirrored, or OpenDash's own Shizuku screen. */
    enum class Source { NONE, PHONE, VIRTUAL }

    private var source = Source.NONE
    private var virtualDisplayId = -1
    private var virtualSize: Pair<Int, Int>? = null
    private var pendingVirtualComponent: String? = null
    private val shizukuListener: () -> Unit = { handler.post { onShizukuChanged() } }

    private val projectionListener: () -> Unit = { handler.post { onProjectionChanged() } }
    private val packageListener: (String) -> Unit = { handler.post { updateDrivingBlock() } }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onCarDisplay = currentDisplayId() != Display.DEFAULT_DISPLAY
        if (!onCarDisplay) {
            // Tapped the icon on the phone: show setup instead.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.car_shell)
        apps = AppRepository(applicationContext)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        railClock = findViewById(R.id.railClock)
        btnHome = findViewById(R.id.btnHome)
        btnApps = findViewById(R.id.btnApps)
        btnScreen = findViewById(R.id.btnScreen)
        pageHome = findViewById(R.id.pageHome)
        homeClock = findViewById(R.id.homeClock)
        homeDate = findViewById(R.id.homeDate)
        homeStatus = findViewById(R.id.homeStatus)
        recentRow = findViewById(R.id.recentRow)
        pageApps = findViewById(R.id.pageApps)
        pageMirror = findViewById(R.id.pageMirror)
        mirrorView = findViewById(R.id.mirrorView)
        mirrorHint = findViewById(R.id.mirrorHint)
        drivingBlock = findViewById(R.id.drivingBlock)
        bubble = findViewById(R.id.bubble)

        btnHome.setOnClickListener { showPage(Page.HOME); closeRailSoon() }
        btnApps.setOnClickListener { showPage(Page.APPS); closeRailSoon() }
        btnScreen.setOnClickListener { showPage(Page.MIRROR); closeRailSoon() }
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack(); pokeRail() }
        findViewById<View>(R.id.btnMic).setOnClickListener { voice.listen(); closeRailSoon() }

        rootView = root
        rail = findViewById(R.id.rail)
        railHandle = findViewById(R.id.railHandle)
        content = findViewById(R.id.content)
        railHandle.setOnClickListener { setRail(true, animate = true) }
        rail.post { applyRailMode() }
        mirrorHint.setOnClickListener { if (source != Source.VIRTUAL) ProjectionService.requestStart(this) }
        btnScreen.setOnLongClickListener {
            // Long-press: show the phone's own screen instead of OpenDash's screen.
            switchSource(Source.PHONE)
            showPage(Page.MIRROR)
            closeRailSoon()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val homeGrid = GridLayoutManager(this, 4)
        recentRow.layoutManager = homeGrid
        recentRow.adapter = recentAdapter
        recentRow.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tile = (114 * resources.displayMetrics.density).toInt()
            val span = max(2, v.width / tile)
            if (homeGrid.spanCount != span) handler.post { homeGrid.spanCount = span }
        }

        val grid = GridLayoutManager(this, 5)
        pageApps.layoutManager = grid
        pageApps.adapter = gridAdapter
        pageApps.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tile = (114 * resources.displayMetrics.density).toInt()
            val span = max(3, (v.width - v.paddingLeft - v.paddingRight) / tile)
            if (grid.spanCount != span) handler.post { grid.spanCount = span }
        }

        mirrorView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attachSurface(st, w, h)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = attachSurface(st, w, h)
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                ProjectionService.instance?.detachMirror()
                if (virtualDisplayId >= 0) {
                    try { ShizukuBridge.service?.setSurface(null) } catch (_: Exception) {}
                }
                mirrorSurface?.release()
                mirrorSurface = null
                return true
            }
        }
        mirrorView.setOnTouchListener(TouchForwarder())
        pageMirror.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) handler.post { fitMirror() }
        }

        voice = VoiceController(applicationContext, apps, ::showBubble, ::runCommand)
        guard = DrivingGuard(applicationContext) { updateDrivingBlock() }

        Thread {
            val list = apps.all(refresh = true)
            handler.post {
                if (isDestroyed) return@post
                gridAdapter.submit(list)
                refreshRecents()
            }
        }.start()

        showPage(Page.HOME)

        // Mirroring mode: ask for screen sharing right away so the phone prompt is
        // already waiting. Direct mode only asks when an app actually needs mirroring.
        if (!Prefs.directMode(this)) ProjectionService.requestStart(this)
    }

    override fun onResume() {
        super.onResume()
        if (!onCarDisplay) return
        val pkg = pendingDirectPkg ?: return
        pendingDirectPkg = null
        if (SystemClock.uptimeMillis() - pendingDirectAt < 4000) {
            // We came straight back: Android Auto closed the app, so it only works mirrored.
            Prefs.addMirrorOnly(this, pkg)
            showBubble("Android Auto won't run that app directly, so it's opening another way")
            launchFallback(pkg)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY
        else windowManager.defaultDisplay.displayId

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // If the car display went away, Android may move us onto the phone. Close instead.
        if (onCarDisplay && currentDisplayId() == Display.DEFAULT_DISPLAY) finish()
        else if (onCarDisplay) handler.post { fitMirror() }
    }

    override fun onStart() {
        super.onStart()
        if (!onCarDisplay) return
        if (currentDisplayId() == Display.DEFAULT_DISPLAY) { finish(); return }
        if (::rail.isInitialized) rail.post { applyRailMode() }
        UpdateManager.carConnected = true
        guard.start()
        ProjectionService.addListener(projectionListener)
        ShizukuBridge.addListener(shizukuListener)
        TouchInjectorService.packageListeners.add(packageListener)
        tick()
        onProjectionChanged()
    }

    override fun onStop() {
        if (onCarDisplay) {
            handler.removeCallbacksAndMessages(null)
            guard.stop()
            ProjectionService.removeListener(projectionListener)
            ShizukuBridge.removeListener(shizukuListener)
            TouchInjectorService.packageListeners.remove(packageListener)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (onCarDisplay) {
            voice.release()
            ProjectionService.instance?.detachMirror()
            releasePhone()
            if (virtualDisplayId >= 0) {
                try { ShizukuBridge.service?.releaseDisplay() } catch (_: Exception) {}
                virtualDisplayId = -1
            }
            mirrorSurface?.release()
            mirrorSurface = null
            if (isFinishing || !isChangingConfigurations) {
                // Car disconnected: stop sharing, let the phone rotate normally,
                // and install any update that came out while driving.
                ProjectionService.stop(applicationContext)
                UpdateManager.carConnected = false
                UpdateWorker.checkSoon(applicationContext, delayMinutes = 2)
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- slide-out menu

    private lateinit var rootView: View
    private lateinit var rail: View
    private lateinit var railHandle: View
    private lateinit var content: View
    private var railOpen = true
    private val autoHide = Runnable { setRail(false, animate = true) }

    private val railHides: Boolean get() = Prefs.hideRail(this)
    private fun dp(v: Int) = v * resources.displayMetrics.density

    /** Hidden menu (content uses the full width) or docked menu (old layout). */
    private fun applyRailMode() {
        val lp = content.layoutParams as FrameLayout.LayoutParams
        if (railHides) {
            lp.marginStart = 0
            content.layoutParams = lp
            setRail(false, animate = false)
        } else {
            lp.marginStart = rail.width
            content.layoutParams = lp
            handler.removeCallbacks(autoHide)
            rail.translationX = 0f
            railHandle.visibility = View.GONE
            railOpen = true
        }
    }

    private fun setRail(open: Boolean, animate: Boolean) {
        if (!railHides) return
        railOpen = open
        val target = if (open) 0f else -(rail.width + dp(12))
        if (animate) {
            rail.animate().translationX(target).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            rail.animate().cancel()
            rail.translationX = target
        }
        railHandle.visibility = if (open) View.INVISIBLE else View.VISIBLE
        handler.removeCallbacks(autoHide)
        if (open) handler.postDelayed(autoHide, 6000)
    }

    /** Keep the menu open a little longer while you're using it. */
    private fun pokeRail() {
        if (railHides && railOpen) {
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, 6000)
        }
    }

    private fun closeRailSoon() {
        if (railHides && railOpen) {
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, 350)
        }
    }

    // Edge-swipe tracking
    private var edgeDrag = false
    private var edgeStartX = 0f
    private var railSwipeStartX = -1f
    private var swallowUntilUp = false

    /**
     * Swipe in from the left edge to pull the menu out, swipe it left (or tap
     * anywhere else) to put it away.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!onCarDisplay || !railHides || !::rail.isInitialized) return super.dispatchTouchEvent(ev)
        // Where things really are in this window (Android Auto may offset our area).
        val rootLoc = IntArray(2)
        rootView.getLocationInWindow(rootLoc)
        val left = rootLoc[0] + rootView.paddingLeft
        val railW = rail.width.toFloat()
        val railRight = left + railW

        if (swallowUntilUp) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowUntilUp = false
            }
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                railSwipeStartX = -1f
                if (!railOpen && ev.x <= left + dp(28)) {
                    edgeDrag = true
                    edgeStartX = ev.x
                    rail.animate().cancel()
                    railHandle.visibility = View.INVISIBLE
                    return true
                }
                if (railOpen && ev.x > railRight) {
                    setRail(false, animate = true)
                    swallowUntilUp = true
                    return true
                }
                if (railOpen) {
                    railSwipeStartX = ev.x
                    pokeRail()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeDrag) {
                    rail.translationX = (-railW + (ev.x - edgeStartX)).coerceIn(-railW, 0f)
                    return true
                }
                if (railOpen && railSwipeStartX >= 0 && ev.x - railSwipeStartX < -dp(30)) {
                    railSwipeStartX = -1f
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    setRail(false, animate = true)
                    swallowUntilUp = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edgeDrag) {
                    edgeDrag = false
                    val dx = ev.x - edgeStartX
                    // A tap on the edge or a swipe past a third of the way opens it.
                    setRail(dx < dp(6) || dx > railW / 3, animate = true)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- pages

    private fun showPage(p: Page) {
        page = p
        pageHome.visibility = if (p == Page.HOME) View.VISIBLE else View.GONE
        pageApps.visibility = if (p == Page.APPS) View.VISIBLE else View.GONE
        pageMirror.visibility = if (p == Page.MIRROR) View.VISIBLE else View.GONE
        btnHome.isSelected = p == Page.HOME
        btnApps.isSelected = p == Page.APPS
        btnScreen.isSelected = p == Page.MIRROR
        when (p) {
            Page.HOME -> refreshRecents()
            Page.APPS -> pageApps.scrollToPosition(0)
            Page.MIRROR -> {
                if (source == Source.NONE) source = Source.PHONE
                if (source == Source.PHONE) ProjectionService.requestStart(this)
                handler.post { fitMirror() }
            }
        }
        updateDrivingBlock()
    }

    private fun refreshRecents() {
        val pkgs = Prefs.recents(this).ifEmpty { Prefs.DEFAULT_FAVORITES }
        recentAdapter.submit(pkgs.mapNotNull { apps.byPackage(it) }.take(8))
    }

    private fun tick() {
        val now = Date()
        val time = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        railClock.text = time
        homeClock.text = time
        homeDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        handler.postDelayed(::tick, 15_000)
    }

    private fun onProjectionChanged() {
        val active = ProjectionService.isActive
        homeStatus.text = when {
            Prefs.directMode(this) && page != Page.MIRROR -> ""
            !TouchInjectorService.isEnabled(this) -> "Turn on OpenDash in Accessibility to control apps from here"
            !active -> "Tap “Start now” on your phone to share its screen"
            else -> ""
        }
        homeStatus.visibility = if (homeStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        updateMirrorHint()
        fitMirror()
        if (active && source != Source.VIRTUAL) {
            mirrorView.surfaceTexture?.let { attachMirror(it, mirrorView.width, mirrorView.height) }
        }
    }

    // ---------------------------------------------------------------- launching

    private var pendingDirectPkg: String? = null
    private var pendingDirectAt = 0L

    /**
     * Direct mode: open the app on the car display itself (no mirroring, phone can
     * stay locked). Falls back to opening it on the phone and mirroring.
     */
    private fun launch(pkg: String) {
        if (Prefs.directMode(this) && pkg !in Prefs.mirrorOnly(this)) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null && launchOnCar(intent)) {
                Prefs.addRecent(this, pkg)
                pendingDirectPkg = pkg
                pendingDirectAt = SystemClock.uptimeMillis()
                return
            }
            Prefs.addMirrorOnly(this, pkg)
        }
        launchFallback(pkg)
    }

    /** Shizuku screen if available (phone can stay off), else mirror the phone. */
    private fun launchFallback(pkg: String) {
        if (launchVirtual(pkg)) return
        switchSource(Source.PHONE)
        if (apps.launch(pkg)) showPage(Page.MIRROR) else showBubble("Couldn't open that app")
    }

    private fun launchVirtual(pkg: String): Boolean {
        val svc = ShizukuBridge.service ?: return false
        val component = packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
            ?: return false
        Prefs.addRecent(this, pkg)
        switchSource(Source.VIRTUAL)
        if (virtualDisplayId >= 0) startOnVirtual(svc, component) else pendingVirtualComponent = component
        showPage(Page.MIRROR)
        return true
    }

    private fun startOnVirtual(svc: IPrivileged, component: String) {
        val displayId = virtualDisplayId
        holdPhoneAwake(true)
        Thread {
            val ok = try { svc.startApp(component, displayId) } catch (_: Exception) { false }
            handler.post {
                if (!ok) showBubble("Couldn't open that app on OpenDash's screen")
                else setPhonePanel(off = true)
            }
        }.start()
    }

    // If the phone really goes to sleep, apps on OpenDash's screen pause. So while
    // they run, keep the phone awake and switch only its display panel off instead.
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var panelOff = false

    @Suppress("DEPRECATION")
    private fun holdPhoneAwake(hold: Boolean) {
        if (hold && wakeLock == null) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OpenDash:car"
            ).apply { setReferenceCounted(false); acquire() }
        } else if (!hold) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun setPhonePanel(off: Boolean) {
        if (off && !Prefs.phoneScreenOff(this)) return
        if (off == panelOff) return
        val svc = ShizukuBridge.service ?: return
        Thread {
            // Give the wake-up a moment to finish before switching the panel off.
            if (off) Thread.sleep(800)
            val ok = try { svc.setPhoneScreen(!off) } catch (_: Exception) { false }
            handler.post {
                panelOff = off && ok
                if (off && !ok) showBubble("Couldn't turn the phone screen off. Apps still run with it on")
            }
        }.start()
    }

    /** Back to normal: panel on, phone allowed to sleep. */
    private fun releasePhone() {
        if (panelOff) {
            val svc = ShizukuBridge.service
            panelOff = false
            if (svc != null) Thread { try { svc.setPhoneScreen(true) } catch (_: Exception) {} }.start()
        }
        holdPhoneAwake(false)
    }

    private fun switchSource(new: Source) {
        if (new == source) return
        if (source == Source.PHONE) ProjectionService.instance?.detachMirror()
        // Mirroring needs the real phone screen on.
        if (new == Source.PHONE) releasePhone()
        source = new
        updateMirrorHint()
        fitMirror()
        mirrorView.surfaceTexture?.let { attachSurface(it, mirrorView.width, mirrorView.height) }
    }

    private fun onShizukuChanged() {
        if (ShizukuBridge.service == null && virtualDisplayId >= 0) {
            // Shizuku stopped (e.g. phone restarted): the private screen is gone.
            panelOff = false
            holdPhoneAwake(false)
            virtualDisplayId = -1
            virtualSize = null
            if (source == Source.VIRTUAL) showBubble("Shizuku stopped. Open Shizuku on your phone to restart it")
        }
        updateMirrorHint()
    }

    private fun updateMirrorHint() {
        if (source == Source.VIRTUAL) {
            mirrorHint.text = if (ShizukuBridge.service == null) "Shizuku isn't running on your phone" else "Starting…"
            mirrorHint.visibility = if (virtualDisplayId >= 0) View.GONE else View.VISIBLE
        } else {
            mirrorHint.text = "Tap here, then “Start now” on your phone to show it here"
            mirrorHint.visibility = if (ProjectionService.isActive) View.GONE else View.VISIBLE
        }
    }

    private fun launchOnCar(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(currentDisplayId()).toBundle())
        true
    } catch (e: Exception) {
        false
    }

    /** Voice intents (maps, music): car display in direct mode, else phone + mirror. */
    private fun openIntent(intent: Intent, mirror: Boolean): Boolean {
        if (Prefs.directMode(this) && launchOnCar(Intent(intent))) return true
        val ok = apps.startIntent(intent)
        if (ok && mirror) showPage(Page.MIRROR)
        return ok
    }

    private fun goBack() {
        if (page == Page.MIRROR && source == Source.VIRTUAL && virtualDisplayId >= 0) {
            val svc = ShizukuBridge.service
            val id = virtualDisplayId
            if (svc != null) {
                Thread { try { svc.pressBack(id) } catch (_: Exception) {} }.start()
                return
            }
        }
        if (page == Page.MIRROR && TouchInjectorService.instance?.back() == true) return
        if (page != Page.HOME) showPage(Page.HOME)
    }

    private fun runCommand(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.OpenApp -> {
                showBubble("Opening ${cmd.app.label}")
                launch(cmd.app.packageName)
            }
            is VoiceCommand.Navigate -> {
                showBubble("Navigating to ${cmd.destination}")
                val mapsInstalled = apps.byPackage("com.google.android.apps.maps") != null
                val intent = if (mapsInstalled) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(cmd.destination)))
                        .setPackage("com.google.android.apps.maps")
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(cmd.destination)))
                }
                if (!openIntent(intent, mirror = true)) showBubble("No maps app found")
            }
            is VoiceCommand.Play -> {
                showBubble("Playing ${cmd.query}")
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, cmd.query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                if (!openIntent(intent, mirror = false)) showBubble("No music app can play that")
            }
            VoiceCommand.Home -> { hideBubbleSoon(0); showPage(Page.HOME) }
            VoiceCommand.Apps -> { hideBubbleSoon(0); showPage(Page.APPS) }
            VoiceCommand.Back -> { hideBubbleSoon(0); goBack() }
            is VoiceCommand.Unknown -> showBubble("Heard “${cmd.heard}”. Try “open Spotify” or “navigate to…”")
        }
    }

    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    private fun showBubble(text: String?) {
        handler.removeCallbacks(hideBubble)
        if (text.isNullOrBlank()) { bubble.visibility = View.GONE; return }
        bubble.text = text
        bubble.visibility = View.VISIBLE
        val sticky = text == "Listening…" || text == "Working on it…" || text.startsWith("“")
        if (!sticky) hideBubbleSoon(3500)
    }

    private fun hideBubbleSoon(ms: Long) {
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, ms)
    }

    // ---------------------------------------------------------------- mirror

    /** Size the mirror to the phone's aspect ratio, letterboxed in the content area. */
    private fun fitMirror() {
        if (source == Source.VIRTUAL) {
            // OpenDash's own screen is created at exactly this size: no letterboxing.
            val lp = mirrorView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != FrameLayout.LayoutParams.MATCH_PARENT || lp.height != FrameLayout.LayoutParams.MATCH_PARENT) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                mirrorView.layoutParams = lp
            }
            return
        }
        val aw = pageMirror.width
        val ah = pageMirror.height
        if (aw <= 0 || ah <= 0) return
        val phone = phoneScreenSize(this)
        val scale = min(aw.toFloat() / phone.x, ah.toFloat() / phone.y)
        val w = (phone.x * scale).toInt()
        val h = (phone.y * scale).toInt()
        val lp = mirrorView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            mirrorView.layoutParams = lp
        }
    }

    private fun attachSurface(st: SurfaceTexture, w: Int, h: Int) {
        if (source == Source.VIRTUAL) attachVirtual(st, w, h) else attachMirror(st, w, h)
    }

    /** Point OpenDash's Shizuku screen at the car-screen surface (creating it the first time). */
    private fun attachVirtual(st: SurfaceTexture, w: Int, h: Int) {
        val svc = ShizukuBridge.service ?: return
        if (w <= 0 || h <= 0) return
        st.setDefaultBufferSize(w, h)
        val old = mirrorSurface
        val s = Surface(st)
        mirrorSurface = s
        val dpi = resources.displayMetrics.densityDpi
        try {
            if (virtualDisplayId < 0) {
                virtualDisplayId = svc.createDisplay(s, w, h, dpi)
                virtualSize = w to h
            } else {
                if (virtualSize != (w to h)) {
                    svc.resizeDisplay(w, h, dpi)
                    virtualSize = w to h
                }
                svc.setSurface(s)
            }
        } catch (e: Exception) {
            virtualDisplayId = -1
        }
        old?.release()
        if (virtualDisplayId < 0) {
            showBubble("OpenDash's screen couldn't start. Using your phone screen instead")
            val comp = pendingVirtualComponent
            pendingVirtualComponent = null
            switchSource(Source.PHONE)
            comp?.substringBefore('/')?.let { if (apps.launch(it)) ProjectionService.requestStart(this) }
            return
        }
        updateMirrorHint()
        pendingVirtualComponent?.let { comp ->
            pendingVirtualComponent = null
            startOnVirtual(svc, comp)
        }
    }

    private fun attachMirror(st: SurfaceTexture, w: Int, h: Int) {
        val svc = ProjectionService.instance ?: return
        if (w <= 0 || h <= 0) return
        st.setDefaultBufferSize(w, h)
        val old = mirrorSurface
        val s = Surface(st)
        svc.attachMirror(s, w, h)
        mirrorSurface = s
        old?.release()
        mirrorHint.visibility = View.GONE
    }

    private val isBlocked: Boolean
        get() = guard.moving && Prefs.blockVideo(this) &&
            (TouchInjectorService.foregroundPackage ?: "") in Prefs.VIDEO_APPS

    private fun updateDrivingBlock() {
        drivingBlock.visibility = if (page == Page.MIRROR && isBlocked) View.VISIBLE else View.GONE
    }

    /**
     * Records each finger's path on the car screen and replays it on the phone
     * as one gesture when the last finger lifts: taps, long presses, swipes,
     * scrolls and two-finger pinch all come through with their real shape and timing.
     */
    private inner class TouchForwarder : View.OnTouchListener {
        private inner class Track(val path: Path, val start: Long) {
            var end: Long = start
        }

        private val tracks = LinkedHashMap<Int, Track>()
        private var gestureStart = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (source == Source.VIRTUAL) {
                // OpenDash's own screen: pass every touch through live, 1:1.
                val svc = ShizukuBridge.service
                if (svc != null && virtualDisplayId >= 0 && !isBlocked) {
                    val ev = MotionEvent.obtain(e)
                    try { svc.injectMotion(ev, virtualDisplayId) } catch (_: Exception) {}
                    ev.recycle()
                }
                return true
            }
            if (!ProjectionService.isActive || isBlocked) return true
            val phone = phoneScreenSize(this@CarActivity)
            val sx = phone.x / v.width.toFloat()
            val sy = phone.y / v.height.toFloat()
            fun px(x: Float) = (x * sx).coerceIn(1f, phone.x - 1f)
            fun py(y: Float) = (y * sy).coerceIn(1f, phone.y - 1f)

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracks.clear()
                    gestureStart = e.eventTime
                    begin(e, 0, ::px, ::py)
                }
                MotionEvent.ACTION_POINTER_DOWN -> begin(e, e.actionIndex, ::px, ::py)
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val t = tracks[e.getPointerId(i)] ?: continue
                        for (h in 0 until e.historySize) {
                            t.path.lineTo(px(e.getHistoricalX(i, h)), py(e.getHistoricalY(i, h)))
                        }
                        t.path.lineTo(px(e.getX(i)), py(e.getY(i)))
                        t.end = e.eventTime
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> tracks[e.getPointerId(e.actionIndex)]?.end = e.eventTime
                MotionEvent.ACTION_UP -> {
                    tracks[e.getPointerId(e.actionIndex)]?.end = e.eventTime
                    dispatch()
                }
                MotionEvent.ACTION_CANCEL -> tracks.clear()
            }
            return true
        }

        private fun begin(e: MotionEvent, index: Int, px: (Float) -> Float, py: (Float) -> Float) {
            if (tracks.size >= GestureDescription.getMaxStrokeCount()) return
            val path = Path().apply { moveTo(px(e.getX(index)), py(e.getY(index))) }
            tracks[e.getPointerId(index)] = Track(path, e.eventTime)
        }

        private fun dispatch() {
            val inj = TouchInjectorService.instance
            if (inj == null) {
                showBubble("Turn on OpenDash in your phone's Accessibility settings")
                tracks.clear()
                return
            }
            val maxDur = GestureDescription.getMaxGestureDuration() - 1
            val builder = GestureDescription.Builder()
            var any = false
            for (t in tracks.values) {
                val startAt = (t.start - gestureStart).coerceIn(0, maxDur - 1)
                val dur = (t.end - t.start).coerceIn(1, maxDur - startAt)
                builder.addStroke(GestureDescription.StrokeDescription(t.path, startAt, dur))
                any = true
            }
            tracks.clear()
            if (any) inj.dispatch(builder.build())
        }
    }
}
