package com.example.filmcamera.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.filmcamera.data.Mode

private val dialEase = CubicBezierEasing(.3f, 1.4f, .5f, 1f)
private val leverEase = CubicBezierEasing(.3f, 1.3f, .5f, 1f)
private val brushedTile by lazy { brushedMetalTile() }

/** 금속에 새겨진(음각) 글자: 아래쪽 밝은 가장자리 + 위쪽 어두운 가장자리 + 본문 */
@Composable
private fun Engraved(text: String, color: Color, style: TextStyle) {
    Box {
        Text(text, color = Color.White.copy(alpha = .9f), style = style, modifier = Modifier.offset(y = 1.dp))
        Text(text, color = Color.Black.copy(alpha = .28f), style = style, modifier = Modifier.offset(y = (-.5).dp))
        Text(text, color = color, style = style)
    }
}

/** 프레임 카운터 다이얼 (Ø92, 널링 테두리 + 실버 돔 + 숫자 창) */
@Composable
fun FrameDial(mode: Mode, total: Int, remaining: Int, noFilm: Boolean) {
    Box(
        Modifier
            .size(92.dp)
            .boxShadow(circle = true, offsetY = 4.dp, blur = 8.dp, color = Color.Black.copy(alpha = .35f))
            .drawBehind {
                rotate(-90f) { // conic-gradient는 12시 방향에서 시작
                    drawCircle(knurlBrush(Color(0xFF7D7D7A), Color(0xFFF2F2F0), 3f, center))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 실버 돔 (inset 7)
        Box(
            Modifier
                .size(78.dp)
                .clip(CircleShape)
                .drawBehind { drawTurnedMetalDisc(center, size.minDimension / 2f) },
            contentAlignment = Alignment.Center,
        ) {
            DialWindow(mode, total, remaining, noFilm)
        }
    }
}

@Composable
private fun DialWindow(mode: Mode, total: Int, remaining: Int, noFilm: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .size(46.dp, 32.dp)
            .boxShadow(corner = 7.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White.copy(alpha = .7f))
            .clip(shape)
            .background(PF.Black3)
            .insetShadow(corner = 7.dp, offsetY = 2.dp, blur = 4.dp, color = Color.Black.copy(alpha = .8f)),
    ) {
        if (mode == Mode.FILM) {
            val numbers = if (noFilm) listOf("–") else (0..total).map { (total - it).toString() }
            val index = if (noFilm) 0 else total - remaining
            val y by animateDpAsState(-(index * 32).dp, tween(550, easing = dialEase), label = "dial")
            // 스트립은 창보다 훨씬 크므로 가운데 정렬되지 않게 상단 기준으로 배치하고 위로 굴린다
            Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(y = y)) {
                numbers.forEach { n ->
                    Box(Modifier.size(46.dp, 32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            n, color = PF.LightDial,
                            style = TextStyle(
                                fontFamily = PFFonts.Figtree, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "SGL", color = PF.LightDial,
                    style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .1.em),
                )
            }
        }
        // 창 상단 포인트 점 (4dp, 위에서 2dp)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(PF.Accent),
        )
    }
}

/** PHONE_FILM 라벨 + FILM/SINGLE 모드 레버 */
@Composable
fun ModeLever(mode: Mode, onSelect: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val film = mode == Mode.FILM
    val knobX by animateDpAsState(if (film) 0.dp else 56.dp, tween(250, easing = leverEase), label = "lever")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Engraved(
            "PHONE_FILM", Metal.Engrave,
            TextStyle(fontFamily = PFFonts.Figtree, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .28.em),
        )
        Box(
            Modifier
                .size(118.dp, 36.dp)
                .boxShadow(corner = 18.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White.copy(alpha = .8f))
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF151515))
                .insetShadow(corner = 18.dp, offsetY = 2.dp, blur = 4.dp, color = Color.Black.copy(alpha = .8f))
                .border(2.dp, Brush.verticalGradient(listOf(Color.White, Metal.ChromeLo)), RoundedCornerShape(50)),
        ) {
            Box(
                Modifier
                    .offset(x = 3.dp + knobX, y = 3.dp)
                    .size(56.dp, 30.dp)
                    .boxShadow(corner = 15.dp, offsetY = 1.dp, blur = 3.dp, color = Color.Black.copy(alpha = .5f))
                    .clip(RoundedCornerShape(50))
                    .drawBehind {
                        // 광택 크롬: 위쪽 밝음 -> 수평선(어두운 띠) -> 아래쪽 반사광
                        drawRect(Brush.verticalGradient(
                            0f to Color.White, .42f to Color(0xFFDCDCD9), .56f to Color(0xFF9E9E9B), .82f to Color(0xFFC8C8C5), 1f to Color(0xFFEEEEEC),
                        ))
                        // 가공된 그립 홈 3줄 (어두운 선 + 아래 하이라이트)
                        val cx = size.width / 2f
                        for (k in -1..1) {
                            val x = cx + k * 5.dp.toPx()
                            drawLine(Color.Black.copy(alpha = .35f), Offset(x, size.height * .28f), Offset(x, size.height * .72f), 1.3.dp.toPx())
                            drawLine(Color.White.copy(alpha = .85f), Offset(x + 1.3.dp.toPx(), size.height * .28f), Offset(x + 1.3.dp.toPx(), size.height * .72f), 1.dp.toPx())
                        }
                    }
                    .insetShadow(corner = 15.dp, offsetY = 1.dp, blur = 1.dp, color = Color.White.copy(alpha = .9f)),
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(59.dp).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onSelect(Mode.FILM) })
                Box(Modifier.width(59.dp).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onSelect(Mode.SINGLE) })
            }
        }
        Row(Modifier.width(118.dp), horizontalArrangement = Arrangement.SpaceAround) {
            val base = TextStyle(
                fontFamily = PFFonts.Figtree, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = .14.em,
            )
            Engraved("FILM", if (film) PF.Ink else PF.Muted, base)
            Engraved("SINGLE", if (!film) PF.Ink else PF.Muted, base)
        }
    }
}

