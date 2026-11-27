package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.fogofwarlabs.sc4j.engine.mod.ModSettings;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrClientExtension;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrTextPresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows map texts and the texts of other players in the text language, translated by the run's translator ({@link ModContext#getTranslator}):
 * other players' chat while the setting {@value #TRANSLATE_CHAT_SETTING} is on and the texts of the map while the setting
 * {@value #TRANSLATE_MAP_TEXT_SETTING} is on ({@link TextTranslation}).
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
     */
    @Override
    public @Nullable ScrTextPresenter createTextPresenter(@NotNull ModContext context) {
        ModSettings settings = context.getSettings();
        boolean chat = settings.getBoolean(TRANSLATE_CHAT_SETTING);
        boolean mapText = settings.getBoolean(TRANSLATE_MAP_TEXT_SETTING);

        return chat || mapText ? new TextTranslation(context.getTranslator(), chat, mapText) : null;
    }
}
