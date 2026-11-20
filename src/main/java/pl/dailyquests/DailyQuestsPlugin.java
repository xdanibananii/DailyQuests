package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.block.Block;
// Used by kelp column progress counting.
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DailyQuestsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Connection db;
    private ZoneId zone;
    private final Map<UUID, List<Task>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, String> cacheDay = new ConcurrentHashMap<>();
    private final Map<UUID, Double> movementRemainder = new ConcurrentHashMap<>();
    private org.bukkit.configuration.file.YamlConfiguration tasksConfig;
    private final Map<UUID, Location> lastWalk = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Set<UUID> rerollMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingMapHint> pendingMapHints = new ConcurrentHashMap<>();
    private static final String TITLE = ChatColor.DARK_GRAY + "Codzienne zadania";
    private CrateSystem crateSystem;
    private QuestNpcSystem npcSystem;
    private CrateNpcSystem crateNpcSystem;
    private CartographerNpcSystem cartographerNpcSystem;
    private FilipNpcSystem filipNpcSystem;
    private SnafNpcSystem snafNpcSystem;

    private record TaskSlot(int slot, String taskId) { }
    private record CampfireFood(Material result, int amount) { }
    private record PendingMapHint(String mapId, long expiresAt) { }

    private static final class Task {
        String id, name, description, type, target, rewardItem, dimension, difficulty;
        int amount, xp, itemAmount, progress;
        boolean claimed, requiresNether;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("tasks.yml", false);
        File poolMarker = new File(getDataFolder(), "quest-pool-v8.done");
        if (!poolMarker.exists()) {
            mergeDefaultTasks();
            try { poolMarker.createNewFile(); } catch (java.io.IOException ex) { getLogger().warning("Nie zapisano znacznika aktualizacji puli zadan: " + ex.getMessage()); }
        }
        reloadTaskPool();
        try { zone = ZoneId.of(getConfig().getString("timezone", "Europe/Warsaw")); }
        catch (Exception ex) { zone = ZoneId.systemDefault(); getLogger().warning("Nieprawidlowa strefa czasowa; uzywam strefy serwera."); }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "dailyquests.db").getAbsolutePath());
            try (Statement pragmas = db.createStatement()) {
                pragmas.execute("PRAGMA journal_mode=WAL");
                pragmas.execute("PRAGMA synchronous=NORMAL");
                pragmas.execute("PRAGMA busy_timeout=1500");
            }
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS player_tasks (uuid TEXT NOT NULL, day TEXT NOT NULL, slot INTEGER NOT NULL, task_id TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,day,slot))");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY, streak INTEGER NOT NULL DEFAULT 0, last_streak_day TEXT NOT NULL DEFAULT '', total_completed INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS weekly_tasks (uuid TEXT NOT NULL, week TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, claimed INTEGER NOT NULL DEFAULT 0, challenge_id TEXT NOT NULL DEFAULT '', PRIMARY KEY(uuid,week))");
                try { s.executeUpdate("ALTER TABLE weekly_tasks ADD COLUMN challenge_id TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) { }
                s.executeUpdate("CREATE TABLE IF NOT EXISTS task_history (uuid TEXT NOT NULL, task_id TEXT NOT NULL, assigned_day TEXT NOT NULL, PRIMARY KEY(uuid,task_id,assigned_day))");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS placed_wood_blocks (world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, material TEXT NOT NULL, PRIMARY KEY(world,x,y,z))");
                File resetMarker = new File(getDataFolder(), "test-reset-v2.done");
                if (!resetMarker.exists()) {
                    s.executeUpdate("DELETE FROM player_tasks");
                    try { resetMarker.createNewFile(); } catch (java.io.IOException ex) { getLogger().warning("Nie zapisano znacznika resetu: " + ex.getMessage()); }
                    getLogger().info("Wyczyszczono dane testowe graczy.");
                }
            }
        } catch (SQLException e) { getLogger().severe("Nie mozna otworzyc SQLite: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        Objects.requireNonNull(getCommand("zadania")).setExecutor(this);
        Objects.requireNonNull(getCommand("zadania")).setTabCompleter(this);
        crateSystem = new CrateSystem(this);
        npcSystem = new QuestNpcSystem(this, crateSystem);
        crateNpcSystem = new CrateNpcSystem(this, crateSystem);
        cartographerNpcSystem = new CartographerNpcSystem(this, crateSystem);
        filipNpcSystem = new FilipNpcSystem(this);
        snafNpcSystem = new SnafNpcSystem(this);
        getServer().getPluginManager().registerEvents(npcSystem, this);
        getServer().getPluginManager().registerEvents(crateNpcSystem, this);
        getServer().getPluginManager().registerEvents(cartographerNpcSystem, this);
        getServer().getPluginManager().registerEvents(filipNpcSystem, this);
        getServer().getPluginManager().registerEvents(snafNpcSystem, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(crateSystem, this);
        for (Player p : Bukkit.getOnlinePlayers()) ensureTasks(p);
        getLogger().info("DailyQuests wlaczony.");
    }

    @Override public void onDisable() {
        if (crateSystem != null) crateSystem.close();
        if (db != null) try { db.close(); } catch (SQLException ignored) {}
    }

    private void mergeDefaultTasks() {
        File poolFile = new File(getDataFolder(), "tasks.yml");
        org.bukkit.configuration.file.YamlConfiguration current = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(poolFile);
        org.bukkit.configuration.file.YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(getResource("tasks.yml")), StandardCharsets.UTF_8)) {
            defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            getLogger().warning("Nie udało się wczytać domyślnej puli zadań: " + ex.getMessage());
            return;
        }
        ConfigurationSection defaultTasks = defaults.getConfigurationSection("tasks");
        if (defaultTasks == null) return;
        ConfigurationSection currentTasks = current.getConfigurationSection("tasks");
        if (currentTasks == null) currentTasks = current.createSection("tasks");
        int added = 0;
        for (String id : defaultTasks.getKeys(false)) {
            ConfigurationSection defaultsForTask = defaultTasks.getConfigurationSection(id);
            ConfigurationSection existingTask = currentTasks.getConfigurationSection(id);
            if (existingTask == null) {
                currentTasks.set(id, defaultsForTask.getValues(true));
                added++;
            } else {
                for (String key : defaultsForTask.getKeys(false)) {
                    if (!existingTask.contains(key)) {
                        existingTask.set(key, defaultsForTask.get(key));
                        added++;
                    }
                }
            }
        }
        try {
            current.save(poolFile);
            getLogger().info("Zaktualizowano pulę zadań. Dodano " + added + " nowych pozycji; własne zadania zachowano.");
        } catch (java.io.IOException ex) {
            getLogger().warning("Nie udało się zapisać puli zadań: " + ex.getMessage());
        }
    }

    Connection database() { return db; }

    private String today() { return LocalDate.now(zone).toString(); }
    private void reloadTaskPool() {
        File file = new File(getDataFolder(), "tasks.yml");
        tasksConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
    }

    private ConfigurationSection pool() {
        if (tasksConfig == null) reloadTaskPool();
        return tasksConfig.getConfigurationSection("tasks");
    }

    private List<Task> ensureTasks(Player p) {
        String day=today();
        UUID uuid=p.getUniqueId();
        List<Task> cached=cache.get(uuid);
        if(cached!=null && cached.size()==5 && day.equals(cacheDay.get(uuid))
                && cached.stream().allMatch(this::isTaskEnabled)) return cached;
        List<Task> found=new ArrayList<>();
        List<Integer> slots=new ArrayList<>();
        try(PreparedStatement q=db.prepareStatement("SELECT slot,task_id,progress,claimed FROM player_tasks WHERE uuid=? AND day=? ORDER BY slot")) {
            q.setString(1,p.getUniqueId().toString()); q.setString(2,day);
            try(ResultSet rs=q.executeQuery()) {
                while(rs.next()) {
                    Task task=loadTask(rs.getString(2));
                    if(task!=null && isTaskEnabled(task)) {
                        task.progress=rs.getInt(3); task.claimed=rs.getInt(4)==1;
                        found.add(task); slots.add(rs.getInt(1));
                    }
                }
            }
        } catch(SQLException e) { getLogger().warning(e.getMessage()); }
        if(found.size()==5) {
            for(Task task:found) recordTaskHistory(uuid,task.id,day);
            cache.put(uuid,found); cacheDay.put(uuid,day); return found;
        }

        ConfigurationSection sec=pool();
        if(sec==null || sec.getKeys(false).isEmpty()) return found;
        Set<String> assignedToday=tasksAssignedToday(uuid);
        // Ensure tasks created by older plugin versions are also recorded before any reroll.
        for(Task task:found) {
            assignedToday.add(task.id);
            recordTaskHistory(uuid,task.id,day);
        }
        List<String> ids=new ArrayList<>(sec.getKeys(false));
        ids.removeIf(id -> {
            Task candidate=loadTask(id);
            return candidate==null || !isTaskEnabled(candidate) || assignedToday.contains(id);
        });
        Collections.shuffle(ids);
        List<Task> replacements=new ArrayList<>();
        for(String id:ids) {
            Task task=loadTask(id);
            if(task!=null) { replacements.add(task); if(found.size()+replacements.size()>=5) break; }
        }
        try(PreparedStatement del=db.prepareStatement("DELETE FROM player_tasks WHERE uuid=? AND day=?")) {
            del.setString(1,p.getUniqueId().toString()); del.setString(2,day); del.executeUpdate();
        } catch(SQLException ex) { getLogger().warning("Nie udało się odświeżyć zadań: "+ex.getMessage()); }
        List<Task> result=new ArrayList<>(found);
        for(int i=0;i<found.size();i++) save(p.getUniqueId(),slots.get(i),found.get(i));
        int nextSlot=0;
        for(Task task:found) nextSlot=Math.max(nextSlot,slots.get(result.indexOf(task))+1);
        for(Task task:replacements) {
            while(slots.contains(nextSlot)) nextSlot++;
            save(p.getUniqueId(),nextSlot++,task);
            result.add(task);
            recordTaskHistory(uuid,task.id,day);
        }
        cache.put(uuid,result);
        cacheDay.put(uuid,day);
        return result;
    }

    private Task loadTask(String id) {
        ConfigurationSection s=pool(); if(s==null || !s.isConfigurationSection(id)) return null;
        ConfigurationSection c=s.getConfigurationSection(id); Task t=new Task();
        t.id=id;t.name=color(c.getString("name",id));t.description=color(c.getString("description",""));
        t.type=c.getString("type","").toUpperCase(Locale.ROOT);t.target=c.getString("target","ANY").toUpperCase(Locale.ROOT);t.dimension=c.getString("dimension","OVERWORLD").toUpperCase(Locale.ROOT);t.difficulty=c.getString("difficulty","MEDIUM").toUpperCase(Locale.ROOT);
        t.amount=Math.max(1,c.getInt("amount",1));t.xp=Math.max(0,c.getInt("reward-xp",0));
        t.rewardItem=c.getString("reward-item","").toUpperCase(Locale.ROOT);t.itemAmount=Math.max(1,c.getInt("reward-item-amount",1));t.requiresNether=c.getBoolean("requires-nether",false);return t;
    }

    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
    private void save(UUID uuid,int slot,Task t) {
        try(PreparedStatement p=db.prepareStatement("INSERT OR REPLACE INTO player_tasks(uuid,day,slot,task_id,progress,claimed) VALUES(?,?,?,?,?,?)")){
            p.setString(1,uuid.toString());p.setString(2,today());p.setInt(3,slot);p.setString(4,t.id);p.setInt(5,t.progress);p.setInt(6,t.claimed?1:0);p.executeUpdate();
        }catch(SQLException e){getLogger().warning("Blad zapisu zadania: "+e.getMessage());}
    }
    private Set<String> tasksAssignedToday(UUID uuid) {
        Set<String> assigned = new HashSet<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT task_id FROM task_history WHERE uuid=? AND assigned_day=?")) {
            query.setString(1, uuid.toString());
            query.setString(2, today());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) assigned.add(result.getString(1));
            }
        } catch (SQLException ex) {
            getLogger().warning("Nie udało się odczytać dzisiejszej historii zadań: " + ex.getMessage());
        }
        return assigned;
    }

    private void recordTaskHistory(UUID uuid, String taskId, String day) {
        try (PreparedStatement history = db.prepareStatement(
                "INSERT OR IGNORE INTO task_history(uuid,task_id,assigned_day) VALUES(?,?,?)")) {
            history.setString(1, uuid.toString());
            history.setString(2, taskId);
            history.setString(3, day);
            history.executeUpdate();
        } catch (SQLException ex) {
            getLogger().warning("Nie udało się zapisać historii zadania: " + ex.getMessage());
        }
    }

    private void update(Player p,Task t,int delta) {
        if(delta<=0 || !isTaskEnabled(t))return;
        List<Task> list=cache.get(p.getUniqueId());
        if(list==null) list=ensureTasks(p);
        for(int i=0;i<list.size();i++) if(list.get(i).id.equals(t.id)){
            Task actual=list.get(i); if(actual.claimed)return;
            int before=actual.progress;
            actual.progress=Math.min(actual.amount,actual.progress+delta);
            save(p.getUniqueId(),i,actual);
            if(before<actual.amount && actual.progress>=actual.amount){
                ChatColor difficultyColor = switch (actual.difficulty) {
                    case "EASY" -> ChatColor.GREEN;
                    case "HARD" -> ChatColor.RED;
                    default -> ChatColor.YELLOW;
                };
                Component announcement = Component.text("★ ", NamedTextColor.GOLD)
                        .append(Component.text(p.getName(), NamedTextColor.WHITE))
                        .append(Component.text(" ukończył zadanie ", NamedTextColor.GRAY))
                        .append(LegacyComponentSerializer.legacySection().deserialize(difficultyColor + actual.name)
                                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                        LegacyComponentSerializer.legacySection().deserialize(
                                                actual.description == null || actual.description.isBlank()
                                                        ? "Brak opisu zadania." : actual.description))));
                Bukkit.broadcast(announcement);
            }
            break;
        }
    }

    private void rerollAll(CommandSender sender) {
        String day=today();
        // Preserve every task already assigned today, including for offline players.
        try (PreparedStatement existing=db.prepareStatement("SELECT uuid,task_id FROM player_tasks WHERE day=?")) {
            existing.setString(1,day);
            try(ResultSet rows=existing.executeQuery()) {
                while(rows.next()) {
                    try(PreparedStatement history=db.prepareStatement(
                            "INSERT OR IGNORE INTO task_history(uuid,task_id,assigned_day) VALUES(?,?,?)")) {
                        history.setString(1,rows.getString(1));
                        history.setString(2,rows.getString(2));
                        history.setString(3,day);
                        history.executeUpdate();
                    }
                }
            }
        } catch(SQLException ex) {
            sender.sendMessage(ChatColor.RED+"Nie udalo sie zabezpieczyc historii zadan: "+ex.getMessage());
            getLogger().warning("Blad zapisu historii przed /dq roll: "+ex.getMessage());
            return;
        }
        try (PreparedStatement st=db.prepareStatement("DELETE FROM player_tasks WHERE day=?")) {
            st.setString(1,day);
            st.executeUpdate();
        } catch (SQLException ex) {
            sender.sendMessage(ChatColor.RED+"Nie udalo sie przerolowac zadan: "+ex.getMessage());
            getLogger().warning("Blad /dq roll: "+ex.getMessage());
            return;
        }
        cache.clear();
        for (Player online : Bukkit.getOnlinePlayers()) {
            List<Task> newTasks=ensureTasks(online);
            online.sendMessage(ChatColor.GOLD+"★ "+ChatColor.YELLOW+"Administrator wylosowal nowe zadania dzienne!");
            online.sendMessage(ChatColor.GRAY+"Twoj dotychczasowy postep zostal wyzerowany. Otworz "+ChatColor.YELLOW+"/zadania");
            if (TITLE.equals(online.getOpenInventory().getTitle())) open(online);
        }
        sender.sendMessage(ChatColor.GREEN+"Przerolowano zadania dla wszystkich graczy. Usunieto tez dzisiejszy postep graczy offline.");
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━ DAILYQUESTS — POMOC ━━");
        sender.sendMessage(ChatColor.YELLOW + "/dailyquest" + ChatColor.GRAY + " — wyświetla tę listę komend");
        sender.sendMessage(ChatColor.YELLOW + "/zadania" + ChatColor.GRAY + " — otwiera codzienne zadania");
        sender.sendMessage(ChatColor.YELLOW + "/dq weekly" + ChatColor.GRAY + " — pokazuje wyzwanie tygodnia i postęp");
        sender.sendMessage(ChatColor.YELLOW + "/dq top" + ChatColor.GRAY + " — wyświetla ranking graczy");
        sender.sendMessage(ChatColor.YELLOW + "/dq gwiazdki" + ChatColor.GRAY + " — sprawdza własne saldo gwiazdek");
        sender.sendMessage(ChatColor.YELLOW + "/dq mapa pomoc" + ChatColor.GRAY + " — prosi Krzusława o wskazówkę do najbliższej skrzyni (5 poziomów XP)");
        sender.sendMessage(ChatColor.YELLOW + "/dq mapa potwierdz" + ChatColor.GRAY + " — potwierdza zakup wskazówki");
        sender.sendMessage(ChatColor.GRAY + "Kontrakty dzienne znajdziesz u Michaiła Łomonosowa.");
        sender.sendMessage(ChatColor.GRAY + "Losowanie nagród za gwiazdki znajdziesz u Strażnika Skarbca.");
        sender.sendMessage(ChatColor.GRAY + "Mapy skarbów i wyprawy znajdziesz u Krzusława Wędrownego.");
        sender.sendMessage(ChatColor.GRAY + "Wspólny magazyn znajdziesz u Filipa Sznupacza.");
        sender.sendMessage(ChatColor.GRAY + "Dzienną porcję zupy odbierzesz u Snafa.");
        if (sender.hasPermission("dailyquests.admin")) {
            sender.sendMessage(ChatColor.DARK_AQUA + "━━ KOMENDY ADMINISTRATORA ━━");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc quest [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Michaiłem");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc skarbiec [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Strażnikiem Skarbca");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc kartograf [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Krzusławem Wędrownym");
            sender.sendMessage(ChatColor.YELLOW + "/dq kartograf list" + ChatColor.GRAY + " — lista aktywnych map i współrzędnych skrzyń");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc filip [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Filipem Sznupaczem");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc snaf [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Snafem");
            sender.sendMessage(ChatColor.YELLOW + "/dq gwiazdki <dodaj|usun|ustaw> <gracz> <ilość>" + ChatColor.GRAY + " — zarządzanie saldem");
            sender.sendMessage(ChatColor.YELLOW + "/dq gwiazdki sprawdz <gracz>" + ChatColor.GRAY + " — sprawdza saldo gracza");
            sender.sendMessage(ChatColor.YELLOW + "/dq roll" + ChatColor.GRAY + " — losuje ponownie zadania wszystkim graczom");
            sender.sendMessage(ChatColor.YELLOW + "/dq nether <on|off>" + ChatColor.GRAY + " — włącza lub wyłącza zadania z Netheru");
            sender.sendMessage(ChatColor.YELLOW + "/dq end <on|off>" + ChatColor.GRAY + " — włącza lub wyłącza zadania z Endu");
            sender.sendMessage(ChatColor.YELLOW + "/dq reload" + ChatColor.GRAY + " — przeładowuje konfigurację i pulę zadań");
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        boolean admin = sender.hasPermission("dailyquests.admin");
        if (args.length == 1) {
            suggestions.addAll(List.of("help", "top", "weekly", "gwiazdki", "mapa"));
            if (admin) suggestions.addAll(List.of("npc", "kartograf", "roll", "nether", "end", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mapa")) {
            suggestions.addAll(List.of("pomoc", "potwierdz"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("kartograf") && admin) {
            suggestions.add("list");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc") && admin) {
            suggestions.addAll(List.of("quest", "skarbiec", "kartograf", "filip", "snaf"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("npc") && admin
                && Set.of("quest", "skarbiec", "kartograf", "filip", "snaf").contains(args[1].toLowerCase(Locale.ROOT))) {
            suggestions.addAll(List.of("postaw", "usun", "tp", "rename"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("gwiazdki") && admin) {
            suggestions.addAll(List.of("dodaj", "usun", "ustaw", "sprawdz"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("gwiazdki") && admin
                && Set.of("dodaj", "usun", "ustaw", "sprawdz").contains(args[1].toLowerCase(Locale.ROOT))) {
            for (Player online : Bukkit.getOnlinePlayers()) suggestions.add(online.getName());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("nether") || args[0].equalsIgnoreCase("end")) && admin) {
            suggestions.addAll(List.of("on", "off"));
        }
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        suggestions.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(current));
        return suggestions;
    }


    private void requestMapHint(Player player) {
        UUID uuid=player.getUniqueId();
        List<String> mapIds=new ArrayList<>();
        String selectedId=null;
        double nearestDistance=Double.MAX_VALUE;
        try(PreparedStatement query=db.prepareStatement(
                "SELECT map_id,world,x,z FROM dq_treasure_maps WHERE owner_uuid=? AND claimed=0 AND discovered=0")) {
            query.setString(1,uuid.toString());
            try(ResultSet rows=query.executeQuery()) {
                while(rows.next()) {
                    String worldName=rows.getString("world");
                    mapIds.add(worldName);
                    if(!player.getWorld().getName().equals(worldName)) continue;
                    double distance=Math.hypot(player.getLocation().getX()-rows.getInt("x"),
                            player.getLocation().getZ()-rows.getInt("z"));
                    if(distance<nearestDistance) {
                        nearestDistance=distance;
                        selectedId=rows.getString("map_id");
                    }
                }
            }
        } catch(SQLException ex) {
            getLogger().warning("Nie udało się znaleźć najbliższej mapy gracza: "+ex.getMessage());
            player.sendMessage(ChatColor.RED+"Nie udało się odczytać Twoich map skarbów. Spróbuj później.");
            return;
        }
        if(selectedId==null) {
            if(mapIds.isEmpty()) {
                player.sendMessage(ChatColor.RED+"Nie masz żadnej aktywnej mapy skarbów.");
            } else {
                player.sendMessage(ChatColor.YELLOW+"Twoje aktywne skarby znajdują się w innym świecie. Wróć do świata, w którym kupiłeś mapę, aby uzyskać wskazówkę.");
            }
            return;
        }
        long expires=System.currentTimeMillis()+30_000L;
        pendingMapHints.put(uuid,new PendingMapHint(selectedId,expires));
        player.sendMessage(ChatColor.GOLD+"✦ KRZUSŁAW WĘDROWNY — WSKAZÓWKA ✦");
        player.sendMessage(ChatColor.YELLOW+"Mogę wskazać kierunek do najbliższej Twojej skrzyni.");
        player.sendMessage(ChatColor.GRAY+"Koszt: "+ChatColor.GOLD+"5 poziomów doświadczenia");
        player.sendMessage(ChatColor.GRAY+"Aby potwierdzić, wpisz "+ChatColor.YELLOW+"/dq mapa potwierdz");
        player.sendMessage(ChatColor.DARK_GRAY+"Potwierdzenie wygaśnie za 30 sekund.");
    }

    private void confirmMapHint(Player player) {
        UUID uuid=player.getUniqueId();
        PendingMapHint pending=pendingMapHints.remove(uuid);
        if(pending==null || System.currentTimeMillis()>pending.expiresAt()) {
            player.sendMessage(ChatColor.RED+"Nie masz aktywnej prośby o wskazówkę. Użyj /dq mapa pomoc.");
            return;
        }
        if(player.getLevel()<5) {
            player.sendMessage(ChatColor.RED+"Potrzebujesz 5 poziomów doświadczenia, aby kupić wskazówkę.");
            return;
        }
        String worldName=null;
        int x=0,z=0;
        try(PreparedStatement query=db.prepareStatement(
                "SELECT world,x,z FROM dq_treasure_maps WHERE map_id=? AND owner_uuid=? AND claimed=0 AND discovered=0")) {
            query.setString(1,pending.mapId());
            query.setString(2,uuid.toString());
            try(ResultSet row=query.executeQuery()) {
                if(!row.next()) {
                    player.sendMessage(ChatColor.RED+"Ta mapa nie jest już aktywna. Poproś o nową wskazówkę.");
                    return;
                }
                worldName=row.getString("world");
                x=row.getInt("x");
                z=row.getInt("z");
            }
        } catch(SQLException ex) {
            getLogger().warning("Nie udało się odczytać celu wskazówki: "+ex.getMessage());
            player.sendMessage(ChatColor.RED+"Nie udało się pobrać wskazówki. Nie pobrano poziomów.");
            return;
        }
        if(!player.getWorld().getName().equals(worldName)) {
            player.sendMessage(ChatColor.YELLOW+"Musisz znajdować się w świecie, w którym ukryto tę skrzynię. Nie pobrano poziomów.");
            return;
        }
        player.setLevel(player.getLevel()-5);
        double dx=x-player.getLocation().getX();
        double dz=z-player.getLocation().getZ();
        double bearing=(Math.toDegrees(Math.atan2(dx,-dz))+360.0)%360.0;
        String[] directions={"północ","północny wschód","wschód","południowy wschód",
                "południe","południowy zachód","zachód","północny zachód"};
        String direction=directions[(int)Math.floor((bearing+22.5)/45.0)%8];
        int distance=(int)Math.round(Math.hypot(dx,dz));
        player.sendMessage(ChatColor.GOLD+"✦ KRZUSŁAW WĘDROWNY — WSKAZÓWKA ✦");
        player.sendMessage(ChatColor.YELLOW+"Skarb znajduje się na "+ChatColor.GOLD+direction
                +ChatColor.YELLOW+" od Ciebie.");
        player.sendMessage(ChatColor.YELLOW+"Do skrzyni pozostało około "+ChatColor.GOLD+distance
                +ChatColor.YELLOW+" bloków w linii prostej.");
        player.sendMessage(ChatColor.DARK_GRAY+"Wskazówka kosztowała 5 poziomów doświadczenia.");
        player.playSound(player.getLocation(),Sound.BLOCK_NOTE_BLOCK_CHIME,0.8f,1.2f);
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        // /zadania ma otwierać GUI; aliasy /dq i /dailyquest bez argumentów pokazują pomoc.
        if(command.getName().equalsIgnoreCase("zadania") && args.length==0) {
            if(sender instanceof Player player) open(player);
            else sender.sendMessage(ChatColor.YELLOW + "Tej komendy użyj w grze.");
            return true;
        }
        if(label.equalsIgnoreCase("dailyquest") && args.length==0) { showHelp(sender); return true; }
        if(args.length>0 && args[0].equalsIgnoreCase("mapa")) {
            if(!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW+"Tej komendy użyj w grze.");
                return true;
            }
            if(args.length==2 && args[1].equalsIgnoreCase("pomoc")) {
                requestMapHint(player);
            } else if(args.length==2 && args[1].equalsIgnoreCase("potwierdz")) {
                confirmMapHint(player);
            } else {
                player.sendMessage(ChatColor.YELLOW+"Użycie: /dq mapa pomoc lub /dq mapa potwierdz");
            }
            return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("kartograf")) {
            if(!sender.hasPermission("dailyquests.admin")) { sender.sendMessage(ChatColor.RED+"Brak uprawnień."); return true; }
            if(args.length==2 && args[1].equalsIgnoreCase("list")) {
                cartographerNpcSystem.listPurchasedMaps(sender);
            } else {
                sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq kartograf list");
            }
            return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("npc")) {
            if(!sender.hasPermission("dailyquests.admin")) { sender.sendMessage(ChatColor.RED+"Brak uprawnień."); return true; }
            if(args.length<2 || !Set.of("quest","skarbiec","kartograf","filip","snaf").contains(args[1].toLowerCase(Locale.ROOT))) {
                sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc <quest|skarbiec|kartograf|filip|snaf> [postaw|usun|tp|rename <nazwa>]"); return true;
            }
            String npcType=args[1].toLowerCase(Locale.ROOT);
            String action=args.length>=3?args[2].toLowerCase(Locale.ROOT):"postaw";
            if(action.equals("rename")) {
                if(args.length<4) { sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc "+npcType+" rename <nazwa>"); return true; }
                String npcName=String.join(" ",Arrays.copyOfRange(args,3,args.length));
                if(npcName.isBlank()) { sender.sendMessage(ChatColor.RED+"Nazwa nie może być pusta."); return true; }
                if(npcType.equals("quest")) npcSystem.renameNpc(npcName,sender::sendMessage);
                else if(npcType.equals("skarbiec")) crateNpcSystem.renameNpc(npcName,sender::sendMessage);
                else if(npcType.equals("kartograf")) cartographerNpcSystem.renameNpc(npcName,sender::sendMessage);
                else if(npcType.equals("filip")) filipNpcSystem.renameNpc(npcName,sender::sendMessage);
                else snafNpcSystem.renameNpc(npcName,sender::sendMessage);
            } else if(action.equals("postaw")) {
                if(!(sender instanceof Player p)) { sender.sendMessage("Tej komendy użyj w grze."); return true; }
                if(npcType.equals("quest")) npcSystem.spawnAt(p.getLocation(), sender::sendMessage);
                else if(npcType.equals("skarbiec")) { crateNpcSystem.spawnAt(p.getLocation()); sender.sendMessage(ChatColor.GREEN+"Postawiono Strażnika Skarbca."); }
                else if(npcType.equals("kartograf")) cartographerNpcSystem.spawnAt(p.getLocation(), sender::sendMessage);
                else if(npcType.equals("filip")) filipNpcSystem.spawnAt(p.getLocation(), sender::sendMessage);
                else snafNpcSystem.spawnAt(p.getLocation(), sender::sendMessage);
            } else if(action.equals("usun")) {
                if(npcType.equals("quest")) { npcSystem.removeNpc(); sender.sendMessage(ChatColor.GREEN+"Usunięto Michaiła Łomonosowa."); }
                else if(npcType.equals("skarbiec")) { crateNpcSystem.remove(); sender.sendMessage(ChatColor.GREEN+"Usunięto Strażnika Skarbca."); }
                else if(npcType.equals("kartograf")) { cartographerNpcSystem.removeNpc(); sender.sendMessage(ChatColor.GREEN+"Usunięto Krzusława Wędrownego."); }
                else if(npcType.equals("filip")) { filipNpcSystem.removeNpc(); sender.sendMessage(ChatColor.GREEN+"Usunięto Filipa Sznupacza."); }
                else { snafNpcSystem.removeNpc(); sender.sendMessage(ChatColor.GREEN+"Usunięto Snafa."); }
            } else if(action.equals("tp")) {
                if(!(sender instanceof Player p)) { sender.sendMessage("Tej komendy użyj w grze."); return true; }
                if(npcType.equals("quest")) npcSystem.teleportToNpc(p);
                else if(npcType.equals("skarbiec")) crateNpcSystem.teleport(p);
                else if(npcType.equals("kartograf")) cartographerNpcSystem.teleport(p);
                else if(npcType.equals("filip")) filipNpcSystem.teleport(p);
                else snafNpcSystem.teleport(p);
            } else sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc "+npcType+" [postaw|usun|tp|rename <nazwa>]");
            return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("help")) { showHelp(sender); return true; }
        if(args.length>0 && args[0].equalsIgnoreCase("top")){showLeaderboard(sender);return true;}
        if(args.length>0 && args[0].equalsIgnoreCase("weekly")){showWeekly(sender);return true;}
        if(args.length>0 && args[0].equalsIgnoreCase("gwiazdki")) {
            if(args.length==1) {
                if(sender instanceof Player p)crateSystem.showBalance(p);
                else sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq gwiazdki sprawdz <gracz>");
                return true;
            }
            if(!sender.hasPermission("dailyquests.admin")) {
                sender.sendMessage(ChatColor.RED+"Brak uprawnień.");
                return true;
            }
            String action=args[1].toLowerCase(Locale.ROOT);
            if(!Set.of("dodaj","usun","ustaw","sprawdz").contains(action) ||
                    args.length != (action.equals("sprawdz") ? 3 : 4)) {
                sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq gwiazdki <dodaj|usun|ustaw|sprawdz> <gracz> [ilość]");
                return true;
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer target=Bukkit.getOfflinePlayer(args[2]);
            if(!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage(ChatColor.RED+"Ten gracz nie był jeszcze na serwerze: "+args[2]);
                return true;
            }
            if(action.equals("sprawdz")) {
                sender.sendMessage(ChatColor.GOLD+"★ "+target.getName()+": "+crateSystem.getStars(target.getUniqueId())+" gwiazdek.");
                return true;
            }
            int amount;
            try {
                amount=Integer.parseInt(args[3]);
                if(amount<0) throw new NumberFormatException();
            } catch(NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED+"Podaj nieujemną liczbę całkowitą.");
                return true;
            }
            if(crateSystem.modifyStars(target.getUniqueId(),action,amount)) {
                int balance=crateSystem.getStars(target.getUniqueId());
                sender.sendMessage(ChatColor.GREEN+"Zmieniono gwiazdki gracza "+target.getName()+". Aktualne saldo: "+balance+" ★");
                if(target.isOnline() && target.getPlayer()!=null)
                    target.getPlayer().sendMessage(ChatColor.GOLD+"★ Administrator zmienił Twoje gwiazdki. Saldo: "+balance+" ★");
            } else sender.sendMessage(ChatColor.RED+"Nie udało się zmienić gwiazdek (sprawdź konsolę).");
            return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("roll")) {
            if(!sender.hasPermission("dailyquests.admin")){sender.sendMessage(ChatColor.RED+"Brak uprawnien.");return true;}
            rerollAll(sender);
            return true;
        }
        if(args.length>0 && (args[0].equalsIgnoreCase("nether") || args[0].equalsIgnoreCase("end"))) {
            if(!sender.hasPermission("dailyquests.admin")){sender.sendMessage(ChatColor.RED+"Brak uprawnien.");return true;}
            String dimension=args[0].equalsIgnoreCase("nether") ? "NETHER" : "THE_END";
            String configPath="dimensions."+(dimension.equals("NETHER")?"nether":"end")+"-enabled";
            if(args.length!=2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
                sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq "+(dimension.equals("NETHER")?"nether":"end")+" <on|off>");
                return true;
            }
            boolean enabled=args[1].equalsIgnoreCase("on");
            getConfig().set(configPath,enabled);
            saveConfig();
            if(!enabled) replaceDisabledDimensionTasks(dimension);
            cache.clear();
            for(Player online:Bukkit.getOnlinePlayers()) {
                ensureTasks(online);
                if(TITLE.equals(online.getOpenInventory().getTitle())) open(online);
            }
            sender.sendMessage(ChatColor.GREEN+"DailyQuests: zadania "+(dimension.equals("NETHER")?"Netheru":"Endu")+
                    (enabled?" włączone. Nowe losowania mogą je uwzględniać.":" wyłączone. Zastąpiono tylko zadania z tego wymiaru; pozostały postęp zachowano."));
            return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("reload")){
            if(!sender.hasPermission("dailyquests.admin")){sender.sendMessage(ChatColor.RED+"Brak uprawnien.");return true;}
            reloadConfig();reloadTaskPool();cache.clear();sender.sendMessage(ChatColor.GREEN+"DailyQuests: przeladowano konfigurację i pulę zadań.");return true;
        }
        if(args.length==0 || args[0].equalsIgnoreCase("help")) { showHelp(sender); return true; }
        sender.sendMessage(ChatColor.RED + "Nieznana komenda: /" + label + " " + String.join(" ", args));
        sender.sendMessage(ChatColor.GRAY + "Wpisz " + ChatColor.YELLOW + "/dq help" + ChatColor.GRAY + ", aby zobaczyć dostępne komendy.");
        showHelp(sender);
        return true;
    }
    private void replaceDisabledDimensionTasks(String dimension) {
        String day=today();
        ConfigurationSection section=pool();
        if(section==null) return;
        List<String> candidateIds=new ArrayList<>();
        for(String id:section.getKeys(false)) {
            Task candidate=loadTask(id);
            if(candidate!=null && !candidate.dimension.equals(dimension) && isTaskEnabled(candidate))
                candidateIds.add(id);
        }
        try(PreparedStatement players=db.prepareStatement("SELECT DISTINCT uuid FROM player_tasks WHERE day=?")) {
            players.setString(1,day);
            try(ResultSet playerRows=players.executeQuery()) {
                while(playerRows.next()) {
                    String uuid=playerRows.getString(1);
                    List<TaskSlot> replacements=new ArrayList<>();
                    Set<String> assigned=new HashSet<>();
                    try(PreparedStatement rows=db.prepareStatement("SELECT slot,task_id FROM player_tasks WHERE uuid=? AND day=? ORDER BY slot")) {
                        rows.setString(1,uuid); rows.setString(2,day);
                        try(ResultSet tasks=rows.executeQuery()) {
                            while(tasks.next()) {
                                int slot=tasks.getInt(1);
                                String taskId=tasks.getString(2);
                                assigned.add(taskId);
                                Task current=loadTask(taskId);
                                if(current!=null && current.dimension.equals(dimension))
                                    replacements.add(new TaskSlot(slot,taskId));
                            }
                        }
                    }
                    List<String> available=new ArrayList<>(candidateIds);
                    available.removeIf(assigned::contains);
                    Collections.shuffle(available);
                    int count=Math.min(replacements.size(),available.size());
                    for(int i=0;i<count;i++) {
                        TaskSlot slot=replacements.get(i);
                        String nextId=available.get(i);
                        try(PreparedStatement update=db.prepareStatement("UPDATE player_tasks SET task_id=?,progress=0,claimed=0 WHERE uuid=? AND day=? AND slot=?")) {
                            update.setString(1,nextId); update.setString(2,uuid); update.setString(3,day); update.setInt(4,slot.slot());
                            update.executeUpdate();
                        }
                        try(PreparedStatement history=db.prepareStatement("INSERT OR REPLACE INTO task_history(uuid,task_id,assigned_day) VALUES(?,?,?)")) {
                            history.setString(1,uuid); history.setString(2,nextId); history.setString(3,day);
                            history.executeUpdate();
                        }
                        assigned.add(nextId);
                    }
                    if(count<replacements.size())
                        getLogger().warning("Nie znaleziono wystarczającej liczby zamienników dla "+uuid+" ("+dimension+").");
                }
            }
        } catch(SQLException ex) {
            getLogger().severe("Nie udało się wymienić zadań "+dimension+": "+ex.getMessage());
        }
    }

    boolean isDimensionEnabled(String dimension) {
        String normalized = dimension == null ? "OVERWORLD" : dimension.toUpperCase(Locale.ROOT);
        if (normalized.equals("NETHER")) return getConfig().getBoolean("dimensions.nether-enabled", false);
        if (normalized.equals("END") || normalized.equals("THE_END")) return getConfig().getBoolean("dimensions.end-enabled", false);
        return true;
    }

    private boolean isTaskEnabled(Task task) {
        return task != null && isDimensionEnabled(task.dimension)
                && (!task.requiresNether || isDimensionEnabled("NETHER"));
    }

    private boolean dimensionMatches(Task t, World world) {
        if (!isTaskEnabled(t)) return false;
        String actual=world.getEnvironment().name();
        String required=t.dimension.equals("END") ? "THE_END" : t.dimension;
        return required.equals("OVERWORLD") ? actual.equals("NORMAL") : required.equals(actual);
    }

    private String weekKey(){java.time.temporal.WeekFields wf=java.time.temporal.WeekFields.ISO;LocalDate d=LocalDate.now(zone);return d.get(wf.weekBasedYear())+"-W"+String.format(Locale.ROOT,"%02d",d.get(wf.weekOfWeekBasedYear()));}
    private void incrementCompleted(UUID uuid){try(PreparedStatement p=db.prepareStatement("INSERT INTO player_stats(uuid,total_completed) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET total_completed=total_completed+1")){p.setString(1,uuid.toString());p.executeUpdate();}catch(SQLException e){getLogger().warning(e.getMessage());}}
    private void updateStreak(Player p) {
        String day = today();
        try (PreparedStatement q = db.prepareStatement("SELECT streak,last_streak_day FROM player_stats WHERE uuid=?")) {
            q.setString(1, p.getUniqueId().toString());
            try (ResultSet r = q.executeQuery()) {
                int streak = 0;
                String last = "";
                if (r.next()) { streak = r.getInt(1); last = r.getString(2); }
                if (day.equals(last)) return;
                String yesterday = LocalDate.now(zone).minusDays(1).toString();
                streak = last.equals(yesterday) ? streak + 1 : 1;
                try (PreparedStatement u = db.prepareStatement("INSERT INTO player_stats(uuid,streak,last_streak_day) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET streak=excluded.streak,last_streak_day=excluded.last_streak_day")) {
                    u.setString(1, p.getUniqueId().toString());
                    u.setInt(2, streak);
                    u.setString(3, day);
                    u.executeUpdate();
                }
                p.sendMessage(ChatColor.GOLD + "Seria ukończonych dni: " + ChatColor.YELLOW + streak + " ★");
                Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.WHITE + p.getName()
                        + ChatColor.GRAY + " ukończył wszystkie 5 zadań dziennych! "
                        + ChatColor.GOLD + "Seria: " + ChatColor.YELLOW + "dzień " + streak + " ★");
                if (streak % 7 == 0) {
                    p.giveExp(500);
                    p.sendMessage(ChatColor.LIGHT_PURPLE + "7-dniowa seria! Bonus: 500 XP.");
                }
            }
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }
    private record WeeklyChallenge(String type,String target,int amount,String title,String description,int xp,int emeralds,String dimension) {
        WeeklyChallenge(String type,String target,int amount,String title,String description,int xp,int emeralds) {
            this(type,target,amount,title,description,xp,emeralds,"OVERWORLD");
        }
    }
    private static final WeeklyChallenge[] WEEKLY_POOL = {
        new WeeklyChallenge("WALK","ANY",12000,"Wielka wyprawa","Przemierz 12 000 bloków pieszo",2500,12),
        new WeeklyChallenge("SWIM","ANY",2500,"Władca oceanów","Przepłyń 2 500 bloków",2200,10),
        new WeeklyChallenge("BREAK","DIAMOND_ORE",96,"Gorączka diamentów","Wykop 96 rud diamentu",2600,12),
        new WeeklyChallenge("KILL","ZOMBIE",180,"Pogromca nieumarłych","Pokonaj 180 zombie",2300,10),
        new WeeklyChallenge("KILL","SKELETON",150,"Łowca kości","Pokonaj 150 szkieletów",2300,10),
        new WeeklyChallenge("CRAFT","ANY",640,"Mistrz rzemiosła","Wytwórz 640 przedmiotów",2200,10),
        new WeeklyChallenge("FISH","ANY",80,"Głębinowy połów","Złów 80 ryb lub skarbów",2200,10),
        new WeeklyChallenge("ENCHANT","ANY",18,"Zaklinacz","Zaklnij 18 przedmiotów",2500,12),
        new WeeklyChallenge("BREED","ANY",45,"Hodowca","Rozmnoż 45 zwierząt",2100,10),
        new WeeklyChallenge("TRADE","ANY",100,"Kupiec tygodnia","Dokonaj 100 wymian z osadnikami",2300,10),
        new WeeklyChallenge("ANVIL","ANY",35,"Kowal","Połącz lub napraw 35 przedmiotów w kowadle",2400,11),
        new WeeklyChallenge("BREAK","ANCIENT_DEBRIS",24,"Relikt Netheru","Wykop 24 pradawne zgliszcza",3000,15,"NETHER")
    };
    private WeeklyChallenge weeklyChallenge() {
        long week=LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY).toEpochDay()/7L;
        int start=Math.floorMod(week,WEEKLY_POOL.length);
        for(int offset=0;offset<WEEKLY_POOL.length;offset++) {
            WeeklyChallenge candidate=WEEKLY_POOL[(start+offset)%WEEKLY_POOL.length];
            if(isDimensionEnabled(candidate.dimension())) return candidate;
        }
        return WEEKLY_POOL[0];
    }
    private String weeklyChallengeId(WeeklyChallenge challenge) {
        return challenge.type()+":"+challenge.target()+":"+challenge.title();
    }
    private int[] weeklyProgress(UUID uuid,WeeklyChallenge challenge) throws SQLException {
        try(PreparedStatement query=db.prepareStatement("SELECT progress,claimed,challenge_id FROM weekly_tasks WHERE uuid=? AND week=?")) {
            query.setString(1,uuid.toString()); query.setString(2,weekKey());
            try(ResultSet result=query.executeQuery()) {
                if(result.next() && weeklyChallengeId(challenge).equals(result.getString(3)))
                    return new int[]{result.getInt(1),result.getInt(2)};
            }
        }
        return new int[]{0,0};
    }
    private void updateWeekly(Player p,String type,String target,int amount) {
        if(amount<=0) return;
        WeeklyChallenge c=weeklyChallenge();
        if(!isDimensionEnabled(c.dimension()) || !c.type().equals(type)||(!c.target().equals("ANY")&&!c.target().equals(target))) return;
        String week = weekKey();
        int previous = 0;
        try {
            int[] before = weeklyProgress(p.getUniqueId(), c);
            previous = before[0];
        } catch (SQLException ex) {
            getLogger().warning("Błąd odczytu postępu weekly: " + ex.getMessage());
        }
        try(PreparedStatement q=db.prepareStatement(
                "INSERT INTO weekly_tasks(uuid,week,progress,claimed,challenge_id) VALUES(?,?,?,0,?) " +
                "ON CONFLICT(uuid,week) DO UPDATE SET " +
                "progress=CASE WHEN weekly_tasks.challenge_id=excluded.challenge_id THEN MIN("+c.amount()+",weekly_tasks.progress+excluded.progress) ELSE MIN("+c.amount()+",excluded.progress) END, " +
                "claimed=CASE WHEN weekly_tasks.challenge_id=excluded.challenge_id THEN weekly_tasks.claimed ELSE 0 END, " +
                "challenge_id=excluded.challenge_id " +
                "WHERE weekly_tasks.claimed=0 OR weekly_tasks.challenge_id<>excluded.challenge_id")) {
            q.setString(1,p.getUniqueId().toString());q.setString(2,week);q.setInt(3,amount);q.setString(4,weeklyChallengeId(c));q.executeUpdate();
            int[] after = weeklyProgress(p.getUniqueId(), c);
            if (previous < c.amount() && after[0] >= c.amount() && after[1] == 0) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + p.getName()
                        + ChatColor.GOLD + " ukończył wyzwanie weekendowe: " + ChatColor.YELLOW + c.title());
            }
        } catch(SQLException e) { getLogger().warning("Błąd postępu weekly: "+e.getMessage()); }
    }
    void openWeeklyPreview(Player player) {
        WeeklyChallenge challenge = weeklyChallenge();
        String week = weekKey();
        int progress = 0;
        boolean claimed = false;
        try {
            int[] state=weeklyProgress(player.getUniqueId(),challenge);
            progress=state[0]; claimed=state[1]==1;
        } catch (SQLException ex) {
            player.sendMessage(ChatColor.RED + "Nie udało się odczytać wyzwania tygodnia.");
            return;
        }

        String title = ChatColor.DARK_GRAY + "✦ WYZWANIE TYGODNIA ✦";
        Inventory inventory = Bukkit.createInventory(null, 27, title);
        ItemStack filler = make(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < 27; slot++) inventory.setItem(slot, filler);
        inventory.setItem(4, make(Material.CLOCK, ChatColor.GOLD + "✦ WYZWANIE TYGODNIA ✦",
                List.of(ChatColor.GRAY + "Aktualne wyzwanie: " + ChatColor.WHITE + week,
                        ChatColor.DARK_GRAY + "Dostępne bezpośrednio w /zadania")));
        int current = Math.min(progress, challenge.amount());
        inventory.setItem(11, make(Material.WRITABLE_BOOK, ChatColor.YELLOW + challenge.title(),
                List.of(ChatColor.GRAY + challenge.description(), "",
                        ChatColor.DARK_GRAY + "POSTĘP",
                        progressBar(current, challenge.amount()),
                        ChatColor.AQUA.toString() + current + ChatColor.GRAY + " / " + challenge.amount(),
                        claimed ? ChatColor.GREEN + "Nagroda odebrana"
                                : current >= challenge.amount() ? ChatColor.GOLD + "Wyzwanie ukończone"
                                : ChatColor.YELLOW + "W trakcie realizacji")));
        inventory.setItem(15, make(claimed ? Material.LIME_SHULKER_BOX : Material.CHEST, ChatColor.GOLD + "NAGRODA",
                List.of(ChatColor.GREEN + "+" + challenge.xp() + " XP",
                        ChatColor.GREEN + "+" + challenge.emeralds() + " szmaragdów",
                        ChatColor.YELLOW + "+10 ★",
                        "",
                        claimed ? ChatColor.GREEN + "Nagroda odebrana"
                                : current >= challenge.amount() ? ChatColor.YELLOW + "Kliknij, aby odebrać nagrodę"
                                : ChatColor.DARK_GRAY + "Ukończ wyzwanie, aby odebrać nagrodę.")));
        inventory.setItem(22, make(Material.BARRIER, ChatColor.RED + "ZAMKNIJ PODGLĄD",
                List.of(ChatColor.GRAY + "Powrót do gry.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onWeeklyPreviewClick(InventoryClickEvent event) {
        if (!(ChatColor.DARK_GRAY + "✦ WYZWANIE TYGODNIA ✦").equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() == 22) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() == 15) {
            claimWeeklyReward(player);
            if (player.isOnline()) openWeeklyPreview(player);
        }
    }

    private void claimWeeklyReward(Player player) {
        WeeklyChallenge challenge = weeklyChallenge();
        String week = weekKey();
        int[] state;
        try {
            state = weeklyProgress(player.getUniqueId(), challenge);
        } catch (SQLException ex) {
            player.sendMessage(ChatColor.RED + "Nie udało się odczytać wyzwania tygodnia.");
            getLogger().warning("Błąd odczytu nagrody tygodniowej: " + ex.getMessage());
            return;
        }

        int progress = Math.min(state[0], challenge.amount());
        if (state[1] == 1) {
            player.sendMessage(ChatColor.GREEN + "Nagroda za wyzwanie tygodnia została już odebrana.");
            return;
        }
        if (progress < challenge.amount()) {
            player.sendMessage(ChatColor.RED + "Najpierw ukończ wyzwanie tygodnia: "
                    + progress + "/" + challenge.amount() + ".");
            return;
        }

        try (PreparedStatement claim = db.prepareStatement(
                "UPDATE weekly_tasks SET claimed=1 WHERE uuid=? AND week=? AND challenge_id=? AND progress>=? AND claimed=0")) {
            claim.setString(1, player.getUniqueId().toString());
            claim.setString(2, week);
            claim.setString(3, weeklyChallengeId(challenge));
            claim.setInt(4, challenge.amount());
            if (claim.executeUpdate() != 1) {
                player.sendMessage(ChatColor.RED + "Nie udało się odebrać nagrody. Otwórz wyzwanie ponownie.");
                return;
            }
        } catch (SQLException ex) {
            player.sendMessage(ChatColor.RED + "Wystąpił błąd podczas odbierania nagrody.");
            getLogger().warning("Błąd zapisu odbioru nagrody tygodniowej: " + ex.getMessage());
            return;
        }

        player.giveExp(challenge.xp());
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                new ItemStack(Material.EMERALD, challenge.emeralds()));
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        crateSystem.awardStars(player, 10);
        player.sendMessage(ChatColor.GREEN + "Odebrano nagrodę za wyzwanie tygodnia: "
                + challenge.xp() + " XP, " + challenge.emeralds() + " szmaragdów i 10 ★.");
    }

    private void showWeekly(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz może sprawdzić wyzwanie.");
            return;
        }
        WeeklyChallenge challenge = weeklyChallenge();
        int progress;
        boolean claimed;
        try {
            int[] state = weeklyProgress(player.getUniqueId(), challenge);
            progress = state[0];
            claimed = state[1] == 1;
        } catch (SQLException ex) {
            player.sendMessage(ChatColor.RED + "Błąd odczytu wyzwania.");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "━━━━━━━━ WYZWANIE TYGODNIA ━━━━━━━━");
        player.sendMessage(ChatColor.YELLOW + challenge.title());
        player.sendMessage(ChatColor.GRAY + challenge.description() + ".");
        player.sendMessage(ChatColor.GRAY + "Postęp: " + ChatColor.AQUA
                + Math.min(progress, challenge.amount()) + "/" + challenge.amount());
        if (progress >= challenge.amount() && !claimed) {
            claimWeeklyReward(player);
        } else if (claimed) {
            player.sendMessage(ChatColor.GREEN + "Nagroda odebrana.");
        } else {
            player.sendMessage(ChatColor.GRAY + "Nagroda: " + ChatColor.GREEN
                    + challenge.xp() + " XP, " + challenge.emeralds() + " szmaragdów i 10 ★");
        }
    }

    private void showLeaderboard(CommandSender sender){sender.sendMessage(ChatColor.GOLD+"━━ RANKING DAILY QUESTS ━━");try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT uuid,total_completed,streak FROM player_stats ORDER BY total_completed DESC LIMIT 10")){int i=1;while(r.next()){OfflinePlayer op=Bukkit.getOfflinePlayer(UUID.fromString(r.getString(1)));sender.sendMessage(ChatColor.YELLOW+"#"+i+" "+ChatColor.WHITE+(op.getName()==null?"Gracz":op.getName())+ChatColor.GRAY+" — "+ChatColor.GREEN+r.getInt(2)+" zadań "+ChatColor.DARK_GRAY+"(seria: "+r.getInt(3)+")");i++;}if(i==1)sender.sendMessage(ChatColor.GRAY+"Brak wyników.");}catch(SQLException e){sender.sendMessage(ChatColor.RED+"Błąd rankingu.");}}

    private void open(Player p) {
        List<Task> tasks=ensureTasks(p);
        Inventory inv=Bukkit.createInventory(null,54,TITLE);
        ItemStack dark=make(Material.BLACK_STAINED_GLASS_PANE," ",List.of());
        ItemStack accent=make(Material.CYAN_STAINED_GLASS_PANE," ",List.of());
        for(int i=0;i<54;i++) inv.setItem(i,dark);
        for(int i=0;i<9;i++){inv.setItem(i,accent);inv.setItem(45+i,accent);}
        for(int row=1;row<=4;row++){inv.setItem(row*9,accent);inv.setItem(row*9+8,accent);}
        inv.setItem(4,make(Material.NETHER_STAR,ChatColor.GOLD+"✦  CODZIENNE WYZWANIA  ✦",
            List.of(ChatColor.GRAY+"Wykonuj zadania, zdobywaj nagrody i XP.",
                    ChatColor.DARK_GRAY+"━━━━━━━━━━━━━━━━━━━━",
                    ChatColor.YELLOW+"Reset: "+ChatColor.WHITE+today(),
                    ChatColor.GRAY+"Kliknij ukończone zadanie, aby odebrać nagrodę.")));
        WeeklyChallenge weekly = weeklyChallenge();
        int weeklyProgress = 0;
        boolean weeklyClaimed = false;
        try {
            int[] state=weeklyProgress(p.getUniqueId(),weekly);
            weeklyProgress=state[0]; weeklyClaimed=state[1]==1;
        } catch (SQLException ex) {
            getLogger().warning("Nie udało się odczytać podsumowania wyzwania tygodnia: " + ex.getMessage());
        }
        int weeklyCurrent = Math.min(weeklyProgress, weekly.amount());
        inv.setItem(31, make(Material.CLOCK, ChatColor.LIGHT_PURPLE + "✦ WYZWANIE TYGODNIA ✦",
                List.of(ChatColor.YELLOW + weekly.title(),
                        ChatColor.GRAY + weekly.description(),
                        "",
                        ChatColor.DARK_GRAY + "POSTĘP",
                        progressBar(weeklyCurrent, weekly.amount()),
                        ChatColor.AQUA.toString() + weeklyCurrent + ChatColor.GRAY + " / " + weekly.amount(),
                        weeklyClaimed ? ChatColor.GREEN + "✔ Nagroda odebrana"
                                : weeklyCurrent >= weekly.amount() ? ChatColor.GOLD + "★ Ukończone"
                                : ChatColor.YELLOW + "◷ W trakcie",
                        "",
                        ChatColor.GREEN + "Nagroda: " + weekly.xp() + " XP, " + weekly.emeralds() + " szmaragdów, 10 ★",
                        ChatColor.YELLOW + "Kliknij, aby zobaczyć szczegóły.")));
        inv.setItem(40,make(rerollMode.contains(p.getUniqueId())?Material.REDSTONE_BLOCK:Material.EXPERIENCE_BOTTLE,
            rerollMode.contains(p.getUniqueId())?ChatColor.RED+"Anuluj przerzut":ChatColor.LIGHT_PURPLE+"Przerzuć jedno zadanie",
            List.of(ChatColor.GRAY+"Koszt: "+ChatColor.YELLOW+"5 poziomów XP",
                    ChatColor.GRAY+"Kliknij, aby "+(rerollMode.contains(p.getUniqueId())?"wyłączyć":"włączyć")+" tryb przerzutu.",
                    ChatColor.DARK_GRAY+"Następnie kliknij wybrane zadanie.")));
        inv.setItem(49,make(Material.CLOCK,ChatColor.AQUA+"Postęp dnia",
            List.of(ChatColor.GRAY+"Ukończone: "+ChatColor.GREEN+tasks.stream().filter(t->t.progress>=t.amount).count()+ChatColor.GRAY+"/5",
                    ChatColor.GRAY+"Odebrane: "+ChatColor.YELLOW+tasks.stream().filter(t->t.claimed).count()+ChatColor.GRAY+"/5",
                    ChatColor.DARK_GRAY+"Bonus za komplet: "+ChatColor.GOLD+"250 XP")));
        int[] slots={20,21,22,23,24};
        for(int i=0;i<tasks.size()&&i<5;i++){
            Task t=tasks.get(i);
            Material icon=taskIcon(t);
            String status=t.claimed?ChatColor.GREEN+"✔ ODEBRANO":t.progress>=t.amount?ChatColor.GOLD+"★ GOTOWE — ODBIERZ!":ChatColor.YELLOW+"◷ W TRAKCIE";
            List<String> lore=new ArrayList<>();
            lore.add(ChatColor.GRAY+t.description);
            ChatColor tier=t.difficulty.equals("HARD")?ChatColor.RED:t.difficulty.equals("EASY")?ChatColor.GREEN:ChatColor.YELLOW;
            lore.add(tier+"Trudność: "+(t.difficulty.equals("HARD")?"TRUDNE":t.difficulty.equals("EASY")?"ŁATWE":"ŚREDNIE"));
            lore.add("");
            lore.add(ChatColor.DARK_GRAY+"POSTĘP");
            lore.add(progressBar(t.progress,t.amount));
            lore.add(ChatColor.WHITE.toString()+t.progress+ChatColor.GRAY+" / "+ChatColor.WHITE+t.amount);
            lore.add("");
            lore.add(ChatColor.DARK_GRAY+"NAGRODA");
            lore.add(ChatColor.GREEN+"✦ "+t.xp+" XP");
            if(!t.rewardItem.isBlank()) lore.add(ChatColor.AQUA+"✦ "+t.itemAmount+"x "+t.rewardItem);
            lore.add("");
            lore.add(status);
            ItemStack taskItem=make(t.claimed?Material.LIME_STAINED_GLASS_PANE:(t.progress>=t.amount?Material.GOLD_BLOCK:icon),ChatColor.WHITE+t.name,lore);
            Material rewardMaterial=material(t.rewardItem);
            if(rewardMaterial!=null && !t.rewardItem.isBlank()) {
                List<Component> translatedLore=new ArrayList<>();
                String rewardPrefix=ChatColor.AQUA+"✦ "+t.itemAmount+"x "+t.rewardItem;
                for(String line:lore) {
                    if(line.equals(rewardPrefix))
                        translatedLore.add(Component.text("✦ "+t.itemAmount+"x ",NamedTextColor.AQUA)
                                .append(Component.translatable(rewardMaterial.getTranslationKey())));
                    else translatedLore.add(LegacyComponentSerializer.legacySection().deserialize(line));
                }
                ItemMeta taskMeta=taskItem.getItemMeta();
                taskMeta.lore(translatedLore);
                taskItem.setItemMeta(taskMeta);
            }
            inv.setItem(slots[i],taskItem);
        }
        p.openInventory(inv);
    }
    private String progressBar(int progress,int amount){
        int filled=Math.min(10,(int)Math.floor(10.0*progress/Math.max(1,amount)));
        return ChatColor.GREEN+"▰".repeat(filled)+ChatColor.DARK_GRAY+"▰".repeat(10-filled);
    }
    private Material taskIcon(Task t){
        if(t.type.equals("KILL")) return switch(t.target){
            case "ZOMBIE" -> Material.ROTTEN_FLESH;
            case "SKELETON" -> Material.BONE;
            case "SPIDER" -> Material.SPIDER_EYE;
            case "CREEPER" -> Material.GUNPOWDER;
            default -> Material.IRON_SWORD;
        };
        if(t.type.equals("WALK")) return Material.LEATHER_BOOTS;
        if(t.type.equals("FISH")) return Material.FISHING_ROD;
        Material icon=material(t.target);
        return icon==null?Material.WRITABLE_BOOK:icon;
    }
    private ItemStack make(Material mat,String name,List<String> lore){
        ItemStack item=new ItemStack(mat);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(lore);item.setItemMeta(meta);return item;
    }
    private Material material(String name){try{return Material.valueOf(name);}catch(Exception e){return null;}}

    @EventHandler public void onJoin(PlayerJoinEvent e){ensureTasks(e.getPlayer());lastWalk.put(e.getPlayer().getUniqueId(),e.getPlayer().getLocation());}
    @EventHandler public void onQuit(PlayerQuitEvent e){UUID uuid=e.getPlayer().getUniqueId();lastWalk.remove(uuid);movementRemainder.remove(uuid);cache.remove(uuid);cacheDay.remove(uuid);rerollMode.remove(uuid);}
    private String normalizedBlockType(Material material) {
        String type = material.name();

        // Treat regular and deepslate ore blocks as the same ore for BREAK quests.
        // The quest counts the block-break event, not the dropped item
        // (e.g. diamond ore drops DIAMOND, not DIAMOND_ORE).
        if (type.startsWith("DEEPSLATE_") && type.endsWith("_ORE")) {
            type = type.substring("DEEPSLATE_".length());
        }

        // Kelp has a bottom block (KELP) and upper plant segments (KELP_PLANT).
        // Both represent harvested kelp for quest progress.
        return type.equals("KELP_PLANT") ? "KELP" : type;
    }

    private boolean isWoodLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    @EventHandler public void onWoodPlace(BlockPlaceEvent event) {
        if (event.isCancelled() || !isWoodLog(event.getBlockPlaced().getType())) return;
        Location location = event.getBlockPlaced().getLocation();
        try (PreparedStatement statement = db.prepareStatement(
                "INSERT OR REPLACE INTO placed_wood_blocks(world,x,y,z,material) VALUES(?,?,?,?,?)")) {
            statement.setString(1, location.getWorld().getUID().toString());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.setString(5, event.getBlockPlaced().getType().name());
            statement.executeUpdate();
        } catch (SQLException ex) {
            getLogger().warning("Nie udało się zapisać postawionego pnia: " + ex.getMessage());
        }
    }

    private boolean consumePlacedWoodMarker(Location location, Material brokenType) {
        String worldId = location.getWorld().getUID().toString();
        String storedMaterial = null;
        try (PreparedStatement query = db.prepareStatement(
                "SELECT material FROM placed_wood_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
            query.setString(1, worldId);
            query.setInt(2, location.getBlockX());
            query.setInt(3, location.getBlockY());
            query.setInt(4, location.getBlockZ());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) storedMaterial = result.getString(1);
            }
            if (storedMaterial == null) return false;

            try (PreparedStatement delete = db.prepareStatement(
                    "DELETE FROM placed_wood_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                delete.setString(1, worldId);
                delete.setInt(2, location.getBlockX());
                delete.setInt(3, location.getBlockY());
                delete.setInt(4, location.getBlockZ());
                delete.executeUpdate();
            }
            return storedMaterial.equals(brokenType.name());
        } catch (SQLException ex) {
            getLogger().warning("Nie udało się sprawdzić postawionego pnia: " + ex.getMessage());
            // Fail closed: do not award progress when the anti-exploit check fails.
            return true;
        }
    }

    private int countKelpSegments(Block brokenBlock) {
        int count = 0;
        int maxY = brokenBlock.getWorld().getMaxHeight();
        for (int y = brokenBlock.getY(); y < maxY; y++) {
            Material type = brokenBlock.getWorld().getBlockAt(
                    brokenBlock.getX(), y, brokenBlock.getZ()).getType();
            if (type != Material.KELP && type != Material.KELP_PLANT) break;
            count++;
        }
        return Math.max(1, count);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){
        if(e.isCancelled())return;
        Material brokenType = e.getBlock().getType();
        if (isWoodLog(brokenType) && consumePlacedWoodMarker(e.getBlock().getLocation(), brokenType)) return;
        String type=normalizedBlockType(brokenType);
        // Breaking one kelp segment causes the whole column above it to collapse.
        // Bukkit fires one BlockBreakEvent for the player-broken block, so count
        // every kelp segment that is about to be harvested.
        int amount = (brokenType == Material.KELP || brokenType == Material.KELP_PLANT)
                ? countKelpSegments(e.getBlock()) : 1;
        updateWeekly(e.getPlayer(),"BREAK",type,amount);
        for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("BREAK")&&dimensionMatches(t,e.getBlock().getWorld())
                &&(t.target.equals("ANY")||t.target.equals(type)
                ||(t.target.equals("KELP_PLANT")&&type.equals("KELP"))))update(e.getPlayer(),t,amount);
    }
    @EventHandler public void onKill(EntityDeathEvent e){
        Player p=e.getEntity().getKiller();if(p==null)return;
        String type=e.getEntityType().name();
        updateWeekly(p,"KILL",type,1);
        for(Task t:ensureTasks(p))if(t.type.equals("KILL")&&dimensionMatches(t,e.getEntity().getWorld())&&(t.target.equals("ANY")||t.target.equals(type)))update(p,t,1);
    }
    private long countCraftedItems(Player player, Material material) {
        long total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == material) total += cursor.getAmount();
        return total;
    }

    @EventHandler public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.isCancelled()) return;

        // CraftItemEvent is fired before Minecraft has completed the click.
        // Measure the real result on the next tick: this includes every item
        // produced by shift-click crafting, including ingredients pulled from
        // the player's inventory to refill the crafting grid.
        Material resultType = e.getRecipe().getResult().getType();
        long before = countCraftedItems(p, resultType);

        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline() || e.isCancelled()) return;

            long after = countCraftedItems(p, resultType);
            long crafted = Math.max(0L, after - before);
            if (crafted <= 0) return;

            int count = (int) Math.min(Integer.MAX_VALUE, crafted);
            updateCraftProgress(p, resultType, count);
        });
    }


    private void updateCraftProgress(Player player, Material result, int amount) {
        if (amount <= 0) return;
        String type = result.name();
        updateWeekly(player, "CRAFT", type, amount);
        for (Task task : ensureTasks(player)) {
            if (task.type.equals("CRAFT") && (task.target.equals("ANY") || task.target.equals(type))) {
                update(player, task, amount);
            }
        }
    }

    @EventHandler
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        updateCraftProgress(event.getPlayer(), event.getItemType(), event.getItemAmount());
    }

    @EventHandler
    public void onCampfireTake(PlayerInteractEvent event) {
        if (event.isCancelled() || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Material blockType = event.getClickedBlock().getType();
        if (blockType != Material.CAMPFIRE && blockType != Material.SOUL_CAMPFIRE) return;
        if (!(event.getClickedBlock().getState() instanceof org.bukkit.block.Campfire campfire)) return;

        Map<Integer, CampfireFood> readyFood = new HashMap<>();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack input = campfire.getItem(slot);
            if (input == null || input.getType().isAir()) continue;
            if (campfire.getCookTime(slot) < campfire.getCookTimeTotal(slot)) continue;
            Material result = campfireResult(input.getType());
            if (result != null) readyFood.put(slot, new CampfireFood(result, input.getAmount()));
        }
        if (readyFood.isEmpty()) return;

        Player player = event.getPlayer();
        org.bukkit.block.Block campfireBlock = event.getClickedBlock();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !campfireBlock.getType().equals(blockType)) return;
            if (!(campfireBlock.getState() instanceof org.bukkit.block.Campfire after)) return;
            for (Map.Entry<Integer, CampfireFood> entry : readyFood.entrySet()) {
                int slot = entry.getKey(); CampfireFood food = entry.getValue();
                ItemStack remaining = after.getItem(slot);
                int remainingAmount = remaining != null && remaining.getType() != Material.AIR ? remaining.getAmount() : 0;
                int removed = Math.max(0, food.amount() - remainingAmount);
                if (removed > 0) updateCraftProgress(player, food.result(), removed);
            }
        });
    }

    private Material campfireResult(Material input) {
        return switch (input) {
            case BEEF -> Material.COOKED_BEEF;
            case PORKCHOP -> Material.COOKED_PORKCHOP;
            case CHICKEN -> Material.COOKED_CHICKEN;
            case MUTTON -> Material.COOKED_MUTTON;
            case RABBIT -> Material.COOKED_RABBIT;
            case COD -> Material.COOKED_COD;
            case SALMON -> Material.COOKED_SALMON;
            case POTATO -> Material.BAKED_POTATO;
            case KELP -> Material.DRIED_KELP;
            default -> null;
        };
    }
    @EventHandler public void onFish(PlayerFishEvent e){
        if(e.getState()!=PlayerFishEvent.State.CAUGHT_FISH)return;
        if (!(e.getCaught() instanceof org.bukkit.entity.Item caught)) return;
        String caughtType = caught.getItemStack().getType().name();
        updateWeekly(e.getPlayer(),"FISH",caughtType,1);
        for(Task t:ensureTasks(e.getPlayer()))
            if(t.type.equals("FISH") && dimensionMatches(t,e.getPlayer().getWorld())
                    && (t.target.equals("ANY") || t.target.equals(caughtType))) update(e.getPlayer(),t,1);
    }
    
    @EventHandler public void onConsume(PlayerItemConsumeEvent e){updateWeekly(e.getPlayer(),"EAT",e.getItem().getType().name(),1);for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("EAT")&&(t.target.equals("ANY")||t.target.equals(e.getItem().getType().name())))update(e.getPlayer(),t,1);}
    @EventHandler public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent e){updateWeekly(e.getEnchanter(),"ENCHANT","ANY",1);for(Task t:ensureTasks(e.getEnchanter()))if(t.type.equals("ENCHANT"))update(e.getEnchanter(),t,1);}
    @EventHandler public void onBreed(org.bukkit.event.entity.EntityBreedEvent e){if(e.getBreeder() instanceof Player p){updateWeekly(p,"BREED",e.getEntity().getType().name(),1);for(Task t:ensureTasks(p))if(t.type.equals("BREED")&&(t.target.equals("ANY")||t.target.equals(e.getEntity().getType().name())))update(p,t,1);}}
    @EventHandler public void onAnvil(InventoryClickEvent e){if(e.isCancelled()||!(e.getWhoClicked() instanceof Player p)||e.getRawSlot()!=2)return;if(e.getView().getType()==InventoryType.ANVIL){updateWeekly(p,"ANVIL","ANY",1);for(Task t:ensureTasks(p))if(t.type.equals("ANVIL"))update(p,t,1);}if(e.getView().getType()==InventoryType.MERCHANT){updateWeekly(p,"TRADE","ANY",1);for(Task t:ensureTasks(p))if(t.type.equals("TRADE"))update(p,t,1);}}
    @EventHandler public void onMove(PlayerMoveEvent e){
        if(e.getTo()==null)return;
        Player player=e.getPlayer();
        Location from=e.getFrom(),to=e.getTo();
        UUID uuid=player.getUniqueId();
        if(from.getWorld()!=to.getWorld()) {
            lastWalk.put(uuid,to.clone());
            movementRemainder.remove(uuid);
            return;
        }
        if(player.isInsideVehicle()||player.isFlying()) {
            lastWalk.put(uuid,to.clone());
            movementRemainder.remove(uuid);
            return;
        }
        Location previous=lastWalk.put(uuid,to.clone());
        if(previous==null||previous.getWorld()!=to.getWorld())return;
        double segment=Math.hypot(to.getX()-previous.getX(),to.getZ()-previous.getZ());
        if(segment<0.0001)return;
        double accumulated=movementRemainder.getOrDefault(uuid,0.0)+segment;
        int blocks=(int)Math.floor(accumulated);
        movementRemainder.put(uuid,accumulated-blocks);
        if(blocks<=0)return;
        boolean swimming=player.isSwimming();
        String movementType=swimming?"SWIM":"WALK";
        updateWeekly(player,movementType,"ANY",blocks);
        for(Task task:ensureTasks(player)) {
            if(task.type.equals(movementType)&&dimensionMatches(task,to.getWorld())) update(player,task,blocks);
        }
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;
        int slot=e.getRawSlot();
        if (slot == 31) {
            WeeklyChallenge challenge = weeklyChallenge();
            try {
                int[] state = weeklyProgress(p.getUniqueId(), challenge);
                if (state[0] >= challenge.amount() && state[1] == 0) {
                    claimWeeklyReward(p);
                    open(p);
                } else {
                    openWeeklyPreview(p);
                }
            } catch (SQLException ex) {
                p.sendMessage(ChatColor.RED + "Nie udało się odczytać wyzwania tygodnia.");
            }
            return;
        }
        if(slot==40) {
            if(!rerollMode.add(p.getUniqueId())) rerollMode.remove(p.getUniqueId());
            p.sendMessage(rerollMode.contains(p.getUniqueId())
                    ?ChatColor.LIGHT_PURPLE+"Tryb przerzutu aktywny. Kliknij zadanie, które chcesz wylosować ponownie."
                    :ChatColor.GRAY+"Wyłączono tryb przerzutu.");
            open(p);
            return;
        }
        if(slot<20||slot>24)return;
        List<Task> tasks=ensureTasks(p);int index=slot-20;if(index>=tasks.size())return;
        Task t=tasks.get(index);
        if(rerollMode.remove(p.getUniqueId())) {
            if(t.claimed) {
                p.sendMessage(ChatColor.RED+"Nie możesz przerzucić zadania, którego nagrodę już odebrałeś.");
                open(p);
                return;
            }
            if(p.getLevel()<5) {
                p.sendMessage(ChatColor.RED+"Potrzebujesz 5 poziomów doświadczenia, aby przerzucić zadanie.");
                open(p);
                return;
            }
            Set<String> assignedToday=tasksAssignedToday(p.getUniqueId());
            // Also protect against task assignments made before daily history was introduced.
            for(Task existing:tasks) {
                assignedToday.add(existing.id);
                recordTaskHistory(p.getUniqueId(),existing.id,today());
            }
            ConfigurationSection available=pool();
            List<Task> candidates=new ArrayList<>();
            if(available!=null) for(String id:available.getKeys(false)) {
                if(assignedToday.contains(id)) continue;
                Task candidate=loadTask(id);
                if(candidate!=null && isTaskEnabled(candidate)) candidates.add(candidate);
            }
            if(candidates.isEmpty()) {
                p.sendMessage(ChatColor.RED+"Brak innych dostępnych zadań do wylosowania.");
                open(p);
                return;
            }
            p.setLevel(p.getLevel()-5);
            Task next=candidates.get(random.nextInt(candidates.size()));
            tasks.set(index,next);
            cache.put(p.getUniqueId(),tasks);
            cacheDay.put(p.getUniqueId(),today());
            save(p.getUniqueId(),index,next);
            recordTaskHistory(p.getUniqueId(),next.id,today());
            p.playSound(p.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.8f,1.4f);
            p.sendMessage(ChatColor.GREEN+"Wylosowano nowe zadanie za 5 poziomów XP: "+ChatColor.YELLOW+ChatColor.stripColor(next.name));
            open(p);
            return;
        }
        if(t.claimed){p.sendMessage(ChatColor.GRAY+"Ta nagroda zostala juz odebrana.");return;}
        if(t.progress<t.amount){p.sendMessage(ChatColor.RED+"To zadanie nie jest jeszcze ukonczone.");return;}
        t.claimed=true;save(p.getUniqueId(),index,t);
        if(t.xp>0)p.giveExp(t.xp);
        incrementCompleted(p.getUniqueId());
        Material reward=material(t.rewardItem);
        if(reward!=null){ItemStack item=new ItemStack(reward,t.itemAmount);Map<Integer,ItemStack> overflow=p.getInventory().addItem(item);overflow.values().forEach(it->p.getWorld().dropItemNaturally(p.getLocation(),it));}
        p.sendMessage(ChatColor.GREEN+"Odebrano nagrode za: "+ChatColor.RESET+t.name);
        boolean all=tasks.size()==5&&tasks.stream().allMatch(x->x.claimed);
        if(all){p.giveExp(250);updateStreak(p);crateSystem.awardStars(p,5);p.sendMessage(ChatColor.GOLD+"Ukonczyles wszystkie 5 zadan! Bonus: 250 XP i +5 gwiazdek do skrzynki.");}
        open(p);
    }
    @EventHandler public void onDrag(InventoryDragEvent e){if(TITLE.equals(e.getView().getTitle()))e.setCancelled(true);}
}
