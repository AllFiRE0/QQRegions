# QQRegions — Wiki

> ### Idioma
>
> | [Русский](README.md) | [English](README.en.md) | [**Português**](README.pt.md) | [Deutsch](README.de.md) | [中文](README.zh.md) | [한국어](README.ko.md) |
> |:-:|:-:|:-:|:-:|:-:|:-:|

> Poderoso gerenciamento de regiões sobre o **WorldGuard** para servidores **Paper / Leaf (api 26.2)**.

```
/region ...   /territory ...   /tr ...   /rg ...   /private ...   /zone ...
```

O plugin cobre todo o ciclo de privatização no servidor: seleção
interativa, criação e exclusão de regiões, menu de flags, destaque de
fronteiras, gerenciamento de membros, mercado de venda/aluguel, loja de
flags e extensões, raids de clã — e toda a localização em um único arquivo.

---

## Índice

- [1. Requisitos e instalação](#1-requisitos-e-instalação)
- [2. Início rápido](#2-início-rápido)
- [3. Comandos](#3-comandos)
- [4. Permissões](#4-permissões)
- [5. Placeholders](#5-placeholders)
- [6. Menus](#6-menus)
- [7. Configuração](#7-configuração)
- [8. Configuração avançada](#8-configuração-avançada)
- [9. FAQ e solução de problemas](#9-faq-e-solução-de-problemas)

---

## 1. Requisitos e instalação

### 1.1 Requisitos

| Dependência | Tipo | Para quê |
|---|---|---|
| [WorldGuard](https://dev.bukkit.org/projects/worldguard) | **obrigatória** | núcleo de regiões |
| Vault + plugin de economia (EssentialsX, CMI…) | opcional | mercado e loja |
| PlaceholderAPI | opcional | `%qqregions_*%` e qualquer `%…%` dentro dos menus |
| JustTeams | opcional | raids de clã |
| LuckPerms | opcional | modelos de permissão de seleção |
| WorldGuardExtraFlagsPlus | opcional | flags / placeholders extras |

O plugin funciona sem dependências opcionais: uma mecânica indisponível
apenas responde ao jogador com a mensagem correspondente do `lang.yml`.

### 1.2 Instalação

1. Instale o **WorldGuard** (obrigatório).
2. Coloque o `QQRegions.jar` em `plugins/`.
3. Reinicie o servidor — serão criados `config.yml`, `lang.yml`,
   `replace.yml`, `shop.yml`, `menus/*.yml` (e o `data.yml` no primeiro
   uso).
4. Configure o plugin e execute `/region reload`.

### 1.3 Primeira execução

Após a primeira execução, na pasta do plugin:

| Arquivo | Finalidade |
|---|---|
| `config.yml` | todas as configurações do plugin |
| `lang.yml` | todos os textos para jogador/admin/console (um idioma do plugin) |
| `replace.yml` | tradução de valores "brutos" de flags (yes/no, allow/deny) |
| `shop.yml` | loja: preços de flags, pacotes "área extra", "+região" |
| `data.yml` | compras dos jogadores, ofertas, aluguéis (criado automaticamente) |
| `menus/*.yml` | todas as GUIs: info, flags, players, playerconfirm, market, marketconfirm, flagshop, blocks, myflags, help e outras |

### 1.4 Aliases

O nome do comando e os aliases são definidos no `config.yml`:

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

Os aliases são aplicados no `/region reload` sem reiniciar o servidor.
Em todo lugar abaixo, o `/region` significa qualquer alias.

---

## 2. Início rápido

### Passo 1. Seleção

```text
/region select
```

O jogador recebe botões na hotbar: **"Criar região"**, **"Ponto 1"**,
**"Ponto 2"**, **"Selecionar área"**, **"Redefinir seleção"**, **"Cancelar"**.
Os slots dos botões são configurados no `config.yml` (`interactive.buttons.<id>.slot`).
A sessão salva o inventário do jogador e o restaura ao sair.

![Seleção interativa](docs/screenshots/02.png)

- **Botão esquerdo / direito** com o botão na mão — executar a ação do botão
  (no ar e em blocos);
- **o botão "Ponto 1/2"** — posição rápida do ponto pela linha de mira (até 300
  blocos), caso contrário na posição do jogador;
- **botão esquerdo / direito** com a mão vazia — alternar o ponto ativo (1 ou 2);
- **roda do mouse ou teclas 4/6** — mover o ponto (no modo select o slot
  escolhido fica sempre centralizado — `interactive.select-center-slot`,
  então as trocas de slot pelo teclado funcionam como a roda);
- **Shift + roda** — movimento ×`wheel-shift-speed`;
- **Shift + botão esquerdo** — confirmar a seleção.

Durante a sessão, comandos de `interactive.blocked-commands`
(por padrão `ah`, `sell`, `shop`, `baltop`) são bloqueados — proteção contra
"descartar" os botões via leilão/lojas.

Nomes e descrições de botões, painéis e qualquer item suportam
**MiniMessage**: gradientes (`<gradient:#55ffff:#ff55ff>`), `<rainbow>`,
`<color:#RRGGBB>`; um `#RRGGBB` simples direto no texto também é colorido.

### Passo 2. Criando uma região

```text
/region create <nome>
```

Após confirmar a seleção, o plugin pede para digitar um nome no chat
(`cancel` — cancelar, a seleção é mantida). O nome é validado:

- por `region-name.regex` (por padrão `[A-Za-zА-Яа-я0-9_-]{3,32}`);
- contra regiões proibidas de `restrictions.banned-regions`;
- contra o limite `regions.max-regions` + pacotes "+região" comprados.

![Digite o nome da região](docs/screenshots/04.png)

### Passo 3. Flags e membros

```text
/region flags <região>     — menu de flags (botão esquerdo — on/off/padrão, botão direito — grupo)
/region add <jogador> <região> — conceder participação
/region add owner <jogador> <região>
```

Uma flag é mostrada ao jogador apenas com a permissão `qqregions.flags.use.<flag>`
(admin vê tudo; a permissão legada `qqregions.flags.<flag>` também é aceita).
Uma flag comprada na loja é mostrada ao dono sem permissão alguma.

### Passo 4. Destaque de fronteiras

```text
/region visible <região> [particles|blocks|territory]
/region visible off        — ocultar todos os destaques
```

O destaque pode ser vinculado a uma região pela flag `territory-visible`
(no menu de flags): quando o jogador entra na região, as fronteiras destacam
automaticamente e somem após `highlight.region-hide-seconds`. O timeout da
região é independente das seleções (`interactive.view-hide-after`). Regiões
próprias (dono/membro) dentro de `highlight.auto-show-radius` destacam da
mesma forma — **apenas** com a flag `territory-visible: allow`.

![Destaque de fronteiras da região](docs/screenshots/07.png)

### Passo 5. Mercado e loja

```text
/region market                — menu do mercado
/region market flags          — loja de flags
/region market blocks         — extensões (área, "+região")
/region sell <valor> [região] — colocar região à venda (anúncio público)
/region rent <valor> <tempo> [região] — alugar a região (anúncio público)
/region buy [região]          — comprar região de um anúncio público
/region tenant [região]       — alugar de um anúncio público
```

---

## 3. Comandos

A lista completa também está disponível no jogo: `/region help` (saída de
texto) ou o botão **"Ajuda"** no menu (todos os modelos).

### 3.1 Seleção

| Comando | Descrição |
|---|---|
| `/region select` | Modo interativo (botões na hotbar) |
| `/region select pos <1\|2>` | Definir ponto na posição do jogador |
| `/region select point <1\|2>` | Sinônimo de `pos` |
| `/region select max` | A área máxima permitida pelo modelo ao redor do jogador |
| `/region select chunk [N]` | Selecionar área de N chunks (por padrão — limite do modelo) |
| `/region select expand [lado] <N>` | Expandir por N blocos (ou `-N` — reduzir) |
| `/region select outset <N> [h\|v]` | Expandir em todas as direções (h — horizontal, v — vertical) |
| `/region select view <jogador>` | Visualizar a seleção de outro jogador |

**Lados** do `expand`: `north`, `south`, `east`, `west`, `up`, `down`.

O resultado das seleções por comando pode ser visto: barra de status no final
do `select`, além do destaque `interactive.command-selection-view` e
auto-ocultação `command-selection-hide-after`.

### 3.2 Regiões

| Comando | Descrição |
|---|---|
| `/region create <nome>` | Criar região a partir da seleção atual |
| `/region delete [nome]` | Excluir sua região (ou a região em que você está) |
| `/region info [nome]` | Abrir o menu de informações da região |
| `/region flags [nome]` | Abrir o menu de flags da região |
| `/region reload` | Recarregar todas as configurações (+ aliases, + idioma) |

### 3.3 Membros e donos

| Comando | Descrição |
|---|---|
| `/region add <jogador> [região]` | Adicionar jogador como membro |
| `/region add member <jogador> [região]` | O mesmo, explicitamente |
| `/region add owner <jogador> [região]` | Adicionar aos donos |
| `/region remove <jogador> [região]` | Remover membro |
| `/region remove owner <jogador> [região]` | Remover dono (o último não pode ser removido) |

### 3.4 Destaque

| Comando | Descrição |
|---|---|
| `/region visible [nome] [tipo]` | Mostrar fronteiras da região |
| `/region visible off` | Ocultar todos os destaques ativos |
| `/region visible type [tipo]` | Tipo padrão (sem argumento — mostrar o atual) |
| `/region visible self on\|off` | Destaque pessoal "para si": afeta apenas a flag `territory-visible` (comando e menu sempre mostram) |
| `/region visible true\|allow\|false\|deny [tipo] [região]` | Flag de destaque da região |
| `/region view [nome] [tipo]` | Mostrar fronteiras temporariamente (sem alterar a flag) |

**Tipos:** `particles`, `blocks` (displays com brilho), `territory`
(contorno pelo relevo). Para regiões do tipo `particles`/`blocks` funciona a
malha interna `outline.grid` — quadrados em **todas as 6 faces** (topo,
fundo e quatro laterais); uma seleção de jogador desenha **apenas 12 arestas**
(exatamente como antes, sem anéis e malha — veja `outline.rings`/`grid`,
que funcionam só no destaque de regiões). `territory` + `blocks` —
apenas uma cerca feita dos blocos indicados.

### 3.5 Mercado (Vault)

| Comando | Descrição |
|---|---|
| `/region market` | Menu do mercado: lista de anúncios, busca, ordenação |
| `/region market flags` | Loja de flags |
| `/region market blocks` | Extensões: pacotes de área e "+região" |
| `/region sell <valor> [região]` | Colocar região à venda — **anúncio público** (qualquer um compra na hora) |
| `/region sell <jogador> <valor> [região]` | Oferecer compra a um jogador específico (oferta privada) |
| `/region rent <valor> <tempo> [região]` | Alugar a região — **anúncio público** (qualquer um aluga na hora) |
| `/region rent <jogador> <valor> <tempo> [região]` | Oferecer aluguel a um jogador específico |
| `/region rent dur <minutos> [região]` | Prazo de validade do anúncio de aluguel (por padrão — `market.list-duration-minutes`) |
| `/region buy [região]` | Comprar região de um anúncio público |
| `/region tenant [região]` | Alugar região de um anúncio público |
| `/region sell\|rent\|buy\|tenant accept\|decline\|cancel <id>` | Aceitar/recusar/cancelar oferta ou anúncio |
| `/region market myflags` | Menu de flags compradas |

**Tempo de aluguel:** `30` (minutos), `2h` (horas), `7d` (dias), `1w` (semanas),
`1m` (meses), `1y` (anos). Por padrão — 1 semana.

**Anúncios públicos** são aceitos por qualquer jogador, exceto o vendedor/dono.
Se o auto-retorno estiver ativo (`market.rent.auto-rent`, botão de auto-retorno
no menu do mercado), ao fim do aluguel o anúncio volta a aparecer no mercado
por `list-duration-minutes`.

### 3.6 Raids de clã (JustTeams)

| Comando | Descrição |
|---|---|
| `/region raid [região]` | Iniciar um raid (o botão "Raid" no menu info serve apenas para terceiros) |

---

## 4. Permissões

Permissões básicas são concedidas a todos automaticamente; as de serviço e
`bypass` apenas a operadores.

| Permissão | Padrão | Descrição |
|---|---|---|
| `qqregions.use` | `true` | Acesso base aos comandos |
| `qqregions.create` | `true` | Criar regiões |
| `qqregions.delete` | `true` | Excluir regiões |
| `qqregions.select` | `true` | Seleção |
| `qqregions.info` | `true` | Informação da região |
| `qqregions.manage` | `true` | Gerenciar membros/donos |
| `qqregions.flags` | `true` | Menu de flags |
| `qqregions.visible` | `true` | Destaque de fronteiras |
| `qqregions.market` | `true` | Mercado (sell/rent/buy/tenant/market) |
| `qqregions.raid` | `true` | Raids de clã (JustTeams) |
| `qqregions.reload` | `op` | Recarregar configurações |
| `qqregions.admin` | `op` | Acesso total, incluindo regiões de outros |
| `qqregions.bypass.selection-limits` | `op` | Ignorar `max-blocks`/`min-blocks` dos modelos |
| `qqregions.bypass.disabled-worlds` | `op` | Trabalhar em mundos de `disabled-worlds` |
| `qqregions.bypass.banned-regions` | `op` | Trabalhar com `banned-regions` |

`qqregions.admin` inclui: `reload`, `create`, `delete`, `select`,
`info`, `manage`, `flags`, `visible`, `market`, `raid`.

Adicionalmente, para o menu de flags é usada a permissão dinâmica
**`qqregions.flags.use.<flag>`** (ex.: `qqregions.flags.use.pvp`):
mostra a flag no menu de flags **e na loja de flags** e permite alterá-la/
comprá-la. A permissão `qqregions.admin` vê/altera/compra tudo.
Uma opção simples — conceder o privilégio `qqregions.flags.use.*`
(ex.: no grupo de jogadores do LuckPerms): então o jogador terá acesso a
todas as flags. A permissão legada `qqregions.flags.<flag>` também é aceita
(para compatibilidade).

**Modelos de grupo (`config.yml` → `flag-groups`)** — concessão de flags "em
lote": um jogador com a permissão `qqregions.flags.group.<nome>` recebe
automaticamente todas as flags da lista do grupo (ver/alterar/comprar).
Vários grupos são somados; `["*"]` na lista = todas as flags. Funciona junto
com as `qqregions.flags.use.<flag>` individuais.

> Exemplo com grupos: dê ao grupo "novatos" `qqregions.flags.group.newbie`
> (no config — `pvp`, `build`) e ao grupo "veteranos" `qqregions.flags.group.veteran`
> (uma lista ampla) — novatos verão na loja e no menu de flags apenas o seu
> conjunto, veteranos o seu; uma permissão comum pode ser adicionada
> pontualmente via `qqregions.flags.use.<flag>`.

> Nota: é útil conceder os pares `bypass.*` e `admin` a grupos específicos
> no LuckPerms (veja a seção de modelos).

---

## 5. Placeholders

O plugin suporta dois tipos de placeholders:

1. **Internos** — `{nome}` — substituídos pelo plugin no `lang.yml`,
   `menus/*.yml`, textos do `config.yml` e notificações de raid.
2. **Externos PAPI** — `%qqregions_*%` — da extensão própria do QQRegions,
   além de quaisquer públicos (ex.: `%vault_eco_balance%`).

### 5.1 Comuns para menus

`{region}` `{world}` `{player}` `{role}` `{page}` `{pages}`

### 5.2 Por contexto

| Contexto | Placeholders |
|---|---|
| Menu info | `{owners}` `{members}` `{type}` `{area}` `{volume}` `{priority}` `{status}` `{my-regions}` `{max-regions}` `{max-blocks}` |
| Menu de flags | `{flag}` `{flag-name}` `{flag-value}` `{flag-value-label}` `{flag-raw}` `{group}` `{group-label}` `{groups-list}` `{flag-group}` `{flag-group-label}` `{flag-with-group}` `{next-state}` |
| Menu de jogadores | `{player}` `{role}` `{role-ru}` `{player-id}` |
| Busca de jogadores | `{ps-group}` `{ps-balance}` `{ps-balance-symbol}` `{ps-regions}` `{ps-max}` `{ps-clan}` `{ps-sort-list}` |
| Confirmação (playerconfirm) | `{pc-player}` `{pc-role}` `{pc-action}` `{pc-balance}` `{pc-balance-symbol}` `{pc-clan}` `{pc-regions}` `{pc-reg-owner}` `{pc-reg-member}` `{pc-max}` |
| Seletor de território | `{rp-world}` `{rp-type}` `{rp-people}` `{rp-area}` `{rp-dist}` `{rp-sort-list}` |
| Menu do mercado | `{market-type}` `{market-region}` `{market-world}` `{market-price}` `{market-price-symbol}` `{market-who}` `{market-owner}` `{market-status}` |
| Holograma do mercado (market-holo) | `{owner}` (nick do vendedor/dono) `{price}` `{price-symbol}` `{nick}` `{time}` `{region}` |
| Loja de flags | `{flag-name}` `{flag}` `{price}` `{price-symbol}` |
| Loja de extensões | `{pack-name}` `{name}` `{pack-amount}` `{price}` `{price-symbol}` |
| Menu info (botão de raid) | `{raid-clan}` `{raid-balance}` `{raid-balance-symbol}` `{raid-online}` `{raid-total}` `{raid-in-region}` `{raid-needed}` |
| Bossbar de seleção | `{current}` `{max}` `{percent}` `{player}` `{value-color}` |
| Actionbar de info extra | `{height-top}` `{height-bottom}` `{conflict}` `{conflict-regions}` `{conflict-count}` `{current}` `{max}` `{percent}` `{player}` |
| Raid (barras/notificações) | `{region}` `{world}` `{clan}` `{count}` `{total}` `{thief}` `{time}` `{percent}` `{player}` |

> Todos os placeholders de dinheiro (`{price}`, `{market-price}`, `{raid-balance}`,
> `{ps-balance}`, `{pc-balance}`) retornam **apenas o número** (pelo formato
> `market.economy`). Adicione o símbolo da moeda separadamente — cada um tem um
> irmão `-symbol`: `{price} {price-symbol}` e assim por diante. Se `-symbol`
> não estiver no padrão, o número é exibido sem símbolo.

### 5.3 Placeholders PAPI externos `%qqregions_*%`

A extensão é registrada automaticamente se o PlaceholderAPI estiver instalado.
Dá para desativá-la completamente via `placeholders.enabled` em `config.yml`;
os separadores de listas (`owners-separator`, `members-separator`,
`owned-separator`, `membered-separator`) ficam lá também, na seção
`placeholders`.

**Seleção**

| Placeholder | Valor |
|---|---|
| `%qqregions_selection_active%` | `yes`/`no` — existe seleção |
| `%qqregions_selection_blocks%` | blocos na seleção |
| `%qqregions_selection_max_blocks%` | limite do modelo (ou ∞ com bypass) |
| `%qqregions_selection_min_blocks%` | tamanho mínimo |
| `%qqregions_selection_chunks%` | limite de chunks pelo modelo |
| `%qqregions_selection_percent%` | porcentagem do limite (0–100) |
| `%qqregions_selection_over_limit%` | `yes`/`no` |
| `%qqregions_selection_below_min%` | `yes`/`no` |
| `%qqregions_selection_conflict%` | `yes`/`no` — cruza com outras regiões |
| `%qqregions_selection_conflict_count%` | número de regiões em conflito |
| `%qqregions_selection_conflict_regions%` | os ids, separados por vírgula |
| `%qqregions_selection_height_top%` | blocos até a fronteira superior |
| `%qqregions_selection_height_bottom%` | blocos até a fronteira inferior |
| `%qqregions_selection_pos1_x/y/z%` | coordenadas do primeiro ponto (como definido) |
| `%qqregions_selection_pos2_x/y/z%` | coordenadas do segundo ponto |

**Regiões e economia**

| Placeholder | Valor |
|---|---|
| `%qqregions_region_current%` | id da região em que o jogador está |
| `%qqregions_region_flags%` | flags definidas na região: `flag:valor, ...` |
| `%qqregions_region_flag_<flag>%` | valor de uma flag específica (vazio se não definida) |
| `%qqregions_region_owners%` | nomes dos donos da região atual |
| `%qqregions_region_members%` | nomes dos membros (sem donos) da região atual |
| `%qqregions_region_owner_is%` | `yes`/`no` — o jogador é dono da região atual |
| `%qqregions_region_member_is%` | `yes`/`no` — o jogador é membro da região atual (dono também conta) |
| `%qqregions_region_role%` | papel na região atual: `OWNER` / `MEMBER` / `NONE` |
| `%qqregions_region_price_<mundo:região>%` | preço da oferta ativa (`0` se não houver); apenas número, sem símbolo de moeda |
| `%qqregions_region_for_sale_<mundo:região>%` | `yes`/`no` — está à venda |
| `%qqregions_region_for_rent_<mundo:região>%` | `yes`/`no` — está para alugar |
| `%qqregions_region_owner_<mundo:região>%` | dono da região |
| `%qqregions_region_rent_time_<mundo:região>%` | prazo de aluguel da região |
| `%qqregions_player_owned_regions%` | regiões do jogador onde ele é dono (admin — todas), separadas por vírgula |
| `%qqregions_player_owned_count%` | a quantidade |
| `%qqregions_player_membered_regions%` | regiões onde o jogador é membro (não dono), separadas por vírgula |
| `%qqregions_player_membered_count%` | a quantidade |
| `%qqregions_eco_balance%` | saldo formatado — **apenas número** (sem símbolo de moeda) |
| `%qqregions_eco_balance_symbol%` | símbolo da moeda de `market.economy.symbol` (vazio se desativado) |
| `%qqregions_eco_balance_raw%` | saldo "bruto" |
| `%qqregions_eco_has_<valor>%` | `yes`/`no` — os fundos são suficientes |
| `%qqregions_market_listings%` | número de anúncios ativos |

**Regiões próximas**

Distância horizontal (2D) do ponto do jogador até a fronteira da região;
0 se o jogador está dentro. Regiões de `restrictions.banned-regions` não são
consideradas nas listas e contagens.

| Placeholder | Valor |
|---|---|
| `%qqregions_nearby_region%` | id da região mais próxima (vazio se não houver) |
| `%qqregions_nearby_region_distance%` | distância até a região mais próxima em blocos |
| `%qqregions_nearby_region_count%` | número de regiões em um raio de 100 blocos |
| `%qqregions_nearby_region_count_<raio>%` | o mesmo com um raio explícito |

**Raids**

| Placeholder | Valor |
|---|---|
| `%qqregions_raid_active%` | `yes`/`no` — há um raid em andamento |
| `%qqregions_raid_state%` | `idle` / `capturing` / `thief` / `cooldown` |
| `%qqregions_raid_region%` / `%qqregions_raid_world%` | região e mundo do raid |
| `%qqregions_raid_clan%` / `%qqregions_raid_thief%` | clã e "ladrão" |
| `%qqregions_raid_count%` / `%qqregions_raid_players%` / `%qqregions_raid_total%` | número de atacantes |
| `%qqregions_raid_remaining%` / `%qqregions_raid_time%` | segundos até o fim da fase |
| `%qqregions_raid_cooldown%` | segundos de cooldown da região |

> Qualquer `%…%` de terceiros pode ser escrito nos textos dos menus — eles
> resolvem para um jogador específico (exemplo com o saldo de cada jogador —
> no `menus/players.yml`). No menu do dono, os botões de jogadores-donos
> resolvem para aquele jogador específico, e não para quem abriu o menu.

---

## 6. Menus

As GUIs são montadas a partir de `menus/*.yml`. Para cada menu podem ser
definidos vários **modelos** por papel do jogador (`role-required`) e
prioridade:

| Papel | Para quem serve |
|---|---|
| `owner` | dono da região |
| `member` | membro da região |
| `other` | terceiro |
| (vazio) | qualquer um |

Um modelo define: título, tamanho, painéis de fill, botões estáticos
(com `lore`, comando de clique, `permission` opcional e `tooltip: false`
— ocultar o tooltip de um botão específico; no `fill`, a chave
`tooltip: false` oculta o tooltip do fundo), slots dinâmicos
e paginação. Botões dinâmicos são montados em código (flags do WorldGuard,
jogadores da região, ofertas do mercado, compras) e distribuídos pelos
`slots`/`menu-slots`.

**Pseudocomandos de botão:**

| Pseudocomando | Ação |
|---|---|
| `@page:prev` / `@page:next` | virar páginas |
| `@menu:<nome>` | abrir outro menu (info, flags, players, market, flagshop, blocks, myflags, help) |
| `@back` | voltar ao menu anterior |
| `@teleport` | teleportar para o centro da região |
| `@highlight` | destacar as fronteiras da região |
| `@flag:<nome>:<allow\|deny\|default>` | alternar flag (`default` = remover, como `/rg flag -r`) |
| `@flag-search` / `@market-search` | busca de flags / do mercado |
| `@sort` | trocar a ordenação do mercado |
| `@add:owner` / `@add:member` | adicionar jogador (digite o nick no chat) |
| `@player-del:<uuid>\|nick>:owner\|member` | remover jogador |
| `@pf:all\|owners\|members` | filtro da lista de jogadores |
| `@raid:start` | iniciar um raid |
| `@region-info-or-pick` | "Meu território": em uma região — info, caso contrário — seletor de território |
| `@region-delete` | exclusão confirmada de território (WG) |
| `@select` | ativar a seleção interativa ("Criar território" do menu principal) |
| `@ps-sort` | ciclo de ordenação da busca de jogadores (A-Z/Z-A/saldo/regiões±/distância±) |
| `@rpsort` | ciclo de ordenação do seletor de território (próximas/distantes/A-Z/Z-A/pessoas±/área±) |
| `@rinfo:<mundo>:<região>` | abrir info de uma região específica |
| `@menu:help` | abrir ajuda |
| `@menu:main` | voltar ao menu principal (botão "Voltar ao menu principal" no menu info, slot 0) |
| `message!<texto>` | mensagem ao jogador sem o prefixo do plugin |
| `gMessage!<texto>` | mensagem a todos os jogadores do servidor |
| `title:<fade>:<stay>:<fade>!<texto>` | título com timing em ticks (sem `:…!` — padrão 20/40/20) |
| `title!<texto>` | título (20/40/20) |
| `actionbar:<ticks>!<texto>` | actionbar por N ticks (sem número — 60 ticks) |
| `sound!<som> [volume] [pitch]` / `gSound!…` | som para o jogador / para todos |
| `asConsole!<comando>` / `asPlayer!<comando>` | executar como console / como jogador |
| `delay:<ticks>!<ação>` | executar ação com atraso |
| `close` | fechar o menu |

Todos os tipos de ação (exceto `close`) estão disponíveis não só para botões
de menu, mas também para `commands`/`allow-cmds`/`deny-cmds` de itens da loja
(`shop.yml`) e notificações de raid (`config.yml` → `raid.notify.*.commands`).

### 6.1 Menu de informações da região

Aberto por `/region info`. Botões de info fundidos: **Região** (mundo, tipo,
status, área, volume, prioridade, papel + para dono/membro os limites do
jogador `{my-regions}/{max-regions}` e `{max-blocks}`; `∞` quando não há
limite/permissão de admin) e **Jogadores** (donos + membros). No slot 0 —
o botão **"Voltar ao menu principal"** (`@menu:main`): no `menus/info.yml`
padrão ele é adicionado na instalação, e em arquivos personalizados é inserido
automaticamente pelo código (apenas se o slot 0 estiver livre). Para o dono há
uma fileira de botões: **Flags**, **Jogadores**, **Teleporte**
(permissão `qqregions.admin`), **Destaque**, **Mercado**, **Excluir**
(vai ao menu de confirmação), e **Raid** — apenas para terceiros
(`role-required: other`, permissão `qqregions.raid`), com lore do clã
`{raid-clan}` `{raid-balance}` `{raid-online}/{raid-total}`
`{raid-in-region}/{raid-needed}`. Com `raid.enabled: false`, o botão de raid
fica totalmente oculto.

![Menu de informações da região](docs/screenshots/01.png)

### 6.1a Confirmação de exclusão

Aberto pelo botão "Excluir território" do menu info do dono.
**Sim, excluir** (`@menu:confirmdelete` → `@region-delete`, exclui a região
via WG) / **Cancelar** (`@back` — voltar ao info).

### 6.1b Seletor de território

O botão "Meu território" no menu principal: se o jogador está dentro de uma
região — info imediato; caso contrário — seleção de todas as regiões de todos
os mundos. Cada região tem lore (mundo/tipo/jogadores/área/distância),
clique — info. A ordenação `@rpsort` alterna: próximas → distantes → A-Z →
Z-A → por pessoas (cres./decres.) → por área (cres./decres.); o modo atual
fica destacado em verde no lore do botão "Ordenar".

O botão **"Criar território"** (slot 31 do menu principal, `@select`)
ativa a seleção interativa — como `/region select` sem argumentos:
o menu fecha e botões são colocados na hotbar: "Ponto 1" / "Ponto 2"
(clique — coloca o ponto, roda do mouse — move o ativo), "Criar"
(digitar o nome), "Redefinir" e "Cancelar".

### 6.2 Menu de flags

Aberto por `/region flags`. Botões dinâmicos — pelas flags do WorldGuard
(exceto as de `ignore-flags`). Nomes vêm do `config.yml`
(`flags-names`), valores — do `replace.yml`. Botão esquerdo — ciclo de
valores: ligado → desligado → **padrão** (remover, vale o padrão do WorldGuard,
como `/rg flag -r`) → ligado; botão direito — trocar **grupo**
(all/members/owners/nonmembers/nonowners). Ao mostrar uma flag sem valor
próprio, o botão exibe "não definido" e o 3º clique remove a flag.

Uma flag é mostrada ao jogador apenas com permissão sobre ela:
`qqregions.flags.use.<flag>` (via LuckPerms ou para o jogador individualmente),
legada `qqregions.flags.<flag>` ou modelo de grupo
`qqregions.flags.group.<nome>` do `config.yml` →
`flag-groups`. Admin/op veem tudo. Flags compradas na loja ficam sempre
visíveis.

![Menu de flags](docs/screenshots/05.png)

### 6.3 Menu de jogadores

Botões dinâmicos — donos (DIAMOND) e membros (GOLD_INGOT).
Botões **+ Dono / + Membro**, filtros "Remover membros /
remover donos", **Adicionar jogadores** (abre o menu de busca de jogadores).
Controle — apenas donos e admins.

### 6.3a Menu de busca de jogadores

O botão "Adicionar jogadores" no menu de jogadores. Todos os jogadores do
servidor: online primeiro, depois offline, sem você; cabeças com as skins.
Em cada cabeça são exibidos o grupo de adição selecionado, o saldo (Vault),
o número de regiões (dono+membro) e o máximo do jogador, clã (JustTeams).
Botão esquerdo — adicionar ao grupo selecionado (confirmação), botão direito
— remover (confirmação), Shift+botão esquerdo — trocar o grupo de adição.
Ordenação pelo botão "Ordenar" (slot 4): A-Z/Z-A/por saldo/por número de
regiões (cres./decres.)/por distância (mais longe/mais perto). O último dono
não pode ser removido. Confirmação de adição/remoção — menu playerconfirm.yml.

![Menu de jogadores](docs/screenshots/06.png)

### 6.4 Menu do mercado

Lista de anúncios ativos (busca, ordenação: nome → preço →
padrão). Botões: **Extensão**, **Loja de flags**,
**Minhas flags**, **Ajuda**. Cada anúncio:

- **próprio** — botão esquerdo "auto-retorno" (para aluguel) / cancelar,
  botão direito — cancelar; nos próprios aluguéis públicos há ainda o botão
  **prazo do anúncio** (`rent dur`);
- **de terceiros** — botão esquerdo — comprar/alugar na hora (público) ou
  aceitar (oferta privada).

![Menu do mercado](docs/screenshots/08.png)

### 6.5 Loja de flags

Flags vendidas (todas do registro do WorldGuard — incluindo flags de qualquer
outro plugin, ex. WGEFP, exceto `flags-menu.whitelist` e
`flags-menu.shop-ignore`) com preços do `shop.yml`. Um jogador **vê e pode
comprar** uma flag apenas com permissão sobre ela: `qqregions.flags.use.<flag>`
(individualmente ou via grupo do LuckPerms) **ou** o modelo de grupo
`qqregions.flags.group.<nome>` do `config.yml` → `flag-groups` — sem
permissão, a flag não existe na loja e não pode ser comprada; após a
concessão, ela aparece. A permissão legada `qqregions.flags.<flag>` também é
aceita. Admin/op veem e compram tudo. Busca — por nome ou tradução.

![Loja de flags](docs/screenshots/09.png)

### 6.6 Loja de extensões

Os botões são montados a partir do `shop.yml` e ordenados pelo campo
`priority` (menor — primeiro). Pacotes **"Área extra"** (aumentam o
`max-blocks`, uma vez) e **"+região"** (aumentam o limite de regiões,
repetíveis ou com limite `max-purchases`), além de itens personalizados
`custom-items` — concessão única de permissões/comandos após a compra
(condições, `allow-cmds`/`deny-cmds`, veja §7.2).

Cada item tem **`max-purchases`** (limite de compras; `<=0` = ilimitado)
e **`bought-display`**: `HIDE` (padrão) — item comprado e com limite
esgotado desaparece, os botões restantes se movem para frente; `RED_GLASS` —
no lugar dele, um vidro vermelho "&cnome" com o lore "&7Já comprado".
O interruptor comum para flags é `shop.yml` → `flags.bought-display`
(HIDE | RED_GLASS).

![Loja de extensões](docs/screenshots/10.png)

### 6.7 Minhas flags

Apenas as flags compradas pelo jogador. Abrir sem compras responde
com uma mensagem (`lang.yml` → `shop.none-owned`).

![Minhas flags](docs/screenshots/11.png)

### 6.8 Menu de ajuda

Botões estáticos com comandos do plugin por seção (modelos `default` e
`compact`).

![Menu de ajuda](docs/screenshots/13.png)

---

## 7. Configuração

### 7.1 config.yml

Seções principais:

| Seção | O que configura |
|---|---|
| `command` | nome e aliases do comando |
| `restrictions` | `disabled-worlds`, `banned-regions` |
| `region-name` | `regex` de validação e minúsculas forçadas |
| `selection-templates` | modelos de permissão de seleção (veja a seção 8.1) |
| `interactive` | select interativo: velocidade da roda, botões (material + slot na hotbar), slot central do select, comandos bloqueados, `sync-worldedit`, modo view e auto-ocultação |
| `highlight` | destaque de regiões: tipo, `region-hide-seconds` (timeout de auto-ocultação do contorno da região, independente das seleções; `auto-hide-seconds`/`show-seconds` antigos — fallback), cooldown, `hide-on-exit`/`show-on-exit`, `auto-show-radius` (próprias/estranhas — apenas pela flag `territory-visible`), `territory` (cerca + `ignore-blocks`), `particles` |
| `outline` | contorno pontilhado comum: `max-gap` (passo de pontos de qualquer linha, ex. 5), `max-points`, `rings` (anéis por altura), `grid` (quadrados nas 6 faces — só para regiões; seleção de jogador: 12 arestas no visual antigo) |
| `particles` | partículas de seleção |
| `bossbar` / `select-status` | status da seleção: bossbar/actionbar, textos `normal/full/conflict`, info extra (`info.text`) |
| `guard` | proteção contra lag/ping |
| `regions` | limite `max-regions` (0 = sem limite) |
| `flags-menu` | `whitelist` (flags gratuitas) e `shop-ignore` (não vendidas) |
| `flags-names` | nomes personalizados de flags |
| `flag-groups` | modelos de grupo de permissão de flags: a permissão `qqregions.flags.group.<nome>` abre toda a lista de flags de uma vez (veja a seção 4) |
| `menu-update` | auto-atualização de menus: `ticks` (intervalo global de redraw dos menus abertos, padrão 20) e `debounce-after-click` (anti-autoclicker, DESLIGADO POR PADRÃO: quando true, um clique no botão adia o redraw por `update_interval` do menu) |
| `placeholders` | integração com PlaceholderAPI: `enabled` (liga/desliga a extensão `%qqregions_*%`), `owners-separator` / `members-separator` / `owned-separator` / `membered-separator` — separadores das listas de donos, membros e regiões do jogador |
| `market` | mercado: `enabled`, `multiowner` (single/split), `commission` (% para o servidor), `offer-timeout-minutes` (validade da oferta privada) e `offer-timeout-action` (RELIST — publicar / CANCEL — remover), economia (símbolo/agrupamento), aluguel (`grant`, `charge`, `period-minutes`, `list-duration-minutes`, `auto-rent`), holograma de placa (`market-holo` — UM holograma flutuante perto do perímetro, "voa ao redor" da região seguindo o jogador e sempre o encara) |
| `raid` | raids: fases, charge, display, notify |

Exemplo de destaque (contorno + território):

```yaml
highlight:
  type: PARTICLES            # PARTICLES | BLOCKS | TERRITORY
  region-hide-seconds: 60    # timeout de auto-ocultação do contorno da REGIÃO
  territory:
    ignore-blocks:           # blocos que TERRITORY considera vazio
      - BROWN_MUSHROOM
      - RED_MUSHROOM
      - TALL_GRASS
      - SHORT_GRASS
    fence:                   # "cerca" (territory.display: BLOCKS)
      material: OAK_PLANKS
      height: 1.0
      width: 0.3
      thickness: 0.3
      spacing: 1.0
      offset: 0.0
      glow: true

outline:                     # contorno pontilhado comum (seleção + regiões)
  max-gap: 5                 # passo de pontos de qualquer linha (no máx. 5 blocos)
  max-points: 3000           # teto de pontos do contorno
  rings:
    enabled: true
    step: 8                  # um anel a cada 8 blocos de altura
  grid:                      # quadrados no topo/fundo — SÓ para regiões
    enabled: true
    step: 25
```

### 7.2 shop.yml

```yaml
enabled: true
economy-enabled: true

flags:
  default-price: 1000
  # Mostrar flags compradas como vidro vermelho (RED_GLASS) ou ocultá-las (HIDE).
  bought-display: HIDE
  prices:
    pvp: 500
    build: 800
    entry: 600

area-packs:
  big:
    name: "Território grande"
    blocks: 20000            # amount (também lê "amount"/"regions")
    price: 5000
    material: GOLD_INGOT
    priority: 0              # ordem dos botões (menor — primeiro)
    max-purchases: 1         # limite de compras (<=0 = repetível)
    bought-display: HIDE     # RED_GLASS | HIDE

region-packs:
  extra1:
    name: "+1 região"
    regions: 1
    price: 1000
    priority: 10
    max-purchases: 0         # 0 = repetível

custom-items:                # itens personalizados (concessão de permissões/comandos)
  vip:
    name: "Privilégio VIP"
    material: NETHER_STAR
    lore:
      - "&7Concede o privilégio VIP"
    price: 5000
    max-purchases: 1
    bought-display: RED_GLASS
    priority: 20
    conditions:              # AND: com todas verdadeiras -> allow-cmds, senão deny-cmds
      - "%vault_rank%==Default"
    commands:                # sem conditions: executa commands
      - "asConsole! lp user {player} parent add vip"
      - "message! &aPrivilégios VIP ativados!"
      - "sound! ENTITY_PLAYER_LEVELUP 1 1"
    allow-cmds:              # ações quando as condições são atendidas
      - "message! &aPrivilégios concedidos"
    deny-cmds:
      - "message! &cNão é possível comprar VIP duas vezes."
```

**Campos comuns dos itens** (`area-packs` / `region-packs` / `custom-items`):
`name`, `price` (0/negativo = não vendido), `material`, `amount`
(blocos/regiões/número para o lore; em area também lê `blocks`, em region — `regions`),
`max-purchases`, `bought-display`, `priority`, `conditions`,
`commands`, `allow-cmds`, `deny-cmds`, `lore` (apenas em custom-items).

**Condições** (`conditions`) — `placeholder operador valor`: operadores
`=` `!=` `>` `<` `>=` `<=` (números), string `<-` (contém), `!<-`,
`|-` (começa com), `!|-`, `-|` (termina com), `!-|`. Os placeholders
são resolvidos pelo PlaceholderAPI para o comprador.

**Ações** (`commands`/`allow-cmds`/`deny-cmds`) suportam todos os tipos de
ação (veja a tabela de pseudocomandos no §6): `message!`, `gMessage!`,
`title!`/`title:…!`, `actionbar!`/`actionbar:N!`, `sound!`, `gSound!`,
`asConsole!`, `asPlayer!`, `delay:N!`. `{player}` → nick do comprador.

### 7.3 replace.yml

Substituição de valores brutos de flags do WorldGuard / WGEFP por texto
legível:

```yaml
'%worldguard_region_has_flag_pvp%':
  - placeholder: 'yes'
    replacement: 'ativada'
  - placeholder: 'no'
    replacement: '&7desativada'
  - placeholder: 'ELSE'
    replacement: ''

flag-groups:
  - placeholder: 'all'
    replacement: 'todos'
  - placeholder: 'ELSE'
    replacement: '{value}'

flag-values:
  - placeholder: 'allow'
    replacement: '&aativado'
  - placeholder: 'deny'
    replacement: '&cdesativado'
  - placeholder: 'ELSE'
    replacement: '{value}'
```

`{value}` no `replacement` substitui o valor original.

### 7.4 lang.yml

Todos os textos para o jogador. Cores `&`, `&#RRGGBB`, um `#RRGGBB` simples
direto no texto e placeholders `{nome}` estão disponíveis. Além disso, é
suportado **MiniMessage**: gradientes (`<gradient:#55ffff:#ff55ff>`),
`<rainbow>`, `<color:#RRGGBB>`, `<hover:...>`, `<click:...>` etc. — em
qualquer nome e descrição de item e nas mensagens. Strings com mini-marcação
são analisadas pelo MiniMessage (códigos legados `&` nelas são convertidos
automaticamente), strings mini quebradas não derrubam o plugin e recaem na
análise legacy normal. O prefixo especial **`actionbar:N!`** no início de uma
tradução envia o texto para a actionbar por N segundos (com atualização, sem
o prefixo do plugin):

```yaml
prefix: "&8[&bQQRegions&8] "

guard:
  blocked: "actionbar:3!&cO servidor está sobrecarregado — aguarde."
```

O prefixo funciona para **qualquer** mensagem emitida pelo plugin (comandos,
prompts de chat, resultados de cliques nos menus, notificações de raid): se o
texto começa com `actionbar:N!`, vai para a actionbar, caso contrário — para
o chat. O console recebe texto simples.

Em padrões de dinheiro use o par "número + símbolo"
(`{price} {price-symbol}`, `{raid-balance} {raid-balance-symbol}` etc.).
Padrões detalhados de sintaxe de comandos estão na seção `usage:` (ex.
para `/region help`), chaves de tempo compacto — `menu.time-short-*`, valores
"sim/não" no status da seleção — `select-status.yes/no`. A versão do arquivo é
`config-version: 6`; ao migrar de uma versão antiga do plugin, ele preenche
apenas os valores **vazios** — padrões ausentes no `lang.yml` antigo precisam
ser completados manualmente (ex.: adicione `{price-symbol}` às mensagens de
dinheiro, ou as versões anteriores continuarão mostrando o número sem símbolo).

### 7.5 data.yml

Automático; não edite em tempo de execução:

```text
players.<UUID>.flags       — flags compradas
players.<UUID>.area-packs  — pacotes de área
players.<UUID>.region-packs.<id> — quantidade de "+região"
players.<UUID>.custom-items.<id> — quantidade de compras de itens personalizados
offers.*                   — ofertas de mercado e aluguel
```

### 7.6 menus/*.yml

Layouts das GUIs. Edições de botões/textos/slots são aplicadas no
`/region reload`. Os slots do rodapé (45–53) dos menus não se sobrepõem aos
`menu-slots` (10–43), então botões duplicados são impossíveis.

Cada menu tem um `update_interval` (ticks) — com que frequência um menu aberto
é redesenhado (lore/preços/flags atualizam em tempo real); `0` = desativar
auto-atualização desse menu. O padrão global é `menu-update.ticks` no
config.yml. Cliques em botões redesenham o menu imediatamente; o
anti-autoclicker opcional `menu-update.debounce-after-click: true`
(desativado por padrão) adia o redraw de um menu aberto após o clique por
`update_interval` ticks — navegação e ações de botão são instantâneas.
Detalhes — em `menus/MENU_EDITOR.md` §8.

---

## 8. Configuração avançada

### 8.1 Modelos de permissão de seleção

Para o jogador é escolhido o modelo com o maior `priority` cujo
`permission` **ou** `placeholder` corresponde:

```yaml
selection-templates:
  default:
    permission: ""            # vazio = não verificar
    placeholder: ""           # "%plasma_skill_power%>=60" ou "true"/"false"
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

A mecânica do `placeholder` permite conceder limites por qualquer PAPI numérico
(`>=`, `>`, `<=`, `<`, `==` ou apenas `true`/`false`).

### 8.2 Proteção contra lag (guard)

Cliques em botões de menu (e outras mecânicas) são cancelados se o servidor
estiver sobrecarregado ou o ping do jogador for alto — proteção contra "dupe"
de itens durante lag:

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
    source: CLAN            # PLAYER — do saldo do ladrão (Vault), CLAN — do banco
    percent: 10
  display:
    mode: ACTIONBAR         # BOSSBAR | ACTIONBAR | NONE
    text: "&cCaptura {region}: &f{time}&c seg • atacantes &f{count}&c/&f{total}"
    thief-text: "&2Ladrão &f{thief}&2: &f{time}&2 seg"
  notify:
    start:
      message: "&8[&cClã&8] &f{clan} &cestá atacando a região &f{region}&7!"
      commands: []
```

As notificações suportam comandos `asConsole!...` e `asPlayer!...` com
substituição de `{placeholders}`.

### 8.4 Economia

O formato dos números é definido em `market.economy`:

```yaml
market:
  economy:
    symbol: "R$"
    symbol-position: AFTER     # AFTER = "1000 R$", BEFORE = "R$ 1000"
    decimal-places: 0
    grouping: true
    group-separator: ","
    decimal-separator: "."
  rent:
    grant: MEMBER              # MEMBER | OWNER
    charge: PERIOD             # ONCE | PERIOD (cobrança recorrente)
    period-minutes: 1440
  list-duration-minutes: 10080 # validade do anúncio público de aluguel (padrão 7 dias)
  auto-rent: true              # auto-retorno com auto-renovação após o fim do aluguel
  multiowner: single           # single — todo o valor ao iniciador; split — a todos os donos igualmente
  commission:
    enable: false              # comissão do servidor na venda/aluguel
    rate: 0.01                 # fração do valor (0.01 = 1%, 0.05 = 5%). Cobrada do recebedor
  offer-timeout-minutes: 60    # validade da oferta privada (0 = sem limite)
  offer-timeout-action: RELIST # o que fazer ao expirar: RELIST — publicar; CANCEL — remover do mercado
  market-holo:
    enabled: true              # UM holograma de placa perto do perímetro da região durante o anúncio
    view-distance: 24          # raio (por X/Z) em que o holograma conduz o jogador ao longo da fronteira
    y-offset: 1.5              # deslocamento relativo ao nível Y do JOGADOR (0 — exatamente no Y do jogador, 1.5 ≈ rosto)
    scale: 1.0                 # multiplicador do tamanho do texto
    line-width: 200            # comprimento máximo da linha em blocos
```

---

## 9. FAQ e solução de problemas

**O menu não abre / o comando responde "menu não configurado".**
Verifique os arquivos em `menus/` e `/region reload`. Menus individuais podem
ser desativados removendo as seções `dynamic-*` / `purchased-*` — o plugin
então oferece um fallback.

**O mercado não funciona.**
`market.enabled: true` + Vault instalado + plugin de economia.
Sem Vault, os comandos respondem "economia (Vault) indisponível".

**A loja de flags está vazia.**
Flags são vendidas apenas se **não** estiverem em `flags-menu.whitelist`
(as de lá são gratuitas) e não estiverem em `flags-menu.shop-ignore`. Um
`whitelist` vazio significa "todas as flags são vendidas".

**Uma flag comprada não aparece no menu.**
Se você é dono da região — a flag vai aparecer sem permissão. Para terceiros,
uma flag comprada não concede direitos sobre as regiões de outros jogadores.

**Não vejo os pontos da seleção.**
Verifique `particles.enabled: true` (ou `interactive.view-mode: BLOCKS`)
e se o mundo não foi desativado em `restrictions.disabled-worlds`.

**O destaque não dispara pela flag.**
A flag `territory-visible` deve estar como `allow`, e não dispara mais do que
`highlight.cooldown-seconds`. Com `territory-visible: false` a flag é
registrada, mas a entrada não destaca (o comando `/region visible`
funciona). Regiões próprias dentro de `auto-show-radius` também brilham
apenas com a flag `allow` — sem ela não acendem nem por perto, nem ao
entrar numa região vizinha com a flag. O contorno some sozinho após
`highlight.region-hide-seconds` (não ligado aos timeouts de seleção).

**Um raid não inicia.**
Verifique o JustTeams (clã em todos os atacantes), `min-attackers`,
`online-percent`, a lista negra e se os donos estão offline (se
`owners-offline-required: true`).

---