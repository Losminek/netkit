# NetKit — veřejná distribuce (GitHub Releases + web)

**Stav 2026-10-31:** připravuje se vydání 1.0.0 (D13–D26, plán je interní záznam). Release build, workflow, O aplikaci a kontrola aktualizací jsou v kódu, web autora `https://www.daniel.losak.cz/netkit` je veřejný (stránku renderuje web, kontrakt `web/netkit/README.md`) a release klíč existuje. Zatím nic nevyšlo. Fakta o službách
Google a GitHubu byla ověřena 2026-10-27 v oficiálních zdrojích (u každého tvrzení je zdroj).
Značení: **[P]** = prokázáno zdrojem, **[O]** = odhad nebo obecná znalost, ověřit před použitím.
Archiv výsledků researche: interní pracovní záznam (interní záznam).

## 1. Rozhodnutí v kostce

| Téma | Rozhodnutí | Odkaz |
|---|---|---|
| Kanál v1 | GitHub Releases (podepsané APK) + stránka projektu na webu živnosti | D9 |
| Repo | Veřejné, Apache-2.0, **čistý export bez historie** | D9 |
| Aktualizace v appce | **od 1.0.0 `latest.json` z webu** (D14 mění D10), bez samoinstalace | D10, D14 |
| Podpis | Lokálně správce, klíč offline mimo repo, `applicationId` se nemění | D11 |
| Google Play | Fáze 2, viz [publikace-play.md](publikace-play.md) | D12 |
| iOS | Zatím ne, viz [ios.md](ios.md) | D12 |

## 2. GitHub Releases

