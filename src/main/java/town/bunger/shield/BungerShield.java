package town.bunger.shield;

import cloud.commandframework.Command;
import cloud.commandframework.arguments.standard.FloatArgument;
import cloud.commandframework.bukkit.parsers.PlayerArgument;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.execution.AsynchronousCommandExecutionCoordinator;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.paper.PaperCommandManager;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static net.kyori.adventure.text.Component.text;

public final class BungerShield extends JavaPlugin implements Listener {

    /**
     * The permission required to toggle the shield.
     */
    private static final String PERMISSION_USE = "bunger.shield.use";

    /**
     * The permission required to toggle the shield for other players.
     */
    private static final String PERMISSION_OTHER = "bunger.shield.other";

    /**
     * The permission required to be exempt from the shield.
     */
    private static final String PERMISSION_EXEMPT = "bunger.shield.exempt";

    /**
     * The permission required to set the strength of the shield.
     */
    private static final String PERMISSION_STRENGTH = "bunger.shield.strength";

    /**
     * The permission required to set the radius of the shield.
     */
    private static final String PERMISSION_RADIUS = "bunger.shield.radius";

    /**
     * The default strength of a shield, used as a push factor against nearby entities.
     */
    private static final float DEFAULT_STRENGTH = 1.0f;

    /**
     * The default radius of a shield, used to determine how close entities must be to be pushed away.
     */
    private static final float DEFAULT_RADIUS = 3.0f;

    /**
     * The interval at which the shield task pushes entities around, in ticks.
     */
    private static final int TASK_TICK_INTERVAL = 5;

    /**
     * The {@link org.bukkit.persistence.PersistentDataContainer PDC} key used to store the shielded state of a player.
     */
    private final NamespacedKey shieldedKey = new NamespacedKey(this, "shielded");

    /**
     * The {@link org.bukkit.persistence.PersistentDataContainer PDC} key used to store the strength of a player's shield.
     */
    private final NamespacedKey strengthKey = new NamespacedKey(this, "strength");

    /**
     * The {@link org.bukkit.persistence.PersistentDataContainer PDC} key used to store the radius of a player's shield.
     */
    private final NamespacedKey radiusKey = new NamespacedKey(this, "radius");

    /**
     * The cache of currently active shielded players.
     */
    private final Set<UUID> shieldedCache = new HashSet<>();

