"""Parse results/raw/*.txt into results/metrics.csv + results/summary.csv and draw figures/*.png."""
import re
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
RAW, FIG = ROOT / "results" / "raw", ROOT / "figures"

MODES = ["cloud", "clinic", "edge"]
LABEL = {"cloud": "Cloud-only", "clinic": "Clinic fog", "edge": "Patient edge"}
COLOR = {"cloud": "#2a78d6", "clinic": "#eb6834", "edge": "#1baf7a"}   # validated categorical slots 1-3
MARKER = {"cloud": "o", "clinic": "s", "edge": "^"}
INK, INK2, SURFACE, GRID = "#0b0b0b", "#52514e", "#fcfcfb", "#e6e5e1"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "text.color": INK, "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
    "font.size": 10, "axes.titlesize": 11, "axes.titleweight": "bold", "axes.titlelocation": "left",
})


def parse(path):
    text = path.read_text()
    cfg = dict(kv.split("=") for kv in re.search(r"CONFIG (.*)", text).group(1).split())
    row = {"mode": cfg["mode"], "patients": int(cfg["patients"]), "wan_ms": float(cfg["wan_ms"]),
           "interval_ms": float(cfg["interval_ms"]), "run": int(re.search(r"_r(\d+)\.txt", path.name).group(1))}
    loop = re.search(r"\] ---> ([-\d.E+]+)", text)
    row["latency_ms"] = float(loop.group(1)) if loop else None
    energy = {n: float(v) for n, v in re.findall(r"^(\S+) : Energy Consumed = ([-\d.E+]+)", text, re.M)}
    row["energy_cloud"] = energy["cloud"]
    row["energy_gateway"] = energy["clinic-gateway"]
    row["energy_hubs"] = sum(v for n, v in energy.items() if n.startswith("hub-"))
    row["energy_total"] = sum(energy.values())
    row["cost"] = float(re.search(r"Cost of execution in cloud = ([-\d.E+]+)", text).group(1))
    row["network"] = float(re.search(r"Total network usage = ([-\d.E+]+)", text).group(1))
    return row


METRICS = ["latency_ms", "network", "cost", "energy_cloud", "energy_gateway", "energy_hubs", "energy_total"]
KEYS = ["mode", "patients", "wan_ms", "interval_ms"]


def style(ax, title, ylabel, log=False):
    ax.set_title(title)
    ax.set_ylabel(ylabel)
    if log:
        ax.set_yscale("log")


def baseline(summary):
    b = summary[(summary.patients == 3) & (summary.wan_ms == 80) & (summary.interval_ms == 1000)].set_index("mode")
    return b.loc[MODES]


def bars(ax, b, metric, title, ylabel, log=False, fmt="{:,.2f}", scale=1.0):
    x = range(len(MODES))
    vals, errs = b[(metric, "mean")] / scale, b[(metric, "std")].fillna(0) / scale
    ax.bar(x, vals, yerr=errs, color=[COLOR[m] for m in MODES], width=0.6, capsize=3,
           error_kw={"ecolor": INK2, "elinewidth": 1})
    for i, v in enumerate(vals):
        ax.annotate(fmt.format(v), (i, v), ha="center", va="bottom", xytext=(0, 4),
                    textcoords="offset points", fontsize=9)
    ax.set_xticks(list(x), [LABEL[m] for m in MODES])
    ax.grid(axis="x", visible=False)
    style(ax, title, ylabel, log)
    if not log:
        ax.set_ylim(0, vals.max() * 1.18)
    else:
        ax.set_ylim(top=vals.max() * 3)


