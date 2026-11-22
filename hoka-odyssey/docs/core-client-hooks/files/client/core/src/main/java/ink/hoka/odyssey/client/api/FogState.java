package ink.hoka.odyssey.client.api;

/**
 * Туман кадра для {@link ClientContext.FogHook}: как насчитала игра — можно менять любые поля. Цвет — 0..1;
 * он же цвет очистки кадра (видно там, где не нарисовано небо). Расстояния — в блоках от камеры:
 * env* — туман окружения (вода, слепота, атмосфера), rd* — край прорисовки, skyEnd — небо, cloudEnd — облака.
 * Чтобы тумана не было: start и end — очень далеко (например {@code Float.MAX_VALUE}).
 */
public final class FogState {
    public float r, g, b, a;
    public float envStart, envEnd;
    public float rdStart, rdEnd;
    public float skyEnd, cloudEnd;
}
