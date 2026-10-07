package com.example.filmcamera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.filmcamera.data.Film
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 서랍이 한 통에 대해 알아야 하는 상태 */
data class CanisterState(val film: Film, val current: Boolean, val locked: Boolean, val label: String)

private val sheetEase = CubicBezierEasing(.2f, 1.1f, .3f, 1f)
private val liftEase = CubicBezierEasing(.3f, 1.3f, .5f, 1f)
private val pullEase = CubicBezierEasing(.5f, 0f, .7f, .4f)

/**
 * 필름 서랍 오버레이(바텀시트). 하단 플레이트까지 포함해 화면 전체를 어둡게 덮고, 시트는 아래에서 올라온다.
 * 카메라 레이아웃은 그대로이고 이 오버레이만 그 위에 뜬다.
 */
@Composable
fun FilmDrawer(
    subtitle: String,
    canisters: List<CanisterState>,
    onClose: () -> Unit,
    onTake: (Film) -> Unit,
    onLockedTap: (Film) -> Unit,
) {
    var taking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // 딤: 탭하면 닫힘
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0A0A0A).copy(alpha = .5f))
                .clickable(remember { MutableInteractionSource() }, null) { onClose() },
        )

        // 시트: 열릴 때 translateY(110%) -> 0, 420ms
        val slide = remember { Animatable(1.1f) }
        LaunchedEffect(Unit) { slide.animateTo(0f, tween(420, easing = sheetEase)) }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(10.dp)
                .graphicsLayer { translationY = slide.value * size.height }
                .boxShadow(corner = 26.dp, offsetY = (-6).dp, blur = 24.dp, color = Color.Black.copy(alpha = .45f))
                .clip(RoundedCornerShape(26.dp))
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F4F2), Color(0xFFC9C9C6))))
                    // repeating-linear-gradient(0deg, white .07 0-1px, black .035 1-2px)
                    val px = 1.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawRect(Color.White.copy(alpha = .07f), Offset(0f, size.height - y - px), androidx.compose.ui.geometry.Size(size.width, px))
                        drawRect(Color.Black.copy(alpha = .035f), Offset(0f, size.height - y - 2 * px), androidx.compose.ui.geometry.Size(size.width, px))
                        y += 2 * px
                    }
                }
                .insetShadow(corner = 26.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White)
                .pointerInput(Unit) { detectTapGestures { } }, // 시트 안 탭이 딤으로 새지 않게
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("필름 서랍", color = PF.Ink,
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold))
                    Text(subtitle, color = Color(0xFF4E4E4C),
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 12.sp))
                }
                CloseButton(onClose)
            }
            Row(
                Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .drawBehind { drawRect(PF.Black2); drawTile(drawerDots) }
                    .insetShadow(corner = 18.dp, offsetY = 8.dp, blur = 16.dp, color = Color.Black.copy(alpha = .85f))
                    .padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.Bottom,
            ) {
                canisters.forEach { c ->
                    CanisterSlot(
                        c, taking,
                        onTapTake = { taking = true },
                        onTakeDone = { onTake(c.film) },
                        onLockedTap = { onLockedTap(c.film) },
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

private val drawerDots by lazy { dotTexture() }

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .boxShadow(circle = true, offsetY = 2.dp, blur = 4.dp, color = Color.Black.copy(alpha = .3f))
            .clip(CircleShape)
            .drawBehind {
                val c = Offset(size.width * .38f, size.height * .30f)
                val r = kotlin.math.hypot(size.width - c.x, size.height - c.y)
                drawCircle(Brush.radialGradient(0f to Color.White, .5f to Color(0xFFD0D0CD), 1f to Color(0xFF9C9C99), center = c, radius = r))
            }
            .insetShadow(circle = true, offsetY = 1.dp, blur = 1.dp, color = Color.White)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val k = size.width / 24f
            val w = 2.75f * k
            drawLine(PF.InkSoft, Offset(18f * k, 6f * k), Offset(6f * k, 18f * k), w, StrokeCap.Round)
            drawLine(PF.InkSoft, Offset(6f * k, 6f * k), Offset(18f * k, 18f * k), w, StrokeCap.Round)
        }
    }
}

