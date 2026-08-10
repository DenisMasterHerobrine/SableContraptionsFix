package dev.denismasterherobrine.sablecontraptionsfix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SableContraptionsFix.MODID)
public class SableContraptionsFix {
    public static final String MODID = "sablecontraptionsfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableContraptionsFix(final ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, SableContraptionsFixConfig.SPEC);
    }
}
