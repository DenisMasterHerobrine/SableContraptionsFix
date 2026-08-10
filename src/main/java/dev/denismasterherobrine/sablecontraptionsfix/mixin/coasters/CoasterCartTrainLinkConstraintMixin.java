package dev.denismasterherobrine.sablecontraptionsfix.mixin.coasters;

import dev.denismasterherobrine.sablecontraptionsfix.coasters.CoastersSimulatedPerfGuard;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CoasterCartTrainLinkConstraint.class, remap = false)
public abstract class CoasterCartTrainLinkConstraintMixin {
    /**
     * Coasters Simulated restores persisted train links once per server tick per dimension.
     * In dense Sable worlds this re-scans every sub-level to decide whether it is a coaster cart.
     *
     * @author DenisMasterHerobrine
     * @reason Persisted link restoration can be throttled under high sub-level density without changing active physics constraints.
     */
    @Inject(method = "maybeRestorePersistedLinks", at = @At("HEAD"), cancellable = true)
    private static void sablecontraptionsfix$throttleCrowdedLinkRestore(final ServerLevel level, final CallbackInfo ci) {
        if (CoastersSimulatedPerfGuard.shouldSkipServerLinkRestore(level)) {
            ci.cancel();
        }
    }
}
