import pandas as pd
from pathlib import Path
import math
import re
import sys


MAX_OPTIMIZED_FUNCS = 4000
SELECTION_INTERVAL = 60  # minutes

def calculate_total_funcs(config, trace):
    """
    Calculate the number of functions that have actually been
    optimized by the end of the trace.

    Configuration:
        m4000_oaX_obY_uci60_oi10

    X = number of functions selected every 60 minutes
    Y = number of functions optimized every 10 minutes

    Example:
        m4000_oa1000_ob200_uci60_oi10
        d01_b1_e120

        First selection: minute 60
        1000 functions selected
        200 optimized every 10 minutes

        Complete at:
            70 -> 200
            80 -> 400
            90 -> 600
            100 -> 800
            110 -> 1000

        Second selection occurs at minute 120, but the trace
        ends there, so those functions are not counted.

        Result: 1000
    """

    # Parse configuration.
    match = re.search(
        r"m(\d+)_oa(\d+)_ob(\d+)_uci(\d+)_oi(\d+)",
        config
    )

    if not match:
        raise ValueError(f"Could not parse configuration: {config}")

    max_funcs = int(match.group(1))
    oa = int(match.group(2))
    ob = int(match.group(3))
    uci = int(match.group(4))

    # Parse trace duration.
    match = re.search(r"_e(\d+)$", trace)

    if not match:
        raise ValueError(f"Could not parse trace duration: {trace}")

    duration = int(match.group(1))

    # Number of 10-minute optimization rounds required to
    # optimize one complete selection of `oa` functions.
    rounds_required = math.ceil(oa / ob)

    # Time required to finish one selection.
    optimization_time = rounds_required * 10

    total_funcs = 0

    # Selection occurs every `uci` minutes.
    selection_time = uci

    while selection_time < duration:
        completion_time = selection_time + optimization_time

        # Only count functions whose optimization has completed
        # before the trace ends.
        if completion_time <= duration:
            total_funcs += oa

        selection_time += uci

    # Never exceed the configured maximum.
    return min(total_funcs, max_funcs)

def get_highest_guarantee(fulfillment_pct):
    """
    Maps a fulfillment percentage to the highest possible SLA tier.
    Tiers: 75%, 90%, 95%, 97.5%, 99%
    """
    # Define tiers in descending order for logic check
    tiers = [99.9, 99.0, 97.5, 95.0, 90.0, 75.0]
    
    for tier in tiers:
        if fulfillment_pct >= tier:
            # Format as string for the final column
            return tier, f"{tier}%"
    return 0.0, "None"

def process_all_sla_tiers(root_dir):
    """
    Finds all *_cdf_data.csv files in root_dir and subdirectories,
    calculates SLA tier improvements, and saves a report for each.
    """
    root_path = Path(root_dir)
    if not root_path.exists():
        print(f"Error: Directory {root_dir} does not exist.")
        return

    # Find all matching files recursively
    files = list(root_path.rglob("*_cdf_data.csv"))
    print(f"Found {len(files)} CDF data files. Starting batch processing...")

    for input_path in files:
        # Define output path: same folder, new suffix
        output_path = input_path.parent / input_path.name.replace("_cdf_data.csv", "_sla_report.csv")
        
        try:
            df = pd.read_csv(input_path)
        except Exception as e:
            print(f"Skipping {input_path.name}: Error reading file - {e}")
            continue

        # Check for necessary columns
        required_cols = ['invocationsBeforeOpt', 'slaViolationsBeforeOpt', 'invocationsAfterOpt', 'slaViolationsAfterOpt']
        if not all(col in df.columns for col in required_cols):
            # This handles older baseline files that might not have been updated yet
            print(f"Skipping {input_path}: Missing required columns.")
            continue

        # Filtering for functions that actually experienced the optimized state
        df_opt = df[df['invocationsAfterOpt'] > 0].copy()

        if df_opt.empty:
            # We don't print a warning for every file to keep the console clean, 
            # as many baseline/no-opt files will naturally be empty here.
            continue

        # 1. Calculate Raw Fulfillment
        df_opt['SLA-fulfilment-before'] = df_opt.apply(
            lambda x: (1 - (x['slaViolationsBeforeOpt'] / x['invocationsBeforeOpt'])) * 100 
            if x['invocationsBeforeOpt'] > 0 else 0.0, axis=1
        )
        
        df_opt['SLA-fulfilment-optimized'] = (1 - (df_opt['slaViolationsAfterOpt'] / df_opt['invocationsAfterOpt'])) * 100

        # 2. Map to Tiers (Using your existing get_highest_guarantee helper)
        tier_results_before = df_opt['SLA-fulfilment-before'].apply(get_highest_guarantee)
        tier_results_opt = df_opt['SLA-fulfilment-optimized'].apply(get_highest_guarantee)

        df_opt['tier_val_before'] = tier_results_before.apply(lambda x: x[0])
        df_opt['highest-possible-guaratee-before'] = tier_results_before.apply(lambda x: x[1])
        
        df_opt['tier_val_opt'] = tier_results_opt.apply(lambda x: x[0])
        df_opt['highest-possible-guaratee-optimized'] = tier_results_opt.apply(lambda x: x[1])

        # 3. Determine improvement
        df_opt['improvement-in-SLA-class-offering-possible'] = df_opt['tier_val_opt'] > df_opt['tier_val_before']

        # 4. Final selection and rounding
        final_df = df_opt[[
            'functionHash',
            'SLA-fulfilment-before',
            'SLA-fulfilment-optimized',
            'highest-possible-guaratee-before',
            'highest-possible-guaratee-optimized',
            'improvement-in-SLA-class-offering-possible'
        ]].rename(columns={'functionHash': 'funcID'})

        final_df['SLA-fulfilment-before'] = final_df['SLA-fulfilment-before'].round(2)
        final_df['SLA-fulfilment-optimized'] = final_df['SLA-fulfilment-optimized'].round(2)

        # 5. Export
        final_df.to_csv(output_path, index=False)
        
        improved_count = final_df['improvement-in-SLA-class-offering-possible'].sum()
        print(f"Processed: {input_path.name} -> {improved_count} improvements found.")

    print("\n--- All reports generated successfully ---")


