package su.nightexpress.excellenteconomy.tax;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * A staged transfer waiting to be confirmed.
 *
 * <p>Held in memory only: a server restart discards every pending transfer, which is
 * intentional and communicated to the player.
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
