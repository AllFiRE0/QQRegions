package dev.qqregions.selection;

/**
 * Направления расширения выделения.
 */
public enum ExpandDirection {
    NORTH, SOUTH, EAST, WEST, UP, DOWN;

    public static ExpandDirection fromString(String s) {
        if (s == null) {
            return null;
        }
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "north":
            case "n":
                return NORTH;
            case "south":
            case "s":
                return SOUTH;
            case "east":
            case "e":
                return EAST;
            case "west":
            case "w":
                return WEST;
            case "up":
            case "u":
                return UP;
            case "down":
            case "d":
                return DOWN;
            default:
                return null;
        }
    }
}