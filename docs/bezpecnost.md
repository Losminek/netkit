# NetKit — bezpečnost a oprávnění

**Stav 2026-10-25:** výchozí model. Každá změna oprávnění nebo způsobu ukládání tajemství se
zapisuje sem (se zdrojem a datem). Aplikace nemá server, účty ani telemetrii.

## 1. Model hrozeb (stručně)

| Hrozba | Dopad | Opatření |
|---|---|---|
| Ztráta/odcizení telefonu | únik SSH klíčů a hesel | klíče a hesla jen v šifrovaném úložišti vázaném na Keystore; zamčený telefon; biometrika pro klíč v M2 |
| Škodlivá aplikace na telefonu | čtení dat NetKitu | `allowBackup=false`, data v soukromém úložišti aplikace, žádné exportované komponenty bez důvodu, žádné světově čitelné soubory |
| MITM při SSH | únik přihlášení | ověření host key (TOFU, tvrdé varování při změně), moderní algoritmy |
| Zneužití skeneru | právní a etické riziko | výchozí jen vlastní podsíť, potvrzení pro cizí rozsahy, žádný brute force/exploit/flood |
| Únik při ladění | tajemství v logu | žádné logování hesel, klíčů, příkazů a obsahu terminálu; release bez ladicích logů |
| Kompromitovaná závislost | zadní vrátka | permisivní licence, malé množství závislostí, verifikace závislostí Gradle, review změn lockfile/verification metadat |
| Únik podpisového klíče | zfalšované APK | klíč vlastní správce mimo repo; v repu žádný `.jks`; debug a release podpis oddělený |

## 2. Oprávnění podle funkce

Přidává se až s funkcí, která ji potřebuje. Sloupec „Stav“ je skutečný stav manifestu;
vyplňuje implementátor.

| Oprávnění | Funkce | Proč | Stav |
|---|---|---|---|
| `INTERNET` | WoL, LAN, SSH, rychlostní test, Přehled | UDP magic packet, síťové sondy, DNS, SSH transport; M2: HTTPS/WSS rychlostního testu; M4: veřejná IP (HTTPS na `1.1.1.1`) a poskytovatel (DNS TXT); F3: kontrola aktualizací (HTTPS GET `latest.json` z webu projektu), žádné nové oprávnění | M1: přidáno |
| `ACCESS_WIFI_STATE` | Wi-Fi analyzér | stav a výsledky `WifiManager`; WoL samotný jej nepotřebuje | M1c: přidáno |
| `ACCESS_NETWORK_STATE` | WoL, LAN, Wi-Fi | výběr `Network` a `LinkProperties`, shodná síť pro DNS i soket | M1: přidáno |
| `cz.netkit.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | interní AndroidX ochrana | vlastní `signature` oprávnění pro neexportované dynamické receivery na API 29–32 | M1a: vráceno po schválení správce v revizi 02 |
| `CHANGE_WIFI_STATE` | Wi-Fi | uživatelem vyžádané `startScan` | M1c: přidáno |
| `ACCESS_FINE_LOCATION` | Wi-Fi | `startScan` a výsledky skenu vyžadují přesnou polohu i na nových API; runtime žádost až po akci uživatele | M1c: přidáno |
| `ACCESS_COARSE_LOCATION` | Wi-Fi | na Androidu 12+ se žádá společně s FINE; pouze přibližná poloha scan nepovolí | M1c: přidáno |
| `NEARBY_WIFI_DEVICES` | Wi-Fi | použité scan API potřebuje FINE, toto oprávnění jej nenahrazuje | nepřidáno |
| `CHANGE_WIFI_MULTICAST_STATE` | LAN | jen pro `WifiManager.MulticastLock` (příjem multicastu). Identifikace zařízení (M2) posílá mDNS a SSDP dotazy z efemérního portu a odpovědi přijímá unicastem (mDNS legacy unicast, RFC 6762 §6.7; SSDP odpověď na M-SEARCH), takže zámek nepotřebuje; nástroj Služby používá `NsdManager` (API 34+) | nepřidáno (2026-10-26 ověřeno pro identifikaci) |
| `READ_PHONE_STATE` | Přehled: mobilní síť | obě SIM (`getActiveSubscriptionInfoList`), typ sítě (`getDataNetworkType`, `getVoiceNetworkType`) a změna datové SIM; runtime dialog až z karty mobilní sítě (rozhodnutí O-4), viz § Mobilní síť | M4: přidáno |
| `READ_BASIC_PHONE_STATE` | Přehled: mobilní síť | API 33+, `normal` bez dialogu: jen `getDataNetworkType`/`getVoiceNetworkType` u datové SIM bez `READ_PHONE_STATE` | M4: přidáno |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_*` | SSH relace na pozadí | **není v M1**; samostatné rozhodnutí v M2 | — |

Nikdy nepřidávat (`READ_PHONE_STATE` je od M4 výjimka podle rozhodnutí O-4, viz § Mobilní síť): `READ_PHONE_NUMBERS`, `READ_PRIVILEGED_PHONE_STATE`, `READ_CONTACTS`, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`,
`ACCESS_BACKGROUND_LOCATION`, `REQUEST_INSTALL_PACKAGES`, `RECORD_AUDIO`, kamera, SMS.

Ověření API 2026-10-25: [`ConnectivityManager`](https://developer.android.com/reference/android/net/ConnectivityManager)
vyžaduje `ACCESS_NETWORK_STATE` pro `getNetworkCapabilities`, `getLinkProperties` i výčet sítí.
WoL nečte SSID/BSSID ani výsledky skenování a nepotřebuje oprávnění k poloze.
[`Network.bindSocket(DatagramSocket)`](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket))
váže nepřipojený soket na vybranou síť. Snapshot IP/prefixu i soket používají tutéž
`Network`; DNS vlastního hostname rovněž běží přes ni. Chybějící nebo ztracená Wi-Fi
znamená chybu, bez fallbacku na mobilní síť. Timeout zahrnuje DNS i odeslání, zrušení
ruší DNS požadavek a uzavírá soket. Test emulátoru neprokazuje směrování na fyzickém telefonu.

## 3. Konfigurace aplikace

- `android:allowBackup="false"`, `android:dataExtractionRules` vylučuje všechna data;
  `fullBackupContent` neposkytuje nic.
- `android:usesCleartextTraffic="false"` a `networkSecurityConfig` bez výjimek. (SSH, WoL, ping
  a TCP sondy nejsou HTTP a tuto politiku neovlivňují. Rychlostní test od M2 používá výhradně
  HTTPS/WSS, viz § Rychlostní test.) Jediné nešifrované HTTP, které aplikace sama dělá,
  je stažení UPnP popisu zařízení — viz výjimka níže; konfigurace se kvůli ní **nemění**.

### Výjimka: UPnP popis zařízení přes ruční HTTP/1.0 (M2, 2026-10-26)

UPnP popis (`LOCATION` z odpovědi SSDP) existuje jen jako nešifrované `http://` na zařízení
v LAN; TLS tam není k dispozici. Místo povolení cleartextu v `networkSecurityConfig` (to by
platilo pro celou aplikaci a každou HTTP knihovnu) jde jediný požadavek ručně psaným
`GET … HTTP/1.0` přes holý `Socket` z `Network.socketFactory` vybrané Wi-Fi. Politika
`cleartextTrafficPermitted="false"` tak dál chrání veškerý ostatní provoz aplikace; tento
socket ji záměrně obchází a je proto omezený takto (kód `UpnpProbe.describe`,
pravidla `upnpDescriptionTarget` / `parseHttpResponseBody` / `parseUpnpDescription` v commonMain):

