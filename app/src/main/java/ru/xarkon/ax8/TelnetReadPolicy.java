package ru.xarkon.ax8;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Bounded Telnet responses and monotonic command deadlines, independent of wall-clock changes. */
public final class TelnetReadPolicy {
    public static final int MAX_OUTPUT_CHARS = 2_000_000;
    private TelnetReadPolicy(){}

    public static long deadlineAfter(int timeoutMs){
        return deadlineAfterAt(System.nanoTime(),timeoutMs);
    }
    public static long deadlineAfterAt(long startNanos,int timeoutMs){
        // Telnet operations always use positive timeouts; reject invalid callers.
        if(timeoutMs<=0)throw new IllegalArgumentException("Telnet timeout must be positive");
        return startNanos+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }
    public static boolean expired(long deadlineNanos){
        return expiredAt(System.nanoTime(),deadlineNanos);
    }
    public static boolean expiredAt(long nowNanos,long deadlineNanos){
        // Subtraction remains correct across nanoTime's signed wrap for short deadlines.
        return nowNanos-deadlineNanos>=0;
    }
    public static void appendBounded(StringBuilder output,String chunk)throws IOException{
        if(chunk==null||chunk.isEmpty())return;
        if(chunk.length()>MAX_OUTPUT_CHARS-output.length())
            throw new IOException("Ответ Telnet слишком большой");
        output.append(chunk);
    }
}
