"""
보정 전/후 사진 한 쌍에서 3D LUT(.cube)를 추출하는 스크립트

사용법:
    pip install numpy pillow scipy
    python tools/extract_lut.py my_film.cube before1.jpg after1.jpg [before2.jpg after2.jpg ...]

각 before / after 쌍은 같은 장면, 같은 구도(크롭 X)여야 합니다.
장면이 다른 쌍을 여러 개 주면 하나의 LUT로 합칩니다(권장: 하늘/피부/중립색/그림자 포함).
결과: my_film.cube (LUT), my_film_check.jpg (첫 쌍 기준 전 / LUT 적용 / 후 비교)
검증: 각 쌍의 아래 30%를 학습에서 빼 두고 그 영역의 오차를 따로 출력합니다.
"""
import sys
sys.stdout.reconfigure(encoding="utf-8")
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, map_coordinates

N = 33          # LUT 격자 크기 (17 / 33 / 65)
MAX_SIDE = 1200 # 분석용 축소 크기
SIGMA = 1.0     # 격자 평활화 강도 (클수록 부드럽고 덜 정확)
REG = 2e-5      # 데이터가 적은 색 영역은 원본 유지 쪽으로 당기는 정도


def load_pair(before_path, after_path):
    b = Image.open(before_path).convert("RGB")
    scale = MAX_SIDE / max(b.size)
    if scale < 1:
        b = b.resize((int(b.width * scale), int(b.height * scale)), Image.LANCZOS)
    a = Image.open(after_path).convert("RGB").resize(b.size, Image.LANCZOS)
    f = lambda im: np.asarray(im, dtype=np.float32).reshape(-1, 3) / 255.0
    return b, a, f(b), f(a)


def poly_feats(x):
    r, g, b = x[:, 0], x[:, 1], x[:, 2]
    return np.stack([np.ones_like(r), r, g, b, r * r, g * g, b * b, r * g, g * b, r * b], axis=1)


def fit_poly(src, dst, ridge=1e-3):
    """전역 2차 다항식 색 변환(기본 모델). 데이터가 없는 영역에서도 부드럽게 외삽된다."""
    F = poly_feats(src).astype(np.float64)
    A = F.T @ F + ridge * len(src) * np.eye(F.shape[1])
    return np.linalg.solve(A, F.T @ dst)  # [10, 3]


def apply_poly(x, W):
    return poly_feats(x) @ W


def build_lut(src, dst):
    W = fit_poly(src, dst)
    delta = dst - apply_poly(src, W)  # 다항식으로 설명 안 되는 잔차만 LUT 격자로
    pos = np.clip(src, 0, 1) * (N - 1)
    i0 = np.minimum(np.floor(pos).astype(int), N - 2)
    frac = pos - i0

    sum_w = np.zeros(N ** 3, dtype=np.float64)
    sum_d = np.zeros((N ** 3, 3), dtype=np.float64)

    # 각 픽셀을 주변 8개 격자점에 트라이리니어로 분배(splatting)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (
                    (frac[:, 0] if dr else 1 - frac[:, 0])
                    * (frac[:, 1] if dg else 1 - frac[:, 1])
                    * (frac[:, 2] if db else 1 - frac[:, 2])
                )
                idx = ((i0[:, 0] + dr) * N + (i0[:, 1] + dg)) * N + (i0[:, 2] + db)
                sum_w += np.bincount(idx, weights=w, minlength=N ** 3)
                for c in range(3):
                    sum_d[:, c] += np.bincount(idx, weights=w * delta[:, c], minlength=N ** 3)

    sum_w = sum_w.reshape(N, N, N)
    sum_d = sum_d.reshape(N, N, N, 3)

    # 정규화 컨볼루션: 빈 격자점은 이웃 데이터로 채우고, 데이터가 없으면 delta=0(원본 유지)
    reg = REG * len(src)
    w_s = gaussian_filter(sum_w, SIGMA, mode="nearest")
    lut_delta = np.zeros_like(sum_d)
    for c in range(3):
        d_s = gaussian_filter(sum_d[..., c], SIGMA, mode="nearest")
        lut_delta[..., c] = d_s / (w_s + reg)

    grid = np.stack(np.meshgrid(*[np.linspace(0, 1, N)] * 3, indexing="ij"), axis=-1)
    base = apply_poly(grid.reshape(-1, 3), W).reshape(N, N, N, 3)
    return np.clip(base + lut_delta, 0, 1).astype(np.float32)  # [r, g, b, 3]


