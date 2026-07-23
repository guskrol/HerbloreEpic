package org.gusta.herblore;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.paint.PaintContext;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

@ScriptManifest(name = "Herblore Cheapest 61", gameType = GameType.OS)
public class HerbloreTrainerScript extends Script {
    private static final String SCRIPT_VERSION = "v0.1.0-cheapest-61";
    private static final int TARGET_LEVEL = 61;
    private static final int RESTOCK_TARGET_ACTIONS = 800;
    private static final int MIN_OUTPUTS_TO_SELL = 300;
    private static final int MIN_COINS_RESERVE = 5_000;
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final Tile GRAND_EXCHANGE_TILE = new Tile(3164, 3487, 0);
    private static final double BUY_MARKUP = 1.12D;
    private static final double SELL_MARKDOWN = 0.98D;
    private static final double GE_TAX_RATE = 0.02D;
    private static final String COINS = "Coins";

    private final Queue<GeAction> pendingGeActions = new ArrayDeque<>();
    private final List<GeAction> placedGeActions = new ArrayList<>();
    private final Pricing pricing = new Pricing();

    private Stats stats;
    private HerbloreMethod activeMethod;
    private Quote activeQuote;
    private long nextGeCollectAt;
    private long nextIdleLogAt;
    private boolean targetBankSweepDone;

