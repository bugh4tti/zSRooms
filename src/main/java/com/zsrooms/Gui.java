package com.zsrooms;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Todos los menús se leen de menu.yml (54 lugares). */
public class Gui implements Listener {

    private final ZSRooms plugin;

    public Gui(ZSRooms plugin) {
        this.plugin = plugin;
    }

    public static class Holder implements InventoryHolder {
        public final String type;
        public final String room;
        /** slot -> acción del item */
        public final Map<Integer, String> actions = new HashMap<>();
        /** slot -> nombre de la room (menú principal) */
        public final Map<Integer, String> rooms = new HashMap<>();
        public Inventory inv;

        public Holder(String type, String room) {
            this.type = type;
            this.room = room;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ---------------------------------------------------------------- textos

    private YamlConfiguration menu() {
        return plugin.menu();
    }

    private String val(String key, String def) {
        return menu().getString("values." + key, def);
    }

    private String onOff(boolean b) {
        return b ? val("enabled", "&aON") : val("disabled", "&cOFF");
    }

    private String blocksText(Room r) {
        if (r.blocks.equals("AIR")) return val("barrier-off", "&cOFF");
        return val("barrier-on", "&aON &7(%material%)").replace("%material%", r.blocks);
    }

    private String effectsText(Room r) {
        if (r.effects.isEmpty()) return val("none", "&7ninguno");
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, int[]> e : r.effects.entrySet()) {
            int s = e.getValue()[0];
            String time = s < 0 ? val("infinite", "∞") : s + val("seconds-suffix", "s");
            l.add(val("effect-format", "&f%effect% %level% &7(%time%)")
                    .replace("%effect%", e.getKey())
                    .replace("%level%", String.valueOf(e.getValue()[1]))
                    .replace("%time%", time));
        }
        return String.join("&7, ", l);
    }

    private String ph(String s, Room r) {
        if (r == null) return s;
        return s.replace("%room%", r.name)
                .replace("%players%", r.players.size() + "/2")
                .replace("%utilities%", onOff(r.utilities))
                .replace("%keepinventory%", onOff(r.keepInventory))
                .replace("%blocks%", blocksText(r))
                .replace("%effects%", effectsText(r))
                .replace("%winner%", r.lastWinner)
                .replace("%loser%", r.lastLoser);
    }

    // ---------------------------------------------------------------- construcción

    private ItemStack build(ConfigurationSection sec, Room r) {
        Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
        if (mat == null || mat == Material.AIR) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZSRooms.color(ph(sec.getString("name", " "), r)));
            List<String> lore = new ArrayList<>();
            for (String l : sec.getStringList("lore")) lore.add(ZSRooms.color(ph(l, r)));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void border(Inventory inv, ConfigurationSection sec) {
        ConfigurationSection b = sec.getConfigurationSection("border");
        if (b == null) return;
        ItemStack pane = build(b, null);
        for (int slot : b.getIntegerList("slots")) {
            if (slot >= 0 && slot < 54) inv.setItem(slot, pane);
        }
    }

    private void items(Inventory inv, Holder h, ConfigurationSection sec, Room r) {
        ConfigurationSection s = sec.getConfigurationSection("items");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            ConfigurationSection it = s.getConfigurationSection(key);
            if (it == null) continue;
            int slot = it.getInt("slot", -1);
            if (slot < 0 || slot >= 54) continue;
            inv.setItem(slot, build(it, r));
            h.actions.put(slot, it.getString("action", "none").toLowerCase());
        }
    }

    // ---------------------------------------------------------------- menús

    public void openMain(Player p) {
        ConfigurationSection sec = menu().getConfigurationSection("main");
        if (sec == null) {
            p.sendMessage(plugin.msg("menu-missing"));
            return;
        }
        Holder h = new Holder("main", null);
        Inventory inv = Bukkit.createInventory(h, 54, ZSRooms.color(sec.getString("title", "&8Rooms")));
        h.inv = inv;
        border(inv, sec);
        items(inv, h, sec, null);

        List<Integer> slots = sec.getIntegerList("room-slots");
        List<Room> list = new ArrayList<>(plugin.manager().all());
        boolean admin = p.hasPermission("zsrooms.admin");

        for (int i = 0; i < list.size() && i < slots.size(); i++) {
            Room r = list.get(i);
            int slot = slots.get(i);
            if (slot < 0 || slot >= 54) continue;

            String state = (r.closing || r.players.size() >= 2) ? "full"
                    : (r.players.size() == 1 ? "waiting" : "free");
            ConfigurationSection is = sec.getConfigurationSection("room-item." + state);
            if (is == null) continue;

            ItemStack it = build(is, r);
            if (admin) {
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                    for (String l : sec.getStringList("admin-lore")) lore.add(ZSRooms.color(ph(l, r)));
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
            }
            inv.setItem(slot, it);
            h.rooms.put(slot, r.name);
        }
        p.openInventory(inv);
    }

    public void openSettings(Player p, Room r) {
        ConfigurationSection sec = menu().getConfigurationSection("settings");
        if (sec == null) {
            p.sendMessage(plugin.msg("menu-missing"));
            return;
        }
        Holder h = new Holder("settings", r.name);
        Inventory inv = Bukkit.createInventory(h, 54, ZSRooms.color(ph(sec.getString("title", "&8Room: %room%"), r)));
        h.inv = inv;
        border(inv, sec);
        items(inv, h, sec, r);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        Holder h = (Holder) top.getHolder();
        Player p = (Player) e.getWhoClicked();
        int slot = e.getSlot();

        if (h.type.equals("main")) {
            String rn = h.rooms.get(slot);
            if (rn != null) {
                Room r = plugin.manager().get(rn);
                if (r == null) return;
                if (e.isRightClick() && p.hasPermission("zsrooms.admin")) {
                    openSettings(p, r);
                } else if (r.exit != null) {
                    p.closeInventory();
                    p.teleport(r.exit);
                }
                return;
            }
            String action = h.actions.get(slot);
            if ("close".equals(action)) p.closeInventory();
            return;
        }

        if (h.type.equals("settings")) {
            if (!p.hasPermission("zsrooms.admin")) return;
            Room r = plugin.manager().get(h.room);
            if (r == null) {
                p.closeInventory();
                return;
            }
            String action = h.actions.get(slot);
            if (action == null) return;
            switch (action) {
                case "close":
                    p.closeInventory();
                    break;
                case "back":
                    openMain(p);
                    break;
                case "toggle-utilities":
                    r.utilities = !r.utilities;
                    plugin.manager().save();
                    openSettings(p, r);
                    break;
                case "toggle-keepinventory":
                    r.keepInventory = !r.keepInventory;
                    plugin.manager().save();
                    openSettings(p, r);
                    break;
                case "toggle-blocks": {
                    int res = plugin.manager().toggleBlocks(r);
                    if (res == -1) p.sendMessage(plugin.msg("world-missing"));
                    else if (res == -2) p.sendMessage(plugin.msg("room-too-big"));
                    openSettings(p, r);
                    break;
                }
                default:
                    break;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
    }
