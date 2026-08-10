package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.denismasterherobrine.sablecontraptionsfix.SableStorageDiagnostics;
import dev.denismasterherobrine.sablecontraptionsfix.optimization.SableHighLoadOptimizer;
import dev.denismasterherobrine.sablecontraptionsfix.duck.SableHoldingChunkExtension;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SubLevelHoldingChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private ServerSubLevelContainer container;
    @Shadow @Final private SubLevelStorage storage;
    @Shadow @Final private Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels;
    @Shadow @Final private Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks;
    @Shadow @Final private ObjectSet<ChunkPos> queuedUnloads;
    @Shadow private boolean verboseLogging;
    @Unique
    private boolean sablecontraptionsfix$storageRecoveryScanned;

    @Shadow private void processUnloads() {
        throw new AssertionError();
    }

    @Shadow private void setDirty(final ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Shadow @Nullable private SubLevelHoldingChunk getOrLoadHoldingChunk(final ChunkPos chunkPos, final boolean create) {
        throw new AssertionError();
    }

    /**
     * Run storage recovery before Sable checks which holding entries can load.
     * This lets cyclic dependency groups load as one complete group.
     *
     * @author DenisMasterHerobrine
     * @reason Load dependency groups atomically across holding chunks and recover missing in-memory dependencies from storage.
     */
    @Overwrite
    public void processChanges() {
        this.verboseLogging = SableConfig.VERBOSE_SERIALIZATION_LOGGING.get();
        this.processUnloads();

        if (!this.sablecontraptionsfix$storageRecoveryScanned) {
            this.sablecontraptionsfix$storageRecoveryScanned = true;
            SableStorageDiagnostics.logStack("Running one-time Sable storage recovery scan before loading holding sub-levels.");
            this.sablecontraptionsfix$loadAllPersistedHoldingChunks();
            this.sablecontraptionsfix$recoverOrphanedStorageEntries();
        } else if (SableHighLoadOptimizer.shouldRecoverMissingDependencies(this.level, this.container, this.allHoldingSubLevels)) {
            SableStorageDiagnostics.logStack("Detected Sable holding dependency missing from loaded holding chunks; scanning persisted holding and raw storage before deciding the group is unloadable.");
            this.sablecontraptionsfix$loadAllPersistedHoldingChunks();
            this.sablecontraptionsfix$recoverOrphanedStorageEntries();
        }

        final boolean bootstrapFastLoad = SableHighLoadOptimizer.isBootstrapFastLoad(this.level, this.allHoldingSubLevels);

        final Object2ObjectMap<UUID, HoldingSubLevel> readySubLevels = new Object2ObjectOpenHashMap<>();
        final ObjectOpenHashSet<UUID> processedSubLevelIds = new ObjectOpenHashSet<>(this.allHoldingSubLevels.size());

        for (final SubLevelHoldingChunk chunk : this.loadedHoldingChunks.values()) {
            if (this.queuedUnloads.contains(chunk.getChunkPos())) {
                continue;
            }
            ((SableHoldingChunkExtension) chunk).sablecontraptionsfix$collectReadySubLevels(this.level, this.allHoldingSubLevels, readySubLevels, processedSubLevelIds);
        }


        final int maxLoadsThisTick = SableHighLoadOptimizer.maxHoldingLoadsPerTick(this.allHoldingSubLevels.size(), bootstrapFastLoad);
        final int loadLimit = Math.min(readySubLevels.size(), maxLoadsThisTick);
        if (loadLimit > 0) {
            SableStorageDiagnostics.logStack("Loading {} complete Sable holding dependency group members{}.", loadLimit, loadLimit < readySubLevels.size() ? " (deferred " + (readySubLevels.size() - loadLimit) + " ready members)" : "");
        }

        int loadedThisTick = 0;
        for (final HoldingSubLevel holdingSubLevel : readySubLevels.values()) {
            if (loadedThisTick >= maxLoadsThisTick) {
                break;
            }

            /**
             * Per-object load logging is intentionally suppressed.
             * Large stress tests can load thousands of entries in one pass.
             *
             * @author DenisMasterHerobrine
             * @reason Holding load diagnostics must stay bounded during stress tests.
             */
            this.loadHoldingSubLevel(holdingSubLevel);
            loadedThisTick++;
        }
    }

    /**
     * Force-load the whole dependency closure, not only entries in one chunk.
     * This keeps linked builds from loading as partial broken groups.
     *
     * @author DenisMasterHerobrine
     * @reason Force-loaded sub-levels can have dependencies in other holding chunks.
     */
    @Overwrite
    public void snatchAndLoad(final GlobalSavedSubLevelPointer pointer, final UUID subLevelId) {
        final ChunkPos chunkPos = pointer.chunkPos();
        final SubLevelHoldingChunk holdingChunk = this.getOrLoadHoldingChunk(chunkPos, false);

        if (holdingChunk == null) {
            SableStorageDiagnostics.logStack("Attempted to snatch Sable sub-level {} stored at {}, but no holding chunk exists at the pointer chunk position. Scanning storage once before giving up.", subLevelId, pointer);
            this.sablecontraptionsfix$loadAllPersistedHoldingChunks();
        }

        final HoldingSubLevel root = this.allHoldingSubLevels.get(subLevelId);
        if (root == null) {
            SableStorageDiagnostics.logStack("Attempted to snatch Sable sub-level {} stored at {}, but it was not present after storage scan.", subLevelId, pointer);
            return;
        }

        final Object2ObjectMap<UUID, HoldingSubLevel> readySubLevels = new Object2ObjectOpenHashMap<>();
        final SableHoldingChunkExtension ownerChunk = (SableHoldingChunkExtension) this.sablecontraptionsfix$getOwnerChunk(root);
        ownerChunk.sablecontraptionsfix$resetReadyScanBackoff();
        ownerChunk.sablecontraptionsfix$collectReadySubLevels(this.level, this.allHoldingSubLevels, readySubLevels, new ObjectOpenHashSet<>());

        if (!readySubLevels.containsKey(subLevelId)) {
            SableStorageDiagnostics.logStack("Sable force-load snatch for {} at {} did not produce a loadable dependency closure. root={}", subLevelId, pointer, SableStorageDiagnostics.describe(root));
            return;
        }

        for (final HoldingSubLevel holdingSubLevel : readySubLevels.values()) {
            this.loadHoldingSubLevel(holdingSubLevel);
        }
        this.setDirty(chunkPos);
    }

    /**
     * Stop chunk unload when a player is inside any sub-level in the dependency chain.
     * The unload would hide the build while the player still needs it active.
     *
     * @author DenisMasterHerobrine
     * @reason Player-occupied dependency chains must not move to holding storage.
     */
    @Inject(method = "processUnload", at = @At("HEAD"), cancellable = true)
    private void sablecontraptionsfix$blockPlayerOccupiedProcessUnload(final ChunkPos chunkPos,
                                                                       final Collection<ServerSubLevel> forceLoaded,
                                                                       final CallbackInfo ci) {
        final BoundingBox3d bounds = new BoundingBox3d(chunkPos.x << 4, -Double.MAX_VALUE, chunkPos.z << 4, (chunkPos.x << 4) + 16, Double.MAX_VALUE, (chunkPos.z << 4) + 16);
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        if (container == null) {
            return;
        }

        for (final SubLevel subLevel : container.queryIntersecting(bounds)) {
            final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;
            if (forceLoaded.contains(serverSubLevel)) {
                continue;
            }

            final Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(serverSubLevel);
            for (final ServerSubLevel chainedSubLevel : chain) {
                if (SableStorageDiagnostics.shouldProtectUnload(chainedSubLevel)) {
                    SableStorageDiagnostics.logProtectedUnload("processUnload chunk " + chunkPos + " dependency chain", chainedSubLevel);
                    ci.cancel();
                    return;
                }
            }
        }
    }



    /**
     * Remove the holding entry only after fullyLoad succeeds.
     * If loading fails, the pointer must stay on disk for a later retry.
     *
     * @author DenisMasterHerobrine
     * @reason Never drop persisted holding pointers before a successful load.
     */
    @Overwrite
    public void loadHoldingSubLevel(final HoldingSubLevel holdingSubLevel) {
        final SubLevelData data = holdingSubLevel.data();
        final ServerSubLevel subLevel = SubLevelSerializer.fullyLoad(this.level, data);

        if (subLevel != null) {
            subLevel.setLastSerializationPointer(holdingSubLevel.pointer());
            this.sablecontraptionsfix$releaseHoldingSubLevel(data.uuid());
            return;
        }

        final MinecraftServer server = this.level.getServer();
        if (server instanceof final dev.ryanhcode.sable.mixinterface.toast.SableToastableServer toastable) {
            toastable.sable$reportSubLevelLoadFailure(holdingSubLevel.pointer());
        }

        SableStorageDiagnostics.logStack("Sable SubLevelSerializer.fullyLoad returned null for uuid={}, bounds={}, deps={}, originChunk={}, pointer={}. Keeping holding pointer for retry/recovery.", data.uuid(), data.bounds(), data.dependencies(), data.getOriginLoadedChunk(), holdingSubLevel.pointer());
    }

    /**
     * Remove one loaded UUID from both indexes.
     * Dirtying the owner chunk makes the saved pointer list match memory.
     *
     * @author DenisMasterHerobrine
     * @reason Loaded entries must be removed from holding storage consistently.
     */
    @Unique
    private void sablecontraptionsfix$releaseHoldingSubLevel(final UUID uuid) {
        this.allHoldingSubLevels.remove(uuid);
        for (final SubLevelHoldingChunk chunk : this.loadedHoldingChunks.values()) {
            final HoldingSubLevel removed = ((SableHoldingChunkExtension) chunk).sablecontraptionsfix$removeLoadedHoldingSubLevel(uuid);
            if (removed != null) {
                this.setDirty(chunk.getChunkPos());
                return;
            }
        }
    }

    /**
     * Find the holding chunk that owns a holding sub-level.
     * Force-load uses this chunk to collect and mark the right closure.
     *
     * @author DenisMasterHerobrine
     * @reason Force-load needs the real owner chunk for dirty tracking.
     */
    @Unique
    private SubLevelHoldingChunk sablecontraptionsfix$getOwnerChunk(final HoldingSubLevel target) {
        for (final SubLevelHoldingChunk chunk : this.loadedHoldingChunks.values()) {
            for (final HoldingSubLevel holdingSubLevel : chunk.getLoadedHoldingSubLevels()) {
                if (holdingSubLevel.data().uuid().equals(target.data().uuid())) {
                    return chunk;
                }
            }
        }
        throw new IllegalStateException("No owner holding chunk for " + SableStorageDiagnostics.describe(target));
    }

    /**
     * Load every saved .slvlr holding chunk into memory.
     * Missing in-memory dependencies can live in unloaded holding chunks.
     *
     * @author DenisMasterHerobrine
     * @reason Recovery must see all saved holding entries before it decides a dependency is missing.
     */
    @Unique
    private void sablecontraptionsfix$loadAllPersistedHoldingChunks() {
        final File[] regionFiles = this.storage.getFolder().toFile().listFiles((dir, name) -> name.endsWith(SubLevelRegionFile.FILE_EXTENSION));
        if (regionFiles == null) {
            return;
        }

        int loaded = 0;
        for (final File regionFile : regionFiles) {
            final String fileName = regionFile.getName();
            final String withoutExtension = fileName.substring(0, fileName.length() - SubLevelRegionFile.FILE_EXTENSION.length());
            final String[] parts = withoutExtension.split("\\.");
            if (parts.length != 3 || !"r".equals(parts[0])) {
                continue;
            }

            final int regionX;
            final int regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (final NumberFormatException ignored) {
                continue;
            }

            for (int localX = 0; localX < SubLevelRegionFile.SIDE_LENGTH; localX++) {
                for (int localZ = 0; localZ < SubLevelRegionFile.SIDE_LENGTH; localZ++) {
                    final ChunkPos chunkPos = new ChunkPos(
                            regionX * SubLevelRegionFile.SIDE_LENGTH + localX,
                            regionZ * SubLevelRegionFile.SIDE_LENGTH + localZ
                    );
                    if (!this.loadedHoldingChunks.containsKey(chunkPos.toLong()) && this.getOrLoadHoldingChunk(chunkPos, false) != null) {
                        loaded++;
                    }
                }
            }
        }

        SableStorageDiagnostics.logStack("Sable storage dependency recovery scan loaded {} additional holding chunks; allHoldingSubLevels={}", loaded, this.allHoldingSubLevels.size());
    }

    /**
     * Scan raw .slvls files for entries that lost their .slvlr pointer.
     * Recovered entries are attached to a valid holding chunk and saved later.
     *
     * @author DenisMasterHerobrine
     * @reason Orphaned storage entries can still contain valid physical builds.
     */
    @Unique
    private void sablecontraptionsfix$recoverOrphanedStorageEntries() {
        final File[] storageFiles = this.storage.getFolder().toFile().listFiles((dir, name) -> name.endsWith(SubLevelStorageFile.FILE_EXTENSION));
        if (storageFiles == null) {
            return;
        }

        int recovered = 0;
        int skipped = 0;

        for (final File storageFile : storageFiles) {
            final int[] parsed = this.sablecontraptionsfix$parseStorageFileName(storageFile.getName());
            if (parsed == null || parsed[2] < 0 || parsed[2] > Short.MAX_VALUE) {
                continue;
            }

            final int regionX = parsed[0];
            final int regionZ = parsed[1];
            final int storageIndex = parsed[2];

            final Path indexedExternalPath = this.sablecontraptionsfix$getIndexedExternalPath(regionX, regionZ, storageIndex);
            final Path legacyExternalPath = this.sablecontraptionsfix$getLegacyExternalPath(regionX, regionZ);

            try (final SubLevelStorageFile indexed = new SubLevelStorageFile(storageFile.toPath(), indexedExternalPath)) {
                final SubLevelStorageFile legacy = Files.isDirectory(legacyExternalPath) && !legacyExternalPath.equals(indexedExternalPath)
                        ? new SubLevelStorageFile(storageFile.toPath(), legacyExternalPath)
                        : null;
                try {
                    for (int subLevelIndex = 0; subLevelIndex < indexed.getTotalIndexCapacity(); subLevelIndex++) {
                        final CompoundTag tag = this.sablecontraptionsfix$readStoredSubLevel(indexed, legacy, subLevelIndex, storageFile);
                        if (tag == null) {
                            continue;
                        }

                        final SubLevelData data = SubLevelSerializer.fromData(tag);
                        if (data == null) {
                            skipped++;
                            continue;
                        }

                        if (this.allHoldingSubLevels.containsKey(data.uuid()) || this.container.getSubLevel(data.uuid()) != null) {
                            continue;
                        }

                        final ChunkPos ownerChunk = this.sablecontraptionsfix$findExistingPointerOwner(regionX, regionZ, storageIndex, subLevelIndex);
                        final ChunkPos recoveredChunk = ownerChunk != null
                                ? ownerChunk
                                : this.sablecontraptionsfix$chooseRecoveredHoldingChunk(regionX, regionZ, data);
                        data.setOriginLoadedChunk(recoveredChunk);

                        final GlobalSavedSubLevelPointer pointer = new GlobalSavedSubLevelPointer(recoveredChunk, (short) storageIndex, (short) subLevelIndex);
                        final SubLevelHoldingChunk holdingChunk = this.getOrLoadHoldingChunk(recoveredChunk, true);
                        if (!holdingChunk.getSubLevelPointers().contains(pointer.local())) {
                            holdingChunk.getSubLevelPointers().add(pointer.local());
                            this.setDirty(recoveredChunk);
                        }

                        final HoldingSubLevel holdingSubLevel = new HoldingSubLevel(data, pointer);
                        holdingChunk.acceptHoldingSubLevel(holdingSubLevel);
                        this.allHoldingSubLevels.put(data.uuid(), holdingSubLevel);
                        recovered++;

                        /**
                         * Per-object orphan recovery logging is intentionally suppressed.
                         * The summary below reports the total recovered count.
                         *
                         * @author DenisMasterHerobrine
                         * @reason Raw recovery diagnostics must stay bounded during stress tests.
                         */
                    }
                } finally {
                    if (legacy != null) {
                        legacy.close();
                    }
                }
            } catch (final IOException e) {
                SableStorageDiagnostics.logStack("Failed scanning Sable storage file {} for orphaned sub-levels", storageFile, e);
            }
        }

        SableStorageDiagnostics.logStack("Sable raw storage recovery scan complete: recovered={}, skipped={}, allHoldingSubLevels={}", recovered, skipped, this.allHoldingSubLevels.size());
    }

    /**
     * Read an entry with the indexed external path first.
     * The legacy path fallback keeps old damaged storage readable.
     *
     * @author DenisMasterHerobrine
     * @reason Existing worlds can still use Sable's old shared external path.
     */
    @Unique
    private CompoundTag sablecontraptionsfix$readStoredSubLevel(final SubLevelStorageFile indexed,
                                                               final SubLevelStorageFile legacy,
                                                               final int subLevelIndex,
                                                               final File storageFile) {
        try {
            return indexed.read(subLevelIndex);
        } catch (final IOException indexedFailure) {
            if (legacy == null) {
                SableStorageDiagnostics.logStack("Failed reading Sable storage entry {} from {} with indexed external path", subLevelIndex, storageFile, indexedFailure);
                return null;
            }

            try {
                return legacy.read(subLevelIndex);
            } catch (final IOException legacyFailure) {
                SableStorageDiagnostics.logStack("Failed reading Sable storage entry {} from {} with both indexed and legacy external paths", subLevelIndex, storageFile, legacyFailure);
                return null;
            }
        }
    }

    /**
     * Search loaded holding chunks for an existing pointer owner.
     * This avoids duplicating a pointer that is already valid.
     *
     * @author DenisMasterHerobrine
     * @reason Raw recovery should not create duplicate holding pointers.
     */
    @Unique
    private ChunkPos sablecontraptionsfix$findExistingPointerOwner(final int regionX,
                                                                   final int regionZ,
                                                                   final int storageIndex,
                                                                   final int subLevelIndex) {
        final short storage = (short) storageIndex;
        final short subLevel = (short) subLevelIndex;
        for (final SubLevelHoldingChunk holdingChunk : this.loadedHoldingChunks.values()) {
            final ChunkPos chunkPos = holdingChunk.getChunkPos();
            if (chunkPos.getRegionX() != regionX || chunkPos.getRegionZ() != regionZ) {
                continue;
            }

            for (final dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer pointer : holdingChunk.getSubLevelPointers()) {
                if (pointer.storageIndex() == storage && pointer.subLevelIndex() == subLevel) {
                    return chunkPos;
                }
            }
        }
        return null;
    }

    /**
     * Choose a safe holding chunk for an orphaned storage entry.
     * Pose chunk is best; bounds chunk and region clamp are fallbacks.
     *
     * @author DenisMasterHerobrine
     * @reason Recovered entries need a stable chunk owner for future saves.
     */
    @Unique
    private ChunkPos sablecontraptionsfix$chooseRecoveredHoldingChunk(final int regionX,
                                                                      final int regionZ,
                                                                      final SubLevelData data) {
        final ChunkPos poseChunk = new ChunkPos(BlockPos.containing(data.pose().position().x, data.pose().position().y, data.pose().position().z));
        if (poseChunk.getRegionX() == regionX && poseChunk.getRegionZ() == regionZ) {
            return poseChunk;
        }

        final int minChunkX = Mth.floor(data.bounds().minX()) >> 4;
        final int maxChunkX = Mth.floor(data.bounds().maxX()) >> 4;
        final int minChunkZ = Mth.floor(data.bounds().minZ()) >> 4;
        final int maxChunkZ = Mth.floor(data.bounds().maxZ()) >> 4;
        final ChunkPos boundsChunk = new ChunkPos((minChunkX + maxChunkX) >> 1, (minChunkZ + maxChunkZ) >> 1);
        if (boundsChunk.getRegionX() == regionX && boundsChunk.getRegionZ() == regionZ) {
            return boundsChunk;
        }

        final int localX = Mth.clamp(poseChunk.x - regionX * SubLevelRegionFile.SIDE_LENGTH, 0, SubLevelRegionFile.SIDE_LENGTH - 1);
        final int localZ = Mth.clamp(poseChunk.z - regionZ * SubLevelRegionFile.SIDE_LENGTH, 0, SubLevelRegionFile.SIDE_LENGTH - 1);
        final ChunkPos fallback = new ChunkPos(regionX * SubLevelRegionFile.SIDE_LENGTH + localX, regionZ * SubLevelRegionFile.SIDE_LENGTH + localZ);
        SableStorageDiagnostics.logStack("Recovered Sable storage entry uuid={} has poseChunk={} and boundsChunk={} outside storage region [{}, {}]; using fallback holdingChunk={}", data.uuid(), poseChunk, boundsChunk, regionX, regionZ, fallback);
        return fallback;
    }

    /**
     * Build the fixed external folder path for a storage file index.
     * Each .slvls index gets its own folder for large entries.
     *
     * @author DenisMasterHerobrine
     * @reason Indexed storage files must not share external data paths.
     */
    @Unique
    private Path sablecontraptionsfix$getIndexedExternalPath(final int regionX, final int regionZ, final int storageIndex) {
        return this.storage.getFolder().resolve("r." + regionX + "." + regionZ + "." + storageIndex + ".s");
    }

    /**
     * Build Sable's old shared external folder path.
     * It is only used as a read fallback for existing worlds.
     *
     * @author DenisMasterHerobrine
     * @reason Old worlds can still contain data in the legacy external path.
     */
    @Unique
    private Path sablecontraptionsfix$getLegacyExternalPath(final int regionX, final int regionZ) {
        return this.storage.getFolder().resolve("r." + regionX + "." + regionZ + ".r");
    }

    /**
     * Parse storage file names like r.x.z.index.slvls.
     * Invalid names are skipped during raw recovery scans.
     *
     * @author DenisMasterHerobrine
     * @reason Raw recovery must only scan valid Sable storage files.
     */
    @Unique
    private int[] sablecontraptionsfix$parseStorageFileName(final String fileName) {
        final String withoutExtension = fileName.substring(0, fileName.length() - SubLevelStorageFile.FILE_EXTENSION.length());
        final String[] parts = withoutExtension.split("\\.");
        if (parts.length != 4 || !"r".equals(parts[0])) {
            return null;
        }

        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

}
