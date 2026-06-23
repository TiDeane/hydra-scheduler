# Hydra Scheduler

This repository contains three folders:

1. `azure-dataset` - contains tools to generate, process, and execute traces;
2. `fake-worker` - contains code of a serverless worker that "accepts" function invocations and responses after the indicated duration;
3. `scripts` - contains convenience scripts to execute traces with the `azure-dataset` tools.

## Dataset Tools

This directory contains the source code of the tools (written in Java) and convenience `.sh` scripts that invoke the tools.
The trace generator supports the Azure Functions 2019 dataset and the Huawei private 2023 minute datasets.

### Trace sources

The Huawei traces are published and advertised through the SIR Lab data release repository:

- Repository: [sir-lab/data-release](https://github.com/sir-lab/data-release)
- Paper: [How Does It Function? Characterizing Long-term Trends in Production Serverless Workloads](https://arxiv.org/abs/2312.10127)

This repository uses the Huawei private 2023 minute datasets. The Huawei generator expects the private minute-level files under `azure-dataset/input/huawei_private_minute` with the following layout:

- `requests_minute/day_XXX.csv` - per-minute invocation counts for each function;
- `function_delay_minute/day_XXX.csv` - per-minute average function duration;
- `memory_limit_minute/day_XXX.csv` - per-minute function memory limit.

The generator does not copy the raw trace rows directly. Instead, it converts the minute-level aggregate trace into the invocation trace format used by the rest of this repository:

```text
HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp
```

For each selected day and minute range, the generator reads the function columns from the three Huawei files, skips functions with zero requests or missing duration/memory values, and expands each per-minute request count into individual invocation records. Each generated invocation keeps the function identifier, memory limit, and average duration from the minute aggregate. Its timestamp is placed randomly within the corresponding minute, then the final trace is sorted and normalized so that the first invocation starts at timestamp `0`. The common generator filters can then downscale the trace by maximum function count, concurrent invocations, users, or memory footprint.

The generated CSV is therefore a replayable workload derived from the trace's arrival rates, function identities, execution durations, and memory limits. It is not a full reproduction of every raw platform event in the Huawei dataset.

### Simulator overview

The simulator replays a generated invocation trace in timestamp order. It models the lifecycle of function containers at a high level rather than executing real function code.

Each invocation has an owner, function id, memory footprint, duration, start timestamp, and derived finish timestamp. During replay, the simulator maintains a set of active invocation records ordered by finish time. Before processing a new invocation, it evicts records whose finish time plus the configured keep-alive window has already passed. The remaining records represent either currently running invocations or warm cached function containers that may be reused.

When a new invocation arrives, the simulator checks whether there is an inactive warm record for the same function. If such a record exists, the invocation is treated as warm and reuses that cached function slot. If not, the invocation is counted as a cold start. The new invocation is then added to the active set and remains there until its execution finishes and its keep-alive timeout expires.

Every sample interval, currently one second, the simulator emits aggregate statistics:

- invocations processed since the previous sample;
- cold starts since the previous sample;
- currently running users, functions, invocations, and memory footprint;
- cached users, cached functions, and cached memory footprint.

The optional AOT simulation mode extends this model with a sliding cold-start window. Functions that repeatedly cold start within the configured window are marked as optimized, and the output additionally reports optimized cold starts and optimized running functions. This mode still uses the same trace replay and keep-alive model; it only changes how repeated cold starts for hot functions are classified.

Usually, the sequence of steps to start working with the dataset is as follows:

1. Build the tool with `build.sh`;
2. Download the dataset files with `download_dataset.sh --azure` or `download_dataset.sh --huawei`;
3. Generate the trace with the desired duration using `trace-generator.sh --source <azure|huawei>`. This script accepts parameters. You can learn more about using the parameters by looking at the source code in `AzureInvocationTraceGenerator.java` or `HuaweiInvocationTraceGenerator.java`. The resulting trace will be a CSV file containing a list of invocations;
4. Add a realistic language distribution of the functions in your trace by running `trace-languages.sh`. This script accepts two parameters: "i" (long version: "input") - the input trace and "t" (long version: "trace") - the output trace;

Now you have the tools built and the trace generated. If you want to ensure the correct language distribution, you can run the `check-multifunc.sh` script to see how many invocations per language there are.

In order to configure the multi-worker executor (i.e., the trace executor that runs the entire trace with multiple workers), you can change the global variables in `Environment.java`. Pay close attention to the `WORKER_COUNT` and `FAKE_WORKER_FIRST_PORT` fields - their values should match the parameters that you pass to the `deploy-swarm.sh` script in the `fake-worker` directory (described later in this README).

## Fake Worker

This directory contains the source code of the fake serverless worker that simulates invocation processing. Currently, the fake worker accepts only two types of messages:

1. "u" ("u" stands for "upload") - returns immediately, needed to simulate function registration;
2. "i \<duration\>" ("i" stands for "invocation") - returns after `<duration>` milliseconds; `<duration>` should be a valid integer.

Please note that this worker is not an HTTP server; it is a socket server written in Java (see `Server.java`).

The sequence of steps to launch fake workers:

1. Build the worker with `build.sh`;
2. Run `deploy.sh <port>` to run a single worker;
3. Alternatively, run `deploy-swarm.sh <count> <first-port>` to run `<count>` workers with port range starting from `<first-port>`;
4. You can check the logs of your workers in `/tmp/fake-workers`;
5. Run `cleanup-swarm.sh` to terminate all running workers.

If you want to test yor fake worker, you can run `run-client.sh` and start typing messages. This script expects the fake worker to run on port 5454.

## Scripts

This directory contains the scripts to run the large-scale experiment (abbreviated LSE). Currently, only the `benchmark-lse-simulation.sh` script works correctly as this repository is still under development.

The `benchmark-lse-simulation.sh` script expects fake workers to be already created. 

Usage of this script: `benchmark-lse-simulation.sh <hy|hy-sf|hy-si|ow> </path/to/trace.csv>`.
