FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY backend/lib/mysql-connector-j.jar /app/backend/lib/mysql-connector-j.jar
COPY backend/src/App.java /app/backend/src/App.java
COPY frontend /app/frontend

RUN mkdir -p /app/backend/out \
    && javac -cp /app/backend/lib/mysql-connector-j.jar -d /app/backend/out /app/backend/src/App.java

WORKDIR /app/backend

EXPOSE 3000

CMD ["java", "-cp", "out:lib/mysql-connector-j.jar", "App"]
