package ogc.rs.processes.collectionAppending;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import ogc.rs.apiserver.util.ProcessException;
import ogc.rs.processes.ProcessService;
import ogc.rs.processes.util.Status;
import ogc.rs.processes.util.UtilClass;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static ogc.rs.processes.collectionAppending.Constants.*;

public class CollectionAppendingProcess implements ProcessService {

    private static final Logger LOGGER = LogManager.getLogger(CollectionAppendingProcess.class);
    private final PgPool pgPool;
    private final UtilClass utilClass;
    private String awsEndPoint;
    private String accessKey;
    private String secretKey;
    private String awsBucketUrl;
    private String databaseName;
    private String databaseHost;
    private String databasePassword;
    private String databaseUser;
    private int databasePort;
    private Vertx vertx;
    private final boolean VERTX_EXECUTE_BLOCKING_IN_ORDER = false;

    public CollectionAppendingProcess(PgPool pgPool, JsonObject config, Vertx vertx) {

        this.pgPool = pgPool;
        this.utilClass = new UtilClass(pgPool);
        this.vertx = vertx;
        initializeConfig(config);

    }

    private void initializeConfig(JsonObject config) {

        this.awsEndPoint = config.getString("awsEndPoint");
        this.accessKey = config.getString("awsAccessKey");
        this.secretKey = config.getString("awsSecretKey");
        this.awsBucketUrl = config.getString("s3BucketUrl");
        this.databaseName = config.getString("databaseName");
        this.databaseHost = config.getString("databaseHost");
        this.databasePassword = config.getString("databasePassword");
        this.databaseUser = config.getString("databaseUser");
        this.databasePort = config.getInteger("databasePort");

    }

    @Override
    public Future<JsonObject> execute(JsonObject requestInput) {

        Promise<JsonObject> objectPromise = Promise.promise();

        requestInput.put("progress", calculateProgress(1, 5));
        LOGGER.debug("AWS BUCKET URL is: {}" , awsBucketUrl);

        String tableID = requestInput.getString("resourceId");
        requestInput.put("collectionsDetailsTableId", tableID);

        utilClass.updateJobTableStatus(requestInput, Status.RUNNING, STARTING_APPEND_PROCESS_MESSAGE)
                .compose(progressUpdateHandler -> checkIfCollectionPresent(requestInput))
                .compose(collectionCheckHandler -> utilClass.updateJobTableProgress(
                        requestInput.put("progress", calculateProgress(2, 5)).put("message", COLLECTION_EXISTS_MESSAGE)))
                .compose(progressUpdateHandler -> checkSchema(requestInput))
                .compose(schemaCheckHandler -> utilClass.updateJobTableProgress(
                        requestInput.put("progress", calculateProgress(3, 5)).put("message", SCHEMA_VALIDATION_SUCCESS_MESSAGE)))
                .compose(progressUpdateHandler -> appendDataToTempTable(requestInput))
                .compose(appendHandler -> utilClass.updateJobTableProgress(
                        requestInput.put("progress",calculateProgress(4,5)).put("message",APPEND_PROCESS_MESSAGE)))
                .compose(progressUpdateHandler -> mergeTempTableToCollection(requestInput))
                .compose(mergeHandler -> utilClass.updateJobTableStatus(requestInput, Status.SUCCESSFUL, APPEND_PROCESS_COMPLETED_MESSAGE))
                .onSuccess(successHandler -> {
                    LOGGER.debug("COLLECTION APPENDING DONE");
                    deleteTempTable(requestInput);
                    objectPromise.complete();
                }).onFailure(failureHandler -> {
                    LOGGER.error("COLLECTION APPENDING FAILED: {} " , failureHandler.getMessage());
                    deleteTempTable(requestInput)
                            .onComplete(deleteHandler -> handleFailure(requestInput, failureHandler.getMessage(), objectPromise));
                });

        return objectPromise.future();

    }

