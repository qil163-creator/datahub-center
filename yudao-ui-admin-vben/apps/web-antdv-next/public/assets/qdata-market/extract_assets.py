"""从用户提供的 qData 截图提取集市装饰与图标。"""

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent
SOURCE = Path("C:/Users/LZQ/AppData/Local/Temp/codex-clipboard-31ef8c82-58ec-4347-8321-f71d600dc473.png")
DETAIL = Path("C:/Users/LZQ/AppData/Local/Temp/codex-clipboard-ba43392f-a966-478b-b79f-8d57e13d354b.png")
source = Image.open(SOURCE).convert("RGBA")
detail = Image.open(DETAIL).convert("RGBA")
assets = {}


def crop(name, box, *, image=source, transparent=False, shield=False, role="decorative"):
    """只裁取图像像素，并移除与边框连通的白色背景。"""
    result = image.crop(box)
    if transparent:
        pixels = result.load()
        width, height = result.size
        pending = [(x, 0) for x in range(width)] + [(x, height - 1) for x in range(width)]
        pending += [(0, y) for y in range(height)] + [(width - 1, y) for y in range(height)]
        visited = set()
        while pending:
            x, y = pending.pop()
            if (x, y) in visited or not (0 <= x < width and 0 <= y < height):
                continue
            visited.add((x, y))
            red, green, blue, alpha = pixels[x, y]
            if min(red, green, blue) < 248:
                continue
            pixels[x, y] = (red, green, blue, 0)
            pending.extend([(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)])
    if shield:
        width, height = result.size
        mask = Image.new("L", result.size)
        draw = ImageDraw.Draw(mask)
        draw.rounded_rectangle((0, 0, width - 1, height // 2 + 3), radius=4, fill=255)
        draw.ellipse((0, 0, width - 1, height - 1), fill=255)
        result.putalpha(mask)
    filename = f"{name}.png"
    result.save(ROOT / filename, optimize=True)
    assets[name] = {
        "file": f"/assets/qdata-market/{filename}",
        "sourceReference": str(DETAIL if image is detail else SOURCE),
        "sourceBox": list(box),
        "pixelSize": list(result.size),
        "suggestedCssSize": [round(value / 1.5, 2) for value in result.size],
        "role": role,
        "hasAlpha": result.getextrema()[3][0] < 255,
    }
    return result


crop("stat-total", (878, 518, 965, 583), transparent=True)
crop("stat-authorized", (1478, 518, 1541, 584), transparent=True)
crop("stat-favorite", (2072, 518, 2134, 582), transparent=True)
crop("stat-applying", (2661, 519, 2723, 584), transparent=True)
crop("feature-rich", (383, 355, 447, 419), shield=True)
crop("feature-compliant", (700, 355, 764, 419), shield=True)
crop("feature-open", (1018, 355, 1082, 419), shield=True)
crop("empty-permission", (1797, 674, 2048, 917), image=detail, transparent=True)
crop("icon-asset", (864, 671, 888, 694), transparent=True)
crop("icon-score", (3328, 670, 3345, 696), transparent=True)
crop("icon-star", (3420, 668, 3445, 696), transparent=True)
crop("icon-folder", (410, 1493, 437, 1520), transparent=True)
crop("icon-document", (438, 1531, 463, 1558), transparent=True)
crop("icon-send", (3378, 829, 3398, 852), transparent=True)
crop("icon-eye", (3245, 830, 3267, 850))
crop("icon-return", (3423, 197, 3446, 219), image=detail, transparent=True)
crop("banner-wave", (1730, 165, 2490, 466))
crop("banner-right", (3182, 165, 3526, 466))

manifest = {
    "sourceReference": str(SOURCE),
    "referenceCanvas": {"width": source.width, "height": source.height},
    "deviceScaleFactorInference": 1.5,
    "scaleNote": "原截图的 90px 顶栏、24px 图标与常见 60px/16px CSS 尺寸对应，最终应以运行时 DOM 度量复核。",
    "assets": assets,
    "limitations": [
        "完整 banner 背景被真实文字与搜索控件覆盖；banner-wave 和 banner-right 只包含无文字的源图片段。",
        "feature 图标保留源截图颜色；仅在盾牌轮廓外添加透明遮罩。",
        "icon-eye 保留源按钮蓝色背景，适合相同颜色的详情按钮。",
    ],
}
(ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

blueprint = {
    "referenceCanvas": {"width": 3558, "height": 1882},
    "inferredCssViewport": {"width": 2372, "height": 1254.67},
    "inferredDeviceScaleFactor": 1.5,
    "regions": {
        "business": {"sourceBox": [314, 144, 3556, 1881]},
        "hero": {"sourceBox": [338, 165, 3526, 466], "cssHeight": 200.67},
        "filter": {"sourceBox": [338, 489, 808, 1880], "cssWidth": 313.33},
        "statistics": {"sourceBox": [831, 489, 3526, 609], "cssHeight": 80},
        "card1": {"sourceBox": [831, 633, 3526, 885], "cssHeight": 168},
        "card2": {"sourceBox": [831, 909, 3526, 1161], "cssHeight": 168},
        "gap": {"css": 16},
    },
    "detail": {
        "metadata": {"sourceBox": [338, 165, 3526, 472]},
        "tabs": {"sourceBox": [338, 496, 3526, 1871]},
        "emptyPermission": {"sourceBox": [1797, 674, 2048, 917]},
    },
    "applicationModal": {
        "sourceBox": [1329, 114, 2231, 645],
        "cssWidth": 601.33,
        "cssHeight": 354,
    },
    "tokens": {
        "primary": "#4464ff",
        "pageBackground": "#f0f2f5",
        "surface": "#ffffff",
        "label": "#909399",
        "border": "#ebeef5",
    },
}
(ROOT / "blueprint.json").write_text(json.dumps(blueprint, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

thumbs = []
for name in assets:
    if name.startswith("banner"):
        continue
    item = Image.open(ROOT / f"{name}.png").convert("RGBA")
    item.thumbnail((220, 190))
    tile = Image.new("RGBA", (250, 225), "#f0f2f5")
    tile.alpha_composite(item, ((250 - item.width) // 2, 12))
    ImageDraw.Draw(tile).text((8, 205), name, fill="#333333")
    thumbs.append(tile)
sheet = Image.new("RGBA", (1000, ((len(thumbs) + 3) // 4) * 225), "white")
for index, tile in enumerate(thumbs):
    sheet.alpha_composite(tile, ((index % 4) * 250, (index // 4) * 225))
sheet.save(ROOT / "asset-contact-sheet.png")
print(json.dumps({name: value["pixelSize"] for name, value in assets.items()}, ensure_ascii=False))
