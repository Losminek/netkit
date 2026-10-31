# Funkce: Wake-on-LAN

**Stav:** specifikace pro M0 + M1a (první svislý řez). Náhrada aplikace Wake-on-LAN.

## 1. Co umí

- Seznam uložených zařízení: název, MAC, cílová adresa, UDP port, volitelně IP/hostname
  pro kontrolu probuzení, poznámka.
- Odeslání magic packetu jedním klepnutím; zpětná vazba (odesláno / chyba / zařízení odpovídá).
- Automatická broadcast adresa podle aktuální sítě telefonu (IP + prefix → `x.y.z.255`),
  ruční přepis, nebo limited broadcast `255.255.255.255`.
- Volitelné opakování (např. 3× po 250 ms) a kontrola probuzení (ping nebo TCP probe po N s).
- Přidání, úprava, smazání, řazení; export a import seznamu do JSON (bez tajemství)
  a import/export CSV ve formátu aplikace Wake On Lan (Mike Webb).

Mimo rozsah M1a: SecureOn heslo (6 B za paketem), zástupce na plochu a widget (M2), napojení
na LAN skener „probudit tento host“ (M2), WoL přes internet s port-forwardem (jde pokrýt tím, že
cíl může být hostname/veřejná IP a port; dokumentace ovšem nic o veřejných adresách neobsahuje).

## 2. Technické detaily

- **Magic packet** = 6× `0xFF` + 16× opakovaný 6bajtový MAC = **102 B**. Čistá funkce v `commonMain`
  s testy.
- **MAC parser** přijímá `AA:BB:CC:DD:EE:FF`, `AA-BB-…`, `AABB.CCDD.EEFF` a `AABBCCDDEEFF`
  (bez rozlišení velikosti písmen), odmítá nesprávnou délku, nehexadecimální znaky
  a víceznakové oddělovače. Multicast/broadcast MAC jako cíl odmítnout (varování).
- **Port:** výchozí UDP 9 (alternativně 7). Povolit 1–65535.
- **Odeslání (Android):** `DatagramSocket` s `broadcast = true`. Pokud je aktivní více sítí
  (Wi-Fi + mobilní data), socket **navázat na Wi-Fi síť** (`ConnectivityManager` →
  `Network.bindSocket`), jinak může paket odejít mobilním rozhraním a nikdy nedorazí do LAN.
  Ověřit na zařízení.
- **Broadcast adresa:** z `LinkProperties` (IP + prefix). Výpočet v `commonMain`, vstup je
  `(ip, prefix)`, výstup adresa; test na `/24`, `/16`, `/25`, `/30`, `/31`, `/32`.
- Oprávnění: `INTERNET`, `ACCESS_WIFI_STATE`. `CHANGE_WIFI_MULTICAST_STATE` WoL nepotřebuje
  (odesílání, ne příjem). Další nepřidávat.
- Android nepotřebuje `ACCESS_FINE_LOCATION` pro zjištění IP/prefixu z `LinkProperties`
  (ověřit; SSID ne).

## 3. Datový model

```text
WolDevice(id, name, mac, targetMode: AUTO_BROADCAST | LIMITED_BROADCAST | CUSTOM, customTarget?,
          port = 9, probeHost?, probePort?, note?, createdAt, updatedAt)
```

Perzistence viz [architektura.md](../architektura.md) §5; zařízení nejsou tajná, ale patří
mezi osobní údaje sítě (MAC), proto platí `allowBackup=false`.

## 4. UI

- Seznam karet: název, MAC (zkráceně), stav posledního odeslání, tlačítko „Probudit“.
- Přidat/upravit: pole s validací MAC v reálném čase, přepínač cíle (auto / limited / vlastní),
  port, volitelná kontrola probuzení.
- Chyby lidsky: „Telefon není připojen k Wi-Fi“, „Neplatné MAC“, „Odeslání selhalo: <důvod>“.

## 5. Kritéria funkce (podklad pro AC v promptech)

- Magic packet pro známý MAC je přesně 102 B se správným obsahem (unit test).
- Parser přijme čtyři formáty a odmítne neplatné vstupy (unit test, tabulka případů).
- Broadcast adresa pro vzorové (ip, prefix) odpovídá referenčním hodnotám (unit test).
- Odeslání na `10.0.2.2:9` z emulátoru zachytí UDP listener na hostu s 102 B a správným MAC.
- Zařízení přežije restart aplikace.
- **Fyzický test (správce):** zapnutý stroj s WoL v BIOSu a OS se vypne, aplikace jej probudí;
  ověřit i s aktivními mobilními daty (paket jde přes Wi-Fi).

