# NetKit — závislosti a licence

**Stav k 2026-10-26:** evidence skutečného [katalogu verzí](../gradle/libs.versions.toml),
build skriptů a API použitých při opravách revize 07. §2 rozlišuje přímé deklarace
od již přítomných tranzitivních knihoven, které aplikace používá. Historické datum
jednotlivého licenčního ověření není tvrzení o novém sestavení celého grafu.

## 1. Pravidla

- Závislosti jen s permisivní licencí (Apache-2.0, MIT, BSD-2/3). Copyleft (GPL, LGPL,
  AGPL, MPL a obdobné licence) vyžaduje výslovné rozhodnutí správce; do té doby nepřidávat.
- Evidovat každou **přímou** závislost v §2 s verzí, licencí z POM / LICENSE,
  veřejným zdrojem a datem ověření. Odkaz na poznámky k vydání sám o sobě licenci nedokládá.
- Po přidání závislosti zkontrolovat celý vyřešený strom včetně tranzitivních knihoven,
  jejich POM / LICENSE a zdroje. Povolená licence přímé závislosti sama nezaručuje, že jsou
  povoleny i licence všech tranzitivních knihoven. Doplnit výsledek licenčním soupisem a zkontrolovat změny grafu,
  zámku závislostí a metadat ověřování.
- Závislosti stahovat jen z oficiálních Maven Central / Google Maven zdrojů. Zapnout Gradle
  ověřování závislostí v přísném režimu (`gradle/verification-metadata.xml`). Metadata
  ověřovat a commitovat pouze s příslušnou revizí.
- `shared/commonMain` používá jen KMP kompatibilní artefakty; Android-only knihovny zůstávají
  v `androidApp` nebo `shared/androidMain`.

## 2. Přímé závislosti, pluginy a použité tranzitivní knihovny

Verze a licence ověřeny 2026-10-25 podle skutečného `gradle/libs.versions.toml`, sestavovacích skriptů
a POM/LICENSE. Wrapper a sestavení projektu jsou ověřeny z konfigurace a implementačního reportu (interní záznam).
Přísné grafy běhových a testovacích závislostí a licence uvedené v jejich POM byly zkontrolovány 2026-10-25;
přesný soupis verzí a zdrojů je v [licenčním inventáři projektu](project-license-inventory.tsv)
a původní Gradle grafy v lokálním logu `artifacts/review-fixes/dependency-graphs.log`; lze je znovu získat příkazem v následující sekci.

| Přímá závislost / plugin | Verze | Licence | Oficiální POM / LICENSE | Modul / účel | Přidal |
|---|---:|---|---|---|---|
| Gradle Wrapper | 8.11.1 | Apache-2.0 | [Gradle 8.11.1](https://docs.gradle.org/8.11.1/release-notes.html), [kompatibilita Javy](https://docs.gradle.org/8.11.1/userguide/compatibility.html#java), [licence Gradlu](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE) | sestavení projektu | Codex, fáze A |
| Android Gradle Plugin (`com.android.application`, `com.android.library`) | 8.9.2 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.9.2/gradle-8.9.2.pom), [kompatibilita AGP](https://developer.android.com/build/releases/about-agp) | sestavení Android aplikace a sdílené knihovny | Codex, fáze A |
| Kotlin Android plugin (`org.jetbrains.kotlin.android`) | 2.2.20 | Apache-2.0 | [POM Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.20/kotlin-gradle-plugin-2.2.20.pom), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | Kotlin v `androidApp` | Codex, fáze A |
| Kotlin Multiplatform plugin (`org.jetbrains.kotlin.multiplatform`) | 2.2.20 | Apache-2.0 | [POM Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.20/kotlin-gradle-plugin-2.2.20.pom), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | `shared` androidTarget + jvm | Codex, fáze A |
| Kotlin Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) | 2.2.20 | Apache-2.0 | [POM Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.20/kotlin-compose-compiler-plugin-embeddable-2.2.20.pom), [pokyny k přechodu](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) | Compose compiler v `androidApp` | Codex, fáze A |
| Kotlin Serialization plugin (`org.jetbrains.kotlin.plugin.serialization`) | 2.2.20 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-serialization/2.2.20/kotlin-serialization-2.2.20.pom), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | generování serializérů v `shared`, deklarace pluginu v `androidApp` | Codex, fáze B |
| Compose BOM (`androidx.compose:compose-bom`) | 2025.08.00 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.08.00/compose-bom-2025.08.00.pom) | sjednocení verzí Compose v `androidApp` | Codex, fáze A |
| `androidx.compose.ui:ui` | 1.9.0 (BOM) | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.9.0/ui-1.9.0.pom) | Compose UI v `androidApp` | Codex, fáze A |
| `androidx.compose.foundation:foundation` | 1.9.0 (BOM) | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.0/foundation-1.9.0.pom) | Compose foundation v `androidApp` | Codex, fáze A |
| `androidx.compose.material3:material3` | 1.3.2 (BOM) | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.3.2/material3-1.3.2.pom) | Material 3 | Codex, fáze A |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.10.1/activity-compose-1.10.1.pom) | propojení Activity a Compose | Codex, fáze A |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.9.4 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.9.4/lifecycle-viewmodel-2.9.4.pom) | ViewModelProvider a AndroidViewModel; Compose integraci lifecycle poskytuje tranzitivní knihovna níže | Codex, fáze A |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.10.2/kotlinx-coroutines-core-1.10.2.pom), [licence projektu](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) | proudy a korutiny v `shared/commonMain` a `androidApp` | Codex, fáze B |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test/1.10.2/kotlinx-coroutines-test-1.10.2.pom), [licence projektu](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) | testovací dispatchery v `shared/commonTest` a `androidApp:test` | Codex, fáze B |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.9.0/kotlinx-serialization-json-1.9.0.pom), [licence projektu](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/LICENSE) | JSON modely a ukládání v `shared/commonMain` a `androidApp` | Codex, fáze B |
| `androidx.datastore:datastore-core` | 1.1.7 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core/1.1.7/datastore-core-1.1.7.pom), [licence AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) | implementace ukládání přes DataStore v `androidApp` | Codex, fáze B |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0.pom), [licence AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) | kompilační závislost `androidApp`, stejná verze jako v runtime; zpřístupní `ProfileInstallReceiver` nástrojům manifestu bez potlačení lint kontroly | Codex, fáze B |
| `kotlin("test")` | 2.2.20 (Kotlin plugin) | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-test/2.2.20/kotlin-test-2.2.20.pom), [dokumentace Kotlin Gradle testů](https://kotlinlang.org/docs/gradle-configure-project.html), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | testovací API v `shared/commonTest` | Codex, fáze A |
| `kotlin("test-testng")` | 2.2.20 (Kotlin plugin) | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-test-testng/2.2.20/kotlin-test-testng-2.2.20.pom), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | adaptér testovacího rámce v `shared/jvmTest`, `shared/androidUnitTest` a `androidApp:test` | Codex, fáze B |
| `org.testng:testng` | 7.11.0 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/testng/testng/7.11.0/testng-7.11.0.pom), [licence](https://github.com/testng-team/testng/blob/7.11.0/LICENSE) | testovací běh v `shared/jvmTest`, `shared/androidUnitTest` a `androidApp:test` | Codex, fáze B |
| `com.squareup.okhttp3:okhttp` (Android varianta `okhttp-android`) | 5.3.2 | Apache-2.0 | [POM Maven Central](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.3.2/okhttp-5.3.2.pom), [LICENSE](https://github.com/lysine-dev/okhttp/blob/parent-5.3.2/LICENSE.txt) | HTTPS (LibreSpeed) a WebSocket (NDT7) rychlostního testu v `androidApp`, viz níže | Claude, M2 rychlost |

Verze Compose výše jsou vyřešené ze skutečného POM BOM použitého ve fázi A. Jde o ověřenou
sadu Compose 1.9 pro cíl projektu `compileSdk 35`. Neznamená to, že každý artefakt v BOM je
přímou závislostí; v katalogu jsou přímo deklarovány pouze `ui`, `foundation` a `material3`.

### Použité tranzitivní knihovny v Android UI (revize 07)

Nejde o nové přímé závislosti ani o přidání `material-icons-extended`. Verze jsou
v [licenčním inventáři](project-license-inventory.tsv); oba oficiálně publikované
POMy z existující Gradle cache byly znovu přečteny 2026-10-26 a obsahují Apache-2.0.
HTTP prohlížeč POMů při této kontrole nebyl dostupný; nešlo o nové stažení ani
přepočet celého dependency grafu. Android varianty `*-android` mají stejné verze.

| Použitá tranzitivní knihovna | Verze | Licence | Oficiální zdroj | Účel |
|---|---|---|---|---|
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.4 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.9.4/lifecycle-runtime-compose-2.9.4.pom) | `collectAsStateWithLifecycle` pro UI proudy |
| `androidx.compose.material:material-icons-core` | 1.7.8 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core/1.7.8/material-icons-core-1.7.8.pom) | navigace `Search`, `AutoMirrored.Filled.List` (LAN), `Lock`, `PlayArrow`; při viditelném popisku ikona nemá duplicitní contentDescription |

