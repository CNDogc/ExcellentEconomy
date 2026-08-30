# ExcellentEconomy – Transfer Tax 分支

本项目 fork 自 [nulli0n/ExcellentEconomy](https://github.com/nulli0n/ExcellentEconomy)。原插件是一款支持无限自定义货币的现代轻量经济插件（内置 **Vault** 与 **PlaceholderAPI** 支持），本分支在其基础上新增了**玩家间转账税**功能。

---

## 💸 转账税

对玩家间转账收取费用。由付款方承担：付款方被扣除 `金额 + 税额`，收款方精确收到 `金额`，税额直接销毁 —— 通缩回收机制。

**范围：** 仅对 `/pay` 指令征税。管理员操作（`/eco give|take|set`）、Vault API 调用、商店插件与任务奖励均**不受**影响。

### 配置

首次启动时生成 `plugins/ExcellentEconomy/tax.yml`，`/excellenteconomy reload` 会重新读取。全部配置项：

| 配置项 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `Enabled` | `false` | 总开关。为 `false` 时 `/pay` 立即到账，无税无确认 —— 与原版行为完全一致。 |
| `Base_Rate` | `0.05` | 无档位命中时的税率。纯小数，`0.05` = 5%。 |
| `Fixed_Amount` | `0` | 在百分比之外额外叠加的固定税额，取整前计算。 |
| `Min_Tax_Amount` | `1` | 整数货币的税额下限，防止小额转账取整后钻空子免税。 |
| `Max_Rate` | `0.5` | 在档位合并**之后**生效的硬上限 —— 防止误配的 `ADD` 叠加超过 100%。 |
| `Rounding` | `UP` | `UP` 远离零取整，`DOWN` 趋向零取整。小数货币无论哪种模式均保留 2 位。 |
| `Combination` | `MAX` | 权限档位与财富档位的合并方式：`MAX`、`MIN`、`ADD`、`FIRST_MATCH`。 |
| `Confirm_Timeout_Seconds` | `60` | 待确认转账的有效时长。 |
| `Confirm_Command_Aliases` | `confirm` | 逗号分隔。第一个别名是展示给玩家的那个。 |

两套档位体系，均可选：

```yaml
Permission_Tiers:        # 玩家取其持有所有档位中的最低税率
  0:
    permission: excellenteconomy.tax.rate.vip
    rate: 0.02
  1:
    permission: excellenteconomy.tax.rate.member
    rate: 0.03

Wealth_Tiers:            # 先匹配最高档位；文件内的书写顺序无关
  0:
    min_balance: 1000000
    rate: 0.10
  1:
    min_balance: 100000
    rate: 0.07
```

> 权限档位是**列表而非键值映射**。权限节点含有点号，而带点的 YAML 键会被解析成嵌套路径。

### 权限

| 权限节点 | 默认 | 效果 |
| :--- | :--- | :--- |
| `excellenteconomy.command.confirm` | `TRUE` | 执行确认指令。**必须保持 `TRUE`** —— 下游插件会*以玩家身份*执行它。 |
| `excellenteconomy.tax.exempt` | `FALSE` | 完全免除转账税。 |

### 占位符

两个占位符，供下游插件（如 MenuWallet）使用。**这是硬性的跨插件契约** —— 名称与返回格式将保持向后兼容。如需其他格式，会注册*新的*占位符，原有占位符继续可用。

| 占位符 | 示例输出 |
| :--- | :--- |
| `%excellenteconomy_transfer_tax_rate_<货币ID>%` | `0.05` |
| `%excellenteconomy_transfer_tax_amount_<货币ID>_<金额>%` | `50` |

两者均保证：纯小数字符串，绝不返回 `null`、绝不为空、无颜色代码、无百分号、无科学计数法，且在玩家为 `null` 时也能解析（回退到基础税率）。

金额占位符按**最后一个**下划线切分载荷，因为货币 ID 本身可以合法包含下划线（如 `mystery_coins`），而金额永远不会。

### 无服务器验证税收计算

税收计算被隔离在 `TaxRates` 中，不依赖运行中的服务器，可以直接验证：

```
./gradlew printPlaceholderSamples
```

（如果 wrapper 连不上 `services.gradle.org`，改用本地 Gradle：`gradle printPlaceholderSamples`。）

这是**回归检查，而非演示**。它加载 `samples/tax.yml`，运行真实的 `TaxRates` 代码，断言全部 104 个值符合预期 —— 税收逻辑一变，构建变红，退出码 1。

它还守护 `lang_cn.yml` 的结构：在某处插入一个更浅缩进的键，会悄悄把后面所有更深缩进的块重新挂到新的父级下，而 `git diff` 会显示这些行*未变化*，因为变化的只是它们的父级。因此这些路径被断言为能在代码读取的位置解析，挂错的翻译会让构建失败，而不是悄悄回退到英文。

文件名必须是 `lang_<语言>.yml`，不是 `messages_<语言>.yml`：nightcore 的 `LangRegistry` 用 `lang_` 前缀拼文件名（2.16.1 与 2.16.4 的常量池都已核实），`messages_` 是旧命名，要靠 `updateLegacy()` 迁移，而**该迁移在目标文件已存在时直接跳过**——打旧命名等于新翻译永远送不到已装好的服务器上。

```
--- transfer_tax_rate ---
PASS   Steve (no tiers)             transfer_tax_rate_coins          -> "0.05"   want "0.05"
PASS   Rich  (wealth tier)          transfer_tax_rate_coins          -> "0.07"   want "0.07"
PASS   Vip   (exempt)               transfer_tax_rate_coins          -> "0"      want "0"
PASS   null  (no player context)    transfer_tax_rate_coins          -> "0.05"   want "0.05"

--- Combination=ADD and Max_Rate clamping ---
PASS   Rich  (0.30 + 0.40, clamped) transfer_tax_rate_coins          -> "0.5"    want "0.5"
PASS   Rich  (0.30 + 0.40, no clamp) transfer_tax_rate_coins         -> "0.7"    want "0.7"
PASS   Rich  (permission wins)      transfer_tax_rate_coins          -> "0.3"    want "0.3"

OK - 104 checks passed.
```

覆盖范围：基础税率、两套档位体系、免税权限、`null` 玩家回退、小数与整数货币、两种取整模式、`Min_Tax_Amount` 下限、`ADD` 模式下的 `Max_Rate` 限幅、全部四种 `Combination` 模式、畸形载荷（未知货币、非数字、负数与零金额、缺少分隔符）、资金流转代码依赖的 `ChangeBalanceEvent` 不变量、一项漂移守护（断言每个 `writeDefaults` 值仍与 `samples/tax.yml` 一致）、配置加固（负数被限幅、未知枚举值回退、畸形档位被跳过）、非有限金额（NaN 与 Infinity 收敛为零金额分解，而非向下游泄漏非有限数值），以及针对 `lang_cn.yml` 的结构守护（断言每个键都能在其代码读取的父级下解析）。

`getRate` 和 `calculate` 是*仅有的*两处计算税率与税额的地方 —— `/confirm` 与占位符都经由它们路由，这正是菜单里显示的数字与实际扣掉的数字永远一致的原因。

---

## English

### Transfer Tax

Charge a fee on player-to-player transfers. The payer covers it: they are charged `amount + tax`, the receiver gets exactly `amount`, and the tax itself is destroyed — a deflationary sink.

**Scope:** only the `/pay` command is taxed. Admin operations (`/eco give|take|set`), Vault API calls, shop plugins and quest rewards are **not** touched.

#### Setup

`plugins/ExcellentEconomy/tax.yml` is generated on first start and re-read by `/excellenteconomy reload`. All knobs:

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `Enabled` | `false` | Master switch. When `false`, `/pay` transfers instantly with no tax and no confirmation — identical to stock behaviour. |
| `Base_Rate` | `0.05` | Rate when no tier matches. Plain decimal, `0.05` = 5%. |
| `Fixed_Amount` | `0` | Flat amount added on top of the percentage, before rounding. |
| `Min_Tax_Amount` | `1` | Floor for whole-number currencies, so tiny transfers can't round to a tax-free loophole. |
| `Max_Rate` | `0.5` | Hard ceiling applied **after** tiers combine — stops a misconfigured `ADD` from stacking past 100%. |
| `Rounding` | `UP` | `UP` rounds away from zero, `DOWN` towards it. Decimal currencies round to 2 places either way. |
| `Combination` | `MAX` | How a permission tier and a wealth tier merge: `MAX`, `MIN`, `ADD`, `FIRST_MATCH`. |
| `Confirm_Timeout_Seconds` | `60` | How long a pending transfer stays valid. |
| `Confirm_Command_Aliases` | `confirm` | Comma separated. The first alias is the one shown to players. |

Two tier systems, both optional:

```yaml
Permission_Tiers:        # player keeps the LOWEST rate among every tier they hold
  0:
    permission: excellenteconomy.tax.rate.vip
    rate: 0.02
  1:
    permission: excellenteconomy.tax.rate.member
    rate: 0.03

Wealth_Tiers:            # matched highest bracket first; file order does not matter
  0:
    min_balance: 1000000
    rate: 0.10
  1:
    min_balance: 100000
    rate: 0.07
```

> Permission tiers are a **list, not a mapping**. Permission nodes contain dots, and a dotted YAML key would be parsed as a nested path.

#### Permissions

| Node | Default | Effect |
| :--- | :--- | :--- |
| `excellenteconomy.command.confirm` | `TRUE` | Run the confirm command. **Must stay `TRUE`** — downstream plugins dispatch it *as the player*. |
| `excellenteconomy.tax.exempt` | `FALSE` | Pay no transfer tax at all. |

#### Placeholders

Two placeholders, consumed by downstream plugins (e.g. MenuWallet). **These are a hard cross-plugin contract** — the names and the return format will stay backwards compatible. If a different format is ever needed, a *new* placeholder gets registered and these keep working.

| Placeholder | Example output |
| :--- | :--- |
| `%excellenteconomy_transfer_tax_rate_<currencyId>%` | `0.05` |
| `%excellenteconomy_transfer_tax_amount_<currencyId>_<amount>%` | `50` |

Both guarantee: plain decimal string, never `null`, never empty, no colour codes, no percent sign, no scientific notation, and resolvable with a `null` player (falls back to the base rate).

The amount form splits its payload on the **last** underscore, because currency ids may legally contain underscores (`mystery_coins`) while an amount never does.

#### Verifying tax math without a server

The tax math is isolated behind `TaxRates` and has no dependency on a running server, so it can be exercised directly:

```
./gradlew printPlaceholderSamples
```

(If the wrapper can't reach `services.gradle.org`, point it at a local Gradle instead:
`gradle printPlaceholderSamples`.)

This is a **regression check, not a demo**. It loads `samples/tax.yml`, runs the real `TaxRates` code and asserts all 104 values against expectations — a change to the tax math turns the build red, exit code 1.

It also guards the shape of `lang_cn.yml`: inserting a key at a shallower indentation silently re-parents every following deeper-indented block, and `git diff` shows those lines as *unchanged* because only their parent moved. The paths are therefore asserted to resolve where the code reads them, so a mis-parented translation fails the build instead of quietly falling back to English.

The files must be named `lang_<locale>.yml`, not `messages_<locale>.yml`: nightcore's `LangRegistry` builds the name from a `lang_` prefix (verified in the constant pool of both 2.16.1 and 2.16.4). `messages_` is the legacy name that `updateLegacy()` migrates — and **that migration is skipped when the target file already exists**, so shipping the legacy name means new translations never reach an existing install.

```
--- transfer_tax_rate ---
PASS   Steve (no tiers)             transfer_tax_rate_coins          -> "0.05"   want "0.05"
PASS   Rich  (wealth tier)          transfer_tax_rate_coins          -> "0.07"   want "0.07"
PASS   Vip   (exempt)               transfer_tax_rate_coins          -> "0"      want "0"
PASS   null  (no player context)    transfer_tax_rate_coins          -> "0.05"   want "0.05"

--- Combination=ADD and Max_Rate clamping ---
PASS   Rich  (0.30 + 0.40, clamped) transfer_tax_rate_coins          -> "0.5"    want "0.5"
PASS   Rich  (0.30 + 0.40, no clamp) transfer_tax_rate_coins         -> "0.7"    want "0.7"
PASS   Rich  (permission wins)      transfer_tax_rate_coins          -> "0.3"    want "0.3"

OK - 104 checks passed.
```

Covered: base rate, both tier systems, exempt permission, `null` player fallback, decimal vs whole-number currencies, both rounding modes, the `Min_Tax_Amount` floor, `Max_Rate` clamping under `ADD`, all four `Combination` modes, malformed payloads (unknown currency, non-numeric, negative and zero amounts, missing separator), the `ChangeBalanceEvent` invariants the money-movement code depends on, a drift guard asserting every `writeDefaults` value still matches `samples/tax.yml`, config hardening (negative numbers clamped, unknown enum values falling back, malformed tiers skipped), non-finite amounts (NaN and Infinity collapsing to a zero-amount breakdown rather than leaking a non-finite number downstream), and a structural guard on `lang_cn.yml` asserting every key resolves under the parent the code reads it from.

`getRate` and `calculate` are the *only* places rates and amounts are computed — both `/confirm` and the placeholders route through them, which is what guarantees the number shown in a menu always matches the number actually deducted.
