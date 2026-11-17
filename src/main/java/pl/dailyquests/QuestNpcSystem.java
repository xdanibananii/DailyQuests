package pl.dailyquests;

import org.bukkit.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
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
    private final Set<UUID> playersInNpcRange = new HashSet<>();
    private final Map<UUID, Long> lastAmbientMessage = new HashMap<>();
    private static final long AMBIENT_COOLDOWN_MS = 20_000L;
    private final Map<UUID, Long> lastWarning = new HashMap<>();
    private static final String[] AMBIENT_LINES = {
            "Ach, wędrowcze... każda wielka przygoda zaczyna się od małego zlecenia.",
            "Pamiętaj, młody człowieku: cierpliwość jest cenniejsza niż diamenty.",
            "Mam przeczucie, że los szykuje dla ciebie interesujące zadanie.",
            "Przez te wszystkie lata nauczyłem się jednego: zawsze warto mieć fach w ręku.",
            "Szukasz zajęcia? Być może znajdzie się coś, co będzie wymagało twoich umiejętności.",
            "Nie lekceważ drobnych przysług. Z nich rodzą się wielkie historie.",
            "Świat pełen jest skarbów, ale prawdziwą wartość ma dobrze wykonana praca.",
            "Ho, ho... mam kilka spraw do załatwienia. Zajrzyj do mnie, gdy będziesz gotów.",
            "Wędrowałem po wielu krainach i powiadam ci: każda z nich potrzebuje pomocnych dłoni.",
            "Jeśli szukasz uczciwej zapłaty, być może trafiłeś pod właściwy adres.",
            "Dawniej sam przemierzałem te szlaki. Dziś wolę powierzać zadania młodszym.",
            "Roztropny podróżnik nigdy nie odrzuca okazji do zdobycia doświadczenia."
    };

    private static final class Contract {
        String id, name, description, material, dimension;
        int amount, stars, xp, delivered, slot;
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
            boolean hasSlot = false;
            try (ResultSet columns = st.executeQuery("PRAGMA table_info(npc_contracts)")) {
                while (columns.next()) if ("slot".equalsIgnoreCase(columns.getString("name"))) hasSlot = true;
            }
            if (!hasSlot) {
                st.executeUpdate("ALTER TABLE npc_contracts RENAME TO npc_contracts_legacy");
                st.executeUpdate("CREATE TABLE npc_contracts (uuid TEXT NOT NULL, day TEXT NOT NULL, slot INTEGER NOT NULL, quest_id TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,day,slot))");
                st.executeUpdate("INSERT INTO npc_contracts(uuid,day,slot,quest_id,claimed) SELECT uuid,day,0,quest_id,claimed FROM npc_contracts_legacy");
                st.executeUpdate("DROP TABLE npc_contracts_legacy");
                plugin.getLogger().info("Zmigrowano kontrakty Michaiła do systemu trzech dziennych zleceń.");
            }
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS npc_contracts_unique_quest ON npc_contracts(uuid,day,quest_id)");
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie mozna utworzyc tabeli kontraktow NPC: " + ex.getMessage());
        }
        loadDefaultPool();
        restoreNpc();
        startAmbientTask();
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
            configure(villager, yaml.getString("name", "Michaił Łomonosow"));
            return;
        }
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        configure(villager, yaml.getString("name", "Michaił Łomonosow"));
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
        int metadataUpdated = 0;
        for (String id : defaultContracts.getKeys(false)) {
            ConfigurationSection contract = defaultContracts.getConfigurationSection(id);
            if (contract == null) continue;
            if (!existing.contains(id)) {
                existing.set(id, contract.getValues(true));
                added++;
            } else if (contract.contains("dimension") && !existing.isSet(id + ".dimension")) {
                existing.set(id + ".dimension", contract.getString("dimension"));
                metadataUpdated++;
            }
        }
        // Ender pearls are obtainable in the Overworld; older pool versions
        // incorrectly restricted this contract to an enabled End dimension.
        if (existing.contains("ender_pearls_for_portal.dimension")
                && "END".equalsIgnoreCase(existing.getString("ender_pearls_for_portal.dimension", ""))) {
            existing.set("ender_pearls_for_portal.dimension", "OVERWORLD");
            metadataUpdated++;
        }
        // Bukkit/Paper exposes the item as TURTLE_SCUTE; migrate older SCUTE configs.
        for (String id : existing.getKeys(false)) {
            String item = existing.getString(id + ".item", "");
            if ("SCUTE".equalsIgnoreCase(item)) {
                existing.set(id + ".item", "TURTLE_SCUTE");
                metadataUpdated++;
            }
        }
        if (added > 0 || metadataUpdated > 0) {
            try {
                current.save(questsFile);
                plugin.getLogger().info("Zaktualizowano pulę kontraktów Michaiła: dodano " + added + ", uzupełniono metadane " + metadataUpdated + "; własne wpisy zachowano.");
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
            String materialName = section.getString("item", "LEATHER").toUpperCase(Locale.ROOT);
            if ("SCUTE".equals(materialName)) materialName = "TURTLE_SCUTE";
            c.material = Material.valueOf(materialName).name();
            c.dimension = section.getString("dimension", "OVERWORLD").toUpperCase(Locale.ROOT);
            c.amount = Math.max(1, section.getInt("amount", 1));
            c.stars = Math.max(0, section.getInt("reward-stars", 0));
            c.xp = Math.max(0, section.getInt("reward-xp", 0));
            return c;
        } catch (Exception ex) {
            plugin.getLogger().warning("Nieprawidlowy kontrakt NPC '" + id + "': " + ex.getMessage());
            return null;
        }
    }

    private List<Contract> getContracts(Player player) {
        String day = today();
        UUID uuid = player.getUniqueId();
        Map<Integer, Contract> assigned = new TreeMap<>();
        List<Integer> invalidSlots = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT slot,quest_id,claimed FROM npc_contracts WHERE uuid=? AND day=? ORDER BY slot")) {
            query.setString(1, uuid.toString());
            query.setString(2, day);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int slot = rows.getInt(1);
                    Contract contract = readContract(rows.getString(2));
                    if (contract != null && plugin.isDimensionEnabled(contract.dimension)) {
                        contract.slot = slot;
                        contract.claimed = rows.getInt(3) == 1;
                        assigned.put(slot, contract);
                    } else invalidSlots.add(slot);
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Błąd odczytu kontraktów NPC: " + ex.getMessage());
            return new ArrayList<>();
        }

        for (int invalidSlot : invalidSlots) {
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM npc_contracts WHERE uuid=? AND day=? AND slot=?")) {
                delete.setString(1, uuid.toString()); delete.setString(2, day); delete.setInt(3, invalidSlot); delete.executeUpdate();
            } catch (SQLException ex) { plugin.getLogger().warning("Nie udało się usunąć wyłączonego kontraktu: " + ex.getMessage()); }
        }
        ConfigurationSection pool = quests().getConfigurationSection("contracts");
        if (pool == null || pool.getKeys(false).isEmpty()) return new ArrayList<>(assigned.values());

        Set<String> assignedIds = new HashSet<>();
        assigned.values().forEach(contract -> assignedIds.add(contract.id));
        Set<String> recentlyAssigned = new HashSet<>();
        try (PreparedStatement history = db.prepareStatement(
                "SELECT DISTINCT quest_id FROM npc_contracts WHERE uuid=? AND day>=date(?, '-30 days')")) {
            history.setString(1, uuid.toString());
            history.setString(2, day);
            try (ResultSet rows = history.executeQuery()) {
                while (rows.next()) recentlyAssigned.add(rows.getString(1));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się odczytać historii kontraktów: " + ex.getMessage());
        }

        List<String> candidates = new ArrayList<>(pool.getKeys(false));
        candidates.removeIf(id -> {
            Contract contract = readContract(id);
            return contract == null || !plugin.isDimensionEnabled(contract.dimension)
                    || assignedIds.contains(id) || recentlyAssigned.contains(id);
        });
        Collections.shuffle(candidates);
        if (candidates.size() < 3 - assigned.size()) {
            candidates = new ArrayList<>(pool.getKeys(false));
            candidates.removeIf(id -> {
                Contract contract = readContract(id);
                return contract == null || !plugin.isDimensionEnabled(contract.dimension) || assignedIds.contains(id);
            });
            Collections.shuffle(candidates);
        }

        for (int slot = 0; slot < 3; slot++) {
            if (assigned.containsKey(slot) || candidates.isEmpty()) continue;
            String id = candidates.remove(0);
            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT OR IGNORE INTO npc_contracts(uuid,day,slot,quest_id,claimed) VALUES(?,?,?, ?,0)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, day);
                insert.setInt(3, slot);
                insert.setString(4, id);
                if (insert.executeUpdate() == 1) {
                    Contract contract = readContract(id);
                    if (contract != null) {
                        contract.slot = slot;
                        assigned.put(slot, contract);
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().warning("Błąd przydzielania kontraktu NPC: " + ex.getMessage());
            }
        }
        return new ArrayList<>(assigned.values());
    }

    private Contract contractAt(List<Contract> contracts, int slot) {
        return contracts.stream().filter(contract -> contract.slot == slot).findFirst().orElse(null);
    }

    void spawnAt(Location location, CommandSenderAdapter reply) {
        if (location.getWorld() == null) return;
        removeNpc();
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        String npcName = yaml.getString("name", "Michaił Łomonosow");
        configure(villager, npcName);
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX());
        yaml.set("y", location.getY());
        yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw());
        yaml.set("pitch", location.getPitch());
        yaml.set("uuid", villager.getUniqueId().toString());
        yaml.set("name", npcName);
        saveNpc(yaml);
        reply.send(ChatColor.GREEN + "Michaił Łomonosow zajął już swoje miejsce.");
    }

    private void configure(Villager villager, String name) {
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(false);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCollidable(false);
        villager.setCanPickupItems(false);
        villager.setProfession(Villager.Profession.CARTOGRAPHER);
        villager.setCustomName(ChatColor.GOLD + "✦ " + color(name) + ChatColor.GOLD + " ✦");
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Michaił pozostaje dokładnie w miejscu, obraca się ku graczom
     * i od czasu do czasu zagaduje każdego w promieniu pięciu bloków.
     */
    private void startAmbientTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!npcFile.exists()) return;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
            String savedUuid = yaml.getString("uuid", "");
            if (savedUuid.isBlank()) return;

            Entity entity;
            try {
                entity = Bukkit.getEntity(UUID.fromString(savedUuid));
            } catch (IllegalArgumentException ex) {
                return;
            }
            if (!(entity instanceof Villager villager) || !villager.isValid()) return;

            World savedWorld = Bukkit.getWorld(yaml.getString("world", ""));
            if (savedWorld == null || villager.getWorld() != savedWorld) return;

            Location anchor = new Location(savedWorld, yaml.getDouble("x"), yaml.getDouble("y"),
                    yaml.getDouble("z"), (float) yaml.getDouble("yaw"), 0.0f);

            // AI jest wyłączone, a dodatkowo kasujemy pęd i cofamy każde przesunięcie.
            villager.setAI(false);
            villager.setVelocity(new Vector(0, 0, 0));
            if (villager.getLocation().distanceSquared(anchor) > 0.0025) {
                villager.teleport(anchor);
            }

            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Player player : savedWorld.getPlayers()) {
                double distance = player.getLocation().distanceSquared(villager.getLocation());
                if (distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }

            if (nearest != null) {
                Location npcLocation = villager.getLocation();
                Location playerLocation = nearest.getLocation();
                double dx = playerLocation.getX() - npcLocation.getX();
                double dz = playerLocation.getZ() - npcLocation.getZ();
                if (dx * dx + dz * dz > 0.0001) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    villager.setRotation(yaw, 0.0f);
                }
            }

            // Dialog uruchamia się przy każdym ponownym wejściu w promień 5 bloków.
            // Gdy gracz wyjdzie poza zasięg, usuwamy go ze zbioru; kolejne podejście
            // wywoła następną, losową kwestię. Bez jednorazowego cooldownu.
            Set<UUID> nearbyNow = new HashSet<>();
            for (Player player : savedWorld.getPlayers()) {
                UUID playerId = player.getUniqueId();
                if (player.getLocation().distanceSquared(villager.getLocation()) > 25.0) continue;

                nearbyNow.add(playerId);
                if (playersInNpcRange.add(playerId)) {
                    long now = System.currentTimeMillis();
                    if (now - lastAmbientMessage.getOrDefault(playerId, 0L) >= AMBIENT_COOLDOWN_MS) {
                        lastAmbientMessage.put(playerId, now);
                        String line = AMBIENT_LINES[random.nextInt(AMBIENT_LINES.length)];
                        say(player, line);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.55f, 1.35f);
                    }
                }
            }
            playersInNpcRange.retainAll(nearbyNow);
        }, 20L, 10L);
    }

    void renameNpc(String name, java.util.function.Consumer<String> reply) {
        if (!npcFile.exists()) { reply.accept(ChatColor.RED + "Michaił nie został jeszcze postawiony."); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        Entity entity = null;
        try { String id = yaml.getString("uuid", ""); if (!id.isBlank()) entity = Bukkit.getEntity(UUID.fromString(id)); }
        catch (IllegalArgumentException ignored) {}
        if (!(entity instanceof Villager villager) || !villager.isValid()) {
            reply.accept(ChatColor.RED + "Nie znaleziono Michaiła. Spróbuj ponownie po jego odtworzeniu."); return;
        }
        yaml.set("name", name); saveNpc(yaml);
        villager.setCustomName(ChatColor.GOLD + "✦ " + color(name) + ChatColor.GOLD + " ✦");
        villager.setCustomNameVisible(true);
        reply.accept(ChatColor.GREEN + "Zmieniono nazwę NPC na: " + color(name));
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

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        playersInNpcRange.remove(event.getPlayer().getUniqueId());
        lastAmbientMessage.remove(event.getPlayer().getUniqueId());
        lastWarning.remove(event.getPlayer().getUniqueId());
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
        List<Contract> contracts = getContracts(player);
        if (contracts.isEmpty()) { say(player, "Na dziś nie mam wolnych zleceń. Zajrzyj jutro."); return; }
        say(player, "Mam dla ciebie trzy kontrakty. Wybierz te, które najbardziej ci odpowiadają.");
        open(player, contracts);
    }
    private void say(Player player, String message) {
        player.sendMessage(ChatColor.GOLD + "Michaił Łomonosow" + ChatColor.DARK_GRAY + " » "
                + ChatColor.YELLOW + message);
    }

    private void open(Player player, List<Contract> contracts) {
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack green = item(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < 54; slot++) inventory.setItem(slot, dark);
        for (int slot = 0; slot < 9; slot++) { inventory.setItem(slot, green); inventory.setItem(45 + slot, green); }
        for (int row = 1; row < 5; row++) { inventory.setItem(row * 9, green); inventory.setItem(row * 9 + 8, green); }
        inventory.setItem(4, item(Material.WRITABLE_BOOK, ChatColor.GOLD + "✦ KSIĘGA KONTRAKTÓW ✦",
                List.of(ChatColor.GRAY + "Michaił przygotował trzy zlecenia.",
                        ChatColor.GRAY + "Każde rozliczasz i odbierasz osobno.",
                        "", ChatColor.DARK_GREEN + "MISTRZ ZLECEŃ", ChatColor.DARK_GRAY + "3 kontrakty dziennie.")));
        int[] infoSlots = {20, 22, 24}, deliverSlots = {29, 31, 33}, rerollSlots = {38, 40, 42};
        for (int index = 0; index < 3; index++) {
            Contract c = contractAt(contracts, index);
            if (c == null) continue;
            Material required = Material.matchMaterial(c.material);
            if (required == null) required = Material.PAPER;
            int owned = count(player, required);
            List<String> lore = new ArrayList<>(wrapLore(c.description, 34));
            lore.add(""); lore.add(ChatColor.GRAY + "Wymagane: " + ChatColor.WHITE + c.amount + " × " + pretty(required));
            lore.add(ChatColor.GRAY + "Masz: " + (owned >= c.amount ? ChatColor.GREEN : ChatColor.RED) + Math.min(owned, c.amount) + ChatColor.GRAY + " / " + c.amount);
            lore.add(""); lore.add(ChatColor.GOLD + "Nagroda: " + c.stars + " ★"); lore.add(ChatColor.GREEN + "+" + c.xp + " XP");
            lore.add(""); lore.add(c.claimed ? ChatColor.GREEN + "✔ Kontrakt rozliczony" : ChatColor.YELLOW + "Dostawa do rozliczenia");
            inventory.setItem(infoSlots[index], item(required, ChatColor.GOLD + String.valueOf(index + 1) + ". " + c.name, lore));
            if (c.claimed) {
                inventory.setItem(deliverSlots[index], item(Material.LIME_DYE, ChatColor.GREEN + "KONTRAKT ROZLICZONY", List.of(ChatColor.GRAY + "To zlecenie zostało już wykonane.")));
                inventory.setItem(rerollSlots[index], item(Material.GRAY_DYE, ChatColor.DARK_GRAY + "KONTRAKT ZAKOŃCZONY", List.of(ChatColor.GRAY + "Nie można go już zmienić.")));
            } else {
                inventory.setItem(deliverSlots[index], item(owned >= c.amount ? Material.LIME_CONCRETE : Material.GRAY_DYE,
                        owned >= c.amount ? ChatColor.GREEN + "ODDAJ TOWAR" : ChatColor.RED + "BRAKUJE TOWARU",
                        List.of(ChatColor.GRAY + "Wymagane: " + c.amount + " × " + pretty(required), ChatColor.GRAY + "W ekwipunku: " + owned, "",
                                owned >= c.amount ? ChatColor.YELLOW + "Kliknij, aby odebrać zapłatę." : ChatColor.DARK_GRAY + "Wróć po zebraniu całej dostawy.")));
                inventory.setItem(rerollSlots[index], item(Material.EXPERIENCE_BOTTLE, ChatColor.LIGHT_PURPLE + "PRZELOSUJ KONTRAKT",
                        List.of(ChatColor.GRAY + "Wylosuj inne zlecenie w tym miejscu.", "", ChatColor.YELLOW + "Koszt: 10 poziomów Minecraft")));
            }
        }
        inventory.setItem(49, item(Material.BARRIER, ChatColor.RED + "ZAMKNIJ KSIĘGĘ", List.of(ChatColor.GRAY + "Wróć, kiedy będziesz gotów.")));
        player.openInventory(inventory);
    }

    @EventHandler public void onContractClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int clicked = event.getRawSlot();
        if (clicked == 49) { player.closeInventory(); return; }
        int slot = switch (clicked) { case 29, 38 -> 0; case 31, 40 -> 1; case 33, 42 -> 2; default -> -1; };
        if (slot < 0) return;
        if (clicked == 29 || clicked == 31 || clicked == 33) deliverContract(player, slot);
        else rerollContract(player, slot);
    }

    private void deliverContract(Player player, int slot) {
        List<Contract> contracts = getContracts(player);
        Contract c = contractAt(contracts, slot);
        if (c == null || c.claimed) { open(player, contracts); return; }
        Material required = Material.matchMaterial(c.material);
        if (required == null) return;
        int have = count(player, required);
        if (have < c.amount) { player.sendMessage(ChatColor.YELLOW + "Brakuje ci jeszcze " + (c.amount - have) + " × " + pretty(required) + "."); open(player, contracts); return; }
        remove(player, required, c.amount);
        try (PreparedStatement update = db.prepareStatement("UPDATE npc_contracts SET claimed=1 WHERE uuid=? AND day=? AND slot=? AND claimed=0")) {
            update.setString(1, player.getUniqueId().toString()); update.setString(2, today()); update.setInt(3, slot);
            if (update.executeUpdate() != 1) { player.getInventory().addItem(new ItemStack(required, c.amount)); player.sendMessage(ChatColor.RED + "Nie udało się zatwierdzić kontraktu. Przedmioty zwrócono."); return; }
        } catch (SQLException ex) { player.getInventory().addItem(new ItemStack(required, c.amount)); plugin.getLogger().warning("Błąd zatwierdzania kontraktu NPC: " + ex.getMessage()); player.sendMessage(ChatColor.RED + "Wystąpił błąd. Przedmioty zwrócono."); return; }
        if (c.stars > 0) crates.awardStars(player, c.stars); if (c.xp > 0) player.giveExp(c.xp);
        say(player, "Dostawa się zgadza. Dobra robota — oto twoja zapłata.");
        open(player, getContracts(player));
    }

    private void rerollContract(Player player, int slot) {
        List<Contract> currentContracts = getContracts(player);
        Contract current = contractAt(currentContracts, slot);
        if (current == null || current.claimed) { say(player, "Tego kontraktu nie można już zmienić."); open(player, currentContracts); return; }
        ConfigurationSection pool = quests().getConfigurationSection("contracts"); if (pool == null) return;
        Set<String> used = new HashSet<>(); for (Contract c : currentContracts) if (c.slot != slot) used.add(c.id);
        Set<String> recent = new HashSet<>();
        try (PreparedStatement history = db.prepareStatement("SELECT DISTINCT quest_id FROM npc_contracts WHERE uuid=? AND day>=date(?, '-30 days')")) {
            history.setString(1, player.getUniqueId().toString()); history.setString(2, today());
            try (ResultSet rows = history.executeQuery()) { while (rows.next()) recent.add(rows.getString(1)); }
        } catch (SQLException ex) { plugin.getLogger().warning("Błąd odczytu historii kontraktów: " + ex.getMessage()); }
        List<String> candidates = new ArrayList<>(pool.getKeys(false));
        candidates.removeIf(id -> { Contract c = readContract(id); return c == null || !plugin.isDimensionEnabled(c.dimension) || used.contains(id) || id.equals(current.id) || recent.contains(id); });
        if (candidates.isEmpty()) { candidates = new ArrayList<>(pool.getKeys(false)); candidates.removeIf(id -> { Contract c = readContract(id); return c == null || !plugin.isDimensionEnabled(c.dimension) || used.contains(id) || id.equals(current.id); }); }
        if (candidates.isEmpty()) { say(player, "Nie mam teraz innego zlecenia."); open(player, currentContracts); return; }
        if (player.getLevel() < 10) { say(player, "Do ponownego losowania potrzebujesz 10 poziomów doświadczenia."); open(player, currentContracts); return; }
        Collections.shuffle(candidates);
        try (PreparedStatement update = db.prepareStatement("UPDATE npc_contracts SET quest_id=?,claimed=0 WHERE uuid=? AND day=? AND slot=? AND claimed=0")) {
            update.setString(1, candidates.get(0)); update.setString(2, player.getUniqueId().toString()); update.setString(3, today()); update.setInt(4, slot);
            if (update.executeUpdate() != 1) { say(player, "Nie udało się zmienić kontraktu."); open(player, getContracts(player)); return; }
            player.setLevel(player.getLevel() - 10); player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.2f);
            say(player, "Mam dla ciebie coś innego. Nowy kontrakt kosztował 10 poziomów doświadczenia."); open(player, getContracts(player));
        } catch (SQLException ex) { plugin.getLogger().warning("Nie udało się przerzucić kontraktu NPC: " + ex.getMessage()); say(player, "Coś poszło nie tak. Spróbuj ponownie."); open(player, currentContracts); }
    }
    @EventHandler public void onContractDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    @EventHandler public void onNpcDamage(EntityDamageEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player) {
            long now=System.currentTimeMillis();
            if(now-lastWarning.getOrDefault(player.getUniqueId(),0L)>=2500L) {
                lastWarning.put(player.getUniqueId(),now);
                String[] lines={"Ejże, młodzieńcze! Rąk na starszych się nie podnosi. Okaż nieco szacunku.","Spokojnie, mój drogi. Mądrości nie wykuwa się pięścią.","Za moich czasów uczono, że starszyzna zasługuje na szacunek.","Ho, ho! Jeśli masz tyle energii, spożytkuj ją na porządne zadanie, nie na mnie."};
                say(player,lines[random.nextInt(lines.length)]);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.65f, 0.85f);
            }
        }
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
