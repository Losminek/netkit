# NetKit — postup revize

**Stav 2026-10-25.** Platí globální pravidlo: změnu kódu před mergem do `main` reviduje agent,
který ji nepsal, nad **diffem**, ne nad závěrem implementátora. Dokumentační změny revizi nepotřebují.

## 1. Kdo a jak

| Implementoval | Reviduje | Jak |
|---|---|---|
| Codex | Claude Code | `/code-review` nad větví `codex/<téma>` proti `main`, nebo subagent s diffem a tímto checklistem |
| Claude | Codex | `codex -p audit review --base main` (read-only profil, bez sítě) |

Implementátor opravuje nálezy. Revizor po opravě znovu čte diff, ne jen komentář k opravě.

## 2. Postup revize jednoho řezu

1. `git -C <repo> branch --show-current` v hlavním klonu je `main`; práce je na větvi `codex/<téma>`
   (opakovaná vada: hlavní klon zůstal na pracovní větvi).
2. `git status --short` (necommitnuté a cizí změny) a `git log main..codex/<téma>` (rozsah).
3. Přečíst **report** v interní pracovní záznam a porovnat tabulku AC → důkaz → mezera s kódem.
   Report je tvrzení, ne důkaz.
4. Spustit sám klíčové kontroly po poslední změně kódu:
   `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`, grep `commonMain`,
   `agent-semgrep` na změněné cesty, secret-scan `--staged` na výsledném stavu.
5. Prověřit manifest diff, závislosti (`zavislosti.md` odpovídá `*.gradle.kts` a lockfile/metadatům)
   a `bezpecnost.md`.
6. Zkontrolovat seznam `adb` příkazů v reportu: žádný bez `-s emulator-*`.
7. Napsat nálezy podle závažnosti do interní pracovní záznam (soubor, řádek, problém, scénář selhání,
   doporučená oprava). Neprohlašovat za „hotovo“, dokud neproběhl fyzický test (správce).

## 3. Checklist (rozšiřuje se podle řezu)

**Vždy:**

- [ ] `commonMain` bez `java.*`, `javax.*`, `android.*`.
- [ ] Žádné tajemství v kódu, testech, fixturách ani logu; žádný `.jks`/token v diffu.
- [ ] Každé nové oprávnění je v `bezpecnost.md` s důvodem.
- [ ] Nová knihovna: řádek v `zavislosti.md` (verze, licence, zdroj, datum).
- [ ] Síťové operace mají timeout, jsou zrušitelné a mají omezenou souběžnost.
- [ ] Chyby se ukazují uživatelsky srozumitelně, ne pád ani prázdná obrazovka.
- [ ] Texty v resources (cs), žádné natvrdo v kódu.
- [ ] Testy testují chování, ne jen existenci; negativní případy a hraniční hodnoty.
- [ ] Žádné oslabení testů, lintu ani prahů kvůli průchodu.
- [ ] Rozsah diffu odpovídá zadání (žádné „nezadáno“ bez vysvětlení).

**WoL:** packet 102 B; MAC formáty; broadcast výpočet na hranách (/31, /32); socket navázaný
na Wi-Fi síť; žádné oprávnění navíc.

**LAN:** timeouty, souběžnost, zrušení; výchozí rozsah jen vlastní podsíť; varování pro cizí rozsahy;
parsery odolné vůči nečekanému výstupu; žádný brute force.

**Wi-Fi:** chování bez oprávnění/polohy; stáří údajů; throttling nepředstírá čerstvost;
anonymizované fixtury.

**SSH:** host key (TOFU, tvrdé varování při změně); politika algoritmů; tajemství jen přes
`SecretStore`; žádné logování vstupu; zavření relace opravdu uzavře spojení; potvrzení vícerádkového vložení.

## 4. Mezery vůči zadání (třídy)

Při návratu reportu označit mezery AC: `chybí` (práce není), `částečně`, `odporuje` (proti zadání nebo
pravidlům), `nezadáno` (změna, kterou nikdo nechtěl — neodstraňovat potichu, předložit správci).
Nové kolo je nový prompt s odkazem na původní AC; původní AC se nepřepisují.
