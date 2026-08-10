package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = ClientboundStartTrackingSubLevelPacket.class, remap = false)
public abstract class ClientboundStartTrackingSubLevelPacketMixin {
    @Shadow public abstract long plotCoordinate();
    @Shadow public abstract UUID subLevelID();
    @Shadow public abstract Pose3dc lastPose();
    @Shadow public abstract Pose3d pose();
    @Shadow public abstract BoundingBox3ic bounds();
    @Shadow public abstract @Nullable String name();
    @Shadow public abstract int gameTick();

    /**
     * Reuse an existing client sub-level when the server starts tracking the same plot again.
     * This keeps the old render data visible during temporary unload and reload cycles.
     *
     * @author DenisMasterHerobrine
     * @reason A holding reload should refresh the existing render object, not remove and recreate it.
     */
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void sablecontraptionsfix$refreshExistingRenderGhost(final @org.spongepowered.asm.mixin.injection.Coerce Object context, final CallbackInfo ci) {
        final Level level = this.sablecontraptionsfix$level(context);
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof final ClientSubLevelContainer clientContainer)) {
            return;
        }

        final int plotX = ChunkPos.getX(this.plotCoordinate());
        final int plotZ = ChunkPos.getZ(this.plotCoordinate());
        final SubLevel existing = clientContainer.getSubLevel(plotX, plotZ);
        if (!(existing instanceof final ClientSubLevel clientSubLevel)) {
            return;
        }

        if (!clientSubLevel.getUniqueId().equals(this.subLevelID())) {
            clientContainer.removeSubLevel(plotX, plotZ, SubLevelRemovalReason.REMOVED);
            return;
        }

        final SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
        interpolator.receiveSnapshot(this.gameTick() - 1, this.lastPose());
        interpolator.receiveSnapshot(this.gameTick(), this.pose());

        final ClientSableInterpolationState interpolationState = clientContainer.getInterpolation();
        if (!interpolationState.isStopped()) {
            clientSubLevel.setInitialPosesFrom(interpolationState);
        }

        interpolator.setFirstPoses(this.pose(), this.lastPose());
        clientSubLevel.getPlot().setBoundingBox(this.bounds());
        clientSubLevel.forceUpdateBounds();
        clientSubLevel.updateRenderData();
        clientSubLevel.setName(this.name());

        ci.cancel();
    }

    @org.spongepowered.asm.mixin.Unique
    private Level sablecontraptionsfix$level(final Object context) {
        try {
            return (Level) context.getClass().getMethod("level").invoke(context);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read packet level from Sable PacketContext", e);
        }
    }
}
