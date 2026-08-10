package dev.denismasterherobrine.sablecontraptionsfix.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class SableContraptionsFixMixinPlugin implements IMixinConfigPlugin {
    private static final String COASTERS_MIXIN_PREFIX = "dev.denismasterherobrine.sablecontraptionsfix.mixin.coasters.";
    private static final boolean COASTERS_SIMULATED_PRESENT = sablecontraptionsfix$isClassPresent("dev.silvergold.simulatedcoasters.SimulatedCoasters");

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.startsWith(COASTERS_MIXIN_PREFIX)) {
            return COASTERS_SIMULATED_PRESENT;
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName,
                         final ClassNode targetClass,
                         final String mixinClassName,
                         final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName,
                          final ClassNode targetClass,
                          final String mixinClassName,
                          final IMixinInfo mixinInfo) {
    }

    private static boolean sablecontraptionsfix$isClassPresent(final String className) {
        try {
            Class.forName(className, false, SableContraptionsFixMixinPlugin.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        }
    }
}
