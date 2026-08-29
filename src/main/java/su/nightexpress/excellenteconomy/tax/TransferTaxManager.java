package su.nightexpress.excellenteconomy.tax;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import su.nightexpress.excellenteconomy.EconomyFiles;
import su.nightexpress.excellenteconomy.EconomyPlaceholders;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.command.CommandManager;
import su.nightexpress.excellenteconomy.config.Lang;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.tax.placeholder.TransferTaxPlaceholders;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserManager;
import su.nightexpress.nightcore.commands.command.NightCommand;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.core.config.CoreLang;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.placeholder.CommonPlaceholders;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step {@code /pay}: the transfer is staged, the tax is shown, and the money only moves
 * once the player runs the confirm command.
 *
 * <p>When tax is disabled this manager is inert: {@link PayCommand} keeps calling
 * {@link CurrencyManager#send(Player, CoinsUser, ExcellentCurrency, double)} directly, so the
 * upstream behaviour is bit-for-bit unchanged.
 */
public class TransferTaxManager extends AbstractManager<EconomyPlugin> {

    /**
     * Tolerance used when re-checking that the staged tax still matches the current one.
     */
    private static final double TAX_EPSILON = 0.000001D;

    private final CurrencyRegistry currencyRegistry;
    private final CommandManager   commandManager;
    private final UserManager      userManager;
    private final CurrencyManager  currencyManager;

    private final TaxConfig config;
    private final TaxRates  rates;

    /**
     * Sender id -> staged transfer. Keyed by the sender because a player may only ever have
     * one transfer awaiting confirmation.
     */
    private final Map<UUID, PendingTransfer> pending;

    /**
     * Sender id -> target user, populated only when the target is offline. An online target is
     * always re-resolved through {@link UserManager#getOrFetch(Player)} so a relog can never
     * leave us writing to a stale user object.
     */
    private final Map<UUID, CoinsUser> offlineTargets;

    public TransferTaxManager(@NonNull EconomyPlugin plugin,
                              @NonNull CurrencyRegistry currencyRegistry,
                              @NonNull CommandManager commandManager,
                              @NonNull UserManager userManager,
                              @NonNull CurrencyManager currencyManager) {
        super(plugin);
        this.currencyRegistry = currencyRegistry;
        this.commandManager = commandManager;
        this.userManager = userManager;
        this.currencyManager = currencyManager;

        this.config = new TaxConfig();
        this.rates = new TaxRates(this.config, userManager);
        this.pending = new ConcurrentHashMap<>();
        this.offlineTargets = new ConcurrentHashMap<>();
    }

    @Override
    protected void onLoad() {
        this.loadConfiguration();
        this.loadCommands();

        this.plugin.addGlobalPlaceholders(new TransferTaxPlaceholders(this.currencyRegistry, this.rates));

        // Sweep abandoned transfers once a minute.
        this.addAsyncTask(this::purgeExpired, 1200L);
    }

    @Override
    protected void onShutdown() {
        this.pending.clear();
        this.offlineTargets.clear();
    }

    private void loadConfiguration() {
        FileConfig config = FileConfig.load(this.plugin.dataPath().resolve(EconomyFiles.FILE_TAX));

        this.config.writeDefaults(config);
        config.saveChanges();
        this.config.load(config);
    }

    private void loadCommands() {
        this.commandManager.addStandaloneCommand(NightCommand.literal(this.plugin, this.config.getConfirmAliases(),
            builder -> builder
                .description(Lang.COMMAND_CONFIRM_DESC)
                .permission(Perms.COMMAND_CONFIRM)
                .executes((context, arguments) -> {
                    if (!context.isPlayer()) {
                        context.errorBadPlayer();
                        return false;
                    }

                    return this.confirm(context.getPlayerOrThrow());
                })
        ));
    }

    @NonNull
    public TaxConfig getConfig() {
        return this.config;
    }

    public boolean isEnabled() {
        return this.config.isEnabled();
    }

    /**
     * @see TaxRates#getRate(Player, ExcellentCurrency)
     */
    public double getRate(@Nullable Player player, @NonNull ExcellentCurrency currency) {
        return this.rates.getRate(player, currency);
    }

    /**
     * @see TaxRates#calculate(Player, ExcellentCurrency, double)
     */
    @NonNull
    public TaxBreakdown calculate(@Nullable Player player, @NonNull ExcellentCurrency currency, double amount) {
        return this.rates.calculate(player, currency, amount);
    }

    /**
     * Stages a transfer instead of executing it, then shows the tax breakdown.
     *
     * <p>Runs the same validation as {@link CurrencyManager#send} up front, so a doomed
     * transfer is rejected while the player is still looking at the {@code /pay} result
     * rather than one command later.
     */
    public boolean startTransfer(@NonNull Player sender, @NonNull CoinsUser targetUser,
                                 @NonNull ExcellentCurrency currency, double rawAmount) {
        if (targetUser.isHolder(sender)) {
            currency.sendPrefixed(CoreLang.COMMAND_EXECUTION_NOT_YOURSELF, sender);
            return false;
        }

        double amount = currency.floorIfNeeded(rawAmount);
        if (amount <= 0D) return false;

        double minAmount = currency.getMinTransferAmount();
        if (minAmount > 0D && amount < minAmount) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_TOO_LOW, sender, builder -> builder
                .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(minAmount))
            );
            return false;
        }

        if (!targetUser.getSettings(currency).isPaymentsEnabled()) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NO_PAYMENTS, sender, builder -> builder
                .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
            );
            return false;
        }

        TaxBreakdown breakdown = this.calculate(sender, currency, amount);

        CoinsUser fromUser = this.userManager.getOrFetch(sender);
        if (breakdown.total() > fromUser.getBalance(currency)) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NOT_ENOUGH, sender);
            return false;
        }

        UUID senderId = sender.getUniqueId();
        PendingTransfer transfer = PendingTransfer.create(senderId, targetUser.getId(), currency.getId(), breakdown);

        // Written synchronously: MenuWallet dispatches /confirm one tick after /pay and must
        // never race against this.
        this.pending.put(senderId, transfer);
        if (targetUser.player().isEmpty()) {
            this.offlineTargets.put(senderId, targetUser);
        }
        else {
            this.offlineTargets.remove(senderId);
        }

        this.sendDetails(sender, targetUser, currency, breakdown);

        return true;
    }

    private void sendDetails(@NonNull Player sender, @NonNull CoinsUser targetUser,
                             @NonNull ExcellentCurrency currency, @NonNull TaxBreakdown breakdown) {
        Lang.TAX_CONFIRM_DETAILS.message().sendWith(sender, builder -> builder
            .with(currency.placeholders())
            .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
            .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(breakdown.amount()))
            .with(EconomyPlaceholders.GENERIC_RATE, () -> TaxRates.formatPercent(breakdown.rate()))
            .with(EconomyPlaceholders.GENERIC_TAX, () -> currency.format(breakdown.tax()))
            .with(EconomyPlaceholders.GENERIC_TOTAL, () -> currency.format(breakdown.total()))
            .with(CommonPlaceholders.GENERIC_VALUE, () -> "/" + this.config.getConfirmAliases()[0])
        );
    }

    /**
     * Executes the caller's staged transfer.
     *
     * <p>Every exit point sends a message. Downstream plugins (MenuWallet) dispatch this
     * command as the player and parse nothing, but operators and players always need to know
     * whether the money moved, so silence is never an option.
     */
    public boolean confirm(@NonNull Player sender) {
        UUID senderId = sender.getUniqueId();
        // Claimed by removing, not by get-then-drop: the async purge sweep touches this map
        // too, and a retry racing a slow validation would otherwise reach a second withdrawal.
        PendingTransfer transfer = this.pending.remove(senderId);
        if (transfer == null) {
            Lang.TAX_CONFIRM_ERROR_NONE.message().send(sender);
            return false;
        }

        if (transfer.isExpired(this.config.getTimeoutSeconds())) {
            this.release(transfer);
            Lang.TAX_CONFIRM_ERROR_EXPIRED.message().send(sender);
            return false;
        }

        if (!this.currencyManager.canPerformOperations()) {
            Lang.CURRENCY_OPERATION_DISABLED.message().send(sender);
            return false;
        }

        ExcellentCurrency currency = this.currencyRegistry.getById(transfer.currencyId());
        if (currency == null) {
            // The currency was unregistered while the transfer sat pending.
            this.release(transfer);
            Lang.COMMAND_SYNTAX_INVALID_CURRENCY.message().sendWith(sender, builder -> builder
                .with(CommonPlaceholders.GENERIC_INPUT, transfer::currencyId)
            );
            return false;
        }

        CoinsUser targetUser = this.resolveTarget(transfer);
        if (targetUser == null) {
            this.release(transfer);
            Lang.TAX_CONFIRM_ERROR_TARGET_INVALID.message().send(sender);
            return false;
        }

        if (targetUser.isHolder(sender)) {
            this.release(transfer);
            currency.sendPrefixed(CoreLang.COMMAND_EXECUTION_NOT_YOURSELF, sender);
            return false;
        }

        if (!targetUser.getSettings(currency).isPaymentsEnabled()) {
            this.release(transfer);
            currency.sendPrefixed(Lang.TAX_CONFIRM_ERROR_NO_PAYMENTS, sender, builder -> builder
                .with(currency.placeholders())
                .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
            );
            return false;
        }

        CoinsUser fromUser = this.userManager.getOrFetch(sender);
        if (transfer.total() > fromUser.getBalance(currency)) {
            this.release(transfer);
            currency.sendPrefixed(Lang.TAX_CONFIRM_ERROR_NOT_ENOUGH, sender, builder -> builder
                .with(currency.placeholders())
                .with(EconomyPlaceholders.GENERIC_TOTAL, () -> currency.format(transfer.total()))
            );
            return false;
        }

        // The rate may have drifted between /pay and /confirm (permission or balance changed).
        // Reject instead of silently charging an amount the player never agreed to.
        TaxBreakdown current = this.calculate(sender, currency, transfer.amount());
        if (Math.abs(current.tax() - transfer.tax()) > TAX_EPSILON) {
            this.release(transfer);
            currency.sendPrefixed(Lang.TAX_CONFIRM_ERROR_RATE_CHANGED, sender);
            return false;
        }

        // Dropped before any balance is touched: a second /confirm (double click) can only
        // ever land on the "no pending transfer" branch, never on a second withdrawal.
        this.release(transfer);

        if (!this.execute(sender, fromUser, targetUser, currency, transfer)) {
            Lang.TAX_CONFIRM_ERROR_BLOCKED.message().send(sender);
            return false;
        }

        currency.sendPrefixed(Lang.TAX_CONFIRM_DONE_SENDER, sender, builder -> builder
            .with(currency.placeholders())
            .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(transfer.amount()))
            .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(fromUser.getBalance(currency)))
            .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
        );

        if (transfer.tax() > 0D) {
            currency.sendPrefixed(Lang.TAX_CONFIRM_DONE_TAX, sender, builder -> builder
                .with(currency.placeholders())
                .with(EconomyPlaceholders.GENERIC_TAX, () -> currency.format(transfer.tax()))
                .with(EconomyPlaceholders.GENERIC_RATE, () -> TaxRates.formatPercent(transfer.rate()))
                .with(EconomyPlaceholders.GENERIC_TOTAL, () -> currency.format(transfer.total()))
            );
        }

        targetUser.player().ifPresent(target -> {
            currency.sendPrefixed(Lang.TAX_CONFIRM_NOTIFY, target, builder -> builder
                .with(currency.placeholders())
                .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(transfer.amount()))
                .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(targetUser.getBalance(currency)))
                .with(CommonPlaceholders.PLAYER.resolver(sender))
            );
        });

        return true;
    }

    /**
     * Moves the money. The sender pays principal + tax, the receiver gets the principal, and
     * the tax is destroyed.
     *
     * <p>Deduction happens first on purpose: upstream {@code send()} credits the receiver
     * before debiting the sender, so a listener cancelling the withdrawal mints money out of
     * thin air. If the deposit is the part that gets vetoed, the sender is refunded so no
     * money is burned by a rollback.
     */
    private boolean execute(@NonNull Player sender, @NonNull CoinsUser fromUser, @NonNull CoinsUser targetUser,
                            @NonNull ExcellentCurrency currency, @NonNull PendingTransfer transfer) {
        double senderBefore = fromUser.getBalance(currency);

        fromUser.removeBalance(currency, transfer.total());
        fromUser.markDirty();

        // Equality, not ">=". editBalance() restores the exact previous double when
        // ChangeBalanceEvent is cancelled, so an unchanged balance IS the veto signal. A ">="
        // test would also fire when a listener topped the sender up mid-event - that is not a
        // veto, and bailing out there would drop the debit on the floor with no refund.
        if (fromUser.getBalance(currency) == senderBefore) return false;

        double targetBefore = targetUser.getBalance(currency);

        targetUser.addBalance(currency, transfer.amount());
        targetUser.markDirty();

        // Same reasoning: only an untouched balance means the deposit was vetoed. Refunding
        // on a "<=" test would hand the sender their money back while the receiver keeps the
        // deposit, minting the principal out of thin air.
        if (targetUser.getBalance(currency) == targetBefore) {
            fromUser.addBalance(currency, transfer.total());
            fromUser.markDirty();
            return false;
        }

        this.currencyManager.logTransfer(sender, currency, targetUser, transfer);

        return true;
    }

    @Nullable
    private CoinsUser resolveTarget(@NonNull PendingTransfer transfer) {
        Player online = Bukkit.getPlayer(transfer.targetId());
        if (online != null) return this.userManager.getOrFetch(online);

        return this.offlineTargets.get(transfer.senderId());
    }

    /**
     * Frees the offline-target cache once {@link #confirm(Player)} has claimed the entry from
     * {@code pending}. The pending map is cleaned by the claim itself, not here.
     */
    private void release(@NonNull PendingTransfer transfer) {
        this.offlineTargets.remove(transfer.senderId());
    }

    private void purgeExpired() {
        long timeout = this.config.getTimeoutSeconds();

        this.pending.entrySet().removeIf(entry -> {
            if (!entry.getValue().isExpired(timeout)) return false;

            this.offlineTargets.remove(entry.getKey());
            return true;
        });
    }
}
