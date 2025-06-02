#!/bin/sh

MS=10000
USERCOUNT=3000
EVENTCOUNT=3
ROUNDROBIN=0
RANDOMUSER=1
DT=`date +'%Y%m%d_%H%M'`
#while
#	[ "$MS" -gt 1000 ]
#do
# 	java -jar voltdb-xdcr-conflictgenerator.jar $MS $USERCOUNT $EVENTCOUNT $ROUNDROBIN $RANDOMUSER 10.13.1.20 10.13.1.21 10.13.1.22 | tee -a ${DT}_${MS}_${USERCOUNT}_${EVENTCOUNT}_${ROUNDROBIN}_${RANDOMUSER}.lst
#	MS=`expr $MS - 1000`
#	sleep 10
#done


for i in 1 10 5000 50000 1000 2000 10000 
do

for r in  1 0 
do
for j in 0 1 
do

MS=10000
USERCOUNT=${i}
RANDOMUSER=${j}
ROUNDROBIN=${r}

while
	[ "$MS" -gt 0 ]
do
	ALREADY=`ls *_${MS}_${USERCOUNT}_${EVENTCOUNT}_${ROUNDROBIN}_${RANDOMUSER}.lst`

	if
		[  "${ALREADY}" != ""  ]
	then
		echo covered...
		ls *_${MS}_${USERCOUNT}_${EVENTCOUNT}_${ROUNDROBIN}_${RANDOMUSER}.lst
	else
		java -jar ../jars/voltdb-xdcr-conflictgenerator.jar $MS $USERCOUNT $EVENTCOUNT $ROUNDROBIN $RANDOMUSER 10.13.1.20 10.13.1.21 10.13.1.22 | tee -a ${DT}_${MS}_${USERCOUNT}_${EVENTCOUNT}_${ROUNDROBIN}_${RANDOMUSER}.lst
	fi

	if 
		[ "${MS}" -gt 1000 ]
	then
		MS=`expr $MS - 100`
	else
		if 
			[ "${MS}" -gt 10 ]
		then
			MS=`expr $MS - 10`
		else
			MS=`expr $MS - 1`
		fi
	fi
	ALREADY=`ls *_${MS}_${USERCOUNT}_${EVENTCOUNT}_${ROUNDROBIN}_${RANDOMUSER}.lst`
	if
		[  "${ALREADY}" == ""  ]
	then
		sleep 10
	fi

done


done
done
done
