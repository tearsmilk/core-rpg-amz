package core.amazake.systems.corenpc.dialog;

import java.util.List;

/** Ответ игрока: текст, условия показа, действия, куда дальше (id страницы, «диалог:страница» или null — закрыть). */
public record Answer(String text, List<Condition> requires, List<Action> actions, String target) {

    public Answer {
        requires = List.copyOf(requires);
        actions = List.copyOf(actions);
    }
}
