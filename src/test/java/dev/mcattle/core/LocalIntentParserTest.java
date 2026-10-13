package dev.mcattle.core;

import dev.mcattle.core.intent.LocalIntentParser;
import dev.mcattle.core.intent.ParsedCommand;
import dev.mcattle.core.llm.Orders.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalIntentParserTest {
    private static final List<String> NPCS = List.of("OakJames", "CobbleMaya", "CreeperJack");
    private static final List<String> PLAYERS = List.of("Steve", "Alex");

    private static ParsedCommand parse(String s) {
        return LocalIntentParser.parse(s, NPCS, PLAYERS);
    }

    private static Step first(String s) {
        ParsedCommand c = parse(s);
        assertTrue(c.understood(), "should understand: " + s + " (cleaned: " + c.cleaned() + ")");
        return c.steps().get(0);
    }

    @Test
    void addressing() {
        ParsedCommand c = parse("OakJames give me 5 wood blocks");
        assertEquals(List.of("OakJames"), c.addressed());
        assertFalse(c.everyone());
        c = parse("hey maya, follow me");
        assertEquals(List.of("CobbleMaya"), c.addressed());
        c = parse("everyone build me a house");
        assertTrue(c.everyone());
        assertTrue(c.addressed().isEmpty());
        // the Minecraft half of a name alone does not address anyone
        assertTrue(parse("i need cobble").addressed().isEmpty());
    }

    @Test
    void give() {
        Step s = first("OakJames give me 5 wood blocks");
        assertEquals("give", s.action());
        assertEquals("speaker", s.target());
        assertEquals("wood blocks", s.item());
        assertEquals(5, s.count());

        s = first("give alex a stack of cobblestone please");
        assertEquals("Alex", s.target());
        assertEquals("cobblestone", s.item());
        assertEquals(64, s.count());

        s = first("give me everything");
        assertEquals("*", s.item());

        s = first("bring me three diamonds");
        assertEquals(3, s.count());
        assertEquals("diamonds", s.item());
    }

    @Test
    void fights() {
        List<Step> steps = parse("CreeperJack attack me").steps();
        assertEquals("attack", steps.get(1).action());
        assertEquals("speaker", steps.get(1).target());
        assertFalse(steps.get(1).boolOption("lethal", true));
        assertTrue(parse("kill me").steps().get(1).boolOption("lethal", false));
        Step hunt = first("kill 5 zombies");
        assertEquals("hunt", hunt.action());
        assertEquals("zombie", hunt.item());
        assertEquals(5, hunt.count());
    }

    @Test
    void movement() {
        assertEquals("stop", first("stop").action());
        assertEquals("stop", first("Maya wait here").action());
        assertEquals("follow", first("follow me").action());
        assertEquals("goto", first("come here").action());
        assertEquals("autonomous", first("ok you're free").action());
        assertEquals("explore", first("go explore").action());
    }

    @Test
    void building() {
        Step s = first("build me a big cobblestone house");
        assertEquals("build", s.action());
        assertEquals("house", s.option("structure", ""));
        assertEquals("cobblestone", s.option("material", ""));
        assertEquals("large", s.option("size", ""));
        assertTrue(s.text().contains("cobblestone house"), s.text());
        s = first("build a castle out of stone bricks");
        assertEquals("fort", s.option("structure", ""));
        assertEquals("stone_bricks", s.option("material", ""));
    }

    @Test
    void crafting_and_gathering() {
        Step s = first("craft a diamond pickaxe");
        assertEquals("craft", s.action());
        assertEquals("diamond pickaxe", s.item());
        assertEquals(1, s.count());
        s = first("go chop some oak trees");
        assertEquals("chop", s.action());
        assertEquals("oak_log", s.item());
        s = first("mine 10 iron ore");
        assertEquals("mine", s.action());
        assertEquals(10, s.count());
        assertEquals("iron ore", s.item());
        s = first("get 20 cobblestone");
        assertEquals("obtain", s.action());
        assertEquals(20, s.count());
    }

    @Test
    void complexAndQuestions() {
        assertTrue(parse("chop some wood and then build a house next to the lake").complex());
        assertTrue(parse("what are you doing?").question());
        assertFalse(parse("how do I make a beacon").understood());
        assertFalse(parse("tell me a joke about creepers").understood());
    }
}
