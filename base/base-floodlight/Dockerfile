# Copyright 2017 Telstra Open Source
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

FROM kilda/base-ubuntu

# Add Tools
ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update && \
  apt-get install -y --no-install-recommends git ant maven rsync && \
  rm -rf /var/lib/apt/lists/*

# Build Loxigen
RUN git clone --recursive -b STABLE --single-branch https://github.com/kilda/loxigen.git /app/loxigen
WORKDIR /app/loxigen
RUN make java
WORKDIR /app/loxigen/loxi_output/openflowj
ENV MAVEN_OPTS="-Xmx4G"
RUN mvn install -DskipTests -Dmaven.javadoc.skip=true

# Build Floodlight
RUN git clone --recursive -b STABLE --single-branch  https://github.com/kilda/floodlight.git /app/floodlight
WORKDIR /app/floodlight
RUN mvn install -DskipTests
RUN mkdir /var/lib/floodlight
RUN chmod 777 /var/lib/floodlight
