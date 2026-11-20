package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

public final class SnafNpcSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_GREEN + "SNAF — ZUPA NA DZIEŃ";
    private final DailyQuestsPlugin plugin;
    private final Connection db;
    private final NamespacedKey npcKey;
    private final File npcFile;
    private final Random random = new Random();

    SnafNpcSystem(DailyQuestsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.npcKey = new NamespacedKey(plugin, "snaf_npc");
        this.npcFile = new File(plugin.getDataFolder(), "snaf_npc.yml");
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS snaf_daily_claims (uuid TEXT NOT NULL, day TEXT NOT NULL, claimed_at INTEGER NOT NULL, PRIMARY KEY(uuid,day))");
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie można utworzyć tabeli dziennych odbiorów Snafa: " + ex.getMessage());
        }
        restoreNpc();
    }

    private String today() {
        try { return LocalDate.now(ZoneId.of(plugin.getConfig().getString("timezone", "Europe/Warsaw"))).toString(); }
        catch (Exception ex) { return LocalDate.now(ZoneId.of("Europe/Warsaw")).toString(); }
    }

    private void restoreNpc() {
        if (!npcFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null || !yaml.contains("x")) return;
        Location location = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
        world.getChunkAt(location).load();
        String saved = yaml.getString("uuid", "");
        Entity existing = null;
        try { if (!saved.isBlank()) existing = Bukkit.getEntity(UUID.fromString(saved)); }
        catch (IllegalArgumentException ignored) { }
        if (existing instanceof Villager villager) {
            configure(villager, yaml.getString("name", "Snaf"));
            return;
        }
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        configure(villager, yaml.getString("name", "Snaf"));
        yaml.set("uuid", villager.getUniqueId().toString());
        save(yaml);
    }

    void spawnAt(Location location, java.util.function.Consumer<String> reply) {
        if (location.getWorld() == null) return;
        removeNpc();
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        String name = yaml.getString("name", "Snaf");
        configure(villager, name);
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX()); yaml.set("y", location.getY()); yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw()); yaml.set("pitch", location.getPitch());
        yaml.set("uuid", villager.getUniqueId().toString()); yaml.set("name", name);
        save(yaml);
        reply.accept(ChatColor.GREEN + "Postawiono Snafa.");
    }

    private void configure(Villager villager, String name) {
        if (villager.isInsideVehicle()) villager.leaveVehicle();
        villager.setAI(false); villager.setInvulnerable(true); villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false); villager.setCanPickupItems(false); villager.setCollidable(false);
        villager.setProfession(Villager.Profession.FARMER);
        villager.setCustomName(ChatColor.GREEN + "✦ " + ChatColor.WHITE + color(name) + ChatColor.GREEN + " ✦");
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
    }

    void removeNpc() {
        if (npcFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
            World savedWorld = Bukkit.getWorld(yaml.getString("world", ""));
            if (savedWorld != null && yaml.contains("x")) savedWorld.getChunkAt((int) Math.floor(yaml.getDouble("x")) >> 4, (int) Math.floor(yaml.getDouble("z")) >> 4).load();
            String saved = yaml.getString("uuid", "");
            try {
                Entity entity = saved.isBlank() ? null : Bukkit.getEntity(UUID.fromString(saved));
                if (entity != null) entity.remove();
            } catch (IllegalArgumentException ignored) { }
            npcFile.delete();
        }
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager
                    && villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) villager.remove();
        }
    }

    void renameNpc(String name, java.util.function.Consumer<String> reply) {
        if (!npcFile.exists()) { reply.accept(ChatColor.RED + "Snaf nie jest jeszcze postawiony."); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        yaml.set("name", name); save(yaml);
        Entity entity = findNpc(yaml);
        if (entity instanceof Villager villager) configure(villager, name);
        reply.accept(ChatColor.GREEN + "Zmieniono nazwę Snafa na: " + color(name));
    }

    void teleport(Player player) {
        if (!npcFile.exists()) { player.sendMessage(ChatColor.RED + "Snaf nie jest jeszcze postawiony."); return; }
        Entity entity = findNpc(YamlConfiguration.loadConfiguration(npcFile));
        if (entity == null) { player.sendMessage(ChatColor.RED + "Nie znaleziono Snafa."); return; }
        player.teleport(entity.getLocation());
    }

    private Entity findNpc(YamlConfiguration yaml) {
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world != null && yaml.contains("x")) world.getChunkAt((int) Math.floor(yaml.getDouble("x")) >> 4, (int) Math.floor(yaml.getDouble("z")) >> 4).load();
        try {
            String saved = yaml.getString("uuid", "");
            return saved.isBlank() ? null : Bukkit.getEntity(UUID.fromString(saved));
        } catch (IllegalArgumentException ex) { return null; }
    }

    private void save(YamlConfiguration yaml) {
        try { yaml.save(npcFile); }
        catch (java.io.IOException ex) { plugin.getLogger().warning("Nie udało się zapisać Snafa: " + ex.getMessage()); }
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        open(event.getPlayer());
    }

    @EventHandler
    public void onMount(EntityMountEvent event) {
        if (event.getMount() instanceof Villager villager
                && villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    private boolean alreadyClaimed(UUID uuid) {
        try (PreparedStatement query = db.prepareStatement("SELECT 1 FROM snaf_daily_claims WHERE uuid=? AND day=?")) {
            query.setString(1, uuid.toString()); query.setString(2, today());
            try (ResultSet result = query.executeQuery()) { return result.next(); }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się sprawdzić odbioru Snafa: " + ex.getMessage());
            return true;
        }
    }

    private void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);
        ItemStack filler = item(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < 27; slot++) if (slot < 9 || slot > 17) inventory.setItem(slot, filler);
        boolean claimed = alreadyClaimed(player.getUniqueId());
        inventory.setItem(13, item(claimed ? Material.BARRIER : Material.MUSHROOM_STEW,
                claimed ? ChatColor.RED + "Dzisiejsza porcja odebrana" : ChatColor.GREEN + "Odbierz 3 zupki",
                claimed ? List.of(ChatColor.GRAY + "Wróć jutro — Snaf znów coś ugotuje.")
                        : List.of(ChatColor.GRAY + "Dostaniesz łącznie 3 zupki.",
                        ChatColor.YELLOW + "Każda jest losowana osobno:",
                        ChatColor.WHITE + "80% — zupa grzybowa",
                        ChatColor.LIGHT_PURPLE + "20% — podejrzana potrawka",
                        ChatColor.DARK_GRAY + "Limit odnawia się o północy.")));
        inventory.setItem(22, item(Material.CLOCK, ChatColor.YELLOW + "Codzienny limit",
                List.of(ChatColor.GRAY + "3 sztuki na gracza, każdego dnia.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 13) return;
        String day = today();
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT OR IGNORE INTO snaf_daily_claims(uuid,day,claimed_at) VALUES(?,?,?)")) {
            insert.setString(1, player.getUniqueId().toString());
            insert.setString(2, day);
            insert.setLong(3, System.currentTimeMillis());
            if (insert.executeUpdate() != 1) {
                player.sendMessage(ChatColor.RED + "Dzisiejsze 3 zupki zostały już odebrane. Wróć jutro!");
                open(player);
                return;
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się zapisać odbioru Snafa: " + ex.getMessage());
            player.sendMessage(ChatColor.RED + "Nie udało się odebrać zupy. Spróbuj ponownie później.");
            return;
        }

        int soups = 0, stews = 0;
        for (int i = 0; i < 3; i++) {
            if (random.nextDouble() < 0.20) stews++; else soups++;
        }
        if (soups > 0) give(player, Material.MUSHROOM_STEW, soups);
        if (stews > 0) give(player, Material.SUSPICIOUS_STEW, stews);
        player.sendMessage(ChatColor.GREEN + "Snaf wręcza ci dzisiejszą porcję: "
                + ChatColor.WHITE + soups + "× zupa grzybowa"
                + ChatColor.GRAY + " oraz " + ChatColor.LIGHT_PURPLE + stews + "× podejrzana potrawka"
                + ChatColor.GRAY + ".");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
        open(player);
    }

    private void give(Player player, Material material, int amount) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, amount));
        overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(name); meta.setLore(lore); stack.setItemMeta(meta);
        return stack;
    }

    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
}