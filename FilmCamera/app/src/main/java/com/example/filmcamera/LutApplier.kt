package com.example.filmcamera

/**
 * 촬영본에 LUT를 적용하는 CPU 구현. 프리뷰 셰이더(3D 텍스처 선형 보간)와 같은 33^3 삼선형 보간이다.
 * Android 의존성이 없어 JVM 단위 테스트로 Python(extract_lut.apply_lut) 결과와 비교할 수 있다.
 */
object LutApplier {

    /** px(ARGB_8888)의 [from, to) 구간을 제자리에서 변환한다. 알파는 유지. */
    fun applyArgb(px: IntArray, from: Int, to: Int, lut: CubeLut) {
        val n = lut.size
        val d = lut.data
        val scale = (n - 1) / 255f

        // 8비트 입력은 256가지뿐이므로 격자 위치/가중치를 미리 계산
        val idx = IntArray(256)
        val frac = FloatArray(256)
        for (v in 0..255) {
            val p = v * scale
            var i = p.toInt()
            if (i > n - 2) i = n - 2
            idx[v] = i
            frac[v] = p - i
        }

        val sr = 3
        val sg = 3 * n
        val sb = 3 * n * n

        for (k in from until to) {
            val c = px[k]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val fr = frac[r]; val fg = frac[g]; val fb = frac[b]
            val base = idx[r] * sr + idx[g] * sg + idx[b] * sb

            var o0 = 0f; var o1 = 0f; var o2 = 0f
            for (cb in 0..1) {
                val wb = if (cb == 1) fb else 1f - fb
                for (cg in 0..1) {
                    val wg = wb * (if (cg == 1) fg else 1f - fg)
                    for (cr in 0..1) {
                        val w = wg * (if (cr == 1) fr else 1f - fr)
                        val i = base + cr * sr + cg * sg + cb * sb
                        o0 += w * d[i]
                        o1 += w * d[i + 1]
                        o2 += w * d[i + 2]
                    }
                }
            }
            px[k] = (c and 0xFF000000.toInt()) or
                (to8(o0) shl 16) or (to8(o1) shl 8) or to8(o2)
        }
    }

    private fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
