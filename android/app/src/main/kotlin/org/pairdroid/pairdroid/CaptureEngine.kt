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
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject

/**
 * In-memory only, bounded JPEG transport. No camera, audio, or recording.
 *
 * The MediaProjection token is obtained once (one system consent dialog) and kept alive
 * for the lifetime of the foreground service. When the parent disconnects, we "park"
 * (pause frame capture but keep the projection alive). When the parent reconnects,
 * we "resume" instantly — no new consent dialog.
 *
 * stop() (full teardown) is only called on explicit revocation or service destruction.
 */
class CaptureEngine(private val context: Context, private val ended: () -> Unit) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    /** True when actively streaming frames to the parent. */
    @Volatile var active = false; private set

    /** True when the projection token is held but streaming is paused (parked state). */
    @Volatile var parked = false; private set

    @Volatile private var latest: JSONObject = JSONObject()
    private var last = 0L
    private var quality = 55

    /** Obtain the MediaProjection token and start streaming. Called only on first consent. */
    fun start(result: Int, consent: Intent) {
        check(!active && !parked) { "Already sharing or parked" }
        val worker = HandlerThread("pairdroid-capture").apply { start() }; thread = worker
        val handler = Handler(worker.looper)
        val mp = requireNotNull(
            context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, consent)
        ) { "Android did not authorize projection" }
        projection = mp
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
     * Park the projection: keep the MediaProjection token alive but stop sending frames.
     * The virtual display is released (saves GPU/battery), projection stays.
     * Call this when the parent stops watching — avoids the consent dialog on reconnect.
     */
    @Synchronized fun park() {
        if (!active && !parked) return
        active = false; parked = true; latest = JSONObject()
        display?.release(); display = null
        reader?.close(); reader = null
        // HandlerThread stays alive to handle projection callbacks.
    }

    /**
     * Resume from parked state: recreate the virtual display using the existing token.
     * No system consent dialog — the projection is already authorized.
     */
    @Synchronized fun resume() {
        check(parked && projection != null) { "No parked projection to resume" }
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
                    val bytes = java.io.ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
                    if (cropped !== bitmap) cropped.recycle(); bitmap.recycle()
                    val data = bytes.toByteArray()
                    quality = if (data.size > 120000) (quality - 5).coerceAtLeast(30) else (quality + 1).coerceAtMost(60)
                    if (active) latest = JSONObject().put("jpeg", Base64.encodeToString(data, Base64.NO_WRAP))
                        .put("width", image.width).put("height", image.height)
                        .put("capturedAt", System.currentTimeMillis()).put("quality", quality)
                } catch (_: Exception) { /* One failed frame must not retain an Image. */ }
                finally { image.close() }
            }, handler)
        }

    fun frame(): JSONObject = JSONObject(latest.toString()).put("active", active)

    /** Full teardown — releases the projection token. Only call on revocation or service destroy. */
    @Synchronized fun stop() {
        active = false; parked = false; latest = JSONObject()
        display?.release(); display = null
        reader?.close(); reader = null
        val old = projection; projection = null; old?.stop()
        thread?.quitSafely(); thread = null
    }
}

