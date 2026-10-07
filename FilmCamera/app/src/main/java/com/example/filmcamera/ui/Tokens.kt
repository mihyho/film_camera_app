package com.example.filmcamera.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.filmcamera.R

/** 디자인 핸드오프(2a 실버 × 블랙) 토큰. 값은 design_handoff_phone_film/README.md 기준. */
object PF {
    // 카메라 바디
    val Silver = Color(0xFFD4D4D1)
    val SilverHi = Color(0xFFF7F7F5)
    val SilverMid = Color(0xFFC4C4C1)
    val SilverEdge = Color(0xFFB9B9B6)
    val SilverShadow = Color(0xFF8A8A87)
    val SilverLine = Color(0xFF9A9A97)
    val Black = Color(0xFF141414)
    val Black2 = Color(0xFF1B1B1B)
    val Black3 = Color(0xFF0D0D0D)
    val Brown = Color(0xFF3D2618)

    // 텍스트
    val Ink = Color(0xFF1A1A1A)
    val InkSoft = Color(0xFF2A2A28)
    val Muted = Color(0xFF7A7A77)
    val LabelEngrave = Color(0xFF4A4A48)
    val Light = Color(0xFFF0F0EE)
    val LightDial = Color(0xFFF2F2F0)

    // 루트에서 덮어쓴 실버 액센트
    val Accent = Color(0xFF858583)
    val Accent400 = Color(0xFFB0B0AE)

    val ToastBg = Color(0xFF201E1D)
}

object PFFonts {
    // 가변 폰트 하나 대신 굵기별 정적 폰트를 쓴다: 가변 폰트의 기본 굵기(Light)가 한글 대체 폰트의 굵기 선택을 망쳤다
    val Figtree = FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
        Font(R.font.figtree_extrabold, FontWeight.ExtraBold),
    )
    val Caprasimo = FontFamily(Font(R.font.caprasimo, FontWeight.Normal))
}

/** material3 없이 쓰는 최소 Text. 색은 style이 아니라 color 인자로 넘긴다. */
@Composable
fun Text(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) = BasicText(
    text, modifier, style.copy(color = color), overflow = TextOverflow.Clip, softWrap = softWrap, maxLines = maxLines,
)
