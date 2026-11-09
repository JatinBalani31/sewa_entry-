package org.sewaentry.tablet.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.sewaentry.tablet.face.FaceAligner
import org.sewaentry.tablet.face.FaceEmbedder
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class FaceCameraFrame(
    val faceCount: Int,
    val yawDegrees: Float? = null,
    val qualityGood: Boolean = false,
    val embedding: FloatArray? = null,
)

@Composable
fun FaceCameraPreview(
    modifier: Modifier = Modifier,
    onFrame: (FaceCameraFrame) -> Unit,
    onStatus: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val latestOnFrame = rememberUpdatedState(onFrame)
    val latestOnStatus = rememberUpdatedState(onStatus)

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val activeFrames = AtomicInteger()
        val resourceLock = Any()
        var disposed = false
        var resourcesClosed = false
        var cameraProvider: ProcessCameraProvider? = null
        var faceDetector: FaceDetector? = null
        var faceEmbedder: FaceEmbedder? = null
        var analysis: ImageAnalysis? = null

        fun closeResourcesWhenIdle() {
            synchronized(resourceLock) {
                if (disposed && activeFrames.get() == 0 && !resourcesClosed) {
                    resourcesClosed = true
                    faceDetector?.close()
                    faceEmbedder?.close()
                    executor.shutdown()
                }
            }
        }

        try {
            faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.2f)
                    .build(),
            )
            faceEmbedder = FaceEmbedder(context)
        } catch (error: IOException) {
            latestOnStatus.value("Face model could not be loaded: ${error.message ?: "asset error"}")
        } catch (error: IllegalArgumentException) {
            latestOnStatus.value("Face model is invalid: ${error.message ?: "model contract error"}")
        } catch (error: IllegalStateException) {
            latestOnStatus.value("Face model is unavailable: ${error.message ?: "runtime error"}")
        }

        if (faceDetector != null && faceEmbedder != null) {
            val detector = faceDetector
            val embedder = faceEmbedder
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(
                executor,
                FaceCameraAnalyzer(
                    detector = detector,
                    embedder = embedder,
                    callbackExecutor = executor,
                    activeFrames = activeFrames,
                    onFrameProcessingComplete = ::closeResourcesWhenIdle,
                    onFrame = { latestOnFrame.value(it) },
                    onStatus = { latestOnStatus.value(it) },
                ),
            )
            analysis = imageAnalysis

            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    if (!disposed) {
                        try {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                imageAnalysis,
                            )
                            cameraProvider = provider
                        } catch (error: ExecutionException) {
                            latestOnStatus.value(
                                "Camera could not start: ${error.cause?.message ?: error.message ?: "provider error"}",
                            )
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            latestOnStatus.value("Camera startup was interrupted.")
                        } catch (error: IllegalArgumentException) {
                            latestOnStatus.value("A front-facing camera is not available.")
                        } catch (error: IllegalStateException) {
                            latestOnStatus.value("Camera could not start: ${error.message ?: "camera unavailable"}")
                        } catch (error: SecurityException) {
                            latestOnStatus.value("Camera permission is required for enrollment.")
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

        onDispose {
            cameraProvider?.unbindAll()
            analysis?.clearAnalyzer()
            synchronized(resourceLock) {
                disposed = true
                executor.execute(::closeResourcesWhenIdle)
            }
        }
    }
}

private class FaceCameraAnalyzer(
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val callbackExecutor: ExecutorService,
    private val activeFrames: AtomicInteger,
    private val onFrameProcessingComplete: () -> Unit,
    private val onFrame: (FaceCameraFrame) -> Unit,
    private val onStatus: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastAnalysisAt = 0L

    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisAt = now

        val frameClosed = AtomicBoolean(false)
        val detectionCompleted = AtomicBoolean(false)
        var detectionStarted = false
        fun closeFrame() {
            if (frameClosed.compareAndSet(false, true)) imageProxy.close()
        }
        fun completeDetection() {
            if (detectionStarted && detectionCompleted.compareAndSet(false, true)) {
                activeFrames.decrementAndGet()
                onFrameProcessingComplete()
            }
        }

        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                closeFrame()
                onStatus("Camera frame is unavailable.")
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            activeFrames.incrementAndGet()
            detectionStarted = true
            detector.process(image)
                .addOnSuccessListener(callbackExecutor) { faces ->
                    try {
                        processFaces(imageProxy, faces)
                    } catch (error: RuntimeException) {
                        onStatus("Could not process camera frame: ${error.message ?: "unexpected frame error"}")
                    }
                }
                .addOnFailureListener(callbackExecutor) { error ->
                    onStatus("Face detection failed: ${error.message ?: "unknown detector error"}")
                }
                .addOnCompleteListener(callbackExecutor) {
                    completeDetection()
                    closeFrame()
                }
        } catch (error: RuntimeException) {
            completeDetection()
            closeFrame()
            onStatus("Camera frame could not be processed: ${error.message ?: "camera processing error"}")
        }
    }

    private fun processFaces(imageProxy: androidx.camera.core.ImageProxy, faces: List<Face>) {
        if (faces.size != 1) {
            onFrame(FaceCameraFrame(faceCount = faces.size))
            return
        }
        val face = faces.single()
        val qualityGood = FaceAligner.isGoodQuality(face)
        if (!qualityGood) {
            onFrame(
                FaceCameraFrame(
                    faceCount = 1,
                    yawDegrees = face.headEulerAngleY,
                    qualityGood = false,
                ),
            )
            return
        }

        var bitmap: Bitmap? = null
        var upright: Bitmap? = null
        var aligned: Bitmap? = null
        try {
            val source = imageProxy.toBitmap()
            bitmap = source
            val rotated = rotateBitmap(source, imageProxy.imageInfo.rotationDegrees)
            upright = rotated
            val normalized = FaceAligner.align(rotated, face)
            aligned = normalized
            onFrame(
                FaceCameraFrame(
                    faceCount = 1,
                    yawDegrees = face.headEulerAngleY,
                    qualityGood = true,
                    embedding = embedder.embed(normalized),
                ),
            )
        } catch (error: RuntimeException) {
            onStatus("Could not prepare face sample: ${error.message ?: "invalid camera frame"}")
        } finally {
            aligned?.recycle()
            if (upright !== bitmap) upright?.recycle()
            bitmap?.recycle()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 180L
    }
}
