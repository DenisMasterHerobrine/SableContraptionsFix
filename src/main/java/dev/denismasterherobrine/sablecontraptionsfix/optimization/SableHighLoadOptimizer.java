package dev.denismasterherobrine.sablecontraptionsfix.optimization;

import dev.denismasterherobrine.sablecontraptionsfix.SableContraptionsFixConfig;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.UUID;

public final class SableHighLoadOptimizer {
    private static final Object STATE_LOCK = new Object();
    private static final IdentityHashMap<Object2ObjectMap<UUID, HoldingSubLevel>, OptimizationState> STATES = new IdentityHashMap<>();

    private SableHighLoadOptimizer() {
    }

    public static boolean enabled() {
        return SableContraptionsFixConfig.ENABLE_SABLE_HIGH_LOAD_OPTIMIZATIONS.get();
    }

    public static boolean shouldThrottleHoldingStorage(final int holdingSubLevelCount) {
        return enabled() && holdingSubLevelCount >= SableContraptionsFixConfig.SABLE_OPTIMIZATION_MIN_HOLDING_SUB_LEVELS.get();
    }

    public static boolean isBootstrapFastLoad(final ServerLevel level,
                                              final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels) {
        if (!enabled() || allHoldingSubLevels.isEmpty()) {
            return false;
        }

        if (SableContraptionsFixConfig.SABLE_BOOTSTRAP_REQUIRE_PLAYER.get() && level.players().isEmpty()) {
            return false;
        }

        final OptimizationState state = stateFor(level, allHoldingSubLevels);
        return level.getGameTime() < state.bootstrapEndTick;
    }

    public static long holdingReadyScanIntervalTicks(final int holdingSubLevelCount, final boolean bootstrapFastLoad) {
        if (bootstrapFastLoad || !shouldThrottleHoldingStorage(holdingSubLevelCount)) {
            return 0L;
        }
        return SableContraptionsFixConfig.SABLE_HOLDING_READY_SCAN_INTERVAL_TICKS.get();
    }

    public static long dependencyRetryIntervalTicks(final int holdingSubLevelCount, final boolean bootstrapFastLoad) {
        if (bootstrapFastLoad || !shouldThrottleHoldingStorage(holdingSubLevelCount)) {
            return 1L;
        }
        return SableContraptionsFixConfig.SABLE_HOLDING_DEPENDENCY_RETRY_INTERVAL_TICKS.get();
    }

    public static int maxHoldingLoadsPerTick(final int holdingSubLevelCount, final boolean bootstrapFastLoad) {
        if (bootstrapFastLoad) {
            return SableContraptionsFixConfig.SABLE_BOOTSTRAP_MAX_LOADS_PER_TICK.get();
        }
        if (!shouldThrottleHoldingStorage(holdingSubLevelCount)) {
            return Integer.MAX_VALUE;
        }
        return SableContraptionsFixConfig.SABLE_HOLDING_MAX_LOADS_PER_TICK.get();
    }
    public static boolean shouldThrottleLoadedPhysics(final int loadedSubLevelCount) {
        return enabled() && loadedSubLevelCount >= SableContraptionsFixConfig.SABLE_PHYSICS_SUBSTEP_THROTTLE_MIN_SUB_LEVELS.get();
    }

    public static int physicsSubstepsPerTick(final int loadedSubLevelCount, final int requestedSubsteps) {
        final int sanitizedRequestedSubsteps = Math.max(1, requestedSubsteps);
        if (!shouldThrottleLoadedPhysics(loadedSubLevelCount)) {
            return sanitizedRequestedSubsteps;
        }
        return Math.max(1, Math.min(sanitizedRequestedSubsteps, SableContraptionsFixConfig.SABLE_PHYSICS_MAX_SUBSTEPS_PER_TICK.get()));
    }
    public static int physicsSolverIterations(final int loadedSubLevelCount, final int requestedIterations) {
        return cappedPhysicsIterationCount(loadedSubLevelCount, requestedIterations, SableContraptionsFixConfig.SABLE_PHYSICS_MAX_SOLVER_ITERATIONS.get());
    }

