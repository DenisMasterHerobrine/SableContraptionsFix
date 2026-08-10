package dev.denismasterherobrine.sablecontraptionsfix;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.util.SableNBTUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SableStorageDiagnostics {
    private static final double PLAYER_GUARD_MARGIN = 8.0;
    public static final double MAX_PERSISTED_LINEAR_VELOCITY = 512.0;
    public static final double MAX_PERSISTED_ANGULAR_VELOCITY = 64.0;
    private static final Set<String> REPORTED_MISSING_DEPENDENCIES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> REPORTED_PROTECTED_UNLOADS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> REPORTED_WORLD_BORDER_CLAMPS = ConcurrentHashMap.newKeySet();

    private SableStorageDiagnostics() {
    }

    public static void logStack(final String message, final Object... args) {
        if (!SableContraptionsFixConfig.ENABLE_DIAGNOSTICS.get()) {
            return;
        }

        if (SableContraptionsFixConfig.ENABLE_DIAGNOSTIC_STACKTRACES.get()) {
            final Object[] argsWithStack = new Object[args.length + 1];
            System.arraycopy(args, 0, argsWithStack, 0, args.length);
            argsWithStack[args.length] = new IllegalStateException("SableContraptionsFix diagnostic stacktrace");
            SableContraptionsFix.LOGGER.error(message, argsWithStack);
            return;
        }

        SableContraptionsFix.LOGGER.warn(message, sanitizeDiagnosticArgs(args));
    }

    private static Object[] sanitizeDiagnosticArgs(final Object[] args) {
        Object[] sanitized = args;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof final Throwable throwable) {
                if (sanitized == args) {
                    sanitized = args.clone();
                }
                sanitized[i] = throwable.toString();
            }
        }
        return sanitized;
    }

    public static void reportMissingDependency(final UUID owner, final UUID dependency) {
        final String key = owner + "->" + dependency;
        if (REPORTED_MISSING_DEPENDENCIES.add(key)) {
            logStack("Sable holding sub-level {} is waiting for dependency {} that is not loaded into any holding chunk. Scanning storage for orphaned dependency entries.", owner, dependency);
        }
    }

    public static boolean hasMissingDependency(final Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels) {
        for (final HoldingSubLevel holdingSubLevel : allHoldingSubLevels.values()) {
            for (final UUID dependency : holdingSubLevel.data().dependencies()) {
                if (!allHoldingSubLevels.containsKey(dependency)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean shouldProtectUnload(final SubLevel subLevel) {
        if (!(subLevel instanceof final ServerSubLevel serverSubLevel)) {
            return false;
        }

        /*
         * Network tracking only means a player can see the sub-level.
         * It must not block unloading, or visible stress-test builds loop forever.
         */

        if (!(serverSubLevel.getLevel() instanceof final ServerLevel level)) {
            return false;
        }

        for (final ServerPlayer player : level.players()) {
            final SubLevel containing = Sable.HELPER.getContaining(player);
            if (containing != null && containing.getUniqueId().equals(serverSubLevel.getUniqueId())) {
                return true;
            }

            final SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
            if (tracking != null && tracking.getUniqueId().equals(serverSubLevel.getUniqueId())) {
                return true;
            }

            if (contains(serverSubLevel.boundingBox(), player.position(), PLAYER_GUARD_MARGIN)) {
                return true;
            }
        }

        return false;
    }

    public static void logProtectedUnload(final String source, final ServerSubLevel subLevel) {
        if (REPORTED_PROTECTED_UNLOADS.add(subLevel.getUniqueId())) {
            logStack("Blocked Sable UNLOADED removal from {} for sub-level {}. pointer={}, bounds={}, trackingPlayers={}. This is logged once per sub-level to avoid unload-loop log spam.",
                    source,
                    describe(subLevel),
                    subLevel.getLastSerializationPointer(),
                    subLevel.boundingBox(),
                    subLevel.getTrackingPlayers());
        }
    }

    public static void reportWorldBorderClamp(final ServerSubLevel subLevel,
                                             final double deltaX,
                                             final double deltaZ,
                                             final double minX,
                                             final double maxX,
                                             final double minZ,
                                             final double maxZ) {
        if (REPORTED_WORLD_BORDER_CLAMPS.add(subLevel.getUniqueId())) {
            logStack(
                    "Clamped Sable physical sub-level {} to world border. deltaX={}, deltaZ={}, border=[{}, {}, {}, {}]",
                    describe(subLevel),
                    deltaX,
                    deltaZ,
                    minX,
                    maxX,
                    minZ,
                    maxZ
            );
        }
    }

    public static String describe(final ServerSubLevel subLevel) {
        return "name=" + subLevel.getName() + ", uuid=" + subLevel.getUniqueId() + ", runtime=" + subLevel.getRuntimeId();
    }

    public static String describe(final HoldingSubLevel holdingSubLevel) {
        final SubLevelData data = holdingSubLevel.data();
        final GlobalSavedSubLevelPointer pointer = holdingSubLevel.pointer();
        return "uuid=" + data.uuid() + ", pointer=" + pointer + ", bounds=" + data.bounds() + ", deps=" + data.dependencies();
    }

    public static void sanitizePersistedVelocity(final CompoundTag tag, final String key, final double maxLength) {
        if (!tag.contains(key)) {
            return;
        }

        final Vector3d velocity = SableNBTUtils.readVector3d(tag.getCompound(key));
        final double lengthSquared = velocity.lengthSquared();
        if (!Double.isFinite(lengthSquared)) {
            tag.remove(key);
            logStack("Removed invalid persisted Sable {}={} from sub-level uuid={}", key, velocity, tag.hasUUID("uuid") ? tag.getUUID("uuid") : "unknown");
            return;
        }

        final double maxLengthSquared = maxLength * maxLength;
        if (lengthSquared <= maxLengthSquared) {
            return;
        }

        final double originalLength = Math.sqrt(lengthSquared);
        velocity.mul(maxLength / originalLength);
        tag.put(key, SableNBTUtils.writeVector3d(velocity));
        logStack("Clamped persisted Sable {} from {} to {} for sub-level uuid={}", key, originalLength, maxLength, tag.hasUUID("uuid") ? tag.getUUID("uuid") : "unknown");
    }

    private static boolean contains(final BoundingBox3dc bounds, final Vec3 point, final double margin) {
        return point.x >= bounds.minX() - margin && point.x <= bounds.maxX() + margin
                && point.y >= bounds.minY() - margin && point.y <= bounds.maxY() + margin
                && point.z >= bounds.minZ() - margin && point.z <= bounds.maxZ() + margin;
    }
}
