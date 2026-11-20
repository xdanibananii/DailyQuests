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
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;

public final class FilipNpcSystem implements Listener {
    private static final String TITLE = ChatColor.DARK_AQUA + "FILIP SZNUPACZ — WSPÓLNY MAGAZYN";
    private static final int PAGE_SIZE = 45;
    private final DailyQuestsPlugin plugin;
    private final Connection db;
    private final NamespacedKey npcKey;
    private final File npcFile;
    private final Map<UUID, Integer> pages = new HashMap<>();

    FilipNpcSystem(DailyQuestsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.npcKey = new NamespacedKey(plugin, "filip_npc");
        this.npcFile = new File(plugin.getDataFolder(), "filip_npc.yml");
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS filip_shared_items (id INTEGER PRIMARY KEY AUTOINCREMENT, item_data TEXT NOT NULL, deposited_at INTEGER NOT NULL)");
        } catch (SQLException ex) {
            plugin.getLogger().severe("Nie można utworzyć wspólnego magazynu Filipa: " + ex.getMessage());
        }
        restoreNpc();
    }

    private void restoreNpc() {
        if (!npcFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null || !yaml.contains("x")) return;
        Location location = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
        Entity existing = findNpc(yaml);
        if (existing instanceof Villager villager) { configure(villager, yaml.getString("name", "Filip Sznupacz")); return; }
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        configure(villager, yaml.getString("name", "Filip Sznupacz"));
        yaml.set("uuid", villager.getUniqueId().toString()); save(yaml);
    }

    void spawnAt(Location location, java.util.function.Consumer<String> reply) {
        if (location.getWorld() == null) return;
        removeNpc();
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        String name = yaml.getString("name", "Filip Sznupacz");
        configure(villager, name);
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX()); yaml.set("y", location.getY()); yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw()); yaml.set("pitch", location.getPitch());
        yaml.set("uuid", villager.getUniqueId().toString()); yaml.set("name", name); save(yaml);
        reply.accept(ChatColor.GREEN + "Postawiono Filipa Sznupacza.");
    }

    private void configure(Villager villager, String name) {
        if (villager.isInsideVehicle()) villager.leaveVehicle();
        villager.setAI(false); villager.setInvulnerable(true); villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false); villager.setCanPickupItems(false); villager.setCollidable(false);
        villager.setProfession(Villager.Profession.LIBRARIAN);
        villager.setCustomName(ChatColor.AQUA + "✦ " + ChatColor.WHITE + color(name) + ChatColor.AQUA + " ✦");
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
    }

    void removeNpc() {
        if (npcFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
            Entity entity = findNpc(yaml); if (entity != null) entity.remove();
            npcFile.delete();
        }
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager
                    && villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) villager.remove();
        }
    }

    void renameNpc(String name, java.util.function.Consumer<String> reply) {
        if (!npcFile.exists()) { reply.accept(ChatColor.RED + "Filip nie jest jeszcze postawiony."); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcFile);
        yaml.set("name", name); save(yaml);
        Entity entity = findNpc(yaml); if (entity instanceof Villager villager) configure(villager, name);
        reply.accept(ChatColor.GREEN + "Zmieniono nazwę Filipa na: " + color(name));
    }

    void teleport(Player player) {
        if (!npcFile.exists()) { player.sendMessage(ChatColor.RED + "Filip nie jest jeszcze postawiony."); return; }
        Entity entity = findNpc(YamlConfiguration.loadConfiguration(npcFile));
        if (entity == null) { player.sendMessage(ChatColor.RED + "Nie znaleziono Filipa."); return; }
        player.teleport(entity.getLocation());
    }

    private Entity findNpc(YamlConfiguration yaml) {
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world != null && yaml.contains("x")) world.getChunkAt((int) Math.floor(yaml.getDouble("x")) >> 4, (int) Math.floor(yaml.getDouble("z")) >> 4).load();
        try { String id = yaml.getString("uuid", ""); return id.isBlank() ? null : Bukkit.getEntity(UUID.fromString(id)); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private void save(YamlConfiguration yaml) {
        try { yaml.save(npcFile); }
        catch (IOException ex) { plugin.getLogger().warning("Nie udało się zapisać Filipa: " + ex.getMessage()); }
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        open(event.getPlayer(), 0);
    }

    @EventHandler
    public void onMount(EntityMountEvent event) {
        if (event.getMount() instanceof Villager villager
                && villager.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    private void open(Player player, int page) {
        long count = countItems();
        int pagesCount = Math.max(1, (int) Math.ceil(count / (double) PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, pagesCount - 1));
        pages.put(player.getUniqueId(), safePage);
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);
        List<StoredItem> items = loadPage(safePage);
        for (int i = 0; i < items.size() && i < PAGE_SIZE; i++) inventory.setItem(i, items.get(i).item());
        inventory.setItem(45, item(Material.HOPPER, ChatColor.GREEN + "Jak zdeponować?",
                List.of(ChatColor.GRAY + "Przenieś przedmiot ze swojego ekwipunku",
                        ChatColor.GRAY + "do tego okna, używając SHIFT + klik.")));
        inventory.setItem(48, item(Material.ARROW, ChatColor.YELLOW + "Poprzednia strona", List.of()));
        inventory.setItem(49, item(Material.BOOK, ChatColor.AQUA + "WSPÓLNY MAGAZYN",
                List.of(ChatColor.GRAY + "Strona " + (safePage + 1) + " / " + pagesCount,
                        ChatColor.GRAY + "Łącznie stosów: " + count,
                        ChatColor.DARK_GRAY + "Każdy gracz może wpłacać i wypłacać.")));
        inventory.setItem(50, item(Material.ARROW, ChatColor.YELLOW + "Następna strona", List.of()));
        inventory.setItem(53, item(Material.BARRIER, ChatColor.RED + "Zamknij", List.of()));
        player.openInventory(inventory);
    }

    private record StoredItem(long id, ItemStack item) { }

    private long countItems() {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM filip_shared_items")) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException ex) { plugin.getLogger().warning("Błąd liczenia magazynu Filipa: " + ex.getMessage()); return 0; }
    }

    private List<StoredItem> loadPage(int page) {
        List<StoredItem> result = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT id,item_data FROM filip_shared_items ORDER BY id LIMIT ? OFFSET ?")) {
            query.setInt(1, PAGE_SIZE); query.setInt(2, page * PAGE_SIZE);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try { result.add(new StoredItem(rows.getLong(1), deserialize(rows.getString(2)))); }
                    catch (Exception ex) { plugin.getLogger().warning("Pominięto uszkodzony wpis w magazynie Filipa: " + ex.getMessage()); }
                }
            }
        } catch (SQLException ex) { plugin.getLogger().warning("Błąd odczytu magazynu Filipa: " + ex.getMessage()); }
        return result;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw >= 54) {
            if (event.isShiftClick() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
                ItemStack deposit = event.getCurrentItem().clone();
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO filip_shared_items(item_data,deposited_at) VALUES(?,?)")) {
                    insert.setString(1, serialize(deposit)); insert.setLong(2, System.currentTimeMillis());
                    insert.executeUpdate();
                    event.setCurrentItem(null);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.1f);
                    Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && TITLE.equals(player.getOpenInventory().getTitle())) open(player, pages.getOrDefault(player.getUniqueId(), 0)); });
                } catch (Exception ex) {
                    plugin.getLogger().warning("Nie udało się zdeponować przedmiotu u Filipa: " + ex.getMessage());
                    player.sendMessage(ChatColor.RED + "Nie udało się zdeponować przedmiotu.");
                }
            }
            return;
        }
        if (raw == 48) { open(player, pages.getOrDefault(player.getUniqueId(), 0) - 1); return; }
        if (raw == 50) { open(player, pages.getOrDefault(player.getUniqueId(), 0) + 1); return; }
        if (raw == 53) { player.closeInventory(); return; }
        if (raw < 0 || raw >= PAGE_SIZE) return;
        ItemStack displayed = event.getCurrentItem();
        if (displayed == null || displayed.getType().isAir()) return;
        int page = pages.getOrDefault(player.getUniqueId(), 0);
        List<StoredItem> visible = loadPage(page);
        if (raw >= visible.size()) return;
        StoredItem selected = visible.get(raw);
        ItemStack simulated = displayed.clone();
        Inventory preview = Bukkit.createInventory(null, 36);
        ItemStack[] currentContents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < Math.min(currentContents.length, preview.getSize()); slot++) {
            ItemStack content = currentContents[slot];
            if (content != null && !content.getType().isAir()) preview.setItem(slot, content.clone());
        }
        Map<Integer, ItemStack> overflow = preview.addItem(simulated.clone());
        if (!overflow.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Zwolnij miejsce w ekwipunku, aby odebrać ten stos.");
            return;
        }
        try (PreparedStatement delete = db.prepareStatement("DELETE FROM filip_shared_items WHERE id=?")) {
            delete.setLong(1, selected.id());
            if (delete.executeUpdate() == 1) {
                player.getInventory().addItem(simulated);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.9f);
                open(player, page);
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Nie udało się wypłacić przedmiotu z magazynu Filipa: " + ex.getMessage());
            player.sendMessage(ChatColor.RED + "Nie udało się wypłacić przedmiotu.");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
    }

    private String serialize(ItemStack item) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) { output.writeObject(item); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private ItemStack deserialize(String encoded) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object value = input.readObject();
            if (!(value instanceof ItemStack item)) throw new IOException("Nieprawidłowy typ przedmiotu");
            return item;
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(name); meta.setLore(lore); stack.setItemMeta(meta);
        return stack;
    }

    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
}