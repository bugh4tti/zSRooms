package com.zsrooms;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZSRooms extends JavaPlugin {

    // &x&R&R&G&G&B&B
    private static final Pattern HEX_X = Pattern.compile("(?i)&x((?:&[0-9a-f]){6})");
    // &#RRGGBB
    private static final Pattern HEX_AMP = Pattern.compile("(?i)&#([0-9a-f]{6})");
    // #RRGGBB (formato de degradados: #FDFF00&lM#FEFF2C&lE)
    private static final Pattern HEX_BARE = Pattern.compile("(?i)(?<!&)#([0-9a-f]{6})");

    private RoomManager manager;
    private Gui gui;
    private YamlConfiguration menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMenu();

        manager = new RoomManager(this);
        manager.load();
        gui = new Gui(this);

        RoomCommand cmd = new RoomCommand(this);
        PluginCommand pc = getCommand("zsrooms");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new RoomListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiHook(this).register();
            getLogger().info("PlaceholderAPI detectado: placeholders registrados.");
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
            manager.save();
        }
    }

    public RoomManager manager() {
        return manager;
    }

    public Gui gui() {
        return gui;
    }

    public YamlConfiguration menu() {
        return menu;
    }

    public void loadMenu() {
        File f = new File(getDataFolder(), "menu.yml");
        if (!f.exists()) saveResource("menu.yml", false);
        menu = YamlConfiguration.loadConfiguration(f);
    }

    public void reloadAll() {
        reloadConfig();
        loadMenu();
    }

    // ---------------------------------------------------------------- sonidos

    /** Reproduce el sonido de config.yml (sounds.enter / sounds.leave). */
    public void playSound(Player p, String path) {
        ConfigurationSection s = getConfig().getConfigurationSection("sounds." + path);
        if (s == null) return;
        String name = s.getString("sound", "");
        if (name == null || name.trim().isEmpty()) return;
        try {
            p.playSound(p.getLocation(), name.trim(),
                    (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- colores

    public static String color(String s) {
        if (s == null) return "";
        s = hex(HEX_X, s, true);
        s = hex(HEX_AMP, s, false);
        s = hex(HEX_BARE, s, false);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String hex(Pattern p, String s, boolean stripAmp) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String h = m.group(1);
            if (stripAmp) h = h.replace("&", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#" + h).toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- mensajes

    private String fmt(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace(kv[i], kv[i + 1]);
        }
        return s;
    }

    /** Mensaje con prefijo. */
    public String msg(String key, String... kv) {
        String prefix = getConfig().getString("messages.prefix", "");
        String s = getConfig().getString("messages." + key, "&cMensaje faltante: " + key);
        return color(prefix + fmt(s, kv));
    }

    /** Mensaje sin prefijo. */
    public String raw(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&cMensaje faltante: " + key);
        return color(fmt(s, kv));
    }
    }
