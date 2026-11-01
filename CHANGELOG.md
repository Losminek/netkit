# Changelog

## 1.0.1 — SSH profily a iOS ve zdrojích (2026-11-01)

- Export SSH profilů v novém formátu v2 (typ ověření, startovací příkaz, keepalive, pořadí; bez hesel a klíčů) a import v1, v2 i starších exportů z telefonu.
- Pořadí SSH profilů šipkami, náhled změn před importem a varování u startovacího příkazu.
- Profil s ověřením klíčem bez přiřazeného klíče se nepřipojí a nabídne výběr nebo vytvoření klíče.
- Export funguje i po ukončení aplikace systémem během výběru souboru; varování lze zavřít.
- Zdrojový kód iOS verze (SwiftUI nad sdíleným jádrem) je součástí repozitáře; iOS se zatím nevydává.

## 1.0.0 — první veřejné vydání (2026-10-31)

- První veřejná verze: LAN skener, Wi-Fi analýza, SSH terminál, Wake-on-LAN, rychlostní testy a kontrola aktualizací.
- Kontrola aktualizací přes web autora se zálohou na GitHub Releases, nejvýš jednou za 24 hodin, lze vypnout.
- O aplikaci se zásadami ochrany soukromí a plnými texty licencí open source komponent.
- Bezpečnostní úpravy: limit vnoření JSON při importu, odkazy jen do vlastního repozitáře, citlivý obsah schránky.
- Podepsané APK s ověřením otisku certifikátu a `latest.json` u každého vydání.

## 0.7.0 — připravovaný zdrojový export (2026-10-28)

Verze odpovídá `netkitVersion` / `versionName` v `androidApp/build.gradle.kts`.
Nejde o oznámení vydání plánované verze 1.0.0.

- Diagnostika LAN, Wi-Fi spektrum a živé grafy signálu.
- SSH profily, terminál, Wake-on-LAN a přenos zařízení v JSON/CSV.
- LibreSpeed a NDT7 rychlostní testy s historií.
- Přehled aktivní sítě, mobilní signál a dual SIM, volitelná veřejná IP a poskytovatel.
- Příprava veřejných zdrojů, Apache-2.0, atribucí závislostí a exportu bez historie.
