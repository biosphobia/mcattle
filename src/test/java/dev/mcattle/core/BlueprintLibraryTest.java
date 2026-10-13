package dev.mcattle.core;

import dev.mcattle.core.build.Blueprint;
import dev.mcattle.core.build.Blueprint.Role;
import dev.mcattle.core.build.BlueprintLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintLibraryTest {
    @Test
    void houseRoofIsClosedAndHasEntrance() {
        Blueprint b = BlueprintLibrary.create("house", 7, 4);
        // every column inside the walls is covered by some roof block above the walls
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                boolean covered = false;
                for (int y = 5; y < b.height(); y++) {
                    Role r = b.get(x, y, z);
                    if (r == Role.ROOF || r == Role.WALL) covered = true;
                }
                assertTrue(covered, "roof hole at " + x + "," + z);
            }
        }
        assertEquals(Role.DOOR, b.get(3, 1, 0));
        assertEquals(Role.CLEAR, b.get(3, 2, 0));
        assertEquals(Role.CLEAR, b.get(3, 1, 3), "interior is cleared");
    }

    @Test
    void sizesAreClamped() {
        assertTrue(BlueprintLibrary.create("tower", 0, 500).height() <= 43);
        assertTrue(BlueprintLibrary.create("wall", 1000, 0).width() <= 64);
        assertEquals("house", BlueprintLibrary.create("spaceship", 0, 0).name());
    }

    @Test
    void statueStandsOnItsLegs() {
        Blueprint b = BlueprintLibrary.create("statue", 1, 0);
        assertNotNull(b.get(1, 0, 0), "legs touch the ground");
        assertNull(b.get(0, 0, 0), "arms don't");
    }

    @Test
    void iglooHasADoorInItsWall() {
        Blueprint b = BlueprintLibrary.create("igloo", 7, 0);
        assertEquals(1, b.roleCounts().getOrDefault(Role.DOOR, 0));
    }
}
