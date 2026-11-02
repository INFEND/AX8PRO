package ru.xarkon.ax8;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Minimal BusyBox Telnet client on AX8 port 4719. Runs exclusively on worker thread. */
final class TelnetShell implements Closeable {
    private final RouterClient api;
    private final String user,pass;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int state=0, op=0;

    TelnetShell(RouterClient api,String user,String pass){this.api=api;this.user=user;this.pass=pass;}
    private void send(String s)throws IOException {out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}
    private boolean portOpen(){try(Socket s=new Socket()){s.connect(new InetSocketAddress(api.hostname(),4719),650);return true;}catch(Exception e){return false;}}
    private void startDaemon()throws Exception {
        api.ensureAuth();
        api.post(RouterClient.map("goformId","tw_telnet_config","telnetd_enable","0","debug_enable","0"));
        Thread.sleep(700);
        org.json.JSONObject result=api.post(RouterClient.map("goformId","tw_telnet_config","telnetd_enable","1","debug_enable","0"));
        if(!"success".equalsIgnoreCase(result.optString("result","")) && !"ok".equalsIgnoreCase(result.optString("result","")) && !"0".equals(result.optString("result","")))
            throw new IOException("Не удалось запустить Telnet: "+result);
        for(int i=0;i<20;i++){if(portOpen())return;Thread.sleep(200);}
        throw new IOException("Telnet 4719 не открылся");
    }
    private String read(int timeoutMs)throws Exception {
        socket.setSoTimeout(timeoutMs);
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] raw=new byte[4096];
        int n;try{n=in.read(raw);}catch(SocketTimeoutException e){return "";}
        if(n<0)throw new EOFException("Telnet разорван");
        for(int i=0;i<n;i++) {
            int c=raw[i]&255;
            if(state==0){if(c==255)state=1;else b.write(c);}
            else if(state==1){if(c==255){b.write(c);state=0;}else if(c>=251&&c<=254){op=c;state=2;}else if(c==250)state=3;else state=0;}
            else if(state==2){int response=(op==253||op==254)?252:254;out.write(new byte[]{(byte)255,(byte)response,(byte)c});out.flush();state=0;}
            else if(state==3){if(c==255)state=4;}
            else if(state==4){state=c==240?0:3;}
        }
        return b.toString("UTF-8");
    }
    private String until(String[] needles,int totalMs)throws Exception {
        long deadline=TelnetReadPolicy.deadlineAfter(totalMs);StringBuilder result=new StringBuilder();
        while(!TelnetReadPolicy.expired(deadline)){TelnetReadPolicy.appendBounded(result,read(550));for(String s:needles)if(result.indexOf(s)>=0)return result.toString();}
        throw new SocketTimeoutException("Telnet не отвечает: "+String.join("/",needles));
    }
    private void connect()throws Exception {
        close();if(!portOpen())startDaemon();
        socket=new Socket();socket.connect(new InetSocketAddress(api.hostname(),4719),3000);
        in=socket.getInputStream();out=socket.getOutputStream();state=0;
        until(new String[]{"login:"},5000);send(user+"\r\n");
        until(new String[]{"Password:"},5000);send(pass+"\r\n");
        String greeting=until(new String[]{"~ #","# "},6000);
        if(greeting.contains("Login incorrect"))throw new IOException("Неверные данные Telnet");
    }
    static final class Result {final String output;final int code;Result(String s,int c){output=s;code=c;}}
    synchronized Result exec(String command,int timeoutMs)throws Exception {
        if(socket==null||socket.isClosed())connect();
        String marker="__AX8M_"+Math.abs(System.nanoTime()%1_000_000_000L)+"__";
        try {
            send(command+"\n"+"echo "+marker+":$?\n");
            java.util.regex.Pattern markerPattern=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(marker)+":(\\d+)");
            StringBuilder outputBuffer=new StringBuilder();
            java.util.regex.Matcher match=markerPattern.matcher(outputBuffer);
            long deadline=TelnetReadPolicy.deadlineAfter(timeoutMs);
            while(!match.find() && !TelnetReadPolicy.expired(deadline)) {
                TelnetReadPolicy.appendBounded(outputBuffer,read(450));
                match=markerPattern.matcher(outputBuffer);
            }
            if(!match.find(0))throw new SocketTimeoutException("Не получен код ответа Telnet");
            String output=outputBuffer.toString();
            int rc=Integer.parseInt(match.group(1));
            String result=output.substring(0,match.start()).replace("\r","");
            StringBuilder clean=new StringBuilder();String first=command.split("\n")[0].trim();
            for(String row:result.split("\n")){
                String line=row.trim();if(line.isEmpty()||line.equals(first)||line.equals("#")||line.equals("~ #")||line.startsWith("echo "+marker))continue;
                if(clean.length()>0)clean.append('\n');clean.append(row);
            }
            return new Result(clean.toString(),rc);
        }catch(Exception e){close();throw e;}
    }
    synchronized Result exec(String command)throws Exception {return exec(command,6000);}
    @Override public synchronized void close(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;in=null;out=null;}
}