## 6. Doplnění D-4 (2026-10-26, čeká runtime a fyzický test)

Schéma2 přidává repeatCount (výchozí3, rozsah1–10), repeatIntervalMs (250,
50–2000), probeEnabled (false) a probeDelaySeconds (20,1–120). Čtení schématu1
přidá tyto výchozí hodnoty, ostatní data zachová; další zápis/export je schema2.
Staré uložené probeHost/probePort samy kontrolu nezapínají.

Jedna operace vybere Wi-Fi jednou a všechny pakety i pozdější DNS/TCP/ICMP
kontrola použijí stejnou Network. Žádný fallback. TCP OPEN i RST potvrzují,
že zařízení odpovídá; timeout sám neznamená vypnuté zařízení. Zákaz ICMP, ztráta sítě nebo
chyba resolveru mají stav kontrola nedostupná, nikoli vypnuté zařízení.
Kontrola je zrušitelná; zrušení po odeslání nemění odeslané pakety na chybu.

Pořadí je uložené jako pořadí seznamu; řazení podle názvu a ruční nahoru/dolů
jsou persistované operace. Editace zařízení zachovává jeho pozici.
SAF JSON import validuje celý soubor (UTF-8, schema1/2, jedinečná ID, všechny
položky, limit1MiB), potom ukáže náhled přidávaných/přepisovaných zařízení. Až potvrzení
uloží atomicky celý výsledek; odmítnutí nic nemění. Export generuje snapshot
při návratu ze SAF, limit platí také pro výsledek sloučení. Při chybě exportu
se aplikace pokusí odstranit neúplný dokument; provider může odstranění odmítnout.

Testy: WolRevisionTest (migrace, import/limit, merge, opakování/probe cancellation),
DeviceSerializerTest a dosavadní controller testy. Skutečné výsledky viz report06.

### Import z cizího souboru (R4, 2026-10-26)

Import má vlastní striktní validaci bez výjimky pro starší data: název nejvýše
100 znaků, poznámka 1000, cílový host a probeHost 253 (i neaktivní pole), ID
`[A-Za-z0-9._-]{1,64}`, createdAt/updatedAt nezáporné. Validace rozsahů a adres
zůstává. Čtení vlastního staršího úložiště dál toleruje původní delší název či
poznámku; export takového záznamu jej nemění, nový import jej ale odmítne.
Názvy/cíle/poznámky na kartě mají omezený počet řádků s výpustkou.

Jediný úvodní UTF-8 BOM je povolen. UI rozlišuje velikost, kódování, formát,
schéma, neznámé pole, duplicitní ID a neplatný záznam. Dvě různá ID se stejnou
MAC zůstanou povolena s upozorněním v náhledu (normalizované formáty MAC).
Žádný záznam se potichu nezahodí. `WolImportLimitsTest` zahrnuje i 900kB legacy
název, meze a obejití přes nepoužívané cílové pole; výsledky viz report08.


### Dokončení interakcí po revizi07 (LW3–5/8/9/14)

Před odesláním proběhne jediná kontrola odpovědi s limitem1,5s na již vybrané
Wi-Fi. Pokud zařízení odpoví, UI výslovně uvede, že odpovídalo **před odesláním**;
požadované WoL pakety se přesto odešlou. Jinak po odeslání začne samostatná
zrušitelná kontrola: opakuje pokus přibližně po2s (plus trvání sondy), do celkového
deadlinu. `probeDelaySeconds` zůstává názvem pole schema2 pro kompatibilitu,
ale nově znamená **dobu ověřování**, ne prodlevu jediné kontroly; výchozí20s,
rozsah1–120s. Jednotlivý pokus se vejde do zbývajícího deadlinu (nejvýše5s).
Monotónní čas dodává Android `SystemClock.elapsedRealtime`, testy virtuální hodiny.

`probingDeviceId` je oddělený od `sendingDeviceId`: během kontroly lze přidat,
upravit, smazat či řadit zařízení. Karta ukazuje „Kontroluji…“ a zrušení kontroly.
Nové probouzení zruší předchozí kontrolu. Probe používá neměnný snapshot zařízení
(a vybrané Wi-Fi); změna/smazání či přepsání záznamu kontrolu zneplatní. Pozdní
výsledek se nemůže připsat změněnému záznamu ani obnovit smazanou kartu.
Odeslané pakety zůstávají odeslanými i při zrušení následné kontroly. Text chybějící
odpovědi připomíná, že zařízení může být zapnuté; nedostupnost sond není odpověď.

