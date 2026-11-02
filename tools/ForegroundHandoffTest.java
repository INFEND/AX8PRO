package ru.xarkon.ax8;

public final class ForegroundHandoffTest {
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception{
        check(!ForegroundHandoff.pending(),"initially idle");
        ForegroundHandoff.begin();
        check(ForegroundHandoff.pending(),"first cleanup blocks service");
        ForegroundHandoff.begin();
        ForegroundHandoff.end();
        check(ForegroundHandoff.pending(),"second cleanup still pending");
        Thread worker=new Thread(ForegroundHandoff::end);
        worker.start();worker.join();
        check(!ForegroundHandoff.pending(),"cross-thread completion visible");
        ForegroundHandoff.end();
        check(!ForegroundHandoff.pending(),"extra completion cannot underflow");
        System.out.println("ForegroundHandoffTest OK");
    }
}
