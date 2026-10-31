# Funkce: mobilní síť (karta v Přehledu, M4)

Stav 2026-10-27: **datová vrstva** (M4 A2) a **UI v Přehledu** (M4 A3, větev `claude/m4-a3-prehled`,
§7) napsané, unit testy a build prošly. Emulátor ani fyzický telefon: NOT RUN (checklist
[testování §13](../testovani.md#13-fyzický-checklist-m4), M4-10 až M4-19 a M4-20 a dál).
Rozhodnutí: interní pracovní záznam F3, F4 a O-4. Oprávnění: [bezpečnost § Mobilní síť](../bezpecnost.md#mobilní-síť-m4-2026-10-27).

## 1. Co karta ukazuje

U každé aktivní SIM (bez `READ_PHONE_STATE` jen u SIM, která nese data):

| Údaj | Pole v `CellularSim` | Zdroj |
|---|---|---|
| SIM 1 / SIM 2 | `slotIndex` (0 = SIM 1) | `SubscriptionInfo.getSimSlotIndex`, bez oprávnění `SubscriptionManager.getSlotIndex(subId)` |
| Operátor | `operatorName` | `getNetworkOperatorName()`, pak `ServiceState.getOperatorAlphaLong/Short` (jen s polohou), pak `getSimOperatorName()` / `SubscriptionInfo.getCarrierName` (eSIM, prázdné jméno) |
| Standard LTE / 5G NSA / 5G SA / 3G / 2G | `rat` | `resolveRat` + `simRat`, viz §3 |
| Signál v dBm | `primaryDbm(signal)` | LTE RSRP, NR SS-RSRP (záloha CSI-RSRP); 3G RSCP (`getDbm`), 2G RSSI |
| RSRQ a SINR | `signal.rsrqDb`, `signal.sinrDb` | LTE RSRQ a RSSNR, NR SS-RSRQ a SS-SINR (záloha CSI) |
| Kvalita | `cellQuality(signal)` | prahy v §4 |
| Která SIM nese data | `isDataSim` | `getActiveDataSubscriptionId()` (API 30+), na API 29 `getDefaultDataSubscriptionId()` |
| Roaming | `roaming` | `ServiceState.getRoaming()` jen ve stavu `STATE_IN_SERVICE`, jinak `null` |
| Stáří hodnoty | `elapsedMs` | `SignalStrength.getTimestampMillis()` (API 30+, čas modemu), na API 29 `elapsedRealtime()` při příjmu |

Celkový stav `CellularState`: `airplaneMode`, `noSim` (žádná aktivní SIM a žádný slot ve stavu
`SIM_STATE_READY`), `phoneStatePermission` (UI nabídne „Povolit zobrazení obou SIM“, když
`cellularPermissionNeeded(context)`), `locationPermission` (bez polohy chybí jméno sítě ze
`ServiceState`).

## 2. API a vlákna

- `CellularMonitor(context).observe(): Flow<CellularState>` (`callbackFlow`, `conflate`).
  Registrace při sběru, při zrušení se odregistruje vše (callbacky, posluchač SIM, receiver)
  a skončí vlastní `HandlerThread`. Žádné volání Telephony neběží na hlavním vlákně.
- Na každou SIM `TelephonyManager.createForSubscriptionId(subId)` a vlastní objekt
  callbacku (dokumentace `registerTelephonyCallback`: pro více subId samostatný objekt):
  - API 31+: `TelephonyCallback` se `SignalStrengthsListener`, `DisplayInfoListener`,
    `ServiceStateListener` (žádný nepotřebuje oprávnění);
  - API 29–30: `PhoneStateListener(executor)` s `LISTEN_SIGNAL_STRENGTHS | LISTEN_SERVICE_STATE`,
    na API 30 s `READ_PHONE_STATE` i `LISTEN_DISPLAY_INFO_CHANGED`.
- Změny sady SIM: `OnSubscriptionsChangedListener` (API 30+ s executorem, API 29 vytvořený
  na vlákně monitoru kvůli `Looper.myLooper()`), režim letadlo `ACTION_AIRPLANE_MODE_CHANGED`
  (chráněný systémový broadcast, bez příznaku `RECEIVER_*`), aktivní datová SIM
  `ActiveDataSubscriptionIdListener` / `LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE` jen
  s `READ_PHONE_STATE`; bez něj se datová SIM znovu přečte při každé události.
  Veřejná konstanta `ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED` v SDK 35 není (ověřeno
  `javap` nad `android.jar`), proto se nepoužívá.
- Oprávnění udělené až za běhu monitor pozná při další události (signál chodí každých pár
  sekund při rozsvíceném displeji); UI může po dialogu flow sebrat znovu.
- `getDataNetworkType()` a `getVoiceNetworkType()` (pro SIM bez dat) jen s `READ_PHONE_STATE`
  nebo `READ_BASIC_PHONE_STATE` (API 33+), jinak `null`. Bez nich se použije
  `TelephonyDisplayInfo.getNetworkType()` (API 31+ bez oprávnění). Každé volání chrání
  `SecurityException` (i `UnsupportedOperationException` a chybu binderu) → neznámá hodnota.
- `requestCellInfoUpdate()` ani `getAllCellInfo()` se nepoužívají: signál je jen ze
  `SignalStrength.getCellSignalStrengths()`.

## 3. Standard (RAT)

`resolveRat(dataNetworkType, overrideNetworkType, hasNrSignal)`:

- `NR_SA` jen při `dataNetworkType == NETWORK_TYPE_NR` (20);
- `NR_NSA`: datový typ LTE (13, i skryté `LTE_CA` 19) a override `NR_NSA` (3), `NR_NSA_MMWAVE` (4)
  nebo `NR_ADVANCED` (5), nebo NR měření v tomtéž `SignalStrength`; při neznámém typu jen podle
  override;
- 3G a 2G podle typu (UMTS/HSPA/HSPA+/TD-SCDMA/EV-DO = 3G, GPRS/EDGE/GSM/CDMA/1xRTT = 2G),
  override se u nich ignoruje;
- `IWLAN` (Wi-Fi volání) a `UNKNOWN` = neznámý standard.

`simRat` doplní pro API 29–30 bez oprávnění zdroj signálu (LTE, NR s LTE kotvou = 5G NSA, 3G, 2G).
Samotné NR měření bez LTE se za 5G SA **nepovažuje**, SA potvrdí jen datový typ.

## 4. Platné hodnoty a kvalita

Rozsahy podle dokumentace `CellSignalStrength*` (a range-checků AOSP android15-release);
`CellInfo.UNAVAILABLE` (`Int.MAX_VALUE`) a hodnoty mimo rozsah jsou `null`:

| Měření | Rozsah |
|---|---|
| LTE RSRP / RSRQ / RSSNR / RSSI | −140..−43 dBm / −34..3 dB / −20..30 dB / −113..−51 dBm |
| NR SS-RSRP a CSI-RSRP | −156..−31 dBm |
| NR SS-RSRQ / CSI-RSRQ | −43..20 dB / −20..−3 dB |
| NR SS-SINR / CSI-SINR | −23..40 dB / −23..23 dB |
| 3G RSCP (`getDbm`, TD-SCDMA `getRscp`) | −120..−24 dBm |
| 2G RSSI | −113..−51 dBm |
| Úroveň `getLevel` | 0..4 |

Výběr měření pro měřidlo: NR s RSRP (5G noha), pak LTE s RSRP, pak 3G, pak 2G; ze stejného
typu nejsilnější. Kvalita (dolní mez včetně):

| Standard | Výborný | Dobrý | Slabší | Slabý |
|---|---|---|---|---|
| LTE, 5G (RSRP) | ≥ −80 | ≥ −90 | ≥ −100 | < −100 |
| 3G (RSCP) | ≥ −85 | ≥ −95 | ≥ −105 | < −105 |
| 2G (RSSI) | ≥ −89 | ≥ −97 | ≥ −103 | < −103 |

LTE/5G prahy jsou návrh M4 F3 (doladit podle fyzického testu). 3G a 2G přebírají výchozí
prahy AOSP (`CellSignalStrengthWcdma` RSCP GREAT/GOOD/MODERATE −85/−95/−105,
`CellSignalStrengthGsm` RSSI −89/−97/−103). Starý radio HAL může u 3G v `getDbm` vrátit
RSSI místo RSCP; kvalita pak vyjde o něco lepší. Bez platné hlavní hodnoty = „Bez signálu“.

## 5. Frekvence aktualizace a limity

- Modem posílá `SignalStrength` jen při změně ≥ 2 dB a nejvýš jednou za 3 s; se zhasnutým
  displejem reporty vypíná (AOSP `SignalStrengthController`, `DeviceStateMonitor`). Graf
  mobilního signálu bude proto schodovitý, ne 1 s jako u Wi-Fi.
- Bez `READ_PHONE_STATE` jen datová SIM. Bez polohy může chybět jméno operátora ze sítě.
- HyperOS (Xiaomi) může reporty omezovat; ověří fyzický test.
- CDMA měření se nemapuje (v ČR není).

## 6. Testy

- `shared/src/commonTest/.../cellular/CellularTest.kt`: rozsahy a `UNAVAILABLE`, záloha CSI,
  výběr měření (NSA, nečinná NR noha), prahy kvality všech standardů, mapování typů sítě,
  `resolveRat` pro všechny kombinace typ × override × NR signál, `simRat`, jméno operátora.
- Android vrstva (`toReading`, registrace) je tenká a unit testem bez Robolectricu
  neověřitelná; patří do fyzického checklistu (M4-10 až M4-19).

## 7. UI v Přehledu (M4 A3)

Kód: `androidApp/.../overview/OverviewCards.kt` (karty), `wifi/live/SignalScreen.kt` (rozvržení),
`wifi/live/WifiLiveViewModel.kt` (sběr). Čistá logika `shared/.../overview/Overview.kt`.

- **Kdy je mobilní karta hlavní.** `overviewMode`: bez připojené Wi-Fi a s telefonií
  (`FEATURE_TELEPHONY`) je hlavní karta mobilní (`CELLULAR`), i v režimu letadlo nebo bez dat.
  S Wi-Fi (i pod VPN) zůstává hlavní Wi-Fi karta a pod ní kompaktní „Mobilní síť“. Ethernet:
  jen kompaktní karta. Tablet bez telefonie a bez Wi-Fi: Wi-Fi karta „Nepřipojeno“ jako dřív.
- **Hlavní karta.** Měřidlo RSRP na stupnici −140…−44 dBm (`GaugeRange.CELLULAR`), vedle něj
  SIM, operátor a štítky standardu, „Data“ (jen při dvou SIM) a „Roaming“. Dlaždice RSRQ, SINR
  a IPv4 mobilní sítě. Měřidlo ukazuje SIM s daty (`primarySim`), ostatní SIM jsou řádky pod ním.
- **Kompaktní karta.** Jeden řádek na SIM v pořadí slotů (`sortedSims`): „SIM n · operátor“,
  standard, „Data“, roaming, vpravo dBm a kvalita.
- **Stavy.** Režim letadlo, „Bez SIM“ (`noSim`), „Bez služby“ (SIM bez signálu), „Čekám na
  první měření…“ do první události monitoru. Bez polohy (`ACCESS_FINE_LOCATION`) na mobilních
  datech pruh s textem `cellular_location_note` a tlačítkem pro oprávnění (stejný postup jako
  u Wi-Fi). Wi-Fi hlášky (bez Wi-Fi, bez brány, vypnutá poloha) se bez Wi-Fi nezobrazují.
- **Obě SIM (O-4).** Když `cellularPermissionNeeded`, karta nabídne „Zobrazit obě SIM“
  (`cellular_permission`), které spustí systémový dialog `READ_PHONE_STATE`. Po povolení
  ViewModel monitor sebere znovu (`restartCellular`), takže druhá SIM naskočí hned, ne až při
  další události. Po odmítnutí místo tlačítka krátká `InfoNote` a aplikace se znovu sama neptá.
- **Graf.** Výběr metriky „Signál“ bez Wi-Fi kreslí RSRP primární SIM na stejné 1s časové ose
  jako Wi-Fi (hodnota se vzorkuje z posledního hlášení modemu, čára je schodovitá), rozsah
  −140…−44 dBm, 15 min historie, rozbalení `FullscreenChart`. Události (`CellularEventLog`):
  „Změna standardu“ (stejná datová SIM, dva známé standardy, např. LTE → 5G NSA) a „Přepnutí
  datové SIM“ (jiné subscription id, „SIM 1 → SIM 2“); neznámý standard a chvíle bez datové SIM
  (letadlo) událost nevytvoří. K nim se přidá ztráta paketů internetového cíle.
- **Hlavička.** „Mobilní data“ a pod tím „operátor · standard“; s VPN „VPN · přes operátor · standard“.
- **Rychlost.** Test na mobilních datech uloží do kontextu operátora, standard a signál datové SIM
  na začátku (`SpeedCellularContext`), viz [speedtest.md](speedtest.md).

