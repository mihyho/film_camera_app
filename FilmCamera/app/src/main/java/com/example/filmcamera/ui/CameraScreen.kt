package com.example.filmcamera.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.filmcamera.AppController
import com.example.filmcamera.BuildConfig
import com.example.filmcamera.Panel
import com.example.filmcamera.data.Film
import com.example.filmcamera.data.Mode
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 디자인 2a(silver) 카메라 화면: 상단 실버 플레이트 / 블랙 뷰파인더 / 다크 브라운 하단 */
@Composable
fun CameraScreen(
    c: AppController, glView: GLSurfaceView, onShutter: () -> Unit,
    onZoomBy: (Float) -> Unit, onZoomToggle: () -> Unit,
) {
    val s = c.state
    val film = s.mode == Mode.FILM
    val roll = s.roll
    val total = roll?.total ?: 24
    val remaining = roll?.remaining ?: total
    val noFilm = film && roll == null

    val drawerName = when {
        !film -> s.singleFilm.displayName
        roll != null -> "${roll.film.displayName} · $remaining"
        else -> c.pendingFilm?.displayName ?: "필름 서랍"
    }
    val drawerAccent = when {
        !film -> Color(s.singleFilm.accent)
        roll != null -> Color(roll.film.accent)
        else -> c.pendingFilm?.let { Color(it.accent) } ?: Color(0xFF5A5A58)
    }
    val label = when {
        noFilm -> ""
        film -> "${c.currentFilm.label} · $remaining/$total"
        else -> "${c.currentFilm.label} · SINGLE"
    }

    // 단일 모드 썸네일: 가장 최근 단일 촬영(갤러리 uri)
    val context = LocalContext.current
    val lastUri = if (!film) s.singleShots.firstOrNull()?.uri else null
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lastUri) {
        thumb = if (lastUri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(android.net.Uri.parse(lastUri), Size(256, 256), null) }.getOrNull()
        }
    }

    if (c.panel != null) BackHandler { (c.panel as? Panel.Viewer)?.let { c.closeViewer(it) } ?: run { c.panel = null } }

    Box(Modifier.fillMaxSize().background(PF.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            TopPlate(
                mode = s.mode, total = total, remaining = remaining, noFilm = noFilm,
                drawerName = drawerName, drawerAccent = drawerAccent,
                onMode = c::setMode, onDrawer = c::openDrawer,
            )
            ViewfinderZone(
                glView = glView, label = label, grid = s.grid, stampOn = s.stamp, stampText = c.stampText(),
                flashTrigger = c.flashTrigger, noFilm = noFilm,
                zoom = c.zoomRatio, zoomEnabled = c.zoomMax > 1.01f && c.panel == null && !c.drawerOpen,
                onZoomBy = onZoomBy, onZoomToggle = onZoomToggle,
                pendingFilm = c.pendingFilm, pendingLength = c.pendingLength,
                onPendingLength = { c.pendingLength = it }, onLoad = c::loadPendingRoll,
                onDrawer = c::openDrawer,
                drawerName = drawerName, drawerAccent = drawerAccent,
                modifier = Modifier.weight(1f),
                panel = {
                    when (val p = c.panel) {
                        is Panel.Develop -> s.devRolls.firstOrNull { it.start == p.rollStart }?.let { roll ->
                            DevelopPanel(
                                roll,
                                onNewFilm = { c.panel = null; c.openDrawer() },
                                onSingle = { c.panel = null; c.setMode(Mode.SINGLE) },
                                onOpen = c::openViewer,
                            )
                        }
                        Panel.Gallery -> GalleryPanel(
                            pendingRoll = s.roll, devRolls = s.devRolls, singles = s.singleShots,
                            onBack = { c.panel = null }, onOpen = c::openViewer,
                        )
                        Panel.Settings -> SettingsPanel(
                            grid = s.grid, stamp = s.stamp, roll = s.roll, debug = BuildConfig.DEBUG,
                            onGrid = c::setGrid, onStamp = c::setStamp, onDiscard = c::discardRoll,
                            onFinishRoll = c::debugFinishRoll, onWipe = c::debugWipe, onBack = { c.panel = null },
                        )
                        is Panel.Viewer -> ViewerPanel(p.shot, p.title, p.meta, onClose = { c.closeViewer(p) })
                        null -> {}
                    }
                },
            )
            BottomPlate(
                showCount = film && roll != null, shotCount = roll?.shots?.size ?: 0, lastThumb = thumb,
                onPhotos = { c.togglePanel(Panel.Gallery) }, onShutter = onShutter, onSettings = { c.togglePanel(Panel.Settings) },
            )
        }
        if (c.drawerOpen) {
            BackHandler { c.closeDrawer() }
            val canisters = Film.entries.map { f ->
                val locked = film && roll != null && roll.film != f
                val cur = when {
                    !film -> s.singleFilm == f
                    roll != null -> roll.film == f
                    else -> c.pendingFilm == f
                }
                val label = when {
                    cur -> if (film && roll != null) "장전됨 · ${remaining}장" else "끼워져 있음"
                    locked -> "잠김"
                    else -> "꺼내기"
                }
                CanisterState(f, cur, locked, label)
            }
            val subtitle = when {
                !film -> "하나 꺼내서 바로 바꿔 끼워요"
                roll != null -> "${roll.film.displayName} 롤을 다 찍을 때까지 다른 필름은 잠겨 있어요"
                else -> "넣을 필름을 하나 꺼내세요"
            }
            FilmDrawer(subtitle, canisters, onClose = c::closeDrawer, onTake = c::takeFilm, onLockedTap = { c.lockedFilmTapped() })
        }
        Toast(c, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars).padding(bottom = 132.dp + 16.dp))
    }
}

@Composable
private fun Toast(c: AppController, modifier: Modifier) {
    val t = c.toast ?: return
    LaunchedEffect(t.id) {
        delay(2200)
        c.dismissToast(t.id)
    }
    Box(modifier) {
        Text(
            t.text, color = PF.Light,
            style = TextStyle(fontFamily = PFFonts.Figtree, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(PF.ToastBg).padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
