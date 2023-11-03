package com.mssde.apps;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

public class Drone {
    private final String brokerUrl;
    private final String clientId;
    private final String droneId;
    private String status = "idle";
    private String request_id = null;
    private String latlong = "0,0";
    private int freq = 60000; // 60 seconds default

    public Drone(String brokerUrl, String clientId, String droneId, int statusFreq) {
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.droneId = droneId;
        this.freq = statusFreq;
    }

    public void start() {
        try {
            // TODO: Add last will support to mark the drone as offline
            MqttClient mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            mqttClient.connect(connOpts);

            mqttClient.subscribe("commands/" + droneId, new IMqttMessageListener() {
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    JSONObject json = new JSONObject(new String(message.getPayload()));
                    if (json.getString("drone_id").equals(droneId) && status.equals("idle")) {
                        request_id = json.getString("request_id");
                        status = "on route";
                    }
                }
            });

            while (true) {
                JSONObject statusJson = new JSONObject();
                statusJson.put("drone_id", droneId);
                statusJson.put("status", status);
                statusJson.put("latlong", latlong);
                if (request_id != null) {
                    statusJson.put("request_id", request_id);
                }

                System.out
                        .println("Sending status for " + droneId + " on position " + latlong + " and status " + status);
                mqttClient.publish("status/" + droneId, new MqttMessage(statusJson.toString().getBytes()));

                Thread.sleep(this.freq); // Sending status every 1 minute. Adjust as needed.
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Please provide broker URL, client ID, drone ID and status frequency as arguments.");
            return;
        }

        String brokerUrl = args[0];
        String clientId = args[1];
        String droneId = args[2];
        int statusFreq = Integer.parseInt(args[3]);

        Drone drone = new Drone(brokerUrl, clientId, droneId, statusFreq);
        drone.start();
    }
}
