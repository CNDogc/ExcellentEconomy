package su.nightexpress.excellenteconomy.currency.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.command.CommandArguments;
import su.nightexpress.excellenteconomy.command.currency.CurrencyCommand;
import su.nightexpress.excellenteconomy.config.Lang;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.tax.TransferTaxManager;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserManager;
import su.nightexpress.nightcore.commands.Arguments;
import su.nightexpress.nightcore.commands.builder.LiteralNodeBuilder;
import su.nightexpress.nightcore.commands.context.CommandContext;
import su.nightexpress.nightcore.commands.context.ParsedArguments;
import su.nightexpress.nightcore.core.config.CoreLang;

public class PayCommand implements CurrencyCommand {

    private final CurrencyManager manager;
    private final UserManager     userManager;

    public PayCommand(@NonNull CurrencyManager manager, @NonNull UserManager userManager) {
        this.manager = manager;
        this.userManager = userManager;
    }

    @Override
    public boolean isFallback() {
        return false;
    }

    @Override
    public void build(@NonNull LiteralNodeBuilder builder, @NonNull ExcellentCurrency currency) {
        builder
            .playerOnly()
            .permission(Perms.COMMAND_CURRENCY_SEND)
            .description(Lang.COMMAND_CURRENCY_SEND_DESC)
            .withArguments(
                Arguments.playerName(CommandArguments.PLAYER),
                CommandArguments.positiveAmount(currency)
            );
    }

    @Override
    public boolean execute(@NonNull CommandContext context, @NonNull ParsedArguments arguments,
                           @NonNull ExcellentCurrency currency) {
        Player sender = context.getPlayerOrThrow();
        String targetName = arguments.getString(CommandArguments.PLAYER);
        double amount = arguments.getDouble(CommandArguments.AMOUNT);

        TransferTaxManager taxManager = this.manager.getTaxManager();
        if (!taxManager.isEnabled()) {
            this.payDirect(context, sender, targetName, currency, amount);
            return true;
        }

        // Online targets resolve right here, so the pending transfer exists before this command
        // returns. Downstream plugins (MenuWallet) dispatch /confirm one tick after /pay and
        // must never lose that race, which the async lookup below would otherwise cause.
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer != null) {
            taxManager.startTransfer(sender, this.userManager.getOrFetch(targetPlayer), currency, amount);
            return true;
        }

        // Offline target: the pending transfer lands a tick or two later. Only a human ever
        // takes this branch, and they get told the tax before they can confirm anything.
        this.userManager.loadByNameAsync(targetName).thenAccept(opt -> {
            CoinsUser targetUser = opt.orElse(null);
            if (targetUser == null) {
                currency.sendPrefixed(CoreLang.ERROR_INVALID_PLAYER, context.getSender());
                return;
            }

            taxManager.startTransfer(sender, targetUser, currency, amount);
        });

        return true;
    }

    private void payDirect(@NonNull CommandContext context, @NonNull Player sender, @NonNull String targetName,
                           @NonNull ExcellentCurrency currency, double amount) {
        this.userManager.loadByNameAsync(targetName).thenAccept(opt -> {
            CoinsUser targetUser = opt.orElse(null);
            if (targetUser == null) {
                currency.sendPrefixed(CoreLang.ERROR_INVALID_PLAYER, context.getSender());
                return;
            }

            this.manager.send(sender, targetUser, currency, amount);
        });
    }
}
