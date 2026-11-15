package core.amazake.systems.corenpc.dialog;

import java.util.List;
import java.util.Map;

/** Диалог из dialogs/&lt;id&gt;.yml: страницы, стартовая, действия при завершении (не при отмене). */
public record Dialog(String id, String start, Map<String, Page> pages, List<Action> onEnd, String mode) {

    public Dialog {
        pages = Map.copyOf(pages);
        onEnd = List.copyOf(onEnd);
    }

    public Page page(final String pageId) {
        return pageId == null ? null : this.pages.get(pageId);
    }
}
