package bentobox.addon.limits.listeners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import bentobox.addon.limits.Limits;
import bentobox.addon.limits.objects.IslandBlockCount;
import world.bentobox.bentobox.api.events.island.IslandEvent.IslandDeleteEvent;
import world.bentobox.bentobox.api.localization.TextVariables;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.Database;
import world.bentobox.bentobox.util.Util;

/**
 * @author tastybento
 *
 */
public class BlockLimitsListener implements Listener {

    /**
     * Blocks that are not counted
     */
    private static final List<Material> DO_NOT_COUNT = Arrays.asList(Material.LAVA, Material.WATER, Material.AIR, Material.FIRE, Material.END_PORTAL, Material.NETHER_PORTAL);
    private static final List<Material> STACKABLE = Arrays.asList(Material.SUGAR_CANE, Material.BAMBOO);
    /**
     * Save every 10 blocks of change
     */
    private static final Integer CHANGE_LIMIT = 9;
    private final Limits addon;
    private final Map<String, IslandBlockCount> islandCountMap = new HashMap<>();
    private final Map<String, Integer> saveMap = new HashMap<>();
    private final Database<IslandBlockCount> handler;
    private final Map<World, Map<Material, Integer>> worldLimitMap = new HashMap<>();
    private Map<Material, Integer> defaultLimitMap = new HashMap<>();

    public BlockLimitsListener(Limits addon) {
        this.addon = addon;
        handler = new Database<>(addon, IslandBlockCount.class);
        List<String> toBeDeleted = new ArrayList<>();
        handler.loadObjects().forEach(ibc -> {
            // Clean up
            if (addon.isCoveredGameMode(ibc.getGameMode())) {
                ibc.getBlockCount().keySet().removeIf(DO_NOT_COUNT::contains);
                // Store
                islandCountMap.put(ibc.getUniqueId(), ibc);
            } else {
                toBeDeleted.add(ibc.getUniqueId());
            }
        });
        toBeDeleted.forEach(handler::deleteID);
        loadAllLimits();
    }

    /**
     * Loads the default and world-specific limits
     */
    private void loadAllLimits() {
        // Load the default limits
        addon.log("Loading default limits");
        if (addon.getConfig().isConfigurationSection("blocklimits")) {
            ConfigurationSection limitConfig = addon.getConfig().getConfigurationSection("blocklimits");
            defaultLimitMap = loadLimits(limitConfig);
        }

        // Load specific worlds
        if (addon.getConfig().isConfigurationSection("worlds")) {
            ConfigurationSection worlds = addon.getConfig().getConfigurationSection("worlds");
            for (String worldName : worlds.getKeys(false)) {
                World world = Bukkit.getWorld(worldName);
                if (world != null && addon.inGameModeWorld(world)) {
                    addon.log("Loading limits for " + world.getName());
                    worldLimitMap.putIfAbsent(world, new HashMap<>());
                    ConfigurationSection matsConfig = worlds.getConfigurationSection(worldName);
                    worldLimitMap.put(world, loadLimits(matsConfig));
                }
            }
        }

    }

    /**
     * Loads limit map from configuration section
     *
     * @param cs - configuration section
     * @return limit map
     */
    private Map<Material, Integer> loadLimits(ConfigurationSection cs) {
        Map<Material, Integer> mats = new HashMap<>();
        for (String material : cs.getKeys(false)) {
            Material mat = Material.getMaterial(material);
            if (mat != null && mat.isBlock() && !DO_NOT_COUNT.contains(mat)) {
                mats.put(mat, cs.getInt(material));
                addon.log("Limit " + mat + " to " + cs.getInt(material));
            } else {
                addon.logError("Material " + material + " is not a valid block. Skipping...");
            }
        }
        return mats;
    }


    /**
     * Save the count database completely
     */
    public void save() {
        islandCountMap.values().forEach(handler::saveObject);
    }

