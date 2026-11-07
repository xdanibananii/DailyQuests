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
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DailyQuestsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Connection db;
    private ZoneId zone;
    private final Map<UUID, List<Task>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastWalk = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private static final String TITLE = ChatColor.DARK_GRAY + "Codzienne zadania";

    private static final class Task {
        String id, name, description, type, target, rewardItem;
        int amount, xp, itemAmount, progress;
        boolean claimed;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("tasks.yml", false);
        try { zone = ZoneId.of(getConfig().getString("timezone", "Europe/Warsaw")); }
        catch (Exception ex) { zone = ZoneId.systemDefault(); getLogger().warning("Nieprawidlowa strefa czasowa; uzywam strefy serwera."); }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "dailyquests.db").getAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS player_tasks (uuid TEXT NOT NULL, day TEXT NOT NULL, slot INTEGER NOT NULL, task_id TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,day,slot))");
            }
        } catch (SQLException e) { getLogger().severe("Nie mozna otworzyc SQLite: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        Objects.requireNonNull(getCommand("zadania")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) ensureTasks(p);
        getLogger().info("DailyQuests wlaczony.");
    }

    @Override public void onDisable() {
        if (db != null) try { db.close(); } catch (SQLException ignored) {}
    }

    private String today() { return LocalDate.now(zone).toString(); }
    private ConfigurationSection pool() { return getConfigSection(); }
    private ConfigurationSection getConfigSection() {
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(getDataFolder(), "tasks.yml"));
        return cfg.getConfigurationSection("tasks");
    }

    private List<Task> ensureTasks(Player p) {
        String day = today();
        try (PreparedStatement q = db.prepareStatement("SELECT task_id,progress,claimed FROM player_tasks WHERE uuid=? AND day=? ORDER BY slot")) {
            q.setString(1,p.getUniqueId().toString()); q.setString(2,day);
            try (ResultSet rs=q.executeQuery()) {
                List<Task> found=new ArrayList<>();
                while(rs.next()) { Task t=loadTask(rs.getString(1)); if(t!=null){t.progress=rs.getInt(2);t.claimed=rs.getInt(3)==1;found.add(t);} }
                if(found.size()==5){cache.put(p.getUniqueId(),found);return found;}
            }
        } catch(SQLException e){getLogger().warning(e.getMessage());}
        ConfigurationSection sec=pool();
        if(sec==null || sec.getKeys(false).isEmpty()) return new ArrayList<>();
        List<String> ids=new ArrayList<>(sec.getKeys(false)); Collections.shuffle(ids);
        List<Task> chosen=new ArrayList<>();
        for(String id:ids){Task t=loadTask(id);if(t!=null){chosen.add(t);if(chosen.size()==5)break;}}
        try(PreparedStatement del=db.prepareStatement("DELETE FROM player_tasks WHERE uuid=?")) {
            del.setString(1,p.getUniqueId().toString());del.executeUpdate();
        }catch(SQLException ignored){}
        for(int i=0;i<chosen.size();i++) save(p.getUniqueId(),i,chosen.get(i));
        cache.put(p.getUniqueId(),chosen); return chosen;
    }

    private Task loadTask(String id) {
        ConfigurationSection s=pool(); if(s==null || !s.isConfigurationSection(id)) return null;
        ConfigurationSection c=s.getConfigurationSection(id); Task t=new Task();
        t.id=id;t.name=color(c.getString("name",id));t.description=color(c.getString("description",""));
        t.type=c.getString("type","").toUpperCase(Locale.ROOT);t.target=c.getString("target","ANY").toUpperCase(Locale.ROOT);
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
        if(delta<=0)return;
        List<Task> list=ensureTasks(p);
        for(int i=0;i<list.size();i++) if(list.get(i).id.equals(t.id)){
            Task actual=list.get(i); if(actual.claimed)return;
            int before=actual.progress;
            actual.progress=Math.min(actual.amount,actual.progress+delta);
            save(p.getUniqueId(),i,actual);
            if(before<actual.amount && actual.progress>=actual.amount){
                p.playSound(p.getLocation(),Sound.UI_TOAST_CHALLENGE_COMPLETE,0.8f,1.0f);
                p.sendTitle(ChatColor.GOLD+"Zadanie ukończone!",ChatColor.YELLOW+ChatColor.stripColor(actual.name)+"  "+ChatColor.GREEN+"Odbierz nagrodę w /zadania",8,55,15);
                p.sendMessage(ChatColor.GOLD+"★ "+ChatColor.GREEN+"Ukończono zadanie: "+ChatColor.WHITE+ChatColor.stripColor(actual.name)+ChatColor.GRAY+" — nagroda czeka w "+ChatColor.YELLOW+"/zadania");
            } else {
                p.playSound(p.getLocation(),Sound.UI_BUTTON_CLICK,0.25f,1.6f);
            }
            break;
        }
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length>0 && args[0].equalsIgnoreCase("reload")){
            if(!sender.hasPermission("dailyquests.admin")){sender.sendMessage(ChatColor.RED+"Brak uprawnien.");return true;}
            reloadConfig();cache.clear();sender.sendMessage(ChatColor.GREEN+"DailyQuests: przeladowano config.");return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("Ta komenda jest dla graczy.");return true;}
        open(p);return true;
    }

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
    @EventHandler public void onQuit(PlayerQuitEvent e){lastWalk.remove(e.getPlayer().getUniqueId());cache.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void onBreak(BlockBreakEvent e){
        if(e.isCancelled())return;
        String type=e.getBlock().getType().name();
        for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("BREAK")&&(t.target.equals("ANY")||t.target.equals(type)))update(e.getPlayer(),t,1);
    }
    @EventHandler public void onKill(EntityDeathEvent e){
        Player p=e.getEntity().getKiller();if(p==null)return;
        String type=e.getEntityType().name();
        for(Task t:ensureTasks(p))if(t.type.equals("KILL")&&(t.target.equals("ANY")||t.target.equals(type)))update(p,t,1);
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
        for (Task t : ensureTasks(p))
            if (t.type.equals("CRAFT") && (t.target.equals("ANY") || t.target.equals(type))) update(p, t, count);
    }

    @EventHandler public void onFish(PlayerFishEvent e){
        if(e.getState()!=PlayerFishEvent.State.CAUGHT_FISH)return;
        for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("FISH"))update(e.getPlayer(),t,1);
    }
    @EventHandler public void onMove(PlayerMoveEvent e){
        if(e.getTo()==null)return;
        Location a=e.getFrom(),b=e.getTo();if(a.getWorld()!=b.getWorld()||a.getBlockX()==b.getBlockX()&&a.getBlockZ()==b.getBlockZ())return;
        if(e.getPlayer().isInsideVehicle()||e.getPlayer().isFlying())return;
        Location prev=lastWalk.put(e.getPlayer().getUniqueId(),b.clone());
        if(prev==null||prev.getWorld()!=b.getWorld())return;
        int d=(int)Math.floor(Math.sqrt(Math.pow(b.getX()-prev.getX(),2)+Math.pow(b.getZ()-prev.getZ(),2)));
        if(d>0)for(Task t:ensureTasks(e.getPlayer()))if(t.type.equals("WALK"))update(e.getPlayer(),t,d);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;
        int slot=e.getRawSlot();if(slot<20||slot>24)return;
        List<Task> tasks=ensureTasks(p);int index=slot-20;if(index>=tasks.size())return;
        Task t=tasks.get(index);
        if(t.claimed){p.sendMessage(ChatColor.GRAY+"Ta nagroda zostala juz odebrana.");return;}
        if(t.progress<t.amount){p.sendMessage(ChatColor.RED+"To zadanie nie jest jeszcze ukonczone.");return;}
        t.claimed=true;save(p.getUniqueId(),index,t);
        if(t.xp>0)p.giveExp(t.xp);
        Material reward=material(t.rewardItem);
        if(reward!=null){ItemStack item=new ItemStack(reward,t.itemAmount);Map<Integer,ItemStack> overflow=p.getInventory().addItem(item);overflow.values().forEach(it->p.getWorld().dropItemNaturally(p.getLocation(),it));}
        p.sendMessage(ChatColor.GREEN+"Odebrano nagrode za: "+ChatColor.RESET+t.name);
        boolean all=tasks.size()==5&&tasks.stream().allMatch(x->x.claimed);
        if(all){p.giveExp(250);p.sendMessage(ChatColor.GOLD+"Ukonczyles wszystkie 5 zadan! Bonus: 250 XP.");}
        open(p);
    }
    @EventHandler public void onDrag(InventoryDragEvent e){if(TITLE.equals(e.getView().getTitle()))e.setCancelled(true);}
}
