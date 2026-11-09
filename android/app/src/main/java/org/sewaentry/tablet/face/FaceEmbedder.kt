package org.sewaentry.tablet.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class FaceEmbedder(context: Context) : AutoCloseable {
    private val interpreter = Interpreter(
        loadModel(context),
        Interpreter.Options().setNumThreads(4),
    )

    init {
        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)
        check(input.dataType() == DataType.FLOAT32) { "MobileFaceNet input must be float32" }
        check(input.shape().contentEquals(intArrayOf(1, FaceModelContract.INPUT_SIZE, FaceModelContract.INPUT_SIZE, 3))) {
            "MobileFaceNet input shape does not match the supported model contract"
        }
        check(output.dataType() == DataType.FLOAT32) { "MobileFaceNet output must be float32" }
        check(output.shape().contentEquals(intArrayOf(1, FaceModelContract.EMBEDDING_SIZE))) {
            "MobileFaceNet output shape does not match the supported model contract"
        }
    }

    fun embed(faceBitmap: Bitmap): FloatArray {
        val resized = resizeBitmap(faceBitmap, FaceModelContract.INPUT_SIZE, FaceModelContract.INPUT_SIZE)
        val input = ByteBuffer.allocateDirect(
            FaceModelContract.INPUT_SIZE * FaceModelContract.INPUT_SIZE * 3 * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        val pixels = IntArray(FaceModelContract.INPUT_SIZE * FaceModelContract.INPUT_SIZE)
        resized.getPixels(
            pixels,
            0,
            FaceModelContract.INPUT_SIZE,
            0,
            0,
            FaceModelContract.INPUT_SIZE,
            FaceModelContract.INPUT_SIZE,
        )
        for (pixel in pixels) {
            input.putFloat((((pixel shr 16) and 0xff) - 127.5f) / 128f)
            input.putFloat((((pixel shr 8) and 0xff) - 127.5f) / 128f)
            input.putFloat(((pixel and 0xff) - 127.5f) / 128f)
        }
        input.rewind()
        if (resized !== faceBitmap) resized.recycle()

        val output = Array(1) { FloatArray(FaceModelContract.EMBEDDING_SIZE) }
        interpreter.run(input, output)
        val embedding = output[0]
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        check(norm > 1e-10f) { "MobileFaceNet produced an empty embedding" }
        return FloatArray(embedding.size) { embedding[it] / norm }
    }

    override fun close() {
        interpreter.close()
    }

    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val matrix = Matrix().apply {
            setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private fun loadModel(context: Context): MappedByteBuffer =
        context.assets.openFd(MODEL_FILENAME).use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    asset.startOffset,
                    asset.declaredLength,
                )
            }
        }

    private companion object {
        const val MODEL_FILENAME = "mobilefacenet.tflite"
    }
}
