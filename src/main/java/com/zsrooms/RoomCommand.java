package com.zsrooms;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RoomCommand implements CommandExecutor, TabCompleter {

    private final ZSRooms plugin;

    public RoomCommand(ZSRooms plugin) {
        this.plugin = plugin;
    }

    private void usage(CommandSender s, String u) {
        s.sendMessage(plugin.msg("usage", "%usage%", u));
    }

    private Room room(CommandSender s, String name) {
        Room r = plugin.manager().get(name);
        if (r == null) s.sendMessage(plugin.msg("room-not-found", "%room%", name));
        return r;
    }

    private Boolean onOff(String s) {
        if (s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false")) return false;
        return null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        RoomManager m = plugin.manager();

        if (a.length == 0) {
            if (!(s instanceof Player)) {
                s.sendMessage(plugin.msg("players-only"));
                return true;
            }
            if (!s.hasPermission("zsrooms.use")) {
                s.sendMessage(plugin.msg("no-permission"));
                return true;
            }
            plugin.gui().openMain((Player) s);
            return true;
        }

        if (!s.hasPermission("zsrooms.admin")) {
            s.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "wand": {
                if (!(s instanceof Player)) {
                    s.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                if (a.length < 2) {
                    usage(s, "/room wand <hacha|pico|azada>");
                    return true;
                }
                if (!Wands.give((Player) s, a[1])) {
                    s.sendMessage(plugin.msg("invalid-wand"));
                    return true;
                }
                s.sendMessage(plugin.msg("wand-given", "%wand%", a[1].toLowerCase()));
                return true;
            }

            case "create": {
                if (!(s instanceof Player)) {
                    s.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                if (a.length < 2) {
                    usage(s, "/room create <nombre>");
                    return true;
                }
                Player p = (Player) s;
                String name = a[1];
                if (!name.matches("[A-Za-z0-9_-]{1,16}")) {
                    s.sendMessage(plugin.msg("invalid-name"));
                    return true;
                }
                if (m.get(name) != null) {
                    s.sendMessage(plugin.msg("room-exists"));
                    return true;
                }
                RoomManager.Selection sel = m.selections.get(p.getUniqueId());
                List<String> miss = new ArrayList<>();
                if (sel == null || sel.pos1 == null) miss.add("pos1 de la room (hacha, click izq)");
                if (sel == null || sel.pos2 == null) miss.add("pos2 de la room (hacha, click der)");
                if (sel == null || sel.exit == null) miss.add("zona del pico (click izq)");
                if (sel == null || sel.bpos1 == null) miss.add("pos1 de la barrera (azada, click izq)");
                if (sel == null || sel.bpos2 == null) miss.add("pos2 de la barrera (azada, click der)");
                if (!miss.isEmpty()) {
                    s.sendMessage(plugin.msg("selection-missing", "%missing%", String.join(", ", miss)));
                    return true;
                }
                if (sel.pos1.getWorld() != sel.pos2.getWorld()
                        || sel.pos1.getWorld() != sel.exit.getWorld()
                        || sel.pos1.getWorld() != sel.bpos1.getWorld()
                        || sel.pos1.getWorld() != sel.bpos2.getWorld()) {
                    s.sendMessage(plugin.msg("selection-world"));
                    return true;
                }
                Room r = m.create(name, sel);
                if (r == null) {
                    s.sendMessage(plugin.msg("exit-inside"));
                    return true;
                }
                s.sendMessage(plugin.msg("room-created", "%room%", r.name));
                return true;
            }

            case "save": {
                if (!(s instanceof Player)) {
                    s.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                if (a.length < 2) {
                    usage(s, "/room save <room>");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                Player p = (Player) s;
                RoomManager.SaveResult res = m.saveFromSelection(r, m.selections.get(p.getUniqueId()));
                if (res.errorKey != null) {
                    s.sendMessage(plugin.msg(res.errorKey, "%room%", r.name, "%tool%", res.detail));
                    return true;
                }
                s.sendMessage(plugin.msg("room-saved", "%room%", r.name, "%updated%", String.join(", ", res.updated)));
                return true;
            }

            case "delete": {
                if (a.length < 2) {
                    usage(s, "/room delete <nombre>");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                String n = r.name;
                m.delete(r);
                s.sendMessage(plugin.msg("room-deleted", "%room%", n));
                return true;
            }

            case "setname": {
                if (a.length < 3) {
                    usage(s, "/room setname <room> <nuevo nombre>");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                if (!a[2].matches("[A-Za-z0-9_-]{1,16}")) {
                    s.sendMessage(plugin.msg("invalid-name"));
                    return true;
                }
                String old = r.name;
                if (!m.rename(r, a[2])) {
                    s.sendMessage(plugin.msg("room-exists"));
                    return true;
                }
                s.sendMessage(plugin.msg("room-renamed", "%old%", old, "%room%", r.name));
                return true;
            }

            case "utilities": {
                if (a.length < 3) {
                    usage(s, "/room utilities <room> on/off");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                Boolean v = onOff(a[2]);
                if (v == null) {
                    usage(s, "/room utilities <room> on/off");
                    return true;
                }
                r.utilities = v;
                m.save();
                s.sendMessage(plugin.msg("utilities-set", "%room%", r.name, "%value%", v ? "on" : "off"));
                return true;
            }

            case "keepinventory": {
                if (a.length < 3) {
                    usage(s, "/room keepinventory <room> true/false");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                Boolean v = onOff(a[2]);
                if (v == null) {
                    usage(s, "/room keepinventory <room> true/false");
                    return true;
                }
                r.keepInventory = v;
                m.save();
                s.sendMessage(plugin.msg("keepinventory-set", "%room%", r.name, "%value%", String.valueOf(v)));
                return true;
            }

            case "effects": {
                if (a.length < 4) {
                    usage(s, "/room effects <room> add <efecto> <segundos|infinite> [nivel]  |  /room effects <room> remove <efecto>");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                PotionEffectType t = RoomManager.effect(a[3]);
                if (t == null) {
                    s.sendMessage(plugin.msg("invalid-effect"));
                    return true;
                }
                String key = RoomManager.key(t);
                if (a[2].equalsIgnoreCase("add")) {
                    if (a.length < 5) {
                        usage(s, "/room effects <room> add <efecto> <segundos|infinite> [nivel]");
                        return true;
                    }
                    int secs;
                    if (a[4].equalsIgnoreCase("infinite") || a[4].equalsIgnoreCase("infinito") || a[4].equalsIgnoreCase("inf")) {
                        secs = -1;
                    } else {
                        try {
                            secs = Integer.parseInt(a[4]);
                        } catch (NumberFormatException ex) {
                            s.sendMessage(plugin.msg("invalid-time"));
                            return true;
                        }
                        if (secs <= 0) {
                            s.sendMessage(plugin.msg("invalid-time"));
                            return true;
                        }
                    }
                    int lvl = 1;
                    if (a.length >= 6) {
                        try {
                            lvl = Math.max(1, Integer.parseInt(a[5]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    r.effects.put(key, new int[]{secs, lvl});
                    for (Player p : m.online(r)) m.applyEffects(p, r);
                    m.save();
                    s.sendMessage(plugin.msg("effect-added", "%effect%", key, "%room%", r.name));
                    return true;
                }
                if (a[2].equalsIgnoreCase("remove")) {
                    r.effects.remove(key);
                    for (Player p : m.online(r)) p.removePotionEffect(t);
                    m.save();
                    s.sendMessage(plugin.msg("effect-removed", "%effect%", key, "%room%", r.name));
                    return true;
                }
                usage(s, "/room effects <room> add/remove <efecto> ...");
                return true;
            }

            case "blocks": {
                if (a.length < 3) {
                    usage(s, "/room blocks <room> on/off/<MATERIAL>");
                    return true;
                }
                Room r = room(s, a[1]);
                if (r == null) return true;
                String arg = a[2];
                int res;
                String value;
                if (arg.equalsIgnoreCase("on")) {
                    res = m.closeBarrier(r);
                    value = "cerrada";
                } else if (arg.equalsIgnoreCase("off")) {
                    res = m.openBarrier(r);
                    value = "abierta";
                } else {
                    Material mat = Material.matchMaterial(arg);
                    if (mat == null || !mat.isBlock() || mat.isAir()) {
                        s.sendMessage(plugin.msg("invalid-material"));
                        return true;
                    }
                    res = m.changeBlocks(r, mat);
                    value = "material " + mat.name();
                }
                if (res == -1) {
                    s.sendMessage(plugin.msg("world-missing"));
                } else if (res == -2) {
                    s.sendMessage(plugin.msg("room-too-big"));
                } else {
                    s.sendMessage(plugin.msg("blocks-set", "%room%", r.name, "%value%", value, "%count%", String.valueOf(res)));
                }
                return true;
            }

            case "reload": {
                plugin.reloadAll();
                s.sendMessage(plugin.msg("reloaded"));
                return true;
            }

            default: {
                for (String line : plugin.getConfig().getStringList("messages.help")) {
                    s.sendMessage(ZSRooms.color(line));
                }
                return true;
            }
        }
    }

    // ---------------------------------------------------------------- tab

    private List<String> filter(List<String> opts, String start) {
        List<String> out = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(start.toLowerCase())) out.add(o);
        }
        return out;
    }

    private List<String> roomNames() {
        List<String> l = new ArrayList<>();
        for (Room r : plugin.manager().all()) l.add(r.name);
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("zsrooms.admin")) return new ArrayList<>();
        if (a.length == 1) {
            return filter(Arrays.asList("wand", "create", "save", "delete", "setname", "utilities",
                    "effects", "keepinventory", "blocks", "reload"), a[0]);
        }
        String sub = a[0].toLowerCase();
        if (a.length == 2) {
            if (sub.equals("wand")) {
                return filter(Arrays.asList("hacha", "pico", "azada"), a[1]);
            }
            if (Arrays.asList("save", "delete", "setname", "utilities", "effects", "keepinventory", "blocks").contains(sub)) {
                return filter(roomNames(), a[1]);
            }
            return new ArrayList<>();
        }
        if (a.length == 3) {
            switch (sub) {
                case "utilities":
                    return filter(Arrays.asList("on", "off"), a[2]);
                case "keepinventory":
                    return filter(Arrays.asList("true", "false"), a[2]);
                case "effects":
                    return filter(Arrays.asList("add", "remove"), a[2]);
                case "blocks": {
                    List<String> mats = new ArrayList<>(Arrays.asList("on", "off"));
                    for (Material mt : Material.values()) {
                        if (mt.isBlock() && !mt.isAir() && !mt.name().startsWith("LEGACY_")) mats.add(mt.name());
                    }
                    return filter(mats, a[2]);
                }
                default:
                    return new ArrayList<>();
            }
        }
        if (a.length == 4 && sub.equals("effects")) {
            List<String> l = new ArrayList<>();
            for (PotionEffectType t : PotionEffectType.values()) {
                if (t != null) l.add(RoomManager.key(t));
            }
            return filter(l, a[3]);
        }
        if (a.length == 5 && sub.equals("effects") && a[2].equalsIgnoreCase("add")) {
            return filter(Arrays.asList("infinite", "30", "60", "300", "600"), a[4]);
        }
        return new ArrayList<>();
    }
            }
