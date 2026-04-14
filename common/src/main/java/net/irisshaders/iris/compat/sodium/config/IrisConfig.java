package net.irisshaders.iris.compat.sodium.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ColorThemeBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.caffeinemc.mods.sodium.client.config.builder.ColorThemeBuilderImpl;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatterImpls;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

public class IrisConfig implements ConfigEntryPoint {
	public static final Identifier MONO = Identifier.fromNamespaceAndPath("iris", "textures/gui/config-icon-mono.png");
	public static final Identifier COLOR = Identifier.fromNamespaceAndPath("iris", "textures/gui/config-icon.png");
	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		var settingsPage = builder.createOptionPage().setName(Component.literal("Settings"))
			.addOptionGroup(builder.createOptionGroup().addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath("iris", "settings")).setTooltip(Component.literal("Packs")).setName(Component.translatable("options.iris.shaderPackList"))
				.setScreenConsumer(i -> Minecraft.getInstance().setScreen(new ShaderPackScreen(i)))));

		var wynncraftGroup = builder.createOptionGroup()
			.addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("iris", "color_space"), ColorSpace.class)
				.setBinding(i -> {
					IrisVideoSettings.colorSpace = i;
				}, () -> IrisVideoSettings.colorSpace)
				.setName(Component.translatable("options.iris.colorSpace"))
				.setDefaultValue(ColorSpace.SRGB)
				.setTooltip(Component.translatable("options.iris.colorSpace.sodium_tooltip"))
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setElementNameProvider(ColorSpace::getName))
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("iris", "shadow_distance"))
				.setDefaultValue(32)
				.setBinding(value -> IrisVideoSettings.shadowDistance = value, () -> IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance))
				.setName(Component.translatable("options.iris.shadowDistance"))
				.setTooltip(i -> {
					if (!IrisVideoSettings.isShadowDistanceSliderEnabled()) {
						return Component.translatable("options.iris.shadowDistance.disabled");
					} else {
						return Component.translatable("options.iris.shadowDistance.sodium_tooltip");
					}
				})
				.setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(i -> Component.translatable("options.chunks", i), Component.literal("None")))
				.setEnabledProvider(i -> IrisVideoSettings.isShadowDistanceSliderEnabled(), ConfigState.UPDATE_ON_REBUILD)
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setRange(new Range(0, 32, 1))
				.setImpact(OptionImpact.HIGH)
			)
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("iris", "glint_brightness"))
				.setDefaultValue(110)
				.setBinding(value -> IrisVideoSettings.glintBrightness = value, () -> IrisVideoSettings.glintBrightness)
				.setName(Component.translatable("options.iris.glintBrightness"))
				.setTooltip(Component.translatable("options.iris.glintBrightness.tooltip"))
				.setValueFormatter(ControlValueFormatterImpls.percentage())
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setRange(new Range(50, 200, 5))
				.setImpact(OptionImpact.LOW)
			)
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("iris", "tint_brightness"))
				.setDefaultValue(75)
				.setBinding(value -> IrisVideoSettings.tintBrightness = value, () -> IrisVideoSettings.tintBrightness)
				.setName(Component.translatable("options.iris.tintBrightness"))
				.setTooltip(Component.translatable("options.iris.tintBrightness.tooltip"))
				.setValueFormatter(ControlValueFormatterImpls.percentage())
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setRange(new Range(25, 150, 5))
				.setImpact(OptionImpact.LOW)
			)
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("iris", "wynncraft_scene_darkening"))
				.setDefaultValue(100)
				.setBinding(value -> IrisVideoSettings.wynncraftSceneDarkening = value, () -> IrisVideoSettings.wynncraftSceneDarkening)
				.setName(Component.translatable("options.iris.wynncraftSceneDarkening"))
				.setTooltip(Component.translatable("options.iris.wynncraftSceneDarkening.tooltip"))
				.setValueFormatter(ControlValueFormatterImpls.percentage())
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setRange(new Range(0, 100, 5))
				.setImpact(OptionImpact.LOW)
			)
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("iris", "wynncraft_entity_emissivity"))
				.setDefaultValue(100)
				.setBinding(value -> IrisVideoSettings.wynncraftEntityEmissivity = value, () -> IrisVideoSettings.wynncraftEntityEmissivity)
				.setName(Component.translatable("options.iris.wynncraftEntityEmissivity"))
				.setTooltip(Component.translatable("options.iris.wynncraftEntityEmissivity.tooltip"))
				.setValueFormatter(ControlValueFormatterImpls.percentage())
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setRange(new Range(0, 100, 5))
				.setImpact(OptionImpact.LOW)
			)
			.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("iris", "wynncraft_night_vision_disables_boost"))
				.setDefaultValue(true)
				.setBinding(value -> IrisVideoSettings.wynncraftNightVisionDisablesBoost = value, () -> IrisVideoSettings.wynncraftNightVisionDisablesBoost)
				.setName(Component.translatable("options.iris.wynncraftNightVisionDisablesBoost"))
				.setTooltip(Component.translatable("options.iris.wynncraftNightVisionDisablesBoost.tooltip"))
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setImpact(OptionImpact.LOW)
			)
			.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("iris", "wynncraft_mist_woods_fog"))
				.setDefaultValue(true)
				.setBinding(value -> IrisVideoSettings.wynncraftMistWoodsFog = value, () -> IrisVideoSettings.wynncraftMistWoodsFog)
				.setName(Component.translatable("options.iris.wynncraftMistWoodsFog"))
				.setTooltip(Component.translatable("options.iris.wynncraftMistWoodsFog.tooltip"))
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setImpact(OptionImpact.LOW)
			);

		if (net.irisshaders.iris.BuildConfig.WYNNIRIS_EXPERIMENTAL) {
			wynncraftGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("iris", "wynncraft_debug_logging"))
				.setDefaultValue(false)
				.setBinding(value -> IrisVideoSettings.wynncraftDebugLogging = value, () -> IrisVideoSettings.wynncraftDebugLogging)
				.setName(Component.translatable("options.iris.wynncraftDebugLogging"))
				.setTooltip(Component.translatable("options.iris.wynncraftDebugLogging.tooltip"))
				.setStorageHandler(() -> {
					try {
						Iris.getIrisConfig().save();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.setImpact(OptionImpact.LOW)
			);
		}

		settingsPage.addOptionGroup(wynncraftGroup);

		builder.registerOwnModOptions().setName(Iris.MODNAME).setIcon(MONO).setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFFf556e2))
			.setVersion(Iris.getVersionSimple())
			.addPage(builder.createExternalPage().setName(Component.translatable("options.iris.shaderPackSelection.title")).setScreenConsumer(i -> Minecraft.getInstance().setScreen(new ShaderPackScreen(i))))
			.addPage(settingsPage)
			.registerOptionOverlay(Identifier.parse("sodium:quality.filtering_mode"), builder.createEnumOption(Identifier.parse("sodium:quality.filtering_mode"), TextureFilteringMethod.class)
				.setTooltip(i -> {
					if (i == TextureFilteringMethod.RGSS) {
						return Component.translatable("options.textureFiltering." + i.name().toLowerCase(Locale.ROOT) + ".tooltip").append(Component.literal(" (RGSS is not usable with shaders on.)"));
					} else {
						return Component.translatable("options.textureFiltering." + i.name().toLowerCase(Locale.ROOT) + ".tooltip");
					}
				})
				.setAllowedValuesProvider(state -> {
					if (Iris.getCurrentPack().isPresent()) {
						return Set.of(TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC);
					} else {
						return Set.of(TextureFilteringMethod.values());
					}
				}, ConfigState.UPDATE_ON_REBUILD)
			).registerOptionOverlay(Identifier.parse("sodium:quality.graphics"), builder.createBooleanOption(Identifier.parse("sodium:quality.graphics"))
				.setTooltip(i -> {
					if (Iris.getCurrentPack().isPresent()) {
						return Component.literal("This option is not relevant when a shader pack is active.");
					} else {
						return Component.translatable("options.improvedTransparency.tooltip");
					}
				})
				.setEnabledProvider(i -> {
					return Iris.getCurrentPack().isEmpty();
				}, ConfigState.UPDATE_ON_REBUILD)
			);
	}
}
