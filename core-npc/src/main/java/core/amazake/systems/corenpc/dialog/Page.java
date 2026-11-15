package core.amazake.systems.corenpc.dialog;

import java.util.List;

/**
 * Страница диалога. speaker — имя говорящего (MiniMessage, может быть пустым), lines — строки текста (MiniMessage,
 * тег {@code <wait:20>} — пауза печати в тиках), speed — символов за тик (0 — по умолчанию), sound — звук при показе,
 * actions — при показе, answers — ответы (пусто — «дальше» на next или конец).
 */
public record Page(String id, String speaker, List<String> lines, double speed, String sound, List<Action> actions,
                   List<Answer> answers, String next) {

    public Page {
        lines = List.copyOf(lines);
        actions = List.copyOf(actions);
        answers = List.copyOf(answers);
    }
}
