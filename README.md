# NetKit

**English** · [Česky](README.cs.md)

Network toolkit for Android 10+: Wi-Fi analyzer, LAN scanner, SSH client and Wake-on-LAN
in one app. Kotlin and Jetpack Compose, Czech and English UI, no account, ads or analytics.

## Features

- Overview of the active network, Wi-Fi signal and mobile connection (dual SIM).
- Wi-Fi spectrum, channels, security and live signal charts.
- LAN device discovery, ping, DNS, TCP ports and traceroute.
- SSH profiles with host key verification and a terminal (limited VT/ANSI, not a full xterm).
- Wake-on-LAN with JSON/CSV import and export.
- Speed tests via LibreSpeed or M-Lab NDT7, with history.
- Optional public IP and provider lookup (Cloudflare, Team Cymru).

**Scan only networks you own or are explicitly allowed to test.** External services see your
public IP address; a speed test can transfer a lot of data.

## Download

Signed APK from [GitHub Releases](https://github.com/Losminek/netkit/releases/latest) or the
[project page](https://www.daniel.losak.cz/netkit) (checksums and install steps). The app checks
for new versions at most once a day (can be turned off); installing an update is up to you.

Signing certificate SHA-256, check with `apksigner verify --print-certs netkit-X.Y.Z.apk`:
`74:F3:95:FD:D9:FC:58:98:BF:20:0E:C4:23:5B:66:67:05:D1:35:9D:66:0C:84:5C:04:29:70:EF:08:D5:CB:8C`

Privacy policy: <https://www.daniel.losak.cz/netkit/privacy>.

## Build

Requires JDK 21, Android SDK Platform 35 and Build Tools 35.0.0.

```bash
export JAVA_HOME="<path-to-jdk-21>" ANDROID_HOME="<path-to-android-sdk>"
./gradlew --no-daemon --dependency-verification strict testDebugUnitTest lintDebug assembleDebug
```

`assembleDev` builds `cz.netkit.app.dev`, which installs next to the release app.
Project documentation in [`docs/`](docs/) is in Czech ([toolchain](docs/toolchain.md),
[architecture](docs/architektura.md), [security](docs/bezpecnost.md)).

## License

NetKit is licensed under the [Apache License 2.0](LICENSE). Third-party components keep their
own licences: [NOTICE](NOTICE) and [licence inventory](docs/project-license-inventory.tsv).
Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
