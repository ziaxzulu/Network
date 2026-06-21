/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetemCommandPlanner {
    private NetemCommandPlanner() {
    }

    public static List<String> apply(String interfaceName, String latency, String jitter, String loss) {
        List<String> commands = new ArrayList<>();
        commands.add("tc qdisc replace dev " + interfaceName + " root netem"
                + append(" delay", latency)
                + append("", jitter)
                + append(" loss", loss));
        return Collections.unmodifiableList(commands);
    }

    public static List<String> clear(String interfaceName) {
        List<String> commands = new ArrayList<>();
        commands.add("tc qdisc del dev " + interfaceName + " root");
        return Collections.unmodifiableList(commands);
    }

    public static List<String> status(String interfaceName) {
        List<String> commands = new ArrayList<>();
        commands.add("tc qdisc show dev " + interfaceName);
        return Collections.unmodifiableList(commands);
    }

    private static String append(String prefix, String value) {
        if (value == null || value.trim().isEmpty() || "0".equals(value.trim()) || "0%".equals(value.trim())) {
            return "";
        }
        return prefix + " " + value.trim();
    }
}
