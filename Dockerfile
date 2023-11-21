FROM maven:3.9.5-eclipse-temurin-21
LABEL author="Georgelys Marcano"
LABEL email="georgelys.marcano@alumnos.upm.es"

RUN apt -y update && \
    apt install -y supervisor && \
    mkdir -p /App/drones && \
    apt clean -y && \
    rm -rfv /var/lib/apt/lists/*

COPY . /App/drones
WORKDIR /App/drones

RUN cp /App/drones/supervisord.conf /etc/supervisor/conf.d/supervisord.conf && \
    mvn verify clean --fail-never && \
    mvn install -P director && \
    mvn install -P api && \
    mvn install -P drone

EXPOSE 8080

CMD /usr/bin/supervisord 