- jen po dokončeném hledání zařízení v rozsahu `lanDevicesRange` (vlastní podsíť ≤ /24)
  a jen pro hosta, který na hledání odpověděl i poslal odpověď SSDP;
- URL musí být `http`, host literál IPv4 **shodný s odesílatelem** SSDP odpovědi a ve vlastní
  podsíti, bez user-info; DNS se neprovádí, přesměrování se nesleduje, jen status 200;
- connect + odeslání + čtení celkem 2 s, odpověď nejvýše 64 KiB, `Connection: close`
  (bez keep-alive), čtení končí s úplnou odpovědí (Content-Length / poslední chunk),
  nedokončená odpověď po 2 s se zahodí, nejvýše 8 souběžně; žádné cookies, hlavičky s údaji uživatele ani tělo
  požadavku;
- XML bez DTD a entit (deklarace `<!DOCTYPE`/`<!ENTITY` dokument odmítne), jen pět
  předdefinovaných a číselné entity, limit hloubky a počtu tagů, hodnoty sanitizované
  (`sanitizeLanText`, 128 znaků); nic se neukládá na disk ani neloguje;
- `presentationURL` se nikdy neotevírá automaticky ani nezkracuje (nad 512 znaků se zahodí);
  tlačítko „Otevřít v prohlížeči“ (ACTION_VIEW, request pak dělá prohlížeč) je jen pro
  `http(s)` s literálem IPv4 ve vlastní podsíti nebo cestu na adrese popisu.

Riziko, které zůstává: obsah popisu je nedůvěryhodný a nešifrovaný (kdokoli v LAN ho může
podvrhnout); zobrazuje se jen jako text. StrictMode `detectCleartextNetwork` by tento socket
nahlásil — v aplikaci zapnutý není.
- Žádná `exported="true"` komponenta kromě launcher aktivity; nové exportované komponenty
  se zdůvodňují zde.
- Release build: `isMinifyEnabled` a `shrinkResources` zvážit (R8), `debuggable=false`.
- `FLAG_SECURE` na terminálu volitelně (viz [ssh.md](funkce/ssh.md)).

M1a odstraňuje ze sloučeného manifestu nepoužívaný exportovaný
`androidx.profileinstaller.ProfileInstallReceiver`. Interní AndroidX startup provider
zůstává neexportovaný. AndroidX Core deklaruje a používá vlastní
`cz.netkit.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` s úrovní `signature`; jeho
původní odstranění je po revizi 02 a výslovném schválení správce zrušeno. Nejde o
systémové runtime oprávnění ani oprávnění k další funkci telefonu. Chrání kompatibilní
registraci neexportovaných receiverů přes `ContextCompat` na API 29–32.

