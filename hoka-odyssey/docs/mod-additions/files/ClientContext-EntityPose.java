// Фрагмент ink/hoka/odyssey/client/api/ClientContext.java (мод 0.48.0 + добавка offset).
// Восстановлен по байткоду 0.48.0 (javap) и исходникам 0.25 с GitHub — влить в своё дерево 0.48, а не заменять файл.
// Меняется только запись EntityPose: пятое поле offset и конструктор с прежними четырьмя полями.

    /**
     * Поза модели сущности целиком (мир): поворот tilt, курс тела и голова — в системе tilt; null — как у игры.
     * Ядро ставит углы модели как есть (bodyRot = bodyYaw, yRot = headYaw − bodyYaw, xRot = headPitch), а при
     * отрисовке сдвигает модель на offset (если задан) и поворачивает на tilt вокруг её ног. Только картинка:
     * столкновения и рамка сущности позу не видят.
     */
    default void onEntityPose(EntityPoseHook hook) {}

    interface EntityPoseHook { EntityPose pose(net.minecraft.world.entity.Entity entity, float partialTick); }

    /**
     * Поза модели. tilt — поворот модели (мир) вокруг её ног; bodyYaw, headYaw, headPitch — углы Minecraft (градусы)
     * в системе tilt. offset — НОВОЕ (необязательное, null — нет): сдвиг модели в мире, блоки, от позиции сущности
     * (её ног) до точки, вокруг которой поворачивается модель (её ног после сдвига). Так модуль ставит модель куда
     * нужно при ровной рамке сущности: стоя на стене — ноги на стене, свободно в пространстве — поворот вокруг
     * середины тела (offset = (0, h/2, 0) − tilt·(0, h/2, 0)).
     */
    record EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch,
                      org.joml.Vector3fc offset) {
        /** Как в 0.48.0 — без сдвига: старые модули (и уже собранные jar) работают без изменений. */
        public EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch) {
            this(tilt, bodyYaw, headYaw, headPitch, null);
        }
    }
