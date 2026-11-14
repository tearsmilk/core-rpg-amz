package core.amazake.systems.rezusmenu;

import core.amazake.systems.rezusmenu.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;

/**
 * Родная панель Minecraft 1.21.11 (диалог) со списком кастомных меню. Кнопка шлёт серверу «свой клик» с ключом
 * {@code rezusmenu:open/<id>}; его ловит {@link RezusMenuSystem#onCustomClick}.
 */
final class HubDialog {

    static final String NAMESPACE = "rezusmenu";
    static final String OPEN = "open/";

    private HubDialog() {
    }

    static Dialog build(final Settings s) {
        final List<ActionButton> buttons = new ArrayList<>();
        for (final Settings.MenuEntry m : s.menus()) {
            buttons.add(ActionButton.builder(Text.item(m.label()))
                .tooltip(m.tooltip().isBlank() ? null : Text.item(m.tooltip()))
                .width(220)
                .action(DialogAction.customClick(Key.key(NAMESPACE, OPEN + m.id()), null))
                .build());
        }
        final ActionButton exit = ActionButton.builder(Text.item("<gray>Закрыть</gray>")).width(220).build();
        final DialogBase base = DialogBase.builder(Text.item(s.dialogTitle()))
            .body(List.of(DialogBody.plainMessage(Text.item(s.dialogBody()))))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .build();
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons).columns(1).exitAction(exit).build()));
    }
}
