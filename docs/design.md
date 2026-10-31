# NetKit — design systém M3 („Čistý UniFi“)

Závazný podklad pro vzhled aplikace od M3. Vzor: [prototyp](design/prototyp-m3.html), směr
„Čistý UniFi“. Rozhodnutí: prace/15-navrh-m3.md (interní záznam). Kde se prototyp
a tento dokument liší, platí tento dokument. Hodnoty px z prototypu = dp/sp v Compose.

## 1. Principy

- **Ploché vrstvy místo rámečků.** Karty nemají okraj ani stín. Odlišují se jen tónem
  povrchu (`surface` > `surface2` > `surface3`). Oddělovač řádků je velmi jemný (`divider`).
- **Data jsou hlavní obsah.** Velká tabulární čísla, jednotky menší a tlumené. Text jen tam,
  kde nese informaci. Vysvětlivka nejvýše jedna na obrazovku a sbalená (`InfoNote`).
- **Jedna úroveň přepínačů** na obrazovce. Druhou volbu (který graf) dělá výběr metriky
  (karty s hodnotou), ne další segmentový přepínač.
- **Mono písmo jen pro strojové hodnoty:** IP, MAC, BSSID, porty v textu a terminál.
  Čísla měření jsou v Manrope s tabulárními číslicemi.
- **Žádné VERSALKY s prostrkáním**, žádné emoji, žádné barevné proužky na okraji karet.
- **Stav kódovat tvarem i barvou:** tečka + štítek, sémantické barvy ok/warn/err
  oddělené od akcentu.

## 2. Barvy (tokeny `NetKitColors`)

Názvy tokenů v Compose zůstávají jako dnes, kde to jde. Nové tokeny jsou označené (nový).

| Token | Tmavý | Světlý | Použití |
|---|---|---|---|
| `background` | `#0C131A` | `#EEF3F6` | pozadí obrazovky |
| `surface` | `#141E28` | `#FFFFFF` | karta (Panel) |
| `surface2` | `#1A2633` | `#F1F5F8` | vnořené prvky: dlaždice, segment, chipy, ikony |
| `surface3` | `#22303E` | `#E3EAEF` | stopa měřidla, aktivní volba v malém přepínači, progress track |
| `divider` (nový) | `#FFFFFF` 5 % | `#0C2D41` 6 % | oddělovač řádků, mřížka grafu |
| `text` | `#E8F1F5` | `#0B1A24` | hlavní text, hodnoty |
| `muted` | `#93A6B3` | `#4E6372` | popisky, sekundární text |
| `dim` | `#5F7380` | `#7C8E9A` ¹ | osy, neaktivní ikony, poznámky |
| `accent` | `#2DD4BF` | `#0A7166` | primární akce, signál, vybraná záložka |
| `accentInk` | `#04201C` | `#FFFFFF` | text na `accent` |
| `accentSoft` | accent 12 % | accent 10 % | tonální tlačítko, indikátor v navigaci, štítek |
| `info` | `#4C9DFF` | `#1F6EBF` ¹ | příjem/stahování, latence internetu, roaming |
| `violet` (nový) | `#B69CFF` | `#6E4FD0` | odesílání (druhá série vedle `info`) |
| `ok` | `#3DDC97` | `#117C57` ¹ | dobrý stav |
| `warn` | `#F5B544` | `#975F00` ¹ | varování, změna kanálu |
| `err` | `#F2706B` | `#C04039` ¹ | chyba, ztráta paketů |
| `navBackground` (nový) | `#101922` | `#FFFFFF` | spodní navigace |
| `terminalBackground`/`terminalText` | `#05090C` / `#B8F5E9` | stejné (terminál je vždy tmavý) | SSH terminál |

`*Soft` varianty sémantických barev = barva s 14–15 % krytím v tmavém režimu, ve světlém
10 % (`0x1A`, stejně jako `accentSoft`). Světlý režim musí splnit kontrast WCAG AA: text
4,5:1 na `surface` i `background`, čáry grafů a ikony 3:1. Ověřuje
`python3 -I tools/check_contrast.py` (parsuje tokeny z `Theme.kt`, nenulový návratový kód
při selhání): `text`/`muted` na `surface`, `background` a `surface2` ≥ 4,5; `dim` ≥ 3;
sémantické barvy jako text na `surface` ≥ 4,5 a jako čára ≥ 3; text štítku na jeho `*Soft`
podkladu nad `surface` ≥ 4,5; `accentInk` na `accent` a terminál ≥ 4,5.

