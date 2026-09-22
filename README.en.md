# QQRegions — Wiki

> ### Language
>
> | [Русский](README.md) | [**English**](README.en.md) | [Português](README.pt.md) | [Deutsch](README.de.md) | [中文](README.zh.md) | [한국어](README.ko.md) |
> |:-:|:-:|:-:|:-:|:-:|:-:|

> Powerful region management on top of **WorldGuard** for **Paper / Leaf (api 26.2)** servers.

```
/region ...   /territory ...   /tr ...   /rg ...   /private ...   /zone ...
```

The plugin covers the whole privatization lifecycle on the server: interactive
selection, creating and deleting regions, a flags menu, border highlighting,
member management, a sale/rent market, a flag and extension shop, clan raids —
and the entire localization in a single file.

---

## Table of contents

- [1. Requirements and installation](#1-requirements-and-installation)
- [2. Quick start](#2-quick-start)
- [3. Commands](#3-commands)
- [4. Permissions](#4-permissions)
- [5. Placeholders](#5-placeholders)
- [6. Menus](#6-menus)
- [7. Configuration](#7-configuration)
- [8. Advanced configuration](#8-advanced-configuration)
- [9. FAQ and troubleshooting](#9-faq-and-troubleshooting)

---

## 1. Requirements and installation

### 1.1 Requirements

| Dependency | Type | Purpose |
|---|---|---|
| [WorldGuard](https://dev.bukkit.org/projects/worldguard) | **required** | region core |
| Vault + economy plugin (EssentialsX, CMI…) | soft | market and shop |
| PlaceholderAPI | soft | `%qqregions_*%` and any `%…%` inside menus |
| JustTeams | soft | clan raids |
| LuckPerms | soft | selection permission templates |
| WorldGuardExtraFlagsPlus | soft | extra flags / placeholders |

The plugin works without soft dependencies: a missing mechanic simply replies
to the player with the appropriate message from `lang.yml`.

### 1.2 Installation

1. Install **WorldGuard** (required).
2. Put `QQRegions.jar` into `plugins/`.
3. Restart the server — `config.yml`, `lang.yml`,
   `replace.yml`, `shop.yml`, `menus/*.yml` will be created (and `data.yml`
   on the first action).
4. Configure the plugin and run `/region reload`.

### 1.3 First run

After the first run, in the plugin folder:

| File | Purpose |
|---|---|
| `config.yml` | all plugin settings |
| `lang.yml` | all player/admin/console texts (one plugin language) |
| `replace.yml` | translation of "raw" flag values (yes/no, allow/deny) |
| `shop.yml` | shop: flag prices, "+area", "+region" packs |
| `data.yml` | player purchases, offers, rentals (created automatically) |
| `menus/*.yml` | all GUIs: info, flags, players, playerconfirm, market, marketconfirm, flagshop, blocks, myflags, help and others |

### 1.4 Aliases

The command name and aliases are set in `config.yml`:

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

Aliases are picked up on `/region reload` without a server restart.
Everywhere below, `/region` means any alias.

---

## 2. Quick start

### Step 1. Selection

```text
/region select
```

The player gets hotbar buttons: **"Create region"**, **"Point 1"**,
**"Point 2"**, **"Select area"**, **"Reset selection"**, **"Cancel"**.
Button slots are configured in `config.yml` (`interactive.buttons.<id>.slot`).
The session saves the player's inventory and restores it on exit.

![Interactive selection](docs/screenshots/02.png)

- **LMB / RMB** with the button in hand — perform the button action (both in
  the air and on a block);
- **the "Point 1/2" button** — quick point placement by line of sight (up to 300
  blocks), otherwise at the player's position;
- **LMB / RMB** with an empty hand — switch the active point (1 or 2);
- **scroll wheel or keys 4/6** — move the point (in select mode the chosen
  slot is always held in the center — `interactive.select-center-slot`,
  so keyboard slot switching works like the wheel);
- **Shift + scroll** — movement ×`wheel-shift-speed`;
- **Shift + LMB** — confirm the selection.

During a session, commands from `interactive.blocked-commands`
(by default `ah`, `sell`, `shop`, `baltop`) are blocked — protection from
"throwing away" buttons via auction/shops.

Names and descriptions of buttons, panels and any items support
**MiniMessage**: gradients (`<gradient:#55ffff:#ff55ff>`), `<rainbow>`,
`<color:#RRGGBB>`; a bare `#RRGGBB` right in the text is colored too.

### Step 2. Creating a region

```text
/region create <name>
```

After confirming the selection, the plugin asks you to type a name in chat
(`cancel` — cancel, selection is kept). The name is validated:

- against `region-name.regex` (by default `[A-Za-zА-Яа-я0-9_-]{3,32}`);
- against forbidden regions from `restrictions.banned-regions`;
- against the `regions.max-regions` limit plus purchased "+region" packs.

![Entering a region name](docs/screenshots/04.png)

### Step 3. Flags and members

```text
/region flags <region>     — flags menu (LMB — on/off/default, RMB — group)
/region add <player> <region> — grant membership
/region add owner <player> <region>
```

A flag is shown to the player only with the permission `qqregions.flags.use.<flag>`
(admins see everything; the legacy permission `qqregions.flags.<flag>` is also
accepted). A flag purchased in the shop is shown to the owner without any
permission.

### Step 4. Border highlighting

```text
/region visible <region> [particles|blocks|territory]
/region visible off        — hide all highlights
```

Highlighting can be tied to a region via the `territory-visible` flag (in the
flags menu): when a player enters the region, the borders highlight
automatically and fade out after `highlight.region-hide-seconds`. The region
timeout is independent of selections (`interactive.view-hide-after`). Your own
regions (owner/member) within `highlight.auto-show-radius` highlight the same
way — **only** with the flag `territory-visible: allow`.

![Region border highlighting](docs/screenshots/07.png)

### Step 5. Market and shop

```text
/region market                — market menu
/region market flags          — flag shop
/region market blocks         — extensions (area, "+region")
/region sell <amount> [region] — list a region for sale (public listing)
/region rent <amount> <time> [region] — rent out a region (public listing)
/region buy [region]          — buy a region from a public listing
/region tenant [region]       — rent from a public listing
```

---

## 3. Commands

The full list is also available in game: `/region help` (text output) or the
**"Help"** button in the menu (all templates).

### 3.1 Selection

| Command | Description |
|---|---|
| `/region select` | Interactive mode (hotbar buttons) |
| `/region select pos <1\|2>` | Set a point at the player's position |
| `/region select point <1\|2>` | Synonym of `pos` |
| `/region select max` | The maximum area allowed by the template around the player |
| `/region select chunk [N]` | Select an area of N chunks (by default — template limit) |
| `/region select expand [side] <N>` | Expand by N blocks (or `-N` — shrink) |
| `/region select outset <N> [h\|v]` | Expand in all directions (h — horizontal, v — vertical) |
| `/region select view <player>` | View another player's selection |

**`expand` sides:** `north`, `south`, `east`, `west`, `up`, `down`.

The result of command-based selections can be seen: a status bar at the end of
`select`, plus the highlight `interactive.command-selection-view` and auto-hide
`command-selection-hide-after`.

### 3.2 Regions

| Command | Description |
|---|---|
| `/region create <name>` | Create a region from the current selection |
| `/region delete [name]` | Delete your region (or the region you are standing in) |
| `/region info [name]` | Open the region info menu |
| `/region flags [name]` | Open the region flags menu |
| `/region reload` | Reload all configs (+ aliases, + language) |

### 3.3 Members and owners

| Command | Description |
|---|---|
| `/region add <player> [region]` | Add a player as member |
| `/region add member <player> [region]` | Same, explicitly |
| `/region add owner <player> [region]` | Add to owners |
| `/region remove <player> [region]` | Remove a member |
| `/region remove owner <player> [region]` | Remove an owner (the last one cannot be removed) |

### 3.4 Highlighting

| Command | Description |
|---|---|
| `/region visible [name] [type]` | Show region borders |
| `/region visible off` | Hide all active highlights |
| `/region visible type [type]` | Default type (without an argument — show the current one) |
| `/region visible self on\|off` | Personal "for yourself" highlight: only affects the `territory-visible` flag (command and menu always show) |
| `/region visible true\|allow\|false\|deny [type] [region]` | Region highlight flag |
| `/region view [name] [type]` | Temporarily show borders (without changing the flag) |

**Types:** `particles`, `blocks` (glowing displays), `territory`
(outline along the terrain). For regions of type `particles`/`blocks` the
internal `outline.grid` mesh works — squares on **all 6 faces** (top,
bottom and four sides); a player selection draws **only 12 edges**
(exactly like before, without rings and mesh — see `outline.rings`/`grid`,
they work only for region highlighting). `territory` + `blocks` —
only a fence made of the specified blocks.

### 3.5 Market (Vault)

| Command | Description |
|---|---|
| `/region market` | Market menu: listing list, search, sorting |
| `/region market flags` | Flag shop |
| `/region market blocks` | Extensions: area packs and "+region" |
| `/region sell <amount> [region]` | List a region for sale — **public listing** (anyone buys instantly) |
| `/region sell <player> <amount> [region]` | Offer a purchase to a specific player (private offer) |
| `/region rent <amount> <time> [region]` | Rent out a region — **public listing** (anyone rents instantly) |
| `/region rent <player> <amount> <time> [region]` | Offer a rental to a specific player |
| `/region rent dur <minutes> [region]` | Validity period of a rental listing (by default — `market.list-duration-minutes`) |
| `/region buy [region]` | Buy a region from a public listing |
| `/region tenant [region]` | Rent a region from a public listing |
| `/region sell\|rent\|buy\|tenant accept\|decline\|cancel <id>` | Accept/decline/cancel an offer or listing |
| `/region market myflags` | Purchased flags menu |

**Rental time:** `30` (minutes), `2h` (hours), `7d` (days), `1w` (weeks),
`1m` (months), `1y` (years). By default — 1 week.

**Public listings** are accepted by any player except the seller/owner.
If auto-return is enabled (`market.rent.auto-rent`, auto-return button in the
market menu), when the rental ends the listing appears on the market again
for `list-duration-minutes`.

### 3.6 Clan raids (JustTeams)

| Command | Description |
|---|---|
| `/region raid [region]` | Start a raid (the "Raid" button in the info menu is only for outsiders) |

---

## 4. Permissions

Basic permissions are granted to everyone automatically; service and `bypass`
permissions only to operators.

| Permission | Default | Description |
|---|---|---|
| `qqregions.use` | `true` | Base access to commands |
| `qqregions.create` | `true` | Creating regions |
| `qqregions.delete` | `true` | Deleting regions |
| `qqregions.select` | `true` | Selection |
| `qqregions.info` | `true` | Region info |
| `qqregions.manage` | `true` | Managing members/owners |
| `qqregions.flags` | `true` | Flags menu |
| `qqregions.visible` | `true` | Border highlighting |
| `qqregions.market` | `true` | Market (sell/rent/buy/tenant/market) |
| `qqregions.raid` | `true` | Clan raids (JustTeams) |
| `qqregions.reload` | `op` | Reload configs |
| `qqregions.admin` | `op` | Full access, including other players' regions |
| `qqregions.bypass.selection-limits` | `op` | Ignore template `max-blocks`/`min-blocks` |
| `qqregions.bypass.disabled-worlds` | `op` | Work in worlds from `disabled-worlds` |
| `qqregions.bypass.banned-regions` | `op` | Work with `banned-regions` |

`qqregions.admin` includes: `reload`, `create`, `delete`, `select`,
`info`, `manage`, `flags`, `visible`, `market`, `raid`.

Additionally, for the flags menu the dynamic permission
**`qqregions.flags.use.<flag>`** is used (e.g. `qqregions.flags.use.pvp`):
it shows the flag in the flags menu **and in the flag shop** and allows
changing/buying it. The `qqregions.admin` permission sees/changes/buys
everything. A simple option — grant the privilege `qqregions.flags.use.*`
(e.g. in the LuckPerms player group): then the player gets access to all
flags. The legacy permission `qqregions.flags.<flag>` is also accepted
(for compatibility).

**Group templates (`config.yml` → `flag-groups`)** — granting flags "in a
batch": a player with the permission `qqregions.flags.group.<name>`
automatically gets all flags from that group's list (see/change/buy).
Multiple groups are summed up; `["*"]` in the list = all flags. Works together
with individual `qqregions.flags.use.<flag>`.

> Example with groups: give the "newbie" group `qqregions.flags.group.newbie`
> (in config — `pvp`, `build`), and the "vet" group `qqregions.flags.group.veteran`
> (a broad list) — newbies will only see their set in the shop and flags menu,
> vets their own; a common permission can be added point-wise via
> `qqregions.flags.use.<flag>`.

> Note: it is useful to give the `bypass.*` pairs and `admin` to specific
> groups in LuckPerms (see the templates section).

---

## 5. Placeholders

The plugin supports two kinds of placeholders:

1. **Internal** — `{name}` — substituted by the plugin in `lang.yml`,
   `menus/*.yml`, texts in `config.yml` and raid notifications.
2. **External PAPI** — `%qqregions_*%` — from QQRegions' own expansion,
   plus any public ones (e.g. `%vault_eco_balance%`).

### 5.1 Common for menus

`{region}` `{world}` `{player}` `{role}` `{page}` `{pages}`

### 5.2 By context

| Context | Placeholders |
|---|---|
| Info menu | `{owners}` `{members}` `{type}` `{area}` `{volume}` `{priority}` `{status}` `{my-regions}` `{max-regions}` `{max-blocks}` |
| Flags menu | `{flag}` `{flag-name}` `{flag-value}` `{flag-value-label}` `{flag-raw}` `{group}` `{group-label}` `{groups-list}` `{flag-group}` `{flag-group-label}` `{flag-with-group}` `{next-state}` |
| Players menu | `{player}` `{role}` `{role-ru}` `{player-id}` |
| Player search | `{ps-group}` `{ps-balance}` `{ps-balance-symbol}` `{ps-regions}` `{ps-max}` `{ps-clan}` `{ps-sort-list}` |
| Confirmation (playerconfirm) | `{pc-player}` `{pc-role}` `{pc-action}` `{pc-balance}` `{pc-balance-symbol}` `{pc-clan}` `{pc-regions}` `{pc-reg-owner}` `{pc-reg-member}` `{pc-max}` |
| Territory picker | `{rp-world}` `{rp-type}` `{rp-people}` `{rp-area}` `{rp-dist}` `{rp-sort-list}` |
| Market menu | `{market-type}` `{market-region}` `{market-world}` `{market-price}` `{market-price-symbol}` `{market-who}` `{market-owner}` `{market-status}` |
| Market hologram (market-holo) | `{owner}` (seller/owner nickname) `{price}` `{price-symbol}` `{nick}` `{time}` `{region}` |
| Flag shop | `{flag-name}` `{flag}` `{price}` `{price-symbol}` |
| Extension shop | `{pack-name}` `{name}` `{pack-amount}` `{price}` `{price-symbol}` |
| Info menu (raid button) | `{raid-clan}` `{raid-balance}` `{raid-balance-symbol}` `{raid-online}` `{raid-total}` `{raid-in-region}` `{raid-needed}` |
| Selection bossbar | `{current}` `{max}` `{percent}` `{player}` `{value-color}` |
| Extra info actionbar | `{height-top}` `{height-bottom}` `{conflict}` `{conflict-regions}` `{conflict-count}` `{current}` `{max}` `{percent}` `{player}` |
| Raid (bars/notifications) | `{region}` `{world}` `{clan}` `{count}` `{total}` `{thief}` `{time}` `{percent}` `{player}` |

> All money placeholders (`{price}`, `{market-price}`, `{raid-balance}`,
> `{ps-balance}`, `{pc-balance}`) return **only a number** (per the
> `market.economy` format). Add the currency symbol separately — each has a
> `-symbol` sibling: `{price} {price-symbol}` and so on. If `-symbol` is not in
> the pattern, the number is shown without a symbol.

### 5.3 External PAPI placeholders `%qqregions_*%`

The expansion registers automatically if PlaceholderAPI is installed.
You can switch it off completely via `placeholders.enabled` in `config.yml`;
the list separators (`owners-separator`, `members-separator`,
`owned-separator`, `membered-separator`) are there too, in the `placeholders`
section.

**Selection**

| Placeholder | Value |
|---|---|
| `%qqregions_selection_active%` | `yes`/`no` — whether a selection exists |
| `%qqregions_selection_blocks%` | blocks in the selection |
| `%qqregions_selection_max_blocks%` | template limit (or ∞ with bypass) |
| `%qqregions_selection_min_blocks%` | minimum size |
| `%qqregions_selection_chunks%` | chunk limit per template |
| `%qqregions_selection_percent%` | percent of the limit (0–100) |
| `%qqregions_selection_over_limit%` | `yes`/`no` |
| `%qqregions_selection_below_min%` | `yes`/`no` |
| `%qqregions_selection_conflict%` | `yes`/`no` — intersects other regions |
| `%qqregions_selection_conflict_count%` | number of conflicting regions |
| `%qqregions_selection_conflict_regions%` | their ids, comma-separated |
| `%qqregions_selection_height_top%` | blocks to the upper border |
| `%qqregions_selection_height_bottom%` | blocks to the lower border |
| `%qqregions_selection_pos1_x/y/z%` | coordinates of the first point (as placed) |
| `%qqregions_selection_pos2_x/y/z%` | coordinates of the second point |

**Regions and economy**

| Placeholder | Value |
|---|---|
| `%qqregions_region_current%` | id of the region the player is standing in |
| `%qqregions_region_flags%` | flags set on the region: `flag:value, ...` |
| `%qqregions_region_flag_<flag>%` | value of a specific flag (empty if not set) |
| `%qqregions_region_owners%` | names of the owners of the current region |
| `%qqregions_region_members%` | names of the members (without owners) of the current region |
| `%qqregions_region_owner_is%` | `yes`/`no` — the player owns the current region |
| `%qqregions_region_member_is%` | `yes`/`no` — the player is a member of the current region (owner counts too) |
| `%qqregions_region_role%` | role in the current region: `OWNER` / `MEMBER` / `NONE` |
| `%qqregions_region_price_<world:region>%` | price of the active offer (`0` if none); number only, without currency symbol |
| `%qqregions_region_for_sale_<world:region>%` | `yes`/`no` — is for sale |
| `%qqregions_region_for_rent_<world:region>%` | `yes`/`no` — is for rent |
| `%qqregions_region_owner_<world:region>%` | region owner |
| `%qqregions_region_rent_time_<world:region>%` | region rental term |
| `%qqregions_player_owned_regions%` | the player's regions where they are the owner (admin — all), comma-separated |
| `%qqregions_player_owned_count%` | their count |
| `%qqregions_player_membered_regions%` | regions where the player is a member (not an owner), comma-separated |
| `%qqregions_player_membered_count%` | their count |
| `%qqregions_eco_balance%` | formatted balance — **number only** (without currency symbol) |
| `%qqregions_eco_balance_symbol%` | currency symbol from `market.economy.symbol` (empty if disabled) |
| `%qqregions_eco_balance_raw%` | "raw" balance |
| `%qqregions_eco_has_<amount>%` | `yes`/`no` — whether funds are sufficient |
| `%qqregions_market_listings%` | number of active listings |

**Nearby regions**

Horizontal (2D) distance from the player's point to the region border;
0 if the player is standing inside. Regions from `restrictions.banned-regions`
are not taken into account in lists and counts.

| Placeholder | Value |
|---|---|
| `%qqregions_nearby_region%` | id of the nearest region (empty if none) |
| `%qqregions_nearby_region_distance%` | distance to the nearest region in blocks |
| `%qqregions_nearby_region_count%` | number of regions within 100 blocks |
| `%qqregions_nearby_region_count_<radius>%` | the same with an explicit radius |

**Raids**

| Placeholder | Value |
|---|---|
| `%qqregions_raid_active%` | `yes`/`no` — whether a raid is ongoing |
| `%qqregions_raid_state%` | `idle` / `capturing` / `thief` / `cooldown` |
| `%qqregions_raid_region%` / `%qqregions_raid_world%` | raid region and world |
| `%qqregions_raid_clan%` / `%qqregions_raid_thief%` | clan and "thief" |
| `%qqregions_raid_count%` / `%qqregions_raid_players%` / `%qqregions_raid_total%` | number of attackers |
| `%qqregions_raid_remaining%` / `%qqregions_raid_time%` | seconds until the phase ends |
| `%qqregions_raid_cooldown%` | region cooldown seconds |

> Any third-party `%…%` can be written into menu texts — they resolve for a
> specific player (an example with each player's balance is in
> `menus/players.yml`). In the owner's menu, owner-player buttons resolve for
> that specific player, not the menu opener.

---

## 6. Menus

GUIs are built from `menus/*.yml`. Each menu can have multiple **templates**
by player role (`role-required`) and priority:

| Role | Who it suits |
|---|---|
| `owner` | region owner |
| `member` | region member |
| `other` | outsider |
| (empty) | anyone |

A template defines: title, size, fill panels, static buttons
(with `lore`, click command, optional `permission` and `tooltip: false`
— hide the hover tooltip of a specific button; for `fill` the key
`tooltip: false` hides the tooltip from the background), dynamic
slots and pagination. Dynamic buttons are assembled in code (WorldGuard flags,
region players, market offers, purchases) and laid out over
`slots`/`menu-slots`.

**Button pseudo-commands:**

| Pseudo-command | Action |
|---|---|
| `@page:prev` / `@page:next` | flip pages |
| `@menu:<name>` | open another menu (info, flags, players, market, flagshop, blocks, myflags, help) |
| `@back` | return to the previous menu |
| `@teleport` | teleport to the region center |
| `@highlight` | highlight the region borders |
| `@flag:<name>:<allow\|deny\|default>` | toggle a flag (`default` = unset, like `/rg flag -r`) |
| `@flag-search` / `@market-search` | search flags / market |
| `@sort` | change market sorting |
| `@add:owner` / `@add:member` | add a player (type the name in chat) |
| `@player-del:<uuid>\|name>:owner\|member` | remove a player |
| `@pf:all\|owners\|members` | player list filter |
| `@raid:start` | start a raid |
| `@region-info-or-pick` | "My territory": in a region — info, otherwise — territory picker |
| `@region-delete` | confirmed territory deletion (WG) |
| `@select` | enable interactive selection ("Create territory" from the main menu) |
| `@ps-sort` | player search sorting cycle (A-Z/Z-A/balance/regions±/distance±) |
| `@rpsort` | territory picker sorting cycle (near/far/A-Z/Z-A/people±/area±) |
| `@rinfo:<world>:<region>` | open info for a specific region |
| `@menu:help` | open help |
| `@menu:main` | return to the main menu (the "Back to main menu" button in the info menu, slot 0) |
| `message!<text>` | message to the player without the plugin prefix |
| `gMessage!<text>` | message to all server players |
| `title:<fade>:<stay>:<fade>!<text>` | title with timing in ticks (without `:…!` — default 20/40/20) |
| `title!<text>` | title (20/40/20) |
| `actionbar:<ticks>!<text>` | actionbar for N ticks (without a number — 60 ticks) |
| `sound!<sound> [volume] [pitch]` / `gSound!…` | sound to the player / to everyone |
| `asConsole!<command>` / `asPlayer!<command>` | execute as console / as player |
| `delay:<ticks>!<action>` | run an action with a delay |
| `close` | close the menu |

All action types (except `close`) are available not only to menu buttons but
also to `commands`/`allow-cmds`/`deny-cmds` of shop items (`shop.yml`) and
raid notifications (`config.yml` → `raid.notify.*.commands`).

### 6.1 Region info menu

Opened by `/region info`. Merged info buttons: **Region** (world, type,
status, area, volume, priority, role + for owner/member the player limits
`{my-regions}/{max-regions}` and `{max-blocks}`; `∞` when there is no
limit/admin permission) and **Players** (owners + members). In slot 0 —
the **"Back to main menu"** button (`@menu:main`): in the default
`menus/info.yml` it is added on install, and in customized files it is
inserted automatically by code (only if slot 0 is free). For the owner there
is a row of buttons: **Flags**, **Players**, **Teleport**
(permission `qqregions.admin`), **Highlight**, **Market**, **Delete**
(goes to the confirmation menu), and **Raid** — only for an outsider
(`role-required: other`, permission `qqregions.raid`), with clan lore
`{raid-clan}` `{raid-balance}` `{raid-online}/{raid-total}`
`{raid-in-region}/{raid-needed}`. When `raid.enabled: false` the raid button
is fully hidden.

![Region info menu](docs/screenshots/01.png)

### 6.1a Delete confirmation

Opened by the "Delete territory" button in the owner's info menu.
**Yes, delete** (`@menu:confirmdelete` → `@region-delete`, deletes the region
via WG) / **Cancel** (`@back` — return to info).

### 6.1b Territory picker menu

The "My territory" button in the main menu: if the player is inside a region —
info immediately; otherwise — a picker of all regions of all worlds. Each
region has lore (world/type/players/area/distance), click — info.
The `@rpsort` sort cycles: near → far → A-Z → Z-A →
by people (asc./desc.) → by area (asc./desc.); the current mode
is highlighted in green in the "Sort" button lore.

The **"Create territory"** button (slot 31 of the main menu, `@select`)
enables interactive selection — like `/region select` without arguments:
the menu closes, hotbar buttons are placed: "Point 1" / "Point 2"
(click — place a point, mouse wheel — move the active one), "Create"
(typing the name), "Reset" and "Cancel".

### 6.2 Flags menu

Opened by `/region flags`. Dynamic buttons — by WorldGuard flags
(except those in `ignore-flags`). Names come from `config.yml`
(`flags-names`), values — from `replace.yml`. LMB — value cycle:
on → off → **default** (unset, WorldGuard default applies, like
`/rg flag -r`) → on; RMB — switch **group**
(all/members/owners/nonmembers/nonowners). When showing a flag without its own
value, the button shows "not set" and the 3rd click unsets the flag.

A flag is shown to the player only with permission on it:
`qqregions.flags.use.<flag>` (via LuckPerms or to the player individually),
legacy `qqregions.flags.<flag>` or the group template
`qqregions.flags.group.<name>` from `config.yml` →
`flag-groups`. Admin/op see everything. Flags purchased in the shop are always
visible.

![Flags menu](docs/screenshots/05.png)

### 6.3 Players menu

Dynamic buttons — owners (DIAMOND) and members (GOLD_INGOT).
**+ Owner / + Member** buttons, "Remove members / remove owners" filters,
**Add players** (opens the player search menu). Controlling — only owners
and admins.

### 6.3a Player search menu

The "Add players" button in the players menu. All server players: online
first, then offline, without yourself; heads with player skins. Each
head shows the selected add group, balance (Vault), number of regions
(owner+member) and the player's max, clan (JustTeams).
LMB — add to the selected group (confirmation), RMB — remove
(confirmation), Shift+LMB — change the add group. Sort by the
"Sort" button (slot 4): A-Z/Z-A/by balance/by region count (asc./desc.)/
by distance (far/near). The last owner cannot be removed.
Add/remove confirmation — playerconfirm.yml menu.

![Players menu](docs/screenshots/06.png)

### 6.4 Market menu

List of active listings (search, sorting: name → price →
default). Buttons: **Extension**, **Flag shop**,
**My flags**, **Help**. Each listing:

- **own** — LMB "auto-return" (for rent) / cancel, RMB — cancel;
  for your own public rentals there is an additional **listing duration**
  button (`rent dur`);
- **foreign** — LMB — buy/rent instantly (public) or
  accept (private offer).

![Market menu](docs/screenshots/08.png)

### 6.5 Flag shop

Sellable flags (all from the WorldGuard registry — including flags from any
other plugins, e.g. WGEFP, except `flags-menu.whitelist` and
`flags-menu.shop-ignore`) with prices from `shop.yml`. A player **sees and can
buy** a flag only with permission on it: `qqregions.flags.use.<flag>`
(individually or via a LuckPerms group) **or** the group template
`qqregions.flags.group.<name>` from `config.yml` → `flag-groups` — until the
permission exists, the flag is absent from the shop and cannot be bought; after
granting it, it appears. The legacy permission `qqregions.flags.<flag>` is also
accepted. Admin/op see and buy everything. Search — by name or translation.

![Flag shop](docs/screenshots/09.png)

### 6.6 Extension shop

Buttons are built from `shop.yml` and sorted by the `priority` field (lower —
earlier). **"+area"** packs (increase `max-blocks`, once) and
**"+region"** packs (increase the region limit, repeatable or with a
`max-purchases` limit), plus custom items `custom-items` —
one-time granting of permissions/commands after purchase (conditions, `allow-cmds`/
`deny-cmds`, see §7.2).

Each item has **`max-purchases`** (purchase limit; `<=0` = unlimited)
and **`bought-display`**: `HIDE` (default) — a bought and limit-exhausted
item disappears, the remaining buttons shift forward; `RED_GLASS` —
a red glass "&cname" with the lore "&7Already bought" in its place.
The common switch for flags is `shop.yml` → `flags.bought-display`
(HIDE | RED_GLASS).

![Extension shop](docs/screenshots/10.png)

### 6.7 My flags

Only the flags bought by the player. Opening without purchases replies
with a message (`lang.yml` → `shop.none-owned`).

![My flags](docs/screenshots/11.png)

### 6.8 Help menu

Static buttons with plugin commands by section (templates `default` and
`compact`).

![Help menu](docs/screenshots/13.png)

---

## 7. Configuration

### 7.1 config.yml

Main sections:

| Section | What it configures |
|---|---|
| `command` | command name and aliases |
| `restrictions` | `disabled-worlds`, `banned-regions` |
| `region-name` | validation `regex` and forced lowercase |
| `selection-templates` | selection permission templates (see section 8.1) |
| `interactive` | interactive select: wheel speed, buttons (material + hotbar slot), center-select slot, blocked commands, `sync-worldedit`, view mode and auto-hide |
| `highlight` | region highlighting: type, `region-hide-seconds` (auto-fade timeout of a region outline, independent of selections; old `auto-hide-seconds`/`show-seconds` — fallback), cooldown, `hide-on-exit`/`show-on-exit`, `auto-show-radius` (own/foreign — only by the `territory-visible` flag), `territory` (fence + `ignore-blocks`), `particles` |
| `outline` | common dashed outline: `max-gap` (point step of any line, e.g. 5), `max-points`, `rings` (rings by height), `grid` (squares on all 6 faces — only for regions; player selection: 12 edges in the old look) |
| `particles` | selection particles |
| `bossbar` / `select-status` | selection status: bossbar/actionbar, texts `normal/full/conflict`, extra info (`info.text`) |
| `guard` | lag/ping protection |
| `regions` | `max-regions` limit (0 = no limit) |
| `flags-menu` | `whitelist` (free flags) and `shop-ignore` (not sold) |
| `flags-names` | custom flag names |
| `flag-groups` | flag permission group templates: the permission `qqregions.flags.group.<name>` opens the whole flag list at once (see section 4) |
| `menu-update` | menu auto-update: `ticks` (global redraw interval of open menus, default 20) and `debounce-after-click` (anti-autoclicker, OFF BY DEFAULT: when true, a button click delays the redraw by the menu `update_interval`) |
| `placeholders` | PlaceholderAPI integration: `enabled` (switches the `%qqregions_*%` expansion on/off), `owners-separator` / `members-separator` / `owned-separator` / `membered-separator` — separators of owner, member and player-region lists |
| `market` | market: `enabled`, `multiowner` (single/split), `commission` (% for the server), `offer-timeout-minutes` (private offer validity) and `offer-timeout-action` (RELIST — list publicly / CANCEL — remove), economy (symbol/grouping), rent (`grant`, `charge`, `period-minutes`, `list-duration-minutes`, `auto-rent`), the sign hologram (`market-holo` — ONE floating hologram near the perimeter, "flies around" the region following the player and always faces them) |
| `raid` | raids: phases, charge, display, notify |

Highlight example (outline + territory):

```yaml
highlight:
  type: PARTICLES            # PARTICLES | BLOCKS | TERRITORY
  region-hide-seconds: 60    # auto-fade timeout of a REGION outline
  territory:
    ignore-blocks:           # blocks TERRITORY treats as void
      - BROWN_MUSHROOM
      - RED_MUSHROOM
      - TALL_GRASS
      - SHORT_GRASS
    fence:                   # "fence" (territory.display: BLOCKS)
      material: OAK_PLANKS
      height: 1.0
      width: 0.3
      thickness: 0.3
      spacing: 1.0
      offset: 0.0
      glow: true

outline:                     # common dashed outline (selection + regions)
  max-gap: 5                 # point step of any line (no more than 5 blocks)
  max-points: 3000           # outline point ceiling
  rings:
    enabled: true
    step: 8                  # a ring every 8 blocks of height
  grid:                      # squares on the top/bottom — ONLY for regions
    enabled: true
    step: 25
```

### 7.2 shop.yml

```yaml
enabled: true
economy-enabled: true

flags:
  default-price: 1000
  # Whether to show bought flags as red glass (RED_GLASS) or hide them (HIDE).
  bought-display: HIDE
  prices:
    pvp: 500
    build: 800
    entry: 600

area-packs:
  big:
    name: "Big territory"
    blocks: 20000            # amount (also reads "amount"/"regions")
    price: 5000
    material: GOLD_INGOT
    priority: 0              # button order (lower — earlier)
    max-purchases: 1         # purchase limit (<=0 = repeatable)
    bought-display: HIDE     # RED_GLASS | HIDE

region-packs:
  extra1:
    name: "+1 region"
    regions: 1
    price: 1000
    priority: 10
    max-purchases: 0         # 0 = repeatable

custom-items:                # custom items (granting permissions/commands)
  vip:
    name: "VIP privilege"
    material: NETHER_STAR
    lore:
      - "&7Grants the VIP privilege"
    price: 5000
    max-purchases: 1
    bought-display: RED_GLASS
    priority: 20
    conditions:              # AND: when all are true -> allow-cmds, otherwise deny-cmds
      - "%vault_rank%==Default"
    commands:                # without conditions: executes commands
      - "asConsole! lp user {player} parent add vip"
      - "message! &aVIP privileges activated!"
      - "sound! ENTITY_PLAYER_LEVELUP 1 1"
    allow-cmds:              # actions when conditions are met
      - "message! &aPrivileges granted"
    deny-cmds:
      - "message! &cYou cannot buy VIP twice."
```

**Common item fields** (`area-packs` / `region-packs` / `custom-items`):
`name`, `price` (0/negative = not for sale), `material`, `amount`
(blocks/regions/number for lore; area also reads `blocks`, region — `regions`),
`max-purchases`, `bought-display`, `priority`, `conditions`,
`commands`, `allow-cmds`, `deny-cmds`, `lore` (only for custom-items).

**Conditions** (`conditions`) — `placeholder operator value`: operators
`=` `!=` `>` `<` `>=` `<=` (numbers), string `<-` (contains), `!<-`,
`|-` (starts with), `!|-`, `-|` (ends with), `!-|`. Placeholders
are resolved by PlaceholderAPI for the buyer.

**Actions** (`commands`/`allow-cmds`/`deny-cmds`) support all
action types (see the pseudo-command table in §6): `message!`, `gMessage!`,
`title!`/`title:…!`, `actionbar!`/`actionbar:N!`, `sound!`, `gSound!`,
`asConsole!`, `asPlayer!`, `delay:N!`. `{player}` → buyer's name.

### 7.3 replace.yml

Replacing raw WorldGuard / WGEFP flag values with readable text:

```yaml
'%worldguard_region_has_flag_pvp%':
  - placeholder: 'yes'
    replacement: 'enabled'
  - placeholder: 'no'
    replacement: '&7disabled'
  - placeholder: 'ELSE'
    replacement: ''

flag-groups:
  - placeholder: 'all'
    replacement: 'all'
  - placeholder: 'ELSE'
    replacement: '{value}'

flag-values:
  - placeholder: 'allow'
    replacement: '&aenabled'
  - placeholder: 'deny'
    replacement: '&cdisabled'
  - placeholder: 'ELSE'
    replacement: '{value}'
```

`{value}` in `replacement` substitutes the original value.

### 7.4 lang.yml

All texts for the player. `&` colors, `&#RRGGBB`, a bare `#RRGGBB` right in
the text and `{name}` placeholders are available. Additionally,
**MiniMessage** is supported: gradients (`<gradient:#55ffff:#ff55ff>`),
`<rainbow>`, `<color:#RRGGBB>`, `<hover:...>`, `<click:...>` etc. — in any item
names and descriptions and in messages. Strings with mini-markup are parsed by
MiniMessage (legacy `&` codes in them are converted automatically), broken
mini-strings do not crash and fall back to normal legacy parsing.
The special **`actionbar:N!`** prefix at the beginning of a translation sends
the text to the actionbar for N seconds (with updates, without the plugin prefix):

```yaml
prefix: "&8[&bQQRegions&8] "

guard:
  blocked: "actionbar:3!&cThe server is overloaded — please wait."
```

The prefix works for **any** message output by the plugin (commands,
chat prompts, menu click results, raid notifications): if the line starts with
`actionbar:N!`, the text goes to the actionbar, otherwise — to chat.
The console receives plain text.

In money patterns use the "number + symbol" placeholder pair
(`{price} {price-symbol}`, `{raid-balance} {raid-balance-symbol}` etc.).
Detailed command syntax patterns are in the `usage:` section (e.g. for
`/region help`), compact-time keys — `menu.time-short-*`, the "yes/no"
values in the selection status — `select-status.yes/no`. The file version is
`config-version: 6`; when migrating from an old plugin version the plugin only
fills in **empty** values — patterns missing from the old `lang.yml` need to be
completed manually (e.g. add `{price-symbol}` to money messages, otherwise
earlier versions will keep showing the number without a symbol).

### 7.5 data.yml

Automatic; do not edit on the fly:

```text
players.<UUID>.flags       — purchased flags
players.<UUID>.area-packs  — area packs
players.<UUID>.region-packs.<id> — count of "+region"
players.<UUID>.custom-items.<id> — purchase count of custom items
offers.*                   — market and rent offers
```

### 7.6 menus/*.yml

GUI layouts. Button/text/slot edits are applied on
`/region reload`. Menu footer slots (45–53) do not overlap with
`menu-slots` (10–43), so duplicate buttons are impossible.

Each menu has an `update_interval` (ticks) — how often an open menu is redrawn
(lore/prices/flags update on the fly); `0` = disable auto-update for that menu.
The global default is `menu-update.ticks` in config.yml.
Button clicks redraw the menu immediately; the optional anti-autoclicker
`menu-update.debounce-after-click: true` (disabled by default) delays the
redraw of an open menu after a click by `update_interval` ticks — navigation
and button actions are then instant.
Details — in `menus/MENU_EDITOR.md` §8.

---

## 8. Advanced configuration

### 8.1 Selection permission templates

For a player the template with the greatest `priority` is chosen whose
`permission` **or** `placeholder` matches:

```yaml
selection-templates:
  default:
    permission: ""            # empty = do not check
    placeholder: ""           # "%plasma_skill_power%>=60" or "true"/"false"
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

The `placeholder` mechanic allows granting limits by any numeric PAPI
(`>=`, `>`, `<=`, `<`, `==` or just `true`/`false`).

### 8.2 Lag protection (guard)

Menu button clicks (and other mechanics) are cancelled if the server is
overloaded or the player's ping is high — protection against item "duping"
during lag:

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
    source: CLAN            # PLAYER — from the thief's balance (Vault), CLAN — from the bank
    percent: 10
  display:
    mode: ACTIONBAR         # BOSSBAR | ACTIONBAR | NONE
    text: "&cCapture {region}: &f{time}&c sec • attackers &f{count}&c/&f{total}"
    thief-text: "&2Thief &f{thief}&2: &f{time}&2 sec"
  notify:
    start:
      message: "&8[&cClan&8] &f{clan} &cis attacking the region &f{region}&7!"
      commands: []
```

Notifications support `asConsole!...` and `asPlayer!...` commands with
`{placeholder}` substitution.

### 8.4 Economy

The number format is set in `market.economy`:

```yaml
market:
  economy:
    symbol: "$"
    symbol-position: AFTER     # AFTER = "1000 $", BEFORE = "$ 1000"
    decimal-places: 0
    grouping: true
    group-separator: ","
    decimal-separator: "."
  rent:
    grant: MEMBER              # MEMBER | OWNER
    charge: PERIOD             # ONCE | PERIOD (recurring charge)
    period-minutes: 1440
  list-duration-minutes: 10080 # public rent listing validity (default 7 days)
  auto-rent: true              # auto-return with auto-renewal after the rental ends
  multiowner: single           # single — the whole amount to the initiator; split — to all owners equally
  commission:
    enable: false              # server commission on sale/rent
    rate: 0.01                 # share of the amount (0.01 = 1%, 0.05 = 5%). Charged from the recipient
  offer-timeout-minutes: 60    # private offer validity (0 = unlimited)
  offer-timeout-action: RELIST # what to do on expiry: RELIST — list publicly; CANCEL — remove from market
  market-holo:
    enabled: true              # ONE sign hologram near the region perimeter for the listing duration
    view-distance: 24          # radius (by X/Z) in which the hologram leads the player along the border
    y-offset: 1.5              # offset relative to the PLAYER's Y level (0 — exactly at player Y, 1.5 ≈ face)
    scale: 1.0                 # text size multiplier
    line-width: 200            # max line length in blocks
```

---

## 9. FAQ and troubleshooting

**The menu does not open / the command replies "menu not configured".**
Check the files in `menus/` and `/region reload`. Individual menus can be
disabled by removing the `dynamic-*` / `purchased-*` sections — the plugin
will then offer a fallback.

**The market does not work.**
`market.enabled: true` + Vault installed + an economy plugin.
Without Vault, commands reply "economy (Vault) unavailable".

**The flag shop is empty.**
Flags are only sold if they are **not** in `flags-menu.whitelist`
(those are free) and not in `flags-menu.shop-ignore`. An empty `whitelist`
means "all flags are sold".

**A purchased flag is not visible in the menu.**
If you are the region owner — the flag will appear without permission.
For outsiders, a purchased flag grants no rights over other players' regions.

**I do not see selection points.**
Check `particles.enabled: true` (or `interactive.view-mode: BLOCKS`)
and that the world is not disabled in `restrictions.disabled-worlds`.

**Highlighting does not trigger from the flag.**
The `territory-visible` flag must be `allow`, and it does not trigger more often
than `highlight.cooldown-seconds`. For `territory-visible: false` the flag is
registered but entry does not highlight (the `/region visible`
command works). Your own regions within `auto-show-radius` also glow
only with the `allow` flag — without it they do not light up anywhere, not
nearby nor when entering a neighboring region with the flag. The outline
fades on its own after `highlight.region-hide-seconds` (not tied to selection
timeouts).

**A raid does not start.**
Check JustTeams (a clan on all attackers), `min-attackers`,
`online-percent`, the blacklist, and that the owners are offline (if
`owners-offline-required: true`).

---