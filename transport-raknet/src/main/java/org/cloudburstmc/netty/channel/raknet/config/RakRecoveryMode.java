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

package org.cloudburstmc.netty.channel.raknet.config;

/**
 * Selects the sender-side loss recovery policy for a RakNet session.
 *
 * <p>This setting does not change RakNet's wire format. {@link #LEGACY} remains the default so existing
 * applications retain their current recovery behaviour unless they explicitly opt into {@link #BOUNDED} or
 * {@link #MODEL_BASED}.</p>
 */
public enum RakRecoveryMode {
    /** The historical eager NACK and stale-datagram recovery policy. */
    LEGACY,

    /** Bounded NACK recovery and a single exponentially backed-off PTO probe. */
    BOUNDED,

    /**
     * Experimental bounded recovery with delivery-rate and minimum-RTT based congestion control and pacing.
     * This mode uses the same wire-compatible recovery machinery as {@link #BOUNDED}, but deliberately leaves
     * the existing bounded Reno controller available for direct comparison.
     */
    MODEL_BASED;

    /** Returns whether this mode uses bounded NACK and PTO recovery. */
    public boolean usesBoundedRecovery() {
        return this != LEGACY;
    }

    /** Returns whether this mode uses the experimental model-based congestion controller. */
    public boolean usesModelBasedCongestionControl() {
        return this == MODEL_BASED;
    }
}