¹ Upraveno v B1 (2026-10-27) proti prototypu, aby světlý režim prošel kontrolou. Původní
hodnoty a důvod: `dim` `#7E909C` měl na `background` 2,96:1 (< 3); `warn` `#A86A00` měl
jako text na `surface` 4,44:1; štítky `info`/`ok`/`warn`/`err` na 14–15% podkladu měly
3,7–4,2:1. Oprava: podklad `*Soft` ve světlém režimu snížen na 10 % a barvy minimálně
ztmaveny (`info` `#1F6FC0`, `ok` `#12805A`, `warn` `#A86A00`, `err` `#C2413A` → hodnoty
v tabulce). Odstín zůstal, změna je pod hranicí běžné rozlišitelnosti kromě `warn`
(o ~10 % tmavší). Tmavý režim prošel beze změny.

Série v grafech: první série `accent` (signál, brána) nebo `info` (příjem, internet), druhá
`violet` (odesílání) **čárkovaná**, aby se lišila i bez barvy. Lichoběžníky spektra používají
paletu `WifiChartPalette`, která se sladí s tokeny.

## 3. Typografie

Písma (OFL, přibalená variabilní TTF v `res/font/`): **Manrope** (`NetKitFonts.sans`),
**JetBrains Mono** (`NetKitFonts.mono`). Geist byl odstraněn v B1 (zdroje a SHA-256 v `docs/zavislosti.md`).

| Styl | Velikost/řádek | Řez | Poznámka |
|---|---|---|---|
| `screenTitle` | 26/30 | 700, −0,02 em | nadpis obrazovky (např. „Example-WiFi“, „Spektrum“) |
| `subtitle` | 13/18 | 500 | pod nadpisem, `muted` |
| `panelTitle` | 15/20 | 700 | název karty |
| `body` | 14/20 | 500 | běžný text, řádek klíč–hodnota |
| `label` | 12,5/16 | 500–600 | popisek nad hodnotou, `muted`, věta (ne versalky) |
| `value` | 17/22 | 700, tnum | hodnota v dlaždici, výběr metriky |
| `metric` | 19/24 | 700, tnum | hodnota fáze speed testu |
| `gauge` | 38/40 | 700, −0,03 em, tnum | dBm v měřidle |
| `hero` | 46/48 | 700, −0,035 em, tnum | rychlost pod ciferníkem |
| `unit` | 11,5/14 | 600 | jednotka za hodnotou, `muted` |
| `tag` | 12/16 | 600 | štítky, chipy |
| `nav` | 11,5/14 | 700 | popisek ve spodní navigaci |
| `address` | 0,92× okolí | JetBrains Mono 400–500 | IP, MAC, BSSID |
| `terminal` | 12,5/20 (8–24 nastavitelné) | JetBrains Mono 400 | SSH terminál |

Tabulární číslice: `fontFeatureSettings = "tnum"` ve všech číselných stylech. Velikosti v sp,
rozvržení musí unést `font_scale` 1,3 bez oříznutí (viz §7).

**Záporné hodnoty v měřidle (dBm):** číslice jsou centrované a znaménko mínus je
předsazené vlevo mimo centrování. Mínus má řez 500 a krytí 72 %. Znaménko se nevypouští.

## 4. Tvary, rozestupy, rozvržení

- **Rádiusy:** karta 16, vnořený prvek 12, tlačítko 12, malé tlačítko 10, štítek 8,
  pilulka/chip 999.
- **Rozestupy:** okraj obrazovky 16, mezera mezi kartami 16, vnitřní odsazení karty 20, mezera
  uvnitř karty 14. Mřížka po 4.
- **Hlavička obrazovky:** nadpis a podtitul vlevo, vpravo jedna akce (primární malé
  tlačítko nebo ikona 40×40 na `surface2`). Výška ≥ 52.
- **Dlaždice hodnot:** 3 v řadě s mezerou 8 na `surface2`. Šířky podle obsahu (např. PHY 1fr,
  IPv4 1,45fr, brána 1,45fr, celá adresa). Hodnota se nikdy nezkracuje třemi tečkami, při nedostatku
  místa se řada zalomí.
- **Řádky klíč–hodnota:** výška obsahu + 9 nahoře a dole, oddělovač `divider`, první řádek
  bez oddělovače. Klíč `muted`, hodnota `text` 600 vpravo.