    @Override
    public boolean onStart(String... args) {
        stats = new Stats();
        addTask(new HerbloreTask());
        log("Herblore Cheapest 61 " + SCRIPT_VERSION + " started");
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (event == null || event.getMessage() == null || stats == null) {
            return;
        }
        String message = event.getMessage();
        stats.lastChat = message;
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("you do not have enough")
                || lower.contains("not enough")
                || lower.contains("you can't")
                || lower.contains("nothing interesting happens")) {
            log("Game message: " + message);
        }
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        if (paint == null || stats == null) {
            return;
        }
        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 8;
        int width = 350;
        int height = 236;
        paint.fill(new Rectangle(x, y, width, height), new Color(18, 22, 28, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("Herblore Cheapest 61 " + SCRIPT_VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status, 45), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Method: " + (activeMethod == null ? "-" : shortText(activeMethod.label, 35)),
                x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Level: " + herbloreLevel(ctx) + "/" + TARGET_LEVEL
                + " | XP: " + stats.xpGained(ctx)
                + " (" + stats.xpPerHour(ctx) + "/h)", x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        String gpXp = activeQuote == null ? "-" : formatGpPerXp(activeQuote.netCostPerXp) + " gp/xp";
        paint.drawText("Net cost: " + gpXp
                + " | action: " + (activeQuote == null ? "-" : activeQuote.netCostPerAction + " gp"),
                x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Actions: " + stats.actionsCompleted
                + " | GE queued: " + pendingGeActions.size()
                + " | placed: " + placedGeActions.size(),
                x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Last GE: " + shortText(stats.lastGeAction, 45), x + 12, line, new Color(245, 228, 160), 11);
        line += 16;
        paint.drawText("Last chat: " + shortText(stats.lastChat, 45), x + 12, line, new Color(195, 210, 230), 11);
    }

    @Override
    protected void onStop() {
        clearClientInteractionState();
        getLogger().info("Herblore Cheapest 61 " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        clearClientInteractionState();
    }

    private class HerbloreTask implements ScriptTask {
        @Override
        public boolean shouldExecute() {
            return true;
        }

        @Override
        public void run() {
            APIContext ctx = getAPIContext();
            if (ctx == null) {
                Time.sleep(600, 900);
                return;
            }

            stats.startExperienceIfNeeded(ctx);

            if (!pendingGeActions.isEmpty() || !placedGeActions.isEmpty()) {
                handleGrandExchange(ctx);
                return;
            }

            if (ctx.grandExchange().isOpen()) {
                stats.setStatus("Closing GE before Herblore work");
                ctx.grandExchange().close();
                Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
                return;
            }

            if (herbloreLevel(ctx) < 3) {
                stats.setStatus("Druidic Ritual/level 3 Herblore required before training");
                logOccasionally("Herblore is below level 3. Complete Druidic Ritual first.");
                Time.sleep(2500, 4000);
                return;
            }

            if (targetReached(ctx)) {
                handleTargetReached(ctx);
                return;
            }

            if (!selectMethod(ctx)) {
                return;
            }

            if (hasProcessInventory(ctx, activeMethod)) {
                processInventory(ctx, activeMethod);
                return;
            }

            prepareInventoryOrRestock(ctx, activeMethod);
        }
    }

    private boolean selectMethod(APIContext ctx) {
        int level = herbloreLevel(ctx);
        if (activeMethod != null && level >= activeMethod.level) {
            if (activeQuote == null) {
                activeQuote = pricing.quote(ctx, activeMethod);
            }
            return activeQuote != null && activeQuote.hasPrices();
        }

        List<Quote> quotes = new ArrayList<>();
        for (HerbloreMethod method : HerbloreMethod.all()) {
            if (level < method.level || method.level >= TARGET_LEVEL) {
                continue;
            }
            Quote quote = pricing.quote(ctx, method);
            if (quote.hasPrices()) {
                quotes.add(quote);
            }
        }

        Quote best = quotes.stream()
                .min(Comparator
                        .comparingDouble((Quote quote) -> quote.netCostPerXp)
                        .thenComparing(quote -> -quote.method.actionsPerHour))
                .orElse(null);

        if (best == null) {
            activeMethod = null;
            activeQuote = null;
            stats.setStatus("No priced Herblore method found at level " + level);
            logOccasionally("No Herblore method had complete GE prices at level " + level);
            Time.sleep(2000, 3200);
            return false;
        }

        activeMethod = best.method;
        activeQuote = best;
        stats.setStatus("Selected " + activeMethod.label
                + " at " + formatGpPerXp(activeQuote.netCostPerXp) + " gp/xp");
        log("Selected " + activeMethod.label
                + " netCost/action=" + activeQuote.netCostPerAction
                + " netCost/xp=" + formatGpPerXp(activeQuote.netCostPerXp)
                + " buy=" + activeQuote.inputCost
                + " sell=" + activeQuote.outputRevenue);
        return true;
    }

    private void handleTargetReached(APIContext ctx) {
        activeMethod = null;
        activeQuote = null;

        if (!targetBankSweepDone || inventoryHasAnyOutput(ctx)) {
            if (!openBank(ctx, "checking outputs after reaching Herblore " + TARGET_LEVEL)) {
                return;
            }
            if (depositInventoryIfNeeded(ctx, null)) {
                return;
            }
            if (prepareAnyOutputSale(ctx, true)) {
                return;
            }
            targetBankSweepDone = true;
            closeBank(ctx, "Herblore " + TARGET_LEVEL + " reached");
            return;
        }

        stats.setStatus("Target complete: Herblore " + TARGET_LEVEL + " reached");
        Time.sleep(3000, 5000);
    }

    private void prepareInventoryOrRestock(APIContext ctx, HerbloreMethod method) {
        if (!openBank(ctx, "preparing " + method.label)) {
            return;
        }

        if (depositInventoryIfNeeded(ctx, method)) {
            return;
        }

        if (prepareAnyOutputSale(ctx, false)) {
            return;
        }

        if (hasMethodSuppliesAvailable(ctx, method)) {
            prepareProcessInventory(ctx, method);
            return;
        }

        activeMethod = null;
        activeQuote = null;
        if (!selectMethod(ctx)) {
            return;
        }
        if (hasMethodSuppliesAvailable(ctx, activeMethod)) {
            prepareProcessInventory(ctx, activeMethod);
            return;
        }

        planRestock(ctx, activeMethod);
    }

    private boolean depositInventoryIfNeeded(APIContext ctx, HerbloreMethod method) {
        if (ctx.inventory().isEmpty()) {
            return false;
        }

        if (method != null && hasProcessInventory(ctx, method)) {
            return false;
        }

        stats.setStatus("Depositing Herblore inventory");
        ctx.bank().depositInventory();
        Time.sleep(600, 1000);
        return true;
    }

    private boolean prepareAnyOutputSale(APIContext ctx, boolean force) {
        for (HerbloreMethod method : HerbloreMethod.all()) {
            int outputCount = ctx.inventory().getCount(true, method.output)
                    + (ctx.bank().isOpen() ? ctx.bank().getCount(method.output) : 0);
            if (outputCount <= 0) {
                continue;
            }
            if (!force && activeMethod != null && activeMethod.usesInput(method.output)) {
                continue;
            }
            if (!force && outputCount < MIN_OUTPUTS_TO_SELL && hasMethodSuppliesAvailable(ctx, method)) {
                continue;
            }
            prepareOutputSale(ctx, method, outputCount);
            return true;
        }
        return false;
    }

    private void prepareOutputSale(APIContext ctx, HerbloreMethod method, int totalOutput) {
        int inventoryOutput = ctx.inventory().getCount(true, method.output);
        if (inventoryOutput <= 0) {
            stats.setStatus("Withdrawing " + method.output + " as notes to sell");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            if (ctx.bank().withdrawAll(method.output)
                    || ctx.bank().withdraw(Math.max(1, ctx.bank().getCount(method.output)), method.output)) {
                Time.sleep(700, 1100, () -> ctx.inventory().contains(method.output), 100);
            }
            inventoryOutput = ctx.inventory().getCount(true, method.output);
        }

        if (inventoryOutput <= 0) {
            stats.setStatus("Wanted to sell " + method.output + ", but none was found");
            return;
        }

        int fallback = activeQuote != null && namesMatch(activeQuote.method.output, method.output)
                ? activeQuote.outputSellPrice
                : Math.max(1, pricing.lowPrice(pricing.itemDetail(getAPIContext(), method.output)));
        int price = pricing.quickSellPrice(method.output, fallback);
        pendingGeActions.add(GeAction.sell(method.output, Math.min(totalOutput, inventoryOutput), price));
        stats.lastGeAction = "Queued sale " + inventoryOutput + "x " + method.output;
        log(stats.lastGeAction);
        closeBank(ctx, "Going to GE to sell " + method.output);
    }

    private void prepareProcessInventory(APIContext ctx, HerbloreMethod method) {
        if (!inventoryOnlyContains(ctx, method.inputNames())) {
            stats.setStatus("Clearing inventory for " + method.label);
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return;
        }

        int currentActions = actionsInInventory(ctx, method);
        int targetActions = Math.min(method.maxActionsPerInventory(), availableActions(ctx, method));
        if (targetActions <= 0) {
            return;
        }

        for (Ingredient input : method.inputs) {
            int wanted = targetActions * input.perAction;
            int inventoryCount = itemCountInventory(ctx, input.name);
            if (inventoryCount > wanted) {
                stats.setStatus("Normalising inventory for " + method.label);
                ctx.bank().depositInventory();
                Time.sleep(600, 900);
                return;
            }
        }

        if (currentActions >= targetActions) {
            closeBank(ctx, "Ready for " + method.label);
            return;
        }

        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        for (Ingredient input : method.inputs) {
            int wanted = targetActions * input.perAction;
            int inventoryCount = itemCountInventory(ctx, input.name);
            if (inventoryCount >= wanted) {
                continue;
            }

            int toWithdraw = Math.min(wanted - inventoryCount, ctx.bank().getCount(input.name));
            if (toWithdraw <= 0) {
                continue;
            }
            stats.setStatus("Withdrawing " + toWithdraw + "x " + input.name);
            if (toWithdraw >= ctx.bank().getCount(input.name)) {
                ctx.bank().withdrawAll(input.name);
            } else {
                ctx.bank().withdraw(toWithdraw, input.name);
            }
            Time.sleep(500, 800);
            return;
        }

        closeBank(ctx, "Ready for " + method.label);
    }

    private void planRestock(APIContext ctx, HerbloreMethod method) {
        Quote quote = pricing.quote(ctx, method);
        if (!quote.hasPrices()) {
            stats.setStatus("Cannot price " + method.label + "; refreshing method");
            activeMethod = null;
            activeQuote = null;
            Time.sleep(1200, 1800);
            return;
        }
        activeQuote = quote;

        long coins = ctx.inventory().getCount(true, COINS) + ctx.bank().getCount(COINS);
        RestockPlan plan = createRestockPlan(ctx, method, quote, Math.max(0L, coins - MIN_COINS_RESERVE));
        if (plan.isEmpty()) {
            stats.setStatus("Not enough coins for " + method.label
                    + "; sell outputs or add cash");
            logOccasionally("Not enough coins for Herblore restock. Coins=" + coins
                    + " cost/action=" + quote.inputCost);
            Time.sleep(1800, 2800);
            return;
        }

        enqueueRestock(method, quote, plan);
        closeBank(ctx, "Going to GE for " + method.label + " supplies");
    }

    private RestockPlan createRestockPlan(
            APIContext ctx,
            HerbloreMethod method,
            Quote quote,
            long availableCoins
    ) {
        int targetActions = restockActionsUntilNextUnlock(ctx, method);
        RestockPlan plan = restockPlanForTarget(ctx, method, quote, targetActions);
        while (plan.cost > availableCoins && targetActions > 0) {
            targetActions = Math.max(0, (int) Math.floor(targetActions * 0.8D));
            plan = restockPlanForTarget(ctx, method, quote, targetActions);
        }
        return plan;
    }

    private RestockPlan restockPlanForTarget(
            APIContext ctx,
            HerbloreMethod method,
            Quote quote,
            int targetActions
    ) {
        List<RestockItem> items = new ArrayList<>();
        long totalCost = 0L;
        for (Ingredient input : method.inputs) {
            int available = itemCountInventory(ctx, input.name)
                    + (ctx.bank().isOpen() ? ctx.bank().getCount(input.name) : 0);
            int wanted = targetActions * input.perAction;
            int missing = Math.max(0, wanted - available);
            if (missing <= 0) {
                continue;
            }
            int price = quote.buyPrice(input.name);
            items.add(new RestockItem(input.name, missing));
            totalCost += (long) missing * price;
        }
        return new RestockPlan(items, totalCost);
    }

    private int restockActionsUntilNextUnlock(APIContext ctx, HerbloreMethod method) {
        int currentXp = herbloreXp(ctx);
        int targetXp = xpForLevel(TARGET_LEVEL);
        int nextUnlockLevel = nextUnlockLevel(herbloreLevel(ctx), method.level);
        if (nextUnlockLevel > 0 && nextUnlockLevel < TARGET_LEVEL) {
            targetXp = Math.min(targetXp, xpForLevel(nextUnlockLevel));
        }
        int neededXp = Math.max(1, targetXp - currentXp);
        int actions = (int) Math.ceil(neededXp / method.xp);
        return Math.max(1, Math.min(RESTOCK_TARGET_ACTIONS, actions));
    }

    private int nextUnlockLevel(int currentLevel, int currentMethodLevel) {
        int next = Integer.MAX_VALUE;
        for (HerbloreMethod method : HerbloreMethod.all()) {
            if (method.level > currentLevel
                    && method.level > currentMethodLevel
                    && method.level < next) {
                next = method.level;
            }
        }
        return next == Integer.MAX_VALUE ? -1 : next;
    }

    private void enqueueRestock(HerbloreMethod method, Quote quote, RestockPlan plan) {
        for (RestockItem item : plan.items) {
            int price = pricing.quickBuyPrice(item.name, quote.buyPrice(item.name));
            pendingGeActions.add(GeAction.buy(item.name, item.quantity, price));
        }
        stats.lastGeAction = "Queued supplies for " + method.label + ": " + plan.describe();
        log(stats.lastGeAction);
    }

    private void processInventory(APIContext ctx, HerbloreMethod method) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting for current Herblore action");
            Time.sleep(600, 1000);
            return;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(300, 600, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
            return;
        }

        if (method.type == MethodType.CLEAN) {
            cleanInventory(ctx, method);
            return;
        }

        combineInventory(ctx, method);
    }

    private void cleanInventory(APIContext ctx, HerbloreMethod method) {
        int beforeActions = actionsInInventory(ctx, method);
        if (beforeActions <= 0) {
            return;
        }

        stats.setStatus("Cleaning " + method.inputName() + " (" + beforeActions + " left)");
        ItemWidget item = ctx.inventory().getItem(method.inputName());
        if (item == null || !item.isValid()) {
            Time.sleep(500, 800);
            return;
        }

        if (!item.interact("Clean")) {
            Point point = inventoryItemCenter(item);
            if (point == null || !ctx.mouse().click(point, false)) {
                stats.setStatus("Could not click " + method.inputName());
                Time.sleep(700, 1100);
                return;
            }
        }

        Time.sleep(500, 1000,
                () -> actionsInInventory(ctx, method) < beforeActions
                        || ctx.localPlayer().isAnimating(),
                100);
        waitForBatchToFinish(ctx, method, beforeActions, 35_000L);
        recordProcessed(ctx, method, beforeActions);
    }

    private void combineInventory(APIContext ctx, HerbloreMethod method) {
        int beforeActions = actionsInInventory(ctx, method);
        if (beforeActions <= 0) {
            return;
        }

        int beforeOutput = ctx.inventory().getCount(true, method.output);
        stats.setStatus("Starting " + method.label + " batch (" + beforeActions + " actions)");
        if (!useFirstInputOnSecond(ctx, method)) {
            stats.setStatus("Could not use ingredients for " + method.label);
            Time.sleep(800, 1300);
            return;
        }

        Time.sleep(600, 1400,
                () -> makeInterfaceOpen(ctx)
                        || ctx.localPlayer().isAnimating()
                        || actionsInInventory(ctx, method) < beforeActions
                        || ctx.inventory().getCount(true, method.output) > beforeOutput,
                100);

        if (makeInterfaceOpen(ctx) && !clickMakeTarget(ctx, method)) {
            stats.setStatus("Creation menu open, but target not found: " + method.output);
            Time.sleep(1000, 1600);
            return;
        }

        Time.sleep(900, 2200,
                () -> ctx.localPlayer().isAnimating()
                        || actionsInInventory(ctx, method) < beforeActions
                        || ctx.inventory().getCount(true, method.output) > beforeOutput,
                100);
        waitForBatchToFinish(ctx, method, beforeActions, method.type == MethodType.TAR ? 55_000L : 35_000L);
        recordProcessed(ctx, method, beforeActions);
    }

    private boolean useFirstInputOnSecond(APIContext ctx, HerbloreMethod method) {
        if (method.inputs.size() < 2) {
            return false;
        }
        String first = method.inputs.get(0).name;
        String second = method.inputs.get(1).name;
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 70) {
            return selectInventoryItemForUse(ctx, first)
                    && useSelectedItemOnInventoryItem(ctx, second);
        }
        return selectInventoryItemForUse(ctx, second)
                && useSelectedItemOnInventoryItem(ctx, first);
    }

    private void waitForBatchToFinish(APIContext ctx, HerbloreMethod method, int beforeActions, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int lastActionCount = beforeActions;
        long lastProgressAt = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            int actions = actionsInInventory(ctx, method);
            if (actions <= 0) {
                stats.setStatus("Batch complete: " + method.label);
                return;
            }
            if (actions < lastActionCount) {
                lastActionCount = actions;
                lastProgressAt = System.currentTimeMillis();
                stats.setStatus(method.label + " (" + actions + " left)");
            }
            if (!ctx.localPlayer().isAnimating() && System.currentTimeMillis() - lastProgressAt > 4_000L) {
                return;
            }
            Time.sleep(450, 750);
        }
    }

