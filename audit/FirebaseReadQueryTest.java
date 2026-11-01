package com.mlcentral.ventas;

import java.net.URLDecoder;
import java.util.*;

public class FirebaseReadQueryTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static String key(int i) { return String.format("-O00000000000000%04d", i); }
    public static void main(String[] args) throws Exception {
        check(FirebaseReadQuery.suffix(0, key(1)).isEmpty(), "whole state reads unchanged");
        check(FirebaseReadQuery.suffix(40, "").endsWith("limitToLast=40"), "initial baseline");
        check(FirebaseReadQuery.suffix(50, null).endsWith("limitToLast=50"), "recovery preserved");
        check(FirebaseReadQuery.suffix(40, "bad\"&auth=bad").endsWith("limitToLast=40"), "invalid cursor fallback");
        String query = URLDecoder.decode(FirebaseReadQuery.suffix(40, key(0)), "UTF-8");
        check(query.contains("startAt=\""+key(0)+"\""), "inclusive cursor");
        check(query.endsWith("limitToFirst=41"), "cursor plus 40 pending");
        check(!query.contains("limitToLast"), "no latest-tail truncation");
        // Simulated REST server: 125 pending records must be delivered in order.
        List<String> server = new ArrayList<>();
        for (int i=0;i<=125;i++) server.add(key(i));
        String cursor = key(0);
        Set<String> delivered = new LinkedHashSet<>();
        int pages=0;
        while (!cursor.equals(key(125))) {
            String q=FirebaseReadQuery.suffix(40,cursor);
            int limit=Integer.parseInt(q.substring(q.lastIndexOf('=')+1));
            String before=cursor;
            int count=0;
            for (String item:server) {
                if (item.compareTo(before)<0) continue;
                if (++count>limit) break;
                if (item.compareTo(before)>0) { check(delivered.add(item), "no duplicate"); cursor=item; }
            }
            check(++pages<=4, "bounded pagination");
        }
        check(delivered.size()==125, "all events recovered");
        check(pages==4,"four pages");
        check(SnapshotRequestPolicy.shouldRequest(false, 100000, 0, true), "first request");
        check(!SnapshotRequestPolicy.shouldRequest(false, 105000, 100000, true), "no 5s request storm");
        check(!SnapshotRequestPolicy.shouldRequest(false, 129999, 100000, true), "wait for state poll");
        check(SnapshotRequestPolicy.shouldRequest(false, 130000, 100000, true), "stale retry in 30s");
        check(!SnapshotRequestPolicy.shouldRequest(false, 130000, 100000, false), "healthy throttle");
        check(SnapshotRequestPolicy.shouldRequest(false, 400000, 100000, false), "healthy retry in 5min");
        check(SnapshotRequestPolicy.shouldRequest(true, 105000, 100000, true), "manual immediate");
        check(SnapshotRequestPolicy.shouldRequest(false, 90000, 100000, true), "clock rollback recoverable");
        System.out.println("PASS: baseline, recovery, invalid cursor, bounds, 125 events in 4 pages without gaps or duplicates; stale/healthy/manual/clock snapshot policy");
    }
}
