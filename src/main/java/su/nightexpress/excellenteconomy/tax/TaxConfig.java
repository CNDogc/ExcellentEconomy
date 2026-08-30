package su.nightexpress.excellenteconomy.tax;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TaxConfig {

    public enum Rounding {
        UP, DOWN
    }

    public enum Combination {
        MAX, MIN, ADD, FIRST_MATCH
    }

    /**
     * A tax rate bound to a permission node. Permission names contain dots, so tiers are
     * stored as an ordered list instead of a mapping (a dotted YAML key would be parsed
     * as a nested path by the configuration reader).
     */
    public record PermissionTier(String permission, double rate) { }

    /**
     * A tax rate bound to a minimum balance. Sorted descending by {@code minBalance},
     * so the highest bracket the player qualifies for wins.
     */
    public record WealthTier(double minBalance, double rate) { }

    private boolean     enabled        = true;
    private double      baseRate       = 0.05D;
    private double      fixedAmount    = 0D;
    private double      minTaxAmount   = 1D;
    private double      maxRate        = 0.5D;
    private long        timeoutSeconds = 60L;
    private Rounding    rounding       = Rounding.UP;
    private Combination combination    = Combination.MAX;
    private String[]    confirmAliases = {"confirm"};

    private final List<PermissionTier> permissionTiers = new ArrayList<>();
    private final List<WealthTier>     wealthTiers     = new ArrayList<>();

    public void writeDefaults(@NonNull YamlConfiguration config) {
        // 注释仅在首次生成时写入：已存在的键不动，避免覆盖服主自己改过的注释。
        if (!config.contains("Enabled")) {
            // 整个配置文件是新生成的（缺少主开关），补上文件头说明。
            if (config.getKeys(false).isEmpty()) {
                config.options().setHeader(List.of(
                    " ExcellentEconomy - 转账税 (Transfer Tax)",
                    "",
                    " 文件位置：plugins/ExcellentEconomy/tax.yml",
                    " 重载方式：/excellenteconomy reload",
                    "",
                    " 税费仅对 /pay（玩家对玩家转账）生效。",
                    " 管理员操作（/eco give、/eco take、/eco set）、Vault API 调用、",
                    " 商店插件与任务奖励均不收税。",
                    "",
                    " 税费由付款方承担：付款方被扣除（金额 + 税额），收款方精确收到金额，",
                    " 税额本身直接销毁（通缩回收）。"
                ));
            }

            config.set("Enabled", false);
            config.setComments("Enabled", List.of(
                " 总开关。为 false 时 /pay 立即到账，无税、无确认步骤 —— 与原版行为完全一致。"
            ));
        }
        if (!config.contains("Base_Rate")) {
            config.set("Base_Rate", 0.05D);
            config.setComments("Base_Rate", List.of(
                " 未命中任何权限档位与财富档位时适用的税率。",
                " 书写为纯小数：0.05 = 5%。"
            ));
        }
        if (!config.contains("Fixed_Amount")) {
            config.set("Fixed_Amount", 0D);
            config.setComments("Fixed_Amount", List.of(
                " 在百分比之外额外叠加的固定税额（取整前计算）。",
                " 想让每笔转账至少收一点税时很有用。"
            ));
        }
        if (!config.contains("Min_Tax_Amount")) {
            config.set("Min_Tax_Amount", 1D);
            config.setComments("Min_Tax_Amount", List.of(
                " 整数货币可收取的最小税额。",
                " 没有此下限时，1 金币按 5% 收税取整后为 0，玩家可以拆成多笔小额转账避税。"
            ));
        }
        if (!config.contains("Max_Rate")) {
            config.set("Max_Rate", 0.5D);
            config.setComments("Max_Rate", List.of(
                " 有效税率的硬上限，在档位合并之后生效。",
                " 防止误配的 ADD 组合把档位叠加到 100% 以上。"
            ));
        }
        if (!config.contains("Rounding")) {
            config.set("Rounding", Rounding.UP.name());
            config.setComments("Rounding", List.of(
                " 原始税额如何取整为可支付金额。",
                "   UP   - 远离零取整（玩家略多付）",
                "   DOWN - 趋向零取整（玩家略少付）",
                " 无论哪种模式，小数货币均保留 2 位小数。"
            ));
        }
        if (!config.contains("Combination")) {
            config.set("Combination", Combination.MAX.name());
            config.setComments("Combination", List.of(
                " 权限档位与财富档位同时命中时如何合并。",
                "   MAX         - 取两者中较高者",
                "   MIN         - 取两者中较低者（对玩家有利）",
                "   ADD         - 两者相加",
                "   FIRST_MATCH - 权限档位恒胜出"
            ));
        }
        if (!config.contains("Confirm_Timeout_Seconds")) {
            config.set("Confirm_Timeout_Seconds", 60);
            config.setComments("Confirm_Timeout_Seconds", List.of(
                " 待确认转账的有效时长（秒）。超时后玩家需重新执行 /pay。"
            ));
        }
        if (!config.contains("Confirm_Command_Aliases")) {
            config.set("Confirm_Command_Aliases", "confirm");
            config.setComments("Confirm_Command_Aliases", List.of(
                " 确认指令的别名，逗号分隔。第一个别名是确认框中展示给玩家的那个。"
            ));
        }

        if (!config.contains("Permission_Tiers.0")) {
            config.set("Permission_Tiers.0.permission", "excellenteconomy.tax.rate.vip");
            config.set("Permission_Tiers.0.rate", 0.02D);
            config.set("Permission_Tiers.1.permission", "excellenteconomy.tax.rate.member");
            config.set("Permission_Tiers.1.rate", 0.03D);
            config.setComments("Permission_Tiers", List.of(
                " 权限档位",
                "",
                " 以列表存储而非键值映射：权限节点含点号，带点的 YAML 键",
                " 会被配置读取器解析成嵌套路径。",
                "",
                " 玩家持有多个档位时，取其中最低税率 —— 可以给全员发 member、",
                " 再在此基础上发 vip，绝不会抬高账单。"
            ));
            config.setComments("Permission_Tiers.0.permission", List.of(" 触发该档位的权限节点"));
            config.setComments("Permission_Tiers.0.rate", List.of(" 该档位的税率"));
            config.setComments("Permission_Tiers.1.permission", List.of(" 触发该档位的权限节点"));
            config.setComments("Permission_Tiers.1.rate", List.of(" 该档位的税率"));
        }

        if (!config.contains("Wealth_Tiers.0")) {
            config.set("Wealth_Tiers.0.min_balance", 1000000);
            config.set("Wealth_Tiers.0.rate", 0.10D);
            config.set("Wealth_Tiers.1.min_balance", 100000);
            config.set("Wealth_Tiers.1.rate", 0.07D);
            config.setComments("Wealth_Tiers", List.of(
                " 财富档位",
                "",
                " 按付款方所持（正在发送的）货币的余额判定。先匹配最高档位，",
                " 文件中的书写顺序无关紧要。"
            ));
            config.setComments("Wealth_Tiers.0.min_balance", List.of(" 触发该档位的最低余额"));
            config.setComments("Wealth_Tiers.0.rate", List.of(" 该档位的税率"));
            config.setComments("Wealth_Tiers.1.min_balance", List.of(" 触发该档位的最低余额"));
            config.setComments("Wealth_Tiers.1.rate", List.of(" 该档位的税率"));
        }
    }

    public void load(@NonNull YamlConfiguration config) {
        this.enabled = config.getBoolean("Enabled", false);
        this.baseRate = Math.max(0D, config.getDouble("Base_Rate", 0.05D));
        this.fixedAmount = Math.max(0D, config.getDouble("Fixed_Amount", 0D));
        this.minTaxAmount = Math.max(0D, config.getDouble("Min_Tax_Amount", 1D));
        this.maxRate = Math.max(0D, config.getDouble("Max_Rate", 0.5D));
        this.timeoutSeconds = Math.max(1L, config.getLong("Confirm_Timeout_Seconds", 60L));

        this.rounding = parseEnum(Rounding.class, config.getString("Rounding"), Rounding.UP);
        this.combination = parseEnum(Combination.class, config.getString("Combination"), Combination.MAX);

        String aliasesRaw = config.getString("Confirm_Command_Aliases");
        if (aliasesRaw != null && !aliasesRaw.isBlank()) {
            this.confirmAliases = aliasesRaw.split(",");
        }

        this.permissionTiers.clear();
        for (int index = 0; config.contains("Permission_Tiers." + index); index++) {
            String permission = config.getString("Permission_Tiers." + index + ".permission");
            double rate = config.getDouble("Permission_Tiers." + index + ".rate");
            if (permission == null || permission.isBlank() || rate < 0D) continue;

            this.permissionTiers.add(new PermissionTier(permission.trim(), rate));
        }

        this.wealthTiers.clear();
        for (int index = 0; config.contains("Wealth_Tiers." + index); index++) {
            double minBalance = config.getDouble("Wealth_Tiers." + index + ".min_balance");
            double rate = config.getDouble("Wealth_Tiers." + index + ".rate");
            if (minBalance < 0D || rate < 0D) continue;

            this.wealthTiers.add(new WealthTier(minBalance, rate));
        }
        // Highest bracket first, so iteration can stop at the first match.
        this.wealthTiers.sort(Comparator.comparingDouble(WealthTier::minBalance).reversed());
    }

    @NonNull
    private static <E extends Enum<E>> E parseEnum(@NonNull Class<E> type, String raw, @NonNull E fallback) {
        if (raw == null || raw.isBlank()) return fallback;

        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public double getBaseRate() {
        return this.baseRate;
    }

    public double getFixedAmount() {
        return this.fixedAmount;
    }

    public double getMinTaxAmount() {
        return this.minTaxAmount;
    }

    public double getMaxRate() {
        return this.maxRate;
    }

    public long getTimeoutSeconds() {
        return this.timeoutSeconds;
    }

    @NonNull
    public Rounding getRounding() {
        return this.rounding;
    }

    @NonNull
    public Combination getCombination() {
        return this.combination;
    }

    @NonNull
    public String[] getConfirmAliases() {
        return this.confirmAliases;
    }

    @NonNull
    public List<PermissionTier> getPermissionTiers() {
        return this.permissionTiers;
    }

    @NonNull
    public List<WealthTier> getWealthTiers() {
        return this.wealthTiers;
    }
}
