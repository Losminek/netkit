# NetKit

Síťová diagnostika pro Android 10 a novější, napsaná v Kotlinu a Jetpack Compose.
Aplikace má české a anglické rozhraní. Sdílená logika je připravena pro Kotlin
Multiplatform; iOS aplikace zatím není k dispozici.

## Funkce

- Přehled aktivní sítě, Wi-Fi signálu a mobilního připojení včetně dual SIM.
- Wi-Fi spektrum, kanály, zabezpečení a průběh signálu.
- Vyhledání zařízení v LAN, ping, DNS, TCP porty a traceroute.
- SSH profily, ověřování host keys a terminál s omezenou podporou VT/ANSI.
- Wake-on-LAN, import a export zařízení v JSON/CSV.
- Měření rychlosti přes LibreSpeed nebo M-Lab NDT7, latence a historie výsledků.
- Volitelné zjištění veřejné IP a poskytovatele přes Cloudflare a Team Cymru.

**Skenujte jen vlastní nebo výslovně povolené sítě.** Rychlostní test přenáší
větší objem dat. Externí služby při požadavku vidí vaši veřejnou IP adresu.

## Omezení a stažení

Výsledky discovery a měření závisí na oprávněních, výrobci telefonu, dostupnosti
Android API a filtraci v síti. Emulátor neověří reálné Wi-Fi skenování, rádiové
údaje ani WoL broadcast. SSH terminál není úplná implementace xterm.

Balíčky budou dostupné v [GitHub Releases](https://github.com/Losminek/netkit/releases)
a na stránce projektu <https://www.daniel.losak.cz/netkit> (stažení, otisky, postup instalace).
Zásady ochrany soukromí: <https://www.daniel.losak.cz/netkit/privacy>.
Tento zdrojový snapshot sám o sobě neznamená publikované či fyzicky ověřené vydání.
Instalace a aktualizace APK probíhá ručně; release podpisový klíč není součástí repozitáře.

## Sestavení

Potřebujete JDK 21, Android SDK Platform 35 a Build Tools 35.0.0.
Verze Gradlu a závislostí jsou připnuté v repozitáři; SDK instalujte samostatně.

```bash
export JAVA_HOME="<cesta-k-jdk-21>"
export ANDROID_HOME="<cesta-k-android-sdk>"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon --dependency-verification strict testDebugUnitTest lintDebug assembleDebug
```

Výstup: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Varianta `assembleDev` používá identitu `cz.netkit.app.dev`, a může tak existovat
vedle release aplikace. Debug APK používá vývojový podpis.
Podrobnosti: [toolchain](docs/toolchain.md), [testování](docs/testovani.md).

## Struktura a dokumentace

- `androidApp/`: Android API, Compose UI, platformní implementace a testy.
- `shared/`: přenositelné modely a logika, společné testy.
- `docs/`: [architektura](docs/architektura.md), specifikace funkcí, design,
  [bezpečnost](docs/bezpecnost.md), [závislosti](docs/zavislosti.md).
- `gradle/`: wrapper, katalog verzí a verification metadata.
- `tools/`: testovací nástroje, SSH pomocník a `make_public_export.sh` pro čistý export
  podle `public_export.manifest`; postup v [public-export.md](docs/public-export.md).
- `LICENSE`, `NOTICE`, `SECURITY.md`, `CHANGELOG.md`: licence, atribuce a informace k vydáním.

## Licence

Vlastní kód NetKitu je pod [Apache License 2.0](LICENSE).
Komponenty třetích stran mají vlastní licence; viz [NOTICE](NOTICE),
[licenční inventář](docs/project-license-inventory.tsv) a přibalené texty licencí.
Bezpečnostní hlášení: [SECURITY.md](SECURITY.md).
