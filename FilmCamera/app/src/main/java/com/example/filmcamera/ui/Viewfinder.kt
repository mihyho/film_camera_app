package com.example.filmcamera.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.example.filmcamera.data.Film

private val dots by lazy { dotTexture() }

@Composable
fun ViewfinderZone(
    glView: GLSurfaceView,
    label: String,
    grid: Boolean,
    stampOn: Boolean,
    stampText: String,
    flashTrigger: Int,
    zoom: Float,
    zoomEnabled: Boolean,
    onZoomBy: (Float) -> Unit,
    onZoomToggle: () -> Unit,
    noFilm: Boolean,
    pendingFilm: Film?,
    pendingLength: Int,
    onPendingLength: (Int) -> Unit,
    onLoad: () -> Unit,
    onDrawer: () -> Unit,
    drawerName: String,
    drawerAccent: Color,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit = {},
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(PF.Black2)
                drawTile(dots)
            }
            .padding(horizontal = 24.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 베젤 패딩 9dp씩. 3:4 프리뷰가 남는 영역 안에 들어가도록 폭을 정한다.
        val previewW = minOf(maxWidth - 18.dp, (maxHeight - 18.dp) * 3f / 4f)
        val previewH = previewW * 4f / 3f
        val bezelTop = Color(0xFFEDEDEB)
        val bezelBottom = Color(0xFFA9A9A6)

        Box(
            Modifier
                .boxShadow(corner = 22.dp, offsetY = 6.dp, blur = 14.dp, color = Color.Black.copy(alpha = .6f))
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(bezelTop, bezelBottom)))
                .insetShadow(corner = 22.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White)
                .padding(9.dp),
        ) {
            Box(Modifier.size(previewW, previewH)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    AndroidView({ glView }, Modifier.fillMaxSize())
                    // 확대 제스처: 핀치로 확대/축소, 두 번 탭하면 1배 <-> 2배. 서랍/패널이 떠 있을 때는 받지 않는다.
                    if (zoomEnabled) {
                        Box(
                            Modifier.fillMaxSize()
                                .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> if (z != 1f) onZoomBy(z) } }
                                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onZoomToggle() }) },
                        )
                    }
                    // 비네팅: inset 0 0 40px rgba(0,0,0,.45)
                    Box(Modifier.fillMaxSize().insetShadow(corner = 14.dp, blur = 40.dp, color = Color.Black.copy(alpha = .45f)))
                    if (grid) GridLines()
                    if (stampOn) {
                        Text(
                            stampText, color = PF.Accent400,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = .08.em),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                            softWrap = false,
                        )
                    }
                    Text(
                        label, color = Color.White.copy(alpha = .85f),
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .16.em),
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 10.dp, start = 12.dp),
                    )
                    if (noFilm) NoFilmOverlay(drawerName, drawerAccent, pendingFilm, pendingLength, onPendingLength, onLoad, onDrawer)
                    panel() // 현상/사진/설정 같은 패널은 이 창 안에서만 뜬다
                    Flash(flashTrigger)
                    ZoomHud(zoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                }
                // SurfaceView는 Compose 클립이 안 먹으므로, 베젤 그라데이션으로 모서리를 덮고 안쪽 2px 테두리를 그린다
                Canvas(Modifier.fillMaxSize()) {
                    val r = 14.dp.toPx()
                    val pad = 9.dp.toPx()
                    val inner = Path().apply {
                        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                    }
                    clipPath(inner, ClipOp.Difference) {
                        drawRect(
                            Brush.verticalGradient(listOf(bezelTop, bezelBottom), startY = -pad, endY = size.height + pad),
                            topLeft = Offset(-2f, -2f), size = Size(size.width + 4f, size.height + 4f),
                        )
                    }
                    val bw = 2.dp.toPx()
                    drawRoundRect(
                        Color(0xFF0B0B0B), Offset(bw / 2, bw / 2), Size(size.width - bw, size.height - bw),
                        CornerRadius(r - bw / 2, r - bw / 2), style = Stroke(bw),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLines() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = .45f)
        val w = 1.5.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
        }
    }
}

@Composable
private fun Flash(trigger: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            alpha.snapTo(.95f)
            alpha.animateTo(0f, tween(380))
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}

/** 필름 모드 + 장전된 롤 없음 */
@Composable
private fun NoFilmOverlay(
    drawerName: String, drawerAccent: Color, pendingFilm: Film?, pendingLength: Int,
    onPendingLength: (Int) -> Unit, onLoad: () -> Unit, onDrawer: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF121212).copy(alpha = .62f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            "NO FILM · 필름을 골라 넣어 주세요", color = PF.Light, softWrap = false,
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .2.em),
        )
        SmallDrawerButton(drawerName, drawerAccent, onDrawer)
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .12f)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(24, 36).forEach { n ->
                val sel = n == pendingLength
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) PF.Light else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null) { onPendingLength(n) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${n}장", color = if (sel) PF.Ink else PF.Light, softWrap = false,
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        val enabled = pendingFilm != null
        val accent = pendingFilm?.let { Color(it.accent) }
        Box(
            Modifier
                .height(46.dp)
                .boxShadow(corner = 23.dp, offsetY = 2.dp, blur = 6.dp, color = Color.Black.copy(alpha = .4f))
                .clip(RoundedCornerShape(50))
                .background(accent ?: Color.White.copy(alpha = .18f))
                .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { onLoad() }
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pendingFilm != null) "${pendingFilm.displayName} ${pendingLength}장 장전하기" else "필름을 골라 주세요",
                color = Color.White, softWrap = false,
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            "넣으면 다 찍을 때까지 바꿀 수 없고, 사진은 롤이 끝나면 현상돼요", color = Color(0xFFC9C9C6),
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 11.sp, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun SmallDrawerButton(name: String, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .offset(y = if (pressed) 2.dp else 0.dp)
            .height(50.dp)
            .boxShadow(corner = 16.dp, offsetY = 2.dp, blur = 0.dp, color = PF.SilverShadow)
            .boxShadow(corner = 16.dp, offsetY = 4.dp, blur = 8.dp, color = Color.Black.copy(alpha = .3f))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(PF.SilverHi, PF.SilverMid)))
            .insetShadow(corner = 16.dp, offsetY = 1.dp, blur = 0.dp, color = Color.White)
            .clickable(src, null, onClick = onClick)
            .padding(start = 11.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilmCanisterIcon(30.dp, accent)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = PF.Ink, softWrap = false,
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
            DrawerHandle()
        }
    }
}


/** 확대 배율 표시: 확대 중에는 계속 보이고, 1배로 돌아오면 잠시 뒤 사라진다 */
@Composable
private fun ZoomHud(zoom: Float, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        visible = true
        if (zoom <= 1.02f) {
            kotlinx.coroutines.delay(1200)
            visible = false
        }
    }
    if (!visible) return
    Text(
        com.example.filmcamera.ZoomMath.format(zoom), color = Color.White,
        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .08.em),
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
