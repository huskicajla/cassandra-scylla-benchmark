"""Create reproducible PNG figures from the final Cassandra vs ScyllaDB V2 CSV files.

Only completed MEASUREMENT rows without failed operations are analysed. Pilot data,
console logs and incomplete runs are intentionally outside this program's scope.

Run from the photo-generator directory:
    python generate_report_figures.py
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns


COLORS = {"cassandra": "#315B9D", "scylla": "#00A6A6"}
DATABASE_LABELS = {"cassandra": "Apache Cassandra", "scylla": "ScyllaDB"}
PROFILE_LABELS = {
    "WRITE_HEAVY": "Write-heavy",
    "BALANCED": "Balanced",
    "READ_HEAVY": "Read-heavy",
}
PROFILE_COLORS = {"WRITE_HEAVY": "#E67E22", "BALANCED": "#8E6BBE", "READ_HEAVY": "#00A6A6"}
FIGURE_DPI = 300


def dataset_label(size: int) -> str:
    return f"{size // 1_000_000}M" if size % 1_000_000 == 0 else f"{size:,}".replace(",", " ")


def prepare_measurements(results_dir: Path) -> pd.DataFrame:
    frame = pd.read_csv(results_dir / "benchmark-results-v2.csv")
    numeric_columns = [
        "configured_write_dataset_size",
        "attempted_operations",
        "successful_operations",
        "failed_operations",
        "throughput_ops_sec",
        "average_latency_ms",
        "p95_ms",
        "p99_ms",
    ]
    for column in numeric_columns:
        frame[column] = pd.to_numeric(frame[column], errors="raise")

    valid = frame.loc[
        (frame["run_type"] == "MEASUREMENT")
        & (frame["failed_operations"] == 0)
        & (frame["successful_operations"] == frame["attempted_operations"])
    ].copy()
    valid["database"] = valid["database"].str.lower()
    valid["dataset_size"] = valid["configured_write_dataset_size"].astype(int)
    valid["dataset_label"] = valid["dataset_size"].map(dataset_label)
    valid["database_label"] = valid["database"].map(DATABASE_LABELS)

    expected_sizes = [1_000_000, 5_000_000, 10_000_000]
    if sorted(valid["dataset_size"].unique()) != expected_sizes:
        raise ValueError(f"Očekivane su finalne serije {expected_sizes}, ali CSV sadrži {sorted(valid['dataset_size'].unique())}.")
    if len(valid) != 224:
        raise ValueError(f"Očekivano je 224 validna V2 measurement reda, pronađeno je {len(valid)}.")
    return valid


def finish_figure(fig: plt.Figure, output: Path, caption: str, top: float = 1.0) -> None:
    fig.text(0.01, 0.01, caption, fontsize=8.5, color="#52616B")
    fig.tight_layout(rect=(0, 0.04, 1, top))
    fig.savefig(output, dpi=FIGURE_DPI, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(f"  created {output.name}")


def annotate_bars(axis: plt.Axes, suffix: str = "", decimals: int = 0) -> None:
    for container in axis.containers:
        labels = []
        for bar in container:
            height = bar.get_height()
            if pd.isna(height):
                labels.append("")
            elif decimals == 0:
                labels.append(f"{height:,.0f}{suffix}".replace(",", " "))
            else:
                labels.append(f"{height:.{decimals}f}{suffix}")
        axis.bar_label(container, labels=labels, padding=4, fontsize=8.5, fontweight="bold")


def grouped_barplot(
    data: pd.DataFrame,
    metric: str,
    axis: plt.Axes,
    title: str,
    ylabel: str,
    suffix: str = "",
    decimals: int = 0,
) -> None:
    order = [dataset_label(size) for size in sorted(data["dataset_size"].unique())]
    sns.barplot(
        data=data,
        x="dataset_label",
        y=metric,
        hue="database_label",
        order=order,
        hue_order=["Apache Cassandra", "ScyllaDB"],
        palette={"Apache Cassandra": COLORS["cassandra"], "ScyllaDB": COLORS["scylla"]},
        errorbar="sd",
        capsize=0.10,
        ax=axis,
    )
    axis.set_title(title, loc="left", fontweight="bold")
    axis.set_xlabel("Veličina originalnog dataseta")
    axis.set_ylabel(ylabel)
    axis.legend(title="")
    axis.grid(axis="y", alpha=0.25)
    axis.set_axisbelow(True)
    annotate_bars(axis, suffix=suffix, decimals=decimals)


def plot_concurrent_write(valid: pd.DataFrame, output_dir: Path) -> None:
    subset = valid.loc[valid["operation"] == "CONCURRENT_WRITE"]

    fig, axis = plt.subplots(figsize=(11.5, 6.5))
    grouped_barplot(subset, "throughput_ops_sec", axis, "Concurrent write throughput (H2)", "Operacije u sekundi")
    finish_figure(
        fig,
        output_dir / "01_concurrent_write_throughput.png",
        "Izvor: validni V2 measurement redovi. Prikazan je prosjek; greške su standardne devijacije ponavljanja.",
    )

    fig, axes = plt.subplots(1, 2, figsize=(15, 6.5))
    grouped_barplot(subset, "average_latency_ms", axes[0], "Prosječna latencija (H1)", "Milisekunde", decimals=2)
    grouped_barplot(subset, "p95_ms", axes[1], "P95 latencija (H1)", "Milisekunde", decimals=2)
    finish_figure(
        fig,
        output_dir / "02_concurrent_write_latency.png",
        "Izvor: validni V2 measurement redovi. Niža latencija je bolja; P95 označava granicu za 95% operacija.",
    )


def plot_single_read(valid: pd.DataFrame, output_dir: Path) -> None:
    subset = valid.loc[valid["operation"] == "READ_SINGLE"]
    fig, axes = plt.subplots(1, 2, figsize=(15, 6.5))
    grouped_barplot(subset, "throughput_ops_sec", axes[0], "Single-read throughput (H2)", "Operacije u sekundi")
    grouped_barplot(subset, "average_latency_ms", axes[1], "Single-read prosječna latencija (H1)", "Milisekunde", decimals=2)
    finish_figure(
        fig,
        output_dir / "03_single_read_performance.png",
        "Izvor: validni V2 measurement redovi za READ_SINGLE workload i isti CQL pristupni obrazac.",
    )


def plot_mixed_ratio(valid: pd.DataFrame, output_dir: Path) -> None:
    mixed = valid.loc[valid["operation"] == "MIXED_WORKLOAD"].copy()
    aggregate = (
        mixed.groupby(["dataset_size", "dataset_label", "workload_profile", "database"], as_index=False)["throughput_ops_sec"]
        .mean()
    )
    pivot = aggregate.pivot(index=["dataset_size", "dataset_label", "workload_profile"], columns="database", values="throughput_ops_sec")
    ratio = (pivot["scylla"] / pivot["cassandra"]).rename("scylla_to_cassandra_ratio").reset_index()
    ratio["profile_label"] = ratio["workload_profile"].map(PROFILE_LABELS)

    fig, axis = plt.subplots(figsize=(11.5, 6.5))
    sns.lineplot(
        data=ratio,
        x="dataset_label",
        y="scylla_to_cassandra_ratio",
        hue="profile_label",
        style="profile_label",
        markers=True,
        dashes=False,
        linewidth=2.5,
        markersize=9,
        palette={PROFILE_LABELS[key]: color for key, color in PROFILE_COLORS.items()},
        hue_order=["Write-heavy", "Balanced", "Read-heavy"],
        ax=axis,
    )
    axis.axhline(1, color="#69747C", linewidth=1.2, linestyle="--")
    axis.set_title("ScyllaDB / Cassandra throughput omjer u mixed workload-u (H4)", loc="left", fontweight="bold")
    axis.set_xlabel("Veličina originalnog dataseta")
    axis.set_ylabel("Omjer throughputa")
    axis.grid(axis="y", alpha=0.25)
    axis.legend(title="Workload profil")
    for row in ratio.itertuples():
        axis.annotate(
            f"{row.scylla_to_cassandra_ratio:.2f}x",
            (row.dataset_label, row.scylla_to_cassandra_ratio),
            xytext=(0, 9),
            textcoords="offset points",
            ha="center",
            fontsize=8.5,
        )
    finish_figure(
        fig,
        output_dir / "04_mixed_workload_ratio.png",
        "Izvor: validni V2 measurement redovi. Vrijednost veća od 1 označava prednost ScyllaDB; H4 se procjenjuje kroz write-heavy i read-heavy profil.",
    )


def resource_aggregates(results_dir: Path) -> pd.DataFrame:
    metadata = pd.read_csv(results_dir / "experiment-metadata-v2.csv")
    metadata["database"] = metadata["database"].str.lower()
    metadata["dataset_size"] = pd.to_numeric(metadata["write_dataset_size"], errors="raise").astype(int)
    resources = pd.read_csv(results_dir / "resource-usage-v1.csv")
    resources["container_cpu_percent"] = pd.to_numeric(resources["container_cpu_percent"], errors="raise")
    resources["container_memory_bytes"] = pd.to_numeric(resources["container_memory_bytes"], errors="raise")

    # Sum the three Docker nodes per timestamp, then average timestamps per experiment.
    timestamp_totals = (
        resources.groupby(["experiment_id", "captured_at_utc"], as_index=False)
        .agg(cluster_cpu_percent=("container_cpu_percent", "sum"), cluster_memory_bytes=("container_memory_bytes", "sum"))
    )
    experiment_means = (
        timestamp_totals.groupby("experiment_id", as_index=False)
        .agg(cluster_cpu_percent=("cluster_cpu_percent", "mean"), cluster_memory_mb=("cluster_memory_bytes", lambda value: value.mean() / 1024**2))
    )
    result = experiment_means.merge(metadata[["experiment_id", "database", "dataset_size"]], on="experiment_id", validate="one_to_one")
    result["dataset_label"] = result["dataset_size"].map(dataset_label)
    result["database_label"] = result["database"].map(DATABASE_LABELS)
    return result


def plot_resources(results_dir: Path, output_dir: Path) -> None:
    resources = resource_aggregates(results_dir)
    fig, axes = plt.subplots(1, 2, figsize=(15, 6.5))
    grouped_barplot(resources, "cluster_cpu_percent", axes[0], "Prosječni zbirni Docker CPU", "Docker CPU (%)", suffix="%")
    grouped_barplot(resources, "cluster_memory_mb", axes[1], "Prosječna zbirna RAM memorija", "RAM (MB)")
    # Keep the upper-right legends away from the highest 10M resource labels.
    for axis in axes:
        axis.set_ylim(top=axis.get_ylim()[1] * 1.20)
    finish_figure(
        fig,
        output_dir / "05_resource_usage.png",
        "Izvor: resource-usage-v1.csv. CPU i RAM su opisne metrike i tumače se zajedno s throughputom.",
    )


def prepare_h3_measurements(results_dir: Path) -> pd.DataFrame:
    frame = pd.read_csv(results_dir / "benchmark-results-v2.csv")
    metadata = pd.read_csv(results_dir / "experiment-metadata-v2.csv")
    events = pd.read_csv(results_dir / "experiment-events-v1.csv")
    numeric_columns = [
        "configured_write_dataset_size",
        "cluster_node_count",
        "attempted_operations",
        "successful_operations",
        "failed_operations",
        "throughput_ops_sec",
        "average_latency_ms",
    ]
    for column in numeric_columns:
        frame[column] = pd.to_numeric(frame[column], errors="raise")
    valid = frame.loc[
        (frame["run_type"] == "MEASUREMENT")
        & (frame["failed_operations"] == 0)
        & (frame["successful_operations"] == frame["attempted_operations"])
    ].copy()
    valid["database"] = valid["database"].str.lower()
    valid["dataset_size"] = valid["configured_write_dataset_size"].astype(int)
    valid["dataset_label"] = valid["dataset_size"].map(dataset_label)
    valid["database_label"] = valid["database"].map(DATABASE_LABELS)
    valid["node_label"] = valid["cluster_node_count"].map({1: "1 čvor", 3: "3 čvora"})

    started = events.loc[events["event_type"] == "STARTED", ["experiment_id", "test_series"]]
    valid = valid.merge(
        metadata[["experiment_id", "replication_factor", "cluster_node_count"]],
        on="experiment_id",
        how="inner",
        suffixes=("", "_metadata"),
        validate="many_to_one",
    ).merge(started, on="experiment_id", how="inner", validate="many_to_one")
    h3 = valid.loc[
        valid["test_series"].str.startswith("H3_")
        & (valid["replication_factor"] == 1)
        & valid["cluster_node_count"].isin([1, 3])
    ].copy()
    if len(h3) != 112:
        raise ValueError(f"Očekivano je 112 validnih kontrolisanih H3 measurement redova, pronađeno je {len(h3)}.")
    return h3


def node_barplot(data: pd.DataFrame, metric: str, axis: plt.Axes, title: str, ylabel: str, decimals: int = 0) -> None:
    sns.barplot(
        data=data,
        x="node_label",
        y=metric,
        hue="database_label",
        order=["1 čvor", "3 čvora"],
        hue_order=["Apache Cassandra", "ScyllaDB"],
        palette={"Apache Cassandra": COLORS["cassandra"], "ScyllaDB": COLORS["scylla"]},
        errorbar="sd",
        capsize=0.10,
        ax=axis,
    )
    axis.set_title(title, loc="left", fontweight="bold")
    axis.set_xlabel("Topologija klastera")
    axis.set_ylabel(ylabel)
    axis.legend(title="")
    axis.grid(axis="y", alpha=0.25)
    axis.set_axisbelow(True)
    annotate_bars(axis, decimals=decimals)


def plot_h3(h3: pd.DataFrame, output_dir: Path) -> None:
    three_node = h3.loc[
        (h3["cluster_node_count"] == 3)
        & (h3["operation"].isin(["CONCURRENT_WRITE", "READ_SINGLE"]))
    ]
    fig, axes = plt.subplots(2, 2, figsize=(15, 10.5))
    grouped_barplot(
        three_node.loc[three_node["operation"] == "CONCURRENT_WRITE"],
        "throughput_ops_sec",
        axes[0, 0],
        "H3, 3 čvora (RF=1): concurrent write throughput",
        "Operacije u sekundi",
    )
    grouped_barplot(
        three_node.loc[three_node["operation"] == "READ_SINGLE"],
        "throughput_ops_sec",
        axes[0, 1],
        "H3, 3 čvora (RF=1): single-read throughput",
        "Operacije u sekundi",
    )
    grouped_barplot(
        three_node.loc[three_node["operation"] == "CONCURRENT_WRITE"],
        "average_latency_ms",
        axes[1, 0],
        "H3, 3 čvora (RF=1): concurrent write latencija",
        "Milisekunde",
        decimals=2,
    )
    grouped_barplot(
        three_node.loc[three_node["operation"] == "READ_SINGLE"],
        "average_latency_ms",
        axes[1, 1],
        "H3, 3 čvora (RF=1): single-read latencija",
        "Milisekunde",
        decimals=2,
    )
    finish_figure(
        fig,
        output_dir / "06_h3_3node_performance.png",
        "Izvor: results_preloaded/benchmark-results-v2.csv. Prikazan je kontrolisani H3 profil s tri čvora i RF=1.",
    )

    topology = h3.loc[h3["operation"].isin(["CONCURRENT_WRITE", "READ_SINGLE"])]
    fig, axes = plt.subplots(2, 2, figsize=(15, 10.5))
    fig.suptitle("H3: kontrolisano 1-node / 3-node poređenje (RF=1)", fontsize=17, fontweight="bold")
    node_barplot(
        topology.loc[topology["operation"] == "CONCURRENT_WRITE"],
        "throughput_ops_sec",
        axes[0, 0],
        "Concurrent write throughput",
        "Operacije u sekundi",
    )
    node_barplot(
        topology.loc[topology["operation"] == "READ_SINGLE"],
        "throughput_ops_sec",
        axes[0, 1],
        "Single-read throughput",
        "Operacije u sekundi",
    )
    node_barplot(
        topology.loc[topology["operation"] == "CONCURRENT_WRITE"],
        "average_latency_ms",
        axes[1, 0],
        "Concurrent write prosječna latencija",
        "Milisekunde",
        decimals=2,
    )
    node_barplot(
        topology.loc[topology["operation"] == "READ_SINGLE"],
        "average_latency_ms",
        axes[1, 1],
        "Single-read prosječna latencija",
        "Milisekunde",
        decimals=2,
    )
    finish_figure(
        fig,
        output_dir / "07_h3_controlled_scalability.png",
        "Kontrolisani H3 prikaz. I 1-node i 3-node konfiguracije koriste RF=1, isti dataset i concurrency=16.",
        top=0.94,
    )


def write_manifest(output_dir: Path) -> None:
    manifest = pd.DataFrame(
        [
            ("01_concurrent_write_throughput.png", "H2", "Prosječni throughput paralelnog upisa po veličini dataseta."),
            ("02_concurrent_write_latency.png", "H1", "Prosječna i P95 latencija paralelnog upisa."),
            ("03_single_read_performance.png", "H1 i H2", "Throughput i prosječna latencija READ_SINGLE testa."),
            ("04_mixed_workload_ratio.png", "H4", "Omjer throughputa ScyllaDB/Cassandra u mixed workload profilima."),
            ("05_resource_usage.png", "Dopunska analiza", "Prosječni zbirni Docker CPU i RAM validnih serija."),
            ("06_h3_3node_performance.png", "H3", "Kontrolisani 3-node H3 profil s RF=1."),
            ("07_h3_controlled_scalability.png", "H3", "Kontrolisano 1-node/3-node poređenje s istim RF=1."),
        ],
        columns=["figure", "hypothesis", "what_it_shows"],
    )
    manifest.to_csv(output_dir / "figure-manifest.csv", index=False, encoding="utf-8-sig")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate documentation figures from final V2 benchmark results.")
    parser.add_argument("--results-dir", type=Path, default=Path("results"))
    parser.add_argument("--preloaded-results-dir", type=Path, default=Path("results_preloaded"))
    parser.add_argument("--output-dir", type=Path, default=Path("photos"))
    arguments = parser.parse_args()

    results_dir = arguments.results_dir.resolve()
    preloaded_results_dir = arguments.preloaded_results_dir.resolve()
    output_dir = arguments.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    required = ["benchmark-results-v2.csv", "experiment-metadata-v2.csv", "resource-usage-v1.csv"]
    missing = [file_name for file_name in required if not (results_dir / file_name).exists()]
    if missing:
        raise FileNotFoundError("Nedostaju obavezni fajlovi: " + ", ".join(missing))

    sns.set_theme(style="whitegrid", font="DejaVu Sans", context="notebook")
    valid = prepare_measurements(results_dir)
    print(f"Potvrđeno: {len(valid)} validna V2 measurement reda bez grešaka.")
    print(f"Generišem slike u: {output_dir}")
    plot_concurrent_write(valid, output_dir)
    plot_single_read(valid, output_dir)
    plot_mixed_ratio(valid, output_dir)
    plot_resources(results_dir, output_dir)
    h3 = prepare_h3_measurements(preloaded_results_dir)
    plot_h3(h3, output_dir)
    write_manifest(output_dir)
    print("Završeno: 7 PNG slika i figure-manifest.csv.")


if __name__ == "__main__":
    main()
