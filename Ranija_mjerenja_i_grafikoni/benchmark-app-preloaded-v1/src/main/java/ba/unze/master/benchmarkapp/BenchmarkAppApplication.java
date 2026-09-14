package ba.unze.master.benchmarkapp;

import ba.unze.master.benchmarkapp.benchmark.BenchmarkExperimentRunner;
import ba.unze.master.benchmarkapp.benchmark.ExperimentEventWriter;
import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.config.DatasetProperties;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import ba.unze.master.benchmarkapp.database.DatabaseSchema;
import ba.unze.master.benchmarkapp.database.TelemetryRepository;
import ba.unze.master.benchmarkapp.preloaded.PreloadedBenchmarkExperimentRunner;
import ba.unze.master.benchmarkapp.preloaded.PreloadedBenchmarkProperties;
import ba.unze.master.benchmarkapp.preloaded.PreloadedDatabaseSchema;
import ba.unze.master.benchmarkapp.preloaded.PreloadedTelemetryRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        DatabaseProperties.class,
        DatasetProperties.class,
        BenchmarkProperties.class,
        PreloadedBenchmarkProperties.class
})
public class BenchmarkAppApplication {

    public static void main(String[] args) {

        var context =
                SpringApplication.run(
                        BenchmarkAppApplication.class,
                        args
                );

        boolean executeOnStartup = context.getEnvironment().getProperty(
                "benchmark.execute-on-startup",
                Boolean.class,
                true
        );

        if (!executeOnStartup) {
            System.out.println("Benchmark dashboard mode started. No experiment will run.");
            return;
        }

        DatabaseClient databaseClient =
                context.getBean(
                        DatabaseClient.class
                );

        ExperimentEventWriter eventWriter =
                context.getBean(
                        ExperimentEventWriter.class
                );

        DatasetProperties datasetProperties =
                context.getBean(
                        DatasetProperties.class
                );

        BenchmarkProperties benchmarkProperties =
                context.getBean(
                        BenchmarkProperties.class
                );

        DatabaseProperties databaseProperties =
                context.getBean(
                        DatabaseProperties.class
                );

        PreloadedBenchmarkProperties preloadedProperties =
                context.getBean(
                        PreloadedBenchmarkProperties.class
                );

        try {
        databaseClient.connect();

        if (preloadedProperties.isEnabled()) {
            PreloadedDatabaseSchema preloadedSchema = context.getBean(
                    PreloadedDatabaseSchema.class
            );
            PreloadedTelemetryRepository preloadedRepository = context.getBean(
                    PreloadedTelemetryRepository.class
            );
            PreloadedBenchmarkExperimentRunner preloadedRunner = context.getBean(
                    PreloadedBenchmarkExperimentRunner.class
            );

            preloadedSchema.createSchema();
            preloadedRepository.prepareStatements();
            preloadedRunner.runAllExperiments();
            System.out.println("All preloaded benchmark workloads finished.");
            return;
        }

        DatabaseSchema schema = context.getBean(DatabaseSchema.class);
        TelemetryRepository repository = context.getBean(TelemetryRepository.class);
        BenchmarkExperimentRunner experimentRunner = context.getBean(BenchmarkExperimentRunner.class);

        schema.createSchema();
        repository.prepareStatements();

        System.out.println();
        System.out.println("========================================");
        System.out.println("BENCHMARK CONFIGURATION");
        System.out.println("========================================");
        System.out.println(
                "Database: "
                        + databaseProperties.getType()
        );
        System.out.println(
                "Write dataset size: "
                        + datasetProperties.getSize()
        );
        System.out.println(
                "Read dataset size: "
                        + benchmarkProperties.getReadDatasetSize()
        );
        System.out.println(
                "Concurrency: "
                        + benchmarkProperties.getConcurrency()
        );
        System.out.println(
                "Consistency level: "
                        + databaseProperties.getConsistencyLevel()
        );
        System.out.println(
                "Replication factor: "
                        + databaseProperties.getReplicationFactor()
        );
        System.out.println(
                "Warm-up runs: "
                        + benchmarkProperties.getWarmupRuns()
        );
        System.out.println(
                "Measurement runs: "
                        + benchmarkProperties.getMeasurementRuns()
        );
        System.out.println(
                "Dataset path: "
                        + datasetProperties.getPath()
        );
        System.out.println(
                "Test series: "
                        + benchmarkProperties.getTestSeries()
        );
        System.out.println("========================================");

        experimentRunner.runAllExperiments();

        System.out.println();
        System.out.println("========================================");
        System.out.println("ALL BENCHMARK EXPERIMENTS FINISHED");
        System.out.println("========================================");

        } catch (RuntimeException | Error failure) {
            eventWriter.writeSetupAborted(
                    databaseProperties.getType().trim().toLowerCase(),
                    benchmarkProperties.getTestSeries(),
                    connectedNodeCount(databaseClient),
                    databaseProperties.getConsistencyLevel().trim().toUpperCase(),
                    databaseProperties.getReplicationFactor(),
                    failure
            );
            throw failure;

        } finally {
            databaseClient.close();
            context.close();
        }
    }

    private static int connectedNodeCount(DatabaseClient databaseClient) {
        if (!databaseClient.isConnected()) {
            return 0;
        }

        return databaseClient.getSession().getMetadata().getNodes().size();
    }
}
