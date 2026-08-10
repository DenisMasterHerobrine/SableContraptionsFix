package dev.denismasterherobrine.sablecontraptionsfix.mixin.coasters;

import dev.denismasterherobrine.sablecontraptionsfix.SableContraptionsFixConfig;
import dev.denismasterherobrine.sablecontraptionsfix.coasters.CoastersPlotScanCache;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CoasterCartPlotScan.class, remap = false)
public abstract class CoasterCartPlotScanMixin {
    /**
     * Coasters Simulated normally scans every block in every loaded plot chunk whenever render code asks for a cart bearing.
     * Dense Sable stress tests call this for every cart each frame, so cache the result per plot and block identity.
     *
     * @author DenisMasterHerobrine
     * @reason Render-time bearing lookup must be O(1) after the first scan for each Sable sub-level.
     */
    @Inject(method = "representativeBearingPlotPos", at = @At("HEAD"), cancellable = true)
    private static void sablecontraptionsfix$cachedRepresentativeBearingPlotPos(final LevelPlot plot,
                                                                               final Block block,
                                                                               final CallbackInfoReturnable<BlockPos> cir) {
        if (!SableContraptionsFixConfig.ENABLE_COASTERS_SIMULATED_PERF_PATCH.get()) {
            return;
        }
        cir.setReturnValue(CoastersPlotScanCache.representativeBearingPlotPos(plot, block));
    }

    /**
     * Replace the raw full-section scan with the same cache-backed optimized scan for every caller.
     * The optimized scan uses section palette prechecks and the plot bounding box before touching individual block states.
     *
     * @author DenisMasterHerobrine
     * @reason All Coasters bearing scans share the same expensive implementation.
     */
    @Inject(method = "scanBearingCells", at = @At("HEAD"), cancellable = true)
    private static void sablecontraptionsfix$cachedScanBearingCells(final LevelPlot plot,
                                                                    final Block block,
                                                                    final CallbackInfoReturnable<CoasterCartPlotScan.BearingScanResult> cir) {
        if (!SableContraptionsFixConfig.ENABLE_COASTERS_SIMULATED_PERF_PATCH.get()) {
            return;
        }
        cir.setReturnValue(CoastersPlotScanCache.scanBearingCells(plot, block));
    }
}
