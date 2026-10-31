# NetKit

[English](README.md) · **Česky**

Síťový nástroj pro Android 10 a novější: Wi-Fi analyzér, LAN skener, SSH klient a Wake-on-LAN
v jedné aplikaci. Kotlin a Jetpack Compose, české a anglické rozhraní, bez účtu, reklam a analytiky.

## Funkce

- Přehled aktivní sítě, Wi-Fi signálu a mobilního připojení (dual SIM).
- Wi-Fi spektrum, kanály, zabezpečení a živé grafy signálu.
- Vyhledání zařízení v LAN, ping, DNS, TCP porty a traceroute.
- SSH profily s ověřením klíče serveru a terminál (omezená podpora VT/ANSI, ne plný xterm).
- Wake-on-LAN s importem a exportem v JSON/CSV.
- Měření rychlosti přes LibreSpeed nebo M-Lab NDT7 s historií.
- Volitelné zjištění veřejné IP a poskytovatele (Cloudflare, Team Cymru).

**Skenujte jen vlastní nebo výslovně povolené sítě.** Externí služby vidí vaši veřejnou IP adresu;
rychlostní test může přenést velký objem dat.

## Stažení

Podepsané APK z [GitHub Releases](https://github.com/Losminek/netkit/releases/latest) nebo ze
[stránky projektu](https://www.daniel.losak.cz/netkit) (kontrolní součty a postup instalace).
Aplikace hledá novou verzi nejvýš jednou denně (lze vypnout), aktualizaci instalujete sami.

SHA-256 podpisového certifikátu, ověření `apksigner verify --print-certs netkit-X.Y.Z.apk`:
`74:F3:95:FD:D9:FC:58:98:BF:20:0E:C4:23:5B:66:67:05:D1:35:9D:66:0C:84:5C:04:29:70:EF:08:D5:CB:8C`

Zásady ochrany soukromí: <https://www.daniel.losak.cz/netkit/privacy>.

## Sestavení

Potřebujete JDK 21, Android SDK Platform 35 a Build Tools 35.0.0.

```bash
export JAVA_HOME="<cesta-k-jdk-21>" ANDROID_HOME="<cesta-k-android-sdk>"
./gradlew --no-daemon --dependency-verification strict testDebugUnitTest lintDebug assembleDebug
```

`assembleDev` vyrobí `cz.netkit.app.dev`, který jde nainstalovat vedle release aplikace.
Dokumentace je v [`docs/`](docs/) ([toolchain](docs/toolchain.md),
[architektura](docs/architektura.md), [bezpečnost](docs/bezpecnost.md)).

## Licence

NetKit je pod [Apache License 2.0](LICENSE). Komponenty třetích stran mají vlastní licence:
[NOTICE](NOTICE) a [licenční inventář](docs/project-license-inventory.tsv).
Zranitelnosti hlaste soukromě podle [SECURITY.md](SECURITY.md).
