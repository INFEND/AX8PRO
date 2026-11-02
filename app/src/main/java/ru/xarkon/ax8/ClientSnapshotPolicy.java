package ru.xarkon.ax8;

/** Fail closed on malformed client snapshots instead of reporting zero clients. */
public final class ClientSnapshotPolicy {
    private ClientSnapshotPolicy(){}

    /** A genuine empty array is valid. Every nonempty row must be either a
     * recognizable connected client or an explicitly offline entry.
     * Unparseable rows must never become a successful empty snapshot.
     */
    public static boolean accept(int totalRows,int validRows){
        return totalRows>=0 && validRows>=0 && validRows==totalRows;
    }
}
