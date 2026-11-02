package ru.xarkon.ax8;

/** Immutable WebUI connection identity; never include credentials in diagnostics. */
public final class RouterEndpointConfig {
    public final String host, username, password;
    private final String normalizedHost;

    public RouterEndpointConfig(String host, String username, String password) {
        this.host = host == null ? "" : host.trim();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        String normalized = this.host;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://"))
            normalized = "http://" + normalized;
        while (normalized.endsWith("/") && normalized.length() > 8)
            normalized = normalized.substring(0, normalized.length() - 1);
        normalizedHost = normalized;
    }

    public boolean sameHost(RouterEndpointConfig other) {
        return other != null && normalizedHost.equals(other.normalizedHost);
    }

    public boolean sameConnection(RouterEndpointConfig other) {
        return sameHost(other) && username.equals(other.username) && password.equals(other.password);
    }
}
