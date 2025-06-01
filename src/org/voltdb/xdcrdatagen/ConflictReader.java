package org.voltdb.xdcrdatagen;

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
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Headers;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.xdcrutil.ComplainOnErrorCallback;
import org.voltdb.xdcrutil.UserTable;
import org.voltdb.xdcrutil.XdcrActionType;
import org.voltdb.xdcrutil.XdcrConflictMessage;
import org.voltdb.xdcrutil.XdcrFormatException;

import com.google.gson.Gson;

public class ConflictReader implements Runnable {

    public static final int POLL_TIME_SECONDS = 120;
    String kafkaHostName = null;
    String voltHostName = null;
    boolean finished = false;

    public ConflictReader(String voltHostName, String kafkaHostName) {
        super();
        this.kafkaHostName = kafkaHostName;
        this.voltHostName = voltHostName;
        msg("Kafka=" + kafkaHostName +  ", volt="+ voltHostName);
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
        System.out.println(strDate + ":Reader    :" + message);

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

    @Override
    public void run() {
        long lastRecordTimeMs = System.currentTimeMillis();

        try {
            Client c = connectVoltDB(voltHostName);
            Properties props = new Properties();
            props.put("bootstrap.servers", kafkaHostName + ":9092");
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "ConflictReader");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("max.poll.records", "50000");
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
            Thread.sleep(4000);
            consumer.subscribe(Arrays.asList("voltdbexportVOLTDB_AUTOGEN_XDCR_CONFLICTS_PARTITIONED"));

            Gson g = new Gson();

            while (true) {

                //msg("polling Kafka on " + kafkaHostName + " for " + POLL_TIME_SECONDS + " seconds....");
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(POLL_TIME_SECONDS));
                msg("Got " + records.count());

                if (records.count() == 0 && finished
                        && (System.currentTimeMillis() - lastRecordTimeMs > (POLL_TIME_SECONDS * 1000))) {
                    break;
                } else if (records.count() > 0) {
                    lastRecordTimeMs = System.currentTimeMillis();
                }

                for (ConsumerRecord<String, String> record : records) {

                    try {

                        XdcrConflictMessage newMessage = new XdcrConflictMessage(record.key(), record.value());

                        newMessage.m_JsonEncodedTuple = UserTable
                                .makeXdcrConflictMessageGsonFriendly(newMessage.m_JsonEncodedTuple);

                        if (newMessage.m_JsonEncodedTuple != null) {
                            UserTable t = g.fromJson(newMessage.m_JsonEncodedTuple, UserTable.class);

                        }

                        if (newMessage.m_actionType == XdcrActionType.D
                                && newMessage.m_tableName.equalsIgnoreCase("USER_RECENT_TRANSACTIONS")) {

                        } else {
//                            if (newMessage.m_currentClusterId == 6) {
//                                System.out.println(newMessage.toShortString());
//                            }

                          //  if (!newMessage.isM_wasAccepted()) {

                                ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
                                newMessage.insertToVoltDBUsingProcedure(coec, c, "ResolveConflict");
                                newMessage = null;
                          //  }
                        }
                    } catch (XdcrFormatException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                //msg("Waiting 1 second...");
                Thread.sleep(10);
            }

            consumer.close();
            msg("Reader Finished");
        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
    }

    /**
     * @param finished the finished to set
     */
    public void requestFinish() {
        msg("told to finish");
        this.finished = true;
    }

}
