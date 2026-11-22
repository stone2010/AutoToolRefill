package com.stg.autorefill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Auto Tool Refill - when a tool breaks, put a replacement in that hotbar slot.
 *
 * <p>Why this mod needs three trigger paths: in 1.9.4 vanilla, a tool reaching zero
 * durability does <b>not</b> fire any Forge event. {@code ItemStack.damageItem} simply
 * decrements {@code stackSize} to 0, and the server's
 * {@code PlayerInteractionManager.tryHarvestBlock} then calls {@code setHeldItem(null)}
 * with no hook in between. {@code PlayerDestroyItemEvent} is only posted client-side by
 * {@code PlayerControllerMP}, and only when {@code stackSize <= 0}.</p>
 *
 * <p>So the mod listens on three paths:</p>
 * <ol>
 *   <li>client-side {@code PlayerDestroyItemEvent} - mapped to the server player and queued;</li>
 *   <li>server tick scan for a leftover {@code stackSize <= 0} shell in the inventory;</li>
 *   <li>server tick diff: the slot held a tool in use last tick and is empty this tick.</li>
 * </ol>
 *
 * <p>Refill rules:</p>
 * <ol>
 *   <li>a same-type tool exists in the inventory - move it into the broken slot;</li>
 *   <li>no tool, but enough materials - craft one on the spot (no crafting table needed);</li>
 *   <li>neither - do nothing.</li>
 * </ol>
 */
@Mod(modid = AutoRefill.MODID, name = AutoRefill.NAME, version = AutoRefill.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.9,1.11)")
public class AutoRefill {

    public static final String MODID = "autorefill";
    public static final String NAME = "Auto Tool Refill";
    public static final String VERSION = "1.2.0";

    private static final Logger LOG = LogManager.getLogger(NAME);

    /** Vanilla tool tiers, worst to best. */
    private static final String[] TIERS = {"wooden", "stone", "golden", "iron", "diamond"};
    /** Tool types this mod understands. */
    private static final String[] TYPES = {"pickaxe", "axe", "shovel", "sword", "hoe"};
    /** Craft fallback order when the broken tier cannot be crafted. */
    private static final String[] CRAFT_FALLBACK = {"diamond", "iron", "stone", "wooden", "golden"};

    /** A tool that lost durability within this many ticks counts as "in use". */
    private static final int USED_WINDOW = 40;
    /** How long a queued refill waits at most (ticks). */
    private static final int MAX_LIFE = 60;

    private boolean enableRefill = true;
    private boolean enableAutoCraft = true;
    private boolean showMessage = true;

    /** Error counter, used to rate-limit the log. */
    private int errorCount = 0;

    /** Per-player state of the held tool on the previous tick. */
    private final Map<UUID, Held> lastHeld = new ConcurrentHashMap<UUID, Held>();
    /**
     * Queued refills. The write must wait until the server has actually cleared the slot,
     * otherwise the following {@code setHeldItem(null)} would wipe the new tool again.
     */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();

    private static final class Held {
        final String itemId;
        final int slot;
        final int max;
        int damage;
        int fresh;

        Held(String itemId, int slot, int damage, int max, int fresh) {
            this.itemId = itemId;
            this.slot = slot;
            this.damage = damage;
            this.max = max;
            this.fresh = fresh;
        }
    }

    private static final class Pending {
        final String itemId;
        final int slot;
        int delay;
        int life;

        Pending(String itemId, int slot, int delay) {
            this.itemId = itemId;
            this.slot = slot;
            this.delay = delay;
            this.life = MAX_LIFE;
        }
    }

    // ------------------------------------------------------------------ setup

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        try {
            cfg.load();
            enableRefill = cfg.getBoolean("enableRefill", "general", true,
                    "Move a same-type tool from the inventory into the broken slot "
                            + "(工具坏了，自动把背包里的同类工具换到那个槽位)");
            enableAutoCraft = cfg.getBoolean("enableAutoCraft", "general", true,
                    "Craft a replacement from inventory materials when no spare tool exists. "
                            + "Vanilla tools only, no crafting table needed "
                            + "(没有备用工具时，直接用背包里的材料合成一把)");
            showMessage = cfg.getBoolean("showMessage", "general", true,
                    "Print a chat message when a tool is refilled or crafted "
                            + "(补位或合成时在聊天栏提示一句)");
        } catch (Exception e) {
            LOG.warn("Failed to read config, using defaults", e);
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("Auto Tool Refill loaded (refill={}, autoCraft={})", enableRefill, enableAutoCraft);
    }

