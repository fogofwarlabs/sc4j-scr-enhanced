package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.fogofwarlabs.sc4j.engine.mod.ModSettings;
import org.fogofwarlabs.sc4j.scr.ScrMod;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrClientExtension;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrTextPresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows map texts and the texts of other players in the text language, translated by the run's translator ({@link ModContext#getTranslator}):
 * other players' chat while the setting {@value #TRANSLATE_CHAT_SETTING} is on and the texts of the map while the setting
 * {@value #TRANSLATE_MAP_TEXT_SETTING} is on ({@link TextTranslation}). The requests carry the translation contexts of the game in the
 * StarCraft: Remastered folder of the setting {@value ScrMod#GAME_FOLDER_SETTING} ({@link TranslationContexts}).
 */
public final class TranslationClientExtension implements ScrClientExtension {
    /**
     * The setting that turns the translation of other players' chat on.
     */
    public static final @NotNull String TRANSLATE_CHAT_SETTING = "translateChat";

    /**
     * The setting that turns the translation of the map's texts on.
     */
    public static final @NotNull String TRANSLATE_MAP_TEXT_SETTING = "translateMapText";

    /**
     * Creates a {@link TranslationClientExtension} instance.
     */
    public TranslationClientExtension() {
    }

    /**
     * Creates the text translation of the run.
     *
     * @param context the context of the mod, with its settings and the run's translator
     * @return the created text translation, or {@code null} while both settings are off
     * @implSpec Starts reading the game terms of the translation contexts while a setting is on.
     */
    @Override
    public @Nullable ScrTextPresenter createTextPresenter(@NotNull ModContext context) {
        ModSettings settings = context.getSettings();
        boolean chat = settings.getBoolean(TRANSLATE_CHAT_SETTING);
        boolean mapText = settings.getBoolean(TRANSLATE_MAP_TEXT_SETTING);

        if (!chat && !mapText) {
            return null;
        }

        TranslationContexts contexts = TranslationContexts.load(settings.getDirectory(ScrMod.GAME_FOLDER_SETTING), context.getLogger());

        return new TextTranslation(context.getTranslator(), contexts, chat, mapText);
    }
}
