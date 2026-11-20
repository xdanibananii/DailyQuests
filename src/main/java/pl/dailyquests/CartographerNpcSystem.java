package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class CartographerNpcSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "✦ KRZUSŁAW WĘDROWNY — KRAM KARTOGRAFA ✦";
    private static final int[] SHOP_SLOTS = {19, 21, 23, 28, 30, 32};
    private final DailyQuestsPlugin plugin;
    private final CrateSystem crates;
    private final Connection db;
    private final NamespacedKey npcKey, mapIdKey, chestIdKey, chestOwnerKey;
    private final File npcFile;
    private final Random random = new Random();
    private final Set<UUID> nearbyPlayers = new HashSet<>();
    private final Map<UUID, Long> lastAmbient = new HashMap<>();
    private final Map<UUID, Long> lastWarning = new HashMap<>();
    private final Set<String> greetedTreasures = new HashSet<>();

    private static final class TreasureMarkerRenderer extends MapRenderer {
        private final Set<UUID> renderedPlayers = new HashSet<>();
        @Override public void render(MapView map, org.bukkit.map.MapCanvas canvas, Player viewer) {
            if (renderedPlayers.add(viewer.getUniqueId())) {
                canvas.getCursors().addCursor(0, 0, (byte) 8, MapCursor.Type.RED_MARKER);
            }
        }
    }

    private record MapOffer(String id, String name, Material icon, Material currency,
                            int itemCost, int starCost, String tier, String description) { }

    private static final List<MapOffer> OFFERS = List.of(
            new MapOffer("wanderer", "Mapa włóczęgi", Material.MAP, Material.IRON_INGOT, 32, 0, "COMMON",
                    "Pierwszy krok ku przygodzie. Podobno nawet zwykłe wzgórza skrywają sekrety."),
            new MapOffer("seeker", "Mapa poszukiwacza", Material.FILLED_MAP, Material.GOLD_INGOT, 16, 0, "UNCOMMON",
                    "Prowadzi do miejsca, w którym można znaleźć coś więcej niż stare kości."),
            new MapOffer("explorer", "Mapa odkrywcy", Material.COMPASS, Material.EMERALD, 12, 0, "RARE",
                    "Dla tych, którzy nie boją się zejść z utartego szlaku."),
            new MapOffer("forgotten", "Mapa zapomniana", Material.PAPER, null, 0, 5, "EPIC",
                    "Stare pergaminy wspominają o skarbach, o których świat dawno zapomniał."),
            new MapOffer("ancient", "Mapa pradawna", Material.MAP, null, 0, 10, "EPIC",
                    "Jej znaki pochodzą z czasów, gdy królestwa dopiero powstawały."),
            new MapOffer("legendary", "Mapa legendarna", Material.FILLED_MAP, null, 0, 20, "LEGENDARY",
                    "Niewielu widziało taką mapę. Jeszcze mniej wróciło z jej wyprawy.")
    );

    private static final String[] AMBIENT_LINES = {
            "Nie szukaj drogi na skróty, wędrowcze. Najpiękniejsze skarby leżą na końcu najdłuższych szlaków.",
            "Mam tu mapy starsze niż niejedno królestwo. Niektóre nawet wskazują prawdziwe miejsca!",
            "Słyszałem o skrzyni ukrytej pod warstwą ziemi. Albo to była moja własna spiżarnia...",
            "Kompas nie kłamie. Co najwyżej czasem nie mówi całej prawdy.",
            "Widziałem ruiny, których nie ma na żadnej mapie. I mapy, które prowadzą do ruin, których nie ma.",
            "Jeśli usłyszysz szepty spod ziemi, to pewnie wiatr. A jeśli nie — kop głębiej!",
            "Najlepszy kartograf zna nie tylko drogę do skarbu, ale i drogę powrotną.",
            "Mam przeczucie, że dziś ktoś odkryje coś, o czym bardowie będą śpiewać przez lata.",
            "Nie każda plama na pergaminie to atrament. Czasem to ślad po bardzo starym obiedzie.",
            "Wyruszaj śmiało! Tylko nie zapomnij kilofa. Mapy samej skrzyni nie wykopią."
    };

    private static final String[] GREETINGS = {
            "A witaj, podróżniku! Szukasz drogi, czy może czegoś, co warto przy tej drodze znaleźć?",
            "Krzusław Wędrowny, kartograf do usług. Mam mapy, których nie powstydziłby się sam królewski odkrywca.",
            "Podejdź bliżej! Każda z tych map prowadzi do prawdziwego skarbu. O ile dobrze pamiętam...",
            "Oho, nowa twarz! Wyglądasz na kogoś, kto potrafi odróżnić północ od własnego nosa.",
            "Witaj! Mam coś dla poszukiwaczy przygód, kolekcjonerów i tych, którym zwyczajnie brakuje żelaza."
    };

    private static final String[] PURCHASE_LINES = {
            "Doskonały wybór! Niech mapa prowadzi cię ku chwale i bogactwu.",
            "Umowa stoi! Trzymaj pergamin i nie zgub go po drodze.",
            "Ha! Czuję, że ta wyprawa zapisze się w kronikach.",
            "Ostrożnie z tą mapą. Papier stary, ale wskazówki wciąż aktualne.",
            "Niech ci sprzyjają gwiazdy, wędrowcze. I obyś zabrał wystarczająco dużo kilofów."
    };

    CartographerNpcSystem(DailyQuestsPlugin plugin, CrateSystem crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.db = plugin.database();
        this.npcKey = new NamespacedKey(plugin, "cartographer_npc");
        this.mapIdKey = new NamespacedKey(plugin, "dq_treasure_map");
        this.chestIdKey = new NamespacedKey(plugin, "dq_treasure_id");
        this.chestOwnerKey = new NamespacedKey(plugin, "dq_treasure_owner");
        this.npcFile = new File(plugin.getDataFolder(), "cartographer_npc.yml");
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dq_treasure_maps (map_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, tier TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, map_view_id INTEGER NOT NULL DEFAULT -1)");
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie można utworzyć tabeli map skarbów: " + ex.getMessage());
        }
        try (Statement migration = db.createStatement()) {
            migration.executeUpdate("ALTER TABLE dq_treasure_maps ADD COLUMN map_view_id INTEGER NOT NULL DEFAULT -1");
        } catch (SQLException ignored) { }
        restoreMapRenderers();
        restoreNpc();
        startAmbientTask();
    }

    void spawnAt(Location location, java.util.function.Consumer<String> reply) {
        if (location.getWorld() == null) return;
        removeNpc();
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        configure(villager, "Krzusław Wędrowny");
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("name", "Krzusław Wędrowny");
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX()); yaml.set("y", location.getY()); yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw()); yaml.set("pitch", location.getPitch());
        yaml.set("uuid", villager.getUniqueId().toString());
        saveNpc(yaml);
        reply.accept(ChatColor.GREEN + "Krzusław Wędrowny otworzył swój kram kartograficzny.");
    }

    private void restoreNpc() {
        if (!npcFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(npcFile);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null || !yaml.contains("x")) return;
        Location location = npcLocation(yaml, world);
        world.getChunkAt(location).load();
        Entity existing = null;
        try {
            String uuid = yaml.getString("uuid", "");
            if (!uuid.isBlank()) existing = Bukkit.getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) { }
        if (existing instanceof Villager villager && villager.isValid()) {
            configure(villager, yaml.getString("name", "Krzusław Wędrowny"));
            return;
        }
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        configure(villager, yaml.getString("name", "Krzusław Wędrowny"));
        yaml.set("uuid", villager.getUniqueId().toString());
        saveNpc(yaml);
    }

    private void configure(Villager villager, String name) {
        if (villager.isInsideVehicle()) villager.leaveVehicle();
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCollidable(false);
        villager.setCanPickupItems(false);
        villager.setProfession(Villager.Profession.CARTOGRAPHER);
        villager.setCustomName(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + color(name) + ChatColor.GOLD + " ✦");
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void startAmbientTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!npcFile.exists()) { nearbyPlayers.clear(); return; }
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(npcFile);
            World world = Bukkit.getWorld(yaml.getString("world", ""));
            if (world == null) { nearbyPlayers.clear(); return; }
            Entity entity;
            try { entity = Bukkit.getEntity(UUID.fromString(yaml.getString("uuid", ""))); }
            catch (Exception ex) { nearbyPlayers.clear(); return; }
            if (!(entity instanceof Villager villager) || !villager.isValid()) return;
            Location anchor = npcLocation(yaml, world);
            villager.setAI(false);
            villager.setVelocity(new Vector(0, 0, 0));
            if (villager.getLocation().distanceSquared(anchor) > 0.0025) villager.teleport(anchor);
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Player player : world.getPlayers()) {
                double distance = player.getLocation().distanceSquared(villager.getLocation());
                if (distance < nearestDistance) { nearest = player; nearestDistance = distance; }
            }
            if (nearest != null) {
                Location p = nearest.getLocation(), n = villager.getLocation();
                double dx = p.getX() - n.getX(), dz = p.getZ() - n.getZ();
                if (dx * dx + dz * dz > 0.0001)
                    villager.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0.0f);
            }
            Set<UUID> current = new HashSet<>();
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(villager.getLocation()) > 25.0) continue;
                UUID id = player.getUniqueId();
                current.add(id);
                if (nearbyPlayers.add(id)) {
                    long now = System.currentTimeMillis();
                    if (now - lastAmbient.getOrDefault(id, 0L) >= 20_000L) {
                        lastAmbient.put(id, now);
                        say(player, AMBIENT_LINES[random.nextInt(AMBIENT_LINES.length)]);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.55f, 1.25f);
                    }
                }
            }
            nearbyPlayers.retainAll(current);
        }, 20L, 10L);
    }

    private void say(Player player, String message) {
        player.sendMessage(ChatColor.GOLD + "Krzusław Wędrowny" + ChatColor.DARK_GRAY + " » "
                + ChatColor.YELLOW + message);
    }

    @EventHandler public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8f, 1.1f);
        say(player, GREETINGS[random.nextInt(GREETINGS.length)]);
        openShop(player);
    }

    private void openShop(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack accent = item(Material.YELLOW_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, dark);
        for (int i = 0; i < 9; i++) { inv.setItem(i, accent); inv.setItem(45 + i, accent); }
        for (int row = 1; row < 5; row++) {
            inv.setItem(row * 9, accent);
            inv.setItem(row * 9 + 8, accent);
        }
        inv.setItem(4, item(Material.WRITABLE_BOOK, ChatColor.GOLD + "✦ KRAM KRZUSŁAWA ✦",
                List.of(ChatColor.GRAY + "Wybierz mapę i wyrusz na poszukiwanie skarbu.",
                        ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━",
                        ChatColor.YELLOW + "Twoje gwiazdki: " + crates.getStars(player.getUniqueId()) + " ★",
                        ChatColor.GRAY + "Każda mapa prowadzi do osobistej skrzyni.",
                        ChatColor.DARK_GRAY + "Skarby czekają w głębi dzikich ostępów.")));
        for (int i = 0; i < OFFERS.size(); i++) {
            MapOffer offer = OFFERS.get(i);
            ChatColor tierColor = tierColor(offer.tier());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + offer.description());
            lore.add("");
            lore.add(tierColor + "RZADKOŚĆ: " + offer.tier());
            lore.add(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━");
            if (offer.starCost() > 0)
                lore.add(ChatColor.GOLD + "Cena: " + ChatColor.YELLOW + offer.starCost() + " ★");
            else
                lore.add(ChatColor.GOLD + "Cena: " + ChatColor.WHITE + offer.itemCost() + " × " + pretty(offer.currency()));
            lore.add(ChatColor.GRAY + "Posiadasz: " + ChatColor.WHITE
                    + (offer.starCost() > 0 ? crates.getStars(player.getUniqueId())
                    : count(player, offer.currency())) + (offer.starCost() > 0 ? " ★" : " szt."));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Kliknij, aby kupić mapę.");
            inv.setItem(SHOP_SLOTS[i], item(offer.icon(), tierColor + "✦ " + offer.name(), lore));
        }
        inv.setItem(49, item(Material.NETHER_STAR, ChatColor.GOLD + "TWOJE GWIAZDKI",
                List.of(ChatColor.YELLOW + crates.getStars(player.getUniqueId()) + " ★",
                        ChatColor.GRAY + "Lepsze mapy kupisz za gwiazdki",
                        ChatColor.DARK_GRAY + "Zdobywaj je za codzienne zadania.")));
        inv.setItem(53, item(Material.BARRIER, ChatColor.RED + "ZAMKNIJ KRAM", List.of(ChatColor.GRAY + "Do zobaczenia na szlaku!")));
        player.openInventory(inv);
    }

    @EventHandler public void onShopClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw == 53) { player.closeInventory(); return; }
        for (int i = 0; i < SHOP_SLOTS.length; i++) {
            if (raw == SHOP_SLOTS[i]) { purchase(player, OFFERS.get(i)); return; }
        }
    }

    @EventHandler public void onShopDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private void purchase(Player player, MapOffer offer) {
        if (!hasMapSpace(player)) {
            say(player, "Zrób nieco miejsca w plecaku. Nie chcę, żeby cenna mapa wylądowała w błocie.");
            openShop(player);
            return;
        }
        if (offer.starCost() > 0 && crates.getStars(player.getUniqueId()) < offer.starCost()) {
            say(player, "Brakuje ci gwiazdek, wędrowcze. Ta mapa kosztuje " + offer.starCost() + " ★.");
            openShop(player);
            return;
        }
        if (offer.starCost() == 0 && count(player, offer.currency()) < offer.itemCost()) {
            say(player, "Nie masz przy sobie wymaganej zapłaty. Potrzebuję " + offer.itemCost()
                    + " szt. " + pretty(offer.currency()) + ".");
            openShop(player);
            return;
        }
        Location treasure = findTreasureLocation(player, offer);
        if (treasure == null) {
            say(player, "Dziś szlaki są wyjątkowo kapryśne. Nie znalazłem bezpiecznego miejsca na skarb — spróbuj za chwilę.");
            return;
        }
        String mapId = UUID.randomUUID().toString();
        if (offer.starCost() > 0) {
            if (!spendStars(player.getUniqueId(), offer.starCost())) {
                say(player, "Brakuje ci gwiazdek, wędrowcze. Ta mapa kosztuje " + offer.starCost() + " ★.");
                openShop(player);
                return;
            }
        } else {
            remove(player, offer.currency(), offer.itemCost());
        }

        try {
            ItemStack map = createTreasureMap(mapId, treasure, offer);
            int mapViewId = ((MapMeta) map.getItemMeta()).getMapView().getId();
            placeTreasure(mapId, player.getUniqueId(), treasure, offer, mapViewId);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(map);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.25f);
            say(player, PURCHASE_LINES[random.nextInt(PURCHASE_LINES.length)]);
            player.sendMessage(ChatColor.GRAY + "» " + ChatColor.YELLOW + "Otrzymano: "
                    + tierColor(offer.tier()) + offer.name() + ChatColor.GRAY + ". Sprawdź mapę w ekwipunku!");
            openShop(player);
        } catch (Exception ex) {
            plugin.getLogger().severe("Nie udało się utworzyć wyprawy dla " + player.getName() + ": " + ex.getMessage());
            if (offer.starCost() > 0) crates.modifyStars(player.getUniqueId(), "dodaj", offer.starCost());
            else player.getInventory().addItem(new ItemStack(offer.currency(), offer.itemCost()));
            say(player, "Coś poszło nie tak z przygotowaniem wyprawy. Zapłatę zwróciłem.");
        }
    }

    private ItemStack createTreasureMap(String mapId, Location treasure, MapOffer offer) {
        MapView view = Bukkit.createMap(treasure.getWorld());
        view.setCenterX(treasure.getBlockX());
        view.setCenterZ(treasure.getBlockZ());
        view.setScale(MapView.Scale.NORMAL);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        addMarkerRenderer(view);
        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) stack.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName(tierColor(offer.tier()) + "✦ " + offer.name());
        meta.setLore(List.of(ChatColor.GRAY + "Mapa prowadząca do ukrytego skarbu.",
                ChatColor.GRAY + "Świat: " + treasure.getWorld().getName(),
                ChatColor.DARK_GRAY + "Szukaj czerwonego znacznika na mapie.",
                ChatColor.YELLOW + "Wykop skrzynię i odbierz nagrodę!",
                ChatColor.DARK_GRAY + "Wyprawa przypisana do: " + mapId.substring(0, 8)));
        meta.getPersistentDataContainer().set(mapIdKey, PersistentDataType.STRING, mapId);
        stack.setItemMeta(meta);
        return stack;
    }

    private void placeTreasure(String mapId, UUID owner, Location location, MapOffer offer, int mapViewId) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO dq_treasure_maps(map_id,owner_uuid,world,x,y,z,tier,claimed,created_at,map_view_id) VALUES(?,?,?,?,?,?,?,0,?,?)")) {
            insert.setString(1, mapId);
            insert.setString(2, owner.toString());
            insert.setString(3, location.getWorld().getName());
            insert.setInt(4, location.getBlockX());
            insert.setInt(5, location.getBlockY());
            insert.setInt(6, location.getBlockZ());
            insert.setString(7, offer.tier());
            insert.setLong(8, System.currentTimeMillis());
            insert.setInt(9, mapViewId);
            insert.executeUpdate();
        }
        Block block = location.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) throw new IllegalStateException("Nie utworzono skrzyni skarbu");
        chest.getPersistentDataContainer().set(chestIdKey, PersistentDataType.STRING, mapId);
        chest.getPersistentDataContainer().set(chestOwnerKey, PersistentDataType.STRING, owner.toString());
        chest.update(true, false);
        fillTreasure(chest.getBlockInventory(), offer.tier());
    }

    private void addMarkerRenderer(MapView view) {
        if (view.getRenderers().stream().noneMatch(TreasureMarkerRenderer.class::isInstance)) {
            view.addRenderer(new TreasureMarkerRenderer());
        }
    }

    private void restoreMapRenderers() {
        try (PreparedStatement query = db.prepareStatement(
                "SELECT DISTINCT map_view_id FROM dq_treasure_maps WHERE claimed=0 AND map_view_id>=0");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                MapView view = Bukkit.getMap((short) rows.getInt(1));
                if (view != null) addMarkerRenderer(view);
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się odtworzyć znaczników map skarbów: " + ex.getMessage());
        }
    }

    private void fillTreasure(Inventory inventory, String tier) {
        List<ItemStack> loot = new ArrayList<>();
        int stacks = switch (tier) {
            case "COMMON" -> 3;
            case "UNCOMMON" -> 4;
            case "RARE" -> 5;
            case "EPIC" -> 6;
            default -> 7;
        };
        List<Material> pool = switch (tier) {
            case "COMMON" -> List.of(Material.COAL, Material.IRON_INGOT, Material.COPPER_INGOT, Material.BREAD,
                    Material.GOLD_NUGGET, Material.LAPIS_LAZULI, Material.ARROW);
            case "UNCOMMON" -> List.of(Material.IRON_INGOT, Material.GOLD_INGOT, Material.EMERALD,
                    Material.COOKED_BEEF, Material.LAPIS_LAZULI, Material.EXPERIENCE_BOTTLE);
            case "RARE" -> List.of(Material.DIAMOND, Material.EMERALD, Material.GOLDEN_APPLE,
                    Material.OBSIDIAN, Material.EXPERIENCE_BOTTLE, Material.AMETHYST_SHARD);
            case "EPIC" -> List.of(Material.DIAMOND, Material.DIAMOND, Material.HEART_OF_THE_SEA,
                    Material.ECHO_SHARD, Material.ANCIENT_DEBRIS, Material.GOLDEN_APPLE);
            default -> List.of(Material.DIAMOND, Material.NETHERITE_SCRAP, Material.HEART_OF_THE_SEA,
                    Material.ENCHANTED_GOLDEN_APPLE, Material.DIAMOND_BLOCK, Material.ECHO_SHARD);
        };
        for (int i = 0; i < stacks; i++) {
            Material material = pool.get(random.nextInt(pool.size()));
            int amount = switch (tier) {
                case "COMMON" -> 3 + random.nextInt(8);
                case "UNCOMMON" -> 2 + random.nextInt(5);
                case "RARE" -> 1 + random.nextInt(3);
                case "EPIC" -> 1 + random.nextInt(2);
                default -> 1;
            };
            loot.add(new ItemStack(material, Math.min(amount, material.getMaxStackSize())));
        }
        Collections.shuffle(loot);
        for (ItemStack stack : loot) {
            int slot;
            do { slot = random.nextInt(inventory.getSize()); } while (inventory.getItem(slot) != null);
            inventory.setItem(slot, stack);
        }
    }

    private Location findTreasureLocation(Player player, MapOffer offer) {
        World world = player.getWorld().getEnvironment() == World.Environment.NORMAL
                ? player.getWorld() : Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElse(null);
        if (world == null) return null;
        int minRadius = offer.tier().equals("LEGENDARY") ? 700 : offer.tier().equals("EPIC") ? 550 : 350;
        int maxRadius = offer.tier().equals("LEGENDARY") ? 1800 : 1400;
        Location origin = player.getWorld().getEnvironment() == World.Environment.NORMAL
                ? player.getLocation() : world.getSpawnLocation();
        for (int attempt = 0; attempt < 120; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int radius = minRadius + random.nextInt(Math.max(1, maxRadius - minRadius + 1));
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            int chunkX = x >> 4, chunkZ = z >> 4;
            if (!world.isChunkGenerated(chunkX, chunkZ)) continue;
            Block surface = world.getHighestBlockAt(x, z);
            if (!surface.getType().isSolid() || surface.isLiquid()) continue;
            if (surface.getY() < world.getMinHeight() + 8 || surface.getY() > world.getMaxHeight() - 8) continue;
            Block chestBlock = surface.getRelative(0, -1, 0);
            if (!naturalGround(chestBlock.getType()) || !naturalSurface(surface.getType())) continue;
            if (chestBlock.getType().isAir() || chestBlock.getState() instanceof Chest) continue;
            boolean clear = true;
            for (int dx = -2; dx <= 2 && clear; dx++) for (int dz = -2; dz <= 2; dz++) {
                Block nearby = world.getBlockAt(x + dx, surface.getY(), z + dz);
                if (!naturalSurface(nearby.getType())) { clear = false; break; }
            }
            if (!clear || tooCloseToOtherTreasure(world, x, z)) continue;
            Location spawn = world.getSpawnLocation();
            if (Math.hypot(x - spawn.getX(), z - spawn.getZ()) < 600) continue;
            return chestBlock.getLocation();
        }
        return null;
    }

    private boolean tooCloseToOtherTreasure(World world, int x, int z) {
        try (PreparedStatement query = db.prepareStatement(
                "SELECT x,z FROM dq_treasure_maps WHERE world=? AND claimed=0")) {
            query.setString(1, world.getName());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (Math.hypot(x - rows.getInt(1), z - rows.getInt(2)) < 180) return true;
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się sprawdzić odległości od innych skarbów: " + ex.getMessage());
            return true;
        }
        return false;
    }

    private boolean naturalGround(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SAND, RED_SAND,
                    GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, CLAY -> true;
            default -> false;
        };
    }

    private boolean naturalSurface(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SAND, RED_SAND,
                    GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, SNOW_BLOCK -> true;
            default -> false;
        };
    }

    @EventHandler public void onTreasureInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction().isLeftClick()) return;
        if (!(event.getClickedBlock().getState() instanceof Chest chest)) return;
        String mapId = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (mapId == null) return;
        String owner = chest.getPersistentDataContainer().get(chestOwnerKey, PersistentDataType.STRING);
        if (owner == null || !owner.equals(event.getPlayer().getUniqueId().toString())) {
            event.setCancelled(true);
            say(event.getPlayer(), "Ta skrzynia należy do innego odkrywcy. Każda wyprawa ma swojego właściciela.");
            return;
        }
        if (greetedTreasures.add(mapId)) {
            say(event.getPlayer(), "A więc jednak! Znalazłeś ją! Niech twoje znalezisko przejdzie do legend.");
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.15f);
        }
    }

    @EventHandler public void onTreasureBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Chest chest)) return;
        String mapId = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (mapId == null) return;
        event.setCancelled(true);
        say(event.getPlayer(), "Nie tak prędko! Skarby otwiera się kluczem... a tym kluczem jest twoja mapa.");
    }

    @EventHandler public void onTreasureClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) return;
        String mapId = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (mapId == null || !chest.getBlockInventory().isEmpty()) return;
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE dq_treasure_maps SET claimed=1 WHERE map_id=? AND claimed=0")) {
            update.setString(1, mapId);
            if (update.executeUpdate() == 1) {
                chest.getBlock().setType(Material.AIR, false);
                if (event.getPlayer() instanceof Player player) {
                    removeMapItem(player, mapId);
                    say(player, "Skarb jest twój! A teraz... może czas na kolejną wyprawę?");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.3f);
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się oznaczyć skarbu jako odebranego: " + ex.getMessage());
        }
    }

    @EventHandler public void onTreasureMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestIdKey, PersistentDataType.STRING)) event.setCancelled(true);
        if (event.getDestination().getHolder() instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestIdKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    @EventHandler public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> block.getState() instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestIdKey, PersistentDataType.STRING));
    }

    @EventHandler public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> block.getState() instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestIdKey, PersistentDataType.STRING));
    }

    private void removeMapItem(Player player, String mapId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || !(stack.getItemMeta() instanceof MapMeta meta)) continue;
            if (mapId.equals(meta.getPersistentDataContainer().get(mapIdKey, PersistentDataType.STRING))) {
                stack.setAmount(stack.getAmount() - 1);
                break;
            }
        }
    }

    private boolean spendStars(UUID uuid, int amount) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE dq_wallet SET stars=stars-? WHERE uuid=? AND stars>=?")) {
            update.setInt(1, amount); update.setString(2, uuid.toString()); update.setInt(3, amount);
            return update.executeUpdate() == 1;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się pobrać gwiazdek za mapę: " + ex.getMessage());
            return false;
        }
    }

    private boolean hasMapSpace(Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    private int count(Player player, Material material) {
        int amount = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && stack.getType() == material) amount += stack.getAmount();
        return amount;
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

    void renameNpc(String name, java.util.function.Consumer<String> reply) {
        if (!npcFile.exists()) { reply.accept(ChatColor.RED + "Krzusław nie został jeszcze postawiony."); return; }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(npcFile);
        Entity entity = null;
        try { entity = Bukkit.getEntity(UUID.fromString(yaml.getString("uuid", ""))); }
        catch (Exception ignored) { }
        if (!(entity instanceof Villager villager) || !villager.isValid()) {
            reply.accept(ChatColor.RED + "Nie znaleziono Krzusława. Spróbuj ponownie po jego odtworzeniu.");
            return;
        }
        yaml.set("name", name); saveNpc(yaml); configure(villager, name);
        reply.accept(ChatColor.GREEN + "Zmieniono nazwę kartografa na: " + color(name));
    }

    void removeNpc() {
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities())
                if (entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) entity.remove();
        if (npcFile.exists()) npcFile.delete();
        nearbyPlayers.clear();
    }

    void teleport(Player player) {
        if (!npcFile.exists()) { player.sendMessage(ChatColor.RED + "Krzusław nie został jeszcze postawiony."); return; }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(npcFile);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null || !yaml.contains("x")) { player.sendMessage(ChatColor.RED + "Nie zapisano lokalizacji Krzusława."); return; }
        Location location = npcLocation(yaml, world);
        world.getChunkAt(location).load();
        player.teleport(location);
    }

    @EventHandler public void onNpcMount(EntityMountEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getEntity().isInsideVehicle()) event.getEntity().leaveVehicle();
    }

    @EventHandler public void onNpcDamage(EntityDamageEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player) {
            long now = System.currentTimeMillis();
            if (now - lastWarning.getOrDefault(player.getUniqueId(), 0L) >= 2500L) {
                lastWarning.put(player.getUniqueId(), now);
                String[] lines = {
                        "Hej! Te ręce służą do kopania skarbów, nie do okładania kartografów!",
                        "Ostrożnie z tymi pięściami! Mapa może się zgnieść, a ja razem z nią.",
                        "Jeśli szukasz przygód, mam mapy. Jeśli szukasz guza, właśnie go znalazłeś.",
                        "Za moich czasów podróżnicy okazywali kartografom nieco więcej szacunku!"
                };
                say(player, lines[random.nextInt(lines.length)]);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.65f, 0.85f);
            }
        }
    }

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        nearbyPlayers.remove(event.getPlayer().getUniqueId());
        lastAmbient.remove(event.getPlayer().getUniqueId());
        lastWarning.remove(event.getPlayer().getUniqueId());
    }

    private Location npcLocation(org.bukkit.configuration.file.YamlConfiguration yaml, World world) {
        return new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
    }

    private void saveNpc(org.bukkit.configuration.file.YamlConfiguration yaml) {
        try { yaml.save(npcFile); }
        catch (Exception ex) { plugin.getLogger().warning("Nie zapisano lokalizacji Krzusława: " + ex.getMessage()); }
    }

    private boolean naturalGround(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SAND, RED_SAND,
                    GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, CLAY -> true;
            default -> false;
        };
    }

    private boolean naturalSurface(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SAND, RED_SAND,
                    GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, SNOW_BLOCK -> true;
            default -> false;
        };
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
            case "RARE" -> ChatColor.AQUA;
            case "EPIC" -> ChatColor.LIGHT_PURPLE;
            case "LEGENDARY" -> ChatColor.GOLD;
            default -> ChatColor.WHITE;
        };
    }

    private String pretty(Material material) {
        StringJoiner joiner = new StringJoiner(" ");
        for (String part : material.name().toLowerCase(Locale.ROOT).split("_"))
            joiner.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        return joiner.toString();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
