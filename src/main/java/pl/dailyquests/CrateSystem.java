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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        balanceExistingRewards();
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

    private void balanceExistingRewards() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        File marker = new File(plugin.getDataFolder(), "rewards-validation-v3.done");

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!hasValidRewardPool(yaml)) {
            File backup = new File(plugin.getDataFolder(), "rewards.yml.corrupt.bak");
            if (backup.exists()) backup = new File(plugin.getDataFolder(),
                    "rewards.yml.corrupt-" + System.currentTimeMillis() + ".bak");

            try {
                if (file.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                plugin.saveResource("rewards.yml", true);
                yaml = YamlConfiguration.loadConfiguration(file);
                if (!hasValidRewardPool(yaml)) {
                    plugin.getLogger().severe("Domyślny rewards.yml również jest nieprawidłowy. System skrzynek pozostaje wyłączony.");
                    return;
                }
                plugin.getLogger().warning("Wykryto uszkodzony rewards.yml. Zapisano kopię jako "
                        + backup.getName() + " i przywrócono domyślną pulę nagród.");
            } catch (java.io.IOException | IllegalArgumentException ex) {
                plugin.getLogger().severe("Nie udało się zabezpieczyć lub przywrócić rewards.yml: " + ex.getMessage());
                return;
            }
        }

        File oddsMarker = new File(plugin.getDataFolder(), "crate-odds-v4.done");
        if (!oddsMarker.exists()) {
            for (int i = 0; i < yaml.getMapList("rewards").size(); i++) {
                String base = "rewards." + i + ".";
                String tier = yaml.getString(base + "tier", "COMMON").toUpperCase(Locale.ROOT);
                int weight = switch (tier) {
                    case "UNCOMMON" -> 150;
                    case "RARE" -> 10;
                    case "EPIC", "LEGENDARY" -> 1;
                    default -> 1000;
                };
                yaml.set(base + "weight", weight);
            }
            try {
                yaml.save(file);
                if (oddsMarker.createNewFile()) {
                    plugin.getLogger().info("Zastosowano bardzo rzadkie szanse dla nagród RARE, EPIC i LEGENDARY.");
                }
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Nie udało się zapisać nowych szans nagród: " + ex.getMessage());
            }
        }

        if (!marker.exists()) {
            try {
                if (marker.createNewFile()) plugin.getLogger().info("Zweryfikowano pulę nagród skrzynki (v3).");
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Nie udało się zapisać znacznika walidacji nagród: " + ex.getMessage());
            }
        }
    }

    private boolean hasValidRewardPool(YamlConfiguration yaml) {
        List<Map<?, ?>> rows = yaml.getMapList("rewards");
        if (rows.isEmpty()) return false;

        int validRewards = 0;
        long totalWeight = 0;
        for (Map<?, ?> row : rows) {
            try {
                Object materialValue = row.get("material");
                Object amountValue = row.get("amount");
                Object weightValue = row.get("weight");
                if (materialValue == null || amountValue == null || weightValue == null) return false;

                Material material = Material.matchMaterial(String.valueOf(materialValue).toUpperCase(Locale.ROOT));
                int amount = Integer.parseInt(String.valueOf(amountValue));
                int weight = Integer.parseInt(String.valueOf(weightValue));
                if (material == null || !material.isItem() || amount <= 0 || weight <= 0) return false;

                validRewards++;
                totalWeight += weight;
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return validRewards > 0 && totalWeight > 0;
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

    public boolean modifyStars(UUID uuid, String operation, int amount) {
        if (db == null || amount < 0) return false;
        try {
            if ("dodaj".equals(operation)) {
                try (PreparedStatement st = db.prepareStatement(
                        "INSERT INTO dq_wallet(uuid,stars) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET stars=MIN(2147483647, stars+excluded.stars)")) {
                    st.setString(1, uuid.toString());
                    st.setInt(2, amount);
                    return st.executeUpdate() > 0;
                }
            }
            if ("usun".equals(operation)) {
                try (PreparedStatement st = db.prepareStatement(
                        "INSERT INTO dq_wallet(uuid,stars) VALUES(?,0) ON CONFLICT(uuid) DO UPDATE SET stars=MAX(0,stars-?)")) {
                    st.setString(1, uuid.toString());
                    st.setInt(2, amount);
                    return st.executeUpdate() > 0;
                }
            }
            if ("ustaw".equals(operation)) {
                try (PreparedStatement st = db.prepareStatement(
                        "INSERT INTO dq_wallet(uuid,stars) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET stars=excluded.stars")) {
                    st.setString(1, uuid.toString());
                    st.setInt(2, amount);
                    return st.executeUpdate() > 0;
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Błąd modyfikacji gwiazdek: " + ex.getMessage());
        }
        return false;
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
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(4, item(Material.NETHER_STAR, ChatColor.GOLD + "★ SKRZYNKA DAILYQUESTS ★",
                List.of(ChatColor.GRAY + "Losuj wyjątkowe nagrody!", "",
                        ChatColor.YELLOW + "Koszt: " + COST + " gwiazdek",
                        ChatColor.YELLOW + "Twoje saldo: " + getStars(player.getUniqueId()) + " ★",
                        "", ChatColor.DARK_GRAY + "Szanse na rzadkości:",
                        ChatColor.GRAY + "COMMON " + chanceForTier("COMMON") + "%  " + ChatColor.GREEN + "UNCOMMON " + chanceForTier("UNCOMMON") + "%",
                        ChatColor.BLUE + "RARE " + chanceForTier("RARE") + "%  " + ChatColor.RED + "EPIC " + chanceForTier("EPIC") + "%",
                        ChatColor.GOLD + "LEGENDARY " + chanceForTier("LEGENDARY") + "%")));
        ItemStack chest = item(Material.CHEST, ChatColor.YELLOW + "OTWÓRZ SKRZYNKĘ",
                List.of(ChatColor.GRAY + "Kliknij, aby wydać " + COST + " gwiazdek.",
                        ChatColor.GRAY + "Aktualne saldo: " + getStars(player.getUniqueId()) + " ★",
                        "", ChatColor.GREEN + "Powodzenia!"));
        ItemMeta meta = chest.getItemMeta();
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        chest.setItemMeta(meta);
        inv.setItem(22, chest);
        inv.setItem(49, item(Material.GOLD_INGOT, ChatColor.GOLD + "Twoje gwiazdki",
                List.of(ChatColor.YELLOW + "" + getStars(player.getUniqueId()) + " ★")));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.1f);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 22) return;
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
            player.sendMessage(ChatColor.RED + "Pula nagród jest nieprawidłowa lub pusta. Gwiazdki zwrócono; administrator powinien sprawdzić rewards.yml.");
            open(player);
            return;
        }

        // CS-style roulette: a strip of rewards moves from right to left.
        // The winning reward is fixed before the animation and stops under the center marker.
        final int winnerIndex = 48;
        final int finalOffset = winnerIndex - 4;
        List<Reward> strip = new ArrayList<>();
        for (int i = 0; i <= winnerIndex + 4; i++) strip.add(choose(rewards));
        Reward won = choose(rewards);
        strip.set(winnerIndex, won);

        Inventory inv = player.getOpenInventory().getTopInventory();
        player.sendMessage(ChatColor.LIGHT_PURPLE + "★ Ruletka skrzynki ruszyła! Obserwuj środek...");
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 0.8f);

        ItemStack frame = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        ItemStack marker = item(Material.YELLOW_STAINED_GLASS_PANE,
                ChatColor.GOLD + "▼ WYGRANA ▼", List.of(ChatColor.YELLOW + "Nagroda zatrzyma się tutaj"));
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9;
            if (row != 2) inv.setItem(slot, frame);
        }
        inv.setItem(13, marker);
        inv.setItem(31, marker);
        inv.setItem(4, item(Material.NETHER_STAR, ChatColor.GOLD + "★ LOSOWANIE ★",
                List.of(ChatColor.GRAY + "Nagroda pod wskaźnikiem zostanie przyznana")));

        BukkitTask task = new BukkitRunnable() {
            int offset = 0;
            int ticksUntilMove = 0;

            @Override public void run() {
                if (!player.isOnline()) {
                    cancel();
                    opening.remove(player.getUniqueId());
                    refund(player.getUniqueId(), COST);
                    return;
                }
                if (ticksUntilMove > 0) {
                    ticksUntilMove--;
                    return;
                }

                for (int column = 0; column < 9; column++) {
                    Reward shown = strip.get(offset + column);
                    inv.setItem(18 + column, rewardItem(shown, true));
                }
                offset++;
                int remaining = finalOffset - offset;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.55f,
                        Math.min(1.8f, 0.65f + offset * 0.018f));

                if (offset >= finalOffset + 1) {
                    cancel();
                    opening.remove(player.getUniqueId());
                    inv.setItem(22, rewardItem(won, false));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
                    deliver(player, won);
                    return;
                }

                // Fast start, then increasingly long pauses for a satisfying stop.
                ticksUntilMove = remaining > 25 ? 0 : remaining > 15 ? 1
                        : remaining > 9 ? 2 : remaining > 5 ? 3 : remaining > 2 ? 5 : 8;
            }
        }.runTaskTimer(plugin, 1L, 1L);
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
                reward.amount = Math.max(1, Integer.parseInt(String.valueOf(value(row, "amount", 1))));
                reward.weight = Math.max(0, Integer.parseInt(String.valueOf(value(row, "weight", 1))));
                reward.tier = String.valueOf(value(row, "tier", "COMMON")).toUpperCase(Locale.ROOT);
                reward.name = color(String.valueOf(value(row, "name", pretty(reward.material.name()))));
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
        if (result.isEmpty()) plugin.getLogger().severe("Pula rewards.yml nie zawiera poprawnej nagrody z wagą większą od zera. Sprawdź material, amount i weight.");
        else plugin.getLogger().info("Wczytano "+result.size()+" pozycji nagród do skrzynki.");
        return result;
    }

    private String chanceForTier(String tier) {
        List<Reward> rewards = loadRewards();
        long total = rewards.stream().mapToLong(r -> r.weight).sum();
        if (total <= 0) return "0";
        long tierWeight = rewards.stream().filter(r -> r.tier.equalsIgnoreCase(tier))
                .mapToLong(r -> r.weight).sum();
        double percent = tierWeight * 100.0 / total;
        if (percent < 0.01) return String.format(Locale.US, "%.4f", percent);
        if (percent < 1.0) return String.format(Locale.US, "%.2f", percent);
        return String.format(Locale.US, "%.1f", percent);
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
        ChatColor tierColor = tierColor(reward.tier);
        player.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(ChatColor.GOLD + "★ " + tierColor + "WYLOSOWANO: " + reward.name);
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

    private void refund(UUID uuid, int amount) {
        if (db == null) return;
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO dq_wallet(uuid,stars) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET stars=stars+excluded.stars")) {
            st.setString(1, uuid.toString());
            st.setInt(2, amount);
            st.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się zwrócić gwiazdek: " + ex.getMessage());
        }
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

    private Object value(Map<?,?> map, String key, Object fallback) { return map.containsKey(key) ? map.get(key) : fallback; }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
