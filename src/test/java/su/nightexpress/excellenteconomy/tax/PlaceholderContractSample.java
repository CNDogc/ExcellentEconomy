package su.nightexpress.excellenteconomy.tax;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.tax.placeholder.TransferTaxPlaceholders;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;
import su.nightexpress.nightcore.bridge.placeholder.PlaceholderRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Regression check for the two cross-plugin placeholders.
 *
 * <p>Runs the real {@code tax.yml} -> {@link TaxConfig} -> {@link TaxRates} ->
 * {@link TransferTaxPlaceholders} -> {@link PlaceholderRegistry} pipeline and asserts every
 * resolved value against an expectation, so a change to the tax math turns the build red
 * instead of quietly printing different numbers.
 *
 * <p>No Minecraft server is involved: {@link Player} and {@link ExcellentCurrency} are JDK
 * dynamic proxies, and the economy profile lookup is injected.
 *
 * <p>Run with: {@code java ... PlaceholderContractSample [path/to/tax.yml]}
 * <br>Exit code 0 = all checks pass, 1 = at least one mismatch.
 */
public final class PlaceholderContractSample {

    /**
     * Non-decimal currency: amounts are floored, tax is rounded to whole units.
     */
    private static final String COINS = "coins";

    /**
     * Decimal currency with an underscore in its id - proves the payload is split on the LAST
     * underscore and not the first.
     */
    private static final String MYSTERY_COINS = "mystery_coins";

    private static int checks;
    private static int failures;

    /**
     * Set by tests to play the role of a Bukkit listener: the stub plugin manager hands every
     * fired event to it. Always clear it in a {@code finally} block.
     */
    @Nullable
    private static Consumer<Event> eventHook;

