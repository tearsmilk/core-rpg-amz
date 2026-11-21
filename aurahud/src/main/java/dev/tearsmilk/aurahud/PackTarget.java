package dev.tearsmilk.aurahud;

import java.nio.file.Path;
import java.util.logging.Logger;

public record PackTarget(Path assets, Path work, Logger log) {

    public boolean installing() {
        return assets != null;
    }
}