/** 슬롯(80×156) + 필름 통 + 상태 라벨 */
@Composable
private fun CanisterSlot(
    c: CanisterState, taking: Boolean,
    onTapTake: () -> Unit, onTakeDone: () -> Unit, onLockedTap: () -> Unit, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lift by animateDpAsState(if (c.current) (-26).dp else 0.dp, tween(350, easing = liftEase), label = "lift")
    val shake = remember { Animatable(0f) }
    val pull = remember { Animatable(0f) } // 0 -> 1: 위로 뽑히며 회전/페이드

    Column(
        Modifier.clickable(remember { MutableInteractionSource() }, null) {
            when {
                c.current -> onClose()
                c.locked -> scope.launch {
                    onLockedTap()
                    shake.snapTo(0f)
                    shake.animateTo(0f, keyframes {
                        durationMillis = 420
                        0f at 0 using FastOutSlowInEasing
                        -7f at 70 using FastOutSlowInEasing
                        7f at 140 using FastOutSlowInEasing
                        -5f at 210 using FastOutSlowInEasing
                        5f at 280 using FastOutSlowInEasing
                        -2f at 350 using FastOutSlowInEasing
                        0f at 420
                    })
                }
                !taking -> {
                    onTapTake()
                    scope.launch { pull.animateTo(1f, tween(380, easing = pullEase)) }
                    scope.launch { delay(400); onTakeDone() } // 뽑힌 뒤 장착하고 서랍을 닫는다
                }
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // CSS처럼 슬롯 배경(+안쪽 그림자)은 아래층, 필름 통은 그 위에 클립 없이 얹는다(올라온 캡이 슬롯 밖으로 나온다)
        Box(
            Modifier
                .size(80.dp, 156.dp)
                .boxShadow(corner = 16.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White.copy(alpha = .08f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PF.Black3)
                    .insetShadow(corner = 16.dp, offsetY = 4.dp, blur = 10.dp, color = Color.Black.copy(alpha = .95f)),
            )
            Box(
                Modifier
                    .padding(bottom = 10.dp)
                    .graphicsLayer {
                        translationX = shake.value * density
                        translationY = (lift.toPx()) + pull.value * (-130.dp.toPx())
                        rotationZ = -6f * pull.value
                        alpha = 1f - 0.8f * pull.value
                    },
            ) {
                Canister(c.film)
            }
        }
        Text(c.label, color = if (c.current) PF.Light else Color(0xFFA8A8A6), softWrap = false,
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    }
}

/** 필름 통: 캡 / 림 / 몸통(포인트 컬러 + 원통 하이라이트) / 림 */
@Composable
private fun Canister(film: Film) {
    val accent = Color(film.accent)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(24.dp, 9.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF8A8A87), Color(0xFFF2F2F0), Color(0xFF9A9A97)))),
        )
        Rim()
        Column(
            Modifier
                .size(58.dp, 96.dp)
                .boxShadow(offsetY = 6.dp, blur = 6.dp, color = Color.Black.copy(alpha = .6f))
                .drawBehind {
                    drawRect(accent)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = .42f), .28f to Color.White.copy(alpha = .3f),
                            .52f to Color.White.copy(alpha = 0f), 1f to Color.Black.copy(alpha = .5f),
                        ),
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Text("${film.iso}", color = Color.White.copy(alpha = .9f),
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .14.em))
            Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .92f)).padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text(film.displayName, color = PF.Ink, softWrap = false,
                    style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold))
            }
            Text("PHONE_FILM", color = Color.White.copy(alpha = .8f), softWrap = false,
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .14.em))
        }
        Rim()
    }
}

@Composable
private fun Rim() {
    Box(
        Modifier.size(62.dp, 8.dp).clip(RoundedCornerShape(3.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF7A7A77), Color(0xFFEFEFED), Color(0xFF8F8F8C)))),
    )
}
