package com.zsrooms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RoomListener implements Listener {

    private final ZSRooms plugin;
    private final RoomManager m;
    private final Map<UUID, BukkitTask> spawnTasks = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();

    public RoomListener(ZSRooms plugin) {
        this.plugin = plugin;
        this.m = plugin.manager();
    }

    private String pos(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private String ok(Object o) {
        return o == null ? "&c✘" : "&a✔";
    }

    // ---------------------------------------------------------------- herramientas

    @EventHandler
    public void onWand(PlayerInteractEvent e) {
        // Solo mano principal y solo clickeando un bloque con la herramienta:
        // no se activa con la mano vacía ni con el aire.
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        String kind = Wands.kind(e.getItem());
        if (kind == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("zsrooms.admin")) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        boolean left = a == Action.LEFT_CLICK_BLOCK;
        RoomManager.Selection s = m.selections.computeIfAbsent(p.getUniqueId(), k -> new RoomManager.Selection());
        Location l = b.getLocation();

        switch (kind) {
            case "axe":
                if (left) {
                    s.pos1 = l;
                    p.sendMessage(plugin.msg("sel-pos1", "%pos%", pos(l)));
                } else {
                    s.pos2 = l;
                    p.sendMessage(plugin.msg("sel-pos2", "%pos%", pos(l)));
                }
                break;
            case "pick":
                if (left) {
                    Location ex = l.clone().add(0.5, 1, 0.5);
                    ex.setYaw(p.getLocation().getYaw());
                    ex.setPitch(p.getLocation().getPitch());
                    s.exit = ex;
                    p.sendMessage(plugin.msg("sel-exit", "%pos%", pos(l)));
                } else {
                    p.sendMessage(plugin.msg("sel-status",
                            "%p1%", ok(s.pos1), "%p2%", ok(s.pos2), "%ex%", ok(s.exit),
                            "%b1%", ok(s.bpos1), "%b2%", ok(s.bpos2)));
                }
                break;
            case "hoe":
                if (left) {
                    s.bpos1 = l;
                    p.sendMessage(plugin.msg("sel-bpos1", "%pos%", pos(l)));
                } else {
                    s.bpos2 = l;
                    p.sendMessage(plugin.msg("sel-bpos2", "%pos%", pos(l)));
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- entrar / salir

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to == null || to.getWorld() == null) return;
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player p = e.getPlayer();

        // Cancela el /spawn pendiente si se mueve
        BukkitTask t = spawnTasks.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            p.sendMessage(plugin.msg("spawn-cancelled"));
        }

        Room in = m.byPlayer(p.getUniqueId());
        Room target = m.byLocation(to);

        // Salió de la room (caminando, /spawn, teleport...)
        if (in != null && in != target) {
            m.leave(p, in, RoomManager.Cause.LEAVE);
        }

        // Entró a una room
        if (target != null && m.byPlayer(p.getUniqueId()) == null && !p.hasPermission("zsrooms.bypass")) {
            if (!m.tryJoin(p, target) && target.exit != null) {
                e.setTo(target.exit.clone());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        BukkitTask t = spawnTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
        Room r = m.byPlayer(p.getUniqueId());
        if (r != null) m.leave(p, r, RoomManager.Cause.DISCONNECT);
    }

    // ---------------------------------------------------------------- PvP

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Room r = m.byPlayer(victim.getUniqueId());
        if (r == null) return;

        Entity d = e.getDamager();
        Player attacker = null;
        if (d instanceof Player) {
            attacker = (Player) d;
        } else if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) d).getShooter();
        }
        if (attacker == null) return;

        // Sin PvP durante la cuenta regresiva ni mientras la room se cierra
        if (!r.fighting && r.players.contains(attacker.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Room r = m.byPlayer(p.getUniqueId());
        if (r == null) return;
        if (r.keepInventory) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
        m.handleDeath(p, r);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location l = m.respawn.remove(e.getPlayer().getUniqueId());
        if (l != null) e.setRespawnLocation(l);
    }

    // ---------------------------------------------------------------- /spawn con delay

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (bypass.contains(id)) return;
        Room r = m.byPlayer(id);
        if (r == null) return;

        String raw = e.getMessage().substring(1);
        String label = raw.split(" ")[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        String sc = plugin.getConfig().getString("settings.spawn-command", "spawn").toLowerCase();
        if (!label.equals(sc)) return;

        int delay = plugin.getConfig().getInt("settings.spawn-delay", 5);
        if (delay <= 0) return;

        e.setCancelled(true);
        if (spawnTasks.containsKey(id)) return;
        p.sendMessage(plugin.msg("spawn-countdown", "%time%", delay + " segundos"));
        spawnTasks.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            spawnTasks.remove(id);
            if (!p.isOnline()) return;
            bypass.add(id);
            try {
                p.performCommand(raw);
            } finally {
                bypass.remove(id);
            }
        }, delay * 20L));
    }

    // ---------------------------------------------------------------- utilities (telas y lanas)

    private boolean isUtilityBlock(Material mat) {
        String n = mat.name();
        for (String s : plugin.getConfig().getStringList("settings.utility-blocks")) {
            if (s.startsWith("*")) {
                if (n.endsWith(s.substring(1).toUpperCase())) return true;
            } else if (n.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Room r = m.byPlayer(p.getUniqueId());
        if (r == null || r.utilities) return;
        if (isUtilityBlock(e.getBlockPlaced().getType())) {
            e.setCancelled(true);
            p.sendMessage(plugin.msg("utilities-off"));
        }
    }
  }
