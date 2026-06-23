#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

cd "$DIR" || {
  echo "Redirection failed!"
  exit 1
}

TRACES=(
  #"output/d01_b1_e120_f20000.csv"
  #"output/d01_b1_e360_f20000.csv"
  #"output/d01_b1_e720_f20000.csv"
  #"output/d01_b1_e1440_f20000.csv"
  #"output/d01_b1_e120.csv"
  #"output/d01_b1_e720.csv"
)

STRATEGIES=(
  "random"
  "cold-start"
  "longest-running"
  "sla-violation"
  "all-rounder"
)

OPTIMIZATIONS=(
  # "-aot"
  # "-snapshot"
  "-aot -snapshot"
)

# run all combinations of traces, strategies and optimizations
{
  # run baseline for every trace
  for trace in "${TRACES[@]}"; do
    echo "./trace-simulator.sh -u no-opt -i $trace"
  done

  # run every combination
  for optimization in "${OPTIMIZATIONS[@]}"; do
    for strategy in "${STRATEGIES[@]}"; do
      for trace in "${TRACES[@]}"; do
        echo "./trace-simulator.sh $optimization -u $strategy -i $trace"
      done
    done
  done

} | parallel -j 4 --progress # keep 4 jobs running at all times
