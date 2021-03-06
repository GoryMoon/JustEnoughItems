package mezz.jei.ingredients;

import javax.annotation.Nullable;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import mezz.jei.Internal;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.color.ColorNamer;
import mezz.jei.config.Config;
import mezz.jei.util.FileUtil;
import mezz.jei.util.LegacyUtil;
import mezz.jei.util.Log;
import mezz.jei.util.Translator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;
import net.minecraft.util.StringUtils;
import net.minecraft.util.text.TextFormatting;

public final class IngredientInformation {
	private static final String CACHE_FILE = "tooltipCache.zip";
	private static final Splitter NEWLINE_SPLITTER = Splitter.on("\\n");
	private static final Joiner NEWLINE_JOINER = Joiner.on("\\n");
	private static final Object SAVE_LOCK = new Object();
	private static boolean needsSaving = false;
	private static boolean saving;

	private static final Map<String, List<String>> TOOLTIP_CACHE = new HashMap<String, List<String>>();
	@Nullable
	private static Language TOOLTIP_CACHE_LANG;

	private IngredientInformation() {
	}

	public static <T> String getDisplayName(T ingredient, IIngredientHelper<T> ingredientHelper) {
		String displayName = ingredientHelper.getDisplayName(ingredient);
		return removeChatFormatting(displayName);
	}

	public static <T> List<String> getTooltipStrings(T ingredient, IIngredientHelper<T> ingredientHelper, IIngredientRenderer<T> ingredientRenderer, Set<String> toRemove) {
		String ingredientUid = ingredientHelper.getUniqueId(ingredient);
		List<String> tooltipStrings = TOOLTIP_CACHE.get(ingredientUid);
		if (tooltipStrings == null) {
			tooltipStrings = getTooltipStringsUncached(ingredient, ingredientRenderer, toRemove);
			TOOLTIP_CACHE.put(ingredientUid, tooltipStrings);
			markDirty();
		}
		return tooltipStrings;
	}

	private static <T> List<String> getTooltipStringsUncached(T ingredient, IIngredientRenderer<T> ingredientRenderer, Set<String> excludeWords) {
		List<String> tooltip = LegacyUtil.getTooltip(ingredientRenderer, Minecraft.getMinecraft(), ingredient, Config.getSearchAdvancedTooltips());
		List<String> cleanTooltip = new ArrayList<String>(tooltip.size());
		for (String line : tooltip) {
			line = removeChatFormatting(line);
			line = Translator.toLowercaseWithLocale(line);
			for (String excludeWord : excludeWords) {
				line = line.replace(excludeWord, "");
			}
			if (!StringUtils.isNullOrEmpty(line)) {
				cleanTooltip.add(line);
			}
		}
		return cleanTooltip;
	}

	private static String removeChatFormatting(String string) {
		String withoutFormattingCodes = TextFormatting.getTextWithoutFormattingCodes(string);
		return (withoutFormattingCodes == null) ? "" : withoutFormattingCodes;
	}

	public static <V> Collection<String> getColorStrings(V ingredient, IIngredientHelper<V> ingredientHelper) {
		Iterable<Color> colors = ingredientHelper.getColors(ingredient);
		ColorNamer colorNamer = Internal.getColorNamer();
		return colorNamer.getColorNames(colors, true);
	}

	public static void onResourceReload() {
		Minecraft minecraft = Minecraft.getMinecraft();
		Language currentLanguage = minecraft.getLanguageManager().getCurrentLanguage();
		if (!currentLanguage.equals(TOOLTIP_CACHE_LANG)) {
			saveTooltipCacheSync(TOOLTIP_CACHE, TOOLTIP_CACHE_LANG);
			TOOLTIP_CACHE.clear();
			TOOLTIP_CACHE_LANG = currentLanguage;
			loadTooltipCache(currentLanguage);
		}
	}

	private static void loadTooltipCache(Language language) {
		File tooltipFile = new File(Config.getJeiConfigurationDir(), CACHE_FILE);
		String languageCode = language.getLanguageCode();
		String zipEntryName = "tooltipCache_" + languageCode + ".txt";
		FileUtil.readZipFileSafely(tooltipFile, zipEntryName, new FileUtil.ZipInputFileOperation() {
			@Override
			public void handle(ZipInputStream zipInputStream) throws IOException {
				BufferedReader reader = new BufferedReader(new InputStreamReader(zipInputStream));

				while (true) {
					String ingredientUid = reader.readLine();
					if (ingredientUid == null) {
						break;
					}
					String tooltip = reader.readLine();
					if (tooltip == null) {
						break;
					}
					List<String> tooltipLines = NEWLINE_SPLITTER.splitToList(tooltip);
					TOOLTIP_CACHE.put(ingredientUid, tooltipLines);
				}

				reader.close();
			}
		});
	}

	public static void markDirty() {
		needsSaving = true;
	}

	public static void onClientTick() {
		if (Minecraft.getMinecraft().world.getWorldTime() % 1200 == 0 && needsSaving) {
			synchronized (SAVE_LOCK) {
				if (needsSaving && !saving) {
					needsSaving = false;
					saving = true;
					saveTooltipCacheASync();
				}
			}
		}
	}

	private static void saveTooltipCacheASync() {
		if (TOOLTIP_CACHE.isEmpty() || TOOLTIP_CACHE_LANG == null) {
			return;
		}

		final Map<String, List<String>> tooltipCache = new HashMap<String, List<String>>(TOOLTIP_CACHE);
		final Language tooltipCacheLang = TOOLTIP_CACHE_LANG;
		new Thread(new Runnable() {
			@Override
			public void run() {
				saveTooltipCacheSync(tooltipCache, tooltipCacheLang);
				synchronized (SAVE_LOCK) {
					saving = false;
				}
			}
		}).start();
	}

	private static void saveTooltipCacheSync(final Map<String, List<String>> tooltipCache, @Nullable Language tooltipCacheLang) {
		if (tooltipCache.isEmpty() || tooltipCacheLang == null) {
			return;
		}

		File file = new File(Config.getJeiConfigurationDir(), "tooltipCache.zip");
		String languageCode = tooltipCacheLang.getLanguageCode();
		String zipEntryName = "tooltipCache_" + languageCode + ".txt";
		boolean write = FileUtil.writeZipFileSafely(file, zipEntryName, new FileUtil.ZipOutputFileOperation() {
			@Override
			public void handle(ZipOutputStream zipOutputStream) throws IOException {
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutputStream));

				for (Map.Entry<String, List<String>> entry : tooltipCache.entrySet()) {
					String ingredientUid = entry.getKey();
					List<String> tooltipLines = entry.getValue();
					String tooltip = NEWLINE_JOINER.join(tooltipLines);
					writer.write(ingredientUid);
					writer.newLine();
					writer.write(tooltip);
					writer.newLine();
				}
			}
		});

		if (write) {
			Log.info("Saved tooltip cache to {}.", file.getAbsoluteFile());
		}
	}
}
