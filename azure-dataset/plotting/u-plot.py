import re
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path


def parse_output_files(root_dir):
    data = []
    patterns = {
        'utility':           r"Utility:\s+([^,]+)",
        'trace':             r"Trace:\s+([^\s,]+)",
        'aot':               r"useAOT:\s+(true|false|True|False)",
        'snapshot':          r"useSnapshot:\s+(true|false|True|False)",
        'duration':          r"Total duration:\s+(\d+)",
        'footprint':         r"Total footprint:\s+(\d+)",
        'sla_violations':    r"Total SLA violations:\s+(\d+)",
        'sla_violation_cost':r"Total SLA violation cost:\s+([\d\.]+)",
        'opt_cold_starts':   r"Total optimized cold starts:\s+(\d+)",
        'opt_sla_violations':r"Total optimized SLA violations:\s+(\d+)",
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
                    elif val.replace('.', '', 1).isdigit():
                        row[key] = float(val) if '.' in val else int(val)
                    else:
                        row[key] = val

            if row:
                row['folder'] = folder_path

                # Extract TraceID (core parameters) from trace string or filename
                trace_str = row.get('trace') or path.stem
                param_match = re.search(r"d\d+_b\d+_e\d+(?:_[fimu]\d+)*", trace_str)
                row['TraceID'] = param_match.group(0) if param_match else "unknown_trace"

                # Extract configuration name from the folder path.
                # Assumes the configuration directory matches the pattern m<N>_oa<N>_ob<N>_...
                # Walk up the path parts to find it; fall back to the immediate folder name.
                config_name = folder_path.name
                for part in folder_path.parts:
                    if re.match(r"m\d+_oa\d+", part):
                        config_name = part
                        break
                row['config_name'] = config_name

                is_aot  = row.get('aot',      False) or ('.aot.'      in filename)
                is_snap = row.get('snapshot', False) or ('.snapshot.' in filename)

                if is_aot and is_snap: row['Config'] = "AOT+Snapshot"
                elif is_aot:           row['Config'] = "AOT"
                elif is_snap:          row['Config'] = "Snapshot"
                else:                  row['Config'] = "Baseline"

                data.append(row)

    return pd.DataFrame(data)


def generate_cross_config_plots(df_trace, trace_id, save_dir):
    """
    For a given TraceID, plot every metric with:
      - X-axis groups  = utility
      - Bars per group = configuration (config_name), AOT+Snapshot only
    Savings metrics are computed relative to each configuration's own no-opt baseline.
    """
    # ── 1. Keep only AOT+Snapshot rows ──────────────────────────────────────
    df_aot = df_trace[df_trace['Config'] == 'AOT+Snapshot'].copy()
    if df_aot.empty:
        print(f"  No AOT+Snapshot rows for trace {trace_id}. Skipping.")
        return

    cost_cols = ['sla_violation_cost', 'duration', 'footprint', 'sla_violations']
    for c in cost_cols:
        if c in df_aot.columns:
            df_aot[c] = df_aot[c].fillna(0)

    # ── 2. Compute per-configuration savings ─────────────────────────────────
    # For each config_name, locate its own no-opt + Baseline row as the reference.
    savings_rows = []
    for cfg, grp in df_trace.groupby('config_name'):
        bl_mask = (
            grp['utility'].str.contains('no-opt', case=False) &
            (grp['Config'] == 'Baseline')
        )
        if not bl_mask.any():
            # No baseline for this config – savings will be NaN
            ref = {c: float('nan') for c in cost_cols}
        else:
            ref = grp[bl_mask].iloc[0]

        cfg_rows = df_aot[df_aot['config_name'] == cfg].copy()
        cfg_rows['s_saved']             = ref.get('duration',          0) - cfg_rows['duration']
        cfg_rows['footprint_saved']      = ref.get('footprint',         0) - cfg_rows['footprint']
        cfg_rows['violations_prevented'] = ref.get('sla_violations',    0) - cfg_rows['sla_violations']
        cfg_rows['cost_saved']           = ref.get('sla_violation_cost',0) - cfg_rows.get('sla_violation_cost', 0)
        savings_rows.append(cfg_rows)

    df_plot = pd.concat(savings_rows, ignore_index=True)

    # ── 3. Ordering ───────────────────────────────────────────────────────────
    all_utilities  = df_plot['utility'].unique().tolist()
    other_utils    = [u for u in all_utilities if u.lower() != 'no-opt']
    util_order     = other_utils + [u for u in all_utilities if u.lower() == 'no-opt']

    # Sort configurations naturally so legends are consistent
    config_order   = sorted(df_plot['config_name'].unique().tolist())

    # ── 4. Metric definitions ─────────────────────────────────────────────────
    metrics = [
        ('duration',             'Total Duration (s)'),
        ('s_saved',              'Total Latency Saved (s)'),
        ('footprint',            'Total Footprint (MB-s)'),
        ('footprint_saved',      'Total Footprint Saved (MB-s)'),
        ('sla_violations',       'Total SLA Violations'),
        ('violations_prevented', 'SLA Violations Prevented'),
        ('sla_violation_cost',   'SLA Violation Cost (MB-s)'),
        ('cost_saved',           'SLA Cost Saved (MB-s)'),
        ('opt_cold_starts',      'Total Optimized Starts'),
        ('opt_sla_violations',   'Optimized SLA Violations'),
    ]

    # For absolute metrics, keep the no-opt utility group visible so readers
    # can see the unoptimised reference alongside the optimised utilities.
    include_baseline_utilities = {
        'duration', 'footprint', 'sla_violations', 'sla_violation_cost'
    }

    # ── 5. Draw ───────────────────────────────────────────────────────────────
    sns.set_theme(style="whitegrid")
    fig, axes = plt.subplots(5, 2, figsize=(16, 30))
    fig.suptitle(
        f'Cross-Configuration Analysis (AOT+Snapshot) | Trace: {trace_id}',
        fontsize=22, fontweight='bold', y=0.98
    )
    axes = axes.flatten()

    for i, (col, title) in enumerate(metrics):
        ax = axes[i]

        if col not in df_plot.columns:
            ax.set_visible(False)
            continue

        # For savings/prevention metrics, hide the no-opt group (it's always 0)
        if col in include_baseline_utilities:
            plot_data = df_plot.copy()
        else:
            plot_data = df_plot[~df_plot['utility'].str.contains('no-opt', case=False)]

        if plot_data.empty or plot_data[col].isnull().all():
            ax.set_visible(False)
            continue

        current_order = [u for u in util_order if u in plot_data['utility'].unique()]

        sns.barplot(
            data=plot_data,
            x='utility', y=col,
            hue='config_name', hue_order=config_order,
            order=current_order,
            ax=ax,
            palette='tab10',
            errorbar=None,
        )

        # Dynamic Y scaling
        clean = plot_data[col].dropna()
        ymin, ymax = clean.min(), clean.max()
        if ymin == ymax:
            ax.set_ylim(ymin - 1, ymax + 1)
        elif (ymax - ymin) < (ymax * 0.3) and ymin > 0:
            padding = (ymax - ymin) * 0.2
            ax.set_ylim(ymin - padding, ymax + padding)
        else:
            ax.set_ylim(min(0, ymin), ymax * 1.15)

        ax.set_title(title, fontsize=14, fontweight='bold')
        ax.set_xlabel('')
        ax.set_xticklabels(ax.get_xticklabels(), rotation=30, ha='right')

        # Legend only on first subplot; remove from the rest
        if i == 0:
            ax.legend(title='Configuration', loc='upper right',
                      fontsize=8, title_fontsize=9)
        else:
            legend = ax.get_legend()
            if legend:
                legend.remove()

    plt.tight_layout(rect=[0, 0.03, 1, 0.96])
    save_path = save_dir / f"cross_config_{trace_id}.png"
    plt.savefig(save_path, dpi=300)
    plt.close(fig)
    print(f"  Plot saved: {save_path}")


# ── EXECUTION ─────────────────────────────────────────────────────────────────
exclude_list = [
    'cold-start-no-forecast',    'cold-start-imminent',    'cold-start-expected',
    'longest-running-no-forecast','longest-running-imminent','longest-running-expected',
    'random-no-forecast',         'random-imminent',         'random-expected',
]

df_results = parse_output_files('output')

if not df_results.empty:
    # One plot per TraceID, aggregating across all configurations
    for trace_id, trace_df in df_results.groupby('TraceID'):

        # Average repeated runs (same utility × config × config_name)
        df_summary = (
            trace_df
            .groupby(['utility', 'Config', 'config_name', 'folder'], as_index=False)
            .mean(numeric_only=True)
        )

        df_filtered = df_summary[~df_summary['utility'].isin(exclude_list)]

        # Save beside the first folder that contributed to this TraceID
        first_folder = trace_df['folder'].iloc[0].parent
        generate_cross_config_plots(df_filtered, trace_id, first_folder)