**Historické AC-007 pro M1a [revidováno, schváleno správcem 2026-10-25]:** sloučený manifest obsahuje
`uses-permission` právě pro `android.permission.INTERNET`,
`android.permission.ACCESS_NETWORK_STATE` a vlastní
`cz.netkit.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Obsahuje právě jednu deklaraci
`permission` stejného vlastního názvu s `protectionLevel="signature"`, žádné další.
`allowBackup="false"`, pravidla extrakce vylučují vše, `usesCleartextTraffic="false"`;
jediná komponenta s `exported="true"` je `MainActivity`; `ProfileInstallReceiver`
zůstává odstraněn. Rozšíření M1b–d přidává výše uvedená Wi-Fi oprávnění na výslovný pokyn implementovat všechny funkce; původní množina M1a se proto na nový APK nevztahuje. Ověření: přesné množiny uses-permission/permission a atributy
sloučeného manifestu, příkaz a výstup v navazujícím reportu.

### Schválená politika VPN

správce v revizi 02 výslovně schválil zachování podkladové Wi-Fi: **WoL pakety i DNS
vlastního cíle jdou mimo VPN**, ve všech režimech. UI na to upozorňuje. Název cíle může
vidět DNS resolver Wi-Fi; interní jména dostupná jen přes VPN DNS nemusí fungovat.
Aplikace při chybě nepřepíná na mobilní síť ani na VPN. VPN s blokováním provozu mimo
tunel může odeslání odmítnout; aplikace takovou politiku neobchází. Volba odesílání přes
VPN pro CUSTOM je případný samostatný řez, nyní se nepřidává.

WoL posílá 1–10 stejných 102bajtových paketů (výchozí3 po250ms) na uživatelem nakonfigurovaný cíl. Hláška o odeslání
nepotvrzuje probuzení zařízení. Pro prefix `/31` nebo `/32` se automatický directed broadcast
odmítne; aplikace sama nepřepne na limited broadcast. Ruční cíl a limited broadcast jsou
výslovné volby uživatele. Kontrola probuzení, opakování a SAF import/export jsou doplněny podle D-4; DNS i TCP/ICMP kontrola používají tutéž Wi-Fi Network.

## 4. Tajemství a úložiště

- SSH hesla a privátní klíče: `SecretStore` (AES-GCM, klíč v Android Keystore, preferovat
  StrongBox, jinak TEE; zobrazit v nastavení, jakou úroveň zařízení dalo). Soubory v
  `noBackupFilesDir`. Klíč v Keystore nesmí být exportovatelný.
- Nešifrované: WoL zařízení, profily bez hesel, nastavení, otisky host key. Jsou to osobní
  údaje sítě; zůstávají v soukromém úložišti aplikace.
- Export dat: JSON bez tajemství, jen na akci uživatele a s varováním, kam se soubor uloží.
- Import privátního klíče: kopírování z vybraného souboru do `SecretStore`; původní soubor
  uživatel maže sám (aplikace jej nemaže).


### Implementace SSH úložiště a důvěry (M1d, 2026-10-25)

`AndroidSecretStore` používá AES-256-GCM s novým náhodným 12bajtovým nonce pro každý
zápis. Keystore alias je `netkit.ssh.aes.v1`. Soubor obsahuje verzi formátu, nonce a
ciphertext s autentizačním tagem; identifikátor položky je AAD. Přesunutí ciphertextu
pod jiné ID nebo změna obsahu proto nesmí projít dešifrováním. Zápisy přes `AtomicFile`
a procesový mutex chrání dokončení zápisu a souběžné vytvoření aliasu. Keystore klíč
se neexportuje; po jeho ztrátě není plaintext recovery ani tichý fallback.

StrongBox se zkusí, pokud zařízení hlásí jeho dostupnost; `StrongBoxUnavailableException`
vede k běžnému Keystore. UI klíčů ukazuje skutečně zjištěnou úroveň StrongBox/hardware/software,
ne pouze požadovanou variantu. Software fallback má nižší ochranu při kompromitaci OS.
Biometrické odemykání ani požadavek uživatelské autentizace Keystore nejsou v tomto řezu.
Hesla/passphrase žijí dočasně v paměti formuláře; nejsou v `savedInstanceState`. Pracovní
byte arrays se tam, kde je aplikace vlastní, nulují, ale nejde o záruku vymazání všech
kopií v JVM, Compose a SSH knihovně.

Import čte pouze explicitně vybraný soubor (limit 256 KiB), dešifruje jej s passphrase
v paměti a uloží normalizovaný privátní OpenSSH klíč pouze šifrovaně přes SecretStore.
Původní importovaný soubor se nemaže. DataStore obsahuje veřejná metadata a reference,
nikoli hesla ani privátní klíče. Export profilů vynechává i reference, serverovou důvěru
a metadata uživatelských klíčů. Názvy, adresy a startovací příkazy jsou nešifrovaná
metadata profilu; do startovacího příkazu nepatří tajemství.

Před autentizací se server ověří přes `StrictHostKeyChecking=yes` a vlastní repository.
Neznámý server vyžaduje kontrolu SHA256 otisku uživatelem, pak atomické compare-and-insert
uložení pro přesný endpoint. Změněný veřejný klíč se vždy odmítne, i kdyby callback
vrátil souhlas. Reset důvěry je samostatně potvrzená akce v přehledu ověřených serverů
se zavřením relací a varováním před MITM. Autentizační výzvy obsahují lokálně známý
profil a `uživatel@endpoint`; jméno/výzvy dodané serverem jsou oddělené a nepředstavují
důvěryhodnou identitu. Každá výzva má vlastní náhodné request ID, nezávislé na session ID.
Odpověď UI prochází atomickou bránou přiřazenou tomuto ID: opožděné potvrzení či heslo
z prošlého dialogu nesmí dokončit další výzvu jiné relace ani další kolo téže relace.
JSch logování není zapnuté a výjimky se nevypisují do UI ani logu.

SSH používá systémovou výchozí trasu včetně VPN. DNS běží přes `DnsResolver` s `network=null`,
CancellationSignal a časovým limitem; numerické adresy se parsují bez DNS. Toto chování
se liší od výslovně schválené přímé Wi-Fi cesty WoL. Ověření na emulátoru neprokazuje
VPN směrování na fyzickém telefonu.

Oficiální Android zdroje ověřené 2026-10-25:
[Android Keystore](https://developer.android.com/privacy-and-security/keystore),
[StrongBox nastavení](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setIsStrongBoxBacked(boolean)),
[DnsResolver](https://developer.android.com/reference/android/net/DnsResolver),
[InetAddresses](https://developer.android.com/reference/android/net/InetAddresses).

## 5. Politika SSH algoritmů

Implementace M1d používá uzavřené allowlisty `shared/.../ssh/SshModels.kt` (`SshAlgorithms`).
Knihovna nesmí nabídnout další výchozí algoritmus ani při chybě kompatibility serveru:

| Kategorie | Povolené hodnoty, v pořadí preference |
|---|---|
| KEX | `curve25519-sha256`, `curve25519-sha256@libssh.org`, `ecdh-sha2-nistp256`, `ecdh-sha2-nistp384`, `ecdh-sha2-nistp521`, `diffie-hellman-group16-sha512`, `diffie-hellman-group14-sha256` |
| Host key a autentizační signatury | `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521`, `rsa-sha2-512`, `rsa-sha2-256` |
| Šifry oběma směry | `aes256-gcm@openssh.com`, `aes128-gcm@openssh.com`, `chacha20-poly1305@openssh.com`, `aes256-ctr`, `aes128-ctr` |
| MAC oběma směry | `hmac-sha2-512-etm@openssh.com`, `hmac-sha2-256-etm@openssh.com`, `hmac-sha2-512`, `hmac-sha2-256` |
| Komprese | `none` |

`ssh-rsa` jako SHA-1 podpis, DSA, CBC, RC4, 3DES a KEX s SHA-1 nejsou povolené.
RSA veřejný klíč stále používá formát `ssh-rsa`; podpis při spojení musí být RSA/SHA-2.
Generované i importované uživatelské RSA klíče mají nejméně 3072 bitů. JSch strict KEX
nabízí (`enable_strict_kex=yes`), ale nevynucuje podporu protistrany
(`require_strict_kex` není zapnutý). AES-GCM má přednost před chacha20; nejde o
záruku odstranění všech rizik při komunikaci se starým serverem. Nejde o příslib podpory všech
starších síťových zařízení: nekompatibilní server skončí chybou, bez slabého fallbacku.

Ověřené zdroje 2026-10-25: [JSch README](https://github.com/mwiede/jsch/tree/jsch-2.28.0),
[konfigurace JSch](https://github.com/mwiede/jsch/blob/jsch-2.28.0/src/main/java/com/jcraft/jsch/JSch.java),
[RSA/SHA-2 v SSH, RFC 8332](https://www.rfc-editor.org/rfc/rfc8332).
BC lightweight implementace Ed25519/XDH jsou zvolené explicitně; neprobíhá výměna
systémového Android cryptographic provideru. Licence a přesné souřadnice viz
[závislosti](zavislosti.md).

## 6. Skenování sítě — etika a právo

- Skenování cizích sítí bez povolení je v mnoha jurisdikcích trestné nebo porušuje podmínky
  poskytovatele. UI to připomíná při prvním spuštění skeneru a při volbě rozsahu mimo podsíť telefonu.
- Aplikace nedává funkce pro: brute force přihlášení, zjišťování zranitelností, DoS, deauth,
  ARP spoofing, zachytávání cizího provozu.
- **Automatické hledání zařízení (M2)** běží jen po souhlasu uživatele pro konkrétní síť
  („Je to vaše síť?“), jen ve vlastní podsíti Wi-Fi zúžené na nejvýše /24 a nejvýše jednou za
  5 minut. Souhlas je uložen pod HMAC-SHA256 klíčem (SSID, brána, podsíť) s náhodným klíčem
  instalace v soukromých `SharedPreferences` bez zálohy; SSID se v čitelné podobě neukládá
  ani neloguje. Bez známého SSID se souhlas neukládá (brána + podsíť nejsou jednoznačné),
  odmítnutí ano. Ruční hledání zůstává vždy dostupné ve stejném rozsahu.
- NetBIOS NBSTAT dotazy (UDP 137, jeden 50B datagram na živý host, timeout 800 ms,
  nejvýše 8 souběžně) používají socket vázaný na vybranou Wi-Fi; odpověď parsuje striktně
  ohraničený parser a jména se sanitizují. **Žádné nové oprávnění**: stačí stávající
  `INTERNET` a `ACCESS_NETWORK_STATE`; SSID se čte jen, pokud už bylo uděleno oprávnění
  k poloze pro Wi-Fi analyzér (Devices o ně samo nežádá). Podrobnosti:
  [lan-scanner.md §6](funkce/lan-scanner.md).
- **Identifikace mDNS a SSDP/UPnP (M2)** navazuje jen na dokončené hledání (automatické se
  souhlasem, nebo ruční) a jen na živé hosty v tomtéž rozsahu; ruší se s hledáním, při
  odchodu z obrazovky, změně Wi-Fi a odvolání souhlasu. Za jedno hledání: mDNS ≈ 2,5 s,
  dvakrát (počet hostů / 32 + 1) datagramů na 224.0.0.251:5353 s TTL 255 (link-local, router
  nepředává); SSDP 2× `M-SEARCH upnp:rootdevice` na 239.255.255.250:1900 s TTL 1, okno 3 s;
  pak nejvýše 8 souběžných HTTP GET popisu (výjimka v §3). Sockety vázané na vybranou Wi-Fi
  (`Network.bindSocket` / `Network.socketFactory`, `IP_MULTICAST_IF` = Wi-Fi), nikdy mobilní
  data. Přijímají se jen odpovědi ze zdrojových adres ve vlastní podsíti a ohraničené
  velikostí (mDNS ≤ 9000 B, SSDP ≤ 4096 B, ≤ 256 datagramů). **Žádné nové oprávnění**
  (bez MulticastLock).

## 7. Co kontrolovat při revizi

Viz [revize.md](revize.md). Zvlášť: nové oprávnění, nové `exported`, logování v kódu SSH,
nové závislosti, nový způsob ukládání a skutečná síťová cesta: WoL a Wi-Fi vázané
nástroje nesmějí tiše přejít na mobilní síť, SSH naopak používá výchozí trasu Androidu
včetně VPN nebo mobilního připojení. Žádný `adb` příkaz v reportu nesmí mířit na fyzický telefon.

### Obnova úložiště po revizi F2

Chyba IO, poškozený obsah a nepodporované schéma mají odlišné hlášky. IO nabízí retry.
U poškození/novějšího schématu lze po samostatném potvrzení původní soubor atomicky
přesunout do unikátního soukromého adresáře `datastore/wol-archive-*` a začít s prázdným
seznamem. Původní bytes se nepřepisují; aplikace ukáže cestu k archivu i po restartu (nejnovější nalezený neprázdný archiv). Chyba archivace
reset neprovede a prázdný adresář po neúspěšném přesunu uklidí. Platný mezitím opravený soubor se neresetuje. Archiv zůstává v sandboxu
aplikace a pod stejným zákazem záloh; nejde o obecnou funkci exportu/importu. U nepodporovaného schématu aplikace nejprve
doporučí aktualizaci; archivace s prázdným seznamem zůstává až sekundární potvrzenou volbou.

## M1b–M1d — rozšíření schválené správcem 2026-10-25

Původní přesná sada AC-007 platila pro WoL. Pro Wi-Fi analyzér jsou nyní přidána
`ACCESS_WIFI_STATE` (čtení scanů/info), `CHANGE_WIFI_STATE` (startScan),
`ACCESS_FINE_LOCATION` (scan výsledky a identifikátory AP) a `ACCESS_COARSE_LOCATION`
(společný runtime dialog s FINE na Androidu 12+). Přibližná poloha ke skenování nestačí.
Žádná poloha na pozadí, aplikace data neodesílá. Runtime žádost přijde až ve Wi-Fi záložce.
`NEARBY_WIFI_DEVICES` se pro startScan/getScanResults nepřidává: tyto metody nadále
vyžadují FINE, nejde o správu Wi-Fi připojení.

Oficiální zdroje ověřené 2026-10-25:
[Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan),
[Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions),
[přesná/přibližná poloha](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime).
LAN, SSH a WoL dále používají INTERNET a ACCESS_NETWORK_STATE; interní AndroidX signature
oprávnění zůstává. Žádná nová exportovaná komponenta ani služba na pozadí se nepřidává.

## Revize M1: SSH životní cyklus, vstup a obnova (2026-10-26)

Relace při `onStop` zůstávají otevřené včetně čekajících TOFU/autentizačních dialogů.
Monotónní politika `SshBackgroundPolicy` má limit 300 000 ms (5 minut); kontroluje
vypršení při návratu i při plánovaném timeoutu na pozadí. Explicitní zavření,
ukončení aktivity a `onCleared` relace ukončují. Bez foreground service nelze
zaručit, že OS proces uchová.

Terminál žádá `IME_FLAG_NO_PERSONALIZED_LEARNING`, `TYPE_TEXT_FLAG_NO_SUGGESTIONS`,
bez autocorrect a autofill. Je to požadavek na IME, nikoli záruka chování cizí
klávesnice. Záložní vstup maskuje rozepsaný text. Přepínač zákazu snímků je
v release výchozí zapnutý, v debug vypnutý; aktivuje `FLAG_SECURE` a na API33+
zakazuje recent screenshot.

Keystore alias vzniká pouze pokud neexistuje, nikdy při chybě dešifrování či
chybějícím klíči při čtení ciphertextu. Poškozená SSH metadata lze po potvrzení
archivovat do soukromého adresáře; původní soubor se tiše nepřepisuje.
Poslední odmítnutý host key se uchovává i po zapomenutí důvěry. Algoritmus
připnutého klíče dostává přednost pouze uvnitř původního allowlistu.

Zdroje ověřené 2026-10-26:
[EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING),
[InputType](https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_FLAG_NO_SUGGESTIONS),
[recents](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)),
[FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE),
[JSch Session](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/src/main/java/com/jcraft/jsch/Session.java).

Wi-Fi/location hardware features jsou explicitně nepovinné v manifestu; aplikace
proto zůstává instalovatelná i bez GPS. Nepřidává se žádné nové oprávnění.


## Uzavírací revize M1 (2026-10-26)

TOFU a autentizační dialog mají samostatný limit180s (`SshPromptPolicy`), celkový
handshake600s počítá s interakcí uživatele. Nejde o prodloužení síťových socket
limitů. Čekající dialog je oznámen i mimo SSH záložku. Reconnect znovu vyžaduje
potvrzení cíle a startovacího příkazu; odmítnutí nezahájí další transport.
Composition se při ztrátě fokusu dokončí bez implicitního Enteru; pravý Alt je
modifikátor rozložení, levý Alt terminálový Meta. Konkrétní IME a BT klávesnice
zbývá ověřit fyzicky. Podrobnosti a veřejné API zdroje: [SSH](funkce/ssh.md).

WoL import vždy používá přísné limity všech polí, i neaktivních; tolerance starých
záznamů platí pouze pro čtení vlastního úložiště. Náhled vypisuje zařízení a cíle,
neprivátní/nevyřešené cíle varují. Kontrola probuzení používá stejnou vybranou
Wi-Fi jako paket, polluje do deadlinu a změna zařízení zneplatní starý výsledek.
Žádné nové oprávnění ani síťová služba nebyly přidány.

Accessibility resource ID odvozená z testTag jsou zveřejněna pouze při
`BuildConfig.DEBUG`. Běžné popisky a sémantika dostupnosti zůstávají v release.


## Rychlostní test (M2, 2026-10-26)

Podrobnosti metody: [speedtest.md](funkce/speedtest.md).

- **Provoz.** Rychlostní test volá HTTPS na zvolený LibreSpeed server
  (`backend/garbage.php`, `empty.php`, `getIP.php`) a pro NDT7 HTTPS na
  `locate.measurementlab.net` + WSS na vrácený server `*.measurement-lab.org`. Jen na
  výslovné „Spustit test“, nic se nespouští automaticky ani na pozadí. Jeden test přenese
  podle rychlosti linky stovky MB až jednotky GB; UI na to upozorňuje. Další HTTPS provoz
  aplikace: veřejná IP (§ Přehled, M4) a kontrola aktualizací (§ Kontrola aktualizací).
- **Aktivní síť (M4, mění původní „jen Wi-Fi“ z M2).** Sokety
  (`OkHttpClient.socketFactory(network.socketFactory)`) i DNS (`Network.getAllByName`) jsou vázané na
  výchozí `Network` z `ActiveNetworkMonitor`: Wi-Fi, mobilní data, ethernet nebo VPN, která aplikaci
  pokrývá. Na měřené síti (mobilní data, měřený hotspot) test začne až po potvrzení odhadu dat. Bez sítě
  test odmítne; ztráta nebo výměna sítě během testu ho ukončí (`NETWORK_LOST`). Klient má
  `Proxy.NO_PROXY`: výchozí `ProxySelector` odpovídá výchozí síti, ne vázané; síť dostupná jen přes HTTP
  proxy test neprojde. Metoda: [speedtest.md](funkce/speedtest.md) §M4.
- **Cleartext zakázán.** Vlastní LibreSpeed URL projde `checkLibreSpeedUrl`: jen `https://`
  (bez schématu se doplní https), bez jména/hesla, query, fragmentu a `..`. NDT7 přijímá
  z locate jen `wss://` na `*.measurement-lab.org`; `ws://` varianty se zahodí. Redirect
  locate API nesmí přejít z https na http. `network_security_config` beze změny.
