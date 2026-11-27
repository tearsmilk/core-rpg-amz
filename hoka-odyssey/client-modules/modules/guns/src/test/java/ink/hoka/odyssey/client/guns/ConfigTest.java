package ink.hoka.odyssey.client.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class ConfigTest {
    private static float fov(String version, String value) {
        Properties p = new Properties();
        if (version != null) p.setProperty("config_version", version);
        if (value != null) p.setProperty("viewmodel_fov", value);
        return GunsConfig.viewFov(p);
    }

    @Test
    void viewmodelFovModelByDefault() {
        assertEquals(GunsConfig.FOV_MODEL, fov("2", null));
        assertEquals(GunsConfig.FOV_MODEL, fov("2", "model"));
        assertEquals(GunsConfig.FOV_MODEL, fov("2", " Model "));
        assertEquals(GunsConfig.FOV_MODEL, fov("2", "чушь"));
        // в файлах версии 1 стояло 70 по умолчанию — это «как в TACZ», а не выбор игрока
        assertEquals(GunsConfig.FOV_MODEL, fov("1", "70"));
        assertEquals(GunsConfig.FOV_MODEL, fov(null, "70"));
    }

    @Test
    void viewmodelFovNumberOrWorld() {
        assertEquals(70f, fov("2", "70"));
        assertEquals(55f, fov("1", "55"));
        assertEquals(30f, fov("2", "10"));
        assertEquals(150f, fov("2", "400"));
        assertEquals(0f, fov("2", "0"));
    }
}
