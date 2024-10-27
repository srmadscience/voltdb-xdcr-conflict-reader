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

public class ConflictGenerator {

    private static final int USER_SUBTRACT_TX_VALUE = -1;
    private static final int USER_ADD_TX_VALUE = 100;
    private static final int USER_INITIAL_BALANCE = 1000;

    private static final int USER_COUNT = 100;
    private static final int TX_COUNT = 101;
    private static final int USER_COMMENT_LENGTH = 100;

    public static void main(String[] args) {
        // TODO Auto-generated method stub
        Client[] sites = new Client[args.length];

        try {

            for (int i = 0; i < sites.length; i++) {
                sites[i] = connectVoltDB(args[i]);
            }

            try {

                ClientResponse cr = sites[0].callProcedure("inc_busy_user", -1, "", -1);

                callAdhocProcedure(sites, 0, "DELETE FROM XDCR_CONFLICTS;");
                callAdhocProcedure(sites, 0, "DELETE FROM XDCR_NEEDED_CHANGES;");

            } catch (ProcCallException e) {

                for (int i = 0; i < sites.length; i++) {
                    callAdhocProcedure(sites, i, "DROP PROCEDURE inc_busy_user IF EXISTS;");
                    callAdhocProcedure(sites, i, "DROP TABLE busy_users IF EXISTS;");
                    callAdhocProcedure(sites, i, "CREATE TABLE busy_users (userid bigint not null primary key"
                            + ", a_number bigint not null" + ", user_comment varchar(" + USER_COMMENT_LENGTH + "));");
                    callAdhocProcedure(sites, i, "PARTITION TABLE busy_users ON COLUMN userid;");
                    callAdhocProcedure(sites, i, "DR TABLE busy_users;");
                    callAdhocProcedure(sites, i, "CREATE INDEX uu_idx1 ON busy_users(a_number);");
                    callAdhocProcedure(sites, i,
                            "CREATE PROCEDURE inc_busy_user PARTITION ON TABLE busy_users "
                                    + "COLUMN userid PARAMETER 2  AS UPDATE busy_users SET a_number = a_number + ?"
                                    + ", user_comment = substring(user_comment||','||?,1," + USER_COMMENT_LENGTH
                                    + ") WHERE userid = CAST(? AS BIGINT);");

                }
            }

            ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

            msg("Deleting " + USER_COUNT + " users...");
            for (int i = 0; i < USER_COUNT; i++) {
                sites[0].callProcedure(coec, "busy_users.DELETE", i);
            }

            sites[0].drain();

            waitUntilAllSitesHave(sites, 0);

            msg("Creating " + USER_COUNT + " users...");
            for (int i = 0; i < USER_COUNT; i++) {
                sites[0].callProcedure(coec, "busy_users.INSERT", i, USER_INITIAL_BALANCE, "INSERTED");
            }

            sites[0].drain();

            waitUntilAllSitesHave(sites, USER_COUNT);

            ConflictReader conflictReader = new ConflictReader(args[0], "10.13.1.20");
            Thread conflictReaderThread = new Thread(conflictReader);

            Random r = new Random(42);

            boolean roundRobin = false;
            boolean randomUser = false;
            boolean randomButOnlyOnceOrder = false;

            ArrayList<Integer> randomOrderList = new ArrayList<Integer>();

            for (int i = 0; i < TX_COUNT; i++) {
                randomOrderList.add(i);
            }

            // conflictReaderThread.start();

            msg("Starting conflict run. RoundRobin = " + roundRobin + " random user = " + randomUser
                    + " randomButOnlyOnceOrder=" + randomButOnlyOnceOrder);
            for (int i = 0; i < TX_COUNT; i++) {

                if (i % 10000 == 0) {
                    msg("On transaction " + i + "..");

                }
                if (i % 100 == 0) {
                    msg("On transactionSLEEP " + i + "..");
                    Thread.sleep(1000);

                }

                int userId = i % USER_COUNT;

                if (randomUser) {
                    if (randomButOnlyOnceOrder) {
                        userId = randomOrderList.remove(r.nextInt(randomOrderList.size()));
                    } else {
                        userId = r.nextInt(USER_COUNT);
                    }
                }

                if (roundRobin) {
                    sites[i % sites.length].callProcedure(coec, "inc_busy_user", USER_SUBTRACT_TX_VALUE,
                            i + "/" + (i % sites.length) + ": add " + USER_SUBTRACT_TX_VALUE, userId);
                    sites[(i + 1) % sites.length].callProcedure(coec, "inc_busy_user", USER_ADD_TX_VALUE,
                            i + "/" + ((i + 1) % sites.length) + ": add " + USER_ADD_TX_VALUE, userId);
                } else {
                    sites[0].callProcedure(coec, "inc_busy_user", USER_SUBTRACT_TX_VALUE,
                            i + "/" + "0: add " + USER_SUBTRACT_TX_VALUE, userId);
                    sites[1].callProcedure(coec, "inc_busy_user", USER_ADD_TX_VALUE,
                            i + "/" + "1: add " + USER_ADD_TX_VALUE, userId);

                }
            }

            for (int i = 0; i < sites.length; i++) {
                sites[i].drain();
            }

            conflictReaderThread.start();
            conflictReader.requestFinish();
            while (conflictReaderThread.isAlive()) {
                msg("Waiting for reader to finish...");
                Thread.sleep(Duration.ofSeconds(ConflictReader.POLL_TIME_SECONDS / 3));
            }

            long expectedTotal = (USER_INITIAL_BALANCE * USER_COUNT)
                    + ((USER_ADD_TX_VALUE + USER_SUBTRACT_TX_VALUE) * TX_COUNT);
            long inUsers = getUserTotal(sites[0]);
            long bufferedChanges = getChangeTotal(sites[0]);

            if (expectedTotal == (inUsers + bufferedChanges)) {
                msg("All changes caught. Expected " + expectedTotal + ", got " + inUsers + "+" + bufferedChanges
                        + ", = " + (inUsers + bufferedChanges));
            } else {
                msg("Expected " + expectedTotal + ", got " + inUsers + "+" + bufferedChanges + ", = "
                        + (inUsers + bufferedChanges) + " out by " + ((inUsers + bufferedChanges) - expectedTotal));
            }

        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

    }

    static long getUserTotal(Client c) throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc", "select sum(a_number) how_many from busy_users");
        cr.getResults()[0].advanceRow();
        long aValue = cr.getResults()[0].getLong("how_many");
        if (cr.getResults()[0].wasNull()) {
            return 0;
        }
        return aValue;

    }

    static long getChangeTotal(Client c) throws NoConnectionsException, IOException, ProcCallException {

        ClientResponse cr = c.callProcedure("@AdHoc",
                "select sum(cast(resolution as bigint)) how_many from XDCR_NEEDED_CHANGES WHERE ACCEPTED_1_OR_0 = 0;");
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