def summarize_sla_improvements(root_dir):
    root_path = Path(root_dir)
    results = []

    files = list(root_path.rglob("*_sla_report.csv"))
    if not files:
        print(f"No SLA reports found in {root_dir}.")
        return

    for file_path in files:
        config = file_path.parent.name
        filename = file_path.name
        core_name = filename.replace("_sla_report.csv", "")

        try:
            parts = core_name.rsplit("_", 2)
            trace = parts[0] if len(parts) == 3 else core_name
            utility = parts[1] if len(parts) == 3 else "N/A"
            opt = parts[2] if len(parts) == 3 else "N/A"

            df = pd.read_csv(file_path)
            if df.empty:
                continue

            # Calculate the expected number of optimized functions.
            total_funcs = calculate_total_funcs(config, trace)

            improvements = df[
                "improvement-in-SLA-class-offering-possible"
            ].sum()

            improvement_pct = (
                improvements / total_funcs
            ) * 100 if total_funcs > 0 else 0

            results.append({
                "Config": config,
                "Trace": trace,
                "Utility": utility,
                "Optimization": opt,
                "Improved %": round(improvement_pct, 2),
                "Total Funcs": total_funcs
            })

        except Exception as e:
            print(f"Error processing {filename}: {e}")

    summary_df = pd.DataFrame(results)
    if summary_df.empty:
        return

    # Sort all data globally first to ensure consistency
    summary_df = summary_df.sort_values(by=["Config", "Trace", "Improved %"], ascending=[True, True, False])

    print("\n" + " SLA IMPROVEMENT ANALYSIS REPORT ".center(110, "="))

    grouped = summary_df.groupby("Config")
    for config_name, group in grouped:
        print(f"\n\n### CONFIGURATION: **{config_name}**")
        print("=" * 110)
        
        # --- TABLE 1: PER-TRACE RAW DATA ---
        print(f"{'TRACE':<35} | {'UTILITY':<20} | {'OPT':<15} | {'IMP %':<10} | {'TOTAL'}")
        print("-" * 110)
        
        for _, row in group.iterrows():
            print(f"{row['Trace']:<35} | {row['Utility']:<20} | {row['Optimization']:<15} | {row['Improved %']:>8}% | {row['Total Funcs']}")
        
        # --- TABLE 2: STRATEGY LEADERBOARD FOR THIS CONFIG ---
        print(f"\n--- STRATEGY RANKING FOR {config_name} ---")
        
        rankings = group.groupby(['Utility', 'Optimization']).agg({
            'Improved %': ['mean', 'std']
        }).reset_index()
        
        rankings.columns = ['Utility', 'Optimization', 'Avg_Imp', 'Std_Dev']
        rankings = rankings.sort_values(by="Avg_Imp", ascending=False)

        print(f"{'UTILITY METHOD':<25} | {'OPT STRATEGY':<20} | {'AVG IMP %':<12} | {'STD DEV'}")
        print("-" * 80)
        for _, row in rankings.iterrows():
            std_str = f"{row['Std_Dev']:>8.2f}" if not pd.isna(row['Std_Dev']) else "     N/A"
            print(f"{row['Utility']:<25} | {row['Optimization']:<20} | {row['Avg_Imp']:>10.2f}% | {std_str}")
        
        print("-" * 110)

if __name__ == "__main__":
    process_all_sla_tiers("output/")
    summarize_sla_improvements("output/")