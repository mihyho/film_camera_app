package com.example.filmcamera.data

/** 롤 잠금 규칙. 상태를 직접 바꾸지 않고 새 AppState를 돌려주는 순수 함수라 JVM에서 테스트할 수 있다. */
object RollEngine {

    val ROLL_LENGTHS = listOf(24, 36)

    /** 필름 모드에서 롤이 장전돼 있으면 필름을 바꿀 수 없다. 단일 모드는 항상 자유. */
    fun isLocked(s: AppState): Boolean = s.mode == Mode.FILM && s.roll != null

    fun setMode(s: AppState, mode: Mode) = s.copy(mode = mode)

    fun loadRoll(s: AppState, film: Film, total: Int, now: Long): AppState {
        check(s.roll == null) { "이미 장전된 롤이 있습니다" }
        require(total in ROLL_LENGTHS) { "롤 길이는 24 또는 36장" }
        return s.copy(roll = Roll(film, total, emptyList(), now))
    }

    /** 단일 모드의 필름 선택. 진행 중인 롤에는 영향이 없다. */
    fun selectSingleFilm(s: AppState, film: Film) = s.copy(singleFilm = film)

    /**
     * 필름 모드 촬영 기록. 마지막 장이면 롤을 현상 목록으로 옮기고 잠금을 푼다.
     * @return 새 상태와 "이번 장으로 롤이 끝났는지"
     */
    fun recordFilmShot(s: AppState, shot: Shot, now: Long): Pair<AppState, Boolean> {
        val roll = checkNotNull(s.roll) { "장전된 롤이 없습니다" }
        val shots = roll.shots + shot
        if (shots.size < roll.total) return s.copy(roll = roll.copy(shots = shots)) to false
        val developed = DevelopedRoll(roll.film, roll.total, shots, roll.start, now, exported = false)
        return s.copy(roll = null, devRolls = listOf(developed) + s.devRolls) to true
    }

    fun recordSingleShot(s: AppState, shot: Shot) = s.copy(singleShots = listOf(shot) + s.singleShots)

    /** 갤러리 내보내기가 끝난 롤의 사진 uri를 채우고 exported 표시 */
    fun markExported(s: AppState, rollStart: Long, uris: List<String>): AppState = s.copy(
        devRolls = s.devRolls.map { r ->
            if (r.start != rollStart) r
            else r.copy(shots = r.shots.mapIndexed { i, sh -> sh.copy(uri = uris[i]) }, exported = true)
        },
    )

    fun pendingExports(s: AppState): List<DevelopedRoll> = s.devRolls.filter { !it.exported }

    fun setGrid(s: AppState, v: Boolean) = s.copy(grid = v)
    fun setStamp(s: AppState, v: Boolean) = s.copy(stamp = v)
}
