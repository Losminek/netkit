# NetKit — iOS analýza

**Stav 2026-11-01:** P0 ověřena v ARM simulátoru — SwiftUI nad `:shared`, link, build, Kotlin/Native,
XCTest bridge a CS/EN UI testy prošly v [CI 41219799892](https://github.com/Losminek/netkit-ios/actions/runs/41219799892).
P1 prošla v [CI 41236666699](https://github.com/Losminek/netkit-ios/actions/runs/41236666699).
P2b prošla v [CI 41286184503](https://github.com/Losminek/netkit-ios/actions/runs/41286184503):
45 XCTest a 6 UI testů. P3 prošla v [CI 41287557230](https://github.com/Losminek/netkit-ios/actions/runs/41287557230).
Opravy revize R1 (interní záznam) prošly v [CI 41292675688](https://github.com/Losminek/netkit-ios/actions/runs/41292675688) (XCTest 68, UI 8); 2026-11-01 přeneseno do vývojového repa a od verze 1.0.1 součástí veřejného exportu.
Parita SSH profilů v2 (§13) prošla v [CI 41323807968](https://github.com/Losminek/netkit-ios/actions/runs/41323807968)
na `f3b5fad`: XCTest 83/0, UI 18/18 CS/EN a Linux PASS.
Aktuální implementaci a omezení popisují §8–13.
Níže původní analýza z 2026-10-27 (D12) a v §7 ověření
buildu bez Macu. Údaje označené „ověřit“ je nutné potvrdit v oficiální dokumentaci Applu
v době, kdy se o iOS bude rozhodovat. Údaje v §6 byly ověřeny 2026-10-27; **[P]** = prokázáno
zdrojem, **[O]** = odhad.

## 1. Proč iOS a pro koho

Kolega chce stejnou aplikaci na iPhonu. správce iOS nepoužívá, takže jde o službu kolegovi, ne o
vlastní potřebu. To ovlivňuje prioritu: iOS je mimo M1 a mimo M2.

## 2. Co iOS dovolí a nedovolí

| Funkce | Android (M1) | iOS | Poznámka |
|---|---|---|---|
| Wake-on-LAN | ano | **ano** (UDP) | UDP broadcast vyžaduje Local Network oprávnění; multicast/broadcast může vyžadovat entitlement `com.apple.developer.networking.multicast` (ověřit) |
| LAN discovery | TCP/ICMP/mDNS | **částečně** | bez raw ICMP, ICMP přes `SimplePing`-styl (ověřit), TCP probe ano, Bonjour (`NWBrowser`) ano; Local Network permission |
| Ping, port scan, DNS | ano | **ano** | TCP sokety (Network.framework), ICMP ping přes Apple příklad |
| Traceroute | přes `ping -t` | **omezeně / ne** | bez raw soketů; ověřit možnosti |
| SSH klient | ano | **ano** | knihovny (SwiftNIO SSH, libssh2) — jiné než v Androidu; sdílené jen modely a logika |
| Wi-Fi scan okolních sítí | ano | **ne** | žádné veřejné API pro seznam AP |
| Aktuální SSID/BSSID/RSSI | ano | **ano s omezeními** | `NEHotspotNetwork.fetchCurrent` s entitlementem a oprávněním k poloze (ověřit) |
| Graf obsazení kanálů | ano | **ne** | vyžaduje scan |

Závěr: iOS verze je podmnožina — **WoL, ping/porty/DNS, mDNS, SSH, info o aktuální síti** — bez
Wi-Fi analyzéru.

## 3. Co vyžaduje vývoj a distribuce

- **macOS + Xcode** pro build iOS (Kotlin/Native pro Apple targety i Xcode proj). Na Linuxu jde sestavit
  jen Android a JVM. Řešení: GitHub Actions macOS runner (placené minuty), nebo zapůjčený Mac.
- **Apple Developer Program** (placený, roční — cenu ověřit): nutný pro TestFlight a ad-hoc distribuci
  kolegům. Bez něj jde jen podpis „free provisioning“ s krátkou platností profilu (ověřit) — pro
  kolegu nepraktické. správce účet objednává, agent ne.
- Alternativy distribuce (alternativní tržiště v EU) mají vlastní podmínky; zvážit až při rozhodnutí.
- Kolega jako tester: TestFlight (vyžaduje App Store Connect a revizi beta buildu).

## 4. Architektonické důsledky teď

- `:shared` zůstává Kotlin Multiplatform: čisté funkce a state holdery bez `java.*` (viz CLAUDE.md).
  Platformní věci za rozhraními (`PacketSender`, `HostProber`, `SshClient`, `SecretStore`).
- iOS targety (`iosArm64`, `iosSimulatorArm64`) se zapnou až na stroji s macOS. V M0 se
  **nedeklarují**, aby build na Linuxu nezatěžovaly.
- UI iOS: dvě cesty, rozhodnutí odloženo:

| Varianta | Pro | Proti |
|---|---|---|
| Compose Multiplatform (sdílené UI) | jedno UI pro oba systémy | závislost na CMP pro iOS, terminálový komponent by byl vlastní, zákazník iOS očekává nativní pocit |
| SwiftUI nad `:shared` | nativní UX, jednodušší přístup k iOS API | druhá UI vrstva, musí ji psát někdo, kdo SwiftUI zná |

## 5. Co se udělá dřív, než se o iOS rozhodne

1. Po M2 samostatné rozhodnutí správce: zda a pro koho. **Rozhodnuto 2026-10-27 (D12): zatím ne**, viz §6.
2. iOS spike (na macOS): zkompilovat `:shared` pro iOS, odeslat WoL paket z jednoduchého SwiftUI shellu,
   změřit, co sdílení doopravdy ušetřilo.
3. Teprve poté plán iOS milníků, včetně nákladů na účet a CI.

## 6. Rozhodnutí 2026-10-27: iOS zatím ne

### Důvody

- Wi-Fi analyzér, tedy hlavní důvod NetKitu, na iOS **nejde**: „iOS has no general-purpose API for Wi-Fi
  scanning“, žádost o entitlement pro NEHotspotHelper na skenování byla odmítnuta **[P]**
  ([forum 718089](https://developer.apple.com/forums/thread/718089)).
- správce nemá Mac ani iPhone. Kotlin/Native nesestaví binárky pro Apple targety na Linuxu ani Windows **[P]**
  ([kotlinlang](https://kotlinlang.org/docs/native-target-support.html)); build jde jen na macOS (vlastní
  nebo CI), ladění Local Network a entitlementů potřebuje fyzický iPhone **[O]**.
- SSH je největší položka (viz §6 tabulka).
- Pro funkce LAN scan, ping, WoL a SSH existují hotové iOS aplikace **[O]**; pro kolegu to znamená, že
  vlastní iOS verze dává smysl jen pro funkci, kterou hotové aplikace nemají.

### Proveditelnost podle ověřených zdrojů

| Funkce | Verdikt | Zdroj |
|---|---|---|
| Wi-Fi scan okolních AP | **nejde** | **[P]** výše |
| Info o aktuální síti (SSID/BSSID, signál 0–1) | `NEHotspotNetwork.fetchCurrent`, entitlement Access Wi-Fi Information + oprávnění k poloze | **[O]** forum, referenci API se načíst nepodařilo |
| Wake-on-LAN (UDP broadcast) | vyžaduje restricted entitlement `com.apple.developer.networking.multicast` na reálném zařízení, žádá se formulářem, schválení není zaručené | **[P]** [Apple news](https://developer.apple.com/news/?id=0oi77447) |
| Local Network oprávnění | pro LAN scan, WoL i mDNS; Bonjour entitlement není potřeba, jen `NSBonjourServices` a popisný text | **[P]** stejný zdroj |
| ICMP ping | datagramový ICMP socket (styl SimplePing), TCP probe a Network.framework jsou veřejné API; traceroute nejistý | **[O]** ověřit na zařízení |
| SSH | SwiftNIO SSH: Apache-2.0, 0.x, „does not ship production-ready SSH clients“, Ed25519 a ECDSA; Citadel: MIT, SSH klient s PTY a SFTP, RSA jen ve forku; Kotlin KMP SSH knihovnu jsem nenašel | **[P]** repozitáře [swift-nio-ssh](https://github.com/apple/swift-nio-ssh), [Citadel](https://github.com/orlandos-nl/Citadel) |
| Speedtest | HTTP přes URLSession bez omezení | **[O]** |

Realistická podmnožina: mDNS, TCP port scan a ping, info o aktuální Wi-Fi, speedtest, WoL
(podmíněně entitlementem), případně SSH. Wi-Fi analyzér a graf kanálů odpadají.

### Účet, distribuce a náklady

| Položka | Fakta | Zdroj |
|---|---|---|
| Apple Developer Program | 99 USD ročně (cenu v Kč Apple nezveřejňuje, objeví se při enrollmentu), auto-renewable | **[P]** [programs](https://developer.apple.com/programs/whats-included/) |
| Typ účtu pro OSVČ | **individual**, jako prodejce se zobrazí občanské jméno; organizace vyžaduje právnickou osobu a D-U-N-S, DBA a obchodní jména se nepřijímají | **[P]** [enrollment](https://developer.apple.com/support/enrollment/) |
| Enterprise Program | 299 USD, ≥100 zaměstnanců, D-U-N-S, jen interní distribuce zaměstnancům: **nepoužitelné** | **[P]** [enterprise](https://developer.apple.com/programs/enterprise/) |
| Distribuce kolegům | TestFlight: build platí 90 dní, až 100 interních a 10 000 externích testerů, první externí build prochází beta review. Unlisted: žádost formulářem, projde plným review, přístup přes odkaz, bez 90denního limitu. Ad Hoc: max. 100 zařízení (UDID). Custom Apps: přes Apple Business, jen pro konkrétní organizace. EU alternativní distribuce vyžaduje organizaci a finanční kritéria: **pro OSVČ nedostupné** | **[P]** [TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/), [Unlisted](https://developer.apple.com/support/unlisted-app-distribution), [Web Distribution EU](https://developer.apple.com/support/web-distribution-eu/) |
| DSA trader | deklaruje se vždy; trader zveřejní adresu (lze P.O. Box), telefon a e-mail; kdo v EU nedistribuuje přes App Store (jen TestFlight), traderem není | **[P]** [DSA help](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/) |
| Xcode a SDK | od 2026-04-28 upload s Xcode 26 a iOS 26 SDK; od 2026-09-09 cílit na iOS 13+ | **[P]** [upcoming-requirements](https://developer.apple.com/news/upcoming-requirements/) |
| CI pro build | Xcode Cloud 25 h/měsíc v členství; GitHub Actions macOS 0,062 USD/min; Codemagic 500 min zdarma, dále 0,095 USD/min; MacStadium M4 mini od 149 USD/měs.; vlastní Mac mini ≈ 26 000 Kč (sekundární zdroj) | **[P]** [Xcode Cloud](https://developer.apple.com/xcode-cloud/), [GitHub](https://docs.github.com/en/billing/reference/actions-runner-pricing), [Codemagic](https://codemagic.io/pricing/), [MacStadium](https://www.macstadium.com/pricing) |
| Export compliance | aplikace se standardním šifrováním určuje export compliance; bez SSH stačí `ITSAppUsesNonExemptEncryption=NO`; SSH s vlastním kryptem pravděpodobně zasáhne ECCN 5D992 a francouzský režim ANSSI **[O]**, ověřit mimo tento dokument | **[P]** [export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance) |
| iPhone pro testy | použitý s iOS 26 zhruba 5–8 tis. Kč | **[O]** |

Odhad času **[O]**: spike 2–4 dny; MVP bez SSH 2–4 týdny práce agenta plus správceovy testy na zařízení;
SSH navíc 2–4 týdny (knihovna, terminál UI, export compliance); účet, TestFlight, privacy policy 1–2 dny.
Za rok 1: 99 USD účet, build téměř 0 Kč (Xcode Cloud nebo Codemagic), případně Mac (≈ 26 000 Kč) a iPhone.

### Podmínky znovuotevření

iOS se znovu otevře, až platí **všechno** z toho:

1. Kolega potřebuje konkrétní funkci, kterou hotové iOS aplikace nemají (ne Wi-Fi analyzér, ten na iOS nejde).
2. Je jasné, zda je SSH povinné (rozhoduje export compliance a rozsah práce).
3. Je k dispozici Mac nebo CI s rozpočtem a iPhone pro testy.
4. správce rozhodl o účtu (individual, trader status) a distribuci (TestFlight nebo Unlisted).
5. Proběhlo vydání v1 a případně Play (D9, D12); pořadí: spike → účet, ne naopak.
6. Před placením se ověří stav multicast entitlementu (formulář je zdarma).

### Co zůstává otevřené

Má firma kolegů Apple Business Manager (jen pro Custom Apps)? Je správce ochoten zveřejnit údaje obchodníka?
Compose Multiplatform (stable od 1.8.0, květen 2025 **[P]** [JetBrains](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/))
nebo SwiftUI nad `:shared`: stále odloženo (§4).

## 7. Build bez Macu (ověřeno 2026-10-31)

| Cesta | Verdikt | Zdroj |
|---|---|---|
| xtool (Linux, SwiftPM → iOS app) | **nepoužít**: staví jen SwiftPM projekty, Kotlin `:shared` nepřeloží; potřebuje rozbalený Xcode, jehož licence povoluje SDK jen na Apple hardware; bez simulátoru, nasazuje na fyzické zařízení | **[P]** [xtool](https://github.com/xtool-org/xtool), [Xcode and Apple SDKs Agreement](https://www.apple.com/legal/sla/docs/xcode.pdf) |
| Kotlin/Native na Linuxu | `.klib` pro Apple targety jen bez cinterop; framework/XCFramework jen na macOS; Gradle link na Linuxu tiše přeskočí | **[P]** [native-target-support](https://kotlinlang.org/docs/native-target-support.html) |
| GitHub Actions macOS | standardní runnery ve **veřejném** repu zdarma; v soukromém repu z kreditu (Pro 18 USD/měs, macOS 0,062 USD/min) | **[P]** [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions) |

Zvoleno (D28): vývoj ve veřejném `Losminek/netkit-ios`, build a testy v simulátoru na macOS runneru.

## 8. Implementace SwiftUI — P0 (2026-10-31)

Platná volba je **SwiftUI, iOS 17+**, nad Kotlin Multiplatform `:shared`. Historické odložení UI
a podmínka Apple účtu v §4–6 již neplatí pro vývoj a simulátorové testy. Účet, podpis pro zařízení
a distribuce nejsou součástí této fáze. Android aplikace ani její platformní implementace se nemění.

### Co je připraveno

- `shared/build.gradle.kts` deklaruje `iosArm64` a `iosSimulatorArm64` jen na macOS nebo při
  `-Pnetkit.ios=true`. Obě varianty poskytují statický framework `NetKitShared` a úlohu
  `:shared:assembleNetKitSharedDebugXCFramework`. Linux bez přepínače nadále deklaruje Android a JVM.
- `iosApp/NetKit.xcodeproj`, sdílené schéma `NetKit`: jedna SwiftUI obrazovka zobrazuje verzi a
  výsledek existujícího `WolDomainKt.parseIpv4`. Jde o lokální výpočet nad dokumentační adresou,
  bez síťového provozu. `commonMain` se kvůli této kostře nemění.
- CS/EN texty jsou v `Localizable.xcstrings`. `PrivacyInfo.xcprivacy` deklaruje žádné sledování,
  sběr dat ani použití required-reason API v kostře. Síťová oprávnění, Bonjour služby a popis polohy
  se doplní spolu s konkrétní funkcí; P0 je nevyžaduje ani nežádá.
- Kotlin `iosTest` ověřuje parser a výpočet broadcastu bez soketu. XCTest volá skutečný sdílený parser;
  UI testy spouštějí aplikaci v češtině a angličtině a kontrolují výsledek i verzi.

### Sestavení a ověření

Workflow [ios.yml](../.github/workflows/ios.yml) má samostatný Linux job pro Android/JVM regresi
a macOS job pro ARM simulátor. V privátním `netkit-dev` se spouští jen ručně (`workflow_dispatch`),
protože macOS minuty se počítají 10×; spustit po změně `iosApp/`, `iosMain` nebo sdíleného API,
které `iosMain` volá. Verze toolchainu a jejich zdroje jsou v
[závislostech](zavislosti.md#6-ios-kostra-a-ci-p0-2026-10-31).
macOS job kontroluje existenci binárky, hlavičky a module map frameworku; pouhý úspěšný Gradle
příkaz nestačí. Následují `xcodebuild build` a `xcodebuild test`; od opravy P2 3/3 používají
lokální ad-hoc podpis simulátoru (`CODE_SIGN_IDENTITY=-`) kvůli Keychain testům.
Tato [pseudoidentita nevyžaduje certifikát](https://developer.apple.com/library/archive/documentation/Security/Conceptual/CodeSigningGuide/RequirementLang/RequirementLang.html),
Apple účet, vývojový tým ani provisioning profil. Nejde o podpis pro distribuci či zařízení.
Logy a `.xcresult` se ukládají jako artefakt `ios-simulator-results` i při neúspěchu.

**Vývoj:** iOS je od verze 1.0.1 součástí veřejného exportu. Pro samostatné iOS kolo se
`Losminek/netkit-ios` dočasně odarchivuje, synchronizuje se sdílené jádro a pracuje se na větvi
`codex/*`. Po nezávislé revizi a zeleném `ios.yml` se změny vracejí přes PR do vývojového repa;
po uzavření kola se veřejné iOS repo znovu archivuje. Push a CI zajišťuje pověřený reviewer.
Android se z tohoto repa nevydává ani netaguje.

Na macOS lze pro simulátor spustit:

```sh
./gradlew --no-daemon --dependency-verification strict -Pnetkit.ios=true \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:iosSimulatorArm64Test
xcodebuild -project iosApp/NetKit.xcodeproj -scheme NetKit \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.5' \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build test
```

Xcode projekt odkazuje přímo na framework odpovídající platformě a konfiguraci. Plný XCFramework
(device + simulator) sestaví `:shared:assembleNetKitSharedDebugXCFramework` na macOS;
CI P0 ověřuje pouze debug simulator slice. Release framework ani device slice zatím ověřeny nejsou.
Linuxové spuštění s přepínačem není důkazem Apple linkování. Před další fází musí projít oba CI joby.

### Dosud neimplementováno a neověřeno

Síťové funkce P1 popisuje §9. Implementaci SSH, Keychainu a terminálu P2 popisuje §10.
iOS nemá Wi-Fi scan okolí ani graf kanálů; ty nejsou plánovány.
P0 bridge, linkování a lokalizované UI testy potvrdil běh CI uvedený v úvodu. To neověřuje
síťové transporty P1 ani chování na zařízení.

Později na fyzickém zařízení, nikoli v této fázi:

- Local Network dialog: povolení umožní cílenou LAN operaci, odmítnutí zobrazí srozumitelný stav.
- Wi-Fi info: bez entitlementu/polohy prázdný stav; s oprávněním údaje pouze o aktuální síti.
- WoL: unicast na povolený cíl včetně opakování po vypršení ARP cache uspaného cíle.
  Úspěch UDP send znamená předání systému, nikoli důkaz odeslání rámce či probuzení.
  Broadcast až s oprávněným multicast entitlementem.
- ICMP: datagramový ping v povolené síti, timeout a zrušení bez raw socketů.
- Entitlementy: ověřit dostupnost před zařízením/distribucí, nepožadovat je kvůli CI simulátoru.

## 9. Síťové nástroje — P1 (ověřeno v simulátoru)

P1 používá Swift transporty nad existující doménou `:shared`. Adaptéry v `iosMain` zpřístupňují
validaci portů, DNS zprávy, ICMP matcher, sanitizované mDNS názvy, rychlostní výpočty a skutečný
`WolController`. `commonMain`, Android aplikace a její platformní implementace se v P1 nemění.

### Transporty a rozsah

| Nástroj | Implementace | Omezení |
|---|---|---|
| TCP porty | `NWConnection`, sdílený parser portů; jeden zadaný host, nejvýše 64 portů a čtyři současná spojení | Každý port má timeout; odlišuje odpověď, odmítnutí, timeout a chybu. Nejde o automatický sken rozsahu. |
| DNS | UDP `NWConnection` na zadaný resolver, sdílené kódování a parsování zpráv | Kontroluje ID, otázku, třídu a typ, QR/RCODE a truncation; P1 UI dotazuje A, bez TCP fallbacku a DNSSEC validace. |
| Ping | IPv4 `SOCK_DGRAM` / ICMP, checksum a náhodný payload; shared matcher kontroluje sekvenci a celý payload | Jeden dotaz s RTT a timeoutem, bez raw socketu. Dostupnost skutečného ICMP čeká na zařízení. Traceroute není implementován. |
| mDNS | `NWBrowser` pro předem deklarovaný typ `_ssh._tcp`, sdílená sanitizace a model `LanDeviceName` | Omezené prohlížení služby po explicitním startu, nikoli seznam všech zařízení nebo všech Bonjour typů. |
| Aktuální Wi-Fi | `NEHotspotNetwork.fetchCurrent`, explicitní načtení SSID/BSSID | Bez Access Wi-Fi Information entitlementu vrací prázdný stav; entitlement se nepřidává ani nežádá. Nejde o sken okolí. |
| WoL | `WolController`, Swift implementace transportu přes UDP `NWConnection` a platformního úložiště | Unicast pouze v podsíti vybraného Wi-Fi rozhraní. Broadcast režim vytváří shared controller, transport jej bez restricted entitlementu odmítá. |
| LibreSpeed | URLSession HTTPS, šest paralelních download a tři upload úlohy, sdílený trace a výpočet po náběhu | URLSession může HTTP/2 či HTTP/3 multiplexovat; počet úloh není záruka počtu TCP spojení ani shody s Android metodikou. |
| NDT7 | HTTPS locate, validace shared parserem, URLSession WSS s ověřeným subprotokolem | Download vyhodnocuje klientský trace po náběhu, upload používá serverové AppInfo za celý test; metody se liší a UI to uvádí. |

Swift Network.framework byl zvolen pro TCP, DNS a WoL, protože sjednocuje rušení, timeouty a
výběr rozhraní s ostatními nativními API. POSIX je omezen na ICMP a čtení lokálního rozhraní,
kde Network.framework neposkytuje potřebný datagramový ICMP transport. Žádná nová síťová knihovna.
LAN sondy používají systémovou trasu; iOS P1 neslibuje Android vazbu všech LAN nástrojů na Wi-Fi.
Uživatel potvrzuje, že zkoumá vlastní nebo povolený cíl, před zahájením operace.

WoL připíná konkrétní `NWInterface`, vyžaduje Wi-Fi a při změně pozorované cesty ruší operace.
Bez entitlementu nelze zaručit detekci každé změny AP/SSID na stejném rozhraní; timeout a token
cesty jsou konzervativní ochrana, nikoli důkaz stejné fyzické sítě. Mobilní fallback pro WoL není.
Routovaný unicast mimo místní podsíť P1 nepovoluje. Úspěšné UDP odeslání samo nedokazuje probuzení.
Profily se ukládají atomicky do Application Support se zákazem zálohy, přes sdílený verzovaný
WoL codec a limit 1 MiB. Chyba čtení se nezamění za prázdný seznam ani automatický reset.
Adapter `WakeProber` podporuje připnutý TCP/ICMP pro profil s povolenou kontrolou; P1 formulář
zatím nevystavuje nastavení této kontroly u nových profilů.

### Oprávnění, soukromí a testy

`Info.plist` obsahuje `NSLocalNetworkUsageDescription`, `NSBonjourServices` a
`NSLocationWhenInUseUsageDescription`; popisy jsou v CS/EN `InfoPlist.strings`. Uživatelské texty
jsou ve String Catalogu. Aplikace bez entitlementu nežádá polohu, kterou pro Wi-Fi nemůže využít.
`PrivacyInfo.xcprivacy` deklaruje žádné sledování a důvod `35F9.1` pro měření intervalů.
Obsahuje i deklaraci diagnostických/síťových údajů předávaných při volitelném NDT7: veřejná IP
není anonymní údaj a M-Lab výsledky uchovává a zveřejňuje. Před každým startem je potvrzení
objemu dat a u NDT7 také zveřejnění IP a odkaz na zásady. App Store formuláře se v této fázi nevyplňují.

Speedtest nic nespouští automaticky, odpovědi mají velikostní limity a přesměrování se odmítají.
Přístupové URL s tokeny se neukládají ani nelogují. Testy nepřenášejí zátěž na veřejné servery:
TCP, DNS a WoL používají loopback; speed transporty používají lokální mocky. WSS fake ověřuje
orchestraci, nenahrazuje handshake skutečného M-Lab serveru ani měření rychlosti na zařízení.

Testy P1 prošly v CI uvedeném v úvodu; následná regrese prošla i v P2b. Linuxové testy samy
nemohou potvrdit Swift kompilaci, Objective-C export, Apple linkování ani síťové API simulátoru.
Fyzická Wi-Fi zůstává neověřena. Local Network dialog podle Apple simulátor nepodporuje.

Ověřené oficiální zdroje (2026-10-31):

- [Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy): Local Network, Bonjour, multicast/broadcast a omezení simulátoru.
- [NEHotspotNetwork.fetchCurrent](https://developer.apple.com/documentation/networkextension/nehotspotnetwork/fetchcurrent(completionhandler:)): podmínky údajů o aktuální Wi-Fi.
- [Apple privacy API reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype): důvod pro intervalové měření.
- [M-Lab NDT](https://www.measurementlab.net/tests/ndt/index.html) a [Apple privacy details](https://developer.apple.com/app-store/app-privacy-details/): předávaná IP, výsledky a deklarace sběru.


## 10. SSH klient a terminál (P2, 2026-10-31)

P1 je ověřena v simulátoru: [CI 41236666699](https://github.com/Losminek/netkit-ios/actions/runs/41236666699),
Linux i macOS PASS, XCTest 30/30, UI 3/3 a Kotlin/Native testy PASS.
P2b prošla v [CI 41286184503](https://github.com/Losminek/netkit-ios/actions/runs/41286184503):
45 XCTest a 6 UI testů. Tento běh ověřil Swift sestavení, Apple linkování a Keychain simulátoru;
lokální Linux navíc ověřuje přenosné Kotlin bridge testy, ale nenahrazuje macOS ani zařízení.

- SwiftUI obrazovka SSH umožňuje uložit/upravit profil, přihlásit se heslem nebo v aplikaci
  vygenerovaným Ed25519 klíčem, otevřít PTY shell a relaci zavřít. Veřejný OpenSSH klíč lze
  zkopírovat pro registraci na vlastním serveru. Tajemství jsou pouze v Keychainu.
- Používá oficiální SwiftNIO SSH 0.15.1; přesné přímé i tranzitivní verze a licence jsou
  v [zavislosti.md §8](zavislosti.md#8-ios-ssh-p2-ověřeno-2026-10-31). Podporovaná kryptografická
  sada je podmnožinou Android allowlistu, bez slabšího fallbacku.
- TOFU před autentizací ukáže endpoint, algoritmus a SHA256 otisk z SSH veřejného blobu.
  První klíč vyžaduje výslovné potvrzení; jiný již uložený klíč je odmítnut a UI ukáže varování.
  Reset důvěry je samostatně potvrzená akce a zavře aktivní relaci. Pozdní odpověď na zrušenou
  či vypršelou výzvu nemůže potvrdit další spojení. Metadata se ukládají atomicky a bez záloh.
- JSON import profilů používá `SshProfileImport`, maximálně 256 KiB a hloubku JSON 16,
  náhled přidaných/změněných/přeskočených položek a potvrzení. Změna hosta/portu/uživatele
  odpojí uložené heslo; import nepřenáší tajemství ani důvěru k serverům.
- Vykreslení používá shared `TerminalBuffer`, vstup `TerminalInput` a `TerminalComposition`.
  Zachovává VT subset, barvy, bold, kurzor a změnu velikosti PTY. Klávesnice má Ctrl, Esc a šipky;
  vložení řídicích znaků se potvrzuje a sanitizuje. Neimplementuje OSC schránku, odkazy ani obrázky.
- Kopie terminálu je lokální schránka s expirací 60 s; podrobnosti a meze v bezpečnostní dokumentaci.
  Terminál ani příkazy se nezapisují do souboru nebo logu. Příchozí a odchozí fronty jsou omezené.
- Jedna aktivní relace používá systémovou trasu včetně VPN. Pozadí má stejnou pětiminutovou
  ochrannou lhůtu jako sdílená politika, ale iOS může proces pozastavit nebo ukončit; není
  zapnutý background mode a aplikace neslibuje trvalé SSH na pozadí.

Omezení P2: RSA host/user klíče, import cizích privátních klíčů, keyboard-interactive,
SFTP/tunely, automatické startup příkazy a keepalive nejsou implementovány. Metadata startupCommand
/keepaliveSeconds se zachovají, ale transport je neprovádí. Generované privátní klíče se neexportují.
Původní P2 ponechávala po nahrazení přihlašovacího údaje neodkazované položky v Keychainu;
opravu úklidu a mazání profilů/klíčů řeší R1 (§12). Tato omezení nezavádějí slabší autentizaci.

XCTest používají skutečný NIOSSH server pouze na 127.0.0.1, runtime generované host/user klíče
a heslo, bez spouštění příkazů hostitele. Prověřují password/key auth, TOFU před autentizací,
změnu host key, zrušení/pozdní potvrzení, Keychain a sdílený terminál. UI smoke přidává prázdný
stav SSH. Výsledek P2b je uveden výše; dosavadní síťové testy zůstávají součástí regrese.

Od opravy P2 3/3 běží unit testy v hostitelské `NetKit.app` přes `TEST_HOST`/`BUNDLE_LOADER`,
aby používaly oprávnění procesu aplikace. Test target obsahuje jen testy a fixture;
aplikační typy zpřístupňuje `@testable import NetKit` s testability v Debug. NetKitShared
linkuje aplikace; v test targetu je odstraněn explicitní link i autolink tohoto frameworku.
Import jeho typů zachovává framework search paths. Přímé Swift Package produkty lokálního
SSH test serveru zůstávají. P2b doplnila aplikační produkt `NIOFoundationCompat` z již
pinovaného swift-nio; test target jej přímo nelinkuje. Linkování a Keychain potvrdil zelený běh P2b.

**Export compliance před distribucí:** SSH přidává do aplikace vlastní protokolovou vrstvu
s kryptografií, i když matematické primitivy na iOS poskytuje CryptoKit. Hodnota
`ITSAppUsesNonExemptEncryption` a případné podklady se rozhodnou před distribucí;
P2 ji nenastavuje a nečiní právní závěr o výjimce. Zdrojem postupu je
[Apple export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance/)
(ověřeno 2026-10-31). Apple účet, podepisování pro zařízení, TestFlight a App Store zůstávají mimo rozsah.

Na zařízení zbývá ověřit zamčený Keychain, pozastavení/ukončení aplikace, změnu síťové cesty,
VPN a Wi-Fi/mobilní přechod, IME/hardwarovou klávesnici a skutečné chování schránky/Universal Clipboard.
Simulátor tato ověření nenahrazuje.


## 11. Dokončení — P3 (ověřeno 2026-11-01)

P3 upravuje navigaci na právě čtyři záložky ve stejném pořadí jako Android:
Přehled / Zařízení / SSH / WoL (EN Overview / Devices / SSH / WoL). Přehled zobrazuje
aktuální Wi-Fi přímo a odkazy na podobrazovky Speedtest a O aplikaci; Zařízení obsahuje mDNS, ping,
TCP porty a DNS. Systémová položka More se nepoužívá. Spektrum není součástí iOS.
CS i EN UI testy mají ověřit čtyři položky, přístup k O aplikaci a prázdné stavy (AC-009).
P3 potvrdil zelený běh [CI 41287557230](https://github.com/Losminek/netkit-ios/actions/runs/41287557230);
nejde o ověření na fyzickém zařízení.

O aplikaci poskytuje verzi, lokalizovaný odkaz na zásady a offline licence komponent.
`IosAboutDomain.privacyUrl(language:)` přijímá jazykový kód: `cs` (bez ohledu na velikost
písmen) vrátí `https://www.daniel.losak.cz/netkit/privacy`, ostatní kódy
`https://www.daniel.losak.cz/en/netkit/privacy`. Wrapper předává českou základní URL a lokalizaci
provede sdílené `privacyPolicyUrl` (přes `localizedWebUrl`) z `update/ProjectLinks.kt`.
Kotlin testy kontrolují českou variantu i anglický fallback bez síťového přístupu.

MVP podporuje host keys Ed25519 a ECDSA; přihlášení klíčem v UI používá pouze v aplikaci
vygenerovaný Ed25519 klíč. RSA host ani uživatelské klíče SwiftNIO SSH nepodporuje.
P3 při selhání vyjednávání zobrazuje obecnou CS/EN hlášku s informací o podporovaných
Ed25519/ECDSA host keys; chyba vyjednávání sama neurčuje, zda příčinou bylo RSA.
Nepřidává slabší fallback ani obejití kontroly host key. Offline licence a oznámení vycházejí z přesných pinovaných
verzí; přehled verzí, zdrojů a licencí zůstává v [zavislosti.md](zavislosti.md).

Fyzický iPhone, entitlementy, distribuce a export compliance zůstávají mimo toto ověření;
checklist zařízení v §8 a §10 platí dál. Nezávislá revize před merge je nadále nutná.


## 12. Opravy nezávislé revize — R1 (implementace, čeká CI)

R1 opravuje nálezy nad P0–P2 a přidává schválené cílené kontroly. Předchozí zelený P3 běh
nepotvrzuje tyto nové změny. Výsledek Swift sestavení, Kotlin/Native a simulátorových testů R1
se doplní po CI; na Linuxu nelze Apple transporty ani Keychain ověřit. Přenosné Kotlin bridge
testy prošly v dočasné JVM konfiguraci (22 testů); nejde o Swift ani Kotlin/Native běh.

- SSH: monotónní hodiny zahrnující spánek a životní cyklus na úrovni aplikace, překryv terminálu
  při neaktivní scéně, úklid odpojených hesel až po úspěšném zápisu metadat a mazání profilů/klíčů.
  Klíč odkazovaný jakýmkoli profilem nelze smazat. Chybějící metadata při čisté instalaci vedou
  k vyčištění příslušné Keychain služby; chybu čtení nelze zaměnit za čistou instalaci.
  Při startu s validními metadaty se přes `accountIDs` znovu porovnají reference a odstraní
  osiřelé položky, takže neúspěšný dřívější úklid lze zopakovat. Při poškozených nebo nečitelných
  metadatech se Keychain tímto postupem nemaže. Skutečné snímky přepínače aplikací s překryvem
  terminálu zatím nebyly ověřeny (NOT RUN).
- Změněný host key zůstává bezpečnostním varováním i při chybě zápisu odmítnutého otisku.
  UI doplňuje uložený/nabízený otisk a kontext dříve odmítnutého klíče. Import ukazuje
  `user@[host]:port → user@[host]:port` ze sdíleného endpointu; změna cíle odpojí heslo,
  ale záměrně zachová `keyRef`. Náhled a TOFU zůstávají povinné.
- LAN: kontrola vlastní sítě dostává aktuální Wi-Fi CIDR a konkrétní důvody potvrzení;
  odepřené Local Network oprávnění má vlastní chybu. Vstupy se ořezávají a validují před startem,
  zrušení čistí průběžný stav. Cílené testy doplňují souběžnost a rušení. Klasifikace PolicyDenied
  má test čisté rozhodovací funkce; skutečný `currentPath.unsatisfiedReason` při odepření
  oprávnění vyžaduje zařízení, nikoli jen tuto statickou kontrolu.
- LibreSpeed upload zmenšuje tělo pouze při HTTP 413, nejníže na 256 KiB; ostatní chyby
  měření ukončí jako neúspěšné. Pozdní 413 souběžného staršího pokusu s větším tělem vede
  k opakování s aktuální velikostí, i když ji mezitím jiný proud úspěšně přijal. Test NDT7
  řídí pořadí asynchronním signálem a zachovává potřebnou dobu přenosu.
- R1-11: RTT a rychlosti používají `formatted(.number.precision(.fractionLength(...)))`,
  což je doloženo statickou kontrolou volání API; skutečné CS/EN vykreslení R1 čeká na CI.
- WoL připíná generaci rozhraní a IPv4/masky místo každé aktualizace cesty. Wi-Fi vybírá
  podle typu v `availableInterfaces`, bez podmínky `path.status == .satisfied`, aby ji
  nevyřadilo samotné chybějící internetové připojení. Broadcast se bez entitlementu v editoru nenabízí.
  Unicast může po vypršení ARP cache uspaného cíle selhat i při úspěšném UDP send;
  bez zařízení nelze účinnost probuzení doložit. Toto omezení nenahrazuje varování v UI.

CI před kontrolou verze explicitně vybírá instalovaný Xcode 16.4 a Linux job navíc kontroluje
zakázané importy v `commonMain` stejně jako hlavní CI. R1 nemění `commonMain`, `commonTest`,
Android ani Gradle konfiguraci. Nezávislá kontrola diffu a zelené R1 CI zůstávají branou před předáním.


## 13. SSH profily v2 — implementace, čeká simulátorové CI

SwiftUI používá stávající sdílené jádro přes `IosSshDomain`; `commonMain` ani Android se nemění.
Export vytváří pouze v2 (`netkit-ssh-profiles.json`), se stejným pořadím jako v seznamu a bez
lokálních ID, referencí, přihlašovacích údajů, klíčů a důvěry serverů. Před otevřením Files se
připraví a ověří neměnný snapshot. Duplicitní jména se vypíšou, překročení 256 KiB má vlastní
hlášku; při těchto chybách se exportní dialog neotevře. Chyby lze zavřít tlačítkem OK.

Import přijímá v1, v2 a starý export telefonu podle [SSH §9](funkce/ssh.md#9-výměnný-formát-v2-starý-export-a-pořadí-profilů).
Náhled ukazuje změněná pole před → po: host, port, uživatel, ověření, startovací příkaz a keepalive.
Upozorňuje na změnu příkazu, odpojení hesla a chybějící klíč. Volba lokálního klíče přepočítá
náhled nových profilů; v1 se podle ní přepne mezi heslem a klíčem, v2/legacy zachová explicitní
typ ověření. Změna typu z PASSWORD odpojí heslo stejně jako změna cíle; nepoužívaný záznam
se smaže z Keychainu až po úspěšném uložení potvrzeného importu. Import nepřenáší klíče ani důvěru.

Pořadí se mění šipkami nahoru/dolů, ukládá se do metadat a přežije restart. Krajní šipky a šipky
během operace či otevřeného náhledu jsou zakázané. VoiceOver popisky CS/EN obsahují jméno profilu.
Profil KEY bez existujícího přiřazeného klíče před connect i reconnect zobrazí výzvu k editoru,
aniž vznikne transport. Editor zachová KEY a vyžádá klíč; vytvoření nového Ed25519 klíče je
přístupné i s jinými klíči v seznamu. Klávesnici editoru lze zavřít tlačítkem Hotovo/Done.
Návrat zachová netajná rozepsaná pole, heslo se vymaže.
Smazaný profil nelze z výzvy znovu vytvořit. Reconnect používá přesný profil potvrzený v dialogu.

Keyboard-interactive iOS MVP nepodporuje. Importovaný INTERACTIVE profil má v seznamu i při
pokusu o spojení CS/EN vysvětlení a doporučení upravit ověření na heslo nebo klíč. Editor žádá
výslovnou podporovanou volbu a tu předá při uložení; nepřevádí profil automaticky na heslo.
RSA, cizí privátní klíče, automatické startup příkazy a keepalive zůstávají mimo podporu MVP.

**Ověření:** zelené [CI 41323807968](https://github.com/Losminek/netkit-ios/actions/runs/41323807968)
na `f3b5fad` potvrdilo Swift build, Kotlin/Native, **83 XCTest / 0 selhání**, **18/18 UI testů
CS/EN** a Linux regresi. Exportní UI test přes výslovný DEBUG launch argument zachytí skutečná
připravená data; ověří JSON v2, pořadí po restartu a nepřítomnost lokálních ID, referencí,
klíčů a důvěry. Systémový panel Files při tomto testu neotevírá. Běžný fileExporter se nemění.

UI testy mají opt-in fixture harness jen v Debug simulátoru, izolovaný podle UUID od běžných
metadat a Keychain služby. Obsahuje pouze syntetická data a blokuje síťový transport.
Test náhledu obchází pouze systémový výběr fixture souboru; dále používá běžné dekódování,
plánování a potvrzení importu. Exportní DEBUG háček nahrazuje pouze systémový dialog,
nikoli přípravu nebo validaci exportu. Původní launch a SSH empty testy fixture harness nepoužívají.

**Na iPhonu zbývá:** fyzický export přes Files (otevření, zápis i zrušení dialogu), import
a přenos v1/v2/legacy mezi platformami, VoiceOver/velikost písma,
uložené pořadí po ukončení procesu, výzva → editor → vytvoření klíče, ovládání s klávesnicí,
zamknutý Keychain a reconnect při změně sítě nebo VPN. Platí také checklist §8/§10;
simulátor nenahrazuje fyzické přijetí. Distribuce, Apple účet a podpis pro zařízení nejsou součástí.
