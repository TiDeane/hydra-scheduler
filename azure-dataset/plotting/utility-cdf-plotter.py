import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path

import warnings
warnings.filterwarnings("ignore", category=FutureWarning, module="seaborn")

sns.set_theme(style="whitegrid")

_CONFIG_KEYS = {"aot.snapshot": "AOT+Snapshot",
                "snapshot": "Snapshot",
                "aot": "AOT"
                }
_UTILITIES   = ["cold-impact", "cold-start", "random", "sla-violation", "all-rounder", "no-opt"]

def _identify_config(filename: str) -> str:
    return next((v for k, v in _CONFIG_KEYS.items() if k in filename), "Baseline")

def _identify_label(filename: str) -> str:
    utility = next((u for u in _UTILITIES if u in filename), "unknown")
    return "no-opt (Baseline)" if utility == "no-opt" else utility

# Generates one CDF plot for each trace and utility configuration, with lines for ALL optimizations combinations
def generate_multi_config_cdf(base_output_path, trace_id):
    base_path = Path(base_output_path)

    for folder in base_path.iterdir():
        if not folder.is_dir():
            continue

        all_data = []
        for path in folder.glob(f"*{trace_id}*_cdf_data.csv"):
            filename = path.name.lower()

            df = pd.read_csv(path)
            if df.empty:
                continue

            if 'invocationsBeforeOpt' in df.columns:
                df['total_invocations'] = df['invocationsBeforeOpt'] + df['invocationsAfterOpt']
                df['sla_violations'] = df['slaViolationsBeforeOpt'] + df['slaViolationsAfterOpt']
                df['violation_rate'] = df['sla_violations'] / df['total_invocations']
            elif 'total_invocations' in df.columns and 'sla_violations' in df.columns:
                pass 
            else:
                print(f"⚠️ Warning: Skipping {path.name}. Unrecognized column headers: {list(df.columns)}")
                continue
            
            df = df[df['total_invocations'] > 0].copy()
            if df.empty:
                continue
                
            df['violation_rate'] = df['sla_violations'] / df['total_invocations']

            config = _identify_config(filename)
            label  = _identify_label(filename)  # "Baseline (No-Opt)" or "Cold Start", etc.

            # Append config to non-baseline labels to disambiguate lines
            if label != "Baseline (No-Opt)":
                label = f"{label} ({config})"

            df = df.sort_values("violation_rate", ignore_index=True)
            df["percentile_functions"] = (df.index + 1) / len(df) * 100
            df["Label"] = label
            all_data.append(df)

        if not all_data:
            continue

        full_cdf_df = pd.concat(all_data, ignore_index=True)

        violating = full_cdf_df[full_cdf_df["violation_rate"] > 0]
        y_start   = max(0, violating["percentile_functions"].min() - 5) if not violating.empty else 0

        fig, ax = plt.subplots(figsize=(12, 7))

        sns.lineplot(
            data=full_cdf_df,
            x="violation_rate",
            y="percentile_functions",
            hue="Label",
            linewidth=2.5,
            palette="tab10",
            ax=ax,
        )

        ax.set_title(
            f"SLA Violation CDF | Configuration: {folder.name}\nTrace: {trace_id}",
            fontsize=15, fontweight="bold",
        )
        ax.set_xlabel("SLA Violation Rate", fontsize=12)
        ax.set_ylabel("Cumulative % of Active Functions", fontsize=12)
        ax.set_xlim(-0.02, 1.05)
        ax.set_ylim(y_start, 100.5)
        ax.legend(title="Utility & Strategy", bbox_to_anchor=(1.05, 1), loc="upper left")

        fig.tight_layout()

        save_path = folder / f"cdf_{trace_id}.png"
        fig.savefig(save_path, dpi=300)
        plt.close(fig)
        print(f"CDF saved in: {save_path}")