    public static int physicsPgsIterations(final int loadedSubLevelCount, final int requestedIterations) {
        return cappedPhysicsIterationCount(loadedSubLevelCount, requestedIterations, SableContraptionsFixConfig.SABLE_PHYSICS_MAX_PGS_ITERATIONS.get());
    }

    public static int physicsStabilizationIterations(final int loadedSubLevelCount, final int requestedIterations) {
        return cappedPhysicsIterationCount(loadedSubLevelCount, requestedIterations, SableContraptionsFixConfig.SABLE_PHYSICS_MAX_STABILIZATION_ITERATIONS.get());
    }

    private static int cappedPhysicsIterationCount(final int loadedSubLevelCount, final int requestedIterations, final int configuredCap) {
        final int sanitizedRequestedIterations = Math.max(1, requestedIterations);
        if (!shouldThrottleLoadedPhysics(loadedSubLevelCount)) {
            return sanitizedRequestedIterations;
        }
        return Math.max(1, Math.min(sanitizedRequestedIterations, configuredCap));
    }

    public static boolean shouldCooldownWorldBorderClamp(final int loadedSubLevelCount) {
        return shouldThrottleLoadedPhysics(loadedSubLevelCount) && SableContraptionsFixConfig.SABLE_WORLD_BORDER_CLAMP_COOLDOWN_TICKS.get() > 0;
    }

    public static boolean shouldBypassChunkReadiness(final boolean bootstrapFastLoad) {
        return bootstrapFastLoad && SableContraptionsFixConfig.SABLE_BOOTSTRAP_IGNORE_CHUNK_READINESS.get();
    }

    public static boolean shouldRecoverMissingDependencies(final ServerLevel level,
                                                           final ServerSubLevelContainer container,
                                                           final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels) {
        if (!shouldThrottleHoldingStorage(allHoldingSubLevels.size())) {
            return hasMissingDependency(container, allHoldingSubLevels);
        }

        final long gameTime = level.getGameTime();
        final long interval = SableContraptionsFixConfig.SABLE_MISSING_DEPENDENCY_CHECK_INTERVAL_TICKS.get();
        final OptimizationState state = stateFor(level, allHoldingSubLevels);
        synchronized (STATE_LOCK) {
            if (gameTime < state.nextMissingDependencyScanTick) {
                return false;
            }

            state.nextMissingDependencyScanTick = gameTime + interval;
        }
        return hasMissingDependency(container, allHoldingSubLevels);
    }

    public static boolean isDependencyAlreadyLoaded(final ServerSubLevelContainer container, final UUID uuid) {
        return container != null && container.getSubLevel(uuid) != null;
    }

    private static boolean hasMissingDependency(final ServerSubLevelContainer container,
                                                final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels) {
        for (final HoldingSubLevel holdingSubLevel : allHoldingSubLevels.values()) {
            for (final UUID dependency : holdingSubLevel.data().dependencies()) {
                if (!allHoldingSubLevels.containsKey(dependency) && !isDependencyAlreadyLoaded(container, dependency)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static OptimizationState stateFor(final ServerLevel level,
                                              final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels) {
        synchronized (STATE_LOCK) {
            OptimizationState state = STATES.get(allHoldingSubLevels);
            if (state == null) {
                state = new OptimizationState(level.getGameTime() + SableContraptionsFixConfig.SABLE_BOOTSTRAP_FAST_LOAD_TICKS.get());
                STATES.put(allHoldingSubLevels, state);
            }
            return state;
        }
    }

    private static final class OptimizationState {
        private final long bootstrapEndTick;
        private long nextMissingDependencyScanTick;

        private OptimizationState(final long bootstrapEndTick) {
            this.bootstrapEndTick = bootstrapEndTick;
        }
    }
}
