# Funkce: LAN skener, ping, traceroute, porty

**Stav 2026-10-26:** opravy M1 jsou implementované v pracovní větvi. Konkrétní build,
testy a runtime důkazy eviduje interní pracovní záznam; tento popis není přijetí M1.
Fyzický checklist provádí správce, nikoli agent.

## 1. Implementované funkce

- **Discovery IPv4 hostů:** výchozí rozsah obsahuje adresu telefonu a má prefix
  `max(prefix Wi-Fi, 24)`. Vlastní IPv4 telefonu vynechá. Použije ICMP a při neúspěchu
  souběžně TCP porty 80, 443, 22 a 445; blokující TCP sondy mají společný limit 64 workerů.
  Úspěšné sondy zobrazí IP, dostupné reverzní DNS jméno, odezvu a metodu ICMP/TCP;
  TCP odmítnutí značí živý endpoint metodou TCP-RST.
  mDNS je samostatný nástroj; jeho výsledky se automaticky neslučují do discovery seznamu.
- **Ping:** průběžné měření do zastavení nebo odchodu do pozadí. Odesláno/přijato/ztráta
  platí pro celou relaci; min/průměr/max/jitter a export pro posledních 600 vzorků,
  graf a seznam pro posledních 120. Číslo vzorku a čas od startu (monotónní hodiny)
  se po odříznutí starých vzorků nenulují; export je dostupný i za běhu jako snímek. Jitter je průměr absolutních rozdílů sousedních úspěšných
  odpovědí; přes timeout se rozdíl nepočítá.
- **Traceroute:** `/system/bin/ping` s TTL 1–30. Zobrazuje pouze údaje skutečně získané
  z výstupu. Po pěti úvodních hopech bez adresy skončí s vysvětlením neúplné trasy;
  chybějící hopy ani jejich RTT nevymýšlí. Ověření výstupu HyperOS zůstává otevřené.
- **TCP port scan:** preset 105 běžných portů, seznam nebo vzestupné rozsahy
  (např. `22,80,8000-8100`), názvy známých služeb a volitelný pasivní banner.
  Karty portů uvádějí úspěšná TCP spojení; souhrn rozlišuje otevřené, odmítnuté,
  timeoutované a chybové sondy. Timeout není důkaz uzavřeného portu. TCP se zde nepoužívá
  k přihlašování ani k posílání aplikačních požadavků.
- **DNS:** A/AAAA přes Android resolver na vybrané Wi-Fi, bez Wi-Fi na aktivní síti (M4);
  IPv4 PTR přes binární IN/PTR DNS dotaz na témže `Network`. Samotné ICMP/TCP sondy jsou
  pouze IPv4.
- **mDNS / Bonjour:** ručně zvolený typ služby, výchozí `_http._tcp.`, hledání po dobu
  12 sekund, nejvýše 128 služeb, název/typ/adresa/port. V této implementaci vyžaduje
  Android 14 / API 34; na API 29–33 UI výslovně uvede nepodporu.
- **Akce:** kopírování IP, přechod na ping hostu, kopírování nebo sdílení výsledků jako
  text/CSV/JSON, i ze snímku probíhajícího pingu. Úplný export lze uložit přes systémový výběr
  souboru (SAF, nejvýše 8 MiB, zápis mimo UI vlákno). Kopírování a sdílení přes IPC
  mají limit 64 Ki znaků UTF-16; větší výsledek nabízí uložení do souboru bez zkrácení.
  Formát i připravený snímek přežijí rotaci ve ViewModelu; obsah se generuje při
  uložení. Při selhání aplikace zkusí odstranit právě vytvořený neúplný SAF dokument
  (poskytovatel může odstranění odmítnout). Chyba výběru souboru, schránky nebo příjemce sdílení má chybovou hlášku. CSV escapuje pole a chrání názvy či bannery před interpretací jako
  vzorce v tabulkovém editoru. JSON zahrnuje i výchozí hodnoty; RTT je v exportu zaokrouhlené
  na 3 desetinná místa. Přechod přímo do SSH a WoL zůstává M2.

## 2. Síťová cesta a omezení Androidu

### Aktivní síť: co jde na mobilních datech (M4, 2026-10-27)

Nástroje se dělí podle toho, co potřebují. Volbu sítě dělá čistá funkce
`chooseToolRoute` (`shared/net/ToolRoute.kt`, testy `ToolRouteTest`); „místní“ adresu
určuje `isPrivateOrLocalTarget` (RFC 1918, CGNAT 100.64/10, link-local IPv4/IPv6,
loopback, ULA fc00::/7). Aktivní síť sleduje `ActiveNetworkMonitor`
(`registerDefaultNetworkCallback`).

| Nástroj | Wi-Fi | Mobilní data | VPN |
|---|---|---|---|
| Ping, traceroute, porty na **veřejný** cíl | ano, přes aktivní síť | ano | ano (přes tunel) |
| Ping, traceroute, porty na **místní** adresu | ano, socket vázaný na Wi-Fi | ne: hláška „Adresa je v místní síti, připojte se k Wi-Fi nebo VPN“ | ano, pokud adresa není v podsíti Wi-Fi nebo Wi-Fi chybí |
| DNS | resolver Wi-Fi | resolver aktivní sítě | resolver Wi-Fi, bez ní VPN |
| Skener sítě (Zařízení), identifikace, mDNS | ano | ne | ne (jen ne-VPN Wi-Fi) |

