import re
import sys
from pathlib import Path
from collections import defaultdict
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D

# --- Plot styling (EuroSys camera-ready requirements) ---
plt.rcParams.update({
    'font.family': 'sans-serif',
    'font.size': 10.0,
    'axes.labelsize': 10.5,
    'axes.titlesize': 11.0,
    'xtick.labelsize': 10.0,
    'ytick.labelsize': 10.0,
    'legend.fontsize': 10.0,
    'legend.title_fontsize': 10.0,
    'pdf.fonttype': 42,
    'ps.fonttype': 42,
    'grid.linestyle': ':',
    'grid.alpha': 0.5,
})

# Internal/file name -> display label. "cold-impact" is our paper's renamed
# label for what the simulator's own output still calls "longest-running".
# was actually changed to match the paper.
INTERNAL_TO_DISPLAY = {
    'cold-start': 'cold-start',
    'cold-impact': 'cold-impact',
    'random': 'random',
    'no-opt': 'no-opt',
    'sla-violation': 'sla-violation',
    'all-rounder': 'all-rounder',
}
DISPLAY_STRATEGIES = [
                      'random',
                      'cold-start',
                      'cold-impact',
                      'sla-violation',
                      'all-rounder'
                    ]

STRATEGY_COLOR = {
    'cold-start':  '#9ecae1',
    'cold-impact': '#a1d99b',
    'random':      '#c6dbef',
    'sla-violation':      '#f15bb5',
    'all-rounder':      '#0077b6',
}

CONDITIONS = ['decision_tree', 'aot_only', 'snap_only']
CONDITION_LABEL = {
    'decision_tree': 'AOT + Snapshot',
    'aot_only':      'AOT Only',
    'snap_only':      'Snapshot Only',
}
CONDITION_HATCH = {
    'decision_tree':  None,
    'aot_only':       'xxx',
    'snap_only':      '///',
}

NO_OPT_COLOR = '#666666'

OUTPUT_FILE_PATTERN = re.compile(
    r"\.csv\.([a-zA-Z0-9\-]+)((?:\.aot)?)((?:\.snapshot)?)\.output$"
)


def parse_filename(filename):
    """
    Extracts (internal_strategy_name, condition) from a filename like
    'd01_b1_e720.csv.cold-start.aot.snapshot.output'.
    condition is one of 'aot_only', 'snap_only', 'decision_tree', or None
    (for baseline/no-opt files with no aot/snapshot suffix).
    """
    match = OUTPUT_FILE_PATTERN.search(filename)
    if not match:
        return None, None

    strategy, aot_flag, snap_flag = match.groups()
    has_aot = bool(aot_flag)
    has_snap = bool(snap_flag)

    if has_aot and has_snap:
        condition = 'decision_tree'
    elif has_aot:
        condition = 'aot_only'
    elif has_snap:
        condition = 'snap_only'
    else:
        condition = None  # baseline, e.g. no-opt

    return strategy, condition


def parse_output_content(content):
    """Parses the key metrics from a single .output file's content."""
    patterns = {
        'duration': r"Total duration:\s+(\d+)",
        'footprint': r"Total footprint:\s+(\d+)",
        'sla_violations': r"Total SLA violations:\s+(\d+)",
    }
    result = {}
    for key, pattern in patterns.items():
        m = re.search(pattern, content, re.IGNORECASE)
        if m:
            result[key] = float(m.group(1))
    return result