    private Future<Void> checkIfCollectionPresent(JsonObject requestInput) {

        Promise<Void> promise = Promise.promise();
        pgPool.withConnection(
                sqlConnection -> sqlConnection.preparedQuery(COLLECTIONS_DETAILS_SELECT_QUERY)
                        .execute(Tuple.of(requestInput.getString("collectionsDetailsTableId")))
                        .onSuccess(successHandler -> {
                            if (successHandler.size() > 0) {
                                promise.complete();
                            } else {
                                LOGGER.error("Collection not found.");
                                promise.fail("Collection not found.");
                            }
                        }).onFailure(failureHandler -> {
                            LOGGER.error("Failed to check collection in db: {} " , failureHandler.getMessage());
                            promise.fail("Failed to check collection existence in db.");
                        }));

        return promise.future();

    }

    private Future<Void> checkSchema(JsonObject requestInput) {

        Promise<Void> promise = Promise.promise();

        vertx.executeBlocking(future -> {
                    try {
                        JsonObject geoJsonSchema = getGeoJsonSchemaWithOgrinfo(requestInput);
                        Set<String> geoJsonAttributes = extractAttributesFromGeoJsonSchema(geoJsonSchema);

                        // Add 'geom' and 'id' attributes to the geoJsonAttributes set
                        geoJsonAttributes.add("geom");
                        geoJsonAttributes.add("id");

                        getDbSchema(requestInput.getString("collectionsDetailsTableId"))
                                .onSuccess(dbSchema -> {
                                    if (geoJsonAttributes.equals(dbSchema)) {
                                        LOGGER.debug("Schema validation successful.");
                                        future.complete();
                                    } else {
                                        String errorMsg = "Schema validation failed. GeoJSON schema: " + geoJsonAttributes + ", DB schema: " + dbSchema;
                                        LOGGER.error(errorMsg);
                                        future.fail(errorMsg);
                                    }
                                })
                                .onFailure(future::fail);
                    } catch (Exception e) {
                        LOGGER.error("Schema validation failed: ", e);
                        future.fail(e);
                    }
                }, VERTX_EXECUTE_BLOCKING_IN_ORDER).onSuccess(handler -> promise.complete())
                .onFailure(promise::fail);

        return promise.future();
    }

