package com.zsrooms;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Un team armado (o en partida): dos equipos de "size" jugadores. */
public class TeamMatch {

    public final int size;
    public final List<UUID> teamA = new ArrayList<>();
    public final List<UUID> teamB = new ArrayList<>();
    public UUID host;
    public boolean started = false;
    public boolean notifiedWait = false;

    public TeamMatch(int size) {
        this.size = size;
    }

    public String name() {
        return size + "v" + size;
    }

    public boolean contains(UUID id) {
        return teamA.contains(id) || teamB.contains(id);
    }

    public List<UUID> teamOf(UUID id) {
        return teamA.contains(id) ? teamA : teamB;
    }

    public List<UUID> otherTeam(UUID id) {
        return teamA.contains(id) ? teamB : teamA;
    }

    public boolean sameTeam(UUID a, UUID b) {
        return (teamA.contains(a) && teamA.contains(b)) || (teamB.contains(a) && teamB.contains(b));
    }

    public List<UUID> all() {
        List<UUID> l = new ArrayList<>(teamA);
        l.addAll(teamB);
        return l;
    }

    public boolean isFull() {
        return teamA.size() == size && teamB.size() == size;
    }

    public boolean isEmpty() {
        return teamA.isEmpty() && teamB.isEmpty();
    }

    public List<Player> online() {
        List<Player> l = new ArrayList<>();
        for (UUID id : all()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p);
        }
        return l;
    }

    public static String names(List<UUID> ids) {
        List<String> l = new ArrayList<>();
        for (UUID id : ids) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            l.add(n == null ? "?" : n);
        }
        return String.join(", ", l);
    }
}
