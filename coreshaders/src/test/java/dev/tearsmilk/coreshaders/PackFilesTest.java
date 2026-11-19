package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PackFilesTest {
    private static final Path PACK = Path.of(System.getProperty("user.dir")).resolve("pack/assets");

    private static JsonElement read(String rel) throws Exception {
        return JsonParser.parseString(Files.readString(PACK.resolve(rel)));
    }

    @Test
    void generatorMatchesBundledPack() throws Exception {
        for (String img : Settings.DEFAULT_IMAGES) {
            assertEquals(read("coreshaders/models/item/cube_" + img + ".json"), PackFiles.cubeModel(img), img);
            assertEquals(read("coreshaders/models/item/plane_" + img + ".json"), PackFiles.planeModel(img), img);
            assertEquals(read("coreshaders/items/cube_" + img + ".json"),
                PackFiles.itemDefinition("cube_" + img, Faces.CUBE_TINTS), img);
            assertEquals(read("coreshaders/items/plane_" + img + ".json"),
                PackFiles.itemDefinition("plane_" + img, Faces.PLANE_TINTS), img);
        }
    }

    @Test
    void jsonRoundTrips() {
        String s = PackFiles.json(PackFiles.planeModel("x"));
        assertEquals(PackFiles.planeModel("x"), JsonParser.parseString(s));
    }
}
