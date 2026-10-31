# NetKit — podklad pro Google Play Data safety

**Pracovní podklad, nikoli vyplněné nebo schválené prohlášení.** Stav 2026-10-28;
stejný HEAD a omezení jako [evidence datových toků](data-toky.md). Play je budoucí
fáze. Před odesláním posoudit skutečnou distribuovanou variantu včetně F3 a všech SDK.

## Jak používat podklad

Google rozlišuje zpracování pouze v zařízení a přenos mimo zařízení; příjemcem
může být i třetí strana. Dočasné zpracování má vlastní podmínky a formulářové
zacházení. Přenos na výslovnou akci uživatele může mít výjimku pro „sharing“,
nikoli automaticky pro všechny otázky formuláře. Zařazení IP záleží na jejím
použití. Samotné oprávnění Androidu neurčuje odpověď Data safety.
[Oficiální nápověda Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
(ověřeno 2026-10-28).

Tato pravidla nejsou důkazem skutečné retence u příjemce. Návrhy kategorií níže
jsou podklady ke kontrole v konzoli, ne rozhodnutí o jejich povinném zaškrtnutí.

## Souhrnné otázky formuláře

| Otázka | Podklad k odpovědi | Stav |
|---|---|---|
| Sbírá aplikace některý z požadovaných typů uživatelských dat? | Nelze vyplnit globální „ne“ jen proto, že NetKit nemá účty a analytiku. Existují přenosy D09–D18. **Je veřejná IP odeslaná speedtest serverům sbíraná data? OTEVŘENO — ověřit v Play Console, nerozhodnuto.** Stejně ověřit Cloudflare trace, Cymru, živé sondy a web aktualizací. | OTEVŘENO |
| Sdílí aplikace data s třetími stranami? | Technickými příjemci jsou DNS resolvery, zvolené servery, Cloudflare (trace i hosting webu), Team Cymru, GitHub (záloha kontroly aktualizací, D22), LibreSpeed, M-Lab a příjemci ručního exportu. Výjimku „user-initiated transfer“ ověřit pro konkrétní tok; nepřenášet ji na automatický Přehled nebo kontrolu aktualizací. | OTEVŘENO |
| Jsou všechna sbíraná uživatelská data šifrována při přenosu? | HTTPS/WSS a SSH ano pro svůj obsah; UPnP HTTP, WoL, ICMP/TCP sondy, mDNS/SSDP/NetBIOS ne. DNS nemá univerzální garanci šifrování. **Globální „ano“ není doložené.** Konečná odpověď vyžaduje přiřadit deklarované datové typy ke konkrétním přenosům. | OTEVŘENO; technická inventura PASS |
| Lze požádat o smazání dat? | Lokální data lze smazat (D21), exporty zvlášť. Není zavedený serverový mazací endpoint autora. Nelze slíbit smazání M-Lab výsledků ani logů cizích serverů. Před „ano“ ověřit, na jaká deklarovaná data se otázka vztahuje, a uvést funkční kontakt/postup. | OTEVŘENO pro vzdálená data |
| Vytváří aplikace účty? | NetKit účet nevytváří (D01). SSH profil je připojení k cizímu serveru, nikoli účet u autora. | Podklad: ne |
| Je sběr povinný, nebo volitelný? | Ruční nástroje a speedtest jsou volitelné. Veřejná IP je výchozí zapnutá s vypínačem, živý Přehled spouští sondy při otevření. F3 plánuje zapnuté kontroly s vypínačem. V konzoli posoudit podle konkrétního datového typu, nikoli jednou odpovědí za aplikaci. | OTEVŘENO |
| K jakému účelu se data používají? | Z kódu: funkčnost aplikace (diagnostika, správa, měření a oznámení verze); žádné reklamní či marketingové SDK. M-Lab své výsledky používá také pro výzkum, viz jeho zásady. | Podklad, ověřit účely u příjemců |
| Jsou data zpracována pouze dočasně? | Paměťová cache v aplikaci nedokazuje dočasné zpracování na serveru. M-Lab výsledky zveřejňuje a dlouhodobě uchovává; u ostatních příjemců retence není doložena. | Nelze plošně označit ano |

## Matice datových typů a toků

| Data / možné kategorie k ověření | Technický podklad: sbírá/sdílí | Přenos / uchování / smazání | Evidence |
|---|---|---|---|
| WoL seznam, SSH metadata, lokální historie rychlosti a souhlasy | Běžné ukládání je pouze místní. Přenosy při použití funkce a exportu posoudit zvlášť. | Metadata nejsou šifrována aplikací; privátní úložiště, systémové zálohy zakázané; smazání lokálně. | [D02–D06](data-toky.md#d02), [D19](data-toky.md#d19), [D21](data-toky.md#d21) |
| Wi-Fi identifikátory a mobilní stav; případně „Device or other IDs“ / „Location“ podle skutečného použití | Mobilní karta nic neodesílá. Wi-Fi seznam se neuploaduje; SSID může být v místní historii. GPS poloha se nezjišťuje. Nevydávat oprávnění FINE za sběr GPS. | RAM; historie rychlosti místně. Pozor na uživatelem vytvořený export, nikoli analytický upload. | [D05](data-toky.md#d05), [D07–D08](data-toky.md#d07), [D20](data-toky.md#d20) |
| IP, hostname, MAC, uživatelské jméno, příkazy, autentizace; případně „Personal info“, „Device or other IDs“ a obsah dle akce | Cíle sond, DNS resolvery, WoL příjemci a SSH server dostávají nezbytná data. Konkrétní kategorii a výjimky pro přímý přenos ověřit. | SSH šifrovaně, ostatní viz souhrn. Serverové logy neřídí NetKit. Privátní SSH klíč není odesílán jako soubor. | [D09–D12](data-toky.md#d09) |
| Veřejná IP a měření rychlosti; možné „Device or other IDs“, „App info and performance“, případně „Approximate location“ pouze dle skutečného použití IP | **Klasifikace IP odeslané speedtest serverům jako „collected“ zůstává otevřená v Play Console.** Příjemci LibreSpeed/M-Lab existují bez ohledu na klasifikaci. | HTTPS/WSS; ruční test. M-Lab publikuje IP a výsledky. Smazání místní historie neodstraní vzdálená data. | [D13](data-toky.md#d13), [D14](data-toky.md#d14) |
| Veřejná IP, ASN, verze klienta, provozní metadata | Cloudflare trace a resolver/Team Cymru; kategorie IP i „collection/sharing“ otevřené. Živý ping a DNS nejsou řízené vypínačem IP. | HTTPS trace, DNS dle cesty, ICMP bez šifrování. Vypínač IP vyčistí místní cache, ne cizí logy. | [D15–D16](data-toky.md#d15) |
| F3: IP, verze klienta, čas/cesta požadavku | Web autora `www.daniel.losak.cz` a hosting Cloudflare (Workers Logs vypnuté); záloha GitHub jen při chybě webu (D22). | Plán HTTPS GET nejvýš 1×/24 h automaticky, vypínač; ruční kontrola. Žádný doložený vzdálený mazací mechanismus. | [D17](data-toky.md#d17), ověřeno nad kódem 2026-10-29 |
| Exportované soubory, sdílený text, schránka; „Files and docs“ / obsah dle exportu | Příjemce volí uživatel, může to být i cloud. Prověřit výjimku pro uživatelem vyžádaný přenos. | Soubor či IPC, šifrování/retenci příjemce nelze zaručit. Kopie maže uživatel i u příjemce. | [D18](data-toky.md#d18) |
| Reklamní identifikátor, analytika používání, automatické hlášení pádů, telefonní číslo, IMEI, IMSI, ICCID, Cell ID | V prověřené implementaci se nečtou/neodesílají; nedeklarovat pouze na základě širokého názvu oprávnění. | Statická inventura, ne kompletní audit binárních závislostí. | [D01](data-toky.md#d01), [D08](data-toky.md#d08), [D20](data-toky.md#d20) |

## Co zbývá před odesláním

1. Ověřit F3 po sloučení: endpoint, skutečné hlavičky, cookies/identifikátory,
   automatický limit i chování ruční kontroly, vypnutí, lokální stav a odkazy.
2. Kontakt správce, doména a logování webu (Workers Logs vypnuté) jsou doplněné
   2026-10-31 (privacy oddíl 10). Potvrdit s provozovatelem `rychlost.zelina.eu` provozní podmínky a souhlas (D20).
3. V Play Console rozhodnout otevřené zařazení veřejné IP a ostatních dat,
   výjimky „sharing“, dočasné zpracování a souhrnnou odpověď na šifrování.
4. Zkontrolovat všechny varianty distribuované přes Play a skutečné SDK;
   zdokumentovat odpovědi a datum. Tento soubor neslouží jako tvrzení „Data not collected“.
5. Policy je od 2026-10-31 na `https://www.daniel.losak.cz/netkit/privacy` (česky; předloha
   [CS](privacy-cs.md), překlad [EN](privacy-en.md)). Tuto URL uvést v Play Console.

Další ověřené primární zdroje (2026-10-28):
[M-Lab NDT](https://www.measurementlab.net/tests/ndt/) potvrzuje IP a zveřejňování výsledků;
[M-Lab privacy](https://www.measurementlab.net/privacy/) popisuje uchování a kontakt pro žádosti.
