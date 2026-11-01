# NetKit — testování

**Stav 2026-10-26:** testovací postupy po revizi 07 a zadání 07. Výsledky finálního ověření patří do reportu 08; tento dokument sám není důkaz PASS.

## 1. Úrovně a co dokážou

| Úroveň | Kde běží | Dokáže | Nedokáže |
|---|---|---|---|
| JVM unit (`:shared` commonTest/jvmTest, `androidApp/src/test`) | `./gradlew testDebugUnitTest` | čisté funkce (magic packet, parsery, výpočty adres, kanály, skóre), controllery s falešnými rozhraními, serializace | cokoli závislé na Android API |
| Lint | `lintDebug` | základní chyby manifestu, oprávnění, zastaralá API | logika |
| Emulátor `netkit-api35` (headless) | `adb -s emulator-5556 …` | start aplikace, navigace, Compose UI, perzistence přes restart, WoL paket na host (`10.0.2.2`), TCP/ping/SSH proti hostu | **reálný Wi-Fi scan**, discovery fyzické LAN, WoL broadcast do reálné sítě, Xiaomi/HyperOS chování, StrongBox |
| Fyzický telefon (správce) | ručně | vše výše uvedené „nedokáže“ | — |

Pravidlo: tvrzení „funguje na telefonu“ bez záznamu fyzického testu se nepíše. Report rozlišuje
**PASS (JVM) / PASS (emulátor) / NOT RUN (telefon)**.

## 2. Emulátor — přístup a omezení

