package su.nightexpress.excellenteconomy.tax;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * A staged transfer waiting to be confirmed.
 *
 * <p>Held in memory only: a restart silently discards every pending transfer. That is safe
 * because no money has moved yet - the sender simply gets "no pending transfer" if they try to
 * confirm afterwards - but it is not announced to the player, so the transfer just vanishes.
 */
public record PendingTransfer(
    UUID senderId,
    UUID targetId,
    String currencyId,
    double amount,
    double rate,
    double tax,
    double total,
    long createdAt
) {

    @NonNull
    public static PendingTransfer create(@NonNull UUID senderId, @NonNull UUID targetId,
                                         @NonNull String currencyId, @NonNull TaxBreakdown breakdown) {
        return new PendingTransfer(senderId, targetId, currencyId, breakdown.amount(), breakdown.rate(),
            breakdown.tax(), breakdown.total(), System.currentTimeMillis());
    }

    public boolean isExpired(long timeoutSeconds) {
        return System.currentTimeMillis() - this.createdAt > timeoutSeconds * 1000L;
    }
}
