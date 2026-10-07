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
import io
import sys
sys.stdout.reconfigure(encoding="utf-8")
import numpy as np
from PIL import Image, ImageCms
from scipy.ndimage import gaussian_filter, map_coordinates

N = 33          # LUT 격자 크기 (17 / 33 / 65)
MAX_SIDE = 1200 # 분석용 축소 크기
SIGMA = 1.0     # 격자 평활화 강도 (클수록 부드럽고 덜 정확)
REG = 2e-5      # 데이터가 적은 색 영역은 원본 유지 쪽으로 당기는 정도
GATE_SIGMA = 4.5   # 게이트 밀도를 퍼뜨리는 정도(격자 단위). 작으면 경계가 가팔라져 하늘 같은 평탄한 곳의 잡음이 증폭된다
GATE_REF = 1.0e-5  # 격자점당 데이터 비율이 이 값 이상이면 효과 100%, 0이면 원본 그대로
MAX_GAIN = 2.0     # LUT 국소 기울기 상한: 입력의 작은 차이(센서 노이즈)를 이 배율 이상 벌리지 않는다. 1.0 = 원본과 같은 기울기
LAST_GRADE = None
LAST_GATE_COVERAGE = 0.0  # 마지막 build_lut에서 효과가 절반 이상 적용된 격자 비율(진단용)


def _icc_to_srgb(im, icc):
    """ICC 프로파일이 sRGB가 아니면(예: Display P3) sRGB로 변환한다. 앱의 카메라 프레임은 sRGB 기준이다."""
    if not icc:
        return im  # 프로파일 없음 -> sRGB로 간주
    try:
        src = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        if "srgb" in ImageCms.getProfileDescription(src).strip().lower():
            return im
        intent = getattr(getattr(ImageCms, "Intent", ImageCms), "RELATIVE_COLORIMETRIC", None)
        if intent is None:
            intent = ImageCms.INTENT_RELATIVE_COLORIMETRIC
        return ImageCms.profileToProfile(im, src, ImageCms.createProfile("sRGB"), renderingIntent=intent, outputMode="RGB")
    except Exception as e:  # 변환 실패 시 원본 값 사용(경고)
        print(f"경고: 색 프로파일 변환 실패, 그대로 사용합니다 ({e})")
        return im


def load_pair(before_path, after_path):
    b0 = Image.open(before_path)
    b_icc = b0.info.get("icc_profile")
    scale = MAX_SIDE / max(b0.size)
    b = b0.convert("RGB")
    if scale < 1:
        b = b.resize((int(b.width * scale), int(b.height * scale)), Image.LANCZOS)
    b = _icc_to_srgb(b, b_icc)
    a0 = Image.open(after_path)
    a_icc = a0.info.get("icc_profile")
    a = _icc_to_srgb(a0.convert("RGB").resize(b.size, Image.LANCZOS), a_icc)
    f = lambda im: np.asarray(im, dtype=np.float32).reshape(-1, 3) / 255.0
    return b, a, f(b), f(a)


# ───────────── 보정 규칙(전역 모델) ─────────────
# 사진에 나온 색만 외우면 처음 보는 색에서 엉뚱해진다. 그래서 먼저 "보정이 색을 어떻게 바꾸는가"를 규칙으로 배운다.
#  1) 색온도/틴트: 모든 색에 같은 방향으로 걸리는 전역 이동(파랑<->노랑, 초록<->자홍). 선형광 RGB 채널 이득.
#  2) 밝기별 곡선: 그림자 들뜸/하이라이트 눌림(대비) -> Oklab 밝기 L의 이동
#  3) 밝기별 채도/색상/틴트: 색이 얼마나 바래는지, 어느 쪽으로 도는지, 그림자/하이라이트에 입히는 색 -> a,b 평면의 복소수 변환 z' = m*z + t
# 규칙은 색이 아니라 밝기에만 의존하므로 모든 색에 정의된다. 데이터가 없는 밝기는 원본 쪽으로 서서히 되돌린다.

NB = 20           # 밝기 구간 수
WB_LIMIT = (0.5, 2.0)