Řazení názvů na Androidu používá injektovaný `Collator(cs)` s nerozlišováním
velikosti písmen a kanonickou normalizací; shodu rozhodne ID. Ruční přesun
nahoru/dolů se nadále ukládá. JVM test se skutečným `Collator` zahrnuje českou
abecedu včetně „ch“, diakritiky a kombinované podoby znaku; společný test ověřuje
použití komparátoru, shody a platné/neplatné ruční přesuny.

Náhled importu je posuvný a obsahuje název, MAC, režim/cíl, port, případnou kontrolu
a jméno přepisovaného záznamu. Vlastní cíle mimo RFC1918 a dosud nepřeložená DNS
jména mají upozornění; náhled kvůli tomu neprovádí DNS dotaz ani síťovou sondu.
Duplicitní MAC pod různými ID zůstává upozorněním, nikoli tichým zahozením.

Přenos má tlačítko Zrušit, které ruší coroutine a stávající stream/`CancellationSignal`.
Zrušený import se nepřijme, u exportu se aplikace pokusí odstranit neúplný dokument.
Provider může odmítnout úklid nebo opožděně dokončit vlastní operaci, proto nelze
zaručit jeho chování. Zprávu lze skrýt a začátek nového přenosu ji vyčistí.
Odmítnutí importu kvůli rozepsanému formuláři/odesílání má vlastní důvod; systémový
picker bez dostupné Activity je zachycen s lokalizovanou hláškou.

Checkbox kontroly a jeho popisek tvoří jednu přepínací oblast s rolí Checkbox,
nejméně48dp. Seznam i formulář mají `contentPadding` a společné `Spacing`.
Accessibility resource ID v dialozích jsou zapnutá jen v debug buildu.

