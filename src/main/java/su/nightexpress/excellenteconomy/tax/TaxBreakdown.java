package su.nightexpress.excellenteconomy.tax;

/**
 * Immutable result of a tax calculation.
 *
 * @param amount Transfer principal (what the receiver gets).
 * @param rate   Applied tax rate as a plain decimal (0.05 = 5%).
 * @param tax    Tax amount, already rounded with server-side rules.
 * @param total  Total debit taken from the sender (amount + tax).
 */
public record TaxBreakdown(double amount, double rate, double tax, double total) {

    public static TaxBreakdown of(double amount, double rate, double tax) {
        return new TaxBreakdown(amount, rate, tax, amount + tax);
    }

    public static TaxBreakdown none(double amount) {
        return new TaxBreakdown(amount, 0D, 0D, amount);
    }

    public boolean hasTax() {
        return this.tax > 0D;
    }
}
