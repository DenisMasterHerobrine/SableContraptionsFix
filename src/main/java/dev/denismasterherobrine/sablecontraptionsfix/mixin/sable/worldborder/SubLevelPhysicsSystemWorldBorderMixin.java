package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable.worldborder;

import dev.denismasterherobrine.sablecontraptionsfix.SableContraptionsFixConfig;
import dev.denismasterherobrine.sablecontraptionsfix.SableStorageDiagnostics;
import dev.denismasterherobrine.sablecontraptionsfix.optimization.SableHighLoadOptimizer;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.denismasterherobrine.sablecontraptionsfix.compat.SableRapierAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemWorldBorderMixin {
    @Unique
    private static final double sablecontraptionsfix$BORDER_EPSILON = 1.0E-6;
    @Unique
    private static final double sablecontraptionsfix$MAX_SAFE_BUILD_RADIUS = 16_384.0;
    @Unique
    private final Vector3d sablecontraptionsfix$corner = new Vector3d();
    @Unique
    private final Object2LongOpenHashMap<UUID> sablecontraptionsfix$nextWorldBorderClampTick = new Object2LongOpenHashMap<>();
    @Unique
    private double sablecontraptionsfix$minX;
    @Unique
    private double sablecontraptionsfix$maxX;
    @Unique
    private double sablecontraptionsfix$minZ;
    @Unique
    private double sablecontraptionsfix$maxZ;

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private PhysicsPipeline pipeline;

    /**
     * Keep the full physical build inside the world border after physics updates its pose.
     * The pipeline is teleported too, so the next physics step starts from the clamped pose.
     *
     * @author DenisMasterHerobrine
     * @reason Players should not be carried outside the valid world area.
     */
    @Inject(method = "updatePose", at = @At("TAIL"))
    private void sablecontraptionsfix$clampPhysicalSubLevelToWorldBorder(final ServerSubLevel subLevel,
                                                                        final CallbackInfo ci) {
        if (SableContraptionsFixConfig.ALLOW_PHYSICAL_SUB_LEVELS_CROSS_WORLD_BORDER.get() || subLevel.isRemoved()) {
            return;
        }

        final WorldBorder border = this.level.getWorldBorder();
        this.sablecontraptionsfix$updatePhysicalBounds(subLevel);

        final double deltaX = sablecontraptionsfix$correction(this.sablecontraptionsfix$minX, this.sablecontraptionsfix$maxX, border.getMinX(), border.getMaxX());
        final double deltaZ = sablecontraptionsfix$correction(this.sablecontraptionsfix$minZ, this.sablecontraptionsfix$maxZ, border.getMinZ(), border.getMaxZ());
        final double maxCorrection = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
        final double minCorrection = Math.max(sablecontraptionsfix$BORDER_EPSILON, SableContraptionsFixConfig.SABLE_WORLD_BORDER_CLAMP_MIN_CORRECTION.get());

        if (maxCorrection <= minCorrection) {
            return;
        }

        final int loadedSubLevelCount = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(this.level).getLoadedCount();
        if (SableHighLoadOptimizer.shouldCooldownWorldBorderClamp(loadedSubLevelCount)) {
            final long gameTime = this.level.getGameTime();
            final UUID uuid = subLevel.getUniqueId();
            final double forceClampThreshold = Math.max(1.0, minCorrection * 4.0);
            if (gameTime < this.sablecontraptionsfix$nextWorldBorderClampTick.getLong(uuid) && maxCorrection < forceClampThreshold) {
                return;
            }
            this.sablecontraptionsfix$nextWorldBorderClampTick.put(uuid, gameTime + SableContraptionsFixConfig.SABLE_WORLD_BORDER_CLAMP_COOLDOWN_TICKS.get());
        }

        final Vector3d correctedPosition = new Vector3d(subLevel.logicalPose().position()).add(deltaX, 0.0, deltaZ);
        this.pipeline.teleport(subLevel, correctedPosition, subLevel.logicalPose().orientation());
        if (!SableRapierAccess.tryResetVelocityWithoutWake(this.level, subLevel)) {
            this.pipeline.resetVelocity(subLevel);
        }

        subLevel.logicalPose().position().set(correctedPosition);
        subLevel.latestLinearVelocity.zero();
        subLevel.latestAngularVelocity.zero();
        SableStorageDiagnostics.reportWorldBorderClamp(subLevel, deltaX, deltaZ, border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ());
    }

    /**
     * Rebuild physical X/Z bounds from the current pose and plot bounds.
     * If the pose still points at the plotyard, use the physical center only.
     *
     * @author DenisMasterHerobrine
     * @reason Cached Sable bounds can contain plotyard coordinates during early physics updates.
     */
    @Unique
    private void sablecontraptionsfix$updatePhysicalBounds(final ServerSubLevel subLevel) {
        final BoundingBox3ic localBounds = subLevel.getPlot().getBoundingBox();
        final Vector3d center = subLevel.logicalPose().position();
        this.sablecontraptionsfix$minX = Double.POSITIVE_INFINITY;
        this.sablecontraptionsfix$maxX = Double.NEGATIVE_INFINITY;
        this.sablecontraptionsfix$minZ = Double.POSITIVE_INFINITY;
        this.sablecontraptionsfix$maxZ = Double.NEGATIVE_INFINITY;

        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.minX(), localBounds.minY(), localBounds.minZ());
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.minX(), localBounds.minY(), localBounds.maxZ() + 1.0);
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.minX(), localBounds.maxY() + 1.0, localBounds.minZ());
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.minX(), localBounds.maxY() + 1.0, localBounds.maxZ() + 1.0);
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.maxX() + 1.0, localBounds.minY(), localBounds.minZ());
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.maxX() + 1.0, localBounds.minY(), localBounds.maxZ() + 1.0);
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.maxX() + 1.0, localBounds.maxY() + 1.0, localBounds.minZ());
        this.sablecontraptionsfix$includeCorner(subLevel, localBounds.maxX() + 1.0, localBounds.maxY() + 1.0, localBounds.maxZ() + 1.0);

        if (!Double.isFinite(this.sablecontraptionsfix$minX)
                || Math.max(Math.abs(this.sablecontraptionsfix$minX - center.x), Math.abs(this.sablecontraptionsfix$maxX - center.x)) > sablecontraptionsfix$MAX_SAFE_BUILD_RADIUS
                || Math.max(Math.abs(this.sablecontraptionsfix$minZ - center.z), Math.abs(this.sablecontraptionsfix$maxZ - center.z)) > sablecontraptionsfix$MAX_SAFE_BUILD_RADIUS) {
            this.sablecontraptionsfix$minX = center.x;
            this.sablecontraptionsfix$maxX = center.x;
            this.sablecontraptionsfix$minZ = center.z;
            this.sablecontraptionsfix$maxZ = center.z;
        }
    }

    /**
     * Transform one local plot corner and include it in the physical X/Z bounds.
     * This avoids using stale cached bounds from Sable.
     *
     * @author DenisMasterHerobrine
     * @reason The border clamp must use current physical coordinates only.
     */
    @Unique
    private void sablecontraptionsfix$includeCorner(final ServerSubLevel subLevel, final double x, final double y, final double z) {
        subLevel.logicalPose().transformPosition(this.sablecontraptionsfix$corner.set(x, y, z), this.sablecontraptionsfix$corner);
        this.sablecontraptionsfix$minX = Math.min(this.sablecontraptionsfix$minX, this.sablecontraptionsfix$corner.x);
        this.sablecontraptionsfix$maxX = Math.max(this.sablecontraptionsfix$maxX, this.sablecontraptionsfix$corner.x);
        this.sablecontraptionsfix$minZ = Math.min(this.sablecontraptionsfix$minZ, this.sablecontraptionsfix$corner.z);
        this.sablecontraptionsfix$maxZ = Math.max(this.sablecontraptionsfix$maxZ, this.sablecontraptionsfix$corner.z);
    }

    /**
     * Compute the smallest axis offset that keeps one bounds interval inside the border.
     * Oversized builds are centered because they cannot fully fit.
     *
     * @author DenisMasterHerobrine
     * @reason The world-border clamp needs a stable minimal correction.
     */

    @Unique
    private static double sablecontraptionsfix$correction(final double currentMin,
                                                         final double currentMax,
                                                         final double allowedMin,
                                                         final double allowedMax) {
        final double currentSize = currentMax - currentMin;
        final double allowedSize = allowedMax - allowedMin;
        if (currentSize >= allowedSize) {
            return ((allowedMin + allowedMax) * 0.5) - ((currentMin + currentMax) * 0.5);
        }

        double delta = 0.0;
        if (currentMin < allowedMin) {
            delta = allowedMin - currentMin;
        }
        if (currentMax + delta > allowedMax) {
            delta += allowedMax - (currentMax + delta);
        }
        return delta;
    }
}
