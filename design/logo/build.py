"""날씨톡톡 로고 원본 SVG를 만든다(말줄임 구름 시안 A).

python design/logo/build.py  →  design/logo/source/*.svg
글자 윤곽선은 Noto Sans KR(SIL OFL, 로고 사용 가능) 굵기 800에서 뽑는다. fonttools가 필요하다.
"""
import io
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

OUT = Path(__file__).parent / "source"
FONT = Path("C:/Windows/Fonts/NotoSansKR-VF.ttf")

BLUE = "#2F6FED"
SUN = "#FFC531"
INK = "#191F28"
TEAL = "#12B5A6"  # 대안 색 제안

# --- 심벌 도형(256 캔버스). 구름은 원 세 개의 합집합 윤곽, 점 세 개는 진짜 구멍(반대 방향 원).
CLOUD = (
    "M82 180 A30 30 0 0 1 82.39 120.00"
    " A46 46 0 0 1 173.97 124.27"
    " A28 28 0 0 1 170 180 Z"
)
TAIL = "M108 180 C98 200 80 212 54 214 C66 204 72 190 74 170 Z"
DOTS = "".join(
    f" M{x - 9} 156 A9 9 0 1 0 {x + 9} 156 A9 9 0 1 0 {x - 9} 156 Z" for x in (102, 128, 154)
)
# 해: 구름보다 6만큼 큰 윤곽을 잘라 내 틈을 둔다(한 색일 때도 해와 구름이 갈린다).
SUN_CUT = (
    "M134.10 74.36 A40 40 0 1 1 191.55 125.70"
    " A34 34 0 0 0 179.58 119.36"
    " A52 52 0 0 0 134.10 74.36 Z"
)
# 그린 도형의 실제 범위(x 52~210, y 52~214)를 가운데로, 광학 중심이라 살짝 위로
CENTER = "translate(-3 -6)"


def symbol(cloud, sun, dots=True):
    d = CLOUD + " " + TAIL + (DOTS if dots else "")
    return (
        f'<g transform="{CENTER}">'
        f'<path fill="{sun}" d="{SUN_CUT}"/>'
        f'<path fill="{cloud}" d="{d}"/>'
        "</g>"
    )


def svg(view_w, view_h, body, title="날씨톡톡 로고"):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view_w} {view_h}">'
        f"<title>{title}</title>{body}</svg>\n"
    )


def tile(bg, rx=58, dots=True, scale=1.0):
    t = f"translate({128 - 128 * scale} {128 - 128 * scale}) scale({scale})"
    return f'<rect width="256" height="256" rx="{rx}" fill="{bg}"/><g transform="{t}">{symbol("#FFFFFF", SUN, dots)}</g>'


def wordmark_path(text, size=118, tracking=-0.02):
    """글자를 윤곽선 path로. 기준선 y=0, 반환: (path d, 너비)."""
    font = TTFont(FONT)
    font = instantiateVariableFont(font, {"wght": 800})
    buf = io.BytesIO()
    font.save(buf)
    font = TTFont(io.BytesIO(buf.getvalue()))
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upem = font["head"].unitsPerEm
    s = size / upem
    pen = SVGPathPen(gs)
    x = 0.0
    for ch in text:
        g = cmap[ord(ch)]
        tp = TransformPen(pen, (s, 0, 0, -s, x, 0))
        gs[g].draw(tp)
        x += gs[g].width * s + size * tracking
    return pen.getCommands(), x - size * tracking


def main():
    OUT.mkdir(exist_ok=True)
    files = {
        # 흰 바탕용 심벌: 파란 구름 + 노란 해
        "symbol.svg": svg(256, 256, symbol(BLUE, SUN)),
        "symbol-small.svg": svg(256, 256, symbol(BLUE, SUN, dots=False)),
        # 어두운·파란 바탕용(반전)
        "symbol-reversed.svg": svg(256, 256, symbol("#FFFFFF", SUN)),
        # 한 색
        "symbol-black.svg": svg(256, 256, symbol("#000000", "#000000")),
        "symbol-white.svg": svg(256, 256, symbol("#FFFFFF", "#FFFFFF")),
        "symbol-mono-blue.svg": svg(256, 256, symbol(BLUE, BLUE)),
        # 앱 아이콘(둥근 타일)과 꽉 찬 정사각(스토어·토스 콘솔이 모서리를 깎는 곳용)
        "app-icon.svg": svg(256, 256, tile(BLUE)),
        "app-icon-square.svg": svg(256, 256, tile(BLUE, rx=0)),
        "app-icon-small.svg": svg(256, 256, tile(BLUE, dots=False, scale=1.08)),
        "app-icon-alt-teal.svg": svg(256, 256, tile(TEAL)),
        # 안드로이드 적응형 아이콘 전경: 투명 바탕, 안전 영역(가운데 66%) 안으로 줄임
        "android-foreground.svg": svg(256, 256, f'<g transform="translate(38.4 38.4) scale(0.7)">{symbol("#FFFFFF", SUN)}</g>'),
        "android-monochrome.svg": svg(256, 256, f'<g transform="translate(38.4 38.4) scale(0.7)">{symbol("#000000", "#000000")}</g>'),
    }

    word, ww = wordmark_path("날씨톡톡")
    gap = 34
    # 가로형: 타일 아이콘 + 글자(타일 높이 256, 글자 기준선은 시각 중심에 맞춘 값)
    hw = 256 + gap + ww
    files["lockup-horizontal.svg"] = svg(
        round(hw), 256,
        tile(BLUE) + f'<path fill="{INK}" transform="translate({256 + gap} 170)" d="{word}"/>',
    )
    files["lockup-horizontal-reversed.svg"] = svg(
        round(hw), 256,
        tile(BLUE) + f'<path fill="#FFFFFF" transform="translate({256 + gap} 170)" d="{word}"/>',
    )
    files["lockup-horizontal-black.svg"] = svg(
        round(hw), 256,
        symbol("#000000", "#000000") + f'<path fill="#000000" transform="translate({256 + gap} 170)" d="{word}"/>',
    )
    # 세로형: 타일 위, 글자 아래
    sw = max(256, ww)
    files["lockup-stacked.svg"] = svg(
        round(sw), 256 + 40 + 130,
        f'<g transform="translate({(sw - 256) / 2} 0)">{tile(BLUE)}</g>'
        f'<path fill="{INK}" transform="translate({(sw - ww) / 2} {256 + 40 + 104})" d="{word}"/>',
    )
    files["wordmark.svg"] = svg(round(ww), 130, f'<path fill="{INK}" transform="translate(0 104)" d="{word}"/>')

    for name, text in files.items():
        (OUT / name).write_text(text, encoding="utf-8")
        print("wrote", OUT / name)


if __name__ == "__main__":
    main()
