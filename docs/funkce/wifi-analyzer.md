# Funkce: Wi-Fi analyzér

**Stav:** implementováno v M1c; fyzické ověření na Xiaomi 15 je stále otevřené.
Náhrada základního přehledu Wi-Fi z WiFiman a Wi-Fi části Network Analyzeru. Výsledky
emulátoru ani napsaný kód se nepovažují za důkaz fyzického skenu.

### Implementační stav a známé mezery

- Hotové: foreground scan vyvolaný uživatelem, seznam dostupných AP, pásmo/kanál/střední
  frekvence/šířka, RSSI, security, standard, skutečné stáří cache, aktuální Wi-Fi spojení
  včetně IP/gateway/DNS a RX/TX link rate, filtrování/řazení, graf obsazení, historie
  vybraného BSSID a lokální doporučení kanálu.
- Wi‑Fi 80+80 MHz se vykresluje a hodnotí jako dva samostatné 80MHz segmenty. Dostupnost
  těchto údajů stále závisí na tom, zda je Android/ovladač vrátí v `ScanResult`.
- Android scan vyžaduje runtime `ACCESS_FINE_LOCATION` společně s
  `ACCESS_COARSE_LOCATION`; aplikace pokračuje jen s uděleným přesným oprávněním a zapnutou
  systémovou polohou. Nepoužívá `NEARBY_WIFI_DEVICES`. Scan je ruční a foreground-only.
  Throttled/nepotvrzený scan ponechá data označená jako cache; UI nezobrazuje odhadovaný
  odpočet, protože Android nedává aplikaci spolehlivý čas příštího povoleného scanu.
- OUI databáze není součástí aplikace: výrobce proto není známý. UI jen oznamuje, zda je
  BSSID lokálně administrovaná; tento bit sám neurčuje výrobce ani zařízení.
- Zabezpečení z `ScanResult.capabilities` zachovává `UNKNOWN` pro nepodporované/neznámé
  příznaky. WPA2/WPA3 enterprise klasifikace používá zveřejněné Android capability příznaky.
  Obecné `RSN-EAP` samo nerozliší WPA2-Enterprise od běžného WPA3-Enterprise; proto se
  uvádí jako WPA2 baseline. WPA3-Enterprise se uvádí pouze při jednoznačných příznacích
  SHA256 + MFPR + MFPC bez EAP/SHA1, nebo Suite-B-192. Viz [AOSP ScanResultUtil](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/util/ScanResultUtil.java)
  a [AOSP AccessPoint](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsLib/src/com/android/settingslib/wifi/AccessPoint.java).
