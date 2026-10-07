package com.example.filmcamera.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** CSS blur 반경(= 2σ)을 Android setShadowLayer 반경으로 변환 (Skia: σ = 0.57735·r + 0.5) */
private fun blurRadius(blurPx: Float) = max(0f, (blurPx / 2f - 0.5f) / 0.57735f)

private fun DrawScope.shapePath(
    left: Float, top: Float, w: Float, h: Float, corner: Float, circle: Boolean,
): Path = Path().apply {
    val r = if (circle) min(w, h) / 2f else corner
    addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(r, r)))
}

/** 화면 밖으로 밀어 그린 채움은 보이지 않고 그림자만 남기는 트릭에 쓰는 거리 */
private const val OFFSCREEN = 20000f

/**
 * CSS `box-shadow: dx dy blur spread color` (바깥 그림자). 요소 자신이 덮는 영역은 그리지 않는다.
 * - blur > 0: setShadowLayer. 도형은 화면 밖에 그리고 그림자만 안쪽으로 되돌려 채움이 보이지 않게 한다.
 * - blur = 0: setShadowLayer는 반경 0이면 꺼지므로 오프셋/확장한 도형을 직접 칠한다.
 */
fun Modifier.boxShadow(
    corner: Dp = 0.dp, circle: Boolean = false,
    offsetX: Dp = 0.dp, offsetY: Dp = 0.dp, blur: Dp, spread: Dp = 0.dp, color: Color,
): Modifier = drawBehind {
    val sp = spread.toPx()
    val dx = offsetX.toPx()
    val dy = offsetY.toPx()
    val body = shapePath(0f, 0f, size.width, size.height, corner.toPx(), circle)
    clipPath(body, ClipOp.Difference) {
        drawIntoCanvas { c ->
            val p = Paint()
            if (blur.toPx() <= 0f) {
                val shape = shapePath(-sp + dx, -sp + dy, size.width + 2 * sp, size.height + 2 * sp, corner.toPx() + sp, circle)
                p.color = color
                c.drawPath(shape, p)
            } else {
                val shape = shapePath(-sp - OFFSCREEN, -sp, size.width + 2 * sp, size.height + 2 * sp, corner.toPx() + sp, circle)
                p.asFrameworkPaint().apply {
                    isAntiAlias = true
                    this.color = android.graphics.Color.BLACK
                    setShadowLayer(blurRadius(blur.toPx()), dx + OFFSCREEN, dy, color.toArgb())
                }
                c.drawPath(shape, p)
            }
        }
    }
}

/** CSS `box-shadow: inset dx dy blur color`. 요소 위(콘텐츠 앞)에 그린다. */
fun Modifier.insetShadow(
    corner: Dp = 0.dp, circle: Boolean = false,
    offsetX: Dp = 0.dp, offsetY: Dp = 0.dp, blur: Dp, color: Color,
): Modifier = drawWithContent {
    drawContent()
    val dx = offsetX.toPx()
    val dy = offsetY.toPx()
    val body = shapePath(0f, 0f, size.width, size.height, corner.toPx(), circle)
    val big = 400f
    clipPath(body) {
        drawIntoCanvas { c ->
            val p = Paint()
            if (blur.toPx() <= 0f) {
                // 도형을 (dx, dy)만큼 민 뒤 그 바깥을 칠하면, 안쪽 가장자리에 단단한 띠가 남는다
                val moved = shapePath(dx, dy, size.width, size.height, corner.toPx(), circle)
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(-big, -big, size.width + big, size.height + big))
                    addPath(moved)
                }
                p.color = color
                c.drawPath(ring, p)
            } else {
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(-big, -big, size.width + big, size.height + big))
                    addPath(body)
                }
                p.asFrameworkPaint().apply {
                    isAntiAlias = true
                    this.color = android.graphics.Color.BLACK
                    setShadowLayer(blurRadius(blur.toPx()), dx, dy, color.toArgb())
                }
                c.drawPath(ring, p)
            }
        }
    }
}

/** CSS `repeating-conic-gradient(a 0 3deg, b 3deg 6deg)` 를 위한 sweep 브러시 (12시 방향 시작은 캔버스를 -90° 돌려서 맞춘다) */
fun knurlBrush(a: Color, b: Color, segmentDeg: Float = 3f, center: Offset): Brush {
    val n = (360f / segmentDeg).toInt()
    val stops = ArrayList<Pair<Float, Color>>()
    for (i in 0 until n) {
        val c = if (i % 2 == 0) a else b
        stops += (i / n.toFloat()) to c
        stops += ((i + 1) / n.toFloat()) to c
    }
    return Brush.sweepGradient(*stops.toTypedArray(), center = center)
}

/**
 * feTurbulence(fractalNoise)를 흉내 낸 그레인 타일. 픽셀마다 독립 노이즈라 타일 이음새가 없다.
 * alpha = maxAlpha * n (n은 평균 .5 부근에 몰린 값, feTurbulence의 알파 채널 분포와 비슷)
 */
fun grainTile(sizePx: Int, rgb: Int, maxAlpha: Float, seed: Long): Bitmap {
    val rnd = Random(seed)
    val px = IntArray(sizePx * sizePx) {
        // 4개 균일분포 평균 -> 종 모양, 평균 .5, 표준편차 ~.14
        val n = ((rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat()) / 4f).coerceIn(0f, 1f)
        val a = (n * maxAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        (a shl 24) or (rgb and 0xFFFFFF)
    }
    return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

/** 뷰파인더 배경 도트: radial-gradient(rgba(255,255,255,.07) 1px,..) 5px 격자 + radial-gradient(rgba(0,0,0,.6) 1px,..) 7px 격자(2,3 오프셋) */
fun dotTexture(): Bitmap {
    val size = 35 // 5와 7의 최소공배수
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val light = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((0.07f * 255).toInt(), 255, 255, 255) }
    val dark = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((0.6f * 255).toInt(), 0, 0, 0) }
    for (x in 0 until size step 5) for (y in 0 until size step 5) c.drawCircle(x.toFloat(), y.toFloat(), 1.0f, light)
    for (x in 0 until size step 7) for (y in 0 until size step 7) c.drawCircle(x + 2f, y + 3f, 1.0f, dark)
    return bmp
}

fun DrawScope.drawTile(bitmap: Bitmap, origin: Offset = Offset.Zero) {
    val m = android.graphics.Matrix().apply { setTranslate(origin.x, origin.y) }
    val p = Paint()
    p.asFrameworkPaint().apply {
        this.shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT).also { it.setLocalMatrix(m) }
        isFilterBitmap = false
    }
    drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, p) }
}
