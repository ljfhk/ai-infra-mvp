#!/bin/bash
cd /opt/mvp
pkill -9 -f "server-inspection" 2>/dev/null
sleep 2
export JAVA_HOME=/usr/local/jdk-17.0.16
export PATH=$JAVA_HOME/bin:$PATH
nohup java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar target/server-inspection-mvp-1.0.0-SNAPSHOT.jar </dev/null >/tmp/app.log 2>&1 &
echo "Started PID: $!"
