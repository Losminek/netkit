# Funkce: SSH klient

**Stav:** specifikace pro M1d. Náhrada ConnectBotu. Nejnáročnější funkce na bezpečnost
i UX; implementuje se poslední.

## 1. Co umí (M1)

- **Profily hostů**: název, host, port, uživatel, způsob autentizace (heslo / klíč),
  odkaz na klíč, volitelný startovací příkaz, keepalive.
- **Autentizace**: heslo, keyboard-interactive, veřejný klíč. Typy klíčů: Ed25519 (výchozí
  pro generování), ECDSA, RSA (min. 3072 bitů pro generování). Import privátního klíče
  (OpenSSH, PEM) s passphrase; generování klíče v aplikaci; zobrazení/kopírování veřejného klíče.
- **Ověření host key**: první připojení ukáže otisk (SHA256) a vyžádá potvrzení (TOFU);
  uložené otisky; **změna klíče = tvrdé varování, výchozí je odmítnout**.
- **Terminál**: `xterm-256color`, změna velikosti, scrollback (nejvýše 5000 řádků a rozpočet 8 MiB/relaci), barvy,
  řádek přídavných kláves (Esc, Tab, Ctrl, Alt, šipky, Home/End, PgUp/PgDn, `|`, `~`, `/`),
  výběr a kopírování, vkládání, nastavitelná velikost písma.
- **Více relací** vedle sebe (záložky nebo přepínač), čistý odchod (`exit`, zavření záložky).

## 2. Mimo rozsah M1

- Port forwarding (local/remote/dynamic), SFTP/SCP, jump host/ProxyJump, agent forwarding,
  X11, mosh, Telnet, sériová konzole. Zapsat do roadmapy po M1.
- Foreground service je mimo M1. Podle rozhodnutí správce D-7 (2026-10-26) relace
  na pozadí přežívá nejvýše **5 minut**, pak se zavře; systém může proces ukončit dříve.

## 3. Bezpečnostní požadavky

- Hesla a privátní klíče **jen** přes `SecretStore` ([architektura.md](../architektura.md) §6),
  nikdy v databázi/JSON, logu ani v `savedInstanceState`.
- Politika algoritmů: zakázat DSA a `ssh-rsa` se SHA-1, slabé šifry (CBC, RC4, 3DES) a KEX
  (`diffie-hellman-group1`); preferovat curve25519 a AES-GCM před chacha20-poly1305.
  Přesný seznam závisí na zvolené knihovně a zapíše se do `bezpecnost.md`.
- Žádné automatické přijetí neznámého ani změněného host key.
- Nelogovat vstup uživatele ani obsah terminálu; ladicí logy jen bez tajemství a vypnuté
  v release.
- Schránka: při kopírování z terminálu nepřidávat automatiku; vkládání neprovádí příkaz
  automaticky bez Enter (ochrana před paste-jackingem — vícerádkové vložení potvrdit).
- `FLAG_SECURE` v SSH je přepínatelný; podle D-3 výchozí release zapnuto, debug vypnuto.
  Na API 33+ se při stejné volbě vypne i náhled v přehledu aplikací.
- Odemčení klíče biometrikou je M2.

## 4. Technické volby (rozhodne implementátor po rešerši, zápis do `zavislosti.md`)

| Oblast | Kandidáti | Na co se dívat |
|---|---|---|
| SSH protokol | sshj, Apache MINA SSHD, ConnectBot `sshlib`, JSch (fork mwiede) | licence (jen permisivní), poslední aktivita, funkce s Ed25519 a moderními KEX/šiframi, chování s Bouncy Castle na Androidu (Android má vlastní zkrácený provider), velikost |
| Terminálový emulátor + view | `termlib` z ConnectBotu, `terminal-emulator`/`terminal-view` z Termuxu, vlastní minimální | licence jednotlivých modulů (ověřit v repu, ne z paměti), kvalita VT100/xterm, podpora Compose/View, údržba |
| Úložiště klíčů | Android Keystore + AES-GCM v `noBackupFilesDir` | StrongBox dostupnost, export |

Pokud jediná použitelná varianta má nepermisivní licenci, implementátor **zastaví** a předloží
rozhodnutí (D7), nekopíruje kód.

## 5. Kritéria funkce

