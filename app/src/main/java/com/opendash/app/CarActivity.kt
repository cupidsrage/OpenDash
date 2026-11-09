package com.opendash.app

import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Intent
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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

    private val recentAdapter = AppAdapter(fillWidth = false) { launch(it.packageName) }
    private val gridAdapter = AppAdapter(fillWidth = true) { launch(it.packageName) }

    private lateinit var voice: VoiceController
    private lateinit var guard: DrivingGuard

    private var page = Page.HOME
    private var mirrorSurface: Surface? = null
    private var onCarDisplay = false

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

        btnHome.setOnClickListener { showPage(Page.HOME) }
        btnApps.setOnClickListener { showPage(Page.APPS) }
        btnScreen.setOnClickListener { showPage(Page.MIRROR) }
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnMic).setOnClickListener { voice.listen() }
        mirrorHint.setOnClickListener { ProjectionService.requestStart(this) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        recentRow.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recentRow.adapter = recentAdapter

        val grid = GridLayoutManager(this, 5)
        pageApps.layoutManager = grid
        pageApps.adapter = gridAdapter
        pageApps.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tile = (132 * resources.displayMetrics.density).toInt()
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

        // The car just connected and opened us: get screen sharing going right away
        // so the phone prompt is already waiting when you reach for an app.
        ProjectionService.requestStart(this)
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
        UpdateManager.carConnected = true
        guard.start()
        ProjectionService.addListener(projectionListener)
        TouchInjectorService.packageListeners.add(packageListener)
        tick()
        onProjectionChanged()
    }

    override fun onStop() {
        if (onCarDisplay) {
            handler.removeCallbacksAndMessages(null)
            guard.stop()
            ProjectionService.removeListener(projectionListener)
            TouchInjectorService.packageListeners.remove(packageListener)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (onCarDisplay) {
            voice.release()
            ProjectionService.instance?.detachMirror()
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
                ProjectionService.requestStart(this)
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
            !TouchInjectorService.isEnabled(this) -> "Turn on OpenDash in Accessibility to control apps from here"
            !active -> "Tap “Start now” on your phone to share its screen"
            else -> ""
        }
        homeStatus.visibility = if (homeStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        mirrorHint.visibility = if (active) View.GONE else View.VISIBLE
        fitMirror()
        if (active) mirrorView.surfaceTexture?.let { attachMirror(it, mirrorView.width, mirrorView.height) }
    }

    // ---------------------------------------------------------------- launching

    private fun launch(pkg: String) {
        if (apps.launch(pkg)) showPage(Page.MIRROR) else showBubble("Couldn't open that app")
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
