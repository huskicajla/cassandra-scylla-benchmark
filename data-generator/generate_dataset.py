import csv
import json
import random
import uuid
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path

DEFAULT_TOTAL_RECORDS = 50_000_000
DEFAULT_CHUNK_SIZE = 1_000_000
DEFAULT_DEVICE_COUNT = 10_000
DEFAULT_SEED = 42

DEFAULT_OUTPUT_DIR = (
    Path(__file__).resolve().parent
    / "output"
    / "telemetry-50m"
)

METRIC_TYPES = (
    "temperature",
    "humidity",
    "pressure",
    "voltage",
    "light",
)

BASE_TIME = datetime(
    2025,
    1,
    1,
    0,
    0,
    0,
    tzinfo=timezone.utc,
)

def generate_value(
    metric_type: str,
    rng: random.Random,
) -> float:

    if metric_type == "temperature":
        return round(rng.uniform(15.0, 40.0), 4)

    if metric_type == "humidity":
        return round(rng.uniform(20.0, 90.0), 4)

    if metric_type == "pressure":
        return round(rng.uniform(980.0, 1050.0), 4)

    if metric_type == "voltage":
        return round(rng.uniform(3.0, 5.0), 4)

    if metric_type == "light":
        return round(rng.uniform(0.0, 1000.0), 4)

    raise ValueError(
        f"Unknown metric type: {metric_type}"
    )

def generate_record(
    global_index: int,
    device_count: int,
    rng: random.Random,
):
    """
    Deterministic distribution:

    record 0  -> sensor-00000
    record 1  -> sensor-00001
    ...
    record 9999 -> sensor-09999
    record 10000 -> sensor-00000
    ...

    This guarantees that every 1M-record chunk contains
    records from all sensors.
    """

    device_index = global_index % device_count
    device_event_index = global_index // device_count

    device_id = f"sensor-{device_index:05d}"

    metric_type = METRIC_TYPES[
        global_index % len(METRIC_TYPES)
    ]

    event_time = (
        BASE_TIME
        + timedelta(minutes=device_event_index)
    )

    random_bits = rng.getrandbits(128)
    record_id = uuid.UUID(int=random_bits)

    value = generate_value(
        metric_type,
        rng
    )

    return (
        device_id,
        event_time.isoformat(
            timespec="milliseconds"
        ).replace("+00:00", "Z"),
        str(record_id),
        metric_type,
        value,
    )

def generate_chunk(
    chunk_number: int,
    start_index: int,
    record_count: int,
    total_records: int,
    device_count: int,
    output_dir: Path,
    rng: random.Random,
):
    filename = (
        output_dir
        / f"chunk-{chunk_number:03d}.csv"
    )

    print(
        f"[{chunk_number + 1}] "
        f"Generating {record_count:,} records -> "
        f"{filename.name}"
    )

    with filename.open(
        "w",
        newline="",
        encoding="utf-8",
        buffering=1024 * 1024,
    ) as file:

        writer = csv.writer(
            file,
            lineterminator="\n",
        )

        writer.writerow(
            [
                "device_id",
                "event_time",
                "id",
                "metric_type",
                "value",
            ]
        )

        for offset in range(record_count):

            global_index = (
                start_index + offset
            )

            record = generate_record(
                global_index,
                device_count,
                rng,
            )

            writer.writerow(record)

            if (
                (offset + 1) % 100_000 == 0
                or offset + 1 == record_count
            ):
                current = (
                    start_index
                    + offset
                    + 1
                )

                print(
                    f"    {current:,}/"
                    f"{total_records:,}"
                )

    return filename

def create_manifest(
    output_dir: Path,
    total_records: int,
    chunk_size: int,
    device_count: int,
    seed: int,
    chunk_files,
):

    manifest = {
        "dataset_name": "telemetry-50m",
        "total_records": total_records,
        "chunk_count": len(chunk_files),
        "records_per_chunk": chunk_size,
        "device_count": device_count,
        "seed": seed,
        "metric_types": list(METRIC_TYPES),
        "base_time": BASE_TIME.isoformat(),
        "format": "CSV",
        "columns": [
            "device_id",
            "event_time",
            "id",
            "metric_type",
            "value",
        ],
        "schema": {
            "device_id": "text",
            "event_time": "timestamp",
            "id": "uuid",
            "metric_type": "text",
            "value": "double",
        },
        "chunks": [
            {
                "file": path.name,
                "records": (
                    chunk_size
                    if i < len(chunk_files) - 1
                    else None
                ),
            }
            for i, path in enumerate(chunk_files)
        ],
    }

    for i, path in enumerate(chunk_files):

        if i == len(chunk_files) - 1:
            remaining = (
                total_records
                - chunk_size * i
            )

            manifest["chunks"][i]["records"] = remaining

    manifest_path = output_dir / "manifest.json"

    with manifest_path.open(
        "w",
        encoding="utf-8",
    ) as file:

        json.dump(
            manifest,
            file,
            indent=2,
        )

    return manifest_path

def main():

    parser = argparse.ArgumentParser(
        description=(
            "Generate large synthetic "
            "telemetry dataset."
        )
    )

    parser.add_argument(
        "--records",
        type=int,
        default=DEFAULT_TOTAL_RECORDS,
    )

    parser.add_argument(
        "--chunk-size",
        type=int,
        default=DEFAULT_CHUNK_SIZE,
    )

    parser.add_argument(
        "--devices",
        type=int,
        default=DEFAULT_DEVICE_COUNT,
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=DEFAULT_SEED,
    )

    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
    )

    args = parser.parse_args()

    if args.records <= 0:
        raise ValueError(
            "Number of records must be positive."
        )

    if args.chunk_size <= 0:
        raise ValueError(
            "Chunk size must be positive."
        )

    if args.devices <= 0:
        raise ValueError(
            "Number of devices must be positive."
        )

    args.output.mkdir(
        parents=True,
        exist_ok=True,
    )

    chunk_count = (
        args.records
        + args.chunk_size
        - 1
    ) // args.chunk_size

    print()
    print("=" * 60)
    print("TELEMETRY DATASET GENERATOR")
    print("=" * 60)
    print(
        f"Total records : {args.records:,}"
    )
    print(
        f"Chunk size    : {args.chunk_size:,}"
    )
    print(
        f"Chunks        : {chunk_count}"
    )
    print(
        f"Devices       : {args.devices:,}"
    )
    print(
        f"Seed          : {args.seed}"
    )
    print(
        f"Output        : {args.output}"
    )
    print("=" * 60)
    print()

    rng = random.Random(args.seed)

    chunk_files = []

    for chunk_number in range(
        chunk_count
    ):

        start_index = (
            chunk_number
            * args.chunk_size
        )

        record_count = min(
            args.chunk_size,
            args.records - start_index,
        )

        chunk_path = generate_chunk(
            chunk_number=chunk_number,
            start_index=start_index,
            record_count=record_count,
            total_records=args.records,
            device_count=args.devices,
            output_dir=args.output,
            rng=rng,
        )

        chunk_files.append(chunk_path)

    manifest_path = create_manifest(
        output_dir=args.output,
        total_records=args.records,
        chunk_size=args.chunk_size,
        device_count=args.devices,
        seed=args.seed,
        chunk_files=chunk_files,
    )

    print()
    print("=" * 60)
    print("DATASET GENERATION COMPLETE")
    print("=" * 60)
    print(
        f"Records : {args.records:,}"
    )
    print(
        f"Chunks  : {len(chunk_files)}"
    )
    print(
        f"Manifest: {manifest_path}"
    )
    print("=" * 60)


if __name__ == "__main__":
    main()