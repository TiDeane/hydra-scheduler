#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

cd "$DIR" || {
  echo "Redirection failed!"
  exit 1
}

JAR=$DIR/build/libs/azure-dataset-1.0-all.jar
MAIN=org.graalvm.argo.dataset.utility.forecasting.OptimizationScheduler

$JAVA_HOME/bin/java -cp $JAR $MAIN $@
