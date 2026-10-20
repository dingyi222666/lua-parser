package fixture.dynamic;

public class Greeter {
    public static int Z_COUNT;
    public String aLabel;

    public static Greeter create() {
        return new Greeter();
    }

    public String greet() {
        return "dynamic dex";
    }

    public static String libraryMarker() {
        return "runtime import";
    }
}
