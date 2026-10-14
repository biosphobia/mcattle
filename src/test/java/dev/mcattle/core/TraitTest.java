package dev.mcattle.core;

import com.google.gson.Gson;
import dev.mcattle.core.social.TraitSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraitTest {
    private static final long T0 = 1_700_000_000_000L;

    @Test
    void repeatedNudgesStrengthenOneTrait() {
        TraitSet t = new TraitSet();
        t.reinforce("likes pink wool", 0.25, T0);
        t.reinforce("loves pink wool", 0.25, T0);
        t.reinforce("really loves pink wool!!", 0.25, T0);
        assertEquals(1, t.traits().size(), t.describe(5));
        assertTrue(t.traits().get(0).strength > 0.5);
        assertEquals(3, t.traits().get(0).reinforced);
        t.reinforce("afraid of creepers", 0.25, T0);
        t.reinforce("afraid of zombies", 0.25, T0);
        assertEquals(3, t.traits().size(), t.describe(5));
    }

    @Test
    void weakenAndRemove() {
        TraitSet t = new TraitSet();
        t.reinforce("afraid of the dark", 0.3, T0);
        assertNotNull(t.reinforce("afraid of the dark", -0.1, T0));
        assertNull(t.reinforce("afraid of the dark", -0.5, T0));
        assertTrue(t.traits().isEmpty());
        t.reinforce("talks like a pirate", 0.5, T0);
        assertTrue(t.remove("talks like a pirate"));
    }

    @Test
    void experiencesBecomeTraits() {
        TraitSet t = new TraitSet();
        assertNull(t.experience("death:creeper", 1, 2, "afraid of creepers", 0.3, T0));
        assertNotNull(t.experience("death:creeper", 1, 2, "afraid of creepers", 0.3, T0));
        assertEquals(1, t.traits().size());
        assertNull(t.experience("death:creeper", 1, 2, "afraid of creepers", 0.3, T0));
        assertNotNull(t.experience("death:creeper", 1, 2, "afraid of creepers", 0.3, T0));
        assertEquals(2, t.traits().get(0).reinforced);
    }

    @Test
    void fadesUnlessReinforcedAndStrongOnesLast() {
        TraitSet t = new TraitSet();
        t.reinforce("likes sunflowers", 0.2, T0);
        t.reinforce("obsessed with cake", 1, T0);
        t.reinforce("obsessed with cake", 1, T0);
        t.tick(T0);
        for (int day = 1; day <= 14; day++) t.tick(T0 + day * 86_400_000L);
        assertNull(t.find("likes sunflowers"));
        assertNotNull(t.find("obsessed with cake"));
    }

    @Test
    void parsesModelField() {
        List<TraitSet.Change> c = TraitSet.parse("+likes pink wool; ++obsessed with cake; -afraid of the dark; --talks like a pirate; hums a lot");
        assertEquals(5, c.size());
        assertEquals(0.25, c.get(0).amount());
        assertEquals(0.5, c.get(1).amount());
        assertTrue(c.get(2).amount() < 0);
        assertTrue(c.get(3).remove());
        assertEquals("hums a lot", c.get(4).text());
    }

    @Test
    void capsAndSerializes() {
        TraitSet t = new TraitSet();
        for (int i = 0; i < 60; i++) t.reinforce("quirk number " + i + " zz" + i, 0.1 + i / 100.0, T0);
        assertEquals(TraitSet.MAX_TRAITS, t.traits().size());
        Gson g = new Gson();
        TraitSet back = g.fromJson(g.toJson(t), TraitSet.class);
        assertEquals(t.describe(3), back.describe(3));
    }
}