    // ------------------------------------------------------------------ trigger A: client event

    @SubscribeEvent
    public void onItemDestroyed(PlayerDestroyItemEvent event) {
        try {
            EntityPlayer player = event.getEntityPlayer();
            if (player == null || player.capabilities.isCreativeMode) {
                return;
            }
            if (event.getHand() != EnumHand.MAIN_HAND) {
                return;
            }
            ItemStack broken = event.getOriginal();
            if (broken == null || broken.getItem() == null || broken.getMaxDamage() <= 0) {
                return; // only care about damageable tools, not blocks or buckets
            }
            String brokenId = idOf(broken.getItem());
            if (brokenId == null) {
                return;
            }
            EntityPlayerMP serverPlayer = asServerPlayer(player);
            if (serverPlayer == null) {
                return; // connected to a remote server, the server inventory cannot be changed
            }
            enqueue(serverPlayer.getUniqueID(), brokenId, serverPlayer.inventory.currentItem, 2);
        } catch (Throwable t) {
            logError("Error while handling PlayerDestroyItemEvent", t);
        }
    }

    /** This mod must never crash the game, and must never flood the log. */
    private void logError(String what, Throwable t) {
        errorCount++;
        if (errorCount <= 3 || errorCount % 600 == 0) {
            LOG.error(what + " (" + errorCount + " times so far)", t);
        }
    }

