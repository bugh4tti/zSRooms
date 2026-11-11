package com.zsrooms;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Room {

    public String name;
    public String world;

    // Región de la room (hacha)
    public int minX, minY, minZ, maxX, maxY, maxZ;

    // Región de la barrera / cristales (azada)
    public int bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;

    // Zona de expulsión (pico)
    public Location exit;

    public boolean utilities = false;
    public boolean keepInventory = false;
    /** Bloque actual de la barrera (AIR = abierta). */
    public String blocks = "GLASS";
    /** Último bloque de barrera que no fue AIR (para volver a cerrarla). */
    public String blocksOn = "GLASS";
    /** Posiciones "x,y,z" de los bloques de la barrera. */
    public List<String> barrier = new ArrayList<>();
    /** efecto (key de Minecraft) -> {segundos (-1 = infinito), nivel} */
    public Map<String, int[]> effects = new LinkedHashMap<>();
    public String lastWinner = "Ninguno";
    public String lastLoser = "Ninguno";

    // Estado en ejecución (no se guarda)
    public final Set<UUID> players = new LinkedHashSet<>();
    /** Ya entraron los 2 jugadores (la barrera se cerró). */
    public boolean active = false;
    /** PvP habilitado. */
    public boolean fighting = false;
    /** Contador para abrir la barrera en marcha. */
    public boolean closing = false;
    public BukkitTask startTask, closeTask;

    public Room(String name) {
        this.name = name;
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
