package ru.xarkon.ax8;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** ASCII-only literal transfer, with exact indexed readback. No escape decoding
 * by the remote shell (old BusyBox printf implementations differ).
 */
final class ServiceScriptTransfer {
    private static final String PREFIX="AX8FILE:";
    private ServiceScriptTransfer(){}
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String path(String p){
        if(!p.matches("/tmp/ax8pro-services/[a-z.]+"))throw new IllegalArgumentException("Unsafe service path");
        return quote(p);
    }
    static String ascii(byte[] bytes){
        for(byte b:bytes)if(b!='\n'&&b!='\t'&&(b<32||b>126))
            throw new IllegalArgumentException("Non-ASCII service transfer");
        String s=new String(bytes,StandardCharsets.US_ASCII);
        if(!s.endsWith("\n"))throw new IllegalArgumentException("Missing final newline");
        return s;
    }
    static List<String> commands(String destination,byte[] bytes){
        String p=path(destination),s=ascii(bytes);
        List<String> commands=new ArrayList<>();
        commands.add(": > "+p);
        String[] lines=s.split("\n",-1);
        for(int i=0;i<lines.length-1;i++){
            String line=lines[i];
            // Split long lines into literal fragments, never splitting quoting.
            // At most 64 characters per fragment, including arbitrary apostrophes.
            int offset=0;
            while(line.length()-offset>64){
                commands.add("printf '%s' "+quote(line.substring(offset,offset+64))+" >> "+p);
                offset+=64;
            }
            commands.add("printf '%s\\n' "+quote(line.substring(offset))+" >> "+p);
        }
        return commands;
    }
    static String readCommand(String destination){
        return "awk '{printf \"AX8FILE:%d:%s\\n\", NR, $0}' "+path(destination);
    }
    static int mismatch(byte[] bytes,String remote){
        String[] expected=ascii(bytes).split("\n",-1);
        int next=1;
        for(String row:remote.replace("\r","").split("\n",-1)){
            if(!row.startsWith(PREFIX))continue;
            String tag=PREFIX+next+":";
            if(next>=expected.length||!row.equals(tag+expected[next-1]))return next;
            next++;
        }
        return next==expected.length?0:next;
    }
}