    /**
     * The task that pushes players away from the shielded players.
     */
    private @Nullable BukkitTask shieldTask = null;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);

        // Create command manager
        PaperCommandManager<CommandSender> manager;
        try {
            manager = PaperCommandManager.createNative(this, AsynchronousCommandExecutionCoordinator.<CommandSender>builder().build());
        } catch (Exception e) {
            this.getLogger().severe("Failed to initialize command manager: " + e.getMessage());
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register Brigadier
        manager.registerBrigadier();
        // Register asynchronous completions
        manager.registerAsynchronousCompletions();

        // Register exception handlers
        new MinecraftExceptionHandler<CommandSender>()
            .withInvalidSyntaxHandler()
            .withInvalidSenderHandler()
            .withNoPermissionHandler()
            .withArgumentParsingHandler()
            .withCommandExecutionHandler()
            .apply(manager, (sender) -> sender);

        Command.Builder<CommandSender> shield = manager.commandBuilder("shield");

        manager.command(
            shield
                .argument(PlayerArgument.optional("player"))
                .permission(PERMISSION_USE)
                .handler(this::executeToggle)
        );
        manager.command(
            shield
                .literal("strength")
                .permission(PERMISSION_STRENGTH)
                .argument(FloatArgument.<CommandSender>builder("strength").withMin(0).withMax(10).asOptional().build())
                .handler(this::executeStrength)
        );
        manager.command(
            shield
                .literal("radius")
                .permission(PERMISSION_RADIUS)
                .argument(FloatArgument.<CommandSender>builder("radius").withMin(1).withMax(10).asOptional().build())
                .handler(this::executeRadius)
        );
    }

    /**
     * Adds players to the shielded cache when they join, if they logged out while shielded.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(PERMISSION_USE)) {
            boolean enabled = player.getPersistentDataContainer().getOrDefault(shieldedKey, PersistentDataType.BOOLEAN, false);
            if (enabled) {
                player.sendMessage(text("You are shielded.", NamedTextColor.GREEN));
                addShielded(player);
            }
        }
    }

    /**
     * Removes players from the shielded cache when they leave, if they are shielded.
     */
    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean enabled = player.getPersistentDataContainer().getOrDefault(shieldedKey, PersistentDataType.BOOLEAN, false);
        if (enabled) {
            removeShielded(player);
            if (!player.hasPermission(PERMISSION_USE)) {
                player.getPersistentDataContainer().remove(shieldedKey);
            }
        }
    }

    /**
     * Adds a player to the shielded cache and starts the shield task if it is not already running.
     */
    private void addShielded(Player player) {
        shieldedCache.add(player.getUniqueId());
        if (shieldTask == null) {
            startShieldTask();
        }
    }

    /**
     * Removes a player from the shielded cache and stops the shield task if it's running and the cache is empty.
     */
    private void removeShielded(Player player) {
        shieldedCache.remove(player.getUniqueId());
        if (shieldedCache.isEmpty() && shieldTask != null) {
            shieldTask.cancel();
            shieldTask = null;
        }
    }

    /**
     * Runs the shield task every {@link #TASK_TICK_INTERVAL} ticks.
     *
     * <p>
     * This task iterates the {@link #shieldedCache} and pushes nearby entities away from the shielded player.
     * </p>
     */
    private void startShieldTask() {
        shieldTask = this.getServer().getScheduler().runTaskTimer(this, () -> {
            List<UUID> uncache = new ArrayList<>();
            for (UUID shieldedId : shieldedCache) {
                Player shielded = this.getServer().getPlayer(shieldedId);
                if (shielded == null) {
                    uncache.add(shieldedId);
                    continue;
                } else if (!shielded.hasPermission(PERMISSION_USE)) {
                    shielded.getPersistentDataContainer().remove(shieldedKey);
                    uncache.add(shieldedId);
                    continue;
                }

                float strength = shielded.getPersistentDataContainer().getOrDefault(strengthKey, PersistentDataType.FLOAT, DEFAULT_STRENGTH);
                float radius = shielded.getPersistentDataContainer().getOrDefault(radiusKey, PersistentDataType.FLOAT, DEFAULT_RADIUS);

                for (Entity nearby : shielded.getNearbyEntities(radius, radius, radius)) {
                    if (nearby instanceof Player nearbyPlayer) {
                        if (nearbyPlayer.hasPermission(PERMISSION_EXEMPT)) {
                            // Exempt players don't get pushed.
                            continue;
                        } else if (nearbyPlayer.hasPermission(PERMISSION_USE)) {
                            boolean enabled = nearbyPlayer.getPersistentDataContainer().getOrDefault(shieldedKey, PersistentDataType.BOOLEAN, false);
                            if (enabled) {
                                // Shielded players don't push each other.
                                continue;
                            }
                        }
                    }

                    Vector pushDirection = nearby.getLocation().toVector()
                        .subtract(shielded.getLocation().toVector())
                        .normalize();
                    nearby.setVelocity(pushDirection.multiply(strength));
                }
            }
            uncache.forEach(shieldedCache::remove);
        }, 0, TASK_TICK_INTERVAL);
    }

    /**
     * Command handler for /shield [player]
     */
    private void executeToggle(@NonNull CommandContext<CommandSender> context) {
        Optional<Player> playerArg = context.getOptional("player");

        Player player;
        if (playerArg.isPresent()) {
            if (!context.getSender().hasPermission(PERMISSION_OTHER)) {
                context.getSender().sendMessage(text("You do not have permission to shield other players.", NamedTextColor.RED));
                return;
            }
            player = playerArg.get();
        } else if (context.getSender() instanceof Player sender) {
            player = sender;
        } else {
            context.getSender().sendMessage(text("Non-player senders must specify a player argument.", NamedTextColor.RED));
            return;
        }

        boolean enabled = player.getPersistentDataContainer().getOrDefault(shieldedKey, PersistentDataType.BOOLEAN, false);
        if (enabled) {
            player.getPersistentDataContainer().remove(shieldedKey);
            removeShielded(player);
            context.getSender().sendMessage(text("Disabled shield for " + player.getName() + ".", NamedTextColor.GREEN));
        } else {
            player.getPersistentDataContainer().set(shieldedKey, PersistentDataType.BOOLEAN, true);
            addShielded(player);
            context.getSender().sendMessage(text("Enabled shield for " + player.getName() + ".", NamedTextColor.GREEN));
        }
    }

    /**
     * Command handler for /shield strength [strength]
     */
    private void executeStrength(@NonNull CommandContext<CommandSender> context) {
        if (!(context.getSender() instanceof Player player)) {
            context.getSender().sendMessage(text("You must be a player to use this command.", NamedTextColor.RED));
            return;
        }

        Optional<Float> strengthArg = context.getOptional("strength");
        if (strengthArg.isEmpty()) {
            float strength = player.getPersistentDataContainer().getOrDefault(strengthKey, PersistentDataType.FLOAT, DEFAULT_STRENGTH);
            player.sendMessage(text("Your shield strength is currently " + strength + ".", NamedTextColor.GREEN));
        } else {
            float strength = strengthArg.get();
            player.getPersistentDataContainer().set(strengthKey, PersistentDataType.FLOAT, strength);
            player.sendMessage(text("Set your shield strength to " + strength + ".", NamedTextColor.GREEN));
        }
    }

    /**
     * Command handler for /shield radius [radius]
     */
    private void executeRadius(@NonNull CommandContext<CommandSender> context) {
        if (!(context.getSender() instanceof Player player)) {
            context.getSender().sendMessage(text("You must be a player to use this command.", NamedTextColor.RED));
            return;
        }

        Optional<Float> radiusArg = context.getOptional("radius");
        if (radiusArg.isEmpty()) {
            float radius = player.getPersistentDataContainer().getOrDefault(radiusKey, PersistentDataType.FLOAT, DEFAULT_RADIUS);
            player.sendMessage(text("Your shield radius is currently " + radius + ".", NamedTextColor.GREEN));
        } else {
            float radius = radiusArg.get();
            player.getPersistentDataContainer().set(radiusKey, PersistentDataType.FLOAT, radius);
            player.sendMessage(text("Set your shield radius to " + radius + ".", NamedTextColor.GREEN));
        }
    }
}
