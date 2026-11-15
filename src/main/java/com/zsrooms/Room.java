package com.zsrooms;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Room {

    public String name;
    public String world;

    // Región de la room (hacha)
    public int minX, minY, minZ, maxX, maxY, maxZ;

    // Región de la barrera (azada)
    public int bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;

    // Zona del pico (a donde mandan a los que intentan entrar cuando está completa)
    public Location exit;

    public boolean utilities = false;
    public boolean keepInventory = false;

    /** Room de apuesta (solo 1v1): al entrar los 2 se abre el menú de apuesta. */
    public boolean bet = false;

    /** Material con el que se cierra la barrera (se cambia con /room blocks). */
    public String blocks = "GLASS";
    /** true = la barrera está cerrada (puesta), false = abierta. */
    public boolean barrierClosed = false;

    /** Jugadores por equipo: 1 = 1v1, 2 = 2v2, 3 = 3v3, 4 = 4v4. */
    public int teamSize = 1;

    /** efecto (key de Minecraft) -> {segundos (-1 = infinito), nivel} */
    public Map<String, int[]> effects = new LinkedHashMap<>();
    public String lastWinner = "Ninguno";
    public String lastLoser = "Ninguno";

    // Estado en ejecución (no se guarda)
    public final Set<UUID> players = new LinkedHashSet<>();
    /** Ya está completa y empezó el duelo / la partida. */
    public boolean active = false;
    /** PvP habilitado. */
    public boolean fighting = false;
    /** Contador para abrir la barrera en marcha. */
    public boolean closing = false;
    /** Partida de equipos en curso (solo rooms 2v2, 3v3, 4v4). */
    public TeamMatch match;
    /** Apuesta en curso (menú abierto o items en escrow). */
    public BetSession betSession;
    public BukkitTask startTask, closeTask;

    public Room(String name) {
        this.name = name;
    }

    public int capacity() {
        return 2 * Math.max(1, teamSize);
    }

    public String modeName() {
        int n = Math.max(1, teamSize);
        return n + "v" + n;
    }

    /** "2v2" -> 2. Devuelve -1 si no es 1v1, 2v2, 3v3 o 4v4. */
    public static int parseMode(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        if (s.length() == 3 && s.charAt(1) == 'v' && s.charAt(0) == s.charAt(2)) {
            int n = s.charAt(0) - '0';
            if (n >= 1 && n <= 4) return n;
        }
        return -1;
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null || world == null || !l.getWorld().getName().equals(world)) {
            return false;
        }
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long barrierVolume() {
        return (long) (bMaxX - bMinX + 1) * (bMaxY - bMinY + 1) * (bMaxZ - bMinZ + 1);
    }
}
