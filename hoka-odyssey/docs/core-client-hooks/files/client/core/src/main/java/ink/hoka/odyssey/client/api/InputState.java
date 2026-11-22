package ink.hoka.odyssey.client.api;

/**
 * Ввод своего игрока за тик для {@link ClientContext.InputHook}: клавиши (как их видит игра и как их получит
 * сервер) и вектор движения. Можно менять любые поля. moveStrafe: +1 — влево; moveForward: +1 — вперёд.
 * Если клавиши поменяли, а вектор нет — ядро пересчитает вектор из клавиш, как ваниль.
 */
public final class InputState {
    public boolean forward, backward, left, right, jump, shift, sprint;
    public float moveStrafe, moveForward;
}