- **Řádek seznamu:** ikona 40×40 (rádius 12, `surface2`) | název 15/650 + podtitul 12,5
  `muted` | koncová hodnota a chevron.
- **Úzký displej / velké písmo:** měřidlo a informace o síti se při šířce karty < 320 dp
  skládají pod sebe (`BoxWithConstraints`). Uzly karty cesty se skládají pod sebe, když se
  kterákoli strojová hodnota (IP, AS) nevejde na jeden řádek svého sloupce, a vždy při
  `font_scale` ≥ 1,3 (M4).

## 5. Komponenty

| Komponenta | Specifikace |
|---|---|
| `SegmentedTabs` | stopa `surface2`, rádius 12, padding 4. **Posuvný indikátor** `surface` (rádius 9) se animuje na vybranou položku. Vybraný text `text`, ostatní `muted`, výška 38. testTagy beze změny. |
| `Panel` / `SectionCard` | `surface`, rádius 16, padding 20, bez okraje a stínu (světlý režim: stín 1 dp s 6 % krytím). |
| Tlačítka | primární (`accent`/`accentInk`), tonální (`accentSoft`/`accent`), neutrální (`surface2`/`text`). Výška 44, malé 36. Ikona 18 + text 14,5/700. Obrysová tlačítka se nepoužívají. |
| `Tag` (dříve `StatusChip`) | rádius 8, padding 4×9, `tag` styl, volitelná tečka 6 dp. Tóny: neutral (`surface2`/`muted`), accent, ok, warn, err, info. Sans, ne mono. |
| `LiveBadge` | štítek „živě · 1 s“ v akcentu s pulzující tečkou (2 s, zvětšující se prstenec). |
| `SignalGauge` | oblouk 240°, poloměr 62, tah 10, kulaté konce. Stopa `surface3`, hodnota v barvě kvality (ok/accent/warn/err). Uvnitř hodnota `gauge` + „dBm“, **pod obloukem** kvalita (13/700 v barvě kvality). Animace oblouku 800 ms. Stupnice `range` (M4): `GaugeRange.WIFI` −90…−30 dBm (RSSI), `GaugeRange.CELLULAR` −140…−44 dBm (RSRP). Bez `range` platí předané `fraction` jako dřív. Místo kvality smí být stav („Režim letadlo“, „Bez SIM“, „Bez služby“) v neutrální barvě. |
| Mobilní měřidlo (M4) | `SignalGauge` s `GaugeRange.CELLULAR` a vedle něj SIM (`label`), operátor (`panelTitle`) a štítky: standard (accent s tečkou), „Data“ (jen při dvou SIM), „Roaming“ (warn). Pod tím dlaždice RSRQ, SINR (dB) a IPv4 mobilní sítě (mono). Další SIM jako řádky SIM. Kvalita podle RSRP: ≥ −80 výborný (ok), ≥ −90 dobrý (accent), ≥ −100 slabší (warn), jinak slabý (err). |
| Karta cesty (M4) | `SectionCard` „Připojení“, vpravo jen menu (ikona 40, `More`): „Obnovit“ a „Zjišťovat veřejnou IP“ se zaškrtávátkem. Uzly: poskytovatel (`Globe`: jméno, veřejná IPv4, IPv6, „AS…“ v `dim`), přístupový bod (`Router`, jen na Wi-Fi: pásmo a kanál), telefon (`Phone`: „Tento telefon“ a lokální IP). Uzel = kruh 40 na `surface2` s ikonou 20 `muted`, pod ním (nebo vedle něj ve sloupci) název `body` 600 a řádky `label`, IP v mono `text`. Spojovací linka 2 dp `surface3` vede středy ikon; na živé síti nad ní `accent` s krytím 35–75 %, které jemně dýchá (`rememberBreathing`, perioda 2 s, s omezením animací stojí). Stavy uzlu poskytovatele: načítání („zjišťuji veřejnou IP…“), veřejná IP, „za NAT operátora“ (štítek info), „veřejná IP nedostupná“, „zjišťování vypnuto“ a „Bez připojení“; v posledních třech je první úsek linky čárkovaný a nedýchá. IP se nikdy nezkracuje (viz §4). |
| Řádek se dvěma řádky (M4, Latence) | Jako řádek klíč–hodnota, ale pod popiskem (`body`, `muted`) jsou poznámky na samostatných řádcích: `label` v `dim`, strojové hodnoty mono (brána, cíl, resolver DNS, dotazované jméno). Hodnota vpravo nejvýš 55 % šířky, zarovnaná na účaří popisku; mezera 12 dp zůstává i při 360 dp a písmu 1,3. DNS: první poznámka je IP resolveru, nebo „Soukromé DNS · host“, druhá dotazované jméno. |
| Řádek SIM (M4) | „SIM 1 · operátor“ (`body` 600) a pod ním štítky standardu, „Data“ (accent) a „Roaming“ (warn); vpravo „−97 dBm“ (`body` 600) a kvalita (`label` v barvě kvality). Rytmus a oddělovač jako řádek klíč–hodnota. |
| Řádek roamingu (M4) | Vlevo „před 12 s“ (`body`, `muted`) a štítky: změna pásma („2,4 → 5 GHz“), jinak změna kanálu („k. 36 → 100“), a „po výpadku“ (warn); štítky se při úzké šířce zalomí pod čas. Vpravo obě zkrácené BSSID mono na jednom řádku. Na 412 dp nejvýš dva řádky. |
| Výběr metriky | 3 karty `surface2` (rádius 12) s názvem a živou hodnotou. U názvu krátká čárka v barvě série. Vybraná má okraj a podklad z barvy série 9–55 %. Nahrazuje vnitřní segment grafu. |
| `TimeSeriesChart` | viz §6 |
| `SpeedDial` | půlkruh 180°, otočný bod dole uprostřed. Stopa tah 5 `surface3`, průběh v barvě fáze (stahování `info`, odesílání `violet`). 41 rysek (každá 5. hlavní), rozsvícené do aktuální hodnoty. Popisky 0, 10, 50, 100, 250, 500, 1000 na log. stupnici `log10(1+v)/log10(1001)` uvnitř rysek. Tenká ručička (2,2) a kroužek 7 na otočném bodu. **Hodnota pod ciferníkem** (`hero`) + „Mbit/s“ + fáze s barevnou tečkou. Pod tím 3 dlaždice fází (Latence, Stahování, Odesílání) s průběhovou linkou 2 dp dole u aktivní fáze. |
| Tepelný pás kanálů | řada buněk (výška 44, rádius 6, mezera 3) v barvě skóre ok → warn → err. Mezery pásma (UNII) jako čárkovaný obrys. Připojený kanál má tečku nad buňkou. Pod pásem popisky vybraných kanálů a stupnice „volný → rušený“. Klepnutím se vybere kanál a pod pásem se zobrazí jeho detail (DFS, AP v dosahu s RSSI). Nahrazuje seznam desítek řádků. |
| Mřížka portů | 3 sloupce, buňka `surface2` rádius 10: port 15/700 v akcentu + služba 12 `muted`. |
| Identifikace zařízení | řádky jen pro zjištěné údaje. Nezjištěné jedním řádkem „Nezjištěno: mDNS, reverzní DNS“ v `dim`. |
| Tlačítko probuzení (WoL) | kruh 52, `accentSoft`/`accent`. Při čekání rotující prstenec, po úspěchu plný `accent` a štítek „odesláno · odpovídá na 22“. |
| Spodní navigace | `navBackground`, horní linka `divider`, 5 položek. Ikona 22 v pilulce 54×30, vybraná: pilulka `accentSoft` a ikona `accent`, popisek `text`. Ostatní `dim`. |
| `InfoNote` | ikona info `dim` + text 13 `muted`, rozbalení animované (`animateContentSize`). |
| `EmptyState` | ikona 32 v kruhu `surface2`, krátká věta a primární akce pod ní (např. „Přidat zařízení“). |

