"""
필름 3종의 LUT를 한 번에 만든다.

사진 쌍은 필름별 폴더에 `<이름>_before.<확장자>` / `<이름>_after.<확장자>` 로 넣는다:

    local/samples/gold/seoul_before.webp      local/samples/gold/seoul_after.webp
    local/samples/gold/street_before.jpg      local/samples/gold/street_after.jpg
    local/samples/tungsten/...                local/samples/forest/...

사용법:
    python tools/build_luts.py                 # 사진 쌍이 있는 필름만 만든다(없는 필름은 건너뛰고 기존 LUT 유지)
    python tools/build_luts.py gold            # 특정 필름만

결과: FilmCamera/app/src/main/assets/luts/<필름>.cube
      (확인용 비교 이미지는 local/luts/<필름>_check.jpg)
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_lut  # noqa: E402

FILMS = ["tungsten", "gold", "forest"]
# 필름별로 색온도(K)를 고정하고 싶을 때만 적는다. 적은 필름은 사진에서 색온도를 추정하지 않고 이 값으로 맞춘다.
# 텅스텐 필름 = 3300K 설정: 낮 햇빛(약 6500K) 장면이 파랗고 차갑게 보인다.
FILM_KELVIN = {"tungsten": 3300}
SAMPLES = ROOT / "local" / "samples"
ASSETS = ROOT / "FilmCamera" / "app" / "src" / "main" / "assets" / "luts"
CHECKS = ROOT / "local" / "luts"


def find_pairs(folder: Path):
    """`X_before.*` 와 `X_after.*` 가 둘 다 있는 쌍만 (before, after) 로 돌려준다."""
    pairs, problems = [], []
    for b in sorted(folder.glob("*_before.*")):
        stem = b.name.rsplit("_before.", 1)[0]
        afters = sorted(folder.glob(f"{stem}_after.*"))
        if afters:
            pairs.append((b, afters[0]))
        else:
            problems.append(f"{b.name}: 짝이 되는 {stem}_after.* 가 없습니다")
    for a in sorted(folder.glob("*_after.*")):
        stem = a.name.rsplit("_after.", 1)[0]
        if not list(folder.glob(f"{stem}_before.*")):
            problems.append(f"{a.name}: 짝이 되는 {stem}_before.* 가 없습니다")
    return pairs, problems


def main():
    targets = sys.argv[1:] or FILMS
    for t in targets:
        if t not in FILMS:
            sys.exit(f"알 수 없는 필름: {t} (가능: {', '.join(FILMS)})")
    CHECKS.mkdir(parents=True, exist_ok=True)

    for film in targets:
        folder = SAMPLES / film
        print(f"\n=== {film} ===")
        if not folder.is_dir():
            print(f"건너뜀: {folder} 폴더가 없습니다 (기존 LUT 유지)")
            continue
        pairs, problems = find_pairs(folder)
        for p in problems:
            print("경고:", p)
        if not pairs:
            print("건너뜀: 사진 쌍이 없습니다 (기존 LUT 유지)")
            continue
        print(f"사진 쌍 {len(pairs)}개: " + ", ".join(b.name.rsplit('_before.', 1)[0] for b, _ in pairs))
        if len(pairs) < 2:
            print("참고: 한 장면만으로는 그 장면에 없는 색(피부톤, 빨강, 보라 등)을 추정하게 됩니다. 2~4쌍을 권장합니다.")
        flat = [str(x) for pair in pairs for x in pair]
        extract_lut.run(
            str(ASSETS / f"{film}.cube"), flat,
            check_path=str(CHECKS / f"{film}_check.jpg"), title=film, kelvin=FILM_KELVIN.get(film),
        )


if __name__ == "__main__":
    main()
