package org.voltdb.xdcrdemos;

import java.io.IOException;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;

import com.google.gson.Gson;

public abstract class AbstractXDCRDemo {

    private static final int USER_SUBTRACT_TX_VALUE = -1;
    private static final int USER_ADD_TX_VALUE = 100;
    private static final int USER_INITIAL_BALANCE = 1000;
    private static final int USER_COUNT = 10;
    private static final int EVENT_COUNT = USER_COUNT * 3;
    private static final int USER_TRANSACTION_HISTORY_SIZE = 8000;
    private static final int SLEEP_MS = 0;
    private static final int RANDOM_REUSE_MS = 0;

    public static void main(String[] args) {

        Random r = new Random(42);

        boolean roundRobin = false;
        boolean randomUser = false;
        boolean randomButOnlyOnceOrder = false;

        Client[] sites = new Client[args.length];
        long skipCount = 0;

        boolean keepGoing = true;
        int passCount = 1;

        try {

            ConflictReader conflictReader = new ConflictReader(args[0], "10.13.1.20");

            connectToVoltSites(args, sites);

            createTables(sites);

            while (keepGoing) {

                Thread conflictReaderThread = new Thread(conflictReader);
                conflictReaderThread.start();

                callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS;");
                callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS_RAW;");
                callAdhocProcedure(sites, 0, "DELETE FROM XDCR_NEEDED_CHANGES;");

                ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

                deleteExistingUsers(sites, coec);

                waitUntilAllSitesHave(sites, 0);

                int[] userTranCount = new int[USER_COUNT];
                long[] userLastTran = new long[USER_COUNT];
                long[] userShortestGapBetweenTrans = new long[USER_COUNT];

                createUsers(sites, userTranCount, userLastTran, userShortestGapBetweenTrans);

                waitUntilAllSitesHave(sites, USER_COUNT);
                Thread.sleep(Duration.ofSeconds(5));

                ArrayList<Integer> randomOrderList = new ArrayList<Integer>();

                for (int i = 0; i < EVENT_COUNT; i++) {
                    randomOrderList.add(i % USER_COUNT);
                }

                //

                msg("Starting conflict run. RoundRobin = " + roundRobin + " random user = " + randomUser
                        + " randomButOnlyOnceOrder=" + randomButOnlyOnceOrder);

                int transactionId = 0;

                for (int i = 0; i < EVENT_COUNT; i++) {

                    if (i % 10000 == 0) {
                        msg("On event " + i + "..");

                    }
                    if (i > 0 && i % 100 == 0) {
                        msg("On event " + i + ", sleep " + SLEEP_MS + " ms");
                        Thread.sleep(SLEEP_MS);

                    }

                    int userId = i % USER_COUNT;

                    if (randomUser) {
                        if (randomButOnlyOnceOrder) {
                            userId = randomOrderList.remove(r.nextInt(randomOrderList.size()));
                        } else {
                            while (true) {
                                userId = r.nextInt(USER_COUNT);
                                if (userLastTran[userId] + RANDOM_REUSE_MS <= System.currentTimeMillis()) {
                                    break;
                                }
                                skipCount++;
                            }

                        }
                    }

                    int site1Id = 0;
                    int site2Id = 0;

                    if (roundRobin) {
                        site1Id = i % sites.length;
                        site2Id = (i + 1) % sites.length;

                    } else {
                        site1Id = 0;
                        site2Id = 1;
                    }

                 //   for (int z = 0; z < 10; z++) {
                        callInc(sites[site1Id], userId, transactionId, USER_SUBTRACT_TX_VALUE, site1Id, userTranCount,
                                userLastTran, userShortestGapBetweenTrans);
                        transactionId++;
                 //   }
                    callInc(sites[site2Id], userId, transactionId, USER_ADD_TX_VALUE, site2Id, userTranCount,
                            userLastTran, userShortestGapBetweenTrans);
                    transactionId++;

                }

                for (int i = 0; i < sites.length; i++) {
                    sites[i].drain();
                }

                msg("Waiting 10 seconds");
                Thread.sleep(Duration.ofSeconds(10));

                // conflictReaderThread.start();
                conflictReader.requestFinish();
                while (conflictReaderThread.isAlive()) {
                    msg("Waiting for reader to finish...");
                    Thread.sleep(Duration.ofSeconds(ConflictReader.POLL_TIME_SECONDS / 3));
                }

                int failcount = 0;

                for (int i = 0; i < USER_COUNT; i++) {

                    long expectedTotal = (USER_INITIAL_BALANCE)
                            + ((USER_ADD_TX_VALUE + USER_SUBTRACT_TX_VALUE) * (userTranCount[i] / 2));

                    long inUsers = getUserTotal(sites[0], i);
                    long bufferedChanges = getChangeTotal(sites[0], i);
                    long acceptedChangeTotal = getAcceptedChangeTotal(sites[0], i);
                    long unacceptedChangeTotal = getUnacceptedChangeTotal(sites[0], i);

                    if (acceptedChangeTotal % 3 != 0) {
                        msg(acceptedChangeTotal + " accepted changes not divisible by 3");
                        failcount++;
                    } else if (unacceptedChangeTotal % 3 != 0) {
                        msg(unacceptedChangeTotal + " unaccepted changes not divisible by 3");
                        failcount++;
                    }

                    if (expectedTotal == (inUsers + bufferedChanges)) {
//                    msg("All changes caught. Expected " + expectedTotal + ", got " + inUsers + "+" + bufferedChanges
//                            + ", = " + (inUsers + bufferedChanges));
                    } else {
                        msg(i + ":Expected " + expectedTotal + " from " + userTranCount[i] + " transactions, got "
                                + inUsers + "+" + bufferedChanges + ", = " + (inUsers + bufferedChanges) + " out by "
                                + ((inUsers + bufferedChanges) - expectedTotal));
                        failcount++;
                        msg(acceptedChangeTotal + " accepted changes reported");
                        msg(unacceptedChangeTotal + " unaccepted changes reported");
                        msg("smallest tran gap is " + userShortestGapBetweenTrans[i] + "ms");

                    }
                }

                msg(failcount + " failures. skips=" + skipCount + ", passcount=" + passCount);
                if (failcount > 0) {
                    keepGoing = false;
                } else {
                    passCount++;
                }
            }

        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

    }

    /**
     * @param sites
     * @param userTranCount
     * @param userLastTran
     * @param userShortestGapBetweenTrans
     * @throws IOException
     * @throws NoConnectionsException
     */
    private static void createUsers(Client[] sites,  int[] userTranCount,
            long[] userLastTran, long[] userShortestGapBetweenTrans) throws IOException, NoConnectionsException {
        ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
        msg("Creating " + USER_COUNT + " users...");
        for (int i = 0; i < USER_COUNT; i++) {
            sites[0].callProcedure(coec, "busy_users.INSERT", i, USER_INITIAL_BALANCE, -1, ";");
            userTranCount[i] = 0;
            userLastTran[i] = 0;
            userShortestGapBetweenTrans[i] = Long.MAX_VALUE;
        }
    }

    /**
     * @param sites
     * @param coec
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    private static void deleteExistingUsers(Client[] sites, ComplainOnErrorCallback coec)
            throws IOException, NoConnectionsException, ProcCallException {
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
    }

    /**
     * @param args
     * @param sites
     * @throws Exception
     */
    private static void connectToVoltSites(String[] args, Client[] sites) throws Exception {
        for (int i = 0; i < sites.length; i++) {
            sites[i] = connectVoltDB(args[i]);
        }
    }

    /**
     * @param sites
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    private static void createTables(Client[] sites) throws IOException, NoConnectionsException, ProcCallException {
        try {

            ClientResponse cr = sites[0].callProcedure("inc_busy_user", -1, -1, "", -1);

        } catch (ProcCallException e) {

            for (int i = 0; i < sites.length; i++) {
                callAdhocProcedure(sites, i, "DROP PROCEDURE inc_busy_user IF EXISTS;");

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
        }
    }

    private static void callInc(Client client, int userId, int transactionId, int delta, int siteId,
            int[] userTranCount, long[] userLastTran, long[] userShortestGapBetweenTrans)
            throws NoConnectionsException, IOException {
        ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
        client.callProcedure(coec, "inc_busy_user", delta, transactionId, transactionId, userId);
        client.callProcedure(coec, "busy_user_txs.INSERT", userId, transactionId, delta, siteId, new Date());
        userTranCount[userId]++;
        long transactionGap = System.currentTimeMillis() - userLastTran[userId];
        userLastTran[userId] = System.currentTimeMillis();

        if (siteId == 0 && transactionGap < userShortestGapBetweenTrans[userId]) {
            userShortestGapBetweenTrans[userId] = transactionGap;
            // msg(""+userShortestGapBetweenTrans[userId]);
        }

    }

    static long getUserTotal(Client c, int userId) throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc",
                "select sum(a_number) how_many from busy_users where userid = " + userId);
        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getChangeTotal(Client c, int userId) throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc",
                "select sum(cast(resolution as bigint)) how_many from XDCR_NEEDED_CHANGES WHERE ACCEPTED_1_OR_0 = 0 AND OBE IS NULL and rowpk = '"
                        + userId + "';");
        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getAcceptedChangeTotal(Client c, int userId)
            throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc",
                "select count(*) how_many  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 1 and rowpk = '" + userId
                        + "';");
        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getUnacceptedChangeTotal(Client c, int userId)
            throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc",
                "select count(*) how_many  FROM XDCR_CONFLICTS_RAW where WASACCEPTED = 0 and rowpk = '" + userId
                        + "';");
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
                msg("Connect to " + hostnameArray[i] + "...");
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
