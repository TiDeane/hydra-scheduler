import os
import re
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path

import warnings
warnings.filterwarnings("ignore", category=FutureWarning, module="seaborn")

def parse_output_files(root_dir):
    data = []
    patterns = {
        'utility': r"Utility:\s+([^,]+)",
        'trace': r"Trace:\s+([^\s,]+)",
        'aot': r"useAOT:\s+(true|false|True|False)",
        'snapshot': r"useSnapshot:\s+(true|false|True|False)",
        'duration': r"Total duration:\s+(\d+)",
        'footprint': r"Total footprint:\s+(\d+)",
        'sla_violations': r"Total SLA violations:\s+(\d+)",
        #'functions_sla_violated': r"Functions that suffered SLA violations:\s+(\d+)",
        'sla_violation_cost': r"Total SLA violation cost:\s+([\d\.]+)", # New Metric
        'opt_cold_starts': r"Total optimized cold starts:\s+(\d+)",
        'opt_sla_violations': r"Total optimized SLA violations:\s+(\d+)",
        #'opt_functions_sla_violated': r"Optimized functions that suffered SLA violations:\s+(\d+)"
    }

    for path in Path(root_dir).rglob('*.output'):
        if 'forecast-type' in path.parts:
            continue
            
        folder_path = path.parent
        filename = path.name.lower()
        
        with open(path, 'r') as f:
            content = f.read()
            row = {}
            for key, pattern in patterns.items():
                match = re.search(pattern, content, re.IGNORECASE)
                if match:
                    val = match.group(1).strip()
                    if val.lower() in ['true', 'false']:
                        row[key] = val.lower() == 'true'
                    elif val.replace('.','',1).isdigit():
                        row[key] = float(val) if '.' in val else int(val)
                    else:
                        row[key] = val
            
            if row:
                row['folder'] = folder_path
                # Use filename as fallback if 'trace' isn't inside the file
                trace_str = row.get('trace') or path.stem 
                # Extract the core parameters (dX_bY_eZ...) to group results correctly
                param_match = re.search(r"d\d+_b\d+_e\d+(?:_[fimu]\d+)*", trace_str)
                row['TraceID'] = param_match.group(0) if param_match else "unknown_trace"
                
                is_aot = row.get('aot', False) or ('.aot.' in filename)
                is_snap = row.get('snapshot', False) or ('.snapshot.' in filename)
                
                if is_aot and is_snap: row['Config'] = "AOT+Snapshot"
                elif is_aot: row['Config'] = "AOT"
                elif is_snap: row['Config'] = "Snapshot"
                else: row['Config'] = "Baseline"
                
                data.append(row)

    return pd.DataFrame(data)

