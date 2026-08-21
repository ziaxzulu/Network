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

public enum DisappearanceMode {
    CLOSE("close"),
    STOP_READING("stop-reading"),
    BLACKHOLE("blackhole");

    private final String cliName;

    DisappearanceMode(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return this.cliName;
    }

    public static DisappearanceMode parse(String value) {
        for (DisappearanceMode mode : values()) {
            if (mode.cliName.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value.replace('-', '_'))) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown disappearance mode: " + value);
    }
}