def fig_baseline(summary):
    b = baseline(summary)
    fig, axes = plt.subplots(1, 4, figsize=(15, 4))
    bars(axes[0], b, "latency_ms", "Alert loop latency", "ms (log scale)", log=True)
    bars(axes[1], b, "network", "Network usage", "latency x bytes / ms", fmt="{:,.0f}")
    bars(axes[2], b, "cost", "Cloud execution cost", "iFogSim cost units (log scale)", log=True, fmt="{:,.0f}")
    bars(axes[3], b, "energy_total", "Total energy, all devices", "iFogSim energy units (x 1e9)", fmt="{:.3f}", scale=1e9)
    fig.suptitle("GlucoFog: cloud-only vs fog placement (3 patients, 1 h simulated, mean of repeated runs)",
                 x=0.01, ha="left", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(FIG / "fig1_baseline.png", dpi=160)


def fig_energy_tiers(summary):
    b = baseline(summary)
    fig, axes = plt.subplots(1, 3, figsize=(12, 4))
    for ax, (key, name) in zip(axes, [("energy_cloud", "Cloud"), ("energy_gateway", "Clinic gateway"),
                                      ("energy_hubs", "Patient hubs (sum)")]):
        bars(ax, b, key, name, "iFogSim energy units (x 1e9)", fmt="{:.3f}", scale=1e9)
    fig.suptitle("Energy by tier: nearly identical across placements (differences under 1.3%)",
                 x=0.01, ha="left", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(FIG / "fig2_energy_by_tier.png", dpi=160)


def sweep(summary, xcol, xlabel, fname, title, logx=False, panels=None):
    panels = panels or [("latency_ms", "Alert loop latency (ms, log)", True),
                        ("network", "Network usage", False), ("cost", "Cloud cost (log)", True)]
    base = {"patients": 3, "wan_ms": 80, "interval_ms": 1000}
    sel = summary
    for k, v in base.items():
        if k != xcol:
            sel = sel[sel[k] == v]
    fig, axes = plt.subplots(1, len(panels), figsize=(5 * len(panels), 4))
    xs = sorted(sel[xcol].unique())
    for i, (ax, (metric, ylabel, log)) in enumerate(zip(axes, panels)):
        for m in MODES:
            d = sel[sel["mode"] == m].sort_values(xcol)
            ax.errorbar(d[xcol], d[(metric, "mean")], yerr=d[(metric, "std")].fillna(0), color=COLOR[m],
                        marker=MARKER[m], markersize=6, linewidth=2, capsize=2,
                        markeredgecolor=SURFACE, markeredgewidth=1.5)
            if i == 0:
                ax.annotate(LABEL[m], (d[xcol].iloc[-1], d[(metric, "mean")].iloc[-1]), xytext=(7, 0),
                            textcoords="offset points", fontsize=8.5, va="center", color=INK2)
        ax.set_xlabel(xlabel)
        if logx:
            ax.set_xscale("log")
        ax.set_xticks(xs, [f"{x:g}" for x in xs])
        ax.minorticks_off()
        style(ax, ylabel, "", log)
        span = (max(xs) - min(xs)) if not logx else None
        ax.set_xlim(min(xs) * (0.8 if logx else 1) - (0 if logx else span * 0.04),
                    max(xs) * (1.9 if logx else 1) + (0 if logx else span * 0.22))
    handles = [Line2D([0], [0], color=COLOR[m], marker=MARKER[m], linewidth=2, markersize=6,
                      markeredgecolor=SURFACE, label=LABEL[m]) for m in MODES]
    fig.legend(handles=handles, loc="upper right", ncol=3, frameon=False, bbox_to_anchor=(0.995, 0.995))
    fig.suptitle(title, x=0.01, ha="left", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(FIG / fname, dpi=160)


def main():
    FIG.mkdir(exist_ok=True)
    df = pd.DataFrame(parse(p) for p in sorted(RAW.glob("*.txt")))
    df.to_csv(ROOT / "results" / "metrics.csv", index=False)
    summary = df.groupby(KEYS)[METRICS].agg(["mean", "std", "count"]).reset_index()
    flat = summary.copy()
    flat.columns = ["_".join(c).strip("_") for c in flat.columns]
    flat.to_csv(ROOT / "results" / "summary.csv", index=False)

    fig_baseline(summary)
    fig_energy_tiers(summary)
    sweep(summary, "patients", "Patients", "fig3_patients.png", "Scaling with the number of patients")
    sweep(summary, "wan_ms", "Clinic-to-cloud latency (ms)", "fig4_wan_latency.png",
          "Effect of WAN latency (the farther the cloud, the larger the fog advantage)")
    sweep(summary, "interval_ms", "CGM reading interval (ms, log)", "fig5_interval.png",
          "Effect of the sensor reporting interval", logx=True)
    print(f"{len(df)} runs -> results/metrics.csv, results/summary.csv, figures/*.png")


if __name__ == "__main__":
    main()
