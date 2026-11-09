package com.opendash.app

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Entry point Android Auto binds to when OpenDash is opened on the car screen. */
class DashCarAppService : CarAppService() {
    // Personal sideloaded build: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
}

class DashScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarSurfaceRenderer(carContext)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        // The car just connected and opened us: ask for screen sharing right away
        // so the phone prompt is already waiting when you reach for an app.
        ProjectionService.requestStart(carContext.applicationContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        renderer.release()
        // Car disconnected: stop sharing and let the phone rotate normally again.
        ProjectionService.stop(carContext.applicationContext)
    }

    override fun onGetTemplate(): Template {
        val back = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_back)).build())
            .setOnClickListener {
                if (TouchInjectorService.instance?.back() != true) {
                    renderer.showPage(CarShellPresentation.Page.HOME)
                }
            }
            .build()
        val home = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_home)).build())
            .setOnClickListener { renderer.showPage(CarShellPresentation.Page.HOME) }
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(back).addAction(home).build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }
            .build()
    }
}
