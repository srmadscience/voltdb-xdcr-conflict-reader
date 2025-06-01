package org.voltdb.xdcrutil;

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

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.xdcrdatagen.ConflictReader;

import com.google.gson.Gson;

public class ConflictMatchingIssue {

    private static final String KAFKA_HOST = "10.13.1.20";
    private static final int USER_SUBTRACT_TX_VALUE = -1;
    private static final int USER_ADD_TX_VALUE = 100;
    private static final int USER_INITIAL_BALANCE = 1000;
    private static final int USER_COUNT = 100;
    private static final int EVENT_COUNT = USER_COUNT * 2;
    private static final int USER_TRANSACTION_HISTORY_SIZE = 2000;
    private static final int SLEEP_MS = 1000;
    private static final int RANDOM_REUSE_MS = 5000;

    public static void main(String[] args) {
        // TODO Auto-generated method stub
        Client[] sites = new Client[args.length];
        long skipCount = 0;

        try {

            ConflictReader conflictReader = new ConflictReader(args[0], KAFKA_HOST);
            Thread conflictReaderThread = new Thread(conflictReader);
            conflictReaderThread.start();

            for (int i = 0; i < sites.length; i++) {
                sites[i] = connectVoltDB(args[i]);
            }

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

            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS;");
            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS2;");
            callAdhocProcedure(sites, 0, "DELETE FROM XDCR_NEEDED_CHANGES;");

            ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

            msg("Deleting " + USER_COUNT + " users...");
            for (int i = 0; i < USER_COUNT; i++) {
                sites[0].callProcedure(coec, "busy_users.DELETE", i);
                sites[0].callProcedure("@AdHoc", "DELETE FROM busy_user_txs WHERE userid = " + i + ";");
            }

            sites[0].drain();

            waitUntilAllSitesHave(sites, 0);

            int[] userTranCount = new int[USER_COUNT];
            long[] userLastTran = new long[USER_COUNT];

            msg("Creating " + USER_COUNT + " users...");
            for (int i = 0; i < USER_COUNT; i++) {
                sites[0].callProcedure(coec, "busy_users.INSERT", i, USER_INITIAL_BALANCE, -1, ";");
                userTranCount[i] = 0;
                userLastTran[i] = 0;
            }

            waitUntilAllSitesHave(sites, USER_COUNT);
            Thread.sleep(Duration.ofSeconds(5));

            int transactionId = 0;
            int siteId = 0;

            int FIRST_USER = 0;
            int SECOND_USER = 1;

            msg("Simple Conflict Start");
            callInc(sites[siteId], FIRST_USER, transactionId++, USER_SUBTRACT_TX_VALUE, siteId, userTranCount,
                    userLastTran);
            siteId = 1;
            callInc(sites[siteId], FIRST_USER, transactionId++, USER_ADD_TX_VALUE, siteId, userTranCount, userLastTran);
            msg("Simple Conflict End");

            /**
             * 
             * Got:
             * 
             * "3619631938174978","1730727863688000","101","2","8589934592","1","EXT","U","MSMT","0","A","21","1730727862432000","C","BUSY_USERS","21","1730727863688000","{""A_NUMBER"":""999"",""HIST"":"";0;"",""LAST_TX"":""0"",""USERID"":""0""}"
             * "3619631938174978","1730727863688000","102","2","8589934592","1","EXP","U","MSMT","0","A","21","1730727855332000","C","BUSY_USERS","21","1730727863688000","{""A_NUMBER"":""1000"",""HIST"":"";"",""LAST_TX"":""-1"",""USERID"":""0""}"
             * "3619631938174978","1730727863688000","103","2","8589934592","1","NEW","U","NONE","0","A","22","1730727862454000","C","BUSY_USERS","21","1730727863688000","{""A_NUMBER"":""1100"",""HIST"":"";1;"",""LAST_TX"":""1"",""USERID"":""0""}"
             * "3619631926460418","1730727863887000","101","2","8589934592","1","EXT","U","MSMT","0","R","22","1730727862454000","C","BUSY_USERS","22","1730727863887000","{""A_NUMBER"":""1100"",""HIST"":"";1;"",""LAST_TX"":""1"",""USERID"":""0""}"
             * "3619631926460418","1730727863887000","102","2","8589934592","1","EXP","U","MSMT","0","R","21","1730727855332000","C","BUSY_USERS","22","1730727863887000","{""A_NUMBER"":""1000"",""HIST"":"";"",""LAST_TX"":""-1"",""USERID"":""0""}"
             * "3619631926460418","1730727863887000","103","2","8589934592","1","NEW","U","NONE","0","R","21","1730727862432000","C","BUSY_USERS","22","1730727863887000","{""A_NUMBER"":""999"",""HIST"":"";0;"",""LAST_TX"":""0"",""USERID"":""0""}"
             * 
             * 
             */

            Thread.sleep(Duration.ofSeconds(5));

            msg("More Complex Conflict Start");
            for (int i = 0; i < 10; i++) {

                siteId = 0;
                callInc(sites[siteId], FIRST_USER, transactionId++, USER_SUBTRACT_TX_VALUE, siteId, userTranCount,
                        userLastTran);
                siteId = 1;
                callInc(sites[siteId], FIRST_USER, transactionId++, USER_ADD_TX_VALUE, siteId, userTranCount,
                        userLastTran);
            }
            msg("More Conflict End");

            siteId = 0;
            msg("Chatty Conflict Start");
            for (int i = 0; i < 5; i++) {
                callInc(sites[siteId], SECOND_USER, transactionId++, USER_SUBTRACT_TX_VALUE, siteId, userTranCount,
                        userLastTran);
                for (int j = 10; j < 40; j++) {
                    callInc(sites[siteId], j, transactionId++, USER_SUBTRACT_TX_VALUE, siteId, userTranCount,
                            userLastTran);

                }

            }
            siteId = 1;
            callInc(sites[siteId], SECOND_USER, transactionId++, USER_ADD_TX_VALUE, siteId, userTranCount,
                    userLastTran);
            msg("Chatty Conflict End");
            for (int j = 41; j < USER_COUNT; j++) {
                callInc(sites[siteId], j, transactionId++, USER_ADD_TX_VALUE, siteId, userTranCount, userLastTran);

            }

            /**
             * Got:
             */

            /**
             * 
             * Random r = new Random(42);
             * 
             * boolean roundRobin = false; boolean randomUser = false; boolean
             * randomButOnlyOnceOrder = false;
             * 
             * ArrayList<Integer> randomOrderList = new ArrayList<Integer>();
             * 
             * for (int i = 0; i < EVENT_COUNT; i++) { randomOrderList.add(i % USER_COUNT);
             * }
             * 
             * //
             * 
             * msg("Starting conflict run. RoundRobin = " + roundRobin + " random user = " +
             * randomUser + " randomButOnlyOnceOrder=" + randomButOnlyOnceOrder);
             * 
             * int transactionId = 0;
             * 
             * for (int i = 0; i < EVENT_COUNT; i++) {
             * 
             * if (i % 10000 == 0) { msg("On event " + i + "..");
             * 
             * } if (i > 0 && i % 100 == 0) { msg("On event " + i + ", sleep " + SLEEP_MS +
             * " ms"); Thread.sleep(SLEEP_MS);
             * 
             * }
             * 
             * int userId = i % USER_COUNT;
             * 
             * if (randomUser) { if (randomButOnlyOnceOrder) { userId =
             * randomOrderList.remove(r.nextInt(randomOrderList.size())); } else {
             * while(true) { userId = r.nextInt(USER_COUNT); if ( userLastTran[userId] +
             * RANDOM_REUSE_MS < System.currentTimeMillis()) { break; } skipCount++; }
             * 
             * } }
             * 
             * if (roundRobin) { int siteId = i % sites.length; callInc(sites[siteId],
             * userId, transactionId, USER_SUBTRACT_TX_VALUE, siteId,
             * userTranCount,userLastTran); transactionId++;
             * 
             * siteId = (i + 1) % sites.length; callInc(sites[siteId], userId,
             * transactionId, USER_ADD_TX_VALUE, siteId, userTranCount,userLastTran);
             * transactionId++;
             * 
             * // sites[i % sites.length].callProcedure(coec, "inc_busy_user",
             * USER_SUBTRACT_TX_VALUE, transactionId, // transactionId++, userId); //
             * sites[(i + 1) % sites.length].callProcedure(coec, "inc_busy_user",
             * USER_ADD_TX_VALUE, transactionId, // transactionId++, userId); } else { int
             * siteId = 0; callInc(sites[siteId], userId, transactionId,
             * USER_SUBTRACT_TX_VALUE, siteId, userTranCount,userLastTran); transactionId++;
             * 
             * siteId = 1; callInc(sites[siteId], userId, transactionId, USER_ADD_TX_VALUE,
             * siteId, userTranCount,userLastTran); transactionId++; // //
             * sites[0].callProcedure(coec, "inc_busy_user", USER_SUBTRACT_TX_VALUE,
             * transactionId, // transactionId++, userId); // sites[1].callProcedure(coec,
             * "inc_busy_user", USER_ADD_TX_VALUE, transactionId, transactionId++, //
             * userId);
             * 
             * } }
             * 
             * for (int i = 0; i < sites.length; i++) { sites[i].drain(); }
             * 
             * msg("Waiting 10 seconds"); Thread.sleep(Duration.ofSeconds(10));
             * 
             * // conflictReaderThread.start(); conflictReader.requestFinish(); while
             * (conflictReaderThread.isAlive()) { msg("Waiting for reader to finish...");
             * Thread.sleep(Duration.ofSeconds(ConflictReader.POLL_TIME_SECONDS / 3)); }
             * 
             * int failcount = 0;
             * 
             * for (int i = 0; i < USER_COUNT; i++) {
             * 
             * long expectedTotal = (USER_INITIAL_BALANCE) + ((USER_ADD_TX_VALUE +
             * USER_SUBTRACT_TX_VALUE) * (userTranCount[i] / 2));
             * 
             * long inUsers = getUserTotal(sites[0], i); long bufferedChanges =
             * getChangeTotal(sites[0], i);
             * 
             * if (expectedTotal == (inUsers + bufferedChanges)) { // msg("All changes
             * caught. Expected " + expectedTotal + ", got " + inUsers + "+" +
             * bufferedChanges // + ", = " + (inUsers + bufferedChanges)); } else { msg(i +
             * ":Expected " + expectedTotal + " from " + userTranCount[i] + " transactions,
             * got " + inUsers + "+" + bufferedChanges + ", = " + (inUsers +
             * bufferedChanges) + " out by " + ((inUsers + bufferedChanges) -
             * expectedTotal)); failcount++; } }
             * 
             * msg(failcount + " failures. skips="+skipCount);
             * 
             * 
             **/

        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

    }

    private static void callInc(Client client, int userId, int transactionId, int delta, int siteId,
            int[] userTranCount, long[] userLastTran) throws NoConnectionsException, IOException {
        ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
        client.callProcedure(coec, "inc_busy_user", delta, transactionId, transactionId, userId);
        userTranCount[userId]++;
        userLastTran[userId] = System.currentTimeMillis();

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
