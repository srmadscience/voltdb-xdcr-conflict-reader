package org.voltdb.xdcrutil;


public class UserTable implements Comparable<UserTable> {
	
	//{"USERID":"71047","USER_JSON_OBJECT":"Cluster=6 newbal=30328 798420","USER_LAST_SEEN":"2020-09-06 16:47:29.105000","USER_OWNING_CLUSTER":"6","USER_SOFTLOCK_EXPIRY":"null","USER_SOFTLOCK_SESSIONID":"null","USER_VALIDATED_BALANCE":"30328","USER_VALIDATED_BALANCE_TIMESTAMP":"2020-09-07 13:40:30.373000"}
	
	static final String[] REAL_COLUMN_NAMES =  
		{"userid","user_Json_Object","user_Last_Seen","user_Owning_Cluster","user_Softlock_Expiry","user_Softlock_SessionId","user_Validated_Balance_Timestamp","user_Validated_Balance"};
	
	long userid;
	
	String userJsonObject;
	
	String userLastSeen;
	
	int userOwningCluster;
	
	String userSoftlockExpiry;
	
	long userSoftlockSessionId;
	
	long userValidatedBalance;
	
	String userValidatedBalanceTimestamp;

	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("UserTable [userid=");
		builder.append(userid);
		builder.append(", userJsonObject=");
		builder.append(userJsonObject);
		builder.append(", lastSeenDate=");
		builder.append(userLastSeen);
		builder.append(", userOwningCluster=");
		builder.append(userOwningCluster);
		builder.append(", userSoftlockExpiry=");
		builder.append(userSoftlockExpiry);
		builder.append(", userSoftlockSessionId=");
		builder.append(userSoftlockSessionId);
		builder.append(", userValidatedBalance=");
		builder.append(userValidatedBalance);
		builder.append("]");
		return builder.toString();
	}
	
	public static String makeXdcrConflictMessageGsonFriendly(String xdcrMessage) {
		
		if (xdcrMessage.equals("NULL")) {
			return null;
		}
			
		String newMessage = new String(xdcrMessage);
		
		for (int i=0; i < REAL_COLUMN_NAMES.length; i++) {
			newMessage = newMessage.replace(REAL_COLUMN_NAMES[i].toUpperCase(), REAL_COLUMN_NAMES[i].replace("_", ""));
			newMessage = newMessage.replace(",\"" + REAL_COLUMN_NAMES[i].replace("_", "") + "\":\"null\"","");
		}
		
		
//		newMessage =  newMessage.replace(",\"userSoftlockExpiry\":\"null\"","");
//		newMessage = newMessage.replace(",\"userSoftlockSessionId\":\"null\"","");
		
		return newMessage;
	}

	public String getComparisonKey() {
	
		return userid + "\t" + userValidatedBalanceTimestamp;
		
	}
	
	@Override
	public int compareTo(UserTable o) {
		return getComparisonKey().compareTo(o.getComparisonKey());
	}
	

}
