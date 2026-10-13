package dev.mcattle.core.llm;

/** A blocking language-model backend. Called from a worker thread, never from the server thread. */
public interface LlmProvider {
    /** Plans orders for the situation report. */
    LlmResult<Orders> plan(String situation) throws Exception;

    /** Writes a few banter lines. */
    LlmResult<Banter> banter(String situation) throws Exception;

    /** Designs a free-form structure for the build request in the context. */
    LlmResult<Design> design(String context) throws Exception;

    /** Decides the next round of an ongoing activity. */
    LlmResult<ActivityTurn> direct(String context) throws Exception;

    /** Rewrites the canned chat lines in an NPC's speaking style. */
    LlmResult<StyleLines> restyle(String context) throws Exception;

    String describe();
}
