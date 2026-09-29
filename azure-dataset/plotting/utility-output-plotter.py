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
        'sla_violation_footprint': r"Total SLA violation cost:\s+([\d\.]+)",
        'opt_cold_starts': r"Total optimized cold starts:\s+(\d+)",
        'opt_sla_violations': r"Total optimized SLA violations:\s+(\d+)",
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

def generate_symmetrical_improvement_plots(df, save_dir, trace_id, use_gb=False):
    if df.empty:
        return

    # Isolate the Baseline (no-opt)
    baseline_mask = (df['utility'].str.contains('no-opt', case=False)) & (df['Config'] == 'Baseline')
    if not any(baseline_mask):
        print(f"No baseline for {trace_id} in {save_dir}. Skipping.")
        return
    
    df_plot = df.copy()
    
    cost_cols = ['sla_violation_footprint', 'duration', 'footprint', 'sla_violations']
    for c in cost_cols:
        if c in df_plot.columns:
            df_plot[c] = df_plot[c].fillna(0)
            
    # --- Dynamic Unit Scaling Toggle ---
    # Input data is in MB·s. If use_gb is True, convert to GB·s.
    mem_unit = "GB·s" if use_gb else "MB·s"
    if use_gb:
        for col in ['footprint', 'sla_violation_footprint']:
            if col in df_plot.columns:
                df_plot[col] = df_plot[col] / 1000.0

    baseline_data = df_plot[baseline_mask].iloc[0]

    # Calculate relative gains using standard "Reduction" terminology
    df_plot['duration_reduction'] = baseline_data.get('duration', 0) - df_plot['duration']
    df_plot['footprint_reduction'] = baseline_data.get('footprint', 0) - df_plot['footprint']
    df_plot['sla_violation_reduction'] = baseline_data.get('sla_violations', 0) - df_plot['sla_violations']
    
    base_cost = baseline_data.get('sla_violation_footprint', 0)
    df_plot['sla_footprint_reduction'] = base_cost - df_plot.get('sla_violation_footprint', 0)

    # --- EuroSys Publication Style Setup ---
    plt.rcParams.update({
        'font.family': 'sans-serif',
        'font.size': 10.0,             # Mandatory EuroSys minimum font size: 10pt
        'axes.labelsize': 10.5,        # Axis labels >= 10pt
        'axes.titlesize': 11.0,        # Plot title >= 10pt
        'xtick.labelsize': 10.0,       # Tick labels >= 10pt
        'ytick.labelsize': 10.0,
        'legend.fontsize': 10.0,       # Legend text >= 10pt
        'legend.title_fontsize': 10.0,
        'pdf.fonttype': 42,            # Embed TrueType fonts for camera-ready compliance
        'ps.fonttype': 42,
        'grid.linestyle': ':',
        'grid.alpha': 0.5,
    })

    # Data structure with dynamic unit headers
    metrics = [
        ('duration',                'Total Execution Duration (s)'),
        ('duration_reduction',      'Total Duration Reduction (s)'),
        ('footprint',               f'Total Memory Footprint ({mem_unit})'),
        ('footprint_reduction',     f'Total Footprint Reduction ({mem_unit})'),
        ('sla_violations',          'Total SLA Violations'),
        ('sla_violation_reduction', 'SLA Violation Reduction'),
        ('sla_violation_footprint',      f'SLA Violation Footprint ({mem_unit})'),
        ('sla_footprint_reduction',      f'SLA Footprint Reduction ({mem_unit})'),
        ('opt_cold_starts',         'Total Optimized Cold Starts'),
        ('opt_sla_violations',      'Optimized SLA Violations')
    ]

    include_baseline = ['duration', 'footprint', 'sla_violations', 'sla_violation_footprint']

    config_style = {
        'Baseline':     {'color': '#e0e0e0', 'hatch': '///'},
        'AOT':          {'color': '#9ecae1', 'hatch': '\\\\\\'},
        'Snapshot':     {'color': '#4292c6', 'hatch': '...'},
        'AOT+Snapshot': {'color': '#084594', 'hatch': '+++'}
    }

    fig, axes = plt.subplots(5, 2, figsize=(7.0, 8.5))
    axes = axes.flatten()

    all_utilities = df_plot['utility'].unique().tolist()
    other_utils = [u for u in all_utilities if u.lower() != 'no-opt']
    util_order = other_utils + ([u for u in all_utilities if u.lower() == 'no-opt'])
    
    hue_order = ["Baseline", "AOT", "Snapshot", "AOT+Snapshot"]
    active_configs = [h for h in hue_order if h in df_plot['Config'].unique()]

    df_plot['utility'] = df_plot['utility'].replace('longest-running', 'cold-impact')

    util_order = ['cold-impact' if u == 'longest-running' else u for u in util_order]

    for i, (col, title) in enumerate(metrics):
        ax = axes[i]
        
        if col not in df_plot.columns:
            ax.set_visible(False)
            continue

        plot_data = df_plot if col in include_baseline else df_plot[df_plot['utility'] != 'no-opt']
        
        if plot_data.empty or plot_data[col].isnull().all():
            ax.set_visible(False)
            continue

        current_order = [u for u in util_order if u in plot_data['utility'].unique()]
        
        # Draw Bars
        if col in ['opt_cold_starts']:
            bars = sns.barplot(
                data=plot_data, x='utility', y=col, order=current_order, 
                ax=ax, color='#b0bec5', errorbar=None  # Neutral slate-gray for config-agnostic metric
            )
            for bar in bars.patches:
                bar.set_hatch('xx')  # Neutral cross/grid hatch
                bar.set_edgecolor('black')
                bar.set_linewidth(0.5)
        else:
            palette = [config_style[cfg]['color'] for cfg in active_configs if cfg in config_style]
            bars = sns.barplot(
                data=plot_data, x='utility', y=col, hue='Config', 
                hue_order=active_configs, order=current_order, 
                ax=ax, palette=palette, errorbar=None
            )
            
            n_configs = len(active_configs)
            for j, bar in enumerate(ax.patches):
                cfg_idx = j // len(current_order)
                if cfg_idx < n_configs:
                    cfg_name = active_configs[cfg_idx]
                    bar.set_hatch(config_style[cfg_name]['hatch'])
                    bar.set_edgecolor('black')
                    bar.set_linewidth(0.5)

        # Axis Polish & Clean Spacing
        ax.set_title(title, pad=3, fontweight='bold')
        ax.set_xlabel('')
        ax.set_ylabel('')
        ax.tick_params(axis='x', rotation=20, labelsize=8)
        ax.yaxis.grid(True, linestyle=':', alpha=0.6)
        ax.set_axisbelow(True)
        sns.despine(ax=ax)

        # Dynamic Scaling
        clean_values = plot_data[col].dropna()
        ymin, ymax = clean_values.min(), clean_values.max()

        if ymin == ymax:
            ax.set_ylim(ymin - 1, ymax + 1)
        elif (ymax - ymin) < (ymax * 0.3) and ymin > 0:
            padding = (ymax - ymin) * 0.2
            ax.set_ylim(ymin - padding, ymax + padding)
        else:
            ax.set_ylim(min(0, ymin), ymax * 1.18)

        legend = ax.get_legend()
        if legend: 
            legend.remove()

    # Shared Legend
    handles = []
    labels = []
    for cfg in active_configs:
        patch = plt.Rectangle(
            (0,0), 1, 1, 
            facecolor=config_style[cfg]['color'], 
            edgecolor='black', 
            hatch=config_style[cfg]['hatch'], 
            linewidth=0.5
        )
        handles.append(patch)
        labels.append(cfg)

    fig.legend(
        handles, labels, 
        loc='upper center', 
        bbox_to_anchor=(0.5, 0.99), 
        ncol=len(active_configs), 
        frameon=False, 
        fontsize=10
    )

    plt.tight_layout(rect=[0, 0.0, 1, 0.965])
    
    save_path = Path(save_dir) / f"comparison_{trace_id}_eurosys.pdf"
    plt.savefig(save_path, format='pdf', dpi=300, bbox_inches='tight')
    plt.close(fig)
    print(f"EuroSys-compliant vector plot saved: {save_path}")

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
