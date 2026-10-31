# NetKit — ochrana soukromí

**Závazné znění je na webu: <https://www.daniel.losak.cz/netkit/privacy>, účinné od 31. 10. 2026.**
Správce a kontakt: Daniel Lošák, info@losak.cz.

Tento soubor je technická předloha webové stránky. Oddíly 1–9 web převzal beze změny
(revize `5178db9`), oddíl 10 zde zrcadlí text webu. Odkazy D01–D22 vedou na technickou
evidenci a na webu nejsou. Změna chování aplikace, která mění oddíly 1–9, se musí
předat webu (repo `Losminek/osobni-web`) a vydat tam s novým datem účinnosti dřív,
než vyjde verze aplikace s novým chováním. Při rozporu platí web.

## 1. K čemu NetKit používá data

NetKit je nástroj pro diagnostiku a správu vlastních nebo povolených sítí.
Nemá uživatelský účet ani server pro ukládání vašich profilů. Neobsahuje reklamy,
analytiku používání ani automatické odesílání hlášení pádů autorovi. Některé funkce
komunikují se zařízeními ve vaší síti nebo s internetovými službami popsanými níže.
Příjemce spojení vidí jeho zdrojovou IP adresu, čas a provozní metadata.
[D01](data-toky.md#d01), [D09–D17](data-toky.md#d09)

## 2. Co zůstává uložené v aplikaci

V soukromém úložišti aplikace jsou zařízení Wake-on-LAN (včetně MAC a cíle),
SSH profily (jméno, adresa, uživatel, startovací příkaz), veřejné klíče a údaje
pro ověření SSH serverů. Tato metadata nejsou aplikací šifrována. Nevkládejte
hesla do názvů, poznámek ani startovacích příkazů.
[D02](data-toky.md#d02), [D03](data-toky.md#d03)

Heslo se ukládá, pokud si zvolíte jeho zapamatování. Uložená SSH hesla a privátní
klíče jsou v souborech šifrovaných AES-GCM; šifrovací klíč chrání Android Keystore.
Úroveň hardwarové ochrany závisí na telefonu. Při použití jsou tajemství také
dočasně v paměti. Původní soubor importovaného klíče aplikace nemaže.
[D04](data-toky.md#d04), [D18](data-toky.md#d18)

Historie rychlosti uchovává posledních 20 výsledků, čas, server, typ sítě a dostupné
údaje Wi-Fi včetně názvu sítě (SSID), frekvence, signálu a rychlosti linky. Ukládá
se také vlastní adresa serveru, potvrzení upozornění M-Lab a nastavení aplikace.
Souhlas s automatickým hledáním zařízení je přiřazen k síti pomocí lokálního
HMAC otisku; samotné SSID v této evidenci souhlasů není čitelné.
[D05](data-toky.md#d05), [D06](data-toky.md#d06)

Výsledky Wi-Fi a LAN hledání, živé grafy, veřejná IP a údaje mobilní karty jsou
běžně v paměti, ne v trvalé historii. Výjimkou jsou výše uvedené údaje Wi-Fi
u rychlostních testů a vámi vytvořené exporty. NetKit čte počítadla přenesených
bajtů pro graf provozu, nikoli obsah cizích paketů.
[D07](data-toky.md#d07), [D08](data-toky.md#d08), [D15](data-toky.md#d15)

## 3. Diagnostika sítě, Wake-on-LAN a SSH

DNS dotazy obsahují hledané jméno nebo IP a dostává je resolver příslušné sítě
(a případně navazující DNS servery). Ping, traceroute a testy portů posílají sondy
na zvolené cíle. Vyhledávání zařízení používá také mDNS, SSDP a NetBIOS. Automatické
hledání v LAN se spustí až po vašem souhlasu pro danou síť; hledat lze i ručně.
Popis UPnP zařízení se načítá nešifrovaným HTTP jen z ověřené adresy nalezeného
zařízení ve vlastní podsíti. Tyto sondy a místní discovery protokoly nejsou
šifrované; šifrování DNS závisí na použitém resolveru a cestě.
[D09](data-toky.md#d09), [D10](data-toky.md#d10)

Živý Přehled automaticky měří odezvu brány a internetového cíle (výchozí
`1.1.1.1`, Cloudflare) a DNS odezvu (výchozí jméno `one.one.one.one`).
Sondy běží při aktivním živém Přehledu a končí při jeho opuštění nebo přechodu
aplikace do pozadí. Cíle lze změnit. Vypnutí zjišťování veřejné IP tyto sondy nevypíná.
[D16](data-toky.md#d16)

Wake-on-LAN odesílá nešifrovaný paket obsahující MAC probouzeného zařízení na vámi
zvolený cíl nebo broadcast. Volitelně ověřuje odpověď zařízení. Paket i DNS cíle
používají podkladovou Wi-Fi mimo VPN.
[D11](data-toky.md#d11)

SSH předává zvolenému serveru uživatelské jméno, autentizační údaje podle metody,
příkazy a vstup terminálu přes šifrované SSH spojení s kontrolou klíče serveru.
Privátní klíč slouží k autentizaci lokálně a neposílá se jako soubor serveru.
SSH používá systémovou síťovou trasu včetně případné VPN. Provozovatel serveru
může vést vlastní záznamy relace.
[D12](data-toky.md#d12)

## 4. Rychlostní test

Test začíná na vaši akci a může přenést velké množství dat i přes mobilní síť.
Vybraný LibreSpeed server (preset `rychlost.zelina.eu` nebo vaše vlastní HTTPS URL)
vidí veřejnou zdrojovou IP, čas a provoz testu a identifikaci klienta/verze.
Aplikace používá HTTPS, neodesílá uloženou historii ani seznam Wi-Fi sítí a nevolá
telemetrický endpoint LibreSpeed. Zjištění IP přes `getIP.php` nežádá zjištění ISP
pomocí `isp=true`. Nelze tím zaručit, že provozovatel serveru nevede přístupové logy.
[D05](data-toky.md#d05), [D13](data-toky.md#d13)

NDT7 používá vyhledání serveru M-Lab přes HTTPS a měření přes šifrované WSS.
M-Lab dostává veřejnou IP, výsledky a metadata spojení včetně klienta/verze a času.
**M-Lab výsledky včetně veřejné IP zveřejňuje a uchovává dlouhodobě.** Před prvním
testem NetKit vyžaduje potvrzení upozornění. Podrobnosti a kontakt pro žádosti
jsou v [zásadách M-Lab](https://www.measurementlab.net/privacy/).
[D14](data-toky.md#d14)

## 5. Veřejná IP a poskytovatel

Při otevřeném Přehledu je výchozí zjišťování veřejné IP zapnuté. NetKit volá
Cloudflare přes HTTPS na `1.1.1.1/cdn-cgi/trace` a
`[2606:4700:4700::1111]/cdn-cgi/trace`. Cloudflare vidí veřejnou IP, čas a
User-Agent `NetKit/<verze> (Android)`. Požadavek nepřidává cookies ani identifikátor instalace.

Pro zjištění poskytovatele posílá NetKit DNS TXT dotazy do `asn.cymru.com`.
Dotaz obsahuje veřejnou IP v obráceném zápisu a následně číslo autonomního systému
(ASN). Tyto údaje tedy získává resolver aktivní sítě a služba Team Cymru.
Výsledek zůstává jen v paměti, cache je čerstvá 10 minut. Volba
„Zjišťovat veřejnou IP“ v menu karty Připojení funkci vypne, zruší probíhající
dotazy a vymaže výsledek z paměti. Nevymaže záznamy u příjemců.
[D15](data-toky.md#d15)

## 6. Kontrola aktualizací a otevření webů

Při zapnuté kontrole NetKit v popředí automaticky nejvýš jednou za 24 hodin načte
přes HTTPS soubor `latest.json`: nejdřív z `https://www.daniel.losak.cz/netkit/latest.json`
a jen při chybě nebo neplatné odpovědi z GitHub Releases
(`https://github.com/Losminek/netkit/releases/latest/download/latest.json`, GitHub
přesměruje na své úložiště `release-assets.githubusercontent.com`). Výchozí stav je
zapnuto; v O aplikaci je vypínač „Kontrolovat aktualizace“ a ruční „Zkontrolovat teď“.
Požadavek neobsahuje cookies, identifikátor zařízení ani údaje o síti. Web autora,
jeho hosting Cloudflare a případně GitHub vidí veřejnou zdrojovou IP, čas, požadovanou
cestu a User-Agent `NetKit/<verze>`. Místně se uchovává nastavení, čas posledního pokusu
a úspěchu, zdroj poslední odpovědi, poslední platný `latest.json` a skrytá verze.
Pro GitHub platí jeho [prohlášení o ochraně soukromí](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
[D17](data-toky.md#d17)

Aplikace APK sama nestahuje ani neinstaluje. Zvolený odkaz na aktualizaci otevírá
web autora nebo GitHub v prohlížeči. Také otevření webové správy zařízení probíhá
v prohlížeči. Pro tyto návštěvy platí nastavení prohlížeče a zásady navštíveného webu.
Obrazovka licencí open source v O aplikaci funguje bez sítě.
[D22](data-toky.md#d22)

## 7. Oprávnění a mobilní síť

Přístup k síti a Wi-Fi slouží k diagnostice a připojování. Oprávnění k přesné
a přibližné poloze se žádají kvůli Wi-Fi skenu a přístupu k údajům přístupových bodů;
NetKit nezjišťuje GPS souřadnice ani nesleduje polohu na pozadí. Již udělené
oprávnění může v systémovém stavu mobilní sítě zpřístupnit také jméno operátora.
[D20](data-toky.md#d20)

`READ_PHONE_STATE` slouží k zobrazení obou SIM a typu sítě; žádá se z mobilní karty.
`READ_BASIC_PHONE_STATE` slouží k základnímu typu sítě bez samostatného dialogu.
Mobilní karta používá jméno operátora, lokální index SIM/slot, typ sítě, sílu
signálu, roaming a stav služby. **Nečte telefonní čísla, IMEI, IMSI, ICCID ani
Cell ID**, obsah či historii hovorů. Údaje mobilní karty se neposílají ven
ani neukládají na disk.
[D08](data-toky.md#d08), [D20](data-toky.md#d20)

## 8. Exporty a zálohy

Data se předávají do souboru, schránky nebo jiné aplikace, když použijete export,
kopírování či sdílení. Vybrané úložiště může být cloudové. Exporty WoL a LAN mohou
obsahovat adresy a názvy zařízení; export SSH profilů neobsahuje uložená hesla ani
privátní klíče, ale obsahuje údaje profilů včetně startovacích příkazů.
Sdílený obsah terminálu může obsahovat citlivé údaje. Cíl vybíráte vy.
[D18](data-toky.md#d18)

NetKit v konfiguraci Androidu zakazuje systémové zálohování i přenos soukromých
dat aplikace na jiné zařízení. Toto omezení se nevztahuje na soubory, které sami
exportujete do jiného úložiště.
[D19](data-toky.md#d19)

## 9. Vaše možnosti a mazání

Můžete odebrat oprávnění v nastavení Androidu, odvolat souhlas s automatickým
hledáním v LAN, vypnout veřejnou IP i kontrolu aktualizací.
Uložená WoL zařízení, SSH profily, nepoužívané SSH klíče a historii rychlosti lze
mazat v aplikaci. Pro odstranění všech místních dat včetně nastavení a archivů
použijte vymazání úložiště aplikace v Androidu nebo odinstalaci.
[D21](data-toky.md#d21), [D17](data-toky.md#d17)

Exporty a původní importované klíče smažte samostatně tam, kam jste je uložili.
Smazání místní historie neodstraní data přijatá vzdáleným serverem ani zveřejněná
data M-Lab. Žádosti týkající se těchto dat směřujte příslušnému provozovateli.
[D18](data-toky.md#d18), [D14](data-toky.md#d14)

## 10. Správce, právní základ, práva a změny

Správce: Daniel Lošák, kontakt info@losak.cz. Na stejnou adresu můžete posílat žádosti
o ochranu osobních údajů i důvěrná hlášení zranitelností. Do veřejných hlášení nevkládejte
tajemství ani osobní údaje.

Správcem je autor jen u dat, která dostává jeho web (kontrola aktualizací a návštěvy webu).
Místní data v telefonu správce nevidí a nemá k nim vzdálený přístup; spojení na jiné služby
(LibreSpeed, M-Lab, Cloudflare trace, Team Cymru, GitHub, vaše servery) zpracovávají jejich
provozovatelé podle svých zásad.
[D01](data-toky.md#d01), [D02–D06](data-toky.md#d02)

### Web a kontrola aktualizací

Hosting zajišťuje Cloudflare Workers. Požadavky na web včetně `/netkit/latest.json`
zpřístupňují hostingu veřejnou zdrojovou IP, čas, požadovanou cestu a User-Agent. Aplikace
automaticky načítá tento soubor jen v popředí, nejvýš jednou za 24 hodin; ruční kontrolu
spouštíte sami. Právním základem zpracování je oprávněný zájem správce na provozu
a zabezpečení webu a kontroly aktualizací (čl. 6 odst. 1 písm. f) GDPR).
[D17](data-toky.md#d17)

Záznamy jednotlivých požadavků (Workers Logs) jsou vypnuté. Souhrnné metriky provozu jsou
v účtu dostupné nejvýš tři měsíce zpětně. Požadavky, které ochrana Cloudflare zablokuje nebo
podrobí bezpečnostní výzvě, se v účtu zobrazují jako bezpečnostní události (IP adresa, země,
User-Agent, požadovaná adresa) nejvýš 31 dní. Údaje, které Cloudflare zpracovává pro
zabezpečení vlastní sítě, uchovává podle svých zásad. Tento text neslibuje mazání ani dobu
uchování dat u cizích služeb, které aplikace kontaktuje přímo.

Na stránkách NetKitu se nenačítá Turnstile ani skript analytiky. Cloudflare může při
podezřelém provozu u kterékoli stránky webu zobrazit bezpečnostní výzvu a po jejím splnění
uložit nezbytnou cookie; popisuje ji stránka Cookies a úložiště. Web používá pouze agregované
statistiky na straně Cloudflare (edge), bez analytického skriptu v prohlížeči. Cloudflare
Turnstile chrání pouze kontaktní formulář webu a načte se až při práci s ním; Resend slouží
pouze k odeslání zprávy z tohoto formuláře. Tyto služby aplikace při kontrole aktualizací
nepoužívá. Podrobnosti kontaktního formuláře a úložišť uvádějí zásady soukromí webu
a Cookies a úložiště.

### Předání do USA

Předání do USA ze strany správce se týká jen hostingu webu: Cloudflare, Inc. (USA)
zpracovává pro správce požadavky na web včetně `/netkit/latest.json` jako zpracovatel.
Předání se opírá o prováděcí rozhodnutí Komise (EU) 2023/1795 o rámci EU-US Data Privacy
Framework, v němž je Cloudflare certifikován; pro případ, že by se rámec neuplatnil,
sjednává smlouva o zpracování standardní smluvní doložky. GitHub, Inc. (USA) aplikace
kontaktuje přímo, a to jen při chybě nebo neplatné odpovědi webu u kontroly aktualizací;
odkazy na GitHub otevíráte sami v prohlížeči. GitHub tu není příjemcem ani zpracovatelem
správce, je samostatným správcem certifikovaným v témže rámci a údaje zpracovává podle
svých zásad.
[D22](data-toky.md#d22)

### Vaše práva

Máte právo na přístup, opravu, výmaz a omezení zpracování (čl. 15–18 GDPR). Stížnost můžete
podat u Úřadu pro ochranu osobních údajů. Úplný přehled práv a způsob jejich uplatnění
uvádějí zásady soukromí webu.

### Námitka podle čl. 21 GDPR

Z důvodů týkajících se vaší konkrétní situace máte právo kdykoli vznést námitku proti
zpracování osobních údajů, které je založeno na oprávněném zájmu správce (čl. 21 odst. 1
GDPR), tedy proti zpracování k provozu a zabezpečení webu a ke kontrole aktualizací.
Námitku pošlete e-mailem na info@losak.cz.

Po námitce správce údaje dál nezpracovává, pokud neprokáže závažné oprávněné důvody pro
zpracování, které převažují nad vašimi zájmy nebo právy a svobodami, nebo pokud údaje
nepotřebuje k určení, výkonu či obhajobě právních nároků.

Změny tohoto textu budou zveřejněny na výše uvedené adrese s novým datem účinnosti.
