package dev.denismasterherobrine.sablecontraptionsfix.compat;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;

public final class SableRapierAccess {
    private static final ThreadLocal<double[]> LINEAR_VELOCITY = ThreadLocal.withInitial(() -> new double[3]);
    private static final ThreadLocal<double[]> ANGULAR_VELOCITY = ThreadLocal.withInitial(() -> new double[3]);

    private static volatile boolean unavailable;
    private static Method getSceneHandle;
    private static Method getId;
    private static Method getLinearVelocity;
    private static Method getAngularVelocity;
    private static Method addLinearAngularVelocities;

    private SableRapierAccess() {
    }

    public static boolean tryResetVelocityWithoutWake(final ServerLevel level, final PhysicsPipelineBody body) {
        if (unavailable || !initialize()) {
            return false;
        }

        try {
            final long sceneHandle = (Long) getSceneHandle.invoke(null, level);
            final int bodyId = (Integer) getId.invoke(null, body);
            final double[] linearVelocity = LINEAR_VELOCITY.get();
            final double[] angularVelocity = ANGULAR_VELOCITY.get();
            getLinearVelocity.invoke(null, sceneHandle, bodyId, linearVelocity);
            getAngularVelocity.invoke(null, sceneHandle, bodyId, angularVelocity);
            addLinearAngularVelocities.invoke(
                    null,
                    sceneHandle,
                    bodyId,
                    -linearVelocity[0],
                    -linearVelocity[1],
                    -linearVelocity[2],
                    -angularVelocity[0],
                    -angularVelocity[1],
                    -angularVelocity[2],
                    false
            );
            return true;
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            unavailable = true;
            return false;
        }
    }

    private static boolean initialize() {
        if (getSceneHandle != null) {
            return true;
        }

        try {
            final Class<?> rapier3d = Class.forName("dev.ryanhcode.sable.physics.impl.rapier.Rapier3D");
            getSceneHandle = rapier3d.getDeclaredMethod("getSceneHandle", ServerLevel.class);
            getId = rapier3d.getDeclaredMethod("getID", PhysicsPipelineBody.class);
            getLinearVelocity = rapier3d.getDeclaredMethod("getLinearVelocity", long.class, int.class, double[].class);
            getAngularVelocity = rapier3d.getDeclaredMethod("getAngularVelocity", long.class, int.class, double[].class);
            addLinearAngularVelocities = rapier3d.getDeclaredMethod(
                    "addLinearAngularVelocities",
                    long.class,
                    int.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class,
                    boolean.class
            );
            getSceneHandle.setAccessible(true);
            getId.setAccessible(true);
            getLinearVelocity.setAccessible(true);
            getAngularVelocity.setAccessible(true);
            addLinearAngularVelocities.setAccessible(true);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            unavailable = true;
            return false;
        }
    }
}