Vlastní AVD `netkit-api35` bez zámku, port 5556; vytvoření a přesné spuštění viz
[toolchain.md §6](toolchain.md#6-izolované-avd-netkit-d-2-2026-10-26). Sdílený `rex-api35`
na 5554 se nepoužívá a jeho PIN ani konfigurace se nemění.

```bash
adb -s emulator-5556 wait-for-device
adb -s emulator-5556 shell getprop sys.boot_completed   # očekávání: 1
```

- Adresy: host z pohledu emulátoru je **`10.0.2.2`**, emulátor leží za NATem `10.0.2.0/24`
  (router `10.0.2.1`, DNS `10.0.2.3`). LAN skener v emulátoru proto neuvidí domácí síť; slouží
  k ověření logiky a limitů, ne k ověření funkce.
- UDP broadcast z emulátoru se nedostane do reálné sítě. WoL se v emulátoru ověřuje odesláním
  na `10.0.2.2:9009` (cíl `CUSTOM`).
- Virtuální Wi-Fi emulátoru vrací jen simulované AP; neověřuje reálný scan.
- Automatizovaná instrumentace: v REX Mobile nešel `connectedAndroidTest` (UTP plugin vs
  dependency verification); postup tam byl `assemble…AndroidTest` + `adb -s … install -r -t` +
  `am instrument -w`. Pokud NetKit narazí na totéž, použít stejný postup.

## 3. Testovací cíle na hostu (build-host)

Všechno běží pod uživatelem `tester`, bez sudo, na vysokých portech a jen na `127.0.0.1`.
Alias `10.0.2.2` v emulátoru míří právě na loopback hostu (dokumentované chování Android
emulátoru), takže listener na `127.0.0.1` stačí.

**UDP listener pro WoL** (`python3 -I`): port 9 běžný uživatel na Ubuntu 24.04 neobsadí
(`net.ipv4.ip_unprivileged_port_start` = 1024), proto se testuje na portu **9009** — v aplikaci
se u zařízení nastaví cíl `CUSTOM` `10.0.2.2` a port 9009:

```bash
python3 -I - <<'PY'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", 9009))
data, addr = s.recvfrom(2048)
print(len(data), data[:6].hex(), data[6:12].hex())
PY
```

Obsah paketu musí být 102 B: 6× `ff` + 16× MAC. Před spuštěním ověř, že hodnotu
`net.ipv4.ip_unprivileged_port_start` lze číst (`sysctl net.ipv4.ip_unprivileged_port_start`)
a port 9009 je volný (`ss -lun`).

**TCP listener** pro port scan: `python3 -I -m http.server 8081 --bind 127.0.0.1` s explicitním bindem na loopback (nepoužívejte výchozí wildcard listener).

**SSH a LAN fixture M1:** `tools/testsrv/m1_fixture.py` poslouchá jen na
`127.0.0.1`: SSH 22222, banner 18081, odmítnuté spojení 18082, tichý TCP 18083.
Jednorázové údaje vytváří `tools/m1_provision.py` v soukromém dočasném adresáři;
na emulátor je posílá přes stdin, nikoli argv. Server neotvírá skutečný shell.
Provision/run-all/cleanup jsou v §10. Testy se nepřipojují k produkčnímu SSH.

## 4. Kontroly před předáním řezu

1. Po poslední změně kódu proveďte dva úplné strict clean buildy: příkaz níže jednou s `LC_ALL=cs_CZ.UTF-8 LANG=cs_CZ.UTF-8`, podruhé s `LC_ALL=C LANG=C`. Uložte oba výstupy, počty provedení i unikátních testovacích metod a lint (0 chyb, nejvýše 22 varování pro uzavření revize 07). Konfigurace Gradle `Test` předává testovacím procesům `LC_ALL=C`; to nenahrazuje regresní testy klasifikace errno bez textu.

   `./gradlew --no-daemon --dependency-verification strict clean testDebugUnitTest lintDebug assembleDebug :shared:jvmTest assembleDebugAndroidTest`
2. `grep -rE "^import (java|javax|android)\." shared/src/commonMain` prázdné.
3. Emulátor: instalace přes `adb -s emulator-5556`, start, scénáře řezu, screenshot.
4. `agent-semgrep shared androidApp tools` (základní pravidla nepokrývají Kotlin), Python testy a `pyright tools/`; secret-scan `--staged` před každým commitem, výstup každé kontroly uchovat v `artifacts/`.
5. Manifest diff: nové oprávnění je v [bezpecnost.md](bezpecnost.md).
6. Seznam všech spuštěných `adb` příkazů v reportu — žádný bez `-s emulator-*`.

## 5. Fyzický checklist — způsob zápisu

Jediný zdroj kontrol a jejich výsledků je [§9](#9-fyzický-checklist-m1--krok--očekávání--výsledek).
interní pracovní záznam je rozcestník, nikoli druhá editovaná kopie (A-5).
správce instaluje APK s identitou z reportu sám; agent na telefon nesahá.
Kontroly provádějte jen na vlastních nebo výslovně autorizovaných cílech. Změny AP,
VPN, polohy a throttlingu dělejte pouze v testovacím prostředí a potom je vraťte.
Výsledek se zapisuje k jednotlivému kroku do §9, případně formou odkazu na přílohu
se stejným ID, APK SHA-256 a datem. Nedostupný scénář je NOT RUN s důvodem.

## 6. Metriky, které se měří, ne odhadují

- Doba skenu /24 na telefonu (cíl ~20 s, potvrdit).
- Paměť aplikace při skenu (`dumpsys meminfo` na emulátoru, subjektivně na telefonu).
- Četnost obnovení Wi-Fi scanu s throttlingem zapnutým a vypnutým.
- Spotřeba baterie při 10 minutách pingu a skeneru (hrubě; až M2).

## 7. WoL IME E2E — historie, stav R2 a reprodukce

M0 F3 zůstává historicky otevřené (report 03 (interní záznam)).
Report 06 zaznamenal FAIL fokusu formuláře; dřívější ruční úspěch není důkazem
nového APK. V běhu zadání 07 proběhla jediná diagnostická matice R2
`bce50bab3d24409cb5a0d0ace881bc03`: varianta a doložila fokus a IME, b skončila
chybějícím polem a c–f jsou NOT RUN. První oprava viditelnosti/cache kořene i druhá
oprava místa dotyku znovu selhaly na fokusu MAC. Rozpočet A-1 je vyčerpaný;
nejde o úspěšný průchod a matice ani E2E se znovu automaticky nespouštějí.
Důkaz druhého pokusu: `artifacts/n7/wol-repair2/summary.json` a podřízený
`wol/f249776a90d7449fb83abb95c9b3ac4e/summary.json` se screenshotem/diagnostikou.
Identitu buildů a úplnou časovou osu uvádí report 08 (interní záznam).
Ruční kontrola **N7-203 PASS** na finálním APK (identita F v reportu 08):
MAC i Poznámka měly fokus a viditelnou IME při skutečném `input tap/text`,
neplatná MAC ukázala chybu, Uložit bylo nad IME a Back → Back otevřelo zahození.
Důkazy: `final-manual/summary.json` a screenshoty v sadě artefaktů reportu 08.
Nejde o UDP/restart/CRUD E2E. **Fyzický test NOT RUN**; A-2 dovoluje ponechat
E2E jako dluh teprve po obou kontrolách, konečné přijetí je na správci.

Lokální testy používají `kotlin.test` s TestNG (bez JUnit v projektových konfiguracích).
Společné testy se spouštějí pro JVM i Android unit target; `jvmTest` je proto nutné uvést
výslovně vedle příkazu ze sekce 4:

```bash
./gradlew --no-daemon --dependency-verification strict \
  testDebugUnitTest lintDebug assembleDebug :shared:jvmTest assembleDebugAndroidTest
```

Instrumentační runner je vlastní `android.app.Instrumentation` s platformní `UiAutomation`.
Ovládá skutečné Compose UI přes stabilní accessibility resource ID. Nepoužívá přímé volání
controlleru jako náhradu za klikání. Očekávané texty čte z resources aplikace, takže funguje
s českou i anglickou lokalizací; provedený běh je uveden v reportu.

Aktuální předpoklad: vlastní `netkit-api35` na `emulator-5556` a APK z předchozího strict buildu.
Testovací Application dostává pro každý běh nový identifikátor a vlastní soubor v
`files/netkit-test-data/<runId>/`; produkční `files/datastore/wol-devices.json` nepoužívá.
Stejný runId propojí write/read přes restart procesu, další běh používá nový. Testovací
Application se instancuje jen instrumentací, produkční UI neobsahuje testovací přepínač.

```bash
python3 -I tools/run_wol_e2e.py --serial emulator-5556 --install
```

Přímý příkaz výše je diagnostický postup M0 bez identity nainstalovaného buildu.
Pro povolený běh s identitou použijte obal `run_m1_checks.py --phase wol-e2e` (§10), který přidává
SHA obou APK a git SHA. Oba instrumentační runnery jsou ve stejném test APK;
přestavování varianty mezi WoL a M1 není potřeba.

Tento příkaz spustí listener na `127.0.0.1:9009`, ověří obě instrumentační fáze,
restart a data, stáhne screenshoty a odstraní pouze svůj testovací adresář. Všechny adb
příkazy nesou explicitní emulátor; zapisují se do `commands.jsonl` adresáře běhu.
`summary.json` a exit 0 znamenají společný PASS. Samotný exit kód `am instrument` nestačí:
runner vyžaduje PASS marker, správný runId/phase, dokončovací kód i jednotlivé kroky.
Listener musí přijmout přesných 102 B, 6× FF + 16× AA:BB:CC:DD:EE:FF. Timeout nebo
chybějící krok vrátí nenulový exit. WoL runner v `finally` nejprve sbírá diagnostiku
(screenshot, hierarchie, stav okna/IME, logcat), pak uklízí vlastní listener a izolovaná
testovací data. Pokud instrumentace může ještě běžet, zastaví testovaný proces
`cz.netkit.app` na zadaném emulátoru a ověří jeho nepřítomnost. Tento izolovaný WoL
runner proto nepoužívejte při cizích živých SSH relacích. Selhání úklidu mění výsledek
na FAIL; souhrn se zapisuje až po úklidu.
`--keep-test-data` lze použít pro diagnostiku; nikdy se neprovádí `pm clear`.

Write pokrývá navigaci, CRUD, neplatnou MAC, skutečnou IME a Back s potvrzením zahození,
a reálné odeslání na `10.0.2.2:9009`. Read po restartu ověří všechna uživatelská pole,
ID a další editaci/smazání. Accessibility čekání samo nescrolluje; hledání prvku ve
formuláři má samostatné omezené scrollování. Jde stále o platformní accessibility test,
ne Compose semantics test; proto nepokrývá každý vnitřní Compose stav.

Kontroly samotného runneru:

```bash
python3 -I -m unittest discover -s tools -p 'test_*.py' -v
pyright tools/run_wol_e2e.py tools/test_run_wol_e2e.py tools/testsrv/wol_listener.py
```

Původní měření jsou v reportu M0/M1a (interní záznam); opravy a nové běhy
jsou v interní pracovní záznam. Fyzický telefon zůstává odděleným ověřením.

## 8. Kontext fyzického ověření WoL

Původní kontroly M0/M1a jsou zahrnuté v jediné tabulce §9 (včetně AUTO/CUSTOM/LIMITED,
AP izolace, DNS/NXDOMAIN/`.local`, VPN, IME, Back a rotace). Hláška „odesláno“
dokládá předání socketu; doručení potvrzuje příjemce nebo nezávislý capture.
Neúspěšné probuzení samo neurčuje chybu aplikace.

[AOSP DNS Resolver](https://source.android.google.cn/docs/core/ota/modular-system/dns-resolver?hl=en)
popisuje mDNS pro `getaddrinfo` od aktualizace z listopadu 2021. To není důkaz
chování `DnsResolver.query(TYPE_A)` na konkrétním API/OEM; vlastní `.local` test
zůstává povinný, jeho nedostupnost se zaznamená. NetKit nepřidává vlastní mDNS klient.

## 9. Fyzický checklist M1 — krok → očekávání → výsledek

**Telefon: všechny řádky NOT RUN.** Vyplňuje správce po nezávislé revizi. Testuj jen
vlastní zařízení/sítě; serverové klíče měň pouze na izolované testovací instanci.
Před začátkem zaznamenej APK SHA-256, model, OS/HyperOS, datum a síťový kontext.
Do výsledků nepatří hesla, klíče ani zákaznická data. U každé kontroly zapiš
PASS / FAIL / NOT RUN a stručnou poznámku. Nedostupné API/OEM chování není PASS.
U SSH negativních přihlášení jen jeden pokus, žádný brute force. Po skončení
vrať původní testovací nastavení a odstraň dočasný testovací účet vlastní cestou.

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| 001 | Příprava a zobrazení — Zapiš model, OS, verzi aplikace, datum a SHA-256 APK. | Identita odpovídá finálnímu reportu 08; zaznamenejte též IME a rozložení klávesnice. | NOT RUN |
| 002 | Příprava a zobrazení — Nainstaluj upgrade přes vlastní 0.1.0 se známým WoL záznamem. | Původní zařízení a nastavení zůstaly zachované. | NOT RUN |
| 003 | Příprava a zobrazení — Spusť záložku Wi-Fi česky. | Žádný nepřeložený text, prázdné stavy jsou srozumitelné. | NOT RUN |
| 004 | Příprava a zobrazení — Spusť záložku LAN česky. | Žádný nepřeložený text, prázdné stavy jsou srozumitelné. | NOT RUN |
| 005 | Příprava a zobrazení — Spusť záložku SSH česky. | Žádný nepřeložený text, prázdné stavy jsou srozumitelné. | NOT RUN |
| 006 | Příprava a zobrazení — Spusť záložku WoL česky. | Žádný nepřeložený text, prázdné stavy jsou srozumitelné. | NOT RUN |
| 007 | Příprava a zobrazení — Spusť záložku Wi-Fi anglicky. | Anglické texty odpovídají českým. | NOT RUN |
| 008 | Příprava a zobrazení — Spusť záložku LAN anglicky. | Anglické texty odpovídají českým. | NOT RUN |
| 009 | Příprava a zobrazení — Spusť záložku SSH anglicky. | Anglické texty odpovídají českým. | NOT RUN |
| 010 | Příprava a zobrazení — Spusť záložku WoL anglicky. | Anglické texty odpovídají českým. | NOT RUN |
| 011 | Příprava a zobrazení — Přepni světlý režim. Zkontroluj záložku Wi-Fi. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 012 | Příprava a zobrazení — Přepni světlý režim. Zkontroluj záložku LAN. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 013 | Příprava a zobrazení — Přepni světlý režim. Zkontroluj záložku SSH. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 014 | Příprava a zobrazení — Přepni světlý režim. Zkontroluj záložku WoL. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 015 | Příprava a zobrazení — Přepni tmavý režim. Zkontroluj záložku Wi-Fi. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 016 | Příprava a zobrazení — Přepni tmavý režim. Zkontroluj záložku LAN. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 017 | Příprava a zobrazení — Přepni tmavý režim. Zkontroluj záložku SSH. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 018 | Příprava a zobrazení — Přepni tmavý režim. Zkontroluj záložku WoL. | Texty, stavy a grafy jsou čitelné. | NOT RUN |
| 019 | Příprava a zobrazení — Nastav písmo na 200 %. Zkontroluj záložku Wi-Fi. | Akce se neřežou a obsah lze posouvat. | NOT RUN |
| 020 | Příprava a zobrazení — Nastav písmo na 200 %. Zkontroluj záložku LAN. | Akce se neřežou a obsah lze posouvat. | NOT RUN |
| 021 | Příprava a zobrazení — Nastav písmo na 200 %. Zkontroluj záložku SSH. | Akce se neřežou a obsah lze posouvat. | NOT RUN |
| 022 | Příprava a zobrazení — Nastav písmo na 200 %. Zkontroluj záložku WoL. | Akce se neřežou a obsah lze posouvat. | NOT RUN |
| 023 | Příprava a zobrazení — Otoč rozepsaný formulář WoL. | Rozepsaná netajná data zůstala. | NOT RUN |
| 024 | Příprava a zobrazení — Otoč rozepsaný formulář SSH. | Rozepsaná netajná data zůstala; tajemství nejsou v uloženém stavu. | NOT RUN |
| 025 | Příprava a zobrazení — Přepni záložku a vrať se k filtrům Wi-Fi a nástroji LAN. | Volby zůstaly zachované. | NOT RUN |
| 026 | Příprava a zobrazení — Otevři klávesnici: Wi-Fi filtr SSID. | Aktivní pole a hlavní akce jsou dosažitelné nad IME. | NOT RUN |
| 027 | Příprava a zobrazení — Otevři klávesnici: LAN cíl. | Aktivní pole a hlavní akce jsou dosažitelné nad IME. | NOT RUN |
| 028 | Příprava a zobrazení — Otevři klávesnici: SSH nový profil. | Aktivní pole a hlavní akce jsou dosažitelné nad IME. | NOT RUN |
| 029 | Příprava a zobrazení — Otevři klávesnici: SSH terminál. | Aktivní pole a hlavní akce jsou dosažitelné nad IME. | NOT RUN |
| 030 | Příprava a zobrazení — Otevři klávesnici: WoL zařízení. | Aktivní pole a hlavní akce jsou dosažitelné nad IME. | NOT RUN |
| 031 | Příprava a zobrazení — Stiskni Back v upraveném formuláři. | Zahození změn vyžaduje potvrzení. | NOT RUN |
| 032 | Wi-Fi — Při prvním skenu zamítni polohu. | Scan se nespustí a UI vysvětlí důvod. | NOT RUN |
| 033 | Wi-Fi — Povol jen přibližnou polohu. | UI vyžaduje přesnou polohu. | NOT RUN |
| 034 | Wi-Fi — Zamítni oprávnění trvale a otevři Nastavení aplikace. | Systém otevře detail NetKitu. | NOT RUN |
| 035 | Wi-Fi — Vypni systémovou polohu. | UI ukazuje vypnutou polohu. | NOT RUN |
| 036 | Wi-Fi — Použij Nastavení polohy. | Systém otevře příslušné nastavení. | NOT RUN |
| 037 | Wi-Fi — Vypni Wi-Fi. | UI pravdivě hlásí nedostupnost Wi-Fi. | NOT RUN |
| 038 | Wi-Fi — Povol přesnou polohu a Wi-Fi a spusť scan. | AP se zobrazí a údaj o čerstvosti odpovídá výsledku. | NOT RUN |
| 039 | Wi-Fi — Porovnej SSID známého AP. | Shoda s konfigurací AP nebo označená redakce OEM. | NOT RUN |
| 040 | Wi-Fi — Porovnej BSSID známého AP. | Shoda s rádiem AP. | NOT RUN |
| 041 | Wi-Fi — Porovnej frekvenci a kanál známého AP. | Shoda v podporovaném pásmu. | NOT RUN |
| 042 | Wi-Fi — Porovnej šířku kanálu AP. | Shoda nebo výslovně nedostupný údaj. | NOT RUN |
| 043 | Wi-Fi — Porovnej zabezpečení WPA2/WPA3 transition. | Zobrazen přechodný režim, nikoli čisté WPA3. | NOT RUN |
| 044 | Wi-Fi — Porovnej OWE transition na vlastním AP, pokud dostupné. | Není označen jako čisté šifrované OWE. | NOT RUN |
| 045 | Wi-Fi — Porovnej standard a link rate připojení. | Shoda se systémem nebo nedostupný údaj. | NOT RUN |
| 046 | Wi-Fi — Porovnej IPv4 Wi-Fi při aktivní VPN. | Údaje patří podkladové Wi-Fi; brána preferuje IPv4. | NOT RUN |
| 047 | Wi-Fi — Porovnej IPv4 bránu Wi-Fi při aktivní VPN. | Údaje patří podkladové Wi-Fi; brána preferuje IPv4. | NOT RUN |
| 048 | Wi-Fi — Porovnej DNS Wi-Fi při aktivní VPN. | Údaje patří podkladové Wi-Fi; brána preferuje IPv4. | NOT RUN |
| 049 | Wi-Fi — Použij filtr pásma 2,4 GHz. | Seznam obsahuje pouze zvolené pásmo. | NOT RUN |
| 050 | Wi-Fi — Použij filtr pásma 5 GHz. | Seznam obsahuje pouze zvolené pásmo. | NOT RUN |
| 051 | Wi-Fi — Použij filtr pásma 6 GHz. | Seznam obsahuje pouze zvolené pásmo. | NOT RUN |
| 052 | Wi-Fi — Vyhledej část SSID. | Seznam odpovídá dotazu. | NOT RUN |
| 053 | Wi-Fi — Přepni řazení Wi-Fi podle síly. | Pořadí odpovídá názvu/síle/kanálu. | NOT RUN |
| 054 | Wi-Fi — Přepni řazení Wi-Fi podle názvu. | Pořadí odpovídá názvu/síle/kanálu. | NOT RUN |
| 055 | Wi-Fi — Přepni řazení Wi-Fi podle kanálu. | Pořadí odpovídá názvu/síle/kanálu. | NOT RUN |
| 056 | Wi-Fi — Vyber AP pro historii. | Graf jmenuje AP a má časovou osu. | NOT RUN |
| 057 | Wi-Fi — Zopakuj výběr stejného AP. | Historie se nemaže. | NOT RUN |
| 058 | Wi-Fi — Zruš výběr AP. | Výběr je zrušen bez záměny se sousedním AP. | NOT RUN |
| 059 | Wi-Fi — Proveď nový scan stejného AP. | Historie přidá vzorek jen pro nový timestamp. | NOT RUN |
| 060 | Wi-Fi — Počkejte více než 30 s bez nového datasetu. | Viditelné varování o stáří; doporučení a graf nejsou ihned prázdné, do pěti minut používají původní dataset (R6). | NOT RUN |
| 061 | Wi-Fi — Prohlédni graf obsazení. | Má osu kanálu/frekvence a čitelnou legendu. | NOT RUN |
| 062 | Wi-Fi — Prohlédni doporučení bez AP v pásmu. | Pro takové pásmo není doporučení. | NOT RUN |
| 063 | Wi-Fi — Prohlédni doporučení 2,4 GHz. | Kandidáti 1/6/11, šířka/skóre/omezení uvedené. | NOT RUN |
| 064 | Wi-Fi — Prohlédni DFS doporučení, je-li k dispozici. | DFS je označeno. | NOT RUN |
| 065 | Wi-Fi — Se zapnutým throttlingem proveď omezenou sérii scanů. | Omezení/cache není vydáváno za čerstvý úspěch. | NOT RUN |
| 066 | Wi-Fi — Opakuj s vypnutým throttlingem, je-li volba dostupná. | Zapiš skutečné chování OEM, ne předpoklad. | NOT RUN |
| 067 | Wi-Fi — Vrať původní nastavení throttlingu a polohy. | Původní nastavení obnoveno. | NOT RUN |
| 068 | Wi-Fi — Během scanu odejdi z aplikace a vrať se. | Nezůstane trvalý busy stav ani falešně čerstvá cache. | NOT RUN |
| 069 | LAN — Otevři LAN poprvé. | Viditelné upozornění na autorizované sítě. | NOT RUN |
| 070 | LAN — Zvol jednotlivé nástroje. | Validují se jen relevantní pole. | NOT RUN |
| 071 | LAN — Zadejte vlastní nebo dokumentační rozsah RFC 5737 mimo Wi-Fi; potvrzení nepotvrzujte. Samostatně zadejte vlastní rozsah větší než /20 bez spuštění. | Dialog uvádí důvod a počty cílů/portů, případně původní jméno a vyřešené IP; odmítnutí neodesílá sondy. | NOT RUN |
| 072 | LAN — Odmítni potvrzení cíle. | Žádná sonda neproběhne. | NOT RUN |
| 073 | LAN — Změň Wi-Fi při otevřeném potvrzení cíle. | Starý pin se nepoužije pro novou síť. | NOT RUN |
| 074 | LAN — Spusťte discovery vlastní /24, měřte čas a souběžně sledujte zátěž vlastního routeru. | Zapište počet cílů, dobu a zátěž; cíl přibližně 20 s. Při nežádoucí zátěži test zrušte a odchylku zaznamenejte. | NOT RUN |
| 075 | LAN — Porovnej nalezený router/NAS/PC. | Metoda ICMP/TCP/TCP-RST je uvedena; absence není důkaz vypnutí. | NOT RUN |
| 076 | LAN — Zapni AP izolaci na vlastní testovací Wi-Fi. | Neodpovídající host se nevydává za dosažený; po testu izolaci vrať. | NOT RUN |
| 077 | LAN — Zruš discovery. | Stav CANCELLED a počet prohledaných cílů, nové sondy se zastaví. | NOT RUN |
| 078 | LAN — Nech discovery doběhnout. | Stav FINISHED a počty jsou viditelné. | NOT RUN |
| 079 | LAN — Ztrať Wi-Fi během skenu. | Žádný fallback na mobilní síť, srozumitelný stav. | NOT RUN |
| 080 | LAN — Pinguj vlastní router. | Sekvence roste, RTT a ztráta odpovídají odpovědím. | NOT RUN |
| 081 | LAN — Pinguj nereagující vlastní adresu. | Timeout/ztráta bez pádu. | NOT RUN |
| 082 | LAN — Prohlédni statistiku pingu. | Min/avg/max/jitter, osy grafu a bounded seznam. | NOT RUN |
| 083 | LAN — Exportuj ping během běhu. | Soubor je konzistentní snapshot. | NOT RUN |
| 084 | LAN — Pinguj vlastní cíl s aktivní VPN a mobilními daty. | ICMP jde po vybrané Wi-Fi nebo hlásí nedostupnost; dolož routerem. | NOT RUN |
| 085 | LAN — Spusť traceroute při jiné výchozí síti/VPN. | Před spuštěním vyžádá potvrzení možného provozu mimo Wi-Fi. | NOT RUN |
| 086 | LAN — Odmítni varování traceroute. | Sondy se nespustí. | NOT RUN |
| 087 | LAN — Potvrď traceroute k vlastnímu routovanému cíli. | Výsledek nese poznámku mohlo jít mimo Wi-Fi; chybějící hopy nejsou domyšlené. | NOT RUN |
| 088 | LAN — Skenuj známý otevřený TCP port. | Výsledek OPEN. | NOT RUN |
| 089 | LAN — Skenuj známý zavřený TCP port. | Výsledek REFUSED, sken pokračuje. | NOT RUN |
| 090 | LAN — Čti banner serveru přijímajícího spojení a posílajícího RST. | Port zůstane OPEN bez banneru. | NOT RUN |
| 091 | LAN — Čti banner vlastního serveru. | Pouze pasivní omezený text, řídicí znaky sanitizované. | NOT RUN |
| 092 | LAN — Vyřeš A/AAAA vlastního hostname. | Odpověď z DNS vybrané Wi-Fi. | NOT RUN |
| 093 | LAN — Vyřeš PTR vlastní IP s reverzním záznamem. | Sanitizované jméno bez vloženého řádku. | NOT RUN |
| 094 | LAN — Vyřeš PTR adresy bez záznamu. | Srozumitelná absence. | NOT RUN |
| 095 | LAN — Vyřeš vlastní .local jméno. | Zapiš skutečnou cestu a výsledek, ne předpokládanou podporu. | NOT RUN |
| 096 | LAN — Na API34+ vyhledej vlastní mDNS službu. | Název/typ/port a přednost IPv4. | NOT RUN |
| 097 | LAN — Zruš mDNS discovery. | Operace se ukončí. | NOT RUN |
| 098 | LAN — Na dostupném API29–33 otevři mDNS. | Pravdivě mimo rozsah; jinak NOT RUN. | NOT RUN |
| 099 | LAN — Exportuj LAN do TEXT přes SAF. | Obsah odpovídá výsledkům, bez překročení limitu. | NOT RUN |
| 100 | LAN — Exportuj LAN do CSV přes SAF. | Správné buňky a ochrana tabulkových vzorců. | NOT RUN |
| 101 | LAN — Exportuj LAN do JSON přes SAF. | Platné JSON včetně výchozích hodnot. | NOT RUN |
| 102 | LAN — Otoč telefon během SAF exportu. | Formát a snapshot nezmizí, soubor není prázdný. | NOT RUN |
| 104 | SSH — Připoj se na vlastní neznámý testovací server. | TOFU s čitelným SHA256 otiskem. | NOT RUN |
| 105 | SSH — Odmítni TOFU. | Důvěra se neuloží a autentizace neproběhne. | NOT RUN |
| 106 | SSH — Porovnej fingerprint nezávislou cestou a potvrď. | Spojení pokračuje jen se správným klíčem. | NOT RUN |
| 107 | SSH — Připoj se znovu ke stejnému klíči. | Bez nového TOFU. | NOT RUN |
| 108 | SSH — Na izolovaném testovacím serveru vyměň host key. | Tvrdé odmítnutí před autentizací; původní důvěra zachována. | NOT RUN |
| 109 | SSH — Otevři zapomenout host key. | Ukazuje původní i poslední odmítnutý otisk. | NOT RUN |
| 110 | SSH — Potvrď zapomenutí a připoj se znovu. | Nové TOFU zachovává kontext poslední změny. | NOT RUN |
| 111 | SSH — Přidej serveru nový algoritmus při zachování původního. | Preferuje uložený povolený algoritmus, bez falešné změny klíče. | NOT RUN |
| 112 | SSH — Přihlas se heslem na testovací účet. | Autentizace uspěje, tajemství nikde nevypsáno. | NOT RUN |
| 113 | SSH — Zadej jednou chybné heslo na účtu bez lockoutu. | Lokalizovaná chyba autentizace. | NOT RUN |
| 114 | SSH — Přihlas se keyboard-interactive. | Výzva je přiřazena správnému endpointu. | NOT RUN |
| 115 | SSH — Přihlas se Ed25519 klíčem. | Klíčová autentizace uspěje. | NOT RUN |
| 116 | SSH — Importuj vlastní šifrovaný klíč se správnou passphrase. | Uloží se pouze šifrovaně. | NOT RUN |
| 117 | SSH — Importuj s chybnou passphrase. | Rozlišená chyba bez tichého vytvoření nového klíče. | NOT RUN |
| 118 | SSH — Importuj testovací RSA pod 3072 bitů. | Odmítnuto. | NOT RUN |
| 119 | SSH — Importuj testovací DSA klíč. | Odmítnuto. | NOT RUN |
| 120 | SSH — Připoj se k testovacímu serveru jen se SHA-1/DSA/CBC. | Odmítnuto bez slabého fallbacku. | NOT RUN |
| 121 | SSH — Vyvolej DNS chybu vlastním neexistujícím testovacím názvem. | Rozlišená DNS chyba. | NOT RUN |
| 122 | SSH — Vyvolej timeout vlastní nedostupnou adresou. | Rozlišená chyba timeoutu. | NOT RUN |
| 123 | SSH — U profilu nastav neškodný startupCommand. | Příkaz je součástí potvrzení před spuštěním. | NOT RUN |
| 124 | SSH — Otevři čtyři relace. | Každá má čitelný název a jde samostatně ovládat. | NOT RUN |
| 125 | SSH — Zkus pátou relaci. | Viditelný limit čtyř relací. | NOT RUN |
| 126 | SSH — Piš software klávesnicí přímo do terminálu. | Bajty jdou průběžně, není nutné Poslat text. | NOT RUN |
| 127 | SSH — Otevři a zavři IME s promptem na posledním řádku. | Prompt ani historie se neztratí. | NOT RUN |
| 128 | SSH — Spusť less na vlastním testovacím serveru. | Šipky, mezera a q fungují. | NOT RUN |
| 129 | SSH — Spusť nano na dočasném testovacím souboru. | Psaní, Ctrl a ukončení fungují. | NOT RUN |
| 130 | SSH — Spusť vim na dočasném testovacím souboru. | DECCKM a základní ovládání fungují v dokumentovaném rozsahu. | NOT RUN |
| 131 | SSH — Spusť htop na vlastním testovacím serveru. | Šipky a funkční klávesy fungují. | NOT RUN |
| 132 | SSH — Ověř klávesu Delete na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 133 | SSH — Ověř klávesu Insert na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 134–145 | SSH — Ověřte postupně klávesy F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11 a F12 na vlastním testovacím serveru. | Pro každou klávesu zapište přijatou sekvenci; souhrnný PASS vyžaduje shodu všech 12. | NOT RUN |
| 146 | SSH — Ověř klávesu Shift-Tab na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 147 | SSH — Ověř klávesu Ctrl+nahoru na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 148 | SSH — Ověř klávesu Ctrl+dolů na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 149 | SSH — Ověř klávesu Ctrl+vlevo na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 150 | SSH — Ověř klávesu Ctrl+vpravo na vlastním SSH testovacím serveru. | Server obdrží odpovídající sekvenci. | NOT RUN |
| 151 | SSH — Vlož víceřádkový text. | Vyžaduje potvrzení a neprovede nečekaný Enter. | NOT RUN |
| 152 | SSH — Zapni zákaz screenshotů. | Terminál je na snímku chráněný; náhled recent aplikací také. | NOT RUN |
| 153 | SSH — V debug ověřte výchozí vypnutý zákaz screenshotů; zapněte a znovu vypněte přepínač. | Výchozí debug dovoluje snímek, zapnutí jej chrání a vypnutí znovu dovolí. Release má výchozí zákaz zapnutý. | NOT RUN |
| 154 | SSH — Otevři systémový picker při živé relaci. | Relace ani autentizační dialog nejsou zavřeny kvůli onStop. | NOT RUN |
| 155 | SSH — Přejdi HOME a vrať se do 5 minut. | Relace žije, pokud OS nezabil proces. | NOT RUN |
| 156 | SSH — Nech aplikaci na pozadí přes 5 minut. | Relace se uzavře. | NOT RUN |
| 157 | SSH — Zamkni a rychle odemkni telefon při relaci. | Krátký odchod nezavře relaci kvůli onStop. | NOT RUN |
| 158 | SSH — Odpoj síť během relace. | Stav spojení nejspíš ztraceno a možnost reconnect. | NOT RUN |
| 159 | SSH — Použij reconnect. | Vznikne nová ověřená relace. | NOT RUN |
| 160 | SSH — Připoj SSH s VPN a mobilními daty. | Použije výchozí trasu podle OS, na rozdíl od WoL/LAN ICMP. | NOT RUN |
| 161 | SSH — Použij Zavřít všechny relace. | Server potvrdí uzavření všech spojení. | NOT RUN |
| 162 | SSH — Exportuj profily přes SAF. | Žádná hesla, klíče ani reference tajemství. | NOT RUN |
| 163 | SSH — Smažte testovací profil s uloženým tajemstvím a vytvořte nový profil pro stejný testovací účet. | Profil zmizí a nový profil nemůže použít starou referenci tajemství; přímé smazání šifrovaných bytes ověřují automatizované testy, nikoli telefonní UI. | NOT RUN |
| 165 | Wake-on-LAN — Přidej vlastní zařízení s MAC a výchozím opakováním. | Uložené 3 pakety po 250 ms. | NOT RUN |
| 166 | Wake-on-LAN — Ověř odeslání na vlastním packet capture. | Správný 102B magic packet a počet opakování. | NOT RUN |
| 167 | Wake-on-LAN — Změň počet a interval opakování. | Nastavení platí pouze pro dané zařízení. | NOT RUN |
| 168 | Wake-on-LAN — Nastavte TCP kontrolu s probeHost/probePort a dobou kontroly 20 s; vlastní cíl předem vypněte a probuďte. | Po prechecku a paketech běží opakované kontroly po nejvýše 20 s; zachycená odpověď se odliší od odeslání. Neodpověď neprokazuje vypnutí. | NOT RUN |
| 169 | Wake-on-LAN — Nastav ICMP kontrolu s probeHost. | Kontrola jde po stejné Wi-Fi Network nebo hlásí nedostupnost. | NOT RUN |
| 170 | Wake-on-LAN — Zrušte běžící kontrolu probuzení. | Kontrola se ukončí, další sonda se nespustí a pozdní odpověď nepřepíše stav zrušení. | NOT RUN |
| 171 | Wake-on-LAN — Probuď zařízení s VPN a mobilními daty. | UDP i DNS a kontrola jdou po Wi-Fi mimo VPN, dolož capturem. | NOT RUN |
| 172 | Wake-on-LAN — Vypni Wi-Fi při odeslání. | Žádný fallback na mobilní síť. | NOT RUN |
| 173 | Wake-on-LAN — Seřaďte názvy s diakritikou a dvojicí H, Ch, I. | České řazení zachová Ch mezi H a I; záznamy ani nastavení se neztratí. | NOT RUN |
| 174 | Wake-on-LAN — Přesuň zařízení ručně nahoru/dolů. | Pořadí přetrvá restart. | NOT RUN |
| 175 | Wake-on-LAN — Exportuj JSON přes SAF. | Obsahuje kompletní seznam bez tajemství. | NOT RUN |
| 176 | Wake-on-LAN — Importujte platný JSON s novým i přepisovaným záznamem. | Náhled uvádí počty a každý název, MAC, cíl, port, kontrolu a původní název přepisovaného zařízení; nic se neukládá před potvrzením. | NOT RUN |
| 177 | Wake-on-LAN — Odmítni náhled importu. | Data se nezmění. | NOT RUN |
| 178 | Wake-on-LAN — Potvrď náhled importu. | Přijme celý validovaný soubor. | NOT RUN |
| 179 | Wake-on-LAN — Importuj soubor s jedním neplatným záznamem. | Odmítne celý import, žádné tiché zahození. | NOT RUN |
| 180 | Wake-on-LAN — Importuj soubor větší než 1 MiB. | Odmítne jej bez změny dat. | NOT RUN |
| 181 | Wake-on-LAN — Ověř migraci schématu1 po upgrade. | Staré hodnoty zachované, nová nastavení mají výchozí hodnoty. | NOT RUN |
| 183 | SSH — Na fyzické klávesnici s českým rozložením napište pravým Alt znak @. | Server obdrží @ bez ESC prefixu; zaznamenejte model, IME a rozložení. | NOT RUN |
| 184 | SSH — Stejně napište zpětné lomítko (\). | Server obdrží jeden znak zpětného lomítka bez ESC prefixu. | NOT RUN |
| 185 | SSH — Stejně napište svislici (&#124;). | Server obdrží jednu svislici bez ESC prefixu. | NOT RUN |
| 186 | SSH — Stejně napište levou a pravou složenou závorku { }. | Oba znaky dorazí podle českého rozložení, nikoli jako Alt terminálová sekvence. | NOT RUN |
| 187 | SSH — V IME s composition rozepište text s diakritikou, upravte jej Backspace a potvrďte návrh. | Server obdrží potvrzený text právě jednou; neodešle předčasný Enter. Zapište konkrétní IME. | NOT RUN |
| 188 | SSH — V composition vložte emoji mimo BMP, smažte jej a potvrďte zbývající text. | Žádný osamocený surrogate ani náhradní poškozený znak; široké vykreslování terminálu není tímto testem slibováno. | NOT RUN |
| 189 | SSH — Rozepište composition a stiskněte klávesu z terminálové lišty. | Text odejde před klávesovou sekvencí, bez duplikace. | NOT RUN |
| 190 | SSH — Rozepište composition a použijte akci IME Enter. | Text odejde před Enterem, právě jednou. | NOT RUN |
| 191 | SSH — Rozepište composition a přesuňte fokus do jiného pole nebo záložky. | Rozpracovaný text se dokončí bez Enteru; po návratu se znovu neodešle. | NOT RUN |
| 192 | SSH — U profilu s neškodným startupCommand vyvolejte reconnect a odmítněte jeho potvrzení. | Příkaz se nespustí, starý buffer zůstane viditelný. | NOT RUN |
| 193 | SSH — Potvrďte stejný reconnect po nezávislém ověření host key. | Nová relace spustí potvrzený příkaz právě jednou. | NOT RUN |
| 194 | SSH — Vyčistěte obrazovku Ctrl+L, potom otevřete a zavřete IME. | Při kurzoru nahoře se nevrátí staré smazané řádky; prompt zůstává konzistentní. | NOT RUN |
| 195 | SSH — Při pomalém navazování SSH přejděte na jinou záložku ještě před zobrazením TOFU nebo autentizačního promptu. | Banner upozorní na čekající prompt a umožní návrat; aplikace jej drží nejvýše 180 s, server smí ukončit přihlášení dříve. | NOT RUN |
| 196 | Wi-Fi — Bez přijetí nového datasetu zkontrolujte doporučení přibližně po 4 min 59 s. | Dataset je označen starý, doporučení a graf jej stále používají. | NOT RUN |
| 197 | Wi-Fi — Totéž zkontrolujte po 5 min 1 s. | Doporučení a graf obsazení starého datasetu zmizí; UI vysvětlí expiraci (R6). | NOT RUN |
| 198 | Wi-Fi — Po expiraci vyvolejte pouze cache/odmítnutý scan bez nových výsledků. | Čas datasetu se neposune dopředu a doporučení se nevrátí jako čerstvé. | NOT RUN |
| 199 | Wi-Fi — Po expiraci přijměte skutečně nový scan. | Stáří a doporučení se obnoví podle nových dat, nikoli pouhým stiskem Scan. | NOT RUN |
| 200 | Wi-Fi — Ručně označte a odznačte vlastní druhé rádio jako Moje AP. | Změní se příspěvek ke skóre; AP zůstává v seznamu/grafu a výběr historie se nemaže. | NOT RUN |
| 201 | Wi-Fi — Výslovně zapněte vyloučení stejného SSID vlastní připojené mesh sítě. | Vlastní rádia se stejným neprázdným SSID nejsou skórována; po vypnutí se vrátí, neznámý vlastník se nehádá. | NOT RUN |
| 202 | Wi-Fi — V pásmu s alespoň sedmi viditelnými AP zkontrolujte graf; není-li dostupné, uveďte NOT RUN. | Nejvýše šest odlišných barev/tvarů má odpovídající číslovanou legendu; počet ostatních je viditelný a úplný seznam zůstává. | NOT RUN |
| 203 | Wake-on-LAN — Probuďte vlastní zařízení, které na probe odpovídá již před odesláním. | Zobrazí se odpověď již před odesláním; magic packet se přesto odešle, stav netvrdí kauzálně prokázané probuzení. | NOT RUN |
| 204 | Wake-on-LAN — Během ověřování upravte cílové zařízení. | Formulář je dostupný; po uložení změny stará kontrola skončí a její opožděný výsledek se nezapíše k novým datům. | NOT RUN |
| 205 | Wake-on-LAN — Během ověřování smažte cílové zařízení. | Záznam zmizí; pozdní výsledek jej neobnoví ani neovlivní jiné zařízení. | NOT RUN |
| 206 | Wake-on-LAN — Během ověřování změňte Wi-Fi při zapnutých mobilních datech/VPN. | Kontrola nepřejde na novou síť ani mobilní fallback; původně vybraný Network je neměnný, nedostupnost se přizná. | NOT RUN |
| 207 | Wake-on-LAN — Během kontroly změňte řazení nebo importujte změnu jiného zařízení. | UI není blokované kontrolou; nezměněný cíl zůstává přiřazen správně. | NOT RUN |
| 208 | Wake-on-LAN — Vyzkoušejte AUTO broadcast na vlastním přijímači. | Capture dokládá vypočtený broadcast, 102 B a správnou MAC. | NOT RUN |
| 209 | Wake-on-LAN — Vyzkoušejte CUSTOM IPv4 na vlastním přijímači. | Capture dokládá nakonfigurovaný cíl a 102 B. | NOT RUN |
| 210 | Wake-on-LAN — Vyzkoušejte LIMITED 255.255.255.255 na vlastní testovací Wi-Fi. | Capture/absence doručení se zaznamená odděleně od úspěšného odeslání socketem. | NOT RUN |
| 211 | Wake-on-LAN — Zapněte AP izolaci ve vlastní testovací síti a po testu ji vraťte. | Odeslání není vydáváno za doručení/probuzení, které může izolace blokovat. | NOT RUN |
| 212 | Wake-on-LAN — Použijte vlastní běžné DNS jméno přes Wi-Fi. | DNS i UDP používají stejnou Wi-Fi a capture dokládá cílovou IPv4. | NOT RUN |
| 213 | Wake-on-LAN — Použijte vlastní testovací NXDOMAIN. | Konkrétní chyba DNS, žádný dodatečný paket po chybě. | NOT RUN |
| 214 | Wake-on-LAN — Použijte ve vlastní izolované síti nedostupný DNS a operaci zrušte. | Časový limit/zrušení jsou rozlišené; pozdější resolver neodešle paket. | NOT RUN |
| 215 | Wake-on-LAN — Použijte vlastní .local jméno. | Zapište skutečný výsledek a OS/Resolver; při nepodpoře IPv4 nebo běžné DNS, ne falešná garance .local. | NOT RUN |
| 216 | Wake-on-LAN — Ve všech polích včetně poznámky vyvolejte skutečnou IME a pište. | Pole i Uložit lze posunout nad klávesnici; neúspěšný fokus je FAIL, nikoli přeskočení testu. | NOT RUN |
| 217 | Wake-on-LAN — Ve změněném draftu stiskněte Back při IME a znovu po jejím skrytí, odmítněte zahození. | Nejprve se skryje IME, potom dialog; odmítnutí zachová draft. | NOT RUN |
| 218 | Wake-on-LAN — Otočte telefon během odesílání, potom skutečně opusťte aplikaci. | Rotace zachová stav, odchod zruší běžící odesílání; zapište přijatý počet paketů. | NOT RUN |
| 219 | Wake-on-LAN — Importujte syntetický JSON s neznámým polem nebo nepodporovaným schématem. | Celý import je odmítnut konkrétním důvodem; původní seznam beze změny. | NOT RUN |
| 220 | Wake-on-LAN — Importujte syntetický JSON s nadlimitním názvem/poznámkou nebo hostem. | Celý import je odmítnut před náhledem a uložením. | NOT RUN |
| 221 | Wake-on-LAN — Importujte soubor s duplicitním ID. | Celý import je odmítnut; žádný tichý výběr posledního záznamu. | NOT RUN |
| 222 | Wake-on-LAN — Importujte platný soubor s různými ID a stejnou MAC. | Náhled obsahuje varování o duplicitní MAC; bez potvrzení se nic neuloží. | NOT RUN |
| 223 | Wake-on-LAN — Importujte náhled s DNS cílem a veřejnou/dokumentační IPv4, nepotvrzujte jej. | Náhled upozorní na cíl mimo soukromou IPv4; DNS se při náhledu neřeší a sonda se nespustí. | NOT RUN |
| 224 | Wake-on-LAN — Importujte platný UTF-8 soubor s BOM a samostatně poškozený UTF-8. | BOM se přijme; neplatné kódování odmítne celý soubor s rozlišeným důvodem. | NOT RUN |
| 225 | Wake-on-LAN — Zrušte přenos SAF během načítání nebo pomalého exportu, pokud provider scénář umožňuje. | UI dovoluje zrušení, stará operace nepřepíše novou zprávu; při neúspěšném exportu proběhne pokus smazat vytvořený dokument. | NOT RUN |
| 226 | LAN/WoL — Klepněte na text každého checkboxu, poté jej použijte s TalkBack. | Celý řádek přepíná stav právě jednou, čte se jedna položka s rolí checkboxu a aktuálním stavem. | NOT RUN |
| 227 | LAN — Během SAF exportu změňte výsledky nebo nástroj a dokončete picker. | Callback dokončí správný snapshot/formát; překreslení položky neztratí čekající výsledek. | NOT RUN |

Rozcestník 06 (interní záznam) odkazuje pouze sem; druhá editovaná kopie neexistuje.

Původní ID zůstávají dohledatelná: mechanické kontroly F1–F12 (134–145) jsou
sloučené s povinným výsledkem každé klávesy. Řádky **103, 164, 182** nejsou
povinné ruční telefonní kroky: běžné UI neumí vytvořit export nad 8 MiB ani bezpečně
poškodit privátní úložiště. Scénáře zůstávají povinné v automatizovaném ověření:
103 — `LanExportLimitsTest` a úklid neúspěšného SAF exportu;
164 — poškozené SSH metadata, explicitní obnova a shoda archivovaných bytes
(`SshRevisionTest.corruptMetadataIsArchivedVerbatimAndValidMetadataIsNotReset`);
182 — poškozené WoL úložiště, odmítnutí/potvrzení obnovy, archiv původních bytes
(testy úložiště/controlleru). U 163 telefon dokládá dostupné chování, nikoli
forenzní smazání Keystore. Automatizované výsledky s APK jsou v reportu, ne v této
tabulce. Žádný takový přesun není telefonní PASS.

## 10. Izolovaný M1 runner na emulátoru

`tools/run_m1_checks.py` spouští jednu fázi na explicitním emulátoru:
`ui`, `wifi-denied`, `wifi`, `wifi-freshness`, `lan`, `lan-confirm`, `lan-discovery`,
`lan-icmp-rtt`, `ssh`, `ssh-ui`, `ssh-reconnect`, `ssh-rotated`, `ssh-legacy`, `ssh-lifecycle`,
`wol-e2e`, `wol-ime-matrix`. Poslední dvě fáze podléhají rozpočtu R2 v §7.

Výchozí `m1_provision.py run-all --phases` je přesně
`ui,lan,lan-confirm,wifi,wifi-denied,ssh,ssh-ui,ssh-rotated,ssh-legacy,wol-e2e`.
Neobsahuje discovery, měření ICMP, syntetické Wi-Fi UI, reconnect ani lifecycle; pro zadaný
rozsah vždy určete seznam výslovně. Výchozí seznam není doklad celého N7-403.
APK, fixture `files/m1-fixture.json`, klientský klíč `files/m1-client-key`
a loopback servery připravuje a uklízí `tools/m1_provision.py`. Samotný runner
je neinstaluje ani nepřepisuje. Každý běh používá nový 32znakový lowercase hex
`runId` a dosud neexistující adresář důkazů.

Příklad jedné fáze s předem připravenou fixture (nové run ID a cesta pro každý běh):

```bash
python3 -I tools/run_m1_checks.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial emulator-5556 \
  --git-sha "$(git rev-parse HEAD)" \
  --expect-apk-sha256 "$NETKIT_APK_SHA256" \
  --expect-test-apk-sha256 "$NETKIT_TEST_APK_SHA256" \
  --phase lan \
  --run-id 0123456789abcdef0123456789abcdef \
  --output-dir artifacts/m1/lan-0123456789abcdef0123456789abcdef
```

`NETKIT_APK_SHA256` a `NETKIT_TEST_APK_SHA256` jsou předem vypočtené SHA-256 app a test APK určených k testu.
Runner před instrumentací přečte obě nainstalované APK přes `pm path` a
`exec-out cat`, spočítá SHA-256 a získá `versionCode`/`lastUpdateTime`. Identita,
`--git-sha` a serial se zapíšou do `summary.json` před spuštěním;
`--expect-apk-sha256` vynutí shodu app APK a `--expect-test-apk-sha256` test APK; oba přepínače jsou volitelné v CLI, pro finální důkaz je zadejte oba. Git SHA je deklarace volajícího,
shodu zdrojů s APK musí navíc doložit build report.

Instrumentace používá `am instrument -w -r`, limit 125 s; `ssh-lifecycle`
a obaly `wol-e2e`/`wol-ime-matrix` mají 395 s. WoL spouští existující `run_wol_e2e.py` a vyžaduje
jeho write/read, packet listener i restart. Příkazy a serial jsou v
`adb-commands.log`, výsledky v `instrumentation.log` a `summary.json`.
Exit 0 vyžaduje exit 0 ADB, `netkit.result=PASS`, shodu phase/runId, právě jeden
`INSTRUMENTATION_CODE: -1` a všechny markery fáze. Exit 3 znamená SKIP,
exit 1 FAIL, exit 2 chybu argparse. SKIP není PASS. Runner neprovádí retry.
Při timeoutu/přerušení ukončí pouze vlastní hostitelský klientský proces,
dočte jeho výstup s náhradou neplatného UTF-8 a až pět sekund pozoruje
`pidof cz.netkit.app`. Každé pozorování zaznamená; samotný existující PID
neprokazuje běžící instrumentaci. Přímé M1 fáze aplikaci ani SSH relace násilně neukončují; podřízený izolovaný WoL runner má vlastní úklid testovaného procesu popsaný v §7.
Log a konečné summary zapisuje v `finally` i při timeoutu/přerušení.

Wi-Fi vyžaduje aktivní fokusovanou Activity. `wifi-denied` běží bez location
oprávnění, `wifi` po jejich udělení a se zapnutou polohou. Parser připustí SKIP
jen s důvodem a markerem `THROTTLED_CONFIRMED`; aktuální instrumentace nemá
spolehlivý důkaz throttlingu, proto prázdná data/chyba scanu vedou k FAIL.
`wifi-freshness` vykresluje produkční UI nad označenými syntetickými daty (pět
stavů stáří/cache a šest grafických stylů); není důkaz RF scanu ani OEM throttlingu.
`lan-icmp-rtt` měří dvacet ICMP odpovědí hostu a uvádí jednotlivé RTT i min/avg/max.
`lan-confirm` ověřuje skutečný ViewModel, zamítnutí změněného cíle a sondu až po
potvrzení; `lan-discovery` měří 127.0.0.0/24, nikoli fyzickou LAN. `ssh-ui`
rozlišuje kliknutí TOFU od ověřeného uloženého klíče a používá dotykové vyvolání
fokusu/viditelné IME přes `input tap` a psaní přes `input text`. Poslední příkaz
injektuje key eventy, není důkazem `InputConnection.commitText` nebo composition;
nevznikne-li fokus/IME, test selže. Prvky se kontrolují na viditelnost a překrytí
IME. `ssh-reconnect` navíc ověřuje banner čekajícího promptu, odmítnutí reconnectu
se zachováním starého bufferu a potvrzené nové připojení. `ssh-lifecycle` ověřuje HOME/návrat i zavření po pěti minutách.
`ssh-rotated` zachovává původní klíč a nepřipustí autentizaci změněnému serveru;
`ssh-legacy` zkouší odmítnutí zakázaných algoritmů.

Orchestrátor ověřuje AVD `netkit-api35`, dokončený boot a nezamčené okno,
používá `umask 077` a křížově kontroluje fixture log (metoda autentizace,
banner, resize, uzavření; pro SSH také počty `SSH_INPUT_BYTES`, `SSH_COMMAND_OK`
a `AUTH_FAILED` podle fáze, bez výpisu vstupního textu). Každé opakování má vlastní fixture server s limitem
600 s a log `fixture-<phase>-<repeat>.log`; `summary.json` odkazuje na příslušný
log a offset. Pětiminutový lifecycle tak nezávisí na zbývající životnosti
serveru předchozího opakování. První FAIL včetně chybějící fixture evidence sérii
okamžitě zastaví; další fázi nespouští ani po timeoutu/abortu. `run-all` sám neodstraní provisioning; `cleanup`
spusť i po neúspěchu. Odstraňuje pouze vlastní ingress soubory a dočasnou fixture,
neprovádí `pm clear`. Hostitelský soukromý adresář `/tmp/netkit-fixture-*` se uklízí
ve `finally` i při nedostupném/zamčeném emulátoru; neúspěšný úklid zařízení ponechá
netajný receipt pro další pokus. Neodstraňuje historické adresáře `bootstrap-<uuid>` (T11).

```bash
python3 -I tools/m1_provision.py provision \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5556 \
  --state-dir artifacts/n7/final/runtime
python3 -I tools/m1_provision.py run-all \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5556 \
  --state-dir artifacts/n7/final/runtime --repeats 3 \
  --git-sha "$(git rev-parse HEAD)" --expect-apk-sha256 "$NETKIT_APK_SHA256" \
  --expect-test-apk-sha256 "$NETKIT_TEST_APK_SHA256" \
  --phases ui,lan,lan-confirm,lan-discovery,lan-icmp-rtt,wifi,wifi-denied,wifi-freshness,ssh,ssh-ui,ssh-reconnect,ssh-rotated,ssh-legacy
python3 -I tools/m1_provision.py cleanup \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5556 \
  --state-dir artifacts/n7/final/runtime
```

Příklad výše záměrně nespouští vyčerpané R2 znovu. `ssh-lifecycle` přidejte,
pokud se měnila politika/životní cyklus; jeho pětiminutový limit se nezkracuje.
Po neúspěchu musí následovat samostatný `cleanup`, ne bezhlavé pokračování série.
Výsledky, přesné příkazy a omezení patří do interní pracovní záznam.
Tři průchody jsou platná série jen nad shodným app i test APK a fixture bez zásahu
mezi běhy; neúspěch, oprava a nová série musí být popsány odděleně (TI2).
Historické pokusy o probuzení/zrušení keyguardu a swipe na sdíleném AVD
nejsou důkaz odemčení; revize 05 upozorňuje na neúplnost popisu v reportu 04.
V novém běhu se sdílený AVD nepoužívá. M0 F3 zůstává historicky otevřené.

Lokální kontroly infrastruktury (bez komunikace s emulátorem):

```bash
python3 -I -m unittest discover -s tools -p 'test_*.py' -v
pyright tools/
agent-semgrep tools
```

Semgrep zde pokrývá základní Python pravidla, nikoli Kotlin. V fixture zůstávají
lokální `pyright: ignore` pro chybějící typové informace závislostí/dynamické
atributy serveru (T16); čistý Pyright není důkaz jejich typové kontroly.

## 11. Fyzický checklist M2 — vzhled, Signál, Spektrum, Zařízení, import SSH

**Telefon: všechny řádky NOT RUN.** Stejná pravidla jako §9 (jen vlastní sítě a zařízení,
APK instaluje správce, do výsledků žádná hesla, klíče ani zákaznická data). Zapište SHA-256 APK.

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| M2-01 | Nainstalujte M2 přes M1 se známými WoL a SSH daty. | Data zůstala, ikona „Trasa“ je v launcheru v kruhu i v tematické (monochromní) podobě. | NOT RUN |
| M2-02 | Projděte všech pět záložek ve světlém a tmavém režimu a při písmu 200 %. | Nic se nepřekrývá ani neořezává; hlavička, čipy a navigace čitelné. | NOT RUN |
| M2-03 | Trasa na vlastní veřejný cíl (jako v nálezu z 26. 10.). | Hop 1 je router, ne adresa telefonu; odpovídající hopy mají odezvu se štítkem echo; cíl ukončí trasu. | NOT RUN |
| M2-04 | Signál: choďte s telefonem po bytě 2 minuty. | Graf RSSI se plynule mění, hodnota odpovídá WiFimanu ±3 dB; při přechodu mezi AP mesh přibude záznam roamingu. | NOT RUN |
| M2-05 | Signál → Propustnost během stahování velkého souboru. | Graf RX roste; poznámka „za celé zařízení“ je vidět. | NOT RUN |
| M2-06 | Signál → Latence; pak odpojte WAN routeru. | Brána odpovídá, internet hlásí ztrátu; po obnovení se graf vrátí. DNS latence se aktualizuje po ~10 s. | NOT RUN |
| M2-07 | Změňte cíl internetové latence na neplatnou adresu a pak na vlastní. | Neplatná adresa je odmítnuta s vysvětlením, platná se použije a graf se vynuluje. | NOT RUN |
| M2-08 | Odejděte ze Signálu na jinou záložku a na plochu. | Měření se zastaví (žádné ICMP v packet capture routeru). | NOT RUN |
| M2-09 | Spektrum 2,4 a 5 GHz, porovnejte s WiFimanem. | Lichoběžníky na stejných kanálech a šířkách, počty v pásmech sedí, připojené AP zvýrazněné. | NOT RUN |
| M2-10 | Zdraví kanálů na vlastním routeru, který vysílá BSS Load (UniFi/MikroTik s WMM). | U kanálu se ukáže vytížení v %; hodnocení odpovídá řádově WiFimanu. Router bez BSS Load ukáže jen překryv. | NOT RUN |
| M2-11 | Zařízení na vlastní síti poprvé. | Zobrazí se dotaz „Je to vaše síť?“; nic se neskenuje před odpovědí. | NOT RUN |
| M2-12 | Odpovězte Ano, odejděte a vraťte se po 1 min a po 6 min. | Po 1 min se sken neopakuje, po 6 min proběhne automaticky; brána je první a má štítek. | NOT RUN |
| M2-13 | Připojte se k cizí/hostovské síti (vlastní testovací SSID). | Znovu dotaz, žádný automatický sken; po „Ne“ zůstává jen ruční obnovení. | NOT RUN |
| M2-14 | Detail Windows/Samba stroje a NAS. | NetBIOS jméno (např. NAS-DEMO), PTR jméno, ping 10 vzorků se ztrátou. | NOT RUN |
| M2-15 | Detail → Skenovat porty, pak Zrušit během skenu. | Najde otevřené porty z výchozí sady; Zrušit zastaví jen porty, ping běží dál. | NOT RUN |
| M2-16 | Detail hosta s portem 22 → Otevřít SSH. | Přepne na SSH a otevře nový profil s vyplněným hostem. | NOT RUN |
| M2-17 | Detail → Trasa. | Přepne na Nástroje → Trasa s vyplněným cílem. | NOT RUN |
| M2-18 | SSH → Klíče: vygenerujte Ed25519, „Sdílet veřejný klíč“ na PC. | Sdílí se jen řádek `ssh-ed25519 …`, nikdy privátní část. | NOT RUN |
| M2-19 | Na build-hostu `tools/netkit_ssh_export.py install-pubkey --key <soubor.pub> --only <alias>` a pak `export`. | Klíč přibude do `authorized_keys` jen u potvrzených aliasů; vznikne `~/netkit-ssh-profiles.json` s právy 600. | ČÁSTEČNĚ: `install-pubkey` PASS 2026-10-27 (správce; `pve`, `pbs`, `docker105`, `jellyfin`, každý host potvrzen, klíč přidán 1×). `export` a import v aplikaci NOT RUN. |
| M2-20 | SSH → menu → Importovat profily z PC, vyberte soubor. | Náhled ukáže nové, změněné a přeskočené aliasy (ProxyJump apod.) a nabídne klíč telefonu; nic se neuloží před potvrzením. | NOT RUN |
| M2-21 | Potvrďte import a připojte se k importovanému aliasu klíčem. | Přihlášení bez hesla; existující profily si ponechaly svá hesla a startovací příkazy. | NOT RUN |
| M2-22 | Zkuste import při otevřené relaci. | Import odmítnut s vysvětlením, nic se nezmění. | NOT RUN |
| M2-23 | Po importu smažte soubor `netkit-ssh-profiles.json` z telefonu i PC. | Soubor obsahuje hostnames a uživatele; neponechávat ho ve sdílených složkách. | NOT RUN |
| M2-24 | Na PC změňte u aliasu s uloženým heslem v telefonu host, port nebo uživatele, `export` a import do telefonu; druhý alias s heslem nechte beze změny. | Náhled u změněného aliasu varuje „Uložené heslo se odpojí a smaže; při připojení ho zadáte znovu.“, u nezměněného ne. Po potvrzení se připojení ke změněnému aliasu zeptá na heslo (staré se novému cíli nepošle), nezměněný alias se připojí s uloženým heslem. | NOT RUN |
| M2-30 | Zařízení na vlastní síti s Macem/iPhonem nebo Linuxem (Avahi): Obnovit a počkat ~5 s po dokončení. | Během identifikace je vidět „zjišťuji jména a služby…“; u zařízení přibude jméno `….local` se štítkem mDNS (pokud nemá NetBIOS). | NOT RUN |
| M2-31 | Detail tiskárny / Chromecastu / Apple TV → panel Identifikace. | Služby mDNS obsahují odpovídající typy (`_ipp._tcp`, `_googlecast._tcp`, `_airplay._tcp`); všechny tři řádky jmen jsou vidět, chybějící jako „nezjištěno“. | NOT RUN |
| M2-32 | Detail routeru (MikroTik/UniFi s UPnP) a chytré TV / NAS s UPnP. | Sekce UPnP: název, výrobce, model, typ, Server (SSDP); u routeru bez UPnP sekce chybí a je vidět „neodpovědělo na mDNS ani SSDP“, pokud neodpovídá ani mDNS. | NOT RUN |
| M2-33 | U zařízení s webovým rozhraním v UPnP klepněte „Otevřít v prohlížeči“. | Otevře se prohlížeč s adresou zařízení ve vlastní podsíti; bez klepnutí se nic neotevře. | NOT RUN |
| M2-34 | Packet capture na routeru (MikroTik `/tool sniffer`, filtr UDP 5353 a 1900) při jednom hledání. | Z telefonu odejdou nejvýše ~2× (počet živých/32 + 1) mDNS datagramů a 2× M-SEARCH, TTL M-SEARCH = 1; žádný mDNS/SSDP provoz mimo Wi-Fi rozhraní. | NOT RUN |
| M2-35 | Tentýž capture, filtr TCP na porty z LOCATION UPnP zařízení. | Jen HTTP GET na adresu odesílatele SSDP odpovědi, `HTTP/1.0`, `Connection: close`, nejvýše 8 souběžně; žádný redirect se nesleduje. | NOT RUN |
| M2-36 | Během identifikace stiskněte Zrušit, nebo odejděte na jinou záložku. | Indikátor zmizí, další mDNS/SSDP pakety ani HTTP požadavky už neodcházejí (capture). | NOT RUN |
| M2-37 | Cizí/hostovská síť s odpovědí „Ne“ (automatika vypnutá), bez ručního Obnovit. | Žádné mDNS ani SSDP pakety z telefonu; po ručním Obnovit proběhne identifikace jen v rozsahu podsítě. | NOT RUN |
| M2-38 | Zapněte mobilní data, vypněte Wi-Fi uprostřed identifikace. | Identifikace skončí (případně hláška o změně sítě), žádný mDNS/SSDP/HTTP provoz přes mobilní data. | NOT RUN |
| M2-39 | Síť s VPN aplikací (WireGuard) zapnutou. | Identifikace jde přes Wi-Fi (jako zbytek obrazovky Zařízení), výsledky stejné jako bez VPN, nebo prázdné bez chyby, pokud VPN lokální provoz blokuje. | NOT RUN |
| M2-40 | Signál → segment Rychlost, vyberte `rychlost.zelina.eu` a Spustit test (domácí Wi-Fi). | Fáze latence → stahování → odesílání, živý graf, výsledek s náběhem 2–4 s; kontext ukazuje SSID, pásmo/kanál, RSSI, PHY a ISP. Hodnota stahování řádově odpovídá Ookla/LibreSpeed v prohlížeči na stejném AP (±15 %). | NOT RUN |
| M2-41 | Na routeru (Winbox → Interfaces / Torch) sledujte provoz během M2-40. | Stahování ukazuje 6 souběžných TCP spojení na :443, odesílání 3 (+1 spojení latence); vše přes Wi-Fi rozhraní, nic přes LTE. | NOT RUN |
| M2-42 | Vypněte Wi-Fi (mobilní data zapnutá) a spusťte test. | Test odmítne s hláškou „není připojen k Wi-Fi“; čítač mobilních dat v Androidu se nezmění. | NOT RUN |
| M2-43 | Během stahování odejděte na jinou záložku; zopakujte s Zastavit a s tlačítkem Home. | Test se okamžitě zastaví (na routeru spojení zmizí do ~2 s), stav „zastaveno“, nic se neuloží do historie. Otočení telefonu test nepřeruší. | NOT RUN |
| M2-44 | Během testu vypněte Wi-Fi nebo přejděte mimo dosah AP. | Test skončí hláškou o ztrátě Wi-Fi, výsledek „neúplný“, žádný přechod na mobilní data. | NOT RUN |
| M2-45 | Vyberte M-Lab NDT7 poprvé. | Před testem dialog o zveřejnění IP v datech M-Lab; „Zpět“ nic neodešle (router: žádné spojení na measurement-lab.org); „Rozumím, spustit“ test spustí a příště se dialog neukáže. | NOT RUN |
| M2-46 | Dokončete NDT7 test. | Server (stroj · město), klient vs. pohled serveru (aplikační i TCP rychlost, opakované přenosy, MinRTT, RTT při stahování); stahování řádově jako speed.measurementlab.net / Google test ve stejném čase. | NOT RUN |
| M2-47 | Bufferbloat: test na lince bez SQM a pak se zapnutou frontou (MikroTik queue/CAKE), pokud je k dispozici. | Latence při stahování/odesílání a známka se mezi běhy viditelně liší; bez SQM vyšší nárůst. | NOT RUN |
| M2-48 | Vlastní server: zadejte `http://rychlost.zelina.eu`, pak `rychlost.zelina.eu/backend/`, pak neexistující host. | http odmítnuto s vysvětlením; druhá adresa se uloží jako `https://rychlost.zelina.eu/` (nebo se shoduje s presetem); neexistující host skončí chybou DNS. | NOT RUN |
| M2-49 | Po několika testech zkontrolujte Historii, restartujte aplikaci; smažte historii. | Až 20 posledních výsledků přežije restart, neobsahuje IP/ISP; „Smazat historii“ seznam vyprázdní. Data nejsou v Google záloze (allowBackup=false). | NOT RUN |

## 12. Fyzický checklist M3 — nový vzhled (0.5.1-dev)

Xiaomi 15, tmavý i světlý režim systému. Výsledek PASS / FAIL + poznámka.

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| M3-01 | Projděte všech 5 záložek. | Písmo Manrope, mono jen u IP/MAC/BSSID a terminálu; karty bez rámečků; přechod mezi záložkami plynulý (120 Hz bez škubání). | NOT RUN |
| M3-02 | Signál: chvíli choďte po bytě. | dBm v měřidle je uprostřed (mínus předsazený), kvalita pod obloukem, oblouk a čísla plynule dojíždějí; IPv4 i brána v dlaždici celé; na úzkém displeji nebo s větším písmem se dlaždice zalomí na další řádek, hodnota se neusekne. | NOT RUN |
| M3-03 | Signál: přepínejte Signál / Příjem / Internet v kartách metrik. | Graf vyplňuje šířku podle času (5 min), osa vlevo, druhá série čárkovaná; žádný druhý segmentový přepínač. | NOT RUN |
| M3-04 | Rozbalit → otočte telefon proti směru hodinových ručiček. | Graf na šířku čitelně; okno 1/5/15 min, zoom −/+, sevření dvěma prsty, tažení ukáže kurzor s hodnotou a časem; vpravo min/průměr/max a seznam událostí. | NOT RUN |
| M3-05 | 5 min na Signálu, 10 min jiná záložka, zpět, rozbalit. | Za pauzu prázdné místo v grafu; časy v bublině a u událostí odpovídají skutečnému stáří. | NOT RUN |
| M3-06 | V rozbaleném grafu posuňte okno do historie (‹ ›) a počkejte 30 s. | Zobrazený úsek se nehýbe, i když přibývají vzorky. | NOT RUN |
| M3-07 | Vyvolejte roaming (přechod k jinému AP) nebo změňte cíl latence na nedosažitelnou IP. | V grafu barevná čárkovaná značka (roaming modrá, ztráta červená); událost v seznamu s časem. | NOT RUN |
| M3-08 | Rychlost: Spustit test (zelina). | Ciferník s ryskami, ručička nepřekrývá popisky, hodnota **pod** ciferníkem; fáze latence → stahování → odesílání s linkou průběhu; po testu latence pod zátěží a Průběh. | NOT RUN |
| M3-09 | Spektrum: 2,4 a 5 GHz, Zdraví kanálů, klepněte na buňku. | Čipy pásem se vejdou; tečka u pásma připojeného AP; tepelný pás místo seznamu, detail kanálu pod pásem; klepnutí na síť ji rozbalí (BSSID, OUI, Moje AP jen v rozbalení). | NOT RUN |
| M3-10 | Spektrum: vyberte jiné pásmo, pak znovu pásmo s tečkou. | Návrat k automatické volbě pásma připojeného AP. | NOT RUN |
| M3-11 | Zařízení: seznam a detail NASu. | Porty jako mřížka „N ze 105“, identifikace jen zjištěné údaje + „Nezjištěno: …“, akce Trasa a SSH. | NOT RUN |
| M3-12 | SSH: profily, klíč, relace s barevným výstupem (`ls --color`). | Terminál JetBrains Mono, tmavý i ve světlém režimu; „Připojit“ funguje jako dřív. | NOT RUN |
| M3-13 | WoL: ⋮ → Importovat, vyberte CSV z aplikace Wake On Lan (Mike Webb). | Náhled 9 zařízení, poznámka „Skupina: Frýdek/doma“, případné varování SecureOn; po potvrzení se zařízení probudí a kontrola portu odpovídá nastavení. | NOT RUN |
| M3-14 | Nastavení → Displej → Velikost písma na maximum, šířka displeje / zobrazení „větší“. | Nic se neořízne: popisky spodní lišty celé, karty metrik se přepnou na řádky, „Wake-on-LAN“ se neláme uprostřed slova. | NOT RUN |
| M3-15 | Vývojářské možnosti → Měřítko animace = vypnuto. | Hodnoty skočí rovnou na cíl, pulz „živě“ ani prstenec WoL neběží, aplikace funguje. | NOT RUN |
| M3-16 | Venku na slunci (jas max). | Čitelné hodnoty a stavy ve světlém i tmavém režimu. | NOT RUN |

## 13. Fyzický checklist M4 (0.6.0-dev)

Xiaomi 15 (Android 15, HyperOS), Testovací SIM s podporou 5G NSA, druhá SIM podle testovacího scénáře, vlastní testovací Wi-Fi.
Mobilní data spotřebují data tarifu, test rychlosti na nich spouštějte vědomě. Cíle jen vlastní
nebo veřejné resolvery (1.1.1.1, 8.8.8.8). Výsledek PASS / FAIL + poznámka.

- M4-01 až M4-09: aktivní síť pro nástroje, rychlost a SSH.
- M4-10 až M4-19: mobilní signál ([mobilni-sit.md](funkce/mobilni-sit.md)).
- M4-20 až M4-37: Přehled (A3): karta cesty, veřejná IP, mobilní karta v UI, latence a DNS řádek, roaming, rychlost na mobilu.
- M4-38 až M4-41: WoL CSV (import, export, zpětný import do Wake On Lan).

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| M4-01 | Vypněte Wi-Fi (jen mobilní data). Nástroje → Ping `1.1.1.1`, potvrďte. | Štítek „Měří se přes · Mobilní data“; odpovědi přicházejí, RTT řádově desítky ms. | NOT RUN |
| M4-02 | Na mobilních datech: Traceroute `8.8.8.8`, pak Porty `1.1.1.1` (`53,80,443`). | Po potvrzení veřejného cíle běží trasa bez varování „Mohlo jít mimo Wi-Fi“; část hopů „bez odpovědi“ je v pořádku; 53/80/443 otevřené. | NOT RUN |
| M4-03 | Na mobilních datech bez VPN: Ping `192.168.0.1`; pak Skener sítě (Zařízení). | Ping: hláška „Adresa je v místní síti, připojte se k Wi-Fi nebo VPN“, nic se neodešle. Skener: hláška o chybějící Wi-Fi, beze změny proti M3. | NOT RUN |
| M4-04 | Na mobilních datech zapněte VPN do domácí sítě (WireGuard na MikroTiku), Ping `192.168.0.1`. | Štítek „VPN“, odpovědi přes tunel. Po vypnutí VPN během pingu: běh skončí hláškou „Síť se změnila…“. | NOT RUN |
| M4-05 | Na Wi-Fi: Ping `192.168.0.1` a `1.1.1.1`, Traceroute `1.1.1.1`. | Štítek „Wi-Fi“, chování jako v M3 (potvrzení veřejného cíle beze změny). | NOT RUN |
| M4-06 | Na mobilních datech: Rychlost → Spustit test (zelina). | Podtitul „Měří se přes: Mobilní data“; před startem dialog „Test spotřebuje přibližně 200 MB mobilních dat“ (bez historie na mobilu). „Zpět“ nic neodešle; „Spustit test“ provede plný test. | NOT RUN |
| M4-07 | Po M4-06 spusťte test znovu; otevřete Historii a Kontext měření. | Odhad v dialogu odpovídá poslednímu výsledku (≈ (stahování + odesílání) × 10 s / 8 × 1,2, zaokrouhleno na 10 MB). V historii u záznamu „Mobilní data“, starší záznamy „Wi-Fi“. Kontext: „Síť: Mobilní data“, bez řádků SSID/RSSI. | NOT RUN |
| M4-08 | Během testu na mobilních datech zapněte Wi-Fi (telefon se připojí doma). | Test skončí „Připojení se během testu odpojilo nebo změnilo“, výsledek neúplný; na Wi-Fi další test bez dialogu o datech. | NOT RUN |
| M4-09 | Na mobilních datech bez VPN: SSH → Připojit k profilu s adresou `192.168.x.x`; pak profil s veřejnou adresou. | U místní adresy v potvrzení varování „Cíl je v místní síti. Bez VPN se z mobilních dat nepřipojíte.“, připojit lze. U veřejné adresy varování není a spojení funguje. | NOT RUN |
| M4-10 | Čistá instalace, otevřete aplikaci a projděte záložky (Přehled zatím neotvírejte). | Žádný dialog k telefonu při startu; v nastavení aplikace je oprávnění „Telefon“ neudělené. | NOT RUN |
| M4-11 | Wi-Fi vypnutá, mobilní data O2 zapnutá, otevřete Přehled. | Karta mobilní sítě: SIM s daty, operátor O2, standard 5G NSA (nebo LTE, když telefon ve stavovém řádku ukazuje LTE), RSRP v dBm, RSRQ a SINR, štítek Data. Hodnota RSRP odpovídá *Nastavení → O telefonu → Stav SIM* (±3 dB). | NOT RUN |
| M4-12 | Na kartě klepněte „Povolit zobrazení obou SIM“ a povolte. | Systémový dialog Telefon; po povolení se do pár sekund objeví SIM 1 i SIM 2, každá s operátorem, standardem a signálem; štítek Data jen u datové SIM. | NOT RUN |
| M4-13 | V nastavení aplikace oprávnění Telefon odeberte, na kartě klepněte „Povolit zobrazení obou SIM“ a zvolte „Nepovolovat“. | Karta dál ukazuje datovou SIM, nabídka povolení zůstane; aplikace nepadá a nežádá znovu sama. | NOT RUN |
| M4-14 | V nastavení přepněte mobilní data na druhou SIM. | Do několika sekund se štítek Data přesune; bez oprávnění karta přejde na novou datovou SIM. | NOT RUN |
| M4-15 | Zapněte režim letadlo, po 30 s ho vypněte. | Stav „Režim letadlo“, signál zmizí; po vypnutí se SIM a signál vrátí bez restartu aplikace. | NOT RUN |
| M4-16 | Projděte se na místo se slabým signálem (sklep, výtah) a zpět. | dBm se mění po schodech (změna ≥ 2 dB, nejvýš jednou za 3 s), kvalita přechází výborný → dobrý → slabší → slabý podle prahů; žádné nesmyslné hodnoty (např. 2147483647). | NOT RUN |
| M4-17 | Zapněte Wi-Fi PODA a otevřete Přehled. | Wi-Fi karta funguje jako dřív; mobilní karta (pokud ji A3 zobrazuje) dál ukazuje SIM a signál. | NOT RUN |
| M4-18 | Zhasněte displej na 1 min, rozsviťte a vraťte se do Přehledu. | Signál se do pár sekund obnoví (při zhasnutém displeji modem reporty neposílá); stáří hodnoty odpovídá. | NOT RUN |
| M4-19 | Roaming (v zahraničí nebo SIM zahraničního operátora), jinak přeskočit a zapsat NOT RUN. | Štítek Roaming u dané SIM, operátor navštívené sítě. | NOT RUN |
| M4-20 | Na Wi-Fi PODA otevřete Přehled (záložka se jmenuje „Přehled“, segment Živě). | Hlavička SSID / „Připojeno · 5 GHz · kanál …“. Karta „Připojení“: Poskytovatel (jméno PODA, veřejná IPv4, případně IPv6, „AS…“) → Přístupový bod (pásmo, kanál) → Tento telefon (192.168.x.x). Linka mezi uzly jemně dýchá. IP odpovídá WiFimanu. | NOT RUN |
| M4-21 | Wi-Fi vypnutá, mobilní data O2, otevřete Přehled. | Hlavička „Mobilní data“ / „O2 · 5G NSA“. Karta cesty: Poskytovatel (O2, veřejná IPv4, štítek „za NAT operátora“) → Tento telefon (adresa z mobilní sítě). Bez uzlu přístupového bodu. | NOT RUN |
| M4-22 | Na M4-21 porovnejte hlavní kartu se *Stavem SIM* v nastavení. | Měřidlo RSRP (rozsah −140…−44), kvalita, vedle SIM 1, O2, štítek 5G NSA/LTE; dlaždice RSRQ, SINR (dB) a IPv4. RSRP ±3 dB proti nastavení. Wi-Fi hlášky („není připojen k Wi-Fi“) se nezobrazují. | NOT RUN |
| M4-23 | Na M4-21 v grafu ponechte „Signál“ 2 min, pak Rozbalit. | Čára RSRP schodovitá (modem hlásí po ≥ 2 dB, nejvýš 1× za 3 s), osa −140…−60; rozbalený graf se stejnými daty, podtitul „O2 · 5G NSA“. | NOT RUN |
| M4-24 | Na mobilních datech přejděte mezi místy s 5G a LTE (nebo vypněte 5G v nastavení sítě). | V grafu svislá oranžová čára „Změna standardu“ (LTE → 5G NSA / 5G NSA → LTE); štítek standardu se změní. | NOT RUN |
| M4-25 | S oprávněním Telefon (M4-12) přepněte datovou SIM v nastavení a vraťte se. | V grafu modrá čára „Přepnutí datové SIM“ („SIM 1 → SIM 2“), měřidlo přejde na novou datovou SIM. | NOT RUN |
| M4-26 | Čistá instalace, Wi-Fi vypnutá, na mobilní kartě klepněte „Zobrazit obě SIM“ a povolte. | Systémový dialog Telefon; po povolení se druhá SIM objeví do 1–2 s (bez čekání na další hlášení). Při odmítnutí místo tlačítka krátká poznámka „Bez oprávnění k telefonu je vidět jen SIM s daty“, aplikace se znovu sama nezeptá. | NOT RUN |
| M4-27 | Na Wi-Fi s oběma SIM otevřete Přehled. | Pod Wi-Fi kartou kompaktní „Mobilní síť“: řádek na SIM („SIM 1 · O2“, standard, dBm a kvalita, štítek „Data“ jen u datové SIM). | NOT RUN |
| M4-28 | V menu karty Připojení (tři tečky) vypněte „Zjišťovat veřejnou IP“; přepněte Wi-Fi ↔ mobilní data; pak zapněte zpět. | Po vypnutí uzel „zjišťování vypnuto“, čárkovaná první linka, žádný dotaz (ověřit na routeru: žádné spojení na 1.1.1.1:443 a žádný TXT dotaz `*.asn.cymru.com`). Po zapnutí se IP načte. Nastavení přežije restart aplikace. | NOT RUN |
| M4-29 | Na Wi-Fi zavřete a do 10 min znovu otevřete Přehled; pak „Obnovit“. | Opětovné otevření bez nového dotazu (cache 10 min, IP hned); „Obnovit“ dotaz zopakuje (krátce „zjišťuji veřejnou IP…“, pak stejné hodnoty). | NOT RUN |
| M4-30 | Na routeru zablokujte 1.1.1.1:443 (dočasné pravidlo jen pro telefon), otevřete Přehled, pak pravidlo zrušte. | Do ~5 s „veřejná IP nedostupná“, aplikace funguje dál; po zrušení pravidla a „Obnovit“ se IP načte. | NOT RUN |
| M4-31 | Na mobilních datech zapněte VPN (WireGuard domů) a otevřete Přehled. | Hlavička „VPN“ / „přes O2 · 5G NSA“; uzel telefonu se štítkem VPN a adresou tunelu; při plném tunelu je veřejná IP adresa domácí linky (PODA), ne O2. | NOT RUN |
| M4-32 | Latence na Wi-Fi a pak na mobilních datech. | Wi-Fi: řádky Brána (192.168.0.1 pod popiskem), Internet (1.1.1.1 pod popiskem), DNS, ztráta „brána / internet“. Mobilní data: bez řádku Brána, Internet a DNS dál měří (hodnoty desítky ms), ztráta a jitter jen „internet“. | NOT RUN |
| M4-33 | Řádek DNS na Wi-Fi bez soukromého DNS, pak *Soukromé DNS* v nastavení (např. `one.one.one.one`). | Bez soukromého DNS pod „DNS“ IP resolveru (192.168.0.1 nebo resolver operátora), pod ní `one.one.one.one`. Se soukromým DNS „Soukromé DNS · one.one.one.one“. | NOT RUN |
| M4-34 | Nastavení písma na největší (1,3) a zobrazení „menší“ (šířka ~360 dp); projděte Přehled na Wi-Fi i mobilu. | Uzly karty cesty pod sebou, IP celé (nezkrácené), řádky Latence s mezerou mezi popiskem a hodnotou, nic se nepřekrývá. | NOT RUN |
| M4-35 | Na Wi-Fi s mesh (dva AP) přejděte mezi AP a mezi pásmy. | Roaming: řádek „před 12 s“ se štítkem „k. 36 → 100“ nebo „2,4 → 5 GHz“, po pauze „po výpadku“; BSSID vpravo; řádek nejvýš na dva řádky. | NOT RUN |
| M4-36 | Rychlost na mobilních datech (po M4-06), otevřete Kontext měření. | Řádky Síť „Mobilní data“, Operátor „O2“, Standard „5G NSA“ (nebo LTE), Signál na začátku v dBm. | NOT RUN |
| M4-37 | Odejděte z Přehledu na jinou záložku a sledujte provoz (router / tcpdump) 1 min. | Žádné ICMP na 1.1.1.1, žádné dotazy na Cloudflare ani `asn.cymru.com`, dokud není Přehled znovu otevřený. | NOT RUN |
| M4-38 | WoL → ⋮ → Importovat (JSON nebo CSV), vyberte export z aplikace Wake On Lan (`wol-….csv`). | Soubor jde vybrat (není šedý); náhled ukáže zařízení, skupiny jsou v poznámce „Skupina: …“; po potvrzení jsou v seznamu včetně IP zařízení. | NOT RUN |
| M4-39 | WoL → ⋮ → Exportovat CSV (Wake On Lan), uložte do Stažených. | Dialog upozorní, co CSV nepřenáší; vznikne `wol-netkit.csv`. | NOT RUN |
| M4-40 | Soubor z M4-39 naimportujte v aplikaci Wake On Lan (Mike Webb) do prázdného seznamu, pak jedno zařízení probuďte. | Aplikace import přijme; jména, MAC, broadcast, port, IP a skupiny sedí; paket zařízení probudí. | NOT RUN |
| M4-41 | Přepněte jazyk telefonu na angličtinu, v NetKitu znovu exportujte CSV. | Skupiny z poznámek „Skupina: …“ zůstanou zachované (nespadnou do „NetKit“). | NOT RUN |

## 14. SSH profily v2 — fyzický checklist (zadání 29)

Pouze správce na fyzickém telefonu. Zapsat
verzi/commit APK, jazyk, písmo/zobrazení, výsledek a případný screenshot.

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| SSH2-01 | Exportovat PASSWORD, KEY a INTERACTIVE s odlišným keepalive a neškodným startovacím příkazem; soubor importovat do čisté testovací instalace. | V2, pořadí a přenosná pole shodná; žádné ID/reference/klíče/důvěra serverů. | NOT RUN |
| SSH2-02 | Importovat starý export telefonu bez referencí a metadat důvěry. | Přenosná pole zachována; nové lokální identity. | NOT RUN |
| SSH2-03 | Importovat aktualizaci všech šesti polí existujícího profilu. | Každé změněné pole před → po, příkaz zvýrazněný; pozice profilu zachována, změna cíle odpojí heslo. | NOT RUN |
| SSH2-04 | Šipkami přesunout profily, zkontrolovat kraje, restartovat aplikaci a exportovat. | Pořadí trvá a souhlasí s JSON; krajní šipky neaktivní, TalkBack oznámí směr i jméno. | NOT RUN |
| SSH2-05 | Importovat nový KEY bez výběru klíče; Připojit a potvrdit. | Výzva k výběru klíče s přechodem do editoru, žádná nová relace ani změna typu na heslo. | NOT RUN |
| SSH2-06 | Opakovat SSH2-05 s jiným existujícím klíčem v telefonu. | Editor i tak explicitně požaduje přiřazení; po výběru lze uložit. | NOT RUN |
| SSH2-07 | V editoru KEY bez klíčů klepnout Vytvořit klíč, vygenerovat Ed25519 a vrátit se. | Rozepsaná netajná pole zůstala; lze klíč vybrat a uložit; neukládá se rozepsané heslo. | NOT RUN |
| SSH2-08 | V Klíčích vygenerovat Ed25519, importovat v1 bez klíče, Upravit → Privátní klíč → vybrat klíč → Uložit. Opakovat import → generování a restart mezi kroky. | Klíč je v editoru viditelný i volitelný, uložení aktivní. Zapsat zvlášť release a DEV/debug APK; případný prázdný blok zachytit včetně vybraného ověřování, klávesnice a velikosti písma. | NOT RUN |
| SSH2-09 | V CS i EN a se zvětšeným písmem projít náhled, editor a šipky. | Čitelné texty, dostupné akce a posuv; nic se neztratí za klávesnicí. | NOT RUN |
| SSH2-10 | Reconnect relace, jejíž KEY profil už nemá existující přiřazený klíč (v izolované testovací instalaci). | Stejná výzva do editoru před transportem; původní relace/výpis se nepřepíše. | NOT RUN |

### Doplnění R1 (revize zadání 29)

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| SSH2-R1-01 | Export dvou profilů stejného jména, pak validních profilů s celkovým JSON nad 256 KiB. | Konkrétní CS/EN hláška (u duplicit jejich jména), SAF se neotevře a nevznikne 0 B soubor; běžné ukládání duplicit funguje. | NOT RUN |
| SSH2-R1-02 | Import v1 s existujícím klíčem; přepínat klíč / bez klíče. | Náhled nového profilu přepíná KEY/PASSWORD stejně jako následné uložení. | NOT RUN |
| SSH2-R1-03 | Importem změnit PASSWORD → KEY/INTERACTIVE a KEY → PASSWORD/INTERACTIVE. | Nepoužívaná reference odstraněna, opuštěné heslo smazáno; při KEY bez přiřazení varování. Samotná změna cíle zachová keyRef. | NOT RUN |
| SSH2-R1-04 | KEY bez přiřazení s jiným klíčem v seznamu; TalkBack nad správou více profilů. | Vytvořit klíč dostupné; Upravit/Smazat oznamují i jméno profilu, monogram jako před změnou v2. | NOT RUN |
| SSH2-R1-05 | Reconnect a souběžná editace/smazání profilu v izolovaném testu. | Použije se potvrzený cíl/příkaz; smazání vyvolá jen chybu, nenabídne editor obnovující profil. | NOT RUN |

### Doplnění malého kola P3 (po merge zadání 29)

| # | Krok | Očekávání | Výsledek |
|---|---|---|---|
| SSH2-P3-01 | Exportovat profily, během SAF ukončit proces aplikace a dokončit výběr cíle. | Export vznikne z aktuálního úložiště i bez připraveného snapshotu. Při duplicitách nebo překročení 256 KiB konkrétní hláška, žádné UNKNOWN; validace před zápisem. | NOT RUN |
| SSH2-P3-02 | Vyvolat chybu exportu / reconnectu smazaného profilu, klepnout OK. | Hláška i její OK zmizí. | NOT RUN |
| SSH2-P3-03 | V CS i EN importovat změnu PASSWORD → KEY/INTERACTIVE s uloženým heslem. | Varování říká jen, že heslo bude odpojeno a smazáno; neslibuje jeho opětovné zadání. | NOT RUN |