    private void recordProcessed(APIContext ctx, HerbloreMethod method, int beforeActions) {
        int afterActions = actionsInInventory(ctx, method);
        int processed = Math.max(0, beforeActions - afterActions);
        if (processed > 0) {
            stats.actionsCompleted += processed;
            stats.lastProcessedAt = System.currentTimeMillis();
        }
    }

    private boolean selectInventoryItemForUse(APIContext ctx, String itemName) {
        clearInventoryInteractionState(ctx);

        if (ctx.inventory().selectItem(itemName)) {
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
            if (ctx.inventory().isItemSelected()) {
                return true;
            }
        }

        if (ctx.menu().isOpen() && selectUseFromOpenMenu(ctx, itemName)) {
            return true;
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return selectUseFromOpenMenu(ctx, itemName);
        }
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean useSelectedItemOnInventoryItem(APIContext ctx, String itemName) {
        if (!ctx.inventory().isItemSelected()) {
            return false;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(350, 1000, () -> !ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return clickUseFromOpenMenu(ctx, itemName);
        }
        return clicked;
    }

    private boolean selectUseFromOpenMenu(APIContext ctx, String itemName) {
        boolean clicked = clickUseFromOpenMenu(ctx, itemName);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || !ctx.menu().isOpen(), 50);
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean clickUseFromOpenMenu(APIContext ctx, String itemName) {
        if (!ctx.menu().isOpen()) {
            return false;
        }

        boolean clicked = false;
        if (ctx.menu().contains("Use", itemName)) {
            clicked = ctx.menu().interact("Use", itemName, true);
        }
        if (!clicked && ctx.menu().contains("Use")) {
            clicked = ctx.menu().interact("Use", true);
        }
        Time.sleep(250, 700, () -> !ctx.menu().isOpen(), 50);
        return clicked;
    }

    private boolean clickMakeTarget(APIContext ctx, HerbloreMethod method) {
        WidgetChild target = findMakeWidget(ctx, method);
        if (target == null) {
            return false;
        }

        stats.setStatus("Selecting creation target: " + method.output);
        for (String action : makeActions(target)) {
            if (target.interact(action, method.output)
                    || target.interact(action)
                    || ctx.menu().interact(action, method.output, target, true)
                    || ctx.menu().interact(action, target, true)) {
                Time.sleep(700, 1200);
                return true;
            }
        }

        Point point = target.getCentralPoint();
        if (point != null && ctx.mouse().click(point, false)) {
            Time.sleep(700, 1200);
            return true;
        }

        if (target.click()) {
            Time.sleep(700, 1200);
            return true;
        }
        return false;
    }

    private WidgetChild findMakeWidget(APIContext ctx, HerbloreMethod method) {
        WidgetChild named = ctx.widgets()
                .query()
                .itemName(method.output)
                .results()
                .first();
        if (isVisibleWidget(named)) {
            return named;
        }

        String output = normalizedName(method.output);
        String label = normalizedName(method.label);
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (!looksLikeMakeWidget(widget)) {
                continue;
            }
            String combined = normalizedName(widget.getName())
                    + " " + normalizedName(widget.getText())
                    + " " + normalizedName(widget.getRawText());
            if (combined.contains(output) || (!label.isBlank() && combined.contains(label))) {
                return widget;
            }
        }

        List<WidgetChild> makeWidgets = new ArrayList<>();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (looksLikeMakeWidget(widget)) {
                makeWidgets.add(widget);
            }
        }
        return makeWidgets.size() == 1 ? makeWidgets.get(0) : null;
    }

