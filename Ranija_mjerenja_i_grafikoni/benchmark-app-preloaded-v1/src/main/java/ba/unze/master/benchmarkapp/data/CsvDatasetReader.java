package ba.unze.master.benchmarkapp.data;

import ba.unze.master.benchmarkapp.config.DatasetProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.Spliterators;

@Component
public class CsvDatasetReader {

    private final DatasetProperties datasetProperties;

    public CsvDatasetReader(
            DatasetProperties datasetProperties
    ) {
        this.datasetProperties = datasetProperties;
    }

    public Stream<TelemetryRecord> stream() {

        return stream(
                datasetProperties.getSize()
        );
    }

    public Stream<TelemetryRecord> stream(
            long recordLimit
    ) {

        if (recordLimit <= 0) {

            throw new IllegalArgumentException(
                    "Record limit must be greater than zero."
            );
        }

        DatasetIterator iterator =
                new DatasetIterator(
                        Path.of(
                                datasetProperties.getPath()
                        ),
                        recordLimit
                );

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        iterator,
                        0
                ),
                false
        ).onClose(iterator::close);
    }

    public List<TelemetryRecord> readRecords(
            int limit
    ) {

        if (limit <= 0) {

            throw new IllegalArgumentException(
                    "Limit must be greater than zero."
            );
        }

        try (
                Stream<TelemetryRecord> stream =
                        stream(limit)
        ) {

            return stream.toList();

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to read "
                            + limit
                            + " records from dataset.",
                    e
            );
        }
    }

    public long getAvailableRecordCount() {

        return datasetProperties.getSize();
    }

    private static final class DatasetIterator
            implements Iterator<TelemetryRecord> {

        private final Path datasetDirectory;
        private final long recordLimit;

        private BufferedReader reader;

        private int currentChunk;

        private long recordsRead;

        private TelemetryRecord nextRecord;

        private boolean initialized;
        private boolean finished;

        private DatasetIterator(
                Path datasetDirectory,
                long recordLimit
        ) {

            this.datasetDirectory =
                    datasetDirectory;

            this.recordLimit =
                    recordLimit;
        }

        @Override
        public boolean hasNext() {

            if (finished) {
                return false;
            }

            if (!initialized) {
                initialize();
            }

            if (nextRecord != null) {
                return true;
            }

            if (recordsRead >= recordLimit) {

                close();

                return false;
            }

            readNext();

            return nextRecord != null;
        }

        @Override
        public TelemetryRecord next() {

            if (!hasNext()) {

                throw new NoSuchElementException();
            }

            TelemetryRecord result =
                    nextRecord;

            nextRecord = null;

            return result;
        }

        private void initialize() {

            initialized = true;

            if (!Files.exists(datasetDirectory)) {

                throw new IllegalStateException(
                        "Dataset directory does not exist: "
                                + datasetDirectory
                                .toAbsolutePath()
                );
            }

            if (!Files.isDirectory(
                    datasetDirectory
            )) {

                throw new IllegalStateException(
                        "Dataset path is not a directory: "
                                + datasetDirectory
                                .toAbsolutePath()
                );
            }

            openNextChunk();
        }

        private void readNext() {

            while (!finished) {

                if (reader == null) {

                    openNextChunk();

                    if (finished) {
                        return;
                    }
                }

                try {

                    String line;

                    while (
                            (line =
                                    reader.readLine())
                                    != null
                    ) {

                        if (line.isBlank()) {
                            continue;
                        }

                        nextRecord =
                                parseLine(line);

                        recordsRead++;

                        return;
                    }

                    closeCurrentReader();

                    currentChunk++;

                } catch (IOException e) {

                    close();

                    throw new IllegalStateException(
                            "Error reading dataset chunk.",
                            e
                    );
                }
            }
        }

        private void openNextChunk() {

            if (
                    recordsRead >= recordLimit
            ) {

                close();

                return;
            }

            Path chunk =
                    datasetDirectory.resolve(
                            String.format(
                                    "chunk-%03d.csv",
                                    currentChunk
                            )
                    );

            if (!Files.exists(chunk)) {

                close();

                return;
            }

            try {

                reader =
                        Files.newBufferedReader(
                                chunk
                        );

                String header =
                        reader.readLine();

                if (header == null) {

                    closeCurrentReader();

                    currentChunk++;

                    openNextChunk();
                }

            } catch (IOException e) {

                close();

                throw new IllegalStateException(
                        "Unable to open dataset chunk: "
                                + chunk.toAbsolutePath(),
                        e
                );
            }
        }

        private TelemetryRecord parseLine(
                String line
        ) {

            String[] fields =
                    line.split(",", -1);

            if (fields.length != 5) {

                throw new IllegalStateException(
                        "Invalid CSV record. Expected 5 fields but found "
                                + fields.length
                                + ": "
                                + line
                );
            }

            try {

                return new TelemetryRecord(
                        fields[0],
                        Instant.parse(fields[1]),
                        UUID.fromString(fields[2]),
                        fields[3],
                        Double.parseDouble(fields[4])
                );

            } catch (Exception e) {

                throw new IllegalStateException(
                        "Unable to parse CSV record: "
                                + line,
                        e
                );
            }
        }

        private void closeCurrentReader() {

            if (reader == null) {
                return;
            }

            try {

                reader.close();

            } catch (IOException ignored) {
            }

            reader = null;
        }

        private void close() {

            if (finished) {
                return;
            }

            finished = true;

            closeCurrentReader();
        }
    }
}