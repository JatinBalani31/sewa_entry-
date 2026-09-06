package org.sewaentry.tablet.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream

/** One continuous pen stroke — a finger down, moved, and lifted. */
private class Stroke2D {
    val points = mutableStateListOf<Offset>()
}

/**
 * Full-screen signature capture. The sewadar signs with a finger, sees what
 * they drew, and confirms.
 *
 * Strokes are kept as point lists rather than a single accumulated Path so
 * that Undo can drop the last one, and so the same data can be rasterised to
 * a PNG for storage at the end.
 */
@Composable
fun SignatureDialog(
    title: String,
    subtitle: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (signatureBase64: String) -> Unit,
) {
    val strokes = remember { mutableStateListOf<Stroke2D>() }
    var canvasWidth by remember { mutableStateOf(0) }
    var canvasHeight by remember { mutableStateOf(0) }
    // Compose does not recompose on mutations inside a nested list, so this
    // counter is what tells the Canvas a point was appended.
    var revision by remember { mutableStateOf(0) }

    val hasInk = strokes.any { it.points.size > 1 }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Card(
            modifier = Modifier.width(760.dp).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 14.sp, color = InkMuted)
                Spacer(Modifier.height(14.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(SurfaceAlt, RoundedCornerShape(14.dp))
                        .border(2.dp, if (hasInk) accent else Line, RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    strokes.add(Stroke2D().apply { points.add(offset) })
                                    revision++
                                },
                                onDrag = { change, _ ->
                                    strokes.lastOrNull()?.points?.add(change.position)
                                    revision++
                                    change.consume()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                        canvasWidth = size.width.toInt()
                        canvasHeight = size.height.toInt()
                        @Suppress("UNUSED_EXPRESSION") revision // read so this redraws on each point

                        strokes.forEach { stroke ->
                            if (stroke.points.size < 2) return@forEach
                            val path = Path().apply {
                                moveTo(stroke.points.first().x, stroke.points.first().y)
                                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = path,
                                color = Ink,
                                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }

                    if (!hasInk) {
                        Text(
                            "Sign here with your finger",
                            color = InkMuted,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            strokes.clear()
                            revision++
                        },
                        enabled = strokes.isNotEmpty(),
                    ) { Text("Clear") }

                    TextButton(
                        onClick = {
                            strokes.removeLastOrNull()
                            revision++
                        },
                        enabled = strokes.isNotEmpty(),
                    ) { Text("Undo") }

                    Spacer(Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = InkMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val encoded = rasterise(strokes, canvasWidth, canvasHeight)
                            onConfirm(encoded)
                        },
                        enabled = hasInk,
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Flattens the strokes into a PNG and returns it base64-encoded, which is how
 * the signature is stored alongside the attendance row.
 */
private fun rasterise(strokes: List<Stroke2D>, width: Int, height: Int): String {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val path = AndroidPath().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
