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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Todos los menús se leen de menu.yml (54 lugares): hub, main (lista de rooms) y settings. */
public class Gui implements Listener {

    private final ZSRooms plugin;

    public Gui(ZSRooms plugin) {
        this.plugin = plugin;
    }

    public static class Holder implements InventoryHolder {
        public final String type;
        public final String room;
        /** slot -> sección del item en menu.yml (para sus acciones) */
        public final Map<Integer, ConfigurationSection> items = new HashMap<>();
        /** slot -> nombre de la room (lista de rooms) */
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
        if (!r.barrierClosed) return val("barrier-off", "&cOFF");
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

    /** Reemplaza los placeholders (los de la room solo si hay room). */
    private String ph(String s, Room r, Player p) {
        if (r != null) {
            s = s.replace("%room%", r.name)
                    .replace("%mode%", r.modeName())
                    .replace("%players%", r.players.size() + "/" + r.capacity())
                    .replace("%utilities%", onOff(r.utilities))
                    .replace("%keepinventory%", onOff(r.keepInventory))
                    .replace("%blocks%", blocksText(r))
                    .replace("%effects%", effectsText(r))
                    .replace("%winner%", r.lastWinner)
                    .replace("%loser%", r.lastLoser);
        }
        return s.replace("%player%", p == null ? "" : p.getName())
                .replace("%rooms%", String.valueOf(plugin.manager().all().size()))
                .replace("%open_death%", String.valueOf(plugin.getConfig().getInt("settings.open-delay.death", 10)))
                .replace("%open_leave%", String.valueOf(plugin.getConfig().getInt("settings.open-delay.leave", 10)))
                .replace("%open_disconnect%", String.valueOf(plugin.getConfig().getInt("settings.open-delay.disconnect", 5)))
                .replace("%team_countdown%", String.valueOf(plugin.getConfig().getInt("team.countdown", 5)))
                .replace("%spawn_delay%", String.valueOf(plugin.getConfig().getInt("settings.spawn-delay", 5)));
    }

    // ---------------------------------------------------------------- construcción

    private ItemStack build(ConfigurationSection sec, Room r, Player p) {
        Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
        if (mat == null || mat.isAir()) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        it.setAmount(Math.max(1, Math.min(64, sec.getInt("amount", 1))));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZSRooms.color(ph(sec.getString("name", " "), r, p)));
            List<String> lore = new ArrayList<>();
            for (String l : sec.getStringList("lore")) lore.add(ZSRooms.color(ph(l, r, p)));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void border(Inventory inv, ConfigurationSection sec, Player p) {
        ConfigurationSection b = sec.getConfigurationSection("border");
        if (b == null) return;
        ItemStack pane = build(b, null, p);
        for (int slot : b.getIntegerList("slots")) {
            if (slot >= 0 && slot < 54) inv.setItem(slot, pane);
        }
    }

    private void items(Inventory inv, Holder h, ConfigurationSection sec, Room r, Player p) {
        ConfigurationSection s = sec.getConfigurationSection("items");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            ConfigurationSection it = s.getConfigurationSection(key);
            if (it == null) continue;
            int slot = it.getInt("slot", -1);
            if (slot < 0 || slot >= 54) continue;
            inv.setItem(slot, build(it, r, p));
            h.items.put(slot, it);
        }
    }

    // ---------------------------------------------------------------- menús

    /** Menú principal (/room): el hub. Si menu.yml es viejo y no lo tiene, abre la lista de rooms. */
    public void openMain(Player p) {
        ConfigurationSection sec = menu().getConfigurationSection("hub");
        if (sec == null) {
            openRooms(p);
            return;
        }
        Holder h = new Holder("hub", null);
        Inventory inv = Bukkit.createInventory(h, 54, ZSRooms.color(ph(sec.getString("title", "&8zSRooms"), null, p)));
        h.inv = inv;
        border(inv, sec, p);
        items(inv, h, sec, null, p);
        p.openInventory(inv);
    }