- **Verze.** Tag `vX.Y.Z` odpovídá `versionName`; `versionCode = major*10000 + minor*100 + patch`
  (od 0.7.0 počítá `androidApp/build.gradle.kts` z `netkitVersion`; 0.7.0 = 700, 1.0.0 = 10000). Přípona `-dev` do veřejného vydání nepatří. IzzyOnDroid
  chce, aby tag odpovídal `versionName` nebo `versionCode` **[P]**
  ([NewAppInclusions](https://izzyondroid.org/contributing/NewAppInclusions/)).
- **Podpis.** správce podepisuje lokálně (`tools/sign_release.sh`, uvnitř `apksigner`); klíč se nikdy
  nedostane do repa ani do GitHub Secrets (pravidlo z `CLAUDE.md`). CI vyrobí jen nepodepsané release APK
  a `SHA256SUMS`, nahraje je do **draftu** releasu; skript draft stáhne, podepíše, ověří a nahraje
  podepsané APK s `latest.json`, správce draft publikuje. Alternativa (keystore v Secrets) je pohodlnější, ale klíč opouští
  správcův stroj; nedoporučeno **[O]**.
- **Co je u releasu:** podepsané `netkit-X.Y.Z.apk`, `latest.json` (§4), `SHA256SUMS` (obojí),
  volitelně `netkit-X.Y.Z-mapping.txt`; v poznámkách changelog, SHA-256 APK a otisk certifikátu.
- **Otisk release certifikátu (F6, čtou ho `sign_release.sh` a `publish.yml`).** Jediný řádek níže;
  formát 64 hex znaků nebo `AB:CD:…`. Zjistí ho `tools/sign_release.sh --print-fingerprint --keystore …
  --alias …`. Dokud je pole prázdné, podpis i `publish.yml` selžou a web se o vydání nedozví.
- **Otisk release certifikátu (SHA-256):** `74f395fdd9fc5898bf200ec4235b666705d1359d660c845c042970ef08d5cb8c` (klíč vygenerován 2026-10-31, RSA 4096, PKCS12, alias `netkit`; offline záloha u správce)
- **Postup vydání (F3 2026-10-28, upraveno 2026-10-29 podle D22–D24).**
  1. `netkitVersion` v `androidApp/build.gradle.kts` a skutečná doména v `gradle.properties`
     (`netkitWebBase`, D17); commit, tag `vX.Y.Z`, push tagu.
  2. `.github/workflows/release.yml`: `tools/check_release_version.sh` (tag = `netkitVersion`), testy,
     lint, `assembleRelease` (R8; se zástupnou doménou build selže), nepodepsané
     `netkit-X.Y.Z-unsigned.apk`, `netkit-X.Y.Z-mapping.txt` a `SHA256SUMS` do **draftu**.
  3. Správce lokálně: `tools/sign_release.sh X.Y.Z --keystore <cesta> --alias <alias> --upload`
     stáhne nepodepsané APK z draftu a ověří jeho hash, zarovná (16 KB stránky), podepíše (hesla se zadávají
     interaktivně do `apksigner`), porovná otisk s řádkem výše, vytvoří `latest.json`
     (`tools/release_latest_json.py`, česká poznámka z první odrážky verze v `CHANGELOG.md` nebo `--notes-cs`, anglická povinně `--notes-en`; u 1.0.0 se chyběla a `latest.json` se opravoval v draftu),
     nový `SHA256SUMS`, spustí `verify_release_assets.sh` a nahraje vše do draftu včetně poznámek
     (nepodepsané APK odebere, mapping s `--drop-mapping`). Nepublikuje; publikaci dělá správce
     (`gh release edit vX.Y.Z --draft=false` nebo na GitHubu).
  4. `.github/workflows/publish.yml` (`release: published`): `tools/verify_release_assets.sh` ověří hash APK
     i `latest.json`, podpis, jediného podepisujícího s otiskem z řádku výše, `cz.netkit.app`,
     `versionName`/`versionCode`, nepřítomnost `REQUEST_INSTALL_PACKAGES` a že `latest.json` popisuje přesně
     toto APK (verze, `minSdk` z APK, SHA, otisk, `pageUrl` = `netkitWebBase`, `apkUrl` na GitHubu). Pak job
     `rebuild-web` v environmentu `release` zavolá Deploy Hook Workers Builds webu (secret
     `OSOBNI_WEB_DEPLOY_HOOK`, bez něj job selže) a až 15 minut čeká, než `<web>/latest.json` vrátí 200
     se `versionName` právě vydané verze; jinak selže (D26). Výjimka: **první publikovaný release** hook
     přeskočí (viz bod 5). Release se zpracovávají po jednom (`concurrency: publish`, bez rušení běžícího).
     Draft lze ověřit předem: `gh workflow run publish.yml -f tag=vX.Y.Z` (jen kontrola, web se neoznamuje).
  5. **První vydání (1.0.0).** Web má pojistku `netkitReleased: false` (`src/site.json` v `osobni-web`),
     se kterou existující release odmítne. Pořadí: (a) publikovat stabilní veřejný release 1.0.0 (ne draft
     ani prerelease), `publish.yml` hook sám vynechá; (b) session webu sloučí PR `netkitReleased: true`
     a Workers Builds nasadí web s manifestem; (c) ověřit `curl -sI https://www.daniel.losak.cz/netkit/latest.json`
     (200, `application/json`, bez přesměrování) a `versionName`, ve webovém repu `scripts/netkit-postdeploy.mjs`.
     Od druhého vydání volá hook `publish.yml`. Build webu stahuje release anonymně
     (`releases/latest/download/latest.json`, API `releases/latest`, APK pod `releases/download/vX.Y.Z/`),
     takže release musí být ve **veřejném** repu (D18).
  - Lokální `assembleRelease` s `keystore.properties` v kořeni repa (v `.gitignore`; klíče `storeFile`,
    `storePassword`, `keyAlias`, `keyPassword`) vyrobí rovnou podepsané APK; bez souboru nepodepsané.
    R8 test se zástupnou doménou: `-PnetkitAllowPlaceholderWeb=true` (jen lokálně, CI ho přebíjí `=false`).
- **Immutable releases** zamykají tag i soubory po publikaci **[P]**
  ([docs](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)).
  Postup: draft → nahrát všechny soubory → publikovat.
- **Attestations.** `actions/attest` dává SLSA Build L2 **[P]**
  ([docs](https://docs.github.com/en/actions/concepts/security/artifact-attestations)), na plánech
  Free/Pro/Team jen u veřejných repozitářů. Při lokálním podpisu kryje attestation jen nepodepsaný build,
  takže má omezenou hodnotu; volitelné.
- **Limity.** Soubor max. 2 GiB, bandwidth se neomezuje **[P]**
  ([About releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)).

## 3. Repo a licence

- **Licence:** Apache-2.0 (konzistentní s D7 a se závislostmi). `LICENSE` a `NOTICE` zatím v repu
  nejsou (audit 2026-10-27). Bez licence mají ostatní jen právo číst kód **[O]**.
- **Čistý export bez historie.** Audit našel v souborech osobní identifikátor, interní hostname,
  privátní IP a absolutní cesty (viz §8). Historii nepřepisujeme (force push je jen na výslovný pokyn),
  zveřejní se nové repo s jedním počátečním commitem. `prace/` a `prompty/` jsou interní pracovní
  dokumenty; rozhodnutí, co z nich do exportu přejde, je součástí promptu 20.
- **Závislosti:** `docs/zavislosti.md` eviduje Apache-2.0/MIT/BSD/ISC (jBCrypt, výjimka) a fonty OFL
  (výjimka schválená, licence přibalené). Před exportem ověřit úplnost `NOTICE` (JSch fork, BouncyCastle,
  jBCrypt, fonty). LibreSpeed (LGPL): kód se nepřebírá, implementuje se jen HTTP protokol; kdyby někdo
  kód přidal, je potřeba revize licencí.

## 4. Aktualizace v aplikaci

| Var. | Co | Složitost | Bezpečnost / privacy | Rozhodnutí |
|---|---|---|---|---|
| A | Tlačítko otevře stránku Releases (`ACTION_VIEW`) | velmi nízká | žádný síťový kód ani oprávnění | původní v1, nahrazeno C (D14) |
| B | Dotaz na GitHub API `releases/latest` | nízká až střední | HTTPS; GitHub vidí IP; limit 60 req/h na IP bez autorizace **[P]** ([rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)), sdílená NAT/CGNAT ho může vyčerpat | ne |
| C | `latest.json` na webu autora, záloha stejný soubor u GitHub release (D22) | střední | vlastní server, jen oznámení; při výpadku webu GitHub (soubor, ne API, bez limitu 60 req/h); uživatel dál instaluje ručně | **od 1.0.0** (D14, D22), implementováno F3 |
| D | Samoinstalace (`PackageInstaller` + `REQUEST_INSTALL_PACKAGES`) | vysoká | největší útočná plocha, citlivé oprávnění; Play povoluje oprávnění jen aplikacím, jejichž hlavní funkcí je instalace balíčků **[P]** ([Play policy](https://support.google.com/googleplay/android-developer/answer/12085295)) | **ne** |
| E | Obtainium / IzzyOnDroid / F-Droid | nízká na straně appky | aktualizace řídí uživatel; Obtainium čte GitHub Releases | doplněk, odkaz na webu |

**Schéma `latest.json` (schéma 1, implementace F3 2026-10-28,** `shared/.../update/UpdateInfo.kt`**).**
Soubor vzniká jednou při podpisu (`tools/release_latest_json.py`, D23), je assetem release a `publish.yml`
ho ověří. Web ho kopíruje na `<web>/latest.json`; `pageUrl` je bez lomítka na konci (web má
`trailingSlash: 'never'`). Kontrakt pro web: [web/netkit/README.md](../web/netkit/README.md).

```json
{ "schema": 1, "versionName": "1.0.0", "versionCode": 10000,
  "released": "2026-11-02", "minSdk": 29,
  "apkUrl": "https://github.com/<org>/<repo>/releases/download/v1.0.0/netkit-1.0.0.apk",
  "pageUrl": "https://www.<doména>/netkit", "sha256": "<APK>", "certSha256": "<podpisový certifikát>",
  "notes": { "cs": "Krátké shrnutí změn", "en": "Short summary" } }
```

- **Povinné:** `schema` (= 1), `versionName` (`X.Y.Z`), `versionCode` (číslo, musí odpovídat
  `major*10000 + minor*100 + patch` z `versionName`), `minSdk`, a aspoň jedno z `pageUrl` / `apkUrl`.
  **Volitelné:** `released` (`YYYY-MM-DD`), `sha256` a `certSha256` (64 hex, nebo s dvojtečkami),
  `notes` (jazyk ISO 639-1 → text, řídicí znaky nahrazené mezerou, nejvýš 500 znaků). Neznámé klíče se
  ignorují; kompatibilní rozšíření zůstává ve schématu 1, nekompatibilní změna = nová verze aplikace.
- **Odmítne se celý soubor** (fail closed), když: je větší než 16 KiB (UTF-8), zanoření > 16, neplatný
  JSON, chybí povinné pole, špatný typ (číslo v uvozovkách), nesouhlasí `versionCode`, nebo jakákoli
  URL není `https` na přesném hostu webu (`BuildConfig.UPDATE_BASE_URL`) či `github.com`, má user-info,
  jiný port než 443 nebo fragment.
- **Zdroje (D22).** Nejdřív `GET <web>/latest.json`; při chybě sítě, stavu jiném než 200, větší velikosti
  nebo odmítnutém obsahu `GET https://github.com/Losminek/netkit/releases/latest/download/latest.json`
  (`releases/latest` nevrací drafty ani prerelease). GitHub přesměrovává dvakrát (na
  `…/releases/download/vX.Y.Z/latest.json` a na `release-assets.githubusercontent.com`, ověřeno 2026-10-29);
  aplikace sleduje jen přesměrování povolená politikou (bezpecnost.md § Kontrola aktualizací).
- **Chování aplikace.** Kontrola nejvýš 1× za 24 h (čas posledního pokusu, i neúspěšného),
  jen když je aplikace v popředí; ručně „Zkontrolovat teď“ v O aplikaci. Vyšší `versionCode` → karta
  „Dostupná verze X“ nahoře v Přehledu; zavření ji skryje do další verze; `minSdk` nad zařízením → karta
  se neukáže, O aplikaci to vysvětlí. Tlačítko otevře `pageUrl` (jinak `apkUrl`) v prohlížeči
  (`ACTION_VIEW`), instalaci dělá uživatel. Vypínač „Kontrolovat aktualizace“ (výchozí zapnuto) vypíná
  automatickou kontrolu i kartu. Síťová politika: [bezpecnost.md](bezpecnost.md) § Kontrola aktualizací.
- **Doména** je od 2026-10-31 skutečná (`https://www.daniel.losak.cz/netkit`). Se zástupnou doménou
  (TLD `invalid`, `example` aj.) `assembleRelease` i `release.yml` selžou (D17) a debug a dev build se ptají
  jen zálohy na GitHubu. Test karty na emulátoru: debug/dev
  build přečte `files/update-fixture.json` aplikace místo sítě (release build tuto větev nemá).

Integritu APK zajišťuje Android tím, že aktualizaci přijme jen s shodným podpisovým certifikátem **[O]**
(platformní chování); `certSha256` slouží uživateli k ruční kontrole.

## 5. Stránka projektu na webu živnosti

Web je samostatný projekt, zde jen zadání obsahu **[O]**:

- popis a účel, screenshoty, aktuální verze s datem, tlačítko Stáhnout (odkaz na asset releasu),
- SHA-256 APK a otisk podpisového certifikátu, changelog, požadavky (Android 10+, `minSdk 29`),
- návod k povolení instalace z neznámých zdrojů a upozornění na Play Protect, který může
  neznámé aplikace mimo Play zkoumat nebo varovat **[P]** ([Play Protect](https://support.google.com/googleplay/answer/2812853)),
- upozornění „skenujte jen vlastní nebo povolené sítě“, odkaz na zdroje a licenci, privacy policy,
- verze se na webu generuje při buildu z `latest.json` u release (D23), ne dotazem prohlížeče na GitHub API;
  build spouští Deploy Hook Workers Builds z `publish.yml` (D26), kontrakt v
  [web/netkit/README.md](../web/netkit/README.md),
- soubory zůstávají na GitHub Releases, web na ně jen odkazuje.

## 6. Android developer verification

Google zavádí povinnou registraci vývojářů. Stav 2026-10-27:

- **[P]** Od 2026-09-30 platí v Brazílii, Indonésii, Singapuru a Thajsku pro instalace z participujících
  obchodů, globální rozšíření „2027 a dále“
  ([developer.android.com](https://developer.android.com/developer-verification),
  [blog Google](https://android-developers.googleblog.com/2026/06/android-developer-verification.html)).
- **[O]** Pro ČR/EU jsem konkrétní datum nenašel. Přímý sideload se zatím nevztahuje, od 2027 se to
  zřejmě změní.
- **[P]** Neověřený vývojář: uživatel musí použít jednorázový „advanced flow“
  ([Android Help](https://support.google.com/android/answer/17065026)); podle sekundárních zdrojů
  zahrnuje restart a 24hodinové čekání.
- **[P]** Plná registrace stojí 25 USD jednorázově, vyžaduje ověření identity; „limited distribution“
  je zdarma, bez ID, na max. 20 zařízení. Registruje se `applicationId` a SHA-256 otisk podpisového klíče
  ([guides](https://developer.android.com/developer-verification/guides)).

**Důsledek:** `cz.netkit.app` a release klíč jsou po prvním veřejném vydání pevné (D11). správce
rozhodne před prvním releasem, zda se registruje plně (25 USD), nebo zůstane u limited distribution
(jen pro okruh do 20 zařízení).

## 7. F-Droid a IzzyOnDroid

- **IzzyOnDroid [P]**: OSI/FSF licence, metadata v Fastlane struktuře, APK podepsané vývojářem z tagovaných
  GitHub releasů, limit zhruba 30 MB, aktualizace do 12 měsíců
  ([policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)). Uživatelé dostanou APK
  podepsané správcovým klíčem. **Doporučeno po prvním stabilním releasu.**
- **F-Droid [P]**: FLOSS licence, žádné proprietární závislosti ani trackery, build ze zdrojů, F-Droid
  podepisuje vlastním klíčem, pokud nejsou reproducible builds
  ([Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)). Re-signing znamená registraci
  druhého klíče. **Až později.** Přiznané anti-features (volání veřejných speedtest serverů) **[O]**
  ověřit při žádosti.

## 8. Kontroly před veřejným vydáním

Podmínka vydání v1 (audit Codexu 2026-10-27, stav aktualizován 2026-10-29; právní body: prace/28-pravni-checklist.md, interní záznam):

| Kontrola | Stav 2026-10-27 | Odpovědnost |
|---|---|---|
| `LICENSE` Apache-2.0 + `NOTICE` | F2 2026-10-28: v repu, LICENSE bajtově shodný s apache.org; obrazovka Licence open source v práci (větev claude/vydani-app) | prompt 20 |
| Osobní údaje, interní hostname, privátní IP, absolutní cesty v repu (`docs/design/prototyp-m3.html`, `tools/test_netkit_ssh_export.py`, interní pracovní záznam, `docs/testovani.md`) | F2 2026-10-28: vyčištěno, export s grep gate a otisky (`docs/public-export.md`) | prompt 20 |
| Tajemství a keystore v historii | úzká kontrola čistá, obsahový audit neproběhl; čistý export problém řeší | prompt 20 |
| Release `signingConfig`, R8/shrinkResources, release workflow | F3 2026-10-28: v kódu, R8 build PASS; emulátor API 35 PASS (5 záložek, SSH heslem přes JSch/BC, veřejná IP přes OkHttp, bez `ClassNotFoundException`) | prompt 21 |
| Obrazovka O aplikaci s verzí a kontrolou aktualizací (D14) | F3 2026-10-28: emulátor PASS (release: verze 0.7.0 (700), kontrola vypnutá se zástupnou doménou; dev: karta, skrytí, novější verze, aktuální, minSdk, cizí host, vypínač) | prompt 21 |
| 16 KB page size: nativní knihovny jen DataStore a graphics-path, zarovnání ELF 16384; APK ZIP alignment `zipalign -c -P 16 -v 4` nad release APK PASS (F3 2026-10-28), **běh neověřen** | běh NOT RUN | prompt 21 |
| Privacy policy CS/EN, podklad pro Data safety | 2026-10-31: závazné znění na webu `https://www.daniel.losak.cz/netkit/privacy`, účinné od 31. 10. 2026 (správce, kontakt, právní základ, Workers Logs vypnuté, předání do USA, námitka); `docs/public/privacy-*.md` je předloha, oddíly 1–9 shodné s webem, oddíl 10 zrcadlí web | Claude, správce |
| Ikona 512 px, screenshoty, feature graphic, README pro veřejnost, CHANGELOG, SECURITY.md | ikona, feature graphic, README, CHANGELOG, SECURITY hotové (F2, F4), kontakt info@losak.cz 2026-10-31; screenshoty chybí | F7 |
| Web autora a spouštěč buildu (D17, D26) | 2026-10-31: web veřejný, `/netkit` před prvním vydáním, `latest.json` 404; `publish.yml` volá Deploy Hook a čeká na verzi (neběželo, chybí secret `OSOBNI_WEB_DEPLOY_HOOK` a veřejný release) | Claude, správce |
| Veřejné repo pro release (D18) | repo `Losminek/netkit` je soukromé; build webu i záloha aplikace potřebují anonymní přístup k release | správce |
| Zkušební běh release na GitHubu (D24) | 2026-10-29 PASS: `release.yml` ručně (testy, lint, R8, draft „zkušební běh“), `sign_release.sh --upload` s testovacím klíčem do draftu, `publish.yml` ručně nad draftem správně selhal na nevyplněném otisku, web se neoznámil; draft smazán, tag nevznikl. CI na PR PASS (po doplnění 3 kontrolních součtů Gradle pro čistý runner) | Claude |
| Bezpečnostní revize Codex Security (D25) | 2026-10-29: standardní sken `gpt-6.1-sol` + triáž, P1 0 / P2 2 / P3 5 (prace/29, interní záznam); opravy P2 a vybraných P3 zadání 27 | Claude, Codex |
| Fyzický test M3 (README stále čeká) | NOT RUN | správce |
| Registrace `applicationId` + otisku klíče (25 USD nebo limited distribution) | nerozhodnuto | správce |
| Release keystore vygenerován a zálohován offline | 2026-10-31 vygenerován, otisk v §2; offline záloha na dvou místech u správce | správce |
| Preset `rychlost.zelina.eu` ve veřejném buildu, nebo jen vlastní URL + M-Lab | nerozhodnuto | správce |

## 9. Právní a provozní minimum (ČR/EU)

**[O] Není právní porada.**

- **Privacy policy** je potřeba i u aplikace bez serveru. Musí uvést: lokální SSH profily
  (šifrovaný úložný prostor, `allowBackup=false`), žádná telemetrie, volání speedtest serverů (IP
  uživatele vidí M-Lab a LibreSpeed), případně kontrolu aktualizace přes web. Aplikace nesmí tvrdit „nic
  neopouští zařízení“: speedtest odesílá veřejnou IP a UPnP používá nešifrované HTTP (`docs/bezpecnost.md`).
- **Skenování:** upozornění „jen vlastní nebo povolené sítě“ je už v UI (`LanScreen.kt`) a musí být i na webu.
- **Odpovědnost:** Apache-2.0 vylučuje záruky; aplikace se nabízí zdarma, „jak je“.
- **DSA a údaje obchodníka:** čl. 30 DSA se týká online platforem; pro zdarma stažitelné APK z GitHubu
  to nejspíš neplatí **[O]**. Na vlastním webu podnikatel uvádí IČO a sídlo standardně.

## 10. Rizika

| Riziko | Opatření |
|---|---|
| Google upřesní pravidla pro EU a přímý sideload | sledovat [developer-verification](https://developer.android.com/developer-verification); registrace včas |
| Ztráta nebo únik klíče | offline záloha, nikdy v repu; ztráta znamená novou identitu (nové `applicationId`) |
| Zveřejnění interních dokumentů | čistý export (§3), grep před zveřejněním |
| Rate limit GitHub API | v2 používá `latest.json`, ne GitHub API |
| Release bez dokončeného fyzického testu | brána v plánu: M3 test hotový před vydáním v1 |

## 11. Neověřeno

- Konkrétní datum developer verification pro ČR/EU.
- Zda GitHub Releases počítá Google jako „open-source platformu“ pro registraci.
- Přesné chování ETag bez autorizace u GitHub API.
- Zda se restricted settings Androidu 15 týkají polohy potřebné pro Wi-Fi scan u sideloadované appky;
  ověřit na zařízení (fyzický checklist).
