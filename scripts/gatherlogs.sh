#!/bin/sh

scp badger@10.13.1.21:database/voltdbroot/log/volt.log 21_voltlog.log
scp rosal@10.13.1.22:database/voltdbroot/log/volt.log 22_voltlog.log
scp jersey@10.13.1.23:database/voltdbroot/log/volt.log 23_voltlog.log
scp hardtofind@10.13.1.20:/home/hardtofind/kafka_2.13-2.6.0/drconflict_topic.log .
