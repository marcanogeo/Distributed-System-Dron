# Proyecto en Maven Java + Docker + sqlite + mqtt
estructura Publisher/Subscriber usando el protocolo MQTT para realizar las publicaciones de los eventos de estado, por ejemplo, nivel de batería del dron y la ubicación, esta última expresada en coordenadas de latitud y longitud. De forma que el director de vuelo, quien también es un subscritor/publicador, pueda estar gestionando y monitorizando los vuelos que debe realizar el dispositivo aéreo. 

En este caso, se tomó en cuenta que el dron, como dispositivo, también funciona como un publicador y subscritor, ya que debe estar enviando cada cierto tiempo su estado para así conocer su ruta y tener la posibilidad de rastreo en caso de pérdida de conexión con el dispositivo. Además de recibir comandos y solicitudes por parte del director de vuelo. 

Para levantar el proyecto en docker debe hacer esto:

** docker build -t mssde/drones:latest .

Luego,  

** docker compose up -d

Y tendrá todo corriendo
