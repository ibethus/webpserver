FROM eclipse-temurin:21-jre

WORKDIR /work

COPY --chown=1001 target/quarkus-app/lib/ /work/lib/
COPY --chown=1001 target/quarkus-app/*.jar /work/
COPY --chown=1001 target/quarkus-app/app/ /work/app/
COPY --chown=1001 target/quarkus-app/quarkus/ /work/quarkus/

RUN mkdir -p /images && chown 1001:1001 /images

EXPOSE 8080

USER 1001

VOLUME ["/images"]

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "/work/quarkus-run.jar"]
