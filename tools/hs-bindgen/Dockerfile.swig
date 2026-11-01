# SWIG 4.0.1 (Ubuntu focal's package, the version Hyperspace builds with, 1b05a07 and 1.23.2 alike) plus Lua 5.3 for libzhlgen.
FROM ubuntu:focal
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends swig lua5.3 lua-lpeg lua-filesystem && rm -rf /var/lib/apt/lists/*
