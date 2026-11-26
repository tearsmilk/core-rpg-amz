package ink.hoka.odyssey.guns;

import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** АКМ из настоящего config.yml модуля (как его увидит сервер). */
final class TestGuns {
    private TestGuns() {
    }

    static Map<?, ?> config() {
        try (InputStream in = TestGuns.class.getResourceAsStream("/config.yml")) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static GunDef ak() {
        return GunDef.read("ak47", GunDef.Cfg.of(config()));
    }
}
