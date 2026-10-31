# NetKit — pokyny pro přispěvatele a agenty

Kotlin + Jetpack Compose pro Android, přenositelné jádro v `shared`.
Rozcestník, struktura a sestavení: [README.md](README.md).

- Síťové testy jen ve vlastních nebo výslovně povolených sítích.
- Agent nesmí ovládat fyzický telefon; každý příkaz adb musí mít
  `-s emulator-5554` nebo jiné explicitní `emulator-*`.
- Bez souhlasu neinstalovat systémové balíčky ani komponenty SDK.
- Do repozitáře nepatří přihlašovací údaje, privátní klíče ani podpisový keystore.
- `shared/src/commonMain` nesmí importovat `java.*`, `javax.*`, `android.*`.
  Platformní operace patří za rozhraní; síťové operace jsou zrušitelné,
  s timeoutem a omezenou souběžností.
- UI texty patří do CS a EN resources; stav přes ViewModel a StateFlow.
- Nové závislosti vyžadují ověření oficiální licence a zápis do
  `docs/zavislosti.md`; povoleny Apache-2.0, MIT a BSD.
  Existující výjimky: ISC pouze pro jBCrypt přibalený v JSch a OFL-1.1 pro fonty.
- Nová oprávnění zdůvodnit v `docs/bezpecnost.md`.
- Ověřovat kontroly odpovídající změně; výsledek PASS/FAIL/NOT RUN
  vždy spojit se skutečným během. Emulátor nenahrazuje fyzický test.
- Před merge změny kódu musí diff zkontrolovat nezávislý reviewer.
- Bez výslovného pokynu necommitovat, nepushovat a nic nenasazovat.
  Před destruktivní akcí popsat dopad a vyžádat potvrzení.
