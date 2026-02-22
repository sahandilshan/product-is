# ---------------------------------------------------------------------------
# Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
#
# WSO2 LLC. licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
# ---------------------------------------------------------------------------

# Dockerfile for WSO2 Identity Server integration tests.
# Builds a Docker image from the locally built wso2is ZIP distribution.
# Used by Testcontainers in Docker-based integration test mode (-DdockerTests).

FROM eclipse-temurin:21-jre-jammy

ARG WSO2IS_ZIP

RUN groupadd -g 802 wso2 && useradd -u 802 -g wso2 -m wso2carbon
RUN apt-get update && apt-get install -y unzip curl && rm -rf /var/lib/apt/lists/*

COPY ${WSO2IS_ZIP} /tmp/wso2is.zip
RUN unzip -q /tmp/wso2is.zip -d /home/wso2carbon/ && \
    mv /home/wso2carbon/wso2is-* /home/wso2carbon/wso2is && \
    rm /tmp/wso2is.zip && \
    chown -R wso2carbon:wso2 /home/wso2carbon/wso2is

ENV CARBON_HOME=/home/wso2carbon/wso2is
USER wso2carbon
WORKDIR /home/wso2carbon/wso2is
EXPOSE 9443 9763 5005

# Note: Docker HEALTHCHECK is not used by Testcontainers (which uses LogMessageWaitStrategy
# to wait for "WSO2 Carbon started" in container logs instead). This HEALTHCHECK is kept
# for standalone Docker usage outside of Testcontainers.
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=40 \
    CMD curl -k -f https://localhost:9443/carbon/admin/login.jsp || exit 1

ENTRYPOINT ["./bin/wso2server.sh"]
