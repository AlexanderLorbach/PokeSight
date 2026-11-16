package de.alorbach.pokesight.game;

/** Where a game is listed on the start screen, in this order: official games, ROM hacks, then custom games. */
public enum GameCategory {
    /** Main-series games by Game Freak. */
    OFFICIAL,
    /** Fan-made changes of a game's ROM, played in an emulator. */
    ROM_HACK,
    /**
     * Fan games that are neither: e.g. Platinum Together, a multiplayer mod with its own launcher, whose rules can be
     * switched on the start screen ({@link GameDefinition#options()}).
     */
    CUSTOM
}