/** 필름 서랍 버튼 (88×96) */
@Composable
fun DrawerButton(name: String, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val dy by animateDpAsState(if (pressed) 2.dp else 0.dp, tween(100), label = "press")
    Column(
        Modifier
            .offset(y = dy)
            .size(88.dp, 96.dp)
            .boxShadow(corner = 18.dp, offsetY = 2.dp, blur = 0.dp, color = PF.SilverShadow)
            .boxShadow(corner = 18.dp, offsetY = 4.dp, blur = 8.dp, color = Color.Black.copy(alpha = .3f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(PF.SilverHi, PF.SilverMid)))
            .drawBehind {
                drawTile(brushedTile)
                drawRect(anisotropicGlint())
                val inset = 9.dp.toPx()
                val r = 2.7.dp.toPx()
                drawScrew(Offset(inset, inset), r, 25f)
                drawScrew(Offset(size.width - inset, inset), r, -40f)
                drawScrew(Offset(inset, size.height - inset), r, 70f)
                drawScrew(Offset(size.width - inset, size.height - inset), r, 5f)
            }
            .insetShadow(corner = 18.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White)
            .border(1.dp, Color.Black.copy(alpha = .18f), RoundedCornerShape(18.dp))
            .clickable(src, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        FilmCanisterIcon(34.dp, accent)
        Text(name, color = PF.Ink, maxLines = 1, softWrap = false,
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold))
        DrawerHandle()
    }
}

@Composable
fun DrawerHandle() {
    Box(
        Modifier
            .size(44.dp, 5.dp)
            .boxShadow(corner = 3.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White)
            .clip(RoundedCornerShape(50))
            .background(PF.InkSoft)
            .insetShadow(corner = 3.dp, offsetY = 1.dp, blur = 2.dp, color = Color.Black.copy(alpha = .6f)),
    )
}

/** 상단 실버 플레이트 (높이 156) */
@Composable
fun TopPlate(
    mode: Mode, total: Int, remaining: Int, noFilm: Boolean,
    drawerName: String, drawerAccent: Color,
    onMode: (Mode) -> Unit, onDrawer: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(156.dp)
            .boxShadow(offsetY = 2.dp, blur = 0.dp, color = Color(0xFF0B0B0B))
            .drawBehind {
                drawRect(Metal.Base)
                drawTile(brushedTile)
                drawRect(anisotropicGlint())
                drawRect(metalSheen())
                // 베벨: 위쪽 모서리는 빛을 받아 밝고, 아래쪽은 그늘
                drawRect(Color.White.copy(alpha = .9f), Offset.Zero, androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx()))
                drawRect(Color.White.copy(alpha = .35f), Offset(0f, 1.5.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                // 작은 나사 4개
                val m = 12.dp.toPx()
                val r = 3.6.dp.toPx()
                drawScrew(Offset(m, m), r, 30f)
                drawScrew(Offset(size.width - m, m), r, -25f)
                drawScrew(Offset(m, size.height - m), r, 80f)
                drawScrew(Offset(size.width - m, size.height - m), r, 10f)
            }
            .insetShadow(offsetY = (-2).dp, blur = 0.dp, color = PF.SilverLine)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FrameDial(mode, total, remaining, noFilm)
        ModeLever(mode, onMode, Modifier.weight(1f))
        DrawerButton(drawerName, drawerAccent, onDrawer)
    }
}
