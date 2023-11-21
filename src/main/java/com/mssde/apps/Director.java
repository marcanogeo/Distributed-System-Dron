package com.mssde.apps;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.Math.*;

public class Director {

    private String brokerUrl;
    private String clientId;
    private MqttClient mqttClient;
    private Connection dbConnection;

    public Director(String brokerUrl, String clientId) throws MqttException {
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        this.mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable throwable) {
                System.out.println("Connection lost!");
            }

            @Override
            public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
                processIncomingMessage(s, new String(mqttMessage.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                // TODO: finish implementing
            }
        });

        try {
            this.dbConnection = DriverManager.getConnection("jdbc:sqlite:dronesystem.db");
            createTablesIfNotExist();
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void createTablesIfNotExist() throws SQLException {
        Statement stmt = dbConnection.createStatement();
        String dronesTable = "CREATE TABLE IF NOT EXISTS drones ("
                + "drone_id STRING PRIMARY KEY,"
                + "curr_latlong STRING,"
                + "curr_battery STRING DEFAULT '0',"
                + "curr_status STRING,"
                + "curr_request_id STRING);";
        stmt.execute(dronesTable);

        String requestsTable = "CREATE TABLE IF NOT EXISTS requests ("
                + "request_id STRING PRIMARY KEY,"
                + "origin_latlong STRING NOT NULL,"
                + "dest_latlong STRING NOT NULL,"
                + "weight INT NOT NULL,"
                + "drone_id STRING,"
                + "curr_status STRING NOT NULL DEFAULT 'pending',"
                + "curr_latlong STRING);";
        stmt.execute(requestsTable);
        stmt.close();
    }

    public void run() throws MqttException {
        mqttClient.connect();
        List<String> droneIds = getDroneIdsFromDB();
        for (String droneId : droneIds) {
            mqttClient.subscribe("status/" + droneId);
        }
        while (true) {
            // TODO: refactor for requests in "cancelling" status, to send cancellation to
            // drone
            processPendingRequests();
            try {
                Thread.sleep(10000); // 10 seconds
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void processIncomingMessage(String topic, String message) {
        // This method handles messages coming from the drones through the
        // /status/<drone_id> queues

        // NOTE: we expect last will from drones to mark them as offline

        // Parse the incoming messages
        JSONObject jsonMessage = new JSONObject(message);

        // Extract the drone_id from the topic
        String droneId = topic.replace("status/", "");

        try (

                PreparedStatement selectDroneStmt = dbConnection
                        .prepareStatement("SELECT * FROM drones WHERE drone_id = ?");
                PreparedStatement updateDroneStmt = dbConnection.prepareStatement(
                        "UPDATE drones SET curr_latlong = ?, curr_battery = ?, curr_status = ? WHERE drone_id = ?");
                PreparedStatement selectRequestStmt = dbConnection.prepareStatement(
                        "SELECT * FROM requests WHERE drone_id IS NULL AND curr_status = 'pending' ORDER BY ROWID ASC LIMIT 1");
                PreparedStatement updateRequestStmt = dbConnection.prepareStatement(
                        "UPDATE requests SET drone_id = ?, curr_status = 'assigned' WHERE request_id = ?")) {

            // Update the drones table with the information from the incoming message
            selectDroneStmt.setString(1, droneId);

            try (ResultSet rs = selectDroneStmt.executeQuery()) {
                if (rs.next()) {
                    updateDroneStmt.setString(1, jsonMessage.getString("curr_latlong"));
                    updateDroneStmt.setString(2, jsonMessage.getString("curr_battery"));
                    updateDroneStmt.setString(3, jsonMessage.getString("status"));
                    updateDroneStmt.setString(4, droneId);
                    updateDroneStmt.executeUpdate();
                } else {
                    // Handle the case where the drone_id from the incoming message does not exist
                    // in the drones table
                    System.out.println("Warning: Received status for unknown drone ID: " + droneId);
                    return;
                }
            }

            // If a drone reports its status as 'idle', check if there's a pending request
            // that can be assigned to it
            if ("idle".equalsIgnoreCase(jsonMessage.getString("status"))) {
                try (ResultSet rs = selectRequestStmt.executeQuery()) {
                    if (rs.next()) {
                        String requestId = rs.getString("request_id");
                        String originLatLong = rs.getString("origin_latlong");
                        String destLatLong = rs.getString("dest_latlong");
                        int weight = rs.getInt("weight");

                        // Update the request to assign it to the drone
                        updateRequestStmt.setString(1, droneId);
                        updateRequestStmt.setString(2, requestId);
                        updateRequestStmt.executeUpdate();

                        // Send a command to the drone to process the request
                        JSONObject commandMessage = new JSONObject();
                        commandMessage.put("request_id", requestId);
                        commandMessage.put("dest_latlong", originLatLong);
                        commandMessage.put("weight", weight);
                        mqttClient.publish("command/" + droneId, commandMessage.toString().getBytes(), 0, false);
                    }
                }
            }
        } catch (SQLException | MqttException e) {
            e.printStackTrace();
        }
    }

    /**
     * Calculate distance between two points in latitude and longitude.
     * Haversine formula
     */
    public double calcDistance(double origLat, double origLong, double destLat, double destLong) {
        final int R = 6371; // Radius of the earth in km
        double dLat = toRadians(destLat - origLat);
        double dLon = toRadians(destLong - origLong);
        double a = sin(dLat / 2) * sin(dLat / 2) +
                        cos(toRadians(origLat)) * cos(toRadians(destLat)) *
                        sin(dLon / 2) * sin(dLon / 2);
        double c = 2 * atan2(sqrt(a), sqrt(1 - a));
        double distance = R * c; // Convert to meters
        return distance * 1000;
    }

    private void processPendingRequests() {
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM requests WHERE curr_status = 'pending'");
            while (rs.next()) {
                List<Map<String, String>> availableDrones = getAvailableDrones(dbConnection);
                if (availableDrones.isEmpty()) {
                    System.out.println("No available drones in the system to process requests");
                    return;
                }

                String droneId = getClosestDrone(availableDrones, rs.getString("origin_latlong"));
                String requestId = rs.getString("request_id");
                String originLatLong = rs.getString("origin_latlong");
                int weight = rs.getInt("weight");

                String message = "{"
                        + "\"request_id\":\"" + requestId + "\","
                        + "\"dest_latlong\":\"" + originLatLong + "\","
                        + "\"weight\":" + weight
                        + "}";

                System.out.println("Assigning drone " + droneId + " to request " + requestId);
                mqttClient.publish("command/" + droneId, new MqttMessage(message.getBytes()));
                PreparedStatement updateStmt = dbConnection.prepareStatement(
                        "UPDATE requests SET drone_id = ?, curr_status = 'assigned', weight = ? WHERE request_id = ?");
                updateStmt.setString(1, droneId);
                updateStmt.setString(2, rs.getString("weight"));
                updateStmt.setString(3, requestId);
                updateStmt.executeUpdate();
                updateStmt.close();
                System.out.println("Updated request status to \"assigned\" for request " + requestId);
            }
            stmt.close();
        } catch (SQLException | MqttException e) {
            e.printStackTrace();
        }
    }

    private List<Map<String, String>> getAvailableDrones(Connection connection) {
        List<Map<String, String>> availableDrones = new ArrayList<>();
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT drone_id, curr_latlong FROM drones WHERE curr_status = 'idle'");
            while (rs.next()) {
                availableDrones.add(Map.of(rs.getString("drone_id"), rs.getString("curr_latlong")));
            }
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return availableDrones;
    }

    private String getClosestDrone(List<Map<String, String>> availableDrones, String destLatLong) {
        // Return the closest drone to destination
        // TODO: It should also consider battery levels to ensure it will reach!
        double minDistance = Double.MAX_VALUE;
        // Set the first drone as chosen one
        String droneId = availableDrones.get(0).get(0);
        double destLat = Double.parseDouble(destLatLong.split(",")[0]);
        double destLong = Double.parseDouble(destLatLong.split(",")[1]);

        for (Map<String, String> drone : availableDrones) {
            String dest = drone.get(1);
            Double currLat = Double.parseDouble(dest.split(",")[0]);
            Double currLong = Double.parseDouble(dest.split(",")[1]);
            Double distance = calcDistance(currLat, currLong, destLat, destLong);
            if (distance < minDistance) {
                minDistance = distance;
                droneId = drone.get(0);
            }
        }
        return droneId;
    }

    private List<String> getDroneIdsFromDB() {
        List<String> droneIds = new ArrayList<>();
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT drone_id FROM drones");
            while (rs.next()) {
                droneIds.add(rs.getString("drone_id"));
            }
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return droneIds;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: Director <brokerUrl> <clientId>");
            System.exit(1);
        }

        String brokerUrl = args[0];
        String clientId = args[1];

        try {
            System.out.println("Starting director backend " + clientId);
            Director director = new Director(brokerUrl, clientId);
            director.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
