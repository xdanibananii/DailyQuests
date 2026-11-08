package pl.dailyquests;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
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
    private org.bukkit.configuration.file.YamlConfiguration tasksConfig;
    private final Map<UUID, Location> lastWalk = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Set<UUID> rerollMode = ConcurrentHashMap.newKeySet();
    private static final String TITLE = ChatColor.DARK_GRAY + "Codzienne zadania";
    private CrateSystem crateSystem;
    private QuestNpcSystem npcSystem;
    private CrateNpcSystem crateNpcSystem;

    private static final class Task {
        String id, name, description, type, target, rewardItem, dimension, difficulty;
        int amount, xp, itemAmount, progress;
        boolean claimed;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("tasks.yml", false);
        File poolMarker = new File(getDataFolder(), "quest-pool-v4.done");
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
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS player_tasks (uuid TEXT NOT NULL, day TEXT NOT NULL, slot INTEGER NOT NULL, task_id TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,day,slot))");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY, streak INTEGER NOT NULL DEFAULT 0, last_streak_day TEXT NOT NULL DEFAULT '', total_completed INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS weekly_tasks (uuid TEXT NOT NULL, week TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,week))");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS task_history (uuid TEXT NOT NULL, task_id TEXT NOT NULL, assigned_day TEXT NOT NULL, PRIMARY KEY(uuid,task_id,assigned_day))");
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
        getServer().getPluginManager().registerEvents(npcSystem, this);
        getServer().getPluginManager().registerEvents(crateNpcSystem, this);
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
        String day = today();
        try (PreparedStatement q = db.prepareStatement("SELECT task_id,progress,claimed FROM player_tasks WHERE uuid=? AND day=? ORDER BY slot")) {
            q.setString(1,p.getUniqueId().toString()); q.setString(2,day);
            try (ResultSet rs=q.executeQuery()) {
                List<Task> found=new ArrayList<>();
                while(rs.next()) { Task t=loadTask(rs.getString(1)); if(t!=null){t.progress=rs.getInt(2);t.claimed=rs.getInt(3)==1;found.add(t);} }
                if(found.size()==5 && found.stream().allMatch(this::isTaskEnabled)){cache.put(p.getUniqueId(),found);return found;}
            }
        } catch(SQLException e){getLogger().warning(e.getMessage());}
        ConfigurationSection sec=pool();
        if(sec==null || sec.getKeys(false).isEmpty()) return new ArrayList<>();
        Set<String> recent=new HashSet<>();
        try(PreparedStatement h=db.prepareStatement("SELECT task_id FROM task_history WHERE uuid=? AND assigned_day>=date(?, '-30 days')")){h.setString(1,p.getUniqueId().toString());h.setString(2,day);try(ResultSet r=h.executeQuery()){while(r.next())recent.add(r.getString(1));}}catch(SQLException ignored){}
        List<String> ids=new ArrayList<>(sec.getKeys(false));
        ids.removeIf(id -> { ConfigurationSection c=sec.getConfigurationSection(id); String d=c==null?"OVERWORLD":c.getString("dimension","OVERWORLD"); return !isDimensionEnabled(d); });
        Collections.shuffle(ids);
        List<Task> chosen=new ArrayList<>();
        for(String id:ids){if(recent.contains(id))continue;Task t=loadTask(id);if(t!=null){chosen.add(t);if(chosen.size()==5)break;}}
        if(chosen.size()<5)for(String id:ids){if(chosen.stream().anyMatch(t->t.id.equals(id)))continue;Task t=loadTask(id);if(t!=null){chosen.add(t);if(chosen.size()==5)break;}}
        try(PreparedStatement del=db.prepareStatement("DELETE FROM player_tasks WHERE uuid=?")) {
            del.setString(1,p.getUniqueId().toString());del.executeUpdate();
        }catch(SQLException ignored){}
        for(int i=0;i<chosen.size();i++){save(p.getUniqueId(),i,chosen.get(i));try(PreparedStatement h=db.prepareStatement("INSERT OR REPLACE INTO task_history(uuid,task_id,assigned_day) VALUES(?,?,?)")){h.setString(1,p.getUniqueId().toString());h.setString(2,chosen.get(i).id);h.setString(3,day);h.executeUpdate();}catch(SQLException ignored){}}
        cache.put(p.getUniqueId(),chosen); return chosen;
    }

    private Task loadTask(String id) {
        ConfigurationSection s=pool(); if(s==null || !s.isConfigurationSection(id)) return null;
        ConfigurationSection c=s.getConfigurationSection(id); Task t=new Task();
        t.id=id;t.name=color(c.getString("name",id));t.description=color(c.getString("description",""));
        t.type=c.getString("type","").toUpperCase(Locale.ROOT);t.target=c.getString("target","ANY").toUpperCase(Locale.ROOT);t.dimension=c.getString("dimension","OVERWORLD").toUpperCase(Locale.ROOT);t.difficulty=c.getString("difficulty","MEDIUM").toUpperCase(Locale.ROOT);
        t.amount=Math.max(1,c.getInt("amount",1));t.xp=Math.max(0,c.getInt("reward-xp",0));
        t.rewardItem=c.getString("reward-item","").toUpperCase(Locale.ROOT);t.itemAmount=Math.max(1,c.getInt("reward-item-amount",1));return t;
    }

    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
    private void save(UUID uuid,int slot,Task t) {
        try(PreparedStatement p=db.prepareStatement("INSERT OR REPLACE INTO player_tasks(uuid,day,slot,task_id,progress,claimed) VALUES(?,?,?,?,?,?)")){
            p.setString(1,uuid.toString());p.setString(2,today());p.setInt(3,slot);p.setString(4,t.id);p.setInt(5,t.progress);p.setInt(6,t.claimed?1:0);p.executeUpdate();
        }catch(SQLException e){getLogger().warning("Blad zapisu zadania: "+e.getMessage());}
    }
    private void update(Player p,Task t,int delta) {
        if(delta<=0 || !isTaskEnabled(t))return;
        List<Task> list=ensureTasks(p);
        for(int i=0;i<list.size();i++) if(list.get(i).id.equals(t.id)){
            Task actual=list.get(i); if(actual.claimed)return;
            int before=actual.progress;
            actual.progress=Math.min(actual.amount,actual.progress+delta);
            save(p.getUniqueId(),i,actual);
            if(before<actual.amount && actual.progress>=actual.amount){
                p.playSound(p.getLocation(),Sound.UI_TOAST_CHALLENGE_COMPLETE,0.8f,1.0f);
                p.sendMessage(ChatColor.GOLD+"★ "+ChatColor.GREEN+"Ukończono zadanie: "+ChatColor.WHITE+ChatColor.stripColor(actual.name)+ChatColor.GRAY+" — nagroda czeka w "+ChatColor.YELLOW+"/zadania");
            }
            break;
        }
    }

    private void rerollAll(CommandSender sender) {
        try (PreparedStatement st=db.prepareStatement("DELETE FROM player_tasks WHERE day=?")) {
            st.setString(1,today());
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
        sender.sendMessage(ChatColor.GRAY + "Kontrakty dzienne znajdziesz u Michaiła Łomonosowa.");
        sender.sendMessage(ChatColor.GRAY + "Losowanie nagród za gwiazdki znajdziesz u Strażnika Skarbca.");
        if (sender.hasPermission("dailyquests.admin")) {
            sender.sendMessage(ChatColor.DARK_AQUA + "━━ KOMENDY ADMINISTRATORA ━━");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc quest [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Michaiłem");
            sender.sendMessage(ChatColor.YELLOW + "/dq npc skarbiec [postaw|usun|tp|rename <nazwa>]" + ChatColor.GRAY + " — zarządzanie Strażnikiem Skarbca");
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
            suggestions.addAll(List.of("help", "top", "weekly", "gwiazdki"));
            if (admin) suggestions.addAll(List.of("npc", "roll", "nether", "end", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc") && admin) {
            suggestions.addAll(List.of("quest", "skarbiec"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("npc") && admin
                && (args[1].equalsIgnoreCase("quest") || args[1].equalsIgnoreCase("skarbiec"))) {
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

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        // /zadania ma otwierać GUI; aliasy /dq i /dailyquest bez argumentów pokazują pomoc.
        if(command.getName().equalsIgnoreCase("zadania") && args.length==0) {
            if(sender instanceof Player player) open(player);
            else sender.sendMessage(ChatColor.YELLOW + "Tej komendy użyj w grze.");
            return true;
        }
        if(label.equalsIgnoreCase("dailyquest") && args.length==0) { showHelp(sender); return true; }
        if(args.length>0 && args[0].equalsIgnoreCase("npc")) {
            if(!sender.hasPermission("dailyquests.admin")) { sender.sendMessage(ChatColor.RED+"Brak uprawnień."); return true; }
            if(args.length<2 || (!args[1].equalsIgnoreCase("quest") && !args[1].equalsIgnoreCase("skarbiec"))) {
                sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc <quest|skarbiec> [postaw|usun|tp|rename <nazwa>]"); return true;
            }
            boolean questNpc=args[1].equalsIgnoreCase("quest");
            String action=args.length>=3?args[2].toLowerCase(Locale.ROOT):"postaw";
            if(action.equals("rename")) {
                if(args.length<4) { sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc "+(questNpc?"quest":"skarbiec")+" rename <nazwa>"); return true; }
                String npcName=String.join(" ",Arrays.copyOfRange(args,3,args.length));
                if(npcName.isBlank()) { sender.sendMessage(ChatColor.RED+"Nazwa nie może być pusta."); return true; }
                if(questNpc) npcSystem.renameNpc(npcName,sender::sendMessage);
                else crateNpcSystem.renameNpc(npcName,sender::sendMessage);
            } else if(action.equals("postaw")) {
                if(!(sender instanceof Player p)) { sender.sendMessage("Tej komendy użyj w grze."); return true; }
                if(questNpc) npcSystem.spawnAt(p.getLocation(), sender::sendMessage);
                else { crateNpcSystem.spawnAt(p.getLocation()); sender.sendMessage(ChatColor.GREEN+"Postawiono Strażnika Skarbca."); }
            } else if(action.equals("usun")) {
                if(questNpc) { npcSystem.removeNpc(); sender.sendMessage(ChatColor.GREEN+"Usunięto Michaiła Łomonosowa."); }
                else { crateNpcSystem.remove(); sender.sendMessage(ChatColor.GREEN+"Usunięto Strażnika Skarbca."); }
            } else if(action.equals("tp")) {
                if(!(sender instanceof Player p)) { sender.sendMessage("Tej komendy użyj w grze."); return true; }
                if(questNpc) npcSystem.teleportToNpc(p); else crateNpcSystem.teleport(p);
            } else sender.sendMessage(ChatColor.YELLOW+"Użycie: /dq npc <quest|skarbiec> [postaw|usun|tp|rename <nazwa>]");
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
            String dimension=args[0].toLowerCase(Locale.ROOT), path="dimensions."+dimension+"-enabled";
            boolean enabled=!(args.length>1 && args[1].equalsIgnoreCase("off"));
            getConfig().set(path,enabled);saveConfig();cache.clear();
            try(Statement st=db.createStatement()){st.executeUpdate("DELETE FROM player_tasks WHERE day='"+today()+"'");}catch(SQLException ex){getLogger().warning(ex.getMessage());}
            for(Player online:Bukkit.getOnlinePlayers()) ensureTasks(online);
            sender.sendMessage(ChatColor.GREEN+"DailyQuests: zadania z wymiaru "+dimension.toUpperCase(Locale.ROOT)+(enabled?" aktywowane.":" wylaczone.")+" Zadania dzienne zostaly wylosowane ponownie.");
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
    private boolean isDimensionEnabled(String dimension) {
        String normalized = dimension == null ? "OVERWORLD" : dimension.toUpperCase(Locale.ROOT);
        if (normalized.equals("NETHER")) return getConfig().getBoolean("dimensions.nether-enabled", false);
        if (normalized.equals("END") || normalized.equals("THE_END")) return getConfig().getBoolean("dimensions.end-enabled", false);
        return true;
    }

    private boolean isTaskEnabled(Task task) {
        return task != null && isDimensionEnabled(task.dimension);
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
                if (streak % 7 == 0) {
                    p.giveExp(500);
                    p.sendMessage(ChatColor.LIGHT_PURPLE + "7-dniowa seria! Bonus: 500 XP.");
                }
            }
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }
    private record WeeklyChallenge(String type,String target,int amount,String title,String description,int xp,int emeralds) {}
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
        new WeeklyChallenge("BREAK","ANCIENT_DEBRIS",24,"Relikt Netheru","Wykop 24 pradawne zgliszcza",3000,15)
    };
    private WeeklyChallenge weeklyChallenge() {
        long week=LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY).toEpochDay()/7L;
        return WEEKLY_POOL[Math.floorMod(week,WEEKLY_POOL.length)];
    }
    private void updateWeekly(Player p,String type,String target,int amount) {
        if(amount<=0) return;
        WeeklyChallenge c=weeklyChallenge();
        if(!c.type().equals(type)||(!c.target().equals("ANY")&&!c.target().equals(target))) return;
        try(PreparedStatement q=db.prepareStatement("INSERT INTO weekly_tasks(uuid,week,progress,claimed) VALUES(?,?,?,0) ON CONFLICT(uuid,week) DO UPDATE SET progress=MIN("+c.amount()+",progress+excluded.progress) WHERE claimed=0")) {
            q.setString(1,p.getUniqueId().toString());q.setString(2,weekKey());q.setInt(3,amount);q.executeUpdate();
        } catch(SQLException e) { getLogger().warning("Błąd postępu weekly: "+e.getMessage()); }
    }
    void openWeeklyPreview(Player player) {
        WeeklyChallenge challenge = weeklyChallenge();
        String week = weekKey();
        int progress = 0;
        boolean claimed = false;
        try (PreparedStatement query = db.prepareStatement(
                "SELECT progress,claimed FROM weekly_tasks WHERE uuid=? AND week=?")) {
            query.setString(1, player.getUniqueId().toString());
            query.setString(2, week);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    progress = result.getInt(1);
                    claimed = result.getInt(2) == 1;
                }
            }
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
        inventory.setItem(15, make(Material.CHEST, ChatColor.GOLD + "NAGRODA",
                List.of(ChatColor.GREEN + "+" + challenge.xp() + " XP",
                        ChatColor.GREEN + "+" + challenge.emeralds() + " szmaragdów",
                        ChatColor.YELLOW + "+10 ★",
                        "",
                        ChatColor.DARK_GRAY + "Nagroda odbierana automatycznie",
                        ChatColor.DARK_GRAY + "po ukończeniu wyzwania.")));
        inventory.setItem(22, make(Material.BARRIER, ChatColor.RED + "ZAMKNIJ PODGLĄD",
                List.of(ChatColor.GRAY + "Powrót do gry.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onWeeklyPreviewClick(InventoryClickEvent event) {
        if (!(ChatColor.DARK_GRAY + "✦ WYZWANIE TYGODNIA ✦").equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getRawSlot() == 22) {
            player.closeInventory();
        }
    }

    private void showWeekly(CommandSender sender) {
        if(!(sender instanceof Player p)){sender.sendMessage("Tylko gracz może sprawdzić wyzwanie.");return;}
        WeeklyChallenge c=weeklyChallenge();String week=weekKey();int progress=0;boolean claimed=false;
        try(PreparedStatement q=db.prepareStatement("SELECT progress,claimed FROM weekly_tasks WHERE uuid=? AND week=?")) {
            q.setString(1,p.getUniqueId().toString());q.setString(2,week);
            try(ResultSet r=q.executeQuery()){if(r.next()){progress=r.getInt(1);claimed=r.getInt(2)==1;}}
        } catch(SQLException e){p.sendMessage(ChatColor.RED+"Błąd odczytu wyzwania.");return;}
        p.sendMessage(ChatColor.GOLD+"━━━━━━━━ WYZWANIE TYGODNIA ━━━━━━━━");
        p.sendMessage(ChatColor.YELLOW+c.title());p.sendMessage(ChatColor.GRAY+c.description()+".");
        p.sendMessage(ChatColor.GRAY+"Postęp: "+ChatColor.AQUA+Math.min(progress,c.amount())+"/"+c.amount());
        if(progress>=c.amount()&&!claimed) {
            try(PreparedStatement u=db.prepareStatement("INSERT INTO weekly_tasks(uuid,week,progress,claimed) VALUES(?,?,?,1) ON CONFLICT(uuid,week) DO UPDATE SET claimed=1")) {
                u.setString(1,p.getUniqueId().toString());u.setString(2,week);u.setInt(3,c.amount());u.executeUpdate();
            } catch(SQLException ignored) {}
            p.giveExp(c.xp());p.getInventory().addItem(new ItemStack(Material.EMERALD,c.emeralds()));crateSystem.awardStars(p,10);
            p.sendMessage(ChatColor.GREEN+"Wyzwanie ukończone! Nagroda: "+c.xp()+" XP, "+c.emeralds()+" szmaragdów i 10 ★.");
        } else if(claimed)p.sendMessage(ChatColor.GREEN+"Nagroda odebrana.");
        else p.sendMessage(ChatColor.GRAY+"Nagroda: "+ChatColor.GREEN+c.xp()+" XP, "+c.emeralds()+" szmaragdów i 10 ★");
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
        try (PreparedStatement q = db.prepareStatement("SELECT progress,claimed FROM weekly_tasks WHERE uuid=? AND week=?")) {
            q.setString(1, p.getUniqueId().toString());
            q.setString(2, weekKey());
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) { weeklyProgress = rs.getInt(1); weeklyClaimed = rs.getInt(2) == 1; }
            }
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
            if(!t.rewardItem.isBlank()) lore.add(ChatColor.AQUA+"✦ "+t.itemAmount+"x "+pretty(t.rewardItem));
            lore.add("");
            lore.add(status);
            inv.setItem(slots[i],make(t.claimed?Material.LIME_STAINED_GLASS_PANE:(t.progress>=t.amount?Material.GOLD_BLOCK:icon),ChatColor.WHITE+t.name,lore));
        }
        p.openInventory(inv);
    }
    private String progressBar(int progress,int amount){
        int filled=Math.min(10,(int)Math.floor(10.0*progress/Math.max(1,amount)));
        return ChatColor.GREEN+"▰".repeat(filled)+ChatColor.DARK_GRAY+"▰".repeat(10-filled);
    }
    private String pretty(String material){
        String[] words=material.toLowerCase(Locale.ROOT).split("_");
        StringJoiner result=new StringJoiner(" ");
        for(String word:words) result.add(Character.toUpperCase(word.charAt(0))+word.substring(1));
        return result.toString();
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
    @EventHandler public void onQuit(PlayerQuitEvent e){lastWalk.remove(e.getPlayer().getUniqueId());cache.remove(e.getPlayer().getUniqueId());rerollMode.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void onBreak(BlockBreakEvent e){
        if(e.isCancelled())return;
        String type=e.getBlock().getType().name();
        updateWeekly(e.getPlayer(),"BREAK",type,1);
        for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("BREAK")&&dimensionMatches(t,e.getBlock().getWorld())&&(t.target.equals("ANY")||t.target.equals(type)))update(e.getPlayer(),t,1);
    }
    @EventHandler public void onKill(EntityDeathEvent e){
        Player p=e.getEntity().getKiller();if(p==null)return;
        String type=e.getEntityType().name();
        updateWeekly(p,"KILL",type,1);
        for(Task t:ensureTasks(p))if(t.type.equals("KILL")&&dimensionMatches(t,e.getEntity().getWorld())&&(t.target.equals("ANY")||t.target.equals(type)))update(p,t,1);
    }
    private int getCraftedAmount(CraftItemEvent e) {
        ItemStack result = e.getRecipe().getResult();
        int outputPerCraft = Math.max(1, result.getAmount());
        if (!e.isShiftClick()) return outputPerCraft;
        ItemStack[] matrix = e.getInventory().getMatrix();
        int crafts = Integer.MAX_VALUE;
        if (e.getRecipe() instanceof ShapedRecipe shaped) {
            Map<Character, ItemStack> ingredients = shaped.getIngredientMap();
            Map<Character, Integer> required = new HashMap<>();
            for (String line : shaped.getShape()) for (char symbol : line.toCharArray())
                if (symbol != ' ' && ingredients.get(symbol) != null) required.merge(symbol, 1, Integer::sum);
            for (Map.Entry<Character, Integer> entry : required.entrySet()) {
                ItemStack ingredient = ingredients.get(entry.getKey());
                int available = 0;
                for (ItemStack item : matrix) if (item != null && item.getType() == ingredient.getType()) available += item.getAmount();
                crafts = Math.min(crafts, available / entry.getValue());
            }
        } else if (e.getRecipe() instanceof ShapelessRecipe shapeless) {
            Map<Material, Integer> required = new HashMap<>();
            for (ItemStack ingredient : shapeless.getIngredientList())
                if (ingredient != null && !ingredient.getType().isAir()) required.merge(ingredient.getType(), 1, Integer::sum);
            for (Map.Entry<Material, Integer> entry : required.entrySet()) {
                int available = 0;
                for (ItemStack item : matrix) if (item != null && item.getType() == entry.getKey()) available += item.getAmount();
                crafts = Math.min(crafts, available / entry.getValue());
            }
        } else return outputPerCraft;
        if (crafts == Integer.MAX_VALUE || crafts <= 0) return outputPerCraft;
        return crafts * outputPerCraft;
    }

    @EventHandler public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.isCancelled()) return;
        String type = e.getRecipe().getResult().getType().name();
        int count = getCraftedAmount(e);
        updateWeekly(p,"CRAFT",type,count);
        for (Task t : ensureTasks(p))
            if (t.type.equals("CRAFT") && (t.target.equals("ANY") || t.target.equals(type))) update(p, t, count);
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
        Location a=e.getFrom(),b=e.getTo();if(a.getWorld()!=b.getWorld()||a.getBlockX()==b.getBlockX()&&a.getBlockZ()==b.getBlockZ())return;
        if(e.getPlayer().isInsideVehicle()||e.getPlayer().isFlying())return;
        Location prev=lastWalk.put(e.getPlayer().getUniqueId(),b.clone());
        if(prev==null||prev.getWorld()!=b.getWorld())return;
        int d=(int)Math.floor(Math.sqrt(Math.pow(b.getX()-prev.getX(),2)+Math.pow(b.getZ()-prev.getZ(),2)));
        if(d>0&&e.getPlayer().isSwimming())updateWeekly(e.getPlayer(),"SWIM","ANY",d);
        else if(d>0)updateWeekly(e.getPlayer(),"WALK","ANY",d);
        if(d>0&&e.getPlayer().isSwimming())for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("SWIM")&&dimensionMatches(t,b.getWorld()))update(e.getPlayer(),t,d);
        if(d>0)for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("WALK")&&dimensionMatches(t,b.getWorld()))update(e.getPlayer(),t,d);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;
        int slot=e.getRawSlot();
        if (slot == 31) {
            openWeeklyPreview(p);
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
            Set<String> assigned=new HashSet<>();
            for(Task existing:tasks) if(!existing.id.equals(t.id)) assigned.add(existing.id);
            ConfigurationSection available=pool();
            List<Task> candidates=new ArrayList<>();
            if(available!=null) for(String id:available.getKeys(false)) {
                if(assigned.contains(id)) continue;
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
            save(p.getUniqueId(),index,next);
            try(PreparedStatement h=db.prepareStatement("INSERT OR REPLACE INTO task_history(uuid,task_id,assigned_day) VALUES(?,?,?)")) {
                h.setString(1,p.getUniqueId().toString());h.setString(2,next.id);h.setString(3,today());h.executeUpdate();
            } catch(SQLException ex) { getLogger().warning("Nie udało się zapisać historii przerzutu: "+ex.getMessage()); }
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