- **Souhlas M-Lab.** M-Lab ukládá a zveřejňuje každý NDT7 test jako otevřená data včetně
  veřejné IP klienta. Před prvním NDT7 testem aplikace zobrazí jednorázové upozornění; až po
  „Rozumím, spustit“ uloží čas potvrzení (`ndt7ConsentMs`) a test spustí. „Zpět“ nic
  neodešle. LibreSpeed upozornění nevyžaduje (server neukládá výsledky do veřejné databáze;
  telemetrie LibreSpeed se nevolá).
- **Soukromí a logy.** `getIP.php` se volá bez `isp=true`, takže LibreSpeed server kvůli
  aplikaci neposílá IP klienta službě ipinfo.io (s `isp=true` by to dělal pro zjištění ISP);
  zobrazí se jen veřejná IP, neukládá ani neloguje.
  `ConnectionInfo` (IP:port klienta) ze zpráv NDT7 se vůbec nedekóduje. Přístupové tokeny
  M-Lab v URL se neukládají a `Ndt7Target.toString()` je skrývá. Host z locate musí být
  platné DNS jméno bez prázdných labelů a cesta musí následovat přímo za autoritou
  (`/` v query se za cestu nepovažuje). Aplikace do logcatu nic
  z testu nepíše.
- **Úložiště.** `noBackupFilesDir/speedtest.json` (atomický zápis přes dočasný soubor):
  posledních 20 výsledků (rychlosti, latence, název serveru, SSID, frekvence, RSSI, PHY),
  čas souhlasu M-Lab a vlastní URL. Mimo zálohu (`allowBackup=false` a no-backup adresář).
  Poškozený soubor → výchozí stav a varování v UI, další zápis ho nahradí.
