package org.pairdroid.pairdroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject

/**
 * In-memory only, bounded JPEG transport. No camera, audio, or recording.
 *
 * Two capture modes:
 *  - ACCESSIBILITY (preferred, API 30+): MirrorService.takeScreenshot(), enabled once
 *    in Settings > Accessibility. No consent dialog, ever.
 *  - PROJECTION (fallback, API < 30): MediaProjection token obtained once (one system
 *    consent dialog) and kept alive for the lifetime of the foreground service.
 *
 * In both modes, when the parent disconnects we "park" (pause frame capture but keep
 * the capture source alive). When the parent reconnects, we "resume" instantly.
 *
 * stop() (full teardown) is only called on explicit revocation or service destruction.
 */
class CaptureEngine(private val context: Context, private val ended: () -> Unit) {
    enum class Mode { NONE, PROJECTION, ACCESSIBILITY }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    /** True when actively streaming frames to the parent. */
    @Volatile var active = false; private set

    /** True when the capture source is held but streaming is paused (parked state). */
    @Volatile var parked = false; private set

    @Volatile var mode = Mode.NONE; private set

    /** True while a MediaProjection token is held (requires the mediaProjection FGS type). */
    val projectionHeld: Boolean get() = projection != null

    @Volatile private var latest: JSONObject = JSONObject()
    private var last = 0L
    private var quality = 55

    /** True when one-time Accessibility capture ("System Services") is ready to use. */
    fun accessibilityAvailable(): Boolean = Build.VERSION.SDK_INT >= 30 && MirrorService.instance != null

    /** Start streaming via the accessibility service. No system consent dialog. */
    @Synchronized fun startAccessibility() {
        check(!active && !parked) { "Already sharing or parked" }
        val service = MirrorService.instance ?: error("Enable System Services in Settings > Accessibility")
        val worker = thread ?: HandlerThread("pairdroid-capture").apply { start() }.also { thread = it }
        mode = Mode.ACCESSIBILITY; active = true; parked = false; latest = JSONObject()
        a11yTick(service, Handler(worker.looper))
    }

    private fun a11yTick(service: MirrorService, handler: Handler) {
        if (mode != Mode.ACCESSIBILITY) return
        if (MirrorService.instance == null) {
            // Service was disabled/revoked while sharing — full teardown.
            mode = Mode.NONE; active = false; parked = false; ended(); return
        }
        if (!active) { handler.postDelayed({ a11yTick(service, handler) }, 400); return }
        service.screenshot { bitmap ->
            if (bitmap != null) {
                try {
                    if (active && SystemClock.elapsedRealtime() - last >= 400) {
                        last = SystemClock.elapsedRealtime()
                        encode(bitmap)
                    }
                } finally { bitmap.recycle() }
            }
            if (mode == Mode.ACCESSIBILITY) handler.postDelayed({ a11yTick(service, handler) }, 400)
        }
    }

    /** Obtain the MediaProjection token and start streaming. Called only on first consent. */
    fun start(result: Int, consent: Intent) {
        check(!active && !parked) { "Already sharing or parked" }
        val worker = HandlerThread("pairdroid-capture").apply { start() }; thread = worker
        val handler = Handler(worker.looper)
        val mp = requireNotNull(
            context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, consent)
        ) { "Android did not authorize projection" }
        projection = mp
        mode = Mode.PROJECTION
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // OS revoked projection (screen off, DRM, etc.) — full teardown.
                active = false; parked = false; ended()
            }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (active && width > 0 && height > 0) resize(width, height, handler)
            }
        }, Handler(context.mainLooper))
        val metrics = context.resources.displayMetrics
        active = true; parked = false
        try {
            val (w, h) = dimensions(metrics.widthPixels, metrics.heightPixels)
            reader = makeReader(w, h, handler)
            display = mp.createVirtualDisplay(
                "pairdroid-mirror", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
            )
        } catch (e: Exception) { stop(); throw e }
    }

    /**
     * Park the capture: keep the projection token / accessibility loop alive but stop
     * sending frames. Call this when the parent stops watching — avoids any new
     * consent or setup on reconnect.
     */
    @Synchronized fun park() {
        if (!active && !parked) return
        active = false; parked = true; latest = JSONObject()
        display?.release(); display = null
        reader?.close(); reader = null
        // HandlerThread stays alive to handle projection callbacks / the a11y loop.
    }

    /**
     * Resume from parked state using the existing capture source.
     * No system consent dialog — capture is already authorized.
     */
    @Synchronized fun resume() {
        check(parked) { "No parked capture to resume" }
        if (mode == Mode.ACCESSIBILITY) { active = true; parked = false; latest = JSONObject(); return }
        check(projection != null) { "No parked projection to resume" }
        val worker = thread ?: HandlerThread("pairdroid-capture").apply { start() }.also { thread = it }
        val handler = Handler(worker.looper)
        val metrics = context.resources.displayMetrics
        active = true; parked = false; latest = JSONObject()
        val (w, h) = dimensions(metrics.widthPixels, metrics.heightPixels)
        reader = makeReader(w, h, handler)
        display = projection!!.createVirtualDisplay(
            "pairdroid-mirror", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
    }

    /** Downscale to the transport bound and JPEG-compress into [latest], adapting quality. */
    private fun encode(source: Bitmap) {
        val scale = minOf(1.0, 540.0 / maxOf(source.width, source.height))
        val scaled = if (scale < 1.0) Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(2),
            (source.height * scale).toInt().coerceAtLeast(2), true
        ) else source
        val bytes = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        if (scaled !== source) scaled.recycle()
        val data = bytes.toByteArray()
        quality = if (data.size > 120000) (quality - 5).coerceAtLeast(30) else (quality + 1).coerceAtMost(60)
        if (active) latest = JSONObject().put("jpeg", Base64.encodeToString(data, Base64.NO_WRAP))
            .put("width", scaled.width).put("height", scaled.height)
            .put("capturedAt", System.currentTimeMillis()).put("quality", quality)
    }

    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, 540.0 / maxOf(width, height))
        return Pair((width * scale).toInt().coerceAtLeast(2), (height * scale).toInt().coerceAtLeast(2))
    }
    private fun resize(width: Int, height: Int, handler: Handler) {
        val (w, h) = dimensions(width, height)
        val old = reader
        val next = makeReader(w, h, handler)
        display?.surface = null
        display?.resize(w, h, context.resources.displayMetrics.densityDpi)
        display?.surface = next.surface
        reader = next; old?.close()
    }
    private fun makeReader(width: Int, height: Int, handler: Handler): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { source ->
            source.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!active || SystemClock.elapsedRealtime() - last < 400) return@setOnImageAvailableListener
                    last = SystemClock.elapsedRealtime()
                    val plane = image.planes[0]
                    val padded = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                    val bitmap = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    bitmap.recycle()
                    try { encode(cropped) } finally { cropped.recycle() }
                } catch (_: Exception) { /* One failed frame must not retain an Image. */ }
                finally { image.close() }
            }, handler)
        }

    fun frame(): JSONObject = JSONObject(latest.toString()).put("active", active)

    /** Full teardown — releases the capture source. Only call on revocation or service destroy. */
    @Synchronized fun stop() {
        mode = Mode.NONE; active = false; parked = false; latest = JSONObject()
        display?.release(); display = null
        reader?.close(); reader = null
        val old = projection; projection = null; old?.stop()
        thread?.quitSafely(); thread = null
    }
}
