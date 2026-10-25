package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style character event adapted to the 1.12.2 client.
 * Carries a single Unicode code point produced by keyboard input.
 */
public record CharacterEvent(int codepoint) {

    public String codepointAsString() {
        return new String(Character.toChars(this.codepoint));
    }

    public boolean isAllowedChatCharacter() {
        return this.codepoint != 167 && this.codepoint >= 32 && this.codepoint != 127;
    }
}
