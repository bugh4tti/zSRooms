package com.zsrooms;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Editar desde el juego los tiempos de config.yml (se escribe el número en el chat).
 * El archivo se modifica línea por línea, así no se pierden los comentarios.
 */
public class TimeEditor implements Listener {

    public static final String[] KEYS = {"death", "leave", "disconnect", "spawn", "team", "bet"};

    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,4})\\s*(s|seg|segundos)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+):(\\s*)(.*)$");
    private static final Pattern INT_VALUE = Pattern.compile("^(-?\\d+)(.*)$");

    private static class Pending {
        final String key;
        final long expires;

        Pending(String key, long expires) {
            this.key = key;
            this.expires = expires;
        }
    }

    private final ZSRooms plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public TimeEditor(ZSRooms plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- claves

    /** Ruta en config.yml de cada tiempo (null si la clave no existe). */
    public static String path(String key) {
        switch (key) {
            case "death":
                return "settings.open-delay.death";
            case "leave":
                return "settings.open-delay.leave";
            case "disconnect":
                return "settings.open-delay.disconnect";
            case "spawn":
                return "settings.spawn-delay";
            case "team":
                return "team.countdown";
            case "bet":
                return "bet.countdown";
            default:
                return null;
        }
    }

    private static int defaultValue(String key) {
        switch (key) {
            case "disconnect":
            case "spawn":
            case "team":
                return 5;
            default:
                return 10;
        }
    }

    public static int max(String key) {
        return (key.equals("team") || key.equals("bet")) ? 60 : 600;
    }

    private String label(String key) {
        return plugin.getConfig().getString("messages.time-label-" + key, key);
    }

    public int current(String key) {
        return plugin.getConfig().getInt(path(key), defaultValue(key));
    }

    // ---------------------------------------------------------------- pedir el número por chat

    /** Cierra el menú y le pide al jugador que escriba el nuevo tiempo en el chat. */
    public void start(Player p, String key) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(key, System.currentTimeMillis() + 60_000L));
        p.sendMessage(plugin.msg("time-prompt", "%label%", ZSRooms.color(label(key)),
                "%current%", String.valueOf(current(key)), "%max%", String.valueOf(max(key))));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        if (System.currentTimeMillis() > pd.expires) {
            pending.remove(p.getUniqueId());
            return;
        }
        e.setCancelled(true);

        String text = e.getMessage().trim();
        if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
            pending.remove(p.getUniqueId());
            p.sendMessage(plugin.msg("time-cancelled"));
            return;
        }

        Matcher m = NUMBER.matcher(text);
        if (!m.matches() || Integer.parseInt(m.group(1)) > max(pd.key)) {
            p.sendMessage(plugin.msg("time-invalid", "%max%", String.valueOf(max(pd.key))));
            return;
        }
        final int secs = Integer.parseInt(m.group(1));
        pending.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> set(p, pd.key, secs));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- guardar

    /** Guarda el tiempo en config.yml y lo aplica al instante. */
    public boolean set(CommandSender who, String key, int secs) {
        String path = path(key);
        if (path == null) return false;
        if (!who.hasPermission("zsrooms.admin")) {
            who.sendMessage(plugin.msg("no-permission"));
            return false;
        }
        File f = new File(plugin.getDataFolder(), "config.yml");
        if (!setInt(f, path, secs)) {
            who.sendMessage(plugin.msg("time-save-failed", "%path%", path));
            return false;
        }
        plugin.reloadConfig();
        who.sendMessage(plugin.msg("time-set", "%label%", ZSRooms.color(label(key)), "%time%", String.valueOf(secs)));
        return true;
    }

    /** Cambia un número de config.yml sin tocar el resto del archivo ni los comentarios. */
    private static boolean setInt(File file, String path, int value) {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            List<Integer> indents = new ArrayList<>();
            List<String> keys = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue;

                Matcher m = KEY_LINE.matcher(line);
                if (!m.matches()) continue;

                int indent = m.group(1).length();
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    keys.remove(keys.size() - 1);
                }
                indents.add(indent);
                keys.add(m.group(2));

                if (String.join(".", keys).equals(path)) {
                    Matcher num = INT_VALUE.matcher(m.group(4));
                    if (!num.matches()) return false;
                    lines.set(i, m.group(1) + m.group(2) + ":" + m.group(3) + value + num.group(2));
                    Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
                    return true;
                }
            }
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- lista con [click para editar]

    public void sendList(Player p) {
        p.sendMessage(plugin.raw("times-header"));
        for (String key : KEYS) {
            String line = plugin.raw("times-line", "%label%", ZSRooms.color(label(key)),
                    "%time%", String.valueOf(current(key)));
            TextComponent text = new TextComponent(TextComponent.fromLegacyText(line + " "));
            TextComponent button = new TextComponent(TextComponent.fromLegacyText(plugin.raw("times-edit-button")));
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/room settime " + key));
            text.addExtra(button);
            p.spigot().sendMessage(text);
        }
    }
}
