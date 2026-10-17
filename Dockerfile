FROM node:22-alpine AS web-build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY web/ web/
COPY server/Pages/ server/Pages/
COPY engine/resources/ engine/resources/
COPY tools/PackEngineResources.mjs tools/engine-resources.json tools/
RUN npm run build:web
RUN node tools/PackEngineResources.mjs engine/resources resources.pack

FROM gradle:8.7-jdk17 AS engine-build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY engine/ engine/
COPY third_party/ third_party/
RUN --mount=type=cache,id=ftl-gradle-home,target=/home/gradle/.gradle \
    gradle :engine:generateJavaScript --no-daemon --console=plain "-Dorg.gradle.jvmargs=-Xmx3g" "-Pkotlin.daemon.jvmargs=-Xmx3g" && cp -r engine/build/generated/teavm/js /engine-output

# Engine JavaScript alone, for exporting a local build without the full image:
# docker build --target engine-js -o type=local,dest=<dir> .
FROM scratch AS engine-js
COPY --from=engine-build /engine-output/ /

FROM eclipse-temurin:17-jdk AS asset-preparer
WORKDIR /tools
COPY tools/PrepareAssets.java /tools/PrepareAssets.java
COPY engine/resources/ /tools/resources/
ENTRYPOINT ["java", "/tools/PrepareAssets.java", "/game/ftl.dat", "/assets", "/tools/resources"]

FROM mcr.microsoft.com/dotnet/sdk:10.0 AS server-build
WORKDIR /src
COPY server/Ftl.Server.csproj server/
RUN dotnet restore server/Ftl.Server.csproj
COPY server/ server/
RUN dotnet publish server/Ftl.Server.csproj -c Release -o /app --no-restore
COPY tests/server-security/ tests/server-security/
RUN dotnet publish tests/server-security/Ftl.SecurityTests.csproj -c Release -o /security-tests
COPY web/ /app/wwwroot/web/
COPY --from=web-build /workspace/web/style.css /app/wwwroot/web/style.css
COPY --from=engine-build /engine-output/ /app/wwwroot/engine/
COPY --from=web-build /workspace/resources.pack /app/resources.pack

FROM node:22-bookworm-slim AS test-node

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS app
WORKDIR /app
RUN mkdir -p /data /assets && chown -R 10001:10001 /data /assets && chmod 700 /data
COPY --from=test-node /usr/local/bin/node /usr/local/bin/node
COPY --from=test-node /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
COPY package.json package-lock.json /app/
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright
RUN npm ci --ignore-scripts --omit=optional && npx playwright install --with-deps chromium firefox chrome msedge && apt-get update && apt-get install -y --no-install-recommends pulseaudio && chmod -R a+rX /opt/playwright && rm -rf /var/lib/apt/lists/* /root/.npm
COPY --from=server-build --chown=10001:10001 /app/ ./
COPY --from=server-build --chown=10001:10001 /security-tests/ ./security-tests/
COPY third_party/notices/ /app/notices/
COPY tests/ /app/tests/
COPY web/ /app/web/
COPY tools/RunLauncherTests.mjs /app/tools/RunLauncherTests.mjs
USER 10001:10001
ENV ASPNETCORE_HTTP_PORTS=8080 FTL_DATA_DIR=/data FTL_ASSETS_DIR=/assets
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD ["dotnet", "Ftl.Server.dll", "healthcheck"]
ENTRYPOINT ["dotnet", "Ftl.Server.dll"]
