#!/bin/sh

OUTPUTFILE=xdcrbugrun250420.csv 
# 2941, STATS=[2928, 2955], failCount=0, mintrangap=3, args=[9, 1000, 3, 1, 1, 10.13.1.20, 10.13.1.21, 10.13.1.22]
echo "Actual,Site20,Site21,Failures,mintrangap,sleepMs,userCount,eventCount,roundRobin,randomUser,kafkahost,site1,site2" > ${OUTPUTFILE}
grep GREPME *.lst | grep Statistics | awk -F- '{ print $4 }' | sed '1,$s/STATS=\[//g' | sed '1,$s/\]//g' | sed '1,$s/args=\[//g' | sed '1,$s/mintrangap=//g' | sed '1,$s/failCount=//g' | tee -a  ${OUTPUTFILE}
 
