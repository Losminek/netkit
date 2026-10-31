# NetKit — privacy policy

**English translation for convenience. The binding text is the Czech page
<https://www.daniel.losak.cz/netkit/privacy>, effective from 31 October 2026.**
Controller and contact: Daniel Lošák, info@losak.cz.

This file is the technical source of the web page. Sections 1–9 translate the template the
website took over unchanged (revision `5178db9`); section 10 mirrors the website. References
D01–D22 link to the technical evidence and are not on the website. If this translation and
the Czech website differ, the website prevails.

## 1. Why NetKit uses data

NetKit is a tool for diagnosing and managing networks you own or have permission
to use. It has no user account or server for storing your profiles. It contains
no advertising, usage analytics or automatic crash reporting to the author.
Some features communicate with devices on your network or with the internet
services described below. A connection's recipient sees its source IP address,
time and traffic metadata.
[D01](data-toky.md#d01), [D09–D17](data-toky.md#d09)

## 2. What is stored in the app

The app's private storage contains Wake-on-LAN devices (including MAC and target),
SSH profiles (name, address, username, startup command), public keys and SSH
server trust records. The app does not encrypt this metadata. Do not put
passwords in names, notes or startup commands.
[D02](data-toky.md#d02), [D03](data-toky.md#d03)

A password is saved if you choose to remember it. Saved SSH passwords and private
keys are stored in files encrypted with AES-GCM; Android Keystore protects the
encryption key. Hardware protection depends on the phone. Secrets are also
held temporarily in memory while in use. The app does not delete the original
file when you import a key.
[D04](data-toky.md#d04), [D18](data-toky.md#d18)

Speed history retains the latest 20 results, time, server, network type and available
Wi-Fi details including network name (SSID), frequency, signal and link rate.
The custom server address, acknowledgement of the M-Lab notice and app settings
are also stored. Automatic LAN discovery consent is associated with a network
using a local HMAC fingerprint; the SSID itself is not readable in that consent record.
[D05](data-toky.md#d05), [D06](data-toky.md#d06)

Wi-Fi and LAN discovery results, live charts, public IP and mobile network card
details are normally held in memory, not in permanent history. Exceptions are
the Wi-Fi details in speed history described above and exports you create.
NetKit reads byte counters for its traffic chart, not other users' packet contents.
[D07](data-toky.md#d07), [D08](data-toky.md#d08), [D15](data-toky.md#d15)

## 3. Network diagnostics, Wake-on-LAN and SSH

DNS queries contain the name or IP being looked up and go to the relevant network's
resolver (and potentially further DNS servers). Ping, traceroute and port tests
send probes to selected targets. Device discovery also uses mDNS, SSDP and NetBIOS.
Automatic LAN discovery starts only after your consent for that network; you can
also search manually. UPnP descriptions are fetched using unencrypted HTTP only
from a validated address of a discovered device in your own subnet. These probes
and local discovery protocols are not encrypted; DNS encryption depends on the
resolver and route used.
[D09](data-toky.md#d09), [D10](data-toky.md#d10)

The live Overview automatically measures gateway and internet latency (default
target `1.1.1.1`, Cloudflare) and DNS latency (default name `one.one.one.one`).
Probes run while the live Overview is active and stop when you leave it or put
the app in the background. You can change the targets. Disabling public IP lookup
does not disable these probes.
[D16](data-toky.md#d16)

Wake-on-LAN sends an unencrypted packet containing the target device's MAC address
to your selected destination or broadcast address. It can optionally check for
a response. Both the packet and target DNS lookup use the underlying Wi-Fi outside
the VPN.
[D11](data-toky.md#d11)

SSH sends the username, authentication data required by the selected method,
commands and terminal input to the selected server over an encrypted SSH
connection with server key verification. The private key is used locally for
authentication and is not sent to the server as a file. SSH uses the system
network route, including a VPN if present. The server operator may keep its own
session records.
[D12](data-toky.md#d12)

## 4. Speed tests

A test starts on your action and may transfer large amounts of data, including
over a mobile network. The selected LibreSpeed server (the `rychlost.zelina.eu`
preset or your own HTTPS URL) sees the public source IP, test time and traffic,
and client/version identification. The app uses HTTPS, does not upload stored
history or a list of Wi-Fi networks, and does not call LibreSpeed's telemetry
endpoint. Its `getIP.php` lookup does not request ISP lookup using `isp=true`.
This does not guarantee that the server operator keeps no access logs.
[D05](data-toky.md#d05), [D13](data-toky.md#d13)

NDT7 uses HTTPS to locate an M-Lab server and encrypted WSS for measurement.
M-Lab receives the public IP, results and connection metadata including the
client/version and time. **M-Lab publishes results including the public IP and
retains them long term.** NetKit requires acknowledgement of a notice before
the first test. See [M-Lab's privacy policy](https://www.measurementlab.net/privacy/)
for details and a contact for requests.
[D14](data-toky.md#d14)

## 5. Public IP and provider

Public IP lookup is enabled by default while Overview is open. NetKit calls
Cloudflare over HTTPS at `1.1.1.1/cdn-cgi/trace` and
`[2606:4700:4700::1111]/cdn-cgi/trace`. Cloudflare sees the public IP, time and
User-Agent `NetKit/<version> (Android)`. The request adds no cookies or installation identifier.

To identify the provider, NetKit sends DNS TXT queries to `asn.cymru.com`.
The query contains the public IP in reverse notation and then the autonomous
system number (ASN). The active network's resolver and Team Cymru therefore
receive those details. Results stay in memory only, with a cache freshness of
10 minutes. “Look up public IP” in the Connection card menu disables the feature,
cancels ongoing queries and clears the result from memory. It does not delete
records held by recipients.
[D15](data-toky.md#d15)

## 6. Update checks and opening websites

When checks are enabled, NetKit automatically fetches a `latest.json` file over HTTPS
at most once per 24 hours while the app is in the foreground: first from
`https://www.daniel.losak.cz/netkit/latest.json` and only after an error or invalid answer from
GitHub Releases (`https://github.com/Losminek/netkit/releases/latest/download/latest.json`;
GitHub redirects to its storage `release-assets.githubusercontent.com`). Checks are enabled
by default; About provides a “Check for updates” toggle and a manual “Check now” action.
The request carries no cookies, device identifier or network details. The author's website,
its Cloudflare hosting and, if used, GitHub see the public source IP, time, requested path
and User-Agent `NetKit/<version>`. Stored locally: the setting, time of the last attempt and
success, which source answered last, the last valid `latest.json` and a hidden version.
GitHub's [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement) applies to GitHub.
[D17](data-toky.md#d17)

The app does not download or install APKs itself. A selected update link opens
the author's website or GitHub in a browser. Opening a device's web administration
page also uses a browser. Browser settings and the visited website's policies
apply to these visits. The open-source licences screen in About works offline.
[D22](data-toky.md#d22)

## 7. Permissions and mobile networks

Network and Wi-Fi access support diagnostics and connections. Precise and
approximate location permissions are requested for Wi-Fi scanning and access
point details; NetKit does not obtain GPS coordinates or track background location.
An already granted location permission may also expose the operator name in the
system's mobile network service state.
[D20](data-toky.md#d20)

`READ_PHONE_STATE` supports displaying both SIMs and the network type; it is
requested from the mobile card. `READ_BASIC_PHONE_STATE` supports basic network
type information without a separate dialog. The mobile card uses the operator
name, local SIM index/slot, network type, signal strength, roaming and service
state. **It does not read phone numbers, IMEI, IMSI, ICCID or Cell ID**, or call
contents or history. Mobile card details are neither sent out nor saved to disk.
[D08](data-toky.md#d08), [D20](data-toky.md#d20)

## 8. Exports and backups

Data is passed to a file, clipboard or another app when you export, copy or share.
The selected storage provider may be a cloud service. WoL and LAN exports can
contain device addresses and names; SSH profile exports contain no saved passwords
or private keys, but do include profile details and startup commands. Shared
terminal contents may include sensitive information. You choose the destination.
[D18](data-toky.md#d18)

NetKit's Android configuration disables system backup and transfer of the app's
private data to another device. This does not cover files you export to other storage.
[D19](data-toky.md#d19)

## 9. Your controls and deletion

You can revoke permissions in Android settings, revoke automatic LAN discovery
consent, disable public IP lookup and update checks.
You can delete saved WoL devices, SSH profiles, unused SSH keys and speed history
in the app. To remove all local data including settings and archives, clear the
app's storage in Android or uninstall it.
[D21](data-toky.md#d21), [D17](data-toky.md#d17)

Delete exports and original imported keys separately where you saved them.
Deleting local history does not remove data already received by a remote server
or published by M-Lab. Send requests about that data to the relevant operator.
[D18](data-toky.md#d18), [D14](data-toky.md#d14)

## 10. Controller, legal basis, rights and changes

Controller: Daniel Lošák, contact info@losak.cz. You can send data protection requests and
confidential vulnerability reports to the same address. Do not put secrets or personal data
into public reports.

The author is a controller only for data received by his website (update checks and website
visits). The controller cannot see local data on your phone and has no remote access to it;
connections to other services (LibreSpeed, M-Lab, Cloudflare trace, Team Cymru, GitHub, your
own servers) are processed by their operators under their own policies.
[D01](data-toky.md#d01), [D02–D06](data-toky.md#d02)

### Website and update checks

The website is hosted on Cloudflare Workers. Requests to the website, including
`/netkit/latest.json`, expose the public source IP, time, requested path and User-Agent to
the hosting. The app fetches this file automatically only in the foreground, at most once
per 24 hours; you start a manual check yourself. The legal basis is the controller's
legitimate interest in operating and securing the website and the update check
(Art. 6(1)(f) GDPR).
[D17](data-toky.md#d17)

Records of individual requests (Workers Logs) are disabled. Aggregate traffic metrics are
available in the account for at most three months back. Requests that Cloudflare's protection
blocks or challenges appear in the account as security events (IP address, country,
User-Agent, requested address) for at most 31 days. Data Cloudflare processes to secure its
own network is kept under its own policies. This text makes no promise about deletion or
retention by third-party services the app contacts directly.

NetKit pages load neither Turnstile nor an analytics script. With suspicious traffic,
Cloudflare may show a security challenge on any page of the website and, once it is passed,
store a strictly necessary cookie; the Cookies and storage page describes it. The website uses
only aggregate statistics on Cloudflare's side (edge), without an analytics script in the
browser. Cloudflare Turnstile protects only the website's contact form and loads only when you
use it; Resend only sends messages from that form. The app does not use these services for
update checks. The website's privacy policy and the Cookies and storage page describe the
contact form and storage in detail.

### Transfers to the USA

The controller's transfers to the USA concern only the website hosting: Cloudflare, Inc. (USA)
processes requests to the website, including `/netkit/latest.json`, as the controller's
processor. The transfer relies on Commission Implementing Decision (EU) 2023/1795 on the
EU-US Data Privacy Framework, under which Cloudflare is certified; should the framework not
apply, the data processing agreement provides standard contractual clauses. The app contacts
GitHub, Inc. (USA) directly and only after an error or invalid answer from the website during
an update check; you open links to GitHub yourself in a browser. GitHub is not the
controller's recipient or processor here; it is a separate controller certified under the
same framework and processes data under its own policies.
[D22](data-toky.md#d22)

### Your rights

You have the right of access, rectification, erasure and restriction of processing
(Art. 15–18 GDPR). You can lodge a complaint with the Czech Office for Personal Data
Protection. The website's privacy policy lists all rights and how to exercise them.

### Objection under Art. 21 GDPR

On grounds relating to your particular situation, you have the right to object at any time
to processing based on the controller's legitimate interest (Art. 21(1) GDPR), that is, to
processing for operating and securing the website and for update checks. Send the objection
by e-mail to info@losak.cz.

After an objection, the controller no longer processes the data unless it demonstrates
compelling legitimate grounds that override your interests, rights and freedoms, or needs the
data for the establishment, exercise or defence of legal claims.

Changes to this text will be published at the address above with a new effective date.