## 6. Grafy

- **Osa x podle času**, ne podle indexu vzorku. Okno (výchozí 5 min) **vždy vyplní celou
  šířku**. Před začátkem dat je prázdno vlevo, nové vzorky přibývají vpravo. Popisky času
  dole: „teď“, „−1 min“, …
- **Osa y vlevo** s popisky v `dim` 11, mřížka jen vodorovná (`divider`). Jednotka je
  v legendě nebo v hlavičce, ne pod osou. Rozsah: signál Wi-Fi −90…−30 dBm pevně, mobilní
  −140…−44 dBm, ostatní
  automaticky na „hezké“ maximum (1, 2, 2,5, 5 × 10ⁿ) s rezervou 15 %.
- **Série:** čára 2,2 dp (druhá série 1,8 dp čárkovaná 5/4), výplň pod první sérií gradient
  25 % → 0 %, koncový bod kroužek 3,6 dp s vnitřní tečkou v barvě pozadí. Žádná záře.
- **Události:** svislá čárkovaná čára (3/3) v barvě typu: roaming `info`, ztráta paketů `err`,
  změna kanálu/BSSID `warn`; u mobilního signálu (M4) přepnutí datové SIM `info` a změna
  standardu (LTE ↔ 5G) `warn`. V malém grafu tečka nahoře, v rozbaleném navíc popisek
  v pilulce.
