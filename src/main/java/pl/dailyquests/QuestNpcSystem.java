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
        Set<String> recentlyAssigned = new HashSet<>();
        try (PreparedStatement history = db.prepareStatement(
                "SELECT DISTINCT quest_id FROM npc_contracts WHERE uuid=? AND day>=date(?, '-30 days')")) {
            history.setString(1, player.getUniqueId().toString());
            history.setString(2, day);
            try (ResultSet rows = history.executeQuery()) {
                while (rows.next()) recentlyAssigned.add(rows.getString(1));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się odczytać historii kontraktów: " + ex.getMessage());
        }
        ids.removeIf(recentlyAssigned::contains);
        if (ids.isEmpty()) ids = new ArrayList<>(pool.getKeys(false));
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
        villager.setCanPickupItems(false);
        villager.setProfession(Villager.Profession.CARTOGRAPHER);
        villager.setCustomName(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Michaił Łomonosow" + ChatColor.GOLD + " ✦");
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
                    String line = AMBIENT_LINES[random.nextInt(AMBIENT_LINES.length)];
                    say(player, line);
                }
            }
            playersInNpcRange.retainAll(nearbyNow);
        }, 20L, 10L);
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
            Material required = Material.valueOf(c.material);
            player.sendMessage(Component.text(ChatColor.GOLD + "Michaił Łomonosow" + ChatColor.DARK_GRAY + " » "
                    + ChatColor.YELLOW + "Jeśli podejmujesz się zlecenia, przynieś " + c.amount + " × ")
                    .append(Component.translatable(required.getTranslationKey()))
                    .append(Component.text(ChatColor.YELLOW + ". Resztę zostaw mnie.")));
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
        ItemStack requiredItem = item(required, ChatColor.YELLOW + "WYMAGANE TOWARY", List.of());
        ItemMeta requiredMeta = requiredItem.getItemMeta();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        requiredMeta.lore(List.of(
                legacy.deserialize(ChatColor.GRAY + "Dostarcz mi:"),
                Component.text(ChatColor.WHITE + c.amount + " × ").append(Component.translatable(required.getTranslationKey())),
                Component.empty(),
                legacy.deserialize(ChatColor.GRAY + "W twoim ekwipunku: "
                        + (owned >= c.amount ? ChatColor.GREEN.toString() : ChatColor.RED.toString())
                        + Math.min(owned, c.amount) + ChatColor.GRAY + " / " + c.amount),
                Component.empty(),
                legacy.deserialize(ChatColor.DARK_GRAY + "Przedmioty zostaną pobrane"),
                legacy.deserialize(ChatColor.DARK_GRAY + "dopiero po zatwierdzeniu zlecenia.")
        ));
        requiredItem.setItemMeta(requiredMeta);
        inv.setItem(20, requiredItem);

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

    @EventHandler public void onContractClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 40) return;
        Contract c = getContract(player);
        if (c == null || c.claimed) { open(player, c == null ? emptyContract() : c); return; }
        Material required = Material.valueOf(c.material);
        int have = count(player, required);
        if (have < c.amount) {
            player.sendMessage(Component.text(ChatColor.GOLD + "Michaił Łomonosow" + ChatColor.DARK_GRAY + " » "
                    + ChatColor.YELLOW + "Do kompletu brakuje ci jeszcze " + (c.amount - have) + " × ")
                    .append(Component.translatable(required.getTranslationKey()))
                    .append(Component.text(ChatColor.YELLOW + ". Wróć, gdy będziesz mieć całą dostawę.")));
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
        if (!event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player) {
            long now=System.currentTimeMillis();
            if(now-lastWarning.getOrDefault(player.getUniqueId(),0L)>=2500L) {
                lastWarning.put(player.getUniqueId(),now);
                String[] lines={"Ejże, młodzieńcze! Rąk na starszych się nie podnosi. Okaż nieco szacunku.","Spokojnie, mój drogi. Mądrości nie wykuwa się pięścią.","Za moich czasów uczono, że starszyzna zasługuje na szacunek.","Ho, ho! Jeśli masz tyle energii, spożytkuj ją na porządne zadanie, nie na mnie."};
                say(player,lines[random.nextInt(lines.length)]);
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
