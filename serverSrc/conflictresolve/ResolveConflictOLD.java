/* This file is part of VoltDB.
 * Copyright (C) 2008-2023 VoltDB Inc.
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
package conflictresolve;

import java.util.Date;
import java.util.HashMap;

import org.apache.commons.codec.binary.StringUtils;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltProcedure.VoltAbortException;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;
import org.voltdb.xdcrutil.XdcrActionType;
import org.voltdb.xdcrutil.XdcrConflictType;
import org.voltdb.xdcrutil.XdcrRowType;

public class ResolveConflictOLD extends VoltProcedure {

    // @formatter:off

    public static final SQLStmt insertConflict = new SQLStmt(
            "INSERT INTO xdcr_conflicts  (transactionId,\n"
            + "             exportGenerationTime,\n"
            + "             seqno,\n"
            + "             partitionId,\n"
            + "             siteId,\n"
            + "             exportOperation,\n"
            + "             eventTime,\n"
            + "             XdcrRowType,\n"
            + "             XdcractionType,\n"
            + "             XdcrconflictType,\n"
            + "             primaryKeyConflict,\n"
            + "             wasAccepted,\n"
            + "             lastModClusterId,\n"
            + "             rowTimestamp,\n"
            + "             isConsistant,\n"
            + "             tableName,\n"
            + "             currentClusterId,\n"
            + "             conflictTimestamp,inserttime, \n"
            + "             tupleJson,rowPk) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW,?,?);");
  
    public static final SQLStmt getConflictParticipantCount = new SQLStmt(
            "SELECT count(*) how_many FROM xdcr_conflicts WHERE transactionId = ? AND rowpk LIKE '%'||?||'%';");

    public static final SQLStmt getRow = new SQLStmt(
            "SELECT * FROM xdcr_conflicts WHERE transactionId = ? AND XDCRROWTYPE = ? AND rowpk LIKE '%'||?||'%' ;");
    
    public static final SQLStmt delRow = new SQLStmt(
            "DELETE FROM xdcr_conflicts WHERE transactionId = ? AND XDCRROWTYPE = ? ;");
    
    public static final SQLStmt insertLoss = new SQLStmt(
            "INSERT INTO XDCR_NEEDED_CHANGES  (transactionId,\n"
            + "             partitionId,\n"
            + "             siteId, tableName, \n"
            + "             currentClusterId,\n"
            + "             maxrowtimestamp,inserttime, \n"
            + "             tupleJson_ext,tupleJson_exp,tupleJson_new,rowpk,resolution) VALUES (?,?,?,?,?,?,?,?,?,?,?,?);");
   
    public static final SQLStmt checkConflictKnown  = new SQLStmt(
            "SELECT siteid FROM XDCR_NEEDED_CHANGES "
            + "WHERE partitionId = ? AND SITEID= ? "
            + "AND maxrowtimestamp = ? AND TABLENAME = ? AND ROWPK = ? "
            + "AND tupleJson_ext = ? AND tupleJson_exp = ? AND tupleJson_new = ?;");
 
    public static final SQLStmt updateConflictKnown  = new SQLStmt(
            "UPDATE XDCR_NEEDED_CHANGES SET otherClusterId = ? "
            + "WHERE partitionId = ? AND SITEID= ? "
            + "AND maxrowtimestamp = ? AND TABLENAME = ? AND ROWPK = ? "
            + "AND tupleJson_ext = ? AND tupleJson_exp = ? AND tupleJson_new = ?;");
 
	// @formatter:on

    public VoltTable[] run(long m_transactionId, TimestampType m_exportGenerationTime, long m_seqno, long m_partitionId,
            long m_siteId, long m_exportOperation, TimestampType m_eventTime, String m_rowType, String m_actionType,
            String m_conflictType, int m_primaryKeyConflict, int m_wasAccepted, int m_lastModClusterId,
            TimestampType m_rowTimeststamp, int m_isStillConsistent, String m_tableName, int m_currentClusterId,
            TimestampType m_conflictTimestamp, String m_JsonEncodedTuple) throws VoltAbortException {

        if (m_wasAccepted == 0) {

            final String rowPk = getPKAsTabString(m_JsonEncodedTuple);

            voltQueueSQL(insertConflict, m_transactionId, m_exportGenerationTime, m_seqno, m_partitionId, m_siteId,
                    m_exportOperation, m_eventTime, m_rowType, m_actionType, m_conflictType, m_primaryKeyConflict,
                    m_wasAccepted, m_lastModClusterId, m_rowTimeststamp, m_isStillConsistent, m_tableName,
                    m_currentClusterId, m_conflictTimestamp, m_JsonEncodedTuple, rowPk);

            voltQueueSQL(getConflictParticipantCount, m_transactionId, rowPk);
            voltQueueSQL(getRow, m_transactionId, "EXT", rowPk);
            voltQueueSQL(getRow, m_transactionId, "EXP", rowPk);
            voltQueueSQL(getRow, m_transactionId, "NEW", rowPk);

            VoltTable[] queryResults = voltExecuteSQL();

            queryResults[1].advanceRow();
            if (queryResults[1].getLong("how_many") == 3) {

                if (queryResults[2].getRowCount() == 1) {
                    if (queryResults[3].getRowCount() == 1) {
                        if (queryResults[4].getRowCount() == 1) {

                            queryResults[2].advanceRow();
                            queryResults[3].advanceRow();
                            queryResults[4].advanceRow();

                            String ext = queryResults[2].getString("tupleJson");
                            String exp = queryResults[3].getString("tupleJson");
                            String newJson = queryResults[4].getString("tupleJson");

                            HashMap<Long, String> involvedClusters = new HashMap<Long, String>();

                            for (int i = 2; i < queryResults.length; i++) {
                                involvedClusters.put(queryResults[i].getLong("CURRENTCLUSTERID"),
                                        "CURRENTCLUSTERID" + i);
                                involvedClusters.put(queryResults[i].getLong("LASTMODCLUSTERID"),
                                        "LASTMODCLUSTERID" + i);
                            }

                            long extAmount = getAmount(ext);
                            long expAmount = getAmount(exp);
                            long newAmount = getAmount(newJson);
                            long fixAmount = (newAmount - expAmount);

                            TimestampType maxRowTimestamp = queryResults[4].getTimestampAsTimestamp("rowtimestamp");
                            long otherClusterId = queryResults[4].getLong("CURRENTCLUSTERID");

                            voltQueueSQL(checkConflictKnown, m_partitionId, m_siteId, maxRowTimestamp, m_tableName,
                                    rowPk, ext, exp, newJson);
                            voltQueueSQL(delRow, m_transactionId, "EXT");
                            voltQueueSQL(delRow, m_transactionId, "EXP");
                            voltQueueSQL(delRow, m_transactionId, "NEW");

                            VoltTable[] updateResults = voltExecuteSQL();

                            if (rowPk.equals("0")) {
                                for (int i = 0; i < queryResults.length; i++) {
                                    System.out.println(queryResults[i].toFormattedString());

                                }
                                System.out.println(involvedClusters.toString());

                            }
                            if (false /* updateResults[0].advanceRow()*/) {
                                voltQueueSQL(updateConflictKnown, otherClusterId, m_partitionId, m_siteId,
                                        maxRowTimestamp, m_tableName, rowPk, ext, exp, newJson);

                            } else /* if (involvedClusters.size() <= 2) */ {

                                voltQueueSQL(insertLoss, m_transactionId, m_partitionId, m_siteId, m_tableName,
                                        m_currentClusterId, maxRowTimestamp, this.getTransactionTime(), ext, exp,
                                        newJson, rowPk, fixAmount);
                            }
                        }

                    }

                }

            }

        }

        return voltExecuteSQL(true);

    }

    /**
     * @param m_JsonEncodedTuple
     * @return
     */
    private String getPKAsTabString(String m_JsonEncodedTuple) {
        return m_JsonEncodedTuple.split(",")[1].split("\"")[3];
    }

    private long getAmount(String value) {
        String valueAsText = value.split(",")[0].split("\"")[3];
        return Long.parseLong(valueAsText);
    }

}