    public static void main(String[] args) throws Exception {
        installBukkitStub();

        Path taxFile = Path.of(args.length > 0 ? args[0] : "samples/tax.yml");

        Fixture sample = fixtureFromFile("samples/tax.yml", taxFile,
            Map.of("Steve", 500D, "Rich", 500_000D, "Vip", 500D));

        System.out.println("=== " + taxFile.toAbsolutePath().normalize() + " ===");
        System.out.println("enabled=" + sample.config().isEnabled()
            + "  baseRate=" + sample.config().getBaseRate()
            + "  rounding=" + sample.config().getRounding()
            + "  combination=" + sample.config().getCombination()
            + "  minTax=" + sample.config().getMinTaxAmount()
            + "  maxRate=" + sample.config().getMaxRate());
        System.out.println("permission tiers: " + sample.config().getPermissionTiers());
        System.out.println("wealth tiers:     " + sample.config().getWealthTiers());
        System.out.println();

        sampleMatchesDefaults(taxFile);

        sampleRates(sample);
        sampleAmounts(sample);
        sampleMalformedPayloads(sample);

        clampAndCombination();
        roundingModes();
        configHardening();
        nonFiniteAmounts(sample);
        balanceEditInvariants();
        messageKeyPaths();
        shippedDefaultIsDisabled(taxFile);

        System.out.println();
        System.out.println(failures == 0
            ? "OK - " + checks + " checks passed."
            : "FAILED - " + failures + " of " + checks + " checks did not match.");

        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------
    // Cases
    // ---------------------------------------------------------------------------------

    /**
     * Guards the documentation. {@code samples/tax.yml} is what the README shows and what the
     * regression numbers were measured against, so every value {@code writeDefaults} emits must
     * still appear in it. If a default changes and the sample does not, this fails.
     */
    private static void sampleMatchesDefaults(@NonNull Path taxFile) throws Exception {
        System.out.println("--- samples/tax.yml matches generated defaults ---");

        YamlConfiguration sample = new YamlConfiguration();
        sample.load(taxFile.toFile());

        // An empty config, so every writeDefaults branch fires.
        YamlConfiguration generated = new YamlConfiguration();
        new TaxConfig().writeDefaults(generated);

        for (Map.Entry<String, Object> entry : new TreeMap<>(generated.getValues(true)).entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();

            // getValues(deep) also yields the intermediate section nodes. MemorySection does not
            // implement equals, so comparing those would always fail - only leaves are meaningful.
            if (expected instanceof ConfigurationSection) continue;

            Object actual = sample.get(key);

            // YAML hands back Integer for whole numbers and Double for "1D", so compare
            // numerically rather than by type - 1 and 1.0 are the same setting.
            boolean ok = expected instanceof Number expectedNumber && actual instanceof Number actualNumber
                ? Double.compare(expectedNumber.doubleValue(), actualNumber.doubleValue()) == 0
                : Objects.equals(expected, actual);

            checks++;
            if (!ok) failures++;

            System.out.printf("%-6s %-40s -> %-14s want %s%n",
                ok ? "PASS" : "FAIL", key, String.valueOf(actual), String.valueOf(expected));
        }
        System.out.println();
    }

    /**
     * The feature must ship switched off. Dropping this jar onto a stock server has to behave
     * exactly like upstream - otherwise an admin who just wanted the bugfixes starts charging
     * their players 5% without ever opening tax.yml.
     */
    private static void shippedDefaultIsDisabled(@NonNull Path taxFile) throws Exception {
        System.out.println("--- shipped default is disabled ---");

        Fixture shipped = fixtureFromFile("samples/tax.yml", taxFile,
            Map.of("Steve", 500D, "Rich", 500_000D), false);

        check(shipped, "Steve as shipped", player("Steve"), COINS, "0");
        check(shipped, "Rich as shipped", player("Rich"), COINS, "0");
        System.out.println();
    }

    private static void sampleRates(@NonNull Fixture fixture) {
        System.out.println("--- transfer_tax_rate ---");

        // A player holding the exempt permission is charged nothing at all.
        check(fixture, "Steve (no tiers)", player("Steve"), COINS, "0.05");
        check(fixture, "Rich  (wealth tier)", player("Rich"), COINS, "0.07");
        check(fixture, "Vip   (exempt)", player("Vip", Perms.TAX_EXEMPT.getName()), COINS, "0");
        check(fixture, "null  (no player context)", null, COINS, "0.05");

        // The rate is a property of the player, not of the currency being sent.
        check(fixture, "Rich  (decimal currency)", player("Rich"), MYSTERY_COINS, "0.07");
        System.out.println();
    }

    private static void sampleAmounts(@NonNull Fixture fixture) {
        System.out.println("--- transfer_tax_amount ---");

        checkAmount(fixture, "Steve", player("Steve"), COINS, 1000, "50");
        checkAmount(fixture, "Rich", player("Rich"), COINS, 1000, "70");
        checkAmount(fixture, "Vip", player("Vip", Perms.TAX_EXEMPT.getName()), COINS, 1000, "0");

        // The Min_Tax_Amount floor: 10 * 0.05 = 0.5, which would round to 0 and hand players a
        // tax-free loophole for splitting a payment into small chunks.
        checkAmount(fixture, "Steve", player("Steve"), COINS, 10, "1");

        // Decimal currency keeps two places: 33.33 * 0.05 = 1.6665 -> 1.67.
        checkAmount(fixture, "Steve", player("Steve"), MYSTERY_COINS, 33.33, "1.67");
        System.out.println();
    }

    /**
     * Downstream UIs build these payloads from user input, so a bad one must degrade to {@code "0"}
     * rather than throwing or leaking an exception message into chat.
     */
    private static void sampleMalformedPayloads(@NonNull Fixture fixture) {
        System.out.println("--- malformed payloads ---");
        Player steve = player("Steve");

        // Payloads must stay prefixed with the placeholder name: the registry matches on the
        // longest registered prefix and hands the rest to the handler as the payload.
        checkAmount(fixture, "unknown currency", steve, "nope", 1000, "0");
        checkAmount(fixture, "zero amount", steve, COINS, 0, "0");
        checkAmount(fixture, "negative amount", steve, COINS, -1000, "0");
        checkRaw(fixture, "non-numeric amount", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT + "_" + COINS + "_abc", "0");

        // Handler receives a payload with no underscore at all -> cannot split currency from
        // amount -> "0" rather than a crash.
        checkRaw(fixture, "payload without underscore", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT + "_" + COINS, "0");

        // Bare placeholder name with no payload. PlaceholderAPI never sends this, but a
        // downstream plugin assembling the string by hand might.
        checkRaw(fixture, "bare name, no payload", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT, "0");
        checkRaw(fixture, "bare rate name, no payload", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_RATE, "0");
        System.out.println();
    }

    /**
     * {@code Max_Rate} is applied after tiers combine. With {@code ADD}, a permission tier and a
     * wealth tier stack to 0.70 - past the 0.5 ceiling, so the rate must be clamped.
     */
    private static void clampAndCombination() {
        System.out.println("--- Combination=ADD and Max_Rate clamping ---");

        String yaml = """
            Enabled: true
            Base_Rate: 0.05
            Min_Tax_Amount: 0
            Max_Rate: 0.5
            Rounding: UP
            Combination: ADD
            Permission_Tiers:
              0:
                permission: excellenteconomy.tax.rate.member
                rate: 0.30
            Wealth_Tiers:
              0:
                min_balance: 100000
                rate: 0.40
            """;

        Fixture clamped = fixtureFromString("clamped", yaml, Map.of("Steve", 500D, "Rich", 500_000D));

        // Plain player holds no tier: base rate only.
        check(clamped, "Steve (no tier)", player("Steve"), COINS, "0.05");
        // Both tiers stack to 0.70 and are then clamped down to Max_Rate.
        check(clamped, "Rich  (0.30 + 0.40, clamped)", player("Rich", "excellenteconomy.tax.rate.member"),
            COINS, "0.5");

        // Same setup with the ceiling lifted: the stacking is real, not an artefact of the clamp.
        Fixture unclamped = fixtureFromString("unclamped", yaml.replace("Max_Rate: 0.5", "Max_Rate: 1.0"),
            Map.of("Steve", 500D, "Rich", 500_000D));

        check(unclamped, "Rich  (0.30 + 0.40, no clamp)", player("Rich", "excellenteconomy.tax.rate.member"),
            COINS, "0.7");

        // FIRST_MATCH ignores the wealth tier entirely.
        Fixture firstMatch = fixtureFromString("first-match", yaml.replace("Combination: ADD",
            "Combination: FIRST_MATCH"), Map.of("Rich", 500_000D));

        check(firstMatch, "Rich  (permission wins)", player("Rich", "excellenteconomy.tax.rate.member"),
            COINS, "0.3");
        System.out.println();
    }

    private static void roundingModes() {
        System.out.println("--- Rounding and the Min_Tax_Amount floor ---");

        String yaml = """
            Enabled: true
            Base_Rate: 0.05
            Min_Tax_Amount: 1
            Max_Rate: 0.5
            Rounding: DOWN
            Combination: MAX
            """;

        // 10 * 0.05 = 0.5, floored to 0 - but the floor keeps it at 1, so tiny transfers
        // cannot dodge the tax even with Rounding: DOWN.
        Fixture floored = fixtureFromString("down+floor", yaml, Map.of("Steve", 500D));
        checkAmount(floored, "Steve", player("Steve"), COINS, 10, "1");

        // With the floor removed, rounding down really does produce a zero tax.
        Fixture noFloor = fixtureFromString("down+nofloor", yaml.replace("Min_Tax_Amount: 1",
            "Min_Tax_Amount: 0"), Map.of("Steve", 500D));
        checkAmount(noFloor, "Steve", player("Steve"), COINS, 10, "0");

        // Decimal currency, rounding down: 33.33 * 0.05 = 1.6665 -> 1.66.
        checkAmount(noFloor, "Steve", player("Steve"), MYSTERY_COINS, 33.33, "1.66");
        System.out.println();
    }

    /**
     * Server owners hand-edit tax.yml. A bad value must degrade to something sane rather than
     * produce a negative rate, a NaN placeholder or a crash on reload.
     */
    private static void configHardening() {
        System.out.println("--- malformed tax.yml values ---");

        Map<String, Double> balances = Map.of("Steve", 500D);

        // Negative numbers in the config are clamped to zero, never applied as-is.
        Fixture negative = fixtureFromString("negative", """
            Enabled: true
            Base_Rate: -5
            Fixed_Amount: -100
            Min_Tax_Amount: -50
            Max_Rate: -1
            Rounding: UP
            Combination: MAX
            """, balances);

        expect("Base_Rate clamped to 0", negative.config().getBaseRate(), "0.0");
        expect("Fixed_Amount clamped to 0", negative.config().getFixedAmount(), "0.0");
        expect("Min_Tax_Amount clamped to 0", negative.config().getMinTaxAmount(), "0.0");
        check(negative, "negative config -> no tax", player("Steve"), COINS, "0");

        // Unrecognised enum values fall back instead of throwing on reload.
        Fixture junkEnums = fixtureFromString("junk-enums", """
            Enabled: true
            Base_Rate: 0.05
            Min_Tax_Amount: 0
            Max_Rate: 0.5
            Rounding: SIDEWAYS
            Combination: MULTIPLY
            """, balances);

        expect("Rounding falls back to UP", String.valueOf(junkEnums.config().getRounding()), "UP");
        expect("Combination falls back to MAX", String.valueOf(junkEnums.config().getCombination()), "MAX");
        checkAmount(junkEnums, "Steve", player("Steve"), COINS, 1000, "50");

        // A tier with a negative threshold or rate is skipped, not applied.
        Fixture badTier = fixtureFromString("bad-tier", """
            Enabled: true
            Base_Rate: 0.05
            Min_Tax_Amount: 0
            Max_Rate: 0.5
            Rounding: UP
            Combination: MAX
            Permission_Tiers:
              0:
                permission: ""
                rate: 0.02
            Wealth_Tiers:
              0:
                min_balance: -1
                rate: 0.99
            """, balances);

        expect("blank permission tier skipped", String.valueOf(badTier.config().getPermissionTiers().size()), "0");
        expect("negative wealth tier skipped", String.valueOf(badTier.config().getWealthTiers().size()), "0");
        check(badTier, "bad tiers -> base rate", player("Steve"), COINS, "0.05");

        // A zero ceiling means "no tax", which is a legitimate way to disable it.
        Fixture zeroCeiling = fixtureFromString("zero-ceiling", """
            Enabled: true
            Base_Rate: 0.05
            Min_Tax_Amount: 0
            Max_Rate: 0
            Rounding: UP
            Combination: MAX
            """, balances);

        check(zeroCeiling, "Max_Rate 0 -> no tax", player("Steve"), COINS, "0");
        checkAmount(zeroCeiling, "Steve", player("Steve"), COINS, 1000, "0");

        // Master switch off: placeholders must report zero, since /pay skips tax entirely.
        Fixture disabled = fixtureFromString("disabled", """
            Enabled: false
            Base_Rate: 0.05
            Min_Tax_Amount: 1
            Max_Rate: 0.5
            Rounding: UP
            Combination: MAX
            """, balances);

        check(disabled, "Enabled false -> rate 0", player("Steve"), COINS, "0");
        checkAmount(disabled, "Steve", player("Steve"), COINS, 1000, "0");
        System.out.println();
    }

    /**
     * NaN and Infinity both compare false against {@code <= 0}, so a plain positivity check
     * waves them through. {@code TaxRates#calculate} collapses them into a zero-amount
     * breakdown, which is why {@code TransferTaxManager#startTransfer} has to reject them with
     * {@code Double.isFinite} before any pending transfer is staged.
     */
    private static void nonFiniteAmounts(@NonNull Fixture fixture) {
        System.out.println("--- non-finite amounts ---");

        Player steve = player("Steve");
        ExcellentCurrency coins = fixture.registry().getById(COINS);

        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            TaxBreakdown breakdown = fixture.rates().calculate(steve, coins, amount);

            expect("amount " + amount + " -> zero principal", breakdown.amount(), "0.0");
            expect("amount " + amount + " -> zero total", breakdown.total(), "0.0");
            expectTrue("amount " + amount + " -> no tax", !breakdown.hasTax());
        }

        // And the degenerate input never escapes as a non-finite number either way.
        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1D, 0D}) {
            TaxBreakdown breakdown = fixture.rates().calculate(steve, coins, amount);
            expectTrue("total stays finite for " + amount, Double.isFinite(breakdown.total()));
        }

        // The placeholder path rejects them too: parseAmount refuses anything non-finite.
        checkRaw(fixture, "NaN payload", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT + "_" + COINS + "_NaN", "0");
        checkRaw(fixture, "Infinity payload", steve,
            TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT + "_" + COINS + "_Infinity", "0");
        System.out.println();
    }

    /**
     * Pins the invariants {@code TransferTaxManager#execute} relies on to decide whether a
     * balance change was vetoed. That method tests {@code balance == before} rather than
     * {@code >=} / {@code <=}, which is only sound if a cancelled {@link ChangeBalanceEvent}
     * restores the <b>exact</b> previous double - not merely an equal one.
     */
    private static void balanceEditInvariants() {
        System.out.println("--- balance edit and ChangeBalanceEvent veto ---");

        ExcellentCurrency coins = currency(COINS, false);
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.add(coins);

        // 1. Plain debit: the balance moves by exactly the amount removed.
        CoinsUser payer = user("Payer", 1000D, registry);
        payer.removeBalance(coins, 250D);
        expect("plain debit", payer.getBalance(coins), "750.0");

        // 2. A vetoed debit is restored bit-for-bit, so `==` is a sound veto test.
        eventHook = event -> {
            if (event instanceof ChangeBalanceEvent balanceEvent) balanceEvent.setCancelled(true);
        };
        try {
            CoinsUser vetoed = user("Vetoed", 1000D, registry);
            vetoed.removeBalance(coins, 250D);
            expect("vetoed debit restored", vetoed.getBalance(coins), "1000.0");
            expectTrue("vetoed debit is bit-exact",
                Double.doubleToLongBits(vetoed.getBalance(coins)) == Double.doubleToLongBits(1000D));
        }
        finally {
            eventHook = null;
        }

        // 3. A listener that tops the payer up mid-event leaves the balance ABOVE the starting
        // value. That is not a veto: bailing out there would drop the debit with no refund.
        boolean[] applied = {false};
        eventHook = event -> {
            if (event instanceof ChangeBalanceEvent balanceEvent && !applied[0]) {
                applied[0] = true;
                balanceEvent.getUser().addBalance(coins, 300D);
            }
        };
        try {
            CoinsUser topped = user("ToppedUp", 1000D, registry);
            topped.removeBalance(coins, 250D);

            // 1000 - 250 + 300 = 1050. Not equal to the starting 1000, so the debit stands.
            expect("top-up mid-event is not a veto", topped.getBalance(coins), "1050.0");
            expectTrue("top-up leaves balance above start", topped.getBalance(coins) > 1000D);
        }
        finally {
            eventHook = null;
        }
        System.out.println();
    }

    /**
     * Guards the shape of the shipped language file, not just its contents.
     *
     * <p>Inserting a key at a shallower indentation silently re-parents every following
     * deeper-indented block, and {@code git diff} shows those lines as unchanged because their
     * text is identical - only their parent moved. The result is a translation that resolves to
     * nothing and falls back to English with no log entry, invisible in review. So the paths are
     * asserted to resolve where the code reads them, and the mis-parented paths are asserted
     * NOT to exist.
     */
    private static void messageKeyPaths() {
        System.out.println("--- messages_cn.yml key paths ---");

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
            Path.of("src/main/resources/lang/messages_cn.yml").toFile());

        // Reachable from TransferTaxManager. Every one of these must resolve or a Chinese
        // player sees English mid-transfer.
        for (String path : List.of(
            "Command.Confirm.Desc",
            "Tax.Confirm.Details",
            "Tax.Confirm.Done.Sender",
            "Tax.Confirm.Done.Tax",
            "Tax.Confirm.Done.Notify",
            "Tax.Confirm.Error.None",
            "Tax.Confirm.Error.Expired",
            "Tax.Confirm.Error.NotEnough",
            "Tax.Confirm.Error.RateChanged",
            "Tax.Confirm.Error.NoPayments",
            "Tax.Confirm.Error.TargetInvalid",
            "Tax.Confirm.Error.Blocked"
        )) {
            expectTrue(path + " resolves", yaml.contains(path));
        }

        // These belong to Command.Currency, not Command.Confirm. Adding "Confirm:" at two-space
        // indentation once swallowed all three, silently untranslating the payments toggle,
        // currency exchange and balance leaderboard.
        for (String path : List.of(
            "Command.Currency.Payments.Desc",
            "Command.Currency.Payments.Toggle",
            "Command.Currency.Payments.Target",
            "Command.Currency.Exchange.Desc",
            "Command.Currency.Top.Desc",
            "Command.Currency.Top.List",
            "Command.Currency.Top.Entry"
        )) {
            expectTrue(path + " still under Command.Currency", yaml.contains(path));
        }

        for (String path : List.of(
            "Command.Confirm.Payments",
            "Command.Confirm.Exchange",
            "Command.Confirm.Top"
        )) {
            expectTrue(path + " not re-parented under Confirm", !yaml.contains(path));
        }
        System.out.println();
    }

    // ---------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------

    private static void check(@NonNull Fixture fixture, @NonNull String label, @Nullable Player player,
                              @NonNull String currencyId, @NonNull String expected) {
        checkRaw(fixture, label, player, TransferTaxPlaceholders.TRANSFER_TAX_RATE + "_" + currencyId, expected);
    }

    private static void checkAmount(@NonNull Fixture fixture, @NonNull String label, @Nullable Player player,
                                    @NonNull String currencyId, double amount, @NonNull String expected) {
        checkRaw(fixture, label, player, amount(currencyId, amount), expected);
    }

    private static void checkRaw(@NonNull Fixture fixture, @NonNull String label, @Nullable Player player,
                                 @NonNull String payload, @NonNull String expected) {
        String actual = fixture.resolve(player, payload);

        checks++;
        boolean ok = expected.equals(actual);
        if (!ok) failures++;

        System.out.printf("%-6s %-34s %-40s -> %-9s want %s%n",
            ok ? "PASS" : "FAIL", label, payload, quote(actual), quote(expected));
    }

    private static void expect(@NonNull String label, double actual, @NonNull String expected) {
        expect(label, String.valueOf(actual), expected);
    }

    private static void expectTrue(@NonNull String label, boolean condition) {
        expect(label, String.valueOf(condition), "true");
    }

    private static void expect(@NonNull String label, @NonNull String actual, @NonNull String expected) {
        checks++;
        boolean ok = expected.equals(actual);
        if (!ok) failures++;

        System.out.printf("%-6s %-42s -> %-12s want %s%n",
            ok ? "PASS" : "FAIL", label, actual, expected);
    }

    @NonNull
    private static String amount(@NonNull String currencyId, double amount) {
        return TransferTaxPlaceholders.TRANSFER_TAX_AMOUNT + "_" + currencyId + "_" + amount;
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private record Fixture(String name, TaxConfig config, TaxRates rates, CurrencyRegistry registry,
                           PlaceholderRegistry placeholders) {

        @NonNull
        String resolve(@Nullable Player player, @NonNull String payload) {
            String value = this.placeholders.onPlaceholderRequest(player, payload);
            return value == null ? "<null>" : value;
        }
    }

    @NonNull
    private static Fixture fixtureFromFile(@NonNull String name, @NonNull Path file,
                                           @NonNull Map<String, Double> balances) throws Exception {
        return fixtureFromFile(name, file, balances, true);
    }

    private static Fixture fixtureFromFile(@NonNull String name, @NonNull Path file,
                                           @NonNull Map<String, Double> balances,
                                           boolean enabled) throws Exception {
        // A plain Bukkit YamlConfiguration, not nightcore's FileConfig: the latter needs a
        // running NightCore plugin to have registered its codecs.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());

        // samples/tax.yml ships disabled, matching what the plugin generates on a fresh
        // install - but every rate and amount expectation below describes the tax maths,
        // which only runs once the feature is switched on.
        yaml.set("Enabled", enabled);

        return fixture(name, yaml, balances);
    }

    @NonNull
    private static Fixture fixtureFromString(@NonNull String name, @NonNull String yamlText,
                                             @NonNull Map<String, Double> balances) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Bad inline YAML for fixture '" + name + "'", exception);
        }

        return fixture(name, yaml, balances);
    }

    @NonNull
    private static Fixture fixture(@NonNull String name, @NonNull YamlConfiguration yaml,
                                   @NonNull Map<String, Double> balances) {
        TaxConfig config = new TaxConfig();
        config.writeDefaults(yaml);
        config.load(yaml);

        CurrencyRegistry registry = new CurrencyRegistry();
        registry.add(currency(COINS, false));
        registry.add(currency(MYSTERY_COINS, true));

        Function<Player, CoinsUser> lookup = player -> user(player.getName(),
            balances.getOrDefault(player.getName(), 0D), registry);

        TaxRates rates = new TaxRates(config, lookup);

        PlaceholderRegistry placeholders = new PlaceholderRegistry();
        new TransferTaxPlaceholders(registry, rates).addPlaceholders(placeholders);

        return new Fixture(name, config, rates, registry, placeholders);
    }

    @NonNull
    private static Player player(@NonNull String name, @NonNull String... permissions) {
        Set<String> granted = Set.of(permissions);

        return (Player) Proxy.newProxyInstance(PlaceholderContractSample.class.getClassLoader(),
            new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getUniqueId" -> UUID.nameUUIDFromBytes(name.getBytes());
                case "hasPermission" -> args[0] instanceof org.bukkit.permissions.Permission permission
                    ? granted.contains(permission.getName())
                    : granted.contains(String.valueOf(args[0]));
                case "toString" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    @NonNull
    private static ExcellentCurrency currency(@NonNull String id, boolean decimal) {
        return (ExcellentCurrency) Proxy.newProxyInstance(PlaceholderContractSample.class.getClassLoader(),
            new Class[]{ExcellentCurrency.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "isDecimal" -> decimal;
                case "floorIfNeeded" -> decimal ? (double) args[0] : Math.floor((double) args[0]);
                // UserBalance#set funnels through this, so it has to behave or balances read as 0.
                case "floorAndLimit" -> decimal ? (double) args[0] : Math.floor((double) args[0]);
                case "format" -> String.valueOf(args[0]);
                case "toString" -> id;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    @NonNull
    private static CoinsUser user(@NonNull String name, double balance, @NonNull CurrencyRegistry registry) {
        UserBalance userBalance = new UserBalance();
        registry.forEach(currency -> userBalance.set(currency, balance));

        return new CoinsUser(UUID.nameUUIDFromBytes(name.getBytes()), name, userBalance,
            new LinkedHashMap<>(), System.currentTimeMillis(), false);
    }

    /**
     * {@code Perms} builds real {@link org.bukkit.permissions.Permission} objects at class-init time,
     * and those reach for {@code Bukkit.getServer()} to recalculate permissibles. A stub server
     * with an empty permission graph is enough to let the class initialise.
     */
    private static void installBukkitStub() throws ReflectiveOperationException {
        Object pluginManager = Proxy.newProxyInstance(PlaceholderContractSample.class.getClassLoader(),
            new Class[]{PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getPermission" -> null;
                case "getDefaultPermissions" -> Set.of();
                case "getPermissions" -> Set.of();
                case "callEvent" -> {
                    // Stands in for the listener pipeline. callEvent() is void, so nothing to return.
                    if (eventHook != null && args != null && args.length > 0 && args[0] instanceof Event event) {
                        eventHook.accept(event);
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });

        Object server = Proxy.newProxyInstance(PlaceholderContractSample.class.getClassLoader(),
            new Class[]{Server.class}, (proxy, method, args) -> "getPluginManager".equals(method.getName())
                ? pluginManager : defaultValue(method.getReturnType()));

        // Bukkit.setServer() would also print a version banner, which needs Paper's build
        // metadata and blows up outside a real server. Set the field directly instead.
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    @Nullable
    private static Object defaultValue(@NonNull Class<?> type) {
        if (type == void.class) return null;
        if (Set.class.isAssignableFrom(type)) return Set.of();
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    @NonNull
    private static String quote(@Nullable String value) {
        return value == null ? "<null>" : '"' + value + '"';
    }
}
