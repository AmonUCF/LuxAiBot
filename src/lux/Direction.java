package lux;

public enum Direction {
    NORTH("n"), EAST("e"), SOUTH("s"), WEST("w"), CENTER("c");

    public String str;

    Direction(final String s) {
        this.str = s;
    }

    public static Direction getDir(String s) {
        switch(s) {
            case "n":
                return NORTH;
            case "e":
                return EAST;
            case "s":
                return SOUTH;
            case "w":
                return WEST;
            default:
                return CENTER;
        }
    }
    @Override
    public String toString() {
        return this.str;
    }
}