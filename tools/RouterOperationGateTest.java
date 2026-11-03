package ru.xarkon.ax8;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RouterOperationGateTest {
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception{
        AtomicBoolean entered=new AtomicBoolean(false);
        CountDownLatch waiting=new CountDownLatch(1);
        CountDownLatch completed=new CountDownLatch(1);
        Thread contender=new Thread(()->{
            waiting.countDown();
            try(RouterOperationGate.Lease lease=RouterOperationGate.enter()){
                entered.set(true);
            }finally{completed.countDown();}
        });
        try(RouterOperationGate.Lease outer=RouterOperationGate.enter()){
            try(RouterOperationGate.Lease nested=RouterOperationGate.enter()){
                contender.start();
                check(waiting.await(2,TimeUnit.SECONDS),"contender did not start");
                check(!completed.await(150,TimeUnit.MILLISECONDS),"other thread entered during active lease");
                check(!entered.get(),"lock leaked across threads");
                AtomicBoolean rejected=new AtomicBoolean(false);
                Thread invalidCloser=new Thread(()->{
                    try{nested.close();}
                    catch(IllegalMonitorStateException expected){rejected.set(true);}
                });
                invalidCloser.start();
                invalidCloser.join(2000);
                check(!invalidCloser.isAlive(),"cross-thread close hung");
                check(rejected.get(),"cross-thread close must be rejected");
                check(!completed.await(150,TimeUnit.MILLISECONDS),"cross-thread close released lock");
            }
            check(!completed.await(150,TimeUnit.MILLISECONDS),"nested release unlocked outer lease");
        }
        check(completed.await(2,TimeUnit.SECONDS),"waiting thread did not acquire released gate");
        contender.join(2000);
        check(entered.get(),"contender never entered");
        try{
            try(RouterOperationGate.Lease lease=RouterOperationGate.enter()){
                throw new IllegalStateException("simulated failure");
            }
        }catch(IllegalStateException expected){}
        try(RouterOperationGate.Lease lease=RouterOperationGate.enter()){
            lease.close();
            lease.close();
        }
        System.out.println("RouterOperationGateTest OK");
    }
}
