package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SubLevelTrackingSystemMixin {
    /**
     * Keep client-side render data when Sable only moves a sub-level to holding storage.
     * The server may load the same physical build again a few ticks later.
     *
     * @author DenisMasterHerobrine
     * @reason Temporary holding unloads should not make visible builds blink out.
     */
    @Inject(method = "onSubLevelRemoved", at = @At("HEAD"), cancellable = true)
    private void sablecontraptionsfix$keepRenderGhostForHoldingUnload(final SubLevel subLevel,
                                                                      final SubLevelRemovalReason reason,
                                                                      final CallbackInfo ci) {
        if (reason != SubLevelRemovalReason.UNLOADED || !(subLevel instanceof final ServerSubLevel serverSubLevel)) {
            return;
        }

        final SubLevelContainer container = SubLevelContainer.getContainer(serverSubLevel.getLevel());
        if (container != null) {
            final Vector2i origin = container.getOrigin();
            final ChunkPos plotPos = serverSubLevel.getPlot().plotPos;
            final long plotCoordinate = ChunkPos.asLong(plotPos.x - origin.x, plotPos.z - origin.y);
            final ClientboundCustomPayloadPacket stopMoving = new ClientboundCustomPayloadPacket(new ClientboundStopMovingSubLevelPacket(plotCoordinate));
            for (final UUID uuid : serverSubLevel.getTrackingPlayers()) {
                final ServerPlayer player = serverSubLevel.getLevel().getServer().getPlayerList().getPlayer(uuid);
                if (player != null) {
                    player.connection.send(stopMoving);
                }
            }
        }

        ci.cancel();
    }

    /**
     * Force movement snapshots onto the ordered connection.
     * UDP can arrive before the TCP start-tracking bundle during large load bursts.
     *
     * @author DenisMasterHerobrine
     * @reason Client render objects must exist before movement packets are applied.
     */
    @Redirect(method = "sendMovementUpdates", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean sablecontraptionsfix$forceOrderedMovementSnapshots(final SableUDPServer instance, final ServerPlayer player) {
        return false;
    }
}
