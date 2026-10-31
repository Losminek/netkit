# Kontrakt mezi NetKitem a webem autora

Stránku NetKitu renderuje web autora (`Losminek/osobni-web`, Astro, Cloudflare Workers Static Assets).
NetKit dodává data o vydání a určuje adresy, na které se aplikace spoléhá. Rozhodnutí D14, D17, D22,
D23 a D26 jsou v [docs/plan.md](../../docs/plan.md), postup vydání je v
[docs/distribuce.md](../../docs/distribuce.md) §2 a §4.

Původní generátor stránky z F5 (`build.py`, šablona, styl) byl 2026-10-29 odstraněn. Web má vlastní
kopii, ze které se stránka nerenderuje, a `latest.json` se teď tvoří u release. Historie je v gitu.

## Adresy (bez lomítka na konci, web má `trailingSlash: 'never'`)

| Cesta | Obsah | Kdo ji čte |
|---|---|---|
| `<SITE_URL>/netkit` | stránka projektu (Stáhnout, ověření, instalace, soukromí) | lidé; `pageUrl` v `latest.json` |
| `<SITE_URL>/netkit/privacy` | zásady ochrany soukromí, **závazné znění** (předloha oddílů 1–9 `docs/public/privacy-cs.md`) | odkaz v aplikaci (O aplikaci), obchod Play |
| `<SITE_URL>/netkit/latest.json` | kopie `latest.json` z posledního release | aplikace, nejvýš 1× za 24 h |

`SITE_URL` je `https://www.daniel.losak.cz` (od 2026-10-31). `<SITE_URL>/netkit` je přesně `netkitWebBase` v `gradle.properties`. Změna domény nebo cesty po vydání
znamená, že starší instalace najdou aktualizace už jen přes zálohu na GitHubu (D22). Proto adresu
neměnit, nebo starou adresu dál obsluhovat.

## `latest.json`

- **Zdroj:** asset `latest.json` u posledního publikovaného GitHub release
  (`https://github.com/Losminek/netkit/releases/latest/download/latest.json`). Vytváří ho
  `tools/sign_release.sh` (přes `tools/release_latest_json.py`) a `publish.yml` ho před přestavbou
  webu znovu ověří. Web ho při buildu stáhne a vydá beze změny. Pro náhled bez vydání smí použít
  vzorek, ale ten nesmí jít do produkce (`build:strict`).
- **Schéma 1** (pole a jejich pravidla: [docs/distribuce.md](../../docs/distribuce.md) §4):
  `schema`, `versionName`, `versionCode`, `released`, `minSdk`, `apkUrl` (GitHub),
  `pageUrl` (= `netkitWebBase`), `sha256` (APK), `certSha256`, `notes.cs`, `notes.en`.
  Aplikace neznámá pole ignoruje, ale špatný typ nebo URL mimo web a `github.com` odmítne celý soubor.
  Web soubor nemění: `publish.yml` ověřuje přesně tuto sadu polí.
- **Hlavičky:** `Content-Type: application/json; charset=utf-8`, `Cache-Control: public, max-age=300`,
  bez přesměrování (aplikace sleduje přesměrování jen na stejném hostu, nejvýš 3 skoky).
- **Velikost** do 16 KiB.

## Spouštěč buildu webu (D26, mění D23)

Web nasazuje Cloudflare Workers Builds z `main` repa `osobni-web`. Po publikaci a ověření release
`publish.yml` (job `rebuild-web`, environment `release`) pošle `POST` na Deploy Hook ze secretu
`OSOBNI_WEB_DEPLOY_HOOK` a až 15 minut čeká, než `<SITE_URL>/netkit/latest.json` vrátí 200 se
`schema == 1` a `versionName` právě vydané verze (cache až 300 s). Jinak job selže; pak zkontrolovat
Workers Builds. POST nic nenese, verzi a data bere web z `releases/latest`.

- Hook vytváří správce (Workers & Pages → osobni-web → Settings → Builds → Deploy Hooks, větev `main`);
  URL je přístupový údaj, nikdy do repa ani do logu. Rotace: smazat hook, vytvořit nový, vyměnit secret.
- **První publikovaný release hook nevolá.** Web má pojistku `netkitReleased: false` a existující release
  odmítne; session webu ji přepne vlastním PR (postup: [docs/distribuce.md](../../docs/distribuce.md) §2, bod 5).
- `repository_dispatch` a token `WEB_DISPATCH_TOKEN` se nepoužívají; v GitHubu není žádný Cloudflare token.

## Co stránka `/netkit` zobrazí (z `latest.json`)

- verze, datum, velikost APK (z GitHub API nebo z HEAD na `apkUrl`), tlačítko Stáhnout na `apkUrl`;
- SHA-256 APK a otisk certifikátu (`AB:CD:…`; release certifikát `74f395fdd9fc5898bf200ec4235b666705d1359d660c845c042970ef08d5cb8c`), příkazy `sha256sum` a `apksigner verify --print-certs`;
- instalace mimo Obchod Play, Play Protect, aktualizace kartou v aplikaci;
- upozornění: skenovat jen vlastní nebo povolené sítě;
- odkazy na zdrojový kód, release, Obtainium a zásady soukromí.
