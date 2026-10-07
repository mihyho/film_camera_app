package com.example.filmcamera.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.filmcamera.ThumbLoader
import com.example.filmcamera.data.DevelopedRoll
import com.example.filmcamera.data.Roll
import com.example.filmcamera.data.Shot
import com.example.filmcamera.formatStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 디자인 프로토타입의 보조 화면 팔레트 (루트에서 덮어쓴 값) */
object PFLight {
    val Bg = Color(0xFFF0F0EE)
    val Surface = Color(0xFFF8F8F7)
    val Text = Color(0xFF201E1D)
    val Neutral300 = Color(0xFFACACAA)
    val Neutral400 = Color(0xFF949492)
    val Neutral700 = Color(0xFF4E4E4C)
    val Accent = Color(0xFF858583)
    val Sage = Color(0xFF7A8A5E)
    val SageBg = Color(0xFFF0FAE1)
    val SageInk = Color(0xFF272E1B)
}

private val panelGrain by lazy { grainTile(180, 0x000000, .20f, 33L) }

private val h1 = TextStyle(fontFamily = PFFonts.Caprasimo, fontSize = 26.sp, letterSpacing = (-.015).em)
private val h2 = TextStyle(fontFamily = PFFonts.Caprasimo, fontSize = 20.sp)
private val body13 = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 13.sp)

/** 프리뷰 창 안의 패널 공통 틀: 그레인 배경 + (뒤로 버튼 + 제목) 헤더. 상·하단 플레이트는 건드리지 않는다. */
@Composable
fun PanelScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().drawBehind { drawRect(PFLight.Bg); drawTile(panelGrain) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(remember { MutableInteractionSource() }, null, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val k = size.width / 24f
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(15f * k, 18f * k); lineTo(9f * k, 12f * k); lineTo(15f * k, 6f * k)
                    }
                    drawPath(p, PFLight.Accent, style = Stroke(2.75f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Text(title, color = PFLight.Text, style = h1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// ───────────────────────── 사진(갤러리) ─────────────────────────

private sealed interface GItem {
    data class Pending(val roll: Roll) : GItem
    data class Header(val title: String, val meta: String) : GItem
    data class Row(val shots: List<Pair<Int, Shot>>, val total: Int, val title: String) : GItem // (프레임 번호-1, 사진)
    data object Empty : GItem
}

@Composable
fun GalleryPanel(
    pendingRoll: Roll?,
    devRolls: List<DevelopedRoll>,
    singles: List<Shot>,
    onBack: () -> Unit,
    onOpen: (Shot, String, String) -> Unit,
) {
    val items = remember(pendingRoll, devRolls, singles) {
        buildList<GItem> {
            if (pendingRoll != null) add(GItem.Pending(pendingRoll))
            devRolls.forEach { r ->
                add(GItem.Header("${r.film.displayName} ${r.total}장", formatStamp(r.end)))
                r.shots.mapIndexed { i, s -> i to s }.chunked(4).forEach { add(GItem.Row(it, r.total, r.film.displayName)) }
            }
            if (singles.isNotEmpty()) {
                add(GItem.Header("단일 촬영", "${singles.size}장"))
                singles.mapIndexed { i, s -> i to s }.chunked(4).forEach { add(GItem.Row(it, 0, "")) }
            }
            if (pendingRoll == null && devRolls.isEmpty() && singles.isEmpty()) add(GItem.Empty)
        }
    }
    PanelScaffold("사진", onBack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.size
            items(items.size) { idx ->
                when (val it = items[idx]) {
                    is GItem.Pending -> PendingCard(it.roll)
                    is GItem.Header -> Row(
                        Modifier.fillMaxWidth().padding(top = if (idx == 0) 0.dp else 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(it.title, color = PFLight.Text, style = h2)
                        Text(it.meta, color = PFLight.Neutral700, style = body13)
                    }
                    is GItem.Row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        it.shots.forEach { (i, shot) ->
                            val meta = (if (it.total > 0) "${i + 1}번째 프레임 / ${it.total}" else "단일 촬영") + " · ${formatStamp(shot.takenAt)}"
                            val name = if (it.total > 0) it.title else shot.film.displayName
                            Thumb(shot, 240, Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp))
                                .clickable(remember { MutableInteractionSource() }, null) { onOpen(shot, name, meta) })
                        }
                        repeat(4 - it.shots.size) { Box(Modifier.weight(1f)) }
                    }
                    GItem.Empty -> Text("아직 사진이 없어요. 필름을 넣고 찍어 보세요.", color = PFLight.Neutral700,
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 15.sp, lineHeight = 22.sp))
                }
            }
        }
    }
}

