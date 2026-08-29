package su.nightexpress.excellenteconomy.tax.placeholder;

import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.tax.TaxRates;
import su.nightexpress.nightcore.bridge.placeholder.PlaceholderProvider;
import su.nightexpress.nightcore.bridge.placeholder.PlaceholderRegistry;

/**
 * Placeholders consumed by downstream plugins (MenuWallet).
 *
 * <p><b>CROSS-PLUGIN HARD CONTRACT.</b> The names and the return format below must stay
 * backwards compatible forever. External plugins depend on them long-term. If a different
 * format is ever needed, register a NEW placeholder and leave these untouched.
 *
 * <p>Guarantees for both placeholders:
 * <ul>
 *     <li>Plain decimal string, never {@code null}, never empty</li>
 *     <li>No colour codes, no percent sign, no scientific notation</li>
 *     <li>Resolvable with a {@code null} player (falls back to the base rate)</li>
 * </ul>
 */
public class TransferTaxPlaceholders implements PlaceholderProvider {

    /**
     * {@code %excellenteconomy_transfer_tax_rate_<currencyId>%}
     *
     * <p>Returns the tax rate that currently applies to the player, e.g. {@code 0.05}.
     * Returns {@code 0} when no tax applies.
     */
    public static final String TRANSFER_TAX_RATE = "transfer_tax_rate";

    /**
     * {@code %excellenteconomy_transfer_tax_amount_<currencyId>_<amount>%}
     *
     * <p>Returns the exact tax that would be charged for transferring {@code amount},
     * applying the server's rounding rules, e.g. {@code 5} or {@code 0.5}.
     *
     * <p>The payload is split on the <b>last</b> underscore: currency ids may legally contain
     * underscores (see {@code Exchange.Rates} defaults like {@code mystery_coins}), while an
     * amount never does. Splitting on the first underscore would mis-parse such ids.
     */
    public static final String TRANSFER_TAX_AMOUNT = "transfer_tax_amount";

    private final CurrencyRegistry currencyRegistry;
    private final TaxRates         rates;

    /**
     * Depends on {@link TaxRates} rather than on the whole {@code TransferTaxManager} so the
     * contract can be exercised without a running server.
     */
    public TransferTaxPlaceholders(@NonNull CurrencyRegistry currencyRegistry, @NonNull TaxRates rates) {
        this.currencyRegistry = currencyRegistry;
        this.rates = rates;
    }

    @Override
    public void addPlaceholders(@NonNull PlaceholderRegistry registry) {
        registry.registerRaw(TRANSFER_TAX_RATE, (player, payload) -> {
            ExcellentCurrency currency = this.currencyRegistry.getById(payload);
            if (currency == null) return "0";

            return TaxRates.formatDecimal(this.rates.getRate(player, currency));
        });

        registry.registerRaw(TRANSFER_TAX_AMOUNT, (player, payload) -> {
            int index = payload.lastIndexOf('_');
            if (index < 0) return "0";

            ExcellentCurrency currency = this.currencyRegistry.getById(payload.substring(0, index));
            if (currency == null) return "0";

            double amount = parseAmount(payload.substring(index + 1));
            if (amount <= 0D) return "0";

            return TaxRates.formatDecimal(this.rates.calculate(player, currency, amount).tax());
        });
    }

    private static double parseAmount(@NonNull String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : 0D;
        }
        catch (NumberFormatException exception) {
            return 0D;
        }
    }
}