Testy: `WakeProbePolicyTest`, `WolInteractionRevisionTest`, aktualizované scénáře
`WolRevisionTest`, `WolNameOrderTest` a dosavadní `BoundedStreamIoTest`.
Výsledky buildů/runtime a SHA dokládá report08, tato specifikace není PASS.
Zdroje ověřené2026-10-26:
[Collator](https://developer.android.com/reference/java/text/Collator),
[SystemClock.elapsedRealtime](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime()),
[Compose checkbox semantics](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
[ActivityNotFoundException](https://developer.android.com/reference/android/content/ActivityNotFoundException).


### Import CSV (Wake On Lan, Mike Webb)

`decodeWolImportAny(bytes, now)` rozpozná JSON podle prvního nebílého znaku `{`;
ostatní vstup zpracuje jako CSV. CSV podporuje UTF-8 s volitelným BOM, LF/CRLF,
uvozovky, zdvojené uvozovky i čárky a nové řádky uvnitř polí. Limit je 1 MiB,
500 datových záznamů včetně skupin (bez hlavičky) a 1000 znaků na dekódované pole.
Sloupce se hledají podle hlavičky; další sloupce se ignorují, krátké řádky mají
chybějící pole prázdná. Neplatná syntaxe či chybějící povinný sloupec odmítne celý soubor.

| CSV | NetKit |
|---|---|
| `machine_id` | Číslice → stabilní ID `webb-<id>` (nejvýše 59 číslic) |
| `machine_name`, `machine_mac` | Oříznutý název do 100 znaků, MAC normalizovaná stávajícím parserem |
| `machine_broadcastip`, `machine_port` | Vlastní IPv4 cíl (`CUSTOM`), UDP port 1–65535 |
| `machine_ping_port`, `machine_ip` | −1/0 vypne kontrolu; platná IPv4 zařízení se uloží jako host kontroly i tak (export ji vrátí). 1–65535 zapne TCP kontrolu na platné IPv4 zařízení |
| `group_id`, `group_name` | Skupinový řádek má prázdné `machine_id`; poznámka `Skupina: <oříznutý název>`, bez skupiny `null` |
| `machine_sort` | Číselné pořadí; jinak index datového záznamu od nuly, shody zachovají pořadí souboru |
| `secure_on` | Nepřenáší se; vrací se pouze počet zařízení s neprázdnou hodnotou |

Časy vytvoření i úpravy se nastaví na okamžik importu. Barva, SSID a pořadí skupin
se ignorují. Opakovaný import aktualizuje shodná ID přes stávající `mergeWolDevices`;
pozice již uložených zařízení při sloučení zůstávají zachované.

Neplatné zařízení se vrací v `skipped` jako název a důvod enum, ostatní zařízení lze
importovat. Duplicitní ID v CSV přeskočí pozdější jinak platný záznam. Příliš dlouhá
výsledná poznámka se také hlásí jako přeskočený záznam. Bez jediného platného zařízení
import skončí `INVALID_DEVICE`. Výsledný seznam prochází `validateImportedDevices`.

**SecureOn NetKit nepodporuje.** Heslo se nedává do modelu, úložiště, logu ani výjimky.
`secureOnDropped` počítá všechny řádky zařízení s neprázdným heslem, včetně přeskočených.
Náhled má `skippedCount` a `secureOnDropped`; CS/EN texty jsou připravené v
`wol_import_csv.xml`. Jejich zobrazení a MIME typy pickeru doplní Claude při integraci
souběžné úpravy obrazovky. CSV import tedy zatím není ověřený uživatelský tok v UI.

### Export CSV (Wake On Lan, Mike Webb)

`encodeWolCsvExport(devices, groupOf, defaultGroup)` vrací UTF-8 bez BOM, s LF
za každým záznamem. Všechna pole včetně hlavičky jsou v uvozovkách; vnitřní uvozovky
se zdvojují, čárky i nové řádky zůstávají uvnitř pole. Hlavička má přesně 14 sloupců
v pořadí původního exportu Webb.

`wolGroupFromNote(note, prefix)` rozpozná přesný lokalizovaný prefix na začátku
poznámky (např. `Skupina:` nebo `Group:`), ořízne mezery kolem názvu skupiny
a pro prázdný název či cizí poznámku vrátí `null`. Volající předá výsledek přes
`groupOf`; chybějící či prázdná skupina použije `defaultGroup`. Skupiny mají ID
a pořadí od nuly podle prvního výskytu. Každý skupinový řádek má 12 polí:
sedm prázdných, ID, název, pořadí a dvě prázdná. Hned pod ním následují jeho zařízení.

| NetKit | CSV |
|---|---|
| `id` | Číselný suffix `webb-<n>` se zachová; ostatní ID dostanou nejnižší volné číslo od nuly. Původní čísla se rezervují předem; číselné aliasy jako `00`/`0` dostanou při kolizi nové ID. |
| `name`, `mac`, `port` | `machine_name`, MAC velkými písmeny s `:`, `machine_port` |
| `CUSTOM` / ostatní režimy | `machine_broadcastip` = `customTarget` / `255.255.255.255` |
| Pořadí ve vstupním seznamu | `machine_sort` od nuly, nezávisle na seskupení řádků |
| Skupina | `group_id`; `group_name` a `group_sort` jsou v řádku zařízení prázdné |
| `probeHost` | `machine_ip`, nebo prázdné pole |
| `probeEnabled` a `probePort` | `machine_ping_port` = port při zapnuté kontrole s portem, jinak `-1` |
| Konstanty | `machine_colour` = `#33B5E5`; `secure_on` a `machine_ping_ssid` vždy prázdné |

Poznámka se nepřenáší, pouze skupina odvozená z ní. Ztratí se nastavení počtu
a intervalu opakování, zpoždění kontroly a automatického výpočtu broadcastu.
Zapnutá ICMP kontrola bez portu se převede na vypnutou kontrolu. Zpětný import
nastaví výchozí opakování/zpoždění, nové časy, cíl `CUSTOM` a skupinu jako poznámku;
nové řádky v názvech převede na mezery a při vypnuté kontrole zahodí její host/port.
Pro úplnou zálohu nastavení slouží JSON.

Zpětný CSV import má nadále své limity (včetně 500 zařízení **a skupin dohromady**)
a přijímá jen IPv4 cíle a IPv4 hosty aktivní TCP kontroly. Export adresy opisuje,
neprovádí DNS překlad; hostname proto stávající CSV import odmítne. Prázdný seznam
vytvoří pouze hlavičku, kterou import odmítne pro absenci zařízení.
Napojení exportu do menu doplní Claude. Import výsledného souboru v aplikaci
Wake On Lan na fyzickém telefonu musí ověřit správce; samotný round-trip v NetKitu
kompatibilitu s aplikací Webb nedokazuje.
