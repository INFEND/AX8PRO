package ru.xarkon.ax8;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;

/** One bounded, read-only external connectivity probe on the actual AX8 Wi-Fi/LAN network. */
public final class RouterInternetProbe {
    private static final String[] ENDPOINTS={
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204"
    };
    private RouterInternetProbe(){}

    public static final class Result {
        public final int state;
        public final Network network;
        Result(int state,Network network){this.state=state;this.network=network;}
    }
    public static Result check(Context context,String routerBase){
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return new Result(InternetStatusPolicy.UNAVAILABLE,null);
        try {
            URI uri=new URI(routerBase);
            String host=uri.getHost();
            int port=uri.getPort()>0?uri.getPort():
                "https".equalsIgnoreCase(uri.getScheme())?443:80;
            if(host==null||host.isEmpty())return new Result(InternetStatusPolicy.UNAVAILABLE,null);
            // Never test through the default/mobile/VPN network: only AX8's Wi-Fi or wired LAN.
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps==null||(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        &&!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
                if(!canReachRouter(network,host,port))continue;
                for(String endpoint:ENDPOINTS)if(reachesInternet(network,endpoint))
                    return new Result(InternetStatusPolicy.ONLINE,network);
                return new Result(InternetStatusPolicy.OFFLINE,network);
            }
        }catch(Exception ignored){}
        return new Result(InternetStatusPolicy.UNAVAILABLE,null);
    }

    /** Confirm this exact network reaches the configured AX8, without making a router API call. */
    private static boolean canReachRouter(Network network,String host,int port){
        try {
            for(InetAddress address:network.getAllByName(host)){
                try(Socket socket=network.getSocketFactory().createSocket()){
                    socket.connect(new InetSocketAddress(address,port),1500);
                    return true;
                }catch(Exception ignored){}
            }
        }catch(Exception ignored){}
        return false;
    }
    private static boolean reachesInternet(Network network,String endpoint){
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)network.openConnection(new URL(endpoint));
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Cache-Control","no-cache");
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
                return cap!=null&&(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                         ||cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
        }catch(Exception ignored){}
        return false;
    }
}
