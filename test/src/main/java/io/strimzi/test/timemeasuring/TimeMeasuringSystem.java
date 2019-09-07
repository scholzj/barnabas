/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.test.timemeasuring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Time measuring class which is able to collect running time of specific operation in the framework.
 * Collected data are saved into map and from there they can be printed or exported as csv file.
 *
 * Usage example: kafka deployment time, kafka deletion time, CO deployment time, namespace creation ...
 */
public class TimeMeasuringSystem {
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss:SSS");
    private static final Logger LOGGER = LogManager.getLogger(TimeMeasuringSystem.class);
    private static TimeMeasuringSystem instance;
    /**
     * Map for store collected data. System saves operation and test class and method names:
     * test class 1
     *   - method 1
     *      - operation 1
     *      - operation 2
     *   - method 2
     *      - operation 1
     * test class 2
     *   - method 2
     *      - operation 1
     * where operation run time data are stored in MeasureRecord and keys provide names of classes and methods.
     */
    private Map<String, Map<String, Map<String, MeasureRecord>>> measuringMap;
    private String testClass;
    private String testName;

    private TimeMeasuringSystem() {
        measuringMap = new LinkedHashMap<>();
    }

    private static synchronized TimeMeasuringSystem getInstance() {
        if (instance == null) {
            instance = new TimeMeasuringSystem();
        }
        return instance;
    }

    private String createOperationsID(Operation operation) {
        String id = operation.toString();
        if (!operation.equals(Operation.TEST_EXECUTION)
                && !operation.equals(Operation.CO_CREATION)
                && !operation.equals(Operation.CO_DELETION)) {
            id = String.format("%s-%s", id, UUID.randomUUID().toString().split("-")[0]);
        }
        return id;
    }

    private void addRecord(String operationID, MeasureRecord record) {
        if (measuringMap.get(testClass) == null) {
            LinkedHashMap<String, Map<String, MeasureRecord>> newData = new LinkedHashMap<>();
            LinkedHashMap<String, MeasureRecord> newRecord = new LinkedHashMap<>();
            newData.put(testName, newRecord);
            newRecord.put(operationID, record);
            measuringMap.put(testClass, newData);
        } else if (measuringMap.get(testClass).get(testName) == null) {
            LinkedHashMap<String, MeasureRecord> newRecord = new LinkedHashMap<>();
            newRecord.put(operationID, record);
            measuringMap.get(testClass).put(testName, newRecord);
        } else {
            measuringMap.get(testClass).get(testName).put(operationID, record);
        }
    }

    private String setStartTime(Operation operation) {
        String id = createOperationsID(operation);
        try {
            addRecord(id, new MeasureRecord(System.currentTimeMillis()));
            LOGGER.info("Start time of operation {} is correctly stored", id);
        } catch (Exception ex) {
            LOGGER.warn("Start time of operation {} is not set due to exception", id);
        }
        return id;
    }

    private void setEndTime(String id) {
        if (id.equals(Operation.TEST_EXECUTION.toString())) {
            id = createOperationsID(Operation.TEST_EXECUTION);
        }
        if (id.equals(Operation.CO_CREATION.toString())) {
            id = createOperationsID(Operation.CO_CREATION);
        }
        if (id.equals(Operation.CO_DELETION.toString())) {
            id = createOperationsID(Operation.CO_DELETION);
        }
        try {
            measuringMap.get(testClass).get(testName).get(id).setEndTime(System.currentTimeMillis());
            LOGGER.info("End time of operation {} is correctly stored", id);
        } catch (NullPointerException | ClassCastException ex) {
            LOGGER.warn("End time of operation {} is not set due to exception: {}", id, ex);
        }
    }

    private void setTestName(String testName) {
        this.testName = testName;
    }

    private void setTestClass(String testClass) {
        this.testClass = testClass;
    }

    private void printResults() {
        measuringMap.forEach((testClassID, testClassRecords) -> {
            LOGGER.info("================================================");
            LOGGER.info("================================================");
            LOGGER.info(testClassID);
            testClassRecords.forEach((testID, testRecord) -> {
                LOGGER.info("---------------------------------------------");
                LOGGER.info(testID);
                testRecord.forEach((operationID, record) -> {
                    LOGGER.info("Operation id: {} duration: {} started: {} ended: {}",
                            operationID,
                            record.getDurationReadable(),
                            record.getStartTimeHumanReadable(),
                            record.getEndTimeHumanReadable());
                });
            });
        });
    }

