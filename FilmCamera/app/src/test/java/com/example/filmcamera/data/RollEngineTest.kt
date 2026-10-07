package com.example.filmcamera.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RollEngineTest {

    private fun shot(film: Film, i: Int) = Shot(film, "rolls/pending/$i.jpg", 1000L + i)

    @Test
    fun lockOnlyInFilmModeWithRoll() {
        var s = AppState(mode = Mode.FILM)
        assertFalse("롤 없으면 잠금 없음", RollEngine.isLocked(s))
        s = RollEngine.loadRoll(s, Film.GOLD, 24, 1)
        assertTrue("롤 장전 후 필름 모드는 잠금", RollEngine.isLocked(s))
        s = RollEngine.setMode(s, Mode.SINGLE)
        assertFalse("단일 모드에서는 롤이 있어도 자유", RollEngine.isLocked(s))
        s = RollEngine.setMode(s, Mode.FILM)
        assertTrue("다시 필름 모드로 오면 잠금 유지", RollEngine.isLocked(s))
    }

    @Test
    fun cannotLoadSecondRollWhileLocked() {
        val s = RollEngine.loadRoll(AppState(), Film.GOLD, 24, 1)
        try { RollEngine.loadRoll(s, Film.FOREST, 36, 2); fail() } catch (_: IllegalStateException) {}
    }

    @Test
    fun rejectsInvalidRollLength() {
        try { RollEngine.loadRoll(AppState(), Film.GOLD, 30, 1); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun countsDownAndFinishesAtLastShot() {
        var s = RollEngine.loadRoll(AppState(), Film.FOREST, 24, 1)
        for (i in 1..23) {
            val (n, finished) = RollEngine.recordFilmShot(s, shot(Film.FOREST, i), 10)
            s = n
            assertFalse(finished)
            assertEquals(24 - i, s.roll!!.remaining)
        }
        val (done, finished) = RollEngine.recordFilmShot(s, shot(Film.FOREST, 24), 99)
        assertTrue(finished)
        assertNull("롤 종료 시 잠금 해제", done.roll)
        assertFalse(RollEngine.isLocked(done))
        assertEquals(1, done.devRolls.size)
        assertEquals(24, done.devRolls[0].shots.size)
        assertFalse("내보내기 전", done.devRolls[0].exported)
        assertEquals(1, RollEngine.pendingExports(done).size)
    }

    @Test
    fun singleShotsDoNotTouchRoll() {
        var s = RollEngine.loadRoll(AppState(), Film.GOLD, 36, 1)
        s = RollEngine.setMode(s, Mode.SINGLE)
        s = RollEngine.selectSingleFilm(s, Film.TUNGSTEN)
        s = RollEngine.recordSingleShot(s, shot(Film.TUNGSTEN, 1))
        assertEquals(36, s.roll!!.remaining)
        assertEquals(Film.GOLD, s.roll!!.film)
        assertEquals(1, s.singleShots.size)
    }

    @Test
    fun markExportedFillsUris() {
        var s = RollEngine.loadRoll(AppState(), Film.GOLD, 24, 5)
        for (i in 1..24) s = RollEngine.recordFilmShot(s, shot(Film.GOLD, i), 50).first
        s = RollEngine.markExported(s, 5, (1..24).map { "content://media/$it" })
        assertTrue(s.devRolls[0].exported)
        assertEquals("content://media/7", s.devRolls[0].shots[6].uri)
        assertTrue(RollEngine.pendingExports(s).isEmpty())
    }

    @Test
    fun storeRoundTripSurvivesRestart() {
        val f = File.createTempFile("state", ".json")
        val store = StateStore(f)
        var s = RollEngine.loadRoll(AppState(grid = false), Film.TUNGSTEN, 36, 7)
        for (i in 1..5) s = RollEngine.recordFilmShot(s, shot(Film.TUNGSTEN, i), 8).first
        s = RollEngine.recordSingleShot(s, shot(Film.GOLD, 99).copy(uri = "content://x/1"))
        store.save(s)
        val reloaded = StateStore(f).load() // 새 인스턴스 = 앱 재시작
        assertEquals(s, reloaded)
        assertEquals(31, reloaded.roll!!.remaining)
        f.delete()
    }

    @Test
    fun storeSurvivesFinishedRollWithPendingExport() {
        val f = File.createTempFile("state", ".json")
        var s = RollEngine.loadRoll(AppState(), Film.FOREST, 24, 1)
        for (i in 1..24) s = RollEngine.recordFilmShot(s, shot(Film.FOREST, i), 2).first
        StateStore(f).save(s)
        val reloaded = StateStore(f).load()
        assertNull(reloaded.roll)
        assertEquals("재시작 후에도 내보내기 재시도 대상", 1, RollEngine.pendingExports(reloaded).size)
        f.delete()
    }

    @Test
    fun corruptFileFallsBackToDefault() {
        val f = File.createTempFile("state", ".json")
        f.writeText("{ not json")
        assertEquals(AppState(), StateStore(f).load())
        assertNotNull(f)
        f.delete()
    }

    @Test
    fun missingFileGivesDefault() {
        assertEquals(AppState(), StateStore(File("does/not/exist.json")).load())
    }
}
