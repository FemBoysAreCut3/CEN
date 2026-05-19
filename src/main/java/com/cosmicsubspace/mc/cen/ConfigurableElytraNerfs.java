package com.cosmicsubspace.mc.cen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class ConfigurableElytraNerfs extends JavaPlugin implements CommandExecutor, Listener {

    private FileConfiguration messagesConfig;
    private Component commandOutput;
    private final Map<String, Map<UUID, Long>> lastNotifiedTime = new HashMap<>();
    private final Map<UUID, Long> lastOnGround = new HashMap<>();
    private final Map<UUID, List<Long>> boostLog = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();
    private boolean papiEnabled;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.loadMessagesConfig();
        this.papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.commandOutput = parseMsg("command-not-enabled");

        FileConfiguration config = getConfig();
        boolean confAllDisable = config.getBoolean("cen_all_disable");
        boolean confUseTicktime = config.getBoolean("cen-use-tick-time");

        if (!confAllDisable) {
            getServer().getPluginManager().registerEvents(this, this);

            if (config.getBoolean("icarus-enabled")) {
                int confIcarusHit = config.getInt("icarus-durability-hit");
                boolean confIcarusAllowNether = config.getBoolean("icarus-allow-nether");
                boolean confIcarusAllowRaining = config.getBoolean("icarus-allow-raining");
                int confIcarusMinY = config.getInt("icarus-minimum-height");
                int icarusHitPerSec = (int) Math.round(confIcarusHit * 2 / 432.0 * 100);

                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (!p.isGliding()) continue;

                        Location loc = p.getLocation();
                        World w = p.getWorld();
                        int chunkY = loc.getBlockY();

                        int skylight = 0;
                        if (w.hasSkyLight()) {
                            if (chunkY >= w.getMaxHeight()) {
                                skylight = 15;
                            } else {
                                skylight = loc.getBlock().getLightFromSky();
                            }
                        }

                        long time = w.getTime();
                        boolean isDay = (time >= 0) && (time <= 12000);
                        boolean sunUp = confIcarusAllowRaining ? (w.isClearWeather() && isDay && w.hasSkyLight()) : (isDay && w.hasSkyLight());
                        boolean heightHigh = chunkY > confIcarusMinY;
                        boolean sunlightOnPlayer = (skylight == 15) && sunUp && heightHigh;

                        if (!confIcarusAllowNether && w.isUltraWarm()) {
                            sunlightOnPlayer = true;
                        }

                        PlayerInventory pinv = p.getInventory();
                        ItemStack chestplate = pinv.getChestplate();

                        if (chestplate != null && chestplate.getType() == Material.ELYTRA && sunlightOnPlayer && heightHigh) {
                            if (chestplate.getItemMeta() instanceof Damageable dmg) {
                                int damage = dmg.getDamage() + confIcarusHit;
                                if (damage >= Material.ELYTRA.getMaxDurability()) {
                                    damage = Material.ELYTRA.getMaxDurability() - 1;
                                }
                                dmg.setDamage(damage);
                                float durabilityRatio = 1.0f - damage / (float) Material.ELYTRA.getMaxDurability();
                                chestplate.setItemMeta(dmg);
                                pinv.setChestplate(chestplate);

                                if (rateLimitMsg("Icarus", p.getUniqueId(), 10000)) {
                                    String line2Path = confIcarusAllowNether ? "icarus.warn-line2-sunlight" : "icarus.warn-line2-nether";
                                    p.sendMessage(parseMsg("icarus.prefix").append(parseMsg("icarus.warn-line1")));
                                    p.sendMessage(parseMsg("icarus.prefix").append(parseMsg(line2Path)));
                                    p.sendMessage(parseMsg("icarus.prefix").append(parseMsg("icarus.warn-line3", p, "%percentage%", String.valueOf(icarusHitPerSec))));
                                }

                                Component subTitleComp = parseMsg("icarus.title-subtitle", p, "%percentage%", String.valueOf(Math.round(durabilityRatio * 100)));
                                Title title = Title.title(Component.empty(), subTitleComp, Title.Times.times(Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(1000)));
                                p.showTitle(title);
                            }
                        }
                    }
                }, 0L, 10L);
            }

            if (config.getBoolean("acrophobia-enabled")) {
                double confAcrophobiaHeight = config.getDouble("acrophobia-height");
                double confAcrophobiaDurationSec = config.getDouble("acrophobia-duration");
                int confAcrophobiaDurationTicks = (int) Math.round(confAcrophobiaDurationSec * 20);
                int confAcrophobiaPower = (int) config.getDouble("acrophobia-power") - 1;
                double confAcrophobiaDelay = config.getDouble("acrophobia-delay");

                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (Player p : getServer().getOnlinePlayers()) {
                        long t = confUseTicktime ? p.getWorld().getFullTime() * 50 : System.currentTimeMillis();
                        UUID uuid = p.getUniqueId();
                        boolean gliding = p.isGliding();
                        Location loc = p.getLocation();
                        World w = p.getWorld();

                        int highestY = w.getHighestBlockYAt(loc);
                        boolean tooHigh = (loc.getY() - highestY) > confAcrophobiaHeight;
                        boolean scared = tooHigh && gliding;

                        lastOnGround.putIfAbsent(uuid, 0L);

                        if (!scared) {
                            lastOnGround.put(uuid, t);
                        } else {
                            if (rateLimitMsg("acrophobia-warn", uuid, 10000)) {
                                p.sendMessage(parseMsg("acrophobia.prefix").append(parseMsg("acrophobia.warn")));
                            }
                            if (t - lastOnGround.get(uuid) > (confAcrophobiaDelay * 1000 - 0.1)) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, confAcrophobiaDurationTicks, confAcrophobiaPower, true, true));
                                if (rateLimitMsg("acrophobia-notice", uuid, 10000)) {
                                    rateLimitMsg("acrophobia-warn", uuid, 0);
                                    p.sendMessage(parseMsg("acrophobia.prefix").append(parseMsg("acrophobia.notice")));
                                }
                            }
                        }
                    }
                }, 5L, 10L);
            }

            if (config.getBoolean("terminal-velocity-enabled")) {
                double confTvMaxvelMps = config.getDouble("terminal-velocity-speed");
                double confTvMaxvelMpt = confTvMaxvelMps / 20.0;

                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (!p.isGliding()) continue;
                        Vector v = p.getVelocity();
                        if (v.length() > confTvMaxvelMpt) {
                            p.setVelocity(v.normalize().multiply(confTvMaxvelMpt));
                            if (rateLimitMsg("TermVel", p.getUniqueId(), 10000)) {
                                p.sendMessage(parseMsg("terminal-velocity.warn", p, "%speed%", String.valueOf(confTvMaxvelMps)));
                            }
                        }
                    }
                }, 0L, 2L);
            }
        }

        this.buildAndCacheStatusMessage(confAllDisable, confUseTicktime, config);
    }

    @Override
    public void onDisable() {
        lastNotifiedTime.clear();
        lastOnGround.clear();
        boostLog.clear();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        sender.sendMessage(commandOutput);
        return true;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent evt) {
        FileConfiguration config = getConfig();
        if (config.getBoolean("cen_all_disable")) return;

        Player p = evt.getPlayer();
        if (!p.isGliding() || evt.getMaterial() != Material.FIREWORK_ROCKET) return;

        UUID uuid = p.getUniqueId();
        long t = config.getBoolean("cen-use-tick-time") ? p.getWorld().getFullTime() * 50 : System.currentTimeMillis();

        if (config.getBoolean("glider-enabled")) {
            evt.setCancelled(true);
            if (rateLimitMsg("glider", uuid, 1000)) {
                p.sendMessage(parseMsg("glider.warn"));
            }
            return;
        }

        if (config.getBoolean("limit-boost-enabled")) {
            int confLimitboostTimeMs = (int) Math.round(config.getDouble("limit-boost-time-period") * 1000);
            int confLimitboostCount = (int) config.getDouble("limit-boost-count");

            boostLog.putIfAbsent(uuid, new ArrayList<>());
            List<Long> personalList = boostLog.get(uuid);

            personalList.removeIf(time -> Math.abs(time - t) > confLimitboostTimeMs);

            if (!personalList.isEmpty() && (Math.abs(personalList.get(personalList.size() - 1) - t) < 10)) {
                return;
            }

            if (personalList.size() >= confLimitboostCount) {
                evt.setCancelled(true);
                if (rateLimitMsg("limitboost", uuid, 1000)) {
                    if (confLimitboostCount != 1) {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("%count%", String.valueOf(confLimitboostCount));
                        placeholders.put("%seconds%", String.valueOf(confLimitboostTimeMs / 1000));
                        p.sendMessage(parseMsg("limit-boost.warn-multiple", p, placeholders));
                    } else {
                        p.sendMessage(parseMsg("limit-boost.warn-once", p, "%seconds%", String.valueOf(confLimitboostTimeMs / 1000)));
                    }
                }
            } else {
                personalList.add(t);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent evt) {
        UUID uuid = evt.getPlayer().getUniqueId();
        lastOnGround.remove(uuid);
        boostLog.remove(uuid);
        for (Map<UUID, Long> map : lastNotifiedTime.values()) {
            map.remove(uuid);
        }
    }

    private boolean rateLimitMsg(String type, UUID uuid, int millisec) {
        lastNotifiedTime.putIfAbsent(type, new HashMap<>());
        Map<UUID, Long> uuid2time = lastNotifiedTime.get(type);
        uuid2time.putIfAbsent(uuid, -1000000L);

        long lastNotified = uuid2time.get(uuid);
        long t = System.currentTimeMillis();

        if (Math.abs(t - lastNotified) > millisec) {
            uuid2time.put(uuid, t);
            return true;
        }
        return false;
    }

    private void loadMessagesConfig() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private Component parseMsg(String path) {
        return parseMsg(path, null, Collections.emptyMap());
    }

    private Component parseMsg(String path, Player player, String target, String replacement) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put(target, replacement);
        return parseMsg(path, player, placeholders);
    }

    private Component parseMsg(String path, Player player, Map<String, String> internalPlaceholders) {
        String raw = messagesConfig.getString(path, "Missing path: " + path);
        for (Map.Entry<String, String> entry : internalPlaceholders.entrySet()) {
            raw = raw.replace(entry.getKey(), entry.getValue());
        }
        if (papiEnabled && player != null) {
            raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, raw);
        }
        return mm.deserialize(raw);
    }

    private void buildAndCacheStatusMessage(boolean disabled, boolean useTicktime, FileConfiguration config) {
        StringBuilder sb = new StringBuilder();
        sb.append("<light_purple>## <bold>Configurable Elytra Nerfs</bold> ##</light_purple>\n");
        sb.append("  Plugin Status: ").append(disabled ? "<red><bold>DISABLED</bold></red>" : "<green><bold>ENABLED</bold></green>").append("\n");

        if (!disabled) {
            sb.append("    Use tick time: <bold>").append(useTicktime).append("</bold>\n");
            appendModuleStatus(sb, "ICARUS", config.getBoolean("icarus-enabled"), () -> {
                sb.append("      Durability hit: <bold>").append(config.getInt("icarus-durability-hit")).append("</bold>\n");
                sb.append("      Allow nether: <bold>").append(config.getBoolean("icarus-allow-nether")).append("</bold>\n");
                sb.append("      Allow Rain: <bold>").append(config.getBoolean("icarus-allow-raining")).append("</bold>\n");
                sb.append("      Min Y: <bold>").append(config.getInt("icarus-minimum-height")).append("</bold>\n");
            });
            appendModuleStatus(sb, "Glider", config.getBoolean("glider-enabled"), null);
            appendModuleStatus(sb, "Terminal Velocity", config.getBoolean("terminal-velocity-enabled"), () ->
                    sb.append("      Max speed (m/s): <bold>").append(config.getDouble("terminal-velocity-speed")).append("</bold>\n")
            );
            appendModuleStatus(sb, "Limit Boost", config.getBoolean("limit-boost-enabled"), () -> {
                sb.append("      Time range (sec): <bold>").append(config.getDouble("limit-boost-time-period")).append("</bold>\n");
                sb.append("      Max boost count: <bold>").append(config.getInt("limit-boost-count")).append("</bold>\n");
            });
            appendModuleStatus(sb, "Acrophobia", config.getBoolean("acrophobia-enabled"), () -> {
                sb.append("      Height (m): <bold>").append(config.getDouble("acrophobia-height")).append("</bold>\n");
                sb.append("      Blindness duration (sec): <bold>").append(config.getDouble("acrophobia-duration")).append("</bold>\n");
                sb.append("      Blindness power: <bold>").append((int) config.getDouble("acrophobia-power")).append("</bold>\n");
                sb.append("      Delay (sec): <bold>").append(config.getDouble("acrophobia-delay")).append("</bold>\n");
            });
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        commandOutput = mm.deserialize(sb.toString());
    }

    private void appendModuleStatus(StringBuilder sb, String moduleName, boolean enabled, Runnable detailedStats) {
        sb.append("  Module [ <bold>").append(moduleName).append("</bold> ]: ").append(enabled ? "<green><bold>ENABLED</bold></green>" : "<red><bold>DISABLED</bold></red>").append("\n");
        if (enabled && detailedStats != null) {
            detailedStats.run();
        }
    }
}