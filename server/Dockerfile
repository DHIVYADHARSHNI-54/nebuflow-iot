FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src ./src
COPY web ./web
RUN mkdir out && javac -d out $(find src -name "*.java")
CMD ["java", "-cp", "out", "nebuflow.Main", "--demo"]
