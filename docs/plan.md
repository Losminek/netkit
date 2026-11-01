# NetKit — plán

**Stav 2026-10-27:** návrh po dotazníku s správcem. Rozhodnutí D1–D25 jsou schválená
a neotevírají se znovu. Změnit je může jen správce výslovně. D9–D12 (veřejná publikace)
přibyla 2026-10-27 po researchi, viz [distribuce.md](distribuce.md); D12 mění D4.
**Realizace publikace je odložená** (správce 2026-10-27: „tyto věci aktuálně řešit nebudeme“);
rozhodnutí D9–D12 a dokumentace zůstávají jako podklad, nic z nich se neprovádí bez nového pokynu.

## 1. Cíl

Jedna vlastní aplikace pro Android, která u správce nahradí čtyři aplikace a doplní to, co
free verze nedávají:

| Dnešní aplikace | Co z ní správce používá | Kde to žije v NetKit |
|---|---|---|
| Network Analyzer (free) | info o Wi-Fi, sken LAN, ping | [lan-scanner](funkce/lan-scanner.md), [wifi-analyzer](funkce/wifi-analyzer.md) |
| WiFiman | podrobné skenování signálu | [wifi-analyzer](funkce/wifi-analyzer.md) |
| ConnectBot | rychlé SSH z telefonu | [ssh](funkce/ssh.md) |
| Wake-on-LAN | probuzení stroje v LAN | [wol](funkce/wol.md) |

Kritérium úspěchu M1: správce týden nepoužije žádnou ze čtyř původních aplikací pro běžné
úkony (viz „Co zůstává mimo“).

## 2. Pevná rozhodnutí

