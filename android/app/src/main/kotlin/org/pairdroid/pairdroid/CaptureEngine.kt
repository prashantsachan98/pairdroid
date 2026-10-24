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

/** In-memory only, bounded JPEG transport. No camera, audio, or recording. */
class CaptureEngine(private val context: Context, private val ended: () -> Unit) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile var active = false; private set
    @Volatile private var latest: JSONObject = JSONObject()
    private var last = 0L
    private var quality = 55
    fun start(result: Int, consent: Intent) {
        check(!active) { "Already sharing" }
        val worker = HandlerThread("pairdroid-capture").apply { start() }; thread = worker
        val handler = Handler(worker.looper)
        val mp = requireNotNull(context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, consent)) { "Android did not authorize projection" }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ended() }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if(active && width > 0 && height > 0) resize(width, height, handler)
            }
        }, Handler(context.mainLooper))
        val metrics = context.resources.displayMetrics
        active = true
        try {
            val (w,h) = dimensions(metrics.widthPixels, metrics.heightPixels)
            reader = makeReader(w,h,handler)
            display = mp.createVirtualDisplay("Visible PairDroid sharing", w,h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        } catch(e: Exception) { stop(); throw e }
    }
    private fun dimensions(width: Int, height: Int): Pair<Int,Int> {
        val scale = minOf(1.0, 540.0 / maxOf(width, height))
        return Pair((width * scale).toInt().coerceAtLeast(2), (height * scale).toInt().coerceAtLeast(2))
    }
    private fun resize(width: Int, height: Int, handler: Handler) {
        val (w,h) = dimensions(width,height)
        val old = reader
        val next = makeReader(w,h,handler)
        display?.surface = null
        display?.resize(w,h,context.resources.displayMetrics.densityDpi)
        display?.surface = next.surface
        reader = next; old?.close()
    }
    private fun makeReader(width: Int, height: Int, handler: Handler): ImageReader = ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).also { source ->
        source.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if(!active || SystemClock.elapsedRealtime() - last < 400) return@setOnImageAvailableListener
                last = SystemClock.elapsedRealtime()
                val plane = image.planes[0]
                val padded = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                val bitmap = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap,0,0,image.width,image.height)
                val bytes = java.io.ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG,quality,bytes)
                if(cropped !== bitmap) cropped.recycle(); bitmap.recycle()
                val data = bytes.toByteArray()
                quality = if(data.size > 120000) (quality - 5).coerceAtLeast(30) else (quality + 1).coerceAtMost(60)
                if(active) latest = JSONObject().put("jpeg", Base64.encodeToString(data, Base64.NO_WRAP))
                    .put("width", image.width).put("height",image.height).put("capturedAt",System.currentTimeMillis()).put("quality",quality)
            } catch (_: Exception) { /* One failed frame must not retain an Image. */ }
            finally { image.close() }
        }, handler)
    }
    fun frame(): JSONObject = JSONObject(latest.toString()).put("active",active)
    @Synchronized fun stop() {
        active = false; latest = JSONObject()
        display?.release(); display = null
        reader?.close(); reader = null
        val old = projection; projection = null; old?.stop()
        thread?.quitSafely(); thread = null
    }
}
