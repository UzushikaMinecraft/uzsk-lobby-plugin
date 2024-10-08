package net.iamtakagi.uzsk.lobby;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import net.iamtakagi.iroha.ItemBuilder;
import net.iamtakagi.iroha.Style;
import net.iamtakagi.medaka.Button;
import net.iamtakagi.medaka.Medaka;
import net.iamtakagi.medaka.Menu;
import net.iamtakagi.uzsk.core.CorePlayer;
import net.iamtakagi.uzsk.core.menu.ProfileMenu;
import net.iamtakagi.uzsk.lobby.Lobby.MinecraftServer;
import net.iamtakagi.uzsk.lobby.Lobby.LobbyConfig.JoinSettings;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;
import net.megavex.scoreboardlibrary.api.ScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.exception.NoPacketAdapterAvailableException;
import net.megavex.scoreboardlibrary.api.noop.NoopScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.sidebar.Sidebar;
import net.megavex.scoreboardlibrary.api.sidebar.component.ComponentSidebarLayout;
import net.megavex.scoreboardlibrary.api.sidebar.component.SidebarComponent;

import java.util.*;
import java.util.stream.Collectors;

public class Lobby extends JavaPlugin {

  private static Lobby instance;
  private LobbyConfig config;
  private ScoreboardLibrary scoreboard;
  private Map<UUID, LobbySidebar> sidebars = new HashMap();
  private ServerManager serverManager;
  private ServerNPCManager serverNPCManager;

  @Override
  public void onEnable() {
    instance = this;
    if (!this.getServer().getPluginManager().isPluginEnabled("Core")) {
      this.getLogger().severe("Core is not enabled!");
      this.getServer().getPluginManager().disablePlugin(this);
      return;
    }
    if (!this.getServer().getPluginManager().isPluginEnabled("Citizens")) {
      this.getLogger().severe("Citizens is not enabled!");
      this.getServer().getPluginManager().disablePlugin(this);
      return;
    }
    this.saveDefaultConfig();
    this.loadConfig();
    this.setupScoreboard();
    this.getServer().getPluginManager().registerEvents(new GeneralListener(), this);
    this.getServer().getPluginManager().registerEvents(new SidebarListener(), this);
    this.getServer().getPluginManager().registerEvents(new DoubleJumpListener(), this);
    this.serverManager = new ServerManager();
    this.serverManager.init();
    this.serverNPCManager = new ServerNPCManager();
    this.serverNPCManager.init();
    Medaka.init(this);
    Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

    Bukkit.getOnlinePlayers().forEach(p -> {
      LobbySidebar sidebar = new LobbySidebar(p);
      sidebar.init();
      sidebars.put(p.getUniqueId(), sidebar);
    });
  }

  @Override
  public void onDisable() {
    this.saveDefaultConfig();
    scoreboard.close();
    CitizensAPI.getNPCRegistry().deregisterAll();
    CitizensAPI.getNPCRegistry().saveToStore();
  }

  public static Lobby getInstance() {
    return instance;
  }

  public LobbyConfig getLobbyConfig() {
    return config;
  }

  public ServerManager getServerManager() {
    return serverManager;
  }

  public ServerNPCManager getServerNPCManager() {
    return serverNPCManager;
  }

  public Map<UUID, LobbySidebar> getLobbySidebars() {
    return this.sidebars;
  }

  private void loadConfig() {
    this.config = new LobbyConfig((YamlConfiguration) this.getConfig());
  }

  private void setupScoreboard() {
    try {
      scoreboard = ScoreboardLibrary.loadScoreboardLibrary(this);
    } catch (NoPacketAdapterAvailableException e) {
      scoreboard = new NoopScoreboardLibrary();
      this.getLogger().warning("No scoreboard packet adapter available!");
    }
  }

  public class ServerManager {
    private Map<String, MinecraftServer> servers;

    public ServerManager() {
      this.servers = new LinkedHashMap<>();
    }

