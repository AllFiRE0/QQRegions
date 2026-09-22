# QQRegions — Wiki

> ### 언어
>
> | [Русский](README.md) | [English](README.en.md) | [Português](README.pt.md) | [Deutsch](README.de.md) | [中文](README.zh.md) | [**한국어**](README.ko.md) |
> |:-:|:-:|:-:|:-:|:-:|:-:|

> **Paper / Leaf (api 26.2)** 서버를 위한 **WorldGuard** 기반 강력한 지역 관리 플러그인.

```
/region ...   /territory ...   /tr ...   /rg ...   /private ...   /zone ...
```

이 플러그인은 서버의 개인화(privatization) 전체 과정을 다룹니다: 인터랙티브
선택, 지역 생성 및 삭제, 플래그 메뉴, 경계 하이라이트, 멤버 관리, 판매/임대
마켓, 플래그·확장 상점, 클랜 레이드 — 그리고 모든 현지화 텍스트를 단일
파일에 담습니다.

---

## 목차

- [1. 요구 사항 및 설치](#1-요구-사항-및-설치)
- [2. 빠른 시작](#2-빠른-시작)
- [3. 명령어](#3-명령어)
- [4. 권한](#4-권한)
- [5. 플레이스홀더](#5-플레이스홀더)
- [6. 메뉴](#6-메뉴)
- [7. 설정](#7-설정)
- [8. 고급 설정](#8-고급-설정)
- [9. FAQ 및 문제 해결](#9-faq-및-문제-해결)

---

## 1. 요구 사항 및 설치

### 1.1 요구 사항

| 의존성 | 유형 | 용도 |
|---|---|---|
| [WorldGuard](https://dev.bukkit.org/projects/worldguard) | **필수** | 지역 코어 |
| Vault + 경제 플러그인 (EssentialsX, CMI…) | 소프트 | 마켓 및 상점 |
| PlaceholderAPI | 소프트 | `%qqregions_*%` 및 메뉴 내 임의의 `%…%` |
| JustTeams | 소프트 | 클랜 레이드 |
| LuckPerms | 소프트 | 선택 권한 템플릿 |
| WorldGuardExtraFlagsPlus | 소프트 | 추가 플래그 / 플레이스홀더 |

소프트 의존성 없이도 플러그인은 동작합니다: 사용할 수 없는 기능은 그저
`lang.yml`의 해당 메시지로 플레이어에게 응답합니다.

### 1.2 설치

1. **WorldGuard**를 설치합니다(필수).
2. `QQRegions.jar`를 `plugins/`에 넣습니다.
3. 서버를 재시작하면 `config.yml`, `lang.yml`, `replace.yml`, `shop.yml`,
   `menus/*.yml`이 생성됩니다(첫 동작 시 `data.yml`도 생성).
4. 플러그인을 설정하고 `/region reload`를 실행합니다.

### 1.3 첫 실행

첫 실행 후 플러그인 폴더에는:

| 파일 | 용도 |
|---|---|
| `config.yml` | 플러그인의 모든 설정 |
| `lang.yml` | 플레이어/관리자/콘솔용 모든 텍스트(플러그인 단일 언어) |
| `replace.yml` | 플래그의 「원시」 값 번역 (yes/no, allow/deny) |
| `shop.yml` | 상점: 플래그 가격, 「+면적」「+지역」 패키지 |
| `data.yml` | 플레이어 구매, 오퍼, 임대 (자동 생성) |
| `menus/*.yml` | 모든 GUI: info, flags, players, playerconfirm, market, marketconfirm, flagshop, blocks, myflags, help 등 |

### 1.4 별칭

명령어 이름과 별칭은 `config.yml`에서 설정합니다:

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

별칭은 서버 재시작 없이 `/region reload`에서 적용됩니다. 아래의 모든
`/region`은 아무 별칭이나 의미합니다.

---

## 2. 빠른 시작

### 단계 1. 선택

```text
/region select
```

플레이어는 핫바 버튼을 받습니다: **「지역 생성」**, **「포인트 1」**,
**「포인트 2」**, **「영역 선택」**, **「선택 초기화」**, **「취소」**.
버튼 슬롯은 `config.yml`(`interactive.buttons.<id>.slot`)에서 설정합니다.
세션은 플레이어의 인벤토리를 저장하고 종료 시 복원합니다.

![인터랙티브 선택](docs/screenshots/02.png)

- **LMB / RMB** 핸드로 버튼 소지 시 — 버튼 동작 실행(공중 및 블록 모두);
- **「포인트 1/2」 버튼** — 시선 방향으로 빠르게 포인트 배치(최대 300블록),
  그 외에는 플레이어 위치에 배치;
- **LMB / RMB** 빈손 — 활성 포인트 전환(1 또는 2);
- **휠 또는 4/6 키** — 포인트 이동(select 모드에서는 선택 슬롯이 항상
  중앙에 유지됨 — `interactive.select-center-slot`, 따라서 키보드 슬롯
  전환은 휠처럼 동작);
- **Shift + 휠** — 이동 속도 ×`wheel-shift-speed`;
- **Shift + LMB** — 선택 확정.

세션 중에는 `interactive.blocked-commands`의 명령어(기본 `ah`, `sell`,
`shop`, `baltop`)가 차단됩니다 — 경매/상점을 통한 버튼 「던져버리기」 방지.

버튼, 패널 및 모든 아이템의 이름과 설명은 **MiniMessage**를 지원합니다:
그라디언트(`<gradient:#55ffff:#ff55ff>`), `<rainbow>`,
`<color:#RRGGBB>`; 텍스트에 그대로 적힌 `#RRGGBB`도 색상 처리됩니다.

### 단계 2. 지역 생성

```text
/region create <이름>
```

선택을 확정한 후 플러그인은 채팅에서 이름 입력을 요구합니다(`cancel` —
취소, 선택은 유지됨). 이름은 검증됩니다:

- `region-name.regex` 기준(기본 `[A-Za-zА-Яа-я0-9_-]{3,32}`);
- `restrictions.banned-regions`의 금지 지역;
- `regions.max-regions` 제한 + 구매한 「+지역」 패키지.

![지역 이름 입력](docs/screenshots/04.png)

### 단계 3. 플래그와 멤버

```text
/region flags <지역>     — 플래그 메뉴(LMB — 켬/끔/기본, RMB — 그룹)
/region add <플레이어> <지역> — 멤버 자격 부여
/region add owner <플레이어> <지역>
```

플래그는 `qqregions.flags.use.<flag>` 권한이 있어야만 플레이어에게
표시됩니다(관리자는 전부 보임; 레거시 권한 `qqregions.flags.<flag>`도
인정됨). 상점에서 구매한 플래그는 권한 없이도 소유자에게 표시됩니다.

### 단계 4. 경계 하이라이트

```text
/region visible <지역> [particles|blocks|territory]
/region visible off        — 모든 하이라이트 숨기기
```

하이라이트는 `territory-visible` 플래그(플래그 메뉴)로 지역에 연결할 수
있습니다: 플레이어가 지역에 들어오면 경계가 자동으로 하이라이트되고
`highlight.region-hide-seconds` 후에 꺼집니다. 지역 타임아웃은 선택과
무관합니다(`interactive.view-hide-after`). `highlight.auto-show-radius`
범위 내의 자신의 지역(소유자/멤버)도 동일하게 하이라이트됩니다 —
**오직** `territory-visible: allow`일 때만.

![지역 경계 하이라이트](docs/screenshots/07.png)

### 단계 5. 마켓과 상점

```text
/region market                — 마켓 메뉴
/region market flags          — 플래그 상점
/region market blocks         — 확장(면적, 「+지역」)
/region sell <금액> [지역]   — 지역 판매 등록(공개 등록)
/region rent <금액> <시간> [지역] — 지역 임대(공개 등록)
/region buy [지역]            — 공개 등록 지역 구매
/region tenant [지역]         — 공개 등록 임대
```

---

## 3. 명령어

전체 목록은 게임 내에서도 볼 수 있습니다: `/region help`(텍스트 출력) 또는
메뉴의 **「도움말」**버튼(모든 템플릿).

### 3.1 선택

| 명령어 | 설명 |
|---|---|
| `/region select` | 인터랙티브 모드(핫바 버튼) |
| `/region select pos <1\|2>` | 플레이어 위치에 포인트 설정 |
| `/region select point <1\|2>` | `pos`의 동의어 |
| `/region select max` | 템플릿이 허용하는 플레이어 주변 최대 영역 |
| `/region select chunk [N]` | N 청크 영역 선택(기본 — 템플릿 한도) |
| `/region select expand [방향] <N>` | N블록 확장(`-N`은 축소) |
| `/region select outset <N> [h\|v]` | 모든 방향으로 확장(h — 가로, v — 세로) |
| `/region select view <플레이어>` | 다른 플레이어의 선택 보기 |

`expand`의 **방향**: `north`, `south`, `east`, `west`, `up`, `down`.

명령 기반 선택의 결과를 볼 수 있습니다: `select` 끝의 상태 바, 그리고
하이라이트 `interactive.command-selection-view`와 자동 숨김
`command-selection-hide-after`.

### 3.2 지역

| 명령어 | 설명 |
|---|---|
| `/region create <이름>` | 현재 선택에서 지역 생성 |
| `/region delete [이름]` | 자신의 지역 삭제(또는 서 있는 지역) |
| `/region info [이름]` | 지역 정보 메뉴 열기 |
| `/region flags [이름]` | 지역 플래그 메뉴 열기 |
| `/region reload` | 모든 설정 다시 로드(+ 별칭, + 언어) |

### 3.3 멤버와 소유자

| 명령어 | 설명 |
|---|---|
| `/region add <플레이어> [지역]` | 플레이어를 멤버로 추가 |
| `/region add member <플레이어> [지역]` | 동일한 명시적 표현 |
| `/region add owner <플레이어> [지역]` | 소유자로 추가 |
| `/region remove <플레이어> [지역]` | 멤버 제거 |
| `/region remove owner <플레이어> [지역]` | 소유자 제거(마지막 소유자는 제거 불가) |

### 3.4 하이라이트

| 명령어 | 설명 |
|---|---|
| `/region visible [이름] [유형]` | 지역 경계 표시 |
| `/region visible off` | 모든 활성 하이라이트 숨기기 |
| `/region visible type [유형]` | 기본 유형(인수 없음 — 현재 표시) |
| `/region visible self on\|off` | 개인 「자기용」 하이라이트: `territory-visible` 플래그에만 영향(명령어와 메뉴는 항상 표시) |
| `/region visible true\|allow\|false\|deny [유형] [지역]` | 지역 하이라이트 플래그 |
| `/region view [이름] [유형]` | 임시로 경계 표시(플래그 변경 없음) |

**유형:** `particles`, `blocks`(발광 디스플레이), `territory`
(지형을 따라가는 윤곽). `particles`/`blocks` 유형 지역에는 내부 그리드
`outline.grid`가 작동합니다 — **6개 면 모두**(위, 아래 및 4개 측면)에
사각형; 플레이어 선택은 **12개 모서리만** 그립니다(이전과 동일하게, 링과
그리드 없음 — `outline.rings`/`grid` 참고, 이들은 지역 하이라이트에서만
작동). `territory` + `blocks` — 지정한 블록으로 만든 울타리만.

### 3.5 마켓 (Vault)

| 명령어 | 설명 |
|---|---|
| `/region market` | 마켓 메뉴: 등록 목록, 검색, 정렬 |
| `/region market flags` | 플래그 상점 |
| `/region market blocks` | 확장: 면적 패키지 및 「+지역」 |
| `/region sell <금액> [지역]` | 지역 판매 등록 — **공개 등록**(누구나 즉시 구매) |
| `/region sell <플레이어> <금액> [지역]` | 특정 플레이어에게 구매 제안(비공개 오퍼) |
| `/region rent <금액> <시간> [지역]` | 지역 임대 — **공개 등록**(누구나 즉시 임대) |
| `/region rent <플레이어> <금액> <시간> [지역]` | 특정 플레이어에게 임대 제안 |
| `/region rent dur <분> [지역]` | 임대 등록의 유효 기간(기본 — `market.list-duration-minutes`) |
| `/region buy [지역]` | 공개 등록 지역 구매 |
| `/region tenant [지역]` | 공개 등록 지역 임대 |
| `/region sell\|rent\|buy\|tenant accept\|decline\|cancel <id>` | 오퍼/등록 수락/거절/취소 |
| `/region market myflags` | 구매한 플래그 메뉴 |

**임대 시간:** `30`(분), `2h`(시간), `7d`(일), `1w`(주), `1m`(개월),
`1y`(년). 기본 — 1주.

**공개 등록**은 판매자/소유자 외 모든 플레이어가 수락할 수 있습니다.
자동 회수가 활성화된 경우(`market.rent.auto-rent`, 마켓 메뉴의 자동 회수
버튼), 임대가 끝나면 등록이 `list-duration-minutes` 동안 시장에 다시
나타납니다.

### 3.6 클랜 레이드 (JustTeams)

| 명령어 | 설명 |
|---|---|
| `/region raid [지역]` | 레이드 시작(info 메뉴의 「레이드」 버튼은 외부인 전용) |

---

## 4. 권한

기본 권한은 모든 이에게 자동 부여됩니다. 서비스 및 `bypass` 권한은
운영자에게만 부여됩니다.

| 권한 | 기본값 | 설명 |
|---|---|---|
| `qqregions.use` | `true` | 명령어 기본 접근 |
| `qqregions.create` | `true` | 지역 생성 |
| `qqregions.delete` | `true` | 지역 삭제 |
| `qqregions.select` | `true` | 선택 |
| `qqregions.info` | `true` | 지역 정보 |
| `qqregions.manage` | `true` | 멤버/소유자 관리 |
| `qqregions.flags` | `true` | 플래그 메뉴 |
| `qqregions.visible` | `true` | 경계 하이라이트 |
| `qqregions.market` | `true` | 마켓 (sell/rent/buy/tenant/market) |
| `qqregions.raid` | `true` | 클랜 레이드 (JustTeams) |
| `qqregions.reload` | `op` | 설정 다시 로드 |
| `qqregions.admin` | `op` | 타인 지역 포함 전체 접근 |
| `qqregions.bypass.selection-limits` | `op` | 템플릿 `max-blocks`/`min-blocks` 무시 |
| `qqregions.bypass.disabled-worlds` | `op` | `disabled-worlds` 월드에서 작업 |
| `qqregions.bypass.banned-regions` | `op` | `banned-regions` 사용 가능 |

`qqregions.admin`에는 다음이 포함됩니다: `reload`, `create`, `delete`,
`select`, `info`, `manage`, `flags`, `visible`, `market`, `raid`.

추가로 플래그 메뉴에는 동적 권한 **`qqregions.flags.use.<flag>`**가
사용됩니다(예: `qqregions.flags.use.pvp`): 플래그 메뉴**와 플래그 상점**에서
해당 플래그를 표시하고 변경/구매를 허용합니다. `qqregions.admin` 권한은
모두 보고/변경하고/구매합니다. 간단한 방법 — `qqregions.flags.use.*`
특권을 부여(예: LuckPerms 플레이어 그룹): 그러면 플레이어가 모든 플래그에
접근할 수 있습니다. 레거시 권한 `qqregions.flags.<flag>`도 인정됩니다(호환용).

**그룹 템플릿(`config.yml` → `flag-groups`)** — 플래그 일괄 부여:
`qqregions.flags.group.<이름>` 권한이 있는 플레이어는 해당 그룹 목록의 모든
플래그를 자동으로 얻습니다(보기/변경/구매). 여러 그룹은 합산되며, 목록의
`["*"]` = 모든 플래그. 개별 `qqregions.flags.use.<flag>`와 함께 사용됩니다.

> 그룹 예시: 「뉴비」 그룹에 `qqregions.flags.group.newbie`(설정에서는
> `pvp`, `build`)를, 「베테랑」 그룹에 `qqregions.flags.group.veteran`
> (넓은 목록)을 부여 — 뉴비는 상점과 플래그 메뉴에서 자신의 세트만,
> 베테랑은 자신의 세트를 보게 됩니다; 공용 권한은
> `qqregions.flags.use.<flag>`로 점점이 추가할 수 있습니다.

> 참고: `bypass.*` 쌍과 `admin`은 LuckPerms에서 특정 그룹에 부여하는 것이
> 좋습니다(템플릿 섹션 참조).

---

## 5. 플레이스홀더

플러그인은 두 종류의 플레이스홀더를 지원합니다:

1. **내부** — `{이름}` — `lang.yml`, `menus/*.yml`, `config.yml` 텍스트 및
   레이드 알림에서 플러그인이 치환합니다.
2. **외부 PAPI** — `%qqregions_*%` — QQRegions 자체 확장 및 공개
   플레이스홀더(예: `%vault_eco_balance%`).

### 5.1 메뉴 공통

`{region}` `{world}` `{player}` `{role}` `{page}` `{pages}`

### 5.2 컨텍스트별

| 컨텍스트 | 플레이스홀더 |
|---|---|
| info 메뉴 | `{owners}` `{members}` `{type}` `{area}` `{volume}` `{priority}` `{status}` `{my-regions}` `{max-regions}` `{max-blocks}` |
| 플래그 메뉴 | `{flag}` `{flag-name}` `{flag-value}` `{flag-value-label}` `{flag-raw}` `{group}` `{group-label}` `{groups-list}` `{flag-group}` `{flag-group-label}` `{flag-with-group}` `{next-state}` |
| 플레이어 메뉴 | `{player}` `{role}` `{role-ru}` `{player-id}` |
| 플레이어 검색 | `{ps-group}` `{ps-balance}` `{ps-balance-symbol}` `{ps-regions}` `{ps-max}` `{ps-clan}` `{ps-sort-list}` |
| 확인 (playerconfirm) | `{pc-player}` `{pc-role}` `{pc-action}` `{pc-balance}` `{pc-balance-symbol}` `{pc-clan}` `{pc-regions}` `{pc-reg-owner}` `{pc-reg-member}` `{pc-max}` |
| 영토 선택기 | `{rp-world}` `{rp-type}` `{rp-people}` `{rp-area}` `{rp-dist}` `{rp-sort-list}` |
| 마켓 메뉴 | `{market-type}` `{market-region}` `{market-world}` `{market-price}` `{market-price-symbol}` `{market-who}` `{market-owner}` `{market-status}` |
| 마켓 홀로그램 (market-holo) | `{owner}`(판매자/소유자 닉) `{price}` `{price-symbol}` `{nick}` `{time}` `{region}` |
| 플래그 상점 | `{flag-name}` `{flag}` `{price}` `{price-symbol}` |
| 확장 상점 | `{pack-name}` `{name}` `{pack-amount}` `{price}` `{price-symbol}` |
| info 메뉴 (레이드 버튼) | `{raid-clan}` `{raid-balance}` `{raid-balance-symbol}` `{raid-online}` `{raid-total}` `{raid-in-region}` `{raid-needed}` |
| 선택 보스바 | `{current}` `{max}` `{percent}` `{player}` `{value-color}` |
| 추가 정보 액션바 | `{height-top}` `{height-bottom}` `{conflict}` `{conflict-regions}` `{conflict-count}` `{current}` `{max}` `{percent}` `{player}` |
| 레이드 (바/알림) | `{region}` `{world}` `{clan}` `{count}` `{total}` `{thief}` `{time}` `{percent}` `{player}` |

> 모든 금액 플레이스홀더(`{price}`, `{market-price}`, `{raid-balance}`,
> `{ps-balance}`, `{pc-balance}`)는 **숫자만** 반환합니다(`market.economy`
> 형식 기준). 통화 기호는 별도로 추가하세요 — 각각 `-symbol` 형제가
> 있습니다: `{price} {price-symbol}` 등. 템플릿에 `-symbol`이 없으면 숫자는
> 기호 없이 표시됩니다.

### 5.3 외부 PAPI 플레이스홀더 `%qqregions_*%`

PlaceholderAPI가 설치되어 있으면 확장이 자동으로 등록됩니다.
`config.yml`의 `placeholders.enabled`로 완전히 끌 수 있고, 목록 구분자
(`owners-separator`, `members-separator`, `owned-separator`,
`membered-separator`)도 `placeholders` 섹션에 있습니다.

**선택**

| 플레이스홀더 | 값 |
|---|---|
| `%qqregions_selection_active%` | `yes`/`no` — 선택 존재 여부 |
| `%qqregions_selection_blocks%` | 선택의 블록 수 |
| `%qqregions_selection_max_blocks%` | 템플릿 한도(bypass 시 ∞) |
| `%qqregions_selection_min_blocks%` | 최소 크기 |
| `%qqregions_selection_chunks%` | 템플릿 기준 청크 한도 |
| `%qqregions_selection_percent%` | 한도의 백분율 (0–100) |
| `%qqregions_selection_over_limit%` | `yes`/`no` |
| `%qqregions_selection_below_min%` | `yes`/`no` |
| `%qqregions_selection_conflict%` | `yes`/`no` — 타인 지역과 겹침 |
| `%qqregions_selection_conflict_count%` | 충돌 지역 수 |
| `%qqregions_selection_conflict_regions%` | 해당 id, 쉼표 구분 |
| `%qqregions_selection_height_top%` | 위쪽 경계까지 블록 수 |
| `%qqregions_selection_height_bottom%` | 아래쪽 경계까지 블록 수 |
| `%qqregions_selection_pos1_x/y/z%` | 첫 번째 지점의 좌표(설정된 그대로) |
| `%qqregions_selection_pos2_x/y/z%` | 두 번째 지점의 좌표 |

**지역 및 경제**

| 플레이스홀더 | 값 |
|---|---|
| `%qqregions_region_current%` | 플레이어가 서 있는 지역 id |
| `%qqregions_region_flags%` | 지역에 설정된 플래그: `flag:값, ...` |
| `%qqregions_region_flag_<flag>%` | 특정 플래그의 값(설정 안 됨이면 비어 있음) |
| `%qqregions_region_owners%` | 현재 지역 소유자의 이름 |
| `%qqregions_region_members%` | 현재 지역 멤버(소유자 제외)의 이름 |
| `%qqregions_region_owner_is%` | `yes`/`no` — 플레이어가 현재 지역 소유자인지 |
| `%qqregions_region_member_is%` | `yes`/`no` — 플레이어가 현재 지역 멤버인지(소유자도 포함) |
| `%qqregions_region_role%` | 현재 지역에서의 역할: `OWNER` / `MEMBER` / `NONE` |
| `%qqregions_region_price_<월드:지역>%` | 활성 오퍼의 가격(없으면 `0`); 숫자만, 통화 기호 없음 |
| `%qqregions_region_for_sale_<월드:지역>%` | `yes`/`no` — 판매 중 |
| `%qqregions_region_for_rent_<월드:지역>%` | `yes`/`no` — 임대 중 |
| `%qqregions_region_owner_<월드:지역>%` | 지역 소유자 |
| `%qqregions_region_rent_time_<월드:지역>%` | 지역 임대 기간 |
| `%qqregions_player_owned_regions%` | 플레이어가 소유자인 지역(관리자 — 전체), 쉼표 구분 |
| `%qqregions_player_owned_count%` | 그 수 |
| `%qqregions_player_membered_regions%` | 플레이어가 멤버(소유자 아님)인 지역, 쉼표 구분 |
| `%qqregions_player_membered_count%` | 그 수 |
| `%qqregions_eco_balance%` | 포맷된 잔액 — **숫자만**(통화 기호 없음) |
| `%qqregions_eco_balance_symbol%` | `market.economy.symbol`의 통화 기호(비활성이면 공백) |
| `%qqregions_eco_balance_raw%` | 「원시」 잔액 |
| `%qqregions_eco_has_<금액>%` | `yes`/`no` — 자금 충분 여부 |
| `%qqregions_market_listings%` | 활성 등록 수 |

**인근 지역**

플레이어 지점에서 지역 경계까지의 수평(2D) 거리; 플레이어가 안에 서 있으면
0. `restrictions.banned-regions`의 지역은 목록과 개수에서 제외됩니다.

| 플레이스홀더 | 값 |
|---|---|
| `%qqregions_nearby_region%` | 가장 가까운 지역의 id(없으면 비어 있음) |
| `%qqregions_nearby_region_distance%` | 가장 가까운 지역까지의 거리(블록 단위) |
| `%qqregions_nearby_region_count%` | 100블록 반경 내 지역 수 |
| `%qqregions_nearby_region_count_<반경>%` | 명시적 반경으로 동일 |

**레이드**

| 플레이스홀더 | 값 |
|---|---|
| `%qqregions_raid_active%` | `yes`/`no` — 레이드 진행 중 |
| `%qqregions_raid_state%` | `idle` / `capturing` / `thief` / `cooldown` |
| `%qqregions_raid_region%` / `%qqregions_raid_world%` | 레이드 지역 및 월드 |
| `%qqregions_raid_clan%` / `%qqregions_raid_thief%` | 클랜 및 「도둑」 |
| `%qqregions_raid_count%` / `%qqregions_raid_players%` / `%qqregions_raid_total%` | 공격자 수 |
| `%qqregions_raid_remaining%` / `%qqregions_raid_time%` | 단계 종료까지 초 |
| `%qqregions_raid_cooldown%` | 지역 쿨다운 초 |

> 임의의 타사 `%…%`를 메뉴 텍스트에 넣을 수 있습니다 — 특정 플레이어로
> 해석됩니다(각 플레이어 잔액 예시는 `menus/players.yml`).
> 소유자 메뉴에서는 소유자 플레이어 버튼이 메뉴 연 자가 아니라 해당
> 플레이어로 해석됩니다.

---

## 6. 메뉴

GUI는 `menus/*.yml`에서 구성됩니다. 각 메뉴는 플레이어 역할
(`role-required`) 및 우선순위별로 여러 **템플릿**을 가질 수 있습니다:

| 역할 | 대상 |
|---|---|
| `owner` | 지역 소유자 |
| `member` | 지역 멤버 |
| `other` | 외부인 |
| (비어 있음) | 누구나 |

템플릿은 다음을 정의합니다: 제목, 크기, fill 패널, 정적 버튼(`lore`, 클릭
명령, 선택적 `permission` 및 `tooltip: false` — 특정 버튼의 호버 툴팁
숨김; `fill`에서는 `tooltip: false` 키가 배경의 툴팁을 숨김), 동적 슬롯 및
페이지네이션. 동적 버튼은 코드에서 조립됩니다(WorldGuard 플래그, 지역
플레이어, 마켓 오퍼, 구매) 그리고 `slots`/`menu-slots`에 배치됩니다.

**버튼 의사 명령어:**

| 의사 명령어 | 동작 |
|---|---|
| `@page:prev` / `@page:next` | 페이지 넘기기 |
| `@menu:<이름>` | 다른 메뉴 열기 (info, flags, players, market, flagshop, blocks, myflags, help) |
| `@back` | 이전 메뉴로 돌아가기 |
| `@teleport` | 지역 중심으로 텔레포트 |
| `@highlight` | 지역 경계 하이라이트 |
| `@flag:<이름>:<allow\|deny\|default>` | 플래그 전환(`default` = 제거, `/rg flag -r`처럼) |
| `@flag-search` / `@market-search` | 플래그 / 마켓 검색 |
| `@sort` | 마켓 정렬 변경 |
| `@add:owner` / `@add:member` | 플레이어 추가(채팅에 닉 입력) |
| `@player-del:<uuid>\|닉>:owner\|member` | 플레이어 제거 |
| `@pf:all\|owners\|members` | 플레이어 목록 필터 |
| `@raid:start` | 레이드 시작 |
| `@region-info-or-pick` | 「내 영토」: 지역 안이면 info, 아니면 영토 선택기 |
| `@region-delete` | 영토 삭제 확인(WG) |
| `@select` | 인터랙티브 선택 활성화(메인 메뉴의 「영토 생성」) |
| `@ps-sort` | 플레이어 검색 정렬 순환 (A-Z/Z-A/잔액/지역±/거리±) |
| `@rpsort` | 영토 선택기 정렬 순환 (가까운/먼/A-Z/Z-A/사람±/면적±) |
| `@rinfo:<월드>:<지역>` | 특정 지역의 info 열기 |
| `@menu:help` | 도움말 열기 |
| `@menu:main` | 메인 메뉴로 돌아가기(info 메뉴의 「메인 메뉴로」 버튼, 슬롯 0) |
| `message!<텍스트>` | 플러그인 접두사 없이 플레이어에게 메시지 |
| `gMessage!<텍스트>` | 서버 전체 플레이어에게 메시지 |
| `title:<fade>:<stay>:<fade>!<텍스트>` | 틱 단위 타이밍의 타이틀(`:…!` 없으면 기본 20/40/20) |
| `title!<텍스트>` | 타이틀 (20/40/20) |
| `actionbar:<틱>!<텍스트>` | N틱 액션바(숫자 없으면 60틱) |
| `sound!<사운드> [볼륨] [피치]` / `gSound!…` | 플레이어에게 / 모두에게 사운드 |
| `asConsole!<명령>` / `asPlayer!<명령>` | 콘솔로 / 플레이어로 실행 |
| `delay:<틱>!<동작>` | 지연 후 동작 실행 |
| `close` | 메뉴 닫기 |

`close`를 제외한 모든 동작 유형은 메뉴 버튼뿐 아니라 상점 상품
(`shop.yml`)의 `commands`/`allow-cmds`/`deny-cmds`와 레이드 알림
(`config.yml` → `raid.notify.*.commands`)에도 사용할 수 있습니다.

### 6.1 지역 정보 메뉴

`/region info`로 열립니다. 통합된 정보 버튼: **지역**(월드, 유형, 상태,
면적, 부피, 우선순위, 역할 + 소유자/멤버에겐 플레이어 한도
`{my-regions}/{max-regions}` 및 `{max-blocks}`; 한도 없음/관리자 권한이면
`∞`) 및 **플레이어**(소유자 + 멤버). 슬롯 0에는 **「메인 메뉴로」** 버튼
(`@menu:main`): 기본 `menus/info.yml`에서는 설치 시 추가되고, 커스터마이즈된
파일에서는 코드가 자동으로 삽입합니다(슬롯 0이 비어 있을 때만). 소유자에겐
버튼 모음이 있습니다: **플래그**, **플레이어**, **텔레포트**(권한
`qqregions.admin`), **하이라이트**, **마켓**, **삭제**(확인 메뉴로 이동),
그리고 **레이드** — 외부인 전용(`role-required: other`, 권한
`qqregions.raid`), 클랜 lore `{raid-clan}` `{raid-balance}`
`{raid-online}/{raid-total}` `{raid-in-region}/{raid-needed}`.
`raid.enabled: false`이면 레이드 버튼은 완전히 숨겨집니다.

![지역 정보 메뉴](docs/screenshots/01.png)

### 6.1a 삭제 확인

소유자의 info 메뉴에서 「영토 삭제」 버튼으로 열립니다. **예, 삭제**
(`@menu:confirmdelete` → `@region-delete`, WG로 지역 삭제) / **취소**
(`@back` — info로 돌아가기).

### 6.1b 영토 선택기

메인 메뉴의 「내 영토」 버튼: 플레이어가 지역 안에 있으면 — 즉시 info;
아니면 — 모든 월드의 모든 지역 선택. 각 지역에는 lore(월드/유형/플레이어/
면적/거리)가 있고 클릭 시 info가 열립니다. `@rpsort` 정렬 순환: 가까운 →
먼 → A-Z → Z-A → 사람 수(오름/내림) → 면적(오름/내림); 현재 모드는
「정렬」 버튼의 lore에서 초록색으로 강조됩니다.

**「영토 생성」** 버튼(메인 메뉴 슬롯 31, `@select`)은 인터랙티브 선택을
활성화합니다 — 인수 없는 `/region select`처럼: 메뉴가 닫히고 핫바에 버튼이
놓입니다: 「포인트 1」/「포인트 2」(클릭 — 포인트 배치, 휠 — 활성 포인트
이동), 「생성」(이름 입력), 「초기화」 및 「취소」.

### 6.2 플래그 메뉴

`/region flags`로 열립니다. 동적 버튼 — WorldGuard 플래그별(`ignore-flags`에
있는 것 제외). 이름은 `config.yml`(`flags-names`)에서, 값은 `replace.yml`에서
가져옵니다. LMB — 값 순환: 켬 → 끔 → **기본**(제거, WorldGuard 기본값 적용,
`/rg flag -r`처럼) → 켬; RMB — **그룹** 전환 (all/members/owners/
nonmembers/nonowners). 자체 값이 없는 플래그를 표시할 때 버튼은 「설정 안 됨」을
보여주고 3번째 클릭이 플래그를 제거합니다.

플래그는 해당 권한이 있어야만 플레이어에게 표시됩니다:
`qqregions.flags.use.<flag>`(LuckPerms 또는 플레이어 개별), 레거시
`qqregions.flags.<flag>`, 또는 `config.yml` → `flag-groups`의 그룹 템플릿
`qqregions.flags.group.<이름>`. 관리자/OP는 모두 봅니다. 상점에서 구매한
플래그는 항상 표시됩니다.

![플래그 메뉴](docs/screenshots/05.png)

### 6.3 플레이어 메뉴

동적 버튼 — 소유자(DIAMOND)와 멤버(GOLD_INGOT). **+ 소유자 / + 멤버**
버튼, 「멤버 삭제 / 소유자 삭제」 필터, **플레이어 추가**(플레이어 검색
메뉴 열기). 제어 — 소유자와 관리자만.

### 6.3a 플레이어 검색 메뉴

플레이어 메뉴의 「플레이어 추가」 버튼. 서버 전체 플레이어: 온라인 우선,
이후 오프라인, 본인 제외; 스킨이 적용된 헤드. 각 헤드에는 선택된 추가 그룹,
잔액(Vault), 지역 수(소유자+멤버)와 플레이어 최대치, 클랜(JustTeams)이
표시됩니다. LMB — 선택 그룹에 추가(확인), RMB — 제거(확인), Shift+LMB —
추가 그룹 변경. 「정렬」 버튼(슬롯 4)으로 정렬: A-Z/Z-A/잔액별/지역 수별
(오름/내림)/거리별(멀리/가까이). 마지막 소유자는 제거할 수 없습니다.
추가/제거 확인 — playerconfirm.yml 메뉴.

![플레이어 메뉴](docs/screenshots/06.png)

### 6.4 마켓 메뉴

활성 등록 목록(검색, 정렬: 이름 → 가격 → 기본). 버튼: **확장**, **플래그
상점**, **내 플래그**, **도움말**. 각 등록:

- **자신의 것** — LMB 「자동 회수」(임대 시) / 취소, RMB — 취소; 자신의
  공개 임대에는 추가로 **등록 기간** 버튼(`rent dur`)이 있습니다;
- **타인의 것** — LMB — 즉시 구매/임대(공개) 또는 수락(비공개 오퍼).

![마켓 메뉴](docs/screenshots/08.png)

### 6.5 플래그 상점

판매되는 플래그(WorldGuard 레지스트리의 전부 — 다른 플러그인의 플래그 포함,
예: WGEFP, `flags-menu.whitelist` 및 `flags-menu.shop-ignore` 제외), 가격은
`shop.yml`. 플레이어는 해당 권한이 있어야**만 보고 구매할 수**
있습니다: `qqregions.flags.use.<flag>`(개별 또는 LuckPerms 그룹) **또는**
`config.yml` → `flag-groups`의 그룹 템플릿 `qqregions.flags.group.<이름>` —
권한이 없으면 상점에 플래그가 없고 구매도 불가합니다; 부여 후에 나타납니다.
레거시 권한 `qqregions.flags.<flag>`도 인정됩니다. 관리자/OP는 모두 보고
구매합니다. 검색 — 이름 또는 번역으로.

![플래그 상점](docs/screenshots/09.png)

### 6.6 확장 상점

버튼은 `shop.yml`에서 구성되고 `priority` 필드로 정렬됩니다(작을수록 앞).
**「+면적」** 패키지(`max-blocks` 증가, 1회) 및 **「+지역」** 패키지(지역
한도 증가, 반복 가능 또는 `max-purchases` 한도), 그리고 사용자 정의 상품
`custom-items` — 구매 후 1회 권한/명령 부여(조건, `allow-cmds`/`deny-cmds`,
§7.2 참조).

각 상품에는 **`max-purchases`**(구매 한도; `<=0` = 무제한)와
**`bought-display`**: `HIDE`(기본) — 구매 후 한도가 다한 상품은 사라지고,
나머지 버튼은 앞으로 밀리며; `RED_GLASS` — 그 자리에 「&c이름」 레드 글라스와
「&7이미 구매함」 lore가 표시됩니다. 플래그의 공통 스위치는 `shop.yml` →
`flags.bought-display`(HIDE | RED_GLASS).

![확장 상점](docs/screenshots/10.png)

### 6.7 내 플래그

플레이어가 구매한 플래그만 표시. 구매 내역 없이 열면 메시지로 응답합니다
(`lang.yml` → `shop.none-owned`).

![내 플래그](docs/screenshots/11.png)

### 6.8 도움말 메뉴

섹션별 플러그인 명령이 있는 정적 버튼(템플릿 `default` 및 `compact`).

![도움말 메뉴](docs/screenshots/13.png)

---

## 7. 설정

### 7.1 config.yml

주요 섹션:

| 섹션 | 구성 내용 |
|---|---|
| `command` | 명령어 이름 및 별칭 |
| `restrictions` | `disabled-worlds`, `banned-regions` |
| `region-name` | 검증 `regex` 및 강제 소문자 |
| `selection-templates` | 선택 권한 템플릿(8.1 섹션 참조) |
| `interactive` | 인터랙티브 select: 휠 속도, 버튼(재질 + 핫바 슬롯), select 센터 슬롯, 차단 명령, `sync-worldedit`, view 모드 및 자동 숨김 |
| `highlight` | 지역 하이라이트: 유형, `region-hide-seconds`(지역 윤곽 자동 소멸 타임아웃, 선택과 무관; 이전 `auto-hide-seconds`/`show-seconds` — 폴백), 쿨다운, `hide-on-exit`/`show-on-exit`, `auto-show-radius`(자신/타인 — `territory-visible` 플래그로만), `territory`(울타리 + `ignore-blocks`), `particles` |
| `outline` | 공통 점선 윤곽: `max-gap`(모든 선의 점 간격, 예: 5), `max-points`, `rings`(높이별 링), `grid`(6개 면 전체 사각형 — 지역 전용; 플레이어 선택: 예전 모양의 12개 모서리) |
| `particles` | 선택 입자 |
| `bossbar` / `select-status` | 선택 상태: 보스바/액션바, 텍스트 `normal/full/conflict`, 추가 정보(`info.text`) |
| `guard` | 렉/핑 방지 |
| `regions` | `max-regions` 한도(0 = 무제한) |
| `flags-menu` | `whitelist`(무료 플래그) 및 `shop-ignore`(판매 안 함) |
| `flags-names` | 사용자 정의 플래그 이름 |
| `flag-groups` | 플래그 권한 그룹 템플릿: `qqregions.flags.group.<이름>` 권한이 전체 플래그 목록을 즉시 열어줌(4 섹션 참조) |
| `menu-update` | 메뉴 자동 업데이트: `ticks`(열린 메뉴의 전역 다시 그리기 간격, 기본 20) 및 `debounce-after-click`(안티 오토클리커, 기본 꺼짐: true면 버튼 클릭이 다시 그리기를 메뉴의 `update_interval`만큼 지연) |
| `placeholders` | PlaceholderAPI 연동: `enabled`(`%qqregions_*%` 확장 켜기/끄기), `owners-separator` / `members-separator` / `owned-separator` / `membered-separator` — 소유자, 멤버, 플레이어 지역 목록의 구분자 |
| `market` | 마켓: `enabled`, `multiowner`(single/split), `commission`(서버 수수료 %), `offer-timeout-minutes`(비공개 오퍼 유효 기간) 및 `offer-timeout-action`(RELIST — 공개 등록 / CANCEL — 내리기), 경제(기호/그룹핑), 임대(`grant`, `charge`, `period-minutes`, `list-duration-minutes`, `auto-rent`), 간판 홀로그램(`market-holo` — 경계 주변에**하나**의 떠다니는 홀로그램, 플레이어를 따라「비행」하며 항상 바라봄) |
| `raid` | 레이드: 단계, 과금, 표시, 알림 |

하이라이트 예시(윤곽 + 영토):

```yaml
highlight:
  type: PARTICLES            # PARTICLES | BLOCKS | TERRITORY
  region-hide-seconds: 60    # 지역 윤곽 자동 소멸 타임아웃
  territory:
    ignore-blocks:           # TERRITORY가 빈 공간으로 보는 블록
      - BROWN_MUSHROOM
      - RED_MUSHROOM
      - TALL_GRASS
      - SHORT_GRASS
    fence:                   # 「울타리」(territory.display: BLOCKS)
      material: OAK_PLANKS
      height: 1.0
      width: 0.3
      thickness: 0.3
      spacing: 1.0
      offset: 0.0
      glow: true

outline:                     # 공통 점선 윤곽(선택 + 지역)
  max-gap: 5                 # 모든 선의 점 간격(5블록 이하)
  max-points: 3000           # 윤곽 점 상한
  rings:
    enabled: true
    step: 8                  # 높이 8블록마다 링
  grid:                      # 위/아래 사각형 — 지역 전용
    enabled: true
    step: 25
```

### 7.2 shop.yml

```yaml
enabled: true
economy-enabled: true

flags:
  default-price: 1000
  # 구매한 플래그를 레드 글라스(RED_GLASS)로 표시할지, 숨길지(HIDE).
  bought-display: HIDE
  prices:
    pvp: 500
    build: 800
    entry: 600

area-packs:
  big:
    name: "대형 영토"
    blocks: 20000            # amount("amount"/"regions"도 읽음)
    price: 5000
    material: GOLD_INGOT
    priority: 0              # 버튼 순서(작을수록 앞)
    max-purchases: 1         # 구매 한도(<=0 = 반복 가능)
    bought-display: HIDE     # RED_GLASS | HIDE

region-packs:
  extra1:
    name: "+1 지역"
    regions: 1
    price: 1000
    priority: 10
    max-purchases: 0         # 0 = 반복 가능

custom-items:                # 사용자 정의 상품(권한/명령 부여)
  vip:
    name: "VIP 특권"
    material: NETHER_STAR
    lore:
      - "&7VIP 특권을 부여합니다"
    price: 5000
    max-purchases: 1
    bought-display: RED_GLASS
    priority: 20
    conditions:              # AND: 모두 참이면 -> allow-cmds, 아니면 deny-cmds
      - "%vault_rank%==Default"
    commands:                # conditions 없으면 commands 실행
      - "asConsole! lp user {player} parent add vip"
      - "message! &aVIP 권한이 활성화되었습니다!"
      - "sound! ENTITY_PLAYER_LEVELUP 1 1"
    allow-cmds:              # 조건 충족 시 동작
      - "message! &a권한이 부여되었습니다"
    deny-cmds:
      - "message! &cVIP는 두 번 구매할 수 없습니다."
```

**상품 공통 필드**(`area-packs` / `region-packs` / `custom-items`):
`name`, `price`(0/음수 = 판매 안 함), `material`, `amount`(블록/지역/lore용
숫자; area는 `blocks`도, region은 `regions`도 읽음), `max-purchases`,
`bought-display`, `priority`, `conditions`, `commands`, `allow-cmds`,
`deny-cmds`, `lore`(custom-items 전용).

**조건**(`conditions`) — `플레이스홀더 연산자 값`: 연산자 `=` `!=` `>` `<`
`>=` `<=`(숫자), 문자열 `<-`(포함), `!<-`, `|-`(시작), `!|-`, `-|`(끝),
`!-|`. 플레이스홀더는 구매자를 기준으로 PlaceholderAPI가 해석합니다.

**동작**(`commands`/`allow-cmds`/`deny-cmds`)은 모든 동작 유형을 지원합니다
(§6의 의사 명령어 표 참조): `message!`, `gMessage!`, `title!`/`title:…!`,
`actionbar!`/`actionbar:N!`, `sound!`, `gSound!`, `asConsole!`, `asPlayer!`,
`delay:N!`. `{player}` → 구매자 닉.

### 7.3 replace.yml

WorldGuard / WGEFP의 원시 플래그 값을 읽기 쉬운 텍스트로 교체:

```yaml
'%worldguard_region_has_flag_pvp%':
  - placeholder: 'yes'
    replacement: '켜짐'
  - placeholder: 'no'
    replacement: '&7꺼짐'
  - placeholder: 'ELSE'
    replacement: ''

flag-groups:
  - placeholder: 'all'
    replacement: '모두'
  - placeholder: 'ELSE'
    replacement: '{value}'

flag-values:
  - placeholder: 'allow'
    replacement: '&a켜짐'
  - placeholder: 'deny'
    replacement: '&c꺼짐'
  - placeholder: 'ELSE'
    replacement: '{value}'
```

`replacement`의 `{value}`는 원래 값을 치환합니다.

### 7.4 lang.yml

플레이어에게 보여줄 모든 텍스트. `&` 색상, `&#RRGGBB`, 텍스트에 그대로
적힌 `#RRGGBB`, `{이름}` 플레이스홀더를 사용할 수 있습니다. 또한
**MiniMessage**를 지원합니다: 그라디언트(`<gradient:#55ffff:#ff55ff>`),
`<rainbow>`, `<color:#RRGGBB>`, `<hover:...>`, `<click:...>` 등 — 임의의
아이템 이름/설명과 메시지에서. mini 마크업이 있는 문자열은 MiniMessage가
파싱합니다(그 안의 레거시 `&` 코드는 자동 변환), 깨진 mini 문자열은
크래시하지 않고 일반 레거시 파싱으로 폴백합니다. 번역 앞의 특수 접두사
**`actionbar:N!`**은 텍스트를 N초 동안 액션바로 보냅니다(업데이트 포함,
플러그인 접두사 없음):

```yaml
prefix: "&8[&bQQRegions&8] "

guard:
  blocked: "actionbar:3!&c서버가 과부하 상태입니다 — 잠시 기다려 주세요."
```

접두사는 플러그인이 출력하는 **모든** 메시지(명령어, 채팅 프롬프트, 메뉴
클릭 결과, 레이드 알림)에 동작합니다: 줄 시작이 `actionbar:N!`이면 텍스트가
액션바로, 아니면 채팅으로 갑니다. 콘솔은 일반 텍스트를 받습니다.

금액 템플릿에서는 「숫자 + 기호」 플레이스홀더 쌍을 사용하세요(`{price}
{price-symbol}`, `{raid-balance} {raid-balance-symbol}` 등). 명령어 구문의
자세한 템플릿은 `usage:` 섹션에 있습니다(예: `/region help`), 간결 시간
키 — `menu.time-short-*`, 선택 상태의 「예/아니오」 값 —
`select-status.yes/no`. 파일 버전은 `config-version: 6`; 이전 플러그인
버전에서 이전할 때 플러그인은 **빈** 값만 채웁니다 — 이전 `lang.yml`에 없는
템플릿은 수동으로 보완해야 합니다(예: 금액 메시지에 `{price-symbol}` 추가,
아니면 이전 버전이 계속 기호 없는 숫자를 표시).

### 7.5 data.yml

자동 생성; 실행 중에는 수정하지 마세요:

```text
players.<UUID>.flags       — 구매한 플래그
players.<UUID>.area-packs  — 면적 패키지
players.<UUID>.region-packs.<id> — 「+지역」 개수
players.<UUID>.custom-items.<id> — 사용자 정의 상품 구매 횟수
offers.*                   — 마켓 및 임대 오퍼
```

### 7.6 menus/*.yml

GUI 레이아웃. 버튼/텍스트/슬롯 수정은 `/region reload`에서 적용됩니다.
메뉴의 바닥글 슬롯(45–53)은 `menu-slots`(10–43)과 겹치지 않으므로 중복
버튼이 불가능합니다.

각 메뉴에는 `update_interval`(틱)이 있습니다 — 열린 메뉴가 다시 그려지는
주기(lore/가격/플래그가 실시간 업데이트); `0` = 해당 메뉴 자동 업데이트
끄기. 전역 기본값은 config.yml의 `menu-update.ticks`. 버튼 클릭 시 메뉴가
즉시 다시 그려집니다; 선택적 안티 오토클리커 `menu-update.debounce-after-click:
true`(기본 꺼짐)는 클릭 후 열린 메뉴의 다시 그리기를 `update_interval`틱만큼
지연합니다 — 이때 탐색과 버튼 동작은 즉시. 자세한 내용 — `menus/MENU_EDITOR.md`
§8.

---

## 8. 고급 설정

### 8.1 선택 권한 템플릿

플레이어에게는 `priority`가 가장 높고 `permission` **또는** `placeholder`가
일치하는 템플릿이 선택됩니다:

```yaml
selection-templates:
  default:
    permission: ""            # 비어 있음 = 확인하지 않음
    placeholder: ""           # "%plasma_skill_power%>=60" 또는 "true"/"false"
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

`placeholder` 메커니즘은 임의의 숫자 PAPI로 한도를 부여할 수 있게 합니다
(`>=`, `>`, `<=`, `<`, `==` 또는 그냥 `true`/`false`).

### 8.2 렉 방지 (guard)

서버가 과부하되거나 플레이어 핑이 높으면 메뉴 버튼 클릭(및 기타 기능)이
취소됩니다 — 렉 중 아이템 「소환」 방지:

```yaml
guard:
  enabled: true
  min-tps: 15.0
  max-ping: 5000
```

### 8.3 레이드 (JustTeams)

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
    source: CLAN            # PLAYER — 도둑 잔액(Vault)에서, CLAN — 금고에서
    percent: 10
  display:
    mode: ACTIONBAR         # BOSSBAR | ACTIONBAR | NONE
    text: "&c점령 {region}: &f{time}&c초 • 공격자 &f{count}&c/&f{total}"
    thief-text: "&2도둑 &f{thief}&2: &f{time}&2초"
  notify:
    start:
      message: "&8[&c클랜&8] &f{clan} &c이(가) 지역 &f{region}&7을(를) 공격합니다!"
      commands: []
```

알림은 `asConsole!...` 및 `asPlayer!...` 명령어를 지원하며
`{플레이스홀더}` 치환이 있습니다.

### 8.4 경제

숫자 형식은 `market.economy`에서 설정합니다:

```yaml
market:
  economy:
    symbol: "₩"
    symbol-position: AFTER     # AFTER = "1000 ₩", BEFORE = "₩ 1000"
    decimal-places: 0
    grouping: true
    group-separator: ","
    decimal-separator: "."
  rent:
    grant: MEMBER              # MEMBER | OWNER
    charge: PERIOD             # ONCE | PERIOD(주기적 차감)
    period-minutes: 1440
  list-duration-minutes: 10080 # 공개 임대 등록 유효 기간(기본 7일)
  auto-rent: true              # 임대 종료 후 자동 회수 + 자동 갱신
  multiowner: single           # single — 전체 금액이 개시자에게; split — 모든 소유자에게 균등
  commission:
    enable: false              # 판매/임대 서버 수수료
    rate: 0.01                 # 금액 비율(0.01 = 1%, 0.05 = 5%). 수령자에게 부과
  offer-timeout-minutes: 60    # 비공개 오퍼 유효 기간(0 = 무제한)
  offer-timeout-action: RELIST # 만료 시 처리: RELIST — 공개 등록; CANCEL — 마켓에서 내리기
  market-holo:
    enabled: true              # 등록 기간 동안 지역 경계 옆**하나**의 간판 홀로그램
    view-distance: 24          # (X/Z) 반경 내 홀로그램이 플레이어를 경계를 따라 안내
    y-offset: 1.5              # **플레이어**의 Y 높이 기준 오프셋(0 — 정확히 플레이어 Y, 1.5 ≈ 얼굴)
    scale: 1.0                 # 텍스트 크기 배율
    line-width: 200            # 행 최대 길이(블록)
```

---

## 9. FAQ 및 문제 해결

**메뉴가 열리지 않음 / 명령어가 「메뉴가 설정되지 않음」이라고 응답함.**
`menus/`의 파일을 확인하고 `/region reload`를 실행하세요. 개별 메뉴는
`dynamic-*` / `purchased-*` 섹션을 제거하면 비활성화할 수 있습니다 —
플러그인은 대신 폴백을 제안합니다.

**마켓이 작동하지 않음.**
`market.enabled: true` + Vault 설치 + 경제 플러그인. Vault가 없으면
명령어가 「경제(Vault)를 사용할 수 없음」이라고 응답합니다.

**플래그 상점이 비어 있음.**
플래그는 `flags-menu.whitelist`에 **없고**(거긴 무료) `flags-menu.shop-ignore`에
없을 때만 판매됩니다. 빈 `whitelist`는 「모든 플래그가 판매됨」을 의미합니다.

**구매한 플래그가 메뉴에 안 보임.**
지역 소유자라면 — 플래그는 권한 없이 나타납니다. 외부인에게 구매한 플래그는
타인 지역에 대한 권한을 주지 않습니다.

**선택 포인트가 보이지 않음.**
`particles.enabled: true`(또는 `interactive.view-mode: BLOCKS`)와 월드가
`restrictions.disabled-worlds`에서 비활성화되지 않았는지 확인하세요.

**플래그로 하이라이트가 안 켜짐.**
`territory-visible` 플래그가 `allow`여야 하며, `highlight.cooldown-seconds`
보다 자주는 동작하지 않습니다. `territory-visible: false`면 플래그는
등록되지만 진입 시 하이라이트되지 않습니다(`/region visible` 명령어는
작동). `auto-show-radius` 범위의 내 지역도 플래그가 `allow`일 때만 빛납니다
— 플래그가 없으면 가까이에서도, 플래그가 있는 인접 지역에 진입해도 켜지지
않습니다. 윤곽은 `highlight.region-hide-seconds` 후 스스로 꺼집니다(선택
타임아웃과 무관).

**레이드가 시작되지 않음.**
JustTeams(모든 공격자에게 클랜), `min-attackers`, `online-percent`,
블랙리스트, 그리고 소유자가 오프라인인지 확인하세요(`owners-offline-required:
true`인 경우).

---