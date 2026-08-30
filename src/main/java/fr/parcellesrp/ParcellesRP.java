package fr.parcellesrp;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.*;

public final class ParcellesRP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Connection db;
    private final DecimalFormat fmt = new DecimalFormat("0.00");

    @Override public void onEnable() {
        saveDefaultConfig();
        if (getServer().getPluginManager().getPlugin("EconomyRP") == null) {
            getLogger().severe("EconomyRP est obligatoire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try { initDb(); } catch (SQLException e) {
            getLogger().severe("SQLite: "+e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Objects.requireNonNull(getCommand("parcelle")).setExecutor(this);
        Objects.requireNonNull(getCommand("parcelle")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("ParcellesRP 1.0.0 actif.");
    }

    @Override public void onDisable() {
        try { if(db!=null) db.close(); } catch(SQLException ignored){}
    }

    private void initDb() throws SQLException {
        if(!getDataFolder().exists()) getDataFolder().mkdirs();
        db=DriverManager.getConnection("jdbc:sqlite:"+new File(getDataFolder(),"parcelles.db"));
        try(Statement s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS plots(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  world TEXT NOT NULL,
                  min_cx INTEGER NOT NULL,
                  min_cz INTEGER NOT NULL,
                  size_chunks INTEGER NOT NULL,
                  owner_uuid TEXT,
                  owner_name TEXT,
                  UNIQUE(world,min_cx,min_cz)
                )
            """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS members(
                  plot_id INTEGER NOT NULL,
                  uuid TEXT NOT NULL,
                  name TEXT NOT NULL,
                  PRIMARY KEY(plot_id,uuid)
                )
            """);
        }
    }

    private int size(){return Math.max(1,getConfig().getInt("size-chunks",4));}
    private int grid(int chunk){return Math.floorDiv(chunk,size())*size();}
    private Plot at(Location l) {
        int cx=grid(l.getChunk().getX()), cz=grid(l.getChunk().getZ());
        try(PreparedStatement q=db.prepareStatement("SELECT * FROM plots WHERE world=? AND min_cx=? AND min_cz=?")) {
            q.setString(1,l.getWorld().getName()); q.setInt(2,cx); q.setInt(3,cz);
            try(ResultSet r=q.executeQuery()){return r.next()?new Plot(r.getInt("id"),r.getString("world"),r.getInt("min_cx"),r.getInt("min_cz"),r.getInt("size_chunks"),r.getString("owner_uuid"),r.getString("owner_name")):null;}
        } catch(SQLException e){return null;}
    }

    private record Plot(int id,String world,int cx,int cz,int chunks,String ownerUuid,String ownerName) {
        boolean owned(){return ownerUuid!=null&&!ownerUuid.isBlank();}
        boolean owner(UUID u){return owned()&&ownerUuid.equals(u.toString());}
    }

    private boolean member(Plot p, UUID u) {
        if(p==null||!p.owned())return false;
        try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM members WHERE plot_id=? AND uuid=?")) {
            q.setInt(1,p.id);q.setString(2,u.toString());
            try(ResultSet r=q.executeQuery()){return r.next();}
        } catch(SQLException e){return false;}
    }

    private boolean allowed(Player p, Location l) {
        if(p.hasPermission("parcellesrp.admin"))return true;
        Plot pl=at(l);
        return pl==null || !pl.owned() || pl.owner(p.getUniqueId()) || member(pl,p.getUniqueId());
    }

    private double bank(Player p) {
        try {
            Object plugin=getServer().getPluginManager().getPlugin("EconomyRP");
            var m=plugin.getClass().getDeclaredMethod("bank",UUID.class);
            m.setAccessible(true);
            return ((Number)m.invoke(plugin,p.getUniqueId())).doubleValue();
        } catch(Exception e) {
            // EconomyRP's bank method is private in the supplied V2/V3 code.
            // Use the EconomyRP admin command fallback only when direct API is unavailable.
            return 0;
        }
    }

    private boolean economy(String command) {
        String[] parts=command.trim().split("\\s+");
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),String.join(" ",parts));
    }

    private void msg(Player p,String s){p.sendMessage("§8[§6ParcellesRP§8] §r"+s);}
    private String money(double x){return fmt.format(x)+getConfig().getString("currency-symbol","€");}

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] a) {
        if(!(sender instanceof Player p)){sender.sendMessage("Joueurs uniquement.");return true;}
        if(a.length==0){help(p);return true;}
        switch(a[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(p);
            case "acheter" -> buy(p);
            case "vendre" -> sell(p);
            case "ma" -> mine(p);
            case "inviter" -> invite(p,a);
            case "retirer" -> remove(p,a);
            default -> help(p);
        }
        return true;
    }

    private void info(Player p) {
        Plot pl=at(p.getLocation());
        if(pl==null){msg(p,"§aZone libre. §7Cette zone fait "+(size()*16)+"x"+(size()*16)+" blocs.");msg(p,"§ePrix: §f"+money(getConfig().getDouble("price")));return;}
        msg(p,"§6🏠 Parcelle #"+pl.id);
        msg(p,"§7Taille: §f"+(pl.chunks*16)+"x"+(pl.chunks*16)+" blocs §7("+pl.chunks+"x"+pl.chunks+" chunks)");
        msg(p,"§7Propriétaire: "+(pl.owned()?"§f"+pl.ownerName:"§aLibre"));
        msg(p,"§7Grille chunks: §f"+pl.cx+" à "+(pl.cx+pl.chunks-1)+" §7/ §f"+pl.cz+" à "+(pl.cz+pl.chunks-1));
        if(!pl.owned())msg(p,"§eAcheter: §f/parcelle acheter");
    }

    private void buy(Player p) {
        Plot pl=at(p.getLocation());
        if(pl!=null){msg(p,pl.owned()?"§cCette parcelle appartient à "+pl.ownerName+".":"§cParcelle déjà réservée.");return;}
        double price=getConfig().getDouble("price",100000);
        if(!takeFromBank(p,price)){msg(p,"§cVous devez avoir "+money(price)+" en banque.");return;}
        int cx=grid(p.getChunk().getX()),cz=grid(p.getChunk().getZ());
        try(PreparedStatement q=db.prepareStatement("INSERT INTO plots(world,min_cx,min_cz,size_chunks,owner_uuid,owner_name) VALUES(?,?,?,?,?,?)")) {
            q.setString(1,p.getWorld().getName());q.setInt(2,cx);q.setInt(3,cz);q.setInt(4,size());q.setString(5,p.getUniqueId().toString());q.setString(6,p.getName());q.executeUpdate();
            msg(p,"§a🏠 Parcelle achetée ! §f"+(size()*16)+"x"+(size()*16)+" blocs §apour §f"+money(price));
        } catch(SQLException e) {
            giveToBank(p,price);
            msg(p,"§cImpossible de créer la parcelle. Argent remboursé.");
        }
    }

    private void sell(Player p) {
        Plot pl=at(p.getLocation());
        if(pl==null||!pl.owner(p.getUniqueId())){msg(p,"§cVous devez être sur votre parcelle.");return;}
        double price=getConfig().getDouble("price",100000);
        double refund=price*getConfig().getDouble("sell-percent",75)/100.0;
        try(PreparedStatement q=db.prepareStatement("DELETE FROM plots WHERE id=?")) {
            q.setInt(1,pl.id);q.executeUpdate();
            try(PreparedStatement m=db.prepareStatement("DELETE FROM members WHERE plot_id=?")){m.setInt(1,pl.id);m.executeUpdate();}
            giveToBank(p,refund);
            msg(p,"§aParcelle vendue. Remboursement: §f"+money(refund));
        }catch(SQLException e){msg(p,"§cErreur lors de la vente.");}
    }

    private void mine(Player p) {
        Plot pl=at(p.getLocation());
        if(pl==null||!pl.owner(p.getUniqueId())){msg(p,"§cVous ne possédez pas cette parcelle.");return;}
        msg(p,"§a🏠 Votre parcelle #"+pl.id+" — "+(pl.chunks*16)+"x"+(pl.chunks*16)+" blocs.");
        msg(p,"§7Propriétaire: §f"+pl.ownerName);
    }

    private void invite(Player p,String[] a) {
        if(a.length!=2){msg(p,"§c/parcelle inviter <joueur>");return;}
        Plot pl=at(p.getLocation()); if(pl==null||!pl.owner(p.getUniqueId())){msg(p,"§cVous devez être propriétaire.");return;}
        Player t=Bukkit.getPlayerExact(a[1]); if(t==null){msg(p,"§cJoueur introuvable.");return;}
        try(PreparedStatement q=db.prepareStatement("INSERT OR REPLACE INTO members(plot_id,uuid,name) VALUES(?,?,?)")){
            q.setInt(1,pl.id);q.setString(2,t.getUniqueId().toString());q.setString(3,t.getName());q.executeUpdate();
            msg(p,"§aAccès donné à §f"+t.getName());
        }catch(SQLException e){msg(p,"§cErreur.");}
    }

    private void remove(Player p,String[] a) {
        if(a.length!=2){msg(p,"§c/parcelle retirer <joueur>");return;}
        Plot pl=at(p.getLocation()); if(pl==null||!pl.owner(p.getUniqueId())){msg(p,"§cVous devez être propriétaire.");return;}
        Player t=Bukkit.getPlayerExact(a[1]); if(t==null){msg(p,"§cJoueur introuvable.");return;}
        try(PreparedStatement q=db.prepareStatement("DELETE FROM members WHERE plot_id=? AND uuid=?")){
            q.setInt(1,pl.id);q.setString(2,t.getUniqueId().toString());q.executeUpdate();msg(p,"§aAccès retiré à §f"+t.getName());
        }catch(SQLException e){msg(p,"§cErreur.");}
    }

    // EconomyRP V2/V3 expose les commandes admin, mais pas une API publique.
    // Ces deux méthodes utilisent directement les commandes console /eco.
    private boolean takeFromBank(Player p,double amount) {
        // Pour rendre le plugin réellement compatible, on vérifie le solde par /argent impossible à parser.
        // Cette version attend l'ajout des méthodes API publiques dans EconomyRP.
        // Si API absente, achat refusé plutôt que de retirer de l'argent sans vérification.
        try {
            var m=getServer().getPluginManager().getPlugin("EconomyRP").getClass().getDeclaredMethod("bank",UUID.class);
            m.setAccessible(true);
            double current=((Number)m.invoke(getServer().getPluginManager().getPlugin("EconomyRP"),p.getUniqueId())).doubleValue();
            if(current<amount)return false;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"eco take "+p.getName()+" bank "+amount);
            return true;
        } catch(Exception e){return false;}
    }
    private void giveToBank(Player p,double amount) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"eco give "+p.getName()+" bank "+amount);
    }

    @EventHandler public void breakBlock(BlockBreakEvent e){protect(e.getPlayer(),e.getBlock().getLocation(),e::setCancelled);}
    @EventHandler public void placeBlock(BlockPlaceEvent e){protect(e.getPlayer(),e.getBlock().getLocation(),e::setCancelled);}
    @EventHandler public void bucket(PlayerBucketEvent e){if(getConfig().getBoolean("protect.buckets",true))protect(e.getPlayer(),e.getBlock().getLocation(),e::setCancelled);}
    @EventHandler public void interact(PlayerInteractEvent e){
        if(e.getClickedBlock()==null)return;
        Material m=e.getClickedBlock().getType();
        boolean sensitive=m.name().contains("CHEST")||m.name().contains("BARREL")||m.name().contains("DOOR")||m.name().contains("TRAPDOOR")||m.name().contains("FENCE_GATE");
        if(sensitive && getConfig().getBoolean("protect.containers",true))protect(e.getPlayer(),e.getClickedBlock().getLocation(),e::setCancelled);
    }
    @EventHandler public void pvp(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player p)||!getConfig().getBoolean("protect.pvp",true))return;
        if(!allowed(p,e.getEntity().getLocation()))e.setCancelled(true);
    }
    private void protect(Player p,Location l,java.util.function.Consumer<Boolean> cancel){
        if(!allowed(p,l)){cancel.accept(true);msg(p,"§c🚫 Cette parcelle appartient à quelqu'un d'autre.");}
    }

    private void help(Player p){
        msg(p,"§6🏠 ParcellesRP — "+(size()*16)+"x"+(size()*16)+" blocs");
        msg(p,"§e/parcelle info §7- infos");
        msg(p,"§e/parcelle acheter §7- acheter");
        msg(p,"§e/parcelle vendre §7- vendre (remboursement "+getConfig().getDouble("sell-percent",75)+"%)");
        msg(p,"§e/parcelle ma §7- votre parcelle");
        msg(p,"§e/parcelle inviter <joueur> §7- accès");
        msg(p,"§e/parcelle retirer <joueur> §7- retirer accès");
    }

    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){
        if(a.length==1)return List.of("info","acheter","vendre","ma","inviter","retirer");
        return Collections.emptyList();
    }
}