package dev.denismasterherobrine.sablecontraptionsfix.coasters;

import dev.denismasterherobrine.sablecontraptionsfix.SableContraptionsFixConfig;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public final class CoastersPlotScanCache {
    private static final Object LOCK = new Object();
    private static final WeakHashMap<LevelPlot, IdentityHashMap<Block, Entry>> BY_PLOT = new WeakHashMap<>();

    private CoastersPlotScanCache() {
    }

    public static CoasterCartPlotScan.BearingScanResult scanBearingCells(final LevelPlot plot, final Block block) {
        final Fingerprint fingerprint = Fingerprint.of(plot);
        synchronized (LOCK) {
            final IdentityHashMap<Block, Entry> byBlock = BY_PLOT.get(plot);
            if (byBlock != null) {
                final Entry entry = byBlock.get(block);
                if (entry != null && entry.fingerprint.equals(fingerprint)) {
                    return entry.result;
                }
            }
        }

        final CoasterCartPlotScan.BearingScanResult result = scanBearingCellsUncached(plot, block);
        synchronized (LOCK) {
            BY_PLOT.computeIfAbsent(plot, ignored -> new IdentityHashMap<>()).put(block, new Entry(fingerprint, result));
        }
        return result;
    }

    public static BlockPos representativeBearingPlotPos(final LevelPlot plot, final Block block) {
        return scanBearingCells(plot, block).representative();
    }

    private static CoasterCartPlotScan.BearingScanResult scanBearingCellsUncached(final LevelPlot plot, final Block block) {
        final Predicate<BlockState> targetPredicate = state -> state.is(block);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockPos representative = null;
        int bearingCount = 0;

        final BoundingBox3ic plotBounds = plot.getBoundingBox();
        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }

            final ChunkPos chunkPos = chunk.getPos();
            final int minBlockX = chunkPos.getMinBlockX();
            final int minBlockZ = chunkPos.getMinBlockZ();
            if (!mayIntersect(holder.getBoundingBox(), plotBounds, minBlockX, minBlockZ)) {
                continue;
            }
            final int sectionCount = chunk.getSectionsCount();
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                final LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section == null || section.hasOnlyAir() || !section.maybeHas(targetPredicate)) {
                    continue;
                }

                final int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
                final int minX = clampLocal(plotBounds.minX() - minBlockX);
                final int maxX = clampLocal(plotBounds.maxX() - minBlockX);
                final int minY = clampLocal(plotBounds.minY() - sectionMinY);
                final int maxY = clampLocal(plotBounds.maxY() - sectionMinY);
                final int minZ = clampLocal(plotBounds.minZ() - minBlockZ);
                final int maxZ = clampLocal(plotBounds.maxZ() - minBlockZ);

                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            if (!section.getBlockState(x, y, z).is(block)) {
                                continue;
                            }

                            bearingCount++;
                            final BlockPos candidate = mutable.set(minBlockX + x, sectionMinY + y, minBlockZ + z).immutable();
                            if (representative == null || compareYXZ(candidate, representative) < 0) {
                                representative = candidate;
                            }
                        }
                    }
                }
            }
        }

        return new CoasterCartPlotScan.BearingScanResult(representative, bearingCount);
    }

    private static boolean mayIntersect(final BoundingBox3ic chunkBounds,
                                        final BoundingBox3ic plotBounds,
                                        final int chunkMinX,
                                        final int chunkMinZ) {
        if (chunkBounds == null) {
            return false;
        }
        return chunkMinX + chunkBounds.maxX() >= plotBounds.minX()
                && chunkMinX + chunkBounds.minX() <= plotBounds.maxX()
                && chunkBounds.maxY() >= plotBounds.minY()
                && chunkBounds.minY() <= plotBounds.maxY()
                && chunkMinZ + chunkBounds.maxZ() >= plotBounds.minZ()
                && chunkMinZ + chunkBounds.minZ() <= plotBounds.maxZ();
    }

    private static int clampLocal(final int value) {
        if (value < 0) {
            return 0;
        }
        if (value > SectionPos.SECTION_SIZE - 1) {
            return SectionPos.SECTION_SIZE - 1;
        }
        return value;
    }

    private static int compareYXZ(final BlockPos a, final BlockPos b) {
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    private record Entry(Fingerprint fingerprint, CoasterCartPlotScan.BearingScanResult result) {
    }

    private record Fingerprint(int chunkCount, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private static Fingerprint of(final LevelPlot plot) {
            final BoundingBox3ic bounds = plot.getBoundingBox();
            return new Fingerprint(
                    plot.getLoadedChunks().size(),
                    bounds.minX(),
                    bounds.minY(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxY(),
                    bounds.maxZ()
            );
        }
    }
}