### Vyřešené grafy běhových a testovacích závislostí (přísné ověření, 2026-10-25)

Přesný soupis M1 byl získán z `ResolutionResult.allComponents` pro konfigurace
`debugRuntimeClasspath`, `debugUnitTestRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`
a `jvmTestRuntimeClasspath`, při `--dependency-verification strict` (log a příkaz v reportu M1).
Obsahuje **106** jedinečných souřadnic: běhová konfigurace APK 98, Android unit testy 106,
JVM testy 18. Android testovací APK nemá vlastní externí runtime závislosti; používá
platformní instrumentaci a kód testované aplikace. Počty zahrnují variantní KMP moduly a BOM.
Proti inventáři M1a (104) přibyly právě JSch a Bouncy Castle, nic dalšího.
Přesné verze, grafy a zdroje licencí obsahuje TSV. POMy AndroidX, Kotlinu, coroutines
a serialization uvádějí Apache-2.0; zděděné licence odkazují na rodičovský POM.
Všechny knihovny aplikace a projektových testovacích classpathů mají permisivní licence
Apache-2.0, MIT, BSD-3-Clause nebo ISC; přibalené části JSch jsou rozepsané níže.

Mezi další tranzitivní a implicitně přidané artefakty ve výsledných grafech patří:

| Tranzitivní artefakt | Verze | Licence | POM / licence | Poznámka |
|---|---:|---|---|---|
| `org.jcommander:jcommander` | 1.83 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jcommander/jcommander/1.83/jcommander-1.83.pom) | analyzátor příkazového řádku pro TestNG |
| `org.webjars:jquery` | 3.7.1 | MIT | [POM Maven Central](https://repo.maven.apache.org/maven2/org/webjars/jquery/3.7.1/jquery-3.7.1.pom) | prostředek pro sestavy TestNG |
| `org.slf4j:slf4j-api` | 2.0.16 | MIT | [POM Maven Central](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.pom), [rodičovský POM](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-parent/2.0.16/slf4j-parent-2.0.16.pom), [licence SLF4J](https://github.com/qos-ch/slf4j/blob/v_2.0.16/LICENSE.txt) | Licence se dědí přes `slf4j-parent` → `slf4j-bom` |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) | vyřešená metadata anotací |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache-2.0 | [POM Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.20/kotlin-stdlib-2.2.20.pom), [licence Kotlinu](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md) | běhová knihovna Kotlinu |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.pom), [zděděná licence v rodičovském POM](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom) | závislost AndroidX ProfileInstalleru v běhové cestě aplikace |

V přísně ověřených grafech projektu není JUnit ani testovací běh/UI testy AndroidX;
`androidTest` používá platformní API `Instrumentation`/`UiAutomation` a nepřidává vlastní
běhové závislosti. Volitelné integrace TestNG pro Guice/SnakeYAML ve vyřešených grafech nejsou.
Tento soupis pokrývá pouze běhové a testovací cesty projektu, nikoli všechny zásuvné moduly sestavovacího systému,
distribuci Gradlu, nástroje SDK ani kompletní zásobník. Již schválený sdílený toolchain a
distribuce Gradlu nejsou předmětem nového schvalování licencí v tomto auditu.

### Kompatibilita ověřená proti build konfiguraci

- Dokumentace Kotlinu 2.2.20 uvádí plnou podporu Gradlu 7.6.3–8.14 a AGP 7.3.1–8.11.1.
  AGP 8.9 vyžaduje Gradle 8.11.1. Deklarovaná kombinace `Gradle 8.11.1 + AGP 8.9.2 + Kotlin 2.2.20`
  spadá do zveřejněných rozsahů: [matice Kotlinu](https://kotlinlang.org/docs/gradle-configure-project.html),
  [matice AGP](https://developer.android.com/build/releases/about-agp).
- Tabulka kompatibility Javy pro Gradle 8.11.1 potvrzuje běh Gradlu na Javě 21. Fáze A
  výslovně nastavuje nástrojový řetězec Javy/Kotlinu 21; bootstrap sestavení skončilo úspěšně
  (`BUILD SUCCESSFUL` v `artifacts/phase-a-bootstrap.log`). To ověřuje konfiguraci v tomto
  pracovním prostředí, ne obecnou kompatibilitu se zařízeními Android.
- AGP 8.9 podporuje projektové `compileSdk 35`; oficiální tabulka AGP uvádí pro API 35 minimum
  AGP 8.6.0. Nastavené hodnoty min/target SDK jsou 29/35.
- Plugin Compose compileru sleduje od Kotlinu 2.0 jeho verzi; samostatně se nepřidává starší
  artefakt `androidx.compose.compiler:compiler`.

### Přímé doplnění pro SSH M1d (2026-10-25)

| Závislost | Verze | Licence | Zdroj a účel |
|---|---|---|---|
| `com.github.mwiede:jsch` | 2.28.0 | BSD-3-Clause; přibalené JZlib BSD-3-Clause a jBCrypt ISC | [POM Maven Central](https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.0/jsch-2.28.0.pom), [LICENSE](https://github.com/mwiede/jsch/blob/jsch-2.28.0/LICENSE.txt), [JZlib](https://github.com/mwiede/jsch/blob/jsch-2.28.0/LICENSE.JZlib.txt), [jBCrypt](https://github.com/mwiede/jsch/blob/jsch-2.28.0/LICENSE.jBCrypt.txt); SSH transport a autentizace |
| `org.bouncycastle:bcprov-jdk18on` | 1.83 | MIT (Bouncy Castle licence) | [POM Maven Central](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.83/bcprov-jdk18on-1.83.pom), [LICENSE](https://github.com/bcgit/bc-java/blob/r1rv83/LICENSE.html); Ed25519/Curve25519 a další kryptografické implementace na Androidu |

Oba oficiální POMy přečteny; nepřidávají další tranzitivní závislosti. ISC je permisivní
licence (přibalená část JSch). **Výslovná výjimka z D7: schváleno správcem
2026-10-26 (D-1), výhradně pro jBCrypt přibalený v JSch.** Zdroj znovu ověřen
2026-10-26: [LICENSE.jBCrypt.txt v tagu jsch-2.28.0](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.jBCrypt.txt).
Nejde o obecné rozšíření povolených licencí ani o výjimku pro copyleft. Ověření kompatibility s
Androidem 29–35 je oddělené od přečtení POM: příslušný build a běh jsou v reportu M1.

**Výjimka MPL-2.0 pro Public Suffix List (schváleno správcem 2026-10-29).** `okhttp-android` 5.3.2
přibaluje do APK `assets/PublicSuffixDatabase.list`, zkompilovanou podobu Public Suffix List
(MPL-2.0). NetKit soubor nemění; MPL-2.0 platí po souborech, takže ostatní kód nezavazuje. Povinnosti
pro binární distribuci (informovat o licenci a zdrojové podobě) plní obrazovka Licence open source
v aplikaci (plný text `androidApp/src/main/assets/licenses/MPL-2.0.txt`, stažený 2026-10-29
z mozilla.org) a `NOTICE`. Výjimka platí jen pro tento neupravený datový soubor, ne pro jiný kód pod
MPL. Kontrola při aktualizaci OkHttp: `unzip -l` release APK a `components.json` /
`OssComponentsTest` (androidApp/src/test).
JSch používá základní Java8 implementace na Androidu; BC doplňuje kryptografii podle
[README projektu](https://github.com/mwiede/jsch/tree/jsch-2.28.0). Není kopírován kód GPL aplikací.

Hotový `org.connectbot:termlib` byl posouzen včetně starších 0.0.2 a 0.0.4 POM/module:
jeho AndroidX/Compose sada vyžaduje vyšší compileSdk než pevné API35. Neměníme toolchain
ani nevynucujeme neověřené starší transitivy. Volíme vlastní omezený VT/ANSI emulátor;
není deklarován jako plná kompatibilita xterm. Podrobnosti a zbylé limity jsou v reportu.
LAN/Wi-Fi používají Android SDK a Compose Canvas, bez dalších knihoven. OUI databáze
IEEE se nepřebírá bez doložené licence; výrobce je pravdivě nedostupný.

### Přibalené písmo (M3, schváleno správcem 2026-10-27)

Nejde o knihovnu ani o Gradle závislost; dva soubory písma v `androidApp/src/main/res/font/`.
OFL 1.1 dovoluje přibalení do aplikace; licence jde v APK s písmem
(`assets/licenses/manrope-OFL.txt`, `assets/licenses/jetbrains-mono-OFL.txt`), písmo se
samostatně neprodává a nepřejmenovává (soubory jsou jen přejmenované kvůli pravidlům
názvů Android resources, název písma uvnitř souboru se nemění). Nahrazuje Geist z M2
(odstraněn v M3, `docs/design.md` §3).

| Soubor | Verze | Licence | Zdroj | SHA-256 | Ověřeno |
|---|---|---|---|---|---|
| `manrope.ttf` (Manrope, variabilní osa wght 200–800) | 4.505 (tabulka `name`, ID 5) | OFL-1.1 | [google/fonts @ b31870a](https://github.com/google/fonts/blob/b31870aff700ab7a1d74fa0c6887d95beb9e0037/ofl/manrope/Manrope%5Bwght%5D.ttf), `ofl/manrope/Manrope[wght].ttf`, commit 2026-09-14 | `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6` | Claude, 2026-10-27 |
| `manrope-OFL.txt` | — | OFL-1.1 | totéž, `ofl/manrope/OFL.txt` | `58172e0c0fac2cda8a37b348164bb55e44b0e69051e557e92b1d3f6910141f7b` | Claude, 2026-10-27 |
| `jetbrains_mono.ttf` (JetBrains Mono, wght 100–800) | 2.211 (tabulka `name`, ID 5) | OFL-1.1 | [google/fonts @ 6e4b84c](https://github.com/google/fonts/blob/6e4b84c976cadb3c49a40fd9a1c203e4f7fcf2da/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf), `ofl/jetbrainsmono/JetBrainsMono[wght].ttf`, commit 2026-03-03 | `48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda` | Claude, 2026-10-27 |
| `jetbrains-mono-OFL.txt` | — | OFL-1.1 | totéž, `ofl/jetbrainsmono/OFL.txt` | `b2fe5e8987594e9ffd1d2ca52a2f5d73eb8335243893c5d6254b5ad69269591d` | Claude, 2026-10-27 |

Plné SHA commitů: Manrope `b31870aff700ab7a1d74fa0c6887d95beb9e0037` (poslední změna
`ofl/manrope`, 2026-09-14), JetBrains Mono `6e4b84c976cadb3c49a40fd9a1c203e4f7fcf2da`
(poslední změna `ofl/jetbrainsmono`, 2026-03-03); obě starší než dva týdny. Staženo přes
`raw.githubusercontent.com` s pevným SHA, `file` hlásí TrueType. Kontrola obsahu
(skriptem nad tabulkami `cmap` a `GSUB`): obě písma pokrývají české znaky a `−` (U+2212),
Manrope má funkci `tnum` (tabulární číslice), JetBrains Mono je neproporcionální sám.

### Přímé doplnění pro rychlostní test (M2, ověřeno 2026-10-26)

| Artefakt | Verze | Licence | Zdroj | Role |
|---|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.3.2 (vydáno 2025-11-18) | Apache-2.0 | [POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.3.2/okhttp-5.3.2.pom), [Gradle module](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.3.2/okhttp-5.3.2.module) | přímá; KMP kořen, na Androidu se vybere `okhttp-android` |
| `com.squareup.okhttp3:okhttp-android` | 5.3.2 | Apache-2.0 | [POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-android/5.3.2/okhttp-android-5.3.2.pom) | tranzitivní; AAR `minCompileSdk=1` |
| Public Suffix List (`assets/PublicSuffixDatabase.list` z `okhttp-android`) | podle OkHttp 5.3.2 | MPL-2.0 (datový soubor) | [publicsuffix.org/list](https://publicsuffix.org/list/), [MPL-2.0](https://www.mozilla.org/media/MPL/2.0/index.txt) | tranzitivní datový soubor v APK; **výjimka MPL-2.0 schválena správcem 2026-10-29**, viz níže |
| `com.squareup.okio:okio`, `okio-jvm` | 3.16.4 | Apache-2.0 | [POM](https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.16.4/okio-jvm-3.16.4.pom), [LICENSE](https://github.com/lysine-dev/okio/blob/parent-3.16.4/LICENSE.txt) | tranzitivní I/O knihovna OkHttp |
| `androidx.startup:startup-runtime` | 1.1.1 → 1.2.0 | Apache-2.0 | [POM Google Maven](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.2.0/startup-runtime-1.2.0.pom) | už v grafu (ProfileInstaller); OkHttp vyžaduje 1.2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib`, `kotlin-stdlib-common` | 2.2.20 → 2.2.21 (jen Android grafy) | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.pom) | patch verze vyžádaná OkHttp; Kotlin compiler a plugin zůstávají 2.2.20, `shared` jvmTest dál 2.2.20 |

Zdroj verzí: `maven-metadata.xml` a výpis adresáře Maven Central (data vydání), POM a
`.module` (závislosti, licence), `aar-metadata.properties` v AAR (min. compileSdk),
[CHANGELOG v repozitáři projektu](https://github.com/lysine-dev/okhttp/blob/main/CHANGELOG.md)
(dříve `square/okhttp`; repozitář se přesunul pod Commonhaus Foundation, Maven skupina
`com.squareup.okhttp3` zůstává). Licence přečteny z POM i LICENSE v tagu `parent-5.3.2`.

Zvažované a zamítnuté verze:

- **5.5.0 / 5.5.1** (2026-08-16 / 2026-10-22): AAR vyžaduje `compileSdk 37` (ECH pro Android 17),
  projekt a sdílený toolchain mají jen `android-35`; 5.5.1 je navíc mladší než dva týdny.
- **5.4.0** (2026-06-08): AAR vyžaduje `compileSdk 36`; platforma 36 v toolchainu není
  a instalace SDK komponent je mimo hranice agenta.
- **4.12.0** (2023): bez AAR omezení, ale starší větev bez oprav z 5.x.
- **Bez knihovny** (`Network.openConnection` + vlastní RFC 6455 klient): méně závislostí,
  ale vlastní WebSocket/TLS framing je zbytečné riziko; OkHttp je Apache-2.0 a rozšířený.

Upgrade na 5.4+/5.5+ je možný až se zvýšením `compileSdk` (samostatné rozhodnutí; lint to hlásí
jako `GradleDependency`). Sloučený manifest: OkHttp přidává do existujícího neexportovaného
`androidx.startup.InitializationProvider` jen `meta-data okhttp3.internal.platform.PlatformInitializer`;
nové oprávnění ani exportovaná komponenta nepřibyly (`INTERNET` už aplikace má).
Grafy po změně (přísné ověření): `debugRuntimeClasspath` 102 souřadnic (+4: okhttp, okhttp-android,
okio, okio-jvm), `debugUnitTestRuntimeClasspath` 110 (+4); inventář TSV má 111 řádků
(stdlib 2.2.20 zůstává pro `jvmTestRuntimeClasspath`). `gradle/verification-metadata.xml`
doplněn o SHA-256 nových artefaktů (`--write-verification-metadata sha256`), poté build se
`--dependency-verification strict` prošel.

Testy klientů běží proti vlastnímu loopback serveru na `ServerSocket` (HTTP/1.1 a serverová
strana WebSocketu v testech); MockWebServer se nepřidává.

### Externí služby Přehledu (M4 A3, ověřeno 2026-10-27)

Nejde o knihovny: nic se nepřibaluje ani nestahuje do buildu, aplikace volá veřejné služby za
běhu a jen při zapnutém „Zjišťovat veřejnou IP“ ([bezpečnost § Veřejná IP](bezpecnost.md#veřejná-ip-a-poskytovatel-m4-2026-10-27)).
Zdroje a datum přebírá rešerše interní pracovní záznam (2026-10-27).

| Služba | Použití | Podmínky / zdroj | Ověřeno |
|---|---|---|---|
| Cloudflare `/cdn-cgi/trace` na `1.1.1.1` a `[2606:4700:4700::1111]` | veřejná IPv4 / IPv6 (`ip=`), HTTPS | diagnostický endpoint bez smlouvy, [developers.cloudflare.com/fundamentals/reference/cdn-cgi-endpoint](https://developers.cloudflare.com/fundamentals/reference/cdn-cgi-endpoint/) | 2026-10-27 |
| Team Cymru IP-to-ASN přes DNS TXT (`origin.asn.cymru.com`, `origin6.asn.cymru.com`, `AS<n>.asn.cymru.com`) | ASN, prefix a jméno poskytovatele | komunitní služba bez klíče, [team-cymru.com/ip-asn-mapping](https://www.team-cymru.com/ip-asn-mapping) | 2026-10-27 |

Android API (ověřeno `javap` proti `platforms/android-35/android.jar` toolchainu, 2026-10-27):
`DnsResolver.rawQuery(Network, byte[], int, Executor, CancellationSignal, Callback<byte[]>)`
a `DnsResolver.FLAG_NO_CACHE_LOOKUP` (= 4, API 29), `Network.getSocketFactory()`,
`Network.getNetworkHandle()`. HTTP klient je stávající OkHttp (§2, rychlostní test), bez nové závislosti.

## 3. Volba perzistence

Pro desítky WoL záznamů a SSH profilů se v M1a používá **DataStore + JSON**: malé kolekce
se načítají a zapisují atomicky, bez potřeby dotazů a indexů. Tím zůstává model jednoduchý
a není potřeba generování kódu SQLDelight ani jeho plugin. Nevýhodou je ruční verzování a
migrace JSON schématu; větší historie nebo selektivní dotazy by mohly odůvodnit databázi.
Hesla a privátní klíče nepatří do DataStore ani exportovaného JSON; zůstávají v `SecretStore`.

**SQLDelight** se pro malou kolekci nepoužívá: přidal by SQL schéma, generování kódu a plugin.
**Room KMP** se zatím nepoužívá, protože pro tento rozsah by přinesl složitější konfiguraci
než DataStore; vhodnost lze znovu posoudit, až vznikne potřeba databázových dotazů.

## 4. Zvolené a odmítnuté varianty v M0/M1a

| Varianta | Rozhodnutí | Důvod |
|---|---|---|
| Compose Multiplatform Gradle plugin | V M0 se nepoužívá | Uživatelské rozhraní je nativní Android Jetpack Compose; potřebu kompilátoru zajišťuje Kotlin Compose compiler plugin. Modul `shared` má androidTarget a JVM, bez iOS targetu. |
| Compose BOM 2025.08.00 | Zvoleno v M0 | Ověřená sada Compose 1.9 pro nakonfigurované `compileSdk 35`; verze součástí BOM jsou doloženy jeho POM. |
| SQLDelight | V M1a se nepoužívá | SQL schéma, generování kódu a plugin nemají pro desítky uložených profilů přínos oproti zvolené variantě DataStore + JSON. |
| Room KMP | V M1a se nepoužívá | Pro současný rozsah přidává složitější KMP konfiguraci než DataStore. |
| JUnit4 / Compose UI test přes `ui-test-junit4` | Odmítnuto pro projektové classpathy | POM/LICENSE JUnit4 4.13.2 uvádí EPL-1.0, která není v povolených licencích D7. JVM testy používají TestNG a UI testy vlastní platformní `Instrumentation`/`UiAutomation`; testování zůstává povinné. |
| Turbine / MockK | V M1a se nepoužívá | Stávající testy používají `kotlin.test`, TestNG a ručně psané náhradní objekty; další testovací knihovna není pro současné testy nutná. |
| JSch + BC / vlastní VT subset | Zvoleno v M1d | Ověřené permisivní licence; termlib vyžaduje vyšší compileSdk. Plný terminál zůstává otevřený, viz §2 a specifikace SSH. |

JUnit4 není součástí kompilačních ani běhových cest projektových testů. Již schválený sdílený
toolchain a distribuce Gradlu se zde znovu licenčně neschvalují. Dokument zaznamenává aktuální
běhové a testovací grafy projektu; nejde o licenční audit celého vývojového zásobníku.

Před přidáním nové závislosti aktualizovat §2, ověřit POM/LICENSE a celý vyřešený běhový
a testovací strom. Povolená licence přímé závislosti sama o sobě neřeší požadavek D7 pro
tranzitivní artefakty.

## 5. Opravy po nezávislé revizi

Zvolená sada verzí je konzervativní, ověřená sestavením. Netvrdíme, že novější verze
jsou s API 35 nekompatibilní. Aktualizace a bezpečnostní stav přímých i tranzitivních
závislostí se znovu posoudí před dalším funkčním řezem a před vydáním.
Nepoužitá integrace `lifecycle-viewmodel-compose` byla nahrazena `lifecycle-viewmodel`
stejné verze; používáme platformní `ViewModelProvider`.

SHA-256 metadata byla vytvořena při prvním stažení z oficiálních repozitářů (TOFU).
Přísná verifikace detekuje následnou změnu artefaktu, sama nepotvrzuje původ prvního
stažení. Podpisy PGP zapnuté nejsou. Nezávislá revize `74e1924` ověřila wrapper a
hash distribuce a 75 vzorkovaných artefaktů proti oficiálním sidecar souborům bez
neshody; 59 dalších vzorků sidecar nemělo. To není úplné nezávislé ověření všech hashů.
Nadbytečná metadata `junit:junit` a `kotlin-test-junit` jsou odstraněna; metadata
`junit-bom` potřebná při vyhodnocení build pluginů zůstávají.

Globální secret-scan zaměňuje čtyřsložkovou verzi Google annotations za veřejnou IPv4.
Ve třech atributech XML jsou proto tečky zapsané jako `&#46;`; XML parser získá původní
verzi i názvy artefaktů, hashe se nemění. Regenerace metadat tuto reprezentaci přepíše:
před commitem je nutné znovu upravit právě tyto tři atributy a spustit strict build i
secret-scan. Jde o lokální dokumentovaný workaround, ne výjimku či vypnutí hooku.
Změna globálního scanneru nebo jeho allowlistu není součástí NetKitu.

Po revizi 02 byla odstraněna také nepoužívaná verifikační metadata
`lifecycle-viewmodel-compose` a `lifecycle-viewmodel-compose-android`; v inventáři
104 běhových/testovacích souřadnic již nebyla. Strict build po odebrání je v reportu 03.

### Balení JAR a testovací nástroje M1

Multi-release JARy JSch/BC/jspecify obsahují stejné OSGi `MANIFEST.MF` pod
`META-INF/versions/*/OSGI-INF/`. Android OSGi nevyužívá; tyto deskriptory se při balení
vyloučí, licence a NOTICE se naopak sloučí (`META-INF/LICENSE*`, `META-INF/NOTICE*`).
Zdroj syntaxe: [AGP 8.9 ResourcesPackaging](https://developer.android.com/reference/tools/gradle-api/8.9/com/android/build/api/dsl/ResourcesPackaging).

Hostový fixture používá již schválený systémový Python/Paramiko z `docs/toolchain.md`
a výslovného postupu `docs/testovani.md` §3. Nic se neinstalovalo a Paramiko se nebalí
do aplikace ani do Gradle classpathů; licenční soupis výše není auditem tohoto existujícího
systémového testovacího toolchainu. Fixture generuje klíče pouze do soukromého `/tmp`,
nikoli do repa nebo APK.

### API a UI použité při opravách M1 (ověřeno 2026-10-26)

Nová běhová knihovna nepřibyla. `material-icons-core:1.7.8` a
`lifecycle-runtime-compose:2.9.4` již byly v resolved compile grafu;
`artifacts/compile-dependencies.log` dokládá skutečný classpath. Používají se core
ikony, nikoli balík extended. Licence zůstávají v existujícím inventáři.

SAF WoL otevírá deskriptor s `CancellationSignal` a stream vlastní jeho životnost:
[ContentResolver.openFileDescriptor](https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,java.lang.String,android.os.CancellationSignal)),
[AutoCloseInputStream](https://developer.android.com/reference/android/os/ParcelFileDescriptor.AutoCloseInputStream),
[CancellationSignal](https://developer.android.com/reference/android/os/CancellationSignal).
Limit1MiB a timeout15s jsou aplikační politika, nikoli vlastnost AndroidAPI.
Zrušení zavře stream a předá cancel provideru; chování konkrétního OEM/provideru
zůstává fyzickým testem. Neúspěšný export se pokusí odstranit právě vytvořený dokument.
Zdroje pro síťová API a SSHIME jsou u příslušných implementačních poznámek v
`docs/funkce/` a `docs/bezpecnost.md`.

Instrumentace nativního terminálového View používá skutečné Android resource ID;
Compose popup má vlastní `testTagsAsResourceId` pro svůj semantics subtree pouze při `BuildConfig.DEBUG`; release jej nezapíná.
Zdroj: [Compose testing interoperability](https://developer.android.com/develop/ui/compose/testing/interoperability),
ověřeno 2026-10-26. Viditelnost, fokus a nepřekrytí IME testuje instrumentace samostatně.

### TCP errno a prostředí testů (revize 07, ověřeno 2026-10-26)

Bez nové knihovny a bez změny toolchainu. Android čtečka používá veřejné
[ErrnoException.errno](https://developer.android.com/reference/android/system/ErrnoException)
(dostupné od API21) a `OsConstants`; žádnou reflexi ani parsování lokalizovaných
zpráv. [AOSP Android35 IoBridge](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/libcore/io/IoBridge.java)
ukazuje nativní příčinu v ConnectException a timeout příčinu přes `initCause`.
Veřejná smlouva [Socket.connect](https://developer.android.com/reference/java/net/Socket#connect(java.net.SocketAddress,int))
rozlišuje SocketTimeoutException a obecnou IOException; chybějící errno aplikace
nepovažuje za odmítnutí. Meze JVM adaptéru a Android runtime důkazu jsou v
[LAN specifikaci](funkce/lan-scanner.md#tcp-errno-a-locale-revize-07-ověřeno-2026-10-26).

[`Test.environment`](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html#org.gradle.api.tasks.testing.Test:environment(java.lang.String,%20java.lang.Object))
 nastavuje proměnné testovacího procesu. Oba moduly používají `LC_ALL=C` jako
pojistku diagnostiky, nikoli náhradu errno klasifikace. Wrapper zůstává8.11.1;
žádné globální locale ani sdílená konfigurace se nemění.

### ICMP připravenost socketu (R3, ověřeno 2026-10-26)

Bez nové knihovny. Veřejné Android API [Os.poll](https://developer.android.com/reference/android/system/Os#poll(android.system.StructPollfd[],int))
a [StructPollfd](https://developer.android.com/reference/android/system/StructPollfd)
jsou dostupné od API21. Čekání skončí při připravenosti descriptoru nebo timeoutu;
10ms krok zde omezuje čekání na kontrolu zrušení, není to minimální RTT.
[POSIX poll](https://pubs.opengroup.org/onlinepubs/009696799/functions/poll.html)
popisuje připravenost, timeout a jeho možné zaokrouhlení systémem.
Párování typu/kódu, sekvence a dat vychází z [RFC792](https://www.rfc-editor.org/info/rfc792/).
Linux ping socket spravuje identifikátor a checksum — doloženo zdrojem
[ping.c](https://android.googlesource.com/kernel/common/+/android-mainline/net/ipv4/ping.c).
Naměřený userspace RTT zahrnuje plánování; samotný emulátor nedokazuje fyzickou
Wi-Fi ani správnou cestu při VPN a mobilních datech.

### SSH vstup a přihlašovací časové limity (revize 07, ověřeno 2026-10-26)

- [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection):
  `deleteSurroundingText` počítá UTF-16 jednotky; varianta `InCodePoints` počítá
  code points a při neplatném surrogate v požadovaném rozsahu nic nemaže.
  Čistý `TerminalComposition` respektuje tyto dvě smlouvy. Přebytek mazání před
  composition posílá terminálový Backspace; prázdný commit neodešle starý text.
- [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent):
  levý/pravý Alt mají odlišné masky, `getUnicodeChar(metaState)` zohledňuje rozložení.
  Pravý Alt ponechává úplný metaState a neposílá ESC prefix. Skutečné české AltGr
  přes BT/OEM klávesnici není tímto zdrojem ani JVM testem ověřené; patří do
  fyzického checklistu (@, zpětné lomítko, svislice, složené závorky).
- [Session.java v JSch 2.28.0](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/src/main/java/com/jcraft/jsch/Session.java):
  při vlastní socket factory `connect(int)` nejprve vytváří socket a potom nastavuje
  `SO_TIMEOUT`. Síťový read timeout není celkovým časovým limitem synchronního
  dialogu. Aplikace má prompt 180 s, vnější přihlášení 600 s (3×180 s + rezerva),
  DNS 10 s, vytvoření socketu celkem 15 s a nejvýše 5 s na IP; shell vnější limit
  20 s/JSch 15 s. Serverový `LoginGraceTime` může ukončit přihlášení dříve.

Aplikační politika composition: dokončit text bez Enteru při ztrátě fokusu a před
akcí lišty/IME editoru; uzavření InputConnection text vyčistí a odpojený View jej
neodesílá. Výhodou je zachované pořadí text → klávesa, kompromisem dokončení
rozpracovaného návrhu při změně fokusu. Nejde o plný xterm; široké znaky a Unicode
combining vykreslování zůstávají mimo rozsah. Žádná nová knihovna ani oprávnění.

### IME okno a kontrast grafů (revize 07, ověřeno 2026-10-26)

[Řízení viditelnosti klávesnice](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility)
a [windowSoftInputMode](https://developer.android.com/guide/topics/manifest/activity-element#wsoft)
popisují `adjustResize` jako úpravu dostupného prostoru pro IME. MainActivity tuto
volbu deklaruje; doplňuje Compose `imePadding`. To samo nedokládá skutečné OEM
chování na API 29/30: runtime těchto verzí je **NOT RUN**, emulátorový běh se týká
API 35. Viditelnost pole, klávesnice a tlačítka vyžaduje zvláštní UI důkaz.

Wi-Fi grafy používají šest pevných dvojic barva/tvar, číslovanou legendu a nejvýše
šest vykreslených AP v pásmu; úplná data nadále vstupují do seznamu a skórování.
Metoda kontrastu vychází z [W3C Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)
a [W3C Contrast Minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html):
linearizace sRGB s prahem 0,04045, luminanční váhy 0,2126/0,7152/0,0722 a poměr
(Lmax+0,05)/(Lmin+0,05). Nezaokrouhlený práh grafických prvků je 3:1.
Výpočet `artifacts/n7/wifi-chart-contrast.py` čte barvy přímo z Kotlin implementace;
výsledek 14 párů (6 sérií + osy × 2 režimy) je uložen v JSON/logu: nejnižší
poměr světlo 4,968019855769922:1 na #FAFAFA, tma 7,068642673374920:1 na #151A22.
Tento výpočet není kontrolou všech antialiasovaných pixelů, překrytí sérií, textů
celého tématu ani kompletní přístupnosti. Světlo/tma/200 % a TalkBack se ověřují
samostatně; syntetický screenshot není RF ani fyzický telefonní test.

### API pro spektrum a BSS Load (M2, ověřeno 2026-10-26)

Bez nové knihovny a bez nového oprávnění. `ScanResult.getInformationElements()` a třída
`ScanResult.InformationElement` (`getId()`, `getBytes()` jako `ByteBuffer`) jsou podle
`platforms/android-35/data/api-versions.xml` v toolchainu dostupné od **API 30**
(`since="30"`), ověřeno lokálně `javap` nad `android-35/android.jar`; veřejná reference
[ScanResult.InformationElement](https://developer.android.com/reference/android/net/wifi/ScanResult.InformationElement).
Na API 29 se volání přeskočí kontrolou `Build.VERSION.SDK_INT`. Formát prvku BSS Load
(ID 11, tělo 5 B) je podle IEEE 802.11-2020 (oddíl 9.4.2, prvek BSS Load) tak, jak jej uvádí zadání; text
normy nebyl v této session znovu otevřen.

### Compose API pro časový a rozbalený graf (C4, ověřeno 2026-10-27)

Bez nové závislosti nebo oprávnění. Použity stávající Compose artefakty z projektového
BOM; přímý překlad C4 se současným `Theme.kt` a `Components.kt` přes Kotlin/Compose
compiler plugin a AAPT2 je popsaný v reportu C4 (interní záznam).
To nenahrazuje Gradle build s dependency verification ani runtime test dialogu.

- [Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers):
  `drawWithCache` uchovává kreslicí objekty do změny velikosti nebo čteného stavu;
  samotná rotace vrstvy nemění měření layoutu. C4 proto před rotací prohodí constraints.
- [Understand gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures):
  samostatné `pointerInput` bloky pro souběžné detektory, `awaitEachGesture` pro životní
  cyklus kontaktů. C4 pozoruje počet prstů v průchodu Initial a transformuje jen při
  dvou a více prstech; jeden prst odečítá hodnoty.
### Animace ciferníku rychlosti (C5, ověřeno 2026-10-27)

Bez nové knihovny a bez nového oprávnění. Android reference
[`Settings.Global.ANIMATOR_DURATION_SCALE`](https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE)
a [`getFloat(ContentResolver, String, float)`](https://developer.android.com/reference/android/provider/Settings.Global#getFloat(android.content.ContentResolver,%20java.lang.String,%20float))
ověřeny 2026-10-27: hodnota `0` vypíná animace, výchozí škála je `1`.
`SpeedDial` od revize M3 (2026-10-27) nečte nastavení sám: používá `LocalReduceMotion`
z `ui/motion/Motion.kt` jako zbytek aplikace (`ProvideReduceMotion` sleduje změnu
nastavení za běhu přes `ContentObserver`); při nule používá okamžitou změnu místo tween
animace.

## Veřejný export — opakované licenční ověření 2026-10-28

[Evidence jednotlivých POMů](public-license-verification.tsv) pokrývá všechny
souřadnice z `project-license-inventory.tsv` a navíc POM odkazy v tomto dokumentu.
Jde o nové přečtení oficiálně publikovaných POMů z lokální Gradle cache, nikoli
nové stažení nebo nové vyřešení dependency grafu. Evidence uvádí URL, SHA-256,
zdroj zděděné licence a datum pro každou položku. Shell neměl dostupné DNS.

Webovým nástrojem byly 2026-10-28 navíc přečteny oficiální texty:

- [JSch 2.28.0](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.txt): BSD-3-Clause,
  [JZlib](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.JZlib.txt): BSD-3-Clause,
  [jBCrypt](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.jBCrypt.txt): ISC.
  Historická zmínka LGPL v hlavičce JSch/JZlib se týká řady 0.0.*, ne používaného vydání.
  Výjimka ISC zůstává omezena na přibalený jBCrypt (D-1).
- [Bouncy Castle 1.83](https://raw.githubusercontent.com/bcgit/bc-java/r1rv83/LICENSE.html): MIT.
  Text přibalen v `licenses/bouncycastle-LICENSE.html`.
- [OkHttp 5.3.2](https://raw.githubusercontent.com/square/okhttp/parent-5.3.2/LICENSE.txt): Apache-2.0.
  Okio 3.16.4 ověřeno z oficiálního POM v cache; webový fetch tagového LICENSE nebyl dostupný.
- [Manrope](https://raw.githubusercontent.com/google/fonts/b31870aff700ab7a1d74fa0c6887d95beb9e0037/ofl/manrope/OFL.txt)
  a [JetBrains Mono](https://raw.githubusercontent.com/google/fonts/6e4b84c976cadb3c49a40fd9a1c203e4f7fcf2da/ofl/jetbrainsmono/OFL.txt):
  OFL-1.1, stávající schválená výjimka pouze pro fonty. Atribuce nesanitizovat.
- [Gradle 8.11.1](https://raw.githubusercontent.com/gradle/gradle/v8.11.1/LICENSE):
  vlastní Gradle/Wrapper Apache-2.0. Soubor navíc uvádí licence komponent celé
  distribuce (včetně EPL); nejde o knihovny přibalené do aplikace.
- [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt): obsah ověřen na apache.org.
  Lokální `LICENSE` je standardní plný text z distribuce systému; celý obsah
  souhlasí s textem získaným z apache.org webovým nástrojem po normalizaci whitespace
  (SHA-256 `0ffddef9e48f8a09aed5caf2d44f7ba1c1be2d9b8e0a6f693b1635b2d5566645`).
  Binární shoda se staženým souborem apache.org čeká na kontrolu s dostupnou sítí.

Nebylo zjištěno odlišné licenční označení používaných knihoven proti inventáři.
Souhrn `NOTICE` a plné texty v `docs/licenses/` doplňují již přibalené fontové
licence; nenahrazují kontrolu licencí v konkrétním release APK.

## Bezpečnostní hranice parserů a schránky (zadání 27, ověřeno 2026-10-29)

Žádná nová závislost ani změna verzí. Veřejné zdroje byly přečteny webovým nástrojem:

- `kotlinx.serialization` **1.9.0**: [`StreamingJsonDecoder.handleUnknown`](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/formats/json/commonMain/src/kotlinx/serialization/json/internal/StreamingJsonDecoder.kt#L228-L237)
  volá [`AbstractJsonLexer.skipElement`](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/formats/json/commonMain/src/kotlinx/serialization/json/internal/lexer/AbstractJsonLexer.kt#L489-L527),
  který používá smyčku a explicitní seznam tokenů, nikoli rekurzi zásobníku. Limit 16 se
  přesto aplikuje i před typovaným dekódováním síťových odpovědí pro jednotnou hranici vstupů.
- Android **API 33+**: [Copy and paste — sensitive content](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#sensitive-content)
  předepisuje `ClipDescription.EXTRA_IS_SENSITIVE` v `PersistableBundle` extras před
  `setPrimaryClip`; systém pak nezobrazí text v potvrzovacím náhledu. NetKit tak označuje
  kopii terminálu, který může obsahovat tajemství.
- OkHttp **5.3.2**, tag `parent-5.3.2`: [`WebSocketReader`](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/WebSocketReader.kt#L162-L176)
  a [`readMessage`](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/WebSocketReader.kt#L222-L281)
  nemají aplikační velikostní strop příchozí datové zprávy před callbackem.
  [`RealWebSocket.MAX_QUEUE_SIZE`](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/RealWebSocket.kt#L411-L427)
  omezuje odchozí frontu. Dopad F4 a přesné rozlišení v reportu 30 (interní záznam).
