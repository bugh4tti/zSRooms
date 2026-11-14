package com.zsrooms;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Teams e invitaciones para las rooms 2v2, 3v3 y 4v4. */
public class TeamManager {

    private static class Invite {
        final UUID inviter, invitee;
        final TeamMatch match;
        final boolean ally;
        final long expires;

        Invite(UUID inviter, UUID invitee, TeamMatch match, boolean ally, long expires) {
            this.inviter = inviter;
            this.invitee = invitee;
            this.match = match;
            this.ally = ally;
            this.expires = expires;
        }
    }

    private final ZSRooms plugin;
    private final Map<UUID, TeamMatch> byPlayer = new HashMap<>();
    private final List<TeamMatch> matches = new ArrayList<>();
    private final List<Invite> invites = new ArrayList<>();

    public TeamManager(ZSRooms plugin) {
        this.plugin = plugin;
    }

    private void purge() {
        long now = System.currentTimeMillis();
        invites.removeIf(i -> i.expires < now);
    }

    // ---------------------------------------------------------------- invitar

    public void invite(Player p, int size, String targetName, boolean ally) {
        purge();
        RoomManager rm = plugin.manager();
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) {
            p.sendMessage(plugin.msg("team-no-player", "%player%", targetName));
            return;
        }
        if (t.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("team-self"));
            return;
        }
        if (rm.byPlayer(p.getUniqueId()) != null) {
            p.sendMessage(plugin.msg("team-in-room"));
            return;
        }
        if (rm.byPlayer(t.getUniqueId()) != null || byPlayer.containsKey(t.getUniqueId())) {
            p.sendMessage(plugin.msg("team-target-busy", "%player%", t.getName()));
            return;
        }
        if (rm.teamRoomCount(size) == 0) {
            p.sendMessage(plugin.msg("team-no-rooms", "%mode%", size + "v" + size));
            return;
        }

        TeamMatch mt = byPlayer.get(p.getUniqueId());
        if (mt == null) {
            mt = new TeamMatch(size);
            mt.host = p.getUniqueId();
            mt.teamA.add(p.getUniqueId());
            byPlayer.put(p.getUniqueId(), mt);
            matches.add(mt);
        } else if (mt.size != size) {
            p.sendMessage(plugin.msg("team-other-mode"));
            return;
        }
        if (mt.started) {
            p.sendMessage(plugin.msg("team-started"));
            return;
        }

        List<UUID> target = ally ? mt.teamOf(p.getUniqueId()) : mt.otherTeam(p.getUniqueId());
        if (target.size() >= size) {
            p.sendMessage(plugin.msg("team-full"));
            return;
        }

        UUID from = p.getUniqueId();
        UUID to = t.getUniqueId();
        invites.removeIf(i -> i.inviter.equals(from) && i.invitee.equals(to));
        long ttl = plugin.getConfig().getLong("team.invite-seconds", 60) * 1000L;
        invites.add(new Invite(from, to, mt, ally, System.currentTimeMillis() + ttl));

        p.sendMessage(plugin.msg("team-invite-sent", "%player%", t.getName(),
                "%side%", ally ? "aliado" : "enemigo", "%mode%", mt.name()));
        sendInvite(p, t, mt, ally);
    }

    private void sendInvite(Player from, Player to, TeamMatch mt, boolean ally) {
        String text = plugin.raw(ally ? "team-invite-ally" : "team-invite-enemy",
                "%player%", from.getName(), "%mode%", mt.name());
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(text + " "));

        TextComponent accept = new TextComponent(TextComponent.fromLegacyText(plugin.raw("team-accept-button")));
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/room team accept " + from.getName()));
        TextComponent deny = new TextComponent(TextComponent.fromLegacyText(plugin.raw("team-deny-button")));
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/room team deny " + from.getName()));

        line.addExtra(accept);
        line.addExtra(" ");
        line.addExtra(deny);
        to.spigot().sendMessage(line);
    }

    private Invite findInvite(Player p, String inviterName) {
        Invite found = null;
        for (Invite i : invites) {
            if (!i.invitee.equals(p.getUniqueId())) continue;
            if (inviterName != null) {
                Player from = Bukkit.getPlayer(i.inviter);
                if (from == null || !from.getName().equalsIgnoreCase(inviterName)) continue;
            }
            found = i;
        }
        return found;
    }

    // ---------------------------------------------------------------- aceptar / rechazar

    public void accept(Player p, String inviterName) {
        purge();
        Invite inv = findInvite(p, inviterName);
        if (inv == null) {
            p.sendMessage(plugin.msg("team-no-invite"));
            return;
        }
        invites.remove(inv);

        TeamMatch mt = inv.match;
        Player from = Bukkit.getPlayer(inv.inviter);
        if (from == null || !matches.contains(mt) || mt.started || !mt.contains(inv.inviter)) {
            p.sendMessage(plugin.msg("team-invite-expired"));
            return;
        }
        if (plugin.manager().byPlayer(p.getUniqueId()) != null || byPlayer.containsKey(p.getUniqueId())) {
            p.sendMessage(plugin.msg("team-you-busy"));
            return;
        }
        List<UUID> team = inv.ally ? mt.teamOf(inv.inviter) : mt.otherTeam(inv.inviter);
        if (team.size() >= mt.size) {
            p.sendMessage(plugin.msg("team-full"));
            return;
        }

        team.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), mt);
        String side = mt.teamA.contains(p.getUniqueId()) ? "1" : "2";
        for (Player x : mt.online()) {
            x.sendMessage(plugin.msg("team-joined", "%player%", p.getName(), "%team%", side));
        }
        if (mt.isFull()) tryStart(mt);
    }

    public void deny(Player p, String inviterName) {
        purge();
        Invite inv = findInvite(p, inviterName);
        if (inv == null) {
            p.sendMessage(plugin.msg("team-no-invite"));
            return;
        }
        invites.remove(inv);
        p.sendMessage(plugin.msg("team-denied-you"));
        Player from = Bukkit.getPlayer(inv.inviter);
        if (from != null) from.sendMessage(plugin.msg("team-denied", "%player%", p.getName()));
    }

    // ---------------------------------------------------------------- salir / info

    public void leave(Player p) {
        TeamMatch mt = byPlayer.get(p.getUniqueId());
        if (mt == null) {
            p.sendMessage(plugin.msg("team-not-in"));
            return;
        }
        if (mt.started) {
            p.sendMessage(plugin.msg("team-started"));
            return;
        }
        remove(p, mt);
        p.sendMessage(plugin.msg("team-left"));
    }

    private void remove(Player p, TeamMatch mt) {
        UUID id = p.getUniqueId();
        byPlayer.remove(id);
        mt.teamA.remove(id);
        mt.teamB.remove(id);
        invites.removeIf(i -> i.inviter.equals(id) || i.invitee.equals(id));
        if (mt.isEmpty()) {
            matches.remove(mt);
            invites.removeIf(i -> i.match == mt);
            return;
        }
        if (id.equals(mt.host)) mt.host = mt.all().get(0);
        for (Player x : mt.online()) {
            x.sendMessage(plugin.msg("team-member-left", "%player%", p.getName()));
        }
    }

    public void info(Player p) {
        TeamMatch mt = byPlayer.get(p.getUniqueId());
        if (mt == null) {
            p.sendMessage(plugin.msg("team-not-in"));
            return;
        }
        String none = plugin.getConfig().getString("messages.none", "ninguno");
        String a = mt.teamA.isEmpty() ? none : TeamMatch.names(mt.teamA);
        String b = mt.teamB.isEmpty() ? none : TeamMatch.names(mt.teamB);
        p.sendMessage(plugin.msg("team-info", "%mode%", mt.name(), "%team1%", a, "%team2%", b));
    }

    /** Si se desconecta antes de empezar, sale del team. */
    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        invites.removeIf(i -> i.inviter.equals(id) || i.invitee.equals(id));
        TeamMatch mt = byPlayer.get(id);
        if (mt != null && !mt.started) remove(p, mt);
    }

    // ---------------------------------------------------------------- empezar / terminar

    /** Cuando los dos teams están completos, los manda a una room libre de ese modo. */
    public void tryStart(TeamMatch mt) {
        if (mt.started || !mt.isFull()) return;
        Room r = plugin.manager().pickTeamRoom(mt.size);
        if (r == null) {
            if (!mt.notifiedWait) {
                mt.notifiedWait = true;
                for (Player x : mt.online()) {
                    x.sendMessage(plugin.msg("team-waiting-room", "%mode%", mt.name()));
                }
            }
            return;
        }
        plugin.manager().startTeamMatch(r, mt);
    }

    /** Se llama cuando se libera una room o cambia un modo: arranca los teams que estaban esperando. */
    public void checkPending() {
        for (TeamMatch mt : new ArrayList<>(matches)) {
            if (!mt.started && mt.isFull()) tryStart(mt);
        }
    }

    /** La partida terminó: los jugadores quedan libres para armar otro team. */
    public void finish(TeamMatch mt) {
        for (UUID id : mt.all()) byPlayer.remove(id);
        matches.remove(mt);
        invites.removeIf(i -> i.match == mt);
    }
                         }
