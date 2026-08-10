package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.denismasterherobrine.sablecontraptionsfix.SableStorageDiagnostics;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(value = SubLevelStorage.class, remap = false)
public abstract class SubLevelStorageMixin {
    @Shadow private @NotNull Path getExternalPath(final ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Shadow private @NotNull Path getExternalPath(final ChunkPos chunkPos, final int index) {
        throw new AssertionError();
    }

    /**
     * Use a separate external data folder for each storage file index.
     * Sable creates index 1+ after one .slvls file reaches 1024 entries.
     * A shared external folder can make large sub-level data collide.
     *
     * @author DenisMasterHerobrine
     * @reason Keep large external sub-level data isolated per storage file.
     */

    @Redirect(method = "getRegionStorageFile", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;getExternalPath(Lnet/minecraft/world/level/ChunkPos;)Ljava/nio/file/Path;"))
    private Path sablecontraptionsfix$useIndexedExternalPath(final SubLevelStorage instance,
                                                            final ChunkPos requestedChunkPos,
                                                            final ChunkPos methodChunkPos,
                                                            final int storageIndex) {
        final Path indexedPath = this.getExternalPath(methodChunkPos, storageIndex);
        final Path legacyPath = this.getExternalPath(methodChunkPos);
        if (storageIndex == 0 && !Files.exists(indexedPath) && Files.exists(legacyPath)) {
            SableStorageDiagnostics.logStack("Sable storage file index 0 for {} is using legacy external path {} because indexed external path {} does not exist.", methodChunkPos, legacyPath, indexedPath);
            return legacyPath;
        }

        if (storageIndex > 0) {
            SableStorageDiagnostics.logStack("Sable storage file index {} requested external storage for {}; using indexed external path {} instead of shared region external path {}.", storageIndex, methodChunkPos, indexedPath, legacyPath);
        }
        return indexedPath;
    }
}
