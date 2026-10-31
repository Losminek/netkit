# NetKit — publikace na Google Play (fáze 2)

**Stav 2026-10-27:** příprava, nic se neprovádí. Podle D12 přichází Play až po veřejném vydání v1
([distribuce.md](distribuce.md)) a po výslovném správcově pokynu. Fakta ověřena 2026-10-27;
**[P]** = prokázáno zdrojem, **[O]** = odhad.

## 1. Výchozí stav repa

| Položka | Stav | Zdroj |
|---|---|---|
| `applicationId` | `cz.netkit.app` | `androidApp/build.gradle.kts` |
| minSdk / targetSdk / compileSdk | 29 / **35** / 35 | tamtéž |
| Oprávnění | INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, FINE a COARSE location, READ_PHONE_STATE, READ_BASIC_PHONE_STATE (M4, mobilní signál obou SIM) | `AndroidManifest.xml` |
| Nepřítomné | `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, foreground service, `NEARBY_WIFI_DEVICES` | tamtéž |
| Release build | bez `signingConfig`, R8 a flavorů | audit Codex 2026-10-27 |

## 2. Co je potřeba

| Položka | Fakta | Odhad práce / ceny |
|---|---|---|
| Poplatek | 25 USD jednorázově **[P]** ([answer/6112435](https://support.google.com/googleplay/android-developer/answer/6112435)) | 0,5 h |
| Typ účtu | osobní: ověření ID a zařízení, zveřejní se jméno, země a e-mail; organizační vyžaduje D-U-N-S, doklad organizace a ověřený web **[P]** ([13628312](https://support.google.com/googleplay/android-developer/answer/13628312), [10841920](https://support.google.com/googleplay/android-developer/answer/10841920)) | osobní 1–2 h; organizační navíc týdny |
| Closed test (osobní účet) | **min. 12 testerů, opt-in nepřetržitě 14 dní**, poté žádost o produkci; review žádosti obvykle do 7 dní **[P]** ([answer/14151465](https://support.google.com/googleplay/android-developer/answer/14151465)). Výjimku organizačních účtů potvrzují jen třetí strany **[O]** | sehnat testery 2–10 h, kalendářně min. 14 dní + review |
| **targetSdk 36** | nové aplikace a aktualizace musí od 2026-08-31 cílit na API 36 **[P]** ([answer/11926878](https://support.google.com/googleplay/android-developer/answer/11926878)); repo má 35 | 1–3 dny včetně regrese (chování Android 16, Wi-Fi scan) **[O]** |
| AAB | nové aplikace jen AAB, limit 4 GB **[P]** ([guide](https://developer.android.com/guide/app-bundle)) | 2–4 h |
| Play App Signing | upload key (RSA ≥ 2048, resetovatelný) a app signing key u Googlu; vlastní klíč lze nahrát (PEPK) **[P]** ([answer/9842756](https://support.google.com/googleplay/android-developer/answer/9842756)). **Rozhodnutí odloženo (D11)** | 1–2 h |
| 16 KB page size | od **2027-02-01** u cílení na API 35+ **[P]** ([page-sizes](https://developer.android.com/guide/practices/page-sizes)). Nativní knihovny jen DataStore a graphics-path, ELF zarovnání 16384; **APK ZIP alignment neověřen** | ověřit na release APK, pak 0–2 h |
| Grafika a texty | ikona 512×512 PNG s alfa, feature graphic 1024×500, min. 2 screenshoty (doporučeno 4+), název ≤ 30, krátký popis ≤ 80, plný ≤ 4000 znaků **[P]** ([9866151](https://support.google.com/googleplay/android-developer/answer/9866151), [9859152](https://support.google.com/googleplay/android-developer/answer/9859152)) | 4–8 h |
| Privacy policy URL | povinná i bez sběru dat **[P]** ([10787469](https://support.google.com/googleplay/android-developer/answer/10787469)) | 2 h + hosting (web živnosti) |
| Data safety | deklaruje se jen odesílání dat mimo zařízení **[P]**. Podklad: žádná analytika ani server; **otevřená otázka:** zda veřejná IP odeslaná speedtest serverům (M-Lab, LibreSpeed) je „sbíraná data“; SSH hesla a klíče zůstávají na zařízení | 2 h |
| Content rating, cílová skupina | dotazníky v App content | 1 h |
| Trader / DSA | **oficiální stránku Google se nepodařilo načíst**; osobní účet zveřejní jméno, zemi a e-mail a při monetizaci celou adresu, organizační legal name, adresu, e-mail a telefon. Neověřeno, zda živnostník s free appkou spadá do non-trader. Použije-li správce appku jako referenci pro živnost, je pravděpodobně trader **[O]** | ověřit přímo v Play Console před založením účtu |
| Daně a výplaty | free appka bez IAP: žádný merchant profil | 0 |

**Celkem [O]:** 25–40 h práce, hotovostní náklad 25 USD, kalendářně min. 3–4 týdny (14 dní closed testu
+ review).

## 3. Rizika zamítnutí a mitigace

1. **Poloha pro Wi-Fi scan.** FINE a COARSE location nejsou v seznamu oprávnění s povinnou declaration
   form (ta se týká např. background location, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`)
   **[P]** ([answer/16558241](https://support.google.com/googleplay/android-developer/answer/16558241)).
   Pro `startScan`/`getScanResults` je na Androidu 13+ oficiálně nutné FINE
   **[P]** ([wifi-permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)).
   Mitigace: runtime žádost až po akci uživatele (už je), vysvětlení v UI. Riziko nízké až střední **[O]**.
2. **Device and Network Abuse.** Politika zakazuje přístup k cizím zařízením a sítím bez oprávnění
   **[P]** ([answer/16559646](https://support.google.com/googleplay/android-developer/answer/16559646)).
   Síťové skenery a SSH klienty zná Play, ale oficiální stránka je výslovně nezmiňuje **[O]**.
   Mitigace: popis „jen vlastní síť“, bez brute force a exploitů, výchozí scan jen vlastní podsítě,
   v popisu se nepoužívají slova hack, pentest, exploit. Riziko střední **[O]**.
3. **Foreground service** nepoužíváme. Kdyby přibyl (SSH na pozadí), potřebuje deklaraci typu
   ([13392821](https://support.google.com/googleplay/android-developer/answer/13392821)).
4. **`QUERY_ALL_PACKAGES`** zakázáno (`docs/bezpecnost.md`).
5. **Samoaktualizace.** Play verze nesmí sama sebe aktualizovat mimo Play ani stahovat spustitelný kód
   **[P]** (stejná politika Device and Network Abuse). Proto flavor `play` bez update-checkeru a bez
   `REQUEST_INSTALL_PACKAGES`.
6. **Export šifrování (SSH).** Play Console nemá export-compliance formulář (ten má Apple) **[O]**.
7. **Android 17 a lokální síť.** Pro aplikace s targetSdk ≥ 37 bude potřeba oprávnění `ACCESS_LOCAL_NETWORK`;
   bez něj se blokují TCP, UDP, mDNS i sokety, včetně OkHttp. Při targetSdk ≤ 36 se uděluje implicitně
   **[P]** ([local-network-permission](https://developer.android.com/privacy-and-security/local-network-permission)).
   Cílení na 37 bude Play vyžadovat zhruba od srpna 2027 **[O]**; LAN scanner, WoL i SSH pak potřebují
   runtime oprávnění. ICMP dokumentace nezmiňuje, nejistota zůstává.
8. **Doba review:** „až 7 dní, výjimečně déle“ **[P]** ([9859751](https://support.google.com/googleplay/android-developer/answer/9859751)).

## 4. Flavory a aktualizace

- `play`: bez update-checkeru, bez odkazu na GitHub APK; aktualizace přes Play. Případně in-app
  updates API (`com.google.android.play:app-update`; název artefaktu je z paměti, **ověřit** v
  [in-app-updates](https://developer.android.com/guide/playcore/in-app-updates)).
- `github`: tlačítko „Zkontrolovat aktualizace“ podle D10.
- Jeden `applicationId`. Pokud správce nahraje vlastní klíč do Play (PEPK), mají oba kanály stejný podpis
  a APK jdou přes sebe aktualizovat. S klíčem generovaným Googlem se podpisy liší a kanály jsou
  nezaměnitelné **[O]**. Rozhodnutí patří do přípravy Play (D11).
- Odhad práce na flavory 3–6 h **[O]**.

## 5. Doporučené pořadí

1. správce potvrdí zahájení fáze 2 (po veřejném vydání v1).
2. Rozhodnout osobní vs. organizační účet a trader status (ověřit v konzoli).
3. targetSdk a compileSdk 36, regrese na Androidu 16.
4. Flavory `play`/`github`, AAB, podpis (PEPK vs. klíč Googlu).
5. Privacy policy, Data safety, content rating, grafika (podklady z promptu 22).
6. Založit účet (25 USD), ověřit identitu a zařízení.
7. Internal testing, closed test s 12+ testery po 14 dní, žádost o produkci.
8. Produkce se staged rollout (např. 10 %).
9. Průběžně: každoroční zvýšení targetSdk, 16 KB do 2027-02-01, Android 17 a lokální síť, sledování politik.

## 6. Alternativy kanálů

Huawei AppGallery, Samsung Galaxy Store a Amazon Appstore se pro osobní nástroj nevyplatí (další účty,
jiný ekosystém) **[O]**, nic z toho se neověřovalo. F-Droid viz [distribuce.md](distribuce.md) §7.

## 7. Otevřené otázky na správce

1. Osobní účet na jméno, nebo organizační přes živnost? Existuje 12 testerů, kteří vydrží 14 dní?
2. Je appka striktně zdarma bez příjmů a reklam (trader/non-trader)?
3. Je přijatelné zveřejnit adresu a telefon živnosti jako veřejný kontakt?
4. Vlastní klíč do Play (PEPK), nebo klíč generuje Google?
5. Kdy a jak řešit oprávnění lokální sítě pro Android 17 (targetSdk 37)?