- Android 12+ rediguje location-sensitive hodnoty `WifiInfo` z běžného
  `getNetworkCapabilities()`. Aktuální připojení používá jednorázový `NetworkCallback` s
  `FLAG_INCLUDE_LOCATION_INFO` od API 31 a páruje `NetworkCapabilities` i `LinkProperties`
  pro tentýž Wi‑Fi `Network`; na API 29–30 používá callback bez flagu. Callback má 4s
  timeout a odregistruje se při dokončení, zrušení i timeoutu. Viz [Android
  NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
  a [ConnectivityManager.getNetworkCapabilities](https://developer.android.com/reference/android/net/ConnectivityManager#getNetworkCapabilities(android.net.Network)).
- Doporučení je deterministické skóre překryvu z právě viditelných AP, ne doporučení
  regulátora. Neznámá sousední zařízení a DFS/regionální podmínky mohou výsledek změnit.
- Graf a historie jsou vizualizace klientských scan výsledků, ne spektrální analyzér.
  Schopnosti rádia, region, ovladač, oprávnění a scan throttling určují, co telefon vrátí.
- Fyzický checklist v [testovani.md](../testovani.md)
  je **NOT RUN**; žádná položka zatím není potvrzená na telefonu.

## 1. Co umí

- **Seznam AP**: SSID (skryté jako takové), BSSID, RSSI (dBm) s vizuální úrovní, kanál,
  střední frekvence, šířka kanálu (20/40/80/160/320), pásmo 2,4/5/6 GHz, zabezpečení
  (Open/WEP/WPA/WPA2/WPA3, ...), Wi-Fi standard (a/b/g/n/ac/ax/be), pravdivé označení „výrobce nedostupný“ (D-5),
  stáří údaje.
- **Graf obsazení kanálů** (křivky podle šířky jako ve WiFiman) pro každé pásmo.
- **Časový graf signálu** vybrané sítě/BSSID (historie v paměti; volitelně rolovat).
- **Doporučení kanálu**: skóre kanálů podle počtu a síly překrývajících se sítí; návrh
  nejméně zatíženého kanálu v každém pásmu.
- **Aktuální připojení**: SSID/BSSID, RSSI, rychlost linku (rx/tx), frekvence, standard,
  IP, gateway, DNS, bezpečnostní typ.
- Řazení a filtrování (pásmo, síla, název), označení právě připojené sítě.

Mimo rozsah M1c: měření propustnosti (speedtest), mapa pokrytí/heatmapa, Wi-Fi RTT/ranging,
bezdrátové audity (zachytávání paketů, deauth apod. — **nikdy**), spuštění na pozadí.

## 2. Omezení Androidu (zapsat do aplikace i dokumentace)

| Omezení | Důsledek | Přístup |
|---|---|---|
| Android omezuje četnost scanů podle verze a stavu foreground/background | Živý graf se nemusí obnovit po každém stisku | Aplikace spouští scan jen po ruční akci v popředí; při throttlingu ponechá cache se skutečným stářím a zobrazí omezení, bez smyšleného odpočtu |
| Výsledky skenu vyžadují přesné oprávnění polohy a zapnutou systémovou polohu | Bez potřebného oprávnění nelze získat nový scan | Aplikace žádá `FINE` a `COARSE` společně, vyžaduje grant `FINE`, oznamuje chybějící oprávnění nebo vypnutou polohu; `NEARBY_WIFI_DEVICES` nepřidává |
| `WifiInfo` SSID/BSSID bez oprávnění vrací zástupné hodnoty | Aktuální připojení může mít redigovanou identitu | Redigovaná/neznámá hodnota se ukáže jako skrytá či neznámá, ne jako odhad |
| `startScan` je od API 28 zastaralé a Android může požadavek odmítnout | Výsledek nemusí být nový | Sleduje se scan callback/receiver, freshness timestampu a stav volání; cache se neoznačí jako nový scan |
| HyperOS (Xiaomi) může zasahovat do scanů a polohy | Neočekávané chování | Měřit na telefonu; výsledek zapsat do `testovani.md` |

## 3. Technické poznámky

- **Čisté funkce v `commonMain` s testy:**
  - frekvence → kanál/pásmo: 2,4 GHz `(f − 2407) / 5` (kanál 14 = 2484 MHz), 5 GHz `(f − 5000) / 5`,
    6 GHz `(f − 5950) / 5`; kontrola 6 GHz pásma proti oficiální dokumentaci před implementací
    (zapsat zdroj do `zavislosti.md` nebo reportu);
  - překryv kanálů (šířka a střední frekvence → interval frekvencí, průnik);
  - skóre kanálu (váha podle RSSI, např. lineární převod dBm → mW);
  - přepočet dBm → úroveň 0–4 a procento;
  - klasifikace zabezpečení z `capabilities` řetězce;
  - flag lokálně administrované adresy (bit 2 prvního oktetu); OUI lookup není implementován,
    protože nebyl nalezen vhodný ověřený dataset s jasnou permisivní licencí.
- **Platformní:** `WifiScanner` nad `WifiManager`; `NetworkInfoProvider` nad `ConnectivityManager`
  + `WifiInfo`.
- **Zdroje fixtur:** unit testy používají zachycená data (seznam `ScanResult`-like záznamů v JSON)
  a nesmí obsahovat reálná SSID/BSSID cizích sítí — anonymizovat.
- **Graf:** Compose Canvas, bez těžké grafové knihovny, pokud se prokáže, že stačí; jinak
  knihovnu zapsat do `zavislosti.md`.

## 4. Kritéria funkce

- Převody frekvence ↔ kanál odpovídají referenční tabulce pro všechna tři pásma (unit test).
- Překryv kanálů a skóre jsou deterministické a otestované na ručně spočtených příkladech.
- Parser zabezpečení rozpozná sadu `capabilities` řetězců (WPA3-SAE, WPA2-PSK-CCMP, OWE, open…).
- Aplikace bez oprávnění ukáže vysvětlení, ne prázdnou obrazovku nebo pád.
- V emulátoru (virtuální Wi-Fi) nepadá a zobrazuje, co emulátor vrátí; výsledek nehlásit
  jako důkaz funkce na reálných AP.
- **Fyzický test (správce):** reálný scan ukáže známé domácí AP s kanály a šířkami, které souhlasí
  s administrací routeru; graf kanálů odpovídá WiFiman v relativním pořadí; throttling
  zapnutý/vypnutý — rozdíl v četnosti obnovení zapsat do reportu.

## Revize M1: opravy první fáze (2026-10-26)

W1–W4, W14: doporučení se vytváří pouze pro pásmo s alespoň jedním pozorovaným AP;
UI uvádí omezení odhadu. Přechod PSK+SAE je `WPA2_WPA3`, `OWE_TRANSITION` se výslovně
odlišuje od čistého OWE. Výsledky se slučují podle BSSID (bez rozdílu velikosti písmen),
přednost má novější platný timestamp, při shodě nebo jeho absenci silnější RSSI.
Prázdné/null BSSID nezpůsobí kolizi klíče seznamu: nerozlišitelné anonymní výsledky
se slučují podle SSID/frekvence/zabezpečení; neznamená to identifikaci skutečného AP.

Převod kanálu kontroluje hranice a 5MHz rastr; 2477 a 7125 MHz už nevytvářejí
kanály 14/235. 2484 a 5935 MHz zůstávají zvláštní případy. Rozsahy jsou odvozeny z
[AOSP ScanResult](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/ScanResult.java),
ověřeno 2026-10-26: 2412–2472 + 2484, 5160–5885, 5955–7115 + 5935 MHz.
Aplikace při neplatném rastru vrací `null`; nejde o ověření regulace kanálu v dané zemi.
X2: aktuální připojení preferuje IPv4 výchozí bránu; IPv6 je až fallback.

Automatické testy: `WifiRevisionTest` a `WifiDomainTest`. Výsledek běhu a hash APK
patří do reportu 06; fyzické ověření zůstává NOT RUN.

## Dokončení 2c a Wi-Fi UI (2026-10-26)

Následující popis vyjadřuje implementaci, nikoli přijetí na telefonu. Výsledky buildů,
JVM testů a screenshoty konkrétního APK eviduje report 06.

- Doporučení vyřazuje právě připojené BSSID, pokud jeho identitu Android poskytl, a
  AP vyloučená výslovnou volbou uživatele (viz WU4 níže). Zahrnuje zbývající AP
  přítomná v poslední potvrzené neprázdné sadě. Politika R6/A-3 níže
  nahrazuje původní vyřazování jednotlivých AP po 30 s. Pásmo bez sousedních AP
  nedostane doporučení. Čas používá monotónní `elapsedRealtime`, nikoli občanské hodiny.
- V 2,4 GHz se hodnotí pouze kanály 1/6/11 s šířkou 20 MHz. Pro 5/6 GHz lze zvolit
  kandidáty 20/40/80/160 MHz; údaj v doporučení je **střední kanál**, ne volba primárního
  kanálu routeru. Skóre je průměrná výkonová váha geometrického překryvu v mW, nikoli
  naměřený šum, vytížení, throughput nebo regulatorní povolení. UI ukazuje šířku,
  skóre, stáří datasetu a varování u kandidátů zasahujících do DFS spektra. Nabídka
  globálních kandidátů sama nepotvrzuje podporu telefonu, routeru nebo dané země.
- Tabulky středů a 6GHz rastr byly ověřeny 2026-10-26 v
  [AOSP operating classes](https://android.googlesource.com/platform/external/wpa_supplicant_8/+/master/wpa_supplicant/op_classes.c)
  a [AOSP channel conversion](https://android.googlesource.com/platform/external/wpa_supplicant_8/+/master/src/common/ieee802_11_common.c).
  Jde o ověření číselných parametrů, kód nebyl přebírán.
- Seznam AP se publikuje před callbackem aktuálního připojení, který může čekat až
  4 s. Prázdná cache nepřepíše dříve získaná AP ani jejich původní timestampy.
  `startScan=false` nebo chybějící čerstvý výsledek znamená **nepotvrzený scan**;
  samotné API nerozlišuje throttling od dalších příčin. Výjimka značí chybu, nikoli
  potvrzené omezení četnosti.
- Graf obsazení obsahuje frekvenční osu, RSSI osu, barevnou legendu SSID/BSSID a
  přístupný popis. Grafy používají pevnou světlou/tmavou paletu s tvary a výpočtem
  kontrastu (viz WU5 níže); texty a karty nadále používají MaterialTheme. Historie má jméno/BSSID sledovaného AP
  a skutečnou monotónní časovou osu; uchovává nejvýše 90 unikátních novějších vzorků.
  Opětovné klepnutí na vybrané AP výběr zruší a historii zachová. Výběr jiného AP
  založí novou historii; anonymní BSSID nelze spolehlivě sledovat.
- Filtr SSID, pásmo, řazení a šířka kandidáta používají `rememberSaveable`; přepínání
  záložek využívá společný `SaveableStateHolder`. StateFlow se sbírá přes
  `collectAsStateWithLifecycle`. Stáří se přepočítává po sekundě pouze při přítomnosti
  Wi-Fi obrazovky ve stromu a lifecycle STARTED. Po návratu z nastavení se znovu
  zkontrolují oprávnění a aktuální připojení; scan se nespouští automaticky.
- UI rozlišuje chybějící oprávnění, pouze přibližnou polohu, další dialog nenabízený
  systémem, vypnutou polohu a vypnutou Wi-Fi. Nabízí Nastavení aplikace/Nastavení polohy.
  Trvalé odmítnutí se odvozuje až po předchozí žádosti a `shouldShowRequestPermissionRationale=false`;
  příznak provedené žádosti se ukládá v lokálních preferences, bez identifikátorů sítí.
  [Oficiální workflow](https://developer.android.com/training/permissions/requesting), ověřeno 2026-10-26.
- `ssid` i neznámý standard zůstávají v doméně `null`; překlad vzniká až v UI. Otevřená
  síť se vrací pouze při absenci šifrovacích příznaků v rozpoznané sadě nešifrovacích
  capabilities (ESS/IBSS/WPS/UTF-8/WMM). Neznámý token zůstává UNKNOWN, není důvodem
  označit síť za otevřenou nebo bezpečnou.
- W10 ověřeno 2026-10-26: [Android Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
  výslovně vyžaduje FINE pro `startScan/getScanResults` i při target API 33+. Aplikace
  nevolá hotspot/P2P/Aware/RTT API, pro která je požadováno NEARBY_WIFI_DEVICES;
  toto oprávnění se nepřidává. [Scan workflow](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
  uvádí pro API 29+ i CHANGE_WIFI_STATE a zapnutou systémovou polohu.

OUI databáze, mDNS starých API, automatické skeny na pozadí a spektrální měření jsou
mimo tento řez. Screenshoty světlo/tma, CS/EN, fontScale 2.0 a ověření IME/polohy/OEM
nejsou prokázány samotnými unit testy; dokládají se samostatným během nebo zůstávají NOT RUN.

Šířka na kartě aktuálního připojení se doplňuje pouze z potvrzené datové sady nejvýše
5 minut staré se stejnou BSSID a primární frekvencí. Po vypršení nebo bez takového
záznamu zůstává neznámá; jde o údaj ze scanu, nikoli průběžné měření šířky připojení.


## Revize 07 — čerstvost datové sady R6/A-3 (2026-10-26)

Předpoklad A-3 se používá podle zadání 07 bez čekání na další potvrzení. Referenční
čas je okamžik přijetí **potvrzeného neprázdného scanu** v monotónních hodinách
(`WifiAnalyzerState.lastUpdatedElapsedMs`), nikoli nejnovější timestamp některého AP.
Do 30 sekund včetně není varování, nad 30 sekund UI uvádí „Data nejsou čerstvá“.
Do 5 minut včetně zůstává doporučení dostupné; nad 5 minut se skryje a nabídne
nový scan. Čas přijetí je viditelný u výsledků i doporučení. Tyto prahy jsou politika
aplikace, nikoli odvozené omezení Android API.

Obsah sady se během čekání nemění. AP nevypadne z grafu nebo skóre jen proto,
že zestárne jeho timestamp. Nový potvrzený neprázdný scan nahradí celou sadu;
AP nepřítomné v této sadě už v grafu ani skóre není. Neplatná frekvence zůstává
v seznamu, ale nevytváří grafový kanál ani doporučení. Graf a seznam ponechávají
snapshot i po pěti minutách, s označeným stářím; expiruje doporučení, nikoli historie.
Per-AP stáří na kartě je diagnostika původního měření, nezávislá na věku přijetí sady.

**Interpretace cache:** samotné načtení cache nevytvoří čas potvrzeného scanu.
První nepotvrzená cache může naplnit seznam/graf, ale doporučení nemá. Pokud už
existuje předchozí sada, nepotvrzený nebo prázdný výsledek zachová její obsah i čas;
opakované neúspěšné scany ji nemohou omladit. Potvrzení nadále vyžaduje úspěšnou
scan cestu a důkaz alespoň jednoho nového timestampu z doby požadavku. Android
nemusí vysvětlit příčinu odmítnutí; chybějící potvrzení není samo důkaz throttlingu.

Výběr AP a uchovaná historie při stárnutí sady nezaniknou. Vzorky mají skutečný
per-AP timestamp, přidává se jen novější platný vzorek z potvrzené sady v jejím
povoleném časovém okně. Cache, duplicitní timestamp ani tick UI nevytvářejí další
měření. Neznámý/budoucí čas se nevydává za čerstvý údaj.

Čisté regresní testy jsou v `WifiDatasetPolicyTest` (hranice 29/30/31 s,
299/300/301 s, neznámý/budoucí čas, cache, prázdný scan, změna členství, historie)
a upravených `WifiRevisionTest`. Původní očekávání 30s vyřazení se změnilo podle
výslovného N7-104; bezpečnostní klasifikace, deduplikace a platnost frekvencí se
neoslabují. `WifiFreshnessEvidence` v `androidTest` vykresluje skutečné produkční
komponenty nad označenými syntetickými daty s pevným časem. Je to UI test stavů,
nikoli rádio/scan důkaz. Výsledky běhů, screenshoty a SHA APK doplní report 08.


## Revize 07 — WU3/WU4/WU5/WU14/WU16 (2026-10-26)

Při shodném skóre se kandidáti řadí podle potřeby DFS (nejprve bez DFS), pak podle
čísla kanálu. Skóre má přednost: DFS se nezakazuje, pouze nerozhodne remízu v jeho
prospěch. Regulační dostupnost zůstává neověřená.

Připojené BSSID se ze skóre vylučuje automaticky. Uživatel může navíc zapnout
„Vyloučit ze skóre stejné SSID jako připojená síť“ nebo označit jednotlivou BSSID
jako „Moje AP“. Stejné SSID není důkaz stejného vlastníka; tato volba je výchozí
vypnutá, pracuje s přesnou shodou neprázdného SSID a nepovažuje všechny skryté sítě
za jednu síť. Označené AP zůstává v seznamu/grafu, vyloučené je pouze ze skóre.
Volby žijí ve ViewModelu, přežijí rotaci, přepnutí záložky i nový scan, ale po
ukončení procesu se neobnovují z disku. Historie ani zvolená sledovaná BSSID se
jejich změnou nemažou. Karta uvádí, že je AP ze skóre vyloučeno. Zaškrtávací řádky
mají propojený popisek a `Role.Checkbox`; změna vlastníka nespouští výběr historie.

Graf zobrazuje nejvýše šest nejsilnějších AP v každém pásmu; remízu síly řeší stabilní
klíč BSSID. V legendě i u vrcholu křivky se používá shodná barva a tvar (kruh,
čtverec, trojúhelník, kosočtverec, plus, kříž). Číslovaná legenda obsahuje SSID,
BSSID a RSSI. Počet zobrazených/nezobrazených AP je viditelný. Toto omezení je
pouze zobrazovací: seznam a výpočet skóre nadále používají kompletní datovou sadu.
Přístupný popis grafu uvádí identitu a RSSI vykreslených sérií.

Pevné neprůhledné sRGB palety jsou v `wifi/WifiChartPalette.kt`. Graf i barevná značka
legendy mají vlastní stejné pozadí; nepřebírají podobné akcenty Material tématu.
Čára historie používá první barvu téže palety. Rozestupy Wi-Fi UI jsou sjednoceny
přes `Spacing`; rozměry grafu a síla čáry zůstávají samostatné geometrické hodnoty.
České instrukce používají vykání, neznámý standard je označen slovem „neznámý“.

### Kontrast grafiky

Metoda ověřena 2026-10-26 podle [W3C Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)
a [W3C definice relativního jasu a kontrastu](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).
Pro neprůhledné sRGB hodnoty se složky linearizují s prahem 0,04045; jas je vážený
součet (0,2126; 0,7152; 0,0722), poměr `(vyšší jas + 0,05)/(nižší jas + 0,05)`.
Práh pro netextovou grafiku je 3:1; porovnání proběhlo před zaokrouhlením.

| Prvek | Světlá barva na #FAFAFA | Kontrast | Tmavá barva na #151A22 | Kontrast |
|---|---|---:|---|---:|
| Kruh / historie | #0072B2 | 4,968:1 | #56B4E9 | 7,567:1 |
| Čtverec | #A74700 | 5,676:1 | #E69F00 | 7,752:1 |
| Trojúhelník | #007A62 | 5,080:1 | #35C59A | 7,985:1 |
| Kosočtverec | #A52A72 | 6,339:1 | #F080BA | 7,069:1 |
| Plus | #6A4CAA | 6,235:1 | #B5A0F2 | 7,712:1 |
| Kříž | #676700 | 5,721:1 | #F0E442 | 13,204:1 |
| Osy | #565B66 | 6,524:1 | #BCC6D5 | 10,124:1 |

Výpočet čte přímo implementované barvy: `python3 -I artifacts/n7/wifi-chart-contrast.py`;
nezkrácené výsledky a metoda jsou v `artifacts/n7/wifi-chart-contrast.json` a `.log`.
Regresní `WifiChartPaletteTest` kontroluje všechny barvy/osy v obou režimech a
referenční hodnoty výpočtu. Jde o kontrast zdrojových barev vůči pozadí, nikoli
anti-aliasovaných krajních pixelů, vzájemného překrytí křivek nebo kompletní audit
přístupnosti. Rozlišení nezávislé na barvě podporují tvary a text. Čitelnost s velkým
písmem a skutečný rendering dokládají samostatné screenshoty, ne tento výpočet.

`WifiUiPolicyTest` pokrývá DFS remízy, prioritu nižšího skóre, explicitní volby
vlastních AP, neznámá/odlišná SSID, zachování stavu po scanu a zobrazovací limit
bez ořezání skórovaných dat. R6 helper `WifiFreshnessEvidence` zůstává kompatibilní;
časová politika se při tomto doladění nemění. Výsledky centrálních buildů a runtime
jsou v reportu 08; zde se nepředjímá průchod nově napsaných testů ani přijetí M1.

## Spektrum a zdraví kanálů (M2)

**Stav (2026-10-26):** napsaná datová vrstva a čistá logika s unit testy
(`shared/.../wifi/Spectrum.kt`, `BssLoad.kt`, `SpectrumTest`) a čtení prvku BSS Load
v mapování `ScanResult`. UI (composables) dodá jiný řez. Emulátor ani telefon: **NOT RUN**.

### Co se počítá

- **Spektrum:** pro každé pásmo lineární frekvenční osa v MHz. 2,4 GHz kanály 1–13
  (14 jen při AP na 2484 MHz), 5 GHz globální 20MHz kanály 36–64, 100–144 a 149–177,
  6 GHz 1–233 po 4 (zvláštní kanál 2 jen při AP na 5935 MHz). Osa jde od dolní hrany
  prvního po horní hranu posledního kanálu; mezery bez kanálu (5 GHz: 5330–5490 a
  5730–5735 MHz) mají skutečnou šířku, osa tedy není index kanálů a spojené šířky
  zachovávají poměr. Popisky: všechny kanály 2,4 GHz; 5 GHz 36, 52, 100, 116, 132, 149, 165;
  6 GHz 1, 33, 65, …, 225. Rastr není potvrzením regionálního povolení.
- **AP jako lichoběžník:** obsazený rozsah z `occupiedFrequencySegments` (střední
  frekvence a šířka z `ScanResult`; bez středu primární kanál ±šířka/2; 80+80 dva
  segmenty), vrchol = RSSI. Horní plošina je zúžena o 10 % šířky na každé straně, jde jen
  o vizuální tvar, ne o spektrální masku. Pořadí: nejsilnější první, remíza podle
  stabilního klíče `accessPointKey`. Spektrum nemá zobrazovací limit (graf obsazení dál
  kreslí nejvýše 6 AP) a stejně jako graf zůstává i po vypršení doporučení.
- **Počty AP** pro přepínač Vše / 2,4 / 5 / 6 GHz: jen AP s platnou frekvencí
  (`snapshotAccessPoints`), z aktuální datové sady.
- **Zdraví kanálu** pro každý 20MHz primární kanál osy:
  - *Překryv* = `overlapScore` (součet výkonu sousedních AP v mW vážený podílem
    překryvu s 20MHz kanálem) převedený na dBm. Jedno AP přímo na kanálu s −70 dBm dá
    −70 dBm; AP překrývající kanál z poloviny se započte poloviční váhou.
  - *Vytížení* = nejvyšší „channel utilization“ z prvku BSS Load mezi AP, jejichž
    **primární** kanál je tento kanál. Hodnotu měří a hlásí samotné AP (podíl času,
    kdy vnímalo médium jako obsazené). Mnoho domácích routerů prvek nevysílá; chybějící
    údaj znamená „neznámé“, nikoli „volné“, a stav nezlepšuje.
  - Výsledek je horší z obou hodnocení: vytížení může závažnost jen zvýšit.

| Hodnocení | Překryv (od) | Vytížení BSS Load (od) |
|---|---|---|
| Výborný | pod −82 dBm nebo žádný překryv | pod 20 % |
| Dobrý | −82 dBm | 20 % |
| Průměrný | −72 dBm | 40 % |
| Špatný | −62 dBm | 70 % |

Hranice patří vyšší kategorii (např. přesně −72 dBm = Průměrný). Překryv se před
porovnáním zaokrouhlí na 0,01 dB. Práh −82 dBm odpovídá obvyklé úrovni detekce
preambule 20MHz kanálu (CCA); ostatní prahy jsou **politika aplikace** zvolená pro
čitelnost, ne normativní hodnoty. Konstanty jsou v `Spectrum.kt` (`HEALTH_*`).

- **Vlastní AP:** připojené BSSID, AP označená „Moje AP“ a volitelně stejné SSID
  (`isOwnAccessPoint`, stejně jako doporučení) se nezapočítávají do překryvu ani počtu
  překrývajících se AP — otázka zní, jak kanál zatěžují ostatní. Jejich hodnota BSS Load
  se ale použije, protože popisuje obsazenost kanálu (provoz všech sítí). Trade-off: u
  vlastního AP s vysokým vlastním provozem může kanál vypadat hůř, než by pomohla změna
  kanálu.
- **Stáří dat:** zdraví vzniká jen tehdy, když `wifiDatasetAge(...).allowsRecommendation`
  (stejná politika jako doporučení, do 5 minut). Jinak `ChannelHealthReport.Stale` a UI
  nabídne nový scan. Pásmo bez jediného pozorovaného AP vrací `NoData`: telefon ho nemusí
  skenovat, prázdné pásmo není důkaz volného spektra.
- U každého kanálu se dále uvádí příznak DFS (`candidateMayRequireDfs`, 20 MHz) a zda
  na něm vysílá připojené AP.

**Zdraví kanálu je heuristika, ne měření vysílacího času (airtime).** Telefon vidí jen
beacony/probe odpovědi AP, nikoli klienty, provoz ani rušení zařízení, která nejsou Wi-Fi.

### BSS Load (IEEE 802.11, prvek 11)

Tělo má právě 5 B: počet stanic uint16 LE, vytížení kanálu uint8 (n/255) a dostupná
kapacita přijetí uint16 LE. `parseBssLoad` jinou délku odmítne (`null`); procento je
`round(n·100/255)` (0 → 0 %, 255 → 100 %). Android vrací prvky přes
`ScanResult.getInformationElements()` od API 30; na API 29 zůstávají
`stationCount` i `channelUtilizationPercent` `null`. Kopie bufferu je omezena na
255 B (maximum těla prvku), framework buffer se nespotřebuje (`duplicate()`).

### Testy a mezery

`SpectrumTest` (15 testů): osy a tiky všech pásem, mezery 5 GHz, rozsah 20/40/80/160 a
80+80 MHz, řazení, počty, hranice prahů, zvýšení závažnosti vytížením, vyloučení
vlastních AP, `Stale`/`NoData` a parser (platný, krátký, dlouhý, 0/255).
Fyzický checklist (doplní se s UI): hodnoty BSS Load proti administraci routeru, který
prvek vysílá; chování na API 29 (bez údaje); porovnání pořadí s WiFiman — **NOT RUN**.
## Živá telemetrie (M2)

**Stav (2026-10-26):** napsaná datová vrstva a `WifiLiveViewModel` (bez UI). Automatické
testy viz níže; emulátor ani fyzický telefon zatím **NOT RUN**. Cílem je obdoba obrazovky
„Signal“ ve WiFiman (záložky Signal / Throughput / Latency s živými grafy).

### Zdroje dat a frekvence

| Údaj | Zdroj | Frekvence | Poznámka |
|---|---|---|---|
| RSSI, RX/TX link rate, frekvence, SSID, BSSID | jeden dlouhodobě registrovaný `NetworkCallback` pro Wi‑Fi (API 31+ s `FLAG_INCLUDE_LOCATION_INFO`), stejný požadavek jako `WifiViewModel.readCurrent`; rádiové hodnoty doplňuje `WifiManager.connectionInfo` | 1 Hz | `startScan` se nevolá, scan throttling se neuplatní. Identita (SSID/BSSID) od API 31 vždy z callbacku; `connectionInfo` se pro RSSI/link rate použije jen při shodné BSSID (pod API 31 je jediným zdrojem) |
| Propustnost zařízení RX/TX | `TrafficStats.getTotalRxBytes/getTotalTxBytes`, rozdíl za ~1 s → Mb/s | 1 Hz | **celé zařízení** (všechna rozhraní), viz limity |
| Latence k bráně | ICMP echo (`WifiIcmp.echo`) na IPv4 výchozí bránu z `LinkProperties` Wi‑Fi sítě | 1 Hz, timeout 1 s | max. 1 echo v letu na cíl |
| Latence do internetu | ICMP echo na nastavitelný cíl, výchozí `1.1.1.1` | 1 Hz, timeout 1 s | jen číselná IPv4; odmítá `0.0.0.0/8`, `127.0.0.0/8`, `224.0.0.0/3` |
| Latence DNS | `DnsResolver.query` (záznam A, `FLAG_NO_CACHE_LOOKUP`) na Wi‑Fi `Network`, výchozí jméno `one.one.one.one` | každých 10 s, timeout 2 s | zrušení přes `CancellationSignal` jako v `LanBackend` |
| Roaming | změna normalizované BSSID mezi po sobě jdoucími snímky | 1 Hz | nejvýše 50 událostí; změna po výpadku/pauze se označí `afterGap` |

Řady jsou kruhové buffery po 300 vzorcích (5 min při 1 Hz; DNS 30 vzorků = 5 min). Vzorek
`null` znamená „měřeno, ale ztraceno“ (timeout, bez Wi‑Fi) a počítá se do ztrátovosti;
mezera v časech znamená „neměřeno“ (obrazovka pozastavena). Statistiky: poslední, min,
průměr, max, počet, ztrátovost v % a jitter (průměr absolutních rozdílů po sobě jdoucích
platných vzorků; ztracené vzorky se přeskakují). Kvalita signálu: vynikající ≥ −55 dBm,
dobrý ≥ −67 dBm, uspokojivý ≥ −75 dBm, jinak slabý (konstanty `SIGNAL_*_MIN_DBM`
v `LiveTelemetry.kt`). Čistá logika je v `shared/.../wifi/LiveTelemetry.kt`, Android část
v `androidApp/.../wifi/live/`.

### Životní cyklus a hranice

- Vzorkuje se **jen při viditelné obrazovce v popředí**: UI volá `setActive(true)` při
  zobrazení a `setActive(false)` při odchodu nebo přechodu aplikace do pozadí. `false`
  okamžitě zruší všechny smyčky, rozpracované ICMP/DNS a odregistruje callback. Žádná
  foreground služba, nic v manifestu, nic na pozadí.
- Žádné nové oprávnění. Bez `ACCESS_FINE_LOCATION` nebo při vypnuté systémové poloze
  Android rediguje SSID/BSSID: signál a latence se měří dál, roaming ale nelze
  rozpoznat (stav `PERMISSION_MISSING` / `LOCATION_DISABLED`). Oprávnění se uděluje na
  obrazovce Wi‑Fi analyzéru.
- Chybové a nedostupné stavy jsou typované (`WifiLiveIssue`, `LiveProbeStatus`,
  `LiveDnsStatus`) s texty v `strings_wifi.xml`; text výjimek se do UI nedostává.
- Při ztrátě Wi‑Fi dostávají řady signálu a latence vzorky `null` a stav ukazuje `NO_WIFI`.

### Limity

- **Obnova RSSI závisí na frameworku a ovladači** (typicky jednou za ~3 s při zapnutém
  displeji, výrobce to může měnit). Vzorkování 1 Hz proto často opakuje stejnou hodnotu;
  nejde o chybu měření. Link rate je vyjednaná PHY rychlost, ne skutečná propustnost.
- **Propustnost je za celé zařízení**: `TrafficStats.getTotal*` sčítá všechna rozhraní
  (Wi‑Fi, mobilní data, VPN, tethering). Pole se proto jmenují `deviceRxMbps/deviceTxMbps`.
  Vrací‑li zařízení `UNSUPPORTED` (−1), řada zůstane prázdná (`TRAFFIC_STATS_UNAVAILABLE`);
  pokles čítače (reset) se zaznamená jako mezera, nikdy jako záporná rychlost.
- **Internetový cíl dostává 1 ICMP echo za sekundu jen po dobu, kdy je obrazovka
  viditelná.** Výchozí `1.1.1.1` je veřejný resolver Cloudflare; uživatel může nastavit
  vlastní IPv4. Jde o běžný ping, ne o sken; zásady z [bezpecnost.md](../bezpecnost.md)
  platí beze změny (jen vlastní síť a vlastní cíle).
- Echo na bránu i do internetu je **vázané na Wi‑Fi `Network`** stejně jako LAN ICMP
  (`network.bindSocket`), nikdy nepřepadne na mobilní data. Nepovolí‑li zařízení
  neprivilegovaný ICMP socket, stav je `ICMP_UNAVAILABLE` a vzorky se nepřidávají.
- DNS latence měří dotaz přes resolver Wi‑Fi sítě; při zapnutém Private DNS (DoT) zahrnuje
  i jeho režii. Druhý dotaz na stejné jméno může zodpovědět cache resolveru v síti.

### Ověření

- `LiveTelemetryTest` (commonTest): ring buffer (kapacita, vytlačení, mimo pořadí),
  statistiky se ztrátou a jitterem, prahy kvality na hranicích, propustnost s resetem /
  `UNSUPPORTED` / nulovým intervalem, roaming s normalizací, redigací a limitem 50, validace
  cílů. `LiveRadioSourceTest` (androidApp JVM): výběr zdroje rádiových hodnot a normalizace SSID.
- Fyzický checklist (správce, NOT RUN): RSSI se mění při pohybu, roaming mezi dvěma AP
  vytvoří událost, latence k bráně odpovídá pingu z jiného klienta, propustnost reaguje na
  stahování, po odchodu z obrazovky ustanou ICMP pakety (ověřit na routeru/tcpdumpu).

## Přehled (M4 A3, 2026-10-27)

Záložka Signál se jmenuje **Přehled** (EN Overview); tagy `tab_signal` a `signal_screen`
i segment Živě | Rychlost zůstávají. Stav: kód, unit testy, lint a build; emulátor a telefon
NOT RUN (checklist [testování §13](../testovani.md#13-fyzický-checklist-m4), M4-20 a dál).
Mobilní část: [mobilni-sit.md §7](mobilni-sit.md#7-ui-v-přehledu-m4-a3).

- **Hlavička** podle typu připojení: SSID (Wi-Fi), „Mobilní data / operátor · standard“,
  „Ethernet“, „VPN / přes <podkladová síť>“ (SSID nebo operátor, je-li známý), „Bez připojení“.
- **Karta cesty „Připojení“**: poskytovatel (jméno z Team Cymru přes `shortProviderName`,
  veřejná IPv4 a IPv6, „AS…“) → přístupový bod (jen Wi-Fi: pásmo a kanál) → „Tento telefon“
  s lokální IP aktivní sítě. Stavy: zjišťuji, „za NAT operátora“ (`isBehindCarrierNat`: lokální
  IPv4 v 100.64/10, nebo na mobilních datech jiná než veřejná), nedostupné, vypnuto, bez
  připojení. Menu: „Obnovit“ (bez cache) a vypínač „Zjišťovat veřejnou IP“. Načítání, cache
  a soukromí: [bezpečnost § Veřejná IP](../bezpecnost.md#veřejná-ip-a-poskytovatel-m4-2026-10-27).
- **Latence na aktivní síti.** Internetový cíl (ICMP) a DNS běží na výchozí síti z
  `ActiveNetworkMonitor` (Wi-Fi, mobilní data, VPN), brána jen na Wi-Fi. Bez aktivní sítě je
  stav „Bez připojení“ (`LiveProbeStatus.NO_NETWORK`, `LiveDnsStatus.NO_NETWORK`). Stejná smyčka
  `latencyLoop(LiveProbe)` pro bránu i internet, jen jiná síť a cíl. S VPN jde internetový ping
  tunelem (dříve vázaný na Wi-Fi).
- **Řádek DNS (O-5):** pod popiskem „DNS“ je IP prvního resolveru aktivní sítě
  (`LinkProperties.getDnsServers`), nebo „Soukromé DNS · host“ při aktivním Private DNS
  (v oportunistickém režimu bez jména IP resolveru); na druhém řádku dotazované jméno.
- **Řádky Latence** mají poznámku na druhém řádku pod popiskem (mono `dim`); hodnota vpravo
  nejvýš 55 % šířky, mezera 12 dp zůstává i na 360 dp s písmem 1,3. Ztráta paketů a jitter
  ukazují bez Wi-Fi jen hodnotu internetu.
- **Roaming:** čas vlevo, změna pásma („2,4 → 5 GHz“) nebo kanálu („k. 36 → 100“) jako štítek
  (`roamingChange`), „po výpadku“ jako štítek warn, BSSID mono vpravo; na 412 dp nejvýš dva řádky.

