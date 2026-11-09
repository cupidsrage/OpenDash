package com.opendash.app

import android.app.Presentation
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Everything you see on the car screen. Rendered onto the Android Auto
 * surface through a private virtual display.
 */
class CarShellPresentation(outer: Context, display: Display) :
    Presentation(outer, display, R.style.Theme_OpenDash_Car) {

    enum class Page { HOME, APPS, MIRROR }

    private val handler = Handler(Looper.getMainLooper())
    private val appCtx = outer.applicationContext
    private val apps = AppRepository(appCtx)

    private lateinit var root: View
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

    private val recentAdapter = AppAdapter(fillWidth = false) { launch(it.packageName) }
    private val gridAdapter = AppAdapter(fillWidth = true) { launch(it.packageName) }

    private lateinit var voice: VoiceController
    private lateinit var guard: DrivingGuard

    private var page = Page.HOME
    private var mirrorSurface: Surface? = null
    private var pendingSafeArea: Rect? = null
    private var created = false

    // Scroll gestures arrive as many small deltas; we batch them into one swipe.
    private var accX = 0f
    private var accY = 0f
    private val flushScroll = Runnable { flushSwipe(-accX, -accY, 220) }

    private val projectionListener: () -> Unit = { handler.post { onProjectionChanged() } }
    private val packageListener: (String) -> Unit = { handler.post { updateDrivingBlock() } }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.car_shell)

        root = findViewById(R.id.root)
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

        btnHome.setOnClickListener { showPage(Page.HOME) }
        btnApps.setOnClickListener { showPage(Page.APPS) }
        btnScreen.setOnClickListener { showPage(Page.MIRROR) }
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnMic).setOnClickListener { voice.listen() }

        recentRow.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        recentRow.adapter = recentAdapter

        val grid = GridLayoutManager(context, 5)
        pageApps.layoutManager = grid
        pageApps.adapter = gridAdapter
        pageApps.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tile = (132 * context.resources.displayMetrics.density).toInt()
            val span = max(3, (v.width - v.paddingLeft - v.paddingRight) / tile)
            if (grid.spanCount != span) handler.post { grid.spanCount = span }
        }

        mirrorView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attachMirror(st, w, h)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = attachMirror(st, w, h)
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                ProjectionService.instance?.detachMirror()
                mirrorSurface?.release()
                mirrorSurface = null
                return true
            }
        }
        pageMirror.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) handler.post { fitMirror() }
        }

        voice = VoiceController(appCtx, apps, ::showBubble, ::runCommand)
        guard = DrivingGuard(appCtx) { updateDrivingBlock() }
        guard.start()

        ProjectionService.addListener(projectionListener)
        TouchInjectorService.packageListeners.add(packageListener)

        created = true
        pendingSafeArea?.let { setSafeArea(it) }

        // Load apps off the main thread; icons can take a moment.
        Thread {
            val list = apps.all(refresh = true)
            handler.post {
                gridAdapter.submit(list)
                refreshRecents()
            }
        }.start()

        tick()
        showPage(Page.HOME)
        onProjectionChanged()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        if (!created) return
        created = false
        ProjectionService.removeListener(projectionListener)
        TouchInjectorService.packageListeners.remove(packageListener)
        ProjectionService.instance?.detachMirror()
        mirrorSurface?.release()
        mirrorSurface = null
        voice.release()
        guard.stop()
    }

    /** Keep our content out from under Android Auto's own buttons. */
    fun setSafeArea(area: Rect) {
        if (!created) { pendingSafeArea = area; return }
        val w = root.width.takeIf { it > 0 } ?: display.mode.physicalWidth
        val h = root.height.takeIf { it > 0 } ?: display.mode.physicalHeight
        if (area.isEmpty) return
        root.setPadding(
            area.left.coerceAtLeast(0), area.top.coerceAtLeast(0),
            (w - area.right).coerceAtLeast(0), (h - area.bottom).coerceAtLeast(0)
        )
    }

    // ---------------------------------------------------------------- pages

    fun showPage(p: Page) {
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
                ProjectionService.requestStart(appCtx)
                handler.post { fitMirror() }
            }
        }
        updateDrivingBlock()
    }

    private fun refreshRecents() {
        val pkgs = Prefs.recents(appCtx).ifEmpty { Prefs.DEFAULT_FAVORITES }
        val list = pkgs.mapNotNull { apps.byPackage(it) }.take(8)
        recentAdapter.submit(list)
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
            !TouchInjectorService.isEnabled(appCtx) -> "Turn on OpenDash in Accessibility to control apps from here"
            !active -> "Phone screen not shared yet. Open any app to start"
            else -> ""
        }
        homeStatus.visibility = if (homeStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        mirrorHint.visibility = if (active) View.GONE else View.VISIBLE
        fitMirror()
        if (active) mirrorView.surfaceTexture?.let { attachMirror(it, mirrorView.width, mirrorView.height) }
    }

    // ---------------------------------------------------------------- launching

    private fun launch(pkg: String) {
        if (apps.launch(pkg)) {
            showPage(Page.MIRROR)
        } else {
            showBubble("Couldn't open that app")
        }
    }

    private fun goBack() {
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
                if (apps.startIntent(intent)) showPage(Page.MIRROR) else showBubble("No maps app found")
            }
            is VoiceCommand.Play -> {
                showBubble("Playing ${cmd.query}")
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, cmd.query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                if (!apps.startIntent(intent)) showBubble("No music app can play that")
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
        val aw = pageMirror.width
        val ah = pageMirror.height
        if (aw <= 0 || ah <= 0) return
        val phone = phoneScreenSize(appCtx)
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
        get() = guard.moving && Prefs.blockVideo(appCtx) &&
            (TouchInjectorService.foregroundPackage ?: "") in Prefs.VIDEO_APPS

    private fun updateDrivingBlock() {
        drivingBlock.visibility = if (page == Page.MIRROR && isBlocked) View.VISIBLE else View.GONE
    }

    private fun mirrorRect(): RectF {
        val loc = IntArray(2)
        mirrorView.getLocationInWindow(loc)
        return RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + mirrorView.width).toFloat(), (loc[1] + mirrorView.height).toFloat()
        )
    }

    /** Car-screen point inside the mirror -> phone-screen pixel. */
    private fun toPhone(x: Float, y: Float, r: RectF, phone: Point): Pair<Float, Float> =
        ((x - r.left) / r.width() * phone.x) to ((y - r.top) / r.height() * phone.y)

    // ---------------------------------------------------------------- input from the car

    fun handleTap(x: Float, y: Float) {
        if (page == Page.MIRROR && ProjectionService.isActive) {
            val r = mirrorRect()
            if (r.width() > 0 && r.contains(x, y)) {
                if (isBlocked) return
                val inj = TouchInjectorService.instance
                if (inj == null) {
                    showBubble("Turn on OpenDash in your phone's Accessibility settings")
                    return
                }
                val (px, py) = toPhone(x, y, r, phoneScreenSize(appCtx))
                inj.tap(px, py)
                return
            }
        }
        syntheticTap(x, y)
    }

    fun handleScroll(dx: Float, dy: Float) {
        when (page) {
            Page.APPS -> pageApps.scrollBy(0, dy.toInt())
            Page.HOME -> recentRow.scrollBy(dx.toInt(), 0)
            Page.MIRROR -> {
                accX += dx
                accY += dy
                handler.removeCallbacks(flushScroll)
                handler.postDelayed(flushScroll, 90)
            }
        }
    }

    fun handleFling(vx: Float, vy: Float) {
        when (page) {
            Page.APPS -> pageApps.fling(0, (-vy).toInt())
            Page.HOME -> recentRow.fling((-vx).toInt(), 0)
            Page.MIRROR -> {
                handler.removeCallbacks(flushScroll)
                // Finger travel so far, plus a bit of momentum.
                flushSwipe(-accX + vx * 0.12f, -accY + vy * 0.12f, 120)
            }
        }
    }

    /** Replays a drag of ([fingerDx], [fingerDy]) car pixels as one swipe on the phone. */
    private fun flushSwipe(fingerDx: Float, fingerDy: Float, durationMs: Long) {
        accX = 0f
        accY = 0f
        if (isBlocked) return
        val inj = TouchInjectorService.instance ?: return
        val r = mirrorRect()
        if (r.width() <= 0) return
        val phone = phoneScreenSize(appCtx)
        val dx = fingerDx / r.width() * phone.x
        val dy = fingerDy / r.height() * phone.y
        if (hypot(dx, dy) < 12f) return
        val cx = phone.x / 2f
        val cy = phone.y / 2f
        val maxX = phone.x - 2f
        val maxY = phone.y - 2f
        inj.swipe(
            (cx - dx / 2).coerceIn(2f, maxX), (cy - dy / 2).coerceIn(2f, maxY),
            (cx + dx / 2).coerceIn(2f, maxX), (cy + dy / 2).coerceIn(2f, maxY),
            durationMs
        )
    }

    /** Delivers a tap to our own views (rail buttons, app tiles). */
    private fun syntheticTap(x: Float, y: Float) {
        val decor = window?.decorView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        decor.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        decor.dispatchTouchEvent(up)
        up.recycle()
    }
}
