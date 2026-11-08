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
import java.util.UUID;

public final class CrateNpcSystem implements Listener {
 private final DailyQuestsPlugin plugin; private final CrateSystem crates; private final NamespacedKey key; private final File file;
 CrateNpcSystem(DailyQuestsPlugin plugin, CrateSystem crates) { this.plugin=plugin; this.crates=crates; key=new NamespacedKey(plugin,"crate_npc"); file=new File(plugin.getDataFolder(),"crate_npc.yml"); restore(); }
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
  p.sendMessage(ChatColor.LIGHT_PURPLE+"Strażnik Skarbca"+ChatColor.DARK_GRAY+" » "+ChatColor.YELLOW+"Witaj, podróżniku. Twoje gwiazdki mogą dziś przynieść ci prawdziwy skarb."); crates.open(p);
 }
 @EventHandler public void onDamage(EntityDamageEvent e) { if(e.getEntity().getPersistentDataContainer().has(key,PersistentDataType.BYTE)) e.setCancelled(true); }
}