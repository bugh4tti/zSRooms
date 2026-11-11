package com.zsrooms;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PapiHook extends PlaceholderExpansion {

    private final ZSRooms plugin;

    public PapiHook(ZSRooms plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "zsrooms";
    }

    @Override
    public String getAuthor() {
        return "Bughatti";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        int i = params.indexOf('_');
        if (i < 0) return null;
        String key = params.substring(0, i).toLowerCase();
        String roomName = params.substring(i + 1);

        Room r = plugin.manager().get(roomName);
        if (r == null) return "Room inválida";

        switch (key) {
            case "ganador":
            case "winner":
                return r.lastWinner;
            case "perdedor":
            case "loser":
                return r.lastLoser;
            case "effect":
            case "effects":
            case "efectos": {
                if (r.effects.isEmpty()) return plugin.getConfig().getString("messages.none", "ninguno");
                List<String> l = new ArrayList<>();
                for (Map.Entry<String, int[]> e : r.effects.entrySet()) {
                    int s = e.getValue()[0];
                    l.add(e.getKey() + " " + e.getValue()[1] + (s < 0 ? " (∞)" : " (" + s + "s)"));
                }
                return String.join(", ", l);
            }
            case "blocks":
            case "bloques":
                return r.blocks.equals("AIR") ? "off" : "on";
            case "keepinventory":
                return String.valueOf(r.keepInventory);
            case "utilities":
                return r.utilities ? "on" : "off";
            case "players":
            case "jugadores":
                return r.players.size() + "/2";
            default:
                return null;
        }
    }
}
