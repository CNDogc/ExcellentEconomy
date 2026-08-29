<p align="center">
  <img src="https://nightexpressdev.com/excellenteconomy/logo.png">
</p>

**ExcellentEconomy** is a modern, lightweight economy plugin that lets you create unlimited custom currencies. You can finally manage your Coins, Points, Tokens, and any other currency in one place instead of using multiple plugins - with built-in **Vault** and **PlaceholderAPI** support.

Everything is designed for **total customization**, from text strings to in-game commands. You have the freedom to **change everything** to perfectly match your server's style and needs!

To upgrade from CoinsEngine, see [This Guide](https://nightexpressdev.com/excellenteconomy/upgrade-guide/).

## 🖼️ Showcase

![](https://nightexpressdev.com/excellenteconomy/img/config.png)
![](https://nightexpressdev.com/excellenteconomy/img/leaderboards.png)

## ⭐ Core Features

- **Vault Integration** – Works right out of the box with Vault to hook into all your economy stuff automatically.
- **Database Options** – Pick the storage that fits your needs. Use SQLite for a simple setup or MySQL if you are scaling up.
- **Modern Formatting** – Make your messages pop! We fully support MiniMessage, so you can use gradients and hex colors in every menu and chat message.
- **Amount Shortcuts** – Stop counting zeros. Just type `1k` or `1m` in commands to save yourself some time.
- **Data Import** – Switching from another plugin? No big deal. You can move all player balances over with just one command.
- **Data Maintenance** – Keep things snappy by automatically purging old data from players who haven't logged in for a while.
- **Operation Logs** – Stay in the loop. Every single transaction is tracked in the console or a dedicated log file so nothing goes missing.
- **Wallet** – Check all your different balances at once with a single, easy command.
- **PlaceholderAPI Support** – Loaded with built-in placeholders, making it easy to display player/server stats anywhere on your server.
- **Transfer Tax** – Charge a fee on player-to-player transfers, with permission tiers, wealth brackets, and a confirm step. See [Transfer Tax](#-transfer-tax).
- **Developer API** – Use API to hook into the system and integrate it with your plugins.

---

## 💵 Currency Features

- **Display Name** – Pick any name you want for your currency.
- **Unique Symbols** – Assign a visual symbol (like a dollar sign or a custom character) to represent your funds.
- **Flexible Formatting** – Fully customize how the currency balance looks in-game.
- **Custom Commands** – Set up your own shorthand commands so players can access their wallet easily.
- **Visual Icons** – Choose any material or item to act as the icon for a currency in various menus.
- **Decimal Support** – Toggle between simple whole numbers or precise decimal values for more granular economies.
- **Permission Access** – Control whether everyone can use the currency or if it requires a specific permission node.
- **P2P Transfers** – Enable or disable the ability for players to send money to each other, complete with minimum transfer limits.
- **Balance Limits** – Define exactly how much cash a new player starts with and set a maximum cap to prevent infinite wealth.
- **Exchange Rates** – Set up a conversion system to swap a currency for others at whatever rate you choose.
- **Database Management** – Specify a custom database column name for clean data storage.
- **Cross-Server Syncing** – Choose if the currency should stay local to one server or synchronize across your entire network.
- **Custom Prefixes** – Add a specific tag or prefix to identify a currency in all chat messages.
- **Leaderboards** – Enable rankings to show off the top earners and track the richest players on the server.

## 🧰 Requirements

The following versions and platforms are supported: 

| **Server Version**  | **Paper** | **Spigot** | **Folia** | **Java Version**
| :---: | :---: | :---: | :--: | :---: |
| 26.2 | ✔️ | ✔️ | ❌ | 25 |
| 26.1.2 | ✔️ | ✔️ | ❌ | 25 |
| 26.1.1 | ✔️ | ✔️ | ❌ | 25 |
| 1.21.11 | ✔️ | ✔️ | ❌ | 25 |
| 1.21.10 | ✔️ | ✔️ | ❌ | 25 |
| 1.21.9 | ✔️ | ✔️ | ❌ | 25 |
| 1.21.8 | ✔️ | ✔️ | ❌ | 25 |

- Anything not listed in the compatibility table is **NOT** supported.
- Make sure to check out all known issues and incompatibilities [here](https://nightexpressdev.com/excellenteconomy/faq/).

**Dependencies:**
- [NightCore](https://nightexpressdev.com/nightcore/) - Framework **required** for the plugin to run.

**Optional Plugins:**
- [PlaceholderAPI](https://spigotmc.org/resources/6245/) - For global placeholders to use in other plugins.

## 💸 Transfer Tax

Charge a fee on player-to-player transfers. The payer covers it: they are charged `amount + tax`, the receiver gets exactly `amount`, and the tax itself is destroyed — a deflationary sink.

**Scope:** only the `/pay` command is taxed. Admin operations (`/eco give|take|set`), Vault API calls, shop plugins and quest rewards are **not** touched.

### Setup

`plugins/ExcellentEconomy/tax.yml` is generated on first start and re-read by `/excellenteconomy reload`. All knobs:

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `Enabled` | `true` | Master switch. When `false`, `/pay` transfers instantly with no tax and no confirmation — identical to stock behaviour. |
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

### Permissions

| Node | Default | Effect |
| :--- | :--- | :--- |
| `excellenteconomy.command.confirm` | `TRUE` | Run the confirm command. **Must stay `TRUE`** — downstream plugins dispatch it *as the player*. |
| `excellenteconomy.tax.exempt` | `FALSE` | Pay no transfer tax at all. |

### Placeholders

Two placeholders, consumed by downstream plugins (e.g. MenuWallet). **These are a hard cross-plugin contract** — the names and the return format will stay backwards compatible. If a different format is ever needed, a *new* placeholder gets registered and these keep working.

| Placeholder | Example output |
| :--- | :--- |
| `%excellenteconomy_transfer_tax_rate_<currencyId>%` | `0.05` |
| `%excellenteconomy_transfer_tax_amount_<currencyId>_<amount>%` | `50` |

Both guarantee: plain decimal string, never `null`, never empty, no colour codes, no percent sign, no scientific notation, and resolvable with a `null` player (falls back to the base rate).

The amount form splits its payload on the **last** underscore, because currency ids may legally contain underscores (`mystery_coins`) while an amount never does.

### Verifying tax math without a server

The tax math is isolated behind `TaxRates` and has no dependency on a running server, so it can be exercised directly:

```
./gradlew printPlaceholderSamples
```

(If the wrapper can't reach `services.gradle.org`, point it at a local Gradle instead:
`gradle printPlaceholderSamples`.)

This is a **regression check, not a demo**. It loads `samples/tax.yml`, runs the real `TaxRates` code and asserts all 75 values against expectations — a change to the tax math turns the build red, exit code 1.

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

OK - 24 checks passed.
```

Covered: base rate, both tier systems, exempt permission, `null` player fallback, decimal vs whole-number currencies, both rounding modes, the `Min_Tax_Amount` floor, `Max_Rate` clamping under `ADD`, all four `Combination` modes, malformed payloads (unknown currency, non-numeric, negative and zero amounts, missing separator), the `ChangeBalanceEvent` invariants the money-movement code depends on, a drift guard asserting every `writeDefaults` value still matches `samples/tax.yml`, config hardening (negative numbers clamped, unknown enum values falling back, malformed tiers skipped), and non-finite amounts (NaN and Infinity collapsing to a zero-amount breakdown rather than leaking a non-finite number downstream).

`getRate` and `calculate` are the *only* places rates and amounts are computed — both `/confirm` and the placeholders route through them, which is what guarantees the number shown in a menu always matches the number actually deducted.

## ❤️ Donate

Everything here is created and maintained by a single person. If you enjoy my work or find my plugins useful, feel free to [Buy me a coffee](https://ko-fi.com/nightexpress) :)  

Thank you!