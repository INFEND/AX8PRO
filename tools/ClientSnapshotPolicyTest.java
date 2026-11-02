import ru.xarkon.ax8.ClientSnapshotPolicy;

public final class ClientSnapshotPolicyTest {
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args){
        check(ClientSnapshotPolicy.accept(0,0),"empty array is a valid empty client list");
        check(ClientSnapshotPolicy.accept(1,1),"one valid client is accepted");
        check(ClientSnapshotPolicy.accept(4,4),"online and offline rows are accepted");
        check(!ClientSnapshotPolicy.accept(1,0),"unknown client row cannot erase snapshot");
        check(!ClientSnapshotPolicy.accept(4,3),"partially decoded list cannot erase clients");
        check(!ClientSnapshotPolicy.accept(2,3),"invalid count must be rejected");
        check(!ClientSnapshotPolicy.accept(-1,0),"negative total is invalid");
        check(!ClientSnapshotPolicy.accept(0,-1),"negative valid count is invalid");
        System.out.println("Client snapshot integrity: 8 checks PASSED");
    }
}
