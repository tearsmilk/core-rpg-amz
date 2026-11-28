package ink.hoka.odyssey.client.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PlayerAnimTest {
    private static final String JSON = """
            {"format_version": "1.8.0", "animations": {
              "hold_upper": {"loop": true, "animation_length": 2.0, "bones": {
                "right_arm": {
                  "rotation": {"0.0": {"post": [-90, 0, 2], "lerp_mode": "catmullrom"},
                               "1.0": {"post": [-80, 10, 2], "lerp_mode": "catmullrom"},
                               "2.0": {"post": [-90, 0, 2], "lerp_mode": "catmullrom"}},
                  "position": {"0.0": {"post": {"vector": [2, -2, 1]}, "lerp_mode": "linear"},
                               "2.0": {"post": [4, -2, 1], "lerp_mode": "linear"}}},
                "torso": {"rotation": [10, 40, 0]},
                "unknown_bone": {"rotation": [1, 2, 3]}}},
              "reload_upper": {"animation_length": 1.0, "bones": {
                "left_arm": {"position": {"0.5": {"pre": [0, 0, 0], "post": [0, 5, 0], "lerp_mode": "linear"}}}}}
            }}
            """;

    private static PlayerAnim anim() {
        return PlayerAnim.parse(JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesClipsLoopAndBones() {
        PlayerAnim a = anim();
        PlayerAnim.Clip hold = a.clip("hold_upper");
        assertNotNull(hold);
        assertTrue(hold.loop());
        assertEquals(2.0f, hold.length(), 1e-6);
        assertFalse(a.clip("reload_upper").loop());
        assertNotNull(hold.tracks()[PlayerAnim.boneIndex("right_arm")]);
        assertNotNull(hold.tracks()[PlayerAnim.boneIndex("torso")]);
        assertEquals(0.5f, hold.local(2.5f), 1e-6);                  // петля
        assertEquals(1.0f, a.clip("reload_upper").local(3f), 1e-6);   // разовая — стоп в конце
    }

    @Test
    void samplesKeysCatmullLinearPrePost() {
        PlayerAnim.Clip hold = anim().clip("hold_upper");
        float[] v = new float[3];
        PlayerAnim.Track arm = hold.tracks()[PlayerAnim.boneIndex("right_arm")];
        assertTrue(PlayerAnim.sample(arm.rot(), 1.0f, v));
        assertEquals(-80, v[0], 1e-4);                                // ровно на ключе
        assertEquals(10, v[1], 1e-4);
        assertTrue(PlayerAnim.sample(arm.pos(), 1.0f, v));
        assertEquals(3, v[0], 1e-4);                                  // линейно посередине
        PlayerAnim.Track torso = hold.tracks()[PlayerAnim.boneIndex("torso")];
        assertTrue(PlayerAnim.sample(torso.rot(), 1.7f, v));          // постоянный [x, y, z]
        assertEquals(40, v[1], 1e-6);
        assertFalse(PlayerAnim.sample(torso.pos(), 0, v));
        PlayerAnim.Track left = anim().clip("reload_upper").tracks()[PlayerAnim.boneIndex("left_arm")];
        PlayerAnim.sample(left.pos(), 0.49f, v);
        assertEquals(0, v[1], 1e-3);                                  // pre до скачка
        PlayerAnim.sample(left.pos(), 0.6f, v);
        assertEquals(5, v[1], 1e-6);                                  // post после
    }

    @Test
    void appliesPlayerAnimatorConventions() {
        // как PlayerAnimator: поворот — градусы в радианы как есть (тело — поверх позы игры), положение — к месту
        // части по умолчанию, y с обратным знаком
        PlayerAnim.Clip hold = anim().clip("hold_upper");
        float[][] vanilla = new float[6][6];
        vanilla[2][3] = -5;
        vanilla[2][4] = 2;
        vanilla[1][0] = 0.5f;                                          // присел: тело наклонено игрой
        float[][] out = new float[6][6];
        for (int i = 0; i < 6; i++) System.arraycopy(vanilla[i], 0, out[i], 0, 6);
        ThirdPerson.sample(hold, 0, vanilla, out, 0, 3);
        assertEquals(Math.toRadians(-90), out[2][0], 1e-6);
        assertEquals(Math.toRadians(2), out[2][2], 1e-6);
        assertEquals(-5 + 2, out[2][3], 1e-6);
        assertEquals(2 + 2, out[2][4], 1e-6);                          // −2 в файле — ниже на 2 (y вниз у модели)
        assertEquals(1, out[2][5], 1e-6);
        assertEquals(0.5 + Math.toRadians(10), out[1][0], 1e-6);
        assertEquals(Math.toRadians(40), out[1][1], 1e-6);
        assertEquals(0, out[0][0], 1e-6);                              // головы в клипе нет — как была
    }

    @Test
    void easeInOutSine() {
        assertEquals(0, ThirdPerson.ease(-1), 0);
        assertEquals(0.5, ThirdPerson.ease(0.5f), 1e-6);
        assertEquals(1, ThirdPerson.ease(2), 0);
    }
}