    public void init() {
      Lobby.getInstance().getLobbyConfig().getServers().forEach(s -> {
        String[] data = s.split(":");
        servers.put(data[0], new MinecraftServer(data[0], data[1], Integer.parseInt(data[2]), data[8]));
      });
      Lobby.getInstance().getServer().getScheduler().runTaskTimerAsynchronously(Lobby.getInstance(), new Runnable() {
        @Override
        public void run() {
          servers.values().forEach(server -> server.update());
        }
      }, 0, 100);
    }

    public Map<String, MinecraftServer> getServers() {
      return this.servers;
    }

    public int getConnectionsSize() {
      return this.servers.values().stream().mapToInt(MinecraftServer::getOnlinePlayers).sum()
          + Lobby.getInstance().getServer().getOnlinePlayers().size();
    }
  }

  public class ServerMenu extends Menu {

    public ServerMenu(Plugin instance) {
      super(instance);
      this.setAutoUpdate(true);
    }

    @Override
    public Map<Integer, Button> getButtons(Player player) {
      Map<Integer, Button> buttons = new HashMap<>();
      final int[] index = { 0 };
      Lobby.getInstance().getServerManager().getServers().values().forEach(s -> {
        buttons.put(index[0], new net.iamtakagi.medaka.Button(instance) {
          @Override
          public ItemStack getButtonItem(Player player) {
            ItemBuilder b = new ItemBuilder(s.isOnline() ? Material.GREEN_WOOL : Material.RED_WOOL)
                .name(Style.WHITE + s.name);
            if (s.isOnline()) {
              b.lore(
                  s.description,
                  Style.GRAY + "接続数: " + s.onlinePlayers + "/" + s.maxPlayers);
            } else {
              b.lore(Style.RED + "このサーバーはオフラインです");
            }
            return b.build();
          }

          @Override
          public void clicked(Player player, ClickType clickType) {
            Utils.connectToServer(player, s.name);
          }
        });
        index[0]++;
      });
      return buttons;
    }

    @Override
    public String getTitle(Player player) {
      return "クリックしてサーバーを選択";
    }

  }

  static class Items {
    static ItemStack SERVER_SELECTOR;
    static ItemStack PROFILE_VIEWER;
    static {
      SERVER_SELECTOR = new ItemBuilder(Material.COMPASS).name(Style.AQUA + "サーバー選択 / クリックしてメニューを開く").build();
      PROFILE_VIEWER = new ItemBuilder(Material.NETHER_STAR).name(Style.AQUA + "プロフィール / クリックしてメニューを開く").build();
    }
  }

  class GeneralListener implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      player.teleport(player.getWorld().getSpawnLocation());
      player.getInventory().clear();
      player.setGameMode(GameMode.SURVIVAL);
      player.getInventory().setItem(0, Items.SERVER_SELECTOR);
      player.getInventory().setItem(8, Items.PROFILE_VIEWER);