    private void saveResults(Map data, String name, String path) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(data);
            Date timestamp = new Date(System.currentTimeMillis());
            Path logPath = Paths.get(path, "timeMeasuring");
            Files.createDirectories(logPath);
            Files.write(Paths.get(logPath.toString(),
                    String.format("%s-%s.json", name, dateFormat.format(timestamp))), json.getBytes(Charset.forName("UTF-8")));
        } catch (Exception ex) {
            LOGGER.warn("Cannot save output of time measuring: " + ex.getMessage());
        }

    }

    private Map<String, Long> getSumDuration() {
        Map<String, Long> sumData = new LinkedHashMap<>();
        Arrays.stream(Operation.values()).forEach(value -> sumData.put(value.toString(), (long) 0));

        measuringMap.forEach((testClassID, testClassRecords) -> testClassRecords.forEach((testID, testRecord) -> {
            testRecord.forEach((operationID, record) -> {
                String key = operationID.split("-")[0];
                sumData.put(key, sumData.get(key) + record.duration);
            });
        }));

        return sumData;
    }

    private long getTestDuration(String testClass, String testName, String operationID) {
        return measuringMap.get(testClass).get(testName).get(operationID).duration;
    }

    private long getTestStartTime(String testClass, String testName, String operationID) {
        return measuringMap.get(testClass).get(testName).get(operationID).startTime;
    }

    public static void setTestName(String testClass, String testName) {
        TimeMeasuringSystem.getInstance().setTestName(testName);
        TimeMeasuringSystem.getInstance().setTestClass(testClass);
    }

    public static String startOperation(Operation operation) {
        return TimeMeasuringSystem.getInstance().setStartTime(operation);
    }

    public static void stopOperation(String operationId) {
        TimeMeasuringSystem.getInstance().setEndTime(operationId);
    }

    public static void stopOperation(Operation operationId) {
        TimeMeasuringSystem.stopOperation(operationId.toString());
    }

    public static void printAndSaveResults(String path) {
        TimeMeasuringSystem.getInstance().printResults();
        TimeMeasuringSystem.getInstance().saveResults(TimeMeasuringSystem.getInstance().measuringMap, "duration_report", path);
        TimeMeasuringSystem.getInstance().saveResults(TimeMeasuringSystem.getInstance().getSumDuration(), "duration_sum_report", path);
    }

    public static int getDurationInSecconds(String testClass, String testName, String operationID) {
        return (int) (TimeMeasuringSystem.getInstance().getTestDuration(testClass, testName, operationID) / 1000);
    }

    public static int getCurrentDuration(String testClass, String testName, String operationID) {
        long duration = System.currentTimeMillis() - TimeMeasuringSystem.getInstance().getTestStartTime(testClass, testName, operationID);
        return (int) (duration / 1000) + 1;
    }

    public static long getDuration(String testClass, String testName, String operationID) {
        return TimeMeasuringSystem.getInstance().getTestDuration(testClass, testName, operationID);
    }


    /**
     * Test time duration class for data about each operation.
     */
    private static class MeasureRecord {
        private long startTime;
        private long endTime;
        private long duration;
        private String durationReadable;

        MeasureRecord(long startTime, long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = endTime - startTime;
            this.durationReadable = getDurationHumanReadable();
        }

        MeasureRecord(long startTime) {
            this.startTime = startTime;
        }

        long getStartTime() {
            return startTime;
        }

        long getEndTime() {
            return endTime;
        }

        void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        long getDuration() {
            return this.duration;
        }

        String getDurationReadable() {
            return this.durationReadable;
        }

        void setEndTime(long endTime) {
            this.endTime = endTime;
            this.duration = endTime - startTime;
            this.durationReadable = getDurationHumanReadable();
        }

        String getDurationHumanReadable() {
            long millis = getDuration();
            long hours = TimeUnit.MILLISECONDS.toHours(millis);
            long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - (TimeUnit.MILLISECONDS.toHours(millis) * 60);
            long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - (TimeUnit.MILLISECONDS.toMinutes(millis) * 60);
            long milliseconds = TimeUnit.MILLISECONDS.toMillis(millis) - (TimeUnit.MILLISECONDS.toSeconds(millis) * 1000);

            return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds);
        }

        String getStartTimeHumanReadable() {
            return transformMillisToDateTime(startTime);
        }

        String getEndTimeHumanReadable() {
            return transformMillisToDateTime(endTime);
        }

        private String transformMillisToDateTime(long millis) {
            Date date = new Date(millis);
            SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss,SSS");
            return format.format(date);
        }
    }
}