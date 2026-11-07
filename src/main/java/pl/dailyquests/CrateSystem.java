package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class CrateSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "Skrzynka DailyQuests";
    private static final int COST = 20;
    private final JavaPlugin plugin;
    private final Connection db;
    private final Random random = new Random();
    private final Map<UUID, BukkitTask> opening = new HashMap<>();
    private final NamespacedKey crateKey;

    private static final class Reward {
        Material material;
        int amount, weight;
        String tier, name;
        List<String> lore;
        Map<String,Integer> enchants;
    }

    public CrateSystem(JavaPlugin plugin) {
        this.plugin = plugin;
        this.crateKey = new NamespacedKey(plugin, "dq_crate_button");
        plugin.saveResource("rewards.yml", false);
        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "dailyquests.db").getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS dq_wallet (uuid TEXT PRIMARY KEY, stars INTEGER NOT NULL DEFAULT 0)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS dq_crate_stats (uuid TEXT PRIMARY KEY, opened INTEGER NOT NULL DEFAULT 0, legendary INTEGER NOT NULL DEFAULT 0)");
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie można uruchomić systemu skrzynek: " + ex.getMessage());
        }
        this.db = connection;
    }

    public void close() {
        for (BukkitTask task : opening.values()) task.cancel();
        opening.clear();
        if (db != null) try { db.close(); } catch (SQLException ignored) {}
    }

    public int getStars(UUID uuid) {
        if (db == null) return 0;
        try (PreparedStatement q = db.prepareStatement("SELECT stars FROM dq_wallet WHERE uuid=?")) {
            q.setString(1, uuid.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Błąd odczytu gwiazdek: " + ex.getMessage());
            return 0;
        }
    }

    public void awardStars(Player player, int amount) {
        if (db == null || amount <= 0) return;
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO dq_wallet(uuid,stars) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET stars=stars+excluded.stars")) {
            st.setString(1, player.getUniqueId().toString());
            st.setInt(2, amount);
            st.executeUpdate();
            player.sendMessage(ChatColor.GOLD + "★ " + ChatColor.YELLOW + "+" + amount
                    + " gwiazdek! " + ChatColor.GRAY + "Saldo: " + ChatColor.WHITE + getStars(player.getUniqueId()) + " ★");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Błąd przyznawania gwiazdek: " + ex.getMessage());
        }
    }

    public void showBalance(Player player) {
        player.sendMessage(ChatColor.GOLD + "★ Twoje saldo: " + ChatColor.YELLOW + getStars(player.getUniqueId()) + " gwiazdek");
        player.sendMessage(ChatColor.GRAY + "Ukończ wszystkie 5 zadań dziennych, aby otrzymać kolejne 5.");
        player.sendMessage(ChatColor.GRAY + "Skrzynka kosztuje " + ChatColor.YELLOW + COST + " ★" + ChatColor.GRAY + ". Komenda: " + ChatColor.YELLOW + "/dq skrzynka");
    }

    public void open(Player player) {
        if (db == null) {
            player.sendMessage(ChatColor.RED + "System skrzynek jest chwilowo niedostępny.");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(4, item(Material.NETHER_STAR, ChatColor.GOLD + "★ SKRZYNKA DAILYQUESTS ★",
                List.of(ChatColor.GRAY + "Losuj wyjątkowe nagrody!", "",
                        ChatColor.YELLOW + "Koszt: " + COST + " gwiazdek",
                        ChatColor.YELLOW + "Twoje saldo: " + getStars(player.getUniqueId()) + " ★",
                        "", ChatColor.DARK_GRAY + "Szanse na rzadkości:",
                        ChatColor.GRAY + "COMMON 40%  " + ChatColor.GREEN + "UNCOMMON 30%",
                        ChatColor.BLUE + "RARE 18%  " + ChatColor.RED + "EPIC 9%",
                        ChatColor.GOLD + "LEGENDARY 3%")));
        ItemStack chest = item(Material.CHEST, ChatColor.YELLOW + "OTWÓRZ SKRZYNKĘ",
                List.of(ChatColor.GRAY + "Kliknij, aby wydać " + COST + " gwiazdek.",
                        ChatColor.GRAY + "Aktualne saldo: " + getStars(player.getUniqueId()) + " ★",
                        "", ChatColor.GREEN + "Powodzenia!"));
        ItemMeta meta = chest.getItemMeta();
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        chest.setItemMeta(meta);
        inv.setItem(13, chest);
        inv.setItem(22, item(Material.GOLD_INGOT, ChatColor.GOLD + "Twoje gwiazdki",
                List.of(ChatColor.YELLOW + "" + getStars(player.getUniqueId()) + " ★")));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.1f);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 13) return;
        if (opening.containsKey(player.getUniqueId())) return;
        if (!spend(player.getUniqueId(), COST)) {
            player.sendMessage(ChatColor.RED + "Nie masz wystarczająco gwiazdek! Potrzebujesz " + COST + " ★.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
            open(player);
            return;
        }
        beginOpening(player);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private boolean spend(UUID uuid, int amount) {
        if (db == null) return false;
        try (PreparedStatement st = db.prepareStatement("UPDATE dq_wallet SET stars=stars-? WHERE uuid=? AND stars>=?")) {
            st.setInt(1, amount);
            st.setString(2, uuid.toString());
            st.setInt(3, amount);
            return st.executeUpdate() == 1;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Błąd pobierania gwiazdek: " + ex.getMessage());
            return false;
        }
    }

    private void beginOpening(Player player) {
        List<Reward> rewards = loadRewards();
        if (rewards.isEmpty()) {
            awardStars(player, COST);
            player.sendMessage(ChatColor.RED + "Pula nagród jest pusta. Zwrócono gwiazdki.");
            open(player);
            return;
        }
        Reward won = choose(rewards);
        Inventory inv = player.getOpenInventory().getTopInventory();
        player.sendMessage(ChatColor.LIGHT_PURPLE + "★ Skrzynka ruszyła... powodzenia!");
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 0.8f);
        BukkitTask task = new BukkitRunnable() {
            int step = 0;
            int visual = 0;
            @Override public void run() {
                if (!player.isOnline() || !TITLE.equals(player.getOpenInventory().getTitle())) {
                    cancel();
                    opening.remove(player.getUniqueId());
                    return;
                }
                step++;
                int interval = step < 14 ? 1 : step < 23 ? 2 : step < 30 ? 3 : 5;
                if (step % interval != 0) return;
                Reward shown = rewards.get(random.nextInt(rewards.size()));
                inv.setItem(13, rewardItem(shown, true));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.45f, 0.7f + (visual * 0.035f));
                visual++;
                if (step >= 38) {
                    cancel();
                    opening.remove(player.getUniqueId());
                    deliver(player, won);
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
        opening.put(player.getUniqueId(), task);
    }

    private List<Reward> loadRewards() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Reward> result = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("rewards")) {
            try {
                Reward reward = new Reward();
                reward.material = Material.valueOf(String.valueOf(row.get("material")).toUpperCase(Locale.ROOT));
                reward.amount = Math.max(1, Integer.parseInt(String.valueOf(row.getOrDefault("amount", 1))));
                reward.weight = Math.max(0, Integer.parseInt(String.valueOf(row.getOrDefault("weight", 1))));
                reward.tier = String.valueOf(row.getOrDefault("tier", "COMMON")).toUpperCase(Locale.ROOT);
                reward.name = color(String.valueOf(row.getOrDefault("name", pretty(reward.material.name()))));
                Object loreValue = row.get("lore");
                reward.lore = new ArrayList<>();
                if (loreValue instanceof List<?> lines) for (Object line : lines) reward.lore.add(color(String.valueOf(line)));
                reward.enchants = new LinkedHashMap<>();
                Object enchValue = row.get("enchants");
                if (enchValue instanceof Map<?,?> enchants) for (Map.Entry<?,?> entry : enchants.entrySet())
                    reward.enchants.put(String.valueOf(entry.getKey()), Integer.parseInt(String.valueOf(entry.getValue())));
                if (reward.weight > 0) result.add(reward);
            } catch (Exception ex) {
                plugin.getLogger().warning("Pominięto nieprawidłową nagrodę w rewards.yml: " + ex.getMessage());
            }
        }
        return result;
    }

    private Reward choose(List<Reward> rewards) {
        int total = rewards.stream().mapToInt(r -> r.weight).sum();
        int roll = random.nextInt(Math.max(1, total));
        for (Reward reward : rewards) {
            roll -= reward.weight;
            if (roll < 0) return reward;
        }
        return rewards.get(rewards.size() - 1);
    }

    private void deliver(Player player, Reward reward) {
        ItemStack won = rewardItem(reward, false);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(won);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        String tierColor = tierColor(reward.tier);
        player.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(ChatColor.GOLD + "★ " + tierColor + "WYL0SOWANO: " + reward.name);
        player.sendMessage(ChatColor.GRAY + "Rzadkość: " + tierColor + reward.tier);
        player.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━");
        player.playSound(player.getLocation(), reward.tier.equals("LEGENDARY") ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, reward.tier.equals("LEGENDARY") ? 0.8f : 1.2f);
        if (reward.tier.equals("LEGENDARY")) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.YELLOW + player.getName() + ChatColor.WHITE + " trafił(a) " + ChatColor.GOLD + "LEGENDARY " + reward.name + ChatColor.GOLD + " ze skrzynki DailyQuests!");
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 50, 0.6, 0.8, 0.6, 0.15);
        } else if (reward.tier.equals("EPIC")) {
            player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation().add(0, 1, 0), 25, 0.5, 0.7, 0.5, 0.08);
        }
        updateStats(player.getUniqueId(), reward.tier.equals("LEGENDARY"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) open(player); }, 50L);
    }

    private void updateStats(UUID uuid, boolean legendary) {
        if (db == null) return;
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO dq_crate_stats(uuid,opened,legendary) VALUES(?,1,?) ON CONFLICT(uuid) DO UPDATE SET opened=opened+1,legendary=legendary+excluded.legendary")) {
            st.setString(1, uuid.toString());
            st.setInt(2, legendary ? 1 : 0);
            st.executeUpdate();
        } catch (SQLException ex) { plugin.getLogger().warning("Błąd statystyk skrzynek: " + ex.getMessage()); }
    }

    private ItemStack rewardItem(Reward reward, boolean rolling) {
        ItemStack stack = new ItemStack(reward.material, reward.amount);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(tierColor(reward.tier) + reward.name);
        List<String> lore = new ArrayList<>();
        lore.add(tierColor(reward.tier) + "✦ " + reward.tier);
        for (String line : reward.lore) lore.add(line);
        if (!reward.enchants.isEmpty()) {
            for (Map.Entry<String,Integer> enchant : reward.enchants.entrySet()) {
                Enchantment type = Enchantment.getByKey(NamespacedKey.minecraft(enchant.getKey().toLowerCase(Locale.ROOT)));
                if (type != null) meta.addEnchant(type, enchant.getValue(), true);
            }
        }
        if (rolling) lore.add(ChatColor.DARK_GRAY + "Losowanie...");
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ChatColor tierColor(String tier) {
        return switch (tier) {
            case "UNCOMMON" -> ChatColor.GREEN;
            case "RARE" -> ChatColor.BLUE;
            case "EPIC" -> ChatColor.RED;
            case "LEGENDARY" -> ChatColor.GOLD;
            default -> ChatColor.GRAY;
        };
    }

    private String pretty(String material) {
        StringJoiner out = new StringJoiner(" ");
        for (String part : material.toLowerCase(Locale.ROOT).split("_"))
            out.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        return out.toString();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
