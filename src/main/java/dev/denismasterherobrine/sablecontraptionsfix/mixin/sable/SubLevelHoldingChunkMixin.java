package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.denismasterherobrine.sablecontraptionsfix.SableStorageDiagnostics;
import dev.denismasterherobrine.sablecontraptionsfix.optimization.SableHighLoadOptimizer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.denismasterherobrine.sablecontraptionsfix.duck.SableHoldingChunkExtension;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class SubLevelHoldingChunkMixin implements SableHoldingChunkExtension {
    @Shadow @Final private Object2ObjectMap<UUID, HoldingSubLevel> loadedHoldingSubLevels;
    @Shadow @Final ObjectOpenHashSet<UUID> visitedSet;

    @Shadow private static boolean canLoadSubLevel(final ServerLevel level, final SubLevelData data) {
        throw new AssertionError();
    }

    @Unique
    private final ObjectList<HoldingSubLevel> sablecontraptionsfix$pendingLoadChain = new ObjectArrayList<>();
    @Unique
    private final Object2LongOpenHashMap<UUID> sablecontraptionsfix$nextDependencyRetryTick = new Object2LongOpenHashMap<>();
    @Unique
    private final ObjectOpenHashSet<UUID> sablecontraptionsfix$scratchDependencyChain = new ObjectOpenHashSet<>(4096);

    @Unique
    private long sablecontraptionsfix$nextReadyScanTick;

    /**
     * Remove a holding entry only after the caller has loaded it successfully.
     * Failed loads must stay available for retry and storage repair.
     *
     * @author DenisMasterHerobrine
     * @reason Holding entries must stay recoverable until load succeeds.
     */
    @Override
    public HoldingSubLevel sablecontraptionsfix$removeLoadedHoldingSubLevel(final UUID uuid) {
        return this.loadedHoldingSubLevels.remove(uuid);
    }

    @Override
    public void sablecontraptionsfix$resetReadyScanBackoff() {
        this.sablecontraptionsfix$nextReadyScanTick = 0L;
        this.sablecontraptionsfix$nextDependencyRetryTick.clear();
    }

    /**
     * Collect a loadable dependency closure across all known holding chunks.
     * Sable's original single-chunk check could strand cyclic dependency groups.
     *
     * @author DenisMasterHerobrine
     * @reason Dependency groups can span chunks and must load as one closure.
     */
    @Override
    public void sablecontraptionsfix$collectReadySubLevels(final ServerLevel level,
                                                           final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels,
                                                           final Object2ObjectMap<UUID, HoldingSubLevel> readySubLevels,
                                                           final ObjectSet<UUID> processedSubLevelIds) {
        if (this.loadedHoldingSubLevels.isEmpty()) {
            return;
        }

        this.visitedSet.clear();

        final long gameTime = level.getGameTime();
        final int totalHoldingSubLevels = allHoldingSubLevels.size();
        final boolean bootstrapFastLoad = SableHighLoadOptimizer.isBootstrapFastLoad(level, allHoldingSubLevels);
        final long readyScanInterval = SableHighLoadOptimizer.holdingReadyScanIntervalTicks(totalHoldingSubLevels, bootstrapFastLoad);
        final ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
        if (readyScanInterval > 0L && this.sablecontraptionsfix$nextReadyScanTick > gameTime) {
            return;
        }

        final int readyCountBefore = readySubLevels.size();
        for (final HoldingSubLevel holdingSubLevel : this.loadedHoldingSubLevels.values()) {
            final UUID uuid = holdingSubLevel.data().uuid();
            if (processedSubLevelIds.contains(uuid) || this.visitedSet.contains(uuid) || readySubLevels.containsKey(uuid)) {
                continue;
            }

            final ObjectOpenHashSet<UUID> chain = this.sablecontraptionsfix$scratchDependencyChain;
            chain.clear();
            if (!this.sablecontraptionsfix$collectLoadableDependencyChain(level, container, bootstrapFastLoad, holdingSubLevel, allHoldingSubLevels, chain)) {
                this.visitedSet.addAll(chain);
                processedSubLevelIds.addAll(chain);
                this.sablecontraptionsfix$delayDependencyChainRetry(chain, gameTime, totalHoldingSubLevels, bootstrapFastLoad);
                continue;
            }

            this.visitedSet.addAll(chain);
            processedSubLevelIds.addAll(chain);
            this.sablecontraptionsfix$clearDependencyChainRetry(chain);
            for (final UUID chainedUuid : chain) {
                final HoldingSubLevel chainedSubLevel = allHoldingSubLevels.get(chainedUuid);
                if (chainedSubLevel != null) {
                    readySubLevels.put(chainedUuid, chainedSubLevel);
                }
            }
        }


        if (readyScanInterval > 0L) {
            this.sablecontraptionsfix$nextReadyScanTick = gameTime + readyScanInterval;
        }
        this.sablecontraptionsfix$pendingLoadChain.clear();
    }

    /**
     * Walk dependency UUIDs without recursion.
     * A missing UUID means storage must be scanned before the group can load.
     *
     * @author DenisMasterHerobrine
     * @reason Missing dependencies must stop loading and trigger recovery.
     */
    @Unique
    private boolean sablecontraptionsfix$collectLoadableDependencyChain(final ServerLevel level,
                                                                        final ServerSubLevelContainer container,
                                                                        final boolean bootstrapFastLoad,
                                                                        final HoldingSubLevel root,
                                                                        final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels,
                                                                        final ObjectSet<UUID> chain) {
        this.sablecontraptionsfix$pendingLoadChain.clear();
        this.sablecontraptionsfix$pendingLoadChain.add(root);

        while (!this.sablecontraptionsfix$pendingLoadChain.isEmpty()) {
            final HoldingSubLevel holdingSubLevel = this.sablecontraptionsfix$pendingLoadChain.remove(this.sablecontraptionsfix$pendingLoadChain.size() - 1);
            final SubLevelData data = holdingSubLevel.data();
            final UUID uuid = data.uuid();

            if (!chain.add(uuid)) {
                continue;
            }

            if (!SableHighLoadOptimizer.shouldBypassChunkReadiness(bootstrapFastLoad) && !canLoadSubLevel(level, data)) {
                return false;
            }

            for (final UUID dependencyUuid : data.dependencies()) {
                final HoldingSubLevel dependencySubLevel = allHoldingSubLevels.get(dependencyUuid);

                if (dependencySubLevel == null) {
                    if (SableHighLoadOptimizer.isDependencyAlreadyLoaded(container, dependencyUuid)) {
                        continue;
                    }
                    SableStorageDiagnostics.reportMissingDependency(uuid, dependencyUuid);
                    return false;
                }

                this.sablecontraptionsfix$pendingLoadChain.add(dependencySubLevel);
            }
        }

        return true;
    }

    @Unique
    private void sablecontraptionsfix$delayDependencyChainRetry(final ObjectSet<UUID> chain, final long gameTime, final int totalHoldingSubLevels, final boolean bootstrapFastLoad) {
        final long nextRetry = gameTime + SableHighLoadOptimizer.dependencyRetryIntervalTicks(totalHoldingSubLevels, bootstrapFastLoad);
        for (final UUID uuid : chain) {
            this.sablecontraptionsfix$nextDependencyRetryTick.put(uuid, nextRetry);
        }
    }

    @Unique
    private void sablecontraptionsfix$clearDependencyChainRetry(final ObjectSet<UUID> chain) {
        for (final UUID uuid : chain) {
            this.sablecontraptionsfix$nextDependencyRetryTick.removeLong(uuid);
        }
    }
}
