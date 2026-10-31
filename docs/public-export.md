# Čistý veřejný export

`tools/make_public_export.sh /tmp/netkit-public` vytvoří nový adresář mimo
repozitář. Existující cíl musí být prázdný. Skript odmítá cíl v repozitáři,
v jiném Git repozitáři, symlink, symlinky ve zdrojích i lokální soubory.
Neinstaluje balíčky, nenastavuje remote a nepushuje.

Manifest `tools/public_export.manifest` je seznam jednotlivých povolených
souborů z aktuálního pracovního stromu, nikoli z HEAD. Neznámé soubory se
nepřidávají automaticky. Každý záznam `file` obsahuje zdroj a cíl oddělené
TAB; explicitní `exclude` potvrzuje vynechání interních reportů a zadání.
`.git`, artefakty, build cache, lokální konfigurace a klíče jsou navíc zakázané
v kódu, takže je manifest nemůže omylem povolit.

`tools/public/CLAUDE.md`, `tools/public/README.md` a `tools/public/toolchain.md`
nahrazují v exportu interní pokyny, rozcestník se stavem práce a dokumentaci lokálního
stroje. Samotný adresář `tools/public/` se neexportuje. Aplikační kód se při kopírování
netransformuje.

Před prvním commitem se provede `grep -nEi` každého textového souboru podle
vzoru `PATTERN` v `tools/public_export.py`: adresy, e-maily, domény, lokální
cesty, hostname/SSID a slova pro hesla či klíče. Každý nález musí odpovídat
ručně posouzenému otisku celého souboru v
`tools/public_export.reviewed.tsv`. Kontrolují se i soubory bez grep nálezů,
aby změna bez známého vzoru také vyžadovala revizi. Binární wrapper a fonty mají otisk celého
souboru; jiné binární soubory jsou odmítnuty. Výstup chyby uvádí pouze
soubor, řádek a typ, nikdy hodnotu. Samostatná kontrola:

```bash
python3 -I tools/public_export.py scan /tmp/netkit-public
```

Prázdný výstup a návratový kód 0 znamenají, že nejsou nové nerevidované
nálezy. Samotný široký grep není prázdný: syntetické adresy, názvy API,
veřejné služby a zákonné atribuce musí zůstat. Neexistuje spolehlivý regex,
který pozná libovolné osobní jméno nebo skutečnou privátní adresu od příkladu;
kontrola doplňuje ruční inventuru, nenahrazuje ji. Baseline nikdy neobnovujte
automaticky. Po změně souboru posuďte celý diff a teprve potom aktualizujte jeho otisk.
Otisk zahrnuje i kontext nálezu; cena je nutnost nové revize při každé změně
exportovaného souboru. Export je úmyslně zmrazený snapshot, ne automatický sync.

Skript vyžaduje existující `secret-scan.sh` vedle zdrojového Git hooku nebo
cestu v `NETKIT_SECRET_SCAN`. Kopíruje jej pouze do lokální `.git/hooks`
exportu, zavádí pre-commit a spouští `--staged` i před commitem. Původní hook
nemění. Ve veřejném klonu bez tohoto lokálního nástroje je potřeba scanner
dodat explicitně; bez něj skript odmítne vytvořit commit.

Commit má zprávu „NetKit: počáteční veřejné vydání“, neutrální identitu
`NetKit contributors <netkit@example.org>` a nemá rodiče. Uživatelská Git
konfigurace se pro nový export izoluje, aby nepřenesla remote či identitu.
Neúspěšná kontrola ponechá nepublikovaný adresář pro inspekci; opakujte export
do nového prázdného cíle. Nikdy nepoužívejte tento postup k přepisu historie.

Kontakty v SECURITY a NOTICE jsou od 2026-10-31 doplněné (info@losak.cz); jejich
otisky v `public_export.reviewed.tsv` se obnoví v F7. Tento export sám nic nepublikuje. Zdrojový snapshot 0.7.0
není hotové vydání 1.0.0. Kontrolu konkrétního release APK provést zvlášť.
