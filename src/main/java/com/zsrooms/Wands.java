package com.zsrooms;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Wands {

    public static final String AXE_NAME = "&6&lHacha de Room";
    public static final String PICK_NAME = "&b&lPico de Room";
    public static final String HOE_NAME = "&d&lAzada de Room";

    private static ItemStack make(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZSRooms.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(ZSRooms.color(s));
            meta.setLore(l);
            meta.setUnbreakable(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack axe() {
        return make(Material.WOODEN_AXE, AXE_NAME,
                "&7Click izq: &fPos1 de la room",
                "&7Click der: &fPos2 de la room");
    }

    private static ItemStack pick() {
        return make(Material.WOODEN_PICKAXE, PICK_NAME,
                "&7Click izq: &fZona de expulsión",
                "&7Click der: &fVer estado");
    }

    private static ItemStack hoe() {
        return make(Material.WOODEN_HOE, HOE_NAME,
                "&7Click izq: &fPos1 de la barrera (cristales)",
                "&7Click der: &fPos2 de la barrera (cristales)");
    }

    /** Da UNA herramienta según la opción. Devuelve false si la opción no existe. */
    public static boolean give(Player p, String option) {
        switch (option.toLowerCase()) {
            case "hacha":
            case "axe":
                p.getInventory().addItem(axe());
                return true;
            case "pico":
            case "pickaxe":
                p.getInventory().addItem(pick());
                return true;
            case "azada":
            case "hoe":
                p.getInventory().addItem(hoe());
                return true;
            default:
                return false;
        }
    }

    /** Devuelve "axe", "pick", "hoe" o null si no es una herramienta de room. */
    public static String kind(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        String n = meta.getDisplayName();
        if (it.getType() == Material.WOODEN_AXE && n.equals(ZSRooms.color(AXE_NAME))) return "axe";
        if (it.getType() == Material.WOODEN_PICKAXE && n.equals(ZSRooms.color(PICK_NAME))) return "pick";
        if (it.getType() == Material.WOODEN_HOE && n.equals(ZSRooms.color(HOE_NAME))) return "hoe";
        return null;
    }
}