def collect_configuration(folder):
    """
    Returns:
      data: {display_strategy: {condition: {metric: value}}}
      no_opt_data: {metric: value}
    for a single configuration folder.
    """
    data = defaultdict(lambda: defaultdict(dict))
    no_opt_data = {}

    for path in sorted(folder.glob("*.output")):
        internal_strategy, condition = parse_filename(path.name)
        if internal_strategy is None:
            print(f"Warning: could not parse filename {path.name}, skipping.", file=sys.stderr)
            continue

        display_strategy = INTERNAL_TO_DISPLAY.get(internal_strategy)
        if display_strategy is None:
            continue  # not a strategy we care about for this plot

        metrics = parse_output_content(path.read_text())
        if not metrics:
            print(f"Warning: no metrics parsed from {path}", file=sys.stderr)
            continue

        # duration: ms -> s
        # footprint: MB-s -> GB-s (single conversion, footprint is already
        # per-second here, not MB-ms like the system-level logs)
        duration_s = metrics.get('duration', 0.0) / 1000.0
        footprint_gbs = metrics.get('footprint', 0.0) / 1000.0
        sla = metrics.get('sla_violations', 0.0)

        parsed = {'duration': duration_s, 'footprint': footprint_gbs, 'sla': sla}

        if display_strategy == 'no-opt' and condition is None:
            no_opt_data = parsed
        elif condition is not None:
            data[display_strategy][condition] = parsed

    return data, no_opt_data


def plot_simulator_technique_comparison(data, no_opt_data, output_path):
    metrics = [
        ('duration', 'Total Invocation Duration (s)'),
        ('footprint', 'Total Invocation Footprint (GB-s)'),
        ('sla', 'Total SLA Violations'),
    ]

    fig, axes = plt.subplots(1, 3, figsize=(11, 3.2))

    bar_width = 0.26
    x = np.arange(len(DISPLAY_STRATEGIES))
    offsets = {'aot_only': -bar_width, 'snap_only': 0, 'decision_tree': bar_width}

    for ax, (metric_key, metric_label) in zip(axes, metrics):
        for condition in CONDITIONS:
            values = [data.get(s, {}).get(condition, {}).get(metric_key, 0.0) for s in DISPLAY_STRATEGIES]
            ax.bar(
                x + offsets[condition], values, bar_width,
                label=CONDITION_LABEL[condition],
                color=[STRATEGY_COLOR[s] for s in DISPLAY_STRATEGIES],
                hatch=CONDITION_HATCH[condition],
                edgecolor='black', linewidth=0.8,
            )

        if metric_key in no_opt_data:
            ax.axhline(no_opt_data[metric_key], color=NO_OPT_COLOR,
                       linestyle='--', linewidth=1.2, zorder=1)

        ax.set_xticks(x)
        ax.set_xticklabels(DISPLAY_STRATEGIES, rotation=30, ha='right', fontsize=10)
        ax.set_ylabel(metric_label)
        ax.set_ylim(bottom=0)
        ax.grid(axis='y')

    legend_handles = [
        Patch(facecolor='white', edgecolor='black', hatch=CONDITION_HATCH[c], label=CONDITION_LABEL[c])
        for c in CONDITIONS
    ] + [Line2D([0], [0], color=NO_OPT_COLOR, linestyle='--', label='no-opt')]

    fig.legend(handles=legend_handles, loc='upper center', ncol=4,
               bbox_to_anchor=(0.5, 1.12), frameon=True)

    fig.tight_layout()
    fig.savefig(output_path, bbox_inches='tight')
    plt.close(fig)
    print(f"Saved: {output_path}")


def find_configuration_folders(root_dir):
    """
    A 'configuration folder' is any directory that directly contains at
    least one *.output file.
    """
    folders = set()
    for path in Path(root_dir).rglob("*.output"):
        folders.add(path.parent)
    return sorted(folders)


def main():
    root_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else (Path.cwd() / "output")

    if not root_dir.exists():
        print(f"Error: directory not found: {root_dir}", file=sys.stderr)
        sys.exit(1)

    folders = find_configuration_folders(root_dir)
    if not folders:
        print(f"No .output files found under {root_dir}", file=sys.stderr)
        sys.exit(1)

    for folder in folders:
        data, no_opt_data = collect_configuration(folder)

        missing = [s for s in DISPLAY_STRATEGIES if s not in data or len(data[s]) < 3]
        if missing or not no_opt_data:
            print(f"Skipping {folder}: incomplete data "
                  f"(missing strategies/conditions: {missing}, no_opt present: {bool(no_opt_data)})",
                  file=sys.stderr)
            continue

        output_path = folder / "simulator_technique_comparison.pdf"
        plot_simulator_technique_comparison(data, no_opt_data, output_path)


if __name__ == "__main__":
    main()


if __name__ == "__main__":
    main()