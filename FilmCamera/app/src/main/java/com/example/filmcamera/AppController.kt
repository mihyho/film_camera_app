package com.example.filmcamera

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.filmcamera.data.AppState
import com.example.filmcamera.data.Film
import com.example.filmcamera.data.Mode
import com.example.filmcamera.data.RollEngine
import com.example.filmcamera.data.Shot
import com.example.filmcamera.data.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

data class ToastMsg(val id: Int, val text: String)

/** 가운데 카메라 화면(프리뷰 창) 안에 뜨는 패널. 상단/하단 플레이트는 그대로다. */
sealed interface Panel {
    data class Develop(val rollStart: Long) : Panel
    data object Gallery : Panel
    data object Settings : Panel
    /** 사진 크게 보기. 닫으면 back(열기 전 패널)으로 돌아간다. */
    data class Viewer(val shot: com.example.filmcamera.data.Shot, val title: String, val meta: String, val back: Panel?) : Panel
}

/** 날짜 각인 형식: '26 10 07 */
fun formatStamp(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "'%02d %02d %02d".format(c.get(Calendar.YEAR) % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/** 앱 상태(롤/모드/설정)를 들고 있고, 바뀔 때마다 즉시 디스크에 저장한다. UI 전용 상태(토스트 등)는 저장하지 않는다. */
class AppController(private val appContext: Context) {

    private val store = StateStore(File(appContext.filesDir, "state.json"))

    var state by mutableStateOf(store.load())
        private set

    var toast by mutableStateOf<ToastMsg?>(null)
        private set
    private var toastSeq = 0

    var pendingFilm by mutableStateOf<Film?>(null)
    var pendingLength by mutableIntStateOf(24)
    var flashTrigger by mutableIntStateOf(0)
        private set
    var drawerOpen by mutableStateOf(false)
        private set
    var panel by mutableStateOf<Panel?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exporting = HashSet<Long>()

    private val luts = HashMap<Film, CubeLut>()

    fun lutFor(film: Film): CubeLut =
        luts.getOrPut(film) { appContext.assets.open(film.lutAsset).use { CubeLut.parse(it) } }

    /** 프리뷰/촬영에 쓰는 필름. 필름 모드에서 롤이 없으면 첫 필름(디자인 기준)을 보여 준다. */
    val currentFilm: Film
        get() = if (state.mode == Mode.FILM) state.roll?.film ?: Film.entries.first() else state.singleFilm

    private fun commit(next: AppState) {
        store.save(next) // 저장이 성공한 뒤에만 메모리 상태를 바꾼다
        state = next
    }

    fun showToast(text: String) {
        toast = ToastMsg(++toastSeq, text)
    }

    fun dismissToast(id: Int) {
        if (toast?.id == id) toast = null
    }

    fun openDrawer() {
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    /** 서랍에서 필름을 뽑아 끼운다. 단일 모드는 바로 교체, 필름 모드는 장전할 필름으로 고른다. */
    fun takeFilm(film: Film) {
        if (state.mode == Mode.SINGLE) commit(RollEngine.selectSingleFilm(state, film)) else pendingFilm = film
        drawerOpen = false
    }

    /** 롤이 장전된 채 다른 필름을 누른 경우 */
    fun lockedFilmTapped() {
        val r = state.roll ?: return
        showToast("롤을 다 찍어야 바꿀 수 있어요 · 남은 ${r.remaining}장")
    }

    fun triggerFlash() {
        flashTrigger++
    }

    fun setMode(mode: Mode) {
        val s = state
        if (s.mode == mode) return
        commit(RollEngine.setMode(s, mode))
        showToast(
            when {
                mode == Mode.SINGLE -> "단일 촬영 · 필름 롤은 그대로 멈춰 있어요"
                s.roll != null -> "${s.roll.film.displayName} 롤로 돌아왔어요 · 남은 ${s.roll.remaining}장"
                else -> "필름 모드 · 필름을 넣어 주세요"
            },
        )
    }

    fun loadPendingRoll() {
        val film = pendingFilm ?: return
        commit(RollEngine.loadRoll(state, film, pendingLength, System.currentTimeMillis()))
        pendingFilm = null
        showToast("${film.displayName} ${state.roll!!.total}장 장전됨")
    }

    /** 사진 파일이 저장된 뒤에 호출한다(저장 성공 -> 카운트 감소 순서). */
    fun onPhotoSaved(saved: SavedPhoto, mode: Mode, film: Film) {
        val now = System.currentTimeMillis()
        if (mode == Mode.FILM) {
            if (state.roll == null) return
            val (next, finished) = RollEngine.recordFilmShot(state, Shot(film, saved.file!!, now), now)
            commit(next)
            if (finished) {
                val rollStart = next.devRolls.first().start
                exportPending() // 갤러리로 내보내기(백그라운드)
                scope.launch {
                    delay(650) // 마지막 셔터 직후 잠깐 뒤에 현상 화면으로
                    panel = Panel.Develop(rollStart)
                }
            }
        } else {
            commit(RollEngine.recordSingleShot(state, Shot(film, saved.uri!!, now, saved.uri)))
        }
    }

    /**
     * 다 찍은 롤을 갤러리로 내보낸다. 앱 시작 시에도 호출되어, 내보내기 도중 앱이 죽었던 롤을 이어서 처리한다.
     * 전부 성공해야 exported=true로 기록하고 비공개 원본을 지운다.
     */
    fun exportPending() {
        for (roll in RollEngine.pendingExports(state)) {
            if (!exporting.add(roll.start)) continue
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { RollExporter.export(appContext, roll) } }
                result.onSuccess { uris ->
                    commit(RollEngine.markExported(state, roll.start, uris))
                    withContext(Dispatchers.IO) { RollExporter.deletePrivateFiles(appContext, roll) }
                }
                result.onFailure { showToast("사진을 갤러리로 옮기지 못했어요 · 다음에 다시 시도해요") }
                exporting.remove(roll.start)
            }
        }
    }

    fun stampText(): String = formatStamp(System.currentTimeMillis())

    // ── 패널 (모두 가운데 프리뷰 창 안에서만 열린다) ──

    /** 이미 열려 있으면 닫는다(하단 버튼 토글) */
    fun togglePanel(target: Panel) {
        panel = if (panel == target) null else target
    }

    fun openViewer(shot: Shot, title: String, meta: String) {
        panel = Panel.Viewer(shot, title, meta, back = panel)
    }

    fun closeViewer(v: Panel.Viewer) {
        panel = v.back
    }

    fun setGrid(v: Boolean) = commit(RollEngine.setGrid(state, v))
    fun setStamp(v: Boolean) = commit(RollEngine.setStamp(state, v))

    // ── 디버그 전용(설정 화면의 "프로토타입 테스트") ──

    /** 남은 장수를 가짜 사진으로 채워 롤을 끝낸다(현상 흐름 테스트용) */
    fun debugFinishRoll() {
        val roll = state.roll ?: return
        scope.launch {
            repeat(roll.remaining) { i ->
                val saved = withContext(Dispatchers.IO) { DebugPhotos.make(appContext, roll.film, roll.shots.size + i) }
                onPhotoSaved(saved, Mode.FILM, roll.film)
            }
        }
        panel = null
    }

    /** 앱 데이터를 지운 것과 같은 상태로 되돌린다(롤 잠금 해제, 사진 목록 초기화) */
    fun debugWipe() {
        commit(AppState())
        File(appContext.filesDir, "rolls").deleteRecursively()
        pendingFilm = null
        panel = null
        showToast("데이터를 지웠어요 · 롤 잠금이 풀렸어요")
    }
}
