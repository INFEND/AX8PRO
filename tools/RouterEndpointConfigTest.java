import ru.xarkon.ax8.RouterEndpointConfig;
public final class RouterEndpointConfigTest {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        RouterEndpointConfig original = new RouterEndpointConfig("192.168.150.1", "root", "secret");
        check(original.sameConnection(new RouterEndpointConfig("http://192.168.150.1/", "root", "secret")), "equivalent WebUI address");
        check(original.sameHost(new RouterEndpointConfig(" 192.168.150.1 ", "different", "other")), "same host despite changed credentials");
        check(!original.sameConnection(new RouterEndpointConfig("192.168.150.1", "root", "changed")), "password change requires rebind");
        check(!original.sameConnection(new RouterEndpointConfig("192.168.150.1", "admin", "secret")), "username change requires rebind");
        check(!original.sameHost(new RouterEndpointConfig("192.168.150.2", "root", "secret")), "router change resets cache");
        check(!original.sameHost(new RouterEndpointConfig("https://192.168.150.1", "root", "secret")), "protocol change resets cache");
        check(!original.sameConnection(null), "missing connection differs");
        check(new RouterEndpointConfig(null, null, null).sameConnection(new RouterEndpointConfig("", "", "")), "null fields safe");
        check(!original.toString().contains("secret"), "credentials not exposed by diagnostics");
        System.out.println("Router endpoint identity: " + checks + " checks PASSED");
    }
}
