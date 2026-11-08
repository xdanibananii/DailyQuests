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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

public final class QuestNpcSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "✦ " + ChatColor.DARK_GREEN + "KSIĘGA KONTRAKTÓW" + ChatColor.DARK_GRAY + " ✦";
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
        YamlConfiguration current = YamlConfiguration.loadConfiguration(questsFile);
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("npc_quests.yml")), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().warning("Nie udało się wczytać domyślnych kontraktów: " + ex.getMessage());
            return;
        }
        ConfigurationSection defaultContracts = defaults.getConfigurationSection("contracts");
        if (defaultContracts == null) return;
        ConfigurationSection existing = current.getConfigurationSection("contracts");
        if (existing == null) existing = current.createSection("contracts");
        int added = 0;
        for (String id : defaultContracts.getKeys(false)) {
            if (existing.contains(id)) continue;
            ConfigurationSection contract = defaultContracts.getConfigurationSection(id);
            if (contract != null) {
                existing.set(id, contract.getValues(true));
                added++;
            }
        }
        if (added > 0) {
            try {
                current.save(questsFile);
                plugin.getLogger().info("Dodano " + added + " nowych kontraktów Michaiła Łomonosowa; własne wpisy zachowano.");
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Nie udało się zapisać nowych kontraktów: " + ex.getMessage());
            }
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
        reply.send(ChatColor.GREEN + "Michaił Łomonosow zajął już swoje miejsce.");
    }

    private void configure(Villager villager) {
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(false);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCollidable(false);
        villager.setProfession(Villager.Profession.CARTOGRAPHER);
        villager.setCustomName(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Michaił Łomonosow" + ChatColor.GOLD + " ✦");
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
            say(player, "Na dziś nie mam już wolnego zlecenia. Zajrzyj jutro — interesy nie uciekną.");
            return;
        }
        if (c.claimed) {
            say(player, "Widzę, że dzisiejszą sprawę mamy już załatwioną. Dobra robota. Jutro powinienem mieć coś nowego.");
        } else {
            say(player, "Dobrze, że jesteś. Mam dla ciebie robotę — konkretną, uczciwie płatną i bez zbędnych pytań.");
            say(player, c.description);
            say(player, "Jeśli podejmujesz się zlecenia, przynieś " + c.amount + " szt. " 
                    + pretty(Material.valueOf(c.material)) + ". Resztę zostaw mnie.");
        }
        open(player, c);
    }

    private void say(Player player, String message) {
        player.sendMessage(ChatColor.GOLD + "Michaił Łomonosow" + ChatColor.DARK_GRAY + " » "
                + ChatColor.YELLOW + message);
    }

    private void open(Player player, Contract c) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack green = item(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, dark);
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, green);
            inv.setItem(45 + i, green);
        }
        for (int row = 1; row < 5; row++) {
            inv.setItem(row * 9, green);
            inv.setItem(row * 9 + 8, green);
        }

        inv.setItem(4, item(Material.WRITABLE_BOOK, ChatColor.GOLD + "✦ KSIĘGA KONTRAKTÓW ✦",
                List.of(ChatColor.GRAY + "Zlecenia są proste: wykonujesz pracę,",
                        ChatColor.GRAY + "odbierasz zapłatę. Bez targowania.",
                        "", ChatColor.DARK_GREEN + "MISTRZ ZLECEŃ",
                        ChatColor.DARK_GRAY + "Jedno zlecenie na dzień.")));

        Material required = Material.valueOf(c.material);
        int owned = count(player, required);
        inv.setItem(20, item(required, ChatColor.YELLOW + "WYMAGANE TOWARY",
                List.of(ChatColor.GRAY + "Dostarcz mi:",
                        ChatColor.WHITE.toString() + c.amount + " × " + polishName(required),
                        "", ChatColor.GRAY + "W twoim ekwipunku: "
                                + (owned >= c.amount ? ChatColor.GREEN.toString() : ChatColor.RED.toString())
                                + Math.min(owned, c.amount) + ChatColor.GRAY + " / " + c.amount,
                        "", ChatColor.DARK_GRAY + "Przedmioty zostaną pobrane",
                        ChatColor.DARK_GRAY + "dopiero po zatwierdzeniu zlecenia.")));

        List<String> mapLore = new ArrayList<>();
        mapLore.add(ChatColor.GRAY + "ZLECENIE DNIA");
        mapLore.add("");
        mapLore.addAll(wrapLore(c.description, 38));
        mapLore.add("");
        mapLore.add(c.claimed ? ChatColor.GREEN + "✔ To zlecenie zostało już rozliczone."
                : ChatColor.YELLOW + "Czeka na twoją dostawę.");
        inv.setItem(22, item(Material.FILLED_MAP, ChatColor.GOLD + c.name, mapLore));

        inv.setItem(24, item(Material.CHEST, ChatColor.GOLD + "WYNAGRODZENIE",
                List.of(ChatColor.GRAY + "Za wykonanie zlecenia otrzymasz:",
                        "",
                        ChatColor.YELLOW + "★ " + c.stars + " gwiazdek",
                        ChatColor.GREEN + "+" + c.xp + " doświadczenia",
                        "",
                        ChatColor.DARK_GRAY + "Zapłata po oddaniu kompletu.")));

        int progress = Math.min(10, (int) Math.floor(10.0 * owned / Math.max(1, c.amount)));
        List<String> progressLore = new ArrayList<>();
        progressLore.add(ChatColor.GRAY + "Postęp dostawy: " + owned + " / " + c.amount);
        progressLore.add("");
        StringBuilder bar = new StringBuilder(ChatColor.DARK_GREEN.toString());
        for (int i = 0; i < 10; i++) bar.append(i < progress ? "■" : "□");
        progressLore.add(bar.toString());
        progressLore.add("");
        progressLore.add(c.claimed ? ChatColor.GREEN + "Zlecenie zakończone."
                : owned >= c.amount ? ChatColor.GREEN + "Masz wszystko. Możesz zdać zlecenie."
                : ChatColor.RED + "Brakuje ci jeszcze " + (c.amount - owned) + " szt.");
        inv.setItem(31, item(Material.BOOK, ChatColor.AQUA + "STATUS ZLECENIA", progressLore));

        if (c.claimed) {
            inv.setItem(40, item(Material.LIME_DYE, ChatColor.GREEN + "ZLECENIE ROZLICZONE",
                    List.of(ChatColor.GRAY + "Dziękuję za solidną robotę.",
                            ChatColor.GRAY + "Wróć jutro po kolejne.")));
        } else if (owned >= c.amount) {
            inv.setItem(40, item(Material.LIME_CONCRETE, ChatColor.GREEN + "ODDAJ TOWAR",
                    List.of(ChatColor.GRAY + "Masz wymagane przedmioty.",
                            ChatColor.YELLOW + "Kliknij, aby zakończyć zlecenie.",
                            "", ChatColor.DARK_GREEN + "Odbierz swoje wynagrodzenie.")));
        } else {
            inv.setItem(40, item(Material.GRAY_DYE, ChatColor.RED + "NIE MASZ JESZCZE KOMPLETU",
                    List.of(ChatColor.GRAY + "Wróć, gdy zbierzesz wszystkie",
                            ChatColor.GRAY + "wymagane przedmioty.",
                            "", ChatColor.DARK_GRAY + "Brakuje: " + (c.amount - owned))));
        }
        inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "ZAMKNIJ KSIĘGĘ",
                List.of(ChatColor.GRAY + "Wróć, kiedy będziesz gotów.")));
        player.openInventory(inv);
    }

    private String polishName(Material material) {
        return switch (material) {
            case LEATHER -> "skór";
            case ROTTEN_FLESH -> "szt. zgniłego mięsa";
            case IRON_INGOT -> "szt. sztabek żelaza";
            case COD -> "szt. surowego dorsza";
            case OAK_LOG -> "szt. dębowych bali";
            case STRING -> "szt. nici";
            case ENDER_PEARL -> "szt. pereł Endermana";
            case BONE -> "szt. kości";
            case GUNPOWDER -> "szt. prochu";
            case COPPER_INGOT -> "szt. sztabek miedzi";
            case WHEAT -> "szt. pszenicy";
            case GOLD_INGOT -> "szt. sztabek złota";
            default -> pretty(material);
        };
    }

    @EventHandler public void onContractClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 40) return;
        Contract c = getContract(player);
        if (c == null || c.claimed) { open(player, c == null ? emptyContract() : c); return; }
        Material required = Material.valueOf(c.material);
        int have = count(player, required);
        if (have < c.amount) {
            say(player, "Do kompletu brakuje ci jeszcze " + (c.amount - have) + " szt. " + polishName(required) + ". Wróć, gdy będziesz mieć całą dostawę.");
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
        say(player, "Dostawa się zgadza. Dobra robota — oto twoja zapłata. Jeśli będziesz szukać kolejnego zajęcia, wiesz, gdzie mnie znaleźć.");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        open(player, getContract(player));
    }

    private Contract emptyContract() {
        Contract c = new Contract();
        c.name = "Brak zlecenia"; c.description = "Wróć jutro."; c.material = Material.PAPER.name();
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

    /**
     * Minecraft nie zawija automatycznie długich linii lore w tooltipach.
     * Dzielimy opis na krótkie linie, aby podpowiedź nie rozciągała się przez ekran.
     */
    private List<String> wrapLore(String text, int maxLength) {
        String plain = ChatColor.stripColor(text == null ? "" : text).trim();
        List<String> lines = new ArrayList<>();
        if (plain.isEmpty()) return lines;

        StringBuilder line = new StringBuilder();
        for (String word : plain.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxLength) {
                lines.add(ChatColor.GRAY + line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(ChatColor.GRAY + line.toString());
        return lines;
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
