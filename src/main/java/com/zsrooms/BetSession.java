package com.zsrooms;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/** Una apuesta entre dos jugadores de una room. */
public class BetSession {

    public final UUID id = UUID.randomUUID();
    public final Room room;
    public final UUID[] ids = new UUID[2];
    public final String[] names = new String[2];
    /** Items apostados por cada lado (un lugar por slot del menú). */
    public final ItemStack[][] offers;
    public final boolean[] accepted = new boolean[2];
    public final Inventory[] invs = new Inventory[2];

    /** true = la apuesta está confirmada y los items están en escrow. */
    public boolean locked = false;
    /** true = ya se canceló o se pagó al ganador. */
    public boolean finished = false;
    /** Segundos que faltan para confirmar (-1 = no hay contador). */
    public int countdown = -1;
    public BukkitTask task;

    public BetSession(Room room, UUID a, String aName, UUID b, String bName, int cap) {
        this.room = room;
        this.ids[0] = a;
        this.ids[1] = b;
        this.names[0] = aName;
        this.names[1] = bName;
        this.offers = new ItemStack[2][cap];
    }

    /** Cantidad de lugares ocupados de ese lado. */
    public int count(int side) {
        int n = 0;
        for (ItemStack it : offers[side]) {
            if (it != null) n++;
        }
        return n;
    }

    /** Cantidad total de items (sumando los stacks) de ese lado. */
    public int amount(int side) {
        int n = 0;
        for (ItemStack it : offers[side]) {
            if (it != null) n += it.getAmount();
        }
        return n;
    }
}
