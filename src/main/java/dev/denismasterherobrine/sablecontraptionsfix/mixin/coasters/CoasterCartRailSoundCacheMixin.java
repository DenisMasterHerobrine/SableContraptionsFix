package dev.denismasterherobrine.sablecontraptionsfix.mixin.coasters;

import dev.denismasterherobrine.sablecontraptionsfix.coasters.CoastersSimulatedPerfGuard;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.silvergold.simulatedcoasters.client.sound.CoasterCartRailSoundCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(value = CoasterCartRailSoundCache.class, remap = false)
public abstract class CoasterCartRailSoundCacheMixin {
    @Shadow @Final private static Set<UUID> SOUND_LEADER_IDS;
    @Shadow @Final private static Map<UUID, BlockPos> BEARING_PLOT_BY_CART;
    @Shadow @Final private static Map<UUID, SubLevel> CART_SUB_BY_ID;

    /**
     * Disable Coasters Simulated's per-client-tick train sound scan when hundreds of Sable sub-levels are loaded.
     * The scan walks every loaded plot chunk for every sub-level and dominates frame time in dense coaster stress tests.
     *
     * @author DenisMasterHerobrine
     * @reason Rail sounds are optional; preserving frame time is required when the cache would scan hundreds of sub-levels every tick.
     */
    @Inject(method = "refreshTrainSoundCache", at = @At("HEAD"), cancellable = true)
    private static void sablecontraptionsfix$skipOverloadedTrainSoundScan(final ClientLevel level, final CallbackInfo ci) {
        if (!CoastersSimulatedPerfGuard.shouldDisableClientRailSoundCache(level)) {
            return;
        }

        SOUND_LEADER_IDS.clear();
        BEARING_PLOT_BY_CART.clear();
        CART_SUB_BY_ID.clear();
        ci.cancel();
    }
}
