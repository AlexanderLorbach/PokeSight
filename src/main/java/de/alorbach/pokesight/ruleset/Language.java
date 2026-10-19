package de.alorbach.pokesight.ruleset;

/** Game language. Selects the ROM's memory offsets and the display names. */
public enum Language {
    EN("English"),
    DE("Deutsch");

    private final String displayName;

    Language(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