def apply_lut(img_rgb01, lut):
    pos = np.clip(img_rgb01, 0, 1) * (N - 1)
    coords = [pos[:, c] for c in range(3)]
    out = np.stack(
        [map_coordinates(lut[..., c], coords, order=1, mode="nearest") for c in range(3)],
        axis=-1,
    )
    return np.clip(out, 0, 1)


def write_cube(path, lut, title="Film"):
    with open(path, "w") as f:
        f.write(f'TITLE "{title}"\nLUT_3D_SIZE {N}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
        # .cube 순서: R이 가장 빨리 변함 -> [b][g][r]
        for b in range(N):
            for g in range(N):
                for r in range(N):
                    v = lut[r, g, b]
                    f.write(f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")


def load_pairs(paths, holdout=0.3):
    tr_s, tr_d, te_s, te_d, first = [], [], [], [], None
    for bp, ap in zip(paths[0::2], paths[1::2]):
        b_img, a_img, src, dst = load_pair(bp, ap)
        if first is None:
            first = (b_img, a_img, src, dst)
        w, h = b_img.size
        cut = int(h * (1 - holdout)) * w
        tr_s.append(src[:cut]); tr_d.append(dst[:cut])
        te_s.append(src[cut:]); te_d.append(dst[cut:])
    return first, [np.concatenate(x) for x in (tr_s, tr_d, te_s, te_d)]


def main():
    if len(sys.argv) < 4 or len(sys.argv) % 2 != 0:
        print(__doc__)
        sys.exit(1)
    out_p, pair_paths = sys.argv[1], sys.argv[2:]

    (b_img, a_img, src0, dst0), (tr_s, tr_d, te_s, te_d) = load_pairs(pair_paths)

    # 1) 검증: 학습에서 뺀 영역으로 정확도 측정
    lut_v = build_lut(tr_s, tr_d)
    e = lambda p, d: np.abs(p - d).mean() * 255
    print(f"[검증] 학습 영역 오차 {e(apply_lut(tr_s, lut_v), tr_d):.2f} / "
          f"보지 않은 영역 오차 {e(apply_lut(te_s, lut_v), te_d):.2f} (보정 전 {e(te_s, te_d):.2f})")

    # 2) 최종 LUT는 전체 데이터로
    all_s, all_d = np.concatenate([tr_s, te_s]), np.concatenate([tr_d, te_d])
    lut = build_lut(all_s, all_d)
    write_cube(out_p, lut, title=out_p.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0])
    print(f"전체 평균 오차(0~255): 보정 전 {e(all_s, all_d):.2f} -> LUT 적용 후 {e(apply_lut(all_s, lut), all_d):.2f}")
    g = np.linspace(0, 1, 5)[:, None].repeat(3, 1)
    print("회색 램프:", " | ".join("%.2f→%.2f,%.2f,%.2f" % (i[0], *o) for i, o in zip(g, apply_lut(g, lut))))
    print(f"저장: {out_p}")

    pred = apply_lut(src0, lut)
    w, h = b_img.size
    pred_img = Image.fromarray((pred.reshape(h, w, 3) * 255).astype(np.uint8))
    sheet = Image.new("RGB", (w * 3, h))
    for i, im in enumerate([b_img, pred_img, a_img]):
        sheet.paste(im, (w * i, 0))
    check = out_p.rsplit(".", 1)[0] + "_check.jpg"
    sheet.save(check, quality=90)
    print(f"비교 이미지: {check}  (왼쪽 전 / 가운데 LUT 적용 / 오른쪽 후)")


if __name__ == "__main__":
    main()