      JoinSettings joinSettings = Lobby.getInstance().getLobbyConfig().getJoinSettings();
      List<String> joinMessages = joinSettings.getMessages();
      player.sendMessage(joinMessages.stream()
          .map(message -> ChatColor.translateAlternateColorCodes('&', message))
          .collect(Collectors.toList()).toArray(String[]::new));
      player.sendTitle(ChatColor.translateAlternateColorCodes('&', joinSettings.getTitle()),
          ChatColor.translateAlternateColorCodes('&', joinSettings.getSubtitle()), 20, 60, 20);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
      if (event.getItem() == null || event.getItem().getType() == Material.AIR) {
        return;
      }
      if (event.getItem().equals(Items.SERVER_SELECTOR)) {
        new ServerMenu(Lobby.getInstance()).openMenu(event.getPlayer());
      }
      if (event.getItem().equals(Items.PROFILE_VIEWER)) {
        CorePlayer corePlayer = CorePlayer.getCorePlayer(event.getPlayer().getUniqueId());
        if (corePlayer == null) {
          return;
        }
        if (corePlayer.getProfile() == null) {
          return;
        }
        new ProfileMenu(instance, corePlayer.getProfile()).openMenu(event.getPlayer());
      }
    }

    @EventHandler
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
      if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
        event.setCancelled(true);
      }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
      event.setCancelled(true);
      event.getPlayer().updateInventory();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player) {
        event.setCancelled(true);
      }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player) {
        event.setCancelled(true);
      }
    }

    @EventHandler
    public void onChange(FoodLevelChangeEvent e) {
      e.setCancelled(true);
    }

    @EventHandler
    public void onClickInv(InventoryClickEvent e) {
      if (e.getWhoClicked() instanceof Player) {
        Player player = (Player) e.getWhoClicked();
        if (player.getGameMode() != GameMode.CREATIVE) {
          if (e.getClickedInventory() instanceof PlayerInventory) {
            e.setCancelled(true);
          }
        }
      }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
      Player player = e.getPlayer();
      if (player.getGameMode() != GameMode.CREATIVE) {
        if (e.getBlockPlaced() != null) {
          e.setCancelled(true);
        }
      }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
      Player player = e.getPlayer();
      if (player.getGameMode() != GameMode.CREATIVE) {
        e.setCancelled(true);
      }
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
      event.setCancelled(true);
    }
  }

  class DoubleJumpListener implements Listener {
    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
      Player player = event.getPlayer();
      if (player.getGameMode() != GameMode.CREATIVE) {
        event.setCancelled(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        Vector velocity = player.getLocation().getDirection().multiply(1.5).setY(1);
        player.setVelocity(velocity);
      }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
      Player player = event.getPlayer();
      if (player.getGameMode() != GameMode.CREATIVE &&
          player.getLocation().subtract(0.0, 1.0, 0.0).getBlock().getType() != Material.AIR &&
          !player.isFlying()) {
        player.setAllowFlight(true);
      }
    }
  }

  class LobbySidebar {
    private Player player;
    private Sidebar sidebar;
    private ComponentSidebarLayout layout;
    private BukkitTask tickTask;

    LobbySidebar(Player player) {
      this.player = player;
    }

    public void init() {
      this.sidebar = scoreboard.createSidebar();
      this.sidebar.addPlayer(player);
      this.tickTask = Bukkit.getScheduler().runTaskTimer(instance, this::tick, 0, 20);
    }

    public void destroy() {
      this.sidebar.removePlayer(player);
      this.tickTask.cancel();
    }

    private void tick() {
      if (!player.isOnline()) {
        destroy();
        sidebars.remove(player.getUniqueId());
        return;
      }
      SidebarComponent.Builder builder = SidebarComponent.builder();
      SidebarComponent title = SidebarComponent.staticLine(
          Component.text(ChatColor.translateAlternateColorCodes('&', config.getSidebarSettings().getTitle())));
      for (int i = 0; i < config.getSidebarSettings().getLines().size(); i++) {
        String line = config.getSidebarSettings().getLines().get(i);
        if (line.length() > 0) {
          builder.addStaticLine(Component.text(ChatColor.translateAlternateColorCodes('&', parseLine(line))));
        } else {
          builder.addStaticLine(Component.empty());
        }
      }
      SidebarComponent component = builder.build();
      this.layout = new ComponentSidebarLayout(title, component);
      this.layout.apply(this.sidebar);
    }

    private String parseLine(String raw) {
      if (raw.contains("{PING}")) {
        raw = raw.replace("{PING}", "" + player.getPing());
      }

      if (raw.contains("{TPS}")) {
        double[] recetTps = Utils.getRecentTps();
        double avgTps = (recetTps[0] + recetTps[1] + recetTps[2]) / 3;
        raw = raw.replace("{TPS}", "" + (Math.floor(avgTps * 100)) / 100);
      }

      if (raw.contains("{DATE}")) {
        raw = raw.replace("{DATE}",
            new SimpleDateFormat(config.getSidebarSettings().getPatternSettings().getDatePattern())
                .format(Calendar.getInstance().getTime()));
      }

      if (raw.contains("{TIME}")) {
        raw = raw.replace("{TIME}",
            new SimpleDateFormat(config.getSidebarSettings().getPatternSettings().getTimePattern())
                .format(Calendar.getInstance().getTime()));
      }

      if (raw.contains("{USAGE_RAM}")) {
        raw = raw.replace("{USAGE_RAM}", String.format("%,d",
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));
      }

      if (raw.contains("{TOTAL_RAM}")) {
        raw = raw.replace("{TOTAL_RAM}", String.format("%,d", Runtime.getRuntime().totalMemory() / 1024 / 1024));
      }

      if (raw.contains("{TOTAL_CONNECTIONS}")) {
        raw = raw.replace("{TOTAL_CONNECTIONS}", "" + Lobby.getInstance().getServerManager().getConnectionsSize());
      }

      return raw;
    }
  }

  class MinecraftServer {
    String name;
    String address;
    int port;
    String description;
    String motd;
    int onlinePlayers;
    int maxPlayers;
    boolean isOnline;

    public MinecraftServer(String name, String address, int port, String description) {
      this.name = name;
      this.address = address;
      this.port = port;
      this.description = description;
    }

    public void update() {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, port), 20);

        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        out.write(0xFE);

        StringBuilder str = new StringBuilder();

        int b;
        while ((b = in.read()) != -1) {
          if (b != 0 && b > 16 && b != 255 && b != 23 && b != 24) {
            str.append((char) b);
          }
        }

        String[] data = str.toString().split("§");
        this.motd = data[0];
        this.onlinePlayers = Integer.valueOf(data[1]);
        this.maxPlayers = Integer.valueOf(data[2]);
        this.isOnline = true;
      } catch (Exception e) {
        this.isOnline = false;
      } finally {
        try {
          socket.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    public boolean isOnline() {
      return this.isOnline;
    }

    private int getOnlinePlayers() {
      return this.onlinePlayers;
    }
  }

  class SidebarListener implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
      LobbySidebar sidebar = new LobbySidebar(event.getPlayer());
      sidebar.init();
      Lobby.getInstance().getLobbySidebars().put(event.getPlayer().getUniqueId(), sidebar);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
      if (Lobby.getInstance().getLobbySidebars().containsKey(event.getPlayer().getUniqueId())) {
        Lobby.getInstance().getLobbySidebars().get(event.getPlayer().getUniqueId()).destroy();
        Lobby.getInstance().getLobbySidebars().remove(event.getPlayer().getUniqueId());
      }
    }
  }

  class LobbyConfig {
    private SidebarSettings sidebarSettings;
    private JoinSettings joinSettings;
    private List<String> servers;

    LobbyConfig(YamlConfiguration yaml) {
      this.sidebarSettings = new SidebarSettings(yaml);
      this.joinSettings = new JoinSettings(yaml);
      this.servers = yaml.getStringList("servers");
    }

    public SidebarSettings getSidebarSettings() {
      return this.sidebarSettings;
    }

    public JoinSettings getJoinSettings() {
      return this.joinSettings;
    }

    public List<String> getServers() {
      return this.servers;
    }

    class SidebarSettings {
      private String title;
      private List<String> lines;
      private PatternSettings patternSettings;

      SidebarSettings(YamlConfiguration yaml) {
        this.title = yaml.getString("sidebar.title");
        this.lines = yaml.getStringList("sidebar.lines");
        this.patternSettings = new PatternSettings(yaml);
      }

      public String getTitle() {
        return this.title;
      }

      public List<String> getLines() {
        return this.lines;
      }

      public PatternSettings getPatternSettings() {
        return this.patternSettings;
      }

      class PatternSettings {
        private String datePattern;
        private String timePattern;
        private String worldDatePattern;
        private String worldTimePattern;

        PatternSettings(YamlConfiguration yaml) {
          this.datePattern = yaml.getString("sidebar.pattern.date");
          this.timePattern = yaml.getString("sidebar.pattern.time");
          this.worldDatePattern = yaml.getString("sidebar.pattern.world_date");
          this.worldTimePattern = yaml.getString("sidebar.pattern.world_time");
        }

        public String getDatePattern() {
          return this.datePattern;
        }

        public String getTimePattern() {
          return this.timePattern;
        }

        public String getWorldDatePattern() {
          return this.worldDatePattern;
        }

        public String getWorldTimePattern() {
          return this.worldTimePattern;
        }
      }
    }

    class JoinSettings {
      private List<String> messages;
      private String title, subtitle;

      JoinSettings(YamlConfiguration yaml) {
        this.messages = yaml.getStringList("join.messages");
        this.title = yaml.getString("join.title");
        this.subtitle = yaml.getString("join.subtitle");
      }

      public List<String> getMessages() {
        return this.messages;
      }

      public String getTitle() {
        return this.title;
      }

      public String getSubtitle() {
        return this.subtitle;
      }
    }
  }

}

