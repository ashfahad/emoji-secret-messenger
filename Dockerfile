FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY EmojiStegoWebServer.java EmojiStegoApp.java EmojiStegoCodecTest.java ./
RUN javac -encoding UTF-8 EmojiStegoWebServer.java EmojiStegoApp.java EmojiStegoCodecTest.java \
    && java -Dfile.encoding=UTF-8 EmojiStegoCodecTest

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/*.class ./

ENV PORT=8080
EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "EmojiStegoWebServer"]