- Pravidlo pro místní cíl: s Wi-Fi jde přes Wi-Fi, kromě případu, kdy je cíl mimo podsíť
  Wi-Fi a běží VPN (typicky firemní 10.x přes tunel). Bez Wi-Fi jde přes aktivní síť, jen
  když je to VPN nebo ethernet. Loopback (telefon sám) se nikdy neodmítne.
- Hostname se přeloží nejdřív resolverem Wi-Fi (místní jména), při chybě resolverem
  aktivní sítě. Síť se vybere až podle přeložené adresy; potvrzení se váže na číselnou
  adresu a konkrétní síť (`PinnedLanHost<ToolRoute>`). Změna výchozí sítě během běhu na
  aktivní síti (mobilní data → Wi-Fi, zapnutí VPN) běh zastaví hláškou „Síť se změnila“.
- Potvrzení cíle: na Wi-Fi beze změny. Mimo Wi-Fi odpadá důvod „mimo podsíť Wi-Fi“
  (žádná vlastní podsíť není), veřejná adresa a velký počet portů potvrzení vyžadují dál.
- Nástroje ukazují štítek „Měří se přes Wi-Fi / Mobilní data / VPN“. U jména bez
  překladu ukazuje aktivní síť; po startu skutečně použitou síť.
- Traceroute na mobilních datech: CGNAT a operátor často ICMP Time Exceeded neposílají,
  řada hopů je proto „bez odpovědi“. To je správný výsledek, ne chyba.
- SSH (mimo tento dokument) jde dál přes výchozí síť; u místní adresy na mobilních
  datech bez VPN jen upozorní, spojení nezakáže.

Ping/discovery ICMP používají vlastní neprivilegovaný ICMP datagram socket
`Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)`, před připojením vázaný přes
`Network.bindSocket(FileDescriptor)` na vybranou ne-VPN Wi-Fi. TCP i DNS používají
stejný `Network`; při odmítnutí vazby se na jinou síť nepřechází. ICMP timeout není
hlášen jako nedostupná implementace; chyba vytvoření/vazby/read socketu je v UI odlišená.
Discovery může pokračovat TCP sondami. TCP výsledky rozlišují OPEN, REFUSED, TIMEOUT,
ERROR; pouze REFUSED (RST) a OPEN prokazují dostupný TCP endpoint. RST po úspěšném
connect při čtení banneru zachovává otevřený port bez banneru.

**Traceroute je výjimka:** systémový `/system/bin/ping -I <iface>` není vázaný na Android
`Network`. Když výchozí síť není ta, přes kterou se měří (místní cíl přes Wi-Fi, zatímco
výchozí je VPN nebo mobilní data), vyžaduje zvláštní souhlas a každý výsledek nese
poznámku „Mohlo jít mimo Wi-Fi“. Na aktivní síti souhlas není potřeba, výchozí síť je
právě ta měřená. Změní-li se výchozí síť až po běžném potvrzení, běh se zastaví; nový
běh vyžádá souhlas. Mobilní data nejsou fallback: použijí se jen pro veřejný cíl, když
jsou aktivní sítí (M4). VPN může provoz mimo tunel blokovat.

