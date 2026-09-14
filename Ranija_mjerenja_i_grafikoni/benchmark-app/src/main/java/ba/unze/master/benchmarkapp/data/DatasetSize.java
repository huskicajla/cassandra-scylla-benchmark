package ba.unze.master.benchmarkapp.data;

public enum DatasetSize {

    ONE_MILLION(
            "1M",
            1_000_000L,
            1
    ),

    FIVE_MILLION(
            "5M",
            5_000_000L,
            5
    ),

    TEN_MILLION(
            "10M",
            10_000_000L,
            10
    ),

    TWENTY_FIVE_MILLION(
            "25M",
            25_000_000L,
            25
    ),

    FIFTY_MILLION(
            "50M",
            50_000_000L,
            50
    );

    private final String label;
    private final long records;
    private final int chunks;

    DatasetSize(
            String label,
            long records,
            int chunks
    ) {
        this.label = label;
        this.records = records;
        this.chunks = chunks;
    }

    public String getLabel() {
        return label;
    }

    public long getRecords() {
        return records;
    }

    public int getChunks() {
        return chunks;
    }

    public static DatasetSize fromLabel(
            String label
    ) {

        for (DatasetSize size : values()) {

            if (
                    size.label.equalsIgnoreCase(label)
            ) {
                return size;
            }
        }

        throw new IllegalArgumentException(
                "Unsupported dataset size: "
                        + label
                        + ". Supported values: 1M, 5M, 10M, 25M, 50M."
        );
    }
}