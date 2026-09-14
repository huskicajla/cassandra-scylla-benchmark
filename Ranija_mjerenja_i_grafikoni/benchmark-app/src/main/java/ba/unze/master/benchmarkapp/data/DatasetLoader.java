package ba.unze.master.benchmarkapp.data;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DatasetLoader {

    private final CsvDatasetReader reader;
    public DatasetLoader(
            CsvDatasetReader reader
    ) {
        this.reader = reader;
    }

    public List<TelemetryRecord> loadForRead(
            long requestedSize
    ) {

        if (requestedSize <= 0) {

            throw new IllegalArgumentException(
                    "Dataset size must be greater than zero."
            );
        }

        if (requestedSize > Integer.MAX_VALUE) {

            throw new IllegalArgumentException(
                    "This in-memory read benchmark cannot load more than "
                            + Integer.MAX_VALUE
                            + " records."
            );
        }

        return loadForRead(
                (int) requestedSize
        );
    }

    public List<TelemetryRecord> loadForRead(
            int requestedSize
    ) {

        if (requestedSize <= 0) {

            throw new IllegalArgumentException(
                    "Dataset size must be greater than zero."
            );
        }

        List<TelemetryRecord> records =
                reader.readRecords(
                        requestedSize
                );

        return records;
    }
}
