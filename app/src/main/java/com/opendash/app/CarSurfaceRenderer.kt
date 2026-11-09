package com.opendash.app

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Android Auto hands navigation apps a raw drawing surface. We put a private
 * virtual display on it and show [CarShellPresentation] there, so the car
 * screen can run normal Android views.
 */
class CarSurfaceRenderer(private val carContext: CarContext) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    var shell: CarShellPresentation? = null
        private set
    private var safeArea: Rect? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "OpenDashCar",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = vd
        shell = CarShellPresentation(carContext, vd.display).also { p ->
            try {
                p.show()
                safeArea?.let { p.setSafeArea(it) }
            } catch (e: Exception) {
                p.release()
            }
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    override fun onStableAreaChanged(stableArea: Rect) {
        safeArea = Rect(stableArea)
        shell?.setSafeArea(stableArea)
    }

    override fun onClick(x: Float, y: Float) {
        shell?.handleTap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        shell?.handleScroll(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        shell?.handleFling(velocityX, velocityY)
    }

    fun showPage(page: CarShellPresentation.Page) {
        shell?.showPage(page)
    }

    fun release() {
        shell?.let {
            it.release()
            try { it.dismiss() } catch (_: Exception) {}
        }
        shell = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