    // Player-related events
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onBlock(BlockPlaceEvent e) {
        notify(e, User.getInstance(e.getPlayer()), process(e.getBlock(), true), e.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onBlock(BlockBreakEvent e) {
        handleBreak(e, e.getPlayer(), e.getBlock());
    }

    void handleBreak(Cancellable e, Player player, Block b) {
        Material mat = b.getType();
        // Special handling for crops that can break in different ways
        if (mat.equals(Material.WHEAT_SEEDS)) {
            mat = Material.WHEAT;
        } else if (mat.equals(Material.BEETROOT_SEEDS)) {
            mat = Material.BEETROOT;
        }
        // Check for stackable plants
        if (STACKABLE.contains(b.getType())) {
            // Check for blocks above
            Block block = b;
            while(block.getRelative(BlockFace.UP).getType().equals(mat) && block.getY() < b.getWorld().getMaxHeight()) {
                block = block.getRelative(BlockFace.UP);
                process(block, false, mat);
            }
        }
        notify(e, User.getInstance(player), process(b, false, mat), mat);
        // Player breaks a block and there was a redstone dust/repeater/... above
        if (b.getRelative(BlockFace.UP).getType() == Material.REDSTONE_WIRE || b.getRelative(BlockFace.UP).getType() == Material.REPEATER || b.getRelative(BlockFace.UP).getType() == Material.COMPARATOR || b.getRelative(BlockFace.UP).getType() == Material.REDSTONE_TORCH) {
            process(b.getRelative(BlockFace.UP), false);
        }
        if (b.getRelative(BlockFace.EAST).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.EAST), false);
        }
        if (b.getRelative(BlockFace.WEST).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.WEST), false);
        }
        if (b.getRelative(BlockFace.SOUTH).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.SOUTH), false);
        }
        if (b.getRelative(BlockFace.NORTH).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.NORTH), false);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockMultiPlaceEvent e) {
        notify(e, User.getInstance(e.getPlayer()), process(e.getBlock(), true), e.getBlock().getType());
    }

    private void notify(Cancellable e, User user, int limit, Material m) {
        if (limit > -1) {
            user.sendMessage("block-limits.hit-limit",
                    "[material]", Util.prettifyText(m.toString()),
                    TextVariables.NUMBER, String.valueOf(limit));
            e.setCancelled(true);
        }
    }

    // Non-player events
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockBurnEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockExplodeEvent e) {
        e.blockList().forEach(b -> process(b, false));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFadeEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFormEvent e) {
        process(e.getBlock(), true);
    }

    /*
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockGrowEvent e) {
        Bukkit.getLogger().info(e.getEventName());
        process(e.getBlock(), true);
    }
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockSpreadEvent e) {
        Bukkit.getLogger().info(e.getEventName());
        process(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityBlockFormEvent e) {
        Bukkit.getLogger().info(e.getEventName());
        process(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(LeavesDecayEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityExplodeEvent e) {
        e.blockList().forEach(b -> process(b, false));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityChangeBlockEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFromToEvent e) {
        if (e.getBlock().isLiquid()) {
            if (e.getToBlock().getType() == Material.REDSTONE_WIRE || e.getToBlock().getType() == Material.REPEATER || e.getToBlock().getType() == Material.COMPARATOR || e.getToBlock().getType() == Material.REDSTONE_TORCH || e.getToBlock().getType() == Material.REDSTONE_WALL_TORCH) {
                process(e.getToBlock(), false);
            }
        }
    }

    private int process(Block b, boolean add) {
        return process(b, add, b.getType());
    }

    // Return equivalents.
    public Material fixMaterial(Material b) {
        switch (b) {
        case REDSTONE_WALL_TORCH:
            return Material.REDSTONE_TORCH;
        case WALL_TORCH:
            return Material.TORCH;
        case ZOMBIE_WALL_HEAD:
            return Material.ZOMBIE_HEAD;
        case CREEPER_WALL_HEAD:
            return Material.CREEPER_HEAD;
        case PLAYER_WALL_HEAD:
            return Material.PLAYER_HEAD;
        case DRAGON_WALL_HEAD:
            return Material.DRAGON_HEAD;
        case BAMBOO_SAPLING:
            return Material.BAMBOO;
        default:
            return b;
        }
    }

    /**
     * Check if a block can be
     *
     * @param b - block
     * @param add - true to add a block, false to remove
     * @param changeTo - material this block will become
     * @return limit amount if over limit, or -1 if no limitation
     */
    private int process(Block b, boolean add, Material changeTo) {
        if (DO_NOT_COUNT.contains(fixMaterial(b.getType())) || !addon.inGameModeWorld(b.getWorld())) {
            return -1;
        }
        // Check if on island
        return addon.getIslands().getIslandAt(b.getLocation()).map(i -> {
            String id = i.getUniqueId();
            String gameMode = addon.getGameModeName(b.getWorld());
            if (gameMode.isEmpty()) {
                // Invalid world
                return -1;
            }
            islandCountMap.putIfAbsent(id, new IslandBlockCount(id, gameMode));
            saveMap.putIfAbsent(id, 0);
            if (add) {
                // Check limit
                int limit = checkLimit(b.getWorld(), fixMaterial(b.getType()), id);
                if (limit > -1) {
                    return limit;
                }
                islandCountMap.get(id).add(fixMaterial(b.getType()));
                saveMap.merge(id, 1, Integer::sum);
            } else {
                if (islandCountMap.containsKey(id)) {
                    // Check for changes
                    Material fixed = fixMaterial(changeTo);
                    if (!fixed.equals(fixMaterial(b.getType())) && fixed.isBlock() && !DO_NOT_COUNT.contains(fixed)) {
                        // Check limit
                        int limit = checkLimit(b.getWorld(), fixed, id);
                        if (limit > -1) {
                            return limit;
                        }
                        islandCountMap.get(id).add(fixed);
                    }
                    islandCountMap.get(id).remove(fixMaterial(b.getType()));
                    saveMap.merge(id, 1, Integer::sum);
                }
            }
            if (saveMap.get(id) > CHANGE_LIMIT) {
                handler.saveObject(islandCountMap.get(id));
                saveMap.remove(id);
            }
            return -1;
        }).orElse(-1);
    }

    /**
     * Check if this material is at its limit for world on this island
     *
     * @param w - world
     * @param m - material
     * @param id - island id
     * @return limit amount if at limit or -1 if no limit
     */
    private int checkLimit(World w, Material m, String id) {
        // Check island limits
        IslandBlockCount island = islandCountMap.get(id);
        if (island.isBlockLimited(m)) {
            return island.isAtLimit(m) ? island.getBlockLimit(m) : -1;
        }
        // Check specific world limits
        if (worldLimitMap.containsKey(w) && worldLimitMap.get(w).containsKey(m)) {
            // Material is overridden in world
            return island.isAtLimit(m, worldLimitMap.get(w).get(m)) ? worldLimitMap.get(w).get(m) : -1;
        }
        // Check default limit map
        if (defaultLimitMap.containsKey(m) && island.isAtLimit(m, defaultLimitMap.get(m))) {
            return defaultLimitMap.get(m);
        }
        // No limit
        return -1;
    }

    /**
     * Gets an aggregate map of the limits for this island
     *
     * @param w - world
     * @param id - island id
     * @return map of limits for materials
     */
    public Map<Material, Integer> getMaterialLimits(World w, String id) {
        // Merge limits
        Map<Material, Integer> result = new HashMap<>();
        // Default
        defaultLimitMap.forEach(result::put);
        // World
        if (worldLimitMap.containsKey(w)) {
            worldLimitMap.get(w).forEach(result::put);
        }
        // Island
        if (islandCountMap.containsKey(id)) {
            islandCountMap.get(id).getBlockLimits().forEach(result::put);
        }
        return result;
    }

    /**
     * Removes island from the database
     *
     * @param e - island delete event
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onIslandDelete(IslandDeleteEvent e) {
        islandCountMap.remove(e.getIsland().getUniqueId());
        saveMap.remove(e.getIsland().getUniqueId());
        if (handler.objectExists(e.getIsland().getUniqueId())) {
            handler.deleteID(e.getIsland().getUniqueId());
        }
    }

    /**
     * Set the island block count values
     *
     * @param islandId - island unique id
     * @param ibc - island block count
     */
    public void setIsland(String islandId, IslandBlockCount ibc) {
        islandCountMap.put(islandId, ibc);
        handler.saveObject(ibc);
    }

    /**
     * Get the island block count
     *
     * @param islandId - island unique id
     * @return island block count or null if there is none yet
     */
    public IslandBlockCount getIsland(String islandId) {
        return islandCountMap.get(islandId);
    }

}