class Utils {

  static double[] getRecentTps() {
    double[] recentTps = null;
    try {
      Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
      recentTps = ((double[]) server.getClass().getField("recentTps").get(server));
    } catch (ReflectiveOperationException e) {
      e.printStackTrace();
    }
    return recentTps;
  }

  public static void connectToServer(Player player, String serverName) {
    player.sendMessage(ChatColor.AQUA + "Connecting to " + serverName + "...");
    ByteArrayDataOutput output = ByteStreams.newDataOutput();
    output.writeUTF("Connect");
    output.writeUTF(serverName);
    player.sendPluginMessage(Lobby.getInstance(), "BungeeCord", output.toByteArray());
  }
}

class ServerNPCManager {
  private List<ServerNPC> npcs;

  public ServerNPCManager() {
    this.npcs = new ArrayList<>();
  }

  public void init() {
    CitizensAPI.getNPCRegistry().deregisterAll();
    CitizensAPI.getNPCRegistry().saveToStore();

    Bukkit.getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onCitizensEnable(CitizensEnableEvent ev) {
        Lobby.getInstance().getLobbyConfig().getServers().forEach(s -> {
          String[] data = s.split(":");
          Location location = new Location(Bukkit.getWorld("world"), Double.valueOf(data[3]),
              Double.valueOf(data[4]), Double.valueOf(data[5]), Float.valueOf(data[6]), Float.valueOf(data[7]));
          ServerNPC npc = new ServerNPC(data[0], location, data[9]);
          System.out.println("NPC Location: " + location.toString());
          npc.init();
          npcs.add(npc);
        });
        Lobby.getInstance().getServer().getScheduler().runTaskTimer(Lobby.getInstance(), new Runnable() {
          @Override
          public void run() {
            npcs.forEach(npc -> npc.update());
          }
        }, 0, 100);

        Bukkit.getServer().getPluginManager().registerEvents(new Listener() {
          @EventHandler
          public void onClick(NPCRightClickEvent event) {
            Lobby.getInstance().getServerNPCManager().getNPCs().forEach(npc -> {
              if (event.getNPC().equals(npc.getNPC())) {
                Utils.connectToServer(event.getClicker(), npc.getServerName());
              }
            });
          }
        }, Lobby.getInstance());
      }
    }, Lobby.getInstance());
  }

  public List<ServerNPC> getNPCs() {
    return this.npcs;
  }
}

class ServerNPC {
  private String serverName;
  private Location location;
  private NPC npc;
  private String skinName;

  public ServerNPC(String serverName, Location location, String skinName) {
    this.location = location;
    this.serverName = serverName;
    this.skinName = skinName;
  }

  public void init() {
    this.npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "");
    this.npc.getOrAddTrait(SkinTrait.class).setSkinName(this.skinName);
    this.npc.spawn(location);
  }

  public void update() {
    MinecraftServer server = Lobby.getInstance().getServerManager().getServers().get(serverName);

    if (server == null)
      return;

    if (server.isOnline()) {
      npc.setName(Style.GREEN + serverName + Style.GRAY + " (" + server.onlinePlayers + "/" + server.maxPlayers + ")");
    } else {
      npc.setName(Style.RED + "サーバー" + serverName + "は現在オフラインです");
    }
  }

  public NPC getNPC() {
    return this.npc;
  }

  public String getServerName() {
    return this.serverName;
  }
}