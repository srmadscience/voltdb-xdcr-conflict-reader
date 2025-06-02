#!/bin/sh

#nohup /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java -jar voltdb-xdcr-conflictgenerator.jar 10.13.1.21 10.13.1.22 10.13.1.23 | tee -a testrun.lst &
#/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java -jar voltdb-xdcr-conflictgenerator.jar 10.13.1.21 10.13.1.22 10.13.1.23 | tee -a testrun.lst 
/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java -jar voltdb-xdcr-conflictgenerator.jar 10.13.1.21 10.13.1.22 | tee -a testrun.lst 
