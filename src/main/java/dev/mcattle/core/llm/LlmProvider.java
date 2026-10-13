package dev.mcattle.core.llm;

/** A blocking language-model backend. Called from a worker thread, never from the server thread. */
public interface LlmProvider {
    /** Plans orders for the situation report. */
    LlmResult<Orders> plan(String situation) throws Exception;

    /** Writes a few banter lines. */
    LlmResult<Banter> banter(String situation) throws Exception;

    String describe();
}
