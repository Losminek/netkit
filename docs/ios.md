# NetKit — iOS analýza

**Stav 2026-10-27:** analýza pro budoucí rozhodnutí. **Nic se nestaví. Rozhodnutí D12: iOS se
zatím nedělá** (viz §6). Údaje označené „ověřit“ je nutné potvrdit v oficiální dokumentaci Applu
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