| # | Rozhodnutí | Důvod / poznámka |
|---|---|---|
| D1 | Android nativně: Kotlin + Jetpack Compose. minSdk 29, compileSdk/targetSdk 35. Primární telefon Xiaomi 15 (HyperOS na Androidu 15). | Wi-Fi scan, kanály a šířka pásma, soketové operace a SSH jsou závislé na nativním Android API. minSdk 29 pokryje běžné telefony kolegů a vynechá legacy větve (Wi-Fi 6 info je od API 30, řeší se runtime kontrolou). |
| D2 | KMP-ready: `:shared` (Kotlin Multiplatform, `commonMain` bez `java.*`) + `:androidApp`. V M1 se deklaruje jen `androidTarget()` a `jvm()` (pro rychlé testy). iOS targety se nedeklarují. | Na Linuxu Kotlin/Native pro Apple nejde sestavit; deklarace by přidala šum a stahování toolchainu. Disciplína „commonMain bez JVM“ se hlídá grepem, takže přidání iOS později není přepis. |
| D3 | M1 obsahuje všechny čtyři funkce. Pořadí implementace: WoL → LAN → Wi-Fi → SSH. | WoL je nejmenší, otestuje celý řetězec. SSH je nejnáročnější (terminál + bezpečnost), proto až poslední. |
| D4 | Distribuce: sideload/GitHub Releases pro správce. Později kolegové (open-source nebo vlastní web). Žádný Google Play v1. **Změněno 2026-10-27: viz D9–D12** (veřejné GitHub Releases + web; Play jako fáze 2 po stabilním vydání). | Play má přísnou politiku na polohu a Wi-Fi scan. Bez Play odpadá review. |
| D5 | Vývoj a build na build-host, headless SDK a AVD z REX Mobile. Codex builduje z CLI a testuje v emulátoru `rex-api35`. Claude revidoval, správce testuje na telefonu. | Toolchain už existuje (viz `toolchain.md`). Emulátor nepodporuje reálný Wi-Fi/LAN, proto je fyzický test povinná brána. |
| D6 | Název a složka `NetKit`, repo `Losminek/netkit` (privátní) po správcově potvrzení. Application ID výchozí `cz.netkit.app` (změna před prvním release je levná, potom ne). | Repo je zatím lokální. |
| D7 | Závislosti jen s permisivní licencí (Apache-2.0, MIT, BSD). | Chrání možnost otevřít repo. |
| D8 | Agent do fyzického telefonu nic neinstaluje: `adb` jen s `-s emulator-*`. | Stejné pravidlo jako REX Mobile. |
| D9 | Veřejná distribuce v1 = GitHub Releases (podepsané APK) + stránka projektu na webu živnosti. Repo bude veřejné pod Apache-2.0 a vznikne **čistým exportem bez historie** (ne přepisem historie stávajícího repa). | Historie a `prace/`, `prompty/` obsahují osobní identifikátory, interní hostname, privátní IP a cesty; čistý export je bezpečnější než force push. Veřejné repo s licencí odemyká IzzyOnDroid. |
| D10 | Aktualizace v appce ve dvou krocích: **v1** tlačítko „Zkontrolovat aktualizace“ jen otevře stránku Releases (`ACTION_VIEW`, bez síťového kódu a bez nových oprávnění); **v2** až poběží web, appka přečte `latest.json` (OkHttp, timeout, limit velikosti, whitelist URL, jen oznámení). **Žádná samoinstalace**, `REQUEST_INSTALL_PACKAGES` zůstává zakázané. | Nejmenší útočná plocha a privacy; samoinstalace kolidují s politikou Play a zbytečně varují uživatele. |
| D11 | Release APK podepisuje správce **lokálně** (`apksigner`), klíč je offline mimo repo. CI sestaví jen nepodepsané APK a `SHA256SUMS` do draftu releasu. `applicationId` `cz.netkit.app` se po prvním veřejném vydání nemění. Rozhodnutí o Play App Signing (vlastní klíč přes PEPK vs. klíč Googlu) se odkládá do přípravy Play. | Klíč a ID tvoří identitu aplikace, jejich změna znamená novou identitu a novou registraci u Google developer verification. |
| D12 | **Mění D4:** Google Play je fáze 2, až po stabilním GitHub release a dokončeném fyzickém testu M3. iOS se **zatím nedělá**; podmínky znovuotevření jsou v [ios.md](ios.md). | Play vyžaduje targetSdk 36, closed test s 12 testery po 14 dní a náležitosti obchodníka (viz [publikace-play.md](publikace-play.md)). iOS: Wi-Fi scan nejde, správce nemá Mac ani iPhone, SSH je největší položka. |
| D13 | (2026-10-28) Potvrzuje D9: APK ze **veřejného repa a GitHub Releases**, web na soubory jen odkazuje. | Plán prace/25-plan-zverejneni.md (interní záznam). |
| D14 | (2026-10-28) **Mění D10:** kontrola aktualizací už ve verzi 1.0.0 přes `latest.json` z webu (nejvýš 1× za 24 h, karta „Dostupná verze“, vypínač, instalace ručně přes prohlížeč). Krok v1 „jen proklik“ odpadá. Samoinstalace a `REQUEST_INSTALL_PACKAGES` dál ne. | správce chce chování jako u checker.zelina.eu; `latest.json` generuje build webu, ne dotaz aplikace na GitHub API. |
| D15 | (2026-10-28) Web živnosti na **Cloudflare (z GitHubu)**, HTML/CSS + Python; stránka NetKitu v portfoliu se stažením aktuální verze. | Build webu při vydání přečte GitHub release a vygeneruje stránku i `latest.json`. |
| D16 | (2026-10-28) První veřejná verze **1.0.0**, po fyzickém testu M3 a M4. | Čistý start pro web, IzzyOnDroid i pozdější Play. |
| D17 | (2026-10-28) Stránka a `latest.json` na `https://www.<doména>/netkit`. **2026-10-31:** `https://www.daniel.losak.cz/netkit` (`netkitWebBase`), zásady na `/netkit/privacy` jsou zdrojem pravdy. | Adresa se zapéká do aplikace; po vydání se nemění. |
| D18 | (2026-10-28) Soukromé repo se přejmenuje na `Losminek/netkit-dev`, veřejné čisté repo bude `Losminek/netkit` (provést až v F2 na výslovný pokyn). | Veřejné repo dostane přirozené jméno, historie zůstane soukromá. |
| D19 | (2026-10-28) Android developer verification: rozhodnutí odloženo, 1.0.0 může vyjít bez registrace. | Pro ČR/EU zatím není datum povinnosti. |
| D20 | (2026-10-28) Preset `rychlost.zelina.eu` zůstává ve veřejné verzi (správce ověří souhlas provozovatele). | |
| D21 | (2026-10-28) Debug build jako `cz.netkit.app.dev` vedle release `cz.netkit.app`. | Release klíč ≠ debug klíč; bez suffixu by každý přechod znamenal odinstalaci a ztrátu dat. |
| D22 | (2026-10-29) **Záloha kontroly aktualizací na GitHubu:** aplikace se ptá `<web>/latest.json`, při chybě, jiném stavu než 200 nebo neplatném obsahu `github.com/Losminek/netkit/releases/latest/download/latest.json`. Přesměrování jen na povolené hosty GitHubu (nejvýš 3 skoky). | Adresa webu je zapečená v aplikaci; změna domény nebo výpadek webu by jinak odřízly starší instalace. Cena: druhý příjemce v privacy policy. |
| D23 | (2026-10-29) **Mění D14/D15:** `latest.json` vzniká jednou při podpisu (`tools/sign_release.sh`), je assetem release a `publish.yml` ho ověří. Web (`Losminek/osobni-web`, Astro na **Cloudflare Workers**, ne Pages) ho kopíruje; build webu spouští `repository_dispatch` `netkit-release` (token `WEB_DISPATCH_TOKEN` v environmentu `release`). Stránku `/netkit` renderuje web, generátor z F5 odstraněn. | Jeden ověřený zdroj pro aplikaci i web; deploy hook Pages pro Workers neplatí. |
| D24 | (2026-10-29) **GitHub Pro:** zkušební běh release workflow na soukromém repu (draft a tag poté smazat), ruleset `main` (zákaz force push a smazání), environment `release` (tagy `v*`; povinný schvalovatel u soukromého repa na Pro nejde, HTTP 422, doplnit po zveřejnění repa), CI na PR (informativní). Ruleset `main-integrita` už existoval. | Workflow nikdy neběžela; povinné PR by rozbilo sync timer, který pushuje na `main`. Zkušební běh 2026-10-29 viz distribuce §8. |
| D25 | (2026-10-29) Před publikací **bezpečnostní revize** Codex Security (`gpt-6.1-sol`: threat model, hloubkový sken, validace) a **právní checklist** (GDPR, ePrivacy, Play, licence v binárce, CRA, identifikace podnikatele) s oficiálními zdroji. | Veřejné vydání pod jménem podnikatele; nález P1/P2 vydání blokuje. |
| D26 | (2026-10-31) **Mění D23 (spouštěč):** build webu spouští **Deploy Hook Workers Builds** (`osobni-web`, větev `main`), URL v secretu `OSOBNI_WEB_DEPLOY_HOOK` (environment `release`). `publish.yml` po ověření release hook zavolá a až 15 min čeká na `<web>/latest.json` s novou verzí; první publikovaný release hook přeskočí (web přepne `netkitReleased` vlastním PR). `repository_dispatch` a `WEB_DISPATCH_TOKEN` odstraněny, žádný Cloudflare token v GitHubu. | Web nemá deploy job ani token v GitHubu (interní záznam). Rotace = smazat hook, vytvořit nový, vyměnit secret. |
| D27 | (2026-10-31) **Výjimka z D16:** 1.0.0 vychází před fyzickým testem M3/M4; správce ho provede nad vydaným release APK a nález řeší opravná verze 1.0.x. | Rozhodnutí správce; aplikace prošla emulátorem, CI a revizemi, release podpis je potřeba ověřit stejně jen na vydaném APK. |
| D28 | (2026-10-31) **Mění D12 (iOS):** iOS se začíná stavět. SwiftUI nad `:shared` (XCFramework), MVP včetně SSH (ping, porty, DNS, mDNS, info o Wi-Fi, speedtest, WoL, SSH), Android beze změny. Vývoj ve **veřejném** `Losminek/netkit-ios` (čistý export, jen syntetická data), protože standardní macOS runnery jsou ve veřejných repech zdarma; po auditu přenos do `netkit-dev` přes PR a archivace repa. Apple účet, TestFlight a test na zařízení mimo rozsah (rozhodne správce později). Zadání `prompty/28-ios-mvp.md`. | xtool (Linux) zamítnut: nepřeloží Kotlin `:shared`, Xcode SDK licencované jen pro Apple hardware, bez simulátoru (ověřeno 2026-10-31, `docs/ios.md` §7). Veřejné repo nejde zpětně „očistit“, proto čisté od začátku. |

