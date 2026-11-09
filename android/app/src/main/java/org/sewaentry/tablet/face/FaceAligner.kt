package org.sewaentry.tablet.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

object FaceAligner {
    private const val CANON_LEFT_EYE_X = 38.2946f
    private const val CANON_LEFT_EYE_Y = 51.6963f
    private const val CANON_RIGHT_EYE_X = 73.5318f
    private const val CANON_RIGHT_EYE_Y = 51.5014f
    private const val BOX_MARGIN = 0.25f
    private const val MAX_YAW_DEGREES = 20f
    private const val MAX_ROLL_DEGREES = 20f
    private const val MIN_EYE_DISTANCE_PX = 24f

    fun isGoodQuality(face: Face): Boolean {
        if (abs(face.headEulerAngleY) > MAX_YAW_DEGREES) return false
        if (abs(face.headEulerAngleZ) > MAX_ROLL_DEGREES) return false

        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (left != null && right != null) {
            if (hypot(right.x - left.x, right.y - left.y) < MIN_EYE_DISTANCE_PX) return false
        }
        return true
    }

    fun align(frame: Bitmap, face: Face): Bitmap {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        return if (left != null && right != null) {
            alignByEyes(frame, left.x, left.y, right.x, right.y)
        } else {
            cropByBox(frame, face.boundingBox)
        }
    }

    private fun alignByEyes(
        frame: Bitmap,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    ): Bitmap {
        val dx = rightX - leftX
        val dy = rightY - leftY
        val sourceDistance = hypot(dx, dy).takeIf { it > 1e-3f } ?: 1f
        val targetDx = CANON_RIGHT_EYE_X - CANON_LEFT_EYE_X
        val targetDy = CANON_RIGHT_EYE_Y - CANON_LEFT_EYE_Y
        val scale = hypot(targetDx, targetDy) / sourceDistance
        val angle = atan2(targetDy, targetDx) - atan2(dy, dx)
        val a = scale * kotlin.math.cos(angle)
        val b = scale * kotlin.math.sin(angle)
        val tx = CANON_LEFT_EYE_X - (a * leftX - b * leftY)
        val ty = CANON_LEFT_EYE_Y - (b * leftX + a * leftY)
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    a, -b, tx,
                    b, a, ty,
                    0f, 0f, 1f,
                ),
            )
        }
        val output = Bitmap.createBitmap(
            FaceModelContract.INPUT_SIZE,
            FaceModelContract.INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(output).drawBitmap(
            frame,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return output
    }

    private fun cropByBox(frame: Bitmap, box: Rect): Bitmap {
        val side = max(box.width(), box.height()) * (1f + BOX_MARGIN)
        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()
        val output = Bitmap.createBitmap(
            FaceModelContract.INPUT_SIZE,
            FaceModelContract.INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val scale = FaceModelContract.INPUT_SIZE / side
        val matrix = Matrix().apply {
            postTranslate(-(centerX - side / 2f), -(centerY - side / 2f))
            postScale(scale, scale)
        }
        Canvas(output).drawBitmap(
            frame,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return output
    }
}
