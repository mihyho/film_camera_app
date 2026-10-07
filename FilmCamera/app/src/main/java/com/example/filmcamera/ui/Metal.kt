package com.example.filmcamera.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import java.util.Random

/** 금속 표현에 쓰는 색 */
object Metal {
    val Base = Color(0xFFD4D4D1)
    val ChromeHi = Color(0xFFFFFFFF)
    val ChromeMid = Color(0xFFD9D9D6)
    val ChromeLo = Color(0xFF8E8E8B)
    val Engrave = Color(0xFF3E3E3C)
}

/**
 * 브러시드 메탈 타일: 가로 방향으로 길게 이어진 가는 결.
 * 한 줄(y)마다 x 방향으로 번지게 평균 낸 노이즈를 만들어 "긁힌 결"을 만들고, 밝은 결은 흰색, 어두운 결은 검정으로 투명하게 얹는다.
 */
fun brushedMetalTile(w: Int = 256, h: Int = 96, seed: Long = 5L): Bitmap {
    val rnd = Random(seed)
    val px = IntArray(w * h)
    var prevBias = 0f
    for (y in 0 until h) {
        // 윗줄과 조금 이어지게 해서 결이 1px보다 굵게 보이도록
        val bias = prevBias * .45f + (rnd.nextFloat() - .5f) * .9f
        prevBias = bias
        val raw = FloatArray(w) { rnd.nextFloat() - .5f }
        // x 방향 박스 블러 2회(가장자리는 감싸서 이음새 없게) -> 긴 결
        val a = blurWrap(blurWrap(raw, 16), 9)
        for (x in 0 until w) {
            val v = (a[x] * 3.2f + bias * .55f).coerceIn(-1f, 1f)
            val alpha = (kotlin.math.abs(v) * if (v > 0) 52f else 42f).toInt().coerceIn(0, 255)
            val rgb = if (v > 0) 0xFFFFFF else 0x000000
            px[y * w + x] = (alpha shl 24) or rgb
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun blurWrap(src: FloatArray, r: Int): FloatArray {
    val n = src.size
    val out = FloatArray(n)
    var sum = 0f
    for (i in -r..r) sum += src[((i % n) + n) % n]
    for (x in 0 until n) {
        out[x] = sum / (2 * r + 1)
        sum += src[(x + r + 1) % n] - src[((x - r) % n + n) % n]
    }
    return out
}

/** 이방성 반사: 빛이 가로로 길게 맺히는 밝은 띠와 어두운 띠 */
fun anisotropicGlint(): Brush = Brush.horizontalGradient(
    0f to Color.Transparent,
    .16f to Color.White.copy(alpha = .30f),
    .30f to Color.Transparent,
    .52f to Color.Black.copy(alpha = .06f),
    .70f to Color.Transparent,
    .82f to Color.White.copy(alpha = .26f),
    .94f to Color.Transparent,
    1f to Color.Black.copy(alpha = .10f),
)

/** 윗면이 밝고 아래로 갈수록 살짝 어두워지는 광택 */
fun metalSheen(): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .38f),
    .22f to Color.White.copy(alpha = .06f),
    .70f to Color.Transparent,
    1f to Color.Black.copy(alpha = .14f),
)

/**
 * 가공된(선반 가공) 은색 원: 동심원 가공 자국 + CD 같은 원뿔형 반사.
 * 중심 기준으로 그리며, 원 안쪽에만 보이도록 호출하는 쪽에서 클립한다.
 */
fun DrawScope.drawTurnedMetalDisc(center: Offset, radius: Float) {
    // 바탕: 위쪽이 밝은 은색
    drawCircle(
        Brush.radialGradient(
            0f to Color.White, .5f to Metal.ChromeMid, 1f to Metal.ChromeLo,
            center = center + Offset(-radius * .25f, -radius * .3f), radius = radius * 1.7f,
        ),
        radius, center,
    )
    // 동심원 가공 자국 (아주 가는 선을 번갈아)
    var r = radius * .12f
    var i = 0
    while (r < radius) {
        drawCircle(
            if (i % 2 == 0) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .09f),
            r, center, style = Stroke(radius * .017f),
        )
        r += radius * .034f
        i++
    }
    // 원뿔형 반사: 서로 반대편에 두 개의 밝은 쐐기
    drawCircle(
        Brush.sweepGradient(
            0f to Color.Transparent, .05f to Color.White.copy(alpha = .55f), .12f to Color.Transparent,
            .30f to Color.Black.copy(alpha = .10f), .50f to Color.Transparent,
            .55f to Color.White.copy(alpha = .50f), .62f to Color.Transparent,
            .80f to Color.Black.copy(alpha = .12f), 1f to Color.Transparent,
            center = center,
        ),
        radius, center,
    )
    // 가장자리 베벨
    drawCircle(Color.White.copy(alpha = .75f), radius - radius * .02f, center, style = Stroke(radius * .035f))
    drawCircle(Color.Black.copy(alpha = .35f), radius, center, style = Stroke(radius * .03f))
}

/** 작은 십자/일자 나사. angle로 홈 방향을 달리해 기계적인 느낌을 낸다 */
fun DrawScope.drawScrew(center: Offset, radius: Float, angle: Float) {
    drawCircle(Color.Black.copy(alpha = .35f), radius * 1.2f, center + Offset(0f, radius * .3f))
    drawCircle(
        Brush.radialGradient(
            0f to Color.White, .55f to Metal.ChromeMid, 1f to Metal.ChromeLo,
            center = center + Offset(-radius * .3f, -radius * .35f), radius = radius * 1.6f,
        ),
        radius, center,
    )
    drawCircle(Color.Black.copy(alpha = .45f), radius, center, style = Stroke(radius * .14f))
    rotate(angle, center) {
        val half = radius * .78f
        // 홈(어두운 선) + 아래쪽 하이라이트
        drawLine(Color.White.copy(alpha = .55f), Offset(center.x - half, center.y + radius * .17f), Offset(center.x + half, center.y + radius * .17f), radius * .26f, StrokeCap.Butt)
        drawLine(Color(0xFF3A3A38), Offset(center.x - half, center.y), Offset(center.x + half, center.y), radius * .26f, StrokeCap.Butt)
    }
}


/** 셔터 링용 은색(크롬) sweep: 결 방향이 있는 금속 반사 */
fun chromeSweep(center: Offset): Brush = Brush.sweepGradient(
    0f to Color(0xFFF4F4F2), .18f to Color(0xFFBFBFBC), .38f to Color(0xFF7E7E7B),
    .52f to Color(0xFFF4F4F2), .70f to Color(0xFFBFBFBC), .88f to Color(0xFF7E7E7B), 1f to Color(0xFFF4F4F2),
    center = center,
)