## 3. Rozsah M1

### V rozsahu

- **Wake-on-LAN**: uložená zařízení (název, MAC, broadcast adresa, port), odeslání magic
  packetu, volitelně opakování a kontrola probuzení pingem. Zástupce na plochu až M2.
- **LAN skener**: discovery hostů v podsíti, ping se statistikou, traceroute, TCP port scan
  (preset portů + rozsah), reverzní DNS, mDNS služby. MAC a výrobce (OUI) hostů je na Androidu
  10+ obvykle nedostupný; OUI se používá pro BSSID Wi-Fi AP a ručně zadané MAC
  ([lan-scanner.md](funkce/lan-scanner.md) §2).
- **Wi-Fi analyzér**: seznam AP (SSID, BSSID, RSSI, kanál, šířka, pásmo 2.4/5/6 GHz,
  zabezpečení, standard), graf obsazení kanálů, časový graf signálu vybrané sítě,
  doporučení nejméně zatíženého kanálu, info o aktuálním připojení (rychlost linku,
  frekvence, IP, gateway, DNS).
- **SSH klient**: profily hostů, heslo a klíč (Ed25519/RSA/ECDSA), `known_hosts` s TOFU
  a varováním při změně klíče, terminálový emulátor s funkčními klávesami (Ctrl, Esc, Tab,
  šipky), více relací.
