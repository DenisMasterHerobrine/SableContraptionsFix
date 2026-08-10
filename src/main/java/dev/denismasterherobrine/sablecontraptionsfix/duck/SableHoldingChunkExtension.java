package dev.denismasterherobrine.sablecontraptionsfix.duck;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public interface SableHoldingChunkExtension {
    void sablecontraptionsfix$collectReadySubLevels(ServerLevel level,
                                                    Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels,
                                                    Object2ObjectMap<UUID, HoldingSubLevel> readySubLevels,
                                                    ObjectSet<UUID> processedSubLevelIds);

    HoldingSubLevel sablecontraptionsfix$removeLoadedHoldingSubLevel(UUID uuid);

    void sablecontraptionsfix$resetReadyScanBackoff();
}
