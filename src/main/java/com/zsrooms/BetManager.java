package com.zsrooms;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Apuestas: menú tipo /trade, escrow de items y pago al ganador.
 * Los items confirmados se guardan en bet-escrow.yml; si algo se corta se devuelven
 * (bet-refunds.yml) cuando el jugador entra.
 */
public class BetManager implements Listener {

    public static class BetHolder implements InventoryHolder {
        public final BetSession session;
        public final int side;
        public Inventory inv;

        public BetHolder(BetSession session, int side) {
            this.session = session;
            this.side = side;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final ZSRooms plugin;
    private final File escrowFile;
    private final File refundFile;
    /** Apuestas confirmadas (items en escrow). */
    private final Map<UUID, BetSession> locked = new HashMap<>();
    /** Items pendientes de devolver a jugadores que no estaban conectados. */
    private final Map<UUID, List<ItemStack>> refunds = new HashMap<>();

    public BetManager(ZSRooms plugin) {
        this.plugin = plugin;
        this.escrowFile = new File(plugin.getDataFolder(), "bet-escrow.yml");
        this.refundFile = new File(plugin.getDataFolder(), "bet-refunds.yml");
    }

    // ---------------------------------------------------------------- archivos

    private List<ItemStack> readItems(List<?> raw) {
        List<ItemStack> l = new ArrayList<>();
        if (raw == null) return l;
        for (Object o : raw) {
            if (o instanceof ItemStack) l.add((ItemStack) o);
        }
        return l;
    }

    private List<ItemStack> nonNull(ItemStack[] items) {
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack it : items) {
            if (it != null) l.add(it);
        }
        return l;
    }