    /** Resolve the server-side player behind a client-side one; null for remote servers. */
    private static EntityPlayerMP asServerPlayer(EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            return (EntityPlayerMP) player;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getPlayerList() == null) {
            return null;
        }
        return server.getPlayerList().getPlayerByUUID(player.getUniqueID());
    }

    // ------------------------------------------------------------------ triggers B and C: server tick

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.worldObj == null || player.worldObj.isRemote) {
            return;
        }
        if (player.capabilities.isCreativeMode) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            logError("Error while refilling a tool", t);
        }
    }

    private void tick(EntityPlayerMP player) {
        InventoryPlayer inv = player.inventory;
        if (inv == null || inv.mainInventory == null) {
            return;
        }
        UUID uid = player.getUniqueID();

        // B. a leftover stackSize<=0 shell: a tool that just broke and was never cleared
        for (int i = 0; i < inv.mainInventory.length; i++) {
            ItemStack shell = inv.mainInventory[i];
            if (shell != null && shell.stackSize <= 0) {
                String brokenId = idOf(shell.getItem());
                boolean damageable = shell.getMaxDamage() > 0;
                inv.mainInventory[i] = null;
                inv.markDirty();
                if (brokenId != null && damageable) {
                    enqueue(uid, brokenId, i, 0);
                }
            }
        }

        // C. the held slot had a tool in use last tick and is empty this tick -> it just broke
        Held prev = lastHeld.get(uid);
        if (prev != null && prev.slot >= 0 && prev.slot < inv.mainInventory.length) {
            ItemStack inPrev = inv.mainInventory[prev.slot];
            boolean gone = (inPrev == null || inPrev.stackSize <= 0);
            boolean inUse = (prev.fresh > 0) || (prev.max > 0 && prev.damage >= prev.max - 1);
            if (gone && inUse) {
                lastHeld.remove(uid);
                enqueue(uid, prev.itemId, prev.slot, 2);
            }
        }

        Pending pd = pending.get(uid);
        if (pd != null) {
            process(player, inv, pd, uid);
        }

        recordHeld(player, inv, uid);
    }

    private void process(EntityPlayerMP player, InventoryPlayer inv, Pending pd, UUID uid) {
        if (pd.slot < 0 || pd.slot >= inv.mainInventory.length) {
            pending.remove(uid);
            return;
        }
        ItemStack now = inv.mainInventory[pd.slot];
        boolean free = (now == null || now.stackSize <= 0);

        if (!free) {
            if (!pd.itemId.equals(idOf(now.getItem()))) {
                pending.remove(uid); // the player put something else there, give up
                return;
            }
            if (--pd.life <= 0) {
                pending.remove(uid); // never actually broke (client prediction rejected)
            }
            return;
        }
        if (pd.delay > 0) {
            --pd.delay;
            if (--pd.life <= 0) {
                pending.remove(uid);
            }
            return;
        }
        pending.remove(uid);
        inv.mainInventory[pd.slot] = null;
        doRefill(player, inv, pd.slot, pd.itemId);
    }

    private void recordHeld(EntityPlayerMP player, InventoryPlayer inv, UUID uid) {
        int slot = inv.currentItem;
        ItemStack held = (slot >= 0 && slot < inv.mainInventory.length) ? inv.mainInventory[slot] : null;
        String heldId = null;
        if (held != null && held.stackSize > 0 && held.getItem() != null && held.getMaxDamage() > 0) {
            heldId = idOf(held.getItem());
        }
        if (heldId == null) {
            lastHeld.remove(uid);
            return;
        }
        int damage = held.getItemDamage();
        int max = held.getMaxDamage();
        Held h = lastHeld.get(uid);
        if (h == null || h.slot != slot || !h.itemId.equals(heldId)) {
            lastHeld.put(uid, new Held(heldId, slot, damage, max, USED_WINDOW));
        } else if (h.damage != damage) {
            h.damage = damage;
            h.fresh = USED_WINDOW;
        } else if (h.fresh > 0) {
            h.fresh--;
        }
    }

    private void enqueue(UUID uid, String itemId, int slot, int delay) {
        if (itemId == null || slot < 0) {
            return;
        }
        if (pending.containsKey(uid)) {
            return; // already one queued
        }
        pending.put(uid, new Pending(itemId, slot, delay));
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerLoggedOutEvent event) {
        if (event.player == null) {
            return;
        }
        UUID uid = event.player.getUniqueID();
        lastHeld.remove(uid);
        pending.remove(uid);
    }

    // ------------------------------------------------------------------ refill / craft

    private void doRefill(EntityPlayerMP player, InventoryPlayer inv, int slot, String brokenId) {
        String type = typeOf(brokenId);

        if (enableRefill) {
            int found = findSpare(inv, slot, brokenId, type, false);
            if (found < 0) {
                found = findSpare(inv, slot, brokenId, type, true); // accept a nearly broken one
            }
            if (found >= 0) {
                ItemStack replacement = inv.mainInventory[found];
                inv.mainInventory[found] = null;
                inv.mainInventory[slot] = replacement;
                inv.markDirty();
                LOG.info("[Refill] slot {}: {} broke -> swapped in {}",
                        slot, brokenId, idOf(replacement.getItem()));
                if (showMessage) {
                    message(player, "autorefill.msg.refilled", "§a[Auto Refill] §r%s",
                            replacement.getDisplayName());
                }
                return;
            }
        }

        if (enableAutoCraft && type != null) {
            for (String craftTier : craftOrder(tierOf(brokenId))) {
                String materialId = materialOf(craftTier);
                if (materialId == null) {
                    continue;
                }
                int needMaterial = materialCount(type);
                int needStick = stickCount(type);
                if (needMaterial <= 0) {
                    continue;
                }
                if (countOf(inv, materialId) < needMaterial) {
                    continue;
                }
                if (countOf(inv, "minecraft:stick") < needStick) {
                    continue;
                }
                Item tool = Item.getByNameOrId("minecraft:" + craftTier + "_" + type);
                if (tool == null) {
                    continue;
                }
                consume(inv, materialId, needMaterial);
                consume(inv, "minecraft:stick", needStick);
                ItemStack made = new ItemStack(tool, 1);
                inv.mainInventory[slot] = made;
                inv.markDirty();
                LOG.info("[Craft] {} broke -> crafted {} from {} x{} + stick x{}",
                        brokenId, idOf(tool), materialId, needMaterial, needStick);
                if (showMessage) {
                    message(player, "autorefill.msg.crafted", "§b[Auto Refill] §r%s",
                            made.getDisplayName());
                }
                return;
            }
        }
    }

    /**
     * Find a same-type tool in the inventory.
     *
     * @param allowWeak when false, tools with a single point of durability left are skipped
     * @return inventory slot index, or -1
     */
    private static int findSpare(InventoryPlayer inv, int target, String brokenId, String type, boolean allowWeak) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < inv.mainInventory.length; i++) {
            if (i == target) {
                continue;
            }
            ItemStack stack = inv.mainInventory[i];
            if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) {
                continue;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                continue;
            }
            String id = idOf(stack.getItem());
            if (id == null) {
                continue;
            }
            boolean match = (type != null) ? type.equals(typeOf(id)) : brokenId.equals(id);
            if (!match) {
                continue;
            }
            int damage = stack.getItemDamage();
            if (damage >= max) {
                continue; // already broken
            }
            if (!allowWeak && damage >= max - 1) {
                continue; // one point left, it would break again immediately
            }
            int score = (id.equals(brokenId) ? 1000000 : 0)
                    + (tierRank(tierOf(id)) + 1) * 100000
                    + (max - damage);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tool identification

    private static String idOf(Item item) {
        if (item == null) {
            return null;
        }
        ResourceLocation name = Item.REGISTRY.getNameForObject(item);
        return name == null ? null : name.toString();
    }

    /** "minecraft:iron_pickaxe" -> "pickaxe"; null for anything that is not a vanilla tool. */
    private static String typeOf(String id) {
        if (id == null || !id.startsWith("minecraft:")) {
            return null;
        }
        String name = id.substring("minecraft:".length());
        for (String type : TYPES) {
            if (name.endsWith("_" + type)) {
                return type;
            }
        }
        return null;
    }

    /** "minecraft:iron_pickaxe" -> "iron"; null for anything that is not a vanilla tool. */
    private static String tierOf(String id) {
        if (id == null || !id.startsWith("minecraft:")) {
            return null;
        }
        String name = id.substring("minecraft:".length());
        for (String tier : TIERS) {
            if (name.startsWith(tier + "_")) {
                return tier;
            }
        }
        return null;
    }

    private static int tierRank(String tier) {
        if (tier == null) {
            return -1;
        }
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i].equals(tier)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> craftOrder(String sameTier) {
        List<String> order = new ArrayList<String>();
        if (sameTier != null) {
            order.add(sameTier);
        }
        for (String tier : CRAFT_FALLBACK) {
            if (!order.contains(tier)) {
                order.add(tier);
            }
        }
        return order;
    }

    private static String materialOf(String tier) {
        if ("wooden".equals(tier)) {
            return "minecraft:planks";
        } else if ("stone".equals(tier)) {
            return "minecraft:cobblestone";
        } else if ("iron".equals(tier)) {
            return "minecraft:iron_ingot";
        } else if ("golden".equals(tier)) {
            return "minecraft:gold_ingot";
        } else if ("diamond".equals(tier)) {
            return "minecraft:diamond";
        }
        return null;
    }

    private static int materialCount(String type) {
        if ("pickaxe".equals(type) || "axe".equals(type)) {
            return 3;
        } else if ("shovel".equals(type)) {
            return 1;
        } else if ("sword".equals(type) || "hoe".equals(type)) {
            return 2;
        }
        return 0;
    }

    private static int stickCount(String type) {
        return "sword".equals(type) ? 1 : 2;
    }

    // ------------------------------------------------------------------ inventory helpers

    private static int countOf(InventoryPlayer inv, String itemId) {
        int total = 0;
        for (ItemStack stack : inv.mainInventory) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (itemId.equals(idOf(stack.getItem()))) {
                total += stack.stackSize;
            }
        }
        return total;
    }

    private static void consume(InventoryPlayer inv, String itemId, int amount) {
        int left = amount;
        for (int i = 0; i < inv.mainInventory.length && left > 0; i++) {
            ItemStack stack = inv.mainInventory[i];
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (!itemId.equals(idOf(stack.getItem()))) {
                continue;
            }
            int take = Math.min(left, stack.stackSize);
            stack.stackSize -= take;
            left -= take;
            if (stack.stackSize <= 0) {
                inv.mainInventory[i] = null;
            }
        }
        inv.markDirty();
    }

    /**
     * Localized chat message. Falls back to the English template when the language file
     * is unavailable (e.g. on a dedicated server, whose language map has no mod entries).
     */
    private static void message(EntityPlayer player, String key, String fallback, Object... args) {
        String text;
        try {
            text = I18n.canTranslate(key) ? I18n.translateToLocalFormatted(key, args)
                    : String.format(fallback, args);
        } catch (Throwable t) {
            text = String.format(fallback, args);
        }
        player.addChatComponentMessage(new TextComponentString(text));
    }
}
