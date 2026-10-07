# phone_film

필름 카메라 색감(3D LUT)을 **카메라 프리뷰와 촬영본에 똑같이** 입히는 안드로이드 카메라 앱입니다.
실제 필름처럼 한 롤(24/36장)을 다 찍을 때까지 필름을 바꿀 수 없고, 사진은 롤이 끝나야 "현상"되어 보입니다.

## 핵심 기능

- **필름 모드**
  - 필름(프리셋) 1개와 24장 또는 36장을 장전하면, 다 찍을 때까지 다른 필름으로 바꿀 수 없습니다.
  - 남은 장수는 프레임 카운터 다이얼에 표시됩니다.
  - 촬영 직후에도 사진은 보이지 않습니다(앱 전용 폴더에만 저장). 필름 모드 촬영본에는 필름 그레인이 들어갑니다.
  - 마지막 장을 찍으면 "현상"되어 사진이 갤러리로 내보내지고, 잠금이 풀립니다.
- **단일 촬영 모드**
  - 잠금 없이 필름을 자유롭게 바꾸고, 찍은 사진은 바로 갤러리에 저장되며 바로 볼 수 있습니다.
  - 진행 중인 필름 롤은 멈춘 채 그대로 유지됩니다.
- **영구 저장**: 앱을 강제 종료하거나 재부팅해도 남은 장수가 유지됩니다. 앱 데이터를 지우거나 재설치하면 롤은 초기화됩니다(의도한 정책).
- **디자인**: 상단 브러시드 알루미늄 플레이트, 가운데 3:4 뷰파인더, 하단 페블 가죽 플레이트. 어느 버튼을 눌러도 이 레이아웃은 바뀌지 않고, 필름 서랍은 오버레이로, 사진·설정·현상 화면은 가운데 뷰파인더 안에서만 열립니다.

## 구성

```
FilmCamera/                  안드로이드 앱 (Kotlin + Jetpack Compose + CameraX + OpenGL ES 3.0)
  app/src/main/java/com/example/filmcamera/
    MainActivity.kt          카메라 세션, 셔터, 촬영 흐름
    AppController.kt         앱 상태(롤/모드/설정), 패널, 갤러리 내보내기 재개
    LutRenderer.kt           프리뷰에 3D LUT를 적용하는 GL 렌더러
    LutApplier.kt            촬영본에 LUT 적용(CPU, 프리뷰와 같은 33^3 삼선형 보간)
    Grain.kt                 필름 그레인(위치 기반 해시 노이즈)
    PhotoPipeline.kt         촬영본 처리·저장(갤러리 / 앱 전용 폴더)
    RollExporter.kt          현상: 롤 사진을 갤러리로 내보내기(전부 성공해야 완료)
    data/                    Film/Roll 모델, 롤 잠금 규칙(RollEngine), 상태 저장(StateStore)
    ui/                      Compose 화면(상단/하단 플레이트, 뷰파인더, 서랍, 패널, 질감 효과)
  app/src/main/assets/luts/  필름 LUT(.cube)
tools/extract_lut.py         보정 전/후 사진 쌍에서 33^3 .cube LUT를 추출하는 스크립트
docs/design-handoff/         디자인 핸드오프(HTML 프로토타입과 명세)
docs/ios-blueprint/          iOS(HIG 기준) 설계 청사진 — 설계 문서이며 구현은 없습니다
local/                       개인 작업물(보정 전/후 사진, LUT 추출 산출물). git에는 올라가지 않습니다
```

## 빌드와 실행

요구 사항: Android Studio(번들 JBR 사용), Android SDK 36, minSdk 29(Android 10) 이상 기기.

```bash
cd FilmCamera
# 시스템 JDK가 너무 새로우면(예: 26) AGP가 실패하므로 Android Studio의 JBR을 쓰세요
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug        # 디버그 APK
./gradlew :app:testDebugUnitTest    # 단위 테스트
```

Android Studio에서 `FilmCamera` 폴더를 열면 `local.properties`(SDK 경로)가 자동으로 만들어집니다. 이 파일은 저장소에 포함되지 않습니다.

**디버그 빌드에서만** 설정 화면에 "프로토타입 테스트" 버튼이 보입니다.

- 남은 장 모두 찍기: 남은 장수를 가짜 사진으로 채워 롤 종료(현상) 흐름을 바로 시험합니다.
- 앱 데이터 지우기: 롤과 사진 목록을 초기화합니다.

## 필름(LUT) 만들기

프리셋은 같은 장면의 보정 전/후 사진 한 쌍(여러 장면을 합칠 수도 있음)에서 3D LUT를 뽑아 만듭니다. 앱 사용자가 사진을 입력하는 기능은 없고, 개발 단계의 오프라인 작업입니다.

```bash
pip install numpy pillow scipy
python tools/extract_lut.py my_film.cube before1.jpg after1.jpg [before2.jpg after2.jpg ...]
```

- 각 before/after 쌍은 같은 장면, 같은 구도(크롭하지 않은)여야 합니다.
- 출력되는 "검증 오차"는 학습에서 뺀 영역(각 쌍의 아래 30%)의 오차이고, 학습 영역 오차보다 높게 나오는 것이 정상입니다.
- 결과 `.cube`를 `FilmCamera/app/src/main/assets/luts/`에 `tungsten.cube`, `gold.cube`, `forest.cube`로 넣으면 앱에 반영됩니다.
- 사진 한 쌍으로는 그 장면에 없는 색(피부톤, 빨강, 보라, 실내 조명)을 추정할 수밖에 없으므로, 필름마다 서로 다른 장면 2~4쌍을 합치는 것을 권장합니다.

## 알려진 한계

- **실제 폰에서 검증하지 않았습니다.** 모든 확인은 에뮬레이터에서 했습니다. 카메라 회전(똑바로 나오는지), 프리뷰 성능, 12MP 촬영 처리 시간, 실제 색감은 폰에서 확인이 필요합니다.
- **필름 3종의 LUT는 임시 파일**입니다(`tungsten`, `gold`, `forest`가 모두 같은 테스트 LUT). 필름을 바꿔도 색이 거의 같습니다.
- 에뮬레이터에서는 질감(가죽, 메탈)을 스크린샷으로만 확인했습니다.
- "NO FILM" 오버레이의 배경 블러는 구현하지 않았습니다(카메라 프리뷰가 `SurfaceView`라 그 뒤를 블러 처리할 수 없어, 어두운 반투명 막만 씁니다).
- 갤러리에서 사진 삭제·공유 기능은 없습니다.

## 기술 스택

Kotlin 2.2(AGP 9.0.1 내장), Jetpack Compose(BOM 2025.09.01), CameraX 1.6.1, OpenGL ES 3.0(`sampler3D`로 33^3 LUT 샘플링), Gradle 9.1.0. 폰트: Figtree, Caprasimo(Google Fonts, OFL).
