# Grafika veřejného vydání NetKit

Export 2026-10-28. Bez instalace nástrojů, bez emulátoru a bez fyzického telefonu.
Žádné screenshoty, SSID, IP adresy ani jména zařízení nebyly použity.

| Soubor | Rozměry | Formát | Velikost |
|---|---|---|---|
| [ikona-512.png](ikona-512.png) | 512 × 512 | 32bit PNG RGBA, sRGB | 58 102 B |
| [feature-graphic.png](feature-graphic.png) | 1024 × 500 | 24bit PNG RGB, sRGB, bez alfa | 25 294 B |

SHA-256:

```text
56767e5c10cdf3ee55790924af00704ce4507e69b440cec467ede42dd9f0f5bd  ikona-512.png
feb4bc9d26db7bd26f13dcbafb600f6e897b9351b1dd74664ce63520cf1026dd  feature-graphic.png
```

## Zdroj a provedení

[ikona-512.svg](ikona-512.svg) je mechanický převod produkčních vektorů
[pozadí](../../../androidApp/src/main/res/drawable/ic_launcher_background.xml) a
[popředí](../../../androidApp/src/main/res/drawable/ic_launcher_foreground.xml),
spojených podle [adaptive icon](../../../androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml).
Zachované `pathData`, souřadnice 108 × 108, tahy, opacity a oba gradienty.
Android `#AARRGGBB` je převedeno na SVG RGB + opacity; gradienty používají
`userSpaceOnUse`. Bez vrstvy DEV, bez nové masky či vnějšího stínu.
Alfa kanál je přítomen, ale všechny výsledné pixely jsou neprůhledné díky plnému
původnímu pozadí. To není export samotného průhledného popředí.

[feature-graphic.svg](feature-graphic.svg) je editovatelná vektorová kompozice;
obsahuje stejné popředí launcher ikony, barvy `background`, `surface`, `surface2`,
`text`, `accent` z [design.md §2](../../design.md#2-barvy-tokeny-netkitcolors) a
písmo Manrope z existujícího [res/font](../../../androidApp/src/main/res/font/manrope.ttf).
Text: „NetKit“, „Vaše síť. Přehledně.“, „Wi-Fi“, „LAN“, „SSH“, „WoL“.
Neobsahuje napodobeninu UI ani tvrzení o výsledcích měření. Použitá licence fontu:
[existující OFL soubor](../../../androidApp/src/main/assets/licenses/manrope-OFL.txt);
font ani nová knihovna se nepřidávaly.

Parametry odpovídají zadání a ověřeným
[pravidlům ikony Google Play](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)
a [požadavkům preview assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
(ověřeno 2026-10-28). Finální schválení obchodu zde neproběhlo.

## Opakovaný render ze SVG

Z kořene repozitáře; používá již dostupné Python GI/Rsvg/Cairo a ImageMagick.
Fontconfig směruje jen tento proces na přibalený font. Žádná systémová instalace.
Příkazy přepíší pouze oba generované PNG.

```bash
python3 -I - <<'PY'
from pathlib import Path
from xml.sax.saxutils import escape
fonts = escape(str(Path('androidApp/src/main/res/font').resolve()))
Path('/tmp/netkit-fonts.conf').write_text(
    '<?xml version="1.0"?><fontconfig><include>/etc/fonts/fonts.conf</include>'
    f'<dir>{fonts}</dir><cachedir>/tmp/netkit-font-cache</cachedir></fontconfig>')
PY
FONTCONFIG_FILE=/tmp/netkit-fonts.conf python3 -I - <<'PY'
from pathlib import Path
import gi, cairo
gi.require_version('Rsvg', '2.0')
from gi.repository import Rsvg
for name, w, h in [('ikona-512', 512, 512), ('feature-graphic', 1024, 500)]:
    path = Path('docs/public/grafika') / name
    svg = Rsvg.Handle.new_from_file(str(path.with_suffix('.svg')))
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
    viewport = Rsvg.Rectangle()
    viewport.x = viewport.y = 0
    viewport.width, viewport.height = w, h
    assert svg.render_document(cairo.Context(surface), viewport)
    surface.write_to_png('/tmp/netkit-' + name + '.png')
PY
convert /tmp/netkit-ikona-512.png -colorspace sRGB -alpha on PNG32:docs/public/grafika/ikona-512.png
convert /tmp/netkit-feature-graphic.png -colorspace sRGB -alpha off PNG24:docs/public/grafika/feature-graphic.png
file docs/public/grafika/*.png
identify -format '%f: %wx%h %[channels] %b\n' docs/public/grafika/*.png
sha256sum docs/public/grafika/*.png
```

Verze rendereru může ovlivnit rastrování, metadata PNG a tím SHA-256. Kontrolní
součty výše patří dodaným souborům; nejsou příslibem shody mezi jinými stroji.
Vizuálně ověřeno v tomto běhu: správné čtyři uzly, barvy, úplná česká diakritika,
žádné oříznutí textu. Úkol screenshotů a jejich fixture zůstává Claudovi.