    /** Lista de rooms. */
    public void openRooms(Player p) {
        ConfigurationSection sec = menu().getConfigurationSection("main");
        if (sec == null) {
            p.sendMessage(plugin.msg("menu-missing"));
            return;
        }
        Holder h = new Holder("main", null);
        Inventory inv = Bukkit.createInventory(h, 54, ZSRooms.color(ph(sec.getString("title", "&8Rooms"), null, p)));
        h.inv = inv;
        border(inv, sec, p);
        items(inv, h, sec, null, p);

        List<Integer> slots = sec.getIntegerList("room-slots");
        List<Room> list = new ArrayList<>(plugin.manager().all());
        boolean admin = p.hasPermission("zsrooms.admin");

        for (int i = 0; i < list.size() && i < slots.size(); i++) {
            Room r = list.get(i);
            int slot = slots.get(i);
            if (slot < 0 || slot >= 54) continue;

            String state = (r.active || r.closing || r.players.size() >= r.capacity()) ? "full"
                    : (r.players.size() >= 1 ? "waiting" : "free");
            ConfigurationSection is = sec.getConfigurationSection("room-item." + state);
            if (is == null) continue;

            ItemStack it = build(is, r, p);
            if (admin) {
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                    for (String l : sec.getStringList("admin-lore")) lore.add(ZSRooms.color(ph(l, r, p)));
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
        Inventory inv = Bukkit.createInventory(h, 54, ZSRooms.color(ph(sec.getString("title", "&8Room: %room%"), r, p)));
        h.inv = inv;
        border(inv, sec, p);
        items(inv, h, sec, r, p);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- acciones

    private String clickKey(ClickType t) {
        switch (t) {
            case LEFT:
                return "left-actions";
            case RIGHT:
                return "right-actions";
            case SHIFT_LEFT:
                return "shift-left-actions";
            case SHIFT_RIGHT:
                return "shift-right-actions";
            case MIDDLE:
                return "middle-actions";
            default:
                return null;
        }
    }

    /** Junta las acciones del item según el click y las ejecuta en orden. */
    private void runItem(Player p, Holder h, ConfigurationSection sec, ClickType click) {
        List<String> acts = new ArrayList<>();
        String single = sec.getString("action");
        if (single != null) acts.add(single);
        acts.addAll(sec.getStringList("actions"));
        String key = clickKey(click);
        if (key != null) acts.addAll(sec.getStringList(key));
        for (String a : acts) run(p, h, a);
    }

    private void run(Player p, Holder h, String raw) {
        String a = raw == null ? "" : raw.trim();
        if (a.isEmpty()) return;

        switch (a.toLowerCase()) {
            case "none":
                return;
            case "close":
                p.closeInventory();
                return;
            case "back":
                if (h.type.equals("settings")) openRooms(p);
                else if (h.type.equals("main")) openMain(p);
                else p.closeInventory();
                return;
            case "toggle-utilities":
            case "toggle-keepinventory":
            case "toggle-blocks":
                toggle(p, h, a.toLowerCase());
                return;
            default:
                break;
        }

        if (!a.startsWith("[")) return;
        int end = a.indexOf(']');
        if (end < 0) return;
        String tag = a.substring(1, end).trim().toLowerCase();
        String arg = a.substring(end + 1).trim().replace("%player%", p.getName());

        switch (tag) {
            case "close":
                p.closeInventory();
                break;
            case "menu":
                if (arg.equalsIgnoreCase("hub")) openMain(p);
                else if (arg.equalsIgnoreCase("rooms")) openRooms(p);
                break;
            case "tp": {
                Room r = plugin.manager().get(arg);
                if (r == null) {
                    p.sendMessage(plugin.msg("room-not-found", "%room%", arg));
                } else if (r.exit != null) {
                    p.closeInventory();
                    p.teleport(r.exit);
                }
                break;
            }
            case "player":
                if (!arg.isEmpty()) p.performCommand(arg.startsWith("/") ? arg.substring(1) : arg);
                break;
            case "console":
                if (!arg.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg.startsWith("/") ? arg.substring(1) : arg);
                }
                break;
            case "message":
                p.sendMessage(ZSRooms.color(arg));
                break;
            case "sound":
                try {
                    p.playSound(p.getLocation(), arg, 1f, 1f);
                } catch (Exception ignored) {
                }
                break;
            default:
                break;
        }
    }

    private void toggle(Player p, Holder h, String what) {
        if (!h.type.equals("settings") || !p.hasPermission("zsrooms.admin")) return;
        Room r = plugin.manager().get(h.room);
        if (r == null) {
            p.closeInventory();
            return;
        }
        switch (what) {
            case "toggle-utilities":
                r.utilities = !r.utilities;
                plugin.manager().save();
                break;
            case "toggle-keepinventory":
                r.keepInventory = !r.keepInventory;
                plugin.manager().save();
                break;
            case "toggle-blocks": {
                int res = plugin.manager().toggleBarrier(r);
                if (res == -1) p.sendMessage(plugin.msg("world-missing"));
                else if (res == -2) p.sendMessage(plugin.msg("room-too-big"));
                break;
            }
            default:
                break;
        }
        openSettings(p, r);
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

        // click en una room de la lista
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
        }

        // click en un item con acciones (hub, botones de la lista y ajustes)
        ConfigurationSection sec = h.items.get(slot);
        if (sec == null) return;
        runItem(p, h, sec, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