    private JsonObject getGeoJsonSchemaWithOgrinfo(JsonObject input) throws IOException {

        CommandLine cmdLine = getOrgInfoCommandLine(input);
        DefaultExecutor executor = new DefaultExecutor();
        executor.setExitValue(0);
        ExecuteWatchdog watchdog = new ExecuteWatchdog(Duration.ofSeconds(10000).toMillis());
        executor.setWatchdog(watchdog);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));

        try {
            int exitValue = executor.execute(cmdLine);
            LOGGER.debug("ogrinfo executed with exit value: {} " , exitValue);
            String output = stdout.toString();
            // Removing the initial message if necessary
            String jsonResponse = output.replace("Had to open data source read-only.", "");
            return new JsonObject(Buffer.buffer(jsonResponse));
        } catch (IOException e) {
            LOGGER.error("ogrinfo execution failed: {}-{} " , stderr, e);
            throw e;
        }
    }

    private CommandLine getOrgInfoCommandLine(JsonObject input) {

        LOGGER.debug("Inside command line");

        String filename = "/" + input.getString("fileName");
        CommandLine ogrinfo = new CommandLine("ogrinfo");
        ogrinfo.addArgument("--config");
        ogrinfo.addArgument("CPL_VSIL_USE_TEMP_FILE_FOR_RANDOM_WRITE");
        ogrinfo.addArgument("NO");
        ogrinfo.addArgument("--config");
        ogrinfo.addArgument("AWS_S3_ENDPOINT");
        ogrinfo.addArgument(awsEndPoint);
        ogrinfo.addArgument("--config");
        ogrinfo.addArgument("AWS_ACCESS_KEY_ID");
        ogrinfo.addArgument(accessKey);
        ogrinfo.addArgument("--config");
        ogrinfo.addArgument("AWS_SECRET_ACCESS_KEY");
        ogrinfo.addArgument(secretKey);
        ogrinfo.addArgument("-json");
        ogrinfo.addArgument("-ro"); // argument to open the file in read-only mode
        ogrinfo.addArgument(String.format("/vsis3/%s%s", awsBucketUrl, filename));

        return ogrinfo;
    }

    private Set<String> extractAttributesFromGeoJsonSchema(JsonObject geoJsonSchema) {

        JsonArray layers = geoJsonSchema.getJsonArray("layers", new JsonArray());
        if (layers.isEmpty()) {
            return Set.of(); // Return empty set if no layers are found
        }
        JsonObject firstLayer = layers.getJsonObject(0);
        JsonArray fields = firstLayer.getJsonArray("fields", new JsonArray());

        return fields.stream()
                .map(field -> ((JsonObject) field).getString("name"))
                .collect(Collectors.toSet());
    }


    private Future<Set<String>> getDbSchema(String tableName) {

        Promise<Set<String>> promise = Promise.promise();
        pgPool.withConnection(
                sqlConnection -> sqlConnection.preparedQuery(DB_SCHEMA_CHECK_QUERY)
                        .execute(Tuple.of(tableName))
                        .onSuccess(successHandler -> {
                            Set<String> columns = StreamSupport.stream(successHandler.spliterator(), false)
                                    .map(row -> row.getString("column_name"))
                                    .collect(Collectors.toSet());
                            promise.complete(columns);
                        }).onFailure(promise::fail));

        return promise.future();

    }

    private Future<Void> appendDataToTempTable(JsonObject requestInput) {

        String fileName = "/" + requestInput.getString("fileName");

        Promise<Void> promise = Promise.promise();

        vertx.executeBlocking(future -> {
            CommandLine cmdLine = getOgr2ogrCommandLine(requestInput, fileName);
            LOGGER.debug("Inside Execution and the command line is: {} ",cmdLine);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(0);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(Duration.ofSeconds(6800).toMillis());
            executor.setWatchdog(watchdog);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));

            try {
                executor.execute(cmdLine);
                LOGGER.debug("Successfully Executed");
                String outLog = stdout.toString();
                String errLog = stderr.toString();
                LOGGER.debug("ogr2ogr output: {}" , outLog);
                LOGGER.debug("ogr2ogr error output: {}" , errLog);
                future.complete();
            } catch (IOException e) {
                String errLog = stderr.toString();
                LOGGER.error("ogr2ogr execution failed: {}-{}" , errLog, e);
                future.fail(e);
            }
        }, VERTX_EXECUTE_BLOCKING_IN_ORDER).onSuccess(handler -> {
            LOGGER.debug("Data appended successfully into temp table.");
            promise.complete();
        }).onFailure(promise::fail);

        return promise.future();

    }

    private CommandLine getOgr2ogrCommandLine(JsonObject requestInput, String fileName) {

        String jobId = requestInput.getString("jobId");
        String tempTableName = "temp_table_for_" + jobId;

        CommandLine cmdLine = new CommandLine("ogr2ogr");
        cmdLine.addArgument("-nln");
        cmdLine.addArgument(tempTableName);
        cmdLine.addArgument("-lco");
        cmdLine.addArgument("PRECISION=NO");
        cmdLine.addArgument("-lco");
        cmdLine.addArgument("LAUNDER=NO");
        cmdLine.addArgument("-lco");
        cmdLine.addArgument("GEOMETRY_NAME=geom");
        cmdLine.addArgument("-lco");
        cmdLine.addArgument("ENCODING=UTF-8");
        cmdLine.addArgument("-lco");
        cmdLine.addArgument("FID=id");

        cmdLine.addArgument("-t_srs");
        cmdLine.addArgument("EPSG:4326");
        cmdLine.addArgument("--config");
        cmdLine.addArgument("PG_USE_COPY");
        cmdLine.addArgument("YES");
        cmdLine.addArgument("-f");
        cmdLine.addArgument("PostgreSQL");
        cmdLine.addArgument(
                String.format("PG:host=%s dbname=%s user=%s port=%d password=%s schemas=public", databaseHost,
                        databaseName, databaseUser, databasePort, databasePassword), false);

        cmdLine.addArgument("-progress");
        cmdLine.addArgument("--debug");
        cmdLine.addArgument("ON");

        cmdLine.addArgument("--config");
        cmdLine.addArgument("AWS_S3_ENDPOINT");
        cmdLine.addArgument(awsEndPoint);
        cmdLine.addArgument("--config");
        cmdLine.addArgument("AWS_ACCESS_KEY_ID");
        cmdLine.addArgument(accessKey);
        cmdLine.addArgument("--config");
        cmdLine.addArgument("AWS_SECRET_ACCESS_KEY");
        cmdLine.addArgument(secretKey);

        cmdLine.addArgument(String.format("/vsis3/%s%s", awsBucketUrl, fileName));
        LOGGER.debug("cmdLine: {}" , cmdLine);
        for (String arg : cmdLine.getArguments()) {
            LOGGER.debug(arg);
        }
        return cmdLine;
    }

    private Future<Void> mergeTempTableToCollection(JsonObject requestInput) {

        Promise<Void> promise = Promise.promise();
        String jobId = requestInput.getString("jobId");
        String collectionId = requestInput.getString("collectionsDetailsTableId");
        String tempTableName = "temp_table_for_" + jobId;
        LOGGER.debug("temp table name is : {} " , tempTableName);

        getDbSchema(collectionId)
                .onSuccess(columns -> {
                    // Exclude 'id' column
                    columns.remove("id");
                    // Every column name should be put into double quotes to avoid the case sensitivity problem of Postgres
                    String columnNames = columns.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
                    String mergeQuery = String.format(
                            MERGE_TEMP_TABLE_QUERY,
                            collectionId,
                            columnNames,
                            columnNames,
                            tempTableName
                    );

                    pgPool.withConnection(sqlConnection ->
                            sqlConnection.query(mergeQuery)
                                    .execute()
                                    .onSuccess(successHandler -> {
                                        LOGGER.debug("Merged temp table into main table successfully.");
                                        promise.complete();
                                    })
                                    .onFailure(failureHandler -> {
                                        LOGGER.error("Failed to merge temp table into main table: {} " , failureHandler.getMessage());
                                        promise.fail(new ProcessException(500, "MERGE_FAILED", "Failed to merge temp table into main table."));
                                    })
                    );
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    private Future<Void> deleteTempTable(JsonObject requestInput) {

        Promise<Void> promise = Promise.promise();
        String jobId = requestInput.getString("jobId");
        String tempTableName = "temp_table_for_" + jobId;
        String deleteQuery = String.format(DELETE_TEMP_TABLE_QUERY, tempTableName);

        pgPool.withConnection(sqlConnection ->
                sqlConnection.query(deleteQuery)
                        .execute()
                        .onSuccess(successHandler -> {
                            LOGGER.debug("Temporary table deleted successfully.");
                            promise.complete();
                        })
                        .onFailure(failureHandler -> {
                            LOGGER.error("Failed to delete temporary table: {}" , failureHandler.getMessage());
                            promise.fail("Failed to delete temporary table.");
                        })
        );

        return promise.future();
    }

    private void handleFailure(JsonObject requestInput, String errorMessage, Promise<JsonObject> promise) {
        utilClass.updateJobTableStatus(requestInput, Status.FAILED, errorMessage)
                .onSuccess(successHandler -> promise.fail(errorMessage))
                .onFailure(failureHandler -> promise.fail(errorMessage));
    }

    private float calculateProgress(int step, int totalSteps) {
        return (float) step / totalSteps * 100;
    }
}
