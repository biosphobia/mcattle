package dev.mcattle.core;

import dev.mcattle.core.build.Blueprint;
import dev.mcattle.core.build.BlueprintLibrary;
import dev.mcattle.core.llm.JsonExtract;
import dev.mcattle.core.llm.Orders;
import dev.mcattle.core.llm.SystemPrompt;
import dev.mcattle.core.names.NameGenerator;
import dev.mcattle.core.stt.Wav;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test
    void namesAreValidAndUnique() {
        NameGenerator gen = new NameGenerator(new Random(1));
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String n = gen.generate(taken);
            assertTrue(NameGenerator.isValidName(n), n);
            assertTrue(taken.add(n), "duplicate " + n);
        }
        assertTrue(NameGenerator.combinations() > 10_000);
        assertEquals(List.of("cobblemaya", "maya"), NameGenerator.nicknames("CobbleMaya"));
        assertEquals(List.of("bob"), NameGenerator.nicknames("Bob"));
    }

    @Test
    void blueprintsAreBuildable() {
        for (String name : BlueprintLibrary.NAMES) {
            Blueprint b = BlueprintLibrary.create(name, 0, 0);
            assertTrue(b.solidCount() > 0, name);
            for (Blueprint.Cell c : b.cells()) {
                assertTrue(c.x() >= 0 && c.y() >= 0 && c.z() >= 0, name + " has negative cell " + c);
            }
        }
        Blueprint house = BlueprintLibrary.create("house", 7, 4);
        assertEquals(1, house.roleCounts().get(Blueprint.Role.DOOR));
        assertTrue(house.solidCount() < 300, "default house should stay affordable: " + house.solidCount());
    }

    @Test
    void lenientJson() {
        Orders o = JsonExtract.parse("Sure!\n```json\n{\"orders\":[{\"npc\":\"A\",\"say\":\"hi\",\"mode\":\"none\",\"steps\":[]}]}\n```", Orders.class);
        assertNotNull(o);
        assertEquals("A", o.orders().get(0).npc());
        assertNull(JsonExtract.parse("no json here", Orders.class));
    }

    @Test
    void wavHeader() {
        byte[] wav = Wav.fromPcm16Mono(new byte[3200], 16000);
        assertEquals(44 + 3200, wav.length);
        assertEquals('R', wav[0]);
        assertEquals(0.0, Wav.rms(new byte[100], 100));
    }

    @Test
    void promptMentionsEverySkill() {
        for (String skill : dev.mcattle.core.llm.Skills.CATALOG.keySet()) {
            assertTrue(SystemPrompt.PLANNER.contains("- " + skill + ":"), skill);
        }
    }
}
