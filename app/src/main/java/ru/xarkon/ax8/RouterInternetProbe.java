package ru.xarkon.ax8;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** One bounded, read-only external connectivity probe on the actual AX8 Wi-Fi/LAN network. */
public final class RouterInternetProbe {
    private static final String[] ENDPOINTS={
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    };
    private static final long PROBE_BUDGET_MS=12000L;
    private RouterInternetProbe(){}

    private static boolean exhausted(long deadline){
        return Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime()>=deadline;
    }

    // Zero means do not start I/O, never an infinite Java socket timeout.
    private static int remainingTimeout(long deadline,int maximum){
        if(Thread.currentThread().isInterrupted())return 0;
        long remaining=deadline-SystemClock.elapsedRealtime();
        return remaining<=0?0:(int)Math.min(remaining,maximum);
    }

    public static final class Result {
        public final int state;
        public final Network network;
        Result(int state,Network network){this.state=state;this.network=network;}
    }
    public static Result check(Context context,String routerBase){
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return new Result(InternetStatusPolicy.UNAVAILABLE,null);
        final long deadline=SystemClock.elapsedRealtime()+PROBE_BUDGET_MS;
        try {
            URI uri=new URI(routerBase);
            String host=uri.getHost();
            int port=uri.getPort()>0?uri.getPort():
                "https".equalsIgnoreCase(uri.getScheme())?443:80;
            if(host==null||host.isEmpty())return new Result(InternetStatusPolicy.UNAVAILABLE,null);
            // Never test through the default/mobile/VPN network: only AX8's Wi-Fi or wired LAN.
            // A VPN may advertise its underlying Wi-Fi transport as well: reject it explicitly.
            List<Network> routerCandidates=new ArrayList<>();
            List<Network> offlineCandidates=new ArrayList<>();
            for(Network network:cm.getAllNetworks()){
                if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(!isRouterLink(caps))continue;
                // On some Android/VPN configurations the router WebUI is reachable
                // but a separately network-bound TCP socket to it is not. Accept
                // the router's *actual default gateway* as alternative link proof.
                // Gateway proof is sufficient: avoid waiting for a TCP timeout first.
                if(!isRouterGateway(cm,network,host) && !canReachRouter(network,host,port,deadline))
                    continue;
                if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                // Router reachability may have blocked while the network changed.
                // Read current capabilities rather than trusting the pre-socket snapshot.
                caps=cm.getNetworkCapabilities(network);
                if(!isRouterLink(caps))continue;
                // Android has already independently checked Internet access on this
                // exact router-reachable physical network. Reuse that fresh platform
                // verdict instead of waiting up to 15 seconds for three serial HTTPS
                // probes. The cellular default network and VPN are excluded above.
                if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    return new Result(InternetStatusPolicy.ONLINE,network);
                routerCandidates.add(network);
            }
            // Prefer system-validated links before any slow external HTTPS request.
            for(Network network:routerCandidates){
                if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                if(!networkStillPresent(context,network))continue;
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    return new Result(InternetStatusPolicy.ONLINE,network);
                // Without Android validation, require a successful router-bound HTTPS
                // probe; a working WebUI alone is not proof of Internet access.
                for(String endpoint:ENDPOINTS){
                    if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                    if(!networkStillPresent(context,network))break;
                    // Another proven link may have recovered while a previous request ran.
                    for(Network candidate:routerCandidates){
                        NetworkCapabilities current=cm.getNetworkCapabilities(candidate);
                        if(current!=null && current.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                && networkStillPresent(context,candidate))
                            return new Result(InternetStatusPolicy.ONLINE,candidate);
                    }
                    // The candidate scan may have outlived the current physical link.
                    if(!networkStillPresent(context,network))break;
                    if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                    boolean reached=reachesInternet(network,endpoint,deadline);
                    if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
                    if(reached && networkStillPresent(context,network))
                        return new Result(InternetStatusPolicy.ONLINE,network);
                    // Android may validate this same physical link during a slow probe.
                    // Reuse that verdict before starting another external request.
                    caps=cm.getNetworkCapabilities(network);
                    if(!isRouterLink(caps))break;
                    if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            && networkStillPresent(context,network))
                        return new Result(InternetStatusPolicy.ONLINE,network);
                }
                if(networkStillPresent(context,network))offlineCandidates.add(network);
            }
            // Revalidate every previously proven router link before returning OFFLINE.
            // A later link may recover or the first candidate may disappear.
            if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
            Network offlineCandidate=null;
            for(Network candidate:offlineCandidates){
                if(!networkStillPresent(context,candidate))continue;
                NetworkCapabilities latest=cm.getNetworkCapabilities(candidate);
                if(!isRouterLink(latest))continue;
                if(latest.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    return new Result(InternetStatusPolicy.ONLINE,candidate);
                if(offlineCandidate==null)offlineCandidate=candidate;
            }
            if(exhausted(deadline))return new Result(InternetStatusPolicy.UNAVAILABLE,null);
            if(offlineCandidate!=null && networkStillPresent(context,offlineCandidate))
                return new Result(InternetStatusPolicy.OFFLINE,offlineCandidate);
        }catch(Exception ignored){}
        return new Result(InternetStatusPolicy.UNAVAILABLE,null);
    }

    private static boolean isRouterLink(NetworkCapabilities caps){
        return caps!=null
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /** Match an AX8 LAN by its actual default gateway when TCP reachability is masked. */
    private static boolean isRouterGateway(ConnectivityManager cm,Network network,String host){
        try {
            LinkProperties link=cm.getLinkProperties(network);
            if(link==null)return false;
            for(RouteInfo route:link.getRoutes()){
                InetAddress gateway=route.getGateway();
                if(route.isDefaultRoute() && gateway!=null &&
                        host.equalsIgnoreCase(gateway.getHostAddress()))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    /** Confirm this exact network reaches the configured AX8, without making a router API call. */
    private static boolean canReachRouter(Network network,String host,int port,long deadline){
        try {
            if(exhausted(deadline))return false;
            for(InetAddress address:network.getAllByName(host)){
                if(exhausted(deadline))return false;
                try(Socket socket=network.getSocketFactory().createSocket()){
                    int timeout=remainingTimeout(deadline,1500);
                    if(timeout==0)return false;
                    socket.connect(new InetSocketAddress(address,port),timeout);
                    return !exhausted(deadline);
                }catch(Exception ignored){}
            }
        }catch(Exception ignored){}
        return false;
    }
    private static boolean reachesInternet(Network network,String endpoint,long deadline){
        HttpURLConnection connection=null;
        try {
            if(exhausted(deadline))return false;
            connection=(HttpURLConnection)network.openConnection(new URL(endpoint));
            int timeout=remainingTimeout(deadline,2500);
            if(timeout==0)return false;
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Cache-Control","no-cache");
            if(exhausted(deadline))return false;
            return connection.getResponseCode()==204;
        }catch(Exception ignored){return false;}
        finally{if(connection!=null)connection.disconnect();}
    }
    public static boolean networkStillPresent(Context context,Network network){
        if(network==null)return false;
        try{
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return false;
            for(Network candidate:cm.getAllNetworks())if(network.equals(candidate)){
                NetworkCapabilities cap=cm.getNetworkCapabilities(candidate);
                return isRouterLink(cap);
            }
        }catch(Exception ignored){}
        return false;
    }
}