Ověřené zdroje 2026-10-26: [`Network.bindSocket(FileDescriptor)` od API 23](https://developer.android.com/reference/android/net/Network#bindSocket(java.io.FileDescriptor)),
[`Os.socket` od API 21](https://developer.android.com/reference/android/system/Os#socket(int,int,int)).
Nejpozději od minSdk29 jsou veřejné [SOCK_NONBLOCK a SOCK_CLOEXEC](https://developer.android.com/reference/android/system/OsConstants#SOCK_NONBLOCK);
neblokující režim a zákaz dědění FD se nastavují atomicky při socket(). Nepoužívá se
`fcntlInt`, které by vyžadovalo API30.
AOSP init nastavuje `ping_group_range` na `0 2147483647` v
[Android 10 / API 29](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-10.0.0_r1/rootdir/init.rc)
i [Android 15 / API 35](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-15.0.0_r1/rootdir/init.rc).
SELinux AOSP povoluje vytváření `icmp_socket` doménám se síťovým přístupem, viz
[Android 10](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-10.0.0_r39/public/net.te)
a [Android 14](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/android14-qpr3-s10-release/public/net.te).
To je zdrojová evidence, **nikoli runtime důkaz na všech API 29–35 nebo OEM**. API 30–33
a SELinux konkrétního API 35 image nejsou tímto prohlášeny za otestované. OEM či VPN
mohou ICMP odmítnout; UI pak uvede nedostupnost, nic se neobchází. Aktuální runtime stav
uvádí report 06, historický procesový ping z reportu 04 nový socket neověřuje.

| Omezení | Aktuální chování |
|---|---|
| ICMP datagram socket může OEM/VPN odmítnout | Ping a discovery bez fallbacku na proces či jinou síť; viditelná hláška, discovery pokračuje přes TCP. Raw socket s CAP_NET_RAW se nepoužívá. |
| MAC/ARP sousedů nelze na běžném Androidu spolehlivě číst | Discovery na ARP nestojí a nenabízí MAC ani výrobce jiných hostů. |
| Traceroute závisí na textu ICMP Time Exceeded | Neúplná trasa má výslovnou hlášku. RTT je nyní dostupné pro dosažený cílový host z echo odpovědi; RTT mezilehlých hopů se zatím nepočítá. |
| Starší NSD API nemá současně požadované síťové omezení a rušení překladu | API 34+ používá discovery na konkrétním `Network` a zrušitelné `ServiceInfoCallback`; API 29–33 bez náhradního hledání na všech transportech. |
| Změna Wi-Fi nebo odchod do pozadí | Práce se zruší; sockety/procesy/NSD callbacky se uklízejí. Žádná služba na pozadí. |

NSD API ověřeno v oficiální dokumentaci dne 2026-10-25:
[discovery na konkrétním Network](https://developer.android.com/reference/android/net/nsd/NsdManager#discoverServices(java.lang.String,int,android.net.Network,java.util.concurrent.Executor,android.net.nsd.NsdManager.DiscoveryListener))
a [zrušitelné sledování informace služby](https://developer.android.com/reference/android/net/nsd/NsdManager#registerServiceInfoCallback(android.net.nsd.NsdServiceInfo,java.util.concurrent.Executor,android.net.nsd.NsdManager.ServiceInfoCallback)).
Multicastové oprávnění ani vlastní multicastový socket se nepřidávají.

## 3. Limity, potvrzení a nastavení

- Rozsah má nejvýše 4096 hostů; bez sítě/broadcastu kromě /31 a /32.
  Port scan má nejvýše 4096 unikátních portů v intervalu 1–65535.
- Rozsah mimo aktuální podsíť Wi-Fi, neprivátní adresa (včetně loopbacku a speciálních
  adres) nebo více než 254 hostů/portů vyžaduje potvrzení. Čistá funkce
  `targetConfirmationReasons` / `requiresTargetConfirmation` má testy hranic. Dialog
  uvádí konkrétní důvody, zadané jméno, připnutou IPv4/CIDR, Wi-Fi a počty hostů/portů. Před potvrzením je DNS přeložené jednou; potvrzuje se neměnný požadavek
  s připnutou číselnou IPv4 a konkrétní Wi-Fi. Potvrzení znovu DNS nepřekládá.
  Změna parametrů potvrzení zneplatní, změna vybrané Wi-Fi operaci odmítne.
- Validují se jen viditelná pole aktivního nástroje; DNS/mDNS neblokuje chybný timeout
  jiného nástroje. Nejednoznačné IPv4 s úvodní nulou a porty se znaménkem se odmítají.
- Souběžnost má výchozí hodnotu **32**, nastavení 1–64. Discovery i port scan používají
  pevný počet host/port workerů; discovery má uvnitř host workeru čtyři souběžné
  fallback sondy. Timeout TCP začíná až po přidělení IO workeru, ne během čekání ve frontě.
- Timeout má výchozí hodnotu **1000 ms**, nastavení 100–5000 ms. TCP fallback při
  discovery používá nejvýše 400 ms na port; pasivní banner má zvlášť 500 ms a 256 B.
  ICMP echo používá monotónní deadline, `Os.poll` a neblokující čtení; traceroute nadále používá
  sekundové `-W` procesu a další limit jeho života.
- Dopředné DNS má limit 4 sekundy, IPv4 PTR 1,5 sekundy. Síťové/platformní čtení běží
  mimo hlavní UI vlákno. Časy na fyzickém telefonu dosud nejsou potvrzené měřením.
- Síť sleduje `NetworkCallback`; změna Wi-Fi/prefixu ruší běh i potvrzení a aktualizuje
  výchozí CIDR. Žádný polling. Stavy běhu jsou IDLE/RUNNING/FINISHED/CANCELLED/FAILED
  s počtem dokončených cílů nebo vzorků.
- PTR se nikdy neodesílá pro 0/8, loopback127/8 ani multicast/reserved224/3;
  tyto cíle se nepředávají externímu resolveru. PTR přijímá jen LDH DNS jména; mDNS a UTF-8 bannery mají limit délky a nahrazení Cc/Cf
  znaků. mDNS preferuje IPv4, IPv6 je druhá volba. Výrobce/MAC sousedů není dostupný.
- Žádný brute force, exploit ani flood režim. Výsledky neopouštějí aplikaci automaticky.

## 4. Rozdělení implementace

`shared/commonMain/lan` obsahuje IPv4/CIDR, porty, parser pingu, statistiku, export,
IN/PTR wire encoder, pevný worker pool a připnutí číselného cíle k síti. Společné testy
pokrývají /30, /31, /32, limity, poškozené vstupy, statistiku, export, omezení souběžnosti,
zrušení workerů a změnu DNS odpovědi A → B bez sondování B po potvrzení A.

`androidApp/lan` obsahuje `LanBackend`, `LanViewModel` a Compose UI. Orchestrace je nyní
v Android ViewModelu, nikoli ve společném `LanScanController`; to je odchylka od původního
architektonického návrhu, nikoli dokončená iOS implementace.

## 5. UI a ověření

LAN používá společné `ScreenHeader`, `SectionCard`, `InfoNote`, `StatusBanner`,
`EmptyState` a `LabeledValue`. Zákonný rozsah skenování zůstává viditelný; technická
omezení jsou sbalitelná. Nástroje jsou v jednom posuvném řádku, zobrazená pole odpovídají
nástroji. Jediné tlačítko Start/Stop leží pod posuvným obsahem a nad IME. Ping má osy
RTT/čas a legendu timeoutů, traceroute tabulku hop/adresa/odezva. Adresy jsou monospace;
řádky exportních akcí se zalamují. Formuláře a výběr nástroje žijí ve ViewModelu,
odběr UI respektuje lifecycle. Screenshot ani 200% font zatím nepředstavuje tato dokumentace.

| Ověření | Důkaz / mezera |
|---|---|
| Pure policy, /20–/19, 127/8, 0.0.0.0, multicast, porty, sanitizace, čas/sekvence a JSON | `LanRevisionTest`, `LanSettingsTest`, existující `PinnedProbeTest`; výsledek konkrétního běhu v reportu06. |
| TCP RST po connect, odmítnutí, chyba bind a UTF-8 banner | `TcpProbeTest`; testy otevírají pouze host loopback. |
| ICMP přes bound socket, TCP banner/odmítnutí/zrušení | Fáze1 emulátorový důkaz uvádí report06 včetně SHA; historický report04 ověřoval jinou ICMP implementaci. |
| Discovery /24, doba a paměť | Čeká měření. Loopback /24 měří orchestrace, ne čas timeoutů skutečné Wi-Fi. Žádné tvrzení cílových20s bez výsledku. |
| SAF, rotace, IME, 200% font | Čeká aktuální UI instrumentace a screenshoty; unit testy exportu neověřují systémový picker. |
| ViewModel/platformní přechody | Bez přímého JVM ViewModel testu; rozhodovací policy je vytažená a testovaná, síťové změny a pin musí ověřit emulátor/telefon. |
| VPN + mobilní data, DNS/PTR, mDNS, AP izolace, reálná LAN a traceroute | NOT RUN na telefonu; interní pracovní záznam provede správce. |

Reprodukovatelný runner a metodika jsou v [testování](../testovani.md). API29–33 mDNS
zůstává výslovně mimo rozsah; žádný fallback na všechny síťové transporty se nepřidává.

### Unicode metadata po nezávislé revizi

Sanitizace zpracovává celé Unicode code pointy, nikoli jednotlivé `Char`. Cc/Cf
(včetně supplementary formátovacích znaků jako U+E0001) nahradí mezerou; osamocený
UTF-16 surrogate znak nahradí U+FFFD. Limit se počítá ve výstupních UTF-16 jednotkách,
platný pár se na hranici nikdy nerozdělí. Běžné supplementary znaky zůstávají zachované.
Používá se pouze common Kotlin API `Regex`, žádný import `java.*` do shared.
Zdroje ověřené 2026-10-26: [Kotlin Regex a odlišnosti platforem](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex/),
[Android Pattern – Unicode kategorie a supplementary znaky](https://developer.android.com/reference/java/util/regex/Pattern.html).
Současné targety jsou Android/JVM; případný budoucí Native target musí spustit stejné
regresní testy, ne předpokládat totožný regex engine.

### TCP errno a locale (revize 07, ověřeno 2026-10-26)

`classifyConnectFailure` nečte text zprávy. Projde nejvýše 16 příčin výjimky;
`SocketTimeoutException` znamená TIMEOUT, jinak čte `ErrnoException.errno`:
`ECONNREFUSED` → REFUSED, `ETIMEDOUT` → TIMEOUT, ostatní/chybějící errno → ERROR.
ENETUNREACH/EHOSTUNREACH tedy nejsou důkazem živého hostu. Nulová hodnota errno
není odmítnutí. Chyba lokálního bind vždy znamená ERROR; po úspěšném connect
zůstává každá chyba banneru OPEN. Neznámé OEM mapování bez errno je konzervativně
ERROR, nikoli odhad z lokalizované zprávy.

Veřejné [ErrnoException API](https://developer.android.com/reference/android/system/ErrnoException)
popisuje číselné pole `errno` od API21 a jeho porovnání s `OsConstants`.
[AOSP IoBridge pro Android35](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/libcore/io/IoBridge.java)
v cestě `connect`/`isConnected` zachovává nativní příčinu v `ConnectException`;
pro ETIMEDOUT vytváří `SocketTimeoutException` a připojí příčinu pomocí `initCause`.
[Socket.connect](https://developer.android.com/reference/java/net/Socket#connect(java.net.SocketAddress,int))
dokumentuje timeout zvlášť od obecné I/O chyby. To není důkaz všech OEM implementací.

JVM `TcpProbeTest.boundNonListeningPortIsRefused` stále používá skutečný rezervovaný
loopback port bez listen. OpenJDK výjimka nemá veřejné errno: test výslovně ověří
`ConnectException` bez příčiny a poté **testovacím adaptérem** doplní známé errno
fixture. Nejde o ověření Android čtečky errno. Samostatný test skutečného socketu
bez adaptéru errno ověřuje ERROR, tabulkové testy ověřují české/anglické/německé
zprávy, různá errno, timeout a nulové Android mock konstanty. Instrumentační fáze
`lan` vyžaduje na odmítnutém fixture portu skutečné REFUSED přes produkční čtečku,
nejen prázdný výsledek (`LAN:TCP_REFUSED_ERRNO:PASS`). Výsledek běhu a SHA patří do reportu08.

Gradle Test úlohy obou modulů nastavují `LC_ALL=C` pouze pro své podprocesy jako
pojistku opakovatelné diagnostiky. Produkční rozhodnutí na locale nezávisí;
předepsaný build se navíc spouští z prostředí `cs_CZ.UTF-8` i `C`.


### ICMP RTT bez pevného čekání (revize 07, 2026-10-26)

Po odeslání echa čeká `WifiIcmp` přes `Os.poll`/`StructPollfd` na POLLIN;
blokovací krok má nejvýše 10 ms pro kontrolu zrušení, ale při čitelné odpovědi se
vrátí hned. Pevné `delay(10)` mezi čteními je odstraněno. Čas se odečte ihned po
`Os.read`, ještě před validací paketu, pomocí `System.nanoTime`. Jde o RTT měřené
aplikací včetně plánování vlákna a systémových volání, nikoli kernelový timestamp
ani laboratorní odhad čisté latence linky. Deadline platí i pro doručenou odpověď.

Před a po poll se kontroluje zrušení; `finally` zavře FD. EAGAIN/EINTR znamenají
další čekání do původního deadlinu, chyba nedosažitelnosti nulovou odpověď,
neplatný FD explicitní nedostupnost ICMP. `Network.bindSocket` stále předchází
`connect`; oprava nemění výběr sítě ani nevytváří fallback.

Čistá `matchesIcmpEchoReply` kontroluje délku, typ Echo Reply, kód0, sekvenci a
celý nonce payload. Nekontroluje shodu identifikátoru/checksum s původním bufferem,
protože je nastavuje kernel ping socketu. `IcmpEchoTest` ověřuje správnou a cizí
odpověď, každý změněný bajt sekvence/nonce, chybný typ/kód, zkrácení, překročení
bufferu a požadavek bez nonce; nepoužívá Android/JVM importy.

Instrumentační fáze `lan-icmp-rtt` získá20 sekvenčních vzorků na emulátorovém aliasu
hosta `10.0.2.2` s timeoutem1000ms a25ms odstupem **mimo měřený interval**.
Log obsahuje jednotlivé nezaokrouhlené hodnoty a min/avg/max. Marker
`20_REPLIES:PASS` dokládá odpovědi; závěr o odstranění kvantování na10ms vyžaduje prohlédnutí těchto hodnot, nikoli jen marker. Měření, SHA APK
a meze výsledku doplní report08; fyzická Wi-Fi/VPN zůstává samostatný test.

Oficiální zdroje ověřené2026-10-26:
[Os.poll](https://developer.android.com/reference/android/system/Os#poll(android.system.StructPollfd[],int))
a [StructPollfd](https://developer.android.com/reference/android/system/StructPollfd)
jsou veřejné odAPI21;
[POSIX poll](https://pubs.opengroup.org/onlinepubs/009696799/functions/poll.html)
rozlišuje připravenost a timeout (timeout může být zaokrouhlen systémem);
[RFC792 Echo](https://www.rfc-editor.org/info/rfc792/) určuje typ/kód/sekvenci/data;
[Android kernel ping.c](https://android.googlesource.com/kernel/common/+/android-mainline/net/ipv4/ping.c)
nastavuje identifikátor podle socketu a přebírá sekvenci z požadavku.


### SAF a ovládání po revizi07 (LW13/WU7/WU14)

Všechny tři SAF launchery exportu jsou registrované na úrovni `LanScreen`,
ne v položce LazyColumn. Odscrollování exportních akcí tedy neodregistruje
callback systémového pickeru. Pole formátu/snapshotu zůstávají ve ViewModelu.
Checkbox čtení banneru a popisek tvoří společnou přepínací oblast48dp s rolí
Checkbox; odsazení používají společné `Spacing`. Systémový SAF/rotaci ještě
vyhodnocuje runtime/fyzický checklist, samotná změna scope není důkaz PASS.

## 6. Zařízení v síti (M2)

**Stav 2026-10-26:** datová vrstva a `LanDevicesViewModel` jsou napsané a pokryté
společnými unit testy; Compose obrazovka zatím neexistuje (staví ji jiný řez). Emulátor ani
fyzický telefon tuto funkci zatím neověřily.

Cíl je seznam zařízení jako „Discovery“ ve WiFimanu: po otevření obrazovky se bez
nastavování zobrazí brána, tento telefon a další odpovídající hosté se jménem; detail
zařízení ukáže IP, jména, živý ping se ztrátou a jedním klepnutím rychlý sken portů.

### Souhlas pro konkrétní síť

- Automatické hledání se řídí stavem `DiscoveryConsent` pro aktuální Wi-Fi:
  `UNKNOWN` → UI se zeptá „Je to vaše síť? Automaticky vyhledávat zařízení“;
  `ALLOWED` → hledání po aktivaci obrazovky (`setActive(true)`), nejvýše jednou za
  5 minut od posledního **dokončeného** hledání v této síti; `DENIED` → nikdy automaticky.
  Ruční tlačítko (`refresh()`) funguje v každém stavu a limit 5 minut nezohledňuje.
- Klíč sítě je HMAC-SHA256 s náhodným klíčem instalace nad kanonickým zápisem
  (SSID, IPv4 brána, podsíť) s délkovými prefixy (`lanNetworkIdentityBytes`). Ukládá se
  jen hex klíč a stav v soukromém `SharedPreferences` souboru `lan-devices`; SSID ani brána
  v čitelné podobě uložené ani logované nejsou.
- SSID Android bez oprávnění k poloze nevydá. Brána + podsíť (např. `192.168.1.1/24`)
  jsou společné mnoha cizím sítím, proto se `ALLOWED` bez známého SSID **neukládá** a platí
  jen do zániku ViewModelu (`consentRemembered = false`). `DENIED` se ukládá vždy.
  Oprávnění k poloze tato funkce nežádá; použije se jen, pokud ho uživatel udělil Wi-Fi analyzéru.
- Čas posledního dokončeného hledání je monotónní `elapsedRealtime`; po restartu telefonu
  (čas „v budoucnosti“) se limit považuje za vypršený.

### Rozsah a sondy

- Rozsah je vždy vlastní podsíť Wi-Fi zúžená na nejvýše /24 (`lanDevicesRange`, stejné
  pravidlo jako výchozí CIDR nástroje Hosté). Jakýkoli důvod potvrzení
  (`targetConfirmationReasons`, např. neprivátní adresa) automatické i ruční hledání na
  této obrazovce zakáže (`RANGE_NOT_ALLOWED`); jiné rozsahy zůstávají jen v nástroji Hosté
  s výslovným potvrzením.
- Živost hostu: stejná pravidla jako discovery nástroje Hosté — ICMP echo (1000 ms), jinak
  TCP 80/443/22/445 (400 ms), OPEN nebo RST. Souběžnost 32 hostů; po odmítnutí ICMP
  socketu se další hosté zkoušejí jen TCP (`icmpUnavailable`).
- Jména jen pro živé hosty: PTR přes existující `LanBackend.reverse` (1,5 s) a souběžně
  NetBIOS NBSTAT (RFC 1002 node status pro `*`, UDP 137, timeout 800 ms, nejvýše 8 dotazů
  současně). Socket je před `connect` vázaný `Network.bindSocket(DatagramSocket)` na
  vybranou Wi-Fi; při chybě se nic nepřepíná. Parser (`NetBiosParser.kt`, commonMain)
  kontroluje ID transakce, příznak odpovědi, opcode/RCODE, počty sekcí, typ/třídu,
  RDLENGTH a počet jmen, odmítá datagram nad 1024 B. Bajty jména mimo tisknutelné ASCII
  nahradí U+FFFD (OEM kódová stránka není známá). Jméno stanice = unikátní sufix 0x00,
  jinak 0x20; skupina = skupinové jméno se sufixem 0x00; odregistrovaná/konfliktní jména se ignorují.
- mDNS jména, služby DNS-SD a SSDP/UPnP doplňuje identifikace po dokončeném hledání,
  viz [Identifikace: mDNS a SSDP/UPnP](#identifikace-mdns-a-ssdpupnp-m2-2026-10-26).

### Seznam

- `LanDevice`: adresa, jména podle zdroje (NetBIOS/mDNS/PTR, sanitizovaná, max. 64 znaků;
  PTR jen platné LDH jméno), skupina NetBIOS, `isGateway`, `isSelf`, první/poslední viděno
  (monotónní ms), poslední RTT, metoda, `stale`, otevřené porty.
- Nové hledání aktualizuje `lastSeen` a zachová jména a porty. Zařízení neviděné
  v posledním **dokončeném** hledání je `stale`; po 30 minutách bez odpovědi zmizí.
  Zrušené hledání nic za stale neoznačí. Nejvýše 4096 zařízení.
- Řazení: brána, pak číselně podle IPv4. Zobrazované jméno: NetBIOS > mDNS > PTR > IP.
  Pořadí zůstává i po doplnění mDNS (rozhodnutí 2026-10-26): NetBIOS je jméno, které
  uživatel na Windows/Samba/NAS sám nastavil (např. `NAS-DEMO`), kdežto mDNS jméno bývá
  generované a s příponou kolize (`laptop-demo-2.local`, `Android-3.local`). Zdroj
  ukazuje čip v řádku seznamu; všechna jména podle zdroje jsou v detailu (panel Identifikace).

### Detail zařízení

- `openDevice(address)` jen pro zařízení ze seznamu v aktuálním rozsahu: ICMP ping přes
  stávající `LanBackend.ping` bez TTL, 1× za sekundu, 10 vzorků → ztráta %, min/průměr/max
  (`pingStatistics`). Odpověď aktualizuje RTT a `lastSeen` v seznamu. `closeDevice()` ping
  zastaví; opětovné `openDevice` jej spustí znovu. Pro tento telefon se ping nespouští.
- `scanPorts(address)`: přednastavených 105 portů (`commonPorts`), existující `tcpProbe`,
  timeout 1000 ms, souběžnost 16, bez banneru. Otevřené porty se průběžně zapisují do
  zařízení; `portsScannedElapsedMs` jen po dokončení. Zavření detailu sken portů nezastaví,
  `cancel()` ano.
- `setActive(false)`, změna Wi-Fi a `onCleared` ruší hledání, ping i sken portů. Chyby jsou
  typované (`LanDevicesError`), bez textu výjimek.

| Ověření | Stav |
|---|---|
| Model, sloučení, stale, řazení, priorita jmen, sanitizace | `LanDevicesTest` (commonTest) |
| Klíč sítě, perzistence souhlasu, limit 5 min, rozsah ≤ /24 | `LanDeviceConsentTest` (commonTest) |
| NetBIOS dotaz a parser (více jmen, zkrácení, příznaky, přetečení počtu, netisknutelné znaky) | `NetBiosParserTest` (commonTest) |
| `LanDevicesViewModel`, NetBIOS socket, čtení SSID/brány | NOT RUN — bez UI a bez emulátoru; patří do UI řezu a fyzického checklistu (Windows/Samba host, SSID s/bez oprávnění k poloze, VPN) |

### Identifikace: mDNS a SSDP/UPnP (M2, 2026-10-26)

Spouští se **jen po dokončeném hledání** (automatickém se souhlasem `ALLOWED`, nebo ručním
Obnovit) a jen pro živé, ne-stale hosty z tohoto hledání v rozsahu `lanDevicesRange`
(bez telefonu samotného). Ruší ji nové hledání, Zrušit, `setActive(false)`, změna Wi-Fi a
u automatického běhu odvolání souhlasu. Výsledky se průběžně zapisují do `LanDevice`
(`names` se zdrojem MDNS, `mdnsServices`, `upnp`); stav `LanDevicesState.identification`.
Chyby jsou tiché (žádná odpověď = žádná data), jen ztráta Wi-Fi ukáže `NETWORK_CHANGED`.
Zrušit u čipu „zjišťuji jména a služby…“ volá `cancelIdentification()` a zastaví jen
identifikaci, ne sken portů ani ping v detailu. `identificationHosts` drží adresy dotázané
posledním průchodem; detail napíše „neodpovědělo na mDNS ani SSDP“ jen pro takovou adresu
po stavu FINISHED, po zrušení/selhání „nebyla dokončena“, jinak (zařízení nedotázané,
nové hledání) „zjistí se po dokončeném hledání“.

**mDNS (≈2,5 s).** `MdnsProbe`: jeden `MulticastSocket` na efemérním portu, vázaný
`Network.bindSocket` na vybranou Wi-Fi, `IP_MULTICAST_IF` = Wi-Fi rozhraní, TTL 255 (RFC 6762
§11; 224.0.0.251 je link-local a router ho nepředává). Posílá reverzní PTR dotazy
`d.c.b.a.in-addr.arpa` s bitem QU, nejvýše 32 otázek v datagramu (komprese přípon, ~350 B),
a jeden výčet služeb `_services._dns-sd._udp.local`; vše dvakrát (t = 0 a 1 s), protože
multicast na Wi-Fi se ztrácí. Zdrojový port ≠ 5353 je „legacy unicast“ (RFC 6762 §6.7):
odpovědi chodí unicastem na náš port, proto **není potřeba členství ve skupině ani
`WifiManager.MulticastLock`** (a tedy ani `CHANGE_WIFI_MULTICAST_STATE`). Přijímá se nejvýše
256 datagramů, každý ≤ 9000 B, jen ze zdrojového portu 5353 a ze zdrojové adresy v rozsahu.
Výklad (`interpretMdnsResponse`, commonMain): reverzní PTR → `host.local` i záznam A
`host.local` → adresa dávají jméno adrese v rozsahu. Pořadí nezávisí na pořadí datagramů
(`MdnsHostName.rank`): odpověď od adresy samotné > odpověď jiného hosta (např. sleep proxy,
jen jako záloha), v rámci toho reverzní PTR > A; při shodě platí dřívější. Služby (PTR
z výčtu nebo z typu služby `_x._tcp.local`) se přiřadí **jen odesílateli** datagramu,
nejvýše 16 na zařízení.
ID transakce musí sedět, nebo být 0 (někteří respondéři ID neopakují).
Trade-off: zařízení, která legacy unicast neimplementují (část IoT), mDNS jméno nedostanou;
pasivní poslech multicastu by vyžadoval MulticastLock, port 5353 (sdílený se systémovým
mDNS) a přijímal by provoz celé sítě — záměrně nezvoleno.

**Parser DNS** (`DnsMessage.kt`, commonMain, čistý Kotlin nad `ByteArray`): hlavička,
otázky a všechny sekce záznamů (nejvýše 128 dekódovaných), A/PTR/SRV/TXT, ostatní typy jen
délka. Komprese jménem jen **zpětnými** ukazateli (cíl < pozice ukazatele) a nejvýše
32 skoků → smyčka není možná; label ≤ 63 B, jméno ≤ 255 B, typy labelů 0x40/0x80
odmítnuty; RDATA musí odpovídat typu (A = 4 B, jméno nepřesáhne RDLENGTH, řetězce TXT
uvnitř RDATA). Neplatné UTF-8 v labelu → U+FFFD; zobrazované jméno dále `sanitizeLanText`
(64 znaků).

**SSDP (≈3 s) a UPnP popis.** `UpnpProbe.search`: `M-SEARCH * HTTP/1.1`, `ST: upnp:rootdevice`
(jedna odpověď na zařízení místo jedné na službu u `ssdp:all`), `MX: 2`, na
239.255.255.250:1900 dvakrát (0 a 0,5 s), TTL 1, ze socketu vázaného na Wi-Fi; odpovědi
unicastem na náš port. Bere se první odpověď na odesílatele, jen od živých hostů v rozsahu,
≤ 4096 B, nejvýše 256 datagramů. `parseSsdpResponse` čte `LOCATION`, `SERVER`, `ST`, `USN`
(hodnoty sanitizované, LOCATION jen tisknutelné ASCII ≤ 512 znaků).
Popis se stáhne **jen** když `upnpDescriptionTarget` povolí: schéma `http`, host je
literál IPv4 shodný s odesílatelem SSDP odpovědi a leží ve vlastní podsíti (≤ /24), bez
user-info, port 1–65535. Stahuje `UpnpProbe.describe`: ručně psaný `GET … HTTP/1.0`
s `Connection: close` přes `Network.socketFactory` (Wi-Fi), connect + čtení celkem 2 s,
odpověď nejvýše 64 KiB, jen status 200, žádné přesměrování, `Content-Length` nebo
ohraničený chunked; nejvýše 8 stahování souběžně. Čtení skončí, jakmile je odpověď úplná
(`httpResponseComplete`: všechny bajty podle Content-Length, poslední chunk, nebo jiný status
než 200), bez čekání na zavření spojení. Odpověď bez Content-Length i chunked se čte do EOF;
když server spojení nezavře do 2 s, částečná odpověď se zahodí (mohla by být useknutá).
Selhání jednoho socketu (i jeho vytvoření) zruší jen danou sondu či zařízení, ne celou
identifikaci; celou ji ukončí jen ztráta Wi-Fi. XML čte vlastní malá čtečka
(`parseUpnpDescription`, commonMain): jakákoli deklarace `<!DOCTYPE`/`<!ENTITY` dokument
odmítne (žádné DTD ani externí entity), dekóduje se jen 5 předdefinovaných a číselných
entit, hloubka ≤ 32, ≤ 4096 tagů, nespárované tagy → null. Čte jen kořenový
`root/device`: `friendlyName`, `manufacturer`, `modelName`, `modelNumber`, `deviceType`,
`presentationURL` (zobrazované hodnoty ≤ 128 znaků; `presentationURL` se nezkracuje —
nad 512 znaků se zahodí, aby tlačítko nikdy neotevřelo useknutou adresu). `presentationURL` se jen zobrazí; tlačítko
„Otevřít v prohlížeči“ (ACTION_VIEW, jen na klepnutí) je jen pro absolutní `http(s)` URL
s literálem IPv4 ve vlastní podsíti, nebo cestu `/…` vůči adrese popisu
(`upnpPresentationTarget`).

**Zátěž.** Identifikace běží až po skončení průchodu 32 hostů, ne souběžně s ním: 2 UDP
sockety, řádově jednotky multicast datagramů za hledání a nejvýše 8 TCP spojení současně
(limit pro rychlý sken portů je 16). Hosty, kteří odpovídají jen na mDNS/SSDP (ne na ICMP ani
TCP sondy), identifikace do seznamu nepřidává — to by vyžadovalo novou metodu živosti.

| Ověření | Stav |
|---|---|
| DNS kodek: komprese, smyčky, zkrácení, limity labelu/jména, RDATA, náhodné bajty | `DnsMessageTest` (commonTest) |
| mDNS dotazy a výklad odpovědí (rozsah, ID, služby, limity) | `MdnsIdentityTest` (commonTest) |
| SSDP, pravidlo LOCATION, HTTP odpověď, XML čtečka, presentationURL | `UpnpIdentityTest` (commonTest) |
| Model: služby, UPnP, zachování po novém hledání, priorita jmen | `LanDeviceIdentityTest` (commonTest) |
| Sockety (legacy unicast, MULTICAST_IF, TTL), HTTP fetch, UI panel | NOT RUN — bez emulátoru; fyzický checklist M2-30 až M2-39 ([testovani.md §11](../testovani.md)) |
