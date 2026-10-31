# Sestavení a testovací prostředí

Použijte JDK 21, Android SDK Platform 35 a Build Tools 35.0.0.
Wrapper a verze pluginů jsou v repozitáři. Instalace SDK je samostatný krok
správce prostředí, skripty exportu nic neinstalují.

```bash
export JAVA_HOME="<cesta-k-jdk-21>"
export ANDROID_HOME="<cesta-k-sdk>"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon --dependency-verification strict testDebugUnitTest lintDebug assembleDebug
```

Případný `local.properties` s `sdk.dir` je lokální a nepatří do Gitu.
Gradle zapisuje do své uživatelské cache; při omezeném sandboxu zvolte vlastní
`GRADLE_USER_HOME`. Neupravujte verification metadata jen kvůli obejití chyby.

`assembleDev` vytváří APK s identitou `cz.netkit.app.dev`, zatímco debug a release
používají `cz.netkit.app`. Debug/dev podpis není release podpis.

Testy UI používají Android emulátor API 35. Každý příkaz adb musí explicitně
zvolit `emulator-*`; fyzická zařízení agent neovládá.
Host je pro emulátor dostupný přes `10.0.2.2`; fixture server naslouchá na loopbacku.
Podrobnosti a fyzický checklist: [testovani.md](testovani.md).
