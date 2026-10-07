package com.example.filmcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * 필름 통 아이콘. PhoneFilmApp.dc.html의 인라인 SVG(viewBox 24, stroke #1a1a1a 1.6, round)를 그대로 옮겼다.
 * 몸통(rect 2.3,6.4)만 현재 필름의 포인트 컬러로 채운다.
 */
@Composable
fun FilmCanisterIcon(size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val parser = PathParser()
    val body = parser.parsePathString("M12.8 7.3h1.3v-.7h8.3v8.6h-2.5a1.6 1.6 0 0 0-1.6 1.6v1.6h-4.2v-.8h-1.3").toPath()
    val spine = parser.parsePathString("M4.2 6.6v10.8").toPath()
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            val white = PF.SilverHi
            val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color?) {
                if (fill != null) drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r, r))
                drawRoundRect(PF.Ink, Offset(x, y), Size(w, h), CornerRadius(r, r), style = stroke)
            }
            drawPath(body, white)
            drawPath(body, PF.Ink, style = stroke)
            rect(2.3f, 6.4f, 10.5f, 11.2f, 0f, accent)
            rect(5.2f, 1.6f, 4.6f, 2.6f, .8f, white)
            rect(1.5f, 4.2f, 12.1f, 2.4f, 1f, white)
            rect(1.5f, 17.4f, 12.1f, 2.4f, 1f, white)
            drawPath(spine, PF.Ink, style = stroke)
            rect(9.1f, 8.2f, 1.9f, 7.6f, .95f, white)
            rect(15.4f, 8f, 1.1f, 1.2f, .3f, null)
            rect(17.6f, 8f, 1.1f, 1.2f, .3f, null)
            rect(19.8f, 8f, 1.1f, 1.2f, .3f, null)
            rect(15.4f, 16.2f, 1.1f, 1.2f, .3f, null)
        }
    }
}

/** Lucide `sliders-horizontal` (viewBox 24, stroke 2.75, round cap) */
@Composable
fun SlidersIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            val st = Stroke(width = 2.75f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), 2.75f, StrokeCap.Round)
            line(21f, 4f, 14f, 4f); line(10f, 4f, 3f, 4f)
            line(21f, 12f, 12f, 12f); line(8f, 12f, 3f, 12f)
            line(21f, 20f, 16f, 20f); line(12f, 20f, 3f, 20f)
            line(14f, 2f, 14f, 6f); line(8f, 10f, 8f, 14f); line(16f, 18f, 16f, 22f)
        }
    }
}
