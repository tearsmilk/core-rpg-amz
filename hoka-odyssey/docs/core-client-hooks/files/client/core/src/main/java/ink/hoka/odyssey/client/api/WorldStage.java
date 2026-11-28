package ink.hoka.odyssey.client.api;

/** Этап рендера мира для {@link ClientContext#onWorldPass} (как в Fabric WorldRenderEvents). */
public enum WorldStage {
    /** Начало основного прохода: после неба, до террейна. */
    START_MAIN,
    /** После сущностей. */
    AFTER_ENTITIES,
    /** Перед полупрозрачным (вода, стекло). */
    BEFORE_TRANSLUCENT,
    /** Конец основного прохода. */
    END_MAIN
}