- **Zdroje a zátěž.** Paměť ohraničená: těla odpovědí čte 64KiB buffer a zahazuje,
  upload opakuje 1MiB náhodný payload, NDT7 upload drží frontu OkHttp pod ~2× velikost
  zprávy (limit OkHttp 16 MiB). Každý krok má timeout (connect/read/write 5 s, probe 2 s,
  NDT7 handshake 10 s, směr nejvýše 15 s) a je zrušitelný: Zastavit, odchod ze segmentu,
  záložky nebo aplikace zavře všechny sokety (`CallRegistry`). Rotace test neruší.
  Během testu neběží živá telemetrie Signálu ani skenery jiných záložek (navigace je ruší).
- **Cizí servery.** Preset `rychlost.zelina.eu` je veřejný LibreSpeed kolegy (Proxmox) a
  spouští se jen na výslovnou volbu; vlastní server zadává uživatel. Test proti serveru
  bez svolení provozovatele aplikace nepodporuje víc než běžný prohlížečový test: jeden
  běh, žádné smyčky ani plánování. Manifest beze změny oprávnění (OkHttp přidal jen
  `meta-data` do existujícího neexportovaného startup provideru).

## Mobilní síť (M4, 2026-10-27)

Rozhodnutí správce O-4 (interní pracovní záznam): karta mobilní sítě v Přehledu ukáže obě SIM
jako WiFiman. Implementace: `androidApp/.../cellular/CellularMonitor.kt`, čistá logika
`shared/.../cellular/Cellular.kt`, popis funkce [mobilni-sit.md](funkce/mobilni-sit.md).

