package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;

public final class SayTask extends Task {
    private final String text;

    public SayTask(String text) {
        this.text = text;
    }

    @Override
    protected Status run(NpcBrain npc) {
        npc.say(text);
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "talking";
    }
}
