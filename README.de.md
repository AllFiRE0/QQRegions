# QQRegions — Wiki

> ### Sprache
>
> | [Русский](README.md) | [English](README.en.md) | [Português](README.pt.md) | [**Deutsch**](README.de.md) | [中文](README.zh.md) | [한국어](README.ko.md) |
> |:-:|:-:|:-:|:-:|:-:|:-:|

> Leistungsstarke Regionsverwaltung auf Basis von **WorldGuard** für **Paper / Leaf (api 26.2)**-Server.

```
/region ...   /territory ...   /tr ...   /rg ...   /private ...   /zone ...
```

Das Plugin deckt den gesamten Privatisierungszyklus auf dem Server ab:
interaktive Auswahl, Erstellen und Löschen von Regionen, Flag-Menü,
Markierung von Grenzen, Verwaltung von Mitgliedern, Markt für Verkauf/Vermietung,
Flag- und Erweiterungs-Shop, Clan-Raids — und die gesamte Lokalisierung in
einer einzigen Datei.

---

## Inhaltsverzeichnis

- [1. Anforderungen und Installation](#1-anforderungen-und-installation)
- [2. Schnellstart](#2-schnellstart)
- [3. Befehle](#3-befehle)
- [4. Rechte (Permissions)](#4-rechte-permissions)
- [5. Platzhalter](#5-platzhalter)
- [6. Menüs](#6-menüs)
- [7. Konfiguration](#7-konfiguration)
- [8. Erweiterte Konfiguration](#8-erweiterte-konfiguration)
- [9. FAQ und Problemlösung](#9-faq-und-problemlösung)

---

## 1. Anforderungen und Installation

### 1.1 Anforderungen

| Abhängigkeit | Typ | Wofür |
|---|---|---|
| [WorldGuard](https://dev.bukkit.org/projects/worldguard) | **verpflichtend** | Regionen-Kern |
| Vault + Wirtschafts-Plugin (EssentialsX, CMI…) | weich | Markt und Shop |
| PlaceholderAPI | weich | `%qqregions_*%` und beliebige `%…%` in Menüs |
| JustTeams | weich | Clan-Raids |
| LuckPerms | weich | Auswahl-Berechtigungsvorlagen |
| WorldGuardExtraFlagsPlus | weich | zusätzliche Flags / Platzhalter |

Das Plugin funktioniert ohne weiche Abhängigkeiten: Eine nicht verfügbare
Mechanik antwortet dem Spieler einfach mit der entsprechenden Meldung aus
`lang.yml`.

### 1.2 Installation

1. Installieren Sie **WorldGuard** (verpflichtend).
2. Legen Sie `QQRegions.jar` in `plugins/` ab.
3. Starten Sie den Server neu — es werden `config.yml`, `lang.yml`,
   `replace.yml`, `shop.yml`, `menus/*.yml` erstellt (und `data.yml` bei der
   ersten Aktion).
4. Konfigurieren Sie das Plugin und führen Sie `/region reload` aus.

### 1.3 Erster Start

Nach dem ersten Start im Plugin-Ordner:

| Datei | Zweck |
|---|---|
| `config.yml` | alle Plugin-Einstellungen |
| `lang.yml` | alle Texte für Spieler/Admin/Konsole (eine Plugin-Sprache) |
| `replace.yml` | Übersetzung von „rohen“ Flag-Werten (yes/no, allow/deny) |
| `shop.yml` | Shop: Flag-Preise, Pakete „+Fläche“, „+Region“ |
| `data.yml` | Käufe der Spieler, Angebote, Mieten (wird automatisch erstellt) |
| `menus/*.yml` | alle GUIs: info, flags, players, playerconfirm, market, marketconfirm, flagshop, blocks, myflags, help u. a. |

### 1.4 Aliase

Name und Aliase des Befehls werden in `config.yml` festgelegt:

```yaml
command:
  name: "region"
  aliases:
    - "territory"
    - "tr"
    - "rg"
    - "private"
    - "zone"
```

Aliase werden bei `/region reload` übernommen, ohne den Server neu zu starten.
Weiter unten steht `/region` für jeden beliebigen Alias.

---

## 2. Schnellstart

### Schritt 1. Auswahl

```text
/region select
```

Der Spieler erhält Hotbar-Buttons: **„Region erstellen“**, **„Punkt 1“**,
**„Punkt 2“**, **„Bereich auswählen“**, **„Auswahl zurücksetzen“**, **„Abbrechen“**.
Die Button-Slots werden in `config.yml` (`interactive.buttons.<id>.slot`)
konfiguriert. Die Sitzung speichert das Inventar des Spielers und stellt es
beim Verlassen wieder her.

![Interaktive Auswahl](docs/screenshots/02.png)

- **LMB / RMB** mit dem Button in der Hand — Button-Aktion ausführen (in der
  Luft und auf Blöcken);
- **der „Punkt 1/2“-Button** — schnelle Punktsetzung per Sichtlinie (bis zu 300
  Blöcke), sonst an der Spielerposition;
- **LMB / RMB** mit leerer Hand — aktiven Punkt wechseln (1 oder 2);
- **Mausrad oder Tasten 4/6** — Punkt verschieben (im Select-Modus bleibt der
  gewählte Slot immer zentriert — `interactive.select-center-slot`,
  daher funktionieren Tastatur-Slotwechsel wie das Rad);
- **Shift + Mausrad** — Bewegung ×`wheel-shift-speed`;
- **Shift + LMB** — Auswahl bestätigen.

Während der Sitzung werden Befehle aus `interactive.blocked-commands`
(standardmäßig `ah`, `sell`, `shop`, `baltop`) blockiert — Schutz davor, die
Buttons über Auktion/Shops „wegzuwerfen“.

Namen und Beschreibungen von Buttons, Panels und beliebigen Gegenständen
unterstützen **MiniMessage**: Verläufe (`<gradient:#55ffff:#ff55ff>`),
`<rainbow>`, `<color:#RRGGBB>`; ein bloßes `#RRGGBB` direkt im Text wird
ebenfalls eingefärbt.

### Schritt 2. Region erstellen

```text
/region create <Name>
```

Nach der Bestätigung der Auswahl fordert das Plugin zur Eingabe eines Namens
im Chat auf (`cancel` — abbrechen, die Auswahl bleibt erhalten). Der Name wird
validiert:

- anhand von `region-name.regex` (standardmäßig `[A-Za-zА-Яа-я0-9_-]{3,32}`);
- gegen verbotene Regionen aus `restrictions.banned-regions`;
- gegen den `regions.max-regions`-Limit + gekaufte „+Region“-Pakete.

![Eingabe des Regionsnamens](docs/screenshots/04.png)

### Schritt 3. Flags und Mitglieder

```text
/region flags <Region>     — Flag-Menü (LMB — an/aus/Standard, RMB — Gruppe)
/region add <Spieler> <Region> — Mitgliedschaft vergeben
/region add owner <Spieler> <Region>
```

Ein Flag wird dem Spieler nur mit der Berechtigung
`qqregions.flags.use.<Flag>` angezeigt (Admins sehen alles; das
Legacy-Recht `qqregions.flags.<Flag>` wird ebenfalls akzeptiert). Ein im Shop
gekauftes Flag wird dem Besitzer ganz ohne Recht angezeigt.

### Schritt 4. Grenzmarkierung

```text
/region visible <Region> [particles|blocks|territory]
/region visible off        — alle Markierungen ausblenden
```

Die Markierung kann über das Flag `territory-visible` (im Flag-Menü) an eine
Region gekoppelt werden: Beim Betreten leuchten die Grenzen automatisch auf und
erlöschen nach `highlight.region-hide-seconds`. Das Regionen-Timeout ist
unabhängig von Auswahlen (`interactive.view-hide-after`). Eigene Regionen
(Besitzer/Mitglied) innerhalb von `highlight.auto-show-radius` leuchten
genauso — **nur** bei `territory-visible: allow`.

![Grenzmarkierung einer Region](docs/screenshots/07.png)

### Schritt 5. Markt und Shop

```text
/region market                — Markt-Menü
/region market flags          — Flag-Shop
/region market blocks         — Erweiterungen (Fläche, „+Region“)
/region sell <Betrag> [Region] — Region zum Verkauf anbieten (öffentliches Angebot)
/region rent <Betrag> <Zeit> [Region] — Region vermieten (öffentliches Angebot)
/region buy [Region]          — Region aus einem öffentlichen Angebot kaufen
/region tenant [Region]       — aus einem öffentlichen Angebot mieten
```

---

## 3. Befehle

Die vollständige Liste ist auch im Spiel verfügbar: `/region help`
(Textausgabe) oder der **„Hilfe“**-Button im Menü (alle Vorlagen).

### 3.1 Auswahl

| Befehl | Beschreibung |
|---|---|
| `/region select` | Interaktiver Modus (Hotbar-Buttons) |
| `/region select pos <1\|2>` | Punkt an der Spielerposition setzen |
| `/region select point <1\|2>` | Synonym zu `pos` |
| `/region select max` | Maximal von der Vorlage erlaubte Fläche um den Spieler |
| `/region select chunk [N]` | Bereich aus N Chunks auswählen (Standard — Vorlagenlimit) |
| `/region select expand [Seite] <N>` | Um N Blöcke erweitern (oder `-N` — verkleinern) |
| `/region select outset <N> [h\|v]` | In alle Richtungen erweitern (h — horizontal, v — vertikal) |
| `/region select view <Spieler>` | Auswahl eines anderen Spielers ansehen |

**Seiten** von `expand`: `north`, `south`, `east`, `west`, `up`, `down`.

Das Ergebnis von Befehlsauswahlen ist sichtbar: Statusleiste am Ende von
`select`, plus Markierung `interactive.command-selection-view` und
Auto-Ausblendung `command-selection-hide-after`.

### 3.2 Regionen

| Befehl | Beschreibung |
|---|---|
| `/region create <Name>` | Region aus der aktuellen Auswahl erstellen |
| `/region delete [Name]` | Eigene Region löschen (oder die Region, in der man steht) |
| `/region info [Name]` | Info-Menü der Region öffnen |
| `/region flags [Name]` | Flag-Menü der Region öffnen |
| `/region reload` | Alle Konfigurationen neu laden (+ Aliase, + Sprache) |

### 3.3 Mitglieder und Besitzer

| Befehl | Beschreibung |
|---|---|
| `/region add <Spieler> [Region]` | Spieler als Mitglied hinzufügen |
| `/region add member <Spieler> [Region]` | Dasselbe, explizit |
| `/region add owner <Spieler> [Region]` | Zu Besitzern hinzufügen |
| `/region remove <Spieler> [Region]` | Mitglied entfernen |
| `/region remove owner <Spieler> [Region]` | Besitzer entfernen (der letzte kann nicht entfernt werden) |

### 3.4 Markierung

| Befehl | Beschreibung |
|---|---|
| `/region visible [Name] [Typ]` | Grenzen der Region anzeigen |
| `/region visible off` | Alle aktiven Markierungen ausblenden |
| `/region visible type [Typ]` | Standard-Typ (ohne Argument — aktuellen anzeigen) |
| `/region visible self on\|off` | Persönliche Markierung „für sich selbst“: wirkt nur auf das Flag `territory-visible` (Befehl und Menü zeigen immer an) |
| `/region visible true\|allow\|false\|deny [Typ] [Region]` | Markierungs-Flag der Region |
| `/region view [Name] [Typ]` | Grenzen vorübergehend anzeigen (ohne Änderung des Flags) |

**Typen:** `particles`, `blocks` (glühende Displays), `territory`
(Kontur entlang des Reliefs). Bei Regionen vom Typ `particles`/`blocks`
funktioniert das interne Gitter `outline.grid` — Quadrate auf **allen 6
Seiten** (oben, unten und vier Seiten); eine Spielerauswahl zeichnet **nur 12
Kanten** (genau wie zuvor, ohne Ringe und Gitter — siehe
`outline.rings`/`grid`, die nur bei Regionsmarkierungen funktionieren).
`territory` + `blocks` — nur ein Zaun aus den angegebenen Blöcken.

### 3.5 Markt (Vault)

| Befehl | Beschreibung |
|---|---|
| `/region market` | Markt-Menü: Angebotsliste, Suche, Sortierung |
| `/region market flags` | Flag-Shop |
| `/region market blocks` | Erweiterungen: Flächenpakete und „+Region“ |
| `/region sell <Betrag> [Region]` | Region zum Verkauf anbieten — **öffentliches Angebot** (jeder kauft sofort) |
| `/region sell <Spieler> <Betrag> [Region]` | Kauf einem bestimmten Spieler anbieten (privates Angebot) |
| `/region rent <Betrag> <Zeit> [Region]` | Region vermieten — **öffentliches Angebot** (jeder mietet sofort) |
| `/region rent <Spieler> <Betrag> <Zeit> [Region]` | Vermietung einem bestimmten Spieler anbieten |
| `/region rent dur <Minuten> [Region]` | Gültigkeitsdauer des Miet-Angebots (Standard — `market.list-duration-minutes`) |
| `/region buy [Region]` | Region aus einem öffentlichen Angebot kaufen |
| `/region tenant [Region]` | Region aus einem öffentlichen Angebot mieten |
| `/region sell\|rent\|buy\|tenant accept\|decline\|cancel <id>` | Angebot/Anzeige annehmen/ablehnen/abbrechen |
| `/region market myflags` | Menü der gekauften Flags |

**Mietzeit:** `30` (Minuten), `2h` (Stunden), `7d` (Tage), `1w` (Wochen),
`1m` (Monate), `1y` (Jahre). Standard — 1 Woche.

**Öffentliche Angebote** werden von jedem Spieler angenommen, außer vom
Verkäufer/Besitzer. Ist Auto-Rückgabe aktiviert
(`market.rent.auto-rent`, Auto-Rückgabe-Button im Markt-Menü), erscheint das
Angebot nach Ende der Miete wieder auf dem Markt für
`list-duration-minutes`.

### 3.6 Clan-Raids (JustTeams)

| Befehl | Beschreibung |
|---|---|
| `/region raid [Region]` | Raid starten (der „Raid“-Button im Info-Menü ist nur für Außenstehende) |

---

## 4. Rechte (Permissions)

Basisrechte werden allen automatisch erteilt; Dienst- und `bypass`-Rechte nur
Operatoren.

| Recht | Standard | Beschreibung |
|---|---|---|
| `qqregions.use` | `true` | Basis-Zugriff auf Befehle |
| `qqregions.create` | `true` | Regionen erstellen |
| `qqregions.delete` | `true` | Regionen löschen |
| `qqregions.select` | `true` | Auswahl |
| `qqregions.info` | `true` | Informationen über die Region |
| `qqregions.manage` | `true` | Mitglieder/Besitzer verwalten |
| `qqregions.flags` | `true` | Flag-Menü |
| `qqregions.visible` | `true` | Grenzmarkierung |
| `qqregions.market` | `true` | Markt (sell/rent/buy/tenant/market) |
| `qqregions.raid` | `true` | Clan-Raids (JustTeams) |
| `qqregions.reload` | `op` | Konfigurationen neu laden |
| `qqregions.admin` | `op` | Voller Zugriff, auch auf fremde Regionen |
| `qqregions.bypass.selection-limits` | `op` | `max-blocks`/`min-blocks` der Vorlagen ignorieren |
| `qqregions.bypass.disabled-worlds` | `op` | In Welten aus `disabled-worlds` arbeiten |
| `qqregions.bypass.banned-regions` | `op` | Mit `banned-regions` arbeiten |

`qqregions.admin` umfasst: `reload`, `create`, `delete`, `select`,
`info`, `manage`, `flags`, `visible`, `market`, `raid`.

Zusätzlich wird für das Flag-Menü das dynamische Recht
**`qqregions.flags.use.<Flag>`** verwendet (z. B. `qqregions.flags.use.pvp`):
Es zeigt das Flag im Flag-Menü **und im Flag-Shop** und erlaubt, es zu
ändern/zu kaufen. Das Recht `qqregions.admin` sieht/ändert/kauft alles.
Eine einfache Option — das Privileg `qqregions.flags.use.*` vergeben
(z. B. in der LuckPerms-Spielergruppe): Dann erhält der Spieler Zugriff auf
alle Flags. Das Legacy-Recht `qqregions.flags.<Flag>` wird ebenfalls akzeptiert
(für Kompatibilität).

**Gruppen-Vorlagen (`config.yml` → `flag-groups`)** — Vergabe von Flags „am
Stück“: Ein Spieler mit dem Recht `qqregions.flags.group.<Name>` erhält
automatisch alle Flags aus der Liste dieser Gruppe (sehen/ändern/kaufen).
Mehrere Gruppen werden summiert; `["*"]` in der Liste = alle Flags.
Funktioniert zusammen mit einzelnen `qqregions.flags.use.<Flag>`.

> Beispiel mit Gruppen: Geben Sie der Gruppe „Neulinge“ `qqregions.flags.group.newbie`
> (im Config — `pvp`, `build`) und der Gruppe „Veteranen“ `qqregions.flags.group.veteran`
> (eine breite Liste) — Neulinge sehen im Shop und Flag-Menü nur ihren Satz,
> Veteranen ihren eigenen; ein gemeinsames Recht kann punktuell über
> `qqregions.flags.use.<Flag>` ergänzt werden.

> Hinweis: Die `bypass.*`-Paare und `admin` sind in LuckPerms sinnvollerweise
> bestimmten Gruppen zu vergeben (siehe Abschnitt zu Vorlagen).

---

## 5. Platzhalter

Das Plugin unterstützt zwei Arten von Platzhaltern:

1. **Interne** — `{Name}` — werden vom Plugin in `lang.yml`, `menus/*.yml`,
   Texten des `config.yml` und Raid-Benachrichtigungen ersetzt.
2. **Externe PAPI** — `%qqregions_*%` — aus der eigenen QQRegions-Erweiterung
   sowie beliebige öffentliche (z. B. `%vault_eco_balance%`).

### 5.1 Allgemein für Menüs

`{region}` `{world}` `{player}` `{role}` `{page}` `{pages}`

### 5.2 Nach Kontext

| Kontext | Platzhalter |
|---|---|
| Info-Menü | `{owners}` `{members}` `{type}` `{area}` `{volume}` `{priority}` `{status}` `{my-regions}` `{max-regions}` `{max-blocks}` |
| Flag-Menü | `{flag}` `{flag-name}` `{flag-value}` `{flag-value-label}` `{flag-raw}` `{group}` `{group-label}` `{groups-list}` `{flag-group}` `{flag-group-label}` `{flag-with-group}` `{next-state}` |
| Spieler-Menü | `{player}` `{role}` `{role-ru}` `{player-id}` |
| Spielersuche | `{ps-group}` `{ps-balance}` `{ps-balance-symbol}` `{ps-regions}` `{ps-max}` `{ps-clan}` `{ps-sort-list}` |
| Bestätigung (playerconfirm) | `{pc-player}` `{pc-role}` `{pc-action}` `{pc-balance}` `{pc-balance-symbol}` `{pc-clan}` `{pc-regions}` `{pc-reg-owner}` `{pc-reg-member}` `{pc-max}` |
| Territorium-Auswahl | `{rp-world}` `{rp-type}` `{rp-people}` `{rp-area}` `{rp-dist}` `{rp-sort-list}` |
| Markt-Menü | `{market-type}` `{market-region}` `{market-world}` `{market-price}` `{market-price-symbol}` `{market-who}` `{market-owner}` `{market-status}` |
| Markt-Hologramm (market-holo) | `{owner}` (Nick des Verkäufers/Besitzers) `{price}` `{price-symbol}` `{nick}` `{time}` `{region}` |
| Flag-Shop | `{flag-name}` `{flag}` `{price}` `{price-symbol}` |
| Erweiterungs-Shop | `{pack-name}` `{name}` `{pack-amount}` `{price}` `{price-symbol}` |
| Info-Menü (Raid-Button) | `{raid-clan}` `{raid-balance}` `{raid-balance-symbol}` `{raid-online}` `{raid-total}` `{raid-in-region}` `{raid-needed}` |
| Auswahl-Bossbar | `{current}` `{max}` `{percent}` `{player}` `{value-color}` |
| Extra-Info-Actionbar | `{height-top}` `{height-bottom}` `{conflict}` `{conflict-regions}` `{conflict-count}` `{current}` `{max}` `{percent}` `{player}` |
| Raid (Bars/Benachrichtigungen) | `{region}` `{world}` `{clan}` `{count}` `{total}` `{thief}` `{time}` `{percent}` `{player}` |

> Alle Geld-Platzhalter (`{price}`, `{market-price}`, `{raid-balance}`,
> `{ps-balance}`, `{pc-balance}`) liefern **nur die Zahl** (gemäß Format
> `market.economy`). Das Währungssymbol setzen Sie separat ein — jeder hat ein
> `-symbol`-Gegenstück: `{price} {price-symbol}` usw. Ist kein `-symbol` in der
> Vorlage, wird die Zahl ohne Symbol ausgegeben.

### 5.3 Externe PAPI-Platzhalter `%qqregions_*%`

Die Erweiterung wird automatisch registriert, wenn PlaceholderAPI installiert
ist. Ganz abschalten kann man sie über `placeholders.enabled` in `config.yml`;
die Listentrenner (`owners-separator`, `members-separator`, `owned-separator`,
`membered-separator`) stehen ebenfalls dort, im Abschnitt `placeholders`.

**Auswahl**

| Platzhalter | Wert |
|---|---|
| `%qqregions_selection_active%` | `yes`/`no` — gibt es eine Auswahl |
| `%qqregions_selection_blocks%` | Blöcke in der Auswahl |
| `%qqregions_selection_max_blocks%` | Vorlagenlimit (oder ∞ bei bypass) |
| `%qqregions_selection_min_blocks%` | Mindestgröße |
| `%qqregions_selection_chunks%` | Chunk-Limit gemäß Vorlage |
| `%qqregions_selection_percent%` | Prozent des Limits (0–100) |
| `%qqregions_selection_over_limit%` | `yes`/`no` |
| `%qqregions_selection_below_min%` | `yes`/`no` |
| `%qqregions_selection_conflict%` | `yes`/`no` — überschneidet fremde Regionen |
| `%qqregions_selection_conflict_count%` | Anzahl der Konfliktregionen |
| `%qqregions_selection_conflict_regions%` | deren Ids, kommasepariert |
| `%qqregions_selection_height_top%` | Blöcke bis zur oberen Grenze |
| `%qqregions_selection_height_bottom%` | Blöcke bis zur unteren Grenze |
| `%qqregions_selection_pos1_x/y/z%` | Koordinaten des ersten Punkts (wie gesetzt) |
| `%qqregions_selection_pos2_x/y/z%` | Koordinaten des zweiten Punkts |

**Regionen und Wirtschaft**

| Platzhalter | Wert |
|---|---|
| `%qqregions_region_current%` | Id der Region, in der der Spieler steht |
| `%qqregions_region_flags%` | gesetzte Flags der Region: `Flag:Wert, ...` |
| `%qqregions_region_flag_<Flag>%` | Wert einer bestimmten Flag (leer, wenn nicht gesetzt) |
| `%qqregions_region_owners%` | Namen der Besitzer der aktuellen Region |
| `%qqregions_region_members%` | Namen der Mitglieder (ohne Besitzer) der aktuellen Region |
| `%qqregions_region_owner_is%` | `yes`/`no` — der Spieler ist Besitzer der aktuellen Region |
| `%qqregions_region_member_is%` | `yes`/`no` — der Spieler ist Mitglied der aktuellen Region (Besitzer zählt auch) |
| `%qqregions_region_role%` | Rolle in der aktuellen Region: `OWNER` / `MEMBER` / `NONE` |
| `%qqregions_region_price_<Welt:Region>%` | Preis des aktiven Angebots (`0`, wenn keins); nur Zahl, ohne Währungssymbol |
| `%qqregions_region_for_sale_<Welt:Region>%` | `yes`/`no` — wird verkauft |
| `%qqregions_region_for_rent_<Welt:Region>%` | `yes`/`no` — wird vermietet |
| `%qqregions_region_owner_<Welt:Region>%` | Besitzer der Region |
| `%qqregions_region_rent_time_<Welt:Region>%` | Mietdauer der Region |
| `%qqregions_player_owned_regions%` | Regionen des Spielers, in denen er Besitzer ist (Admin — alle), kommasepariert |
| `%qqregions_player_owned_count%` | deren Anzahl |
| `%qqregions_player_membered_regions%` | Regionen, in denen der Spieler Mitglied (nicht Besitzer) ist, kommasepariert |
| `%qqregions_player_membered_count%` | deren Anzahl |
| `%qqregions_eco_balance%` | formatierter Kontostand — **nur Zahl** (ohne Währungssymbol) |
| `%qqregions_eco_balance_symbol%` | Währungssymbol aus `market.economy.symbol` (leer, wenn deaktiviert) |
| `%qqregions_eco_balance_raw%` | „roher“ Kontostand |
| `%qqregions_eco_has_<Betrag>%` | `yes`/`no` — reichen die Mittel |
| `%qqregions_market_listings%` | Anzahl aktiver Angebote |

**Nahe Regionen**

Horizontaler (2D) Abstand vom Spielerpunkt bis zur Regionsgrenze; 0, wenn der
Spieler darin steht. Regionen aus `restrictions.banned-regions` werden in
Listen und Zählungen nicht berücksichtigt.

| Platzhalter | Wert |
|---|---|
| `%qqregions_nearby_region%` | Id der nächsten Region (leer, wenn keine) |
| `%qqregions_nearby_region_distance%` | Abstand zur nächsten Region in Blöcken |
| `%qqregions_nearby_region_count%` | Anzahl der Regionen im Radius von 100 Blöcken |
| `%qqregions_nearby_region_count_<Radius>%` | dasselbe mit explizitem Radius |

**Raids**

| Platzhalter | Wert |
|---|---|
| `%qqregions_raid_active%` | `yes`/`no` — läuft ein Raid |
| `%qqregions_raid_state%` | `idle` / `capturing` / `thief` / `cooldown` |
| `%qqregions_raid_region%` / `%qqregions_raid_world%` | Region und Welt des Raids |
| `%qqregions_raid_clan%` / `%qqregions_raid_thief%` | Clan und „Dieb“ |
| `%qqregions_raid_count%` / `%qqregions_raid_players%` / `%qqregions_raid_total%` | Anzahl der Angreifer |
| `%qqregions_raid_remaining%` / `%qqregions_raid_time%` | Sekunden bis zum Phasenende |
| `%qqregions_raid_cooldown%` | Cooldown-Sekunden der Region |

> Beliebige externe `%…%` können in Menü-Texte geschrieben werden — sie werden
> für einen bestimmten Spieler aufgelöst (ein Beispiel mit dem Kontostand jedes
> Spielers — in `menus/players.yml`). Im Besitzer-Menü werden die Buttons von
> Besitzer-Spielern für diesen konkreten Spieler aufgelöst, nicht für den
> Menü-Öffner.

---

## 6. Menüs

Die GUIs werden aus `menus/*.yml` gebaut. Für jedes Menü können mehrere
**Vorlagen** nach Spielerrolle (`role-required`) und Priorität definiert
werden:

| Rolle | Für wen geeignet |
|---|---|
| `owner` | Besitzer der Region |
| `member` | Mitglied der Region |
| `other` | Außenstehender |
| (leer) | jeder |

Eine Vorlage definiert: Titel, Größe, Fill-Panels, statische Buttons
(mit `lore`, Klickbefehl, optionalem `permission` und `tooltip: false`
— das Hover-Popup eines bestimmten Buttons ausblenden; bei `fill`
blendet der Schlüssel `tooltip: false` das Popup vom Hintergrund aus),
dynamische Slots und Pagination. Dynamische Buttons werden im Code
zusammengesetzt (WorldGuard-Flags, Spieler der Region, Marktangebote, Käufe)
und auf `slots`/`menu-slots` verteilt.

**Pseudobefehle von Buttons:**

| Pseudobefehl | Aktion |
|---|---|
| `@page:prev` / `@page:next` | Seiten blättern |
| `@menu:<Name>` | anderes Menü öffnen (info, flags, players, market, flagshop, blocks, myflags, help) |
| `@back` | zurück zum vorherigen Menü |
| `@teleport` | Teleport ins Zentrum der Region |
| `@highlight` | Grenzen der Region markieren |
| `@flag:<Name>:<allow\|deny\|default>` | Flag umschalten (`default` = entfernen, wie `/rg flag -r`) |
| `@flag-search` / `@market-search` | Suche nach Flags / im Markt |
| `@sort` | Marktsortierung ändern |
| `@add:owner` / `@add:member` | Spieler hinzufügen (Nick im Chat eingeben) |
| `@player-del:<uuid>\|Nick>:owner\|member` | Spieler entfernen |
| `@pf:all\|owners\|members` | Filter der Spielerliste |
| `@raid:start` | Raid starten |
| `@region-info-or-pick` | „Mein Territorium“: in einer Region — Info, sonst — Territorium-Auswahl |
| `@region-delete` | bestätigtes Löschen des Territoriums (WG) |
| `@select` | interaktive Auswahl aktivieren („Territorium erstellen“ aus dem Hauptmenü) |
| `@ps-sort` | Sortierzyklus der Spielersuche (A-Z/Z-A/Kontostand/Regionen±/Distanz±) |
| `@rpsort` | Sortierzyklus der Territorium-Auswahl (nah/fern/A-Z/Z-A/Personen±/Fläche±) |
| `@rinfo:<Welt>:<Region>` | Info zu einer bestimmten Region öffnen |
| `@menu:help` | Hilfe öffnen |
| `@menu:main` | zum Hauptmenü zurück („Zurück zum Hauptmenü“-Button im Info-Menü, Slot 0) |
| `message!<Text>` | Nachricht an den Spieler ohne Plugin-Präfix |
| `gMessage!<Text>` | Nachricht an alle Server-Spieler |
| `title:<fade>:<stay>:<fade>!<Text>` | Titel mit Timing in Ticks (ohne `:…!` — Standard 20/40/20) |
| `title!<Text>` | Titel (20/40/20) |
| `actionbar:<Ticks>!<Text>` | Actionbar für N Ticks (ohne Zahl — 60 Ticks) |
| `sound!<Sound> [Lautstärke] [Pitch]` / `gSound!…` | Sound an den Spieler / an alle |
| `asConsole!<Befehl>` / `asPlayer!<Befehl>` | als Konsole / als Spieler ausführen |
| `delay:<Ticks>!<Aktion>` | Aktion verzögert ausführen |
| `close` | Menü schließen |

Alle Aktionsstypen (außer `close`) stehen nicht nur Menü-Buttons zur
Verfügung, sondern auch `commands`/`allow-cmds`/`deny-cmds` von Shop-Artikeln
(`shop.yml`) und Raid-Benachrichtigungen (`config.yml` →
`raid.notify.*.commands`).

### 6.1 Info-Menü der Region

Wird per `/region info` geöffnet. Zusammengefügte Info-Buttons: **Region**
(Welt, Typ, Status, Fläche, Volumen, Priorität, Rolle + für Besitzer/Mitglied
die Spielerlimits `{my-regions}/{max-regions}` und `{max-blocks}`; `∞` bei
fehlendem Limit/Admin-Recht) und **Spieler** (Besitzer + Mitglieder). In Slot 0
— der **„Zurück zum Hauptmenü“**-Button (`@menu:main`): In der Standarddatei
`menus/info.yml` wird er bei der Installation ergänzt, in angepassten Dateien
wird er automatisch vom Code eingefügt (nur wenn Slot 0 frei ist). Beim Besitzer
gibt es eine Button-Reihe: **Flags**, **Spieler**, **Teleport**
(Recht `qqregions.admin`), **Markierung**, **Markt**, **Löschen**
(geht zum Bestätigungsmenü), und **Raid** — nur bei einem Außenstehenden
(`role-required: other`, Recht `qqregions.raid`), mit Clan-Lore
`{raid-clan}` `{raid-balance}` `{raid-online}/{raid-total}`
`{raid-in-region}/{raid-needed}`. Bei `raid.enabled: false` ist der
Raid-Button komplett ausgeblendet.

![Info-Menü der Region](docs/screenshots/01.png)

### 6.1a Löschbestätigung

Wird über den **„Territorium löschen“**-Button im Info-Menü des Besitzers
geöffnet. **Ja, löschen** (`@menu:confirmdelete` → `@region-delete`, löscht
die Region via WG) / **Abbrechen** (`@back` — zurück zum Info).

### 6.1b Territorium-Auswahl

Der „Mein Territorium“-Button im Hauptmenü: Steht der Spieler in einer Region —
sofort Info; sonst — Auswahl aller Regionen aller Welten. Jede Region hat
Lore (Welt/Typ/Spieler/Fläche/Distanz), Klick — Info. Die Sortierung `@rpsort`
wechselt: nah → fern → A-Z → Z-A → nach Personen (aufst./abst.) → nach Fläche
(aufst./abst.); der aktuelle Modus ist grün in der Lore des „Sortieren“-Buttons
markiert.

Der **„Territorium erstellen“**-Button (Slot 31 des Hauptmenüs, `@select`)
aktiviert die interaktive Auswahl — wie `/region select` ohne Argumente:
Das Menü schließt, Hotbar-Buttons werden gelegt: „Punkt 1“ / „Punkt 2“
(Klick — Punkt setzen, Mausrad — aktiven bewegen), „Erstellen“
(Namenseingabe), „Zurücksetzen“ und „Abbrechen“.

### 6.2 Flag-Menü

Wird per `/region flags` geöffnet. Dynamische Buttons — nach WorldGuard-Flags
(außer denen in `ignore-flags`). Namen kommen aus `config.yml`
(`flags-names`), Werte — aus `replace.yml`. LMB — Wertezyklus:
an → aus → **Standard** (entfernen, WorldGuard-Standard greift, wie
`/rg flag -r`) → an; RMB — **Gruppe** wechseln
(all/members/owners/nonmembers/nonowners). Beim Anzeigen eines Flags ohne
eigenen Wert zeigt der Button „nicht gesetzt“ und der 3. Klick entfernt das
Flag.

Ein Flag wird dem Spieler nur mit dem Recht darauf angezeigt:
`qqregions.flags.use.<Flag>` (per LuckPerms oder einzeln für den Spieler),
Legacy `qqregions.flags.<Flag>` oder Gruppen-Vorlage
`qqregions.flags.group.<Name>` aus `config.yml` →
`flag-groups`. Admin/op sehen alles. Im Shop gekaufte Flags sind immer
sichtbar.

![Flag-Menü](docs/screenshots/05.png)

### 6.3 Spieler-Menü

Dynamische Buttons — Besitzer (DIAMOND) und Mitglieder (GOLD_INGOT).
Buttons **+ Besitzer / + Mitglied**, Filter „Mitglieder entfernen /
Besitzer entfernen“, **Spieler hinzufügen** (öffnet das
Spielersuche-Menü). Steuerung — nur Besitzer und Admins.

### 6.3a Spielersuche-Menü

Der „Spieler hinzufügen“-Button im Spieler-Menü. Alle Server-Spieler: online
zuerst, dann offline, ohne sich selbst; Köpfe mit Spieler-Skins. Auf jedem Kopf
werden angezeigt: gewählte Hinzufüge-Gruppe, Kontostand (Vault), Anzahl der
Regionen (Besitzer+Mitglied) und das Maximum des Spielers, Clan (JustTeams).
LMB — zur gewählten Gruppe hinzufügen (Bestätigung), RMB — entfernen
(Bestätigung), Shift+LMB — Hinzufüge-Gruppe wechseln. Sortierung über den
„Sortieren“-Button (Slot 4): A-Z/Z-A/nach Kontostand/nach Regionszahl
(aufst./abst.)/nach Distanz (weiter/näher). Der letzte Besitzer kann nicht
entfernt werden. Hinzufüge-/Entfernungsbestätigung — Menü playerconfirm.yml.

![Spieler-Menü](docs/screenshots/06.png)

### 6.4 Markt-Menü

Liste aktiver Angebote (Suche, Sortierung: Name → Preis →
Standard). Buttons: **Erweiterung**, **Flag-Shop**,
**Meine Flags**, **Hilfe**. Jedes Angebot:

- **eigenes** — LMB „Auto-Rückgabe“ (bei Miete) / abbrechen, RMB — abbrechen;
  bei eigenen öffentlichen Mieten zusätzlich der Button **Angebotsdauer**
  (`rent dur`);
- **fremdes** — LMB — sofort kaufen/mieten (öffentlich) oder
  annehmen (privates Angebot).

![Markt-Menü](docs/screenshots/08.png)

### 6.5 Flag-Shop

Verkäuflich sind Flags (alle aus der WorldGuard-Registry — einschließlich
Flags aus beliebigen anderen Plugins, z. B. WGEFP, außer
`flags-menu.whitelist` und `flags-menu.shop-ignore`) mit Preisen aus
`shop.yml`. Ein Spieler **sieht und kann kaufen** ein Flag nur mit dem Recht
darauf: `qqregions.flags.use.<Flag>` (einzeln oder per LuckPerms-Gruppe)
**oder** Gruppen-Vorlage `qqregions.flags.group.<Name>` aus `config.yml` →
`flag-groups` — ohne Recht fehlt das Flag im Shop und ist nicht kaufbar; nach
Vergabe erscheint es. Das Legacy-Recht `qqregions.flags.<Flag>` wird ebenfalls
akzeptiert. Admin/op sehen und kaufen alles. Suche — nach Name oder Übersetzung.

![Flag-Shop](docs/screenshots/09.png)

### 6.6 Erweiterungs-Shop

Buttons werden aus `shop.yml` gebaut und nach dem Feld `priority` sortiert
(kleiner — früher). Pakete **„+Fläche“** (erhöhen `max-blocks`, einmalig) und
**„+Region“** (erhöhen das Regionenlimit, wiederholbar oder mit Limit
`max-purchases`), plus benutzerdefinierte Artikel `custom-items` —
einmalige Vergabe von Rechten/Befehlen nach dem Kauf (Bedingungen,
`allow-cmds`/`deny-cmds`, siehe §7.2).

Jeder Artikel hat **`max-purchases`** (Kauflimit; `<=0` = unbegrenzt)
und **`bought-display`**: `HIDE` (Standard) — ein gekaufter und limiterschöpfter
Artikel verschwindet, die übrigen Buttons rücken nach vorn; `RED_GLASS` —
an seiner Stelle rotes Glas „&cName“ mit Lore „&7Bereits gekauft“.
Der gemeinsame Schalter für Flags ist `shop.yml` → `flags.bought-display`
(HIDE | RED_GLASS).

![Erweiterungs-Shop](docs/screenshots/10.png)

### 6.7 Meine Flags

Nur die vom Spieler gekauften Flags. Öffnen ohne Käufe antwortet
mit einer Meldung (`lang.yml` → `shop.none-owned`).

![Meine Flags](docs/screenshots/11.png)

### 6.8 Hilfe-Menü

Statische Buttons mit Plugin-Befehlen nach Abschnitten (Vorlagen `default` und
`compact`).

![Hilfe-Menü](docs/screenshots/13.png)

---

## 7. Konfiguration

### 7.1 config.yml

Hauptabschnitte:

| Abschnitt | Was wird konfiguriert |
|---|---|
| `command` | Name und Aliase des Befehls |
| `restrictions` | `disabled-worlds`, `banned-regions` |
| `region-name` | Validierungs-`regex` und erzwungene Kleinschreibung |
| `selection-templates` | Auswahl-Berechtigungsvorlagen (siehe Abschnitt 8.1) |
| `interactive` | interaktives Select: Radgeschwindigkeit, Buttons (Material + Hotbar-Slot), Center-Select-Slot, gesperrte Befehle, `sync-worldedit`, View-Modus und Auto-Ausblendung |
| `highlight` | Regionsmarkierung: Typ, `region-hide-seconds` (Auto-Ausblendungs-Timeout der Regionenkontur, unabhängig von Auswahlen; alte `auto-hide-seconds`/`show-seconds` — Fallback), Cooldown, `hide-on-exit`/`show-on-exit`, `auto-show-radius` (eigene/fremde — nur per Flag `territory-visible`), `territory` (Zaun + `ignore-blocks`), `particles` |
| `outline` | gemeinsame gestrichelte Kontur: `max-gap` (Punktabstand jeder Linie, z. B. 5), `max-points`, `rings` (Ringe nach Höhe), `grid` (Quadrate auf allen 6 Seiten — nur bei Regionen; Spielerauswahl: 12 Kanten im alten Look) |
| `particles` | Auswahl-Partikel |
| `bossbar` / `select-status` | Auswahlstatus: Bossbar/Actionbar, Texte `normal/full/conflict`, Zusatzinfo (`info.text`) |
| `guard` | Schutz vor Lag/Ping |
| `regions` | Limit `max-regions` (0 = kein Limit) |
| `flags-menu` | `whitelist` (kostenlose Flags) und `shop-ignore` (nicht verkauft) |
| `flags-names` | benutzerdefinierte Flag-Namen |
| `flag-groups` | Gruppen-Vorlagen für Flag-Rechte: Das Recht `qqregions.flags.group.<Name>` öffnet sofort die gesamte Flag-Liste (siehe Abschnitt 4) |
| `menu-update` | Menü-Auto-Update: `ticks` (globales Neuzeichnungs-Intervall offener Menüs, Standard 20) und `debounce-after-click` (Anti-Autoclicker, STANDARDMÄSSIG AUS: bei true verschiebt ein Klick auf den Button das Neuzeichnen um `update_interval` des Menüs) |
| `placeholders` | PlaceholderAPI-Integration: `enabled` (schaltet die Erweiterung `%qqregions_*%` ein/aus), `owners-separator` / `members-separator` / `owned-separator` / `membered-separator` — Trennzeichen für Listen von Besitzern, Mitgliedern und Regionen des Spielers |
| `market` | Markt: `enabled`, `multiowner` (single/split), `commission` (% für den Server), `offer-timeout-minutes` (Gültigkeit des privaten Angebots) und `offer-timeout-action` (RELIST — öffentlich anbieten / CANCEL — entfernen), Wirtschaft (Symbol/Gruppierung), Miete (`grant`, `charge`, `period-minutes`, `list-duration-minutes`, `auto-rent`), Schild-Hologramm (`market-holo` — EIN schwebendes Hologramm am Perimeter, „umfliegt“ die Region dem Spieler hinterher und blickt ihn immer an) |
| `raid` | Raids: Phasen, Charge, Display, Notify |

Markierungsbeispiel (Kontur + Territorium):

```yaml
highlight:
  type: PARTICLES            # PARTICLES | BLOCKS | TERRITORY
  region-hide-seconds: 60    # Auto-Ausblendungs-Timeout der REGIONS-Kontur
  territory:
    ignore-blocks:           # Blöcke, die TERRITORY als Leere betrachtet
      - BROWN_MUSHROOM
      - RED_MUSHROOM
      - TALL_GRASS
      - SHORT_GRASS
    fence:                   # „Zaun“ (territory.display: BLOCKS)
      material: OAK_PLANKS
      height: 1.0
      width: 0.3
      thickness: 0.3
      spacing: 1.0
      offset: 0.0
      glow: true

outline:                     # gemeinsame gestrichelte Kontur (Auswahl + Regionen)
  max-gap: 5                 # Punktabstand jeder Linie (nicht mehr als 5 Blöcke)
  max-points: 3000           # Obergrenze der Konturpunkte
  rings:
    enabled: true
    step: 8                  # ein Ring alle 8 Blöcke Höhe
  grid:                      # Quadrate auf oben/unten — NUR bei Regionen
    enabled: true
    step: 25
```

### 7.2 shop.yml

```yaml
enabled: true
economy-enabled: true

flags:
  default-price: 1000
  # Gekaufte Flags als rotes Glas (RED_GLASS) zeigen oder ausblenden (HIDE).
  bought-display: HIDE
  prices:
    pvp: 500
    build: 800
    entry: 600

area-packs:
  big:
    name: "Große Fläche"
    blocks: 20000            # amount (liest auch „amount"/"regions“)
    price: 5000
    material: GOLD_INGOT
    priority: 0              # Button-Reihenfolge (kleiner — früher)
    max-purchases: 1         # Kauflimit (<=0 = wiederholbar)
    bought-display: HIDE     # RED_GLASS | HIDE

region-packs:
  extra1:
    name: "+1 Region"
    regions: 1
    price: 1000
    priority: 10
    max-purchases: 0         # 0 = wiederholbar

custom-items:                # benutzerdefinierte Artikel (Rechte/Befehle vergeben)
  vip:
    name: "VIP-Privileg"
    material: NETHER_STAR
    lore:
      - "&7Vergibt das VIP-Privileg"
    price: 5000
    max-purchases: 1
    bought-display: RED_GLASS
    priority: 20
    conditions:              # AND: bei allen wahr -> allow-cmds, sonst deny-cmds
      - "%vault_rank%==Default"
    commands:                # ohne conditions: führt commands aus
      - "asConsole! lp user {player} parent add vip"
      - "message! &aVIP-Rechte aktiviert!"
      - "sound! ENTITY_PLAYER_LEVELUP 1 1"
    allow-cmds:              # Aktionen bei erfüllten Bedingungen
      - "message! &aRechte vergeben"
    deny-cmds:
      - "message! &cVIP kann nicht zweimal gekauft werden."
```

**Gemeinsame Artikel-Felder** (`area-packs` / `region-packs` / `custom-items`):
`name`, `price` (0/negativ = nicht verkauft), `material`, `amount`
(Blöcke/Regionen/Zahl für die Lore; bei area werden auch `blocks` gelesen, bei
region — `regions`), `max-purchases`, `bought-display`, `priority`,
`conditions`, `commands`, `allow-cmds`, `deny-cmds`, `lore`
(nur bei custom-items).

**Bedingungen** (`conditions`) — `Platzhalter Operator Wert`: Operatoren
`=` `!=` `>` `<` `>=` `<=` (Zahlen), String `<-` (enthält), `!<-`,
`|-` (beginnt mit), `!|-`, `-|` (endet mit), `!-|`. Platzhalter
werden per PlaceholderAPI für den Käufer aufgelöst.

**Aktionen** (`commands`/`allow-cmds`/`deny-cmds`) unterstützen alle
Aktionsstypen (siehe Pseudobefehl-Tabelle in §6): `message!`, `gMessage!`,
`title!`/`title:…!`, `actionbar!`/`actionbar:N!`, `sound!`, `gSound!`,
`asConsole!`, `asPlayer!`, `delay:N!`. `{player}` → Nick des Käufers.

### 7.3 replace.yml

Ersetzen roher WorldGuard-/WGEFP-Flag-Werte durch lesbaren Text:

```yaml
'%worldguard_region_has_flag_pvp%':
  - placeholder: 'yes'
    replacement: 'aktiviert'
  - placeholder: 'no'
    replacement: '&7deaktiviert'
  - placeholder: 'ELSE'
    replacement: ''

flag-groups:
  - placeholder: 'all'
    replacement: 'alle'
  - placeholder: 'ELSE'
    replacement: '{value}'

flag-values:
  - placeholder: 'allow'
    replacement: '&aaktiviert'
  - placeholder: 'deny'
    replacement: '&cdeaktiviert'
  - placeholder: 'ELSE'
    replacement: '{value}'
```

`{value}` im `replacement` setzt den ursprünglichen Wert ein.

### 7.4 lang.yml

Alle Texte für den Spieler. Verfügbar sind `&`-Farben, `&#RRGGBB`, ein bloßes
`#RRGGBB` direkt im Text und Platzhalter `{Name}`. Zusätzlich wird
**MiniMessage** unterstützt: Verläufe (`<gradient:#55ffff:#ff55ff>`),
`<rainbow>`, `<color:#RRGGBB>`, `<hover:...>`, `<click:...>` usw. — in
beliebigen Gegenstandsnamen/-beschreibungen und in Nachrichten. Strings mit
Mini-Markup werden von MiniMessage geparst (Legacy-`&`-Codes darin werden
automatisch umgewandelt), defekte Mini-Strings crashen nicht und fallen auf die
normale Legacy-Analyse zurück. Das Sonder-Präfix **`actionbar:N!`**
am Anfang einer Übersetzung sendet den Text für N Sekunden in die Actionbar
(mit Aktualisierung, ohne Plugin-Präfix):

```yaml
prefix: "&8[&bQQRegions&8] "

guard:
  blocked: "actionbar:3!&cDer Server ist überlastet — bitte warten."
```

Das Präfix funktioniert für **jede** vom Plugin ausgegebene Nachricht
(Befehle, Chat-Prompts, Menü-Klick-Ergebnisse, Raid-Benachrichtigungen): Beginnt
die Zeile mit `actionbar:N!`, geht der Text in die Actionbar, sonst — in den
Chat. Die Konsole erhält einfachen Text.

Verwenden Sie in Geldvorlagen das Paar „Zahl + Symbol“
(`{price} {price-symbol}`, `{raid-balance} {raid-balance-symbol}` usw.).
Detaillierte Vorlagen der Befehls-Syntax stehen im Abschnitt `usage:` (z. B.
für `/region help`), Schlüssel der kompakten Zeit — `menu.time-short-*`, die
„ja/nein“-Werte im Auswahlstatus — `select-status.yes/no`. Die Dateiversion ist
`config-version: 6`; beim Umstieg von einer alten Plugin-Version füllt das
Plugin nur **leere** Werte aus — Vorlagen, die im alten `lang.yml` fehlen,
müssen manuell ergänzt werden (z. B. `{price-symbol}` zu Geldmeldungen
hinzufügen, sonst zeigen frühere Versionen weiterhin die Zahl ohne Symbol).

### 7.5 data.yml

Automatisch; nicht im Betrieb bearbeiten:

```text
players.<UUID>.flags       — gekaufte Flags
players.<UUID>.area-packs  — Flächenpakete
players.<UUID>.region-packs.<id> — Anzahl „+Region“
players.<UUID>.custom-items.<id> — Kaufanzahl benutzerdefinierter Artikel
offers.*                   — Markt- und Mietangebote
```

### 7.6 menus/*.yml

GUI-Layouts. Änderungen an Buttons/Texten/Slots werden bei
`/region reload` übernommen. Die Footer-Slots (45–53) der Menüs überlappen
nicht mit `menu-slots` (10–43), daher sind doppelte Buttons unmöglich.

Jedes Menü hat ein `update_interval` (Ticks) — wie oft ein offenes Menü neu
gezeichnet wird (Lore/Preise/Flags aktualisieren on the fly); `0` =
Auto-Update dieses Menüs deaktivieren. Der globale Standard ist
`menu-update.ticks` in config.yml. Button-Klicks zeichnen das Menü sofort neu;
der optionale Anti-Autoclicker `menu-update.debounce-after-click: true`
(standardmäßig aus) verschiebt das Neuzeichnen eines offenen Menüs nach einem
Klick um `update_interval` Ticks — Navigation und Button-Aktionen sind dabei
sofort. Details — in `menus/MENU_EDITOR.md` §8.

---

## 8. Erweiterte Konfiguration

### 8.1 Auswahl-Berechtigungsvorlagen

Für den Spieler wird die Vorlage mit der höchsten `priority` gewählt, bei der
`permission` **oder** `placeholder` übereinstimmt:

```yaml
selection-templates:
  default:
    permission: ""            # leer = nicht prüfen
    placeholder: ""           # "%plasma_skill_power%>=60" oder "true"/"false"
    priority: 1
    max-blocks: 10000
    min-blocks: 100
    chunks: 16
  vip:
    permission: "luckperms.vip"
    priority: 10
    max-blocks: 50000
    min-blocks: 200
    chunks: 32
```

Die `placeholder`-Mechanik erlaubt es, Limits nach beliebigen numerischen PAPI
zu vergeben (`>=`, `>`, `<=`, `<`, `==` oder nur `true`/`false`).

### 8.2 Schutz vor Lag (guard)

Menü-Button-Klicks (und andere Mechaniken) werden abgebrochen, wenn der Server
überlastet ist oder der Ping des Spielers hoch ist — Schutz vor Item-„Dupe“
bei Lags:

```yaml
guard:
  enabled: true
  min-tps: 15.0
  max-ping: 5000
```

### 8.3 Raids (JustTeams)

```yaml
raid:
  enabled: false
  min-attackers: 2
  online-percent: 50
  capture-time: 60
  thief-time: 60
  cooldown-time: 300
  blacklist: []
  owners-offline-required: true
  abort-on-owner-online: true
  economy:
    enabled: false
    source: CLAN            # PLAYER — vom Kontostand des Diebs (Vault), CLAN — aus der Bank
    percent: 10
  display:
    mode: ACTIONBAR         # BOSSBAR | ACTIONBAR | NONE
    text: "&cEroberung {region}: &f{time}&c Sek. • Angreifer &f{count}&c/&f{total}"
    thief-text: "&2Dieb &f{thief}&2: &f{time}&2 Sek."
  notify:
    start:
      message: "&8[&cClan&8] &f{clan} &cgreift die Region &f{region}&7 an!"
      commands: []
```

Benachrichtigungen unterstützen `asConsole!...`- und `asPlayer!...`-Befehle
mit `{Platzhalter}`-Ersetzung.

### 8.4 Wirtschaft

Das Zahlenformat wird in `market.economy` eingestellt:

```yaml
market:
  economy:
    symbol: "€"
    symbol-position: AFTER     # AFTER = "1000 €", BEFORE = "€ 1000"
    decimal-places: 0
    grouping: true
    group-separator: ","
    decimal-separator: "."
  rent:
    grant: MEMBER              # MEMBER | OWNER
    charge: PERIOD             # ONCE | PERIOD (wiederkehrende Abbuchung)
    period-minutes: 1440
  list-duration-minutes: 10080 # Gültigkeit öffentlicher Mietangebote (Standard 7 Tage)
  auto-rent: true              # Auto-Rückgabe mit Auto-Verlängerung nach Mietende
  multiowner: single           # single — gesamter Betrag an den Initiator; split — an alle Besitzer gleichmäßig
  commission:
    enable: false              # Server-Provision bei Verkauf/Miete
    rate: 0.01                 # Anteil am Betrag (0.01 = 1%, 0.05 = 5%). Wird dem Empfänger abgezogen
  offer-timeout-minutes: 60    # Gültigkeit des privaten Angebots (0 = unbegrenzt)
  offer-timeout-action: RELIST # was bei Ablauf tun: RELIST — öffentlich anbieten; CANCEL — vom Markt nehmen
  market-holo:
    enabled: true              # EIN Schild-Hologramm am Regionen-Perimeter für die Angebotsdauer
    view-distance: 24          # Radius (auf X/Z), in dem das Hologramm den Spieler an der Grenze entlangführt
    y-offset: 1.5              # Versatz relativ zur Y-Ebene des SPIELERS (0 — exakt auf Spieler-Y, 1.5 ≈ Gesicht)
    scale: 1.0                 # Textgrößen-Multiplikator
    line-width: 200            # max. Zeilenlänge in Blöcken
```

---

## 9. FAQ und Problemlösung

**Das Menü öffnet sich nicht / der Befehl antwortet „Menü nicht konfiguriert“.**
Prüfen Sie die Dateien in `menus/` und `/region reload`. Einzelne Menüs können
deaktiviert werden, indem die `dynamic-*`-/`purchased-*`-Abschnitte entfernt
werden — das Plugin bietet dann einen Fallback an.

**Der Markt funktioniert nicht.**
`market.enabled: true` + installiertes Vault + Wirtschafts-Plugin.
Ohne Vault antworten die Befehle „Wirtschaft (Vault) nicht verfügbar“.

**Der Flag-Shop ist leer.**
Flags werden nur verkauft, wenn sie **nicht** in `flags-menu.whitelist`
(dort sind sie kostenlos) und nicht in `flags-menu.shop-ignore` stehen.
Eine leere `whitelist` bedeutet „alle Flags werden verkauft“.

**Ein gekauftes Flag ist nicht im Menü sichtbar.**
Wenn Sie Besitzer der Region sind — erscheint das Flag ohne Recht.
Für Außenstehende gibt ein gekauftes Flag keine Rechte über fremde Regionen.

**Ich sehe keine Auswahlpunkte.**
Prüfen Sie `particles.enabled: true` (oder `interactive.view-mode: BLOCKS`)
und ob die Welt nicht in `restrictions.disabled-worlds` deaktiviert ist.

**Die Markierung reagiert nicht auf das Flag.**
Das Flag `territory-visible` muss `allow` sein und löst nicht öfter als
`highlight.cooldown-seconds` aus. Bei `territory-visible: false` wird das Flag
registriert, aber der Eintritt markiert nicht (der Befehl `/region visible`
funktioniert). Eigene Regionen im Radius `auto-show-radius` leuchten ebenfalls
nur mit dem Flag `allow` — ohne Flag leuchten sie weder in der Nähe noch beim
Betreten einer benachbarten Region mit Flag. Die Kontur erlischt von selbst
nach `highlight.region-hide-seconds` (nicht an Auswahl-Timeouts gebunden).

**Ein Raid startet nicht.**
Prüfen Sie JustTeams (Clan bei allen Angreifern), `min-attackers`,
`online-percent`, die Blacklist und dass die Besitzer offline sind (falls
`owners-offline-required: true`).

---