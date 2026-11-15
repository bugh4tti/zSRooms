package com.zsrooms;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class RoomManager {

    /** Motivo por el que un jugador deja la room. */
    public enum Cause { DEATH, LEAVE, DISCONNECT }

    public static class Selection {
        public Location pos1, pos2, exit, bpos1, bpos2;
    }

    /** Resultado de /room save. */
    public static class SaveResult {
        public String errorKey;
        public String detail = "";
        public final List<String> updated = new ArrayList<>();
    }

    /** Resultado de recargar room.yml. */
    public static class ReloadResult {
        public int applied = 0;
        public final List<String> warnings = new ArrayList<>();
    }

    private final ZSRooms plugin;
    private final File file;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    public final Map<UUID, Selection> selections = new HashMap<>();
    public final Map<UUID, Location> respawn = new HashMap<>();

    public RoomManager(ZSRooms plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "room.yml");
    }

    // ---------------------------------------------------------------- datos (room.yml)

    public void load() {
        rooms.clear();
        File src = file;
        if (!file.exists()) {
            // migración: antes se guardaba en rooms.yml
            File old = new File(plugin.getDataFolder(), "rooms.yml");
            if (old.exists()) src = old;
            else return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(src);
        ConfigurationSection s = y.getConfigurationSection("rooms");
        if (s == null) return;
        String defBlock = plugin.getConfig().getString("settings.default-barrier-block", "GLASS");
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            Room r = new Room(k);
            r.blocks = defBlock;
            readRoom(r, c);
            fixBounds(r);
            if (r.teamSize > 1) r.bet = false;
            Material mat = Material.matchMaterial(r.blocks);
            if (mat == null || mat.isAir() || !mat.isBlock()) {
                r.blocks = defBlock;
                r.barrierClosed = false;
            }
            rooms.put(k.toLowerCase(), r);
        }
        if (src != file) save();
    }

    /** Lee los datos de una sección sobre la room; lo que falta queda como estaba. */
    private void readRoom(Room r, ConfigurationSection c) {
        r.world = c.getString("world", r.world);
        r.minX = c.getInt("min.x", r.minX);
        r.minY = c.getInt("min.y", r.minY);
        r.minZ = c.getInt("min.z", r.minZ);
        r.maxX = c.getInt("max.x", r.maxX);
        r.maxY = c.getInt("max.y", r.maxY);
        r.maxZ = c.getInt("max.z", r.maxZ);
        r.bMinX = c.getInt("barrier-region.min.x", r.bMinX);
        r.bMinY = c.getInt("barrier-region.min.y", r.bMinY);
        r.bMinZ = c.getInt("barrier-region.min.z", r.bMinZ);
        r.bMaxX = c.getInt("barrier-region.max.x", r.bMaxX);
        r.bMaxY = c.getInt("barrier-region.max.y", r.bMaxY);
        r.bMaxZ = c.getInt("barrier-region.max.z", r.bMaxZ);
        Location ex = readLocation(c, "exit", r.world);
        if (ex != null) r.exit = ex;
        r.utilities = c.getBoolean("utilities", r.utilities);
        r.keepInventory = c.getBoolean("keepinventory", r.keepInventory);
        r.bet = c.getBoolean("bet", r.bet);
        r.blocks = c.getString("blocks", r.blocks);
        r.barrierClosed = c.getBoolean("barrier-closed", r.barrierClosed);
        r.lastWinner = c.getString("last-winner", r.lastWinner);
        r.lastLoser = c.getString("last-loser", r.lastLoser);
        int mode = Room.parseMode(c.getString("mode", r.modeName()));
        if (mode > 0) r.teamSize = mode;
        if (c.contains("effects")) {
            r.effects = new LinkedHashMap<>();
            for (String e : c.getStringList("effects")) {
                String[] a = e.split(":");
                if (a.length == 3) {
                    try {
                        r.effects.put(a[0].trim().toLowerCase(),
                                new int[]{Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim())});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    private Location readLocation(ConfigurationSection c, String path, String worldName) {
        Object o = c.get(path);
        if (o instanceof Location) return (Location) o; // formato viejo
        ConfigurationSection s = c.getConfigurationSection(path);
        if (s == null) return null;
        World w = Bukkit.getWorld(s.getString("world", worldName == null ? "" : worldName));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private void fixBounds(Room r) {
        int t;
        if (r.minX > r.maxX) { t = r.minX; r.minX = r.maxX; r.maxX = t; }
        if (r.minY > r.maxY) { t = r.minY; r.minY = r.maxY; r.maxY = t; }
        if (r.minZ > r.maxZ) { t = r.minZ; r.minZ = r.maxZ; r.maxZ = t; }
        if (r.bMinX > r.bMaxX) { t = r.bMinX; r.bMinX = r.bMaxX; r.bMaxX = t; }
        if (r.bMinY > r.bMaxY) { t = r.bMinY; r.bMinY = r.bMaxY; r.bMaxY = t; }
        if (r.bMinZ > r.bMaxZ) { t = r.bMinZ; r.bMinZ = r.bMaxZ; r.bMaxZ = t; }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().header("Rooms de zSRooms\n"
                + "Se puede editar a mano, pero las rooms tienen que estar creadas con /room create.\n"
                + "Despues de editar usa /room reload enseguida.\n"
                + "mode: 1v1, 2v2, 3v3 o 4v4\n"
                + "bet: true/false (room de apuesta, solo 1v1)\n"
                + "effects: efecto:segundos:nivel (segundos -1 = infinito)");
        for (Room r : rooms.values()) {
            String p = "rooms." + r.name + ".";
            y.set(p + "world", r.world);
            y.set(p + "mode", r.modeName());
            y.set(p + "bet", r.bet);
            y.set(p + "min.x", r.minX);
            y.set(p + "min.y", r.minY);
            y.set(p + "min.z", r.minZ);
            y.set(p + "max.x", r.maxX);
            y.set(p + "max.y", r.maxY);
            y.set(p + "max.z", r.maxZ);
            y.set(p + "barrier-region.min.x", r.bMinX);
            y.set(p + "barrier-region.min.y", r.bMinY);
            y.set(p + "barrier-region.min.z", r.bMinZ);
            y.set(p + "barrier-region.max.x", r.bMaxX);
            y.set(p + "barrier-region.max.y", r.bMaxY);
            y.set(p + "barrier-region.max.z", r.bMaxZ);
            if (r.exit != null && r.exit.getWorld() != null) {
                y.set(p + "exit.world", r.exit.getWorld().getName());
                y.set(p + "exit.x", r.exit.getX());
                y.set(p + "exit.y", r.exit.getY());
                y.set(p + "exit.z", r.exit.getZ());
                y.set(p + "exit.yaw", (double) r.exit.getYaw());
                y.set(p + "exit.pitch", (double) r.exit.getPitch());
            }
            y.set(p + "utilities", r.utilities);
            y.set(p + "keepinventory", r.keepInventory);
            y.set(p + "blocks", r.blocks);
            y.set(p + "barrier-closed", r.barrierClosed);
            y.set(p + "last-winner", r.lastWinner);
            y.set(p + "last-loser", r.lastLoser);
            List<String> ef = new ArrayList<>();
            for (Map.Entry<String, int[]> e : r.effects.entrySet()) {
                ef.add(e.getKey() + ":" + e.getValue()[0] + ":" + e.getValue()[1]);
            }
            y.set(p + "effects", ef);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar room.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- recargar room.yml

    private Room copyOf(Room r) {
        Room t = new Room(r.name);
        t.world = r.world;
        t.minX = r.minX;
        t.minY = r.minY;
        t.minZ = r.minZ;
        t.maxX = r.maxX;
        t.maxY = r.maxY;
        t.maxZ = r.maxZ;
        t.bMinX = r.bMinX;
        t.bMinY = r.bMinY;
        t.bMinZ = r.bMinZ;
        t.bMaxX = r.bMaxX;
        t.bMaxY = r.bMaxY;
        t.bMaxZ = r.bMaxZ;
        t.exit = r.exit == null ? null : r.exit.clone();
        t.utilities = r.utilities;
        t.keepInventory = r.keepInventory;
        t.bet = r.bet;
        t.blocks = r.blocks;
        t.barrierClosed = r.barrierClosed;
        t.teamSize = r.teamSize;
        t.lastWinner = r.lastWinner;
        t.lastLoser = r.lastLoser;
        for (Map.Entry<String, int[]> e : r.effects.entrySet()) {
            t.effects.put(e.getKey(), e.getValue().clone());
        }
        return t;
    }

    private void copyRegion(Room from, Room to) {
        to.minX = from.minX;
        to.minY = from.minY;
        to.minZ = from.minZ;
        to.maxX = from.maxX;
        to.maxY = from.maxY;
        to.maxZ = from.maxZ;
    }

    private boolean sameRegion(Room a, Room b) {
        return a.minX == b.minX && a.minY == b.minY && a.minZ == b.minZ
                && a.maxX == b.maxX && a.maxY == b.maxY && a.maxZ == b.maxZ;
    }

    private boolean sameBarrier(Room a, Room b) {
        return a.bMinX == b.bMinX && a.bMinY == b.bMinY && a.bMinZ == b.bMinZ
                && a.bMaxX == b.bMaxX && a.bMaxY == b.bMaxY && a.bMaxZ == b.bMaxZ
                && a.blocks.equals(b.blocks);
    }

    /** Aplica lo que se editó a mano en room.yml (solo rooms ya creadas con /room create). */
    public ReloadResult reloadFromFile() {
        ReloadResult res = new ReloadResult();
        if (!file.exists()) return res;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("rooms");
        if (s == null) return res;
        boolean modeChanged = false;

        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            Room r = get(k);
            if (r == null) {
                res.warnings.add(plugin.msg("reload-unknown-room", "%room%", k));
                continue;
            }

            Room t = copyOf(r);
            readRoom(t, c);
            fixBounds(t);
            if (t.teamSize > 1) t.bet = false;
            t.barrierClosed = r.barrierClosed; // el estado de la barrera no se edita a mano

            Material mat = Material.matchMaterial(t.blocks);
            if (mat == null || mat.isAir() || !mat.isBlock()) {
                res.warnings.add(plugin.msg("reload-bad-material", "%room%", r.name, "%value%", t.blocks));
                t.blocks = r.blocks;
            }

            if (t.exit != null && t.contains(t.exit)) {
                res.warnings.add(plugin.msg("reload-exit-inside", "%room%", r.name));
                t.exit = r.exit;
                if (t.exit != null && t.contains(t.exit)) copyRegion(r, t);
            }

            boolean busy = r.active || r.closing || !r.players.isEmpty();

            // ajustes simples: siempre se aplican
            if (busy) {
                for (Player p : online(r)) clearEffects(p, r);
            }
            r.effects = t.effects;
            if (busy) {
                for (Player p : online(r)) applyEffects(p, r);
            }
            r.utilities = t.utilities;
            r.keepInventory = t.keepInventory;
            r.lastWinner = t.lastWinner;
            r.lastLoser = t.lastLoser;

            // zonas, barrera, salida, modo y apuesta: solo si la room está libre
            boolean sameGeo = Objects.equals(r.world, t.world) && sameRegion(r, t)
                    && sameBarrier(r, t) && Objects.equals(r.exit, t.exit);
            boolean sameMode = r.teamSize == t.teamSize && r.bet == t.bet;
            if (!(sameGeo && sameMode)) {
                if (busy) {
                    res.warnings.add(plugin.msg("reload-room-busy", "%room%", r.name));
                } else {
                    if (!sameMode) {
                        r.teamSize = t.teamSize;
                        r.bet = t.bet;
                        modeChanged = true;
                    }
                    if (!sameGeo) {
                        boolean barrierChanged = !Objects.equals(r.world, t.world) || !sameBarrier(r, t);
                        boolean reclose = r.barrierClosed && barrierChanged;
                        if (reclose) openBarrier(r); // se abre con los datos viejos
                        r.world = t.world;
                        copyRegion(t, r);
                        r.bMinX = t.bMinX;
                        r.bMinY = t.bMinY;
                        r.bMinZ = t.bMinZ;
                        r.bMaxX = t.bMaxX;
                        r.bMaxY = t.bMaxY;
                        r.bMaxZ = t.bMaxZ;
                        r.blocks = t.blocks;
                        r.exit = t.exit;
                        if (reclose) closeBarrier(r);
                    }
                }
            }
            res.applied++;
        }
        save();
        if (modeChanged) plugin.teams().checkPending();
        return res;
    }

    // ---------------------------------------------------------------- acceso

    public Room get(String name) {
        return name == null ? null : rooms.get(name.toLowerCase());
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    public Room byLocation(Location l) {
        for (Room r : rooms.values()) {
            if (r.contains(l)) return r;
        }
        return null;
    }

    public Room byPlayer(UUID id) {
        for (Room r : rooms.values()) {
            if (r.players.contains(id)) return r;
        }
        return null;
    }

    /** Devuelve null si la zona del pico quedó dentro de la room. Las rooms nuevas traen visión nocturna infinita. */
    public Room create(String name, Selection s) {
        Room r = new Room(name);
        r.world = s.pos1.getWorld().getName();
        r.minX = Math.min(s.pos1.getBlockX(), s.pos2.getBlockX());
        r.minY = Math.min(s.pos1.getBlockY(), s.pos2.getBlockY());
        r.minZ = Math.min(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        r.maxX = Math.max(s.pos1.getBlockX(), s.pos2.getBlockX());
        r.maxY = Math.max(s.pos1.getBlockY(), s.pos2.getBlockY());
        r.maxZ = Math.max(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        r.bMinX = Math.min(s.bpos1.getBlockX(), s.bpos2.getBlockX());
        r.bMinY = Math.min(s.bpos1.getBlockY(), s.bpos2.getBlockY());
        r.bMinZ = Math.min(s.bpos1.getBlockZ(), s.bpos2.getBlockZ());
        r.bMaxX = Math.max(s.bpos1.getBlockX(), s.bpos2.getBlockX());
        r.bMaxY = Math.max(s.bpos1.getBlockY(), s.bpos2.getBlockY());
        r.bMaxZ = Math.max(s.bpos1.getBlockZ(), s.bpos2.getBlockZ());
        r.exit = s.exit.clone();
        r.blocks = plugin.getConfig().getString("settings.default-barrier-block", "GLASS");
        r.barrierClosed = false;
        r.effects.put("night_vision", new int[]{-1, 1});
        if (r.contains(r.exit)) return null;
        rooms.put(name.toLowerCase(), r);
        save();
        return r;
    }

    /** /room save: actualiza la room con lo que se marcó con las herramientas. */
    public SaveResult saveFromSelection(Room r, Selection s) {
        SaveResult res = new SaveResult();
        if (s == null) {
            res.errorKey = "save-nothing";
            return res;
        }
        if ((s.pos1 == null) != (s.pos2 == null)) {
            res.errorKey = "save-incomplete";
            res.detail = "hacha";
            return res;
        }
        if ((s.bpos1 == null) != (s.bpos2 == null)) {
            res.errorKey = "save-incomplete";
            res.detail = "azada";
            return res;
        }

        boolean region = s.pos1 != null;
        boolean bregion = s.bpos1 != null;
        boolean exit = s.exit != null;
        if (!region && !bregion && !exit) {
            res.errorKey = "save-nothing";
            return res;
        }
        if (r.active || r.closing || !r.players.isEmpty()) {
            res.errorKey = "room-busy";
            return res;
        }

        String world = region ? s.pos1.getWorld().getName() : r.world;
        boolean sameWorld = true;
        if (region && !s.pos2.getWorld().getName().equals(world)) sameWorld = false;
        if (bregion && (!s.bpos1.getWorld().getName().equals(world) || !s.bpos2.getWorld().getName().equals(world))) {
            sameWorld = false;
        }
        if (exit && !s.exit.getWorld().getName().equals(world)) sameWorld = false;
        if (!sameWorld) {
            res.errorKey = "selection-world";
            return res;
        }
        if (region && !world.equals(r.world) && (!bregion || !exit)) {
            res.errorKey = "save-need-all";
            return res;
        }

        int minX = r.minX, minY = r.minY, minZ = r.minZ, maxX = r.maxX, maxY = r.maxY, maxZ = r.maxZ;
        if (region) {
            minX = Math.min(s.pos1.getBlockX(), s.pos2.getBlockX());
            minY = Math.min(s.pos1.getBlockY(), s.pos2.getBlockY());
            minZ = Math.min(s.pos1.getBlockZ(), s.pos2.getBlockZ());
            maxX = Math.max(s.pos1.getBlockX(), s.pos2.getBlockX());
            maxY = Math.max(s.pos1.getBlockY(), s.pos2.getBlockY());
            maxZ = Math.max(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        }

        Location newExit = exit ? s.exit : r.exit;
        Room tmp = new Room("tmp");
        tmp.world = world;
        tmp.minX = minX;
        tmp.minY = minY;
        tmp.minZ = minZ;
        tmp.maxX = maxX;
        tmp.maxY = maxY;
        tmp.maxZ = maxZ;
        if (newExit != null && tmp.contains(newExit)) {
            res.errorKey = "exit-inside";
            return res;
        }

        boolean reclose = false;
        if (bregion && r.barrierClosed) {
            openBarrier(r);
            reclose = true;
        }

        if (region) {
            r.world = world;
            r.minX = minX;
            r.minY = minY;
            r.minZ = minZ;
            r.maxX = maxX;
            r.maxY = maxY;
            r.maxZ = maxZ;
            res.updated.add("hacha");
            s.pos1 = null;
            s.pos2 = null;
        }
        if (bregion) {
            r.bMinX = Math.min(s.bpos1.getBlockX(), s.bpos2.getBlockX());
            r.bMinY = Math.min(s.bpos1.getBlockY(), s.bpos2.getBlockY());
            r.bMinZ = Math.min(s.bpos1.getBlockZ(), s.bpos2.getBlockZ());
            r.bMaxX = Math.max(s.bpos1.getBlockX(), s.bpos2.getBlockX());
            r.bMaxY = Math.max(s.bpos1.getBlockY(), s.bpos2.getBlockY());
            r.bMaxZ = Math.max(s.bpos1.getBlockZ(), s.bpos2.getBlockZ());
            res.updated.add("azada");
            s.bpos1 = null;
            s.bpos2 = null;
        }
        if (exit) {
            r.exit = s.exit.clone();
            res.updated.add("pico");
            s.exit = null;
        }

        if (reclose) closeBarrier(r);
        save();
        return res;
    }

    public void delete(Room r) {
        forceReset(r);
        rooms.remove(r.name.toLowerCase());
        save();
    }

    public boolean rename(Room r, String newName) {
        if (get(newName) != null) return false;
        rooms.remove(r.name.toLowerCase());
        r.name = newName;
        rooms.put(newName.toLowerCase(), r);
        save();
        return true;
    }

    // ---------------------------------------------------------------- efectos

    public static PotionEffectType effect(String s) {
        PotionEffectType t = null;
        try {
            t = PotionEffectType.getByKey(NamespacedKey.minecraft(s.toLowerCase()));
        } catch (IllegalArgumentException ignored) {
        }
        if (t == null) t = PotionEffectType.getByName(s.toUpperCase());
        return t;
    }

    public static String key(PotionEffectType t) {
        return t.getKey().getKey();
    }

    public void applyEffects(Player p, Room r) {
        for (Map.Entry<String, int[]> e : r.effects.entrySet()) {
            PotionEffectType t = effect(e.getKey());
            if (t == null) continue;
            int secs = e.getValue()[0];
            int dur = secs < 0 ? Integer.MAX_VALUE : secs * 20;
            p.addPotionEffect(new PotionEffect(t, dur, Math.max(0, e.getValue()[1] - 1), true, true));
        }
    }

    public void clearEffects(Player p, Room r) {
        for (String k : r.effects.keySet()) {
            PotionEffectType t = effect(k);
            if (t != null) p.removePotionEffect(t);
        }
    }

    public List<Player> online(Room r) {
        List<Player> l = new ArrayList<>();
        for (UUID id : r.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p);
        }
        return l;
    }

    // ---------------------------------------------------------------- 1v1: entrar y pelear

    public boolean tryJoin(Player p, Room r) {
        // las rooms por equipos solo se usan con /room team
        if (r.teamSize > 1) {
            p.sendMessage(plugin.msg("room-team-only", "%room%", r.name, "%mode%", r.modeName()));
            return false;
        }
        if (r.closing || r.players.size() >= 2) {
            p.sendMessage(plugin.msg("room-full", "%room%", r.name));
            return false;
        }
        r.players.add(p.getUniqueId());
        applyEffects(p, r);
        p.sendMessage(plugin.msg("room-joined", "%room%", r.name));
        plugin.playSound(p, "enter");
        if (r.players.size() == 2) {
            // room de apuesta: primero el menú, el PvP empieza cuando se confirma
            if (r.bet) plugin.bets().start(r);
            else startFight(r);
        }
        return true;
    }

    /** Empieza el duelo 1v1 (también lo usa la apuesta cuando queda confirmada). */
    public void startDuel(Room r) {
        if (r.players.size() == 2) startFight(r);
    }

    /** Entraron los 2: se cierra la barrera y ya se puede pelear. */
    private void startFight(Room r) {
        cancelTasks(r);
        r.active = true;
        r.fighting = true;
        r.closing = false;
        closeBarrier(r);
        for (Player p : online(r)) p.sendMessage(plugin.msg("fight-started"));
    }

    /** Saca a todos de la room y la deja libre (por ejemplo si se cancela una apuesta). */
    public void ejectAll(Room r) {
        cancelTasks(r);
        List<Player> ps = online(r);
        r.players.clear();
        r.fighting = false;
        r.closing = false;
        r.active = false;
        for (Player p : ps) {
            clearEffects(p, r);
            p.sendMessage(plugin.msg("room-left", "%room%", r.name));
            plugin.playSound(p, "leave");
            if (r.exit != null) p.teleport(r.exit);
        }
        plugin.teams().checkPending();
    }

    // ---------------------------------------------------------------- equipos (2v2, 3v3, 4v4)

    public Room pickTeamRoom(int size) {
        List<Room> free = new ArrayList<>();
        for (Room r : rooms.values()) {
            if (r.teamSize == size && r.players.isEmpty() && !r.active && !r.closing
                    && r.exit != null && Bukkit.getWorld(r.world) != null) {
                free.add(r);
            }
        }
        if (free.isEmpty()) return null;
        return free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    public int teamRoomCount(int size) {
        int n = 0;
        for (Room r : rooms.values()) {
            if (r.teamSize == size) n++;
        }
        return n;
    }

    /** Teletransporta a los dos teams a la room, cierra la barrera y empieza el contador. */
    public void startTeamMatch(Room r, TeamMatch mt) {
        cancelTasks(r);
        mt.started = true;
        r.match = mt;
        r.players.clear();
        r.active = true;
        r.fighting = false;
        r.closing = false;

        int i = 0;
        for (UUID id : mt.teamA) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            r.players.add(id);
            p.teleport(teamSpot(r, 0, i++, mt.size));
        }
        i = 0;
        for (UUID id : mt.teamB) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            r.players.add(id);
            p.teleport(teamSpot(r, 1, i++, mt.size));
        }
        for (Player p : online(r)) {
            applyEffects(p, r);
            p.sendMessage(plugin.msg("room-joined", "%room%", r.name));
            plugin.playSound(p, "enter");
        }
        closeBarrier(r);
        startTeamCountdown(r);
    }

    /** Lugar para cada jugador: el team 1 del lado de menor X, el team 2 del lado de mayor X. */
    private Location teamSpot(Room r, int side, int index, int size) {
        World w = Bukkit.getWorld(r.world);
        double fx = side == 0 ? 0.25 : 0.75;
        double fz = (index + 1.0) / (size + 1.0);
        int x = (int) Math.round(r.minX + (r.maxX - r.minX) * fx);
        int z = (int) Math.round(r.minZ + (r.maxZ - r.minZ) * fz);
        float yaw = side == 0 ? -90f : 90f;
        for (int y = r.minY; y <= r.maxY; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid()
                    && w.getBlockAt(x, y + 1, z).isPassable()
                    && w.getBlockAt(x, y + 2, z).isPassable()) {
                return new Location(w, x + 0.5, y + 1, z + 0.5, yaw, 0f);
            }
        }
        int cx = (r.minX + r.maxX) / 2;
        int cz = (r.minZ + r.maxZ) / 2;
        return new Location(w, cx + 0.5, r.minY + 1, cz + 0.5, yaw, 0f);
    }

    private void startTeamCountdown(Room r) {
        int secs = Math.max(0, plugin.getConfig().getInt("team.countdown", 5));
        if (secs == 0) {
            beginTeamFight(r);
            return;
        }
        final int[] left = {secs};
        r.startTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!r.active || r.closing) {
                cancelStart(r);
                return;
            }
            if (left[0] <= 0) {
                cancelStart(r);
                beginTeamFight(r);
                return;
            }
            for (Player p : online(r)) {
                p.sendMessage(plugin.raw("team-countdown-chat", "%time%", String.valueOf(left[0])));
                p.sendTitle(plugin.raw("team-countdown-title", "%time%", String.valueOf(left[0])),
                        plugin.raw("team-countdown-subtitle", "%room%", r.name, "%mode%", r.modeName()),
                        0, 25, 5);
                plugin.playSound(p, "countdown");
            }
            left[0]--;
        }, 0L, 20L);
    }

    private void beginTeamFight(Room r) {
        if (!r.active || r.closing) return;
        r.fighting = true;
        for (Player p : online(r)) {
            p.sendTitle(plugin.raw("team-fight-title"), plugin.raw("team-fight-subtitle"), 0, 30, 10);
            p.sendMessage(plugin.msg("fight-started"));
        }
    }

    private void cancelStart(Room r) {
        if (r.startTask != null) {
            r.startTask.cancel();
            r.startTask = null;
        }
    }

    private int alive(Room r, List<UUID> team) {
        int n = 0;
        for (UUID id : team) {
            if (r.players.contains(id)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- salir / ganar

    /** Un jugador deja la room (muerte, salida/spawn o desconexión). */
    public void leave(Player p, Room r, Cause cause) {
        if (!r.players.remove(p.getUniqueId())) return;
        clearEffects(p, r);

        // si estaban en el menú de apuesta (sin confirmar), se cancela y se devuelven los items
        plugin.bets().onPlayerLeft(r, p.getName());

        if (cause != Cause.DISCONNECT) {
            p.sendMessage(plugin.msg("room-left", "%room%", r.name));
            plugin.playSound(p, "leave");
        }

        // todavía no había empezado: solo se limpia
        if (!r.active) {
            if (r.players.isEmpty()) reset(r);
            return;
        }
        // ya hay un contador en marcha
        if (r.closing) {
            if (r.players.isEmpty()) openRoom(r);
            return;
        }
        // se fueron todos (si había apuesta confirmada, se devuelve)
        if (r.players.isEmpty()) {
            plugin.bets().refund(r);
            openRoom(r);
            return;
        }

        boolean announce = r.fighting && (cause != Cause.DISCONNECT
                || plugin.getConfig().getBoolean("settings.announce-on-disconnect", false));
        Set<UUID> participants = new LinkedHashSet<>(r.players);
        participants.add(p.getUniqueId());

        if (r.match != null) {
            TeamMatch mt = r.match;
            int a = alive(r, mt.teamA);
            int b = alive(r, mt.teamB);
            if (a > 0 && b > 0) return; // los dos teams siguen en pie
            participants.addAll(mt.all());
            if (announce) announceTeamWin(r, mt, b == 0);
        } else {
            Player winner = null;
            for (Player o : online(r)) winner = o;
            if (winner != null) {
                // si había apuesta confirmada, el ganador se lleva todo
                plugin.bets().settle(r, winner, p);
                if (announce) announceWin(winner, p, r);
            }
        }
        scheduleClose(r, participants, cause);
    }

    public void handleDeath(Player dead, Room r) {
        if (r.exit != null) respawn.put(dead.getUniqueId(), r.exit);
        leave(dead, r, Cause.DEATH);
    }

    private void announceWin(Player winner, Player loser, Room r) {
        r.lastWinner = winner.getName();
        r.lastLoser = loser.getName();
        save();
        String[] kv = {"%ganador%", winner.getName(), "%perdedor%", loser.getName(), "%room%", r.name};
        Bukkit.broadcastMessage(plugin.raw("win-broadcast", kv));
        winner.sendTitle(plugin.raw("win-title", kv), plugin.raw("win-subtitle", kv), 10, 60, 20);
    }

    private void announceTeamWin(Room r, TeamMatch mt, boolean aWins) {
        List<UUID> win = aWins ? mt.teamA : mt.teamB;
        List<UUID> lose = aWins ? mt.teamB : mt.teamA;
        String w = TeamMatch.names(win);
        String l = TeamMatch.names(lose);
        r.lastWinner = w;
        r.lastLoser = l;
        save();
        Bukkit.broadcastMessage(plugin.raw("team-win-broadcast",
                "%ganador%", w, "%perdedor%", l, "%room%", r.name, "%mode%", r.modeName()));
        for (UUID id : win) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            String[] kv = {"%ganador%", p.getName(), "%perdedor%", l, "%room%", r.name};
            p.sendTitle(plugin.raw("win-title", kv), plugin.raw("win-subtitle", kv), 10, 60, 20);
        }
    }

    /** Arranca el contador (10s, 9s, 8s...) hasta que se abre la barrera. */
    private void scheduleClose(Room r, Set<UUID> participants, Cause cause) {
        r.closing = true;
        r.fighting = false;
        cancelStart(r);
        int total = Math.max(0, plugin.getConfig().getInt("settings.open-delay." + cause.name().toLowerCase(), 10));
        if (total == 0) {
            openRoom(r);
            return;
        }
        final int[] left = {total};
        r.closeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0] <= 0) {
                openRoom(r);
                return;
            }
            sendCountdown(participants, r, left[0]);
            left[0]--;
        }, 0L, 20L);
    }

    /** Mensaje + sonido de cada segundo del contador. */
    private void sendCountdown(Set<UUID> participants, Room r, int left) {
        String text = plugin.raw("room-opening", "%room%", r.name, "%time%", left + "s");
        String mode = plugin.getConfig().getString("settings.countdown-display", "chat").toLowerCase();
        for (UUID id : participants) {
            Player pl = Bukkit.getPlayer(id);
            if (pl == null) continue;
            if (!mode.equals("actionbar")) pl.sendMessage(text);
            if (mode.equals("actionbar") || mode.equals("both")) {
                pl.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
            }
            plugin.playSound(pl, "countdown");
        }
    }

    /**
     * Abre la barrera. Los que ganaron se quedan adentro (no se los teletransporta):
     * la room queda esperando un nuevo rival.
     */
    public void openRoom(Room r) {
        cancelTasks(r);
        TeamMatch mt = r.match;
        r.match = null;
        r.fighting = false;
        r.closing = false;
        r.active = false;
        r.players.removeIf(id -> Bukkit.getPlayer(id) == null);

        openBarrier(r);
        for (Player p : online(r)) {
            p.sendMessage(plugin.msg("barrier-opened", "%room%", r.name));
        }
        if (mt != null) plugin.teams().finish(mt);
        plugin.teams().checkPending();
    }

    private void reset(Room r) {
        cancelTasks(r);
        r.fighting = false;
        r.closing = false;
        r.active = false;
        plugin.teams().checkPending();
    }

    /** Limpia el estado sin tocar los bloques (se usa al borrar una room). */
    public void forceReset(Room r) {
        plugin.bets().refund(r);
        cancelTasks(r);
        for (Player p : online(r)) clearEffects(p, r);
        r.players.clear();
        r.fighting = false;
        r.closing = false;
        r.active = false;
        if (r.match != null) {
            plugin.teams().finish(r.match);
            r.match = null;
        }
    }

    private void cancelTasks(Room r) {
        cancelStart(r);
        if (r.closeTask != null) {
            r.closeTask.cancel();
            r.closeTask = null;
        }
    }

    public void shutdown() {
        // primero se devuelven las apuestas (menú abierto o items en escrow)
        plugin.bets().shutdown();
        for (Room r : rooms.values()) {
            boolean wasActive = r.active;
            cancelTasks(r);
            for (Player p : online(r)) clearEffects(p, r);
            r.players.clear();
            r.fighting = false;
            r.closing = false;
            r.active = false;
            if (r.match != null) {
                plugin.teams().finish(r.match);
                r.match = null;
            }
            if (wasActive) openBarrier(r);
        }
    }

    // ---------------------------------------------------------------- barrera (azada)

    private boolean tooBig(Room r) {
        return r.barrierVolume() > plugin.getConfig().getInt("settings.max-barrier-blocks", 300000);
    }

    /** Recorre la zona de la azada y cambia los bloques que cumplan la condición. */
    private int fillBarrier(World w, Room r, Predicate<Block> test, Material to) {
        int n = 0;
        for (int x = r.bMinX; x <= r.bMaxX; x++) {
            for (int y = r.bMinY; y <= r.bMaxY; y++) {
                for (int z = r.bMinZ; z <= r.bMaxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (test.test(b)) {
                        b.setType(to, false);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Cierra la barrera: rellena con el material los bloques vacíos de la zona
     * (no pisa construcciones ni pone bloques donde hay un jugador).
     * Devuelve la cantidad de bloques o -1 (mundo no cargado) / -2 (zona muy grande).
     */
    public int closeBarrier(Room r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return -1;
        if (tooBig(r)) return -2;

        Material mat = Material.matchMaterial(r.blocks);
        if (mat == null || mat.isAir() || !mat.isBlock()) mat = Material.GLASS;
        r.blocks = mat.name();

        Set<String> busy = new HashSet<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            busy.add(l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
            busy.add(l.getBlockX() + "," + (l.getBlockY() + 1) + "," + l.getBlockZ());
        }

        int n = fillBarrier(w, r,
                b -> b.getType().isAir() && !busy.contains(b.getX() + "," + b.getY() + "," + b.getZ()),
                mat);
        r.barrierClosed = true;
        save();
        return n;
    }

    /** Abre la barrera: saca los bloques del material de la barrera dentro de la zona. */
    public int openBarrier(Room r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return -1;
        if (tooBig(r)) return -2;

        int n = 0;
        Material mat = Material.matchMaterial(r.blocks);
        if (mat != null && !mat.isAir()) {
            n = fillBarrier(w, r, b -> b.getType() == mat, Material.AIR);
        }
        r.barrierClosed = false;
        save();
        return n;
    }

    /** Cambia el material de la barrera. Si está cerrada, reemplaza los bloques puestos. */
    public int changeBlocks(Room r, Material mat) {
        int n = 0;
        if (r.barrierClosed) {
            World w = Bukkit.getWorld(r.world);
            if (w == null) return -1;
            if (tooBig(r)) return -2;
            Material old = Material.matchMaterial(r.blocks);
            if (old != null && old != mat) {
                n = fillBarrier(w, r, b -> b.getType() == old, mat);
            }
        }
        r.blocks = mat.name();
        save();
        return n;
    }

    public int toggleBarrier(Room r) {
        return r.barrierClosed ? openBarrier(r) : closeBarrier(r);
    }
            }