- Profily a otisky host key se ukládají a čtou; heslo/klíč nikdy mimo `SecretStore`
  (test, že serializovaný JSON neobsahuje tajemství).
- Policy algoritmů je zakódovaná a testovaná (konkrétní algoritmy odmítnuty).
- Proti **testovacímu sshd na hostu** (`10.0.2.2:<port>`, postup v [testovani.md](../testovani.md)):
  přihlášení heslem, přihlášení klíčem, odmítnutí špatného hesla, TOFU potvrzení, odmítnutí
  po změně host key, `ls`/`top` renderuje správně, změna velikosti se přenese, Ctrl-C funguje.
- Zavření záložky ukončí spojení (na straně hosta zmizí relace).
- **Fyzický test (správce):** připojení k reálným strojům (Linux server, MikroTik, Cisco/Huawei
  přes Tabby alternativně), práce v `nano`/`htop`/CLI síťových prvků; porovnat s ConnectBotem
  (klávesy, barvy, škálování) a zapsat rozdíly.

## 6. Implementační stav M1d (2026-10-25)

§1–5 zůstávají specifikací a akceptačními kritérii. Tento oddíl popisuje napsaný kód,
**neoznačuje M1d za přijaté**. Souhrn skutečných běhů testů je v reportu příslušného
řezu v `prace/`; samotná implementace nebo unit test nenahrazuje ověření v emulátoru
ani na správcově telefonu.

| Oblast | Implementace | Zbývající rozdíl / ověření |
|---|---|---|
| Profily | DataStore + JSON, CRUD, host/port/uživatel, heslo/klíč/keyboard-interactive, keepalive, jednorádkový startovací příkaz; export profilů bez tajemství i bez secret/key referencí | Import/export celého úložiště klíčů není součástí; výchozí příkaz se vykoná po explicitním potvrzení připojení |
| Klíče | Generování Ed25519, ECDSA P-256 a RSA 3072; import OpenSSH/PEM s passphrase; veřejný klíč lze zobrazit/kopírovat | Import RSA je konzervativně omezen také na nejméně 3072 bitů; privátní export není v UI |
| Host key | SHA256 fingerprint, explicitní TOFU, atomické vložení nového klíče, přesné připnutí veřejného klíče pro `[host]:port` | Změněný klíč nelze přijmout v připojovacím dialogu. Po nezávislém ověření lze starý záznam explicitně zapomenout; tím se zavřou relace |
| Autentizace | JSch password, publickey, keyboard-interactive; dialog identifikuje lokální profil a `uživatel@endpoint` odděleně od textu serveru | Interoperabilita vendorů a skutečného MFA zůstává fyzickému testu |
| Terminál | Vlastní VT subset: kurzor, mazání, vkládání/mazání znaků a řádků, scroll region, alternativní obrazovka, základní SGR/256/truecolor; PTY resize s debounce, nastavení písma, max. 5000 řádků a 8 MiB kompaktní historie | **Není úplný xterm emulátor:** široké znaky, kombinující Unicode, DEC kreslicí znaková sada a nevyjmenované režimy nejsou implementované. `nano`/`top`/`htop` nelze prohlásit za správně vykreslené pouze z unit testů parseru |
| Vstup | Přímý `InputConnection` bez lokálního echa; Enter, Backspace, Tab, Ctrl/Alt, DECCKM šipky, Home/End, PgUp/PgDn, Insert/Delete, F1–F12, Shift-Tab a Ctrl+šipky. Maskované pole „Text pro terminál“ zůstává jako rozbalitelná záloha | Nová přímá IME cesta musí projít instrumentací s reálným fokusem a viditelnou klávesnicí; interaktivní aplikace ověří správce |
| Schránka | Výběr/kopírování a explicitní kopie výpisu; vložení nejprve do vstupního pole, potvrzení a nahrazení řídicích znaků/nových řádků mezerami | Vícerádkový skript nelze jedním vložením automaticky spustit; uživatel musí jednotlivé příkazy odeslat vědomě |
| Relace | Nejvýše čtyři aktivní relace, zavření jednotlivě/všech, reconnect; standardní Android route včetně VPN; `NetworkCallback` upozorní na možnou ztrátu spojení | Pozadí max. 5 minut, bez foreground service; stav „nejspíš ztraceno“ je varování podle změny sítě, nikoli důkaz TCP disconnect |
| Soukromí obrazovky | `FLAG_SECURE` + recents API33, no-learning/no-suggestions, bez ukládání vstupu do saved state | IME příznaky jsou žádost vůči klávesnici. Biometrika zůstává M2 |

