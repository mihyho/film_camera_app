package com.example.filmcamera.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 페블 가죽 타일 (플레이트와 버튼 면에서 공유) */
private val leatherTile by lazy { pebbleLeatherTile() }
private val leatherBrush by lazy { ShaderBrush(ImageShader(leatherTile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)) }

/** 하단 가죽 플레이트 (높이 132): 페블 가죽 + 광택/마모 + 박음질 */
@Composable
fun BottomPlate(
    showCount: Boolean,
    shotCount: Int,
    lastThumb: Bitmap?,
    onPhotos: () -> Unit,
    onShutter: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .boxShadow(offsetY = (-2).dp, blur = 0.dp, color = Color.Black)
            .drawBehind {
                drawRect(leatherBrush)
                // 위에서 비치는 부드러운 광택, 아래로 갈수록 어두워짐
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .10f), .35f to Color.Transparent, 1f to Color.Black.copy(alpha = .18f)))
                // 오래 쓴 가죽의 얼룩(밝게 닳은 곳, 어둡게 눌린 곳)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFD9A66B).copy(alpha = .13f), Color.Transparent), Offset(size.width * .24f, size.height * .38f), 110.dp.toPx()), 110.dp.toPx(), Offset(size.width * .24f, size.height * .38f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFD9A66B).copy(alpha = .09f), Color.Transparent), Offset(size.width * .72f, size.height * .72f), 90.dp.toPx()), 90.dp.toPx(), Offset(size.width * .72f, size.height * .72f))
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .14f), Color.Transparent), Offset(size.width * .50f, size.height * .20f), 100.dp.toPx()), 100.dp.toPx(), Offset(size.width * .50f, size.height * .20f))
                // 가장자리 눌림(좌우 어둡게)
                drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .26f), .10f to Color.Transparent, .90f to Color.Transparent, 1f to Color.Black.copy(alpha = .26f)))
                // 박음질 + 리벳
                drawStitching(inset = 8.dp.toPx(), corner = 18.dp.toPx(), dashOn = 3.0.dp.toPx(), dashOff = 4.8.dp.toPx(), width = 2.1.dp.toPx())
            }
            .insetShadow(offsetY = 2.dp, blur = 0.dp, color = Color.Black.copy(alpha = .45f))
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PhotoButton(showCount, shotCount, lastThumb, onPhotos)
        ShutterButton(onShutter)
        SettingsButton(onSettings)
    }
}

/** 은색 금속 원형 버튼: 상단 다이얼과 같은 선반 가공 은색(동심원 자국 + 원뿔 반사) */
@Composable
private fun SilverButton(diameter: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(diameter.dp)
            .boxShadow(circle = true, offsetY = 2.dp, blur = 5.dp, color = Color.Black.copy(alpha = .6f))
            .clip(CircleShape)
            .drawBehind { drawTurnedMetalDisc(center, size.minDimension / 2f) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.background(color: Color) = this.drawBehind { drawRect(color) }

/** 금속에 새겨진(음각) 글자: 아래쪽 밝은 가장자리 + 짙은 본문 */
@Composable
private fun EngravedText(text: String, style: TextStyle) {
    Box {
        Text(text, color = Color.White.copy(alpha = .9f), style = style, modifier = Modifier.offset(y = 1.dp))
        Text(text, color = Metal.Engrave, style = style)
    }
}

@Composable
private fun PhotoButton(showCount: Boolean, shotCount: Int, thumb: Bitmap?, onClick: () -> Unit) {
    SilverButton(58, Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
        if (showCount) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EngravedText(shotCount.toString(),
                    TextStyle(fontFamily = PFFonts.Figtree, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 19.sp))
                EngravedText("현상 전",
                    TextStyle(fontFamily = PFFonts.Figtree, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .08.em, lineHeight = 9.sp))
            }
        } else if (thumb != null) {
            Image(thumb.asImageBitmap(), null, Modifier.size(50.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // 눌린 가죽 홈(88) -> 은색 링(76) -> 은색 돔(64)
    Box(
        Modifier
            .offset(y = if (pressed) 2.dp else 0.dp)
            .size(88.dp)
            .clip(CircleShape)
            .drawBehind {
                drawCircle(leatherBrush)
                drawCircle(Color.Black.copy(alpha = .38f))
            }
            .insetShadow(circle = true, offsetY = 3.dp, blur = 8.dp, color = Color.Black.copy(alpha = .9f))
            .clickable(src, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(76.dp)
                .boxShadow(circle = true, offsetY = 3.dp, blur = 6.dp, color = Color.Black.copy(alpha = .6f))
                .clip(CircleShape)
                .drawBehind { drawCircle(chromeSweep(center)) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(64.dp)
                    .clip(CircleShape)
                    .domeBackground(.38f, .30f, Color.White, Color(0xFFD6D6D3), .40f, Color(0xFF8F8F8C))
                    .insetShadow(circle = true, offsetY = 1.dp, blur = 1.dp, color = Color.White),
            )
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .95f else 1f, tween(100), label = "press")
    SilverButton(58, Modifier.scale(s).clickable(src, null, onClick = onClick)) {
        // 음각 아이콘: 아래쪽 밝은 가장자리 + 짙은 본체
        Box {
            SlidersIcon(22.dp, Color.White.copy(alpha = .9f), Modifier.offset(y = 1.dp))
            SlidersIcon(22.dp, Metal.Engrave)
        }
    }
}

/** CSS radial-gradient(circle at x% y%, c0, c1 stop, c2) — 반경은 중심에서 가장 먼 모서리까지 */
private fun Modifier.domeBackground(cx: Float, cy: Float, c0: Color, c1: Color, stop: Float, c2: Color) = drawBehind {
    val c = Offset(size.width * cx, size.height * cy)
    val r = kotlin.math.hypot(size.width - c.x, size.height - c.y)
    drawCircle(Brush.radialGradient(0f to c0, stop to c1, 1f to c2, center = c, radius = r))
}
