package net.sgransoft.portalsnap

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * One Camera2 device feeding one SurfaceTexture, reopened whole on every switch.
 *
 * The gen 1 Portal exposes two: "0" is Meta's smart camera (1280x720 only, cropped
 * and panned in hardware to follow people) and "1" is the wide 4056x3040 sensor.
 */
class CameraSource(ctx: Context) {
    class Opened(val id: String, val size: Size, val sensorOrientation: Int, val fps: Range<Int>?)

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(HandlerThread("camera").apply { start() }.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    val frames = Counter()

    fun ids(): List<String> = manager.cameraIdList.toList()

    fun open(id: String, texture: SurfaceTexture, onOpened: (Opened) -> Unit, onError: (String) -> Unit) {
        handler.post {
            closeNow()
            try {
                val chars = manager.getCameraCharacteristics(id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("no stream configurations")
                val size = pickSize(map.getOutputSizes(SurfaceTexture::class.java))
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val fps = pickFps(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                texture.setDefaultBufferSize(size.width, size.height)
                val s = Surface(texture)
                surface = s
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        device = cam
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                session = sess
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(s)
                                    if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                }.build()
                                sess.setRepeatingRequest(req, null, handler)
                                onOpened(Opened(id, size, orientation, fps))
                            }

                            override fun onConfigureFailed(sess: CameraCaptureSession) =
                                onError("camera $id: session configuration failed")
                        }, handler)
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        cam.close()
                        if (device === cam) device = null
                        onError("camera $id: disconnected")
                    }

                    override fun onError(cam: CameraDevice, error: Int) {
                        cam.close()
                        if (device === cam) device = null
                        onError("camera $id: device error $error")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "open camera $id", e)
                onError("camera $id: ${e.message}")
            }
        }
    }

    fun close() {
        handler.post { closeNow() }
    }

    private fun closeNow() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        device?.close()
        device = null
        surface?.release()
        surface = null
    }

    // 720p where offered (it is all the smart camera offers); otherwise the smallest
    // 16:9 at least 1280 wide, then the smallest anything at least 1280 wide.
    private fun pickSize(sizes: Array<Size>): Size {
        fun ratio(s: Size) = s.width.toFloat() / s.height
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        sizes.filter { abs(ratio(it) - 16f / 9) < 0.01f && it.width >= 1280 }.minByOrNull { it.width }?.let { return it }
        sizes.filter { it.width >= 1280 }.minByOrNull { it.width * it.height }?.let { return it }
        return sizes.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    // The fastest range that holds its rate: [30,30] beats [15,30] when both exist.
    private fun pickFps(ranges: Array<Range<Int>>?): Range<Int>? =
        ranges?.filter { it.upper <= 30 }?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
}
