# QQRegions — Wiki

> ### 语言 / Language
>
> | [Русский](README.md) | [English](README.en.md) | [Português](README.pt.md) | [Deutsch](README.de.md) | [**中文**](README.zh.md) | [한국어](README.ko.md) |
> |:-:|:-:|:-:|:-:|:-:|:-:|

> 基于 **WorldGuard** 的强大区域管理插件，适用于 **Paper / Leaf (api 26.2)** 服务器。

```
/region ...   /territory ...   /tr ...   /rg ...   /private ...   /zone ...
```

该插件覆盖服务器上完整的私有化流程：交互式选区、创建和删除区域、
Flag 菜单、边界高亮、成员管理、出售/租赁市场、Flag 和扩展商店、
氏族突袭——并且所有本地化文本都集中在一个文件中。

---

## 目录

- [1. 要求和安装](#1-要求和安装)
- [2. 快速开始](#2-快速开始)
- [3. 命令](#3-命令)
- [4. 权限](#4-权限)
- [5. 占位符](#5-占位符)
- [6. 菜单](#6-菜单)
- [7. 配置](#7-配置)
- [8. 高级配置](#8-高级配置)
- [9. 常见问题与排错](#9-常见问题与排错)

---

## 1. 要求和安装

### 1.1 要求

| 依赖 | 类型 | 用途 |
|---|---|---|
| [WorldGuard](https://dev.bukkit.org/projects/worldguard) | **必需** | 区域核心 |
| Vault + 经济插件 (EssentialsX、CMI…) | 软依赖 | 市场和商店 |
| PlaceholderAPI | 软依赖 | `%qqregions_*%` 以及菜单内任意 `%…%` |
| JustTeams | 软依赖 | 氏族突袭 |
| LuckPerms | 软依赖 | 选区权限模板 |
| WorldGuardExtraFlagsPlus | 软依赖 | 额外 Flag / 占位符 |

插件无需软依赖即可运行：不可用的机制只会向玩家回复 `lang.yml` 中的
相应消息。

### 1.2 安装

1. 安装 **WorldGuard**（必需）。
2. 将 `QQRegions.jar` 放入 `plugins/`。
3. 重启服务器——将生成 `config.yml`、`lang.yml`、`replace.yml`、
   `shop.yml`、`menus/*.yml`（首次操作时还会生成 `data.yml`）。
4. 配置插件并执行 `/region reload`。

### 1.3 首次启动

首次启动后，插件文件夹中：

| 文件 | 用途 |
|---|---|
| `config.yml` | 插件所有设置 |
| `lang.yml` | 面向玩家/管理员/控制台的所有文本（插件单语言） |
| `replace.yml` | 翻译 Flag 的「原始」值（yes/no、allow/deny） |
| `shop.yml` | 商店：Flag 价格、「+面积」「+区域」礼包 |
| `data.yml` | 玩家购买记录、报价、租约（自动生成） |
| `menus/*.yml` | 所有 GUI：info、flags、players、playerconfirm、market、marketconfirm、flagshop、blocks、myflags、help 等 |

### 1.4 别名

命令名和别名在 `config.yml` 中设置：

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

执行 `/region reload` 即可生效，无需重启服务器。下文所有 `/region`
均代表任意别名。

---

## 2. 快速开始

### 第 1 步. 选区

```text
/region select
```

玩家会获得快捷栏按钮：**「创建区域」**、**「点 1」**、**「点 2」**、
**「选定区域」**、**「重置选区」**、**「取消」**。按钮槽位在
`config.yml`（`interactive.buttons.<id>.slot`）中配置。会话会保存玩家的
背包并在退出时恢复。

![交互式选区](docs/screenshots/02.png)

- **左键 / 右键** 手持按钮——执行按钮动作（在空中和对准方块均可）；
- **「点 1/2」按钮**——按准星快速放置点位（最多 300 格），否则放在玩家位置；
- **左键 / 右键** 空手——切换当前操作的点（1 或 2）；
- **滚轮或按键 4/6**——移动点位（在 select 模式下，所选槽位始终保持在
  中间——`interactive.select-center-slot`，因此键盘切换槽位与滚轮效果相同）；
- **Shift + 滚轮**——移动速度 ×`wheel-shift-speed`；
- **Shift + 左键**——确认选区。

会话期间会屏蔽 `interactive.blocked-commands` 中的命令（默认为 `ah`、
`sell`、`shop`、`baltop`）——防止通过拍卖/商店「丢失」按钮。

按钮、菜单面板和任意物品的名称与描述均支持 **MiniMessage**：渐变
（`<gradient:#55ffff:#ff55ff>`）、`<rainbow>`、`<color:#RRGGBB>`；文本中
直接出现的裸 `#RRGGBB` 也会被着色。

### 第 2 步. 创建区域

```text
/region create <名称>
```

确认选区后，插件会要求你在聊天框中输入名称（`cancel` 可取消，选区会保留）。
名称会被校验：

- 是否符合 `region-name.regex`（默认为 `[A-Za-zА-Яа-я0-9_-]{3,32}`）；
- 是否属于 `restrictions.banned-regions` 中的禁用区域；
- 是否超过 `regions.max-regions` 上限加上已购「+区域」礼包。

![输入区域名称](docs/screenshots/04.png)

### 第 3 步. Flag 与成员

```text
/region flags <区域>     — Flag 菜单（左键——开/关/默认，右键——分组）
/region add <玩家> <区域> — 授予成员身份
/region add owner <玩家> <区域>
```

Flag 只有在玩家拥有 `qqregions.flags.use.<flag>` 权限时才显示
（管理员可见全部；旧版权限 `qqregions.flags.<flag>` 同样被接受）。商店中
购买的 Flag 无需权限即可对所有者显示。

### 第 4 步. 边界高亮

```text
/region visible <区域> [particles|blocks|territory]
/region visible off        — 隐藏所有高亮
```

高亮可通过 `territory-visible` Flag（在 Flag 菜单中）绑定到区域：玩家
进入区域时边界自动高亮，并在 `highlight.region-hide-seconds` 后熄灭。
区域超时与选区无关（`interactive.view-hide-after`）。`highlight.auto-show-radius`
范围内的自己区域（所有者/成员）同样高亮——**仅当** `territory-visible: allow` 时。

![区域边界高亮](docs/screenshots/07.png)

### 第 5 步. 市场与商店

```text
/region market                — 市场菜单
/region market flags          — Flag 商店
/region market blocks         — 扩展（面积、「+区域」）
/region sell <金额> [区域]    — 出售区域（公开挂牌）
/region rent <金额> <时间> [区域] — 出租区域（公开挂牌）
/region buy [区域]            — 购买公开挂牌的区域
/region tenant [区域]         — 租用公开挂牌的区域
```

---

## 3. 命令

完整列表也可在游戏中查看：`/region help`（文本输出）或菜单中的
**「帮助」**按钮（全部模板）。

### 3.1 选区

| 命令 | 描述 |
|---|---|
| `/region select` | 交互模式（快捷栏按钮） |
| `/region select pos <1\|2>` | 在玩家位置设置点 |
| `/region select point <1\|2>` | `pos` 的同义词 |
| `/region select max` | 按模板在玩家周围允许的最大区域 |
| `/region select chunk [N]` | 选择 N 个区块（默认为模板上限） |
| `/region select expand [方向] <N>` | 向外扩展 N 格（`-N` 为缩小） |
| `/region select outset <N> [h\|v]` | 向所有方向扩展（h——水平，v——垂直） |
| `/region select view <玩家>` | 查看其他玩家的选区 |

`expand` 的**方向**：`north`、`south`、`east`、`west`、`up`、`down`。

命令式选区的结果可以看到：`select` 结束时的状态栏，加上高亮
`interactive.command-selection-view` 和自动隐藏 `command-selection-hide-after`。

### 3.2 区域

| 命令 | 描述 |
|---|---|
| `/region create <名称>` | 从当前选区创建区域 |
| `/region delete [名称]` | 删除自己的区域（或你所在的区域） |
| `/region info [名称]` | 打开区域信息菜单 |
| `/region flags [名称]` | 打开区域 Flag 菜单 |
| `/region reload` | 重载所有配置（+ 别名 + 语言） |

### 3.3 成员与所有者

| 命令 | 描述 |
|---|---|
| `/region add <玩家> [区域]` | 将玩家添加为成员 |
| `/region add member <玩家> [区域]` | 同上，显式写法 |
| `/region add owner <玩家> [区域]` | 添加为所有者 |
| `/region remove <玩家> [区域]` | 移除成员 |
| `/region remove owner <玩家> [区域]` | 移除所有者（最后一位所有者不可移除） |

### 3.4 高亮

| 命令 | 描述 |
|---|---|
| `/region visible [名称] [类型]` | 显示区域边界 |
| `/region visible off` | 隐藏所有活动高亮 |
| `/region visible type [类型]` | 默认类型（不带参数——显示当前类型） |
| `/region visible self on\|off` | 个人「自用」高亮：仅影响 `territory-visible` Flag（命令和菜单始终显示） |
| `/region visible true\|allow\|false\|deny [类型] [区域]` | 区域高亮 Flag |
| `/region view [名称] [类型]` | 临时显示边界（不改变 Flag） |

**类型：** `particles`、`blocks`（发光显示牌）、`territory`
（沿地形轮廓）。对于 `particles`/`blocks` 类型的区域，内部网格
`outline.grid` 会生效——在**全部 6 个面**上绘制方格（顶部、底部和四个
侧面）；玩家选区则**只绘制 12 条棱**（与之前完全一致，无环和网格——参见
`outline.rings`/`grid`，它们仅用于区域高亮）。`territory` + `blocks`——
仅由指定方块构成的围栏。

### 3.5 市场 (Vault)

| 命令 | 描述 |
|---|---|
| `/region market` | 市场菜单：挂牌列表、搜索、排序 |
| `/region market flags` | Flag 商店 |
| `/region market blocks` | 扩展：面积礼包和「+区域」 |
| `/region sell <金额> [区域]` | 出售区域——**公开挂牌**（任何人可立即购买） |
| `/region sell <玩家> <金额> [区域]` | 向指定玩家发出购买邀约（私密报价） |
| `/region rent <金额> <时间> [区域]` | 出租区域——**公开挂牌**（任何人可立即租用） |
| `/region rent <玩家> <金额> <时间> [区域]` | 向指定玩家发出租赁邀约 |
| `/region rent dur <分钟> [区域]` | 租赁挂牌的有效期（默认——`market.list-duration-minutes`） |
| `/region buy [区域]` | 购买公开挂牌的区域 |
| `/region tenant [区域]` | 租用公开挂牌的区域 |
| `/region sell\|rent\|buy\|tenant accept\|decline\|cancel <id>` | 接受/拒绝/取消报价或挂牌 |
| `/region market myflags` | 已购 Flag 菜单 |

**租赁时间：** `30`（分钟）、`2h`（小时）、`7d`（天）、`1w`（周）、`1m`（月）、
`1y`（年）。默认——1 周。

**公开挂牌**可由除卖家/所有者以外的任何玩家接受。如果启用了自动回收
（`market.rent.auto-rent`，市场菜单中的自动回收按钮），租赁到期后挂牌会
在市场上再次出现，持续 `list-duration-minutes`。

### 3.6 氏族突袭 (JustTeams)

| 命令 | 描述 |
|---|---|
| `/region raid [区域]` | 启动突袭（info 菜单中的「突袭」按钮仅供外人使用） |

---

## 4. 权限

基础权限会自动授予所有人；服务和 `bypass` 权限仅授予管理员。

| 权限 | 默认 | 描述 |
|---|---|---|
| `qqregions.use` | `true` | 基础命令访问 |
| `qqregions.create` | `true` | 创建区域 |
| `qqregions.delete` | `true` | 删除区域 |
| `qqregions.select` | `true` | 选区 |
| `qqregions.info` | `true` | 区域信息 |
| `qqregions.manage` | `true` | 管理成员/所有者 |
| `qqregions.flags` | `true` | Flag 菜单 |
| `qqregions.visible` | `true` | 边界高亮 |
| `qqregions.market` | `true` | 市场（sell/rent/buy/tenant/market） |
| `qqregions.raid` | `true` | 氏族突袭（JustTeams） |
| `qqregions.reload` | `op` | 重载配置 |
| `qqregions.admin` | `op` | 完全访问，包括他人区域 |
| `qqregions.bypass.selection-limits` | `op` | 忽略模板 `max-blocks`/`min-blocks` |
| `qqregions.bypass.disabled-worlds` | `op` | 可在 `disabled-worlds` 世界工作 |
| `qqregions.bypass.banned-regions` | `op` | 可使用 `banned-regions` |

`qqregions.admin` 包含：`reload`、`create`、`delete`、`select`、`info`、
`manage`、`flags`、`visible`、`market`、`raid`。

另外，Flag 菜单使用动态权限 **`qqregions.flags.use.<flag>`**（例如
`qqregions.flags.use.pvp`）：它会在 Flag 菜单**和 Flag 商店**中显示该 Flag，
并允许修改/购买。`qqregions.admin` 权限可查看/修改/购买一切。简单方案——
授予 `qqregions.flags.use.*` 特权（例如在 LuckPerms 玩家组中）：玩家即可
访问所有 Flag。旧版权限 `qqregions.flags.<flag>` 同样被接受（兼容）。

**分组模板（`config.yml` → `flag-groups`）**——批量授予 Flag：拥有
`qqregions.flags.group.<名称>` 权限的玩家可自动查看该分组列表中的所有 Flag
（查看/修改/购买）。多个分组可叠加；列表中的 `["*"]` 表示所有 Flag。可与单独
的 `qqregions.flags.use.<flag>` 同时使用。

> 分组示例：给「新手」组 `qqregions.flags.group.newbie`（配置中为 `pvp`、
> `build`），给「老手」组 `qqregions.flags.group.veteran`（较宽泛的列表）——
> 新手在商店和 Flag 菜单中只会看到自己的集合，老手看到自己的；公共权限可
> 通过 `qqregions.flags.use.<flag>` 按需补充。

> 注意：`bypass.*` 和 `admin` 建议在 LuckPerms 中授予特定组
> （参见模板章节）。

---

## 5. 占位符

插件支持两类占位符：

1. **内部**——`{名称}`——由插件在 `lang.yml`、`menus/*.yml`、`config.yml`
   文本和突袭通知中替换。
2. **外部 PAPI**——`%qqregions_*%`——来自 QQRegions 自带扩展，以及任何公共
   占位符（例如 `%vault_eco_balance%`）。

### 5.1 菜单通用

`{region}` `{world}` `{player}` `{role}` `{page}` `{pages}`

### 5.2 按上下文

| 上下文 | 占位符 |
|---|---|
| info 菜单 | `{owners}` `{members}` `{type}` `{area}` `{volume}` `{priority}` `{status}` `{my-regions}` `{max-regions}` `{max-blocks}` |
| Flag 菜单 | `{flag}` `{flag-name}` `{flag-value}` `{flag-value-label}` `{flag-raw}` `{group}` `{group-label}` `{groups-list}` `{flag-group}` `{flag-group-label}` `{flag-with-group}` `{next-state}` |
| 玩家菜单 | `{player}` `{role}` `{role-ru}` `{player-id}` |
| 玩家搜索 | `{ps-group}` `{ps-balance}` `{ps-balance-symbol}` `{ps-regions}` `{ps-max}` `{ps-clan}` `{ps-sort-list}` |
| 确认（playerconfirm） | `{pc-player}` `{pc-role}` `{pc-action}` `{pc-balance}` `{pc-balance-symbol}` `{pc-clan}` `{pc-regions}` `{pc-reg-owner}` `{pc-reg-member}` `{pc-max}` |
| 领地选择器 | `{rp-world}` `{rp-type}` `{rp-people}` `{rp-area}` `{rp-dist}` `{rp-sort-list}` |
| 市场菜单 | `{market-type}` `{market-region}` `{market-world}` `{market-price}` `{market-price-symbol}` `{market-who}` `{market-owner}` `{market-status}` |
| 市场全息投影（market-holo） | `{owner}`（卖家/所有者昵称）`{price}` `{price-symbol}` `{nick}` `{time}` `{region}` |
| Flag 商店 | `{flag-name}` `{flag}` `{price}` `{price-symbol}` |
| 扩展商店 | `{pack-name}` `{name}` `{pack-amount}` `{price}` `{price-symbol}` |
| info 菜单（突袭按钮） | `{raid-clan}` `{raid-balance}` `{raid-balance-symbol}` `{raid-online}` `{raid-total}` `{raid-in-region}` `{raid-needed}` |
| 选区 Bossbar | `{current}` `{max}` `{percent}` `{player}` `{value-color}` |
| 附加信息 Actionbar | `{height-top}` `{height-bottom}` `{conflict}` `{conflict-regions}` `{conflict-count}` `{current}` `{max}` `{percent}` `{player}` |
| 突袭（进度条/通知） | `{region}` `{world}` `{clan}` `{count}` `{total}` `{thief}` `{time}` `{percent}` `{player}` |

> 所有金额占位符（`{price}`、`{market-price}`、`{raid-balance}`、`{ps-balance}`、
> `{pc-balance}`）**只返回数字**（按 `market.economy` 格式）。货币符号需单独
> 添加——每个都有对应的 `-symbol`：`{price} {price-symbol}` 等等。如果模板中
> 没有 `-symbol`，则数字不带符号显示。

### 5.3 外部 PAPI 占位符 `%qqregions_*%`

如果安装了 PlaceholderAPI，扩展会自动注册。可通过 `config.yml` 中的
`placeholders.enabled` 完全关闭；列表分隔符（`owners-separator`、
`members-separator`、`owned-separator`、`membered-separator`）也在
`placeholders` 部分中。

**选区**

| 占位符 | 值 |
|---|---|
| `%qqregions_selection_active%` | `yes`/`no`——是否存在选区 |
| `%qqregions_selection_blocks%` | 选区中的方块数 |
| `%qqregions_selection_max_blocks%` | 模板上限（有 bypass 时为 ∞） |
| `%qqregions_selection_min_blocks%` | 最小尺寸 |
| `%qqregions_selection_chunks%` | 按模板计算的区块上限 |
| `%qqregions_selection_percent%` | 占上限的百分比（0–100） |
| `%qqregions_selection_over_limit%` | `yes`/`no` |
| `%qqregions_selection_below_min%` | `yes`/`no` |
| `%qqregions_selection_conflict%` | `yes`/`no`——是否与其他区域重叠 |
| `%qqregions_selection_conflict_count%` | 冲突区域数量 |
| `%qqregions_selection_conflict_regions%` | 它们的 id，逗号分隔 |
| `%qqregions_selection_height_top%` | 距离上边界的方块数 |
| `%qqregions_selection_height_bottom%` | 距离下边界的方块数 |
| `%qqregions_selection_pos1_x/y/z%` | 第一个点的坐标（按放置时） |
| `%qqregions_selection_pos2_x/y/z%` | 第二个点的坐标 |

**区域与经济**

| 占位符 | 值 |
|---|---|
| `%qqregions_region_current%` | 玩家所在区域的 id |
| `%qqregions_region_flags%` | 区域已设置的 Flag：`flag:值, ...` |
| `%qqregions_region_flag_<flag>%` | 某个特定 Flag 的值（未设置则为空） |
| `%qqregions_region_owners%` | 当前区域所有者的名称 |
| `%qqregions_region_members%` | 当前区域成员（不含所有者）的名称 |
| `%qqregions_region_owner_is%` | `yes`/`no`——玩家是否为当前区域所有者 |
| `%qqregions_region_member_is%` | `yes`/`no`——玩家是否为当前区域成员（所有者也算） |
| `%qqregions_region_role%` | 在当前区域中的角色：`OWNER` / `MEMBER` / `NONE` |
| `%qqregions_region_price_<世界:区域>%` | 有效报价的价格（无则 `0`）；仅数字，无货币符号 |
| `%qqregions_region_for_sale_<世界:区域>%` | `yes`/`no`——正在出售 |
| `%qqregions_region_for_rent_<世界:区域>%` | `yes`/`no`——正在出租 |
| `%qqregions_region_owner_<世界:区域>%` | 区域所有者 |
| `%qqregions_region_rent_time_<世界:区域>%` | 区域租赁期限 |
| `%qqregions_player_owned_regions%` | 玩家拥有的区域（管理员则为全部），逗号分隔 |
| `%qqregions_player_owned_count%` | 其数量 |
| `%qqregions_player_membered_regions%` | 玩家作为成员（非所有者）的区域，逗号分隔 |
| `%qqregions_player_membered_count%` | 其数量 |
| `%qqregions_eco_balance%` | 格式化余额——**仅数字**（无货币符号） |
| `%qqregions_eco_balance_symbol%` | 来自 `market.economy.symbol` 的货币符号（禁用时为空白） |
| `%qqregions_eco_balance_raw%` | 「原始」余额 |
| `%qqregions_eco_has_<金额>%` | `yes`/`no`——资金是否足够 |
| `%qqregions_market_listings%` | 活跃挂牌数量 |

**附近区域**

从玩家位置到区域边界的水平（2D）距离；玩家站在区域内则为 0。
`restrictions.banned-regions` 中的区域不参与列表和计数。

| 占位符 | 值 |
|---|---|
| `%qqregions_nearby_region%` | 最近区域的 id（无则为空） |
| `%qqregions_nearby_region_distance%` | 到最近区域的方块距离 |
| `%qqregions_nearby_region_count%` | 100 方块半径内的区域数量 |
| `%qqregions_nearby_region_count_<半径>%` | 同上，使用显式半径 |

**突袭**

| 占位符 | 值 |
|---|---|
| `%qqregions_raid_active%` | `yes`/`no`——突袭是否进行中 |
| `%qqregions_raid_state%` | `idle` / `capturing` / `thief` / `cooldown` |
| `%qqregions_raid_region%` / `%qqregions_raid_world%` | 突袭的区域与世界 |
| `%qqregions_raid_clan%` / `%qqregions_raid_thief%` | 氏族与「窃贼」 |
| `%qqregions_raid_count%` / `%qqregions_raid_players%` / `%qqregions_raid_total%` | 进攻者数量 |
| `%qqregions_raid_remaining%` / `%qqregions_raid_time%` | 距阶段结束的秒数 |
| `%qqregions_raid_cooldown%` | 区域冷却秒数 |

> 菜单文本中可以写入任意第三方 `%…%`——它们会针对具体玩家解析
> （各玩家余额的示例见 `menus/players.yml`）。在所有者的菜单中，所有者玩家
> 的按钮按该具体玩家解析，而非打开菜单的人。

---

## 6. 菜单

GUI 由 `menus/*.yml` 构建。每个菜单可按玩家角色（`role-required`）和
优先级设置多个**模板**：

| 角色 | 适用于 |
|---|---|
| `owner` | 区域所有者 |
| `member` | 区域成员 |
| `other` | 外人 |
| （空） | 任何人 |

模板包含：标题、尺寸、填充面板、静态按钮（带 `lore`、点击命令、可选的
`permission`，以及 `tooltip: false`——隐藏指定按钮的悬停提示；`fill`
中的 `tooltip: false` 键可隐藏背景的提示）、动态槽位和分页。动态按钮在
代码中组装（WorldGuard Flag、区域玩家、市场报价、购买记录），并按
`slots`/`menu-slots` 排列。

**按钮伪命令：**

| 伪命令 | 动作 |
|---|---|
| `@page:prev` / `@page:next` | 翻页 |
| `@menu:<名称>` | 打开其他菜单（info、flags、players、market、flagshop、blocks、myflags、help） |
| `@back` | 返回上一个菜单 |
| `@teleport` | 传送到区域中心 |
| `@highlight` | 高亮区域边界 |
| `@flag:<名称>:<allow\|deny\|default>` | 切换 Flag（`default` = 移除，如同 `/rg flag -r`） |
| `@flag-search` / `@market-search` | 按 Flag / 市场搜索 |
| `@sort` | 切换市场排序 |
| `@add:owner` / `@add:member` | 添加玩家（在聊天中输入昵称） |
| `@player-del:<uuid>\|昵称>:owner\|member` | 移除玩家 |
| `@pf:all\|owners\|members` | 玩家列表筛选 |
| `@raid:start` | 启动突袭 |
| `@region-info-or-pick` | 「我的领地」：在区域中——info，否则——领地选择器 |
| `@region-delete` | 确认删除领地（WG） |
| `@select` | 启用交互式选区（主菜单中的「创建领地」） |
| `@ps-sort` | 玩家搜索排序循环（A-Z/Z-A/余额/区域±/距离±） |
| `@rpsort` | 领地选择器排序循环（近/远/A-Z/Z-A/人数±/面积±） |
| `@rinfo:<世界>:<区域>` | 打开指定区域的 info |
| `@menu:help` | 打开帮助 |
| `@menu:main` | 返回主菜单（info 菜单中的「返回主菜单」按钮，槽位 0） |
| `message!<文本>` | 向玩家发消息，不带插件前缀 |
| `gMessage!<文本>` | 向服务器全体玩家发消息 |
| `title:<停留>:<停留>:<淡入>!<文本>` | 标题，以 tick 计时（无 `:…!`——默认 20/40/20） |
| `title!<文本>` | 标题（20/40/20） |
| `actionbar:<tick>!<文本>` | Actionbar 显示 N tick（无数字——60 tick） |
| `sound!<音效> [音量] [音调]` / `gSound!…` | 播放音效给玩家 / 所有人 |
| `asConsole!<命令>` / `asPlayer!<命令>` | 以控制台 / 以玩家身份执行 |
| `delay:<tick>!<动作>` | 延迟执行动作 |
| `close` | 关闭菜单 |

除 `close` 外，所有动作类型不仅可用于菜单按钮，还可用于商店商品
（`shop.yml`）的 `commands`/`allow-cmds`/`deny-cmds` 以及突袭通知
（`config.yml` → `raid.notify.*.commands`）。

### 6.1 区域信息菜单

由 `/region info` 打开。合并的信息按钮：**区域**（世界、类型、状态、面积、
体积、优先级、角色；所有者/成员还显示玩家上限 `{my-regions}/{max-regions}`
和 `{max-blocks}`；无上限/有管理员权限时为 `∞`）和**玩家**（所有者 + 成员）。
槽位 0 是**「返回主菜单」**按钮（`@menu:main`）：默认 `menus/info.yml` 中
安装时即添加，而在自定义文件中会由代码自动插入（仅当槽位 0 空闲）。所有者
拥有一排按钮：**Flag**、**玩家**、**传送**（权限 `qqregions.admin`）、
**高亮**、**市场**、**删除**（进入确认菜单），以及**突袭**——仅限外人
（`role-required: other`，权限 `qqregions.raid`），带氏族 lore
`{raid-clan}` `{raid-balance}` `{raid-online}/{raid-total}`
`{raid-in-region}/{raid-needed}`。当 `raid.enabled: false` 时，突袭按钮
完全隐藏。

![区域信息菜单](docs/screenshots/01.png)

### 6.1a 删除确认

由所有者 info 菜单中的「删除领地」按钮打开。**是，删除**
（`@menu:confirmdelete` → `@region-delete`，通过 WG 删除区域）/ **取消**
（`@back`——返回 info）。

### 6.1b 领地选择器

主菜单中的「我的领地」按钮：如果玩家位于区域内——直接打开 info；
否则——从所有世界的所有区域中选择。每个区域带 lore（世界/类型/玩家/面积/
距离），点击——info。`@rpsort` 排序循环：近 → 远 → A-Z → Z-A → 按人数
（升/降）→ 按面积（升/降）；当前模式在「排序」按钮的 lore 中以绿色高亮。

**「创建领地」**按钮（主菜单槽位 31，`@select`）启用交互式选区——如同不带
参数的 `/region select`：菜单关闭，快捷栏会放入按钮：「点 1」/「点 2」
（点击——放置点位，滚轮——移动当前点）、「创建」（输入名称）、「重置」
和「取消」。

### 6.2 Flag 菜单

由 `/region flags` 打开。动态按钮——按 WorldGuard Flag 生成（`ignore-flags`
中的除外）。名称来自 `config.yml`（`flags-names`），值来自 `replace.yml`。
左键——值循环：开 → 关 → **默认**（移除，使用 WorldGuard 默认值，如同
`/rg flag -r`）→ 开；右键——切换**分组**（all/members/owners/nonmembers/
nonowners）。当显示无自身值的 Flag 时，按钮显示「未设置」，第 3 次点击会
移除 Flag。

只有拥有对应权限的玩家才会看到 Flag：`qqregions.flags.use.<flag>`（通过
LuckPerms 或单独授予玩家）、旧版 `qqregions.flags.<flag>`，或来自
`config.yml` → `flag-groups` 的分组模板 `qqregions.flags.group.<名称>`。
管理员/OP 可见全部。商店中购买的 Flag 始终可见。

![Flag 菜单](docs/screenshots/05.png)

### 6.3 玩家菜单

动态按钮——所有者（DIAMOND）和成员（GOLD_INGOT）。**+ 所有者 / + 成员**
按钮、「移除成员 / 移除所有者」筛选、**添加玩家**（打开玩家搜索菜单）。
操作权限——仅所有者和管理员。

### 6.3a 玩家搜索菜单

玩家菜单中的「添加玩家」按钮。服务器所有玩家：在线优先，之后是离线，不含
自己；玩家皮肤头颅。每个头颅显示：选定的添加分组、余额（Vault）、区域数量
（所有者+成员）和玩家的上限、氏族（JustTeams）。左键——添加到所选分组
（确认），右键——移除（确认），Shift+左键——切换添加分组。通过「排序」按钮
（槽位 4）排序：A-Z/Z-A/按余额/按区域数量（升/降）/按距离（远/近）。
最后一位所有者不可被移除。添加/移除确认——playerconfirm.yml 菜单。

![玩家菜单](docs/screenshots/06.png)

### 6.4 市场菜单

活跃挂牌列表（搜索、排序：名称 → 价格 → 默认）。按钮：**扩展**、**Flag
商店**、**我的 Flag**、**帮助**。每个挂牌：

- **自己的**——左键「自动回收」（针对租赁）/取消，右键——取消；
  自己的公开租赁还有额外的**挂牌时长**按钮（`rent dur`）；
- **他人的**——左键——立即购买/租用（公开）或接受（私密报价）。

![市场菜单](docs/screenshots/08.png)

### 6.5 Flag 商店

可出售的 Flag（来自 WorldGuard 注册表——包括任意其他插件的 Flag，
例如 WGEFP，`flags-menu.whitelist` 和 `flags-menu.shop-ignore` 除外），
价格来自 `shop.yml`。玩家**看到并可购买** Flag 的前提是拥有权限：
`qqregions.flags.use.<flag>`（单独或通过 LuckPerms 组）**或**来自
`config.yml` → `flag-groups` 的分组模板 `qqregions.flags.group.<名称>`——
没有权限，商店中就没有该 Flag，也无法购买；授予后才会出现。旧版权限
`qqregions.flags.<flag>` 同样被接受。管理员/OP 可见并购买一切。搜索——
按名称或翻译。

![Flag 商店](docs/screenshots/09.png)

### 6.6 扩展商店

按钮由 `shop.yml` 构建，并按 `priority` 字段排序（数值小——在前）。
**「+面积」**礼包（增加 `max-blocks`，一次性）和**「+区域」**礼包（增加
区域上限，可重复或含 `max-purchases` 上限），以及自定义商品 `custom-items`
——购买后一次性授予权限/命令（条件、`allow-cmds`/`deny-cmds`，见 §7.2）。

每个商品都有**`max-purchases`**（购买上限；`<=0` = 不限次）和
**`bought-display`**：`HIDE`（默认）——已购且额度用尽的商品消失，其余按钮
前移；`RED_GLASS`——其位置显示红色玻璃「&c名称」并带 lore「&7已购买」。
Flag 的全局开关是 `shop.yml` → `flags.bought-display`（HIDE | RED_GLASS）。

![扩展商店](docs/screenshots/10.png)

### 6.7 我的 Flag

仅显示玩家已购买的 Flag。无购买记录时打开会回复一条消息
（`lang.yml` → `shop.none-owned`）。

![我的 Flag](docs/screenshots/11.png)

### 6.8 帮助菜单

按章节展示插件命令的静态按钮（模板 `default` 和 `compact`）。

![帮助菜单](docs/screenshots/13.png)

---

## 7. 配置

### 7.1 config.yml

主要章节：

| 章节 | 配置内容 |
|---|---|
| `command` | 命令名和别名 |
| `restrictions` | `disabled-worlds`、`banned-regions` |
| `region-name` | 校验 `regex` 和强制小写 |
| `selection-templates` | 选区权限模板（见 8.1 节） |
| `interactive` | 交互式 select：滚轮速度、按钮（材质 + 快捷栏槽位）、select 中心槽位、禁用命令、`sync-worldedit`、view 模式和自动隐藏 |
| `highlight` | 区域高亮：类型、`region-hide-seconds`（区域轮廓自动消失超时，与选区无关；旧的 `auto-hide-seconds`/`show-seconds` 为回退）、冷却、`hide-on-exit`/`show-on-exit`、`auto-show-radius`（自己/他人——仅按 `territory-visible` Flag）、`territory`（围栏 + `ignore-blocks`）、`particles` |
| `outline` | 通用虚线轮廓：`max-gap`（任意直线的点距，如 5）、`max-points`、`rings`（按高度绘制环）、`grid`（在全部 6 个面绘制方格——仅限区域；玩家选区：旧样式的 12 条棱） |
| `particles` | 选区粒子 |
| `bossbar` / `select-status` | 选区状态：bossbar/actionbar、文本 `normal/full/conflict`、附加信息（`info.text`） |
| `guard` | 防卡顿/防高延迟 |
| `regions` | `max-regions` 上限（0 = 无上限） |
| `flags-menu` | `whitelist`（免费 Flag）和 `shop-ignore`（不出售） |
| `flags-names` | 自定义 Flag 名称 |
| `flag-groups` | Flag 权限分组模板：`qqregions.flags.group.<名称>` 权限可立即打开整组 Flag 列表（见第 4 节） |
| `menu-update` | 菜单自动更新：`ticks`（已打开菜单的全局重绘间隔，默认 20）和 `debounce-after-click`（防自动点击，默认关闭：为 true 时，点击按钮会将重绘推迟菜单的 `update_interval`） |
| `placeholders` | PlaceholderAPI 集成：`enabled`（开关 `%qqregions_*%` 扩展）、`owners-separator` / `members-separator` / `owned-separator` / `membered-separator` — 所有者、成员和玩家区域列表的分隔符 |
| `market` | 市场：`enabled`、`multiowner`（single/split）、`commission`（服务器百分比）、`offer-timeout-minutes`（私密报价有效期）和 `offer-timeout-action`（RELIST——公开挂牌 / CANCEL——下架）、经济（符号/分组）、租赁（`grant`、`charge`、`period-minutes`、`list-duration-minutes`、`auto-rent`）、招牌全息投影（`market-holo`——沿边界有**一个**悬浮全息投影，跟随玩家「绕飞」并始终面向玩家） |
| `raid` | 突袭：阶段、费用、显示、通知 |

高亮示例（轮廓 + 领地）：

```yaml
highlight:
  type: PARTICLES            # PARTICLES | BLOCKS | TERRITORY
  region-hide-seconds: 60    # 区域轮廓自动消失超时
  territory:
    ignore-blocks:           # TERRITORY 视为空白的方块
      - BROWN_MUSHROOM
      - RED_MUSHROOM
      - TALL_GRASS
      - SHORT_GRASS
    fence:                   # 「围栏」(territory.display: BLOCKS)
      material: OAK_PLANKS
      height: 1.0
      width: 0.3
      thickness: 0.3
      spacing: 1.0
      offset: 0.0
      glow: true

outline:                     # 通用虚线轮廓（选区 + 区域）
  max-gap: 5                 # 任意直线的点距（不超过 5 格）
  max-points: 3000           # 轮廓点数上限
  rings:
    enabled: true
    step: 8                  # 每 8 格高度一个环
  grid:                      # 顶部/底部方格——仅限区域
    enabled: true
    step: 25
```

### 7.2 shop.yml

```yaml
enabled: true
economy-enabled: true

flags:
  default-price: 1000
  # 已购 Flag 以红色玻璃显示（RED_GLASS）还是隐藏（HIDE）。
  bought-display: HIDE
  prices:
    pvp: 500
    build: 800
    entry: 600

area-packs:
  big:
    name: "大型领地"
    blocks: 20000            # amount（也读取 "amount"/"regions"）
    price: 5000
    material: GOLD_INGOT
    priority: 0              # 按钮顺序（越小越靠前）
    max-purchases: 1         # 购买上限（<=0 = 可重复）
    bought-display: HIDE     # RED_GLASS | HIDE

region-packs:
  extra1:
    name: "+1 区域"
    regions: 1
    price: 1000
    priority: 10
    max-purchases: 0         # 0 = 可重复

custom-items:                # 自定义商品（授予权限/命令）
  vip:
    name: "VIP 特权"
    material: NETHER_STAR
    lore:
      - "&7授予 VIP 特权"
    price: 5000
    max-purchases: 1
    bought-display: RED_GLASS
    priority: 20
    conditions:              # AND：全部为真 -> allow-cmds，否则 deny-cmds
      - "%vault_rank%==Default"
    commands:                # 无 conditions 时执行 commands
      - "asConsole! lp user {player} parent add vip"
      - "message! &aVIP 权限已激活！"
      - "sound! ENTITY_PLAYER_LEVELUP 1 1"
    allow-cmds:              # 条件满足时执行的动作
      - "message! &a权限已授予"
    deny-cmds:
      - "message! &c不允许重复购买 VIP。"
```

**商品通用字段**（`area-packs` / `region-packs` / `custom-items`）：
`name`、`price`（0/负值 = 不出售）、`material`、`amount`（方块/区域/用于
lore 的数字；area 也读取 `blocks`，region 读取 `regions`）、`max-purchases`、
`bought-display`、`priority`、`conditions`、`commands`、`allow-cmds`、
`deny-cmds`、`lore`（仅 custom-items）。

**条件**（`conditions`）——`占位符 运算符 值`：运算符 `=` `!=` `>` `<` `>=`
`<=`（数字）、字符串 `<-`（包含）、`!<-`、`|-`（以…开头）、`!|-`、`-|`
（以…结尾）、`!-|`。占位符由 PlaceholderAPI 针对买家解析。

**动作**（`commands`/`allow-cmds`/`deny-cmds`）支持所有动作类型（见 §6 中的
伪命令表）：`message!`、`gMessage!`、`title!`/`title:…!`、
`actionbar!`/`actionbar:N!`、`sound!`、`gSound!`、`asConsole!`、`asPlayer!`、
`delay:N!`。`{player}` → 买家昵称。

### 7.3 replace.yml

将 WorldGuard / WGEFP 的原始 Flag 值替换为可读文本：

```yaml
'%worldguard_region_has_flag_pvp%':
  - placeholder: 'yes'
    replacement: '已开启'
  - placeholder: 'no'
    replacement: '&7已关闭'
  - placeholder: 'ELSE'
    replacement: ''

flag-groups:
  - placeholder: 'all'
    replacement: '全部'
  - placeholder: 'ELSE'
    replacement: '{value}'

flag-values:
  - placeholder: 'allow'
    replacement: '&a已开启'
  - placeholder: 'deny'
    replacement: '&c已关闭'
  - placeholder: 'ELSE'
    replacement: '{value}'
```

`replacement` 中的 `{value}` 会代入原始值。

### 7.4 lang.yml

面向玩家的所有文本。支持 `&` 颜色、`&#RRGGBB`、文本中直接出现的裸
`#RRGGBB` 以及 `{名称}` 占位符。此外还支持 **MiniMessage**：渐变
（`<gradient:#55ffff:#ff55ff>`）、`<rainbow>`、`<color:#RRGGBB>`、
`<hover:...>`、`<click:...>` 等——适用于任意物品的名称/描述和消息。
带 mini 标记的字符串由 MiniMessage 解析（其中的旧版 `&` 代码会自动转换），
损坏的 mini 字符串不会导致崩溃，会回退到普通 legacy 解析。翻译开头的特殊
前缀 **`actionbar:N!`** 会将文本发送到 Actionbar 显示 N 秒（实时更新，不带
插件前缀）：

```yaml
prefix: "&8[&bQQRegions&8] "

guard:
  blocked: "actionbar:3!&c服务器繁忙，请稍候。"
```

该前缀适用于插件输出的**任意**消息（命令、聊天提示、菜单点击结果、突袭
通知）：如果行首是 `actionbar:N!`，文本进入 Actionbar，否则进入聊天。
控制台收到纯文本。

在金额模板中请使用「数字 + 符号」占位符对（`{price} {price-symbol}`、
`{raid-balance} {raid-balance-symbol}` 等）。命令语法的详细模板位于
`usage:` 章节（例如 `/region help`），紧凑时间键为 `menu.time-short-*`，
选区状态中的「是/否」值为 `select-status.yes/no`。文件版本为
`config-version: 6`；从旧版插件迁移时，插件只会补充**空的**值——旧
`lang.yml` 中缺失的模板需要手动完善（例如为金额消息添加 `{price-symbol}`，
否则旧版本会继续显示不带符号的数字）。

### 7.5 data.yml

自动生成；请勿在运行中编辑：

```text
players.<UUID>.flags       — 已购 Flag
players.<UUID>.area-packs  — 面积礼包
players.<UUID>.region-packs.<id> — 「+区域」数量
players.<UUID>.custom-items.<id> — 自定义商品的购买次数
offers.*                   — 市场和租赁报价
```

### 7.6 menus/*.yml

GUI 布局。按钮/文本/槽位的修改在 `/region reload` 后生效。菜单底部槽位
（45–53）与 `menu-slots`（10–43）不重叠，因此不可能出现重复按钮。

每个菜单都有 `update_interval`（tick）——已打开菜单的重绘频率
（lore/价格/Flag 实时更新）；`0` = 关闭该菜单的自动更新。全局默认值是
config.yml 中的 `menu-update.ticks`。点击按钮会立即重绘菜单；可选的防自动
点击器 `menu-update.debounce-after-click: true`（默认关闭）会在点击后将已
打开菜单的重绘推迟 `update_interval` 个 tick——此时导航和按钮动作即时生效。
详情见 `menus/MENU_EDITOR.md` §8。

---

## 8. 高级配置

### 8.1 选区权限模板

为玩家选择 `priority` 最高且 `permission` **或** `placeholder` 匹配的模板：

```yaml
selection-templates:
  default:
    permission: ""            # 为空 = 不检查
    placeholder: ""           # "%plasma_skill_power%>=60" 或 "true"/"false"
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

`placeholder` 机制允许按任意数值型 PAPI 授予限制（`>=`、`>`、`<=`、`<`、
`==` 或仅为 `true`/`false`）。

### 8.2 防卡顿保护 (guard)

如果服务器负载高或玩家延迟高，菜单按钮点击（及其他机制）会被取消——
防止延迟导致的物品「复制」：

```yaml
guard:
  enabled: true
  min-tps: 15.0
  max-ping: 5000
```

### 8.3 突袭 (JustTeams)

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
    source: CLAN            # PLAYER——从窃贼余额（Vault）扣除，CLAN——从氏族金库
    percent: 10
  display:
    mode: ACTIONBAR         # BOSSBAR | ACTIONBAR | NONE
    text: "&c占领 {region}：&f{time}&c 秒 • 进攻者 &f{count}&c/&f{total}"
    thief-text: "&2窃贼 &f{thief}&2：&f{time}&2 秒"
  notify:
    start:
      message: "&8[&c氏族&8] &f{clan} &c正在进攻区域 &f{region}&7！"
      commands: []
```

通知支持 `asConsole!...` 和 `asPlayer!...` 命令，并支持
`{占位符}` 替换。

### 8.4 经济

数字格式在 `market.economy` 中设置：

```yaml
market:
  economy:
    symbol: "¥"
    symbol-position: AFTER     # AFTER = "1000 ¥", BEFORE = "¥ 1000"
    decimal-places: 0
    grouping: true
    group-separator: ","
    decimal-separator: "."
  rent:
    grant: MEMBER              # MEMBER | OWNER
    charge: PERIOD             # ONCE | PERIOD（周期性扣款）
    period-minutes: 1440
  list-duration-minutes: 10080 # 公开租赁挂牌的有效期（默认 7 天）
  auto-rent: true              # 租赁到期后自动回收并自动续租
  multiowner: single           # single——全部金额给发起人；split——所有所有者均分
  commission:
    enable: false              # 服务器售卖/租赁佣金
    rate: 0.01                 # 金额比例（0.01 = 1%，0.05 = 5%）。向收款方收取
  offer-timeout-minutes: 60    # 私密报价有效期（0 = 不限时）
  offer-timeout-action: RELIST # 到期时如何处理：RELIST——公开挂牌；CANCEL——下架
  market-holo:
    enabled: true              # 挂牌期间区域边界旁有**一个**招牌全息投影
    view-distance: 24          # 距离（X/Z 半径）内，全息投影会引导玩家沿边界移动
    y-offset: 1.5              # 相对于**玩家** Y 高度的偏移（0——正好在玩家 Y，1.5 ≈ 面部）
    scale: 1.0                 # 文本大小倍数
    line-width: 200            # 行最大长度（方块数）
```

---

## 9. 常见问题与排错

**菜单无法打开 / 命令回复「菜单未配置」。**
检查 `menus/` 中的文件并执行 `/region reload`。可通过移除 `dynamic-*` /
`purchased-*` 章节来停用个别菜单——插件随后会提供回退方案。

**市场无法工作。**
`market.enabled: true` + 已安装 Vault + 经济插件。没有 Vault 时，命令会
回复「经济（Vault）不可用」。

**Flag 商店为空。**
Flag 只有在**不在** `flags-menu.whitelist`（其中为免费 Flag）且不在
`flags-menu.shop-ignore` 中时才会出售。空 `whitelist` 表示「所有 Flag 都出售」。

**已购 Flag 在菜单中不可见。**
如果您是区域所有者——该 Flag 无需权限即可出现。对其他人而言，已购 Flag
并不授予对他人区域的权利。

**看不到选区点。**
检查 `particles.enabled: true`（或 `interactive.view-mode: BLOCKS`），并确认
世界未在 `restrictions.disabled-worlds` 中被禁用。

**由 Flag 触发的高亮不生效。**
`territory-visible` Flag 必须为 `allow`，且触发频率不超过
`highlight.cooldown-seconds`。当 `territory-visible: false` 时 Flag 会被
注册，但进入时不高亮（`/region visible` 命令可用）。`auto-show-radius`
范围内自己的区域也仅当 Flag 为 `allow` 时才发光——没有该 Flag 时，无论是
靠近还是在进入带 Flag 的相邻区域时都不会亮起。轮廓会在
`highlight.region-hide-seconds` 后自动熄灭（与选区超时无关）。

**突袭无法开始。**
检查 JustTeams（所有进攻者都属于同一氏族）、`min-attackers`、
`online-percent`、黑名单，以及所有者是否离线（如果
`owners-offline-required: true`）。

---