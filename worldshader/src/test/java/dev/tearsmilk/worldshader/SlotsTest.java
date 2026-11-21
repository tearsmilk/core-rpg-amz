package dev.tearsmilk.worldshader;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotsTest {

    @Test
    void freeTakesTheLowestUnusedSlot() {
        assertEquals(0, Slots.free(List.of()));
        assertEquals(1, Slots.free(List.of(0, 2)));
        assertEquals(7, Slots.free(List.of(0, 1, 2, 3, 4, 5, 6)));
        assertEquals(-1, Slots.free(List.of(0, 1, 2, 3, 4, 5, 6, 7)));
    }

    @Test
    void uniqueSlotsStayAsTheyAre() {
        List<Slots.Claim> claims = new ArrayList<>();
        for (int s : new int[]{5, 0, 3}) {
            claims.add(new Slots.Claim(UUID.randomUUID(), s, s));
        }
        Map<UUID, Integer> out = Slots.repair(claims);
        for (Slots.Claim c : claims) {
            assertEquals(c.slot(), out.get(c.id()));
        }
    }

    @Test
    void duplicatesAndBadSlotsGetFreeOnesOldestKeepsItsSlot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        Map<UUID, Integer> out = Slots.repair(List.of(new Slots.Claim(b, 0, 20), new Slots.Claim(a, 0, 10),
                new Slots.Claim(c, -1, 5), new Slots.Claim(d, 9, 1)));
        assertEquals(0, out.get(a));
        Set<Integer> all = new HashSet<>(out.values());
        assertEquals(4, all.size());
        for (int s : out.values()) {
            assertTrue(s >= 0 && s < ZoneCode.SLOTS);
        }
    }

    @Test
    void moreThanEightClaimsLeaveTheNewestWithoutSlot() {
        List<Slots.Claim> claims = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            claims.add(new Slots.Claim(UUID.randomUUID(), 0, i));
        }
        Map<UUID, Integer> out = Slots.repair(claims);
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int s = out.get(claims.get(i).id());
            if (i < ZoneCode.SLOTS) {
                assertTrue(used.add(s));
            } else {
                assertEquals(-1, s);
            }
        }
        assertEquals(0, out.get(claims.get(0).id()));
    }
}