- **Proč `READ_PHONE_STATE` (dangerous, dialog).** Bez něj Android vydá jen datovou SIM
  (`SubscriptionManager.getActiveDataSubscriptionId()`, na API 29 `getDefaultDataSubscriptionId()`);
  seznam aktivních SIM (`getActiveSubscriptionInfoList()`), typ sítě na API 29–32
  (`getDataNetworkType()`) a změnu aktivní datové SIM (`ActiveDataSubscriptionIdListener`)
  vydá jen s ním.
- **Proč `READ_BASIC_PHONE_STATE` (normal, API 33+, bez dialogu).** Stačí na
  `getDataNetworkType()` a `getVoiceNetworkType()`, takže datová SIM ukáže LTE / 5G i bez dialogu.
  Na API 29–32 se ignoruje.
- **Kdy se žádá.** Nikdy při startu. Až z karty mobilní sítě tlačítkem „Povolit zobrazení
  obou SIM“ (`cellularPermissionNeeded(context)` řekne, kdy ho nabídnout: oprávnění chybí,
  telefon má telefonii a má dvě SIM, nebo API < 31). Launcher a dialog dělá UI. Odmítnutí
  nic nerozbije: karta ukáže jen datovou SIM a `phoneStatePermission = false`.
- **Co se čte.** Jméno operátora (`getNetworkOperatorName`, `ServiceState.getOperatorAlphaLong`,
  `getSimOperatorName`, `SubscriptionInfo.getCarrierName`), číslo slotu a `subscriptionId`
  (lokální index Androidu, ne identifikátor karty), typ sítě, `TelephonyDisplayInfo`,
  `SignalStrength` (RSRP/RSRQ/SINR/RSSI, úroveň 0–4), roaming a stav služby z `ServiceState`,
  režim letadlo (`Settings.Global.AIRPLANE_MODE_ON`).
- **Co se nikdy nečte ani nevolá.** Telefonní čísla (`getLine1Number`, `SubscriptionInfo.getNumber`,
  `SubscriptionManager.getPhoneNumber`), IMEI/MEID (`getImei`, `getDeviceId`), IMSI
  (`getSubscriberId`), ICCID (`getSimSerialNumber`, `SubscriptionInfo.getIccId`), MCC/MNC,
  Cell ID, TAC/LAC, PCI, kanál ani seznam buněk (`getAllCellInfo`, `requestCellInfoUpdate`),
  stav hovorů a historie. Nic z mobilní karty se neukládá na disk ani neposílá ven; do logcatu
  se nepíše.
- **Poloha.** Monitor polohu nežádá. S `ACCESS_FINE_LOCATION` (už je kvůli Wi-Fi) vrací
  `ServiceState` i jméno operátora ze sítě; bez polohy ho Android vynechá a použije se jméno
  z `getNetworkOperatorName`/SIM. `locationPermission` ve stavu je jen informace pro UI.
- **Manifest.** `uses-feature android.hardware.telephony required=false`, protože
  `READ_PHONE_STATE` by jinak implikovalo povinnou telefonii a aplikace musí jít i na tabletu
  bez SIM.
