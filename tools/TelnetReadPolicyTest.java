import java.io.IOException;
import ru.xarkon.ax8.TelnetReadPolicy;

public final class TelnetReadPolicyTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        long start=123456789L;
        long deadline=TelnetReadPolicy.deadlineAfterAt(start,6000);
        check(deadline-start==6_000_000_000L,"deadline uses nanoseconds");
        check(!TelnetReadPolicy.expiredAt(start,deadline),"deadline is not immediately expired");
        check(!TelnetReadPolicy.expiredAt(deadline-1,deadline),"deadline not expired before boundary");
        check(TelnetReadPolicy.expiredAt(deadline,deadline),"deadline expires at boundary");
        check(TelnetReadPolicy.expiredAt(deadline+1,deadline),"deadline stays expired");
        long nearWrap=Long.MAX_VALUE-1_000_000L;
        long wrapDeadline=TelnetReadPolicy.deadlineAfterAt(nearWrap,10);
        check(!TelnetReadPolicy.expiredAt(nearWrap,wrapDeadline),"wrap-safe before deadline");
        check(TelnetReadPolicy.expiredAt(nearWrap+10_000_000L,wrapDeadline),"wrap-safe at deadline");
        boolean rejected=false;
        try{TelnetReadPolicy.deadlineAfterAt(start,0);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"zero timeout must be rejected");
        StringBuilder output=new StringBuilder("hello");
        TelnetReadPolicy.appendBounded(output," world");
        check("hello world".contentEquals(output),"ordinary output preserved");
        TelnetReadPolicy.appendBounded(output,"");
        check("hello world".contentEquals(output),"empty chunks ignored");
        StringBuilder full=new StringBuilder(TelnetReadPolicy.MAX_OUTPUT_CHARS);
        for(int i=0;i<TelnetReadPolicy.MAX_OUTPUT_CHARS;i++)full.append('x');
        TelnetReadPolicy.appendBounded(full,"");
        check(full.length()==TelnetReadPolicy.MAX_OUTPUT_CHARS,"exact limit accepted");
        rejected=false;
        try{TelnetReadPolicy.appendBounded(full,"x");}catch(IOException expected){rejected=true;}
        check(rejected && full.length()==TelnetReadPolicy.MAX_OUTPUT_CHARS,"oversized output rejected without mutation");
        System.out.println("Telnet read policy: 12 checks PASSED");
    }
}
