# NetKit — architektura

**Stav 2026-10-26:** popis pracovní implementace oprav M1 na větvi
`codex/m1-opravy`. Napsaný kód není přijetí M1. Buildy, skutečně provedené testy,
známé nedostatky a vazby na SHA APK eviduje report 08 (interní záznam).
Čeká nová nezávislá revize a fyzický test správce.

## 1. Moduly

- `:shared`: Kotlin Multiplatform s cíli `androidTarget()` a `jvm()`. Od iOS P0 se na macOS
  nebo s `-Pnetkit.ios=true` přidávají `iosArm64()` a `iosSimulatorArm64()`. Společné modely,
  čistá logika a WoL controller jsou v `src/commonMain`; testy v `src/commonTest`.
- `:androidApp`: Compose UI, Android ViewModely, síťové implementace, úložiště,
  JVM testy v `src/test` a emulátorová instrumentace v `src/androidTest`.

Balíčky jsou `cz.netkit.shared.<oblast>` a `cz.netkit.app.<oblast>`, ID aplikace
`cz.netkit.app`. iOS kostra používá SwiftUI a statický framework `NetKitShared`; stav ověření
je v [ios.md](ios.md#8-implementace-swiftui--p0-2026-10-31). Platformní
implementace jsou nyní v `androidApp`, samostatný `shared/src/androidMain` se nepoužívá.

## 2. Rozdělení odpovědností

| Oblast | Společná logika v `shared/commonMain` | Android implementace v `androidApp` |
|---|---|---|
| WoL | MAC/IPv4 a magic packet, validace, modely schématu, přísný import/merge, polling `WakeProbePolicy`, `WolController`, `NetworkInfoProvider`, `PacketSender`, `WakeProber`, `WolDeviceStore` | `WolViewModel`, DataStore, vázaný UDP/DNS, `AndroidWakeProber`, SAF a `BoundedStreamIo` |
| LAN | IP/CIDR/porty, důvody potvrzení, připnutí cíle, omezení souběžnosti, výsledky sond, sanitizace a export | `LanViewModel`, `LanBackend`, `TcpProbe`, `WifiIcmp`, NSD a systémový proces pro traceroute |
| Wi-Fi | modely AP, deduplikace, bezpečnost, frekvence/kanály, stáří dat, historie, skóre a kandidáti kanálů | `WifiViewModel`, `WifiManager`/síťové callbacky, oprávnění a `WifiScreen` |
| SSH | profily, politika algoritmů a host key, `SecretStore`/`SshSession`, `TerminalBuffer`, `TerminalComposition`, vstupní sekvence, fronta zápisu a politika pozadí | `SshViewModel`, `JschConnection`, `SshDns`, Keystore/úložiště, přímý IME vstup a Compose terminál |
| Společné UI | lokalizované texty do domény nepatří | `ui/Components.kt`, téma, navigace v `MainActivity`, CS/EN resources |

`commonMain` nepoužívá `java.*`, `javax.*` ani `android.*`. Konkrétní `Network`
ani Android `Context` nepřecházejí do společné domény; WoL používá neprůhledný
`WifiNetworkHandle`. Systémová síťová rozhraní a sokety zůstávají v Android vrstvě.

**Odchylka od původního návrhu:** společný controller existuje pro WoL. LAN,
Wi-Fi a SSH řídí Android ViewModely se `StateFlow`; port na iOS vyžaduje přesun
nebo novou implementaci jejich orchestrace a životního cyklu. KMP zde neznamená
hotový multiplatformní backend. OUI databáze není součástí domény ani aplikace.

## 3. Síťové cesty

- LAN TCP, DNS a ICMP datagram socket se vážou na vybranou ne-VPN Wi-Fi.
  Discovery rozlišuje `OPEN`, `REFUSED`, `TIMEOUT`, `ERROR`; TCP RST je doklad
  odpovídajícího endpointu. Změna sítě ruší připnutý požadavek.
- Traceroute používá systémový proces s rozhraním; není vázaný na Android `Network`.
  Jiná výchozí síť vyžaduje samostatné potvrzení a výsledek nese upozornění, že provoz
  mohl jít mimo Wi-Fi. Detaily a zdrojová evidence ICMP jsou v [LAN](funkce/lan-scanner.md#2-síťová-cesta-a-omezení-androidu).
- WoL vybere Wi-Fi pro celou operaci: DNS, opakované UDP pakety i následná TCP/ICMP
  kontrola používají stejnou síť. Bez dostupné Wi-Fi operace selže, nemá mobilní fallback.
- SSH používá běžnou síťovou trasu Androidu včetně VPN. Každá z nejvýše čtyř relací
  má vlastní JSch transport, identity a terminál. Ztráta výchozí sítě se projeví
  podezřením na ztrátu spojení; UI nabízí opětovné připojení.
- mDNS používá síťově vázané NSD na API 34+. API 29–33 je mimo rozsah;
  aplikace nepoužívá náhradní discovery na všech sítích.

OEM, VPN a chování fyzického rádia se nepovažují za ověřené jen na základě zdrojů
nebo emulátoru. Síťové politiky a oprávnění popisuje [bezpecnost.md](bezpecnost.md).

## 4. Souběžnost a životní cyklus

WoL controller dostává scope a platformní rozhraní zvenku; jeho deterministické
části se testují fake clockem a fake transportem. LAN sondy mají omezenou
souběžnost, v Android backendu nejvýše 64 blokujících TCP workerů. Výkonnost /24
je měření, nikoli vlastnost zaručená architekturou; konkrétní vzorek patří do reportu.

`MainActivity.onStop` mimo změnu konfigurace ruší WoL, LAN a aktivní Wi-Fi práci.
Přepnutí hlavní záložky ruší aktivní LAN/Wi-Fi práci. Rotace zachovává ViewModely.
Wi-Fi UI odebírá stav podle lifecycle a časový tick běží pouze při `STARTED`.

SSH při `onStop` přechází do pětiminutové ochranné lhůty
(`SshBackgroundPolicy.BACKGROUND_GRACE_MILLIS`). Samotné otevření pickeru či
správce hesel relaci ani dialog okamžitě nezamítá. Návrat kontroluje vypršení lhůty;
po limitu se relace zavřou. Zavření nastává také explicitně, při ukončení aktivity
nebo `onCleared`. Foreground service není implementovaná; systém může proces ukončit.
Samostatné timeouty připojení a autentizačních výzev dál platí, viz [SSH](funkce/ssh.md).

Terminál serializuje odesílání a slučuje resize s přibližně 100ms prodlevou.
Historie používá kompaktní řádky s limitem 8 MiB účtovaných dat na relaci a limitem
počtu řádků; nejde o tvrzení o celkovém heapu JVM. Primární resize přesouvá řádky
mezi obrazovkou a historií, alternativní obrazovka historii nepoužívá.

## 5. Perzistence a import/export

WoL zařízení ukládá JSON DataStore se schématem 2. Čtení schématu 1 zachová stará
pole a doplní nastavení opakování a kontroly; další zápis přejde na schéma 2.
Pořadí seznamu představuje uživatelské pořadí. Import přes SAF validuje celý soubor
včetně limitu 1 MiB a jedinečných ID; náhled přidaných/přepsaných zařízení musí
uživatel potvrdit před atomickým uložením. Odeslání a odpověď zařízení jsou rozdílné stavy.

SSH metadata, profily, reference na tajemství a důvěryhodné host keys jsou v
`files/datastore/ssh.json` se schématem 1. Hesla ani privátní klíče v JSON nejsou.
Chyba čtení úložiště nezpůsobí tichý reset. WoL i SSH nabízejí potvrzenou obnovu
s archivací poškozeného souboru; chyba I/O se nepovažuje za souhlas k vymazání dat.

LAN export podporuje text/CSV/JSON, snapshot výsledku i za běhu pingu a limit
8 MiB pro SAF. WoL SAF používá omezené streamové I/O s timeoutem a zavřením při
zrušení. Neúspěšné exporty se pokoušejí odstranit neúplný dokument; cizí provider
může úklid odmítnout. Wi-Fi seznam a historie ani výpis terminálu nejsou dlouhodobá databáze.

## 6. Tajemství a oprávnění

`AndroidSecretStore` ukládá zašifrovaná data do `noBackupFilesDir/ssh-secrets`;
AES-GCM klíč spravuje Android Keystore. Existující, ale nepoužitelný alias se
nenahrazuje novým klíčem. Export SSH profilů nezahrnuje tajemství ani jejich reference.
Silné stránky a meze ochrany, algoritmy SSH i schválená výjimka ISC pro přibalený
jBCrypt jsou popsány v [bezpecnost.md](bezpecnost.md) a [zavislosti.md](zavislosti.md).

Manifest deklaruje INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE,
CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION a ACCESS_COARSE_LOCATION. Wi-Fi a location
hardware mají `required=false`. Interní signature oprávnění AndroidX zůstává;
jeho přítomnost není veřejné síťové oprávnění. Scan stále vyžaduje přesnou polohu
podle použitých Android API; `NEARBY_WIFI_DEVICES` ji zde nenahrazuje, viz
[Wi-Fi a zdroje](funkce/wifi-analyzer.md). Žádné nové oprávnění nelze přidat bez odůvodnění.

## 7. Navigace a UI

Čtyři záložky používají Material 3 a společné komponenty `ScreenHeader`,
`SectionCard`, `InfoNote`, `StatusBanner`, `EmptyState`, `LabeledValue` a škálu
rozestupů 4/8/12/16/24 dp. `MainActivity` drží vybranou záložku a
`SaveableStateHolder`; podobrazovky jsou stav Compose, nikoli samostatné navigační
zásobníky. Formuláře a filtry používají ViewModel nebo `rememberSaveable`, tajemství
se do saveable stavu neukládají. Texty jsou v resources (výchozí CS a EN).

SSH má vlastní `DirectTerminalInput`/`InputConnection` pro přímé odesílání znaků
bez lokálního echo a záložní vstup pro vložení. Podporované VT režimy a klávesy
vyjmenovává [SSH](funkce/ssh.md); plný xterm, široké znaky a Unicode combining
nejsou v rozsahu. Ochrana snímků má přepínač, výchozí zapnuto v release a vypnuto
v debug. Screenshoty, IME, rotace, 200% písmo a dostupnost se ověřují na konkrétním APK;
samotné použití komponent tento test nenahrazuje.

## 8. Testovací infrastruktura a meze

`tools/run_m1_checks.py` váže běh na identitu nainstalovaných APK, git SHA a serial.
`tools/m1_provision.py` obsluhuje izolovanou fixture; `tools/testsrv/m1_fixture.py`
a WoL listener jsou lokální testovací cíle. Testy fixture a runnerů jsou součástí repa.
Výchozí testovací APK obsahuje `M1Instrumentation`; historickou WoL sadu vybírá
Gradle parametr `netkitTestSuite=wol`. Historické otevřené IME E2E se nezaměňuje
s novým důkazem. Postupy a stav jsou v [testování](testovani.md) a reportu 08.

Projekt nemá server, analytiku, reklamy, root požadavek, nativní raw sockety,
externí pluginy ani dynamické načítání kódu. Mimo M1 zůstává OUI databáze,
integrace mezi záložkami, SFTP/forwarding/jump host, foreground service, widget,
release podpis a iOS targety. Vydání a přijetí potvrzuje správce až po revizi a telefonu.


Uzavírací revize07 odděluje fázi odesílání WoL od zrušitelné kontroly odpovědi;
změny záznamu zneplatňují starou kontrolu. Wi-Fi čerstvost používá čas přijetí
celé datové sady (varování30s, doporučení5min), vlastní AP lze výslovně vyloučit.
Graf vykreslí nejvýše6 sérií s barvou/tvarem, skóre dál počítá všechny vhodné AP.
SSH composition je čistá testovaná třída; vstup není plnohodnotný textový editor.
Reconnect zachovává původní tab a výpis do navázání nové relace a znovu potvrzuje
startovací příkaz. Stav pending promptu je vidět i mimo SSH záložku.

Runner hlídá oba očekávané SHA APK. Provisioner křížově ověřuje autentizaci,
příjem bajtů a provedení pevného příkazu ve fixture; po neúspěchu sérii zastaví.
Hostové dočasné údaje uklízí i při nedostupném emulátoru. Detailní meze důkazů
(včetně rozdílu input key eventů a skutečné composition IME) zůstávají v reportu.
