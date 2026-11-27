package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.jetbrains.annotations.NotNull;

/**
 * Names the kinds of texts the client translates, each with the text kind and the description its {@link TranslationContexts translation
 * context} gives the translator. Both are written in Chinese, the language of the instructions of the translation models that use them.
 */
public enum TextKind {
    /**
     * The chat of other players: lobby, channel and in-game chat.
     */
    CHAT("游戏聊天", "即时战略游戏《星际争霸》中玩家之间的聊天，常用游戏简称和网络用语，译文保持口语化、简短。"),
    /**
     * The texts of a map: briefings, objectives, unit names and the strings a map changes.
     */
    MAP_TEXT("游戏地图文本", "即时战略游戏《星际争霸》地图中的文字，如任务简报、目标、单位名称和提示。");

    private final @NotNull String label;
    private final @NotNull String description;

    /**
     * Creates a {@link TextKind} instance.
     *
     * @param label       the text kind the translation context gives
     * @param description the description the translation context gives
     */
    TextKind(@NotNull String label, @NotNull String description) {
        this.label       = label;
        this.description = description;
    }

    /**
     * @return the text kind the translation context gives, such as {@code 游戏聊天}
     */
    public @NotNull String getLabel() {
        return this.label;
    }

    /**
     * @return the description the translation context gives: the game and how its texts are written
     */
    public @NotNull String getDescription() {
        return this.description;
    }
}
