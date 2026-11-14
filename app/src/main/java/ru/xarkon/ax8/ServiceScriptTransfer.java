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
    /** Quoted here documents do not interpret shell syntax, backslashes or
     * dollar signs in the data. Keep each batch small for older Telnet daemons. */
    static List<String> heredocCommands(String destination,byte[] bytes){
        String p=path(destination),s=ascii(bytes),delimiter="AX8_SERVICE_FILE_END";
        String[] lines=s.split("\n",-1);
        Set<String> lineSet=new HashSet<>(Arrays.asList(lines));
        while(lineSet.contains(delimiter))delimiter+="X";
        List<String> result=new ArrayList<>();
        StringBuilder batch=new StringBuilder();
        for(int i=0;i<lines.length-1;i++){
            String line=lines[i];
            if(line.length()>512)throw new IllegalArgumentException("Service file line too long");
            if(batch.length()>0&&batch.length()+line.length()+1>512){
                result.add("cat <<'"+delimiter+"' "+(result.isEmpty()?"> ":">> ")+p+"\n"+batch+delimiter);
                batch.setLength(0);
            }
            batch.append(line).append('\n');
        }
        if(batch.length()>0)result.add("cat <<'"+delimiter+"' "+(result.isEmpty()?"> ":">> ")+p+"\n"+batch+delimiter);
        return result;
    }
    /** Split readback records below typical terminal width. */
    static String chunkReadCommand(String destination){
        return "awk '{if(length($0)==0)printf \"AX8PART:%d:0:\\n\",NR;"+
            "else for(i=1;i<=length($0);i+=32)printf \"AX8PART:%d:%d:%s\\n\",NR,i-1,substr($0,i,32)}' "+path(destination);
    }
    static int chunkMismatch(byte[] bytes,String remote){
        String[] lines=ascii(bytes).split("\n",-1);
        List<String> expected=new ArrayList<>();
        List<Integer> lineNumbers=new ArrayList<>();
        for(int i=0;i<lines.length-1;i++){
            String line=lines[i];
            for(int j=0;j<Math.max(1,line.length());j+=32){
                expected.add("AX8PART:"+(i+1)+":"+j+":"+line.substring(j,Math.min(j+32,line.length())));
                lineNumbers.add(i+1);
            }
        }
        int next=0;
        for(String row:remote.replace("\r","").split("\n",-1)){
            if(!row.startsWith("AX8PART:"))continue;
            if(next>=expected.size())return lines.length;
            if(!expected.get(next).equals(row))return lineNumbers.get(next);
            next++;
        }
        return next==expected.size()?0:lineNumbers.get(next);
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
