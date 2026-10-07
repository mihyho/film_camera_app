package com.example.filmcamera.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import java.util.Random
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 가죽 표현에 쓰는 색 */
object Leather {
    val Thread = Color(0xFF121110)        // 실(스티치)
    val ThreadHi = Color(0xFF8C8378)
    val Cream = Color(0xFFEEE7D8)         // 음각 글자/아이콘
    val Dark = Color(0xFF241408)
}

/**
 * 페블(알갱이) 가죽 질감 타일. Worley 노이즈로 알갱이 중심과 경계(주름)를 만들고,
 * 좌상단 광원의 음영을 넣는다. 가장자리를 감싸서 이음새 없이 반복된다.
 *
 * - f1: 가장 가까운 알갱이 중심까지의 거리, f2: 두 번째로 가까운 중심까지의 거리
 * - f2 - f1 이 작을수록 두 알갱이의 경계 -> 주름처럼 어둡게
 * - 알갱이마다 밝기를 조금씩 달리하고 잔 노이즈를 얹어 실제 가죽처럼 보이게 한다
 */
fun pebbleLeatherTile(size: Int = 384, cells: Int = 32, base: Int = 0xFF5E3C26.toInt(), seed: Long = 7L): Bitmap {
    val rnd = Random(seed)
    val px = FloatArray(cells * cells)
    val py = FloatArray(cells * cells)
    val tone = FloatArray(cells * cells)
    for (j in 0 until cells) for (i in 0 until cells) {
        val k = j * cells + i
        px[k] = i + .15f + rnd.nextFloat() * .7f
        py[k] = j + .15f + rnd.nextFloat() * .7f
        tone[k] = .95f + rnd.nextFloat() * .10f
    }
    val br = (base shr 16) and 0xFF
    val bg = (base shr 8) and 0xFF
    val bb = base and 0xFF
    val out = IntArray(size * size)
    val lumArr = FloatArray(size * size)
    val scale = cells / size.toFloat()
    for (y in 0 until size) for (x in 0 until size) {
        // 도메인 워프: 경계가 곧은 다각형이 되지 않고 둥글게 휘도록 (타일이 이어지도록 주기는 cells의 정수배)
        val tw = (2.0 * Math.PI / cells).toFloat()
        val u0 = x * scale
        val v0 = y * scale
        val u = u0 + .20f * kotlin.math.sin(tw * 3 * v0 + 1.0f) + .11f * kotlin.math.sin(tw * 7 * v0 + 2.3f) + .05f * kotlin.math.sin(tw * 17 * v0 + .7f)
        val v = v0 + .20f * kotlin.math.sin(tw * 3 * u0 + .4f) + .11f * kotlin.math.sin(tw * 7 * u0 + 4.1f) + .05f * kotlin.math.sin(tw * 17 * u0 + 3.3f)
        val ci = floor(u).toInt()
        val cj = floor(v).toInt()
        var f1 = 9f; var f2 = 9f; var bestK = 0; var bx = 0f; var by = 0f
        for (dj in -1..1) for (di in -1..1) {
            val ii = ci + di
            val jj = cj + dj
            val wi = ((ii % cells) + cells) % cells
            val wj = ((jj % cells) + cells) % cells
            val k = wj * cells + wi
            // 타일 경계를 넘어간 이웃의 중심은 한 바퀴(cells) 만큼 옮겨 계산
            val cx = px[k] + (ii - wi)
            val cy = py[k] + (jj - wj)
            val dx = u - cx
            val dy = v - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < f1) { f2 = f1; f1 = d; bestK = k; bx = dx; by = dy } else if (d < f2) f2 = d
        }
        // 주름은 얕고 좁게(완전히 검게 갈라지지 않는다), 알갱이는 둥글게 볼록
        val crease = smooth(0f, .30f, f2 - f1)
        val dome = 1f - smooth(0f, .80f, f1)
        // 광원 방향 (-0.6, -0.6): 알갱이의 좌상단 사면이 밝고 우하단 사면이 어둡다
        val lit = .5f - (bx * .62f + by * .62f) * .95f
        val shade = (lit.coerceIn(0f, 1f) * .55f + dome * .45f)
        val top = dome * dome * dome // 알갱이 윗면의 부드러운 광택(가죽의 둥근 하이라이트)
        var lum = (.93f + .07f * crease) * (.88f + .24f * shade) * tone[bestK] + .07f * top * crease
        lum *= 1f + (rnd.nextFloat() - .5f) * .04f             // 잔 노이즈
        lum = lum.coerceIn(0f, 1.4f)
        lumArr[y * size + x] = lum
    }
    // 가죽은 부드럽다: 1px 번짐(가로 -> 세로, 가장자리는 감싸서 이음새 없음)
    val tmp = FloatArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        tmp[y * size + x] = (lumArr[y * size + (x + size - 1) % size] + 2 * lumArr[y * size + x] + lumArr[y * size + (x + 1) % size]) / 4f
    }
    for (y in 0 until size) for (x in 0 until size) {
        val l = (tmp[((y + size - 1) % size) * size + x] + 2 * tmp[y * size + x] + tmp[((y + 1) % size) * size + x]) / 4f
        val r = min(255, (br * l).toInt())
        val g = min(255, (bg * l).toInt())
        val b = min(255, (bb * l).toInt())
        out[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
}

private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 둥근 사각형 경로를 따라 박음질. 눌린 홈 -> 실 그림자 -> 실 순서로 그려 가죽에 박힌 느낌을 낸다.
 */
fun DrawScope.drawStitching(inset: Float, corner: Float, dashOn: Float, dashOff: Float, width: Float) {
    val path = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner, corner)))
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
    // 1) 눌린 홈: 실 아래에서 가죽이 살짝 파인 자국
    drawPath(path, Color.Black.copy(alpha = .30f), style = Stroke(width * 2.6f, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = .06f), style = Stroke(width * 2.6f, cap = StrokeCap.Round),
        // 하이라이트는 홈 아래쪽 가장자리에서만 비치도록 한 칸 내려 그린다
    )
    // 2) 검은 실은 어두운 가죽에 묻히므로, 실 아래쪽에 빛을 받는 밝은 가장자리를 둔다
    drawContext.canvas.save()
    drawContext.canvas.translate(0f, width * .6f)
    drawPath(path, Color.White.copy(alpha = .20f), style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash))
    drawContext.canvas.restore()
    // 3) 실
    drawPath(path, Leather.Thread, style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash))
    // 4) 실 윗면 하이라이트
    drawContext.canvas.save()
    drawContext.canvas.translate(0f, -width * .22f)
    drawPath(path, Leather.ThreadHi.copy(alpha = .55f), style = Stroke(width * .42f, cap = StrokeCap.Round, pathEffect = dash))
    drawContext.canvas.restore()
}

internal fun max3(a: Float, b: Float, c: Float) = max(a, max(b, c))