/** 현상 대기 중 카드: 사진은 보여 주지 않고 찍은 칸만 채운다 */
@Composable
private fun PendingCard(roll: Roll) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(PFLight.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(roll.film.accent)))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${roll.film.displayName} 현상 대기 중", color = PFLight.Text, style = h2)
                Text("${roll.shots.size} / ${roll.total}장 촬영 · 다 찍으면 현상돼요", color = PFLight.Neutral700, style = body13.copy(fontSize = 12.sp))
            }
        }
        // 12열 슬롯
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (0 until roll.total).chunked(12).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { i ->
                        val shot = i < roll.shots.size
                        Box(
                            Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(3.dp))
                                .background(if (shot) PFLight.Text else Color.Transparent)
                                .border(1.5.dp, if (shot) PFLight.Text else PFLight.Neutral400, RoundedCornerShape(3.dp)),
                        )
                    }
                    repeat(12 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 썸네일(백그라운드 로딩) */
@Composable
fun Thumb(shot: Shot, px: Int, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, shot.uri, shot.file) {
        value = withContext(Dispatchers.IO) { ThumbLoader.load(context, shot, px) }
    }
    Box(modifier.background(Color(0xFF0E0E0E))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// ───────────────────────── 뷰어 ─────────────────────────

@Composable
fun ViewerPanel(shot: Shot, title: String, meta: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, shot.uri, shot.file) {
        value = withContext(Dispatchers.IO) { ThumbLoader.load(context, shot, 1280) }
    }
    Column(Modifier.fillMaxSize().background(PFLight.Text).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = PFLight.Bg, style = h2)
                Text(meta, color = PFLight.Neutral300, style = body13.copy(fontSize = 12.sp))
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0x1AF5EAD8))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val k = size.width / 24f
                    drawLine(PFLight.Bg, Offset(18f * k, 6f * k), Offset(6f * k, 18f * k), 2.75f * k, StrokeCap.Round)
                    drawLine(PFLight.Bg, Offset(6f * k, 6f * k), Offset(18f * k, 18f * k), 2.75f * k, StrokeCap.Round)
                }
            }
        }
        // 남은 공간 안에서 3:4를 유지한다(헤더를 덮지 않게)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * 3f / 4f)
            Box(Modifier.size(w, w * 4f / 3f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0E0E0E))) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

