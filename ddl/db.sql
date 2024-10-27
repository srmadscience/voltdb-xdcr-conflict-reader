
--load classes ../lib/gson-2.7.jar;
load classes ../jars/voltdb-xdcr-conflictreader.jar;

file -inlinebatch END_OF_BATCH

DROP PROCEDURE ResolveConflict IF EXISTS;


DROP TABLE XDCR_CONFLICTS IF EXISTS;

DROP TABLE XDCR_NEEDED_CHANGES IF EXISTS;


END_OF_BATCH

file -inlinebatch END_OF_BATCH

CREATE TABLE XDCR_CONFLICTS(transactionId BIGINT NOT NULL 
,exportGenerationTime TIMESTAMP NOT NULL 
,seqno BIGINT NOT NULL 
,partitionId BIGINT NOT NULL 
,siteId BIGINT NOT NULL 
,exportOperation TINYINT NOT NULL 
,eventTime TIMESTAMP NOT NULL 
,XdcrRowType VARCHAR(3) NOT NULL 
,XdcrActionType VARCHAR(1) NOT NULL 
,XdcrConflictType VARCHAR(4) NOT NULL 
,primaryKeyConflict TINYINT NOT NULL 
,wasAccepted TINYINT NOT NULL 
,lastModClusterId TINYINT NOT NULL 
,rowTimestamp TIMESTAMP NOT NULL 
,isConsistant TINYINT NOT NULL 
,tableName VARCHAR(80) NOT NULL 
,currentClusterId TINYINT NOT NULL 
,conflictTimestamp TIMESTAMP NOT NULL 
,inserttime TIMESTAMP DEFAULT NOW
,tupleJson VARCHAR(500) 
,rowpk VARCHAR(100) 
,primary key (CURRENTCLUSTERID, tablename, CONFLICTTIMESTAMP, rowpk,XDCRROWTYPE,partitionId)
);

PARTITION TABLE XDCR_CONFLICTS ON COLUMN partitionId;

CREATE TABLE XDCR_NEEDED_CHANGES(transactionId BIGINT NOT NULL 
,partitionId BIGINT NOT NULL 
,siteId BIGINT NOT NULL 
,tableName VARCHAR(80) NOT NULL 
,currentClusterId TINYINT NOT NULL 
,CONFLICTTIMESTAMP TIMESTAMP NOT NULL 
,inserttime TIMESTAMP DEFAULT NOW
,tupleJson_ext VARCHAR(500) 
,tupleJson_exp VARCHAR(500) 
,tupleJson_new VARCHAR(500) 
,rowpk VARCHAR(100) 
,resolution VARCHAR(100) 
,otherClusterId TINYINT
,accepted_1_or_0 TINYINT
,primary key (cURRENTCLUSTERID, tablename, CONFLICTTIMESTAMP, rowpk,tupleJson_ext,tupleJson_exp,tupleJson_new,partitionId)
);

PARTITION TABLE XDCR_NEEDED_CHANGES ON COLUMN partitionId;

CREATE PROCEDURE 
   PARTITION ON TABLE XDCR_CONFLICTS COLUMN partitionId PARAMETER 3
   FROM CLASS conflictresolve.ResolveConflict;
   
   
END_OF_BATCH



