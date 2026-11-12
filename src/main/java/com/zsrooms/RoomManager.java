package com.zsrooms;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RoomManager {

    /** Motivo por el que un jugador deja la room. */
    public enum Cause { DEATH, LEAVE, DISCONNECT }

    public static class Selection {
        public Location pos1, pos2, exit, bpos1, bpos2;
    }

    /** Resultado de /room save. */
    public static class SaveResult {
        /** null = todo bien; si no, la clave del mensaje de error. */
        public String errorKey;
        /** Herramienta a la que se refiere el error (para %tool%). */
        public String detail = "";
        public final List<String> updated = new ArrayList<>();
        public boolean scanned = false;
        public int barrierCount = 0;
    }

    private final ZSRooms plugin;
    private final File file;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    public final Map<UUID, Selection> selections = new HashMap<>();
    public final Map<UUID, Location> respawn = new HashMap<>();

    public RoomManager(ZSRooms plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rooms.yml");
    }

    // ---------------------------------------------------------------- datos

    public void load() {
        rooms.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("rooms");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            Room r = new Room(k);
            r.world = c.getString("world");
            r.minX = c.getInt("min.x");
            r.minY = c.getInt("min.y");
            r.minZ = c.getInt("min.z");
            r.maxX = c.getInt("max.x");
            r.maxY = c.getInt("max.y");
            r.maxZ = c.getInt("max.z");
            r.bMinX = c.getInt("barrier-region.min.x");
            r.bMinY = c.getInt("barrier-region.min.y");
            r.bMinZ = c.getInt("barrier-region.min.z");
            r.bMaxX = c.getInt("barrier-region.max.x");
            r.bMaxY = c.getInt("barrier-region.max.y");
            r.bMaxZ = c.getInt("barrier-region.max.z");
            r.exit = c.getLocation("exit");
            r.utilities = c.getBoolean("utilities", false);
            r.keepInventory = c.getBoolean("keepinventory", false);
            r.blocks = c.getString("blocks", "GLASS");
            r.blocksOn = c.getString("blocks-on", "GLASS");
            r.barrier = new ArrayList<>(c.getStringList("barrier"));
            r.lastWinner = c.getString("last-winner", "Ninguno");
            r.lastLoser = c.getString("last-loser", "Ninguno");
            for (String e : c.getStringList("effects")) {
                String[] a = e.split(":");
                if (a.length == 3) {
                    try {
                        r.effects.put(a[0], new int[]{Integer.parseInt(a[1]), Integer.parseInt(a[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            rooms.put(k.toLowerCase(), r);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Room r : rooms.values()) {
            String p = "rooms." + r.name + ".";
            y.set(p + "world", r.world);
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
            y.set(p + "exit", r.exit);
            y.set(p + "utilities", r.utilities);
            y.set(p + "keepinventory", r.keepInventory);
            y.set(p + "blocks", r.blocks);
            y.set(p + "blocks-on", r.blocksOn);
            y.set(p + "barrier", r.barrier);
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
            plugin.getLogger().warning("No se pudo guardar rooms.yml: " + ex.getMessage());
        }
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

    /** Devuelve null si la zona de expulsión quedó dentro de la room. */
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
        r.blocksOn = plugin.getConfig().getString("settings.default-barrier-block", "GLASS");
        r.blocks = r.blocksOn;
        if (r.contains(r.exit)) return null;
        rooms.put(name.toLowerCase(), r);
        save();
        return r;
    }

    /**
     * /room save: actualiza la room con lo que el jugador marcó con las herramientas.
     * Solo cambia lo marcado (hacha = room, azada = barrera, pico = zona de expulsión).
     */
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

        // Mundo
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

        // Nuevos límites de la room
        int minX = r.minX, minY = r.minY, minZ = r.minZ, maxX = r.maxX, maxY = r.maxY, maxZ = r.maxZ;
        if (region) {
            minX = Math.min(s.pos1.getBlockX(), s.pos2.getBlockX());
            minY = Math.min(s.pos1.getBlockY(), s.pos2.getBlockY());
            minZ = Math.min(s.pos1.getBlockZ(), s.pos2.getBlockZ());
            maxX = Math.max(s.pos1.getBlockX(), s.pos2.getBlockX());
            maxY = Math.max(s.pos1.getBlockY(), s.pos2.getBlockY());
            maxZ = Math.max(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        }

        // La zona de expulsión tiene que quedar fuera de la room
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

        // Aplicar
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

        // Si cambió la zona de barrera, se vuelven a detectar los cristales
        if (bregion) {
            int c = scanBarrier(r);
            res.scanned = true;
            res.barrierCount = c;
            if (c < 0) {
                r.barrier.clear();
            } else if (c > 0 && r.blocks.equals("AIR")) {
                r.blocks = r.blocksOn;
            }
        }
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

    // ---------------------------------------------------------------- ciclo de la room

    public boolean tryJoin(Player p, Room r) {
        if (r.closing || r.players.size() >= 2) {
            p.sendMessage(plugin.msg("room-full", "%room%", r.name));
            return false;
        }
        r.players.add(p.getUniqueId());
        applyEffects(p, r);
        p.sendMessage(plugin.msg("room-joined", "%room%", r.name, "%players%", String.valueOf(r.players.size())));
        if (r.players.size() == 2) startFight(r);
        return true;
    }

    private void startFight(Room r) {
        cancelTasks(r);
        r.active = true;
        r.fighting = false;
        r.closing = false;

        if (plugin.getConfig().getBoolean("settings.close-barrier-on-start", true)) {
            Material mat = Material.matchMaterial(r.blocksOn);
            if (mat == null || mat == Material.AIR) mat = Material.GLASS;
            applyBlocks(r, mat);
        }

        int delay = Math.max(0, plugin.getConfig().getInt("settings.start-delay", 5));
        String m = plugin.msg("start-countdown", "%room%", r.name, "%time%", delay + " segundos");
        for (Player p : online(r)) p.sendMessage(m);

        r.startTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            r.startTask = null;
            if (r.active && !r.closing && r.players.size() == 2) {
                r.fighting = true;
                for (Player p : online(r)) p.sendMessage(plugin.msg("fight-started"));
            }
        }, delay * 20L);
    }

    /** Un jugador deja la room (muerte, salida/spawn o desconexión). */
    public void leave(Player p, Room r, Cause cause) {
        if (!r.players.remove(p.getUniqueId())) return;
        clearEffects(p, r);

        // Mensaje de salida (si se desconectó ya no está para leerlo)
        if (cause != Cause.DISCONNECT) {
            p.sendMessage(plugin.msg("room-left", "%room%", r.name));
        }

        // La room todavía no estaba completa: solo se limpia
        if (!r.active) {
            if (r.players.isEmpty()) reset(r);
            return;
        }

        // Ya hay un contador en marcha
        if (r.closing) {
            if (r.players.isEmpty()) openRoom(r);
            return;
        }

        // Se fueron todos
        if (r.players.isEmpty()) {
            openRoom(r);
            return;
        }

        // Anuncio de ganador (muerte, salida o /spawn; la desconexión es opcional)
        boolean announce = r.fighting && (cause != Cause.DISCONNECT
                || plugin.getConfig().getBoolean("settings.announce-on-disconnect", false));
        if (announce) {
            Player winner = null;
            for (Player o : online(r)) winner = o;
            if (winner != null) announceWin(winner, p, r);
        }

        Set<UUID> participants = new LinkedHashSet<>(r.players);
        participants.add(p.getUniqueId());
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

    private void scheduleClose(Room r, Set<UUID> participants, Cause cause) {
        r.closing = true;
        r.fighting = false;
        if (r.startTask != null) {
            r.startTask.cancel();
            r.startTask = null;
        }
        int t = Math.max(0, plugin.getConfig().getInt("settings.open-delay." + cause.name().toLowerCase(), 10));
        String m = plugin.msg("room-closing", "%room%", r.name, "%time%", t + " segundos");
        for (UUID id : participants) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) pl.sendMessage(m);
        }
        r.closeTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            r.closeTask = null;
            openRoom(r);
        }, t * 20L);
    }

    /** Abre la barrera, suelta a los jugadores y deja la room lista para otro duelo. */
    public void openRoom(Room r) {
        cancelTasks(r);
        List<Player> ps = online(r);
        r.players.clear();
        r.fighting = false;
        r.closing = false;
        r.active = false;

        applyBlocks(r, Material.AIR);

        boolean tp = plugin.getConfig().getBoolean("settings.teleport-on-open", true);
        for (Player p : ps) {
            clearEffects(p, r);
            p.sendMessage(plugin.msg("room-left", "%room%", r.name));
            p.sendMessage(plugin.msg("barrier-opened", "%room%", r.name));
            if (tp && r.exit != null) p.teleport(r.exit);
        }
    }

    private void reset(Room r) {
        cancelTasks(r);
        r.fighting = false;
        r.closing = false;
        r.active = false;
    }

    /** Limpia el estado sin tocar los bloques (se usa al borrar una room). */
    public void forceReset(Room r) {
        cancelTasks(r);
        for (Player p : online(r)) clearEffects(p, r);
        r.players.clear();
        r.fighting = false;
        r.closing = false;
        r.active = false;
    }

    private void cancelTasks(Room r) {
        if (r.startTask != null) {
            r.startTask.cancel();
            r.startTask = null;
        }
        if (r.closeTask != null) {
            r.closeTask.cancel();
            r.closeTask = null;
        }
    }

    public void shutdown() {
        for (Room r : rooms.values()) {
            boolean wasActive = r.active;
            cancelTasks(r);
            for (Player p : online(r)) clearEffects(p, r);
            r.players.clear();
            r.fighting = false;
            r.closing = false;
            r.active = false;
            if (wasActive) applyBlocks(r, Material.AIR);
        }
    }

    // ---------------------------------------------------------------- barrera (azada)

    public static boolean isBarrierType(Material m) {
        return m == Material.BARRIER || m.name().contains("GLASS");
    }

    /** Escanea la zona de la azada. -1 = mundo no cargado, -2 = zona muy grande. */
    public int scanBarrier(Room r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return -1;
        if (r.barrierVolume() > plugin.getConfig().getInt("settings.max-barrier-blocks", 300000)) return -2;
        r.barrier.clear();
        for (int x = r.bMinX; x <= r.bMaxX; x++) {
            for (int y = r.bMinY; y <= r.bMaxY; y++) {
                for (int z = r.bMinZ; z <= r.bMaxZ; z++) {
                    Material t = w.getBlockAt(x, y, z).getType();
                    if (isBarrierType(t)) r.barrier.add(x + "," + y + "," + z);
                }
            }
        }
        return r.barrier.size();
    }

    /** Cambia todos los bloques de la barrera. Devuelve la cantidad o un código negativo. */
    public int applyBlocks(Room r, Material mat) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return -1;
        if (r.barrier.isEmpty()) {
            int c = scanBarrier(r);
            if (c < 0) return c;
        }
        int n = 0;
        for (String s : r.barrier) {
            String[] a = s.split(",");
            if (a.length != 3) continue;
            try {
                Block b = w.getBlockAt(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                b.setType(mat, false);
                n++;
            } catch (NumberFormatException ignored) {
            }
        }
        r.blocks = mat.name();
        if (mat != Material.AIR) r.blocksOn = mat.name();
        save();
        return n;
    }

    public int toggleBlocks(Room r) {
        Material target = Material.AIR;
        if (r.blocks.equals("AIR")) {
            target = Material.matchMaterial(r.blocksOn);
            if (target == null || target == Material.AIR) target = Material.GLASS;
        }
        return applyBlocks(r, target);
    }
            }
