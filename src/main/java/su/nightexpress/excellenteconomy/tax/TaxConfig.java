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
        if (!config.contains("Enabled")) config.set("Enabled", true);
        if (!config.contains("Base_Rate")) config.set("Base_Rate", 0.05D);
        if (!config.contains("Fixed_Amount")) config.set("Fixed_Amount", 0D);
        if (!config.contains("Min_Tax_Amount")) config.set("Min_Tax_Amount", 1D);
        if (!config.contains("Max_Rate")) config.set("Max_Rate", 0.5D);
        if (!config.contains("Rounding")) config.set("Rounding", Rounding.UP.name());
        if (!config.contains("Combination")) config.set("Combination", Combination.MAX.name());
        if (!config.contains("Confirm_Timeout_Seconds")) config.set("Confirm_Timeout_Seconds", 60);
        if (!config.contains("Confirm_Command_Aliases")) config.set("Confirm_Command_Aliases", "confirm");

        if (!config.contains("Permission_Tiers.0")) {
            config.set("Permission_Tiers.0.permission", "excellenteconomy.tax.rate.vip");
            config.set("Permission_Tiers.0.rate", 0.02D);
            config.set("Permission_Tiers.1.permission", "excellenteconomy.tax.rate.member");
            config.set("Permission_Tiers.1.rate", 0.03D);
        }

        if (!config.contains("Wealth_Tiers.0")) {
            config.set("Wealth_Tiers.0.min_balance", 1000000);
            config.set("Wealth_Tiers.0.rate", 0.10D);
            config.set("Wealth_Tiers.1.min_balance", 100000);
            config.set("Wealth_Tiers.1.rate", 0.07D);
        }
    }

    public void load(@NonNull YamlConfiguration config) {
        this.enabled = config.getBoolean("Enabled", true);
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
