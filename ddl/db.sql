
--load classes ../lib/gson-2.7.jar;
load classes ../jars/voltdb-xdcr-conflictreader.jar;

file -inlinebatch END_OF_BATCH

DROP PROCEDURE ResolveConflict IF EXISTS;

DROP PROCEDURE check_user_changes IF EXISTS; 

DROP TABLE XDCR_CONFLICTS_RAW IF EXISTS;

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
,tupleJson VARCHAR(2800) 
,rowpk VARCHAR(100) NOT NULL
,primary key (tablename, CURRENTCLUSTERID, rowpk, CONFLICTTIMESTAMP, XDCRROWTYPE,partitionId)
);

PARTITION TABLE XDCR_CONFLICTS ON COLUMN partitionId;

CREATE TABLE XDCR_CONFLICTS_RAW(transactionId BIGINT NOT NULL 
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
,tupleJson VARCHAR(8000) 
,rowpk VARCHAR(100) NOT NULL
,primary key (tablename, CURRENTCLUSTERID, rowpk, CONFLICTTIMESTAMP, XDCRROWTYPE,partitionId,wasAccepted,seqno)
);

PARTITION TABLE XDCR_CONFLICTS_RAW ON COLUMN partitionId;


CREATE TABLE XDCR_NEEDED_CHANGES(transactionId BIGINT NOT NULL 
,partitionId BIGINT NOT NULL 
,siteId BIGINT NOT NULL 
,tableName VARCHAR(80) NOT NULL 
,winningClusterId TINYINT NOT NULL 
,CONFLICTTIMESTAMP TIMESTAMP NOT NULL 
,inserttime TIMESTAMP DEFAULT NOW
,tupleJson_ext VARCHAR(8000) 
,tupleJson_exp VARCHAR(8000) 
,tupleJson_new VARCHAR(8000) 
,rowpk VARCHAR(100) 
,resolution VARCHAR(100) 
,losingClusterId TINYINT
,observingClusterId TINYINT
,accepted_1_or_0 TINYINT
,OBE VARCHAR(10)
,lostbymicros bigint
--,primary key (rowpk,tablename,winningClusterId, CONFLICTTIMESTAMP,tupleJson_ext,tupleJson_exp,tupleJson_new,partitionId)
,primary key (rowpk,tablename,winningClusterId, losingClusterId, observingClusterId,CONFLICTTIMESTAMP,tupleJson_new,partitionId)
);

PARTITION TABLE XDCR_NEEDED_CHANGES ON COLUMN partitionId;


CREATE PROCEDURE 
   PARTITION ON TABLE XDCR_CONFLICTS COLUMN partitionId PARAMETER 3
   FROM CLASS conflictresolve.ResolveConflict;
   


END_OF_BATCH



