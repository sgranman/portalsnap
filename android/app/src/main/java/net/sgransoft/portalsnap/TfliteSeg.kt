package net.sgransoft.portalsnap

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The segmentation model run through TensorFlow Lite itself, instead of through MediaPipe's
 * ImageSegmenter.
 *
 * MediaPipe bundles TFLite in its native library but doesn't expose the Java interpreter, and its
 * GPU path aborts converting the model's output into an MPImage — on both Portals, so it is the
 * conversion and not the driver (see android/README.md). Reading the output tensor here instead
 * is the only way to put this model on the GPU, and the GPU is what would make a heavier or
 * better model affordable at all.
 *
 * The model is mapped straight out of the APK, which works because `noCompress` keeps .tflite
 * entries stored rather than deflated.
 */
class TfliteSeg(ctx: Context, private val asset: String, backend: String) {
    private var gpu: GpuDelegate? = null
    private var nnapi: NnApiDelegate? = null
    private val interpreter: Interpreter

    /** The model's own input size, read from the graph rather than assumed. */
    val inW: Int
    val inH: Int
    val inChannels: Int
    /** The confidence mask's size and how many classes it carries. */
    val outW: Int
    val outH: Int
    val outClasses: Int
    val delegate: String

    private val input: ByteBuffer
    private val output: ByteBuffer

