package dev.mcattle.core;

import com.google.gson.Gson;
import dev.mcattle.core.social.Emotion;
import dev.mcattle.core.social.Relationship;
import dev.mcattle.core.social.SocialEvent;
import dev.mcattle.core.social.SocialMind;
import dev.mcattle.core.social.SocialParse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SocialTest {
    private static final long T0 = 1_700_000_000_000L;

    @Test
    void hitsMakeEnemiesAndAreRemembered() {
        SocialMind m = new SocialMind(42);
        for (int i = 0; i < 4; i++) m.event(SocialEvent.HIT, "steve", "Steve", false, 1.5, null, T0 + i);
        Relationship r = m.relation("steve");
        assertTrue(r.affinity < -20, "affinity " + r.affinity);
        assertTrue(r.trust < 0);
        assertEquals(4, r.hits);
        assertEquals(1, r.episodes().size());
        assertEquals(4, r.episodes().get(0).times);
        assertTrue(m.get(Emotion.ANGER) > 0.4);
        assertTrue(r.describe(3).contains("hit me (x4)"));
        assertNotEquals("friend", r.label());
    }

    @Test
    void kindnessBuildsFriendshipWithDiminishingReturns() {
        SocialMind m = new SocialMind(7);
        for (int i = 0; i < 60; i++) {
            m.event(SocialEvent.GIFT, "alex", "Alex", false, 2, "1 diamond", T0);
            m.event(SocialEvent.HELPED, "alex", "Alex", false, 1, null, T0);
        }
        Relationship r = m.relation("alex");
        assertTrue(r.affinity > 70 && r.affinity <= 100, "affinity " + r.affinity);
        assertTrue(r.label().contains("friend"));
        assertTrue(m.get(Emotion.JOY) > 0.5);
    }

    @Test
    void moodSettlesAndGrudgesFade() {
        SocialMind m = new SocialMind(3);
        m.event(SocialEvent.KILLED_ME, "steve", "Steve", false, 1, null, T0);
        double anger = m.get(Emotion.ANGER);
        double like = m.affinity("steve");
        m.tick(T0);
        m.tick(T0 + 60 * 60_000L);                   // an hour later: calm again
        assertTrue(m.get(Emotion.ANGER) < anger * 0.2);
        for (int h = 2; h <= 48; h++) m.tick(T0 + h * 3_600_000L); // two days: grudge softer but not gone overnight
        double later = m.affinity("steve");
        assertTrue(later > like && later <= 0, like + " -> " + later);
        assertFalse(m.relation("steve").episodes().isEmpty());
    }

    @Test
    void hurtFromAFriendStingsMore() {
        SocialMind m = new SocialMind(11);
        m.relation("bob", "Bob", true, T0).affinity = 80;
        double sadBefore = m.get(Emotion.SADNESS);
        m.event(SocialEvent.HIT, "bob", "Bob", true, 1, null, T0);
        assertTrue(m.get(Emotion.SADNESS) - sadBefore > 0.15);
    }

    @Test
    void emotionWordsAndDescriptions() {
        assertNotNull(Emotion.blend("jealous"));
        assertNotNull(Emotion.blend("Loved"));
        assertNotNull(Emotion.blend("ANGER"));
        assertNull(Emotion.blend("xyzzy"));
        SocialMind m = new SocialMind(5);
        assertTrue(m.feel("furious", 2.5));
        assertTrue(m.moodWords().contains("angry") || m.moodWords().contains("furious") || m.moodWords().contains("annoyed"), m.moodWords());
        assertEquals(Emotion.ANGER, m.dominant());
        assertTrue(m.valence() < 0);
        assertFalse(m.temperament().describe().isBlank());
    }

    @Test
    void parsesModelFieldsAndChatTone() {
        List<SocialParse.Feel> f = SocialParse.feelings("Steve:insulted:2 self:jealous, Alex:grateful");
        assertEquals(3, f.size());
        assertEquals("Steve", f.get(0).who());
        assertEquals(2.0, f.get(0).strength());
        assertEquals(SocialEvent.INSULTED, SocialEvent.parse(f.get(0).word()));
        assertEquals(SocialEvent.PRAISED, SocialEvent.parse("compliment"));
        Map<String, String> bonds = SocialParse.bonds("Steve=frenemies since the diamond incident; Alex = her biggest fan");
        assertEquals("frenemies since the diamond incident", bonds.get("Steve"));
        assertEquals("her biggest fan", bonds.get("Alex"));
        assertEquals(SocialEvent.INSULTED, SocialParse.tone("OakJames you're so useless"));
        assertEquals(SocialEvent.THANKED, SocialParse.tone("thanks bro"));
        assertEquals(SocialEvent.APOLOGIZED, SocialParse.tone("sorry for hitting you"));
        assertEquals(SocialEvent.PRAISED, SocialParse.tone("good job!"));
        assertNull(SocialParse.tone("go mine some iron"));
    }

    @Test
    void survivesJsonRoundTrip() {
        SocialMind m = new SocialMind(9);
        m.event(SocialEvent.GIFT, "u1", "Steve", false, 1, "3 cake", T0);
        m.setBond("u1", "Steve", false, "best buds", T0);
        m.feel("excited", 1);
        Gson gson = new Gson();
        SocialMind back = gson.fromJson(gson.toJson(m), SocialMind.class);
        assertEquals(m.affinity("u1"), back.affinity("u1"), 1e-9);
        assertEquals("best buds", back.relation("u1").bond);
        assertEquals(m.get(Emotion.JOY), back.get(Emotion.JOY), 1e-9);
        assertEquals(m.temperament(), back.temperament());
        assertTrue(back.relation("u1").describe(3).contains("gave me 3 cake"));
    }
}