def _to_lin(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def _to_srgb(l):
    l = np.clip(l, 0, 1)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1 / 2.4) - 0.055)


def to_oklab(rgb):
    return lin_to_oklab(_to_lin(rgb))


def lin_to_oklab(l):
    """선형광 RGB(0..1을 넘어도 됨) -> Oklab. 색온도 이득으로 1.0을 넘은 값을 자르지 않고 끝까지 가져가기 위해 분리했다."""
    r, g, b = l[..., 0], l[..., 1], l[..., 2]
    L_ = np.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    M_ = np.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    S_ = np.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return np.stack([
        0.2104542553 * L_ + 0.7936177850 * M_ - 0.0040720468 * S_,
        1.9779984951 * L_ - 2.4285922050 * M_ + 0.4505937099 * S_,
        0.0259040371 * L_ + 0.7827717662 * M_ - 0.8086757660 * S_,
    ], axis=-1)


def from_oklab(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    lin = np.stack([
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    ], axis=-1)
    return _to_srgb(lin)


NEUTRAL_CHROMA = 0.035   # 원본에서 이 값보다 채도가 낮은 픽셀을 "원래 회색"으로 본다(Oklab 채도)
WB_ZONES = [(0.30, 0.50), (0.50, 0.70), (0.70, 0.92)]  # 밝기 구간(그림자 쪽, 중간, 밝은 쪽). 가장 어두운 쪽은 그림자 들뜸이 섞여 제외
WB_ZONE_MIN = 500         # 구간별 최소 중립색 픽셀 수
WB_CONSISTENT = 0.12      # 구간별 로그 이득 벡터가 이 값 안에서 일치해야 "전역 색온도 이동"으로 인정
LAST_WB_INFO = ""


def fit_white_balance(src, dst):
    """전역 색온도/틴트: **원래 회색이던 픽셀**(중립색)이 보정 후 어느 색이 되었는지, 밝기 구간별로 잰다.
    - 포화된 색(예: 짙은 남색 밤하늘 -> 회색)의 변화는 색온도가 아니라 채도 변화라서 제외한다.
    - 구간마다 이동 방향이 다르면(그림자는 차갑게, 밝은 곳은 따뜻하게 같은 스플릿 톤) 전역 이동이 아니므로 0으로 두고
      밝기별 틴트(fit_grade)가 담당한다. 구간이 하나뿐이면 일관성을 확인할 수 없으므로 절반만 반영한다.
    전체 밝기는 밝기 곡선이 담당하므로 이득의 기하평균은 1로 맞춘다."""
    global LAST_WB_INFO
    lab = to_oklab(src)
    chroma = np.hypot(lab[:, 1], lab[:, 2])
    sl, dl = _to_lin(src), _to_lin(dst)
    base = (chroma < NEUTRAL_CHROMA) & (dl.min(1) > 0.02) & (dl.max(1) < 0.95)
    vecs, counts = [], []
    for lo, hi in WB_ZONES:
        m = base & (lab[:, 0] >= lo) & (lab[:, 0] < hi)
        if m.sum() >= WB_ZONE_MIN:
            lr = np.median(np.log(dl[m] / np.maximum(sl[m], 1e-4)), axis=0)
            vecs.append(lr - lr.mean()); counts.append(int(m.sum()))
    if not vecs:
        LAST_WB_INFO = "중립색 부족 -> 색온도 이동 0"
        return np.ones(3)
    spread = max(np.abs(v - vecs[0]).max() for v in vecs)
    if len(vecs) >= 2 and spread > WB_CONSISTENT:
        LAST_WB_INFO = f"구간별 방향이 달라(스플릿 톤, 편차 {spread:.2f}) 전역 이동 0, 밝기별 틴트가 담당"
        return np.ones(3)
    w = np.array(counts, float)
    lr = sum(v * wi for v, wi in zip(vecs, w)) / w.sum()
    if len(vecs) == 1:
        lr = lr * 0.5
        LAST_WB_INFO = f"구간 1개({counts[0]}픽셀)라 일관성 확인 불가 -> 절반만 반영"
    else:
        LAST_WB_INFO = f"{len(vecs)}개 구간이 일관됨(편차 {spread:.2f}, {sum(counts)}픽셀)"
    return np.clip(np.exp(lr), *WB_LIMIT)


def kelvin_to_linear_rgb(kelvin):
    """색온도(K) 조명의 선형광 sRGB 색(밝기 1로 정규화). 흑체 복사 곡선의 CIE xy 근사(Kim et al., 1667~25000K)."""
    T = float(kelvin)
    if T <= 4000:
        x = -0.2661239e9 / T ** 3 - 0.2343589e6 / T ** 2 + 0.8776956e3 / T + 0.179910
    else:
        x = -3.0258469e9 / T ** 3 + 2.1070379e6 / T ** 2 + 0.2226347e3 / T + 0.240390
    if T <= 2222:
        y = -1.1063814 * x ** 3 - 1.34811020 * x ** 2 + 2.18555832 * x - 0.20219683
    elif T <= 4000:
        y = -0.9549476 * x ** 3 - 1.37418593 * x ** 2 + 2.09137015 * x - 0.16748867
    else:
        y = 3.0817580 * x ** 3 - 5.87338670 * x ** 2 + 3.75112997 * x - 0.37001483
    X, Z = x / y, (1 - x - y) / y
    rgb = np.array([
        3.2404542 * X - 1.5371385 - 0.4985314 * Z,
        -0.9692660 * X + 1.8760108 + 0.0415560 * Z,
        0.0556434 * X - 0.2040259 + 1.0572252 * Z,
    ])
    return np.clip(rgb, 1e-4, None)


def kelvin_white_balance(kelvin):
    """화이트밸런스를 kelvin K로 맞춘 것: 그 색온도의 조명을 중립(회색)으로 만들도록 모든 색에 (1/조명색)을 곱한다.
    sRGB 기준 하얀색(약 6500K)은 3300K 설정에서 파랗게 보인다. 밝기는 밝기 곡선이 담당하므로 기하평균을 1로 맞춘다."""
    g = 1.0 / kelvin_to_linear_rgb(kelvin)
    return g / np.exp(np.log(g).mean())


def apply_white_balance(rgb, g):
    return _to_srgb(_to_lin(rgb) * g)

# ───────────── 파라미터로 만드는 룩(사진 쌍을 쓰지 않음) ─────────────
# 보정 사진에서 학습하면 그 사진의 바램/낮은 대비까지 따라가서 색이 죽을 수 있다. 그래서 원하는 룩을 직접 숫자로 정한다.
# 순서: 색온도(화이트밸런스) -> RGB 원색 채도 -> 대비 -> 하이라이트 밝기. 모두 Oklab 공간에서 처리한다.
PRIMARY_HUES = {"red": 29.0, "green": 142.0, "blue": 264.0}  # Oklab 색상각(도)
HUE_WIDTH = 32.0           # 원색 주변으로 효과가 퍼지는 폭(도, 가우시안 표준편차)
CONTRAST_PIVOT = 0.60      # 대비 기준 밝기(Oklab L). sRGB 중간 회색이 약 0.6
HIGHLIGHT_START = (0.50, 0.95)  # 하이라이트로 보는 밝기 구간


def _hue_weight(h_deg):
    """빨강/초록/파랑 원색에 가까울수록 1에 가까운 가중치(각 원색마다 가우시안, 최댓값)."""
    w = np.zeros_like(h_deg)
    for c in PRIMARY_HUES.values():
        d = np.abs((h_deg - c + 180.0) % 360.0 - 180.0)
        w = np.maximum(w, np.exp(-0.5 * (d / HUE_WIDTH) ** 2))
    return w


def _smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def _fit_gamut(lab):
    """표현 범위(sRGB)를 벗어난 색은 잘라 내지 않고(색이 뭉개짐) 같은 밝기/색상에서 채도만 줄여 범위 안에 넣는다."""
    out = lab.copy()
    L, a, b = lab[:, 0], lab[:, 1], lab[:, 2]
    def inside(scale):
        lin = _raw_rgb(np.stack([L, a * scale, b * scale], axis=-1))
        return np.all((lin >= -1e-4) & (lin <= 1 + 1e-4), axis=-1)
    ok = inside(np.ones(len(L)))
    if ok.all():
        return out
    lo = np.zeros(len(L)); hi = np.ones(len(L))
    for _ in range(12):  # 이분법: 범위 안에 들어오는 가장 큰 채도 배율
        mid = (lo + hi) / 2
        good = inside(mid)
        lo = np.where(good, mid, lo); hi = np.where(good, hi, mid)
    scale = np.where(ok, 1.0, lo)
    out[:, 1] = a * scale; out[:, 2] = b * scale
    return out


def _raw_rgb(lab):
    """from_oklab 과 같지만 0..1로 자르지 않은 값(범위 판정용)."""
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    lin = np.stack([
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    ], axis=-1)
    return lin  # 선형광 기준에서 0..1 범위면 sRGB도 0..1


def parametric_lut(kelvin=None, sat=0.0, highlights=0.0, contrast=1.0):
    """파라미터로 룩을 만든 LUT.
    kelvin: 화이트밸런스 색온도(K). 낮을수록 낮 햇빛 장면이 파랗게 변한다.
    sat: 빨강/초록/파랑 원색의 채도 증가량(0.25 = +25%, 원색에서 멀수록 효과가 줄어든다).
    highlights: 하이라이트 밝기 증가 세기(0 = 없음). 가장 밝은 곳(L=1)은 그대로 두어 날아가지 않는다.
    contrast: 대비 배율(1 = 그대로, 0.93 = 약간 낮춤). 기준은 중간 밝기라 그림자는 조금 뜨고 밝은 곳은 조금 내려간다."""
    grid = np.stack(np.meshgrid(*[np.linspace(0, 1, N)] * 3, indexing="ij"), axis=-1).reshape(-1, 3)
    lin = _to_lin(grid)
    if kelvin:
        lin = lin * kelvin_white_balance(kelvin)  # 1.0을 넘어도 자르지 않는다(자르면 파랑 채도를 더 올릴 여지가 사라진다)
    lab = lin_to_oklab(lin)
    L, a, b = lab[:, 0], lab[:, 1], lab[:, 2]
    if sat:
        h = np.degrees(np.arctan2(b, a)) % 360.0
        k = 1.0 + sat * _hue_weight(h)
        a, b = a * k, b * k
    L = CONTRAST_PIVOT + (L - CONTRAST_PIVOT) * contrast
    if highlights:
        L = L + highlights * _smoothstep(*HIGHLIGHT_START, L) * (1.0 - L)
    lab = _fit_gamut(np.stack([L, a, b], axis=-1))
    return np.clip(from_oklab(lab), 0, 1).reshape(N, N, N, 3).astype(np.float32)


# ───────────── 색상 범위 보정(후처리) ─────────────
GREEN_HUE = 140.0   # Oklab 색상각: 초록 약 140도, 청록(시안) 약 195도. 각도가 커지는 쪽이 청록이다.


def read_cube(path):
    """.cube(R이 가장 빨리 변함) -> [r, g, b, 3]"""
    rows = [l.split() for l in open(path) if l[0].isdigit() or l[0] == "-"]
    n = round(len(rows) ** (1 / 3))
    return np.array(rows, dtype=np.float32).reshape(n, n, n, 3).transpose(2, 1, 0, 3)


def hue_tweak_lut(lut, center=GREEN_HUE, width=25.0, sat=0.0, hue_shift=0.0, min_chroma=(0.02, 0.06)):
    """LUT의 **출력 색**에서 center 색상 주변(가우시안 폭 width도)만 채도를 sat만큼 높이고 색상을 hue_shift도 돌린다.
    다른 색상은 그대로이고, 채도가 거의 없는 색(회색)도 건드리지 않는다. 표현 범위를 벗어나면 채도를 줄여 넣는다.
    hue_shift > 0 이면 초록이 청록 쪽으로 간다."""
    shape = lut.shape
    lab = to_oklab(lut.reshape(-1, 3).astype(np.float64))
    L, a, b = lab[:, 0], lab[:, 1], lab[:, 2]
    C = np.hypot(a, b)
    h = np.degrees(np.arctan2(b, a)) % 360.0
    d = np.abs((h - center + 180.0) % 360.0 - 180.0)
    w = np.exp(-0.5 * (d / width) ** 2) * _smoothstep(min_chroma[0], min_chroma[1], C)
    C2 = C * (1.0 + sat * w)
    h2 = np.radians(h + hue_shift * w)
    lab2 = _fit_gamut(np.stack([L, C2 * np.cos(h2), C2 * np.sin(h2)], axis=-1))
    return np.clip(from_oklab(lab2), 0, 1).reshape(shape).astype(np.float32)


def kelvin_only_lut(kelvin):
    """색온도만 바꾸는 LUT(대비/채도 유지). parametric_lut 의 특수한 경우."""
    return parametric_lut(kelvin=kelvin)



def _smooth_bins(vals, w, sigma=1.2):
    """구간 축으로 신뢰도 가중 평균. 반환: (평활된 값, 평활된 신뢰도)"""
    k = np.exp(-0.5 * (np.arange(-4, 5) / sigma) ** 2)
    k /= k.sum()
    num = np.convolve(vals * w, k, mode="same")
    den = np.convolve(w, k, mode="same")
    out = np.where(den > 1e-9, num / np.maximum(den, 1e-9), 0.0)
    return out, np.clip(den, 0, 1)


def fit_grade(src, dst, kelvin=None):
    """보정 규칙을 학습해 dict로 돌려준다. kelvin이 있으면 색온도를 그 값으로 고정하고(사진에서 추정하지 않음),
    밝기별 곡선/채도/틴트는 그 색온도를 적용한 뒤의 잔여 변화로 학습한다."""
    global LAST_WB_INFO
    if kelvin is not None:
        g = kelvin_white_balance(kelvin)
        LAST_WB_INFO = f"색온도 {kelvin:.0f}K로 고정(낮 햇빛 6500K 장면은 파랗게 변함), 틴트/색상 이동은 학습하지 않고 밝기 곡선과 채도만 학습"
    else:
        g = fit_white_balance(src, dst)
    s2 = apply_white_balance(src, g)
    lab_s, lab_d = to_oklab(s2), to_oklab(dst)
    Ls = lab_s[:, 0]
    edges = np.linspace(0, 1, NB + 1)
    centers = (edges[:-1] + edges[1:]) / 2
    which = np.clip(np.digitize(Ls, edges) - 1, 0, NB - 1)
    # 구간을 믿는 데 필요한 픽셀 수. 색온도를 고정하는 필름은 소수의 픽셀(예: 야경의 가로등)로 밝은 구간의
    # 채도 규칙을 배우지 않도록 이미지의 1% 이상이 있는 구간만 믿는다.
    n0 = max(2000.0, 0.01 * len(src)) if kelvin is not None else max(300.0, 0.0015 * len(src))
    dL = np.zeros(NB); mr = np.ones(NB); mi = np.zeros(NB); tr = np.zeros(NB); ti = np.zeros(NB); conf = np.zeros(NB)
    zs = lab_s[:, 1] + 1j * lab_s[:, 2]
    zd = lab_d[:, 1] + 1j * lab_d[:, 2]
    for k in range(NB):
        sel = which == k
        n = int(sel.sum())
        if n < 30:
            continue
        conf[k] = 1 - np.exp(-n / n0)
        dL[k] = (lab_d[sel, 0] - Ls[sel]).mean()
        z, zp = zs[sel], zd[sel]
        lam = 0.02 ** 2 * n                       # 채도 변화가 거의 없는 구간은 m=1(원본 채도) 쪽으로 당긴다
        if kelvin is not None:
            # 색온도 고정 필름: 틴트/색상 이동(t, 회전)을 학습하면 야경 데이터에 맞추느라 고정한 색온도를 되돌려 버린다.
            # 그래서 채도 배율(실수 m)만 학습한다. 야경의 남색이 회색이 된 것은 "파랑을 되돌리는 틴트"가 아니라 "채도 감소"로 설명된다.
            m = (np.sum((np.conj(z) * zp).real) + lam) / (np.sum(np.abs(z) ** 2) + lam)
            mr[k] = float(m)
            continue
        zc, zpc = z - z.mean(), zp - zp.mean()
        m = (np.sum(np.conj(zc) * zpc) + lam) / (np.sum(np.abs(zc) ** 2) + lam)
        t = zp.mean() - m * z.mean()
        mr[k], mi[k], tr[k], ti[k] = m.real, m.imag, t.real, t.imag
    # 구간 축 평활 + 데이터 없는 구간은 항등(원본)으로
    sm = {}
    for name, v, ident in (("dL", dL, 0.0), ("mr", mr, 1.0), ("mi", mi, 0.0), ("tr", tr, 0.0), ("ti", ti, 0.0)):
        sv, c = _smooth_bins(v, conf)
        sm[name] = c * sv + (1 - c) * ident
    sm["conf"] = _smooth_bins(np.zeros(NB), conf)[1]
    return {"g": g, "centers": centers, **sm}


def apply_grade(rgb, grade):
    """학습한 규칙을 임의의 색(N x 3, 0..1)에 적용한다."""
    s2 = apply_white_balance(rgb, grade["g"])
    lab = to_oklab(s2)
    L = lab[..., 0]
    c = grade["centers"]
    f = lambda key: np.interp(L, c, grade[key])
    out_L = L + f("dL")
    z = (lab[..., 1] + 1j * lab[..., 2]) * (f("mr") + 1j * f("mi")) + (f("tr") + 1j * f("ti"))
    return np.clip(from_oklab(np.stack([out_L, z.real, z.imag], axis=-1)), 0, 1)


def describe_grade(grade):
    g = grade["g"]
    warm = np.log(g[0] / g[2])  # 빨강/파랑 이득비: 양수면 따뜻하게, 음수면 차갑게
    tint = np.log(g[1] / np.sqrt(g[0] * g[2]))  # 초록 이득 / 빨강·파랑 평균: 양수면 초록 쪽
    temp = "따뜻하게(노랑 쪽)" if warm > 0.02 else "차갑게(파랑 쪽)" if warm < -0.02 else "거의 그대로"
    tn = "초록 쪽" if tint > 0.02 else "자홍 쪽" if tint < -0.02 else "거의 그대로"
    return f"색온도 이동: {temp} {abs(warm)*100:.0f}% / 틴트: {tn} {abs(tint)*100:.0f}% (채널 이득 R{g[0]:.2f} G{g[1]:.2f} B{g[2]:.2f})"


def local_gain(lut):
    """격자점마다 입력이 한 칸(1/(N-1)) 변할 때 출력이 변하는 최대 배율(세 축 중 최댓값)."""
    g = np.zeros(lut.shape[:3])
    for ax in range(3):
        d = np.abs(np.diff(lut, axis=ax)).max(axis=-1) * (N - 1)
        pad = [(0, 0)] * 3
        pad[ax] = (0, 1)
        g = np.maximum(g, np.pad(d, pad, mode="edge"))
    return g


def limit_gain(lut, max_gain=None, iters=60):
    """국소 기울기가 max_gain을 넘는 곳만 주변 평균 쪽으로 펴서 노이즈 증폭과 밴딩을 막는다.
    상한을 넘는 정도에 비례해 섞으므로, 이미 완만한 곳(보정 색감 대부분)은 그대로다."""
    max_gain = MAX_GAIN if max_gain is None else max_gain
    out = lut.copy()
    for _ in range(iters):
        over = np.clip((local_gain(out) - max_gain) / max_gain, 0, 1)
        if over.max() <= 0.01:
            break
        m = np.clip(gaussian_filter(over, 1.0, mode="nearest") * 1.5, 0, 1)[..., None] * 0.6
        smooth = np.stack([gaussian_filter(out[..., c], 1.0, mode="nearest") for c in range(3)], axis=-1)
        out = out * (1 - m) + smooth * m
    return out


def build_lut(src, dst, kelvin=None):
    grade = fit_grade(src, dst, kelvin)
    delta = dst - apply_grade(src, grade)  # 규칙으로 설명 안 되는 잔차(장면 고유의 미세 보정)만 LUT 격자로
    global LAST_GRADE
    LAST_GRADE = grade
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
    base = apply_grade(grid.reshape(-1, 3), grade).reshape(N, N, N, 3)  # 규칙은 모든 색에 정의되므로 게이트하지 않는다

    # 게이트: 학습 데이터가 없는 색에서는 다항식 외삽이 엉뚱한 색(예: 초록 -> 갈색)을 만든다.
    # 데이터 밀도가 낮을수록 원본(grid) 쪽으로 되돌려, 모르는 색은 건드리지 않는다.
    global LAST_GATE_COVERAGE
    density = gaussian_filter(sum_w, GATE_SIGMA, mode="nearest") / len(src)
    t = np.clip(density / GATE_REF, 0, 1)
    gate = t * t * (3 - 2 * t)
    LAST_GATE_COVERAGE = float((gate > 0.5).mean())
    out = base + gate[..., None] * lut_delta  # 규칙(전역) + 데이터가 있는 곳의 잔차(게이트)
    out = limit_gain(np.clip(out, 0, 1))
    return np.clip(out, 0, 1).astype(np.float32)  # [r, g, b, 3]


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


def run(out_p, pair_paths, check_path="auto", title=None, kelvin=None):
    """사진 쌍(경로 목록: before1, after1, before2, after2 ...)에서 LUT를 만들어 out_p에 저장한다.
    check_path: "auto"면 out_p 옆에 _check.jpg 저장, None이면 저장하지 않음, 경로면 그곳에 저장."""
    (b_img, a_img, src0, dst0), (tr_s, tr_d, te_s, te_d) = load_pairs(pair_paths)

    # 1) 검증: 학습에서 뺀 영역으로 정확도 측정
    lut_v = build_lut(tr_s, tr_d, kelvin)
    e = lambda p, d: np.abs(p - d).mean() * 255
    print(f"[검증] 학습 영역 오차 {e(apply_lut(tr_s, lut_v), tr_d):.2f} / "
          f"보지 않은 영역 오차 {e(apply_lut(te_s, lut_v), te_d):.2f} (보정 전 {e(te_s, te_d):.2f})")

    # 2) 최종 LUT는 전체 데이터로
    all_s, all_d = np.concatenate([tr_s, te_s]), np.concatenate([tr_d, te_d])
    lut = build_lut(all_s, all_d, kelvin)
    name = title or out_p.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]
    write_cube(out_p, lut, title=name)
    print("보정 규칙 -", describe_grade(LAST_GRADE), f"[{LAST_WB_INFO}]")
    print(f"장면 고유 보정이 적용되는 색 영역: 전체 색 공간의 {LAST_GATE_COVERAGE*100:.1f}% (나머지는 원본 유지)")
    print(f"전체 평균 오차(0~255): 보정 전 {e(all_s, all_d):.2f} -> LUT 적용 후 {e(apply_lut(all_s, lut), all_d):.2f}")
    g = np.linspace(0, 1, 5)[:, None].repeat(3, 1)
    print("회색 램프:", " | ".join("%.2f→%.2f,%.2f,%.2f" % (i[0], *o) for i, o in zip(g, apply_lut(g, lut))))
    print(f"저장: {out_p}")

    if check_path is not None:
        if check_path == "auto":
            check_path = out_p.rsplit(".", 1)[0] + "_check.jpg"
        pred = apply_lut(src0, lut)
        w, h = b_img.size
        pred_img = Image.fromarray((pred.reshape(h, w, 3) * 255).astype(np.uint8))
        sheet = Image.new("RGB", (w * 3, h))
        for i, im in enumerate([b_img, pred_img, a_img]):
            sheet.paste(im, (w * i, 0))
        sheet.save(check_path, quality=90)
        print(f"비교 이미지: {check_path}  (왼쪽 전 / 가운데 LUT 적용 / 오른쪽 후)")


def main():
    if len(sys.argv) < 4 or len(sys.argv) % 2 != 0:
        print(__doc__)
        sys.exit(1)
    run(sys.argv[1], sys.argv[2:])


if __name__ == "__main__":
    main()
