package org.voltdb.xdcrdatagen;

import java.io.IOException;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2024 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.Random;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.xdcrutil.ComplainOnErrorCallback;

import com.google.gson.Gson;

public class ConflictGenerator {

    private static final int USER_SUBTRACT_TX_VALUE = -1;
    private static final int USER_ADD_TX_VALUE = 100;
    private static final int USER_INITIAL_BALANCE = 1000;
    private static final int USER_TRANSACTION_HISTORY_SIZE = 8000;
    private static final int RANDOM_REUSE_MS = 0;
    private static final int STATS_SLEEP_MS = 6000;

    public static void main(String[] args) {

        msg("Parameters:" + Arrays.toString(args));

        final int sleepMs = Integer.parseInt(args[0]);
        final int userCount = Integer.parseInt(args[1]);
        final int eventCount = userCount * Integer.parseInt(args[2]);

        Random r = new Random(42);

        boolean roundRobin = false;

        if (args[3].equals("1")) {
            roundRobin = true;
        }

        boolean randomUser = false;

        if (args[4].equals("1")) {
            randomUser = true;
        }

        boolean randomButOnlyOnceOrder = false;

        Client[] sites = new Client[args.length - 6];
        long[] siteConflicts = new long[args.length - 6];
        long[] siteExports = new long[args.length - 6];
        long skipCount = 0;

        int passCount = 1;

        try {

            ConflictReader conflictReader = new ConflictReader(args[6], args[5]);

            for (int i = 6; i < args.length; i++) {
                sites[i - 6] = connectVoltDB(args[i]);
                siteConflicts[i - 6] = 0;
                siteExports[i - 6] = 0;
            }

            try {

                sites[0].callProcedure("inc_busy_user", -1, -1, "", -1);

            } catch (ProcCallException e) {

                for (int i = 0; i < sites.length; i++) {
                    callAdhocProcedure(sites, i, "DROP PROCEDURE inc_busy_user IF EXISTS;");

                    callAdhocProcedure(sites, i, "DROP PROCEDURE get_unaccepted_change_total IF EXISTS;");

                    callAdhocProcedure(sites, i, "DROP PROCEDURE get_rejected_change_total IF EXISTS;");

                    callAdhocProcedure(sites, i, "DROP PROCEDURE get_accepted_change_total IF EXISTS;");

                    callAdhocProcedure(sites, i, "DROP TABLE busy_users IF EXISTS;");
                    callAdhocProcedure(sites, i,
                            "CREATE TABLE busy_users (userid bigint not null primary key" + ", a_number bigint not null"
                                    + ", last_tx bigint, hist varchar(" + USER_TRANSACTION_HISTORY_SIZE + "));");
                    callAdhocProcedure(sites, i, "PARTITION TABLE busy_users ON COLUMN userid;");
                    callAdhocProcedure(sites, i, "DR TABLE busy_users;");
                    callAdhocProcedure(sites, i, "CREATE INDEX uu_idx1 ON busy_users(a_number);");

                    callAdhocProcedure(sites, i, "DROP TABLE busy_user_txs IF EXISTS;");
                    callAdhocProcedure(sites, i, "CREATE TABLE busy_user_txs (userid bigint not null "
                            + ", last_tx bigint, last_delta bigint, siteid bigint, insert_date timestamp , primary key (userid,last_tx));");
                    callAdhocProcedure(sites, i, "PARTITION TABLE busy_user_txs ON COLUMN userid;");
                    callAdhocProcedure(sites, i, "DR TABLE busy_user_txs;");

                    callAdhocProcedure(sites, i,
                            "CREATE PROCEDURE inc_busy_user PARTITION ON TABLE busy_users "
                                    + "COLUMN userid PARAMETER 3  AS UPDATE busy_users SET a_number = a_number + ?"
                                    + ", last_tx = ?, hist = substring(hist||?||';',1," + USER_TRANSACTION_HISTORY_SIZE
                                    + ") WHERE userid = CAST(? AS BIGINT);");

                }

                callAdhocProcedure(sites, 0, "CREATE PROCEDURE get_rejected_change_total  "
                        // + "PARTITION ON TABLE busy_users COLUMN userID "
                        + "AS " + "select sum(cast(resolution as bigint)) rejected " + "from XDCR_NEEDED_CHANGES "
                        + "WHERE ACCEPTED_1_OR_0 = 0 "
                        + "AND OBE IS NULL AND OBSERVINGCLUSTERID IN (WINNINGCLUSTERID, LOSINGCLUSTERID) "
                        + "and rowpk = ?;");

                callAdhocProcedure(sites, 0, "CREATE PROCEDURE get_accepted_change_total "
                        // + "PARTITION ON TABLE busy_users COLUMN userID "
                        + "AS "
                        + "select count(*) accepted  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 1 and rowpk = ?;");

                callAdhocProcedure(sites, 0, "CREATE PROCEDURE get_unaccepted_change_total "
                        // + "PARTITION ON TABLE busy_users COLUMN userID "
                        + "AS "
                        + "select count(*) how_many  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 0 and rowpk = ?;");

            }

            msg("PARAMS = SLEEP_MS=" + sleepMs + " USER_COUNT=" + userCount);

            Thread conflictReaderThread = new Thread(conflictReader);
            conflictReaderThread.start();

            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS;");
            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS_RAW;");
            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_NEEDED_CHANGES;");

            ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

            ClientResponse cr = sites[0].callProcedure("@AdHoc",
                    "SELECT min(userid) min_userid, max(userid) max_userid FROM busy_users;");

            cr.getResults()[0].advanceRow();

            long minuserid = cr.getResults()[0].getLong("min_userid");
            long maxuserid = cr.getResults()[0].getLong("max_userid");
            if (cr.getResults()[0].wasNull()) {
                minuserid = 0;
                maxuserid = 0;
            }

            msg("Deleting users from " + minuserid + " to " + maxuserid);
            for (long i = minuserid; i <= maxuserid; i++) {
                sites[0].callProcedure(coec, "busy_users.DELETE", i);
                sites[0].callProcedure(coec, "@AdHoc", "DELETE FROM busy_user_txs WHERE userid = " + i + ";");
            }

            waitUntilAllSitesHave(sites, 0);

            int[] userTranCount = new int[userCount];
            long[] userLastTranTimeMS = new long[userCount];
            long[] userShortestGapBetweenTrans = new long[userCount];

            msg("Creating " + userCount + " users...");
            for (int i = 0; i < userCount; i++) {
                sites[0].callProcedure(coec, "busy_users.INSERT", i, USER_INITIAL_BALANCE, -1, ";");
                userTranCount[i] = 0;
                userLastTranTimeMS[i] = 0;
                userShortestGapBetweenTrans[i] = Long.MAX_VALUE;
            }

            waitUntilAllSitesHave(sites, userCount);
            Thread.sleep(Duration.ofSeconds(5));

            waitUntilZeroPending(sites);

            long[] startingExports = getExportStats(sites);

            if (startingExports == null) {
                System.exit(5);
            }

            ArrayList<Integer> randomOrderList = new ArrayList<Integer>();

            for (int i = 0; i < eventCount; i++) {
                randomOrderList.add(i % userCount);
            }

            //

            siteConflicts = getConflictStatsBalance(sites);

            msg("Starting conflict run. RoundRobin = " + roundRobin + " random user = " + randomUser
                    + " randomButOnlyOnceOrder=" + randomButOnlyOnceOrder + " RANDOM_REUSE_MS=" + RANDOM_REUSE_MS);

            int transactionId = 0;

            for (int i = 0; i < eventCount; i++) {

                if (i % 10000 == 0) {
                    msg("On event " + i + "..");

                }
                if (i % userCount == 0) {
                    msg("On event " + i + ", sleep " + sleepMs + " ms");
                    Thread.sleep(sleepMs);

                }

                int userId = i % userCount;

                if (randomUser) {
                    if (randomButOnlyOnceOrder) {
                        userId = randomOrderList.remove(r.nextInt(randomOrderList.size()));
                    } else {
                        while (true) {
                            userId = r.nextInt(userCount);
                            if (userLastTranTimeMS[userId] + RANDOM_REUSE_MS <= System.currentTimeMillis()) {
                                break;
                            }
                            skipCount++;
                            Thread.sleep(1);
                        }

                    }
                }

                int site1Id = 0;
                int site2Id = 1;

                if (roundRobin) {
                    site1Id = i % sites.length;
                    site2Id = (i + 1) % sites.length;

                } else {
                    site1Id = 0;
                    site2Id = 1;
                }

                callInc(sites[site1Id], userId, transactionId, USER_SUBTRACT_TX_VALUE, site1Id, userTranCount,
                        userLastTranTimeMS, userShortestGapBetweenTrans);
                transactionId++;

                callInc(sites[site2Id], userId, transactionId, USER_ADD_TX_VALUE, site2Id, userTranCount,
                        userLastTranTimeMS, userShortestGapBetweenTrans);
                transactionId++;

            }

            for (int i = 0; i < sites.length; i++) {
                sites[i].drain();
            }

            msg("Waiting 10 seconds");
            Thread.sleep(Duration.ofSeconds(10));

            waitUntilZeroPending(sites);

            conflictReader.requestFinish();
            while (conflictReaderThread.isAlive()) {
                msg("Waiting for conflict reader to finish...");
                Thread.sleep(Duration.ofSeconds(ConflictReader.POLL_TIME_SECONDS / 3));
            }

            long[] endingExports = getExportStats(sites);
            long[] exportDeltas = new long[endingExports.length];

            for (int i = 0; i < endingExports.length; i++) {
                exportDeltas[i] = endingExports[i] - startingExports[i];
            }

            int failcount = 0;

            // Test 1 - everyone should have exported same number of rows...

            if (!longsTheSame(exportDeltas)) {
                msg("ERROR:GREPME: Uneven values for export - " + Arrays.toString(exportDeltas));
                failcount++;
            }

            for (int i = 0; i < userCount; i++) {

                if (i % 1000 == 1) {
                    msg("Checking user " + i);
                }

                long expectedTotal = (USER_INITIAL_BALANCE)
                        + ((USER_ADD_TX_VALUE + USER_SUBTRACT_TX_VALUE) * (userTranCount[i] / 2));

                long inUsers = getUserTotal(sites[0], i);
                long bufferedChanges = getRejectedChangeTotal(sites[0], i);

                if (expectedTotal == (inUsers + bufferedChanges)) {
                    // msg("All changes caught. Expected " + expectedTotal + ", got " + inUsers +
                    // "+" + bufferedChanges
                    // + ", = " + (inUsers + bufferedChanges));
                } else {

                    long acceptedChangeTotal = getAcceptedChangeTotal(sites[0], i);
                    long unacceptedChangeTotal = getUnacceptedChangeTotal(sites[0], i);

                    if (acceptedChangeTotal % 3 != 0) {
                        msg(i + "ERROR:GREPME:" + acceptedChangeTotal + " accepted changes not divisible by 3");
                        failcount++;
                    } else if (unacceptedChangeTotal % 3 != 0) {
                        msg(i + "ERROR:GREPME:" + ":" + unacceptedChangeTotal
                                + " unaccepted changes not divisible by 3");
                        failcount++;
                    }

                    msg("ERROR:GREPME:" + i + ":Expected " + expectedTotal + " from " + userTranCount[i]
                            + " transactions, got " + inUsers + "+" + bufferedChanges + ", = "
                            + (inUsers + bufferedChanges) + " out by " + ((inUsers + bufferedChanges) - expectedTotal));
                    failcount++;
                    msg("ERROR:GREPME:" + i + ":" + acceptedChangeTotal + " accepted changes reported");
                    msg("ERROR:GREPME:" + i + ":" + unacceptedChangeTotal + " unaccepted changes reported");
                    msg("ERROR:GREPME:" + "smallest tran gap is " + userShortestGapBetweenTrans[i] + "ms");

                }
            }

            msg(failcount + " failures. skips=" + skipCount + ", passcount=" + passCount);
            msg("tranGaps = " + getMin(userShortestGapBetweenTrans) + "," + Arrays.toString(userShortestGapBetweenTrans)
                    + " SLEEP_MS=" + sleepMs);

            long conflictCount = getRawChangeCount(sites[0]);
            msg("Observed " + conflictCount + " raw conflicts");
            conflictCount /= 3;
            msg("Which means " + conflictCount + " real conflicts");
            msg("Over  " + sites.length + " sites...");
            conflictCount /= sites.length;
            msg("so  " + conflictCount + " conflicts...");

            msg("Checking to see if @Statistics is correct...");
            long[] statsReportedConflicts = checkConflictStatsBalance(sites, siteConflicts);

            if (exportDeltas[0] / 3 != statsReportedConflicts[0]) {
                msg("ERROR:GREPME: Exports not 3x conflicts");
                failcount++;
            }

            if (longsTheSame(statsReportedConflicts)) {
                msg("Statistics agree on how many conflicts happened - " + statsReportedConflicts[0]);
                if (conflictCount != statsReportedConflicts[0]) {
                    msg("ERROR:GREPME: observed conflict count disagrees with stats - " + conflictCount + ", "
                            + statsReportedConflicts[0] + ", failCount=" + failcount + ", mintrangap="
                            + getMin(userShortestGapBetweenTrans) + ", args=" + Arrays.toString(args));
                }
            } else {
                msg("ERROR:GREPME: Statistics Do not agree on how many conflicts happened - " + conflictCount
                        + ", STATS=" + Arrays.toString(statsReportedConflicts) + ", failCount=" + failcount
                        + ", mintrangap=" + getMin(userShortestGapBetweenTrans) + ", args=" + Arrays.toString(args));
            }

        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

    }

    private static long getMin(long[] arrayOfLongs) {
        if (arrayOfLongs == null) {
            return Long.MIN_VALUE;
        }

        long minValue = Long.MAX_VALUE;

        for (int i = 0; i < arrayOfLongs.length; i++) {
            if (arrayOfLongs[0] < minValue) {
                minValue = arrayOfLongs[0];
            }
        }

        return minValue;
    }

    private static boolean longsTheSame(long[] arrayOfLongs) {
        if (arrayOfLongs == null || arrayOfLongs.length <= 1) {
            return true;
        }

        for (int i = 1; i < arrayOfLongs.length; i++) {
            if (arrayOfLongs[0] != arrayOfLongs[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param sites
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    private static long[] checkConflictStatsBalance(Client[] sites, long[] priorConflictCount)
            throws IOException, NoConnectionsException, ProcCallException {

        msg("Sleeping " + STATS_SLEEP_MS + "ms so stats are clean... ");

        try {
            Thread.sleep(STATS_SLEEP_MS);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // HashMap<Long, Long> conflictsByPartition = new HashMap<Long, Long>();

        long[] conflictCount = new long[priorConflictCount.length];

        for (int i = 0; i < sites.length; i++) {
            conflictCount[i] = 0;
            ClientResponse drsttats = sites[i].callProcedure("@Statistics", "DRCONFLICTS", 0);

            while (drsttats.getResults()[0].advanceRow()) {
                long thisConflictCount = drsttats.getResults()[0].getLong("TOTAL_CONFLICT_COUNT");
                // long thisPartitionId = drsttats.getResults()[0].getLong("PARTITION_ID");

                conflictCount[i] += thisConflictCount;

                if (sites.length == 2) {
//                    Long aCount = conflictsByPartition.get(thisPartitionId);
//
//                    if (aCount == null) {
//                        conflictsByPartition.put(thisPartitionId, thisConflictCount);
//                    } else if (aCount != thisConflictCount) {
//                        msg("Error: Values differ for partition " + thisPartitionId + " : stored " + aCount + " != new "
//                                + thisConflictCount);
//
//                    }

                }

            }

            msg("Site " + sites[i].getConnectedHostList().toString() + " @Statistics DRCONFLICTS 0:");

            msg(drsttats.getResults()[0].toFormattedString());
        }

        for (int i = 0; i < sites.length; i++) {
            msg("Site " + i + " saw (total/delta) " + conflictCount[i] + "/" + (conflictCount[i] - priorConflictCount[i])
                    + " conflicts");

            conflictCount[i] -= priorConflictCount[i];

            if (conflictCount[i] != conflictCount[0]) {
                msg("Error: Calculated Relative count " + conflictCount[i] + " disagrees with "
                        + conflictCount[0]);
            }
        }

        return conflictCount;
    }

    private static long[] getExportStats(Client[] sites) throws IOException, NoConnectionsException, ProcCallException {

        long[] exportCount = new long[sites.length];

        for (int i = 0; i < sites.length; i++) {
            exportCount[i] = 0;
            ClientResponse drsttats = sites[i].callProcedure("@Statistics", "EXPORT", 0);

            while (drsttats.getResults()[0].advanceRow()) {
                String source = drsttats.getResults()[0].getString("SOURCE");
                if (source.equals("VOLTDB_AUTOGEN_XDCR_CONFLICTS_PARTITIONED")) {
                    long thisExportCount = drsttats.getResults()[0].getLong("TUPLE_COUNT");
                    long thisExportPending = drsttats.getResults()[0].getLong("TUPLE_PENDING");
                    long thisQueueGap = drsttats.getResults()[0].getLong("QUEUE_GAP");
                    String status = drsttats.getResults()[0].getString("STATUS");

                    if (status.equals("ACTIVE") && thisExportPending == 0 && thisQueueGap == 0) {
                        exportCount[i] += thisExportCount;
                    } else {
                        msg("GREPME export is broken:" + drsttats.getResults()[0].toFormattedString());
                        return null;
                    }

                }
            }
        }

        return exportCount;

    }

    private static void waitUntilZeroPending(Client[] sites)
            throws IOException, NoConnectionsException, ProcCallException {

        long pendingTuples = Long.MAX_VALUE;

        while (pendingTuples > 0) {

            pendingTuples = 0;

            for (int i = 0; i < sites.length; i++) {
                ClientResponse drsttats = sites[i].callProcedure("@Statistics", "EXPORT", 0);

                while (drsttats.getResults()[0].advanceRow()) {
                    String source = drsttats.getResults()[0].getString("SOURCE");
                    if (source.equals("VOLTDB_AUTOGEN_XDCR_CONFLICTS_PARTITIONED")) {
                        pendingTuples += drsttats.getResults()[0].getLong("TUPLE_PENDING");

                    }
                }

                if (pendingTuples > 0) {
                    msg(pendingTuples + " tuples pending");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }

        }

        return;

    }

    /**
     * @param sites
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    private static long[] getConflictStatsBalance(Client[] sites)
            throws IOException, NoConnectionsException, ProcCallException {

        msg("Sleeping " + STATS_SLEEP_MS + "ms so stats are clean... ");

        try {
            Thread.sleep(STATS_SLEEP_MS);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        long[] conflictCount = new long[sites.length];

        for (int i = 0; i < sites.length; i++) {
            conflictCount[i] = 0;
            ClientResponse drsttats = sites[i].callProcedure("@Statistics", "DRCONFLICTS", 0);

            while (drsttats.getResults()[0].advanceRow()) {
                long thisConflictCount = drsttats.getResults()[0].getLong("TOTAL_CONFLICT_COUNT");

                conflictCount[i] += thisConflictCount;
            }
        }

        msg("Saw  " + Arrays.toString(conflictCount) + " preexisting conflicts... ");
        return conflictCount;
    }

    private static void callInc(Client client, int userId, int transactionId, int delta, int siteId,
            int[] userTranCount, long[] userLastTran, long[] userShortestGapBetweenTrans)
            throws NoConnectionsException, IOException {
        ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
        client.callProcedure(coec, "inc_busy_user", delta, transactionId, transactionId, userId);
        client.callProcedure(coec, "busy_user_txs.INSERT", userId, transactionId, delta, siteId, new Date());
        userTranCount[userId]++;
        if (siteId == 0) {
            long transactionGap = System.currentTimeMillis() - userLastTran[userId];
            userLastTran[userId] = System.currentTimeMillis();

            if (transactionGap < userShortestGapBetweenTrans[userId]) {
                userShortestGapBetweenTrans[userId] = transactionGap;
                // msg(""+userShortestGapBetweenTrans[userId]);
            }
        }

    }

    static long getUserTotal(Client c, int userId) throws NoConnectionsException, IOException, ProcCallException {

//        ClientResponse cr = c.callProcedure("@AdHoc",
//                "select sum(a_number) how_many from busy_users where userid = " + userId);
        ClientResponse cr = c.callProcedure("busy_users.SELECT", userId);
        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("a_number");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getRejectedChangeTotal(Client c, int userId)
            throws NoConnectionsException, IOException, ProcCallException {

//        ClientResponse cr = c.callProcedure("@AdHoc",
//                "select sum(cast(resolution as bigint)) how_many "
//                + "from XDCR_NEEDED_CHANGES "
//                + "WHERE ACCEPTED_1_OR_0 = 0 "
//                + "AND OBE IS NULL AND OBSERVINGCLUSTERID IN (WINNINGCLUSTERID, LOSINGCLUSTERID) "
//                + "and rowpk = '"
//                        + userId + "';");
        ClientResponse cr = c.callProcedure("get_rejected_change_total", userId + "");

        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("REJECTED");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getRawChangeCount(Client c) throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc", "select count(*) how_many " + "from XDCR_CONFLICTS_RAW;");

        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getAcceptedChangeTotal(Client c, int userId)
            throws NoConnectionsException, IOException, ProcCallException {

//        ClientResponse cr = c.callProcedure("@AdHoc",
//                "select count(*) how_many  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 1 and rowpk = '" + userId
//                        + "';");
        ClientResponse cr = c.callProcedure("get_accepted_change_total", userId + "");

        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("ACCEPTED");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getUnacceptedChangeTotal(Client c, int userId)
            throws NoConnectionsException, IOException, ProcCallException {

//        ClientResponse cr = c.callProcedure("@AdHoc",
//                "select count(*) how_many  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 0 and rowpk = '" + userId
//                        + "';");
        ClientResponse cr = c.callProcedure("get_unaccepted_change_total", userId + "");

        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    //

    /**
     * @param sites
     * @param i
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    private static void callAdhocProcedure(Client[] sites, int i, String sql)
            throws IOException, NoConnectionsException, ProcCallException {
        msg("Site " + i + ":" + sql);
        sites[i].callProcedure("@AdHoc", sql);

    }

    private static void waitUntilAllSitesHave(Client[] sites, int userCount)
            throws NoConnectionsException, IOException, ProcCallException, InterruptedException {

        while (true) {

            boolean cleanPass = true;

            for (int i = 0; i < sites.length; i++) {

                ClientResponse cr = sites[i].callProcedure("@AdHoc", "SELECT COUNT(*) HOW_MANY FROM busy_users;");
                cr.getResults()[0].advanceRow();

                long siteCount = cr.getResults()[0].getLong("HOW_MANY");

                if (siteCount != userCount) {
                    msg("Site " + sites[i].getConnectedHostList().toString() + " has " + siteCount + " rows. Expecting "
                            + userCount);
                    cleanPass = false;
                } else {
                    msg("Site " + sites[i].getConnectedHostList().toString() + " has expected number of " + userCount
                            + " rows");
                }

            }

            if (cleanPass) {
                break;
            }
            msg("Waiting 1 second..");
            Thread.sleep(Duration.ofMillis(1000));

        }

    }

    /**
     * Print a formatted message.
     * 
     * @param message
     */
    public static void msg(String message) {

        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        String strDate = sdfDate.format(now);
        System.out.println(strDate + ":Generator :" + message);

    }

    /**
     * Connect to VoltDB using a comma delimited hostname list.
     * 
     * @param commaDelimitedHostnames
     * @return
     * @throws Exception
     */
    private static Client connectVoltDB(String commaDelimitedHostnames) throws Exception {
        Client client = null;
        ClientConfig config = null;

        try {
            msg("Logging into VoltDB");

            config = new ClientConfig(); // "admin", "idontknow");
            config.setTopologyChangeAware(true);

            client = ClientFactory.createClient(config);

            String[] hostnameArray = commaDelimitedHostnames.split(",");

            for (int i = 0; i < hostnameArray.length; i++) {
                msg("Connect to '" + hostnameArray[i] + "'...");
                try {
                    client.createConnection(hostnameArray[i]);
                } catch (Exception e) {
                    msg(e.getMessage());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("VoltDB connection failed.." + e.getMessage(), e);
        }

        return client;

    }

}