def generate_symmetrical_improvement_plots(df, save_dir, trace_id):
    if df.empty: return

    # Isolate the Baseline (no-opt)
    baseline_mask = (df['utility'].str.contains('no-opt', case=False)) & (df['Config'] == 'Baseline')
    if not any(baseline_mask):
        print(f"No baseline for {trace_id} in {save_dir}. Skipping.")
        return
    
    baseline_data = df[baseline_mask].iloc[0]
    df_plot = df.copy()
    
    cost_cols = ['sla_violation_cost', 'duration', 'footprint', 'sla_violations']
    for c in cost_cols:
        if c in df_plot.columns:
            df_plot[c] = df_plot[c].fillna(0)
    
    # Calculate relative gains
    df_plot['s_saved'] = baseline_data.get('duration', 0) - df_plot['duration']
    df_plot['footprint_saved'] = baseline_data.get('footprint', 0) - df_plot['footprint']
    df_plot['violations_prevented'] = baseline_data.get('sla_violations', 0) - df_plot['sla_violations']
    
    base_cost = baseline_data.get('sla_violation_cost', 0)
    df_plot['cost_saved'] = base_cost - df_plot.get('sla_violation_cost', 0)

    sns.set_theme(style="whitegrid")
    
    metrics = [
        ('duration', 'Total Duration (s)'),
        ('s_saved', 'Total Latency Saved (s)'),
        ('footprint', 'Total Footprint (MB-s)'),
        ('footprint_saved', 'Total Footprint Saved (MB-s)'),
        ('sla_violations', 'Total SLA Violations'),
        ('violations_prevented', 'SLA Violations Prevented'),
        ('sla_violation_cost', 'SLA Violation Cost (MB-s)'),
        ('cost_saved', 'SLA Cost Saved (MB-s)'),
        #('functions_sla_violated', 'Unique Funcs with Violations'), # should we include this?
        #('opt_functions_sla_violated', 'Optimized Funcs with Violations'), # should we include this?
        ('opt_cold_starts', 'Total Optimized Starts'),
        ('opt_sla_violations', 'Optimized SLA Violations')
    ]

    # Metrics that should display the 'Baseline' bar
    include_baseline = ['duration', 'footprint', 'sla_violations', 'sla_violation_cost']

    # 5x2 for 10 metrics
    fig, axes = plt.subplots(5, 2, figsize=(16, 30)) 
    fig.suptitle(f'Utility Analysis | Trace: {trace_id}\nConfiguration: {save_dir.name}', 
                  fontsize=22, fontweight='bold', y=0.98)
    axes = axes.flatten()

    # --- Utility Ordering ---
    all_utilities = df_plot['utility'].unique().tolist()
    other_utils = [u for u in all_utilities if u.lower() != 'no-opt']
    util_order = other_utils + ([u for u in all_utilities if u.lower() == 'no-opt'])
    
    hue_order = ["Baseline", "AOT", "Snapshot", "AOT+Snapshot"]
    active_configs = [h for h in hue_order if h in df_plot['Config'].unique()]

    for i, (col, title) in enumerate(metrics):
        if col not in df_plot.columns:
            axes[i].set_visible(False)
            continue

        plot_data = df_plot if col in include_baseline else df_plot[df_plot['utility'] != 'no-opt']
        
        if plot_data.empty or plot_data[col].isnull().all():
            axes[i].set_visible(False)
            continue

        current_order = [u for u in util_order if u in plot_data['utility'].unique()]
        
        if col in ['opt_cold_starts']:
            sns.barplot(data=plot_data, x='utility', y=col, order=current_order, 
                        ax=axes[i], color='#5da5da', errorbar=None)
        else:
            sns.barplot(data=plot_data, x='utility', y=col, hue='Config', 
                        hue_order=active_configs, order=current_order, 
                        ax=axes[i], palette='viridis', errorbar=None)
        
        # Dynamic Scaling
        clean_values = plot_data[col].dropna()
        ymin, ymax = clean_values.min(), clean_values.max()

        if ymin == ymax:
            axes[i].set_ylim(ymin - 1, ymax + 1)
        elif (ymax - ymin) < (ymax * 0.3) and ymin > 0:
            padding = (ymax - ymin) * 0.2
            axes[i].set_ylim(ymin - padding, ymax + padding)
        else:
            axes[i].set_ylim(min(0, ymin), ymax * 1.15)
        
        axes[i].set_title(title, fontsize=14, fontweight='bold')
        axes[i].set_xticklabels(axes[i].get_xticklabels(), rotation=30, ha='right')
        
        # Legend handling (only on the first plot)
        if i == 0:
            axes[i].legend(title='Config', loc='upper right')
        else:
            legend = axes[i].get_legend()
            if legend: legend.remove()

    plt.tight_layout(rect=[0, 0.03, 1, 0.96])
    save_path = save_dir / f"comparison_{trace_id}.png"
    plt.savefig(save_path, dpi=300)
    plt.close(fig)
    print(f"Plot saved in: {save_path}")

# --- EXECUTION ---

# note: always include no-opt
#include_list = ['cold-start', 'cold-start-no-forecast', 'cold-start-imminent', 'no-opt']
#include_list = ['longest-running', 'longest-running-no-forecast', 'longest-running-imminent', 'no-opt']
#include_list = ['random', 'random-no-forecast', 'random-imminent', 'no-opt']
exclude_list = ['cold-start-no-forecast', 'cold-start-imminent', 'cold-start-expected',
                'longest-running-no-forecast', 'longest-running-imminent', 'longest-running-expected',
                'random-no-forecast', 'random-imminent', 'random-expected']

df_results = parse_output_files('output')

if not df_results.empty:
    for (folder, trace_id), group_df in df_results.groupby(['folder', 'TraceID']):
        df_summary = group_df.groupby(['utility', 'Config']).mean(numeric_only=True).reset_index()

        #df_filtered = df_summary[df_summary['utility'].isin(include_list)]
        df_filtered = df_summary[~df_summary['utility'].isin(exclude_list)]
        
        generate_symmetrical_improvement_plots(df_filtered, folder, trace_id)
