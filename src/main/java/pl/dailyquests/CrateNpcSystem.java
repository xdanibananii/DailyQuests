package pl.dailyquests;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import java.io.File;
import java.util.*;

public final class CrateNpcSystem implements Listener {
 private final DailyQuestsPlugin plugin; private final CrateSystem crates; private final NamespacedKey key; private final File file;
 private final Set<UUID> playersInRange = new HashSet<>();
 private final Random random = new Random();
 private static final String[] AMBIENT_LINES = {
  "Podejdź, podejdź! Dwadzieścia gwiazdek i kto wie — może trafisz prawdziwy skarb!",
  "Słyszałem, że ktoś ostatnio wyciągnął LEGENDARY... Może dziś szczęście wybierze ciebie?",
  "Nie chomikuj gwiazdek, młody! Niech pracują na wielką wygraną!",
  "Masz wolne dwadzieścia gwiazdek? Mam tu skrzynkę, która aż prosi się o otwarcie!",
  "Jedno losowanie dzieli cię od pięknej historii. Albo przynajmniej od ciekawej przygody!",
  "Podobno szczęście sprzyja odważnym. Ja nic nie sugeruję... ale skrzynka jest tuż obok.",
  "No dalej, bohaterze! Sprawdź, czy dziś wylosujesz coś COMMON, czy może prawdziwy rarytas!",
  "Gwiazdkami można płacić, ale emocji nie da się kupić... chyba że za dwadzieścia gwiazdek!"
 };
 private static final String[] GREETINGS = {
  "Witaj, szczęściarzu! Twoje gwiazdki mogą dziś zamienić się w prawdziwy skarb.",
  "Oho, nowa twarz przy skarbcu! Masz ochotę sprawdzić, co dziś kryje skrzynia?",
  "Podejdź bliżej! Za 20 gwiazdek możesz spróbować szczęścia. Kto wie, co wypadnie?",
  "Witaj, młody hazardzisto! Pokażmy losowi, kto tu dziś rządzi!",
  "Mam przeczucie, że dziś komuś dopisze szczęście... Może właśnie tobie?"
 };
 CrateNpcSystem(DailyQuestsPlugin plugin, CrateSystem crates) { this.plugin=plugin; this.crates=crates; key=new NamespacedKey(plugin,"crate_npc"); file=new File(plugin.getDataFolder(),"crate_npc.yml"); restore(); startAmbientTask(); }
 private void startAmbientTask() {
  Bukkit.getScheduler().runTaskTimer(plugin, () -> {
   if(!file.exists()) { playersInRange.clear(); return; }
   YamlConfiguration y=YamlConfiguration.loadConfiguration(file);
   World world=Bukkit.getWorld(y.getString("world",""));
   if(world==null || !y.contains("x")) { playersInRange.clear(); return; }
   UUID npcId;
   try { npcId=UUID.fromString(y.getString("uuid","")); }
   catch(IllegalArgumentException ex) { playersInRange.clear(); return; }
   Entity entity=Bukkit.getEntity(npcId);
   if(!(entity instanceof Villager villager) || !villager.isValid()) { playersInRange.clear(); return; }

   Set<UUID> nearbyNow=new HashSet<>();
   for(Player player:world.getPlayers()) {
    if(player.getLocation().distanceSquared(villager.getLocation())>36.0) continue;
    UUID id=player.getUniqueId();
    nearbyNow.add(id);
    if(playersInRange.add(id)) {
     String line=AMBIENT_LINES[random.nextInt(AMBIENT_LINES.length)];
     player.sendMessage(ChatColor.LIGHT_PURPLE+"Strażnik Skarbca"+ChatColor.DARK_GRAY+" » "+ChatColor.YELLOW+line);
     player.playSound(player.getLocation(),Sound.BLOCK_NOTE_BLOCK_CHIME,0.55f,1.35f);
    }
   }
   playersInRange.retainAll(nearbyNow);
  },20L,10L);
 }
 private void configure(Villager v) {
  v.setAI(false); v.setInvulnerable(true); v.setPersistent(true); v.setRemoveWhenFarAway(false); v.setCollidable(false); v.setCanPickupItems(false); v.setProfession(Villager.Profession.CLERIC);
  v.setCustomName(ChatColor.GOLD+"✦ "+ChatColor.LIGHT_PURPLE+"Strażnik Skarbca"+ChatColor.GOLD+" ✦"); v.setCustomNameVisible(true); v.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
 }
 private void restore() {
  if(!file.exists()) return; YamlConfiguration y=YamlConfiguration.loadConfiguration(file); World w=Bukkit.getWorld(y.getString("world","")); if(w==null||!y.contains("x")) return;
  Entity e=null; try { String id=y.getString("uuid",""); if(!id.isBlank()) e=Bukkit.getEntity(UUID.fromString(id)); } catch(IllegalArgumentException ignored) {}
  if(e instanceof Villager v) { configure(v); return; }
  Location l=loc(y,w); w.getChunkAt(l).load(); Villager v=(Villager)w.spawnEntity(l,EntityType.VILLAGER); configure(v); y.set("uuid",v.getUniqueId().toString()); save(y);
 }
 void spawnAt(Location l) {
  if(l.getWorld()==null) return; remove(); Villager v=(Villager)l.getWorld().spawnEntity(l,EntityType.VILLAGER); configure(v); YamlConfiguration y=new YamlConfiguration();
  y.set("world",l.getWorld().getName()); y.set("x",l.getX()); y.set("y",l.getY()); y.set("z",l.getZ()); y.set("yaw",l.getYaw()); y.set("pitch",l.getPitch()); y.set("uuid",v.getUniqueId().toString()); save(y);
 }
 void remove() { for(World w:Bukkit.getWorlds()) for(Entity e:w.getEntities()) if(e.getPersistentDataContainer().has(key,PersistentDataType.BYTE)) e.remove(); if(file.exists()) file.delete(); }
 void teleport(Player p) { YamlConfiguration y=YamlConfiguration.loadConfiguration(file); World w=Bukkit.getWorld(y.getString("world","")); if(w==null||!y.contains("x")) { p.sendMessage(ChatColor.RED+"Strażnik Skarbca nie został jeszcze postawiony."); return; } Location l=loc(y,w); w.getChunkAt(l).load(); p.teleport(l); }
 private Location loc(YamlConfiguration y,World w) { return new Location(w,y.getDouble("x"),y.getDouble("y"),y.getDouble("z"),(float)y.getDouble("yaw"),(float)y.getDouble("pitch")); }
 private void save(YamlConfiguration y) { try { y.save(file); } catch(Exception ex) { plugin.getLogger().warning("Nie zapisano NPC skrzyni: "+ex.getMessage()); } }
 @EventHandler public void onInteract(PlayerInteractEntityEvent e) {
  if(!e.getRightClicked().getPersistentDataContainer().has(key,PersistentDataType.BYTE)) return; e.setCancelled(true); Player p=e.getPlayer(); p.playSound(p.getLocation(),Sound.BLOCK_CHEST_OPEN,0.8f,1.1f);
  String greeting=GREETINGS[random.nextInt(GREETINGS.length)];
  p.sendMessage(ChatColor.LIGHT_PURPLE+"Strażnik Skarbca"+ChatColor.DARK_GRAY+" » "+ChatColor.YELLOW+greeting);
  p.sendMessage(ChatColor.GRAY+"» "+ChatColor.YELLOW+"Jedno losowanie kosztuje 20 ★. "+ChatColor.GRAY+"Sprawdź szanse w skrzynce!");
  crates.open(p);
 }
 @EventHandler public void onDamage(EntityDamageEvent e) { if(e.getEntity().getPersistentDataContainer().has(key,PersistentDataType.BYTE)) e.setCancelled(true); }
}