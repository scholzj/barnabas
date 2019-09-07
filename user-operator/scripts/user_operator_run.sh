#!/usr/bin/env bash
if [ -f /opt/user-operator/custom-config/log4j2.properties ];
then
    export JAVA_OPTS="${JAVA_OPTS} -Dlog4j2.configurationFile=file:/opt/user-operator/custom-config/log4j2.properties"
fi
export JAVA_CLASSPATH=lib/io.strimzi.@project.build.finalName@.@project.packaging@:@project.dist.classpath@
export JAVA_MAIN=io.strimzi.operator.user.Main
exec ${STRIMZI_HOME}/bin/launch_java.sh
