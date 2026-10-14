package dev.mcattle.core.social;

import java.util.Locale;

/**
 * Things that happen between an NPC and someone else, with how they move the NPC's feelings and its view of
 * that someone. Amounts are for one occurrence at strength 1; the temperament scales them.
 */
public enum SocialEvent {
    //               joy  sad  ang  fear trust disg surp antic | like  trust respect fear  famil | memory
    HIT(              0,  .10, .35, .15,  0,   0,  .15,  0,     -8,   -6,   0,     4,    1,    "hit me"),
    SPARRED(        .15,   0,   0,   0,   0,   0,   0,  .25,     1,    0,   2,     0,    2,    null),
    KILLED_ME(        0,  .35, .70, .30,  0,   0,  .20,  0,    -25,  -20,  -2,    15,    2,    "killed me"),
    BEAT_ME(          0,  .20, .30,   0,  0,   0,   0,  .20,    -2,    0,   8,     3,    2,    "beat me in a fight"),
    I_BEAT(         .35,   0,   0,   0,   0,   0,   0,  .10,     0,    0,  -3,    -3,    2,    "lost a fight to me"),
    HURT_FRIEND(      0,  .30, .45, .15,  0,  .20,  .20,  0,    -12,  -10,  -4,     4,    1,    "attacked someone i care about"),
    KILLED_FRIEND(    0,  .55, .65, .20,  0,  .30,  .30,  0,    -25,  -20,  -6,     8,    1,    "killed someone i care about"),
    GIFT(           .40,   0,   0,   0, .20,   0,  .20,  0,      6,    4,   1,     0,    2,    "gave me"),
    HELPED(         .20,   0,   0,   0, .30,   0,   0,   0,      4,    5,   3,     0,    2,    "helped me"),
    SAVED(          .30,   0,   0, -.30, .50,  0,  .20,  0,     12,   12,   6,    -2,    3,    "saved me"),
    WORKED_TOGETHER(.15,   0,   0,   0, .10,   0,   0,  .10,     2,    2,   1,     0,    3,    null),
    PLAYED_WITH(    .35,   0,   0,   0, .10,   0,   0,  .20,     3,    1,   0,     0,    4,    null),
    TIME_TOGETHER(  .03,   0,   0,   0, .02,   0,   0,   0,    0.3,  0.2,   0,     0,  0.6,    null),
    PRAISED(        .30,   0,   0,   0, .10,   0,  .10,  0,      4,    1,   1,     0,    1,    "said something nice to me"),
    THANKED(        .20,   0,   0,   0, .10,   0,   0,   0,      2,    1,   0,     0,    1,    null),
    INSULTED(         0,  .20, .30,   0,   0,  .15, .10,  0,     -5,   -1,  -2,     0,    1,    "insulted me"),
    TEASED(         .10,   0,  .10,   0,   0,   0,  .05,  0,    0.5,    0,   0,     0,    2,    null),
    APOLOGIZED(     .10,  -.10,-.30,   0, .10,   0,  .10,  0,      5,    3,   1,    -2,    1,    "apologized"),
    THREATENED(       0,   0,  .20, .35,  0,   0,  .10,  0,     -4,   -5,   0,     6,    1,    "threatened me"),
    BETRAYED(         0,  .45, .50,   0, -.40, .20, .40,  0,    -15,  -30,  -5,     0,    1,    "betrayed me"),
    FLIRTED(        .30,   0,   0, .05, .10,   0,  .20, .10,      3,    1,   0,     0,    2,    "flirted with me"),
    BOSSED_AROUND(    0,   0,  .10,   0,   0,  .05,   0,   0,   -0.5,    0,  -0.5,   0,    1,    null),
    IGNORED(          0,  .15, .05,   0,   0,   0,   0,   0,     -1,    0,   0,     0,    0,    null),
    BROKE_MY_STUFF(   0,  .20, .40,   0,   0,  .10, .10,  0,     -6,   -5,  -1,     0,    1,    "broke my stuff");

    final double[] mood;
    final double affinity, trust, respect, fear, familiarity;
    final String memory;

    SocialEvent(double joy, double sad, double anger, double fear, double trust, double disgust, double surprise,
                double anticipation, double like, double trustDelta, double respect, double fearDelta,
                double familiarity, String memory) {
        this.mood = new double[]{joy, sad, anger, fear, trust, disgust, surprise, anticipation};
        this.affinity = like;
        this.trust = trustDelta;
        this.respect = respect;
        this.fear = fearDelta;
        this.familiarity = familiarity;
        this.memory = memory;
    }

    /** True for things that make the NPC like the other one less. */
    public boolean negative() {
        return affinity < 0;
    }

    /** Parses names the language model uses ("insulted", "insult", "gift", "helped"...). */
    public static SocialEvent parse(String word) {
        if (word == null) return null;
        String w = word.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (SocialEvent e : values()) if (e.name().equals(w)) return e;
        return switch (w) {
            case "INSULT", "MOCKED", "RUDE", "DISRESPECTED" -> INSULTED;
            case "PRAISE", "COMPLIMENTED", "COMPLIMENT", "NICE", "KIND" -> PRAISED;
            case "THANKS", "THANK" -> THANKED;
            case "TEASE", "JOKED", "BANTER" -> TEASED;
            case "APOLOGY", "APOLOGISE", "APOLOGISED", "APOLOGIZE", "SORRY" -> APOLOGIZED;
            case "THREAT", "THREATEN" -> THREATENED;
            case "BETRAY", "LIED", "LIED_TO_ME" -> BETRAYED;
            case "FLIRT", "FLIRTING" -> FLIRTED;
            case "HELP" -> HELPED;
            case "GIFTED", "GAVE" -> GIFT;
            case "PLAYED", "PLAY", "FUN" -> PLAYED_WITH;
            case "BOSSY", "ORDERED" -> BOSSED_AROUND;
            case "IGNORE" -> IGNORED;
            default -> null;
        };
    }
}
