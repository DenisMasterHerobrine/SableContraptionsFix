package dev.denismasterherobrine.sablecontraptionsfix;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SableContraptionsFixConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_DIAGNOSTICS;
    public static final ModConfigSpec.BooleanValue ENABLE_DIAGNOSTIC_STACKTRACES;
    public static final ModConfigSpec.BooleanValue ALLOW_PHYSICAL_SUB_LEVELS_CROSS_WORLD_BORDER;
    public static final ModConfigSpec.BooleanValue ENABLE_COASTERS_SIMULATED_PERF_PATCH;
    public static final ModConfigSpec.IntValue COASTERS_CLIENT_RAIL_SOUND_SCAN_MAX_SUB_LEVELS;
    public static final ModConfigSpec.IntValue COASTERS_SERVER_LINK_RESTORE_THROTTLE_MIN_SUB_LEVELS;
    public static final ModConfigSpec.IntValue COASTERS_SERVER_LINK_RESTORE_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_HIGH_LOAD_OPTIMIZATIONS;
    public static final ModConfigSpec.IntValue SABLE_OPTIMIZATION_MIN_HOLDING_SUB_LEVELS;
    public static final ModConfigSpec.IntValue SABLE_HOLDING_READY_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SABLE_HOLDING_DEPENDENCY_RETRY_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SABLE_MISSING_DEPENDENCY_CHECK_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SABLE_HOLDING_MAX_LOADS_PER_TICK;
    public static final ModConfigSpec.IntValue SABLE_BOOTSTRAP_FAST_LOAD_TICKS;
    public static final ModConfigSpec.IntValue SABLE_BOOTSTRAP_MAX_LOADS_PER_TICK;
    public static final ModConfigSpec.BooleanValue SABLE_BOOTSTRAP_IGNORE_CHUNK_READINESS;
    public static final ModConfigSpec.BooleanValue SABLE_BOOTSTRAP_REQUIRE_PLAYER;
    public static final ModConfigSpec.IntValue SABLE_PHYSICS_SUBSTEP_THROTTLE_MIN_SUB_LEVELS;
    public static final ModConfigSpec.IntValue SABLE_PHYSICS_MAX_SUBSTEPS_PER_TICK;
    public static final ModConfigSpec.IntValue SABLE_PHYSICS_MAX_SOLVER_ITERATIONS;
    public static final ModConfigSpec.IntValue SABLE_PHYSICS_MAX_PGS_ITERATIONS;
    public static final ModConfigSpec.IntValue SABLE_PHYSICS_MAX_STABILIZATION_ITERATIONS;
    public static final ModConfigSpec.IntValue SABLE_WORLD_BORDER_CLAMP_COOLDOWN_TICKS;
    public static final ModConfigSpec.DoubleValue SABLE_WORLD_BORDER_CLAMP_MIN_CORRECTION;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("diagnostics");
        ENABLE_DIAGNOSTICS = builder
                .comment(
                        "Enables SableContraptionsFix diagnostic logs for storage recovery, dependency repair, world-border clamping, and velocity sanitizing.",
                        "Disable this only after the server has been stable for a while; important recovery actions still run, but the extra log messages are suppressed."
                )
                .define("enabled", true);
        ENABLE_DIAGNOSTIC_STACKTRACES = builder
                .comment(
                        "Adds a Java stacktrace to every SableContraptionsFix diagnostic log entry.",
                        "Useful when locating the exact caller that triggered storage repair or unload protection.",
                        "Very noisy on busy servers; keep false unless actively debugging."
                )
                .define("stacktraces", false);
        builder.pop();

        builder.push("world_border");
        ALLOW_PHYSICAL_SUB_LEVELS_CROSS_WORLD_BORDER = builder
                .comment(
                        "Controls whether physical Sable contraptions may cross the Minecraft world border.",
                        "false: the whole physical contraption bounding box is clamped inside the border and velocity is reset when it hits the border.",
                        "true: restores Sable's original behavior and allows contraptions to move beyond the border and players may be carried outside the safe world area."
                )
                .define("allow_physical_sub_levels_cross_world_border", false);
        builder.pop();


        builder.push("optimization");
        ENABLE_SABLE_HIGH_LOAD_OPTIMIZATIONS = builder
                .comment(
                        "Enables aggressive high-load Sable optimizations for worlds with hundreds or thousands of physical sub-levels.",
                        "The optimizations preserve functionality by delaying repeated holding-storage retries instead of removing behavior."
                )
                .define("enabled", true);
        SABLE_OPTIMIZATION_MIN_HOLDING_SUB_LEVELS = builder
                .comment(
                        "Minimum unloaded holding sub-level count before high-load holding-storage throttles activate.",
                        "Below this count, SableContraptionsFix keeps the original every-tick behavior."
                )
                .defineInRange("min_holding_sub_levels", 128, 0, 100000);
        SABLE_BOOTSTRAP_FAST_LOAD_TICKS = builder
                .comment(
                        "Ticks after a holding storage map is first seen where SableContraptionsFix prioritizes making all persisted sub-levels visible quickly.",
                        "During this bootstrap window ready-scan throttling is bypassed so reload/login does not leave contraptions visually missing for minutes."
                )
                .defineInRange("bootstrap_fast_load_ticks", 1200, 0, 24000);
        SABLE_BOOTSTRAP_MAX_LOADS_PER_TICK = builder
                .comment(
                        "Maximum holding sub-levels to fully load per tick during bootstrap fast-load mode.",
                        "Old bootstrap_max_loads_per_tick configs are intentionally ignored because high values caused integrated-client freezes."
                )
                .defineInRange("bootstrap_max_loads_per_tick_safe", 256, 1, 100000);
        SABLE_BOOTSTRAP_REQUIRE_PLAYER = builder
                .comment(
                        "Requires at least one player in the ServerLevel before bootstrap fast-load starts.",
                        "This avoids integrated-server startup loading thousands of sub-levels before the client has joined and before chunk visibility stabilizes."
                )
                .define("bootstrap_require_player", true);
        SABLE_BOOTSTRAP_IGNORE_CHUNK_READINESS = builder
                .comment(
                        "Allows bootstrap fast-load mode to load persisted Sable sub-levels before surrounding world chunks report fully ready.",
                        "Unsafe opt-in. Old bootstrap_ignore_chunk_readiness configs are intentionally ignored because they caused load/unload retry loops."
                )
                .define("bootstrap_ignore_chunk_readiness_unsafe", false);
        SABLE_HOLDING_READY_SCAN_INTERVAL_TICKS = builder
                .comment(
                        "Minimum ticks between ready-load scans for the same holding chunk under high load.",
                        "Higher values reduce TPS cost more aggressively but may delay reloading contraptions that just became loadable."
                )
                .defineInRange("holding_ready_scan_interval_ticks", 20, 1, 12000);
        SABLE_HOLDING_DEPENDENCY_RETRY_INTERVAL_TICKS = builder
                .comment(
                        "Minimum ticks before retrying a holding dependency chain that failed because chunks or dependencies were not ready.",
                        "This prevents thousands of unready chains from being walked every server tick."
                )
                .defineInRange("holding_dependency_retry_interval_ticks", 40, 1, 12000);
        SABLE_MISSING_DEPENDENCY_CHECK_INTERVAL_TICKS = builder
                .comment(
                        "Minimum ticks between global missing-dependency integrity scans under high load.",
                        "The scan is O(number of holding sub-level dependencies), so it is cached aggressively."
                )
                .defineInRange("missing_dependency_check_interval_ticks", 100, 1, 12000);
        SABLE_HOLDING_MAX_LOADS_PER_TICK = builder
                .comment(
                        "Maximum holding sub-levels to fully load in one server tick under high load.",
                        "This smooths reload bursts so one tick does not deserialize hundreds of sub-levels at once."
                )
                .defineInRange("holding_max_loads_per_tick", 64, 1, 100000);
        SABLE_PHYSICS_SUBSTEP_THROTTLE_MIN_SUB_LEVELS = builder
                .comment(
                        "Loaded physical sub-level count at which SableContraptionsFix caps physics substeps per server tick.",
                        "This directly reduces Rapier3D.step calls in dense worlds where native physics dominates TPS."
                )
                .defineInRange("physics_substep_throttle_min_loaded_sub_levels", 512, 0, 100000);
        SABLE_PHYSICS_MAX_SUBSTEPS_PER_TICK = builder
                .comment(
                        "Maximum Sable physics substeps per server tick once the loaded sub-level count crosses the throttle threshold.",
                        "1 halves the default Rapier native step count while keeping one physics update every server tick."
                )
                .defineInRange("physics_max_substeps_per_tick", 1, 1, 10);
        SABLE_PHYSICS_MAX_SOLVER_ITERATIONS = builder
                .comment(
                        "Maximum Rapier solver iterations once loaded sub-level physics throttling is active.",
                        "Lower values trade contact accuracy for server survival in worlds with thousands of physical builds."
                )
                .defineInRange("physics_max_solver_iterations", 10, 1, 64);
        SABLE_PHYSICS_MAX_PGS_ITERATIONS = builder
                .comment(
                        "Maximum Rapier PGS iterations once loaded sub-level physics throttling is active."
                )
                .defineInRange("physics_max_pgs_iterations", 1, 1, 64);
        SABLE_PHYSICS_MAX_STABILIZATION_ITERATIONS = builder
                .comment(
                        "Maximum Rapier stabilization iterations once loaded sub-level physics throttling is active."
                )
                .defineInRange("physics_max_stabilization_iterations", 1, 1, 64);
        SABLE_WORLD_BORDER_CLAMP_COOLDOWN_TICKS = builder
                .comment(
                        "Minimum ticks between repeated world-border teleports for the same physical sub-level under high load.",
                        "This prevents tiny border corrections from waking the same Rapier body every tick."
                )
                .defineInRange("world_border_clamp_cooldown_ticks", 20, 0, 12000);
        SABLE_WORLD_BORDER_CLAMP_MIN_CORRECTION = builder
                .comment(
                        "Smallest world-border X/Z correction, in blocks, that triggers a corrective teleport.",
                        "Tiny corrections are ignored because each Rapier teleport wakes the body."
                )
                .defineInRange("world_border_clamp_min_correction", 0.125, 0.0, 16.0);
        builder.pop();

        builder.push("coasters_simulated");
        ENABLE_COASTERS_SIMULATED_PERF_PATCH = builder
                .comment(
                        "Enables performance guards for Create: Coasters Simulated integration with dense Sable sub-level worlds.",
                        "When enabled, expensive optional coaster scans are disabled or throttled once the loaded Sable sub-level count crosses the thresholds below."
                )
                .define("enabled", true);
        COASTERS_CLIENT_RAIL_SOUND_SCAN_MAX_SUB_LEVELS = builder
                .comment(
                        "Maximum loaded Sable sub-levels before Coasters Simulated client train rail sounds are disabled.",
                        "The original sound cache scans every sub-level every client tick; set higher to keep sounds longer, or 0 to always disable this scan."
                )
                .defineInRange("client_rail_sound_scan_max_sub_levels", 128, 0, 100000);
        COASTERS_SERVER_LINK_RESTORE_THROTTLE_MIN_SUB_LEVELS = builder
                .comment(
                        "Loaded Sable sub-level count at which Coasters Simulated persisted train-link restore is throttled.",
                        "Active train constraints still tick normally; this only limits repeated restore scans in crowded dimensions."
                )
                .defineInRange("server_link_restore_throttle_min_sub_levels", 128, 0, 100000);
        COASTERS_SERVER_LINK_RESTORE_INTERVAL_TICKS = builder
                .comment(
                        "Minimum ticks between Coasters Simulated persisted train-link restore scans in crowded dimensions.",
                        "100 ticks is 5 seconds at 20 TPS and removes the every-tick all-sub-level scan from stress tests."
                )
                .defineInRange("server_link_restore_interval_ticks", 100, 1, 12000);
        builder.pop();
        SPEC = builder.build();
    }

    private SableContraptionsFixConfig() {
    }
}