- **Zdroje (ověřeno 2026-10-27):**
  [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission#READ_BASIC_PHONE_STATE),
  [TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager#getDataNetworkType()),
  [SubscriptionManager](https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()),
  [TelephonyCallback.ActiveDataSubscriptionIdListener](https://developer.android.com/reference/android/telephony/TelephonyCallback.ActiveDataSubscriptionIdListener),
  [TelephonyCallback.ServiceStateListener](https://developer.android.com/reference/android/telephony/TelephonyCallback.ServiceStateListener),
  [PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED](https://developer.android.com/reference/android/telephony/PhoneStateListener#LISTEN_DISPLAY_INFO_CHANGED).

## Veřejná IP a poskytovatel (M4, 2026-10-27)

Rozhodnutí správce O-1 (interní pracovní záznam F5): karta cesty v Přehledu ukáže veřejnou IP
a poskytovatele jako WiFiman. Implementace `androidApp/.../publicip/PublicIpRepository.kt`,
čistá logika `shared/.../publicip/PublicIp.kt` a `shared/.../overview/Overview.kt`.

- **Kdo co vidí.**
  - **Cloudflare** dostane HTTPS požadavek `GET /cdn-cgi/trace` na `1.1.1.1`
    a `[2606:4700:4700::1111]`, a tím vidí veřejnou IPv4 i IPv6 telefonu, čas a User-Agent
    `NetKit/<verze> (Android)`. Nic dalšího se neposílá (žádné cookies, žádný identifikátor).
  - **DNS resolver aktivní sítě** (router, operátor, nebo Soukromé DNS) a **Team Cymru**
    (autoritativní servery `asn.cymru.com`) vidí dotaz TXT se jménem z obrácené veřejné IP
    (`<oktety pozpátku>.origin.asn.cymru.com`) a dotaz `AS<číslo>.asn.cymru.com`. Z dotazu
    je veřejná IP čitelná.
- **Vypínač.** „Zjišťovat veřejnou IP“ v menu karty Připojení (výchozí zapnuto podle O-1).
  Vypnutí okamžitě zruší běžící dotaz (HTTP volání i `CancellationSignal` DNS), smaže výsledek
  z paměti a další dotazy se neposílají ani při změně sítě. Nastavení je v app-private
  SharedPreferences `overview` (zálohy vyloučené jako u všech dat aplikace).
- **Kdy se dotazuje.** Jen při otevřeném Přehledu v popředí: při otevření, při změně aktivní
  sítě (po ustálení 0,8 s) a ručně „Obnovit“. Výsledek drží 10 min v paměti pro danou síť
  (klíč typ + SSID, nebo typ + subscription id SIM; bez nich handle sítě). Na disk se veřejná
  IP ani poskytovatel neukládají a nepíšou se do logcatu.
- **Transport.** HTTPS přes IP literál (certifikát Cloudflare obsahuje IP SAN, ověřuje OkHttp
  bez výjimky z `network_security_config`; cleartext zůstává zakázaný), sokety i DNS vázané na
  aktivní `Network`, `Proxy.NO_PROXY`, bez přesměrování a opakování, limit 5 s, tělo nejvýš
  4 KiB. DNS přes `DnsResolver.rawQuery` s `FLAG_NO_CACHE_LOOKUP`, odpověď se přijme jen se
  stejným ID transakce, přesným jménem a třídou IN/TXT (`txtAnswersFor`, `txtAnswers`).
- **Zdroje a podmínky.** Cloudflare trace je diagnostický endpoint, ne smluvní API
  ([dokumentace](https://developers.cloudflare.com/fundamentals/reference/cdn-cgi-endpoint/));
  při výpadku karta ukáže „veřejná IP nedostupná“. Team Cymru IP-to-ASN je komunitní služba
  bez klíče ([popis](https://www.team-cymru.com/ip-asn-mapping)). Odmítnuté varianty (RIPEstat,
  ipinfo, ip-api, ipapi.co) a důvody: interní pracovní záznam F5. Ověřeno 2026-10-27.

## Veřejné vydání (D9–D12, 2026-10-27)

Rozhodnutí o veřejné distribuci je v [plan.md](plan.md) a [distribuce.md](distribuce.md). Dopady
na bezpečnost a oprávnění:

- **Žádné nové oprávnění kvůli vydání.** (M4 přidává `READ_PHONE_STATE` a `READ_BASIC_PHONE_STATE`
  kvůli kartě mobilní sítě, viz sekce Mobilní síť výše, a dotazy na veřejnou IP; obojí musí být
  v privacy policy a Data safety.) Kontrola aktualizací přes `latest.json` je od 1.0.0 (D14 mění D10),
  politika v sekci Kontrola aktualizací níže; manifest se kvůli ní nemění. `REQUEST_INSTALL_PACKAGES`
  zůstává zakázané: samoinstalace by zvětšila útočnou plochu a kolidovala by s politikou Google Play
  (`publikace-play.md`).
- **Co appka posílá ven (podklad pro privacy policy a Data safety).** DNS dotazy a sondy do cíle, který
  uživatel zadal; WoL a SSH jen na uživatelem zadané cíle; speedtest: LibreSpeed (uživatelem zvolený
  server, preset `rychlost.zelina.eu`) a M-Lab (`locate.measurementlab.net`, NDT7), kterým je vidět
  veřejná IP; Přehled: Cloudflare `cdn-cgi/trace` a DNS TXT na `asn.cymru.com` (veřejná IP, vypínač,
  sekce Veřejná IP a poskytovatel výše); kontrola aktualizací: `GET latest.json` z webu projektu (IP,
  čas, User-Agent s verzí; vypínač, sekce Kontrola aktualizací); UPnP popis zařízení přes nešifrované HTTP v LAN (viz výjimka v §3). Aplikace nemá
  telemetrii ani účty. **Nesmí tvrdit „nic neopouští zařízení“.**
- **Co se ukládá lokálně.** DataStore (WoL, SSH metadata, uživatelé, cíle, příkazy, host keys),
  SSH tajemství AES-GCM v Android Keystore v no-backup úložišti, souhlasy LAN v SharedPreferences,
  `speedtest.json` (20 výsledků včetně SSID). Nic z toho nejde do zálohy (`allowBackup=false`).
- **Upozornění na skenování.** Text „skenujte jen vlastní nebo povolené sítě“ je v UI (`LanScreen.kt`)
  a musí být i na stránce projektu a v popisu v obchodě.
- **Podpisový klíč.** Release klíč nikdy není v repu ani v GitHub Secrets (D11); ztráta nebo únik
  znamená novou identitu aplikace.
- **Čistý export.** Veřejné repo vzniká bez historie a bez osobních údajů, hostname, privátních IP
  a absolutních cest (kontroly v `distribuce.md` §8).
- **Zdroj:** audit připravenosti repa (Codex, jen čtení, 2026-10-27), research v
  interní pracovní záznam (interní záznam).

## Kontrola aktualizací (F3, 2026-10-28; záloha GitHub 2026-10-29)

Rozhodnutí D14 (mění D10), D17, D22 a D23, plán vydání (interní záznam).
Implementace `androidApp/.../update/UpdateChecker.kt` (`OkHttpUpdateHttp`) a `UpdateViewModel.kt`, čistá
logika `shared/.../update/UpdateInfo.kt` a `UpdateFetch.kt` (`nextRedirect`, `checkForUpdate`; testy
`UpdateInfoTest`, `UpdateFetchTest`). Schéma `latest.json`: [distribuce.md](distribuce.md) §4.

- **Kam (D22).** Nejdřív `GET <BuildConfig.UPDATE_BASE_URL>/latest.json`, tj.
  `https://www.daniel.losak.cz/netkit/latest.json` (D17); při chybě sítě, stavu jiném než 200, odmítnutém
  přesměrování, těle nad 16 KiB, neplatném UTF-8 nebo dokumentu odmítnutém parserem
  `GET <SOURCE_URL>/releases/latest/download/latest.json`
  (`https://github.com/Losminek/netkit/…`; jen když `SOURCE_URL` je přesně `https://github.com/<owner>/<repo>`).
  Obě adresy jsou zapečené při buildu z `gradle.properties` (`netkitWebBase`, `netkitSourceUrl`). Se zástupnou
  doménou (do 2026-10-31) se web nekontaktuje a release build selže; debug a dev build se pak ptají jen GitHubu.
- **Přesměrování.** OkHttp je nesleduje (`followRedirects(false)`), aplikace je následuje sama: jen
  301/302/303/307/308, nejvýš 3 na zdroj, jen `https`, výchozí port, bez user-info a fragmentu, host se
  porovnává celý (malými písmeny). Web smí přesměrovat jen na vlastní host; GitHub jen na `github.com`,
  `release-assets.githubusercontent.com` a `objects.githubusercontent.com` (řetěz ověřen 2026-10-29:
  `latest/download` → `download/vX.Y.Z` → `release-assets…`).
- **Co se posílá.** Jen GET bez těla, bez cookies (OkHttp bez `CookieJar`), bez identifikátoru zařízení
  nebo instalace, bez parametrů v URL. Hlavičky: `User-Agent: NetKit/<versionName>`,
  `Accept: application/json` a standardní hlavičky OkHttp (`Host`, `Accept-Encoding: gzip`,
  `Connection`). Web (Cloudflare Workers) a při záloze GitHub a jeho úložiště assetů vidí IP adresu
  telefonu, čas a verzi aplikace.
- **Kdy.** Nejvýš 1× za 24 h podle času posledního pokusu (i neúspěšného, nástěnné hodiny; posun hodin
  zpět znamená novou kontrolu), jen když je aktivita spuštěná (`onStart`), běžící dotaz se ruší při
  `onStop` a při vypnutí vypínače. Ručně „Zkontrolovat teď“ v O aplikaci (bez limitu 24 h, akce
  uživatele, i při vypnutém vypínači). Na pozadí nic neběží, žádný `WorkManager` ani služba.
- **Transport.** HTTPS přes výchozí síť a systémové nastavení proxy, certifikát ověřuje OkHttp proti
  systémovým CA (`network_security_config` beze změny, cleartext zakázaný). Limit 5 s na zdroj včetně
  jeho přesměrování (celkem nejvýš 10 s), bez opakování, bez HTTP cache a cookies. Tělo nejvýš
  16 KiB (podle `Content-Length` i při čtení).
- **Validace.** Parser v `shared` odmítne celý dokument při jakékoli chybě (fail closed); odkazy
  (`pageUrl`, `apkUrl`) jen `https` na přesném hostu webu nebo pod `https://github.com/<owner>/<repo>/`
  z platného `SOURCE_URL`, s přesnou velikostí písmen owner/repo. Bez platného `SOURCE_URL` se
  GitHub odkazy nepovolují. Segmenty `.`/`..` (i percent-encoded), zakódované oddělovače a `%25`
  v GitHub cestě se odmítají; user-info, nestandardní port a fragment také. Port 443 je dovolený.
  Odvozený `releasePageUrl` používá stejné repo. Toto omezení odkazů nemění politiku přesměrování
  při **čtení** záložního manifestu výše.
  V buildu bez webu se dobře utvořený odkaz na neznámý host jen zahodí a otevře se `apkUrl`
  nebo stránka release na GitHubu; poškozený odkaz dál odmítne celý dokument. Text poznámek se zbaví řídicích a formátovacích znaků (i bidi) a zkrátí na 500 znaků.
  Před otevřením odkazu se znovu kontroluje `https://`.
- **Co aplikace s výsledkem dělá.** Jen ukáže kartu „Dostupná verze X“ a na žádost uživatele otevře
  odkaz v prohlížeči (`ACTION_VIEW`, `CATEGORY_BROWSABLE`). Nic nestahuje ani neinstaluje;
  `REQUEST_INSTALL_PACKAGES` nikdy. Android přijme aktualizaci jen se stejným podpisovým certifikátem **[O]**.
- **Co se ukládá.** App-private SharedPreferences `update` (zálohy vyloučené jako všechna data):
  vypínač (výchozí zapnuto), čas posledního pokusu a posledního úspěchu (`last_success_ms`), zdroj
  poslední odpovědi (`last_source`: `web`/`github`, O aplikaci ukáže „přes GitHub“), poslední platné tělo
  `latest.json` (při každém čtení znovu validované) a `versionCode` skryté karty. Do logcatu se nic nepíše.
- **Testovací větev.** Debug a dev build (ne release, `BuildConfig.DEBUG`) čtou místo sítě soubor
  `files/update-fixture.json` aplikace, pokud existuje (zapsat jde jen přes `run-as` u debuggable buildu).
  Prochází stejným parserem a validací.
- **Riziko a opatření.** Kompromitovaný web nebo DNS může nabídnout falešný odkaz: whitelist hostů
  omezuje cíl na vlastní web a GitHub, instalaci dělá uživatel a Android odmítne aktualizaci s jiným
  podpisem; web i release uvádějí SHA-256 APK a otisk certifikátu. Výpadek webu převezme GitHub;
  falešný web nemůže potlačit zálohu jinak než platným, ale starým `latest.json` (oznámení se zpozdí).
- **Privacy policy a Data safety:** kontrola aktualizací patří mezi odchozí spojení (prompt 22).

## Hloubka JSON a citlivý obsah schránky (zadání 27)

- `shared/json/JsonLimits.kt` měří hloubku kontejnerů jedním průchodem bez rekurze a bez
  vytváření stromu. Limit **16** doplňuje dosavadní velikostní limity. Nejhlubší známé
  vstupní schéma má 4 úrovně (NDT7 locate); závorky v řetězcích včetně escapovaných
  uvozovek se nepočítají. Syntaxi dál ověřuje JSON dekodér.
- Před parsováním se kontrolují importy SSH a WoL, LibreSpeed getIP, NDT7 locate/měření
  a `latest.json`. Odmítnutí používá existující `FORMAT`, `null`/prázdný seznam nebo
  `INVALID_JSON`; nevzniká nový stav UI. Privátní úložiště SpeedHistory, DeviceStore
  a SshStorage se nemění, nejsou vstupní hranicí importu ani síťové odpovědi.
- Kopie výpisu SSH terminálu nese na API 33+ příznak citlivého obsahu. Potlačuje systémový
  náhled schránky, **nebrání vložení ani čtení oprávněnou aplikací**. Veřejný SSH klíč,
  fingerprint a LAN výsledky se tímto příznakem neoznačují; nejde o kopie přihlašovacích
  tajemství. Ověření implementace a neprovedené kontroly: report 30 (interní záznam).