- Společné: navigace čtyř záložek, tmavé a světlé téma, česká a anglická lokalizace
  připravená, export dat (zařízení WoL, profily SSH bez hesel) do JSON.

### Mimo rozsah M1

- Port forwarding, SFTP, jump host, SSH agent forwarding (roadmapa po M1).
- Zátěžové testy rychlosti (speedtest), IPv6 scan celé podsítě, Bluetooth, NFC.
- Služba na pozadí / periodické skenování na pozadí / notifikace.
- Cloudová synchronizace, účty, telemetrie, reklamy.
- iOS (viz [ios.md](ios.md)), widgety a zástupce na plochu před M2, vlastní ikona před M2.
- Google Play (fáze 2 podle D12, viz [publikace-play.md](publikace-play.md)).

## 4. Milníky

| Milník | Výstup | Brána přijetí |
|---|---|---|
| **M0 + M1a** | Gradle KMP kostra (`:shared`, `:androidApp`), navigace čtyř záložek, funkční Wake-on-LAN včetně perzistence a testů | AC z interní pracovní záznam, revize Claude, fyzický WoL test správce |
| **M1b** | LAN skener, ping, traceroute, port scan | AC z promptu 02, revize, fyzický test na doma LAN |
| **M1c** | Wi-Fi analyzér | AC z promptu 03, revize, fyzický test (reálný scan, throttling vypnutý) |
| **M1d** | SSH klient | AC z promptu 04, revize se zaměřením na bezpečnost, test proti reálným strojům |
| **M2** | Integrace (WoL z LAN skeneru, otevřít SSH z hostu), polish, release podpis správcovým klíčem, GitHub Release | správce potvrdí použitelnost |
| **Veřejné vydání v1** | Čistý veřejný export, LICENSE/NOTICE, podepsané APK v GitHub Release, About s odkazem na Releases, privacy policy, stránka na webu živnosti | Fyzický test M3 hotový, kontroly z [distribuce.md](distribuce.md) §8, správce podepíše a zveřejní |
| Google Play (fáze 2) | targetSdk 36, flavory, AAB, closed test, produkce | po veřejném vydání v1 a výslovném pokynu, viz `publikace-play.md` |
| iOS spike | Zatím se nedělá (D12), znovuotevření podle `ios.md` | viz `ios.md` |

Prompty 02–05 se píšou až po revizi předchozího řezu. Zadání vychází ze skutečného stavu kódu
a z poznatků fyzického testu.

## 5. Rizika a otevřené body

| Riziko | Dopad | Opatření |
|---|---|---|
| Android škrtí Wi-Fi scan (4 / 2 min pro foreground app, Android 9+) | WifiMan-like živý graf se neobnovuje | Dokumentovat, v UI hlásit stáří dat; návod na vypnutí „Wi-Fi scan throttling“ ve Vývojářských možnostech; ověřit chování na HyperOS na telefonu |
| Android 10+ blokuje `/proc/net/arp` a neumí raw ICMP bez rootu | Discovery je pomalejší a méně přesné než Network Analyzer | Kombinace TCP probe na běžné porty + ICMP přes `InetAddress.isReachable`/`ping` binárku + mDNS; v dokumentaci přiznat limity; MAC zjistit jen tam, kde je k dispozici |
| Emulátor nemá reálný Wi-Fi ani LAN | Část funkcí nejde ověřit mimo telefon | `testovani.md` rozděluje, co dokáže emulátor/JVM a co správce; fyzický checklist je součástí každého řezu |
| SSH knihovna a terminálová komponenta: licence a údržba | Zdržení nebo nutnost vlastní implementace | Rešerše v M0/M1d s ověřením licence a poslední aktivity; fallback: Apache MINA SSHD nebo sshj + vlastní jednoduchý emulátor |
| Skenování cizích sítí | Právní a etické riziko | Varování v UI, scan defaultně jen vlastní podsíť telefonu, bez brute force |
| Udržitelnost KMP | iOS později může být nákladnější, než se zdá | Hlídat disciplínu commonMain; iOS spike samostatně po M2 |
| Výrobcovské chování HyperOS (úsporný režim, background limit) | Dlouhé skeny mohou být zabity | Skeny jen na popředí, stav v ViewModelu; měřit na telefonu |

## 6. Co zůstává mimo (až do rozhodnutí)

Pokud po M1 něco z původních aplikací chybí (např. rozšířené statistiky signálu, export do CSV,
widget), zapíše se to do `README.md` jako požadavek M2+ a správce rozhodne.