    private boolean makeInterfaceOpen(APIContext ctx) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (looksLikeMakeWidget(widget)) {
                return true;
            }
        }
        return false;
    }

    private List<String> makeActions(WidgetChild widget) {
        List<String> actions = new ArrayList<>();
        for (String preferred : new String[]{"Make All", "Make-All", "Make 10", "Make-10", "Make X", "Make-X", "Make"}) {
            if (widget.hasAction(preferred)) {
                actions.add(preferred);
            }
        }
        for (String action : widget.getActions()) {
            if (action != null && action.toLowerCase(Locale.ROOT).contains("make") && !actions.contains(action)) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            actions.add("Make");
        }
        return actions;
    }

    private boolean looksLikeMakeWidget(WidgetChild widget) {
        if (widget == null) {
            return false;
        }
        for (String action : widget.getActions()) {
            if (action != null && action.toLowerCase(Locale.ROOT).contains("make")) {
                return true;
            }
        }
        String text = visibleText(widget).toLowerCase(Locale.ROOT);
        return text.contains("make") || text.contains("how many");
    }

    private void handleGrandExchange(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (!isAtGrandExchange(ctx)) {
            stats.setStatus("Walking to GE for Herblore supplies/sales");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_TILE);
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening Grand Exchange");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmGeWarning(ctx)) {
            return;
        }

        if (!placedGeActions.isEmpty()) {
            handlePlacedGeActions(ctx);
            return;
        }

        GeAction action = pendingGeActions.poll();
        if (action == null) {
            stats.setStatus("Collecting any GE leftovers");
            try {
                ctx.grandExchange().collectToBank();
            } catch (RuntimeException ignored) {
                // Collection is harmless to retry.
            }
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        placeGeAction(ctx, action);
    }

    private void placeGeAction(APIContext ctx, GeAction action) {
        if (action.quantity <= 0) {
            return;
        }

        stats.lastGeAction = action.describe();
        stats.setStatus(action.describe());
        boolean placed;
        if (action.type == GeActionType.BUY) {
            placed = ctx.grandExchange().placeBuyOffer(action.itemName, action.quantity, action.price);
        } else {
            int inventoryCount = ctx.inventory().getCount(true, action.itemName);
            int quantity = Math.min(action.quantity, inventoryCount);
            if (quantity <= 0) {
                stats.setStatus("No inventory item to sell: " + action.itemName);
                Time.sleep(700, 1100);
                return;
            }
            placed = ctx.grandExchange().placeSellOffer(action.itemName, quantity, action.price);
        }

        Time.sleep(1000, 1500);
        if (!placed) {
            if (!confirmGeWarning(ctx)) {
                stats.setStatus("GE offer was not placed: " + action.describe());
                pendingGeActions.add(action);
                Time.sleep(1200, 1800);
            }
            return;
        }

        placedGeActions.add(action);
        nextGeCollectAt = System.currentTimeMillis() + 4_000L;
    }

    private void handlePlacedGeActions(APIContext ctx) {
        if (System.currentTimeMillis() < nextGeCollectAt) {
            stats.setStatus("Waiting for GE offer to fill");
            Time.sleep(800, 1200);
            return;
        }

        int waiting = 0;
        for (GeAction action : placedGeActions) {
            GrandExchangeSlot slot = findSlot(ctx, action);
            if (slot != null && !slot.isCompleted() && !slot.canCollect()) {
                waiting++;
            }
        }

        if (waiting > 0) {
            stats.setStatus("GE offer still pending (" + waiting + ")");
            nextGeCollectAt = System.currentTimeMillis() + 6_000L;
            Time.sleep(900, 1400);
            return;
        }

        stats.setStatus("Collecting completed GE offer(s) to bank");
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection is harmless to retry.
        }
        Time.sleep(900, 1400);
        boolean soldOutput = placedGeActions.stream().anyMatch(action -> action.type == GeActionType.SELL);
        placedGeActions.clear();
        if (soldOutput) {
            activeMethod = null;
            activeQuote = null;
            stats.setStatus("Finished sale cycle; refreshing Herblore selector");
        }
    }

    private GrandExchangeSlot findSlot(APIContext ctx, GeAction action) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (!namesMatch(offer.getItemName(), action.itemName)) {
                continue;
            }
            boolean buyState = slot.getState().name().contains("BUY") || slot.getState().name().contains("BOUGHT");
            boolean sellState = slot.getState().name().contains("SELL") || slot.getState().name().contains("SOLD");
            if ((action.type == GeActionType.BUY && buyState)
                    || (action.type == GeActionType.SELL && sellState)) {
                return slot;
            }
        }
        return null;
    }

    private boolean confirmGeWarning(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null) {
            return false;
        }

        String text = allWidgetText(ctx).toLowerCase(Locale.ROOT);
        if (!text.contains("much higher") && !text.contains("are you sure")) {
            return false;
        }

        stats.setStatus("Confirming GE price warning");
        if (clickWidgetCenter(ctx, yes)
                || yes.interact("Continue")
                || yes.interact("Yes")
                || yes.click()) {
            Time.sleep(1000, 1500);
            return true;
        }
        Time.sleep(600, 900);
        return true;
    }

    private boolean openBank(APIContext ctx, String reason) {
        if (ctx.bank().isOpen()) {
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (!ctx.bank().isReachable()) {
            stats.setStatus("Walking to nearest bank: " + reason);
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkToBank();
            Time.sleep(1200, 1800);
            return false;
        }

        stats.setStatus("Opening bank: " + reason);
        ctx.bank().open();
        Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
        return ctx.bank().isOpen();
    }

    private void closeBank(APIContext ctx, String status) {
        stats.setStatus(status);
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
    }

    private boolean hasProcessInventory(APIContext ctx, HerbloreMethod method) {
        return method != null
                && actionsInInventory(ctx, method) > 0
                && inventoryOnlyContains(ctx, method.inputNames());
    }

    private boolean hasMethodSuppliesAvailable(APIContext ctx, HerbloreMethod method) {
        return availableActions(ctx, method) > 0;
    }

    private int availableActions(APIContext ctx, HerbloreMethod method) {
        if (method == null) {
            return 0;
        }
        int actions = Integer.MAX_VALUE;
        for (Ingredient input : method.inputs) {
            int count = itemCountInventory(ctx, input.name)
                    + (ctx.bank().isOpen() ? ctx.bank().getCount(input.name) : 0);
            actions = Math.min(actions, count / input.perAction);
        }
        return actions == Integer.MAX_VALUE ? 0 : Math.max(0, actions);
    }

    private int actionsInInventory(APIContext ctx, HerbloreMethod method) {
        if (method == null) {
            return 0;
        }
        int actions = Integer.MAX_VALUE;
        for (Ingredient input : method.inputs) {
            actions = Math.min(actions, itemCountInventory(ctx, input.name) / input.perAction);
        }
        return actions == Integer.MAX_VALUE ? 0 : Math.max(0, actions);
    }

    private int itemCountInventory(APIContext ctx, String itemName) {
        return ctx.inventory().getCount(true, itemName);
    }

    private boolean inventoryHasAnyOutput(APIContext ctx) {
        for (HerbloreMethod method : HerbloreMethod.all()) {
            if (ctx.inventory().getCount(true, method.output) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean inventoryOnlyContains(APIContext ctx, String... names) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!matchesAny(item.getName(), names)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAny(String actual, String... names) {
        for (String name : names) {
            if (namesMatch(actual, name)) {
                return true;
            }
        }
        return false;
    }

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }
            return text.equalsIgnoreCase(cleanWidgetText(candidate.getText()))
                    || text.equalsIgnoreCase(cleanWidgetText(candidate.getRawText()));
        })) {
            return widget;
        }
        WidgetChild queried = ctx.widgets().query().textContains(text).results().first();
        return isVisibleWidget(queried) ? queried : null;
    }

    private String cleanWidgetText(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", " ").trim();
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            text.append(' ').append(visibleText(widget));
        }
        return text.toString();
    }

    private String visibleText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (widget.getName() != null) {
            text.append(' ').append(widget.getName());
        }
        if (widget.getText() != null) {
            text.append(' ').append(widget.getText());
        }
        if (widget.getRawText() != null) {
            text.append(' ').append(widget.getRawText());
        }
        return text.toString().replaceAll("<[^>]+>", " ");
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private Point inventoryItemCenter(ItemWidget item) {
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null || tile.getPlane() != 0) {
            return false;
        }
        return tile.getX() >= GE_MIN_X
                && tile.getX() <= GE_MAX_X
                && tile.getY() >= GE_MIN_Y
                && tile.getY() <= GE_MAX_Y;
    }

    private boolean targetReached(APIContext ctx) {
        return herbloreLevel(ctx) >= TARGET_LEVEL;
    }

    private int herbloreLevel(APIContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.skills().get(Skill.Skills.HERBLORE).getRealLevel();
    }

    private int herbloreXp(APIContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.skills().get(Skill.Skills.HERBLORE).getExperience();
    }

    private int xpForLevel(int level) {
        if (level <= 1) {
            return 0;
        }
        int points = 0;
        int output = 0;
        for (int lvl = 1; lvl < level; lvl++) {
            points += Math.floor(lvl + 300.0D * Math.pow(2.0D, lvl / 7.0D));
            output = points / 4;
        }
        return output;
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private void clearClientInteractionState() {
        APIContext ctx = getAPIContext();
        if (ctx == null) {
            return;
        }
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only; stopping must not throw.
        }
    }

    private void clearInventoryInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private void log(String message) {
        if (stats != null) {
            stats.setStatus(message);
        }
        getLogger().info(message);
    }

    private void logOccasionally(String message) {
        long now = System.currentTimeMillis();
        if (now < nextIdleLogAt) {
            return;
        }
        log(message);
        nextIdleLogAt = now + 15_000L;
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String formatGpPerXp(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private enum MethodType {
        CLEAN,
        POTION,
        TAR
    }

    private static class HerbloreMethod {
        private static final List<HerbloreMethod> METHODS = buildMethods();

        private final MethodType type;
        private final int level;
        private final String label;
        private final List<Ingredient> inputs;
        private final String output;
        private final int outputQuantity;
        private final double xp;
        private final int actionsPerHour;

        private HerbloreMethod(
                MethodType type,
                int level,
                String label,
                List<Ingredient> inputs,
                String output,
                int outputQuantity,
                double xp,
                int actionsPerHour
        ) {
            this.type = type;
            this.level = level;
            this.label = label;
            this.inputs = inputs;
            this.output = output;
            this.outputQuantity = outputQuantity;
            this.xp = xp;
            this.actionsPerHour = actionsPerHour;
        }

        private static List<HerbloreMethod> all() {
            return METHODS;
        }

        private static List<HerbloreMethod> buildMethods() {
            List<HerbloreMethod> methods = new ArrayList<>();

            methods.add(clean(3, "Clean guam leaf", "Grimy guam leaf", "Guam leaf", 2.5D));
            methods.add(clean(5, "Clean marrentill", "Grimy marrentill", "Marrentill", 3.8D));
            methods.add(clean(11, "Clean tarromin", "Grimy tarromin", "Tarromin", 5.0D));
            methods.add(clean(20, "Clean harralander", "Grimy harralander", "Harralander", 6.3D));
            methods.add(clean(25, "Clean ranarr weed", "Grimy ranarr weed", "Ranarr weed", 7.5D));
            methods.add(clean(30, "Clean toadflax", "Grimy toadflax", "Toadflax", 8.0D));
            methods.add(clean(40, "Clean irit leaf", "Grimy irit leaf", "Irit leaf", 8.8D));
            methods.add(clean(48, "Clean avantoe", "Grimy avantoe", "Avantoe", 10.0D));
            methods.add(clean(54, "Clean kwuarm", "Grimy kwuarm", "Kwuarm", 11.3D));
            methods.add(clean(58, "Clean huasca", "Grimy huasca", "Huasca", 11.8D));
            methods.add(clean(59, "Clean snapdragon", "Grimy snapdragon", "Snapdragon", 11.8D));

            methods.add(potion(3, "Attack potion", "Guam potion (unf)", "Eye of newt", "Attack potion(3)", 25.0D));
            methods.add(potion(5, "Antipoison", "Marrentill potion (unf)", "Unicorn horn dust", "Antipoison(3)", 37.5D));
            methods.add(potion(12, "Strength potion", "Tarromin potion (unf)", "Limpwurt root", "Strength potion(3)", 50.0D));
            methods.add(potion(15, "Serum 207", "Tarromin potion (unf)", "Ashes", "Serum 207 (3)", 50.0D));
            methods.add(potion(22, "Compost potion", "Harralander potion (unf)", "Volcanic ash", "Compost potion(3)", 60.0D));
            methods.add(potion(22, "Restore potion", "Harralander potion (unf)", "Red spiders' eggs", "Restore potion(3)", 62.5D));
            methods.add(potion(26, "Energy potion", "Harralander potion (unf)", "Chocolate dust", "Energy potion(3)", 67.5D));
            methods.add(potion(30, "Defence potion", "Ranarr potion (unf)", "White berries", "Defence potion(3)", 75.0D));
            methods.add(potion(34, "Agility potion", "Toadflax potion (unf)", "Toad's legs", "Agility potion(3)", 80.0D));
            methods.add(potion(36, "Combat potion", "Harralander potion (unf)", "Goat horn dust", "Combat potion(3)", 84.0D));
            methods.add(potion(38, "Prayer potion", "Ranarr potion (unf)", "Snape grass", "Prayer potion(3)", 87.5D));
            methods.add(potion(45, "Super attack", "Irit potion (unf)", "Eye of newt", "Super attack(3)", 100.0D));
            methods.add(potion(48, "Superantipoison", "Irit potion (unf)", "Unicorn horn dust", "Superantipoison(3)", 106.3D));
            methods.add(potion(50, "Fishing potion", "Avantoe potion (unf)", "Snape grass", "Fishing potion(3)", 112.5D));
            methods.add(potion(52, "Super energy", "Avantoe potion (unf)", "Mort myre fungus", "Super energy(3)", 117.5D));
            methods.add(potion(53, "Hunter potion", "Avantoe potion (unf)", "Kebbit teeth dust", "Hunter potion(3)", 120.0D));
            methods.add(potion(54, "Goading potion", "Harralander potion (unf)", "Aldarium", "Goading potion(3)", 132.0D));
            methods.add(potion(55, "Super strength", "Kwuarm potion (unf)", "Limpwurt root", "Super strength(3)", 125.0D));
            methods.add(potion(58, "Prayer regeneration potion", "Huasca potion (unf)", "Aldarium", "Prayer regeneration potion(3)", 132.0D));
            methods.add(potion(60, "Weapon poison", "Kwuarm potion (unf)", "Dragon scale dust", "Weapon poison", 137.5D));

            methods.add(tar(19, "Guam tar", "Guam leaf", "Guam tar", 30.0D, 2033));
            methods.add(tar(31, "Marrentill tar", "Marrentill", "Marrentill tar", 42.5D, 2023));
            methods.add(tar(39, "Tarromin tar", "Tarromin", "Tarromin tar", 55.0D, 2000));
            methods.add(tar(44, "Harralander tar", "Harralander", "Harralander tar", 72.5D, 2000));
            methods.add(tar(55, "Irit tar", "Irit leaf", "Irit tar", 85.0D, 2000));

            return List.copyOf(methods);
        }

        private static HerbloreMethod clean(int level, String label, String grimy, String clean, double xp) {
            return new HerbloreMethod(
                    MethodType.CLEAN,
                    level,
                    label,
                    List.of(new Ingredient(grimy, 1)),
                    clean,
                    1,
                    xp,
                    3000
            );
        }

        private static HerbloreMethod potion(
                int level,
                String label,
                String base,
                String secondary,
                String output,
                double xp
        ) {
            return new HerbloreMethod(
                    MethodType.POTION,
                    level,
                    label,
                    List.of(new Ingredient(base, 1), new Ingredient(secondary, 1)),
                    output,
                    1,
                    xp,
                    2500
            );
        }

        private static HerbloreMethod tar(
                int level,
                String label,
                String herb,
                String output,
                double xp,
                int actionsPerHour
        ) {
            return new HerbloreMethod(
                    MethodType.TAR,
                    level,
                    label,
                    List.of(new Ingredient(herb, 1), new Ingredient("Swamp tar", 15)),
                    output,
                    15,
                    xp,
                    actionsPerHour
            );
        }

        private int maxActionsPerInventory() {
            if (type == MethodType.TAR) {
                return 27;
            }
            if (type == MethodType.CLEAN) {
                return 28;
            }
            return 14;
        }

        private String inputName() {
            return inputs.isEmpty() ? "" : inputs.get(0).name;
        }

        private String[] inputNames() {
            String[] names = new String[inputs.size()];
            for (int i = 0; i < inputs.size(); i++) {
                names[i] = inputs.get(i).name;
            }
            return names;
        }

        private boolean usesInput(String itemName) {
            for (Ingredient input : inputs) {
                if (input.name.equals(itemName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class Ingredient {
        private final String name;
        private final int perAction;

        private Ingredient(String name, int perAction) {
            this.name = name;
            this.perAction = perAction;
        }
    }

    private class Pricing {
        private Quote quote(APIContext ctx, HerbloreMethod method) {
            long inputCost = 0L;
            List<InputPrice> inputPrices = new ArrayList<>();
            for (Ingredient input : method.inputs) {
                ItemDetail detail = itemDetail(ctx, input.name);
                int buyPrice = highPrice(detail);
                if (buyPrice <= 0) {
                    return Quote.missing(method);
                }
                inputPrices.add(new InputPrice(input.name, buyPrice));
                inputCost += (long) buyPrice * input.perAction;
            }

            ItemDetail outputDetail = itemDetail(ctx, method.output);
            int outputSell = lowPrice(outputDetail);
            if (outputSell <= 0) {
                return Quote.missing(method);
            }

            long outputRevenue = taxedSellValue(outputSell) * method.outputQuantity;
            long netCost = inputCost - outputRevenue;
            return new Quote(method, inputPrices, outputSell, inputCost, outputRevenue, netCost);
        }

        private int quickBuyPrice(String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(getAPIContext(), itemName);
            long market = firstPositive(highPrice(detail), lowPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.ceil(market * BUY_MARKUP))));
        }

        private int quickSellPrice(String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(getAPIContext(), itemName);
            long market = firstPositive(lowPrice(detail), highPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.floor(market * SELL_MARKDOWN))));
        }

        private ItemDetail itemDetail(APIContext ctx, String itemName) {
            if (ctx == null || itemName == null || itemName.isBlank()) {
                return null;
            }
            try {
                return ctx.pricing().get(itemName);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private int highPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getHighestPrice());
        }

        private int lowPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getLowestPrice());
        }

        private long firstPositive(long first, long second, long third) {
            if (first > 0) {
                return first;
            }
            if (second > 0) {
                return second;
            }
            return Math.max(1L, third);
        }

        private long taxedSellValue(long sellPrice) {
            long tax = (long) Math.floor(sellPrice * GE_TAX_RATE);
            return Math.max(0L, sellPrice - tax);
        }
    }

    private static class Quote {
        private final HerbloreMethod method;
        private final List<InputPrice> inputPrices;
        private final int outputSellPrice;
        private final long inputCost;
        private final long outputRevenue;
        private final long netCostPerAction;
        private final double netCostPerXp;

        private Quote(
                HerbloreMethod method,
                List<InputPrice> inputPrices,
                int outputSellPrice,
                long inputCost,
                long outputRevenue,
                long netCostPerAction
        ) {
            this.method = method;
            this.inputPrices = inputPrices;
            this.outputSellPrice = outputSellPrice;
            this.inputCost = inputCost;
            this.outputRevenue = outputRevenue;
            this.netCostPerAction = netCostPerAction;
            this.netCostPerXp = netCostPerAction / Math.max(1.0D, method.xp);
        }

        private static Quote missing(HerbloreMethod method) {
            return new Quote(method, List.of(), 0, 0L, 0L, Long.MAX_VALUE);
        }

        private boolean hasPrices() {
            return outputSellPrice > 0 && !inputPrices.isEmpty() && netCostPerAction != Long.MAX_VALUE;
        }

        private int buyPrice(String itemName) {
            for (InputPrice inputPrice : inputPrices) {
                if (inputPrice.name.equals(itemName)) {
                    return inputPrice.price;
                }
            }
            return 1;
        }
    }

    private static class InputPrice {
        private final String name;
        private final int price;

        private InputPrice(String name, int price) {
            this.name = name;
            this.price = price;
        }
    }

    private static class RestockPlan {
        private final List<RestockItem> items;
        private final long cost;

        private RestockPlan(List<RestockItem> items, long cost) {
            this.items = items;
            this.cost = cost;
        }

        private boolean isEmpty() {
            return items.isEmpty();
        }

        private String describe() {
            List<String> parts = new ArrayList<>();
            for (RestockItem item : items) {
                parts.add(item.quantity + "x " + item.name);
            }
            return String.join(", ", parts);
        }
    }

    private static class RestockItem {
        private final String name;
        private final int quantity;

        private RestockItem(String name, int quantity) {
            this.name = name;
            this.quantity = quantity;
        }
    }

    private enum GeActionType {
        BUY,
        SELL
    }

    private static class GeAction {
        private final GeActionType type;
        private final String itemName;
        private final int quantity;
        private final int price;

        private GeAction(GeActionType type, String itemName, int quantity, int price) {
            this.type = type;
            this.itemName = itemName;
            this.quantity = quantity;
            this.price = price;
        }

        private static GeAction buy(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.BUY, itemName, quantity, price);
        }

        private static GeAction sell(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.SELL, itemName, quantity, price);
        }

        private String describe() {
            return type.name().toLowerCase(Locale.ROOT) + " " + quantity + "x " + itemName + " @ " + price;
        }
    }

    private static class Stats {
        private final long startedAt = System.currentTimeMillis();
        private int startingHerbloreXp = -1;
        private int actionsCompleted;
        private long lastProcessedAt;
        private String status = "Starting";
        private String lastChat = "-";
        private String lastGeAction = "-";

        private void startExperienceIfNeeded(APIContext ctx) {
            if (ctx == null || startingHerbloreXp >= 0) {
                return;
            }
            startingHerbloreXp = ctx.skills().get(Skill.Skills.HERBLORE).getExperience();
        }

        private int xpGained(APIContext ctx) {
            if (ctx == null || startingHerbloreXp < 0) {
                return 0;
            }
            return Math.max(0, ctx.skills().get(Skill.Skills.HERBLORE).getExperience() - startingHerbloreXp);
        }

        private int xpPerHour(APIContext ctx) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - startedAt);
            return (int) Math.round(xpGained(ctx) * 3_600_000D / elapsed);
        }

        private String runtimeText() {
            long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            long hours = seconds / 3600L;
            long minutes = (seconds % 3600L) / 60L;
            long secs = seconds % 60L;
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }

        private void setStatus(String status) {
            this.status = status == null ? "-" : status;
        }
    }
}
