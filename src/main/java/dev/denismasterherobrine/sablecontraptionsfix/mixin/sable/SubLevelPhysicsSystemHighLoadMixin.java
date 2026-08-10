package dev.denismasterherobrine.sablecontraptionsfix.mixin.sable;

import dev.denismasterherobrine.sablecontraptionsfix.optimization.SableHighLoadOptimizer;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.physics.config.PhysicsConfigData;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemHighLoadMixin {
    @Shadow @Final private PhysicsConfigData config;
    @Shadow @Final private PhysicsPipeline pipeline;


    @Unique
    private int sablecontraptionsfix$requestedSubstepsPerTick = -1;
    @Unique
    private int sablecontraptionsfix$requestedSolverIterations = -1;
    @Unique
    private int sablecontraptionsfix$requestedPgsIterations = -1;
    @Unique
    private int sablecontraptionsfix$requestedStabilizationIterations = -1;

    @Inject(method = "initialize", at = @At("TAIL"))
    private void sablecontraptionsfix$captureInitialSubsteps(final CallbackInfo ci) {
        this.sablecontraptionsfix$captureRequestedPhysicsConfig();
    }

    @Inject(method = "onConfigUpdated", at = @At("TAIL"))
    private void sablecontraptionsfix$captureUpdatedSubsteps(final CallbackInfo ci) {
        this.sablecontraptionsfix$captureRequestedPhysicsConfig();
    }

    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"))
    private void sablecontraptionsfix$capPhysicsUnderHighLoad(final ServerSubLevelContainer container, final CallbackInfo ci) {
        if (this.sablecontraptionsfix$requestedSubstepsPerTick <= 0) {
            this.sablecontraptionsfix$captureRequestedPhysicsConfig();
        }

        final int loadedSubLevelCount = container.getLoadedCount();
        final int targetSubsteps = SableHighLoadOptimizer.physicsSubstepsPerTick(loadedSubLevelCount, this.sablecontraptionsfix$requestedSubstepsPerTick);
        final int targetSolverIterations = SableHighLoadOptimizer.physicsSolverIterations(loadedSubLevelCount, this.sablecontraptionsfix$requestedSolverIterations);
        final int targetPgsIterations = SableHighLoadOptimizer.physicsPgsIterations(loadedSubLevelCount, this.sablecontraptionsfix$requestedPgsIterations);
        final int targetStabilizationIterations = SableHighLoadOptimizer.physicsStabilizationIterations(loadedSubLevelCount, this.sablecontraptionsfix$requestedStabilizationIterations);
        final boolean nativeConfigChanged = this.config.solverIterations != targetSolverIterations
                || this.config.pgsIterations != targetPgsIterations
                || this.config.stabilizationIterations != targetStabilizationIterations;

        this.config.substepsPerTick = targetSubsteps;
        this.config.solverIterations = targetSolverIterations;
        this.config.pgsIterations = targetPgsIterations;
        this.config.stabilizationIterations = targetStabilizationIterations;

        if (nativeConfigChanged) {
            this.pipeline.updateConfigFrom(this.config);
        }
    }

    @Unique
    private void sablecontraptionsfix$captureRequestedPhysicsConfig() {
        this.sablecontraptionsfix$requestedSubstepsPerTick = Math.max(1, this.config.substepsPerTick);
        this.sablecontraptionsfix$requestedSolverIterations = Math.max(1, this.config.solverIterations);
        this.sablecontraptionsfix$requestedPgsIterations = Math.max(1, this.config.pgsIterations);
        this.sablecontraptionsfix$requestedStabilizationIterations = Math.max(1, this.config.stabilizationIterations);
    }
}
