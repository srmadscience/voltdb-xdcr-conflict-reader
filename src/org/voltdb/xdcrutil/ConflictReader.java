package org.voltdb.xdcrutil;

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
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;

import com.google.gson.Gson;

public class ConflictReader {

	public static void main(String[] args) {
		// TODO Auto-generated method stub

		try {
			Client c = connectVoltDB("hardtofind");

			String[] conflictDDL = XdcrConflictMessage.toDDL("XDCR_CONFLICTS", "partitionId", 500);

			for (int i = 0; i < conflictDDL.length; i++) {
				msg(conflictDDL[i]);
				c.callProcedure("@AdHoc", conflictDDL[i]);
			}

			
			Properties props = new Properties();
			props.put("bootstrap.servers", "hardtofind:9092");
			props.put("group.id", "test3");
//	     props.put("enable.auto.commit", "true");
//	     props.put("auto.commit.interval.ms", "1000");
			props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
			props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
			KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
			consumer.subscribe(Arrays.asList("voltdbexportVOLTDB_AUTOGEN_XDCR_CONFLICTS_PARTITIONED"));
			
			Gson g = new Gson();
			
			while (true) {
				
				ConsumerRecords<String, String> records = consumer.poll(100);
				for (ConsumerRecord<String, String> record : records) {
					// System.out.printf("offset = %d, key = %s, value = %s%n", record.offset(),
					// record.key(), record.value());

					try {
						XdcrConflictMessage newMessage = new XdcrConflictMessage(record.key(), record.value());
						
						
						newMessage.m_JsonEncodedTuple = UserTable.makeXdcrConflictMessageGsonFriendly(newMessage.m_JsonEncodedTuple);
						System.out.println(newMessage.m_JsonEncodedTuple);
						
						if (newMessage.m_JsonEncodedTuple != null){
							UserTable t = g.fromJson(newMessage.m_JsonEncodedTuple, UserTable.class);
							
							System.out.println(t.toString());
		
						}
						
						if (false && newMessage.m_actionType == XdcrActionType.D
								&& newMessage.m_tableName.equalsIgnoreCase("USER_RECENT_TRANSACTIONS")) {

						} else {
							if (newMessage.m_currentClusterId == 6) {
								System.out.println(newMessage.toShortString());
							}

							ComplainOnErrorCallback coec = new ComplainOnErrorCallback();
							newMessage.insertToVoltDB(coec, c, "XDCR_CONFLICTS");
							newMessage = null;
						}
					} catch (XdcrFormatException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}

		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
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
		System.out.println(strDate + ":" + message);

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
