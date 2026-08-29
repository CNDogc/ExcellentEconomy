package su.nightexpress.excellenteconomy.tax;

import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * Single source of truth for tax math.
 *
 * <p>{@link #getRate(Player, ExcellentCurrency)} and {@link #calculate(Player, ExcellentCurrency, double)}
 * are the ONLY places where rates and tax amounts are computed. Both the {@code /confirm}
 * execution path and the PlaceholderAPI expansions go through them, which is what guarantees
 * that the amount shown in a downstream UI always matches the amount actually deducted.
 */
public class TaxRates {

    private static final int DECIMAL_SCALE = 2;

    private final TaxConfig                    config;
    private final Function<Player, CoinsUser>  userLookup;

    public TaxRates(@NonNull TaxConfig config, @NonNull UserManager userManager) {
        this(config, userManager::getOrFetch);
    }

    /**
     * @param userLookup Resolves a player to their economy profile. Injected rather than taken
     *                   from {@link UserManager} directly so the tax math can be exercised
     *                   without a running server.
     */
    public TaxRates(@NonNull TaxConfig config, @NonNull Function<Player, CoinsUser> userLookup) {
        this.config = config;
        this.userLookup = userLookup;
    }

    @NonNull
    public TaxConfig getConfig() {
        return this.config;
    }

    /**
     * Resolves the tax rate that applies to the given player.
     *
     * @param player May be {@code null} (e.g. a non-player placeholder context). In that case
     *               no permission and no wealth tier can be resolved, so the base rate is used.
     * @return Effective rate as a plain decimal, already clamped to {@code [0, Max_Rate]}.
     */
    public double getRate(@Nullable Player player, @NonNull ExcellentCurrency currency) {
        if (!this.config.isEnabled()) return 0D;
        if (player != null && player.hasPermission(Perms.TAX_EXEMPT)) return 0D;

        Double permissionRate = this.matchPermissionTier(player);
        Double wealthRate = this.matchWealthTier(player, currency);

        double rate;
        if (permissionRate == null && wealthRate == null) {
            rate = this.config.getBaseRate();
        }
        else if (permissionRate == null) {
            rate = wealthRate;
        }
        else if (wealthRate == null) {
            rate = permissionRate;
        }
        else {
            rate = switch (this.config.getCombination()) {
                case MAX -> Math.max(permissionRate, wealthRate);
                case MIN -> Math.min(permissionRate, wealthRate);
                case ADD -> permissionRate + wealthRate;
                case FIRST_MATCH -> permissionRate;
            };
        }

        return Math.max(0D, Math.min(rate, this.config.getMaxRate()));
    }

    /**
     * Computes the full tax breakdown for a transfer.
     *
     * <p>This is the shared entry point used by {@code /confirm} and by the
     * {@code transfer_tax_amount} placeholder, so displayed and deducted values cannot drift apart.
     */
    @NonNull
    public TaxBreakdown calculate(@Nullable Player player, @NonNull ExcellentCurrency currency, double rawAmount) {
        double amount = currency.floorIfNeeded(rawAmount);
        // Also rejects NaN and Infinity: both would slip past a plain `<= 0` check.
        if (!Double.isFinite(amount) || amount <= 0D) return TaxBreakdown.none(0D);

        double rate = this.getRate(player, currency);
        double tax = this.roundTax(currency, amount * rate + this.config.getFixedAmount());

        return TaxBreakdown.of(amount, rate, tax);
    }

    private double roundTax(@NonNull ExcellentCurrency currency, double raw) {
        if (!Double.isFinite(raw) || raw <= 0D) return 0D;

        if (currency.isDecimal()) {
            RoundingMode mode = this.config.getRounding() == TaxConfig.Rounding.UP
                ? RoundingMode.CEILING : RoundingMode.FLOOR;
            return BigDecimal.valueOf(raw).setScale(DECIMAL_SCALE, mode).doubleValue();
        }

        double value = this.config.getRounding() == TaxConfig.Rounding.UP ? Math.ceil(raw) : Math.floor(raw);
        // Whole-number currencies: never let a small transfer round down to a tax-free loophole.
        return Math.max(value, this.config.getMinTaxAmount());
    }

    @Nullable
    private Double matchPermissionTier(@Nullable Player player) {
        if (player == null) return null;

        double best = -1D;
        for (TaxConfig.PermissionTier tier : this.config.getPermissionTiers()) {
            if (!player.hasPermission(tier.permission())) continue;
            // Player keeps the most favourable rate among every tier they hold.
            if (best < 0D || tier.rate() < best) best = tier.rate();
        }

        return best < 0D ? null : best;
    }

    @Nullable
    private Double matchWealthTier(@Nullable Player player, @NonNull ExcellentCurrency currency) {
        if (player == null) return null;

        CoinsUser user = this.userLookup.apply(player);
        double balance = user.getBalance(currency);

        // Tiers are sorted descending, so the first match is the highest bracket reached.
        for (TaxConfig.WealthTier tier : this.config.getWealthTiers()) {
            if (balance >= tier.minBalance()) return tier.rate();
        }

        return null;
    }

    /**
     * Formats a value as a plain decimal string.
     *
     * <p>This is the single formatting exit point for both placeholders. It never returns
     * {@code null}, never applies colour codes and never produces scientific notation
     * ({@code Double.toString} would emit {@code 1.0E-4} for small rates, which no
     * downstream parser expects).
     */
    @NonNull
    public static String formatDecimal(double value) {
        if (value <= 0D) return "0";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Formats a rate for display, e.g. {@code 0.075} -> {@code 7.5%}.
     *
     * <p>Shifts the decimal point on a {@link BigDecimal} rather than doing {@code rate * 100}
     * in floating point: {@code 0.07 * 100} yields {@code 7.000000000000001}.
     */
    @NonNull
    public static String formatPercent(double rate) {
        if (rate <= 0D) return "0%";
        return BigDecimal.valueOf(rate).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }
}
