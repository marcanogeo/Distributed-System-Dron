# Maven Java + Docker + sqlite + mqtt Project

Publisher/Subscriber structure using the MQTT protocol to publish status events, such as the drone's battery level and location, the latter expressed in latitude and longitude coordinates. This allows the flight director, who is also a subscriber/publisher, to manage and monitor the flights that the aerial device must perform.

In this case, it was taken into account that the drone, as a device, also functions as a publisher and subscriber, since it must send its status every so often in order to know its route and be able to track it in case of loss of connection with the device. In addition to receiving commands and requests from the flight director.

To launch the project in Docker, you must do the following:

**docker build -t mssde/drones:latest**

Then,

**docker compose up -d**

And you will have everything running.

