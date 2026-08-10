package dev.denismasterherobrine.sablecontraptionsfix.coasters;

import dev.denismasterherobrine.sablecontraptionsfix.SableContraptionsFixConfig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CoastersSimulatedPerfGuard {
    private static final Map<ResourceKey<Level>, Long> LAST_ALLOWED_LINK_RESTORE_TICK = new ConcurrentHashMap<>();

    private CoastersSimulatedPerfGuard() {
    }

    public static boolean shouldDisableClientRailSoundCache(final Level level) {
        if (!SableContraptionsFixConfig.ENABLE_COASTERS_SIMULATED_PERF_PATCH.get()) {
            return false;
        }

        return subLevelCount(level) >= SableContraptionsFixConfig.COASTERS_CLIENT_RAIL_SOUND_SCAN_MAX_SUB_LEVELS.get();
    }

    public static boolean shouldSkipServerLinkRestore(final ServerLevel level) {
        if (!SableContraptionsFixConfig.ENABLE_COASTERS_SIMULATED_PERF_PATCH.get()) {
            return false;
        }

        if (subLevelCount(level) < SableContraptionsFixConfig.COASTERS_SERVER_LINK_RESTORE_THROTTLE_MIN_SUB_LEVELS.get()) {
            return false;
        }

        final int interval = SableContraptionsFixConfig.COASTERS_SERVER_LINK_RESTORE_INTERVAL_TICKS.get();
        if (interval <= 1) {
            return false;
        }

        final ResourceKey<Level> dimension = level.dimension();
        final long gameTime = level.getGameTime();
        final Long lastAllowed = LAST_ALLOWED_LINK_RESTORE_TICK.get(dimension);
        if (lastAllowed != null && gameTime - lastAllowed < interval) {
            return true;
        }

        LAST_ALLOWED_LINK_RESTORE_TICK.put(dimension, gameTime);
        return false;
    }

    public static int subLevelCount(final Level level) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        return container.getAllSubLevels().size();
    }
}