Vybráno `com.github.mwiede:jsch:2.28.0` s Bouncy Castle lightweight API pro moderní
algoritmy na Androidu. Systémový BC provider se nenahrazuje. Licence a oficiální POM
jsou v [evidenci závislostí](../zavislosti.md); přesné allowlisty v
[bezpečnostní politice](../bezpecnost.md#5-politika-ssh-algoritmů).

Pro terminál byl zvolen vlastní parser. Ověřená nejstarší publikovaná varianta
`org.connectbot:termlib:0.0.2` již požaduje novější sadu AndroidX/Compose; její
[POM](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.0.2/termlib-0.0.2.pom)
uvádí Core 1.17.0, Kotlin 2.2.21 a Compose BOM 2025.11.00. Do aktuálně ověřené sady
SDK 35 se proto nepřidala bez samostatného posouzení kompatibility. To není tvrzení,
že `termlib` má nepovolenou licenci: projekt deklaruje Apache-2.0 a libvterm MIT.
Výhodou vlastního parseru je žádná další nativní závislost; cenou je výše uvedená
neúplná emulace, kterou musí další řez vyřešit před tvrzením o plné náhradě ConnectBotu.

## 7. Opravy revize 05 (2026-10-26, stav implementace)

Následující stav nahrazuje odpovídající historické buňky §6; skutečné výsledky testů
jsou v interní pracovní záznam.

- Resize primární obrazovky drží kurzor viditelný a přesune horní řádky do historie;
  zvětšení je vrací jen s kurzorem na posledním řádku. Alternativní obrazovka vlastní historii nemá a nezahazuje uloženou
  primární obrazovku při přepnutí klávesnice. Nepodporované CSI prefixy a mezilehlé
  bajty se ignorují, nejsou interpretovány jako SGR.
- Relace se při otevření pickeru, zamčení či přepnutí aplikace okamžitě nezavírají.
  Na pozadí mají limit **5 minut** (`SshBackgroundPolicy.BACKGROUND_GRACE_MILLIS`),
  měřený monotónním časem včetně uspání. Návrat po limitu také relace zavře, i kdyby
  systém pozdržel coroutine. Ukončení aktivity/ViewModelu a explicitní zavření relace
  je uzavře; dostupná je akce „Zavřít všechny relace“. Android může proces ukončit
  dříve; bez foreground service nejde o garanci pěti minut živého spojení.
  Revize R5 (2026-10-26) opravila také CS/EN text `ssh_network_notice`, který chybně
  sliboval okamžité zavření při odchodu do pozadí. Audit textů v README, této
  specifikaci, architektuře, bezpečnosti a testovacím checklistu potvrdil shodu
  jejich pětiminutové lhůty s konstantou v kódu. Tato kontrola textů nenahrazuje
  runtime ověření životního cyklu.
- Přepínač „Zakázat snímky obrazovky“ používá `FLAG_SECURE`, na API 33+ také zákaz
  náhledu v přehledu aplikací. Výchozí stav: release zapnuto, debug vypnuto (D-3).
- Záložní vstup je maskovaný, bez autocorrect, s `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
  a `IME_FLAG_NO_PERSONALIZED_LEARNING`. Android tyto příznaky popisuje jako požadavek,
  ne záruku chování všech klávesnic. Obsah vstupu se neukládá do saved state.
- Chyby rozlišují změnu klíče, autentizaci, algoritmy, DNS, timeout a nedostupné
  šifrované údaje. Import rozlišuje slabý RSA, DSA, passphrase a formát; nevypisuje
  původní výjimku knihovny ani obsah klíče.
- Preferuje se již uložený typ serverového klíče v rámci uzavřeného allowlistu.
  RSA blob `ssh-rsa` znamená pouze preferenci podpisů RSA/SHA-2, nikoli povolení SHA-1.
  AES-GCM má přednost před chacha20. Strict KEX se nabízí; podpora protistrany se
  nevynucuje. Tím nevzniká garance stejné ochrany u starých serverů.
- Poslední odmítnutý otisk zůstává v metadatech i po zapomenutí důvěry a je vidět
  v potvrzení zapomenutí i dalším TOFU. Poškozené/nepodporované SSH metadata lze
  výslovně potvrzenou akcí atomicky archivovat; IO chyba ani opravený soubor se
  neresetují. Archiv je soukromý a šifrovaná tajemství se při této obnově nemažou.

Oficiální zdroje ověřené 2026-10-26:
[EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING),
[InputType](https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_FLAG_NO_SUGGESTIONS),
[recents screenshot](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)),
[FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE),
[JSch Session](https://github.com/mwiede/jsch/blob/jsch-2.28.0/src/main/java/com/jcraft/jsch/Session.java).

### Doplňující implementace 2a a UI

Přímý vstup je vlastní `DirectTerminalInput` (`TextView` + `BaseInputConnection`).
Dotyk výslovně žádá fokus a soft keyboard; `commitText` posílá text ihned, Enter
samostatný řídicí bajt. Kompozice je dočasná do potvrzení, není ukládána do zařízení;
IME nedostává okolní text terminálu. Víceřádkové/řídicí vložení se potvrzuje a
sanitizuje do záložního pole, nikdy samo nespustí příkaz. Klávesová lišta podporuje
stejné mapování jako hardware klávesnice. DECCKM `?1h/l` mění šipky i Home/End.

Veškeré aplikační textové a resize zápisy sdílejí `TerminalWritePump`, FIFO s 32
položkami. Resize je slučovaný za 100 ms; při zahlcení textové fronty se zobrazí
chyba, nevzniká neomezená fronta. Layout také slučuje změny před změnou lokální mřížky.
Historie ukládá immutable pole znaků, barev a atributů; run objekty vznikají jen
na požádání rendereru. Rozpočet historie je konzervativně `16 × počet buněk + 256`
bajtů na řádek, nejvýše 8 MiB a 5000 řádků. **Jde o rozpočet uchovávaných dat,
nikoli měření celého JVM/Android heapu** (screen, přechodné snapshoty a UI mají režii).
Test používá 6000 řádků po 240 znacích s jiným atributem každé buňky.

Profily/formuláře bez tajemství a velikost písma používají saveable stav. Hesla,
passphrase, terminálový vstup a obsah schránky se do saved state nikdy neukládají.
Akce se zalamují nebo jsou v menu; dlouhé popisy jsou sbalitelné, výzva k přístupu
jen na autorizované servery zůstává viditelná. Stav změněného klíče používá chybové
barvy tématu a otisky monospace s kopírováním. Základní barvy terminálu vycházejí
z tématu, explicitní ANSI/256/truecolor barvy představují data protokolu.

Další oficiální zdroje ověřené 2026-10-26:
[BaseInputConnection](https://developer.android.com/reference/android/view/inputmethod/BaseInputConnection),
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)).

### Opravy vstupu a připojení po revizi 07

- `TerminalComposition` drží pouze dočasný composing text. UTF-16 i codepoint mazání
  zachovává celé surrogate páry; přebytek před lokálním textem odešle Backspace na
  server. Codepoint mazání při neplatném surrogate v rozsahu nic nemění. Množství
  práce jednoho delete požadavku je omezené na 256 znaků v každém směru.
- Před klávesou či akcí terminálové lišty, editor action a při ztrátě fokusu se
  composing text dokončí **bez přidání Enteru**. Také přepnutí fokusu tedy může odeslat
  dosud rozpracovaný text. Uzavření InputConnection text vyčistí; odpojená relace
  nic neodesílá. Víceřádkový/řídicí obsah stále vyžaduje potvrzení vložení.
- Pravý Alt (AltGr) používá znak vypočtený Androidem s plným meta stavem a nepřidává
  terminálový ESC/Ctrl prefix; levý Alt dál funguje jako terminálový Meta. Čisté testy
  pokrývají české symboly `@`, `\`, `|`, `{`, `}` přes injektovaný layoutový resolver;
  skutečné české rozložení Bluetooth klávesnice zůstává fyzickým testem.
- Reconnect nejprve vyžaduje stejné potvrzení cíle a `startupCommand` jako nové
  připojení. Výpis původní relace zůstává viditelný při potvrzení i navazování nového
  spojení; nový terminál jej nahradí až po připojení. Při neúspěchu původní výpis
  zůstane. Generace transportu brání pozdním callbackům starého spojení přepsat nový stav.
- Zvětšení primární obrazovky obnovuje historii jen s kurzorem na posledním řádku;
  smazaná obrazovka po Ctrl+L se tedy při změně IME nenaplní starými řádky. Uložený
  kurzor se posouvá spolu s obnovenými řádky.
- Chyba zápisu nebo změny PTY velikosti je chyba spojení, nikoli běžný konec. První
  konkrétní příčina se zachová i přes následný timeout či zavření socketu. Odmítnutí
  neznámého klíče má vlastní kategorii; timeout dialogu změněného klíče zůstává změnou
  klíče. Aktuálně nabídnutý klíč není duplicitně označen jako předchozí odmítnutí.
- TOFU a autentizační dialogy mají limit **180 s** (`SshPromptPolicy`). Celý handshake
  má rozpočet **600 s = 3 × 180 s + 60 s síťové rezervy**. Jde o horní hranici, server
  může přihlášení ukončit dříve. DNS má zvlášť 10 s; vlastní socket factory má 15 s
  celkem a nejvýše 5 s pro jednu IP. `Session.connect(20000)` nastavuje během handshake
  socket read timeout 20 s, ne 20s limit pro uživatelský dialog. Navázání shell kanálu
  má vnější limit 20 s a JSch limit 15 s. Pětiminutová politika pozadí se nemění.

Tyto změny mají regresní testy v `TerminalCompositionTest`, `TerminalBufferTest`,
`SshPromptPolicyTest`, `SshConnectionOutcomeTest` a `SshAttemptGateTest`. Stav skutečně
provedeného buildu a emulátorových/telefonních scénářů patří do reportu 08;
existence testu není tvrzením o jeho průchodu.

Oficiální zdroje pro tuto opravu ověřené 2026-10-26:
[InputConnection — jednotky mazání a surrogate páry](https://developer.android.com/reference/android/view/inputmethod/InputConnection),
[KeyEvent — levý/pravý Alt a výpočet znaku](https://developer.android.com/reference/android/view/KeyEvent),
[JSch 2.28.0 Session.connect — socket timeout](https://github.com/mwiede/jsch/blob/jsch-2.28.0/src/main/java/com/jcraft/jsch/Session.java).

## 8. Import profilů z PC

Tento oddíl popisuje formát v1 z PC skriptu. Importní dialog nyní přijímá
také v2 a starý export telefonu; jejich přenos nastavení a omezení popisuje §9.

PC skript `tools/netkit_ssh_export.py` (jen standardní knihovna Pythonu 3.12) převede
aliasy z `~/.ssh/config` na importní soubor `netkit-ssh-profiles` v1 (formát je závazně
popsaný v interní pracovní záznam) a volitelně rozdá veřejný klíč telefonu.
Spouští se na vlastním PC, ne v repozitáři ani v CI.

Při změně hostu, portu nebo uživatele existujícího profilu import odpojí uložené heslo a po úspěšném zápisu ho smaže; při dalším připojení ho uživatel zadá znovu. Pokud smazání šifrovaného souboru selže nebo proces skončí mezi zápisem profilů a smazáním, zůstane soubor bez reference: nic ho nepoužije, ale ani neuklidí (stejně jako dnes při selhání mazání u úpravy nebo smazání profilu). Úklid osiřelých tajemství je otevřený bod pro další milník.

### Doporučený postup

1. V aplikaci vygenerovat Ed25519 klíč s komentářem např. `netkit@xiaomi15` a veřejnou
   část uložit/přenést na PC jako soubor `.pub`.
2. Rozdat veřejný klíč na hosty:
   `python3 -I tools/netkit_ssh_export.py install-pubkey --key ~/netkit.pub --only pve,pbs`
   (nebo `--all`). U každého hosta se zeptá `Přidat klíč na <alias> (<user>@<host>:<port>)? [y/N]`,
   `--yes` dotaz vynechá, `--dry-run` jen vypíše příkazy `ssh-copy-id -f -i <klíč> <alias>`.
   Heslo/2FA se zadává v terminálu; limit 120 s na hosta, nenulový návratový kód při selhání.
   `-f` znamená, že ssh-copy-id nekontroluje, zda klíč na serveru už je (bez privátní části
   to neumí); opakované spuštění tedy přidá duplicitní řádek do `authorized_keys`.
3. Vytvořit importní soubor:
   `python3 -I tools/netkit_ssh_export.py export --out ~/netkit-ssh-profiles.json`
   (výchozí podpříkaz i cesta; `--only a,b`, `--config`, `--default-user`, `--source`).
4. Soubor přenést do telefonu, v aplikaci importovat (nové profily dostanou vybraný klíč)
   a soubor na PC i v telefonu smazat.

### Co skript dělá

- Aliasy hledá v řádcích `Host` včetně `Include` (glob, relativní cesty vůči `~/.ssh` jako
  OpenSSH, hloubka max. 16, ochrana proti cyklu). Vzory s `*`, `?`, `!` jdou do `skipped`
  jako `WILDCARD` (`Host *` se nevypisuje).
- Každý alias vyhodnotí `ssh -G <alias>` (bez připojení, bez shellu, limit 5 s). Tím platí
  `Match`, `Include` i výchozí hodnoty přesně jako u OpenSSH. Pozor: `Match exec` v configu
  se při tom spustí. `ssh -G` vždy doplní uživatele (místní login), proto `--default-user`
  se uplatní hlavně ve zjednodušeném vlastním resolveru, který se použije, když `ssh` chybí
  (první shoda vyhrává, `Host *` jako výchozí; aliasy ze souborů s `Match` → `MATCH`).
- Přeskočí `ProxyJump` (`PROXY_JUMP`), `ProxyCommand` (`PROXY_COMMAND`), prázdný host
  (`MISSING_HOST`) a vše, co neprojde pravidly `SshProfile.valid()` nebo chybí uživatel
  (`UNSUPPORTED_OPTION`); důvod vypíše na stderr. Nejvýše 200 profilů, jinak chyba
  s doporučením `--only`.
- Skupina: komentář `# netkit-group: <název>` přímo nad řádkem `Host` (max. 60 znaků),
  jinak název souboru z `Include` bez přípony.

### Bezpečnost

- Soubor neobsahuje hesla, klíče, passphrase ani cesty `IdentityFile`; obsahuje ale názvy
  hostů, adresy, porty a uživatelská jména → **citlivý**. Přenášet kabelem nebo přes vlastní
  NAS, ne přes cloud/chat; po importu smazat.
- Výstup se zapisuje atomicky s právy `0600`. Zápis do git pracovního stromu skript odmítne
  (ochrana proti omylem commitnutému souboru), `--force` to vědomě povolí.
- `install-pubkey` odmítne soubor obsahující privátní klíč, víc řádků nebo jiný typ než
  `ssh-ed25519`, `ecdsa-sha2-nistp256/384/521`, `ssh-rsa`. Klíč na server přidává jen
  ssh-copy-id po potvrzení; odvolání = smazat řádek s komentářem `netkit@…` v `authorized_keys`.
- Git hosting (`github.com`, `gitlab.com`, `bitbucket.org`, `codeberg.org`, Azure DevOps) se
  v exportu i v `install-pubkey` vynechá jako `UNSUPPORTED_OPTION`, protože nemá shell.
- `install-pubkey --all` varuje u aliasů se slovem `audit`, `prod` nebo `production` (omezené
  a produkční účty) a připomene, že RouterOS `ssh-copy-id` nepodporuje (MikroTik: `/user ssh-keys`).

Testy: `tools/test_netkit_ssh_export.py` (syntetické configy, podvržený `ssh -G` runner,
žádné skutečné připojení). Fyzický průchod importu na telefonu ověřuje správce.

## 9. Výměnný formát v2, starý export a pořadí profilů

Android exportuje `netkit-ssh-profiles.json`: `format: "netkit-ssh-profiles"`,
`schema: 2`, `source: "netkit-android"`, pole `profiles` a prázdné `skipped`.
Každý profil obsahuje `alias`, `host`, `port`, `username`, `authentication`
(`PASSWORD`, `KEY`, `INTERACTIVE`), `startupCommand` a `keepaliveSeconds`.
Pořadí pole je pořadí v aplikaci. Import navíc přijímá volitelné `group` jako
metadata náhledu; skupiny se v aplikaci neukládají.

Export neobsahuje lokální ID, `secretRef`, `keyRef`, klíče ani důvěru serverů.
Obsahuje však cíle, uživatelská jména a startovací příkazy: před přenosem ověřte,
že příkaz sám neobsahuje citlivé údaje. Limity importu jsou 256 KiB, 200 profilů
(a nejvýše 200 přeskočených řádků), omezená hloubka JSON a allowlist polí.
Před otevřením systémového dialogu pro vytvoření souboru se připraví a ověří
neměnný export. Duplicitní jména (včetně jejich výčtu) nebo překročení 256 KiB
mají konkrétní CS/EN hlášku a dialog se neotevře; nevznikne prázdný soubor.
Stejná jména při uložení profilů zůstávají povolená. Změny profilů během
otevřeného dialogu neovlivní již připravený export. Pokud Android během SAF
ukončí proces a snapshot se ztratí, po návratu se export znovu vytvoří z
aktuálního úložiště. Duplicity/velikost se znovu ověří před otevřením cíle
pro zápis a zobrazí stejnou konkrétní chybu. Soubor, který už vytvořil SAF,
může při této chybě zůstat prázdný; aplikace ho neotevře ani nepřepíše.
Varování exportu a smazaného profilu lze zavřít tlačítkem OK.

Import přijímá také v1 z PC skriptu (§8) a původní export telefonu: objekt bez
`format`, `schema: 1`, pole profilů s `name` místo aliasu a starými ID.
Staré ID se nepřebírá. `hostKeys`, `keys`, `rejectedHostKeys` musí být prázdné
seznamy a reference `secretRef`/`keyRef` null (nebo chybět); jinak se celý soubor
odmítne. **Nejde o import zálohy úložiště nebo důvěry serverů.**

Párování používá přesnou shodu aliasu a jména. V1 mění pouze host, port a
uživatele. V2 a starý export navíc aktualizují ověřování, startovací příkaz a
keepalive; náhled ukazuje každý změněný údaj jako „před → po“. Změna příkazu
má varování, protože se příkaz spustí po potvrzení připojení. Změna cíle
(host/port/uživatel) odpojí uložené heslo jako v §8. Existující `keyRef` se
podle rozhodnutí správce 2026-11-01 zachovává i při změně cíle. Při změně
samotného typu ověřování se ale nepoužívaná reference odpojí: odchod z KEY
odstraní `keyRef`, odchod z PASSWORD odstraní `secretRef` a po úspěšném zápisu
smaže uložené heslo. Náhled při aktualizaci na KEY bez existujícího klíče
výslovně požaduje jeho výběr. U nového profilu v1 ukazuje ověřování podle
aktuální volby klíče v importním dialogu.

Nové profily se přidají na konec v pořadí souboru; existující profily si
ponechají pozici. Vybraný lokální klíč dostanou nové v1 profily a nové
v2/legacy profily typu `KEY`. Ostatním typům v2 se ověřování výběrem klíče
nemění. Bez výběru zůstane nový v2/legacy `KEY` bez přiřazeného klíče.
Připojení i reconnect v tom případě nevytvoří transport a nabídnou přechod
do editoru. Totéž platí pro odkaz na již neexistující klíč. Editor výslovně
vyžádá výběr; vždy při chybějícím přiřazení nabídne „Vytvořit klíč“, i s jinými klíči v seznamu. Návrat z Klíčů
zachovává netajná rozepsaná pole, nikoli heslo. Reconnect použije přesný profil potvrzený v dialogu, včetně
cíle a startovacího příkazu. Smazaný profil odmítne chybou bez nabídky editoru,
aby editace neobnovila již smazaný profil.

Řádek správy profilu nabízí úpravu, smazání a šipky nahoru/dolů ve stylu WoL.
Na kraji seznamu a během operace jsou příslušné šipky zakázané; mají CS/EN
popisky pro TalkBack. Pořadí se ukládá v seznamu profilů, přežije restart,
promítne se do exportu a nemění se běžnou editací profilu.

Implementační a testovací důkazy jsou v reportu 32 (interní záznam);
fyzické přijetí zůstává v checklistu správce.
