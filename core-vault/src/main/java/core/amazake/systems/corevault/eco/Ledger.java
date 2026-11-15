package core.amazake.systems.corevault.eco;

import java.util.UUID;

/**
 * Где лежат балансы: свои счета ({@link InternalLedger}) или другой провайдер Vault ({@link ExternalLedger}). Все
 * суммы — минорные единицы валюты core-vault. Методы потокобезопасны и вызываются из любого потока.
 */
public interface Ledger {

    /** Итог операции: ok, баланс после (или текущий при отказе), причина отказа для игрока. */
    record Result(boolean ok, long balance, String error) {
        public static Result ok(final long balance) {
            return new Result(true, balance, "");
        }

        public static Result fail(final long balance, final String error) {
            return new Result(false, balance, error);
        }
    }

    /** Для вывода: «core-vault» или имя внешнего провайдера. */
    String describe();

    boolean internal();

    long balance(UUID id);

    boolean hasAccount(UUID id);

    Result deposit(UUID id, String name, long amount);

    Result withdraw(UUID id, String name, long amount);

    Result set(UUID id, String name, long amount);

    /** Перевод: либо целиком, либо ничего. Результат — баланс отправителя. */
    Result transfer(UUID from, String fromName, UUID to, String toName, long amount);
}