- **Mobilní signál (M4):** RSRP datové SIM na stejné časové ose jako Wi-Fi, pevný rozsah
  −140…−44 dBm, popisky −140, −120, −100, −80, −60. Modem hlásí změnu nejvýš jednou za 3 s,
  čára je proto schodovitá.
- **Rozbalený graf** (na šířku, podle Claude Design, V-11):
  - **Hlavička:** kulaté zavření 40 a název s podtitulem (síť, kanál). Vpravo pilulky
    metriky (Signál / Provoz / Latence), okno 1 / 5 / 15 min a zoom „− 1,0× +“ (1–10×).
  - **Tělo:** graf v kartě `surface` a vedle něj sloupec 212 dp:
    - nápověda „Táhněte po grafu pro odečet. Sevřením nebo kolečkem přiblížíte.“;
    - pro každou sérii karta s min / průměr / max za viditelné okno;
    - seznam událostí v okně (tečka, název, čas „−12:42“).
  - **Gesta:** tažení jedním prstem ukáže kurzor (svislá čára, body na sériích, bublina
    s časem a hodnotami, případně událost). Dva prsty zoom a posun. Posun je omezený na
    dostupnou historii (15 min).
- **Historie:** živý monitor drží 15 min (900 vzorků), ohraničeno, neukládá se na disk.

## 7. Pohyb

| Token | Hodnota | Použití |
|---|---|---|
| `fast` | 180 ms, FastOutSlowIn | barva, stisk, přepnutí štítku |
| `base` | 320 ms, cubic(0,2; 0,8; 0,2; 1) | posuvný indikátor segmentu, vstup obsahu (fade + posun 6 dp), přechod záložek |
| `value` | 600 ms, ease-out cubic | dojíždění čísel (`AnimatedNumber`) |
| `gauge` | 800 ms, cubic(0,2; 0,8; 0,2; 1) | oblouk měřidla a ciferníku |
| `pulse` | 2 s, nekonečně | tečka „živě“; dýchání linky karty cesty (`rememberBreathing`, 1 s tam a 1 s zpět) |
| `spin` | 1 s lineárně | čekání u WoL |

- **Omezení animací:** `Settings.Global.ANIMATOR_DURATION_SCALE == 0` nebo
  „Odstranit animace“ znamená, že vše skočí rovnou na cíl a pulz ani rotace neběží
  (`LocalReduceMotion`).
- Animace běží jen na viditelné obrazovce. Při `ON_PAUSE` se nekonečné animace zastaví.
- Žádné paralaxy, částice ani skeleton shimmer. Jediný „shimmer“ je průběhový pruh
  hledání zařízení.

## 8. Texty

- Krátké věty bez výplně. Popisek říká, co hodnota je („Brána“, „Ztráta paketů“), ne jak
  vznikla.
- Vysvětlení metod (TTL+ICMP, lichoběžníky ze skenu, OUI databáze, LibreSpeed vs. NDT7)
  patří do jedné sbalené `InfoNote` na konci obrazovky nebo do detailu, ne ke každé
  položce.
- Stavové štítky malými písmeny („živě · 1 s“, „připojeno“, „spí“), tlačítka slovesem
  („Skenovat“, „Spustit test“, „Probudit“).
- Opakující se poznámky (např. „Výrobce z OUI není dostupný…“ u každé sítě) se odstraní.
- Všechny texty v resources, výchozí čeština, angličtina připravená. Při úpravě textu se
  upraví obě jazykové verze.

## 9. Ověření vzhledu

Screenshoty z emulátoru `netkit-api35` (`artifacts/m3/shots/`) pro každou obrazovku:
- tmavý a světlý režim;
- šířka 360 a 412 dp, `font_scale` 1,0 a 1,3;
- rozbalený graf na šířku;
- animace vypnuté.

Kontrola proti prototypu a tomuto dokumentu:
- nic oříznutého ani překrytého;
- hodnoty bez tří teček;
- kontrast podle §2;
- hierarchie: jeden přepínač, jedna akce v hlavičce.
