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

public class ResolveConflict extends VoltProcedure {

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
  

  
    public static final SQLStmt getRow = new SQLStmt(
            "SELECT * FROM xdcr_conflicts WHERE CURRENTCLUSTERID = ? "
            + "AND tablename = ? "
            + "AND CONFLICTTIMESTAMP = ?"
            + "AND rowpk = ? AND XDCRROWTYPE = ?;");
    
    public static final SQLStmt delRow = new SQLStmt(
            "DELETE FROM xdcr_conflicts WHERE CURRENTCLUSTERID = ? "
            + "AND tablename = ? "
            + "AND CONFLICTTIMESTAMP = ?"
            + "AND rowpk = ? AND XDCRROWTYPE = ?;");
 
    
    public static final SQLStmt insertLoss = new SQLStmt(
            "INSERT INTO XDCR_NEEDED_CHANGES  (transactionId,\n"
            + "             partitionId,\n"
            + "             siteId, tableName, \n"
            + "             currentClusterId,\n"
            + "             CONFLICTTIMESTAMP,inserttime, \n"
            + "             tupleJson_ext,tupleJson_exp,tupleJson_new,rowpk,resolution,accepted_1_or_0) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?);");
   
  
	// @formatter:on

    public VoltTable[] run(long m_transactionId, TimestampType m_exportGenerationTime, long m_seqno, long m_partitionId,
            long m_siteId, long m_exportOperation, TimestampType m_eventTime, String m_rowType, String m_actionType,
            String m_conflictType, int m_primaryKeyConflict, int m_wasAccepted, int m_lastModClusterId,
            TimestampType m_rowTimeststamp, int m_isStillConsistent, String m_tableName, int m_currentClusterId,
            TimestampType m_conflictTimestamp, String m_JsonEncodedTuple) throws VoltAbortException {

        if (true || m_wasAccepted == 0) {

            final String rowPk = getPKAsTabString(m_JsonEncodedTuple);

            voltQueueSQL(insertConflict, m_transactionId, m_exportGenerationTime, m_seqno, m_partitionId, m_siteId,
                    m_exportOperation, m_eventTime, m_rowType, m_actionType, m_conflictType, m_primaryKeyConflict,
                    m_wasAccepted, m_lastModClusterId, m_rowTimeststamp, m_isStillConsistent, m_tableName,
                    m_currentClusterId, m_conflictTimestamp, m_JsonEncodedTuple, rowPk);

            voltQueueSQL(getRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "EXT");
            voltQueueSQL(getRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "EXP");
            voltQueueSQL(getRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "NEW");

            VoltTable[] queryResults = voltExecuteSQL();

            if (queryResults[1].advanceRow() &&
                    queryResults[2].advanceRow() && 
                    queryResults[3].advanceRow()) {

                long accepted = queryResults[1].getLong("wasAccepted");
                
                String ext = queryResults[1].getString("tupleJson");
                String exp = queryResults[2].getString("tupleJson");
                String newJson = queryResults[3].getString("tupleJson");
                
                long extAmount = getAmount(ext);
                long expAmount = getAmount(exp);
                long newAmount = getAmount(newJson);
                long fixAmount = (newAmount - expAmount);
                
//                if (m_wasAccepted == 1) {
//                    fixAmount = fixAmount * -1;
//                }
                
                voltQueueSQL(delRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "EXT");
                voltQueueSQL(delRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "EXP");
                voltQueueSQL(delRow, m_currentClusterId, m_tableName, m_conflictTimestamp, rowPk, "NEW");
                
                voltQueueSQL(insertLoss, m_transactionId, m_partitionId, m_siteId, m_tableName,
                        m_lastModClusterId, m_conflictTimestamp, this.getTransactionTime(), ext, exp,
                        newJson, rowPk, fixAmount,accepted);


            }
        }
        return voltExecuteSQL();
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
