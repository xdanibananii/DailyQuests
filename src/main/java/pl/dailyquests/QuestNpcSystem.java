package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

public final class QuestNpcSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_GREEN + "Mistrz Zleceń";
    private final DailyQuestsPlugin plugin;
    private final CrateSystem crates;
    private final Connection db;
    private final NamespacedKey npcKey;
    private final File npcFile;
    private final File questsFile;
    private final Random random = new Random();
    private final Map<UUID, Contract> contracts = new HashMap<>();

    private static final class Contract {
        String id, name, description, material;
        int amount, stars, xp, delivered;
        boolean claimed;
    }

    QuestNpcSystem(DailyQuestsPlugin plugin, CrateSystem crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.db = plugin.database();
        this.npcKey = new NamespacedKey(plugin, "quest_giver");
        this.npcFile = new File(plugin.getDataFolder(), "quest_npc.yml");
        this.questsFile = new File(plugin.getDataFolder(), "npc_quests.yml");
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS npc_contracts (uuid TEXT NOT NULL, day TEXT NOT NULL, quest_id TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,day))");
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie mozna utworzyc tabeli kontraktow NPC: " + ex.getMessage());
        }
        loadDefaultPool();
        restoreNpc();
    }


    private void restoreNpc() {
        if (!npcFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null || !yaml.contains("x")) return;
        Location location = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
        world.getChunkAt(location).load();
        String savedUuid = yaml.getString("uuid", "");
        Entity existing = null;
        try { if (!savedUuid.isBlank()) existing = Bukkit.getEntity(UUID.fromString(savedUuid)); }
        catch (IllegalArgumentException ignored) {}
        if (existing instanceof Villager villager) {
            configure(villager);
            return;
        }
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        configure(villager);
        yaml.set("uuid", villager.getUniqueId().toString());
        saveNpc(yaml);
    }

    private void loadDefaultPool() {
        if (!questsFile.exists()) {
            plugin.saveResource("npc_quests.yml", false);
        }
    }

    private String today() {
        ZoneId zone;
        try { zone = ZoneId.of(plugin.getConfig().getString("timezone", "Europe/Warsaw")); }
        catch (Exception ex) { zone = ZoneId.of("Europe/Warsaw"); }
        return LocalDate.now(zone).toString();
    }

    private YamlConfiguration quests() {
        return YamlConfiguration.loadConfiguration(questsFile);
    }

    private Contract readContract(String id) {
        ConfigurationSection section = quests().getConfigurationSection("contracts." + id);
        if (section == null) return null;
        try {
            Contract c = new Contract();
            c.id = id;
            c.name = color(section.getString("name", id));
            c.description = color(section.getString("description", ""));
            c.material = Material.valueOf(section.getString("item", "LEATHER").toUpperCase(Locale.ROOT)).name();
            c.amount = Math.max(1, section.getInt("amount", 1));
            c.stars = Math.max(0, section.getInt("reward-stars", 0));
            c.xp = Math.max(0, section.getInt("reward-xp", 0));
            return c;
        } catch (Exception ex) {
            plugin.getLogger().warning("Nieprawidlowy kontrakt NPC '" + id + "': " + ex.getMessage());
            return null;
        }
    }

    private Contract getContract(Player player) {
        String day = today();
        try (PreparedStatement q = db.prepareStatement("SELECT quest_id,claimed FROM npc_contracts WHERE uuid=? AND day=?")) {
            q.setString(1, player.getUniqueId().toString());
            q.setString(2, day);
            try (ResultSet r = q.executeQuery()) {
                if (r.next()) {
                    Contract c = readContract(r.getString(1));
                    if (c != null) {
                        c.claimed = r.getInt(2) == 1;
                        return c;
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Blad odczytu kontraktu NPC: " + ex.getMessage());
            return null;
        }

        ConfigurationSection pool = quests().getConfigurationSection("contracts");
        if (pool == null || pool.getKeys(false).isEmpty()) return null;
        List<String> ids = new ArrayList<>(pool.getKeys(false));
        Collections.shuffle(ids);
        for (String id : ids) {
            Contract c = readContract(id);
            if (c == null) continue;
            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT OR IGNORE INTO npc_contracts(uuid,day,quest_id,claimed) VALUES(?,?,?,0)")) {
                insert.setString(1, player.getUniqueId().toString());
                insert.setString(2, day);
                insert.setString(3, id);
                insert.executeUpdate();
                return getContract(player);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Blad przydzielania kontraktu NPC: " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    void spawnAt(Location location, CommandSenderAdapter reply) {
        if (location.getWorld() == null) return;
        removeNpc();
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        configure(villager);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX());
        yaml.set("y", location.getY());
        yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw());
        yaml.set("pitch", location.getPitch());
        yaml.set("uuid", villager.getUniqueId().toString());
        saveNpc(yaml);
        reply.send(ChatColor.GREEN + "Postawiono Mistrza Zlecen na twojej pozycji.");
    }

    private void configure(Villager villager) {
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(false);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCollidable(false);
        villager.setProfession(Villager.Profession.CARTOGRAPHER);
        villager.setCustomName(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Mistrz Zleceń");
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
    }

    void removeNpc() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) entity.remove();
            }
        }
        if (npcFile.exists()) npcFile.delete();
    }

    void teleportToNpc(Player player) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        String worldName = yaml.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || !yaml.contains("x")) {
            player.sendMessage(ChatColor.RED + "NPC nie jest jeszcze postawiony.");
            return;
        }
        Location loc = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
        world.getChunkAt(loc).load();
        player.teleport(loc);
    }

    private void saveNpc(YamlConfiguration yaml) {
        try { yaml.save(npcFile); }
        catch (Exception ex) { plugin.getLogger().warning("Nie zapisano lokalizacji NPC: " + ex.getMessage()); }
    }

    @EventHandler public void onNpcInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8f, 0.9f);
        Contract c = getContract(player);
        if (c == null) {
            player.sendMessage(ChatColor.RED + "Mistrz Zlecen: Dziś nie mam dla ciebie żadnej pracy. Wróć jutro.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "Mistrz Zlecen " + ChatColor.DARK_GRAY + "» " + ChatColor.YELLOW
                + (c.claimed ? "Dobra robota, podróżniku. Jutro znajdę dla ciebie coś nowego."
                : "Witaj, wędrowcze. Mam dla ciebie sprawę do załatwienia..."));
        open(player, c);
    }

    private void open(Player player, Contract c) {
        Inventory inv = Bukkit.createInventory(null, 45, TITLE);
        ItemStack pane = item(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 45; i++) inv.setItem(i, pane);
        inv.setItem(4, item(Material.WRITABLE_BOOK, ChatColor.GOLD + "✦ KONTRAKTY MISTRZA ✦",
                List.of(ChatColor.GRAY + "„Drogi bywają długie, ale sakiewka",
                        ChatColor.GRAY + "nie napełni się sama. Pomożesz mi?”",
                        "", ChatColor.DARK_GRAY + "Jeden kontrakt dziennie.")));
        Material material = Material.valueOf(c.material);
        int owned = count(player, material);
        inv.setItem(20, item(material, ChatColor.YELLOW + c.name,
                List.of(ChatColor.GRAY + c.description, "",
                        ChatColor.WHITE + "Przynieś: " + ChatColor.YELLOW + c.amount + "x " + pretty(material),
                        ChatColor.GRAY + "Masz przy sobie: " + ChatColor.AQUA + owned + "/" + c.amount,
                        "", ChatColor.GOLD + "Nagroda:",
                        ChatColor.YELLOW + c.stars + " ★",
                        ChatColor.GREEN + c.xp + " XP",
                        "", c.claimed ? ChatColor.GREEN + "✔ KONTRAKT ODDANY" : ChatColor.YELLOW + "W TRAKCIE")));
        inv.setItem(31, item(c.claimed ? Material.LIME_DYE : Material.EMERALD,
                c.claimed ? ChatColor.GREEN + "Kontrakt wykonany" : ChatColor.GREEN + "ODDAJ ZLECENIE",
                List.of(c.claimed ? ChatColor.GRAY + "Wróć jutro po nowe zlecenie."
                        : ChatColor.GRAY + "Kliknij, gdy masz wszystkie przedmioty.",
                        ChatColor.DARK_GRAY + "Wymagane: " + c.amount + "x " + pretty(material))));
        player.openInventory(inv);
    }

    @EventHandler public void onContractClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 31) return;
        Contract c = getContract(player);
        if (c == null || c.claimed) { open(player, c == null ? emptyContract() : c); return; }
        Material required = Material.valueOf(c.material);
        int have = count(player, required);
        if (have < c.amount) {
            player.sendMessage(ChatColor.RED + "Mistrz Zlecen: Brakuje ci jeszcze " + (c.amount - have)
                    + "x " + pretty(required) + ". Nie wracaj z pustymi rękami!");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
            open(player, c);
            return;
        }
        remove(player, required, c.amount);
        try (PreparedStatement u = db.prepareStatement(
                "UPDATE npc_contracts SET claimed=1 WHERE uuid=? AND day=? AND claimed=0")) {
            u.setString(1, player.getUniqueId().toString());
            u.setString(2, today());
            if (u.executeUpdate() != 1) {
                player.getInventory().addItem(new ItemStack(required, c.amount));
                player.sendMessage(ChatColor.RED + "Nie udało się zatwierdzić kontraktu. Przedmioty zwrócono.");
                return;
            }
        } catch (SQLException ex) {
            player.getInventory().addItem(new ItemStack(required, c.amount));
            plugin.getLogger().warning("Blad zatwierdzania kontraktu: " + ex.getMessage());
            player.sendMessage(ChatColor.RED + "Wystąpił błąd. Przedmioty zwrócono.");
            return;
        }
        if (c.stars > 0) crates.awardStars(player, c.stars);
        if (c.xp > 0) player.giveExp(c.xp);
        player.sendMessage(ChatColor.GOLD + "Mistrz Zlecen " + ChatColor.DARK_GRAY + "» "
                + ChatColor.GREEN + "Umowa stoi! Wiedziałem, że można na tobie polegać.");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        open(player, getContract(player));
    }

    private Contract emptyContract() {
        Contract c = new Contract();
        c.name = "Brak kontraktu"; c.description = "Wroc jutro."; c.material = Material.PAPER.name();
        c.amount = 1; c.claimed = true;
        return c;
    }

    @EventHandler public void onContractDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    @EventHandler public void onNpcDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    private int count(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        return count;
    }

    private void remove(Player player, Material material, int amount) {
        int left = amount;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            left -= take;
            if (left <= 0) break;
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private String pretty(Material material) {
        StringJoiner out = new StringJoiner(" ");
        for (String part : material.name().toLowerCase(Locale.ROOT).split("_"))
            out.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        return out.toString();
    }

    interface CommandSenderAdapter { void send(String message); }
}