    public void load() {
        refunds.clear();
        if (refundFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(refundFile);
            ConfigurationSection s = y.getConfigurationSection("refunds");
            if (s != null) {
                for (String k : s.getKeys(false)) {
                    try {
                        refunds.computeIfAbsent(UUID.fromString(k), x -> new ArrayList<>()).addAll(readItems(s.getList(k)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        // apuestas que quedaron confirmadas por un cierre brusco: se devuelven a sus dueños
        if (escrowFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(escrowFile);
            ConfigurationSection s = y.getConfigurationSection("sessions");
            if (s != null) {
                for (String k : s.getKeys(false)) {
                    ConfigurationSection c = s.getConfigurationSection(k);
                    if (c == null) continue;
                    for (String side : new String[]{"a", "b"}) {
                        try {
                            UUID id = UUID.fromString(c.getString(side, ""));
                            refunds.computeIfAbsent(id, x -> new ArrayList<>()).addAll(readItems(c.getList(side + "-items")));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            saveRefunds();
            escrowFile.delete();
        }
        for (Player p : Bukkit.getOnlinePlayers()) deliverRefunds(p);
    }

    private void saveRefunds() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> e : refunds.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("refunds." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(refundFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar bet-refunds.yml: " + ex.getMessage());
        }
    }

    private void saveEscrow() {
        if (locked.isEmpty()) {
            if (escrowFile.exists()) escrowFile.delete();
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        for (BetSession s : locked.values()) {
            String p = "sessions." + s.id + ".";
            y.set(p + "a", s.ids[0].toString());
            y.set(p + "b", s.ids[1].toString());
            y.set(p + "a-items", nonNull(s.offers[0]));
            y.set(p + "b-items", nonNull(s.offers[1]));
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(escrowFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar bet-escrow.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- items

    /** Los items van al inventario; lo que no entra se tira a los pies del jugador. */
    private void giveItems(Player p, List<ItemStack> items) {
        if (items.isEmpty()) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack it : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
    }

    /** Devuelve los items a su dueño; si no está conectado quedan guardados para cuando entre. */
    private void returnItems(UUID id, ItemStack[] items) {
        List<ItemStack> list = nonNull(items);
        if (list.isEmpty()) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            giveItems(p, list);
        } else {
            refunds.computeIfAbsent(id, x -> new ArrayList<>()).addAll(list);
            saveRefunds();
        }
    }

    private void deliverRefunds(Player p) {
        List<ItemStack> l = refunds.remove(p.getUniqueId());
        if (l == null || l.isEmpty()) return;
        giveItems(p, l);
        saveRefunds();
        p.sendMessage(plugin.msg("bet-refund-delivered"));
    }

    private boolean canHold(PlayerInventory inv, ItemStack it) {
        int need = it.getAmount();
        for (ItemStack c : inv.getStorageContents()) {
            if (c == null || c.getType().isAir()) return true;
            if (c.isSimilar(it)) need -= (c.getMaxStackSize() - c.getAmount());
            if (need <= 0) return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) deliverRefunds(p);
        }, 20L);
    }

    // ---------------------------------------------------------------- textos del menú

    private String fill(String text, BetSession s, int side) {
        return text.replace("%room%", s.room.name)
                .replace("%player%", s.names[side])
                .replace("%rival%", s.names[1 - side])
                .replace("%items%", String.valueOf(s.count(side)))
                .replace("%rival_items%", String.valueOf(s.count(1 - side)))
                .replace("%time%", String.valueOf(Math.max(0, s.countdown)));
    }

    private ItemStack item(ConfigurationSection sec, BetSession s, int side) {
        Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
        if (mat == null || mat.isAir()) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        it.setAmount(Math.max(1, Math.min(64, sec.getInt("amount", 1))));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZSRooms.color(fill(sec.getString("name", " "), s, side)));
            List<String> lore = new ArrayList<>();
            for (String l : sec.getStringList("lore")) lore.add(ZSRooms.color(fill(l, s, side)));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean valid(int slot) {
        return slot >= 0 && slot < 54;
    }

    private void put(Inventory inv, ConfigurationSection btn, String slotKey, String variant, BetSession s, int side) {
        ConfigurationSection base = btn.getConfigurationSection(slotKey);
        ConfigurationSection v = btn.getConfigurationSection(variant);
        if (base == null || v == null) return;
        int slot = base.getInt("slot", -1);
        if (valid(slot)) inv.setItem(slot, item(v, s, side));
    }

    private int slotOf(ConfigurationSection sec, String key) {
        ConfigurationSection b = sec.getConfigurationSection("buttons." + key);
        return b == null ? -1 : b.getInt("slot", -1);
    }

    /** Dibuja el menú de los dos jugadores. */
    private void render(BetSession s) {
        ConfigurationSection sec = plugin.menu().getConfigurationSection("bet");
        if (sec == null) return;
        List<Integer> own = sec.getIntegerList("own-slots");
        List<Integer> oth = sec.getIntegerList("other-slots");
        ConfigurationSection btn = sec.getConfigurationSection("buttons");
        ConfigurationSection border = sec.getConfigurationSection("border");
        ConfigurationSection items = sec.getConfigurationSection("items");

        for (int side = 0; side < 2; side++) {
            Inventory inv = s.invs[side];
            if (inv == null) continue;
            inv.clear();

            if (border != null) {
                ItemStack pane = item(border, s, side);
                for (int slot : border.getIntegerList("slots")) {
                    if (valid(slot)) inv.setItem(slot, pane);
                }
            }
            if (items != null) {
                for (String k : items.getKeys(false)) {
                    ConfigurationSection it = items.getConfigurationSection(k);
                    if (it == null) continue;
                    int slot = it.getInt("slot", -1);
                    if (valid(slot)) inv.setItem(slot, item(it, s, side));
                }
            }
            for (int i = 0; i < s.offers[side].length; i++) {
                ItemStack mine = s.offers[side][i];
                if (mine != null && i < own.size() && valid(own.get(i))) inv.setItem(own.get(i), mine.clone());
                ItemStack theirs = s.offers[1 - side][i];
                if (theirs != null && i < oth.size() && valid(oth.get(i))) inv.setItem(oth.get(i), theirs.clone());
            }
            if (btn != null) {
                put(inv, btn, "accept", s.accepted[side] ? "accepted" : "accept", s, side);
                put(inv, btn, "other-waiting", s.accepted[1 - side] ? "other-accepted" : "other-waiting", s, side);
                put(inv, btn, "countdown-idle", s.countdown >= 0 ? "countdown-running" : "countdown-idle", s, side);
                put(inv, btn, "reject", "reject", s, side);
                put(inv, btn, "clear", "clear", s, side);
            }
        }
    }

    // ---------------------------------------------------------------- empezar la apuesta

    /** Entraron los 2 a una room de apuesta: se abre el menú (si no hay menú, empieza el duelo normal). */
    public void start(Room r) {
        ConfigurationSection sec = plugin.menu().getConfigurationSection("bet");
        List<Player> ps = plugin.manager().online(r);
        int cap = 0;
        if (sec != null) cap = Math.min(sec.getIntegerList("own-slots").size(), sec.getIntegerList("other-slots").size());
        if (sec == null || cap == 0 || ps.size() < 2) {
            plugin.getLogger().warning("Falta la sección 'bet' en menu.yml: la room " + r.name + " empieza sin apuesta.");
            plugin.manager().startDuel(r);
            return;
        }

        Player a = ps.get(0);
        Player b = ps.get(1);
        BetSession s = new BetSession(r, a.getUniqueId(), a.getName(), b.getUniqueId(), b.getName(), cap);
        r.betSession = s;
        for (int side = 0; side < 2; side++) {
            BetHolder h = new BetHolder(s, side);
            h.inv = Bukkit.createInventory(h, 54, ZSRooms.color(fill(sec.getString("title", "&8Apuesta"), s, side)));
            s.invs[side] = h.inv;
        }
        render(s);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (s.finished) return;
            for (int side = 0; side < 2; side++) {
                Player p = Bukkit.getPlayer(s.ids[side]);
                if (p == null) continue;
                p.openInventory(s.invs[side]);
                p.sendMessage(plugin.msg("bet-opened"));
            }
        });
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof BetHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;

        BetHolder h = (BetHolder) top.getHolder();
        BetSession s = h.session;
        if (s.finished || s.locked) return;

        ClickType ct = e.getClick();
        if (ct != ClickType.LEFT && ct != ClickType.RIGHT
                && ct != ClickType.SHIFT_LEFT && ct != ClickType.SHIFT_RIGHT) {
            return;
        }
        Inventory clicked = e.getClickedInventory();
        ConfigurationSection sec = plugin.menu().getConfigurationSection("bet");
        if (clicked == null || sec == null) return;

        Player p = (Player) e.getWhoClicked();
        int side = h.side;
        int slot = e.getSlot();

        if (clicked == top) {
            int idx = sec.getIntegerList("own-slots").indexOf(slot);
            if (idx >= 0 && idx < s.offers[side].length) {
                takeBack(p, s, side, idx);
            } else if (slot == slotOf(sec, "accept")) {
                toggleAccept(p, s, side);
            } else if (slot == slotOf(sec, "reject")) {
                cancel(s, true, "bet-cancelled-rejected", p.getName(), null);
            } else if (slot == slotOf(sec, "clear")) {
                clearOwn(p, s, side);
            }
            return;
        }

        // inventario del jugador: el item se va directo al menú
        if (slot < 0 || slot > 35) return;
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) return;
        offer(p, s, side, slot, it);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof BetHolder) e.setCancelled(true);
    }

    /** Si alguien cierra el menú antes de confirmar, se cancela todo. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof BetHolder)) return;
        BetSession s = ((BetHolder) e.getInventory().getHolder()).session;
        if (s.finished || s.locked) return;
        Player closer = e.getPlayer() instanceof Player ? (Player) e.getPlayer() : null;
        cancel(s, true, "bet-cancelled-closed", e.getPlayer().getName(), closer);
    }

    // ---------------------------------------------------------------- acciones del menú

    private void offer(Player p, BetSession s, int side, int invSlot, ItemStack it) {
        for (String name : plugin.getConfig().getStringList("bet.blocked-items")) {
            if (it.getType().name().equalsIgnoreCase(name)) {
                p.sendMessage(plugin.msg("bet-blocked"));
                return;
            }
        }
        int free = -1;
        for (int i = 0; i < s.offers[side].length; i++) {
            if (s.offers[side][i] == null) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            p.sendMessage(plugin.msg("bet-full"));
            return;
        }
        s.offers[side][free] = it.clone();
        p.getInventory().setItem(invSlot, null);
        changed(s);
    }

    private void takeBack(Player p, BetSession s, int side, int idx) {
        ItemStack it = s.offers[side][idx];
        if (it == null) return;
        if (!canHold(p.getInventory(), it)) {
            p.sendMessage(plugin.msg("bet-inventory-full"));
            return;
        }
        p.getInventory().addItem(it);
        s.offers[side][idx] = null;
        changed(s);
    }

    private void clearOwn(Player p, BetSession s, int side) {
        boolean full = false;
        for (int i = 0; i < s.offers[side].length; i++) {
            ItemStack it = s.offers[side][i];
            if (it == null) continue;
            if (canHold(p.getInventory(), it)) {
                p.getInventory().addItem(it);
                s.offers[side][i] = null;
            } else {
                full = true;
            }
        }
        if (full) p.sendMessage(plugin.msg("bet-inventory-full"));
        changed(s);
    }

    /** Cualquier cambio en los items: se quitan las aceptaciones y el contador. */
    private void changed(BetSession s) {
        s.accepted[0] = false;
        s.accepted[1] = false;
        cancelCountdown(s);
        render(s);
    }

    private void toggleAccept(Player p, BetSession s, int side) {
        if (s.accepted[side]) {
            s.accepted[side] = false;
            cancelCountdown(s);
            render(s);
            return;
        }
        int min = plugin.getConfig().getInt("bet.min-items", 1);
        if (s.count(side) < min) {
            p.sendMessage(plugin.msg("bet-need-items", "%min%", String.valueOf(min)));
            return;
        }
        s.accepted[side] = true;
        Player other = Bukkit.getPlayer(s.ids[1 - side]);
        if (other != null) other.sendMessage(plugin.msg("bet-accepted-other", "%player%", p.getName()));
        if (s.accepted[0] && s.accepted[1]) {
            startCountdown(s);
        } else {
            render(s);
        }
    }

    // ---------------------------------------------------------------- contador

    private void cancelCountdown(BetSession s) {
        if (s.task != null) {
            s.task.cancel();
            s.task = null;
        }
        s.countdown = -1;
    }

    private void tick(BetSession s) {
        for (int side = 0; side < 2; side++) {
            Player p = Bukkit.getPlayer(s.ids[side]);
            if (p != null) plugin.playSound(p, "countdown");
        }
    }

    private void startCountdown(BetSession s) {
        cancelCountdown(s);
        s.countdown = Math.max(1, plugin.getConfig().getInt("bet.countdown", 10));
        for (int side = 0; side < 2; side++) {
            Player p = Bukkit.getPlayer(s.ids[side]);
            if (p != null) p.sendMessage(plugin.msg("bet-countdown-start", "%time%", String.valueOf(s.countdown)));
        }
        render(s);
        tick(s);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (s.finished || s.locked) {
                cancelCountdown(s);
                return;
            }
            s.countdown--;
            if (s.countdown <= 0) {
                cancelCountdown(s);
                lock(s);
                return;
            }
            render(s);
            tick(s);
        }, 20L, 20L);
    }

    /** Termina el contador: los items quedan en escrow y empieza el PvP. */
    private void lock(BetSession s) {
        if (s.finished) return;
        Room r = s.room;
        Player a = Bukkit.getPlayer(s.ids[0]);
        Player b = Bukkit.getPlayer(s.ids[1]);
        if (a == null || b == null || !r.players.contains(s.ids[0]) || !r.players.contains(s.ids[1])) {
            cancel(s, false, "bet-cancelled-left", "Un jugador", null);
            return;
        }
        s.locked = true;
        s.countdown = -1;
        locked.put(s.id, s);
        saveEscrow();

        a.closeInventory();
        b.closeInventory();
        a.sendMessage(plugin.msg("bet-locked"));
        b.sendMessage(plugin.msg("bet-locked"));
        plugin.manager().startDuel(r);
    }

    // ---------------------------------------------------------------- cancelar / pagar / devolver

    private void clearSession(BetSession s) {
        if (s.room.betSession == s) s.room.betSession = null;
        boolean wasLocked = locked.remove(s.id) != null;
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < s.offers[side].length; i++) s.offers[side][i] = null;
        }
        if (wasLocked) saveEscrow();
    }

    /**
     * Cancela la apuesta (antes de confirmar) y devuelve los items.
     * eject = true manda a los dos a la zona del pico.
     */
    public void cancel(BetSession s, boolean eject, String reasonKey, String who, Player skipClose) {
        if (s.finished) return;
        s.finished = true;
        cancelCountdown(s);

        for (int side = 0; side < 2; side++) {
            Player p = Bukkit.getPlayer(s.ids[side]);
            if (p == null || p == skipClose) continue;
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof BetHolder) p.closeInventory();
        }
        for (int side = 0; side < 2; side++) returnItems(s.ids[side], s.offers[side]);
        clearSession(s);
        for (int side = 0; side < 2; side++) {
            Player p = Bukkit.getPlayer(s.ids[side]);
            if (p != null) p.sendMessage(plugin.msg(reasonKey, "%player%", who));
        }
        if (eject) {
            Room r = s.room;
            Bukkit.getScheduler().runTask(plugin, () -> plugin.manager().ejectAll(r));
        }
    }

    /** Alguien salió de la room: si la apuesta todavía no estaba confirmada, se cancela. */
    public void onPlayerLeft(Room r, String name) {
        BetSession s = r.betSession;
        if (s != null && !s.locked && !s.finished) cancel(s, false, "bet-cancelled-left", name, null);
    }

    /** El ganador se lleva los items de los dos. */
    public void settle(Room r, Player winner, Player loser) {
        BetSession s = r.betSession;
        if (s == null || !s.locked || s.finished) return;
        s.finished = true;

        List<ItemStack> all = new ArrayList<>();
        all.addAll(nonNull(s.offers[0]));
        all.addAll(nonNull(s.offers[1]));
        int total = s.amount(0) + s.amount(1);

        giveItems(winner, all);
        clearSession(s);
        winner.sendMessage(plugin.msg("bet-won", "%items%", String.valueOf(total)));
        if (loser != null) loser.sendMessage(plugin.msg("bet-lost"));
    }

    /** Devuelve todo a sus dueños (empate, room borrada o servidor apagándose). */
    public void refund(Room r) {
        BetSession s = r.betSession;
        if (s == null || s.finished) return;
        if (!s.locked) {
            cancel(s, false, "bet-refunded", "", null);
            return;
        }
        s.finished = true;
        for (int side = 0; side < 2; side++) returnItems(s.ids[side], s.offers[side]);
        clearSession(s);
        for (int side = 0; side < 2; side++) {
            Player p = Bukkit.getPlayer(s.ids[side]);
            if (p != null) p.sendMessage(plugin.msg("bet-refunded"));
        }
    }

    public void shutdown() {
        for (Room r : new ArrayList<>(plugin.manager().all())) refund(r);
    }
                            }