    init {
        // Read into a direct buffer rather than mapping: an AssetFileDescriptor's channel is
        // shared with every other asset in the APK, so its offsets aren't the file's. A path
        // starting with / is a file instead, so a candidate model can be pushed to the Portal
        // and tried without rebuilding.
        val model = (if (asset.startsWith("/")) java.io.FileInputStream(asset) else ctx.assets.open(asset)).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
        val options = Interpreter.Options()
        when (backend) {
            "gpu" -> GpuDelegate().also { gpu = it; options.addDelegate(it) }
            // Android's own accelerator API. Only worth anything where the vendor ships a driver
            // for it: a gen 2 Portal has qti-dsp and qti-gpu behind it, a gen 1 has none at all
            // and falls back to a reference implementation slower than XNNPACK.
            "nnapi" -> NnApiDelegate().also { nnapi = it; options.addDelegate(it) }
            else -> options.setNumThreads(4)
        }
        interpreter = try {
            Interpreter(model, options)
        } catch (e: Throwable) {
            gpu?.close(); gpu = null
            nnapi?.close(); nnapi = null
            throw e
        }
        delegate = backend.uppercase()

        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        val inShape = inT.shape()
        val outShape = outT.shape()
        // NHWC both ways for this family; read it rather than trust it.
        inH = inShape[1]; inW = inShape[2]; inChannels = inShape[3]
        outH = outShape[1]; outW = outShape[2]; outClasses = outShape[3]
        Log.i(TAG, "tflite $asset on $delegate: in ${inShape.joinToString("x")} ${inT.dataType()} " +
            "-> out ${outShape.joinToString("x")} ${outT.dataType()}, " +
            "${interpreter.outputTensorCount} output tensor(s)")

        input = ByteBuffer.allocateDirect(inW * inH * inChannels * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(outW * outH * outClasses * 4).order(ByteOrder.nativeOrder())
    }

    /**
     * Runs one frame. [rgba] is the frame already scaled to the model's input, 4 bytes a pixel;
     * [conf] receives the person confidence, one float a pixel, in the model's own output size.
     * Returns how long inference took in ms.
     */
    fun run(rgba: ByteBuffer, conf: FloatArray): Float {
        val tAll = SystemClock.elapsedRealtimeNanos()
        input.rewind()
        rgba.rewind()
        // The selfie models want 0..1 float RGB; the alpha byte is dropped.
        for (p in 0 until inW * inH) {
            val o = p * 4
            input.putFloat((rgba.get(o).toInt() and 255) / 255f)
            input.putFloat((rgba.get(o + 1).toInt() and 255) / 255f)
            input.putFloat((rgba.get(o + 2).toInt() and 255) / 255f)
        }
        input.rewind()
        output.rewind()
        val t0 = SystemClock.elapsedRealtimeNanos()
        interpreter.run(input, output)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        output.rewind()
        val f = output.asFloatBuffer()
        val n = minOf(conf.size, outW * outH)
        when {
            outClasses == 1 -> f.get(conf, 0, n)
            // Pascal VOC's 21 classes, which is what DeepLab was trained on: person is class 15,
            // and the channels are logits, so a softmax turns them into the confidence the rest of
            // the pipeline expects rather than a hard argmax with no soft edge.
            outClasses == VOC_CLASSES -> {
                // A full softmax over 21 channels is 21 exp() a pixel, 1.4M a frame, which cost
                // more than the model did. Person against its closest rival is one exp and says
                // the same thing: 1 where person wins clearly, 0 where it loses clearly, and a
                // soft edge in between, which is what the shaders want anyway.
                val logits = FloatArray(outClasses)
                for (p in 0 until n) {
                    f.position(p * outClasses)
                    f.get(logits, 0, outClasses)
                    var rival = Float.NEGATIVE_INFINITY
                    for (c in 0 until outClasses) if (c != VOC_PERSON && logits[c] > rival) rival = logits[c]
                    conf[p] = 1f / (1f + kotlin.math.exp(rival - logits[VOC_PERSON]))
                }
            }
            // MediaPipe's multiclass: channel 0 is the background, the person is the rest.
            else -> for (p in 0 until n) conf[p] = 1f - f.get(p * outClasses)
        }
        lastTotalMs = (SystemClock.elapsedRealtimeNanos() - tAll) / 1e6f
        return ms
    }

    /**
     * The last run end to end: the RGBA-to-float conversion and reading the mask back, as well as
     * inference. MediaPipe's own figure covers its equivalent work, so this is what compares.
     */
    var lastTotalMs = 0f
        private set

    fun close() {
        runCatching { interpreter.close() }
        gpu?.let { runCatching { it.close() } }
        gpu = null
        nnapi?.let { runCatching { it.close() } }
        nnapi = null
    }

    companion object {
        private const val VOC_CLASSES = 21
        private const val VOC_PERSON = 15

        /**
         * Loads the model each way and times it, so the two questions that decide whether any of
         * this is worth building — does the GPU delegate run these ops on an Adreno, and how fast
         * — get answered before anything is wired into the pipeline.
         */
        fun probe(ctx: Context, asset: String, frames: Int = 12) {
            for (backend in listOf("cpu", "gpu", "nnapi")) {
                var seg: TfliteSeg? = null
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    seg = TfliteSeg(ctx, asset, backend)
                    val loadMs = SystemClock.elapsedRealtime() - t0
                    val rgba = ByteBuffer.allocateDirect(seg.inW * seg.inH * 4).order(ByteOrder.nativeOrder())
                    for (k in 0 until seg.inW * seg.inH * 4) rgba.put(k, ((k * 37) and 255).toByte())
                    val conf = FloatArray(seg.outW * seg.outH)
                    val times = ArrayList<Float>()
                    val totals = ArrayList<Float>()
                    repeat(frames) { times += seg.run(rgba, conf); totals += seg.lastTotalMs }
                    times.sort()
                    totals.sort()
                    Log.i(TAG, ("tflite probe %s %s: load %dms, inference %.1fms, " +
                        "end-to-end %.1fms (conversion %.1fms), in %dx%d out %dx%dx%d").format(
                        asset, seg.delegate, loadMs, times[times.size / 2], totals[totals.size / 2],
                        totals[totals.size / 2] - times[times.size / 2],
                        seg.inW, seg.inH, seg.outW, seg.outH, seg.outClasses))
                } catch (e: Throwable) {
                    Log.w(TAG, "tflite probe $asset ${backend.uppercase()} failed: ${e.message?.take(120)}")
                } finally {
                    seg?.close()
                }
            }
        }
    }
}