# Generates separate CDF plots for each optimization (AOT, Snapshot, etc.)
def generate_separated_cdf_plots(base_output_path, trace_id):
    base_path = Path(base_output_path)

    # --- EuroSys Publication Style Setup ---
    plt.rcParams.update({
        'font.family': 'sans-serif',
        'font.size': 9.5,
        'axes.labelsize': 9.5,
        'xtick.labelsize': 8.5,
        'ytick.labelsize': 8.5,
        'legend.fontsize': 8.5,
        'grid.linestyle': ':',
        'grid.alpha': 0.6,
    })

    # High-contrast, publication & grayscale-ready styles
    # Each entry differs in: Color Hue, Grayscale Darkness, and Dash Pattern
    utility_style = {
        'baseline':          {'color': '#000000', 'ls': '-',               'marker': None, 'me': 0.10, 'lw': 1.8, 'ms': 0},
        'no-opt (Baseline)':{'color': '#000000', 'ls': '-',               'marker': None, 'me': 0.10, 'lw': 1.8, 'ms': 0},
        'random':          {'color': '#d9a100', 'ls': (0, (8, 5)),       'marker': 'x',  'me': 0.12, 'lw': 1.8, 'ms': 6}, # Gold/Yellow + Wide Split + 'X's
        'cold-start':      {'color': '#00b4d8', 'ls': ':',               'marker': '|',  'me': 0.08, 'lw': 1.8, 'ms': 8}, # Cyan + Dotted + Vertical Bars
        'cold-impact': {'color': '#9b5de5', 'ls': '-.',              'marker': 'o',  'me': 0.12, 'lw': 1.8, 'ms': 5}, # Purple + Dash-Dot + Circles
        'sla-violation':   {'color': '#f15bb5', 'ls': (0, (4, 3)),       'marker': '^',  'me': 0.10, 'lw': 1.8, 'ms': 5}, # Pink/Red + Dashed + Triangles
        'all-rounder':     {'color': '#0077b6', 'ls': '-',               'marker': 's',  'me': 0.12, 'lw': 1.8, 'ms': 5}, # Royal Blue + Continuous + Squares
        'warm':            {'color': '#00f5d4', 'ls': (0, (5, 2, 1, 2)), 'marker': '+',  'me': 0.10, 'lw': 1.8, 'ms': 6}  # Lime/Teal + Dash-Dot-Dot + Pluses
    }
    
    fallback_styles = [
        {'color': '#333333', 'ls': '--', 'lw': 1.6, 'marker': None, 'me': 0.10, 'lw': 1.8, 'ms': 0},
        {'color': '#666666', 'ls': '-.', 'lw': 1.6, 'marker': None, 'me': 0.10, 'lw': 1.8, 'ms': 0},
        {'color': '#999999', 'ls': ':',  'lw': 2.0, 'marker': None, 'me': 0.10, 'lw': 1.8, 'ms': 0},
    ]

    for folder in base_path.iterdir():
        if not folder.is_dir():
            continue

        all_data = []
        for path in folder.glob(f"*{trace_id}*_cdf_data.csv"):
            filename = path.name.lower()

            df = pd.read_csv(path)
            if df.empty:
                continue
            
            required_cols = ['invocationsBeforeOpt', 'slaViolationsBeforeOpt', 'invocationsAfterOpt', 'slaViolationsAfterOpt']
            if not all(col in df.columns for col in required_cols):
                print(f"Skipping {path}: Missing required columns.")
                continue

            df['total_invocations'] = df['invocationsBeforeOpt'] + df['invocationsAfterOpt']
            df['sla_violations'] = df['slaViolationsBeforeOpt'] + df['slaViolationsAfterOpt']
            
            df = df[df['total_invocations'] > 0].copy()
            if df.empty:
                continue
                
            df['violation_rate'] = df['sla_violations'] / df['total_invocations']

            df = df.sort_values("violation_rate", ignore_index=True)
            df["percentile_functions"] = (df.index + 1) / len(df) * 100
            df["UtilityLabel"] = _identify_label(filename)
            df["ConfigGroup"]  = _identify_config(filename)
            all_data.append(df)

        if not all_data:
            continue

        full_df      = pd.concat(all_data, ignore_index=True)
        baseline_df  = full_df[full_df["ConfigGroup"] == "Baseline"]
        opt_groups   = [g for g in full_df["ConfigGroup"].unique() if g != "Baseline"]

        for opt in opt_groups:
            plot_df = pd.concat(
                [baseline_df, full_df[full_df["ConfigGroup"] == opt]],
                ignore_index=True,
            )

            violating = plot_df[plot_df["violation_rate"] > 0]
            y_start   = max(0, violating["percentile_functions"].min() - 5) if not violating.empty else 0

            fig, ax = plt.subplots(figsize=(4.8, 3.5))

            unique_labels = sorted(plot_df['UtilityLabel'].unique())
            
            # Ensure Baseline is listed first in legend ordering
            baseline_keys = [l for l in unique_labels if "baseline" in l.lower()]
            for bk in baseline_keys:
                unique_labels.remove(bk)
            unique_labels = baseline_keys + unique_labels

            fallback_idx = 0
            for label in unique_labels:
                df_sub = plot_df[plot_df['UtilityLabel'] == label]
                
                # Robust normalization (handles spaces and underscores)
                lookup_key = label.lower().strip().replace(" ", "-").replace("_", "-")
                
                if lookup_key in utility_style:
                    fmt = utility_style[lookup_key]
                else:
                    fmt = fallback_styles[fallback_idx % len(fallback_styles)]
                    fallback_idx += 1
                    
                ax.plot(
                    df_sub['violation_rate'], 
                    df_sub['percentile_functions'], 
                    label=label,
                    color=fmt['color'],
                    linestyle=fmt['ls'],
                    linewidth=fmt['lw'],
                    marker=fmt['marker'],
                    markersize=fmt['ms'],
                    markevery=fmt['me'],
                )

            ax.set_xlabel("SLA Violation Rate", fontweight="bold")
            ax.set_ylabel("Cumulative % of Active Functions", fontweight="bold")
            
            ax.set_xlim(-0.02, 1.02)
            ax.set_ylim(y_start, 102)
            
            ax.grid(True, linestyle=":", alpha=0.6)
            ax.set_axisbelow(True)

            ax.spines['top'].set_visible(False)
            ax.spines['right'].set_visible(False)

            ax.legend(
                title="Strategy", 
                title_fontsize=8.5,
                loc="upper left", 
                framealpha=0.9,
                edgecolor='gray'
            )

            fig.tight_layout()

            safe_name = opt.lower().replace("+", "-").replace(".", "-")
            save_path = (folder / f"cdf_{trace_id}_{safe_name}").with_suffix('.pdf')
            fig.savefig(save_path, format='pdf', dpi=300, bbox_inches='tight')
            plt.close(fig)
            print(f"Saved EuroSys-compliant CDF: {save_path}")


# --- EXECUTION ---
for trace in ['d01_b1_e720', 'd01_b1_e1440']:
    generate_separated_cdf_plots('output/', trace)
    #generate_multi_config_cdf('output/', trace)
