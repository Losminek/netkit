# Funkce: Rychlostní test

**Stav 2026-10-26:** implementováno na větvi `claude/m2-speed` (kód, unit testy proti
loopback serveru, lint, APK build). **Fyzický telefon: NOT RUN** (checklist M2-40 až M2-49
v [testování §11](../testovani.md#11-fyzický-checklist-m2--vzhled-signál-spektrum-zařízení-import-ssh)).
Emulátor ani reálný běh proti veřejným serverům agent nespouštěl.

Umístění: záložka **Přehled → segment „Rychlost“** (vedle „Živě“; do M4 se záložka jmenovala
Signál), stejný vzor jako Zařízení | Nástroje. Kořenový tag `speed_screen`, ovládací tagy `speed_*`.

**M4 (A1, A3, 2026-10-27):** test běží na **aktivní síti** (Wi-Fi, mobilní data, ethernet nebo
VPN), ne už jen přes Wi-Fi. Na měřené síti (mobilní data, hotspot označený jako měřený) se před
startem vždy zobrazí odhad spotřeby dat a test běží až po potvrzení (rozhodnutí O-3, §4).
Kontext výsledku na mobilních datech obsahuje operátora, standard a signál datové SIM (§3).

## 1. Cíl a rozhodnutí

Přesný a vypovídající test rychlosti aktivní sítě (Wi-Fi, mobilní data, VPN) → internet s dvěma nezávislými enginy:

| Engine | Server | Spojení | Proč |
|---|---|---|---|
| **LibreSpeed** (vlastní klient protokolu) | preset `https://rychlost.zelina.eu` (veřejný LibreSpeed kolegy na Proxmoxu), nebo vlastní `https://` URL | 6 souběžných stahování, 3 odesílání, HTTP/1.1 přes TLS | vícestreamový test podobný Ookla, server pod kontrolou, nízká latence v ČR |
| **NDT7** (M-Lab) | nejbližší server z `locate.measurementlab.net/v2/nearest/ndt/ndt7` | 1 WebSocket přes TLS na směr | nezávislá reference, serverové `TCP_INFO`/BBR metriky |

Proč ne Ookla / nPerf: obě vyžadují komerční SDK a licenční smlouvu (nelze otevřít repo,
D7). Google/M-Lab test v prohlížeči je NDT7 s jedním spojením; správce ho vnímá jako občas
nepřesný — proto je NDT7 jen druhá reference a hlavní engine je vícestreamový LibreSpeed.

LibreSpeed je **LGPL**: žádný kód LibreSpeed se nepřebírá. Implementován je jen veřejný HTTP
protokol jeho backendu (stejně jako by ho volal libovolný HTTP klient):

- `backend/garbage.php?ckSize=N&r=…` — N MiB náhodných dat (používáme N = 25),
- `backend/empty.php?r=…` — POST tělo zahodí (upload), GET vrátí prázdnou odpověď (latence),
- `backend/getIP.php` — JSON `processedString` (veřejná IP; jen zobrazení). Bez `isp=true`, viz §5.

NDT7 podle [specifikace v0.11.0](https://github.com/m-lab/ndt-server/blob/main/spec/ndt7-protocol.md)
(čteno 2026-10-26): subprotokol `net.measurementlab.ndt.v7`, `wss://…/ndt/v7/download|upload`
s `access_token` z locate, User-Agent `NetKit/<verze> (Android)`, metadata
`client_name=netkit-android&client_version=<verze>`.

## 2. Metoda (přesnost)

**Pořadí:** LibreSpeed: kontrola aktivní sítě (na měřené síti potvrzení dat) → latence bez zátěže
(HTTP probe) → stahování 10 s → odesílání 10 s → uložení výsledku. NDT7: kontrola aktivní sítě →
locate → stahování → odesílání →
uložení; NDT7 nemá vlastní ping, „latence bez zátěže“ je `MinRTT` z `TCP_INFO` serveru při stahování.

**Propustnost.** Čítač bajtů každého směru se vzorkuje po 250 ms. Výsledek
= bajty po skončení náběhu / čas po náběhu (Mbit/s, dekadicky: `B × 8 / µs`).
Náběh (TCP slow start, plnění bufferů) se nezapočítává:

- minimálně 2 s (u směru kratšího než 4 s, např. server ukončil dřív, 20 % délky);
- pokud 1s klouzavá rychlost v tom okamžiku ještě nedosahuje 80 % mediánu zbytku testu
  (pomalá rampa na lince s velkým BDP), náběh se prodlouží k prvnímu vzorku, který 80 %
  dosáhne, nejvýše na 4 s a nikdy přes polovinu směru;
- „špička“ = nejvyšší 1s klouzavá rychlost po náběhu. Graf ukazuje 1s okno po 250 ms.

Logika (`ThroughputTrace`, `detectWarmupMs`, `summarizeThroughput`, `chartPoints`) je
v `shared/.../speed/SpeedMeasure.kt` s testy v `commonTest`.

**Stahování (LibreSpeed).** 6 souběžných spojení, každé opakovaně stahuje
`garbage.php?ckSize=25`, čte přes 64KiB buffer a zahazuje (paměť se nehromadí).
`Accept-Encoding: identity` (počítají se bajty z linky, ne dekomprimovaná velikost),
`Cache-Control: no-cache`, náhodný `r` proti cache. Vynucené **HTTP/1.1**: N streamů = N TCP
spojení (HTTP/2 by je multiplexoval do jednoho spojení a z vícestreamového testu udělal
jednostreamový).

**Odesílání (LibreSpeed).** 3 spojení POSTují náhodný (nekomprimovatelný) payload na
`empty.php`. Tělo začíná na 8 MiB; dokud server žádný upload nepřijal, každé selhání
(413, reset kvůli `client_max_body_size`) tělo zmenší na polovinu až na 256 KiB.
Počítají se bajty zapsané do soketu po 64 KiB; zkreslení plněním send bufferu odstraní náběh.

**Selhání jednotlivých spojení.** Spojení, které třikrát po sobě selže (např. proxy s `limit_conn`
vrací 503), pro zbytek směru skončí. Pokud skončí všechna, test selže s typovanou chybou; jinak se
výsledek zobrazí s varováním „funkčních X/Y“, je to dolní odhad a v historii se uloží jako neúplný
včetně počtu funkčních a plánovaných spojení.

**Kontrola, že jde o LibreSpeed.** Vlastní URL může ukazovat na libovolný web (SPA vrací 200
na všechno). Proto: `empty.php` musí vrátit tělo do 512 B (jinak se probe i upload považují za
„není LibreSpeed“); `garbage.php` nesmí mít `Content-Type` text/HTML/JSON/XML, deklarovaná délka
nesmí být pod 1 MiB a kompletně přečtená odpověď pod 1 MiB je chyba. Taková spojení rychle
skončí, test selže hláškou „Server odpovídá, ale ne jako LibreSpeed“ místo 10 s smyčky malých stránek.

**Latence bez zátěže (LibreSpeed).** Jeden zahřívací dotaz (TCP + TLS, zahozen), pak
12 HTTP GET `empty.php` po 100 ms přes udržované spojení (vlastní pool s 1 spojením).
Medián a jitter = průměrná absolutní diference po sobě jdoucích vzorků. Jde o HTTP RTT
včetně zpracování na serveru, ne o ICMP ping (ICMP na server nemusí být povolené a nevypovídá
o cestě HTTP).

**Latence pod zátěží (bufferbloat).** Během stahování i odesílání běží stejný probe každých
250 ms na samostatném spojení; započítávají se vzorky po náběhu. Zobrazí se medián
a nárůst proti klidu. Známka podle nárůstu: A+ < 5 ms, A < 30, B < 60, C < 200, D < 400, jinak F
(prahy obvyklé u Waveform/DSLReports).

**NDT7.** Stahování: počítají se přijaté binární zprávy; server test ukončí do ~10 s
(zavření WebSocketu), klient po 13 s zavře sám a po 15 s spojení zruší (tvrdý limit).
Locate vrací několik nejbližších serverů: když handshake selže dřív, než přišla data
(DNS, spojení, TLS, timeout, HTTP odmítnutí upgradu), zkusí se další, nejvýše 3 servery a další
pokus začne jen do 15 s od prvního. Odesílání začíná na serveru, který prošel stahováním.
Odesílání: klient 10 s posílá náhodné binární zprávy, velikost se škáluje podle přílohy
specifikace (začátek 8 KiB, zdvojení, když je zpráva menší než 1/16 odeslaného objemu, strop
1 MiB; spec dovoluje 16 MiB, strop drží paměť). Klientské bajty = předané soketu
(`zařazeno − queueSize`), fronta OkHttp se drží pod ~2× velikost zprávy. Binární zpráva od
serveru během uploadu = porušení protokolu (chyba). Abruptní EOF po datech spec toleruje.

**Pohled serveru NDT7** (poslední textová zpráva = souhrn, podle spec):
aplikační rychlost serveru (`AppInfo.NumBytes / ElapsedTime`), TCP rychlost včetně režie
(`BytesAcked` při stahování, `BytesReceived` při odesílání / `TCPInfo.ElapsedTime`),
opakované přenosy `BytesRetrans / BytesSent`, `MinRTT` (zobrazen jako latence bez zátěže)
a medián `TCPInfo.RTT` během stahování po náběhu (latence pod zátěží z pohledu TCP odesílatele),
odhad BBR `BBRInfo.BW`, pokud ho server posílá. Server měří celý test včetně náběhu,
klient bez něj — rozdíl je očekávaný. U NDT7 odesílání je číslo serveru přesnější.
`ConnectionInfo` (IP:port klienta) se nedekóduje.

## 3. Kontext výsledku

U výsledku: engine, server (jméno/host, u NDT7 stroj a město), veřejná IP z `getIP.php`
(jen LibreSpeed, jen zobrazení), typ měřené sítě a podle něj:

- **Wi-Fi:** SSID (bez oprávnění k poloze „skrytá/neznámá“), pásmo a kanál, RSSI a PHY RX/TX
  na začátku, medián PHY RX během stahování a TX během odesílání (1 Hz);
- **mobilní data (M4 A3):** operátor, standard (LTE / 5G NSA / 5G SA / 3G / 2G) a signál
  (RSRP) datové SIM na začátku z `CellularMonitor` (čeká nejvýš 2 s, jinak jen „Mobilní data“);
- **ethernet, VPN:** jen typ sítě;

a čas spuštění. Poznámka: výsledek je shora omezen linkou (Wi-Fi reálně ~60–70 % PHY,
mobilní signál a vytížení buňky), routerem, linkou ISP a uplinkem serveru.

**Historie:** posledních 20 výsledků v `noBackupFilesDir/speedtest.json` (atomický zápis),
bez IP, ISP, tokenů a BSSID; od M4 s typem sítě (starší záznamy = Wi-Fi). Operátor a standard
se do historie zatím neukládají, jen do kontextu běhu. Neúplný běh (selhal upload, ztráta nebo
změna sítě, část paralelních spojení)
se uloží s příznakem „neúplný“; uživatelem zastavený běh se neukládá.

## 4. Limity a hranice

- **Aktivní síť (M4).** Sokety i DNS vázané na výchozí `Network` z `ActiveNetworkMonitor`
  (`socketFactory` + `Network.getAllByName`): Wi-Fi, mobilní data, ethernet nebo VPN, která
  aplikaci pokrývá. Bez sítě odmítnutí („Bez připojení“). Ztráta nebo výměna měřené sítě během
  testu (např. zapnutí Wi-Fi na mobilních datech) → „Připojení se během testu odpojilo nebo
  změnilo“, výsledek neúplný.
- **Potvrzení dat na měřené síti (O-3).** Chybí-li `NET_CAPABILITY_NOT_METERED`, test se
  nespustí hned: dialog ukáže odhad spotřeby (z posledního výsledku na stejném typu sítě,
  jinak výchozí) a plný test běží až po „Spustit test“. Potvrzení platí jen pro tu síť;
  přepnutí na jinou měřenou síť vyžaduje nové potvrzení. Lehký režim se nedělá.
- **Životní cyklus.** Zastavit, odchod ze segmentu/záložky, pozadí aplikace zruší vše
  (zavře sokety přes `CallRegistry`); rotace test neruší. Žádná služba na pozadí.
- **Souběh.** Během testu neběží živá telemetrie Signálu (segment není složený) a navigace
  ruší LAN skener/sken Wi-Fi jiných záložek; otevřená SSH relace běží dál (malý provoz).
- **Časové limity.** Connect/read/write 5 s, probe 2 s, locate 10 s, NDT7 handshake 10 s,
  každý směr nejvýše 10 s (LibreSpeed) / 15 s (NDT7).
- **Proxy.** Klient má `Proxy.NO_PROXY`: výchozí `ProxySelector` Androidu odpovídá proxy
  *výchozí* sítě a proxy by měřila sebe místo cesty. Síť, která funguje jen přes HTTP proxy
  (PAC/ruční), test proto neprojde (chyba spojení). Alternativa — číst `LinkProperties.httpProxy`
  měřené sítě — by měřila proxy; zatím záměrně ne.
- **Spotřeba dat.** Podle linky stovky MB až jednotky GB na test; na mobilních datech jde
  z tarifu, proto potvrzení s odhadem.
- **CPU telefonu.** OkHttp WebSocket alokuje každou přijatou NDT7 zprávu (≤ 1 MiB typicky);
  na gigabitu může být limitem CPU/GC telefonu — porovnat s LibreSpeed.
- **Upload na straně klienta** měří bajty předané soketu; po náběhu odpovídá lince, krátké
  špičky na začátku zkreslení nemají vliv na výsledek.
- **OkHttp 5.3.2** (ne 5.4+/5.5): novější AAR vyžadují `compileSdk 36/37`, viz
  [závislosti](../zavislosti.md).

## 5. Soukromí

M-Lab zveřejňuje každý NDT7 test jako otevřená data **včetně veřejné IP klienta**.
LibreSpeed `getIP.php` se volá **bez `isp=true`**: s ním by server kolegy (nebo vlastní server)
posílal IP klienta dál službě ipinfo.io, aby zjistil ISP. Jméno ISP pro měření nic nepřináší
a uživatel ho zná, proto se zobrazí jen veřejná IP a žádná třetí strana se kvůli aplikaci nedotazuje.

Před prvním NDT7 testem jednorázové upozornění s volbou „Rozumím, spustit“ / „Zpět“;
potvrzení se uloží. Podrobně: [bezpečnost § Rychlostní test](../bezpecnost.md#rychlostní-test-m2-2026-10-26).

## 6. Kód a testy

| Část | Soubor |
|---|---|
| Měření, latence, bufferbloat, graf | `shared/.../speed/SpeedMeasure.kt` |
| LibreSpeed URL, endpointy, getIP | `shared/.../speed/LibreSpeed.kt` |
| NDT7 locate, zprávy, pohled serveru, škálování zpráv | `shared/.../speed/Ndt7.kt` |
| Historie a kodek úložiště | `shared/.../speed/SpeedHistory.kt` |
| OkHttp základ, DNS vázané na síť, `CallRegistry`, typované chyby | `androidApp/.../speed/SpeedHttp.kt` |
| Klient LibreSpeed / NDT7 | `androidApp/.../speed/LibreSpeedClient.kt`, `Ndt7Client.kt` |
| ViewModel, úložiště, Wi-Fi a mobilní kontext | `SpeedTestViewModel.kt`, `SpeedStore.kt`, `SpeedWifi.kt` |
| UI a segment v záložce Přehled | `SpeedTestScreen.kt`, `SignalHost.kt` |

Testy: `shared/src/commonTest/.../speed/` (výpočty, náběh, URL validace, locate/měřicí zprávy,
škálování, kodek) a `androidApp/src/test/.../speed/` — klienti proti in-process serveru na
`127.0.0.1` (`ServerSocket`, HTTP/1.1 a serverová strana WebSocketu): paralelní spojení,
`identity` kódování, zmenšení těla při limitu, typované HTTP chyby, zrušení do 2 s, SPA/HTML
a malé `garbage.php` → „není LibreSpeed“, limit spojení → „funkčních 2/4“, `NO_PROXY`;
NDT7 stahování/odesílání/tvrdý limit/porušení protokolu/locate 204, přechod na další server,
bez opakování po protokolové chybě nebo po začátku přenosu, chybná URL → typovaná chyba. Navázání na
aktivní `Network` JVM test neověří — patří do fyzického checklistu (M2-40 až M2-49, M4-06 až M4-08).
