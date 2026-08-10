package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.denismasterherobrine.sablecontraptionsfix.SableStorageDiagnostics;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SubLevelSerializer.class, remap = false)
public abstract class SubLevelSerializerMixin {
    /**
     * Clamp bad persisted velocities before Sable creates SubLevelData.
     * Extreme saved velocity can move builds across many regions on reload.
     *
     * @author DenisMasterHerobrine
     * @reason Persisted velocity must not create unsafe region churn on load.
     */

    @Inject(method = "fromData", at = @At("HEAD"))
    private static void sablecontraptionsfix$sanitizePersistedVelocities(final CompoundTag tag,
                                                                        final CallbackInfoReturnable<SubLevelData> cir) {
        SableStorageDiagnostics.sanitizePersistedVelocity(tag, "linear_velocity", SableStorageDiagnostics.MAX_PERSISTED_LINEAR_VELOCITY);
        SableStorageDiagnostics.sanitizePersistedVelocity(tag, "angular_velocity", SableStorageDiagnostics.MAX_PERSISTED_ANGULAR_VELOCITY);
    }
}
