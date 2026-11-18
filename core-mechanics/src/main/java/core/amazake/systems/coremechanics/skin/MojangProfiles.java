package core.amazake.systems.coremechanics.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Профиль со скином по нику через API Mojang (вне тиков: сеть). */
public final class MojangProfiles {

    private MojangProfiles() {
    }

    /** Профиль с подписанной текстурой; null — нет такого лицензионного ника или нет сети. */
    public static PlayerProfile lookup(final String name) {
        try {
            final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            final HttpResponse<String> r1 = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                    + URLEncoder.encode(name, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (r1.statusCode() != 200) {
                return null;
            }
            final JsonObject j1 = JsonParser.parseString(r1.body()).getAsJsonObject();
            final String id = j1.get("id").getAsString();
            final String real = j1.get("name").getAsString();
            final HttpResponse<String> r2 = http.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + id + "?unsigned=false")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            if (r2.statusCode() != 200) {
                return null;
            }
            final PlayerProfile pp = Bukkit.createProfile(dashed(id), real);
            for (final JsonElement el : JsonParser.parseString(r2.body()).getAsJsonObject().getAsJsonArray("properties")) {
                final JsonObject o = el.getAsJsonObject();
                if ("textures".equals(o.get("name").getAsString())) {
                    pp.setProperty(new ProfileProperty("textures", o.get("value").getAsString(),
                        o.has("signature") ? o.get("signature").getAsString() : null));
                }
            }
            return pp;
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** UUID без дефисов (как отдаёт Mojang) → обычный. */
    public static UUID dashed(final String id) {
        return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /** Профиль офлайн-игрока без скина (Стив). */
    public static PlayerProfile offline(final String name) {
        return Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
    }
}