@Composable
fun SettingsPanel(
    grid: Boolean, stamp: Boolean, roll: Roll?, debug: Boolean,
    onGrid: (Boolean) -> Unit, onStamp: (Boolean) -> Unit,
    onDiscard: () -> Unit, onFinishRoll: () -> Unit, onWipe: () -> Unit, onBack: () -> Unit,
) {
    val hasRoll = roll != null
    var confirming by remember { mutableStateOf(false) }
    PanelScaffold("설정", onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(PFLight.Surface).padding(vertical = 4.dp)) {
                ToggleRow("격자 표시", "3×3 구도선", grid, onGrid)
                ToggleRow("날짜 각인", "사진 오른쪽 아래에 '26 10 07 형식으로", stamp, onStamp)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(PFLight.SageBg).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("롤은 기기에 저장돼요", color = PFLight.SageInk,
                    style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text(
                    "앱을 강제 종료하거나 재부팅해도 남은 장수와 찍은 사진은 그대로예요. 앱 데이터를 지우거나 다시 설치하면 진행 중인 롤은 초기화되고 잠금이 풀려요.",
                    color = PFLight.SageInk, style = body13.copy(lineHeight = 19.sp),
                )
            }
            if (roll != null) DiscardCard(roll) { confirming = true }
            if (debug) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("프로토타입 테스트", color = PFLight.Neutral700, modifier = Modifier.padding(horizontal = 4.dp),
                        style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .12.em))
                    SecondaryButton("남은 장 모두 찍기", enabled = hasRoll, onClick = onFinishRoll)
                    SecondaryButton("앱 데이터 지우기 (재설치)", enabled = true, onClick = onWipe)
                }
            }
        }
        if (confirming && roll != null) {
            DiscardConfirm(roll, onCancel = { confirming = false }, onConfirm = { confirming = false; onDiscard() })
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!value) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = PFLight.Text, style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(sub, color = PFLight.Neutral700, style = body13.copy(fontSize = 12.sp))
        }
        val x by animateDpAsState(if (value) 20.dp else 0.dp, tween(200), label = "knob")
        Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(50)).background(if (value) PFLight.Sage else PFLight.Neutral300)) {
            Box(
                Modifier.offset(x = 4.dp + x, y = 4.dp).size(24.dp)
                    .boxShadow(circle = true, offsetY = 1.dp, blur = 3.dp, color = Color.Black.copy(alpha = .25f))
                    .clip(CircleShape).background(PFLight.Bg),
            )
        }
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, PFLight.Text.copy(alpha = if (enabled) .16f else .08f), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) PFLight.Text else PFLight.Text.copy(alpha = .35f),
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}


private val DiscardRed = Color(0xFFB04A3A)

/** 진행 중인 필름 카드: 필름을 버리는 기능의 진입점(다른 설정과 분리해 경고색으로) */
@Composable
private fun DiscardCard(roll: Roll, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(PFLight.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("진행 중인 필름", color = PFLight.Text,
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Text("${roll.film.displayName} · ${roll.shots.size} / ${roll.total}장 촬영", color = PFLight.Neutral700,
                style = body13.copy(fontSize = 12.sp))
        }
        Text("중간에 포기하고 새로 시작하고 싶을 때 쓰세요. 찍은 사진은 모두 삭제되고 되돌릴 수 없어요.", color = PFLight.Neutral700,
            style = body13.copy(lineHeight = 18.sp))
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, DiscardRed, RoundedCornerShape(16.dp))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("필름 버리기", color = DiscardRed,
                style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
    }
}

/** 되돌릴 수 없는 삭제라서 한 번 더 확인한다(패널 안에 뜨는 오버레이) */
@Composable
private fun DiscardConfirm(roll: Roll, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0A0A0A).copy(alpha = .55f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PFLight.Surface)
                .clickable(remember { MutableInteractionSource() }, null) { /* 카드 안 탭이 닫힘으로 새지 않게 */ }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("필름을 버릴까요?", color = PFLight.Text, style = h2)
            Text(
                if (roll.shots.isEmpty()) "아직 찍은 사진이 없어요. ${roll.film.displayName} 필름을 빼내고 새로 고를 수 있어요."
                else "${roll.film.displayName} 롤에 찍은 ${roll.shots.size}장의 사진이 모두 삭제되고 되돌릴 수 없어요. 필름 잠금이 풀려서 새 필름을 고를 수 있어요.",
                color = PFLight.Neutral700, style = body13.copy(lineHeight = 19.sp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(16.dp))
                        .border(1.dp, PFLight.Text.copy(alpha = .16f), RoundedCornerShape(16.dp))
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { Text("취소", color = PFLight.Text, style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(16.dp)).background(DiscardRed)
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) { Text("버리기", color = Color.White, style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 14.sp, fontWeight = FontWeight.Bold)) }
            }
        }
    }
}
